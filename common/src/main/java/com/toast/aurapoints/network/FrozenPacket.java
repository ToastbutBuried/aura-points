package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: this player is frozen solid by an Ice Spike for {@code ticks} (0 = thawed).
 * The client stops the camera, movement keys, clicks and the hotbar (client/FrozenControls), and
 * lets go by itself once the time is up, in case the thaw packet goes missing.
 */
public record FrozenPacket(int ticks) implements CustomPacketPayload {

	public static final Type<FrozenPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "frozen"));

	public static final StreamCodec<ByteBuf, FrozenPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, FrozenPacket::ticks,
			FrozenPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
