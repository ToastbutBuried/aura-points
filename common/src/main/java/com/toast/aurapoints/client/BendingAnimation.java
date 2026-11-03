package com.toast.aurapoints.client;

import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.ClientAuraState;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;

/**
 * CLIENT ONLY. The Fire Bending pose, one instance per player (Player Animator layer).
 * Procedural instead of keyframes, so the arms can follow the flame wherever the player looks:
 *  - charging: both arms reach for the flame in front of the eyes, trembling a little;
 *  - fully charged (side mode): the right arm holds the flame by the torso - angled forward,
 *    a bit higher than resting - and the left arm relaxes;
 *  - Air Bending fully charged (AIR_RAISED): the right arm held up at shoulder height, in front and
 *    a little out to the side, the wind charge above the hand; the left arm hangs relaxed, a bit out;
 *  - throw: a quick thrust from there to where the player aims, then it eases back to normal.
 * The pose comes from {@link ClientAuraState#bendState}, fed by BendingAnimPacket.
 */
public class BendingAnimation implements IAnimation {

	private static final float HALF_PI = (float) (Math.PI / 2);
	/** The right arm holding the burning hand out at the side: angled outward, a little forward and away from the body. */
	static final float HOLD_X = -0.35f, HOLD_Y = 0.25f, HOLD_Z = 0.55f;
	/** Both arms reaching for a flame in front: each turns in a little so they meet at it. */
	private static final float REACH_IN = 0.12f;
	/**
	 * AIR_RAISED: the right arm out to the side at the angles the server sent (AIR_BENDING_RAISED_*_DEGREES,
	 * the same ones it places the wind charge on the hand with); these are the defaults until one arrives.
	 * Left arm relaxed, slightly out.
	 */
	private float raiseX = BendingAnimPacket.airArmX(50), raiseY = 0f, raiseZ = (float) Math.toRadians(135), raiseDrop = 5f;
	/** shoulder pivot shift (pixels) that keeps the twisted arm turning about its own middle */
	private float raiseShiftX = 0f, raiseShiftY = 0f, raiseShiftZ = 0f;
	/** the whole arm moved to the front (pixels; the model faces -z) */
	private float raiseForward = 0f;
	/** ... and to the right (pixels; the right arm's outward side is -x) */
	private float raiseRight = 0f;
	private static final float RAISE_LEFT_X = -0.15f, RAISE_LEFT_Z = -0.22f;
	private static final int THROW_TICKS = 7;
	/**
	 * Inferno Slam timing in ticks, from the server's windup and stun: arms up until 2 ticks before the
	 * impact, slammed down in the last 2, held down through the stun, then back to rest over SLAM_RETURN.
	 */
	private int slamUpEnd = 5, slamDownEnd = 7, slamHoldEnd = 14;
	private static final int SLAM_RETURN = 4;

	private final AbstractClientPlayer player;

	/** blend toward the pose (0 = vanilla, 1 = full pose), this tick and last tick */
	private float weight, weightO;
	/** how far the arm is drawn to the side */
	private float side, sideO;
	/** how far the arm is raised into the Air Bending pose, and how far it was when the throw began */
	private float raise, raiseO, fromRaise;
	/** ticks since the throw started (-1 = no throw) */
	private int throwTick = -1;
	/** ticks since the Inferno Slam started (-1 = none) */
	private int slamTick = -1;
	/**
	 * The one-shot move (SLAM or THROW) that just finished, while the pose fades out: the fade
	 * keeps that move's last frame instead of falling back to the charging pose. NONE = no fade.
	 */
	private byte fading = BendingAnimPacket.NONE;
	/** The last held pose (BUBBLE_HOLD, GLIDE, DIVE) shown, so it fades out as itself; NONE after an orb pose. */
	private byte lastHeld = BendingAnimPacket.NONE;
	/** when the current held pose began (millis), for the bubble's growing tremble */
	private long heldSince;
	/** Mjölnir's arm angle at the last frame it was really spinning: once the key is let go it stops there. */
	private float spinStopX = Float.NaN;
	/** the gesture playing: ticks since it started (-1 = none), which one, and the packet time it came with */
	private int gestureTick = -1, gestureKind, gestureVariant;
	private long gestureSince;
	private float fromSide;
	/** Where a gesture fading out stays: its end (1000 = done), or the tick an idle was cut short at. */
	private float gestureFadeT = 1000f;
	/** Where the player stood when the idle playing now began: moving away from there ends it. */
	private double idleX, idleY, idleZ;

	public BendingAnimation(AbstractClientPlayer player) {
		this.player = player;
	}

