package com.toast.aurapoints.neoforge;

import com.toast.aurapoints.shim.neoforge.network.handling.IPayloadContext;
import com.toast.aurapoints.shim.neoforge.network.handling.IPayloadHandler;
import com.toast.aurapoints.shim.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

import java.util.concurrent.CompletableFuture;

/** The shim registrar on top of NeoForge's own (same channel version, same handlers). */
final class NeoPayloads implements PayloadRegistrar {
	private final net.neoforged.neoforge.network.registration.PayloadRegistrar real;

	NeoPayloads(net.neoforged.neoforge.network.registration.PayloadRegistrar real) {
		this.real = real;
	}

	private static <T extends CustomPacketPayload> net.neoforged.neoforge.network.handling.IPayloadHandler<T> wrap(IPayloadHandler<T> handler) {
		return (payload, ctx) -> handler.handle(payload, new IPayloadContext() {
			@Override
			public Player player() {
				return ctx.player();
			}

			@Override
			public CompletableFuture<Void> enqueueWork(Runnable work) {
				return ctx.enqueueWork(work);
			}
		});
	}

	@Override
	public <T extends CustomPacketPayload> PayloadRegistrar playToServer(CustomPacketPayload.Type<T> type,
			StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
		real.playToServer(type, codec, wrap(handler));
		return this;
	}

	@Override
	public <T extends CustomPacketPayload> PayloadRegistrar playToClient(CustomPacketPayload.Type<T> type,
			StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
		real.playToClient(type, codec, wrap(handler));
		return this;
	}
}
