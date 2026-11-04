package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: the Blood aura's blood bar ({@code blood} of {@code max}; Blood Moon needs {@code moonCost}).
 * Sent when it changes, on login and on aura change.
 */
public record BloodBarPacket(int blood, int max, int moonCost) implements CustomPacketPayload {

	public static final Type<BloodBarPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "blood_bar"));

	public static final StreamCodec<ByteBuf, BloodBarPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BloodBarPacket::blood,
			ByteBufCodecs.VAR_INT, BloodBarPacket::max,
			ByteBufCodecs.VAR_INT, BloodBarPacket::moonCost,
			BloodBarPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
