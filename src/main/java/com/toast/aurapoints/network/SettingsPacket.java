package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: the player's own display settings (for now: hide yourself in third person while cloaked). */
public record SettingsPacket(boolean selfHide) implements CustomPacketPayload {

	public static final Type<SettingsPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "settings"));

	public static final StreamCodec<ByteBuf, SettingsPacket> STREAM_CODEC =
			ByteBufCodecs.BOOL.map(SettingsPacket::new, SettingsPacket::selfHide);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
