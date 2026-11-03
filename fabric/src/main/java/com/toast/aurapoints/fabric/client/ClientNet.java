package com.toast.aurapoints.fabric.client;

import com.toast.aurapoints.fabric.FabricPayloads;
import com.toast.aurapoints.shim.neoforge.network.handling.IPayloadHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client half of the packets (only loaded on the client). */
public final class ClientNet {
	private ClientNet() {
	}

	public static <T extends CustomPacketPayload> void receive(CustomPacketPayload.Type<T> type, IPayloadHandler<T> handler) {
		ClientPlayNetworking.registerGlobalReceiver(type, (payload, ctx) -> handler.handle(payload, FabricPayloads.context(ctx.player())));
	}

	public static void send(CustomPacketPayload payload, CustomPacketPayload... more) {
		ClientPlayNetworking.send(payload);
		for (CustomPacketPayload p : more) ClientPlayNetworking.send(p);
	}
}
