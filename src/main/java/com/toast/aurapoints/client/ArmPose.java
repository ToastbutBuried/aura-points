package com.toast.aurapoints.client;

import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * CLIENT ONLY. Where a player's arms are in the world right now, the way PlayerRenderer draws them:
 * body yaw, Player Animator's whole-body transform (BendingAnimation's lean, twist and sink about a
 * point 0.7 above the feet), the shoulder pivot, then the arm's current rotation. Effects use it to
 * sit exactly on a hand or fingertip, whatever pose the animation has it in.
 */
public final class ArmPose {

	private ArmPose() {
	}

	/** The arm's model transform: points in arm pixels (the shoulder pivot at 0, the hand towards y = 10..12) to world. */
	public static Matrix4f arm(AbstractClientPlayer p, boolean right, float partial) {
		BendingAnimation anim = BendingAnimation.of(p);
		boolean crouch = p.isCrouching();
		float bodyYaw = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot);
		Matrix4f m = new Matrix4f()
				.translate((float) Mth.lerp(partial, p.xo, p.getX()), (float) Mth.lerp(partial, p.yo, p.getY()) - (crouch ? 0.125f * p.getScale() : 0f),
						(float) Mth.lerp(partial, p.zo, p.getZ()))
				.scale(p.getScale())
				.rotateY((float) Math.toRadians(180f - bodyYaw));
		if (anim != null && anim.isActive()) {
			Vec3f pos = anim.get3DTransform("body", TransformType.POSITION, partial, Vec3f.ZERO);
			Vec3f rot = anim.get3DTransform("body", TransformType.ROTATION, partial, Vec3f.ZERO);
			m.translate(pos.getX(), pos.getY() + 0.7f, pos.getZ()).rotateZ(rot.getZ()).rotateY(rot.getY()).rotateX(rot.getX()).translate(0f, -0.7f, 0f);
		}
		m.scale(-1f, -1f, 1f).scale(0.9375f).translate(0f, -1.501f, 0f)
				.translate((right ? -5f : 5f) / 16f, (crouch ? 5.2f : 2f) / 16f, 0f);
		Vec3f r = anim != null && anim.isActive() ? anim.get3DTransform(right ? "rightArm" : "leftArm", TransformType.ROTATION, partial, Vec3f.ZERO) : Vec3f.ZERO;
		return m.rotate(new Quaternionf().rotationZYX(r.getZ(), r.getY(), r.getX()));
	}

	/** A point of the arm, in arm pixels (x across, y down the arm, z front to back), in the world. */
	public static Vec3 point(AbstractClientPlayer p, boolean right, float partial, float x, float y, float z) {
		Vector3f v = arm(p, right, partial).transformPosition(new Vector3f(x / 16f, y / 16f, z / 16f));
		return new Vec3(v.x(), v.y(), v.z());
	}

	/** The middle of the arm across (the arm box is 4 pixels wide, 3 when slim; the right one sits to -x). */
	public static float middle(AbstractClientPlayer p, boolean right) {
		boolean slim = p.getSkin().model() == PlayerSkin.Model.SLIM;
		return right ? (slim ? -0.5f : -1f) : (slim ? 0.5f : 1f);
	}

	/** The hand (fist) of that arm. */
	public static Vec3 hand(AbstractClientPlayer p, boolean right, float partial) {
		return point(p, right, partial, middle(p, right), 9.5f, 0f);
	}

	/** Just past the fingertips. */
	public static Vec3 fingertip(AbstractClientPlayer p, boolean right, float partial) {
		return point(p, right, partial, middle(p, right), 12f, 0f);
	}

	/** Where the body faces (flat). */
	public static Vec3 forward(AbstractClientPlayer p, float partial) {
		float yaw = (float) Math.toRadians(Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot));
		return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
	}

	/** The body's right (flat). */
	public static Vec3 right(AbstractClientPlayer p, float partial) {
		float yaw = (float) Math.toRadians(Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot));
		return new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
	}

	/** Roughly the mouth: a little below the eyes, at the front of the face. */
	public static Vec3 mouth(AbstractClientPlayer p, float partial) {
		return p.getEyePosition(partial).add(forward(p, partial).scale(0.27)).add(0, -0.17 * p.getScale(), 0);
	}

	/** The feet. */
	public static Vec3 feet(AbstractClientPlayer p, float partial) {
		return p.getPosition(partial);
	}
}