	private byte state() {
		long[] s = ClientAuraState.bendState(player.getId());
		return s == null ? BendingAnimPacket.NONE : (byte) s[0];
	}

	@Override
	public void tick() {
		byte state = state();
		weightO = weight;
		sideO = side;
		raiseO = raise;

		if (state == BendingAnimPacket.SLAM) {
			if (slamTick < 0) {
				long[] s = ClientAuraState.bendState(player.getId());
				int windup = s != null && s.length > 2 ? (int) s[2] : 7, hold = s != null && s.length > 3 ? (int) s[3] : 7;
				slamDownEnd = Math.max(2, windup);
				slamUpEnd = Math.max(1, slamDownEnd - 2);
				slamHoldEnd = slamDownEnd + Math.max(3, hold);
			}
			slamTick = slamTick < 0 ? 0 : slamTick + 1;
			if (slamTick >= slamHoldEnd + SLAM_RETURN) {
				ClientAuraState.setBendState(player.getId(), BendingAnimPacket.NONE);
				slamTick = -1;
				state = BendingAnimPacket.NONE;
				fading = BendingAnimPacket.SLAM;
			}
		} else {
			slamTick = -1;
		}

		if (state == BendingAnimPacket.GESTURE) {
			long[] s = ClientAuraState.bendState(player.getId());
			if (s != null && (gestureTick < 0 || s[1] != gestureSince)) {
				// a new gesture (also one that replaces a gesture still playing)
				gestureTick = 0;
				gestureSince = s[1];
				gestureKind = (int) s[2];
				gestureVariant = (int) s[3];
				gestureFadeT = 1000f;
				idleX = player.getX();
				idleY = player.getY();
				idleZ = player.getZ();
				// idles switched off on this client: never shown (cut at their first frame, which is the rest pose)
				if (gestureKind == BendingAnimPacket.GESTURE_IDLE && !com.toast.aurapoints.config.AuraClientConfig.SHOW_IDLE_ANIMATIONS.get()) {
					gestureFadeT = 0f;
					gestureTick = Integer.MAX_VALUE / 2;
				}
			} else {
				gestureTick++;
			}
			// an idle stops the moment its player does anything, fading out from wherever it was
			if (gestureKind == BendingAnimPacket.GESTURE_IDLE && gestureTick > 0 && gestureTick < Integer.MAX_VALUE / 2 && idleInterrupted()) {
				gestureFadeT = gestureTick;
				gestureTick = Integer.MAX_VALUE / 2;
			}
			if (gestureTick >= Gestures.length(gestureKind, gestureVariant)) {
				gestureTick = -1;
				ClientAuraState.endGesture(player.getId());
				state = state();
				if (state == BendingAnimPacket.NONE) fading = BendingAnimPacket.GESTURE;
			}
		} else {
			gestureTick = -1;
		}

		if (state == BendingAnimPacket.THROW) {
			if (throwTick < 0) {
				throwTick = 0;
				fromSide = side;
				fromRaise = raise;
			} else {
				throwTick++;
			}
			if (throwTick >= THROW_TICKS) {
				// thrown: done with this pose until the next orb
				ClientAuraState.setBendState(player.getId(), BendingAnimPacket.NONE);
				throwTick = -1;
				state = BendingAnimPacket.NONE;
				fading = BendingAnimPacket.THROW;
			}
		} else {
			throwTick = -1;
		}

		if (isHeldPose(state)) {
			long[] s = ClientAuraState.bendState(player.getId());
			if (state != lastHeld && s != null) heldSince = s[1];
			if (state == BendingAnimPacket.MJOLNIR_SPIN && lastHeld != BendingAnimPacket.MJOLNIR_SPIN) spinStopX = Float.NaN;
			lastHeld = state;
		} else if (state != BendingAnimPacket.NONE && state != BendingAnimPacket.GESTURE) {
			lastHeld = BendingAnimPacket.NONE; // orb poses, throw, slam
		}

		float targetWeight = state == BendingAnimPacket.NONE ? 0f : 1f;
		weight = approach(weight, targetWeight, state == BendingAnimPacket.NONE ? 0.2f
				: state == BendingAnimPacket.GESTURE ? 1f : 0.35f); // gestures start at once (their keys begin at rest)
		if (state != BendingAnimPacket.NONE || (weight <= 0 && weightO <= 0)) fading = BendingAnimPacket.NONE;
		if (state == BendingAnimPacket.NONE && weight <= 0 && weightO <= 0) lastHeld = BendingAnimPacket.NONE;
		float targetSide = state == BendingAnimPacket.READY_SIDE ? 1f : 0f;
		if (throwTick < 0) side = approach(side, targetSide, 0.2f);
		if (throwTick < 0) raise = approach(raise, state == BendingAnimPacket.AIR_RAISED ? 1f : 0f, 0.2f);
		if (state == BendingAnimPacket.AIR_RAISED) aimArmAt(ClientAuraState.bendState(player.getId()));
	}

