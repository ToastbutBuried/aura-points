package com.toast.aurapoints.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import static com.toast.aurapoints.arena.Arena.FLOOR;

/**
 * The Altar Hall's decor (5.34, re-fitted to the resized hall): an aura wheel and a glowing rune rim in the floor, a
 * two-step plinth, buttresses and a gilded cornice outside, thirteen lancet windows, a glowing frieze belt and a lantern
 * crown over the oculus. The hall: inner radius {@code r} (Arena.ROOM_R 26), walls Arena.HALL_WALL_H (14) tall under a
 * stepped terrace ceiling that rises to the flat ceiling at Arena.HALL_CEIL_H (18), where the ten pillars (on the gilded
 * floor ring at Arena.PILLAR_RING) meet it. Everything is round the hall's centre {@code cx} and stays inside the
 * hall's own cleared square (|dx|, |dz| &lt;= R + 3), so no new chunks are touched. The preview spec
 * (tools/hall_decor_spec.py) uses the same formulas: keep them in sync.
 */
final class HallDecor {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	/** The wall's top course and the flat ceiling row, as world y. */
	private static final int WALL_TOP = FLOOR + Arena.HALL_WALL_H, CEIL = FLOOR + Arena.HALL_CEIL_H;
	/** The gilded floor ring the pillars stand on: (PILLAR_RING - 1, PILLAR_RING]. */
	private static final double RING_LO = Arena.PILLAR_RING - 1.0, RING_HI = Arena.PILLAR_RING;

	private HallDecor() {}

	/** {@code cx}: the hall's centre x, {@code r}: its inner radius (Arena.ROOM_R), {@code t}: the podium's tiers. */
	static void build(ServerLevel level, int cx, int r, int t) {
		floorInlay(level, cx, r, t);
		plinth(level, cx, r);
		buttresses(level, cx, r);
		windows(level, cx, r);
		lanterns(level, cx, r);
		frieze(level, cx, r);
		cornice(level, cx, r);
		crown(level, cx);
	}

	private static int rnd(double v) {
		return (int) Math.floor(v + 0.5);
	}

	private static void set(ServerLevel level, int x, int y, int z, Block block) {
		set(level, x, y, z, block.defaultBlockState());
	}

