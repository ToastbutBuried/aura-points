package com.toast.aurapoints.client;

import com.toast.aurapoints.network.BendingAnimPacket;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.util.Mth;

/**
 * CLIENT ONLY. Short one-shot arm moves for abilities (BendingAnimPacket.GESTURE), as keyframes:
 * at tick {@code t} each arm is at a pose (pitch x, yaw y, roll z in radians), blended smoothly in
 * between. A pose can be "rest" (the normal vanilla arm) or relative to where the player aims, so a
 * throw always goes toward the crosshair. Angles are for the right arm (x: 0 hanging, -1.57 forward,
 * -3.14 straight up, positive = backward; y and z: positive = outward); the left arm mirrors y and z.
 * Every gesture ends at rest, so it can fade out without a jump.
 */
public final class Gestures {

	private Gestures() {
	}

	/** A keyframe: {@code rest} = the vanilla pose; {@code aim} = x/y are offsets from the aim direction. */
	record Key(float t, float x, float y, float z, boolean aim, boolean rest) {
	}

	static Key k(float t, float x, float y, float z) {
		return new Key(t, x, y, z, false, false);
	}

	static Key aim(float t, float dx, float dy, float z) {
		return new Key(t, dx, dy, z, true, false);
	}

	static Key rest(float t) {
		return new Key(t, 0, 0, 0, false, true);
	}

	/** The same keys for both arms (mirrored). */
	static Key[][] both(Key... keys) {
		return new Key[][]{keys, keys};
	}

