package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: this player's Ice Shell, for the HUD slot. {@code layers} of {@code maxLayers}
 * stand; the outermost is {@code top} whole (0..1); the next one is {@code rebuild} grown back (0..1).
 * {@code nextTicks}: until the next layer is back (0 if all are, or healing hasn't started).
 */
public record IceShellPacket(int maxLayers, int layers, float top, float rebuild, int nextTicks) implements CustomPacketPayload {

	public static final Type<IceShellPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "ice_shell"));

	public static final StreamCodec<ByteBuf, IceShellPacket> STREAM_CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeByte(p.maxLayers());
				buf.writeByte(p.layers());
				buf.writeFloat(p.top());
				buf.writeFloat(p.rebuild());
				buf.writeInt(p.nextTicks());
			},
			buf -> new IceShellPacket(buf.readByte(), buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
