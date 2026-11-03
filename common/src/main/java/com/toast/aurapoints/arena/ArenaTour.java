package com.toast.aurapoints.arena;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraFx;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;

/**
 * DEV ONLY (system property aurapoints.arenatour=true, with the showcase world): on login, rebuilds the
 * arena and flies the player round it while DevShowcaseClient saves a screenshot at each SHOTS tick
 * (run/screenshots/arena_*.png): the hub, an NPC, the leaderboards, the island from below, the hall
 * and ring arrivals - then, with aurapoints.devtest too, it starts the scripted duel (/auradev duel)
 * and spectates it.
 */
@EventBusSubscriber(modid = AuraPoints.MOD_ID)
public final class ArenaTour {

	private ArenaTour() {
	}

	public static final int START = 40, TICKS_PER_VIEW = 80;

	public static boolean on() {
		return Boolean.getBoolean("aurapoints.arenatour");
	}

	/** {x, y, z, yaw, pitch}, one every TICKS_PER_VIEW. */
	private static final double[][] VIEWS = {
			{0.5, 80, 24, 180, 24},          // the hub: NPC arc, beams, title, core
			{0.5, 66.8, -8.5, 180, -4},      // the Duels NPC up close
			{0.5, 68.5, 2, 0, 12},           // the fountain and the leaderboards
			{-44, 58, 6, -100, -12},         // the hub's island from below
			{0.5, 66.6, Arena.TRAIN_Z0 + 6.5, 160, 4},              // the hall's foyer and its way back
			{-2, 66.6, Arena.RING_Z + Arena.RING_R + 7, 210, 6},    // the ring's arrival and its way back
			{-30, 72, Arena.RING_Z + 30, 135, 22},                   // the stands, a wave gate, the live board
			{0.5, 70, Arena.SPAR_Z - 12, 0, 25},                     // the Spar Pit, the range behind it
			{-30, 66, -60, -150, -4},                                // islets on the way to the ring, its beam
	};
	private static final int DUEL_AT = START + VIEWS.length * TICKS_PER_VIEW;

	/** Client ticks (after login) to save screenshots at. */
	public static final int[] SHOTS = new int[VIEWS.length + 2];

	static {
		for (int i = 0; i < VIEWS.length; i++) SHOTS[i] = START + i * TICKS_PER_VIEW + 70;
		SHOTS[VIEWS.length] = DUEL_AT + 70;
		SHOTS[VIEWS.length + 1] = DUEL_AT + 240;
	}

	@SubscribeEvent
	public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!on() || !(event.getEntity() instanceof ServerPlayer player)) return;
		MinecraftServer server = player.getServer();
		if (server == null) return;
		// fresh from the current code (also tries the rebuild's clean-up of the last build)
		AuraFx.later(server, 5, () -> ArenaBuilder.build(Arena.level(server)));
		AuraFx.later(server, START - 10, () -> Arena.enter(player));
		for (int i = 0; i < VIEWS.length; i++) hold(server, player, VIEWS[i], START + i * TICKS_PER_VIEW, TICKS_PER_VIEW);
		// the scripted duel, watched from above
		if (Boolean.getBoolean("aurapoints.devtest")) {
			AuraFx.later(server, DUEL_AT, () -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "auradev duel"));
			AuraFx.later(server, DUEL_AT + 15, () -> Spectate.start(player));
		}
	}

	/** At {@code v} from tick {@code at} for {@code ticks} (put back every 10 ticks: a creative flyer drifts down otherwise). */
	private static void hold(MinecraftServer server, ServerPlayer player, double[] v, int at, int ticks) {
		for (int t = 0; t < ticks; t += 10) {
			final boolean first = t == 0;
			AuraFx.later(server, at + t, () -> {
				player.teleportTo(Arena.level(server), v[0], v[1], v[2], (float) v[3], (float) v[4]);
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				if (first) AuraPoints.LOGGER.info("[arenatour] view at {} {} {}", v[0], v[1], v[2]);
			});
		}
	}
}
