package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: an ability (by enum name) just went on cooldown for
 * {@code ticks} ticks. The client counts it down for the HUD by itself.
 * "DASH:FLIGHT" style names (ability, {@link #SLOT_SEPARATOR}, AuraAbilities.Slot) are an extra cooldown of the ability.
 */
public record CooldownPacket(String ability, int ticks) implements CustomPacketPayload {

	public static final String SLOT_SEPARATOR = ":";

	public static final Type<CooldownPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "cooldown"));

	public static final StreamCodec<ByteBuf, CooldownPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, CooldownPacket::ability,
			ByteBufCodecs.VAR_INT, CooldownPacket::ticks,
			CooldownPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