	/** {right arm keys, left arm keys} for a gesture, or null if unknown. */
	private static Key[][] keys(int kind, int variant) {
		switch (kind) {
			case BendingAnimPacket.GESTURE_FIREBALL:
				return switch (variant) {
					// cock the arm up behind the head, whip it over toward the aim, follow through across the body
					case BendingAnimPacket.FIREBALL_OVERHAND -> new Key[][]{
							{rest(0), k(2, -2.8f, 0.3f, 0.35f), aim(3.5f, -0.25f, 0, 0.05f), k(6, -0.7f, -0.4f, 0.05f), rest(10)},
							{rest(0), aim(2, 0.2f, -0.2f, -0.1f), k(4, -0.2f, 0, -0.3f), rest(10)}};
					// swing the arm out wide to the side, then sweep it across in a flat hook
					case BendingAnimPacket.FIREBALL_SIDEARM -> new Key[][]{
							{rest(0), k(2, -1.3f, 1.2f, 0.35f), aim(3.5f, 0, 0, 0), k(6, -1.2f, -0.8f, 0), rest(10)},
							{rest(0), k(2, -0.2f, 0, -0.45f), k(5, -0.6f, 0.3f, -0.1f), rest(10)}};
					// both hands pulled in to the chest, then thrust out together
					case BendingAnimPacket.FIREBALL_PUSH -> both(
							rest(0), k(2.5f, -1.0f, -0.6f, 0), aim(4, 0, -0.1f, 0), aim(7, 0.1f, -0.1f, 0), rest(11));
					// in the air: both arms flung up wide, then a downward hurl while the other arm swings back
					case BendingAnimPacket.FIREBALL_AIR -> new Key[][]{
							{rest(0), k(2, -2.7f, 0, 0.7f), aim(4, 0.2f, 0, 0.05f), k(7, -0.8f, -0.2f, 0.1f), rest(11)},
							{rest(0), k(2, -2.7f, 0, 0.7f), k(4, 0.5f, 0, 0.4f), k(7, 0.2f, 0, 0.2f), rest(11)}};
					// in water: a wide breaststroke sweep, then both hands push the fireball forward
					case BendingAnimPacket.FIREBALL_WATER -> both(
							rest(0), k(2, -1.4f, 1.0f, 0.2f), aim(4, 0, -0.15f, 0), aim(8, 0, -0.15f, 0), rest(12));
					default -> null;
				};
			case BendingAnimPacket.GESTURE_SHOCKWAVE:
				return switch (variant) {
					// both fists up over the head, hammered down at the feet
					case 0 -> both(rest(0), k(1.5f, -2.9f, 0, 0.15f), k(2.5f, -0.5f, -0.1f, 0.1f), k(5, -0.4f, -0.1f, 0.1f), rest(9));
					// a wide horizontal backhand: in across the body, then swept out
					case 1 -> new Key[][]{
							{rest(0), k(1.5f, -1.4f, -0.9f, 0.1f), k(3, -1.4f, 1.3f, 0.4f), k(5, -1.0f, 1.0f, 0.3f), rest(9)},
							{rest(0), k(2, -0.3f, 0, -0.3f), rest(9)}};
					// in the air: arms crossed at the chest, snapped out wide
					case 2 -> both(rest(0), k(1.5f, -1.2f, -0.7f, 0), k(3, -1.3f, 0.6f, 1.3f), k(6, -0.6f, 0.3f, 1.0f), rest(10));
					// in water: both hands together in front, pushed apart to the sides
					case 3 -> both(rest(0), k(1.5f, -1.5f, -0.4f, 0), k(3.5f, -1.4f, 1.4f, 0.6f), k(6, -0.8f, 1.0f, 0.4f), rest(10));
					default -> null;
				};
			case BendingAnimPacket.GESTURE_HEALING_TIDE:
				return switch (variant) {
					// arms rise slowly, palms up, held open, then lowered
					case 0 -> both(rest(0), k(6, -1.5f, 0.5f, 0.6f), k(10, -1.6f, 0.5f, 0.6f), rest(16));
					// hands meet at the chest, then open outward
					case 1 -> both(rest(0), k(3, -1.2f, -0.8f, 0), k(7, -1.3f, 0.7f, 0.5f), k(10, -1.3f, 0.7f, 0.5f), rest(15));
					// in water: slow flowing circles
					case 2 -> both(rest(0), k(3, -1.8f, 0.6f, 0.5f), k(6, -1.2f, 1.2f, 0.3f), k(9, -0.8f, 0.4f, 0.2f),
							k(12, -1.6f, 0.2f, 0.4f), rest(16));
					default -> null;
				};
			// from the bubble-holding pose (arms out): jerked inward as it bursts / lowered calmly
			case BendingAnimPacket.GESTURE_BUBBLE_POP:
				return both(k(0, -1.4f, 0.4f, 1.1f), k(2, -1.2f, -0.7f, 0.1f), rest(8));
			case BendingAnimPacket.GESTURE_BUBBLE_RELEASE:
				return both(k(0, -1.4f, 0.4f, 1.1f), rest(8));
			case BendingAnimPacket.GESTURE_VANISH:
				return switch (variant) {
					// arms crossed over the face, then swept apart
					case 0 -> both(rest(0), k(2, -2.0f, -0.9f, 0), k(3.5f, -2.0f, -0.9f, 0), k(5.5f, -1.0f, 1.0f, 1.0f), rest(10));
					// one hand drawn across the face like a cloak
					case 1 -> new Key[][]{
							{rest(0), k(2, -2.2f, -1.0f, 0), k(5, -1.3f, 1.2f, 0.6f), rest(10)},
							{rest(0), k(3, -0.4f, 0, -0.2f), rest(10)}};
					default -> null;
				};
			case BendingAnimPacket.GESTURE_DASH:
				return switch (variant) {
					// a sprinter's lunge: arms swept back and low
					case 0 -> both(rest(0), k(1.5f, 0.9f, 0, 0.2f), k(6, 0.8f, 0, 0.2f), rest(10));
					// in the air: arms swept back and a little out, like flying (never in front of the legs)
					case 1 -> both(rest(0), k(1.5f, 0.55f, 0.15f, 0.45f), k(6, 0.6f, 0.15f, 0.5f), rest(10));
					default -> null;
				};
			// landing low: one hand reaching to the ground, the other stretched back
			case BendingAnimPacket.GESTURE_PHANTOM_STEP:
				return new Key[][]{
						{rest(0), k(1, -0.5f, 0.1f, 0.15f), k(6, -0.5f, 0.1f, 0.15f), rest(11)},
						{rest(0), k(1, 1.0f, 0, 0.3f), k(6, 1.0f, 0, 0.3f), rest(11)}};
			case BendingAnimPacket.GESTURE_WIND_CHARGE:
				return switch (variant) {
					// palm thrust from the shoulder, the other arm pulled back
					case 0 -> new Key[][]{
							{rest(0), k(1, -1.0f, -0.2f, 0.2f), aim(2, 0, 0, 0), aim(4, 0.05f, 0, 0), rest(9)},
							{rest(0), k(1.5f, 0.5f, 0, 0.2f), rest(9)}};
					// a two-handed shove
					case 1 -> both(rest(0), k(1, -1.0f, -0.6f, 0), aim(2, 0, -0.1f, 0), aim(5, 0, -0.1f, 0), rest(10));
					// in the air: both hands slammed down from overhead
					case 2 -> both(rest(0), k(1, -3.0f, -0.1f, 0.1f), aim(2.5f, 0.3f, -0.1f, 0), k(5, -0.8f, -0.1f, 0.1f), rest(10));
					default -> null;
				};
			// from the dive pose (arms straight up): thrown wide on the landing shockwave
			case BendingAnimPacket.GESTURE_DIVE_LAND:
				return both(k(0, -3.0f, 0, 0.15f), k(2, -1.0f, 0.2f, 1.4f), k(5, -0.8f, 0.2f, 1.2f), rest(10));
			case BendingAnimPacket.GESTURE_DOUBLE_JUMP:
				return switch (variant) {
					// both arms whipped down, pushing off the air
					case 0 -> both(rest(0), k(1, -1.2f, 0, 0.5f), k(2.5f, 0.3f, 0, 0.4f), rest(8));
					// one arm swept up, the other back
					case 1 -> new Key[][]{{rest(0), k(2, -2.9f, 0.1f, 0.2f), rest(8)}, {rest(0), k(2, 0.4f, 0, 0.3f), rest(8)}};
					default -> null;
				};
			case BendingAnimPacket.GESTURE_HEAT_WAVE:
				return switch (variant) {
					// fists in to the chest, flung wide as the blast goes off
					case 0 -> both(rest(0), k(1, -1.1f, -0.8f, 0), k(2.5f, -1.4f, 0.9f, 1.4f), k(6, -1.0f, 0.7f, 1.2f), rest(11));
					// one arm sweeping a full circle around
					case 1 -> new Key[][]{
							{rest(0), k(1, -1.5f, -1.0f, 0), k(2.5f, -1.5f, 0.3f, 0.3f), k(4, -1.4f, 1.5f, 0.6f), k(6, -0.6f, 1.2f, 0.5f), rest(11)},
							{rest(0), k(2, -0.3f, 0, 0.4f), rest(11)}};
					default -> null;
				};
			// Ice Spike: from the javelin pose, whipped over toward the aim and through; the left arm drops back
			case BendingAnimPacket.GESTURE_ICE_SPIKE:
				return new Key[][]{
						{k(0, -2.75f, 0.25f, 0.3f), aim(2, -0.15f, 0, 0.05f), k(5, -0.8f, -0.35f, 0.05f), rest(10)},
						{aim(0, 0.1f, -0.15f, -0.05f), k(2.5f, 0.3f, 0, -0.25f), rest(10)}};
			// Vine Hook: both hands stretched out at the target while the roots grow, a clench, then a hard yank back to the chest
			case BendingAnimPacket.GESTURE_VINE_HOOK:
				// timed for the default rise + reach (7 ticks): hands out and straining while the roots grow, the yank as they catch
				return both(rest(0), aim(3, -0.05f, -0.12f, 0), aim(6.5f, -0.12f, -0.06f, 0.06f),
						k(8.5f, -0.55f, -0.75f, 0.1f), k(11, -0.5f, -0.7f, 0.1f), rest(16));
			// Chidori: from the low hold, the right hand driven straight out at the aim and held there through the lunge
			case BendingAnimPacket.GESTURE_CHIDORI:
				return new Key[][]{
						{k(0, -0.45f, 0.1f, 0.35f), aim(2, -0.05f, 0, 0.05f), aim(9, 0, 0, 0.05f), rest(14)},
						{k(0, -0.95f, 0.7f, 0.05f), k(2, 0.4f, 0, -0.2f), k(9, 0.35f, 0, -0.2f), rest(14)}};
			// Thunderbolt of Zeus, one per throw (released at tick 6, 2 and the end of the 12-tick Wrath windup)
			case BendingAnimPacket.GESTURE_THUNDERBOLT:
				return switch (variant) {
					// javelin hurl: right arm cocked high behind, left points at the target, whipped over at the release
					case 0 -> new Key[][]{
							{rest(0), k(3.5f, -2.9f, 0.25f, 0.35f), k(5, -3.0f, 0.2f, 0.4f), aim(6.5f, -0.1f, 0, 0.05f), k(9.5f, -0.9f, -0.4f, 0.05f), rest(14)},
							{rest(0), aim(3, 0.15f, -0.25f, -0.1f), aim(6, 0.2f, -0.2f, -0.1f), k(9, -0.2f, 0, -0.3f), rest(14)}};
					// quick flick from the left hand, swung out wide and snapped across
					case 1 -> new Key[][]{
							{rest(0), k(1.5f, -0.5f, 0, 0.25f), rest(9)},
							{rest(0), k(1, -1.4f, 1.0f, 0.3f), aim(2.5f, 0, 0, 0), k(5, -1.1f, -0.7f, 0), rest(10)}};
					// Wrath of Zeus: both arms up to the sky while the storm gathers, then the right one slams down at the aim
					case 2 -> new Key[][]{
							{rest(0), k(3, -3.0f, 0.1f, 0.25f), k(11, -3.1f, 0.05f, 0.3f), aim(13, 0.25f, 0, 0.05f), k(16, -0.8f, -0.2f, 0.1f), rest(21)},
							{rest(0), k(3, -2.9f, 0.1f, 0.45f), k(11, -3.0f, 0.1f, 0.5f), k(13.5f, -0.3f, 0, 0.4f), rest(20)}};
					default -> null;
				};
			// Entanglement: the right hand raised, driven down into the ground, held there, then clawed up as the roots rise
			case BendingAnimPacket.GESTURE_ENTANGLE:
				return new Key[][]{
						{rest(0), k(2, -2.3f, 0.1f, 0.3f), k(4, -0.6f, 0.15f, 0.15f), k(7, -0.55f, 0.15f, 0.15f), k(9.5f, -2.0f, 0.25f, 0.4f), rest(14)},
						{rest(0), k(3, 0.2f, 0, 0.5f), k(7, 0.25f, 0, 0.5f), rest(14)}};
			// Verdant Viper: a low sweep across the ground, out to the side, then the hand sent out after the snake
			case BendingAnimPacket.GESTURE_VIPER:
				return new Key[][]{
						{rest(0), k(2, -0.9f, -0.9f, 0.2f), k(5, -0.7f, 0.9f, 0.4f), aim(8, 0.25f, 0, 0.05f), rest(12)},
						{rest(0), k(2.5f, -0.3f, 0, 0.35f), rest(12)}};
			// Winter's Gate: both hands low in front, palms down, then heaved up high as the wall rises
			case BendingAnimPacket.GESTURE_WINTERS_GATE:
				return both(rest(0), k(3, -0.3f, -0.3f, 0.25f), k(7, -2.4f, 0.2f, 0.5f), k(11, -2.5f, 0.25f, 0.55f), rest(18));
			// Maelstrom: the anchor swung up behind the head with both hands and slammed down
			case BendingAnimPacket.GESTURE_MAELSTROM:
				return both(rest(0), k(4, -3.0f, -0.25f, 0.15f), k(6, -0.5f, -0.3f, 0.1f), k(10, -0.45f, -0.3f, 0.1f), rest(16));
			// Eye of the Storm: the glaive wound back across the body, then whipped out flat in a spinning throw
			case BendingAnimPacket.GESTURE_STORM_EYE:
				return new Key[][]{
						{rest(0), k(3, -1.4f, -1.3f, 0.2f), k(5.5f, -1.4f, 1.2f, 0.4f), k(8, -1.0f, 1.0f, 0.3f), rest(14)},
						{rest(0), k(3, -0.6f, 0.4f, 0.4f), k(5.5f, 0.4f, 0, 0.3f), rest(14)}};
			case BendingAnimPacket.GESTURE_PLANT:
				return switch (variant) {
					// Sonic Blossom: down low, a palm pressed flat to the ground under you
					case 0 -> new Key[][]{
							{rest(0), k(3, -0.5f, 0.1f, 0.1f), k(12, -0.55f, 0.1f, 0.1f), rest(16)},
							{rest(0), k(3, -0.2f, 0, 0.4f), k(12, -0.2f, 0, 0.4f), rest(16)}};
					// Listening Tree: the open hand thrust down at the spot it grows from
					case 1 -> new Key[][]{
							{rest(0), aim(3, 0.3f, 0, 0.1f), aim(8, 0.3f, 0, 0.1f), rest(13)},
							{rest(0), k(3, 0.3f, 0, 0.3f), rest(13)}};
					default -> null;
				};
			// Abyssal Grasp: the hand cocked back by the shoulder, then thrust out clawed at the target
			case BendingAnimPacket.GESTURE_GRASP:
				return new Key[][]{
						{rest(0), k(2, -1.2f, -0.3f, 0.6f), aim(4, -0.05f, 0, -0.1f), aim(8, 0, 0, -0.1f), rest(12)},
						{rest(0), k(3, 0.45f, 0, 0.3f), rest(12)}};
			case BendingAnimPacket.GESTURE_IDLE:
				return IdleGestures.arms(variant);
			case BendingAnimPacket.GESTURE_ULTIMATE:
				return UltimateGestures.arms(variant);
			case BendingAnimPacket.GESTURE_EARTH, BendingAnimPacket.GESTURE_LIGHT, BendingAnimPacket.GESTURE_BLOOD, BendingAnimPacket.GESTURE_VOID:
				return AuraGestures.arms(kind, variant);
			default:
				return null;
		}
	}

