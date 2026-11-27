package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client (5.62.0): the Mage's mana for their HUD. {@code mana} -1 = not a Mage (hide the bar); {@code xp} 0..1 toward
 * the next mana level; {@code turn} &lt; 1 while the Last Prism slows the aim; {@code costs} "bolt,barrier,orb,prism".
 */
public record ManaPacket(float mana, int max, int level, float xp, float turn, String costs) implements CustomPacketPayload {

	public static final Type<ManaPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "mana"));

	public static final StreamCodec<ByteBuf, ManaPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.FLOAT, ManaPacket::mana,
			ByteBufCodecs.VAR_INT, ManaPacket::max,
			ByteBufCodecs.VAR_INT, ManaPacket::level,
			ByteBufCodecs.FLOAT, ManaPacket::xp,
			ByteBufCodecs.FLOAT, ManaPacket::turn,
			ByteBufCodecs.STRING_UTF8, ManaPacket::costs,
			ManaPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
