package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: a purely visual moment for this player's own screen (client/ScreenFx and friends).
 * What {@code value} and {@code text} mean depends on {@code kind}, see the constants.
 */
public record FxPacket(byte kind, int value, String text) implements CustomPacketPayload {

	/** /aura roll started: value = ticks until the reveal, text = "rolled,candidate,candidate,..." (aura ids). */
	public static final byte ROLL = 1;
	/** A stunt kill: text = the stunt's id (e.g. "flame_lance"), value 1 = on a boss. */
	public static final byte STUNT = 2;
	/** Aura points gained: value = how many, text = the new total. */
	public static final byte POINTS = 3;
	/** One of your abilities hit something: value bit 1 = it died, bit 2 = it was a player. */
	public static final byte HIT = 4;
	/** You are stunned for value ticks. */
	public static final byte STUNNED = 5;
	/** Lifesteal healed you: value = tenths of a health point. */
	public static final byte LIFESTEAL = 6;
	/** Someone's Blood Moon: the sky turns red for value ticks. */
	public static final byte BLOOD_MOON = 7;

	public static final Type<FxPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "fx"));

	public static final StreamCodec<ByteBuf, FxPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BYTE, FxPacket::kind,
			ByteBufCodecs.VAR_INT, FxPacket::value,
			ByteBufCodecs.STRING_UTF8, FxPacket::text,
			FxPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
