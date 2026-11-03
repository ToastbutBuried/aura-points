package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the ability animations: the held pose each player shows right now (a Fire Bending
 * orb, the Abyssal Bubble, a Dash Dive, a Featherfall glide), so players who come into view get it,
 * and the Featherfall glide pose, which follows the player's movement. One-shot gestures are sent
 * by the abilities themselves (NetworkHandler.sendGesture).
 */
public final class Poses {

	private Poses() {
	}

	/** gliding players -> ticks the glide conditions have been off (it ends after a few, so it doesn't flicker) */
	private static final Map<UUID, Integer> GLIDING = new HashMap<>();

	/** The held pose to show for this player now (NONE if none). */
	public static byte current(ServerPlayer player) {
		byte orb = FireBending.heldPose(player);
		if (orb != BendingAnimPacket.NONE) return orb;
		orb = AirBending.heldPose(player);
		if (orb != BendingAnimPacket.NONE) return orb;
		if (AbyssalBubble.isHolding(player)) return BendingAnimPacket.BUBBLE_HOLD;
		if (IceSpike.isCharging(player)) return BendingAnimPacket.ICE_AIM;
		if (Chidori.isCharging(player)) return BendingAnimPacket.CHIDORI_HOLD;
		if (Mjolnir.isSpinning(player)) return BendingAnimPacket.MJOLNIR_SPIN;
		if (Mjolnir.isFlying(player)) return BendingAnimPacket.MJOLNIR_FLY;
		if (DashDive.isDiving(player)) return BendingAnimPacket.DIVE;
		if (VerdantViper.isSteering(player)) return BendingAnimPacket.VIPER_STEER;
		if (Earth.isHolding(player)) return BendingAnimPacket.BOULDER_HOLD;
		if (Light.isBeaming(player)) return BendingAnimPacket.PRISM_AIM;
		if (GLIDING.containsKey(player.getUUID())) return BendingAnimPacket.GLIDE;
		return BendingAnimPacket.NONE;
	}

	/** A player who just came into view shows their current pose - NONE too, so no old pose stays. */
	public static void onStartTracking(ServerPlayer watcher, ServerPlayer target) {
		NetworkHandler.sendBendAnimTo(watcher, target, current(target));
	}

	/**
	 * Every server tick for EVERY player (not only Featherfall ones, so losing the ability mid-glide
	 * still ends the pose): wings out while sneaking in a slow Featherfall descent.
	 */
	public static void tickGlide(ServerPlayer player, AuraPlayerData data) {
		boolean other = FireBending.isHolding(player) || AirBending.isHolding(player) || AbyssalBubble.isHolding(player) || DashDive.isDiving(player)
				|| IceSpike.isCharging(player) || Chidori.isCharging(player) || Mjolnir.isSpinning(player) || Mjolnir.isFlying(player)
				|| VerdantViper.isSteering(player) || Earth.isHolding(player) || Light.isBeaming(player);
		boolean airborne = player.isAlive() && !player.isSpectator() && !player.onGround() && !player.isFallFlying()
				&& !player.isInWater() && !player.isPassenger() && !player.getAbilities().flying;
		boolean can = airborne && !other && player.isShiftKeyDown() && AuraAbilities.hasAbility(data, Ability.FEATHERFALL);
		Integer off = GLIDING.get(player.getUUID());
		if (off == null) {
			// starts only once really falling (like the slow falling itself)
			if (can && player.getKnownMovement().y < 0) {
				GLIDING.put(player.getUUID(), 0);
				NetworkHandler.sendBendAnim(player, BendingAnimPacket.GLIDE);
			}
			return;
		}
		if (can) {
			GLIDING.put(player.getUUID(), 0);
			return;
		}
		// on the ground, dead, or another pose took over: at once; otherwise after 4 ticks
		if (!airborne || other || off + 1 >= 4) {
			GLIDING.remove(player.getUUID());
			if (!other) NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
		} else {
			GLIDING.put(player.getUUID(), off + 1);
		}
	}

	public static void onLogout(ServerPlayer player) {
		GLIDING.remove(player.getUUID());
	}
}
