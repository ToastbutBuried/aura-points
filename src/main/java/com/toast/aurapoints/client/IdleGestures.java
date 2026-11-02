package com.toast.aurapoints.client;

import com.toast.aurapoints.client.Gestures.B;
import com.toast.aurapoints.client.Gestures.Key;
import com.toast.aurapoints.network.BendingAnimPacket.Idle;

import java.util.ArrayList;
import java.util.List;

/**
 * CLIENT ONLY. The idle animations' keyframes (BendingAnimPacket.Idle), in the same terms as Gestures:
 * arm angles for the right arm (x: 0 hanging, -1.57 forward, -3.14 straight up, positive = back;
 * y and z: positive = outward; the left arm mirrors), the body (lean forward, twist = right shoulder
 * forward, tilt, drop, stride = right foot forward, spread) and the head (pitch down, yaw to the right,
 * roll; rest = where the player looks). Every track starts at rest and ends at rest on the idle's
 * length, so it blends in and out without a jump. The particles and sounds that go with them are IdleFx.
 */
final class IdleGestures {

	private IdleGestures() {
	}

	static float[] v(float x, float y, float z) {
		return new float[]{x, y, z};
	}

	/** An arm (or head) track. */
	static final class Track {
		private final List<Key> keys = new ArrayList<>();

		Track rest(float t) {
			keys.add(Gestures.rest(t));
			return this;
		}

		Track at(float t, float x, float y, float z) {
			keys.add(Gestures.k(t, x, y, z));
			return this;
		}

		Track at(float t, float[] p) {
			return at(t, p[0], p[1], p[2]);
		}

		/** From t0 until t1, every {@code step} ticks the next of {@code poses}, round and round. */
		Track cycle(float t0, float t1, float step, float[]... poses) {
			int i = 0;
			for (float t = t0; t < t1 - 0.001f; t += step) at(t, poses[i++ % poses.length]);
			return this;
		}

		/** A shake about {@code base}: +amp, -amp, ... every {@code step} ticks, dying down if {@code decay}. */
		Track shake(float t0, float t1, float step, float[] base, float[] amp, boolean decay) {
			int i = 0;
			for (float t = t0; t < t1 - 0.001f; t += step, i++) {
				float k = (i % 2 == 0 ? 1 : -1) * (decay ? 1 - (t - t0) / (t1 - t0) * 0.8f : 1);
				at(t, base[0] + amp[0] * k, base[1] + amp[1] * k, base[2] + amp[2] * k);
			}
			return this;
		}

		/**
		 * The right fingertip traced round a heart in front of the chest - from its bottom tip, up one lobe,
		 * down the other and back - between t0 and t1: each point of the heart curve turned into the arm
		 * angles that put the fingertip there (a straight arm from the shoulder).
		 */
		Track heart(float t0, float t1, int steps) {
			double reach = 12 * 0.9375 / 16; // shoulder to fingertip, in blocks
			for (int i = 0; i <= steps; i++) {
				double s = Math.PI + 2 * Math.PI * i / steps, sin = Math.sin(s);
				// across (to the right, from the right shoulder: the body's middle is 0.35 in) and up from the shoulder
				double across = -0.35 + 0.013 * 16 * sin * sin * sin;
				double up = 0.013 * (13 * Math.cos(s) - 5 * Math.cos(2 * s) - 2 * Math.cos(3 * s) - Math.cos(4 * s));
				double pitch = Math.acos(Math.max(-1, Math.min(1, -up / reach)));
				double yaw = Math.asin(Math.max(-1, Math.min(1, across / (reach * Math.sin(pitch)))));
				at(t0 + (t1 - t0) * i / (float) steps, (float) -pitch, (float) yaw, 0.05f);
			}
			return this;
		}

		Key[] done() {
			return keys.toArray(Key[]::new);
		}
	}

	/** A body track: lean, twist, tilt, drop, stride, spread. */
	static final class Body {
		private final List<B> keys = new ArrayList<>();

		Body rest(float t) {
			keys.add(Gestures.brest(t));
			return this;
		}

		Body at(float t, float lean, float twist, float tilt, float drop, float stride, float spread) {
			keys.add(new B(t, lean, twist, tilt, drop, stride, spread));
			return this;
		}

		Body at(float t, float[] p) {
			return at(t, p[0], p[1], p[2], p[3], p[4], p[5]);
		}

		Body shake(float t0, float t1, float step, float[] base, float[] amp, boolean decay) {
			int i = 0;
			for (float t = t0; t < t1 - 0.001f; t += step, i++) {
				float k = (i % 2 == 0 ? 1 : -1) * (decay ? 1 - (t - t0) / (t1 - t0) * 0.8f : 1);
				float[] p = new float[6];
				for (int j = 0; j < 6; j++) p[j] = base[j] + amp[j] * k;
				at(t, p);
			}
			return this;
		}

		B[] done() {
			return keys.toArray(B[]::new);
		}
	}

	static float[] body(float lean, float twist, float tilt, float drop, float stride, float spread) {
		return new float[]{lean, twist, tilt, drop, stride, spread};
	}

	static Track track() {
		return new Track();
	}

	static Body bodyTrack() {
		return new Body();
	}

