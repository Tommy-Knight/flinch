package com.tommyknight.flinch;

import java.util.Locale;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * One selectable animation.
 *
 * Equality is on the id alone, so a selection survives the combo box model being swapped out
 * while the user filters the list.
 */
@Getter
@EqualsAndHashCode(of = "id")
final class FlinchAnimation
{
	static final int NO_ANIMATION = -1;

	static final FlinchAnimation NONE = new FlinchAnimation(NO_ANIMATION, "None", "None", Group.NONE);

	enum Group
	{
		/** The "do nothing" entry. */
		NONE,
		/** Hand-picked and hand-named, listed first. */
		FAVOURITE,
		/** Everything else built on the human rig — safe on a player model. */
		PLAYER,
		/** NPC and scenery rigs. Listed last, and most of them look wrong on a player. */
		OTHER
	}

	private final int id;
	private final String displayName;
	/** The gameval constant name, surfaced as a tooltip so ids can be cross-referenced. */
	private final String gamevalName;
	private final Group group;
	/** Lowercased once at load so filtering does not re-case 14,000 strings per keystroke. */
	private final String searchKey;

	FlinchAnimation(int id, String displayName, String gamevalName, Group group)
	{
		this.id = id;
		this.displayName = displayName;
		this.gamevalName = gamevalName;
		this.group = group;
		this.searchKey = (displayName + ' ' + gamevalName + ' ' + id).toLowerCase(Locale.ROOT);
	}

	boolean matches(String lowercaseNeedle)
	{
		return searchKey.contains(lowercaseNeedle);
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