	/** How many ticks the gesture lasts (0 if unknown, so it ends at once). */
	public static float length(int kind, int variant) {
		Key[][] k = keys(kind, variant);
		return k == null ? 0 : Math.max(k[0][k[0].length - 1].t(), k[1][k[1].length - 1].t());
	}

	/**
	 * The arm's rotation {@code t} ticks into the gesture. {@code aimX}/{@code headYaw}: the aim
	 * direction as an arm pose; {@code rest}: the vanilla arm. Returns rest when unknown or done.
	 */
	public static Vec3f pose(int kind, int variant, boolean right, float t, float aimX, float headYaw, Vec3f rest) {
		Key[][] all = keys(kind, variant);
		if (all == null) return rest;
		Key[] keys = all[right ? 0 : 1];
		if (t >= keys[keys.length - 1].t()) return resolve(keys[keys.length - 1], right, aimX, headYaw, rest);
		if (t <= keys[0].t()) return resolve(keys[0], right, aimX, headYaw, rest);
		for (int i = 0; i < keys.length - 1; i++) {
			Key a = keys[i], b = keys[i + 1];
			if (t < b.t()) {
				float e = Mth.clamp((t - a.t()) / Math.max(0.01f, b.t() - a.t()), 0, 1);
				e = e * e * (3 - 2 * e);
				Vec3f pa = resolve(a, right, aimX, headYaw, rest), pb = resolve(b, right, aimX, headYaw, rest);
				return new Vec3f(Mth.lerp(e, pa.getX(), pb.getX()), Mth.lerp(e, pa.getY(), pb.getY()), Mth.lerp(e, pa.getZ(), pb.getZ()));
			}
		}
		return rest;
	}