	// ---------------------------------------------------------------
	// Arms: {right, left}
	// ---------------------------------------------------------------

	static Key[][] arms(int variant) {
		Idle idle = Idle.byId(variant);
		if (idle == null) return null;
		int end = idle.length;
		return switch (idle) {
			// a heart traced in the air with one finger, admired with the hand lowered, then blown out
			case FIRE_DOODLE -> new Key[][]{
					track().rest(0).heart(7, 39, 24).at(46, -0.9f, 0.25f, 0.2f).at(66, -0.9f, 0.25f, 0.2f).rest(end).done(),
					track().rest(0).at(10, 0.08f, 0, 0.12f).at(66, 0.08f, 0, 0.12f).rest(end).done()};
			// the fist lights up and is admired - then flares: jerked away, shaken like mad, blown on, and a sigh
			case FIRE_HOT_HANDS -> new Key[][]{
					track().rest(0).at(10, -1.45f, -0.15f, 0.05f).at(36, -1.55f, -0.12f, 0.05f).at(41, -0.9f, 0.35f, 0.45f)
							.shake(43, 78, 2.5f, v(-1.05f, 0.35f, 0.4f), v(0.45f, 0.1f, 0.15f), false)
							.at(80, -1.2f, 0, 0.2f).at(85, -1.75f, -0.55f, 0.02f).at(93, -1.75f, -0.55f, 0.02f)
							.at(99, -0.3f, 0, 0.1f).rest(end).done(),
					track().rest(0).at(10, 0.05f, 0, 0.12f).at(36, 0.05f, 0, 0.12f).at(41, -0.6f, 0.4f, 0.5f)
							.shake(43, 78, 3, v(-0.25f, 0.15f, 0.48f), v(0.15f, 0.05f, 0.12f), false)
							.at(85, -0.2f, 0, 0.15f).rest(end).done()};
			// a little flame tossed hand to hand (out at 13, 31, 49, caught at 23, 41, 59), then snuffed in a fist
			case FIRE_JUGGLE -> {
				float[] up = v(-1.15f, 0.25f, 0.18f), dip = v(-0.95f, 0.25f, 0.18f), flick = v(-1.5f, 0.2f, 0.15f);
				yield new Key[][]{
						track().rest(0).at(8, up).at(10, dip).at(13, flick).at(16, up).at(40, up).at(42, dip).at(45, up)
								.at(46, dip).at(49, flick).at(52, up).at(58, up).at(66, -0.6f, 0.15f, 0.1f).rest(end).done(),
						track().rest(0).at(8, up).at(22, up).at(24, dip).at(27, up).at(28, dip).at(31, flick).at(34, up)
								.at(58, up).at(60, dip).at(64, -1.4f, -0.3f, 0.05f).at(70, -1.45f, -0.32f, 0.05f)
								.at(78, -1.4f, -0.3f, 0.05f).rest(end).done()};
			}
			// cupped hands at the mouth, blowing bubbles; one finger pokes the big one
			case WATER_BUBBLES -> new Key[][]{
					track().rest(0).at(10, -1.75f, -0.55f, 0.02f).at(36, -1.75f, -0.55f, 0.02f).at(42, -2.3f, 0, 0.08f)
							.at(46, -2.45f, -0.05f, 0.05f).at(50, -2.25f, 0, 0.08f).at(60, -0.4f, 0, 0.1f).rest(end).done(),
					track().rest(0).at(10, -1.75f, -0.55f, 0.02f).at(36, -1.75f, -0.55f, 0.02f).at(44, -0.9f, 0, 0.12f)
							.at(60, -0.2f, 0, 0.1f).rest(end).done()};
			// slow, flowing arms, the two in turn, like water
			case WATER_FLOW -> new Key[][]{
					track().rest(0).at(10, -1.5f, 0.6f, 0.45f).at(20, -1.1f, -0.2f, 0.25f).at(30, -1.7f, -0.4f, 0.15f)
							.at(40, -1.9f, 0.3f, 0.55f).at(50, -1.3f, 0.8f, 0.5f).at(60, -1.0f, 0.1f, 0.25f)
							.at(70, -1.6f, -0.3f, 0.2f).at(80, -1.4f, 0.5f, 0.45f).rest(end).done(),
					track().rest(0).at(14, -1.1f, -0.2f, 0.25f).at(24, -1.7f, -0.4f, 0.15f).at(34, -1.9f, 0.3f, 0.55f)
							.at(44, -1.3f, 0.8f, 0.5f).at(54, -1.0f, 0.1f, 0.25f).at(64, -1.6f, -0.3f, 0.2f)
							.at(74, -1.5f, 0.6f, 0.45f).at(84, -1.1f, 0.2f, 0.3f).rest(end).done()};
			// shaken off like a wet dog: arms flopping with the body, then the hands flicked dry
			case WATER_WET_DOG -> Gestures.both(
					track().rest(0).at(8, -0.3f, 0, 0.35f).shake(10, 40, 2, v(-0.18f, 0, 0.38f), v(0.07f, 0, 0.17f), true)
							.at(42, -0.5f, 0.2f, 0.4f).shake(46, 57, 2.5f, v(-0.5f, 0.2f, 0.38f), v(0.1f, 0, 0.08f), false)
							.rest(end).done());
			// a finger up, spinning a tiny whirlwind, then flicking it away to the right
			case WIND_WHIRL -> new Key[][]{
					track().rest(0).at(10, -2.85f, 0.05f, 0.2f)
							.cycle(12, 50, 2, v(-2.73f, 0.05f, 0.2f), v(-2.85f, 0.2f, 0.32f), v(-2.97f, 0.05f, 0.2f), v(-2.85f, -0.1f, 0.08f))
							.at(52, -2.6f, -0.2f, 0.15f).at(56, -1.6f, 0.7f, 0.6f).at(62, -1.3f, 0.6f, 0.5f).rest(end).done(),
					track().rest(0).at(12, 0.1f, 0, 0.15f).at(60, 0.1f, 0, 0.15f).rest(end).done()};
			// leaning back on nothing, arms crossed
			case WIND_LEAN -> Gestures.both(
					track().rest(0).at(14, -1.2f, -0.85f, 0.08f).at(92, -1.2f, -0.85f, 0.08f).rest(end).done());
			// a gust: one hand clamped on the head, the other arm windmilling for balance
			case WIND_HAT -> new Key[][]{
					track().rest(0).at(8, -0.3f, 0, 0.1f).at(12, -2.9f, -0.05f, 0.38f)
							.shake(14, 44, 3, v(-2.88f, -0.04f, 0.36f), v(0.03f, 0.02f, 0.03f), false)
							.at(50, -2.6f, 0, 0.2f).at(60, -0.6f, 0, 0.05f).rest(end).done(),
					// flailing out at the side for balance: quick circles round the arm held out sideways
					track().rest(0).at(10, -0.5f, 0.1f, 1.1f)
							.cycle(12, 44, 2, v(-0.5f, 0, 1.35f), v(-1.15f, 0.3f, 1.1f), v(-0.5f, 0.6f, 0.85f), v(0.15f, 0.3f, 1.1f))
							.at(48, -0.6f, 0.2f, 0.9f).at(54, -0.3f, 0.1f, 0.5f).rest(end).done()};
			// warm breath into cupped hands, three puffs, then the hands rubbed together
			case FROST_BREATH -> new Key[][]{
					track().rest(0).at(10, -1.75f, -0.55f, 0.02f).at(50, -1.75f, -0.55f, 0.02f).at(55, -1.3f, -0.68f, 0.05f)
							.shake(57, 86, 3, v(-1.33f, -0.68f, 0.05f), v(0.07f, 0.03f, 0), false).rest(end).done(),
					track().rest(0).at(10, -1.75f, -0.55f, 0.02f).at(50, -1.75f, -0.55f, 0.02f).at(55, -1.3f, -0.68f, 0.05f)
							.shake(57, 86, 3, v(-1.33f, -0.68f, 0.05f), v(-0.07f, -0.03f, 0), false).rest(end).done()};
			// hugging yourself, shivering all over
			case FROST_SHIVER -> new Key[][]{
					track().rest(0).at(8, -1.3f, -0.95f, 0.12f).shake(10, 63, 1.5f, v(-1.29f, -0.94f, 0.12f), v(0.03f, 0.015f, 0.02f), false)
							.at(66, -1.2f, -0.9f, 0.12f).rest(end).done(),
					track().rest(0).at(8, -1.3f, -0.95f, 0.12f).shake(10, 63, 1.5f, v(-1.29f, -0.94f, 0.12f), v(-0.03f, -0.015f, -0.02f), false)
							.at(66, -1.2f, -0.9f, 0.12f).rest(end).done()};
			// a palm up while snowflakes spiral into it - crushed into a fist with a crack
			case FROST_SNOWFLAKE -> new Key[][]{
					track().rest(0).at(10, -1.3f, 0.15f, 0.15f).at(52, -1.3f, 0.15f, 0.15f).at(56, -1.45f, 0.05f, 0.1f)
							.shake(57, 64, 1.5f, v(-1.45f, 0.05f, 0.1f), v(0.03f, 0, 0.02f), false)
							.at(70, -1.0f, 0.25f, 0.25f).rest(end).done(),
					track().rest(0).at(12, 0.05f, 0, 0.1f).at(70, 0.05f, 0, 0.1f).rest(end).done()};
			// both hands together making a wolf's head: the jaw snaps, then it howls (snout up), hands shaken off
			case SHADOW_PUPPET -> new Key[][]{
					track().rest(0).at(10, -1.5f, -0.5f, 0).cycle(20, 44, 4, v(-1.66f, -0.5f, 0), v(-1.5f, -0.5f, 0))
							.at(48, -1.85f, -0.45f, 0).at(58, -1.85f, -0.45f, 0).at(64, -1.2f, 0.4f, 0.5f)
							.at(70, -1.0f, 0.35f, 0.45f).rest(end).done(),
					track().rest(0).at(10, -1.5f, -0.5f, 0).at(44, -1.5f, -0.5f, 0).at(48, -1.75f, -0.45f, 0)
							.at(58, -1.75f, -0.45f, 0).at(64, -1.2f, 0.4f, 0.5f).at(70, -1.0f, 0.35f, 0.45f).rest(end).done()};
			// crouched with a hand at the brow, looking left, right, behind - then a sigh of relief
			case SHADOW_PARANOID -> new Key[][]{
					track().rest(0).at(10, -2.6f, -0.6f, 0).at(64, -2.6f, -0.6f, 0).at(72, -0.4f, 0, 0.1f).rest(end).done(),
					track().rest(0).at(10, 0.3f, 0, 0.18f).at(64, 0.3f, 0, 0.18f).rest(end).done()};
			// arms wrapped round the body while the shadows coil up; then a shrug
			case SHADOW_FLICKER -> Gestures.both(
					track().rest(0).at(10, -1.0f, -1.0f, 0.12f).at(52, -1.0f, -1.0f, 0.12f).at(60, -0.45f, 0.35f, 0.55f)
							.at(68, -0.45f, 0.35f, 0.55f).rest(end).done());
			// down on one knee, a palm to the ground; flowers come up, cupped and sniffed
			case VERDANT_SPROUT -> new Key[][]{
					track().rest(0).at(12, -0.45f, 0.1f, 0.12f).at(44, -0.45f, 0.1f, 0.12f).at(52, -0.8f, -0.1f, 0.1f)
							.at(64, -0.8f, -0.1f, 0.1f).at(96, 0, 0, 0.05f).rest(end).done(),
					track().rest(0).at(12, -0.25f, 0, 0.35f).at(64, -0.25f, 0, 0.35f).at(96, 0, 0, 0.05f).rest(end).done()};
			// a big stretch: arms up high, to one side, the other, then swung down wide
			case VERDANT_STRETCH -> Gestures.both(
					track().rest(0).at(14, -2.85f, 0.05f, -0.55f).at(36, -2.9f, 0, -0.5f).at(44, -2.85f, 0.05f, -0.45f)
							.at(52, -2.85f, 0.05f, -0.45f).at(62, -0.4f, 0.2f, 1.2f).at(76, -0.1f, 0.05f, 0.3f).rest(end).done());
			// the left arm held out while a little vine curls up it; the right hand pats it fondly
			case VERDANT_VINE -> new Key[][]{
					track().rest(0).at(12, 0.05f, 0, 0.1f).at(46, 0.05f, 0, 0.1f).at(50, -1.3f, -0.65f, 0.05f)
							.cycle(52, 70, 3, v(-1.38f, -0.68f, 0.05f), v(-1.22f, -0.65f, 0.05f)).at(74, -0.6f, 0, 0.1f).rest(end).done(),
					track().rest(0).at(10, -1.45f, 0.25f, 0.25f).at(80, -1.45f, 0.25f, 0.25f).rest(end).done()};
			// hands rubbed together, building static - ZAP - jerked apart, the stung hand shaken out
			case THUNDER_ZAP -> new Key[][]{
					track().rest(0).at(8, -1.3f, -0.7f, 0.05f).shake(10, 37, 2.5f, v(-1.31f, -0.7f, 0.05f), v(0.07f, -0.02f, 0), false)
							.at(40, -0.8f, 0.5f, 0.55f).shake(42, 61, 2, v(-0.8f, 0.35f, 0.45f), v(0.2f, 0.05f, 0.1f), false)
							.at(66, -0.9f, 0.1f, 0.2f).rest(end).done(),
					track().rest(0).at(8, -1.3f, -0.7f, 0.05f).shake(10, 37, 2.5f, v(-1.31f, -0.7f, 0.05f), v(-0.07f, 0.02f, 0), false)
							.at(40, -0.8f, 0.5f, 0.55f).at(46, -0.2f, 0.1f, 0.25f).rest(end).done()};
			// laced fingers pushed out - three cracks, a spark each - shoulders rolled, the neck cracked
			case THUNDER_KNUCKLES -> Gestures.both(
					track().rest(0).at(10, -1.55f, -0.45f, 0).at(16, -1.6f, -0.38f, 0)
							.at(18, -1.66f, -0.35f, 0).at(20, -1.6f, -0.38f, 0).at(24, -1.66f, -0.35f, 0).at(26, -1.6f, -0.38f, 0)
							.at(30, -1.66f, -0.35f, 0).at(33, -1.6f, -0.38f, 0).at(40, -0.3f, 0.2f, 0.4f).at(48, 0.2f, 0.1f, 0.3f)
							.at(54, -0.3f, 0.2f, 0.4f).at(66, 0, 0.05f, 0.1f).rest(end).done());
			// fists clenched at the sides, trembling with charge - released with a flash, arms flung out
			case THUNDER_OVERCHARGE -> Gestures.both(
					track().rest(0).at(10, 0.15f, 0.1f, 0.22f).shake(12, 61, 1.5f, v(0.15f, 0.1f, 0.22f), v(0.02f, 0, 0.02f), false)
							.at(64, -0.6f, 0.3f, 0.9f).at(70, -0.4f, 0.2f, 0.6f).rest(end).done());
			// arms up for a big stretch and a yawn, then out wide
			case STRETCH -> Gestures.both(
					track().rest(0).at(12, -2.85f, 0.05f, -0.55f).at(34, -2.9f, 0.05f, -0.5f).at(44, -0.3f, 0.1f, 1.35f)
							.at(56, -0.15f, 0.05f, 0.5f).rest(end).done());
			// a slow look round: left, right, up
			case LOOK_AROUND -> Gestures.both(track().rest(0).at(10, 0.05f, 0, 0.08f).at(80, 0.05f, 0, 0.08f).rest(end).done());
			// a look at the watch on the left wrist, then arms crossed, a foot tapping
			case WAITING -> new Key[][]{
					track().rest(0).at(30, 0.02f, 0, 0.05f).at(36, -1.2f, -0.85f, 0.08f).at(86, -1.2f, -0.85f, 0.08f).rest(end).done(),
					track().rest(0).at(10, -1.5f, -0.55f, 0.02f).at(30, -1.5f, -0.55f, 0.02f).at(36, -1.2f, -0.85f, 0.08f)
							.at(86, -1.2f, -0.85f, 0.08f).rest(end).done()};
		};
	}

