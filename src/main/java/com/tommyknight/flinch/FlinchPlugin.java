package com.tommyknight.flinch;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
	name = "Flinch",
	description = "Play an emote or dodge animation on yourself when you take a 0, take damage or get poisoned",
	tags = {"animation", "emote", "dodge", "hitsplat", "cosmetic", "flinch"}
)
@Slf4j
public class FlinchPlugin extends Plugin
{
	static final String CONFIG_GROUP = "flinch";

	private static final String KEY_ENABLED = "enabled";
	private static final String KEY_ANIMATION_CANCEL = "animationCancel";
	private static final String KEY_ANIMATION_PREFIX = "anim_";

	/** A game tick is 600ms; animation frame lengths are counted in 20ms client cycles. */
	private static final int CYCLES_PER_TICK = 30;
	private static final int MIN_RESET_TICKS = 1;
	/** Nothing may hold the player hostage for longer than this, whatever the frame data says. */
	private static final int MAX_RESET_TICKS = 25;

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private ClientToolbar clientToolbar;
	@Inject private FlinchCatalogue catalogue;
	@Inject private FlinchConfig config;

	private FlinchPanel panel;
	private NavigationButton navButton;
	private boolean navButtonAdded;

	/**
	 * Set when a hitsplat lands, applied on the following GameTick.
	 *
	 * Being hit makes the server play the weapon's block animation, and that arrives in the
	 * same update packet as the hitsplat, so anything applied from the hitsplat handler is
	 * immediately overwritten. GameTick is posted once that packet has been fully processed,
	 * which is the first moment our animation can survive.
	 */
	private int pendingAnimationId = FlinchAnimation.NO_ANIMATION;

	/** Guards against multi-hitsplat attacks (Scythe, chinning) retriggering within one tick. */
	private int lastTriggerTick = -1;

	/**
	 * Many animations hold their final frame instead of ending. The death poses leave you face
	 * down, and Uri's vanish leaves you invisible. The client only restores the idle pose when
	 * the animation is cleared, so we clear it ourselves once it has had time to play out.
	 */
	private int activeAnimationId = FlinchAnimation.NO_ANIMATION;
	private int animationStartTick = -1;
	private int resetAtTick = -1;

	/** Last known position, used to spot the player walking out from under an animation. */
	private WorldPoint lastLocation;

	@Provides
	FlinchConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlinchConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = injector.getInstance(FlinchPanel.class);
		panel.init(this);

		final BufferedImage icon = ImageUtil.loadImageResource(FlinchPlugin.class, "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Flinch")
			.icon(icon)
			.priority(8)
			.panel(panel)
			.build();

