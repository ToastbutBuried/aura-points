package com.toast.aurapoints;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * The Aura currency. It is a plain scoreboard objective
 * ({@link AuraConfig#CURRENCY_OBJECTIVE}) so quests, bosses and loot tables can
 * pay it with "scoreboard players add @s aura_points N" - no mod code needed.
 * The mod only reads it and takes from it.
 */
public final class AuraCurrency {

	private AuraCurrency() {
	}

	/** The currency objective, created once if missing and reused after that. */
	public static Objective objective(MinecraftServer server) {
		ServerScoreboard scoreboard = server.getScoreboard();
		Objective objective = scoreboard.getObjective(AuraConfig.CURRENCY_OBJECTIVE);
		if (objective == null) {
			objective = scoreboard.addObjective(AuraConfig.CURRENCY_OBJECTIVE, ObjectiveCriteria.DUMMY,
					Component.literal("Aura"), ObjectiveCriteria.RenderType.INTEGER, true, null);
		}
		return objective;
	}

	public static int get(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) return 0;
		return server.getScoreboard().getOrCreatePlayerScore(player, objective(server)).get();
	}

	public static void set(ServerPlayer player, int amount) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		server.getScoreboard().getOrCreatePlayerScore(player, objective(server)).set(amount);
	}

	public static void add(ServerPlayer player, int amount) {
		// saturating: a huge /aura give can't wrap a balance around to negative
		set(player, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) get(player) + amount)));
	}

	/** Takes {@code amount}. Returns false (and takes nothing) if the player has too little. */
	public static boolean take(ServerPlayer player, int amount) {
		int balance = get(player);
		if (balance < amount) return false;
		set(player, balance - amount);
		return true;
	}

	/** Aura price of the next skill point for this player. */
	public static int nextSkillPointCost(AuraPlayerData data) {
		return AuraConfig.SKILL_POINT_BASE_COST + data.skillPointsBought * AuraConfig.SKILL_POINT_COST_STEP;
	}
}
