package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: the player with this entity id is (no longer) Shadow-cloaked - don't draw them at all. */
public record CloakPacket(int entityId, boolean cloaked) implements CustomPacketPayload {

	public static final Type<CloakPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "cloak"));

	public static final StreamCodec<ByteBuf, CloakPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, CloakPacket::entityId,
			ByteBufCodecs.BOOL, CloakPacket::cloaked,
			CloakPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