	/**
	 * Air Bending at full charge: the whole right arm moved down the side (pixels), blended like the pose -
	 * and while throwing from there, back up as the arm swings to the aim.
	 */
	private Vec3f armDrop(float tickDelta, Vec3f value0) {
		float w = Mth.lerp(tickDelta, weightO, weight);
		if (w <= 0) return value0;
		w = w * w * (3 - 2 * w);
		float r;
		if (throwTick >= 0 || fading == BendingAnimPacket.THROW) {
			float p = fading == BendingAnimPacket.THROW ? 1f : Math.min(1f, (throwTick + tickDelta) / THROW_TICKS);
			float out = 1 - (1 - p) * (1 - p) * (1 - p);
			r = fromRaise * (1 - out);
		} else {
			r = Mth.lerp(tickDelta, raiseO, raise);
			r = r * r * (3 - 2 * r);
		}
		if (r <= 0) return value0;
		float k = r * w;
		return new Vec3f(value0.getX() + (raiseShiftX - raiseRight) * k, value0.getY() + (raiseDrop + raiseShiftY) * k,
				value0.getZ() + (raiseShiftZ - raiseForward) * k);
	}

	/** Takes the arm angles and drop sent with AIR_RAISED (out to the side, forward, moved down). */
	private void aimArmAt(long[] s) {
		if (s == null || s.length < 4) return;
		float out = BendingAnimPacket.armAngleRad(s[2]);
		if (Float.isNaN(out)) return;
		float[] arm = BendingAnimPacket.twistedArm(-BendingAnimPacket.armHoldForwardRad(s[3]), out, BendingAnimPacket.armHoldTwistRad(s[3]));
		raiseX = arm[0];
		raiseY = arm[1];
		raiseZ = arm[2];
		raiseShiftX = arm[3];
		raiseShiftY = arm[4];
		raiseShiftZ = arm[5];
		raiseDrop = BendingAnimPacket.armHoldDrop(s[3]);
		raiseForward = BendingAnimPacket.armAngleForward(s[2]);
		raiseRight = BendingAnimPacket.armAngleRight(s[2]);
	}

	private static float approach(float value, float target, float step) {
		return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
	}

	@Override
	public boolean isActive() {
		return weight > 0 || weightO > 0 || state() != BendingAnimPacket.NONE;
	}

	@Override
	public void setupAnim(float tickDelta) {
	}

	/** Animations switched off in the client config: the pose keeps being tracked, just never shown. */
	private static boolean shown() {
		return com.toast.aurapoints.config.AuraClientConfig.ANIMATIONS.get() != com.toast.aurapoints.config.AuraClientConfig.AnimationMode.OFF;
	}

