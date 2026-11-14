package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * How an Ability key was used. TAP is what every ability always did. SNEAK (sneak + key) and CHARGED (the key
 * held CAST_CHARGE_TICKS, the cast firing on the release) are the extra casts in {@link Casts}; an ability
 * without that variant keeps firing its normal cast. Variants unlock with the aura's Mastery
 * (CAST_SNEAK_MASTERY / CAST_CHARGED_MASTERY) and CAST_VARIANTS_ENABLED switches them all off.
 *
 * Abilities whose key already holds (Wind's Dash and Super Wind Charge, Abyssal Bubble, Ice Spike, Chidori,
 * Prism Beam, Abyssal Grasp) never get CHARGED: their hold means something else already.
 */
public enum CastMode {
	TAP, SNEAK, CHARGED;

	private static final Map<Ability, EnumSet<CastMode>> VARIANTS = new EnumMap<>(Ability.class);

	static {
		// Fire
		put(Ability.FIREBALL, SNEAK, CHARGED);
		put(Ability.HEAT_WAVE, SNEAK, CHARGED);
		put(Ability.FIRE_BENDING, SNEAK);
		// Water
		put(Ability.SHOCKWAVE, SNEAK, CHARGED);
		put(Ability.HEALING_TIDE, SNEAK, CHARGED);
		put(Ability.ABYSSAL_BUBBLE, SNEAK);
		// Shadow (a Wind player's Dash is WindLaunch's hold key and never reaches these)
		put(Ability.DASH, SNEAK, CHARGED);
		put(Ability.VANISH, SNEAK, CHARGED);
	}

	private static void put(Ability ability, CastMode first, CastMode... more) {
		VARIANTS.put(ability, EnumSet.of(first, more));
	}

	/** Whether the ability has this cast at all (TAP always). */
	public static boolean has(Ability ability, CastMode mode) {
		if (mode == TAP) return true;
		EnumSet<CastMode> modes = VARIANTS.get(ability);
		return modes != null && modes.contains(mode);
	}

	/** The Mastery level of the aura that unlocks this cast (0 for TAP). */
	public int masteryNeeded() {
		return switch (this) {
			case TAP -> 0;
			case SNEAK -> AuraConfig.CAST_SNEAK_MASTERY;
			case CHARGED -> AuraConfig.CAST_CHARGED_MASTERY;
		};
	}

	/** This player can use this cast of the ability now: it exists, casts are on, and their aura is mastered enough. */
	public static boolean unlocked(ServerPlayer player, Ability ability, CastMode mode) {
		if (mode == TAP) return true;
		if (!AuraConfig.CAST_VARIANTS_ENABLED || !has(ability, mode)) return false;
		return Mastery.level(player) >= mode.masteryNeeded();
	}
}
