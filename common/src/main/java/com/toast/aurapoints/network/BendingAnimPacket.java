package com.toast.aurapoints.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: the Fire Bending pose of player {@code entityId}, sent to everyone who can
 * see them (and the player). The client animates it with Player Animator (client/BendingAnimation).
 */
public record BendingAnimPacket(int entityId, byte state, int windup, int hold) implements CustomPacketPayload {

	public static final byte NONE = 0;
	/** holding the orb in front, charging */
	public static final byte CHARGING = 1;
	/** fully charged, orb stays in front (FIRE_BENDING_FULL_SIDE off) */
	public static final byte READY = 2;
	/** fully charged, orb held above the right hand at the side (FIRE_BENDING_FULL_SIDE) */
	public static final byte READY_SIDE = 3;
	/** the shot - plays once, then back to NONE */
	public static final byte THROW = 4;
	/** Inferno Slam: arms up over the head, then slammed down onto the ground - plays once, then back to NONE */
	public static final byte SLAM = 5;
	/** a short one-shot arm move for an ability (client/Gestures): windup = the gesture kind, hold = its variant */
	public static final byte GESTURE = 6;

	/** Held poses (stay until the server sends another state): Abyssal Bubble held, Featherfall glide, Dash Dive falling. */
	public static final byte BUBBLE_HOLD = 7, GLIDE = 8, DIVE = 9;
	/**
	 * Air Bending fully charged: the right arm raised out to the side, the wind charge on the hand.
	 * windup carries the outward angle ({@link #armAngle}), hold the forward angle and how far the whole
	 * arm is moved down ({@link #armHold}) - the same values the server places the charge with.
	 */
	public static final byte AIR_RAISED = 10;
	/** Ice Spike charging: the right arm drawn back over the shoulder like a javelin, the left pointing at the aim. */
	public static final byte ICE_AIM = 11;
	/** Chidori: right hand low at the side full of lightning, the left gripping its wrist. */
	public static final byte CHIDORI_HOLD = 12;
	/** Mjölnir: the right arm whirling the hammer round at the side like a windmill, faster and faster. */
	public static final byte MJOLNIR_SPIN = 13;
	/** Mjölnir flight: the right arm stretched out at the aim, hammer first, the left swept back. */
	public static final byte MJOLNIR_FLY = 14;
	/** Verdant Viper steered: the right hand stretched out guiding the snake, leaning into it. */
	public static final byte VIPER_STEER = 15;
	/** Earth's Boulder Toss held: both arms straight up, the boulder hanging over the head. */
	public static final byte BOULDER_HOLD = 16;
	/** Light's Prism Beam held: the right arm pointed at the aim, the left hand gripping its forearm. */
	public static final byte PRISM_AIM = 17;

	private static final int ANGLE_SPAN = 2_000_000;

	/**
	 * An arm angle in degrees -> the int sent with AIR_RAISED (hundredths, offset so 0 means "none sent"),
	 * with how far the whole arm moves forward and to the right above it (half pixels, 0..20 each).
	 */
	public static int armAngle(double degrees, double forwardPixels, double rightPixels) {
		int a = Math.max(0, Math.min(ANGLE_SPAN - 1, (int) Math.round(degrees * 100) + 1_000_000));
		int f = Math.max(0, Math.min(20, (int) Math.round(forwardPixels * 2)));
		int r = Math.max(0, Math.min(20, (int) Math.round(rightPixels * 2)));
		return (f * 21 + r) * ANGLE_SPAN + a;
	}

	/** The angle in radians, or NaN if none was sent. */
	public static float armAngleRad(long sent) {
		return sent == 0 ? Float.NaN : (float) Math.toRadians(((sent % ANGLE_SPAN) - 1_000_000) / 100.0);
	}

	/** How far the whole arm moves forward, in pixels. */
	public static float armAngleForward(long sent) {
		return (sent / ANGLE_SPAN) / 21 / 2f;
	}

	/** How far the whole arm moves to the right (outward), in pixels. */
	public static float armAngleRight(long sent) {
		return (sent / ANGLE_SPAN) % 21 / 2f;
	}

	/** armHold's first two parts (forward, drop) span this; the twist sits above them. */
	private static final int HOLD_SPAN = 2_010_000;

	/**
	 * Forward angle (tenths of a degree, -500..500), drop (tenths of a pixel, 0..20) and twist of the arm
	 * about its own length (whole degrees, 0..359) in one int.
	 */
	public static int armHold(double forwardDegrees, double dropPixels, double twistDegrees) {
		int f = Math.max(0, Math.min(9999, (int) Math.round(forwardDegrees * 10) + 5000));
		int d = Math.max(0, Math.min(200, (int) Math.round(dropPixels * 10)));
		int t = Math.floorMod((int) Math.round(twistDegrees), 360);
		return t * HOLD_SPAN + d * 10000 + f;
	}

