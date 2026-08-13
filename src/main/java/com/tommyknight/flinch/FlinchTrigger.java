package com.tommyknight.flinch;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.AnimationID;

/**
 * The events that can play an animation, and the default each starts on.
 *
 * The config key is persisted, so renaming one silently resets that trigger for every
 * existing user. Add new triggers rather than repurposing old keys.
 */
@Getter
@RequiredArgsConstructor
enum FlinchTrigger
{
	ZERO("Block", "zero", Section.COMBAT, AnimationID.HUMAN_REAL_SWIMMING_CLIMB_ANCHOR,
		"A 0 or a blocked hit"),
	DAMAGE("Damage", "damage", Section.COMBAT, FlinchAnimation.NO_ANIMATION,
		"Any hit above 0"),

	POISON("Poison", "poison", Section.STATUS, FlinchAnimation.NO_ANIMATION,
		"Poison damage"),
	VENOM("Venom", "venom", Section.STATUS, FlinchAnimation.NO_ANIMATION,
		"Venom damage"),
	HEAL("Heal", "heal", Section.STATUS, FlinchAnimation.NO_ANIMATION,
		"Being healed");

	enum Section
	{
		COMBAT("Combat"),
		STATUS("Status");

		final String displayName;

		Section(String displayName)
		{
			this.displayName = displayName;
		}
	}

	private final String displayName;
	private final String configKey;
	private final Section section;
	private final int defaultAnimationId;
	private final String tooltip;
}
