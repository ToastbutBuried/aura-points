package com.toast.aurapoints.shim.neoforge.network;

import com.toast.aurapoints.shim.Platform;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class PacketDistributor {
	private PacketDistributor() {
	}

	public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload, CustomPacketPayload... more) {
		Platform.get().sendToPlayer(player, payload, more);
	}

	public static void sendToPlayersTrackingEntity(Entity entity, CustomPacketPayload payload, CustomPacketPayload... more) {
		Platform.get().sendToTracking(entity, false, payload, more);
	}

	public static void sendToPlayersTrackingEntityAndSelf(Entity entity, CustomPacketPayload payload, CustomPacketPayload... more) {
		Platform.get().sendToTracking(entity, true, payload, more);
	}

	public static void sendToServer(CustomPacketPayload payload, CustomPacketPayload... more) {
		Platform.get().sendToServer(payload, more);
	}
}
