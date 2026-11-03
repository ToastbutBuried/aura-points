package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: the Fire Bending charge bar. {@code active} = an orb is held;
 * {@code charge} and {@code readyAt} are 0..1 of full charge (readyAt = where it can be shot).
 * Sent every 2 ticks while an orb is held, and once with active=false when it ends.
 */
public record FireBendingPacket(boolean active, float charge, float readyAt) implements CustomPacketPayload {

	public static final Type<FireBendingPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "fire_bending"));

	public static final StreamCodec<ByteBuf, FireBendingPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, FireBendingPacket::active,
			ByteBufCodecs.FLOAT, FireBendingPacket::charge,
			ByteBufCodecs.FLOAT, FireBendingPacket::readyAt,
			FireBendingPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
