package com.toast.aurapoints.client;

import com.toast.aurapoints.client.Gestures.B;
import com.toast.aurapoints.client.Gestures.Key;

import static com.toast.aurapoints.client.IdleGestures.bodyTrack;
import static com.toast.aurapoints.client.IdleGestures.track;
import static com.toast.aurapoints.client.IdleGestures.v;

/**
 * CLIENT ONLY. The Ultimates' full-body moves (BendingAnimPacket.GESTURE_ULTIMATE; the variant is the index in
 * ability/Ultimates.ORDER), in the same terms as IdleGestures, timed to the server's script for each:
 * 0 Eclipse, 1 Tidal Ascension, 2 Phoenix, 3 Tempest, 4 Permafrost, 5 Overgrowth, 6 Judgement, 7 Tectonic Rift, 8 Sunburst, 9 Blood Moon, 10 Singularity.
 */
final class UltimateGestures {

	private UltimateGestures() {
	}

	private static final int[] LENGTH = {40, 45, 120, 125, 76, 60, 56, 50, 150, 50, 100};

	static Key[][] arms(int variant) {
		if (variant < 0 || variant >= LENGTH.length) return null;
		int end = LENGTH[variant];
		return switch (variant) {
			// Eclipse: low and ready, the right hand a blade - a slash at every blink (6, 12, ... 30), high and low in turn
			case 0 -> new Key[][]{
					track().rest(0).at(4, -0.6f, 0.4f, 0.5f)
							.cycle(5, 34, 3, v(-2.2f, -0.6f, 0.3f), v(-0.6f, 0.9f, 0.6f)).at(36, -0.6f, 0.4f, 0.4f).rest(end).done(),
					track().rest(0).at(4, 0.6f, 0, 0.3f).at(34, 0.6f, 0, 0.3f).rest(end).done()};
			// Tidal Ascension: arms rising from the sides as the water lifts you, held high, then hammered down for the wave
			case 1 -> Gestures.both(track().rest(0).at(10, -1.6f, 0.2f, 1.2f).at(16, -2.9f, 0.1f, -0.4f).at(19, -3.0f, 0.1f, -0.35f)
					.at(23, -0.5f, -0.2f, 0.2f).at(30, -0.4f, 0.3f, 0.9f).at(38, -0.3f, 0.2f, 0.7f).rest(end).done());
			// Phoenix: arms spread like wings, slowly beating, then swept back for the dive
			case 2 -> Gestures.both(track().rest(0).at(6, -0.4f, 0.3f, 1.5f)
					.cycle(8, 100, 10, v(-0.6f, 0.3f, 1.65f), v(-0.2f, 0.3f, 1.25f)).at(104, 0.5f, 0.1f, 0.3f).at(114, 0.5f, 0.1f, 0.3f).rest(end).done());
			// Tempest: both arms up and out, turning slowly as if steering the storm
			case 3 -> Gestures.both(track().rest(0).at(8, -2.6f, 0.2f, -0.7f)
					.cycle(10, 116, 12, v(-2.4f, 0.5f, -0.8f), v(-2.7f, -0.2f, -0.6f), v(-2.5f, 0.1f, -0.9f)).at(118, -1.2f, 0.4f, 1.2f).rest(end).done());
			// Permafrost: hands up, slammed flat on the ground, held there; at the shatter, clenched and thrown up
			case 4 -> Gestures.both(track().rest(0).at(4, -2.8f, 0.1f, -0.3f).at(8, -0.55f, 0.05f, 0.15f).at(66, -0.55f, 0.05f, 0.15f)
					.at(70, -2.3f, 0.2f, -0.3f).at(73, -2.4f, 0.3f, -0.4f).rest(end).done());
			// Overgrowth: palms raised as the tree grows behind you, held, then lowered
			case 5 -> Gestures.both(track().rest(0).at(6, -0.4f, 0.2f, 0.4f).at(25, -2.6f, 0.3f, -0.55f).at(40, -2.6f, 0.3f, -0.55f).rest(end).done());
			// Judgement: arms spread as you rise, the right one up to the sky pumping at each bolt; both up, then slammed down
			case 6 -> {
				var right = track().rest(0).at(8, -1.0f, 0.2f, 1.2f).at(11, -3.0f, 0.05f, -0.2f);
				for (int k = 0; k < 7; k++) right.at(12 + 4 * k + 1, -2.6f, 0.1f, -0.2f).at(12 + 4 * k + 3, -3.05f, 0.05f, -0.2f);
				yield new Key[][]{
						right.at(43, -3.1f, 0.05f, -0.3f).at(46, -0.7f, -0.1f, 0.1f).at(50, -0.6f, 0, 0.2f).rest(end).done(),
						track().rest(0).at(8, -1.0f, 0.2f, 1.2f).at(40, -1.0f, 0.2f, 1.1f).at(43, -3.0f, 0.05f, -0.3f).at(46, -0.7f, -0.1f, 0.1f)
								.at(50, -0.6f, 0, 0.2f).rest(end).done()};
			}
			// Tectonic Rift: fists up, hammered down to the ground and held as the crack runs off, then torn wide apart
			case 7 -> Gestures.both(track().rest(0).at(5, -3.0f, -0.2f, 0.25f).at(8, -0.5f, -0.25f, 0.15f).at(20, -0.55f, -0.25f, 0.15f)
					.at(26, -0.4f, 0.2f, 1.6f).at(40, -0.35f, 0.2f, 1.55f).rest(end).done());
			// Sunburst: both hands raised to the rising sun, held up swaying gently while it burns, flung out wide at the burst
			// (raised overhead, a negative roll is outward)
			case 8 -> Gestures.both(track().rest(0).at(8, -2.75f, 0.1f, -0.3f).cycle(10, 130, 20, v(-2.8f, 0.1f, -0.25f), v(-2.65f, 0.15f, -0.4f))
					.at(134, -2.9f, 0.05f, -0.2f).at(140, -0.4f, 0.2f, 1.9f).at(146, -0.35f, 0.2f, 1.85f).rest(end).done());
			// Blood Moon: arms flung wide and low, head thrown back to the red sky in a long howl, then drawn in
			case 9 -> Gestures.both(track().rest(0).at(6, -0.2f, 0.1f, 1.1f).at(10, -0.35f, 0.15f, 1.3f).shake(10, 34, 2, v(-0.35f, 0.15f, 1.3f), v(0.05f, 0, 0.06f), false)
					.at(38, -0.3f, 0.1f, 1.2f).at(44, -0.6f, -0.4f, 0.2f).rest(end).done());
			// Singularity: the right hand thrust at the spot and held there clawed, straining as it pulls; the left braced back.
			// At the collapse the fist is clenched and pulled in to the chest
			case 10 -> new Key[][]{
					track().rest(0).at(4, -1.65f, -0.05f, 0.05f).shake(6, 84, 2, v(-1.6f, -0.05f, 0.05f), v(0.03f, 0.02f, 0.02f), false)
							.at(86, -1.7f, -0.05f, 0.05f).at(89, -1.1f, -0.7f, 0.1f).at(94, -1.05f, -0.7f, 0.1f).rest(end).done(),
					track().rest(0).at(5, 0.35f, 0, 0.35f).at(86, 0.35f, 0, 0.35f).at(92, -0.3f, 0, 0.2f).rest(end).done()};
			default -> null;
		};
	}

