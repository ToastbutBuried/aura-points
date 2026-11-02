package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent client -> server when a Frost player swings at nothing (only the client knows about a
 * miss). It breaks their Shatter combo. Carries no data.
 */
public record SwingMissPacket() implements CustomPacketPayload {

	public static final Type<SwingMissPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "swing_miss"));

	public static final StreamCodec<ByteBuf, SwingMissPacket> STREAM_CODEC =
			StreamCodec.unit(new SwingMissPacket());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
