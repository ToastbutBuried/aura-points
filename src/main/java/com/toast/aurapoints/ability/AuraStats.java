package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.data.AuraPlayerData;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Aura Trials quests read:
 *  - custom statistics, one counter per ability ("aurapoints:fireball_hits" ...),
 *    checked by FTB Quests stat tasks and visible in the vanilla Statistics screen;
 *  - the advancement aurapoints:aura/<aura>, granted when a player holds that
 *    aura, which unlocks its section of the chapter (the advancement JSONs
 *    live in the world datapack).
 */
public final class AuraStats {

	private AuraStats() {
	}

	public static final DeferredRegister<ResourceLocation> STATS =
			DeferredRegister.create(Registries.CUSTOM_STAT, AuraPoints.MOD_ID);

	private static final List<ResourceLocation> TENTHS = new ArrayList<>();
	private static final List<ResourceLocation> ALL = new ArrayList<>();

	private static ResourceLocation stat(String name) {
		return stat(name, false);
	}

	/** {@code tenths}: the value is stored x10 and shown with one decimal, like vanilla damage stats. */
	private static ResourceLocation stat(String name, boolean tenths) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, name);
		STATS.register(name, () -> id);
		ALL.add(id);
		if (tenths) TENTHS.add(id);
		return id;
	}

	// Shadow
	public static final ResourceLocation VANISH_USES = stat("vanish_uses");
	public static final ResourceLocation DASH_USES = stat("dash_uses");
	public static final ResourceLocation PHANTOM_STEP_LANDINGS = stat("phantom_step_landings");
	public static final ResourceLocation VANISH_STEPS = stat("vanish_steps");
	public static final ResourceLocation BLOODRUSH_TRIGGERS = stat("bloodrush_triggers");
	// Water
	public static final ResourceLocation SHOCKWAVE_HITS = stat("shockwave_hits");
	public static final ResourceLocation HEALING_TIDE_HEALS = stat("healing_tide_heals");
	public static final ResourceLocation IRON_SKIN_BLOCKED = stat("iron_skin_blocked", true);
	public static final ResourceLocation FLOW_HEALED = stat("flow_healed");
	public static final ResourceLocation ABYSSAL_BUBBLE_USES = stat("abyssal_bubble_uses");
	public static final ResourceLocation ABYSSAL_BUBBLE_HITS = stat("abyssal_bubble_hits");
	// Fire
	public static final ResourceLocation FIREBALL_HITS = stat("fireball_hits");
	public static final ResourceLocation HEAT_WAVE_HITS = stat("heat_wave_hits");
	public static final ResourceLocation BERSERKER_HITS = stat("berserker_hits");
	public static final ResourceLocation EXECUTIONER_HITS = stat("executioner_hits");
	public static final ResourceLocation FIRE_BENDING_SHOTS = stat("fire_bending_shots");
	public static final ResourceLocation FIRE_BENDING_HITS = stat("fire_bending_hits");
	// Wind
	public static final ResourceLocation DOUBLE_JUMPS = stat("double_jumps");
	public static final ResourceLocation SUPER_WIND_CHARGE_USES = stat("super_wind_charge_uses");
	public static final ResourceLocation GALE_RIDER_HITS = stat("gale_rider_hits");
	public static final ResourceLocation FEATHERFALL_LANDINGS = stat("featherfall_landings");

	// Frost
	public static final ResourceLocation ICE_SPIKE_THROWS = stat("ice_spike_throws");
	public static final ResourceLocation ICE_SPIKE_HITS = stat("ice_spike_hits");
	public static final ResourceLocation ICE_SPIKE_FREEZES = stat("ice_spike_freezes");
	public static final ResourceLocation ICE_SHELL_BLOCKED = stat("ice_shell_blocked", true);
	public static final ResourceLocation SHATTER_COMBOS = stat("shatter_combos");
	// Verdant
	public static final ResourceLocation VINE_HOOK_PULLS = stat("vine_hook_pulls");
	public static final ResourceLocation BLOOM_GROWN = stat("bloom_grown");
	public static final ResourceLocation ENTANGLE_CATCHES = stat("entangle_catches");
	public static final ResourceLocation VIPER_BITES = stat("viper_bites");
	// Thunder
	public static final ResourceLocation THUNDERBOLT_THROWS = stat("thunderbolt_throws");
	public static final ResourceLocation THUNDERBOLT_HITS = stat("thunderbolt_hits");
	public static final ResourceLocation THUNDERBOLT_WRATH_HITS = stat("thunderbolt_wrath_hits");
	public static final ResourceLocation CHIDORI_LUNGES = stat("chidori_lunges");
	public static final ResourceLocation CHIDORI_HITS = stat("chidori_hits");

	// Aura-touched mobs killed
	public static final ResourceLocation TOUCHED_KILLS = stat("touched_kills");
	// Arena
	public static final ResourceLocation DUEL_WINS = stat("duel_wins");
	// Wave Survival: waves cleared (all runs) and the best wave reached
	public static final ResourceLocation WAVES_CLEARED = stat("waves_cleared");
	public static final ResourceLocation BEST_WAVE = stat("best_wave");
	/** The best 30-second DPS challenge, in tenths (shown with one decimal). */
	public static final ResourceLocation DPS_BEST = stat("dps_best", true);

	/** Aura Mastery XP, one counter per aura: "aurapoints:mastery_fire" ... */
	private static final java.util.Map<AuraType, ResourceLocation> MASTERY = new java.util.EnumMap<>(AuraType.class);

	static {
		for (AuraType aura : AuraType.values()) MASTERY.put(aura, stat("mastery_" + aura.id()));
	}

	/** Elemental reactions set off, one counter each: "aurapoints:reaction_freeze" ... (ids from Reactions.Reaction.id()). */
	private static final java.util.Map<String, ResourceLocation> REACTIONS = new java.util.HashMap<>();

	static {
		for (String id : List.of("freeze", "conduct", "firestorm", "toxic_cloud", "steam", "melt", "overgrowth", "storm")) REACTIONS.put(id, stat("reaction_" + id));
	}

	/** Team combos pulled off, one counter each: "aurapoints:combo_fire_tornado" ... (ids from Combos.Combo.id()). */
	private static final java.util.Map<String, ResourceLocation> COMBOS = new java.util.HashMap<>();

	static {
		for (String id : List.of("fire_tornado", "electrified_bubble", "toxic_gale", "glacier", "magma_field", "boiling_bubble", "prism_burst")) COMBOS.put(id, stat("combo_" + id));
	}

	/** Every team combo counter. */
	public static java.util.Collection<ResourceLocation> comboStats() {
		return java.util.Collections.unmodifiableCollection(COMBOS.values());
	}

	/** The counter of a team combo, or null. */
	public static ResourceLocation combo(String id) {
		return COMBOS.get(id);
	}

	/** Every reaction counter. */
	public static java.util.Collection<ResourceLocation> reactionStats() {
		return java.util.Collections.unmodifiableCollection(REACTIONS.values());
	}

	/** The counter of a reaction, or null. */
	public static ResourceLocation reaction(String id) {
		return REACTIONS.get(id);
	}

	/** The auras whose trials are a plain use count per active ("aurapoints:pillar_lift_uses" ...), fed by AuraHooks.abilityUsed. */
	private static final java.util.Set<AuraType> USE_COUNTED = java.util.EnumSet.of(AuraType.RESONANCE, AuraType.EARTH, AuraType.LIGHT, AuraType.BLOOD, AuraType.VOID);
	private static final java.util.Map<AuraAbilities.Ability, ResourceLocation> USES = new java.util.EnumMap<>(AuraAbilities.Ability.class);

	static {
		for (AuraType aura : USE_COUNTED) for (AuraAbilities.Ability a : aura.abilities)
			if (a.active) USES.put(a, stat(a.name().toLowerCase(java.util.Locale.ROOT) + "_uses"));
	}

	/** An active was used: counts it if its aura is one of the use-counted ones. */
	public static void used(ServerPlayer player, AuraAbilities.Ability ability) {
		ResourceLocation stat = USES.get(ability);
		if (stat != null) add(player, stat, 1);
	}

	/** The use counter of an active, or null if its aura counts something else. */
	public static ResourceLocation uses(AuraAbilities.Ability ability) {
		return USES.get(ability);
	}

	/** Every stat this mod registers (trial counters and mastery XP). */
	public static List<ResourceLocation> all() {
		return java.util.Collections.unmodifiableList(ALL);
	}

	/** True for a stat stored x10 (dps_best, iron_skin_blocked, ice_shell_blocked). */
	public static boolean isTenths(ResourceLocation stat) {
		return TENTHS.contains(stat);
	}

	/** True for an aura's mastery XP counter (not a trial counter). */
	public static boolean isMastery(ResourceLocation stat) {
		return MASTERY.containsValue(stat);
	}

	/** The aura's mastery XP stat, or null for none. */
	public static ResourceLocation mastery(AuraType aura) {
		return MASTERY.get(aura);
	}

	/** Call from FMLCommonSetupEvent (enqueueWork): creates the Stat objects with their formatters. */
	public static void createStats() {
		for (ResourceLocation id : ALL) {
			Stats.CUSTOM.get(id, TENTHS.contains(id) ? StatFormatter.DIVIDE_BY_TEN : StatFormatter.DEFAULT);
		}
	}

	/**
	 * Counts an ability use for the Aura Trials. Nothing counts while cooldowns are
	 * switched off (/aura cooldowns off) or in the arena - spamming abilities must not finish quests.
	 */
	public static void add(ServerPlayer player, ResourceLocation stat, int amount) {
		if (amount <= 0 || AuraAbilities.progressPaused(player)) return;
		player.awardStat(stat, amount);
		// every count is mastery XP for the aura held now (a tenths stat gives a tenth as much)
		Mastery.addXp(player, TENTHS.contains(stat) ? amount / 10 : amount);
	}

	/**
	 * Admin reset for the Aura Trials and Feats: zeroes every ability counter and revokes
	 * every aurapoints:* advancement (stunts, feats, aura gates), then re-grants the gate
	 * of the aura the player holds now. FTB Quests progress itself is reset separately
	 * (/ftbquests change_progress), otherwise the old counts would complete it again.
	 */
	public static int resetProgress(ServerPlayer player, AuraPlayerData data) {
		return resetProgress(player, data, true);
	}

	/** As above; {@code mastery} false keeps the Mastery XP counters. */
	public static int resetProgress(ServerPlayer player, AuraPlayerData data, boolean mastery) {
		for (ResourceLocation id : ALL) {
			if (mastery || !MASTERY.containsValue(id)) player.getStats().setValue(player, Stats.CUSTOM.get(id), 0);
		}
		int revoked = 0;
		MinecraftServer server = player.getServer();
		if (server != null) {
			for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
				if (!holder.id().getNamespace().equals(AuraPoints.MOD_ID)) continue;
				var progress = player.getAdvancements().getOrStartProgress(holder);
				if (!progress.hasProgress()) continue;
				for (String criterion : progress.getCompletedCriteria()) player.getAdvancements().revoke(holder, criterion);
				revoked++;
			}
		}
		grantAuraAdvancement(player, data);
		return revoked;
	}

	/** Grants aurapoints:aura/<aura> if the player holds an aura. Safe to call often. */
	public static void grantAuraAdvancement(ServerPlayer player, AuraPlayerData data) {
		MinecraftServer server = player.getServer();
		if (server == null || data.aura == null) return;
		AdvancementHolder holder = server.getAdvancements().get(
				ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "aura/" + data.aura.id()));
		if (holder == null) {
			AuraPoints.LOGGER.warn("Advancement aurapoints:aura/{} is missing (a datapack removed it?)", data.aura.id());
			return;
		}
		player.getAdvancements().award(holder, "has");
	}
}
