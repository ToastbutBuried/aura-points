package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: abilities switched off in the server config, as enum names
 * joined with commas ("" = none). The cooldown HUD leaves them out. Also whether this
 * player is a server owner (config/CommandRules owners), which the client can't work out itself.
 */
public record DisabledAbilitiesPacket(String names, boolean owner) implements CustomPacketPayload {

	public static final Type<DisabledAbilitiesPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "disabled_abilities"));

	public static final StreamCodec<ByteBuf, DisabledAbilitiesPacket> STREAM_CODEC =
			StreamCodec.composite(ByteBufCodecs.STRING_UTF8, DisabledAbilitiesPacket::names,
					ByteBufCodecs.BOOL, DisabledAbilitiesPacket::owner, DisabledAbilitiesPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
