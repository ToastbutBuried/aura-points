package com.toast.aurapoints.network;

import com.toast.aurapoints.weather.StormType;

import java.util.ArrayList;
import java.util.List;

/**
 * CLIENT data (plain, no client classes): the storms the server last told us about (StormPacket), moved
 * along their drift between packets. client/StormFx draws them.
 */
public final class ClientStorms {

	private ClientStorms() {
	}

	public record Storm(StormType type, double x, double z, int radius, float strength, double vx, double vz, long at) {

		/** Where it is now (drift per second since the packet). */
		public double nowX() {
			return x + vx * (System.currentTimeMillis() - at) / 1000.0;
		}

		public double nowZ() {
			return z + vz * (System.currentTimeMillis() - at) / 1000.0;
		}

		/** 0..1 at a spot: full inside 80% of the radius, falling to 0 at the edge. */
		public float strengthAt(double px, double pz) {
			double dx = px - nowX(), dz = pz - nowZ(), d = Math.sqrt(dx * dx + dz * dz);
			if (d >= radius) return 0f;
			double edge = d <= radius * 0.8 ? 1 : (radius - d) / (radius * 0.2);
			return (float) (edge * strength);
		}

		public double distanceToEdge(double px, double pz) {
			double dx = px - nowX(), dz = pz - nowZ();
			return Math.abs(Math.sqrt(dx * dx + dz * dz) - radius);
		}
	}

	private static volatile List<Storm> storms = List.of();

	public static List<Storm> all() {
		return storms;
	}

	public static void set(String wire) {
		List<Storm> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		if (wire != null && !wire.isEmpty()) {
			for (String one : wire.split(";")) {
				String[] f = one.split(",");
				if (f.length < 8) continue;
				try {
					StormType type = StormType.byId(f[1]);
					if (type == null) continue;
					out.add(new Storm(type, Double.parseDouble(f[2]), Double.parseDouble(f[3]), Integer.parseInt(f[4]), Float.parseFloat(f[5]),
							Double.parseDouble(f[6]), Double.parseDouble(f[7]), now));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		storms = List.copyOf(out);
	}

	/** The strongest storm at a spot and how strong it is there (null if none). */
	public static Storm strongest(double px, double pz) {
		Storm best = null;
		float bestS = 0;
		for (Storm s : storms) {
			float v = s.strengthAt(px, pz);
			if (v > bestS) {
				best = s;
				bestS = v;
			}
		}
		return best;
	}

	public static void clear() {
		storms = List.of();
	}
}
