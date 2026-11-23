package com.toast.aurapoints.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: one of the mod's own titles (5.54). The client sizes it to the screen (client/AuraTitles); times in ticks. */
public record TitlePacket(Component title, Component subtitle, int fadeIn, int stay, int fadeOut) implements CustomPacketPayload {

	public static final Type<TitlePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "title"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TitlePacket> STREAM_CODEC = StreamCodec.composite(
			ComponentSerialization.TRUSTED_STREAM_CODEC, TitlePacket::title,
			ComponentSerialization.TRUSTED_STREAM_CODEC, TitlePacket::subtitle,
			ByteBufCodecs.VAR_INT, TitlePacket::fadeIn,
			ByteBufCodecs.VAR_INT, TitlePacket::stay,
			ByteBufCodecs.VAR_INT, TitlePacket::fadeOut,
			TitlePacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
