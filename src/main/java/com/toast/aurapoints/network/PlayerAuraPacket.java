package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: player {@code entityId} holds aura {@code aura} ("" = none), for the label over their name. */
public record PlayerAuraPacket(int entityId, String aura) implements CustomPacketPayload {

	public static final Type<PlayerAuraPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "player_aura"));

	public static final StreamCodec<ByteBuf, PlayerAuraPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, PlayerAuraPacket::entityId,
			ByteBufCodecs.STRING_UTF8, PlayerAuraPacket::aura,
			PlayerAuraPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
