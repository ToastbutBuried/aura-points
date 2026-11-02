package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: the player's current aura id ("" = none), so the cooldown
 * HUD knows which abilities to draw. Sent on login and whenever the aura changes.
 */
public record AuraHudPacket(String aura) implements CustomPacketPayload {

	public static final Type<AuraHudPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "aura_hud"));

	public static final StreamCodec<ByteBuf, AuraHudPacket> STREAM_CODEC =
			ByteBufCodecs.STRING_UTF8.map(AuraHudPacket::new, AuraHudPacket::aura);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
