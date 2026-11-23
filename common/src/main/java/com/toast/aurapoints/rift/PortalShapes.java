package com.toast.aurapoints.rift;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The trial gates' blueprints (5.55), one per trial kind, so a run's four wings never show the same gate. Built from four
 * kinds of stone (TrialPortal.POST..LAMP) round the opening (|along| <= 1, up 1..4, kept free at every depth), standing
 * from {@code out} -1 (towards the wall) to 0. Pieces come sorted from the floor up.
 */
final class PortalShapes {

	private PortalShapes() {
	}

	/** A piece's place: along the opening, up from the floor, out of its face; and its kind of stone. */
	record P(int s, int y, int z, int kind) {
	}

	private static final int POST = TrialPortal.POST, LINTEL = TrialPortal.LINTEL, CROWN = TrialPortal.CROWN, LAMP = TrialPortal.LAMP;

	private static final class Plan {
		final Map<Long, P> at = new LinkedHashMap<>();

		void put(int s, int y, int z, int kind) {
			if (y < 1 || (Math.abs(s) <= 1 && y <= TrialPortal.H)) return;
			at.put(((long) (s + 32) << 32) | ((long) (y + 32) << 16) | (z + 32), new P(s, y, z, kind));
		}

		/** Both sides. */
		void two(int s, int y, int z, int kind) {
			put(s, y, z, kind);
			put(-s, y, z, kind);
		}

		List<P> done() {
			List<P> out = new ArrayList<>(at.values());
			out.sort(Comparator.comparingInt(P::y).thenComparingInt(p -> -Math.abs(p.s())).thenComparingInt(P::s).thenComparingInt(P::z));
			return out;
		}
	}

	static int top(Trials.Kind k) {
		int t = 0;
		for (P p : of(k)) t = Math.max(t, p.y());
		return t;
	}

	static int half(Trials.Kind k) {
		int h = 0;
		for (P p : of(k)) h = Math.max(h, Math.abs(p.s()));
		return h;
	}

	static List<P> of(Trials.Kind k) {
		Plan p = new Plan();
		switch (k) {
			case DIG -> { // a half-buried trilithon: two unlike menhirs, a cracked capstone, rubble at the feet
				for (int z = -1; z <= 0; z++) {
					for (int y = 1; y <= 5; y++) {
						p.two(2, y, z, y == 3 ? CROWN : POST);
						if (y <= 4) p.put(-3, y, z, POST);
						if (y <= 3) p.put(3, y, z, POST);
					}
					for (int s = -4; s <= 4; s++) {
						if (s == 1) continue; // the crack
						p.put(s, 6, z, LINTEL);
						if (s >= -3 && s <= 3) p.put(s, 7, z, s == -3 || s == 3 ? POST : LINTEL);
					}
					p.put(1, 5, z, LINTEL); // its wedge, slipped down
				}
				p.two(4, 1, 0, POST);
				p.two(4, 2, 0, LAMP);
			}
			case ECHOES -> { // three pointed arches, each behind and over the last
				for (int n = 0; n < 3; n++) {
					int hw = 2 + n, z = n == 2 ? -1 : -n;
					for (int y = 1; y <= 4; y++) p.two(hw, y, z, n == 1 ? LINTEL : POST);
					for (int j = 0; j <= hw; j++) p.two(hw - j, 5 + j, z, j == hw ? (n == 2 ? LAMP : CROWN) : n == 1 ? POST : LINTEL);
				}
				p.two(2, 4, 0, CROWN);
				p.two(4, 4, -1, LAMP);
			}
			case BELLS -> { // a belfry: tall piers, a chamber with its bell, a stepped gable
				for (int y = 1; y <= 8; y++) for (int z = -1; z <= 0; z++) p.two(2, y, z, y == 4 || y == 8 ? CROWN : POST);
				for (int s = -1; s <= 1; s++) p.put(s, 5, 0, LINTEL);
				p.put(0, 7, 0, LAMP);
				p.put(0, 8, 0, CROWN);
				for (int s = -3; s <= 3; s++) for (int z = -1; z <= 0; z++) p.put(s, 9, z, LINTEL);
				for (int s = -1; s <= 1; s++) p.put(s, 10, 0, LINTEL);
				p.put(0, 11, 0, CROWN);
				for (int y = 1; y <= 2; y++) p.two(3, y, 0, POST);
			}
			case GLYPH -> { // standing rune slabs, tallest in the middle, capped by one long stone
				for (int y = 1; y <= 6; y++) p.two(2, y, 0, y % 2 == 0 ? CROWN : POST);
				for (int y = 1; y <= 8; y++) p.two(4, y, -1, y == 4 || y == 7 ? CROWN : POST);
				for (int y = 1; y <= 4; y++) p.two(6, y, 0, y == 3 ? CROWN : POST);
				p.two(4, 9, -1, LAMP);
				p.two(6, 5, 0, LAMP);
				for (int s = -2; s <= 2; s++) p.put(s, 7, 0, LINTEL);
				p.put(0, 8, 0, LAMP);
				p.put(0, 6, 0, CROWN);
			}
			case PRISM -> { // an upright crystal: a six-sided frame, double-edged
				int[] w = {2, 3, 4, 4, 4, 3, 2};
				for (int y = 1; y <= 7; y++) {
					p.two(w[y - 1], y, 0, y == 4 ? LAMP : POST);
					p.two(w[y - 1] + 1, y, -1, LINTEL);
				}
				for (int s = -1; s <= 1; s++) p.put(s, 8, 0, s == 0 ? LAMP : CROWN);
				for (int s = -2; s <= 2; s++) p.put(s, 9, -1, s == 0 ? CROWN : LINTEL);
				p.two(2, 8, -1, LINTEL);
				for (int y = 1; y <= 3; y++) p.two(2, y, -1, CROWN);
			}
			case STATUES -> { // two hooded giants bowing to each other, their hands meeting over the way
				for (int z = -1; z <= 0; z++) {
					for (int y = 1; y <= 7; y++) p.two(4, y, z, POST);
					for (int y = 1; y <= 8; y++) p.two(3, y, z, y <= 1 ? CROWN : POST);
					p.two(2, 8, z, LINTEL);
					p.two(3, 9, z, LINTEL);
					p.two(2, 9, z, LINTEL);
				}
				for (int y = 1; y <= 4; y++) p.two(2, y, 0, y == 4 ? CROWN : POST);
				p.two(2, 5, 0, LINTEL); // sleeves
				p.two(1, 5, 0, CROWN); // hands
				p.put(0, 5, 0, LAMP);
				p.two(2, 7, 0, LAMP); // faces under the hoods
			}
			case RINGS -> { // an open circle, a slimmer one behind it
				for (int s = -6; s <= 6; s++) for (int y = 1; y <= 11; y++) {
					double d = Math.hypot(s, y - 4.6);
					if (Math.abs(d - 4.2) < 0.55) p.put(s, y, 0, (s + y) % 4 == 0 ? CROWN : POST);
					if (Math.abs(d - 5.3) < 0.5) p.put(s, y, -1, LINTEL);
				}
				p.two(4, 5, 0, LAMP);
				p.put(0, 9, 0, LAMP);
				p.two(2, 1, 0, CROWN);
			}
		}
		return p.done();
	}
}
