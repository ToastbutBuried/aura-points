package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: the Abyssal Bubble opened ({@code active}) or ended, for the holder only.
 * Carries where they looked when it opened and the shake settings (degrees), so the client
 * can shake the camera and draw the stability bar by itself.
 */
public record BubblePacket(boolean active, float startYaw, float startPitch, float shakeStart, float shakePerSecond,
						   float breakDegrees, float maxSeconds) implements CustomPacketPayload {

	public static final Type<BubblePacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "bubble"));

	public static final StreamCodec<ByteBuf, BubblePacket> STREAM_CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBoolean(p.active());
				buf.writeFloat(p.startYaw());
				buf.writeFloat(p.startPitch());
				buf.writeFloat(p.shakeStart());
				buf.writeFloat(p.shakePerSecond());
				buf.writeFloat(p.breakDegrees());
				buf.writeFloat(p.maxSeconds());
			},
			buf -> new BubblePacket(buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readFloat(), buf.readFloat(), buf.readFloat()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