	static B[] body(int variant) {
		if (variant < 0 || variant >= LENGTH.length) return null;
		int end = LENGTH[variant];
		return switch (variant) {
			case 0 -> bodyTrack().rest(0).at(4, 0.3f, 0.2f, 0, 0.1f, 0.35f, 0.15f).shake(5, 34, 3, IdleGestures.body(0.3f, 0, 0, 0.1f, 0.35f, 0.15f), IdleGestures.body(0, 0.35f, 0, 0, 0, 0), false)
					.at(36, 0.2f, 0, 0, 0.05f, 0.3f, 0.15f).rest(end).done();
			case 1 -> bodyTrack().rest(0).at(10, -0.15f, 0, 0, 0, 0, 0.05f).at(19, -0.2f, 0, 0, 0, 0, 0.05f).at(23, 0.35f, 0, 0, 0.12f, 0, 0.35f)
					.at(38, 0.3f, 0, 0, 0.1f, 0, 0.35f).rest(end).done();
			case 2 -> bodyTrack().rest(0).at(6, 0.2f, 0, 0, 0, 0, 0.05f).at(12, 0.9f, 0, 0, 0, 0, 0.05f).at(100, 0.9f, 0, 0, 0, 0, 0.05f)
					.at(104, 1.3f, 0, 0, 0, 0, 0.02f).at(114, 1.3f, 0, 0, 0, 0, 0.02f).rest(end).done();
			case 3 -> bodyTrack().rest(0).at(8, -0.1f, 0, 0, 0, 0, 0.1f)
					.shake(10, 116, 12, IdleGestures.body(-0.1f, 0, 0, 0, 0, 0.1f), IdleGestures.body(0, 0.4f, 0.06f, 0, 0, 0), false).at(118, 0.2f, 0, 0, 0, 0, 0.2f).rest(end).done();
			case 4 -> bodyTrack().rest(0).at(4, -0.15f, 0, 0, 0, 0, 0.2f).at(8, 0.55f, 0, 0, 0.18f, 0.25f, 0.3f).at(66, 0.55f, 0, 0, 0.18f, 0.25f, 0.3f)
					.at(70, -0.2f, 0, 0, 0, 0, 0.25f).rest(end).done();
			case 5 -> bodyTrack().rest(0).at(25, -0.25f, 0, 0, 0, 0, 0.12f).at(40, -0.25f, 0, 0, 0, 0, 0.12f).rest(end).done();
			case 6 -> bodyTrack().rest(0).at(8, -0.2f, 0, 0, 0, 0, 0.1f).at(40, -0.15f, 0.1f, 0, 0, 0, 0.1f).at(43, -0.3f, 0, 0, 0, 0, 0.1f)
					.at(46, 0.5f, 0, 0, 0, 0, 0.1f).at(50, 0.3f, 0, 0, 0, 0, 0.1f).rest(end).done();
			case 7 -> bodyTrack().rest(0).at(5, -0.2f, 0, 0, 0, 0, 0.3f).at(8, 0.6f, 0, 0, 0.25f, 0, 0.45f).at(20, 0.58f, 0, 0, 0.24f, 0, 0.45f)
					.at(26, -0.1f, 0, 0, 0.02f, 0, 0.42f).at(40, -0.08f, 0, 0, 0.02f, 0, 0.42f).rest(end).done();
			case 8 -> bodyTrack().rest(0).at(8, -0.22f, 0, 0, 0, 0, 0.15f).at(134, -0.25f, 0, 0, 0, 0, 0.15f).at(140, 0.1f, 0, 0, 0.03f, 0, 0.3f)
					.at(146, 0.08f, 0, 0, 0.03f, 0, 0.3f).rest(end).done();
			case 9 -> bodyTrack().rest(0).at(6, -0.3f, 0, 0, 0, 0, 0.3f).at(38, -0.32f, 0, 0, 0, 0, 0.3f).at(44, 0.2f, 0, 0, 0.05f, 0, 0.2f).rest(end).done();
			case 10 -> bodyTrack().rest(0).at(4, 0.15f, 0.15f, 0, 0.03f, 0.35f, 0).at(86, 0.18f, 0.15f, 0, 0.03f, 0.35f, 0).at(90, -0.15f, -0.1f, 0, 0, 0.2f, 0.1f)
					.rest(end).done();
			default -> null;
		};
	}

