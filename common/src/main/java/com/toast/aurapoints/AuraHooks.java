package com.toast.aurapoints;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.data.AuraPlayerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What datapacks can build on, without knowing anything about the mod:
 *  - scoreboard {@code aura_type}: each player's aura, 0 none, then 1 + its place in AuraType (1 Shadow, 2 Water, 3 Fire,
 *    4 Wind, 5 Frost, 6 Verdant, 7 Thunder, 8 Resonance, 9 Earth, 10 Light, 11 Blood, 12 Void; always current);
 *  - scoreboards {@code itemuse} / {@code itemsecondary} / {@code itemtertiary}: 1 while the player holds the "Item Use" /
 *    "Item Secondary" / "Item Tertiary" key, 0 otherwise - for datapack items;
 *  - scoreboards {@code aura_cd_<ability>} (e.g. aura_cd_fireball): ticks left on that ability's cooldown, 0 when ready.
 *    Writable: /scoreboard players set <player> aura_cd_fireball 100 puts Fireball on a 5s cooldown, 0 makes it ready
 *    (the HUD follows). Only the main cooldown - not the elytra flight set or Wind's launch;
 *  - function tag {@code #aurapoints:left_click}: runs as (and at) the player on every left click - at air, a block or
 *    an entity;
 *  - scoreboards {@code aura_mastery_<aura>} (e.g. aura_mastery_fire): the player's Aura Mastery level (0-5) for every
 *    aura, and {@code aura_mastery}: the level of the aura they hold now (always current, written by the mod - change
 *    levels with /aura mastery set);
 *  - function tags run as (and at) the player: {@code #aurapoints:ability/<ability>} when they use an
 *    ability (e.g. #aurapoints:ability/fireball), {@code #aurapoints:aura_changed} when their aura changes.
 * The objectives are created on server start if missing.
 */
public final class AuraHooks {

	private AuraHooks() {
	}

	public static final String AURA_OBJECTIVE = "aura_type";
	public static final String[] ITEM_OBJECTIVES = {"itemuse", "itemsecondary", "itemtertiary"};
	public static final String MASTERY_OBJECTIVE = "aura_mastery";
	/** aura_branch: the branch in force for the held aura, 0 none, else its place (1 or 2) among that aura's branches. */
	public static final String BRANCH_OBJECTIVE = "aura_branch";

	public static String masteryObjective(AuraType aura) {
		return MASTERY_OBJECTIVE + "_" + aura.id();
	}

	private static Objective objective(MinecraftServer server, String name) {
		ServerScoreboard scoreboard = server.getScoreboard();
		Objective objective = scoreboard.getObjective(name);
		if (objective == null) {
			objective = scoreboard.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(name),
					ObjectiveCriteria.RenderType.INTEGER, true, null);
		}
		return objective;
	}

	public static void createObjectives(MinecraftServer server) {
		objective(server, AURA_OBJECTIVE);
		for (String name : ITEM_OBJECTIVES) objective(server, name);
		objective(server, MASTERY_OBJECTIVE);
		objective(server, BRANCH_OBJECTIVE);
		for (AuraType aura : AuraType.values()) objective(server, masteryObjective(aura));
		for (Ability ability : Ability.values()) if (ability.active) objective(server, cooldownObjective(ability));
	}

	/** aura_cd_<ability> per ability, built once (asked for every tick for every player). */
	private static final EnumMap<Ability, String> CD_NAMES = new EnumMap<>(Ability.class);

	static {
		for (Ability a : Ability.values()) CD_NAMES.put(a, "aura_cd_" + a.name().toLowerCase(Locale.ROOT));
	}

	public static String cooldownObjective(Ability ability) {
		return CD_NAMES.get(ability);
	}

	/** What we last wrote into each aura_cd_* score, so a different value there means a datapack or command changed it. */
	private static final Map<UUID, EnumMap<Ability, Integer>> WRITTEN_COOLDOWNS = new HashMap<>();

	/**
	 * Every tick: a changed aura_cd_* score becomes the new cooldown, then every score is set to the ticks left.
	 * Scores at 0 are only written for players who already have one, so idle players don't fill the scoreboard.
	 */
	public static void syncCooldowns(ServerPlayer player, long now) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		ServerScoreboard scoreboard = server.getScoreboard();
		EnumMap<Ability, Integer> written = WRITTEN_COOLDOWNS.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(Ability.class));
		for (Ability ability : Ability.values()) {
			if (!ability.active) continue;
			Objective objective = objective(server, cooldownObjective(ability));
			var info = scoreboard.getPlayerScoreInfo(player, objective);
			int current = info == null ? 0 : info.value();
			Integer last = written.get(ability);
			if (last != null && current != last) AuraAbilities.setCooldown(player, ability, current, now);
			int left = AuraAbilities.cooldownTicksLeft(player, ability, now);
			if (info == null ? left != 0 : current != left) scoreboard.getOrCreatePlayerScore(player, objective).set(left);
			written.put(ability, left);
		}
	}

	public static void forgetCooldowns(ServerPlayer player) {
		WRITTEN_COOLDOWNS.remove(player.getUUID());
	}

	/** 0 for none, else 1 + the aura's place in the list (Shadow 1 ... Void 12). */
	public static int auraNumber(AuraType aura) {
		return aura == null ? 0 : aura.ordinal() + 1;
	}

	/** Writes the player's aura into aura_type; runs #aurapoints:aura_changed if it is a change. */
	public static void syncAura(ServerPlayer player, AuraPlayerData data) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		var score = server.getScoreboard().getOrCreatePlayerScore(player, objective(server, AURA_OBJECTIVE));
		int now = auraNumber(data.aura);
		boolean changed = score.get() != now;
		score.set(now);
		syncMastery(player, data);
		if (changed) runTag(player, "aura_changed");
	}

	/** Writes the mastery levels into aura_mastery_<aura> and aura_mastery (the held aura's). */
	public static void syncMastery(ServerPlayer player, AuraPlayerData data) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		ServerScoreboard scoreboard = server.getScoreboard();
		for (AuraType aura : AuraType.values()) {
			scoreboard.getOrCreatePlayerScore(player, objective(server, masteryObjective(aura))).set(data.mastery.getOrDefault(aura, 0));
		}
		scoreboard.getOrCreatePlayerScore(player, objective(server, MASTERY_OBJECTIVE)).set(data.aura == null ? 0 : data.mastery.getOrDefault(data.aura, 0));
		var branch = com.toast.aurapoints.ability.Branch.current(data);
		scoreboard.getOrCreatePlayerScore(player, objective(server, BRANCH_OBJECTIVE)).set(branch == null ? 0 : branch.number());
	}

	/** Item key {@code index} (0 itemuse, 1 itemsecondary, 2 itemtertiary) held or let go. */
	public static void setItemKey(ServerPlayer player, int index, boolean down) {
		MinecraftServer server = player.getServer();
		if (server == null || index < 0 || index >= ITEM_OBJECTIVES.length) return;
		server.getScoreboard().getOrCreatePlayerScore(player, objective(server, ITEM_OBJECTIVES[index])).set(down ? 1 : 0);
	}

	public static void clearItemKeys(ServerPlayer player) {
		for (int i = 0; i < ITEM_OBJECTIVES.length; i++) setItemKey(player, i, false);
	}

	/** An ability was used (its cooldown just started). */
	public static void abilityUsed(ServerPlayer player, Ability ability) {
		com.toast.aurapoints.ability.IdleAnimations.markActive(player);
		com.toast.aurapoints.ability.Mastery.onAbilityUsed(player);
		com.toast.aurapoints.ability.AuraStats.used(player, ability);
		if (com.toast.aurapoints.ability.AuraAbilities.cooldownsDisabled(player)) com.toast.aurapoints.arena.TrainingHall.markFreeUse(player);
		// the arena's free cooldowns mustn't feed datapack rewards
		if (!com.toast.aurapoints.arena.Arena.in(player)) runTag(player, "ability/" + ability.name().toLowerCase(Locale.ROOT));
		com.toast.aurapoints.ability.Noise.add(player, com.toast.aurapoints.config.AuraConfig.NOISE_ABILITY);
	}

	/** A left click (M1): runs #aurapoints:left_click. */
	public static void m1(ServerPlayer player) {
		runTag(player, "left_click");
	}

	/** Runs every function in #aurapoints:{@code path} as and at the player (does nothing if the tag is empty). */
	private static void runTag(ServerPlayer player, String path) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		var functions = server.getFunctions();
		var tag = functions.getTag(ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, path));
		if (tag.isEmpty()) return;
		var source = player.createCommandSourceStack().withSuppressedOutput().withPermission(2);
		for (var function : tag) {
			try {
				functions.execute(function, source);
			} catch (Exception e) {
				AuraPoints.LOGGER.warn("Datapack hook #{}:{} failed", AuraPoints.MOD_ID, path, e);
			}
		}
	}
}
