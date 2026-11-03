package com.toast.aurapoints;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;

/**
 * DEV ONLY, with the showcase world:
 *  - aurapoints.idleshowcase=true: on login, plays every idle animation (BendingAnimPacket.Idle) on the
 *    player, one after another, while DevShowcaseClient saves screenshots of each (run/screenshots/idle_*.png).
 *    The natural idles stay off meanwhile.
 *  - aurapoints.idletest=true: the natural idles for real - the player gets Fire and just stands there;
 *    DevShowcaseClient logs every idle that starts and stops ("[idletest]"), walks a step to cut one short,
 *    then sprints to show the trail.
 */
@EventBusSubscriber(modid = AuraPoints.MOD_ID)
public final class DevIdleGallery {

	private DevIdleGallery() {
	}

	public static boolean on() {
		return Boolean.getBoolean("aurapoints.idleshowcase");
	}

	public static boolean test() {
		return Boolean.getBoolean("aurapoints.idletest");
	}

	/** Ticks between one idle's end and the next one's start. */
	private static final int GAP = 30;

	@SubscribeEvent
	public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!on() && !test() || !(event.getEntity() instanceof ServerPlayer player)) return;
		MinecraftServer server = player.getServer();
		if (server == null) return;
		if (test()) {
			AuraFx.later(server, 20, () -> {
				player.getAbilities().flying = false;
				player.onUpdateAbilities();
				var data = com.toast.aurapoints.data.AuraState.get(server).getOrCreate(player.getUUID());
				data.aura = com.toast.aurapoints.ability.AuraType.FIRE;
				NetworkHandler.syncAura(player, data);
				AuraPoints.LOGGER.info("[idletest] standing still as Fire from server tick {}", server.getTickCount());
			});
			return;
		}
		AuraFx.later(server, 20, () -> {
			player.getAbilities().flying = false;
			player.onUpdateAbilities();
			player.teleportTo(player.serverLevel(), Math.floor(player.getX()) + 0.5, player.getY(), Math.floor(player.getZ()) + 0.5, 0f, 0f);
		});
		play(server, player, 0, 60);
	}

	/** Idle {@code i} in {@code delay} ticks, then the next one after it. */
	private static void play(MinecraftServer server, ServerPlayer player, int i, int delay) {
		BendingAnimPacket.Idle[] all = BendingAnimPacket.Idle.values();
		if (i >= all.length) return;
		// -Daurapoints.idleonly=fire_doodle,wind_hat: just those
		String only = System.getProperty("aurapoints.idleonly", "");
		if (!only.isEmpty() && !java.util.Arrays.asList(only.split(",")).contains(all[i].name().toLowerCase(java.util.Locale.ROOT))) {
			play(server, player, i + 1, delay);
			return;
		}
		AuraFx.later(server, delay, () -> {
			if (!AuraFx.alive(player)) return;
			AuraPoints.LOGGER.info("[idlegallery] {}", all[i]);
			NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_IDLE, i);
			play(server, player, i + 1, all[i].length + GAP);
		});
	}
}
