package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: the active abilities the player bought in /aura shop, as
 * enum names joined with commas. The cooldown HUD only shows these.
 */
public record OwnedAbilitiesPacket(String names) implements CustomPacketPayload {

	public static final Type<OwnedAbilitiesPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "owned_abilities"));

	public static final StreamCodec<ByteBuf, OwnedAbilitiesPacket> STREAM_CODEC =
			ByteBufCodecs.STRING_UTF8.map(OwnedAbilitiesPacket::new, OwnedAbilitiesPacket::names);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
