package com.toast.aurapoints.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> everyone who sees the owner: what a Resonance player's tentacles are doing (Abyssal Grasp), drawn
 * by client/Tentacles. phase: NONE, CHARGE (winding back), REACH toward (x, y, z), HOLD the target entity, THROW, MISS (pull back
 * from (x, y, z)); ticks = how long the phase takes.
 */
public record GraspPacket(int owner, int phase, int target, float x, float y, float z, int ticks) implements CustomPacketPayload {

	public static final int NONE = 0, REACH = 1, HOLD = 2, THROW = 3, MISS = 4, CHARGE = 5;

	public static final Type<GraspPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "grasp"));

	public static final StreamCodec<RegistryFriendlyByteBuf, GraspPacket> STREAM_CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.owner);
				buf.writeVarInt(p.phase);
				buf.writeVarInt(p.target);
				buf.writeFloat(p.x);
				buf.writeFloat(p.y);
				buf.writeFloat(p.z);
				buf.writeVarInt(p.ticks);
			},
			buf -> new GraspPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
