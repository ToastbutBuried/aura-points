package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.ArmMath;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * CLIENT ONLY. Keeps a held Air Bending wind charge on the hand this client actually draws.
 * The server places it on the raised hand with its own idea of the body's turn (and the position
 * arrives a ping late), so it can sit a little off. Each frame this works out where the hand is
 * here and glides the drawn charge there - never jumping. The height comes from the server (body
 * turn doesn't change it), only the sideways spot from the arm drawn here. After the throw, or
 * if the gap gets implausibly big, the correction eases back out to zero.
 */
public final class AirChargeSmoothing {

	private AirChargeSmoothing() {
	}

	/** Blocks; a bigger gap means it isn't sitting on the hand at all (yet), so it isn't pulled. */
	private static final double MAX_CORRECTION = 1.5;
	/** How fast the drawn charge catches up (per second; higher = snappier). */
	private static final double FOLLOW_RATE = 14.0;

	private static final class Track {
		Vec3 offset = Vec3.ZERO;
		long lastNanos = System.nanoTime();
	}

	private static final Map<Integer, Track> TRACKS = new HashMap<>();

	/** How far to draw this charge from where the server puts it, this frame. */
	public static Vec3 offset(AbstractWindCharge charge, float partialTicks) {
		Vec3 target = target(charge, partialTicks);
		Track t = TRACKS.get(charge.getId());
		if (t == null) {
			if (target == null) return Vec3.ZERO;
			t = new Track();
			TRACKS.put(charge.getId(), t);
		}
		long now = System.nanoTime();
		double dt = Math.min(0.1, (now - t.lastNanos) / 1.0e9);
		t.lastNanos = now;
		Vec3 goal = target == null ? Vec3.ZERO : target;
		double k = 1.0 - Math.exp(-FOLLOW_RATE * dt);
		t.offset = t.offset.add(goal.subtract(t.offset).scale(k));
		if (target == null && t.offset.lengthSqr() < 1.0e-6) TRACKS.remove(charge.getId());
		return t.offset;
	}

	/** Where the charge should be minus where it is, or null if it isn't a held Air Bending charge. */
	private static Vec3 target(AbstractWindCharge charge, float partialTicks) {
		if (!(charge.getOwner() instanceof AbstractClientPlayer player) || !player.isAlive()) return null;
		long[] s = ClientAuraState.bendState(player.getId());
		if (s == null || s[0] != BendingAnimPacket.AIR_RAISED || s.length < 4) return null;
		float out = BendingAnimPacket.armAngleRad(s[2]);
		if (Float.isNaN(out)) return null;
		float x = -BendingAnimPacket.armHoldForwardRad(s[3]);
		float drop = BendingAnimPacket.armHoldDrop(s[3]);

		Vec3 feet = player.getPosition(partialTicks);
		float bodyYaw = Mth.rotLerp(partialTicks, player.yBodyRotO, player.yBodyRot);
		float forward = BendingAnimPacket.armAngleForward(s[2]);
		float right = BendingAnimPacket.armAngleRight(s[2]);
		Vec3 hand = ArmMath.rightArmPoint(player, feet, bodyYaw, x, 0f, out, drop, forward, right, -1f, 10f, 0f);
		Vec3 at = charge.getPosition(partialTicks);
		Vec3 gap = new Vec3(hand.x - at.x, 0, hand.z - at.z);
		return gap.lengthSqr() > MAX_CORRECTION * MAX_CORRECTION ? null : gap;
	}

	public static void clear() {
		TRACKS.clear();
	}
}