	private static Vec3f resolve(Key k, boolean right, float aimX, float headYaw, Vec3f rest) {
		if (k.rest()) return rest;
		float side = right ? 1f : -1f; // the left arm mirrors yaw and roll
		if (k.aim()) return new Vec3f(aimX + k.x(), headYaw + side * k.y(), side * k.z());
		return new Vec3f(k.x(), side * k.y(), side * k.z());
	}

	// ---------------------------------------------------------------
	// The head (idles only so far): keys are Keys with x = pitch (down), y = yaw (to the right),
	// z = roll; "rest" = where the player is looking. Not mirrored, never aimed.
	// ---------------------------------------------------------------

	private static Key[] headKeys(int kind, int variant) {
		if (kind == BendingAnimPacket.GESTURE_IDLE) return IdleGestures.head(variant);
		if (kind == BendingAnimPacket.GESTURE_ULTIMATE) return UltimateGestures.head(variant);
		return null;
	}

	/** The head {@code t} ticks into a gesture ({@code rest}: where the player looks), or null if the gesture leaves it alone. */
	public static Vec3f head(int kind, int variant, float t, Vec3f rest) {
		Key[] keys = headKeys(kind, variant);
		if (keys == null) return null;
		if (t >= keys[keys.length - 1].t()) return headAt(keys[keys.length - 1], rest);
		if (t <= keys[0].t()) return headAt(keys[0], rest);
		for (int i = 0; i < keys.length - 1; i++) {
			Key a = keys[i], b = keys[i + 1];
			if (t < b.t()) {
				float e = Mth.clamp((t - a.t()) / Math.max(0.01f, b.t() - a.t()), 0, 1);
				e = e * e * (3 - 2 * e);
				Vec3f pa = headAt(a, rest), pb = headAt(b, rest);
				return new Vec3f(Mth.lerp(e, pa.getX(), pb.getX()), Mth.lerp(e, pa.getY(), pb.getY()), Mth.lerp(e, pa.getZ(), pb.getZ()));
			}
		}
		return rest;
	}

