package com.toast.aurapoints;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.IceSpike;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;

/**
 * DEV ONLY (system property aurapoints.icetest=true, set by gradle runClient -Picetest): on login, makes the
 * player Frost and keeps an Ice Spike charging, so the shard in the hand can be screenshotted in third person
 * (the camera is switched by DevIceTestClient).
 */
@EventBusSubscriber(modid = AuraPoints.MOD_ID)
public final class DevIceTest {

	private DevIceTest() {
	}

	public static boolean on() {
		return Boolean.getBoolean("aurapoints.icetest");
	}

	@SubscribeEvent
	public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!on() || !(event.getEntity() instanceof ServerPlayer player)) return;
		MinecraftServer server = player.getServer();
		AuraFx.later(server, 40, () -> {
			com.toast.aurapoints.config.AuraConfig.ICE_SPIKE_MAX_HOLD_TICKS = Integer.MAX_VALUE; // held until the client closes
			ServerLevel level = player.serverLevel();
			level.setDayTime(6000);
			player.setGameMode(GameType.CREATIVE);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
			player.teleportTo(level, 0.5, y, 0.5, 0f, 0f);
			AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
			data.aura = AuraType.FROST;
			data.owned.addAll(AuraType.FROST.keySlots());
			NetworkHandler.syncAura(player, data);
			NetworkHandler.syncOwned(player, data);
			// a side camera for DevIceTestClient: 3 blocks to the player's right, looking at them
			net.minecraft.world.entity.decoration.ArmorStand cam = new net.minecraft.world.entity.decoration.ArmorStand(level, -2.5, y + 0.2, 0.7);
			cam.setYRot(-90f);
			cam.setYHeadRot(-90f);
			cam.setInvisible(true);
			cam.setNoGravity(true);
			cam.setCustomName(net.minecraft.network.chat.Component.literal("aurapoints_icecam")); // names sync to the client, tags don't
			level.addFreshEntity(cam);
			for (int t = 20; t < 2400; t += 10) {
				AuraFx.later(server, t, () -> {
					if (!IceSpike.isCharging(player)) AuraPoints.LOGGER.info("[icetest] press {}", IceSpike.press(player, server.getTickCount()));
				});
			}
		});
	}
}