	// ---------------------------------------------------------------
	// Body
	// ---------------------------------------------------------------

	static B[] body(int variant) {
		Idle idle = Idle.byId(variant);
		if (idle == null) return null;
		int end = idle.length;
		return switch (idle) {
			case FIRE_DOODLE -> bodyTrack().rest(0).at(46, -0.06f, 0, 0, 0, 0, 0.02f).at(58, -0.06f, 0, 0, 0, 0, 0.02f)
					.at(62, 0.07f, 0, 0, 0, 0, 0.02f).at(68, 0.02f, 0, 0, 0, 0, 0.02f).rest(end).done();
			case FIRE_HOT_HANDS -> bodyTrack().rest(0).at(20, 0, 0, 0, 0, 0, 0.03f).at(36, -0.08f, 0, 0, 0, 0, 0.03f)
					.at(41, -0.15f, -0.15f, 0, 0, -0.1f, 0.12f)
					.shake(43, 78, 2.5f, body(-0.05f, 0, 0, 0, -0.08f, 0.12f), body(0, 0.12f, 0.05f, 0, 0, 0), false)
					.at(85, 0.08f, 0, 0, 0, 0, 0.05f).at(93, 0.08f, 0, 0, 0, 0, 0.05f).at(99, 0.15f, 0, 0, 0.03f, 0, 0.05f).rest(end).done();
			case FIRE_JUGGLE -> bodyTrack().rest(0).at(12, 0, 0.06f, 0, 0, 0, 0.03f).at(23, 0, -0.06f, 0, 0, 0, 0.03f)
					.at(41, 0, 0.06f, 0, 0, 0, 0.03f).at(59, 0, -0.06f, 0, 0, 0, 0.03f).at(70, 0.04f, -0.04f, 0, 0, 0, 0.03f).rest(end).done();
			case WATER_BUBBLES -> bodyTrack().rest(0).at(14, 0.05f, 0, 0, 0, 0, 0.02f).at(20, 0.08f, 0, 0, 0, 0, 0.02f)
					.at(26, 0.04f, 0, 0, 0, 0, 0.02f).at(32, 0.08f, 0, 0, 0, 0, 0.02f).at(40, -0.04f, 0, 0, 0, 0, 0.02f)
					.at(54, -0.03f, 0, 0, 0, 0, 0.02f).rest(end).done();
			case WATER_FLOW -> bodyTrack().rest(0).at(10, 0.02f, 0.12f, 0.04f, 0.02f, 0.1f, 0.08f).at(30, 0.02f, -0.12f, -0.04f, 0.02f, -0.1f, 0.08f)
					.at(50, 0.02f, 0.12f, 0.04f, 0.02f, 0.1f, 0.08f).at(70, 0.02f, -0.12f, -0.04f, 0.02f, -0.1f, 0.08f)
					.at(84, 0, 0.04f, 0, 0, 0.03f, 0.05f).rest(end).done();
			case WATER_WET_DOG -> bodyTrack().rest(0).at(8, 0.05f, 0, 0, 0.03f, 0, 0.12f)
					.shake(10, 40, 2, body(0.05f, 0, 0, 0.03f, 0, 0.12f), body(0, 0.28f, 0.05f, 0, 0, 0), true)
					.at(44, 0, 0, 0, 0, 0, 0.1f).rest(end).done();
			case WIND_WHIRL -> bodyTrack().rest(0).at(10, -0.05f, 0, 0, 0, 0, 0.03f).at(52, -0.05f, -0.05f, 0, 0, 0, 0.03f)
					.at(56, 0.02f, 0.15f, 0, 0, 0.05f, 0.05f).at(64, 0, 0.12f, 0, 0, 0.05f, 0.05f).rest(end).done();
			case WIND_LEAN -> bodyTrack().rest(0).at(14, -0.22f, 0.05f, 0, 0, 0.18f, 0.04f).at(30, -0.25f, 0.05f, 0, 0, 0.18f, 0.04f)
					.at(46, -0.22f, 0.05f, 0, 0, 0.18f, 0.04f).at(62, -0.25f, 0.05f, 0, 0, 0.18f, 0.04f)
					.at(78, -0.22f, 0.05f, 0, 0, 0.18f, 0.04f).at(92, -0.22f, 0.05f, 0, 0, 0.18f, 0.04f).rest(end).done();
			case WIND_HAT -> bodyTrack().rest(0).at(12, -0.05f, -0.1f, 0.12f, 0, 0.15f, 0.18f)
					.shake(14, 44, 3, body(-0.03f, -0.1f, 0.09f, 0, 0.15f, 0.18f), body(0.02f, 0, 0.03f, 0, 0, 0), false)
					.at(52, 0, 0, 0, 0, 0, 0.1f).rest(70).rest(end).done();
			case FROST_BREATH -> bodyTrack().rest(0).at(10, 0.06f, 0, 0, 0.02f, 0, 0.02f).at(16, 0.1f, 0, 0, 0.02f, 0, 0.02f)
					.at(22, 0.06f, 0, 0, 0.02f, 0, 0.02f).at(30, 0.1f, 0, 0, 0.02f, 0, 0.02f).at(36, 0.06f, 0, 0, 0.02f, 0, 0.02f)
					.at(44, 0.1f, 0, 0, 0.02f, 0, 0.02f).at(50, 0.06f, 0, 0, 0.02f, 0, 0.02f).at(56, 0.04f, 0, 0, 0.02f, 0, 0.02f)
					.at(86, 0.04f, 0, 0, 0.02f, 0, 0.02f).rest(end).done();
			case FROST_SHIVER -> bodyTrack().rest(0).at(8, 0.1f, 0, 0, 0.02f, 0, 0.01f)
					.shake(10, 63, 1.5f, body(0.1f, 0, 0, 0.02f, 0, 0.01f), body(0, 0.03f, 0.035f, 0, 0, 0), false)
					.at(66, 0.06f, 0, 0, 0.01f, 0, 0.01f).rest(end).done();
			case FROST_SNOWFLAKE -> bodyTrack().rest(0).at(10, 0.05f, 0.05f, 0, 0, 0, 0.02f).at(56, 0.07f, 0.05f, 0, 0, 0, 0.02f)
					.at(64, 0.02f, 0.05f, 0, 0, 0, 0.02f).rest(end).done();
			case SHADOW_PUPPET -> bodyTrack().rest(0).at(10, 0.03f, 0, 0, 0, 0, 0.02f).at(44, 0.03f, 0, 0, 0, 0, 0.02f)
					.at(48, -0.05f, 0, 0, 0, 0, 0.02f).at(58, -0.05f, 0, 0, 0, 0, 0.02f).rest(end).done();
			case SHADOW_PARANOID -> bodyTrack().rest(0).at(10, 0.12f, 0, 0, 0.05f, 0.05f, 0.15f).at(20, 0.12f, -0.15f, 0, 0.05f, 0.05f, 0.15f)
					.at(26, 0.12f, -0.15f, 0, 0.05f, 0.05f, 0.15f).at(36, 0.12f, 0.15f, 0, 0.05f, 0.05f, 0.15f)
					.at(42, 0.12f, 0.15f, 0, 0.05f, 0.05f, 0.15f).at(54, 0.08f, 0.35f, 0, 0.05f, -0.1f, 0.15f)
					.at(62, 0.08f, 0.35f, 0, 0.05f, -0.1f, 0.15f).at(72, -0.05f, 0, 0, 0, 0, 0.05f).rest(end).done();
			case SHADOW_FLICKER -> bodyTrack().rest(0).at(10, 0.08f, 0, 0, 0.03f, 0, 0.02f).at(52, 0.08f, 0, 0, 0.03f, 0, 0.02f)
					.at(60, -0.06f, 0, 0, 0, 0, 0.05f).at(68, -0.06f, 0, 0, 0, 0, 0.05f).rest(end).done();
			case VERDANT_SPROUT -> bodyTrack().rest(0).at(12, 0.45f, 0, 0, 0.12f, 0.45f, 0.1f).at(44, 0.45f, 0, 0, 0.12f, 0.45f, 0.1f)
					.at(52, 0.6f, 0, 0, 0.16f, 0.45f, 0.1f).at(62, 0.6f, 0, 0, 0.16f, 0.45f, 0.1f).at(70, 0.45f, 0, 0, 0.12f, 0.45f, 0.1f)
					.at(96, 0, 0, 0, 0, 0.02f, 0.02f).rest(end).done();
			case VERDANT_STRETCH -> bodyTrack().rest(0).at(14, -0.15f, 0, 0, 0, 0, 0.12f).at(36, -0.18f, 0, 0, 0, 0, 0.12f)
					.at(44, -0.05f, 0, 0.18f, 0, 0, 0.12f).at(52, -0.05f, 0, -0.18f, 0, 0, 0.12f).at(60, 0, 0, 0, 0, 0, 0.08f).rest(end).done();
			case VERDANT_VINE -> bodyTrack().rest(0).at(10, 0.02f, -0.08f, 0, 0, 0, 0.03f).at(80, 0.02f, -0.08f, 0, 0, 0, 0.03f).rest(end).done();
			case THUNDER_ZAP -> bodyTrack().rest(0).at(8, 0.05f, 0, 0, 0.01f, 0, 0.03f).at(37, 0.06f, 0, 0, 0.01f, 0, 0.03f)
					.at(40, -0.12f, 0, 0, 0, -0.08f, 0.1f).shake(42, 61, 2, body(-0.05f, 0, 0, 0, -0.08f, 0.1f), body(0, 0.06f, 0.02f, 0, 0, 0), false)
					.rest(end).done();
			case THUNDER_KNUCKLES -> bodyTrack().rest(0).at(16, 0.05f, 0, 0, 0, 0, 0.04f).at(18, 0.08f, 0, 0, 0, 0, 0.04f)
					.at(21, 0.05f, 0, 0, 0, 0, 0.04f).at(24, 0.08f, 0, 0, 0, 0, 0.04f).at(27, 0.05f, 0, 0, 0, 0, 0.04f)
					.at(30, 0.08f, 0, 0, 0, 0, 0.04f).at(34, 0.03f, 0, 0, 0, 0, 0.04f).at(42, 0, 0.12f, 0.05f, 0, 0, 0.05f)
					.at(50, 0, -0.12f, -0.05f, 0, 0, 0.05f).at(56, 0, 0, 0, 0, 0, 0.04f).rest(end).done();
			case THUNDER_OVERCHARGE -> bodyTrack().rest(0).at(10, 0.06f, 0, 0, 0.04f, 0, 0.22f)
					.shake(12, 61, 1.5f, body(0.06f, 0, 0, 0.04f, 0, 0.22f), body(0, 0.01f, 0.02f, 0, 0, 0), false)
					.at(64, -0.12f, 0, 0, 0, 0, 0.15f).at(74, 0, 0, 0, 0, 0, 0.08f).rest(end).done();
			case STRETCH -> bodyTrack().rest(0).at(12, -0.12f, 0, 0, 0, 0, 0.06f).at(34, -0.14f, 0, 0, 0, 0, 0.06f)
					.at(44, 0, 0, 0, 0, 0, 0.06f).rest(64).rest(end).done();
			case LOOK_AROUND -> bodyTrack().rest(0).at(16, 0, -0.18f, 0, 0, 0, 0.04f).at(30, 0, -0.18f, 0, 0, 0, 0.04f)
					.at(42, 0, 0.18f, 0, 0, 0, 0.04f).at(56, 0, 0.18f, 0, 0, 0, 0.04f).at(66, -0.05f, 0, 0, 0, 0, 0.04f)
					.at(76, -0.05f, 0, 0, 0, 0, 0.04f).rest(end).done();
			case WAITING -> bodyTrack().rest(0).at(10, 0.04f, -0.05f, 0, 0, 0, 0.04f).at(36, 0, 0, 0, 0, 0.02f, 0.06f)
					.shake(38, 85, 4, body(0, 0, 0, 0, 0.08f, 0.06f), body(0, 0, 0, 0, 0.06f, 0), false)
					.at(88, 0, 0, 0, 0, 0.02f, 0.04f).rest(end).done();
		};
	}

