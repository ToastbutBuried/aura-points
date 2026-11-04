package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent client -> server whenever one of the ability keys is pressed or
 * released. {@code key} 0-2 is the "Ability 1/2/3" key (the server looks up which ability of the
 * player's aura sits there), 3-4 the "Item Use" / "Item Secondary" key, {@link #ULTIMATE} the Ultimate key, {@link #ITEM_TERTIARY} the "Item Tertiary" key. The server
 * mirrors the held state into the scoreboard (aura_key_blink and so on) so
 * datapacks can read it, and fires the ability on press.
 *
 * It has to be a packet: the server never learns about key presses by itself.
 */
public record AbilityKeyPacket(int key, boolean down) implements CustomPacketPayload {

	/** The Ultimate key's index (after the ability and item keys). */
	public static final int ULTIMATE = com.toast.aurapoints.ability.AuraType.KEY_SLOTS + 2;

	/** The "Item Tertiary" key's index: added after the Ultimate so the older indexes (and the wire format) stay the same. */
	public static final int ITEM_TERTIARY = com.toast.aurapoints.ability.AuraType.KEY_SLOTS + 3;

	/** The rebindable "Mjölnir Swing" key (spins the hammer). Added after Item Tertiary so older indexes stay. */
	public static final int MJOLNIR_SWING = com.toast.aurapoints.ability.AuraType.KEY_SLOTS + 4;

	/** Not a key of its own: the client's attack button (left mouse), held or let go, reported while Mjölnir is in the main hand. Keeps the Mjölnir flight going. */
	public static final int MJOLNIR_ATTACK_HELD = com.toast.aurapoints.ability.AuraType.KEY_SLOTS + 5;

	public static final Type<AbilityKeyPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "ability_key"));

	public static final StreamCodec<ByteBuf, AbilityKeyPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, AbilityKeyPacket::key,
			ByteBufCodecs.BOOL, AbilityKeyPacket::down,
			AbilityKeyPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