	public static float armHoldForwardRad(long hold) {
		return (float) Math.toRadians((((hold % HOLD_SPAN) % 10000) - 5000) / 10.0);
	}

	public static float armHoldDrop(long hold) {
		return ((hold % HOLD_SPAN) / 10000) / 10f;
	}

	public static float armHoldTwistRad(long hold) {
		return (float) Math.toRadians(hold / HOLD_SPAN);
	}

	/**
	 * The raised arm (xRot forward, zRot out) turned {@code twist} about its own length: the model
	 * rotations {x, y, z} for it, and how far (pixels, model space) the shoulder pivot moves {px, py, pz}
	 * so it turns about the arm's middle instead of the joint - the fingertips' middle stays put.
	 */
	public static float[] twistedArm(float xRot, float zRot, float twist) {
		if (Math.abs(twist) < 1.0e-4f) return new float[]{xRot, 0, zRot, 0, 0, 0};
		org.joml.Matrix3f base = new org.joml.Matrix3f().rotationZYX(zRot, 0, xRot);
		org.joml.Vector3f euler = new org.joml.Matrix3f(base).rotateY(twist).getEulerAnglesZYX(new org.joml.Vector3f());
		// the arm's middle line is at local x -1: its twisted twin lands at Ry(twist)*(-1,0,0); move the pivot back by the difference
		org.joml.Vector3f shift = base.transform(new org.joml.Vector3f(-1f + (float) Math.cos(twist), 0f, -(float) Math.sin(twist)));
		return new float[]{euler.x, euler.y, euler.z, shift.x, shift.y, shift.z};
	}

	/** The Air Bending arm rotations (model xRot, zRot) for the configured angles: swung forward, then raised out. */
	public static float airArmX(double forwardDegrees) {
		return (float) -Math.toRadians(forwardDegrees);
	}

	/** True for the poses that are held until replaced (the orb poses and the ones above). */
	public static boolean isHeld(byte state) {
		return (state >= CHARGING && state <= READY_SIDE) || (state >= BUBBLE_HOLD && state <= PRISM_AIM);
	}

	/** Gesture kinds and their variants (shared with the server, which picks them). */
	public static final int GESTURE_FIREBALL = 1;
	public static final int FIREBALL_OVERHAND = 0, FIREBALL_SIDEARM = 1, FIREBALL_PUSH = 2, FIREBALL_AIR = 3, FIREBALL_WATER = 4;
	public static final int GESTURE_SHOCKWAVE = 2; // 0 hammer, 1 backhand (ground), 2 air, 3 water
	public static final int GESTURE_HEALING_TIDE = 3; // 0 rising palms, 1 open from the chest (ground), 2 water
	public static final int GESTURE_BUBBLE_POP = 4, GESTURE_BUBBLE_RELEASE = 5;
	public static final int GESTURE_VANISH = 6; // 0 crossed arms, 1 cloak sweep
	public static final int GESTURE_DASH = 7; // 0 ground lunge, 1 air tuck
	public static final int GESTURE_PHANTOM_STEP = 8;
	public static final int GESTURE_WIND_CHARGE = 9; // 0 palm thrust, 1 two-handed shove (ground), 2 air slam
	public static final int GESTURE_DIVE_LAND = 10;
	public static final int GESTURE_DOUBLE_JUMP = 11; // 0 arms whip down, 1 one arm up
	public static final int GESTURE_HEAT_WAVE = 12; // 0 fling wide, 1 circle sweep
	public static final int GESTURE_ICE_SPIKE = 13; // the javelin throw, from the ICE_AIM pose
	public static final int GESTURE_VINE_HOOK = 14; // both hands reach for the target, then yank back
	public static final int GESTURE_CHIDORI = 16; // the right hand driven straight at the aim
	public static final int GESTURE_THUNDERBOLT = 15; // 0 overhand hurl, 1 off-hand flick, 2 Wrath (arms to the sky, slam down)
	public static final int GESTURE_ENTANGLE = 17; // a hand driven into the ground, then clawed up as the roots rise
	public static final int GESTURE_VIPER = 18; // a low sweep across the ground, then the hand sent out after the snake
	public static final int GESTURE_WINTERS_GATE = 19; // both hands low, heaved up to raise the wall
	public static final int GESTURE_MAELSTROM = 20; // the anchor swung up two-handed and slammed down
	public static final int GESTURE_STORM_EYE = 21; // a wound-up spinning sidearm throw
	public static final int GESTURE_PLANT = 22; // down on one knee, a palm pressed to the ground
	public static final int GESTURE_GRASP = 23; // a clawed hand thrust out at the target
	/** An idle animation while standing still (ability/IdleAnimations): the variant is an {@link Idle} ordinal. */
	public static final int GESTURE_IDLE = 24;
	/** An Ultimate's full-body move (ability/Ultimates): the variant is its index in Ultimates.ORDER. */
	public static final int GESTURE_ULTIMATE = 25;
	/** Earth (client/AuraGestures): 0 Pillar Lift uppercut, 1 Boulder Toss throw (from BOULDER_HOLD), 2 Quake slam */
	public static final int GESTURE_EARTH = 26;
	/** Light: 0 Radiant Pulse (hands flung open from the chest), 1 Solar Flash (palm thrust, the other arm over the eyes) */
	public static final int GESTURE_LIGHT = 27;
	/** Blood: 0 Hemorrhage (a clawing slash), 1 Blood Tether (a hand thrust out, then pulled back), 2 Crimson Sacrifice (palm cut, fist raised) */
	public static final int GESTURE_BLOOD = 28;
	/** Void: 0 Void Pull (an open hand out, clenched and yanked back), 1 Rift Step (arms crossed, torn apart), 2 Null Orb (cupped hands pushed out) */
	public static final int GESTURE_VOID = 29;