	static Key[] head(int variant) {
		if (variant < 0 || variant >= LENGTH.length) return null;
		int end = LENGTH[variant];
		return switch (variant) {
			case 1 -> track().rest(0).at(16, -0.6f, 0, 0).at(19, -0.6f, 0, 0).at(23, 0.4f, 0, 0).at(38, 0.3f, 0, 0).rest(end).done();
			case 4 -> track().rest(0).at(8, 0.6f, 0, 0).at(66, 0.6f, 0, 0).at(70, -0.3f, 0, 0).rest(end).done();
			case 5 -> track().rest(0).at(25, -0.4f, 0, 0).at(40, -0.4f, 0, 0).rest(end).done();
			case 6 -> track().rest(0).at(11, -0.7f, 0, 0).at(42, -0.7f, 0, 0).at(46, 0.5f, 0, 0).rest(end).done();
			case 7 -> track().rest(0).at(8, 0.5f, 0, 0).at(20, 0.45f, 0, 0).at(26, -0.2f, 0, 0).at(40, -0.1f, 0, 0).rest(end).done();
			case 8 -> track().rest(0).at(10, -0.7f, 0, 0).at(134, -0.7f, 0, 0).at(140, 0.1f, 0, 0).rest(end).done();
			case 9 -> track().rest(0).at(6, -0.85f, 0, 0).at(38, -0.85f, 0, 0).at(44, 0.35f, 0, 0).rest(end).done();
			default -> null;
		};
	}
}
