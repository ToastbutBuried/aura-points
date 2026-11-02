package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: you are being carried by a Vine Hook for {@code ticks} ticks. The server moves you
 * every tick; your client blends between those spots so the camera glides instead of jumping, and your
 * movement keys rest meanwhile (client/CarriedSmoothing).
 */
public record CarriedPacket(int ticks) implements CustomPacketPayload {

	public static final Type<CarriedPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "carried"));

	public static final StreamCodec<ByteBuf, CarriedPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, CarriedPacket::ticks,
			CarriedPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