	// ---------------------------------------------------------------
	// Head: pitch (down), yaw (right), roll
	// ---------------------------------------------------------------

	static Key[] head(int variant) {
		Idle idle = Idle.byId(variant);
		if (idle == null) return null;
		int end = idle.length;
		return switch (idle) {
			case FIRE_DOODLE -> track().rest(0).at(7, 0.5f, -0.05f, 0).at(18, 0.38f, -0.1f, 0).at(23, 0.4f, 0, 0)
					.at(28, 0.38f, 0.1f, 0).at(39, 0.5f, 0.02f, 0).at(46, 0.4f, 0, 0.25f).at(58, 0.4f, 0, -0.15f)
					.at(62, 0.42f, 0, 0).rest(end).done();
			case FIRE_HOT_HANDS -> track().rest(0).at(10, 0.3f, 0.12f, 0).at(36, 0.25f, 0.12f, 0.15f).at(41, -0.15f, -0.2f, 0)
					.shake(43, 78, 4, v(0.1f, 0.1f, 0), v(0, 0.25f, 0.1f), false).at(85, 0.1f, 0, 0).at(93, 0.1f, 0, 0)
					.at(99, 0.45f, 0, 0).rest(end).done();
			case FIRE_JUGGLE -> track().rest(0).at(10, 0.15f, 0.25f, 0).at(18, -0.3f, 0, 0).at(23, 0.15f, -0.25f, 0)
					.at(31, 0.15f, -0.25f, 0).at(36, -0.3f, 0, 0).at(41, 0.15f, 0.25f, 0).at(49, 0.15f, 0.25f, 0)
					.at(54, -0.3f, 0, 0).at(59, 0.15f, -0.25f, 0).at(70, 0.25f, -0.1f, 0).rest(end).done();
			case WATER_BUBBLES -> track().rest(0).at(10, 0.05f, 0, 0).at(34, 0.05f, 0, 0).at(38, -0.45f, 0.1f, 0)
					.at(46, -0.55f, 0.12f, 0).at(54, -0.4f, 0.1f, 0.12f).rest(end).done();
			case WATER_FLOW -> track().rest(0).at(10, 0.15f, 0.3f, 0).at(30, 0.05f, -0.1f, 0).at(50, 0.1f, 0.35f, 0)
					.at(70, 0.1f, -0.15f, 0).rest(end).done();
			case WATER_WET_DOG -> track().rest(0).at(8, 0.05f, 0, 0).shake(10, 40, 2, v(0.05f, 0, 0), v(0, -0.4f, -0.1f), true)
					.at(46, 0.1f, 0, 0.2f).rest(end).done();
			case WIND_WHIRL -> track().rest(0).at(10, -0.6f, 0.15f, 0).at(50, -0.6f, 0.15f, 0).at(56, -0.1f, 0.6f, 0)
					.at(66, 0, 0.7f, 0).rest(end).done();
			case WIND_LEAN -> track().rest(0).at(14, -0.15f, 0.05f, 0.12f).at(50, -0.2f, -0.25f, 0.1f).at(70, -0.15f, 0.1f, 0.15f)
					.at(92, -0.15f, 0.1f, 0.12f).rest(end).done();
			case WIND_HAT -> track().rest(0).at(12, 0.2f, -0.2f, -0.1f).at(44, 0.2f, -0.2f, -0.1f).at(50, 0.05f, 0.3f, 0)
					.at(58, 0.1f, 0, 0.15f).rest(end).done();
			case FROST_BREATH -> track().rest(0).at(10, 0.15f, 0, 0).at(50, 0.15f, 0, 0).at(55, 0.3f, 0, 0).rest(end).done();
			case FROST_SHIVER -> track().rest(0).at(8, 0.25f, 0, 0).shake(10, 63, 1.5f, v(0.25f, 0, 0), v(0, 0.04f, 0.05f), false)
					.rest(end).done();
			case FROST_SNOWFLAKE -> track().rest(0).at(10, 0.4f, 0.2f, 0).at(40, 0.45f, 0.22f, 0.12f).at(56, 0.35f, 0.2f, 0)
					.at(70, 0.3f, 0.25f, 0.1f).rest(end).done();
			case SHADOW_PUPPET -> track().rest(0).at(10, 0.35f, 0, 0).at(18, 0.55f, 0, 0).at(44, 0.55f, 0, 0.1f).at(48, 0.5f, 0, -0.1f)
					.at(58, 0.5f, 0, -0.1f).at(66, 0.4f, 0.25f, 0.15f).rest(end).done();
			case SHADOW_PARANOID -> track().rest(0).at(10, 0.1f, 0, 0).at(20, 0.1f, -0.85f, 0).at(26, 0.1f, -0.85f, 0)
					.at(36, 0.1f, 0.85f, 0).at(42, 0.1f, 0.85f, 0).at(54, 0, 1.2f, 0).at(62, 0, 1.2f, 0).at(70, 0.15f, 0, 0)
					.at(76, 0.25f, 0, 0).rest(end).done();
			case SHADOW_FLICKER -> track().rest(0).at(10, 0.3f, 0, 0).at(52, 0.3f, 0, 0).at(60, -0.05f, 0, 0.25f)
					.at(68, 0, 0, 0.25f).rest(end).done();
			case VERDANT_SPROUT -> track().rest(0).at(12, 0.55f, 0, 0).at(44, 0.55f, 0, 0).at(52, 0.75f, -0.05f, 0)
					.at(62, 0.75f, -0.05f, 0).at(66, 0.6f, 0, 0.15f).rest(end).done();
			case VERDANT_STRETCH -> track().rest(0).at(14, -0.5f, 0, 0).at(30, -0.6f, 0, 0).at(44, -0.3f, 0, 0.2f)
					.at(52, -0.3f, 0, -0.2f).rest(end).done();
			case VERDANT_VINE -> track().rest(0).at(10, 0.25f, -0.35f, 0).at(40, 0.3f, -0.3f, -0.15f).at(60, 0.3f, -0.3f, 0.15f)
					.rest(end).done();
			case THUNDER_ZAP -> track().rest(0).at(8, 0.3f, 0, 0).at(37, 0.3f, 0, 0).at(40, -0.2f, 0, 0.2f)
					.shake(42, 61, 2, v(-0.05f, 0, 0.1f), v(0, 0.15f, 0), false).at(64, 0.25f, 0.25f, 0).rest(end).done();
			case THUNDER_KNUCKLES -> track().rest(0).at(16, 0.15f, 0, 0).at(52, 0.1f, 0, 0).at(58, 0.05f, 0, 0.35f)
					.at(62, 0.05f, 0, -0.35f).at(66, 0, 0, 0).rest(end).done();
			case THUNDER_OVERCHARGE -> track().rest(0).at(10, 0.2f, 0, 0).at(58, 0.05f, 0, 0).at(64, -0.35f, 0, 0)
					.at(72, 0, 0, 0.15f).rest(end).done();
			case STRETCH -> track().rest(0).at(12, -0.45f, 0, 0).at(30, -0.55f, 0, 0.1f).at(44, 0, 0, 0).rest(end).done();
			case LOOK_AROUND -> track().rest(0).at(16, 0.05f, -0.85f, 0).at(30, 0.05f, -0.85f, 0).at(42, 0.05f, 0.85f, 0)
					.at(56, 0.05f, 0.85f, 0).at(66, -0.5f, 0.1f, 0).at(76, -0.5f, 0.1f, 0.1f).rest(end).done();
			case WAITING -> track().rest(0).at(10, 0.45f, -0.35f, 0).at(30, 0.4f, -0.35f, 0).at(40, -0.1f, 0.3f, 0)
					.at(56, -0.1f, -0.3f, 0).at(72, -0.1f, 0.3f, 0).at(84, 0.1f, 0, 0.1f).rest(end).done();
		};
	}
}
