package com.toast.aurapoints.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: an arena announcement (round won, wave cleared, knocked out...). The client shows it as a short
 * title or as a chat line, whichever its arenaMessages setting says. {@code countdown} = a 3-2-1/FIGHT tick: a very
 * short title, or the action bar in chat mode (a chat line per second would be spam). Empty subtitle = none.
 */
public record ArenaTitlePacket(Component title, Component subtitle, boolean countdown) implements CustomPacketPayload {

	public static final Type<ArenaTitlePacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "arena_title"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ArenaTitlePacket> STREAM_CODEC = StreamCodec.composite(
			ComponentSerialization.TRUSTED_STREAM_CODEC, ArenaTitlePacket::title,
			ComponentSerialization.TRUSTED_STREAM_CODEC, ArenaTitlePacket::subtitle,
			ByteBufCodecs.BOOL, ArenaTitlePacket::countdown,
			ArenaTitlePacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