	@Override
	public Vec3f get3DTransform(String part, TransformType type, float tickDelta, Vec3f value0) {
		if (!shown()) return value0;
		float[] body = bodyOn() ? bodyPose(tickDelta) : null;
		switch (part) {
			// the whole body leans, turns and sinks about the hips (the renderer's "body" transform)
			case "body" -> {
				if (body == null) return value0;
				if (type == TransformType.ROTATION) return new Vec3f(value0.getX() - body[0], value0.getY() + body[1], value0.getZ() + body[2]);
				// legs swung out of plumb reach less far down: sink by exactly that, so the feet stay on the ground
				// (a quarter of the authored crouch on top - stiff block legs can't bend at the knee)
				if (type == TransformType.POSITION) return new Vec3f(value0.getX(), value0.getY() - (player.onGround() ? feetDrop(body) + 0.25f * body[3] : 0f), value0.getZ());
				return value0;
			}
			// the head turns back against it, so it keeps looking where the player looks (or where an idle has it look)
			case "head" -> {
				if (type != TransformType.ROTATION) return value0;
				Vec3f look = gestureHead(tickDelta, value0);
				if (body == null) return look;
				return new Vec3f(look.getX() - body[0], look.getY() + body[1], look.getZ() - body[2]);
			}
			// the legs turn back against the lean, so it bends at the hips instead of tipping the whole player over
			case "rightLeg", "leftLeg" -> {
				if (body == null || type != TransformType.ROTATION) return value0;
				boolean rightLeg = part.equals("rightLeg");
				float tx = rightLeg ? -body[4] * 0.8f : body[4] * 0.6f;
				float tz = rightLeg ? body[5] : -body[5];
				// in the air (gliding, diving, flying with Mjölnir) the legs stay in line with the body
				float hip = player.onGround() ? body[0] : 0f;
				return new Vec3f(Mth.lerp(body[6], value0.getX(), tx) - hip, Mth.lerp(body[6], value0.getY(), 0f), Mth.lerp(body[6], value0.getZ(), tz));
			}
			default -> {
			}
		}
		if (type == TransformType.POSITION && part.equals("rightArm")) return armDrop(tickDelta, value0);
		if (type != TransformType.ROTATION) return value0;
		boolean right = part.equals("rightArm");
		if (!right && !part.equals("leftArm")) return value0;

		float w = Mth.lerp(tickDelta, weightO, weight);
		if (w <= 0) return value0;
		w = w * w * (3 - 2 * w); // smoothstep

		float pitch = player.getViewXRot(tickDelta) * Mth.DEG_TO_RAD;
		float headYaw = Mth.wrapDegrees(Mth.rotLerp(tickDelta, player.yHeadRotO, player.yHeadRot)
				- Mth.rotLerp(tickDelta, player.yBodyRotO, player.yBodyRot)) * Mth.DEG_TO_RAD;
		float time = (player.tickCount + tickDelta);

		float x, y, z;
		float aimX = -HALF_PI + pitch;
		// a leaning or turned body takes the arms with it: aim against it, so throws still go at the crosshair
		if (body != null) {
			aimX -= body[0];
			headYaw += body[1];
		}
		if (gestureTick >= 0 || fading == BendingAnimPacket.GESTURE) {
			float t = fading == BendingAnimPacket.GESTURE ? gestureFadeT : gestureTick + tickDelta;
			Vec3f g = Gestures.pose(gestureKind, gestureVariant, right, t, aimX, headYaw, value0);
			return new Vec3f(Mth.lerp(w, value0.getX(), g.getX()), Mth.lerp(w, value0.getY(), g.getY()), Mth.lerp(w, value0.getZ(), g.getZ()));
		}
		if (slamTick >= 0 || fading == BendingAnimPacket.SLAM) {
			// Inferno Slam: both arms swing up over the head, then crash down in front of the body
			float t = fading == BendingAnimPacket.SLAM ? slamHoldEnd + SLAM_RETURN : slamTick + tickDelta;
			float side = right ? 1f : -1f;
			float up = -2.9f, down = -0.45f;
			if (t < slamUpEnd) {
				float e = t / slamUpEnd;
				e = 1 - (1 - e) * (1 - e); // ease out: quick lift, slowing at the top
				x = Mth.lerp(e, value0.getX(), up);
				z = side * 0.3f * e;
			} else if (t < slamDownEnd) {
				float e = (t - slamUpEnd) / (slamDownEnd - slamUpEnd);
				e = e * e; // ease in: gathers speed into the ground
				x = Mth.lerp(e, up, down);
				z = side * Mth.lerp(e, 0.3f, 0.12f);
			} else {
				// the impact: a small recoil, down on the ground through the stun, then back to rest
				float after = t - slamDownEnd;
				float back = Math.max(0f, Math.min(1f, (t - slamHoldEnd) / SLAM_RETURN));
				back = back * back * (3 - 2 * back);
				x = Mth.lerp(back, down - 0.15f * Mth.sin(Math.min(1f, after / 3f) * (float) Math.PI), value0.getX());
				z = Mth.lerp(back, side * 0.12f, value0.getZ());
			}
			y = 0;
			return new Vec3f(Mth.lerp(w, value0.getX(), x), Mth.lerp(w, value0.getY(), y), Mth.lerp(w, value0.getZ(), z));
		}
		byte now = state();
		byte held = heldShown();
		if (held != BendingAnimPacket.NONE && throwTick < 0) {
			float side = right ? 1f : -1f;
			if (held == BendingAnimPacket.VIPER_STEER) {
				// Verdant Viper: the right hand stretched out a little below the aim, guiding the snake with small
				// weaving motions; the left hangs out a little for balance
				if (right) {
					x = aimX + 0.25f + 0.04f * Mth.sin(time * 0.21f);
					y = headYaw + 0.06f * Mth.sin(time * 0.13f);
					z = 0.05f;
				} else {
					x = 0.1f;
					y = 0;
					z = -0.2f;
				}
			} else if (held == BendingAnimPacket.PRISM_AIM) {
				// Prism Beam: the right arm stretched out at the aim, quivering with the beam; the left hand grips its forearm
				float quiver = 0.012f * Mth.sin(time * 4.1f);
				if (right) {
					x = aimX + quiver;
					y = headYaw;
					z = 0.02f;
				} else {
					x = aimX + 0.3f;
					y = headYaw + 0.6f;
					z = 0;
				}
			} else if (held == BendingAnimPacket.BOULDER_HOLD) {
				// Boulder Toss: both arms straight up under the rock, straining a little with its weight
				float secs = (System.currentTimeMillis() - heldSince) / 1000f;
				float strain = Math.min(0.05f, 0.015f + 0.012f * secs) * Mth.sin(time * 1.7f + side);
				x = -2.95f + strain;
				y = 0;
				z = side * 0.32f;
			} else if (held == BendingAnimPacket.BUBBLE_HOLD) {
				// arms stretched out holding the bubble, trembling harder the longer it is held
				float secs = (System.currentTimeMillis() - heldSince) / 1000f;
				float amp = Math.min(0.25f, 0.03f + 0.02f * secs);
				x = -1.4f + amp * Mth.sin(time * 2.3f + side);
				y = side * 0.4f;
				z = side * (1.1f + amp * 0.6f * Mth.sin(time * 1.9f));
			} else if (held == BendingAnimPacket.ICE_AIM) {
				// Ice Spike: the right hand drawn back over the shoulder like a javelin, the left pointing at the aim
				float secs = (System.currentTimeMillis() - heldSince) / 1000f;
				float tremble = Math.min(0.05f, 0.02f * secs) * Mth.sin(time * 2.1f);
				if (right) {
					x = -2.75f + tremble;
					y = 0.25f;
					z = 0.3f;
				} else {
					x = aimX + 0.1f;
					y = headYaw + 0.15f;
					z = 0.05f;
				}
			} else if (held == BendingAnimPacket.MJOLNIR_FLY) {
				// Mjölnir flight: hammer arm straight at the aim, the other swept back along the body
				if (right) {
					x = aimX;
					y = headYaw;
					z = 0;
				} else {
					x = 0.45f;
					y = 0;
					z = -0.2f;
				}
			} else if (held == BendingAnimPacket.MJOLNIR_SPIN) {
				// Mjölnir: the right arm swung round and round at the side (same angle as the server's sparks), the left out for balance
				float secs = (System.currentTimeMillis() - heldSince) / 1000f;
				if (right) {
					if (now == BendingAnimPacket.MJOLNIR_SPIN || Float.isNaN(spinStopX)) {
						double a = com.toast.aurapoints.ability.Mjolnir.spinAngle(secs + tickDelta / 20.0);
						spinStopX = (float) Mth.wrapDegrees(Math.toDegrees(-(Math.PI / 2) - a)) * Mth.DEG_TO_RAD;
					}
					// let go: no more turning - the arm just lowers from where the hammer stopped
					x = spinStopX;
					y = 0;
					z = 0.3f;
				} else {
					x = -0.35f;
					y = 0;
					z = -0.35f;
				}
			} else if (held == BendingAnimPacket.CHIDORI_HOLD) {
				// Chidori: the right hand held low and out at the side, buzzing; the left reaches across to grip its wrist
				float secs = (System.currentTimeMillis() - heldSince) / 1000f;
				float buzz = Math.min(0.06f, 0.03f + 0.02f * secs) * Mth.sin(time * 3.7f);
				if (right) {
					x = -0.45f + buzz;
					y = 0.1f;
					z = 0.35f + buzz * 0.5f;
				} else {
					x = -0.95f;
					y = 0.7f;
					z = 0.05f;
				}
			} else if (held == BendingAnimPacket.GLIDE) {
				// Featherfall: arms spread like wings, swaying gently
				x = -0.25f + 0.06f * Mth.sin(time * 0.15f);
				y = 0;
				z = side * (1.35f + 0.08f * Mth.sin(time * 0.15f + side));
			} else {
				// Dash Dive: arms straight up while plunging
				x = -3.0f;
				y = 0;
				z = side * 0.15f;
			}
			float hw = w * heldBlend();
			return new Vec3f(Mth.lerp(hw, value0.getX(), x), Mth.lerp(hw, value0.getY(), y), Mth.lerp(hw, value0.getZ(), z));
		}
		if (throwTick >= 0 || fading == BendingAnimPacket.THROW) {
			// thrust: from where the flame was to straight at the aim, reaching a bit past it, then settling
			float p = fading == BendingAnimPacket.THROW ? 1f : Math.min(1f, (throwTick + tickDelta) / THROW_TICKS);
			float out = 1 - (1 - p) * (1 - p) * (1 - p); // ease out
			float punch = Mth.sin(p * (float) Math.PI);
			if (right) {
				// from the side hold, the raised Air Bending hold (an overhead hurl), or the front
				float startX = Mth.lerp(fromRaise, Mth.lerp(fromSide, aimX, HOLD_X), raiseX);
				float startY = Mth.lerp(fromRaise, Mth.lerp(fromSide, headYaw - REACH_IN, HOLD_Y), raiseY);
				float startZ = Mth.lerp(fromRaise, HOLD_Z * fromSide, raiseZ);
				x = Mth.lerp(out, startX, aimX) - 0.25f * punch;
				y = Mth.lerp(out, startY, headYaw) - 0.15f * punch;
				z = Mth.lerp(out, startZ, 0);
			} else {
				// the left arm swings back for balance
				x = -0.3f + 0.5f * punch;
				y = 0.3f;
				z = -0.15f;
			}
		} else {
			float s = Mth.lerp(tickDelta, sideO, side);
			// both arms reach for the flame in front, with a faint tremble while it charges
			float tremble = 0.035f * Mth.sin(time * 1.7f);
			if (right) {
				x = Mth.lerp(s, aimX + tremble, HOLD_X);
				y = Mth.lerp(s, headYaw - REACH_IN, HOLD_Y);
				z = Mth.lerp(s, 0, HOLD_Z);
			} else {
				// once the flame is at the side, the left arm goes back to its normal swing
				float reachX = aimX - tremble, reachY = headYaw + REACH_IN;
				x = Mth.lerp(s, reachX, value0.getX());
				y = Mth.lerp(s, reachY, value0.getY());
				z = Mth.lerp(s, 0, value0.getZ());
			}
			float r = Mth.lerp(tickDelta, raiseO, raise);
			if (r > 0) {
				// Air Bending at full charge: the right arm out to the side with the wind charge on the hand
				// (held still, so it stays where the server places the charge); the left arm sways a little
				r = r * r * (3 - 2 * r);
				float sway = 0.03f * Mth.sin(time * 0.12f);
				float rx = right ? raiseX : RAISE_LEFT_X + sway;
				float ry = right ? raiseY : 0f;
				float rz = right ? raiseZ : RAISE_LEFT_Z;
				x = Mth.lerp(r, x, rx);
				y = Mth.lerp(r, y, ry);
				z = Mth.lerp(r, z, rz);
			}
		}
		return new Vec3f(
				Mth.lerp(w, value0.getX(), x),
				Mth.lerp(w, value0.getY(), y),
				Mth.lerp(w, value0.getZ(), z));
	}

