package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import net.minecraft.ChatFormatting;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The auras a player can roll. Each one is a bundle of abilities; on top of
 * that every aura gets the {@link #GLOBAL} abilities. Saved by name, so
 * adding auras later never scrambles saved data.
 */
public enum AuraType {
	// Roles: Shadow fast and stealthy, Water sustain, Fire attack, Wind air, Frost control, Thunder burst. Three actives + two passives each
	// (Shadow: two actives, three passives; Frost: one active so far, three passives).
	SHADOW("Shadow", ChatFormatting.DARK_PURPLE, 0x7B2FBE,
			Ability.VANISH, Ability.DASH, Ability.PHANTOM_STEP, Ability.VANISH_STEP, Ability.BLOODRUSH, Ability.NIGHTSTALKER),
	WATER("Water", ChatFormatting.AQUA, 0x2E9BFF,
			Ability.SHOCKWAVE, Ability.HEALING_TIDE, Ability.ABYSSAL_BUBBLE, Ability.IRON_SKIN, Ability.FLOW),
	FIRE("Fire", ChatFormatting.RED, 0xFF5A1F,
			Ability.FIREBALL, Ability.HEAT_WAVE, Ability.FIRE_BENDING, Ability.BERSERKER, Ability.EXECUTIONER, Ability.FIREBORN),
	WIND("Wind", ChatFormatting.WHITE, 0xDDF3FF,
			Ability.DASH, Ability.SUPER_WIND_CHARGE, Ability.DOUBLE_JUMP, Ability.GALE_RIDER, Ability.FEATHERFALL),
	FROST("Frost", ChatFormatting.DARK_AQUA, 0xA8E4FF,
			Ability.ICE_SPIKE, Ability.SURE_FOOTED, Ability.ICE_SHELL, Ability.SHATTER),
	VERDANT("Verdant", ChatFormatting.GREEN, 0x4FB848,
			Ability.VINE_HOOK, Ability.ENTANGLEMENT, Ability.VERDANT_VIPER, Ability.BLOOM, Ability.VIRULENCE),
	THUNDER("Thunder", ChatFormatting.YELLOW, 0xFFE347,
			Ability.THUNDERBOLT, Ability.CHIDORI, Ability.STATIC_CHARGE, Ability.STORMBORN),
	RESONANCE("Resonance", ChatFormatting.DARK_PURPLE, 0xB01CE0,
			Ability.SONIC_BLOSSOM, Ability.LISTENING_TREE, Ability.ABYSSAL_GRASP, Ability.ECHOLOCATION),
	// started out owner only (secret); public and rollable since 4.87.0
	EARTH("Earth", ChatFormatting.GOLD, 0x9A7148,
			Ability.PILLAR_LIFT, Ability.BOULDER_TOSS, Ability.QUAKE, Ability.STONE_SKIN, Ability.GROUNDED),
	LIGHT("Light", ChatFormatting.YELLOW, 0xFFE9A8,
			Ability.RADIANT_PULSE, Ability.SOLAR_FLASH, Ability.PRISM_BEAM, Ability.HALO, Ability.LUMINOUS),
	BLOOD("Blood", ChatFormatting.DARK_RED, 0xB0121B,
			Ability.HEMORRHAGE, Ability.BLOOD_TETHER, Ability.CRIMSON_SACRIFICE, Ability.BLOODLUST, Ability.THIN_BLOOD);
	// Void was an aura until 5.10.0: it is Shadow's Dark branch now (ability/Branch.DARK); old saves are migrated on load

	/** Abilities every aura has. */
	public static final Set<Ability> GLOBAL = EnumSet.of(Ability.LIFESTEAL);

	public final String displayName;
	public final ChatFormatting color;
	/** Particle color (0xRRGGBB) for this aura's effects. */
	public final int rgb;
	public final List<Ability> abilities;

	AuraType(String displayName, ChatFormatting color, int rgb, Ability... abilities) {
		this.displayName = displayName;
		this.color = color;
		this.rgb = rgb;
		this.abilities = List.of(abilities);
	}

	public boolean has(Ability ability) {
		return abilities.contains(ability) || GLOBAL.contains(ability);
	}

	/** The aura's abilities for a branch (a branch with its own kit replaces the list; null = the base kit). */
	public List<Ability> abilities(Branch branch) {
		return branch != null && branch.aura == this && branch.abilities() != null ? branch.abilities() : abilities;
	}

	public boolean has(Ability ability, Branch branch) {
		return abilities(branch).contains(ability) || GLOBAL.contains(ability);
	}

	/** Lowercase id used in commands and predicates, e.g. "shadow". */
	public String id() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	/** How many "Ability N" keys there are; every aura puts its keyed actives on them in this order. */
	public static final int KEY_SLOTS = 3;

	/** This aura's actives on the Ability 1/2/3 keys (movement first, like Wind and Shadow's Dash). */
	public java.util.List<Ability> keySlots() {
		return switch (this) {
			case SHADOW -> java.util.List.of(Ability.DASH, Ability.VANISH);
			case WATER -> java.util.List.of(Ability.SHOCKWAVE, Ability.HEALING_TIDE, Ability.ABYSSAL_BUBBLE);
			case FIRE -> java.util.List.of(Ability.FIREBALL, Ability.HEAT_WAVE, Ability.FIRE_BENDING);
			case WIND -> java.util.List.of(Ability.DASH, Ability.SUPER_WIND_CHARGE);
			case FROST -> java.util.List.of(Ability.ICE_SPIKE);
			case VERDANT -> java.util.List.of(Ability.VINE_HOOK, Ability.ENTANGLEMENT, Ability.VERDANT_VIPER);
			case THUNDER -> java.util.List.of(Ability.THUNDERBOLT, Ability.CHIDORI);
			case RESONANCE -> java.util.List.of(Ability.ABYSSAL_GRASP, Ability.LISTENING_TREE, Ability.SONIC_BLOSSOM);
			case EARTH -> java.util.List.of(Ability.PILLAR_LIFT, Ability.BOULDER_TOSS, Ability.QUAKE);
			case LIGHT -> java.util.List.of(Ability.RADIANT_PULSE, Ability.SOLAR_FLASH, Ability.PRISM_BEAM);
			case BLOOD -> java.util.List.of(Ability.HEMORRHAGE, Ability.BLOOD_TETHER, Ability.CRIMSON_SACRIFICE);
		};
	}

	/** The key slots for a branch (Dark has its own). */
	public java.util.List<Ability> keySlots(Branch branch) {
		return branch != null && branch.aura == this && branch.keySlots() != null ? branch.keySlots() : keySlots();
	}

	/** The ability on key slot {@code slot} (0-based), or null. */
	public Ability slot(int slot) {
		return slot(slot, null);
	}

	public Ability slot(int slot, Branch branch) {
		var slots = keySlots(branch);
		return slot >= 0 && slot < slots.size() ? slots.get(slot) : null;
	}

	/** Elemental matchups: the auras this one is strong against (it deals more damage to their players). */
	public java.util.List<AuraType> strongAgainst() {
		return switch (this) {
			case FIRE -> java.util.List.of(FROST, VERDANT);
			case WATER -> java.util.List.of(FIRE);
			case VERDANT -> java.util.List.of(WATER, THUNDER);
			case THUNDER -> java.util.List.of(WATER);
			case EARTH -> java.util.List.of(THUNDER);
			case LIGHT -> java.util.List.of(SHADOW);
			case BLOOD -> java.util.List.of(VERDANT);
			default -> java.util.List.of();
		};
	}

	/** The auras this one is strong against with a branch (Dark plays the old Void matchups). */
	public java.util.List<AuraType> strongAgainst(Branch branch) {
		return branch != null && branch.aura == this && branch.strongAgainst() != null ? branch.strongAgainst() : strongAgainst();
	}

	/** The auras strong against this one. */
	public java.util.List<AuraType> weakAgainst() {
		return weakAgainst(null);
	}

	/** The auras strong against this one with a branch: a kit branch (Dark) has matchups of its own, so nobody counters it as plain Shadow. */
	public java.util.List<AuraType> weakAgainst(Branch branch) {
		java.util.List<AuraType> out = new java.util.ArrayList<>();
		if (branch != null && branch.aura == this && branch.strongAgainst() != null) return out;
		for (AuraType t : values()) if (t.strongAgainst().contains(this)) out.add(t);
		return out;
	}

	/** True if an attacker holding {@code attacker}/{@code attackerBranch} is strong against a victim holding {@code victim}/{@code victimBranch}. */
	public static boolean beats(AuraType attacker, Branch attackerBranch, AuraType victim, Branch victimBranch) {
		if (!attacker.strongAgainst(attackerBranch).contains(victim)) return false;
		// a kit branch (Dark) has matchups of its own: the plain aura's counters (Light beats Shadow) don't reach it
		boolean victimKit = victimBranch != null && victimBranch.aura == victim && victimBranch.strongAgainst() != null;
		boolean attackerKit = attackerBranch != null && attackerBranch.aura == attacker && attackerBranch.strongAgainst() != null;
		return attackerKit || !victimKit;
	}

	/** Whether /aura roll can land on it (Frost only once FROST_ROLLABLE is on). */
	public boolean rollable() {
		return switch (this) {
			case FROST -> com.toast.aurapoints.config.AuraConfig.FROST_ROLLABLE;
			case VERDANT -> com.toast.aurapoints.config.AuraConfig.VERDANT_ROLLABLE;
			case THUNDER -> com.toast.aurapoints.config.AuraConfig.THUNDER_ROLLABLE;
			case RESONANCE -> com.toast.aurapoints.config.AuraConfig.RESONANCE_ROLLABLE;
			case EARTH -> com.toast.aurapoints.config.AuraConfig.EARTH_ROLLABLE;
			case LIGHT -> com.toast.aurapoints.config.AuraConfig.LIGHT_ROLLABLE;
			case BLOOD -> com.toast.aurapoints.config.AuraConfig.BLOOD_ROLLABLE;
			case SHADOW -> com.toast.aurapoints.config.AuraConfig.SHADOW_ROLLABLE;
			case WATER -> com.toast.aurapoints.config.AuraConfig.WATER_ROLLABLE;
			case FIRE -> com.toast.aurapoints.config.AuraConfig.FIRE_ROLLABLE;
			case WIND -> com.toast.aurapoints.config.AuraConfig.WIND_ROLLABLE;
		};
	}


	/** Null if there is no aura with that id. */
	public static AuraType byId(String id) {
		for (AuraType type : values()) {
			if (type.id().equals(id)) return type;
		}
		return null;
	}
}