	private static Vec3f headAt(Key k, Vec3f rest) {
		return k.rest() ? rest : new Vec3f(k.x(), k.y(), k.z());
	}

	// ---------------------------------------------------------------
	// The rest of the body
	// ---------------------------------------------------------------

	/**
	 * A body keyframe (fullBodyAnimations): {@code lean} forward (radians, negative = back), {@code twist}
	 * (positive = right shoulder forward), {@code tilt} sideways, {@code drop} (blocks the body sinks),
	 * {@code stride} (positive = right leg forward, left back) and {@code spread} (legs apart). A key with
	 * a stride or spread plants the feet in that stance; others leave the legs to walk normally.
	 */
	record B(float t, float lean, float twist, float tilt, float drop, float stride, float spread) {
		boolean planted() {
			return stride != 0 || spread != 0;
		}
	}

	static B b(float t, float lean, float twist, float drop, float stride, float spread) {
		return new B(t, lean, twist, 0, drop, stride, spread);
	}

	static B brest(float t) {
		return new B(t, 0, 0, 0, 0, 0, 0);
	}

	/** The body keys for a gesture, or null if it moves only the arms. */
	private static B[] bodyKeys(int kind, int variant) {
		switch (kind) {
			case BendingAnimPacket.GESTURE_FIREBALL:
				return switch (variant) {
					// the throw comes from the hips: wound back, then turned through into the follow-through
					case BendingAnimPacket.FIREBALL_OVERHAND -> new B[]{brest(0), b(2, -0.05f, -0.35f, 0, -0.25f, 0), b(3.5f, 0.12f, 0.35f, 0.02f, 0.3f, 0),
							b(6, 0.08f, 0.2f, 0.02f, 0.3f, 0), brest(10)};
					case BendingAnimPacket.FIREBALL_SIDEARM -> new B[]{brest(0), b(2, 0, -0.4f, 0, -0.2f, 0), b(3.5f, 0.1f, 0.4f, 0.02f, 0.25f, 0), brest(10)};
					case BendingAnimPacket.FIREBALL_PUSH -> new B[]{brest(0), b(2.5f, -0.08f, 0, 0, 0, 0), b(4, 0.15f, 0, 0.03f, 0.3f, 0), b(7, 0.15f, 0, 0.03f, 0.3f, 0), brest(11)};
					case BendingAnimPacket.FIREBALL_AIR -> new B[]{brest(0), b(2, -0.2f, 0, 0, 0, 0), b(4, 0.25f, 0, 0, 0, 0), brest(11)};
					default -> null;
				};
			case BendingAnimPacket.GESTURE_SHOCKWAVE:
				return switch (variant) {
					// rearing back, then the whole weight into the hammer blow, feet planted wide
					case 0 -> new B[]{brest(0), b(1.5f, -0.15f, 0, 0, 0, 0.2f), b(2.5f, 0.35f, 0, 0.12f, 0, 0.35f), b(5, 0.35f, 0, 0.12f, 0, 0.35f), brest(9)};
					case 1 -> new B[]{brest(0), b(1.5f, 0, 0.35f, 0, 0, 0.15f), b(3, 0.05f, -0.35f, 0.04f, 0, 0.25f), brest(9)};
					case 2 -> new B[]{brest(0), b(1.5f, 0.2f, 0, 0, 0, 0), b(3, -0.15f, 0, 0, 0, 0), brest(10)};
					default -> null;
				};
			case BendingAnimPacket.GESTURE_HEALING_TIDE:
				return switch (variant) {
					case 0 -> new B[]{brest(0), b(6, -0.12f, 0, 0, 0, 0), b(10, -0.12f, 0, 0, 0, 0), brest(16)};
					case 1 -> new B[]{brest(0), b(3, 0.08f, 0, 0, 0, 0), b(7, -0.1f, 0, 0, 0, 0), brest(15)};
					default -> null;
				};
			case BendingAnimPacket.GESTURE_BUBBLE_POP:
				return new B[]{b(0, -0.05f, 0, 0.03f, 0, 0.25f), b(2, 0.15f, 0, 0.03f, 0, 0.25f), brest(8)};
			case BendingAnimPacket.GESTURE_BUBBLE_RELEASE:
				return new B[]{b(0, -0.05f, 0, 0.03f, 0, 0.25f), brest(8)};
			case BendingAnimPacket.GESTURE_VANISH:
				return switch (variant) {
					// hunched behind the crossed arms, then straightening as they sweep apart
					case 0 -> new B[]{brest(0), b(2, 0.15f, 0, 0.06f, 0, 0), b(3.5f, 0.15f, 0, 0.06f, 0, 0), b(5.5f, -0.05f, 0, 0, 0, 0), brest(10)};
					case 1 -> new B[]{brest(0), b(2, 0, 0.3f, 0, 0, 0), b(5, 0, -0.3f, 0, 0, 0), brest(10)};
					default -> null;
				};
			case BendingAnimPacket.GESTURE_DASH:
				return switch (variant) {
					// a sprinter's lunge: low and far forward
					case 0 -> new B[]{brest(0), b(1.5f, 0.4f, 0, 0.05f, 0.5f, 0), b(6, 0.4f, 0, 0.05f, 0.5f, 0), brest(10)};
					case 1 -> new B[]{brest(0), b(1.5f, 0.5f, 0, 0, 0, 0), b(6, 0.5f, 0, 0, 0, 0), brest(10)};
					default -> null;
				};
			// landing low, one leg out in front
			case BendingAnimPacket.GESTURE_PHANTOM_STEP:
				return new B[]{brest(0), b(1, 0.35f, 0, 0.12f, 0.45f, 0.15f), b(6, 0.35f, 0, 0.12f, 0.45f, 0.15f), brest(11)};
			case BendingAnimPacket.GESTURE_WIND_CHARGE:
				return switch (variant) {
					case 0 -> new B[]{brest(0), b(1, 0, -0.2f, 0, 0, 0), b(2, 0.1f, 0.25f, 0.02f, 0.3f, 0), brest(9)};
					case 1 -> new B[]{brest(0), b(1, -0.05f, 0, 0, 0, 0), b(2, 0.2f, 0, 0.04f, 0.35f, 0), b(5, 0.2f, 0, 0.04f, 0.35f, 0), brest(10)};
					case 2 -> new B[]{brest(0), b(1, -0.2f, 0, 0, 0, 0), b(2.5f, 0.35f, 0, 0, 0, 0), brest(10)};
					default -> null;
				};
			// the dive lands: crouched over the impact, feet wide
			case BendingAnimPacket.GESTURE_DIVE_LAND:
				return new B[]{b(0, 0.3f, 0, 0.12f, 0, 0.35f), b(2, 0.2f, 0, 0.14f, 0, 0.4f), b(5, 0.2f, 0, 0.14f, 0, 0.4f), brest(10)};
			case BendingAnimPacket.GESTURE_DOUBLE_JUMP:
				return new B[]{brest(0), b(1, -0.1f, 0, 0, 0, 0), b(2.5f, 0.12f, 0, 0, 0, 0), brest(8)};
			case BendingAnimPacket.GESTURE_HEAT_WAVE:
				return switch (variant) {
					// gathered in, then bursting open
					case 0 -> new B[]{brest(0), b(1, 0.15f, 0, 0.05f, 0, 0.1f), b(2.5f, -0.15f, 0, 0, 0, 0.3f), b(6, -0.15f, 0, 0, 0, 0.3f), brest(11)};
					case 1 -> new B[]{brest(0), b(1, 0, 0.4f, 0, 0, 0.15f), b(4, 0.05f, -0.4f, 0, 0, 0.25f), brest(11)};
					default -> null;
				};
			// from the javelin stance, the hips turn through the throw
			case BendingAnimPacket.GESTURE_ICE_SPIKE:
				return new B[]{b(0, -0.05f, -0.35f, 0, -0.3f, 0), b(2, 0.15f, 0.35f, 0.02f, 0.3f, 0), b(5, 0.1f, 0.2f, 0.02f, 0.3f, 0), brest(10)};
			// leaning into the reach, then thrown back by the yank
			case BendingAnimPacket.GESTURE_VINE_HOOK:
				return new B[]{brest(0), b(3, 0.12f, 0, 0, 0.3f, 0), b(6.5f, 0.12f, 0, 0, 0.3f, 0), b(8.5f, -0.2f, 0, 0, -0.1f, 0.1f),
						b(11, -0.2f, 0, 0, -0.1f, 0.1f), brest(16)};
			case BendingAnimPacket.GESTURE_CHIDORI:
				return new B[]{b(0, 0.25f, 0, 0.08f, 0.35f, 0), b(2, 0.4f, 0.2f, 0.06f, 0.5f, 0), b(9, 0.4f, 0.2f, 0.06f, 0.5f, 0), brest(14)};
			case BendingAnimPacket.GESTURE_THUNDERBOLT:
				return switch (variant) {
					case 0 -> new B[]{brest(0), b(3.5f, -0.1f, -0.35f, 0, -0.3f, 0), b(6.5f, 0.15f, 0.35f, 0.02f, 0.35f, 0), b(9.5f, 0.1f, 0.2f, 0.02f, 0.35f, 0), brest(14)};
					case 1 -> new B[]{brest(0), b(1, 0, 0.35f, 0, 0, 0), b(2.5f, 0, -0.3f, 0, 0, 0), brest(10)};
					// Wrath: arched back under the sky, then bent over the slam
					case 2 -> new B[]{brest(0), b(3, -0.2f, 0, 0, 0, 0.2f), b(11, -0.25f, 0, 0, 0, 0.25f), b(13, 0.35f, 0, 0.1f, 0, 0.35f),
							b(16, 0.35f, 0, 0.1f, 0, 0.35f), brest(20)};
					default -> null;
				};
			case BendingAnimPacket.GESTURE_ENTANGLE:
				return new B[]{brest(0), b(2, -0.05f, 0, 0, 0, 0), b(4.5f, 0.35f, 0.1f, 0.12f, -0.3f, 0.15f), b(8, 0.35f, 0.1f, 0.12f, -0.3f, 0.15f),
						b(10, -0.05f, 0, 0, 0, 0.1f), brest(14)};
			case BendingAnimPacket.GESTURE_VIPER:
				return new B[]{brest(0), b(2, 0.25f, -0.25f, 0.08f, 0, 0.2f), b(5, 0.2f, 0.25f, 0.06f, 0.2f, 0.15f), b(8, 0.12f, 0.1f, 0, 0.25f, 0), brest(12)};
			case BendingAnimPacket.GESTURE_WINTERS_GATE:
				return new B[]{brest(0), b(3, 0.25f, 0, 0.13f, 0, 0.25f), b(7, -0.15f, 0, 0, 0, 0.25f), b(11, -0.15f, 0, 0, 0, 0.25f), brest(18)};
			case BendingAnimPacket.GESTURE_MAELSTROM:
				return new B[]{brest(0), b(4, -0.2f, 0, 0, 0, 0.2f), b(6, 0.4f, 0, 0.14f, 0, 0.35f), b(10, 0.4f, 0, 0.14f, 0, 0.35f), brest(16)};
			case BendingAnimPacket.GESTURE_STORM_EYE:
				return new B[]{brest(0), b(3, 0.05f, -0.45f, 0, -0.25f, 0), b(5.5f, 0.15f, 0.4f, 0.02f, 0.3f, 0), b(8, 0.1f, 0.25f, 0.02f, 0.3f, 0), brest(14)};
			case BendingAnimPacket.GESTURE_PLANT:
				return switch (variant) {
					// down on one knee as far as a stiff-legged block person goes
					case 0 -> new B[]{brest(0), b(3, 0.45f, 0, 0.12f, 0.45f, 0.1f), b(12, 0.45f, 0, 0.12f, 0.45f, 0.1f), brest(16)};
					case 1 -> new B[]{brest(0), b(3, 0.2f, 0, 0.05f, 0.3f, 0), b(8, 0.2f, 0, 0.05f, 0.3f, 0), brest(13)};
					default -> null;
				};
			case BendingAnimPacket.GESTURE_GRASP:
				return new B[]{brest(0), b(2, 0, -0.2f, 0, 0, 0), b(4, 0.15f, 0.25f, 0.02f, 0.3f, 0), b(8, 0.15f, 0.25f, 0.02f, 0.3f, 0), brest(12)};
			case BendingAnimPacket.GESTURE_IDLE:
				return IdleGestures.body(variant);
			case BendingAnimPacket.GESTURE_ULTIMATE:
				return UltimateGestures.body(variant);
			case BendingAnimPacket.GESTURE_EARTH, BendingAnimPacket.GESTURE_LIGHT, BendingAnimPacket.GESTURE_BLOOD, BendingAnimPacket.GESTURE_VOID:
				return AuraGestures.body(kind, variant);
			default:
				return null;
		}
	}

