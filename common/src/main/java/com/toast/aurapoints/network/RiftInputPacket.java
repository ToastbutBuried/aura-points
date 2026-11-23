package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server, from inside a rift (5.52): {@link #CAM_CLOSED} a camera shot ended or was skipped, {@link #SKILL} a
 * skill check's answer ({@code id} = the check, {@code value} = rift/SkillChecks result).
 */
public record RiftInputPacket(byte kind, int id, int value) implements CustomPacketPayload {

	public static final byte CAM_CLOSED = 0, SKILL = 1;

	public static final Type<RiftInputPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "rift_input"));

	public static final StreamCodec<ByteBuf, RiftInputPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BYTE, RiftInputPacket::kind,
			ByteBufCodecs.VAR_INT, RiftInputPacket::id,
			ByteBufCodecs.VAR_INT, RiftInputPacket::value,
			RiftInputPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
