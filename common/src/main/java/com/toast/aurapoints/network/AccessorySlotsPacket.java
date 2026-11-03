package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: how many accessory slots are switched on and whether they hold one item each (on login and after every reload). */
public record AccessorySlotsPacket(int count, boolean singleItem) implements CustomPacketPayload {

	public static final Type<AccessorySlotsPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "accessory_slots"));

	public static final StreamCodec<ByteBuf, AccessorySlotsPacket> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, AccessorySlotsPacket::count,
					ByteBufCodecs.BOOL, AccessorySlotsPacket::singleItem,
					AccessorySlotsPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
