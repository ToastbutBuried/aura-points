package com.toast.aurapoints.shim.neoforge.network.registration;

import com.toast.aurapoints.shim.neoforge.network.handling.IPayloadHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Play-phase packets. Client handlers are only called (and their classes only loaded) on the client. */
public interface PayloadRegistrar {
	<T extends CustomPacketPayload> PayloadRegistrar playToServer(CustomPacketPayload.Type<T> type,
			StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler);

	<T extends CustomPacketPayload> PayloadRegistrar playToClient(CustomPacketPayload.Type<T> type,
			StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler);
}
