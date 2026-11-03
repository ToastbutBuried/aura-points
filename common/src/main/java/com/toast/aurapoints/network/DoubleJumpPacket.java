package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent client -> server when the player presses jump while in mid-air. The
 * server is never told about a jump press it can't act on by itself, so this
 * has to be a packet; the server then checks the aura, cooldown and whether
 * the player is really airborne before it does anything.
 *
 * Carries no data - the server already knows which player sent it from the
 * connection the packet arrived on.
 */
public record DoubleJumpPacket() implements CustomPacketPayload {

	public static final Type<DoubleJumpPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "double_jump"));

	public static final StreamCodec<ByteBuf, DoubleJumpPacket> STREAM_CODEC =
			StreamCodec.unit(new DoubleJumpPacket());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
