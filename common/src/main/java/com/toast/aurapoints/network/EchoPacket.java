package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: how far Echolocation's fog starts and ends (blocks), and Abyssal Grasp's zoom at full charge (used while the
 * aura is Resonance); and how far Fireborn sees in lava (used while the aura is Fire).
 */
public record EchoPacket(float fogStart, float fogEnd, float graspZoom, float lavaSight) implements CustomPacketPayload {

	public static final Type<EchoPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "echo"));

	public static final StreamCodec<ByteBuf, EchoPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.FLOAT, EchoPacket::fogStart,
			ByteBufCodecs.FLOAT, EchoPacket::fogEnd,
			ByteBufCodecs.FLOAT, EchoPacket::graspZoom,
			ByteBufCodecs.FLOAT, EchoPacket::lavaSight,
			EchoPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