	/**
	 * Did this player do anything that ends an idle: move off the spot it began on, swing, get hurt, use an
	 * item, crouch, leave the ground? (Measured from the start spot - this runs before the tick's movement.)
	 */
	private boolean idleInterrupted() {
		double dx = player.getX() - idleX, dz = player.getZ() - idleZ;
		return dx * dx + dz * dz > 0.01 || Math.abs(player.getY() - idleY) > 0.2 || player.swinging || player.hurtTime > 0
				|| player.isUsingItem() || player.isCrouching() || !player.onGround();
	}

	/** The head during a gesture that has head keys (idles), blended in like the arms; {@code look} otherwise. */
	private Vec3f gestureHead(float tickDelta, Vec3f look) {
		if (gestureTick < 0 && fading != BendingAnimPacket.GESTURE) return look;
		float w = Mth.lerp(tickDelta, weightO, weight);
		if (w <= 0) return look;
		w = w * w * (3 - 2 * w);
		float t = fading == BendingAnimPacket.GESTURE ? gestureFadeT : gestureTick + tickDelta;
		Vec3f h = Gestures.head(gestureKind, gestureVariant, t, look);
		if (h == null) return look;
		return new Vec3f(Mth.lerp(w, look.getX(), Mth.clamp(h.getX(), -1.4f, 1.4f)), Mth.lerp(w, look.getY(), h.getY()), Mth.lerp(w, look.getZ(), h.getZ()));
	}