	private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		if (level.getBlockState(pos) != state) level.setBlock(pos, state, FLAGS);
	}

	private static boolean air(ServerLevel level, int x, int y, int z) {
		return level.getBlockState(new BlockPos(x, y, z)).isAir();
	}

	/**
	 * The aura wheel: ten spokes between the pillars (they stand at (i + 0.5) * 36 degrees) running from the hub to the
	 * rim, two chiseled rings round the hub, and an outer rim of chiseled rings round a glowing rune ring.
	 */
	private static void floorInlay(ServerLevel level, int cx, int r, int t) {
		double inner = t + 1.5;
		for (int x = -r; x <= r; x++) {
			for (int z = -r; z <= r; z++) {
				double d = Math.hypot(x, z);
				if (d > r + 0.4 || d <= inner) continue;
				// a calmer field: the inlay carries the contrast
				if ((d > 7.5 && d <= RING_LO) || (d > RING_HI && d <= r + 0.4)) {
					set(level, cx + x, FLOOR, z, ((x + z) & 1) == 0 ? Blocks.POLISHED_BLACKSTONE_BRICKS : Blocks.DEEPSLATE_TILES);
				}
				if (d > inner && d <= inner + 1.0) set(level, cx + x, FLOOR, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				else if (d > 7.5 && d <= 8.5) set(level, cx + x, FLOOR, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				else if ((d > 20.6 && d <= 21.4) || (d > 22.4 && d <= 23.2)) set(level, cx + x, FLOOR, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				else if (d > 21.4 && d <= 22.4) {
					// the rune rim: every fourth of 64 marks glows
					double a = (Math.atan2(z, x) + Math.PI) / (2 * Math.PI);
					if (((int) (a * 64)) % 4 == 0) {
						set(level, cx + x, FLOOR, z, Blocks.PURPLE_STAINED_GLASS);
						set(level, cx + x, FLOOR - 1, z, Blocks.SEA_LANTERN);
					} else set(level, cx + x, FLOOR, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				}
			}
		}
		// spokes: an amethyst core with chiseled flanks, gilded at both ends
		for (int i = 0; i < 10; i++) {
			double a = Math.toRadians(i * 36), ca = Math.cos(a), sa = Math.sin(a);
			for (int x = -r; x <= r; x++) {
				for (int z = -r; z <= r; z++) {
					double s = x * ca + z * sa, tau = Math.abs(-x * sa + z * ca);
					if (s < 8.5 || s > 20.5 || tau > 1.7) continue;
					double d = Math.hypot(x, z);
					if (d > RING_LO && d <= RING_HI) continue; // the pillars' gilded ring runs on unbroken
					if (tau <= 0.9) set(level, cx + x, FLOOR, z, s < 9.4 || s > 19.5 ? Blocks.GILDED_BLACKSTONE : Blocks.AMETHYST_BLOCK);
					else set(level, cx + x, FLOOR, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				}
			}
		}
	}

	/** Two steps round the foot of the wall: the hall sits on something. */
	private static void plinth(ServerLevel level, int cx, int r) {
		double out = r + 1.5;
		int lim = r + 3;
		for (int dx = -lim; dx <= lim; dx++) {
			for (int dz = -lim; dz <= lim; dz++) {
				double d = Math.hypot(dx, dz);
				if (d > out && d <= out + 1.0) {
					for (int y = FLOOR - 2; y < FLOOR; y++) set(level, cx + dx, y, dz, Blocks.POLISHED_BLACKSTONE_BRICKS);
					set(level, cx + dx, FLOOR, dz, ((dx + dz) & 1) == 0 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE);
				} else if (d > out + 1.0 && d <= out + 1.9) {
					for (int y = FLOOR - 2; y < FLOOR; y++) set(level, cx + dx, y, dz, Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
			}
		}
	}

	/** Outside buttresses on the interior buttresses' 16 angles (not the two by the door), stepping in as they rise. */
	private static void buttresses(ServerLevel level, int cx, int r) {
		int lim = r + 3;
		for (int i = 0; i < 16; i++) {
			double a = 2 * Math.PI * (i + 0.5) / 16;
			if (Math.abs(Math.toDegrees(a) - 180) < 14) continue;
			double ca = Math.cos(a), sa = Math.sin(a);
			for (int dx = -lim; dx <= lim; dx++) {
				for (int dz = -lim; dz <= lim; dz++) {
					double rho = dx * ca + dz * sa, tau = -dx * sa + dz * ca;
					if (rho <= r + 0.8 || Math.abs(tau) > 1.2) continue;
					int top = -1;
					if (rho <= r + 3.4 && Math.abs(tau) <= 1.2) top = FLOOR + 5;
					if (rho <= r + 2.6 && Math.abs(tau) <= 1.2) top = FLOOR + 9;
					if (rho <= r + 2.0 && Math.abs(tau) <= 0.6) top = FLOOR + 12;
					if (top < 0) continue;
					for (int y = FLOOR + 1; y <= top; y++) {
						set(level, cx + dx, y, dz, (y - FLOOR) % 5 == 0 ? Blocks.GILDED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
					}
					if (top == FLOOR + 12) set(level, cx + dx, top, dz, Blocks.CHISELED_POLISHED_BLACKSTONE);
				}
			}
		}
	}

	/** Half the lancet's width at row {@code sy} (0..7): straight to the spring line at 4, then a pointed arch to a point. */
	private static double windowHalf(int sy) {
		if (sy <= 4) return 2.0;
		return Math.max(0.5, 2.0 * (1 - Math.pow((sy - 4) / 4.0, 1.6)));
	}

	/**
	 * Thirteen lancet windows (every 22.5 degrees, none within 35 degrees of the door): purple glass from FLOOR+3 to +10, a
	 * gilded frame, a chiseled sill, a keystone at +11 (below the frieze belt at +12).
	 */
	private static void windows(ServerLevel level, int cx, int r) {
		double out = r + 1.5;
		int lim = r + 3;
		for (int k = 0; k < 16; k++) {
			if (Math.abs(k * 22.5 - 180) < 35) continue;
			double a = Math.toRadians(k * 22.5), ca = Math.cos(a), sa = Math.sin(a);
			for (int dx = -lim; dx <= lim; dx++) {
				for (int dz = -lim; dz <= lim; dz++) {
					double d = Math.hypot(dx, dz), rho = dx * ca + dz * sa;
					if (rho <= 0 || !(d > r + 0.4 && d <= out + 0.9)) continue;
					double tau = Math.abs(-dx * sa + dz * ca);
					for (int sy = -1; sy < 8; sy++) {
						int y = FLOOR + 3 + sy;
						if (sy == -1) {
							if (tau <= 3.2) set(level, cx + dx, y, dz, Blocks.CHISELED_POLISHED_BLACKSTONE);
							continue;
						}
						double half = windowHalf(sy);
						if (tau <= half) set(level, cx + dx, y, dz, Blocks.PURPLE_STAINED_GLASS);
						else if (tau <= half + 1.0 && d <= out) set(level, cx + dx, y, dz, sy % 3 == 0 ? Blocks.GILDED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
					}
					if (tau <= 0.6 && d <= out) set(level, cx + dx, FLOOR + 3 + 8, dz, Blocks.GILDED_BLACKSTONE);
				}
			}
		}
	}

	/** Soul lanterns on the outer plinth step, between the buttresses. */
	private static void lanterns(ServerLevel level, int cx, int r) {
		double out = r + 1.5;
		for (int i = 0; i < 16; i++) {
			if (Math.abs(i * 22.5 - 180) < 14) continue;
			double a = Math.toRadians(i * 22.5);
			int x = rnd(Math.cos(a) * (r + 3)), z = rnd(Math.sin(a) * (r + 3));
			double d = Math.hypot(x, z);
			if (d > out + 1.0 && d <= out + 1.9 && Math.abs(x) <= r + 3 && Math.abs(z) <= r + 3) set(level, cx + x, FLOOR, z, Blocks.SOUL_LANTERN);
		}
	}

	/** A glowing belt over the windows: sea lanterns in the wall course at +12, purple glass on both faces (not by the exit portal's frame). */
	private static void frieze(ServerLevel level, int cx, int r) {
		double out = r + 1.5;
		int y = FLOOR + 12, lim = r + 3;
		for (int dx = -lim; dx <= lim; dx++) {
			for (int dz = -lim; dz <= lim; dz++) {
				double d = Math.hypot(dx, dz);
				if (Math.abs(dz) <= 6 && dx < 0) continue;
				if (d > r + 0.4 && d <= out) set(level, cx + dx, y, dz, Blocks.SEA_LANTERN);
				else if ((d > out && d <= out + 1.0) || (d > r - 0.6 && d <= r + 0.4)) {
					if (air(level, cx + dx, y, dz)) set(level, cx + dx, y, dz, Blocks.PURPLE_STAINED_GLASS);
				}
			}
		}
	}

	/** A gilded lip on the wall's top course with a chiseled corbel under it, all the way round (under the first terrace row). */
	private static void cornice(ServerLevel level, int cx, int r) {
		double out = r + 1.5;
		int lim = r + 3;
		for (int dx = -lim; dx <= lim; dx++) {
			for (int dz = -lim; dz <= lim; dz++) {
				double d = Math.hypot(dx, dz);
				if (d > out && d <= out + 1.0) {
					set(level, cx + dx, WALL_TOP, dz, Blocks.GILDED_BLACKSTONE);
					set(level, cx + dx, WALL_TOP - 1, dz, Blocks.CHISELED_POLISHED_BLACKSTONE);
				}
			}
		}
	}

	/**
	 * The oculus crown (the flat ceiling is at CEIL): a chiseled drum ring at CEIL + 1, eight posts on thick 2x2 feet (four
	 * tall with end rods, four short with soul lanterns), a slab beam joining them at the short posts' height.
	 */
	private static void crown(ServerLevel level, int cx) {
		int base = CEIL + 1;
		for (int dx = -9; dx <= 9; dx++) {
			for (int dz = -9; dz <= 9; dz++) {
				double d = Math.hypot(dx, dz);
				if (d > 5.2 && d <= 6.8) set(level, cx + dx, base, dz, Blocks.CHISELED_POLISHED_BLACKSTONE);
			}
		}
		for (int k = 0; k < 8; k++) {
			double a = Math.toRadians(k * 45), ca = Math.cos(a), sa = Math.sin(a);
			int x = rnd(ca * 6.0), z = rnd(sa * 6.0);
			int sx = Math.abs(ca) > 0.3 ? (ca > 0 ? 1 : -1) : 1, sz = Math.abs(sa) > 0.3 ? (sa > 0 ? 1 : -1) : 1;
			boolean tall = k % 2 == 0;
			int h = tall ? 7 : 4;
			// a thick 2x2 foot, 3 high
			for (int y = base + 1; y <= base + 3; y++) {
				for (int fx : new int[]{0, sx}) for (int fz : new int[]{0, sz}) set(level, cx + x + fx, y, z + fz, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
			// then a slim wall post to the top, and its light
			for (int y = base + 4; y < base + 1 + h; y++) set(level, cx + x, y, z, Blocks.POLISHED_BLACKSTONE_WALL);
			set(level, cx + x, base + 1 + h, z, tall ? Blocks.END_ROD : Blocks.SOUL_LANTERN);
		}
		BlockState beam = Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
		for (int dx = -9; dx <= 9; dx++) {
			for (int dz = -9; dz <= 9; dz++) {
				double d = Math.hypot(dx, dz);
				if (d > 5.6 && d <= 6.4 && air(level, cx + dx, base + 4, dz)) set(level, cx + dx, base + 4, dz, beam);
			}
		}
	}
}