	/**
	 * The body {@code t} ticks into a gesture: {lean, twist, tilt, drop, stride, spread, plant}, plant
	 * being how firmly the legs hold the stance (0 = walking normally). Null if the gesture has no body keys.
	 */
	public static float[] body(int kind, int variant, float t) {
		B[] keys = bodyKeys(kind, variant);
		if (keys == null) return null;
		if (t >= keys[keys.length - 1].t()) return values(keys[keys.length - 1]);
		if (t <= keys[0].t()) return values(keys[0]);
		for (int i = 0; i < keys.length - 1; i++) {
			B a = keys[i], b = keys[i + 1];
			if (t < b.t()) {
				float e = Mth.clamp((t - a.t()) / Math.max(0.01f, b.t() - a.t()), 0, 1);
				e = e * e * (3 - 2 * e);
				float[] va = values(a), vb = values(b);
				float[] out = new float[7];
				for (int j = 0; j < 7; j++) out[j] = Mth.lerp(e, va[j], vb[j]);
				// the legs keep the stance of whichever end is planted, so they don't half-walk in between
				if (a.planted() && b.planted()) out[6] = 1;
				return out;
			}
		}
		return values(keys[keys.length - 1]);
	}

	private static float[] values(B k) {
		return new float[]{k.lean(), k.twist(), k.tilt(), k.drop(), k.stride(), k.spread(), k.planted() ? 1 : 0};
	}
}