	/** This player's animation layer (null before Player Animator has made it). */
	public static BendingAnimation of(AbstractClientPlayer player) {
		var anim = dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess.getPlayerAssociatedData(player).get(ClientAnimations.BENDING);
		return anim instanceof BendingAnimation b ? b : null;
	}

	/** The gesture playing now: {kind, variant, tick}, or null. */
	public int[] gesture() {
		return gestureTick < 0 || gestureTick >= Integer.MAX_VALUE / 2 ? null : new int[]{gestureKind, gestureVariant, gestureTick};
	}

	/** The idle playing now (an Idle ordinal), or -1. */
	public int idleVariant() {
		return gestureKind == BendingAnimPacket.GESTURE_IDLE && gestureTick >= 0 && gestureTick < Integer.MAX_VALUE / 2 ? gestureVariant : -1;
	}

	/** Ticks into the idle playing now, or -1. */
	public int idleTick() {
		return idleVariant() < 0 ? -1 : gestureTick;
	}

	/** How much higher the feet sit with the legs in this stance (blocks; a leg is 0.75 long), the further leg counting. */
	private static float feetDrop(float[] body) {
		float right = (float) Math.hypot(body[4] * 0.8f, body[5]), left = (float) Math.hypot(body[4] * 0.6f, body[5]);
		return 0.75f * (1f - Mth.cos(Math.max(right, left))) * body[6];
	}

