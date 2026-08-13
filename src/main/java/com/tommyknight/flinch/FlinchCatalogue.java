package com.tommyknight.flinch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.AnimationID;

/**
 * The selectable animations, loaded once from animations.tsv.
 *
 * The TSV is a dump of every sequence id in the game with its gameval constant name. Reading
 * ids from data rather than naming ~14,000 constants in code keeps the plugin decoupled from
 * whichever client release it is compiled against — a renamed constant can no longer break the
 * build, and a new one does not need a code change.
 */
@Singleton
@Slf4j
class FlinchCatalogue
{
	private static final String RESOURCE = "animations.tsv";

	/**
	 * The player emotes, pinned to the top of the list with readable names.
	 *
	 * Everything else — dodges, deaths, the rest of the human rig, then the NPC animations —
	 * still appears below, so nothing is lost by keeping this list to the familiar ones.
	 */
	private static final Map<Integer, String> FAVOURITES = new LinkedHashMap<>();

	static
	{
		FAVOURITES.put(AnimationID.EMOTE_AIR_GUITAR, "Air guitar");
		FAVOURITES.put(AnimationID.EMOTE_ANGRY, "Angry");
		FAVOURITES.put(AnimationID.EMOTE_BECKON, "Beckon");
		FAVOURITES.put(AnimationID.EMOTE_BLOW_KISS, "Blow kiss");
		FAVOURITES.put(AnimationID.EMOTE_BOW, "Bow");
		FAVOURITES.put(AnimationID.EMOTE_CHEER, "Cheer");
		FAVOURITES.put(AnimationID.EMOTE_CLAP, "Clap");
		FAVOURITES.put(AnimationID.EMOTE_CLIMBING_ROPE, "Climb rope");
		FAVOURITES.put(AnimationID.EMOTE_CRY, "Cry");
		FAVOURITES.put(AnimationID.EMOTE_DANCE, "Dance");
		FAVOURITES.put(AnimationID.EMOTE_DANCE_HEADBANG, "Dance (headbang)");
		FAVOURITES.put(AnimationID.EMOTE_DANCE_SCOTTISH, "Dance (Scottish)");
		FAVOURITES.put(AnimationID.EMOTE_DANCE_SPIN, "Dance (spin)");
		FAVOURITES.put(AnimationID.EMOTE_EXPLORE, "Explore");
		FAVOURITES.put(AnimationID.EMOTE_FLEX, "Flex");
		FAVOURITES.put(AnimationID.EMOTE_FREMMENIK_SALUTE, "Fremennik salute");
		FAVOURITES.put(AnimationID.EMOTE_GLASS_BOX, "Glass box");
		FAVOURITES.put(AnimationID.EMOTE_GLASS_WALL, "Glass wall");
		FAVOURITES.put(AnimationID.EMOTE_JUMP_WITH_JOY, "Jump for joy");
		FAVOURITES.put(AnimationID.EMOTE_LAUGH, "Laugh");
		FAVOURITES.put(AnimationID.EMOTE_LIGHTBULB, "Idea");
		FAVOURITES.put(AnimationID.EMOTE_MIME_LEAN, "Lean");
		FAVOURITES.put(AnimationID.EMOTE_NO, "No");
		FAVOURITES.put(AnimationID.EMOTE_PANIC, "Panic");
		FAVOURITES.put(AnimationID.EMOTE_PANIC_FLAP, "Panic (flap)");
		FAVOURITES.put(AnimationID.EMOTE_PARTY, "Party");
		FAVOURITES.put(AnimationID.EMOTE_PUSHUPS_5, "Push-ups");
		FAVOURITES.put(AnimationID.EMOTE_RUN_ON_SPOT, "Run on the spot");
		FAVOURITES.put(AnimationID.EMOTE_SHRUG, "Shrug");
		FAVOURITES.put(AnimationID.EMOTE_SITUPS_5, "Sit-ups");
		FAVOURITES.put(AnimationID.EMOTE_SLAP_HEAD, "Slap head");
		FAVOURITES.put(AnimationID.EMOTE_STAMPFEET, "Stamp");
		FAVOURITES.put(AnimationID.EMOTE_STARJUMP_5, "Star jump");
		FAVOURITES.put(AnimationID.EMOTE_THINK, "Think");
		FAVOURITES.put(AnimationID.EMOTE_TRICK, "Trick");
		FAVOURITES.put(AnimationID.EMOTE_VARLAMORE_SALUTE, "Varlamore salute");
		FAVOURITES.put(AnimationID.EMOTE_WAVE, "Wave");
		FAVOURITES.put(AnimationID.EMOTE_YA_BOO_SUCKS, "Raspberry");
		FAVOURITES.put(AnimationID.EMOTE_YAWN, "Yawn");
		FAVOURITES.put(AnimationID.EMOTE_YES, "Yes");
	}

