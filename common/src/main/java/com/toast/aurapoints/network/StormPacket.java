package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client (5.39.0): the local storms near the player, "id,type,x,z,radius,strength,vx,vz" joined
 * by ';' (empty = none). Sent every 2 s by weather/Storms; read into {@link ClientStorms}.
 */
public record StormPacket(String storms) implements CustomPacketPayload {

	public static final Type<StormPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "storms"));

	public static final StreamCodec<ByteBuf, StormPacket> STREAM_CODEC =
			ByteBufCodecs.STRING_UTF8.map(StormPacket::new, StormPacket::storms);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