	private static boolean isHeldPose(byte state) {
		return state == BendingAnimPacket.BUBBLE_HOLD || state == BendingAnimPacket.GLIDE || state == BendingAnimPacket.DIVE
				|| state == BendingAnimPacket.ICE_AIM || state == BendingAnimPacket.CHIDORI_HOLD || state == BendingAnimPacket.MJOLNIR_SPIN
				|| state == BendingAnimPacket.MJOLNIR_FLY || state == BendingAnimPacket.VIPER_STEER || state == BendingAnimPacket.BOULDER_HOLD
				|| state == BendingAnimPacket.PRISM_AIM;
	}

	/** The held pose drawn now: the current one, or the one fading out (NONE if an orb pose or nothing). */
	private byte heldShown() {
		byte now = state();
		return isHeldPose(now) ? now : now == BendingAnimPacket.NONE ? lastHeld : BendingAnimPacket.NONE;
	}

	/** A held pose eases in over its first moments (after a gesture it would otherwise snap into place). */
	private float heldBlend() {
		if (state() == BendingAnimPacket.NONE) return 1f;
		float t = Mth.clamp((System.currentTimeMillis() - heldSince) / 180f, 0f, 1f);
		return t * t * (3 - 2 * t);
	}

	/**
	 * Whether the rest of the body moves too: the client setting, and never in first person (the camera
	 * would swing with it) or in poses the game already turns the body for (elytra, swimming, sleeping, riding).
	 */
	private boolean bodyOn() {
		return com.toast.aurapoints.config.AuraClientConfig.FULL_BODY.get() && !FirstPersonMode.isFirstPersonPass()
				&& !player.isFallFlying() && !player.isVisuallySwimming() && !player.isSleeping() && !player.isPassenger()
				&& !player.isAutoSpinAttack();
	}