	private final Map<Integer, FlinchAnimation> byId = new HashMap<>();
	private List<FlinchAnimation> allAnimations = Collections.emptyList();
	private boolean loaded;

	/**
	 * Every animation, ordered favourites, then the rest of the human rig, then NPC and scenery.
	 * Scrubbing from the top therefore only reaches the ones that look wrong on a player if you
	 * keep going.
	 */
	synchronized List<FlinchAnimation> getAllAnimations()
	{
		load();
		return allAnimations;
	}

	/**
	 * Case-insensitive substring match over the display name, the gameval name and the id.
	 * Capped, because a one-character query matches thousands of entries and only the first
	 * screenful is ever read.
	 */
	synchronized List<FlinchAnimation> search(String query, int limit)
	{
		load();

		final String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty())
		{
			return allAnimations;
		}

		final List<FlinchAnimation> matches = new ArrayList<>();
		for (FlinchAnimation animation : allAnimations)
		{
			if (animation.matches(needle))
			{
				matches.add(animation);
				if (matches.size() >= limit)
				{
					break;
				}
			}
		}
		return matches;
	}

	synchronized FlinchAnimation byId(int id)
	{
		if (id == FlinchAnimation.NO_ANIMATION)
		{
			return FlinchAnimation.NONE;
		}
		load();
		final FlinchAnimation animation = byId.get(id);
		return animation == null ? FlinchAnimation.NONE : animation;
	}

	private void load()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;

		final Map<Integer, String> names = new LinkedHashMap<>();
		try (InputStream in = FlinchCatalogue.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("Missing animation catalogue resource {}", RESOURCE);
				buildLists(Collections.emptyList(), Collections.emptyList());
				return;
			}

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					final int tab = line.indexOf('\t');
					if (tab <= 0)
					{
						continue;
					}

					try
					{
						names.put(Integer.parseInt(line.substring(0, tab).trim()), line.substring(tab + 1).trim());
					}
					catch (NumberFormatException e)
					{
						// Malformed row; skip it rather than lose the whole catalogue.
						log.debug("Bad animation catalogue row: {}", line);
					}
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Could not read animation catalogue", e);
		}

		final List<FlinchAnimation> player = new ArrayList<>();
		final List<FlinchAnimation> other = new ArrayList<>();

		for (Map.Entry<Integer, String> entry : names.entrySet())
		{
			final int id = entry.getKey();
			final String gamevalName = entry.getValue();

			if (FAVOURITES.containsKey(id))
			{
				continue;
			}

			if (isPlayerRig(gamevalName))
			{
				player.add(new FlinchAnimation(id, pretty(gamevalName, true), gamevalName, FlinchAnimation.Group.PLAYER));
			}
			else
			{
				other.add(new FlinchAnimation(id, pretty(gamevalName, false), gamevalName, FlinchAnimation.Group.OTHER));
			}
		}

		final Comparator<FlinchAnimation> byName = Comparator
			.comparing(FlinchAnimation::getDisplayName, String.CASE_INSENSITIVE_ORDER)
			.thenComparingInt(FlinchAnimation::getId);
		player.sort(byName);
		other.sort(byName);

		buildLists(player, other);
	}

	private void buildLists(List<FlinchAnimation> player, List<FlinchAnimation> other)
	{
		final List<FlinchAnimation> favourites = new ArrayList<>();
		for (Map.Entry<Integer, String> entry : FAVOURITES.entrySet())
		{
			favourites.add(new FlinchAnimation(entry.getKey(), entry.getValue(), entry.getValue(),
				FlinchAnimation.Group.FAVOURITE));
		}

		final List<FlinchAnimation> everything =
			new ArrayList<>(1 + favourites.size() + player.size() + other.size());
		everything.add(FlinchAnimation.NONE);
		everything.addAll(favourites);
		everything.addAll(player);
		everything.addAll(other);
		allAnimations = Collections.unmodifiableList(everything);

		byId.clear();
		for (FlinchAnimation animation : allAnimations)
		{
			byId.putIfAbsent(animation.getId(), animation);
		}
	}

	/**
	 * Animations belong to a skeleton. HUMAN_ and EMOTE_ are the player rig; anything else was
	 * authored for an NPC or a piece of scenery and generally renders as a mess on a player.
	 */
	private static boolean isPlayerRig(String gamevalName)
	{
		return gamevalName.startsWith("HUMAN_") || gamevalName.startsWith("EMOTE_");
	}

	private static String pretty(String gamevalName, boolean stripRigPrefix)
	{
		String name = gamevalName;
		if (stripRigPrefix)
		{
			if (name.startsWith("HUMAN_"))
			{
				name = name.substring("HUMAN_".length());
			}
			else if (name.startsWith("EMOTE_"))
			{
				name = name.substring("EMOTE_".length());
			}
		}

		name = name.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
		if (name.isEmpty())
		{
			return gamevalName;
		}
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}
}
