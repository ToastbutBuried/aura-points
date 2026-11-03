package com.toast.aurapoints.fabric;

import com.toast.aurapoints.shim.neoforge.network.handling.IPayloadContext;
import com.toast.aurapoints.shim.neoforge.network.handling.IPayloadHandler;
import com.toast.aurapoints.shim.neoforge.network.registration.PayloadRegistrar;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

import java.util.concurrent.CompletableFuture;

/**
 * Play packets on Fabric. Types register on both sides; client handlers only on the client (ClientNet). Fabric already
 * runs handlers on the main thread, so enqueueWork just runs the work.
 */
public final class FabricPayloads implements PayloadRegistrar {
	public static IPayloadContext context(Player player) {
		return new IPayloadContext() {
			@Override
			public Player player() {
				return player;
			}

			@Override
			public CompletableFuture<Void> enqueueWork(Runnable work) {
				work.run();
				return CompletableFuture.completedFuture(null);
			}
		};
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends CustomPacketPayload> PayloadRegistrar playToServer(CustomPacketPayload.Type<T> type,
			StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
		PayloadTypeRegistry.playC2S().register(type, (StreamCodec<RegistryFriendlyByteBuf, T>) codec);
		ServerPlayNetworking.registerGlobalReceiver(type, (payload, ctx) -> handler.handle(payload, context(ctx.player())));
		return this;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends CustomPacketPayload> PayloadRegistrar playToClient(CustomPacketPayload.Type<T> type,
			StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
		PayloadTypeRegistry.playS2C().register(type, (StreamCodec<RegistryFriendlyByteBuf, T>) codec);
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			com.toast.aurapoints.fabric.client.ClientNet.receive(type, handler);
		}
		return this;
	}
}
