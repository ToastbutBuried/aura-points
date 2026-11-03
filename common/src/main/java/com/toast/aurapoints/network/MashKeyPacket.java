package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: an entangled player pressed a movement key ({@code key} 0 W, 1 A, 2 S, 3 D) while mashing free. */
public record MashKeyPacket(int key) implements CustomPacketPayload {

	public static final Type<MashKeyPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "mash_key"));

	public static final StreamCodec<ByteBuf, MashKeyPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, MashKeyPacket::key,
			MashKeyPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
