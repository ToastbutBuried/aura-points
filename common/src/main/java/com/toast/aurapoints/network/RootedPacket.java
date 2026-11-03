package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: you are rooted in place ({@code on}) - entangled, or steering a Verdant Viper. Your
 * movement keys do nothing, the camera stays free. {@code mash}: you are entangled and break free by
 * mashing W A S D - your client reports every fresh press (MashKeyPacket).
 */
public record RootedPacket(boolean on, boolean mash) implements CustomPacketPayload {

	public static final Type<RootedPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "rooted"));

	public static final StreamCodec<ByteBuf, RootedPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, RootedPacket::on,
			ByteBufCodecs.BOOL, RootedPacket::mash,
			RootedPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
