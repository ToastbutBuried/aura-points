package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;

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

	BLUE_FLAME(AuraType.FIRE, "Blue Flame", ChatFormatting.AQUA, "Precision",
			() -> List.of(
					"Fireball flies " + Math.round((AuraConfig.FIRE_BLUE_FLAME_FIREBALL_SPEED - 1) * 100) + "% faster and pierces the first target it hits.",
					"Fireball cooldown " + (AuraConfig.FIRE_BLUE_FLAME_FIREBALL_COOLDOWN_TICKS / 20) + "s (was " + (AuraConfig.FIREBALL_COOLDOWN_TICKS / 20) + "s).",
					"Scorch: burning targets take " + Math.round(AuraConfig.SCORCH_BONUS * 100) + "% more damage from you.",
					"Fire Bending shots hit for " + AuraAbilities.fmt(AuraConfig.FIRE_BLUE_FLAME_BENDING_DAMAGE_CLOSE) + " close up, "
							+ AuraAbilities.fmt(AuraConfig.FIRE_BLUE_FLAME_BENDING_DAMAGE_FAR) + " far away.",
					"Price: Heat Wave cooldown " + (AuraConfig.FIRE_BLUE_FLAME_HEAT_WAVE_COOLDOWN_TICKS / 20) + "s (was " + (AuraConfig.HEAT_WAVE_COOLDOWN_TICKS / 20) + "s).")),

	MAGMA(AuraType.FIRE, "Magma", ChatFormatting.GOLD, "Zone control",
			() -> List.of(
					"Fireball hits, Heat Wave and the Inferno Slam leave magma pools that burn and hurt enemies standing in them for "
							+ AuraAbilities.fmt(AuraConfig.FIRE_MAGMA_POOL_TICKS / 20f) + "s.",
					"Standing in your own pool gives you Resistance " + (AuraConfig.FIRE_MAGMA_RESISTANCE_AMPLIFIER + 1) + ".",
					"The pools are fire and heat only: no lava blocks, nothing to clean up.",
					"Price: Fireball cooldown " + (AuraConfig.FIRE_MAGMA_FIREBALL_COOLDOWN_TICKS / 20) + "s (was " + (AuraConfig.FIREBALL_COOLDOWN_TICKS / 20) + "s).")),

	PHANTOM(AuraType.SHADOW, "Phantom", ChatFormatting.LIGHT_PURPLE, "Evasion and tricks",
			() -> List.of(
					"Vanish and Dash cooldowns " + Math.round((1 - AuraConfig.PHANTOM_COOLDOWN_FACTOR) * 100) + "% shorter.",
					"Dash leaves a shadow clone where you started it for " + AuraAbilities.fmt(AuraConfig.PHANTOM_DECOY_TICKS / 20f)
							+ "s: mobs hunting you lose you.",
					"Phantom Step keeps you invisible " + Math.round((AuraConfig.PHANTOM_STEP_INVIS_FACTOR - 1) * 100) + "% longer, Vanish Step "
							+ AuraAbilities.fmt(AuraConfig.PHANTOM_VANISH_STEP_BONUS_TICKS / 20f) + "s longer.")),

	DARK(AuraType.SHADOW, "Dark", ChatFormatting.DARK_PURPLE, "Pull, rift and null",
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
	private final Supplier<List<String>> lines;

	Branch(AuraType aura, String displayName, ChatFormatting color, String role, Supplier<List<String>> lines) {
		this.aura = aura;
		this.displayName = displayName;
		this.color = color;
		this.role = role;
		this.lines = lines;
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

	/** A passive this branch hands out on top of the aura's own (hasAbility asks). */
	public static boolean grants(AuraPlayerData data, AuraAbilities.Ability ability) {
		Branch b = current(data);
		return b == BLUE_FLAME && ability == AuraAbilities.Ability.SCORCH;
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
			case DARK -> ticks;
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
