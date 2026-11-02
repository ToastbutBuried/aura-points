package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client, every tick while this player is inside someone else's Abyssal Bubble:
 * where its middle is and how hard it pulls. The client moves its own player (smooth, no
 * rubber-banding) and stops by itself a few ticks after these stop coming.
 */
public record BubblePullPacket(double centerX, double centerZ, float pull, float outwardDrag) implements CustomPacketPayload {

	public static final Type<BubblePullPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "bubble_pull"));

	public static final StreamCodec<ByteBuf, BubblePullPacket> STREAM_CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeDouble(p.centerX());
				buf.writeDouble(p.centerZ());
				buf.writeFloat(p.pull());
				buf.writeFloat(p.outwardDrag());
			},
			buf -> new BubblePullPacket(buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
