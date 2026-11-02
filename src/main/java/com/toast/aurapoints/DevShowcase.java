package com.toast.aurapoints;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.FxPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * DEV ONLY (system property aurapoints.showcase=true, never set on a real server): on login, plays every
 * HUD and animation effect on a timer, so they can be looked at (and screenshotted) without pressing keys.
 */
@EventBusSubscriber(modid = AuraPoints.MOD_ID)
final class DevShowcase {

	private DevShowcase() {
	}

	static boolean on() {
		return Boolean.getBoolean("aurapoints.showcase");
	}

	/** {kind, variant} of every gesture, in the order they're shown. */
	private static final int[][] GESTURES = {
			{BendingAnimPacket.GESTURE_SHOCKWAVE, 0}, {BendingAnimPacket.GESTURE_FIREBALL, 0}, {BendingAnimPacket.GESTURE_DASH, 0},
			{BendingAnimPacket.GESTURE_ENTANGLE, 0}, {BendingAnimPacket.GESTURE_VIPER, 0}, {BendingAnimPacket.GESTURE_WINTERS_GATE, 0},
			{BendingAnimPacket.GESTURE_MAELSTROM, 0}, {BendingAnimPacket.GESTURE_STORM_EYE, 0}, {BendingAnimPacket.GESTURE_PLANT, 0},
			{BendingAnimPacket.GESTURE_PLANT, 1}, {BendingAnimPacket.GESTURE_GRASP, 0}, {BendingAnimPacket.GESTURE_THUNDERBOLT, 2},
			{BendingAnimPacket.GESTURE_PHANTOM_STEP, 0}, {BendingAnimPacket.GESTURE_HEAT_WAVE, 0}, {BendingAnimPacket.GESTURE_CHIDORI, 0},
	};

	@SubscribeEvent
	public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!on() || !(event.getEntity() instanceof ServerPlayer player)) return;
		MinecraftServer server = player.getServer();
		if (server == null) return;
		AuraFx.later(server, 20, () -> {
			AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
			data.aura = AuraType.FIRE;
			data.owned.addAll(AuraType.FIRE.keySlots());
			NetworkHandler.syncAura(player, data);
			NetworkHandler.syncOwned(player, data);
			server.overworld().setDayTime(6000);
			AuraPoints.LOGGER.info("[showcase] start");
		});
		// real abilities, to see their (batched) particles arrive
		AuraFx.later(server, 30, () -> AuraAbilities.tryShockwave(player, server.getTickCount()));
		AuraFx.later(server, 48, () -> AuraAbilities.tryHeatWave(player, server.getTickCount()));
		// the reel
		AuraFx.later(server, 40, () -> NetworkHandler.sendFx(player, FxPacket.ROLL, 32, "fire,shadow,water,fire,wind,frost,verdant,thunder"));
		// cooldowns: sweeping, about to end, the not-ready shake can't be faked server-side
		AuraFx.later(server, 60, () -> {
			NetworkHandler.sendCooldown(player, AuraAbilities.Ability.FIREBALL, 80);
			NetworkHandler.sendCooldown(player, AuraAbilities.Ability.HEAT_WAVE, 200);
			NetworkHandler.sendCooldown(player, AuraAbilities.Ability.FIRE_BENDING, 400);
		});
		AuraFx.later(server, 110, () -> {
			NetworkHandler.sendFx(player, FxPacket.STUNT, 0, "flame_lance");
			NetworkHandler.sendFx(player, FxPacket.POINTS, 20, "420");
			NetworkHandler.sendFx(player, FxPacket.HIT, 0, "");
		});
		AuraFx.later(server, 125, () -> NetworkHandler.sendFx(player, FxPacket.HIT, 1, ""));
		// the charge bar filling up
		for (int t = 0; t <= 60; t += 2) {
			final float c = t / 60f;
			AuraFx.later(server, 140 + t, () -> NetworkHandler.sendFireBending(player, true, c, 0.5f));
		}
		AuraFx.later(server, 220, () -> NetworkHandler.sendFireBending(player, false, 0, 0.5f));
		// the screen edges
		AuraFx.later(server, 230, () -> NetworkHandler.sendFrozen(player, 50));
		AuraFx.later(server, 300, () -> PacketDistributor.sendToPlayer(player, new com.toast.aurapoints.network.RootedPacket(true, true)));
		AuraFx.later(server, 360, () -> PacketDistributor.sendToPlayer(player, new com.toast.aurapoints.network.RootedPacket(false, false)));
		AuraFx.later(server, 370, () -> NetworkHandler.sendFx(player, FxPacket.STUNNED, 50, ""));
		AuraFx.later(server, 430, () -> NetworkHandler.sendFx(player, FxPacket.LIFESTEAL, 40, ""));
		// every gesture, one after the other, then the held poses
		int at = 460;
		for (int[] g : GESTURES) {
			AuraFx.later(server, at, () -> {
				AuraPoints.LOGGER.info("[showcase] gesture {} {}", g[0], g[1]);
				NetworkHandler.sendGesture(player, g[0], g[1]);
			});
			at += 30;
		}
		for (byte held : new byte[]{BendingAnimPacket.VIPER_STEER, BendingAnimPacket.ICE_AIM, BendingAnimPacket.CHIDORI_HOLD,
				BendingAnimPacket.BUBBLE_HOLD, BendingAnimPacket.MJOLNIR_FLY}) {
			AuraFx.later(server, at, () -> {
				AuraPoints.LOGGER.info("[showcase] held {}", held);
				NetworkHandler.sendBendAnim(player, held);
			});
			at += 30;
		}
		AuraFx.later(server, at, () -> {
			NetworkHandler.sendBendAnim(player, BendingAnimPacket.SLAM, 14, 10);
			AuraPoints.LOGGER.info("[showcase] slam");
		});
		AuraFx.later(server, at + 40, () -> {
			NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
			AuraPoints.LOGGER.info("[showcase] done");
		});
	}
}
