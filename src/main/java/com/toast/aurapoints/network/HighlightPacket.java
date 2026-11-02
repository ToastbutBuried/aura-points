package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server -> client: Fire Bending's fire sight. The entity ids the client should draw
 * glowing (see mixin/MinecraftMixin); an empty list turns it off. Only the bender gets it,
 * so nobody else sees the outlines.
 */
public record HighlightPacket(List<Integer> ids) implements CustomPacketPayload {

	public static final Type<HighlightPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "highlight"));

	/** Most ids one packet carries; the server never sends more. */
	public static final int MAX_IDS = 256;

	public static final StreamCodec<ByteBuf, HighlightPacket> STREAM_CODEC =
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_IDS)).map(HighlightPacket::new, HighlightPacket::ids);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