		updateNavButton();
		panel.setLoggedIn(client.getGameState() == GameState.LOGGED_IN);
	}

	@Override
	protected void shutDown()
	{
		clearActiveAnimation();

		if (navButtonAdded)
		{
			clientToolbar.removeNavigation(navButton);
			navButtonAdded = false;
		}
		navButton = null;
		panel = null;
		lastTriggerTick = -1;
		pendingAnimationId = FlinchAnimation.NO_ANIMATION;
	}

	// --- Events ---

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (CONFIG_GROUP.equals(event.getGroup()) && "showSidebar".equals(event.getKey()))
		{
			updateNavButton();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			activeAnimationId = FlinchAnimation.NO_ANIMATION;
			pendingAnimationId = FlinchAnimation.NO_ANIMATION;
			resetAtTick = -1;
			lastLocation = null;
		}

		if (panel != null)
		{
			panel.setLoggedIn(event.getGameState() == GameState.LOGGED_IN);
		}
	}

	/**
	 * Any click is treated as the user taking control back.
	 *
	 * Some animations stall movement for as long as they run, so there has to be a way out that
	 * does not involve waiting. Clicking to walk, attack or interact clears the animation
	 * immediately, whatever the animation cancel setting says.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		pendingAnimationId = FlinchAnimation.NO_ANIMATION;
		clearActiveAnimation();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (pendingAnimationId != FlinchAnimation.NO_ANIMATION)
		{
			final int animationId = pendingAnimationId;
			pendingAnimationId = FlinchAnimation.NO_ANIMATION;
			applyAnimation(animationId);
			lastLocation = location();
			return;
		}

		// Walking out from under an animation counts as taking control back too, because the click that
		// started the walk may have happened before the animation did.
		final WorldPoint location = location();
		final boolean moved = location != null && lastLocation != null && !location.equals(lastLocation);
		lastLocation = location;

		if (activeAnimationId == FlinchAnimation.NO_ANIMATION)
		{
			return;
		}

		if (moved || client.getTickCount() >= resetAtTick)
		{
			clearActiveAnimation();
			return;
		}

		if (isAnimationCancel())
		{
			holdAnimation();
		}
	}

	/**
	 * Takes the animation back when something else has claimed it mid-play.
	 *
	 * A fast weapon swings again before a flinch has finished, and the attack animation wins
	 * because it arrives later. With animation cancel on the flinch is meant to be the thing
	 * that wins, so it is re-applied at the frame it would have reached, not from the start,
	 * so it plays through once rather than stuttering back to the beginning on every swing.
	 */
	private void holdAnimation()
	{
		final Player local = client.getLocalPlayer();
		if (local == null || local.getAnimation() == activeAnimationId)
		{
			return;
		}

		final int elapsedCycles = (client.getTickCount() - animationStartTick) * CYCLES_PER_TICK;
		local.setAnimation(activeAnimationId);
		local.setAnimationFrame(frameAt(activeAnimationId, elapsedCycles));
	}

	/** The frame an animation would be showing this many client cycles in. */
	private int frameAt(int animationId, int elapsedCycles)
	{
		final Animation animation = client.loadAnimation(animationId);
		if (animation == null || animation.isMayaAnim())
		{
			return 0;
		}

		final int[] frameLengths = animation.getFrameLengths();
		if (frameLengths == null || frameLengths.length == 0)
		{
			return 0;
		}

		int remaining = elapsedCycles;
		for (int frame = 0; frame < frameLengths.length; frame++)
		{
			if (remaining < frameLengths[frame])
			{
				return frame;
			}
			remaining -= frameLengths[frame];
		}
		return frameLengths.length - 1;
	}

	private WorldPoint location()
	{
		final Player local = client.getLocalPlayer();
		return local == null ? null : local.getWorldLocation();
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!isEnabled() || event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		final FlinchTrigger trigger = classify(event.getHitsplat());
		if (trigger != null)
		{
			fire(trigger);
		}
	}

	// --- Trigger resolution ---

	private FlinchTrigger classify(Hitsplat hitsplat)
	{
		switch (hitsplat.getHitsplatType())
		{
			case HitsplatID.POISON:
				return FlinchTrigger.POISON;
			case HitsplatID.VENOM:
				return FlinchTrigger.VENOM;
			case HitsplatID.HEAL:
				return FlinchTrigger.HEAL;
			default:
				break;
		}

		if (!isDamageToPlayer(hitsplat.getHitsplatType()))
		{
			return null;
		}

		return hitsplat.getAmount() <= 0 ? FlinchTrigger.ZERO : FlinchTrigger.DAMAGE;
	}

	/**
	 * Only the "me" hitsplat families land on the local player. Everything else, such as prayer drain,
	 * disease, corruption and stat changes, has its own type and must not count as damage.
	 */
	private static boolean isDamageToPlayer(int hitsplatType)
	{
		switch (hitsplatType)
		{
			case HitsplatID.BLOCK_ME:
			case HitsplatID.DAMAGE_ME:
			case HitsplatID.DAMAGE_ME_CYAN:
			case HitsplatID.DAMAGE_ME_ORANGE:
			case HitsplatID.DAMAGE_ME_YELLOW:
			case HitsplatID.DAMAGE_ME_WHITE:
			case HitsplatID.DAMAGE_ME_POISE:
			case HitsplatID.DAMAGE_MAX_ME:
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
			case HitsplatID.DAMAGE_MAX_ME_POISE:
				return true;
			default:
				return false;
		}
	}

	private void fire(FlinchTrigger trigger)
	{
		final int animationId = getAnimationId(trigger);
		if (animationId == FlinchAnimation.NO_ANIMATION)
		{
			return;
		}

		final int tick = client.getTickCount();
		if (tick == lastTriggerTick)
		{
			return;
		}

		// With animation cancel off, a flinch already playing is allowed to finish rather than
		// being restarted by the next hit. The server's own block animation is never treated as
		// a reason to skip, because replacing it is the entire feature.
		if (!isAnimationCancel() && activeAnimationId != FlinchAnimation.NO_ANIMATION)
		{
			return;
		}

		lastTriggerTick = tick;
		pendingAnimationId = animationId;
	}

	/** Plays an animation right now. Used by the panel to preview. Safe to call off the client thread. */
	void play(int animationId)
	{
		clientThread.invoke(() -> applyAnimation(animationId));
	}

	private void applyAnimation(int animationId)
	{
		clientThread.invoke(() ->
		{
			final Player local = client.getLocalPlayer();
			if (local == null)
			{
				return;
			}

			if (animationId == FlinchAnimation.NO_ANIMATION)
			{
				clearActiveAnimation();
				return;
			}

			// Clear first so a held pose is replaced outright rather than the new animation
			// being ignored because one is already running.
			local.setAnimation(FlinchAnimation.NO_ANIMATION);
			local.setAnimation(animationId);
			local.setAnimationFrame(0);

			activeAnimationId = animationId;
			animationStartTick = client.getTickCount();
			resetAtTick = animationStartTick + durationTicks(animationId);
		});
	}

	/**
	 * How long the animation runs, in game ticks, rounded up and capped. Falls back to the cap
	 * when the sequence has not been loaded from the cache yet and its frame data is unavailable.
	 */
	private int durationTicks(int animationId)
	{
		int cycles = 0;

		final Animation animation = client.loadAnimation(animationId);
		if (animation != null)
		{
			if (animation.isMayaAnim())
			{
				cycles = animation.getDuration();
			}
			else
			{
				final int[] frameLengths = animation.getFrameLengths();
				if (frameLengths != null)
				{
					for (int length : frameLengths)
					{
						cycles += length;
					}
				}
			}
		}

		if (cycles <= 0)
		{
			return MAX_RESET_TICKS;
		}

		final int ticks = (cycles + CYCLES_PER_TICK - 1) / CYCLES_PER_TICK;
		return Math.max(MIN_RESET_TICKS, Math.min(MAX_RESET_TICKS, ticks + 1));
	}

	/** Restores the idle pose, but only if the animation we started is still the one playing. */
	private void clearActiveAnimation()
	{
		final int animationId = activeAnimationId;
		activeAnimationId = FlinchAnimation.NO_ANIMATION;
		animationStartTick = -1;
		resetAtTick = -1;

		if (animationId == FlinchAnimation.NO_ANIMATION)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			final Player local = client.getLocalPlayer();
			if (local != null && local.getAnimation() == animationId)
			{
				local.setAnimation(FlinchAnimation.NO_ANIMATION);
			}
		});
	}

	// --- Settings, persisted through ConfigManager rather than @ConfigItem ---
	// The panel is the whole UI, so these keys are deliberately not rendered in the
	// config panel. They still save and sync like any other config value.

	boolean isEnabled()
	{
		return getBoolean(KEY_ENABLED, true);
	}

	void setEnabled(boolean enabled)
	{
		configManager.setConfiguration(CONFIG_GROUP, KEY_ENABLED, enabled);
	}

	boolean isAnimationCancel()
	{
		return getBoolean(KEY_ANIMATION_CANCEL, false);
	}

	void setAnimationCancel(boolean cancel)
	{
		configManager.setConfiguration(CONFIG_GROUP, KEY_ANIMATION_CANCEL, cancel);
	}

	int getAnimationId(FlinchTrigger trigger)
	{
		final String stored = configManager.getConfiguration(CONFIG_GROUP, KEY_ANIMATION_PREFIX + trigger.getConfigKey());
		if (stored == null)
		{
			return trigger.getDefaultAnimationId();
		}

		try
		{
			return Integer.parseInt(stored.trim());
		}
		catch (NumberFormatException e)
		{
			// Pre-release builds stored an enum name here. Fall back rather than throw.
			log.debug("Unreadable stored animation '{}' for trigger {}", stored, trigger);
			return trigger.getDefaultAnimationId();
		}
	}

	void setAnimationId(FlinchTrigger trigger, int animationId)
	{
		configManager.setConfiguration(CONFIG_GROUP, KEY_ANIMATION_PREFIX + trigger.getConfigKey(), animationId);
	}

	Map<FlinchTrigger, FlinchAnimation> getAllAnimations()
	{
		final Map<FlinchTrigger, FlinchAnimation> animations = new EnumMap<>(FlinchTrigger.class);
		for (FlinchTrigger trigger : FlinchTrigger.values())
		{
			animations.put(trigger, catalogue.byId(getAnimationId(trigger)));
		}
		return animations;
	}

	private boolean getBoolean(String key, boolean fallback)
	{
		final Boolean stored = configManager.getConfiguration(CONFIG_GROUP, key, Boolean.class);
		return stored == null ? fallback : stored;
	}

	private void updateNavButton()
	{
		if (navButton == null)
		{
			return;
		}

		final boolean show = config.showSidebar();
		if (show && !navButtonAdded)
		{
			clientToolbar.addNavigation(navButton);
			navButtonAdded = true;
		}
		else if (!show && navButtonAdded)
		{
			clientToolbar.removeNavigation(navButton);
			navButtonAdded = false;
		}
	}
}
