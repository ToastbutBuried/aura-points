package com.toast.aurapoints.ability;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where a point on a player's right arm is in the world, for a given arm rotation - the same
 * transform PlayerRenderer + PlayerModel use (see client/BurningArm). Safe on the server, so
 * things held in the hand can be placed exactly where every client draws the hand.
 */
public final class ArmMath {

	private ArmMath() {
	}

	/** The right arm's transform with the given model rotations (radians) and the shoulder moved {@code drop} pixels down and {@code forward} pixels to the front, in blocks. */
	public static Matrix4f rightArm(Player player, float xRot, float yRot, float zRot, float drop, float forward, float right) {
		return rightArm(player, player.position(), player.yBodyRot, xRot, yRot, zRot, drop, forward, right);
	}

	/** Same, with the feet position and body turn given (e.g. interpolated for a render frame). */
	public static Matrix4f rightArm(Player player, Vec3 feet, float bodyYaw, float xRot, float yRot, float zRot, float drop, float forward, float right) {
		boolean crouch = player.isCrouching();
		return new Matrix4f()
				.translate((float) feet.x, (float) feet.y - (crouch ? 0.125f * player.getScale() : 0f), (float) feet.z)
				.scale(player.getScale())
				.rotateY((float) Math.toRadians(180f - bodyYaw))
				.scale(-1f, -1f, 1f)
				.scale(0.9375f)
				.translate(0f, -1.501f, 0f)
				.translate((-5f - right) / 16f, ((crouch ? 5.2f : 2f) + drop) / 16f, -forward / 16f) // the model faces -z, its right is -x
				.rotate(new Quaternionf().rotationZYX(zRot, yRot, xRot));
	}

	/** A point on the right arm, in pixels from the shoulder pivot (the arm runs from y -2 to the fingertips at 10). */
	public static Vec3 rightArmPoint(Player player, float xRot, float yRot, float zRot, float drop, float forward, float right,
									 float px, float py, float pz) {
		return rightArmPoint(player, player.position(), player.yBodyRot, xRot, yRot, zRot, drop, forward, right, px, py, pz);
	}

	public static Vec3 rightArmPoint(Player player, Vec3 feet, float bodyYaw, float xRot, float yRot, float zRot, float drop, float forward,
									 float right, float px, float py, float pz) {
		Vector3f p = rightArm(player, feet, bodyYaw, xRot, yRot, zRot, drop, forward, right).transformPosition(new Vector3f(px / 16f, py / 16f, pz / 16f));
		return new Vec3(p.x(), p.y(), p.z());
	}
}
