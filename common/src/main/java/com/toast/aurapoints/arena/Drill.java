package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraStats;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The Training Hall's guided drill (the DRILL kiosk): three quick steps that teach the basics by doing
 * them - use an ability, hit a dummy with it, hit the moving dummy. State is per session only (a drill
 * takes a minute); finishing it counts the stat {@code drills_done} and shows what to try next.
 */
public final class Drill {

	private Drill() {
	}

	public enum Step {
		ABILITY("Use an ability", "Press one of your ability keys (Options > Controls > Aura Points).", "Abilities have a cooldown, shown on your HUD."),
		HIT("Hit a dummy with an ability", "Aim an ability at one of the standing dummies.", "Ability hits show their damage above the dummy."),
		MOVING("Hit the moving dummy", "The patrolling dummy walks back and forth: lead your shot.", "Kiosk MOVING DUMMY sets its speed.");

		final String title, task, tip;

		Step(String title, String task, String tip) {
			this.title = title;
			this.task = task;
			this.tip = tip;
		}
	}

	private static final Map<UUID, Integer> RUNNING = new HashMap<>();

	public static boolean running(ServerPlayer player) {
		return RUNNING.containsKey(player.getUUID());
	}

	/** The index of the step a player is on, or -1. */
	public static int stepOf(ServerPlayer player) {
		return RUNNING.getOrDefault(player.getUUID(), -1);
	}

	static void start(ServerPlayer player) {
		var data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (data.aura == null) {
			player.sendSystemMessage(Component.literal("Roll an aura first (open your Aura Book), then come back for the drill.").withStyle(ChatFormatting.RED));
			return;
		}
		if (RUNNING.containsKey(player.getUUID())) {
			player.sendSystemMessage(Component.literal("Drill stopped.").withStyle(ChatFormatting.GRAY));
			RUNNING.remove(player.getUUID());
			return;
		}
		RUNNING.put(player.getUUID(), 0);
		player.sendSystemMessage(Component.literal("Training drill: 3 quick steps. Click the kiosk again to stop.").withStyle(ChatFormatting.GOLD));
		announce(player);
	}

	private static void announce(ServerPlayer player) {
		int i = stepOf(player);
		if (i < 0) return;
		Step s = Step.values()[i];
		player.sendSystemMessage(Component.literal("Step " + (i + 1) + "/3 - " + s.title + ": ").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(s.task).withStyle(ChatFormatting.GRAY)));
	}

	private static void advance(ServerPlayer player) {
		int i = stepOf(player);
		if (i < 0) return;
		Step done = Step.values()[i];
		player.sendSystemMessage(Component.literal("✔ " + done.title + ". ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(done.tip).withStyle(ChatFormatting.GRAY)));
		AuraFx.sound(player.serverLevel(), player, AuraSounds.EXPERIENCE_ORB_PICKUP.value(), 0.6f, 1.2f);
		if (i + 1 >= Step.values().length) {
			RUNNING.remove(player.getUUID());
			finish(player);
		} else {
			RUNNING.put(player.getUUID(), i + 1);
			announce(player);
		}
	}

	private static void finish(ServerPlayer player) {
		player.awardStat(AuraStats.DRILLS_DONE);
		ArenaFx.title(player, Component.literal("Drill complete").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
				Component.literal("Now try the DPS challenge").withStyle(ChatFormatting.GRAY));
		for (String line : new String[]{
				"Next: a hit leaves an element mark - match it with another aura's hit for a REACTION (Aura Book > Reactions).",
				"Next: at Mastery V pick a BRANCH (Aura Book > your aura). Abilities that leave a zone can team up as a COMBO with an ally.",
				"Next: the DPS CHALLENGE kiosk times 30 s of damage on the dummies."}) {
			player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.AQUA));
		}
	}

	/** From AuraHooks.abilityUsed. */
	public static void onAbilityUsed(ServerPlayer player) {
		if (stepOf(player) == 0) advance(player);
	}

	/** From TrainingHall.onDummyHit: an ability hit a dummy; the last step needs the patrolling one. */
	static void onDummyHit(net.minecraft.world.entity.Entity attacker, boolean abilityHit, boolean patrolling) {
		if (!(attacker instanceof ServerPlayer p) || !abilityHit) return;
		int i = stepOf(p);
		if (i == 1 || (i == 2 && patrolling)) advance(p);
	}

	static void tick(MinecraftServer server) {
		for (Iterator<UUID> it = RUNNING.keySet().iterator(); it.hasNext(); ) {
			ServerPlayer p = server.getPlayerList().getPlayer(it.next());
			if (p == null || !Arena.inTraining(p)) it.remove();
		}
	}

	static void clearAll() {
		RUNNING.clear();
	}
}
