package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Aura branches (5.9.0): at Mastery V a player picks one of two branches of the aura they hold. A branch bends
 * the aura's kit (cooldowns, a passive, how an active behaves); it never adds a second aura. The pick is kept
 * per aura (like Mastery), the first pick is free and switching costs {@link AuraConfig#BRANCH_SWITCH_COST} Aura.
 * A player with no branch (or below the Mastery level) plays the base kit exactly as before.
 */
public enum Branch {

	BLUE_FLAME(AuraType.FIRE, "Blue Flame", ChatFormatting.AQUA, "Precision", Items.SOUL_CAMPFIRE,
			() -> List.of(
					"Fireball flies " + Math.round((AuraConfig.FIRE_BLUE_FLAME_FIREBALL_SPEED - 1) * 100) + "% faster and pierces the first target it hits.",
					"Fireball cooldown " + (AuraConfig.FIRE_BLUE_FLAME_FIREBALL_COOLDOWN_TICKS / 20) + "s (was " + (AuraConfig.FIREBALL_COOLDOWN_TICKS / 20) + "s).",
					"Scorch: burning targets take " + Math.round(AuraConfig.SCORCH_BONUS * 100) + "% more damage from you.",
					"Fire Bending shots hit for " + AuraAbilities.fmt(AuraConfig.FIRE_BLUE_FLAME_BENDING_DAMAGE_CLOSE) + " close up, "
							+ AuraAbilities.fmt(AuraConfig.FIRE_BLUE_FLAME_BENDING_DAMAGE_FAR) + " far away.",
					"Price: Heat Wave cooldown " + (AuraConfig.FIRE_BLUE_FLAME_HEAT_WAVE_COOLDOWN_TICKS / 20) + "s (was " + (AuraConfig.HEAT_WAVE_COOLDOWN_TICKS / 20) + "s).")) {
		@Override
		List<AuraAbilities.Ability> adds() {
			return List.of(AuraAbilities.Ability.SCORCH);
		}
	},

	MAGMA(AuraType.FIRE, "Magma", ChatFormatting.GOLD, "Zone control", Items.MAGMA_BLOCK,
			() -> List.of(
					"Fireball hits, Heat Wave and the Inferno Slam leave magma pools that burn and hurt enemies standing in them for "
							+ AuraAbilities.fmt(AuraConfig.FIRE_MAGMA_POOL_TICKS / 20f) + "s.",
					"Standing in your own pool gives you Resistance " + (AuraConfig.FIRE_MAGMA_RESISTANCE_AMPLIFIER + 1) + ".",
					"The pools are fire and heat only: no lava blocks, nothing to clean up.",
					"Price: Fireball cooldown " + (AuraConfig.FIRE_MAGMA_FIREBALL_COOLDOWN_TICKS / 20) + "s (was " + (AuraConfig.FIREBALL_COOLDOWN_TICKS / 20) + "s).")),

	PHANTOM(AuraType.SHADOW, "Phantom", ChatFormatting.LIGHT_PURPLE, "Evasion and tricks", Items.PHANTOM_MEMBRANE,
			() -> List.of(
					"Vanish and Dash cooldowns " + Math.round((1 - AuraConfig.PHANTOM_COOLDOWN_FACTOR) * 100) + "% shorter.",
					"Dash leaves a shadow clone where you started it for " + AuraAbilities.fmt(AuraConfig.PHANTOM_DECOY_TICKS / 20f)
							+ "s: mobs hunting you lose you.",
					"Phantom Step keeps you invisible " + Math.round((AuraConfig.PHANTOM_STEP_INVIS_FACTOR - 1) * 100) + "% longer, Vanish Step "
							+ AuraAbilities.fmt(AuraConfig.PHANTOM_VANISH_STEP_BONUS_TICKS / 20f) + "s longer.")),

	GLACIAL(AuraType.FROST, "Glacial", ChatFormatting.AQUA, "Defensive lockdown", Items.BLUE_ICE,
			() -> List.of(
					"Ice Spike freezes for " + AuraAbilities.fmt(AuraConfig.FROST_GLACIAL_FREEZE_TICKS / 20f) + "s (was " + AuraAbilities.fmt(AuraConfig.ICE_SPIKE_FREEZE_TICKS / 20f)
							+ "s) and its slow lasts " + Math.round((AuraConfig.FROST_GLACIAL_SLOW_FACTOR - 1) * 100) + "% longer.",
					"Ice Shell has " + AuraConfig.FROST_GLACIAL_SHELL_LAYERS + " layers (was " + AuraConfig.ICE_SHELL_LAYERS + ").",
					"Price: the shell rebuilds in " + (AuraConfig.FROST_GLACIAL_SHELL_REBUILD_TICKS / 20) + "s (was " + (AuraConfig.ICE_SHELL_REBUILD_TICKS / 20)
							+ "s) and Shatter needs " + AuraConfig.FROST_GLACIAL_SHATTER_HITS + " hits.")),

	FROSTBITE(AuraType.FROST, "Frostbite", ChatFormatting.DARK_AQUA, "Burst around the combo", Items.PRISMARINE_SHARD,
			() -> List.of(
					"Shatter needs only " + AuraConfig.FROST_BITE_SHATTER_HITS + " hits (was " + AuraConfig.SHATTER_HITS + ").",
					"Brittle: Chilled targets take " + Math.round(AuraConfig.BRITTLE_BONUS * 100) + "% more damage from you.",
					"Price: Ice Shell has " + AuraConfig.FROST_BITE_SHELL_LAYERS + " layers and Ice Spike freezes for "
							+ AuraAbilities.fmt(AuraConfig.FROST_BITE_FREEZE_TICKS / 20f) + "s.")) {
		@Override
		List<AuraAbilities.Ability> adds() {
			return List.of(AuraAbilities.Ability.BRITTLE);
		}
	},

	VENOMWEAVER(AuraType.VERDANT, "Venomweaver", ChatFormatting.GREEN, "Damage over time", Items.SPIDER_EYE,
			() -> List.of(
					"Virulence: poisoned targets take " + Math.round(AuraConfig.VERDANT_VENOM_VIRULENCE_BONUS * 100) + "% more damage from you (was "
							+ Math.round(AuraConfig.VIRULENCE_BONUS * 100) + "%).",
					"Poison stacks: a bite or a trap catch on a poisoned target raises its Poison level by one, up to " + (AuraConfig.VERDANT_VENOM_POISON_MAX_AMPLIFIER + 1) + ".",
					"Price: Verdant Viper cooldown " + (AuraConfig.VERDANT_VENOM_VIPER_COOLDOWN_TICKS / 20) + "s (was " + (AuraConfig.VIPER_COOLDOWN_TICKS / 20) + "s).")),

	GROVE_WARDEN(AuraType.VERDANT, "Grove Warden", ChatFormatting.DARK_GREEN, "Support and trapper", Items.OAK_SAPLING,
			() -> List.of(
					"While Bloom is on and you sneak, friends within " + AuraConfig.VERDANT_GROVE_HEAL_RADIUS + " blocks heal "
							+ AuraAbilities.fmt(AuraConfig.VERDANT_GROVE_HEAL) + " HP a second.",
					"Entanglement traps last " + (AuraConfig.VERDANT_GROVE_TRAP_TICKS / 20) + "s (was " + (AuraConfig.ENTANGLE_TRAP_TICKS / 20) + "s) and the roots need "
							+ AuraConfig.VERDANT_GROVE_MASH_PRESSES + " key presses to break (was " + AuraConfig.ENTANGLE_MASH_PRESSES + ").",
					"Price: no Virulence, and the Viper's poison is level " + (AuraConfig.VERDANT_GROVE_VIPER_POISON_AMPLIFIER + 1) + ".")) {
		@Override
		List<AuraAbilities.Ability> removes() {
			return List.of(AuraAbilities.Ability.VIRULENCE);
		}
	},

	BULWARK(AuraType.EARTH, "Bulwark", ChatFormatting.DARK_GRAY, "Tank", Items.STONE_BRICKS,
			() -> List.of(
					"Stone Skin gives " + AuraAbilities.fmt((float) AuraConfig.EARTH_BULWARK_SKIN_ARMOR) + " armor and " + AuraAbilities.fmt((float) AuraConfig.EARTH_BULWARK_SKIN_TOUGHNESS)
							+ " toughness; Grounded resists " + Math.round(AuraConfig.EARTH_BULWARK_KNOCKBACK_RESISTANCE * 100) + "% knockback and "
							+ Math.round(AuraConfig.EARTH_BULWARK_FALL_REDUCTION * 100) + "% fall damage.",
					"Every enemy your Quake hits gives you " + AuraAbilities.fmt(AuraConfig.EARTH_BULWARK_QUAKE_ABSORB) + " absorption HP for "
							+ (AuraConfig.EARTH_BULWARK_QUAKE_ABSORB_TICKS / 20) + "s (up to " + AuraAbilities.fmt(AuraConfig.EARTH_BULWARK_QUAKE_ABSORB_MAX) + ").",
					"Price: Pillar Lift and Boulder Toss deal " + Math.round((1 - AuraConfig.EARTH_BULWARK_ATTACK_FACTOR) * 100) + "% less.")),

	TECTONIC(AuraType.EARTH, "Tectonic", ChatFormatting.GOLD, "Offense and terrain", Items.DEEPSLATE_BRICKS,
			() -> List.of(
					"Pillar Lift hits for " + AuraAbilities.fmt(AuraConfig.EARTH_TECTONIC_PILLAR_DAMAGE) + " and throws higher; Boulder Toss for "
							+ AuraAbilities.fmt(AuraConfig.EARTH_TECTONIC_BOULDER_DAMAGE) + " (splash " + AuraAbilities.fmt(AuraConfig.EARTH_TECTONIC_BOULDER_SPLASH) + ").",
					"Quake reaches " + AuraAbilities.fmt((float) AuraConfig.EARTH_TECTONIC_QUAKE_RADIUS) + " blocks and hits for " + AuraAbilities.fmt(AuraConfig.EARTH_TECTONIC_QUAKE_DAMAGE) + ".",
					"Price: Stone Skin gives only " + AuraAbilities.fmt((float) AuraConfig.EARTH_TECTONIC_SKIN_ARMOR) + " armor and Grounded resists "
							+ Math.round(AuraConfig.EARTH_TECTONIC_KNOCKBACK_RESISTANCE * 100) + "% knockback.")),

	RADIANT(AuraType.LIGHT, "Radiant", ChatFormatting.YELLOW, "Support", Items.SUNFLOWER,
			() -> List.of(
					"Radiant Pulse heals " + AuraAbilities.fmt(AuraConfig.LIGHT_RADIANT_PULSE_HEAL) + " (was " + AuraAbilities.fmt(AuraConfig.LIGHT_PULSE_HEAL) + ") and Sunburst "
							+ AuraAbilities.fmt(AuraConfig.LIGHT_RADIANT_SUN_HEAL) + " (was " + AuraAbilities.fmt(AuraConfig.LIGHT_SUN_HEAL) + ").",
					"Halo shines on friends within " + AuraConfig.LIGHT_RADIANT_HALO_RADIUS + " blocks: Resistance I, refreshed with every Halo pulse, day or night.",
					"Price: Prism Beam deals " + Math.round((1 - AuraConfig.LIGHT_RADIANT_BEAM_FACTOR) * 100) + "% less, Solar Flash cooldown "
							+ (AuraConfig.LIGHT_RADIANT_FLASH_COOLDOWN_TICKS / 20) + "s (was " + (AuraConfig.LIGHT_FLASH_COOLDOWN_TICKS / 20) + "s).")),

	PRISMATIC(AuraType.LIGHT, "Prismatic", ChatFormatting.GOLD, "Damage", Items.GLOWSTONE,
			() -> List.of(
					"Prism Beam deals " + AuraAbilities.fmt(AuraConfig.LIGHT_PRISM_BEAM_DAMAGE) + " (was " + AuraAbilities.fmt(AuraConfig.LIGHT_BEAM_DAMAGE) + ").",
					"Solar Flash marks what it hits for " + (AuraConfig.LIGHT_PRISM_MARK_TICKS / 20) + "s; the beam on a marked target chains to " + AuraConfig.LIGHT_PRISM_CHAIN_TARGETS
							+ " more enemies within " + AuraAbilities.fmt((float) AuraConfig.LIGHT_PRISM_CHAIN_RADIUS) + " blocks for " + Math.round(AuraConfig.LIGHT_PRISM_CHAIN_FACTOR * 100) + "% damage.",
					"Price: Radiant Pulse heals " + AuraAbilities.fmt(AuraConfig.LIGHT_PRISM_PULSE_HEAL) + " and Halo " + AuraAbilities.fmt(AuraConfig.LIGHT_PRISM_HALO_HEAL) + ".")),

	SANGUINE(AuraType.BLOOD, "Sanguine", ChatFormatting.RED, "Aggressive sustain", Items.REDSTONE,
			() -> List.of(
					"Hemorrhage heals you for " + Math.round(AuraConfig.BLOOD_SANGUINE_HEMO_LIFESTEAL * 100) + "% of its damage and every bleed tick for "
							+ Math.round(AuraConfig.BLOOD_SANGUINE_BLEED_LIFESTEAL * 100) + "% of the bleed damage.",
					"Bloodlust heals " + AuraAbilities.fmt(AuraConfig.BLOOD_SANGUINE_LUST_HEAL) + " per kill (was " + AuraAbilities.fmt(AuraConfig.BLOOD_LUST_HEAL) + "); the Blood bar fills "
							+ Math.round((AuraConfig.BLOOD_SANGUINE_BAR_FACTOR - 1) * 100) + "% faster.",
					"Price: Crimson Sacrifice only multiplies by " + AuraAbilities.fmt((float) AuraConfig.BLOOD_SANGUINE_SACRIFICE_MULTIPLIER) + ", Blood Moon costs "
							+ AuraConfig.BLOOD_SANGUINE_MOON_COST + " of the bar.")),

	SACRIFICIAL(AuraType.BLOOD, "Sacrificial", ChatFormatting.DARK_RED, "High risk, high reward", Items.NETHER_WART,
			() -> List.of(
					"Crimson Sacrifice costs " + AuraAbilities.fmt(AuraConfig.BLOOD_SACRIFICIAL_SACRIFICE_COST) + " HP and multiplies your next " + AuraConfig.BLOOD_SACRIFICIAL_SACRIFICE_HITS
							+ " hits by " + AuraAbilities.fmt((float) AuraConfig.BLOOD_SACRIFICIAL_SACRIFICE_MULTIPLIER) + ".",
					"Blood Tether drains " + AuraAbilities.fmt(AuraConfig.BLOOD_SACRIFICIAL_TETHER_DRAIN) + " per pulse but costs you " + AuraAbilities.fmt(AuraConfig.BLOOD_SACRIFICIAL_TETHER_COST)
							+ " and no longer heals you. Blood Moon costs " + AuraConfig.BLOOD_SACRIFICIAL_MOON_COST + " of the bar.",
					"Price: Bloodlust heals " + AuraAbilities.fmt(AuraConfig.BLOOD_SACRIFICIAL_LUST_HEAL) + " and Thin Blood starts at " + Math.round(AuraConfig.BLOOD_SACRIFICIAL_THIN_THRESHOLD * 100) + "% health.")),

	DARK(AuraType.SHADOW, "Dark", ChatFormatting.DARK_PURPLE, "Pull, rift and null", Items.ENDER_PEARL,
			() -> List.of(
					"Your actives become Void Pull, Rift Step and Null Orb; Dash and Vanish are given up.",
					"Event Horizon and Entropy come with it, and Singularity replaces Eclipse as your Ultimate.",
					"You deal more damage to Light and Wind auras. Bloodrush and Nightstalker stay.",
					"The three Void actives are yours free when you pick it.")) {
		@Override
		boolean kit() {
			return true;
		}
	};

	public final AuraType aura;
	public final String displayName;
	public final ChatFormatting color;
	/** One or two words on how the branch plays. */
	public final String role;
	/** The Aura Book's icon for the branch. */
	public final Item icon;
	private final Supplier<List<String>> lines;

	Branch(AuraType aura, String displayName, ChatFormatting color, String role, Item icon, Supplier<List<String>> lines) {
		this.aura = aura;
		this.displayName = displayName;
		this.color = color;
		this.role = role;
		this.icon = icon;
		this.lines = lines;
	}

	/** Passives (or abilities) the branch adds to the aura's kit, and ones it takes away. */
	List<AuraAbilities.Ability> adds() {
		return List.of();
	}

	List<AuraAbilities.Ability> removes() {
		return List.of();
	}

	/** True for a branch that replaces the aura's whole kit (Dark); the others bend the base kit. */
	boolean kit() {
		return false;
	}

	/** Dark's own kit: its abilities, key slots (Ability 1-3), Ultimate and matchups. Null lists for kit-less branches. */
	public List<AuraAbilities.Ability> abilities() {
		return kit() ? DARK_ABILITIES : null;
	}

	public List<AuraAbilities.Ability> keySlots() {
		return kit() ? DARK_KEYS : null;
	}

	public AuraAbilities.Ability ultimate() {
		return kit() ? AuraAbilities.Ability.SINGULARITY : null;
	}

	public List<AuraType> strongAgainst() {
		return kit() ? List.of(AuraType.LIGHT, AuraType.WIND) : null;
	}

	private static final List<AuraAbilities.Ability> DARK_ABILITIES = List.of(
			AuraAbilities.Ability.VOID_PULL, AuraAbilities.Ability.RIFT_STEP, AuraAbilities.Ability.NULL_ORB,
			AuraAbilities.Ability.EVENT_HORIZON, AuraAbilities.Ability.ENTROPY,
			AuraAbilities.Ability.BLOODRUSH, AuraAbilities.Ability.NIGHTSTALKER);
	private static final List<AuraAbilities.Ability> DARK_KEYS = List.of(
			AuraAbilities.Ability.RIFT_STEP, AuraAbilities.Ability.VOID_PULL, AuraAbilities.Ability.NULL_ORB);

	/** Lowercase id used in commands, e.g. "blue_flame". */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** What the branch changes, one line each (read live so config edits show). */
	public List<String> description() {
		return lines.get();
	}

	/** "Fire · Blue Flame". */
	public String label() {
		return aura.displayName + " · " + displayName;
	}

	/** 1 or 2: the place among its aura's branches (the {@code aura_branch} scoreboard value). */
	public int number() {
		return of(aura).indexOf(this) + 1;
	}

	/** The branches of an aura (empty for auras without any yet). */
	public static List<Branch> of(AuraType aura) {
		List<Branch> out = new ArrayList<>();
		for (Branch b : values()) if (b.aura == aura) out.add(b);
		return out;
	}

	/** The aura id plus ":" and the branch id, what the HUD packet carries ("fire", "shadow:dark"). */
	public static String wire(AuraType aura, Branch branch) {
		return aura == null ? "" : branch == null ? aura.id() : aura.id() + ":" + branch.id();
	}

	public static Branch byId(String id) {
		for (Branch b : values()) if (b.id().equals(id.toLowerCase(Locale.ROOT))) return b;
		return null;
	}

	/** The Mastery level the held aura needs before its branch counts. */
	public static boolean unlocked(AuraPlayerData data) {
		return data.aura != null && !of(data.aura).isEmpty()
				&& data.mastery.getOrDefault(data.aura, 0) >= AuraConfig.BRANCH_MASTERY_REQUIRED;
	}

	/** The branch in force for this player: the pick for the aura held now, only with the Mastery level, else null (base kit). */
	public static Branch current(AuraPlayerData data) {
		if (!AuraConfig.BRANCHES_ENABLED || !unlocked(data)) return null;
		return data.branches.get(data.aura);
	}

	public static Branch current(ServerPlayer player) {
		if (player.getServer() == null) return null;
		return current(AuraState.get(player.getServer()).getOrCreate(player.getUUID()));
	}

	public static boolean is(ServerPlayer player, Branch branch) {
		return current(player) == branch;
	}

	/** An ability's cooldown after the branch's change (before Mastery and Flow). */
	public static int cooldown(ServerPlayer player, AuraAbilities.Ability ability, int ticks) {
		Branch b = current(player);
		if (b == null) return ticks;
		return switch (b) {
			case BLUE_FLAME -> switch (ability) {
				case FIREBALL -> AuraConfig.FIRE_BLUE_FLAME_FIREBALL_COOLDOWN_TICKS;
				case HEAT_WAVE -> AuraConfig.FIRE_BLUE_FLAME_HEAT_WAVE_COOLDOWN_TICKS;
				default -> ticks;
			};
			case MAGMA -> ability == AuraAbilities.Ability.FIREBALL ? AuraConfig.FIRE_MAGMA_FIREBALL_COOLDOWN_TICKS : ticks;
			case PHANTOM -> ability == AuraAbilities.Ability.VANISH || ability == AuraAbilities.Ability.DASH
					? Math.max(1, (int) Math.round(ticks * AuraConfig.PHANTOM_COOLDOWN_FACTOR)) : ticks;
			case RADIANT -> ability == AuraAbilities.Ability.SOLAR_FLASH ? AuraConfig.LIGHT_RADIANT_FLASH_COOLDOWN_TICKS : ticks;
			case VENOMWEAVER -> ability == AuraAbilities.Ability.VERDANT_VIPER ? AuraConfig.VERDANT_VENOM_VIPER_COOLDOWN_TICKS : ticks;
			case DARK, GLACIAL, FROSTBITE, GROVE_WARDEN, BULWARK, TECTONIC, PRISMATIC, SANGUINE, SACRIFICIAL -> ticks;
		};
	}

	/**
	 * Picks (or switches to) a branch of the aura held now. The first pick on an aura is free, a switch costs
	 * BRANCH_SWITCH_COST Aura. Returns the message to show the player.
	 */
	public static net.minecraft.network.chat.Component pick(ServerPlayer player, Branch branch) {
		if (player.getServer() == null) return fail("Not now.");
		AuraState state = AuraState.get(player.getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		String blocker = blocker(data);
		if (blocker != null) return fail(blocker);
		if (com.toast.aurapoints.arena.Arena.in(player)) return fail("Pick your branch outside the arena.");
		if (branch.aura != data.aura) return fail(branch.displayName + " belongs to the " + branch.aura.displayName + " aura.");
		Branch old = data.branches.get(data.aura);
		if (old == branch) return fail("You already walk the " + branch.displayName + " branch.");
		if (old != null) {
			if (!com.toast.aurapoints.AuraCurrency.take(player, AuraConfig.BRANCH_SWITCH_COST)) {
				return fail("Switching branches costs " + AuraConfig.BRANCH_SWITCH_COST + " Aura (you have " + com.toast.aurapoints.AuraCurrency.get(player) + ").");
			}
		}
		data.branches.put(data.aura, branch);
		// a branch with its own kit (Dark) hands over its actives, so the pick is never a shop trip
		if (branch.kit()) for (AuraAbilities.Ability a : branch.abilities()) if (a.active) data.owned.add(a);
		state.markDirty();
		com.toast.aurapoints.network.NetworkHandler.syncAura(player, data);
		com.toast.aurapoints.network.NetworkHandler.syncOwned(player, data);
		var level = player.serverLevel();
		AuraFx.ring(level, player.getX(), player.getY() + 0.1, player.getZ(), 1.0, AuraFx.dust(branch.color.getColor() == null ? 0xFFFFFF : branch.color.getColor(), 1.4f), 24);
		AuraFx.particles(level, net.minecraft.core.particles.ParticleTypes.FLAME, player.getX(), player.getY() + 1.0, player.getZ(), 20, 0.4, 0.6, 0.4, 0.05);
		return net.minecraft.network.chat.Component.literal(old == null ? "Branch chosen: " : "Branch switched: ").withStyle(ChatFormatting.GRAY)
				.append(net.minecraft.network.chat.Component.literal(branch.label()).withStyle(branch.color, ChatFormatting.BOLD));
	}

	private static net.minecraft.network.chat.Component fail(String text) {
		return net.minecraft.network.chat.Component.literal(text).withStyle(ChatFormatting.RED);
	}

	/** Why a player cannot pick yet, or null. */
	public static String blocker(AuraPlayerData data) {
		if (!AuraConfig.BRANCHES_ENABLED) return "Branches are switched off on this server.";
		if (data.aura == null) return "You have no aura yet.";
		if (of(data.aura).isEmpty()) return "The " + data.aura.displayName + " aura has no branches yet.";
		if (!unlocked(data)) return "Branches unlock at " + data.aura.displayName + " Mastery " + Mastery.roman(AuraConfig.BRANCH_MASTERY_REQUIRED) + ".";
		return null;
	}
}