	/**
	 * The body for this frame, {lean, twist, tilt, drop, stride, spread, plant} (see Gestures.body), already
	 * blended in like the arms. Null when it stays as it is.
	 */
	private float[] bodyPose(float tickDelta) {
		float w = Mth.lerp(tickDelta, weightO, weight);
		if (w <= 0) return null;
		w = w * w * (3 - 2 * w);
		float[] b = null;
		if (gestureTick >= 0 || fading == BendingAnimPacket.GESTURE) {
			float t = fading == BendingAnimPacket.GESTURE ? gestureFadeT : gestureTick + tickDelta;
			b = Gestures.body(gestureKind, gestureVariant, t);
		} else if (slamTick >= 0 || fading == BendingAnimPacket.SLAM) {
			// Inferno Slam: rearing back as the arms go up, then bent over the impact, feet wide
			float t = fading == BendingAnimPacket.SLAM ? slamHoldEnd + SLAM_RETURN : slamTick + tickDelta;
			float lean, drop, spread;
			if (t < slamUpEnd) {
				float e = t / slamUpEnd;
				lean = -0.2f * e;
				drop = 0;
				spread = 0.2f * e;
			} else if (t < slamDownEnd) {
				float e = (t - slamUpEnd) / (slamDownEnd - slamUpEnd);
				e = e * e;
				lean = Mth.lerp(e, -0.2f, 0.45f);
				drop = 0.15f * e;
				spread = Mth.lerp(e, 0.2f, 0.38f);
			} else {
				float back = Math.max(0f, Math.min(1f, (t - slamHoldEnd) / SLAM_RETURN));
				back = back * back * (3 - 2 * back);
				lean = 0.45f * (1 - back);
				drop = 0.15f * (1 - back);
				spread = 0.38f * (1 - back);
			}
			b = new float[]{lean, 0, 0, drop, 0, spread, spread > 0.01f ? 1 : 0};
		} else {
			byte held = heldShown();
			if (held != BendingAnimPacket.NONE && throwTick < 0) {
				b = switch (held) {
					case BendingAnimPacket.BUBBLE_HOLD -> new float[]{-0.06f, 0, 0, 0.03f, 0, 0.28f, 1};
					case BendingAnimPacket.GLIDE -> new float[]{0.3f, 0, 0, 0, 0, 0.12f, 1};
					case BendingAnimPacket.DIVE -> new float[]{0.6f, 0, 0, 0, 0, 0.05f, 1};
					case BendingAnimPacket.ICE_AIM -> new float[]{-0.05f, -0.35f, 0, 0, -0.3f, 0, 1};
					case BendingAnimPacket.CHIDORI_HOLD -> new float[]{0.25f, 0, 0, 0.08f, 0.35f, 0, 1};
					case BendingAnimPacket.MJOLNIR_SPIN -> new float[]{-0.05f, 0, 0, 0, 0, 0.2f, 1};
					case BendingAnimPacket.MJOLNIR_FLY -> new float[]{1.1f, 0, 0, 0, 0, 0.05f, 1};
					case BendingAnimPacket.VIPER_STEER -> new float[]{0.12f, 0.1f, 0, 0, 0.25f, 0, 1};
					case BendingAnimPacket.BOULDER_HOLD -> new float[]{-0.08f, 0, 0, 0.05f, 0, 0.32f, 1};
					case BendingAnimPacket.PRISM_AIM -> new float[]{0.08f, 0.15f, 0, 0.03f, 0.3f, 0, 1};
					default -> null;
				};
				if (b != null) {
					float hb = heldBlend();
					for (int i = 0; i < b.length; i++) b[i] *= hb;
				}
			} else if (throwTick >= 0 || fading == BendingAnimPacket.THROW) {
				// the orb is thrown from the hips: turned back, then through
				float p = fading == BendingAnimPacket.THROW ? 1f : Math.min(1f, (throwTick + tickDelta) / THROW_TICKS);
				float out = 1 - (1 - p) * (1 - p) * (1 - p);
				float punch = Mth.sin(p * (float) Math.PI);
				b = new float[]{0.12f * punch, Mth.lerp(out, -0.2f, 0.3f), 0, 0.02f * punch, 0.3f * out, 0, out};
			} else {
				byte now = state();
				if (now == BendingAnimPacket.CHARGING || now == BendingAnimPacket.READY || now == BendingAnimPacket.READY_SIDE
						|| now == BendingAnimPacket.AIR_RAISED) {
					// holding an orb: feet nearly together, turned toward the flame at the side, slowly shifting weight
					// from foot to foot; only half planted, so the legs still swing when walking
					float sd = Mth.lerp(tickDelta, sideO, side);
					float shift = 0.08f * Mth.sin((player.tickCount + tickDelta) * 0.12f);
					b = new float[]{0.05f, -0.15f * sd, 0, 0, shift, now == BendingAnimPacket.AIR_RAISED ? 0.05f : 0.04f, 0.5f};
				}
			}
		}
		if (b == null) return null;
		float[] out = b.clone();
		for (int i = 0; i < out.length; i++) out[i] *= w;
		return out;
	}

	/**
	 * First person: show the animated arms themselves (the held items step aside meanwhile) - except
	 * while gliding or diving, which last long and whose arms would be out of view anyway.
	 */
	@Override
	public FirstPersonMode getFirstPersonMode(float tickDelta) {
		// the client config can keep your own first-person hand normal (or switch everything off)
		if (com.toast.aurapoints.config.AuraClientConfig.ANIMATIONS.get() != com.toast.aurapoints.config.AuraClientConfig.AnimationMode.ALL) {
			return FirstPersonMode.NONE;
		}
		// your own idles in first person: a client setting
		if (gestureKind == BendingAnimPacket.GESTURE_IDLE && (gestureTick >= 0 || fading == BendingAnimPacket.GESTURE)
				&& !com.toast.aurapoints.config.AuraClientConfig.IDLE_FIRST_PERSON.get()) {
			return FirstPersonMode.NONE;
		}
		byte now = state();
		boolean longPose = now == BendingAnimPacket.GLIDE || now == BendingAnimPacket.DIVE
				|| (now == BendingAnimPacket.NONE && (lastHeld == BendingAnimPacket.GLIDE || lastHeld == BendingAnimPacket.DIVE));
		return weight > 0.05f && !longPose ? FirstPersonMode.THIRD_PERSON_MODEL : FirstPersonMode.NONE;
	}

	@Override
	public FirstPersonConfiguration getFirstPersonConfiguration(float tickDelta) {
		return new FirstPersonConfiguration(true, true, false, false);
	}
}