	/**
	 * The idle animations: whose they are (null = anyone's) and how many ticks they last (client/IdleGestures
	 * keys them to end exactly there). The variant sent is the ordinal - only ever add at the end.
	 */
	public enum Idle {
		FIRE_DOODLE(com.toast.aurapoints.ability.AuraType.FIRE, 90),
		FIRE_HOT_HANDS(com.toast.aurapoints.ability.AuraType.FIRE, 110),
		FIRE_JUGGLE(com.toast.aurapoints.ability.AuraType.FIRE, 100),
		WATER_BUBBLES(com.toast.aurapoints.ability.AuraType.WATER, 100),
		WATER_FLOW(com.toast.aurapoints.ability.AuraType.WATER, 100),
		WATER_WET_DOG(com.toast.aurapoints.ability.AuraType.WATER, 70),
		WIND_WHIRL(com.toast.aurapoints.ability.AuraType.WIND, 90),
		WIND_LEAN(com.toast.aurapoints.ability.AuraType.WIND, 110),
		WIND_HAT(com.toast.aurapoints.ability.AuraType.WIND, 80),
		FROST_BREATH(com.toast.aurapoints.ability.AuraType.FROST, 100),
		FROST_SHIVER(com.toast.aurapoints.ability.AuraType.FROST, 80),
		FROST_SNOWFLAKE(com.toast.aurapoints.ability.AuraType.FROST, 90),
		SHADOW_PUPPET(com.toast.aurapoints.ability.AuraType.SHADOW, 100),
		SHADOW_PARANOID(com.toast.aurapoints.ability.AuraType.SHADOW, 90),
		SHADOW_FLICKER(com.toast.aurapoints.ability.AuraType.SHADOW, 90),
		VERDANT_SPROUT(com.toast.aurapoints.ability.AuraType.VERDANT, 110),
		VERDANT_STRETCH(com.toast.aurapoints.ability.AuraType.VERDANT, 90),
		VERDANT_VINE(com.toast.aurapoints.ability.AuraType.VERDANT, 100),
		THUNDER_ZAP(com.toast.aurapoints.ability.AuraType.THUNDER, 80),
		THUNDER_KNUCKLES(com.toast.aurapoints.ability.AuraType.THUNDER, 80),
		THUNDER_OVERCHARGE(com.toast.aurapoints.ability.AuraType.THUNDER, 90),
		STRETCH(null, 80),
		LOOK_AROUND(null, 90),
		WAITING(null, 100);

		public final com.toast.aurapoints.ability.AuraType aura;
		public final int length;

		Idle(com.toast.aurapoints.ability.AuraType aura, int length) {
			this.aura = aura;
			this.length = length;
		}

		public static Idle byId(int id) {
			Idle[] all = values();
			return id >= 0 && id < all.length ? all[id] : null;
		}

		/** An aura's own idles (none for no aura, or an aura without its own yet: those only get the shared ones). */
		public static java.util.List<Idle> of(com.toast.aurapoints.ability.AuraType aura) {
			java.util.List<Idle> out = new java.util.ArrayList<>();
			if (aura == null) return out;
			for (Idle idle : values()) if (idle.aura == aura) out.add(idle);
			return out;
		}

		public static java.util.List<Idle> shared() {
			return java.util.Arrays.stream(values()).filter(i -> i.aura == null).toList();
		}
	}

	public static final Type<BendingAnimPacket> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "bending_anim"));

	public static final StreamCodec<ByteBuf, BendingAnimPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BendingAnimPacket::entityId,
			ByteBufCodecs.BYTE, BendingAnimPacket::state,
			ByteBufCodecs.VAR_INT, BendingAnimPacket::windup,
			ByteBufCodecs.VAR_INT, BendingAnimPacket::hold,
			BendingAnimPacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
