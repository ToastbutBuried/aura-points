package com.toast.aurapoints.rift;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Set pieces for trial arenas (5.49.0, used by {@link TrialDig} and {@link TrialEchoes}): floating rock islands,
 * pillars (whole or broken), pointed arches, braziers, chain lanterns, wing banners and floating debris, all in the
 * arena frame (f forward, l side, dy from the floor) and in the aura's palette. Deterministic from the RandomSource
 * the caller passes in.
 */
final class DigDecor {

	private DigDecor() {
	}

	/** A world point in the arena frame with fractional offsets (camera keys, particles). */
	static Vec3 v(Trials.Trial t, double f, double l, double dy) {
		return new Vec3(t.a.getX() + 0.5 + t.fx * f + t.lx * l, Rifts.FLOOR + dy, t.a.getZ() + 0.5 + t.fz * f + t.lz * l);
	}

	/** The world direction of an arena-frame step (df, dl); one of them should be 0. */
	static Direction dir(Trials.Trial t, int df, int dl) {
		int dx = t.fx * df + t.lx * dl, dz = t.fz * df + t.lz * dl;
		Direction d = Direction.fromDelta(Integer.signum(dx), 0, Integer.signum(dz));
		return d != null ? d : Direction.NORTH;
	}

	static int hash(int a, int b, int c) {
		return Math.floorMod(a * 73856093 ^ b * 19349663 ^ c * 83492791, 97);
	}

	static boolean air(ServerLevel level, Trials.Trial t, int f, int l, int dy) {
		return level.getBlockState(t.arena(f, l, dy)).isAir();
	}

	/** Base stone with a clustered weathering of the edge block (by a coarse hash, so it comes in patches). */
	static Block stone(Trials.Trial t, int f, int l, int dy) {
		int h = hash(Math.floorDiv(f, 3), Math.floorDiv(l, 3), Math.floorDiv(dy, 3));
		return h < 18 ? t.pal.edge() : t.pal.base();
	}

	/**
	 * A floating island: a rounded-square (superellipse) top of {@code top} centred on (cf, cl) with half sizes
	 * (rf, rl), and a tapered rocky underside up to {@code depth} deep in the middle.
	 */
	static void island(ServerLevel level, Trials.Trial t, RandomSource r, int cf, int cl, int rf, int rl, int depth, Block top) {
		for (int f = -rf; f <= rf; f++) {
			for (int l = -rl; l <= rl; l++) {
				double e = Math.pow(Math.abs(f) / (double) rf, 4) + Math.pow(Math.abs(l) / (double) rl, 4);
				if (e > 1) continue;
				int wf = cf + f, wl = cl + l;
				t.set(level, wf, wl, 0, top);
				double inner = 1 - e;
				int d = 1 + (int) Math.round(depth * Math.pow(inner, 0.7)) + hash(wf, wl, 7) % 3;
				for (int dy = -1; dy >= -d; dy--) t.set(level, wf, wl, dy, dy > -d + 2 ? stone(t, wf, wl, dy) : t.pal.edge());
			}
		}
	}

	/** A rectangle of floor with a shallow underside (bridges, landings, checkpoint rows). */
	static void slab(ServerLevel level, Trials.Trial t, int f0, int f1, int l0, int l1, int depth, Block top) {
		for (int f = f0; f <= f1; f++) {
			for (int l = l0; l <= l1; l++) {
				t.set(level, f, l, 0, top);
				int edge = Math.min(Math.min(f - f0, f1 - f), Math.min(l - l0, l1 - l));
				int d = Math.min(depth, 1 + edge + hash(f, l, 3) % 2);
				for (int dy = -1; dy >= -d; dy--) t.set(level, f, l, dy, stone(t, f, l, dy));
			}
		}
	}

	/**
	 * A 3x3 pillar on a 5x5 plinth from dy0 up {@code h} blocks: edge-stone shaft with accent bands, a stepped
	 * capital and a light on top. Broken: cut short with a ragged top and rubble at the foot.
	 */
	static void pillar(ServerLevel level, Trials.Trial t, RandomSource r, int f, int l, int dy0, int h, boolean broken) {
		for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) t.set(level, f + a, l + b, dy0, t.pal.edge());
		int top = broken ? Math.max(3, (int) (h * (0.35 + r.nextFloat() * 0.35))) : h;
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				boolean corner = a != 0 && b != 0;
				int colTop = broken ? top - (corner ? r.nextInt(3) : r.nextInt(2)) : top;
				for (int dy = dy0 + 1; dy <= dy0 + colTop; dy++) {
					int k = dy - dy0;
					Block s = k % 7 == 0 && !corner ? t.pal.accent() : corner ? t.pal.edge() : t.pal.floor();
					t.set(level, f + a, l + b, dy, s);
				}
			}
		}
		if (!broken) {
			for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) if (Math.abs(a) == 2 || Math.abs(b) == 2) t.set(level, f + a, l + b, dy0 + h + 1, t.pal.edge());
			for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) t.set(level, f + a, l + b, dy0 + h + 1, t.pal.accent());
			t.set(level, f, l, dy0 + h + 2, t.pal.light());
		} else if (dy0 >= -1) {
			// rubble at the foot of a broken pillar (only when it stands on a floor)
			for (int k = 0; k < 5; k++) {
				int a = r.nextInt(7) - 3, b = r.nextInt(7) - 3;
				if (Math.abs(a) <= 2 && Math.abs(b) <= 2) continue;
				if (air(level, t, f + a, l + b, dy0 + 1) && !air(level, t, f + a, l + b, dy0)) t.set(level, f + a, l + b, dy0 + 1, r.nextBoolean() ? t.pal.edge() : t.pal.base());
			}
		}
	}

	/** A pillar standing on nothing: the shaft goes down to dy0 and a tapered rock foot hangs below it. */
	static void hangingFoot(ServerLevel level, Trials.Trial t, int f, int l, int dy0, int len) {
		for (int k = 1; k <= len; k++) {
			int w = k < len / 3 ? 2 : k < 2 * len / 3 ? 1 : 0;
			for (int a = -w; a <= w; a++) for (int b = -w; b <= w; b++) t.set(level, f + a, l + b, dy0 - k, stone(t, f + a, l + b, dy0 - k));
		}
	}

	/**
	 * A pointed arch across the side axis at f, from l0 to l1 (pillar centres), with its crown at dyTop. Broken: a
	 * random middle stretch is missing.
	 */
	static void arch(ServerLevel level, Trials.Trial t, RandomSource r, int f, int l0, int l1, int dySpring, int dyTop, boolean broken) {
		int mid = (l0 + l1) / 2, half = Math.max(1, (l1 - l0) / 2);
		int gap0 = broken ? mid - 2 - r.nextInt(4) : Integer.MAX_VALUE, gap1 = broken ? mid + 1 + r.nextInt(5) : Integer.MIN_VALUE;
		for (int l = l0; l <= l1; l++) {
			if (l >= gap0 && l <= gap1) continue;
			double x = Math.abs(l - mid) / (double) half;
			// pointed: the soffit rises steeply near the springers and meets at the crown
			int soffit = dySpring + (int) Math.round((dyTop - 1 - dySpring) * (1 - x * x * x));
			for (int dy = soffit; dy <= dyTop; dy++) {
				Block b = dy == dyTop ? t.pal.edge() : dy == soffit ? t.pal.accent() : t.pal.edge();
				for (int a = -1; a <= 1; a++) t.set(level, f + a, l, dy, a == 0 ? b : t.pal.edge());
			}
		}
		if (!broken) t.set(level, f, mid, dyTop + 1, t.pal.light());
	}

	/** A brazier: a 3x3 edge foot, a post and a lit campfire (fire auras burn red, the rest soul-blue). */
	static void brazier(ServerLevel level, Trials.Trial t, int f, int l, int dy) {
		for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) t.set(level, f + a, l + b, dy, t.pal.edge());
		for (int a : new int[]{-1, 1}) for (int b : new int[]{-1, 1}) t.set(level, f + a, l + b, dy + 1, t.pal.accent());
		t.set(level, f, l, dy + 1, t.pal.edge());
		t.set(level, f, l, dy + 2, warm(t) ? Blocks.CAMPFIRE : Blocks.SOUL_CAMPFIRE);
	}

	static boolean warm(Trials.Trial t) {
		return switch (t.run.aura) {
			case FIRE, BLOOD, EARTH, LIGHT, THUNDER -> true;
			default -> false;
		};
	}

	/** A chain from just below dyTop down {@code len} blocks with a lantern hanging at the end. */
	static void chainLantern(ServerLevel level, Trials.Trial t, int f, int l, int dyTop, int len) {
		for (int k = 1; k <= len; k++) t.set(level, f, l, dyTop - k, Blocks.CHAIN);
		Block lantern = warm(t) ? Blocks.LANTERN : Blocks.SOUL_LANTERN;
		t.set(level, f, l, dyTop - len - 1, lantern.defaultBlockState().setValue(LanternBlock.HANGING, true));
	}

	/** A banner in the wing's colour hanging at (f, l, dy) on the block at (f - df, l - dl). */
	static void banner(ServerLevel level, Trials.Trial t, int f, int l, int dy, int df, int dl) {
		Block b = switch (t.index) {
			case 0 -> Blocks.BLUE_WALL_BANNER;
			case 1 -> Blocks.LIME_WALL_BANNER;
			case 2 -> Blocks.YELLOW_WALL_BANNER;
			default -> Blocks.RED_WALL_BANNER;
		};
		BlockState s = b.defaultBlockState().setValue(WallBannerBlock.FACING, dir(t, df, dl));
		t.set(level, f, l, dy, s);
	}

	/** A floating lump of masonry about {@code size} across, sometimes with a light in it. */
	static void debris(ServerLevel level, Trials.Trial t, RandomSource r, int f, int l, int dy, int size) {
		double rad = size / 2.0;
		int n = (int) Math.ceil(rad);
		for (int a = -n; a <= n; a++) {
			for (int b = -n; b <= n; b++) {
				for (int c = -n; c <= n; c++) {
					double d = Math.sqrt(a * a + b * b * 1.6 + c * c);
					if (d > rad * (0.75 + 0.25 * (hash(f + a, l + c, dy + b) % 4) / 3.0)) continue;
					t.set(level, f + a, l + c, dy + b, b >= 0 && r.nextInt(4) == 0 ? t.pal.edge() : stone(t, f + a, l + c, dy + b));
				}
			}
		}
		if (size >= 3 && r.nextInt(3) == 0) t.set(level, f, l, dy + n, t.pal.light());
	}

	/**
	 * Teleports within the level. NeoForge's FakePlayer ignores {@code connection.teleport}, so dev-test fakes are
	 * moved directly as well.
	 */
	static void tp(net.minecraft.server.level.ServerPlayer p, ServerLevel level, Vec3 to, float yaw, float pitch) {
		p.teleportTo(level, to.x, to.y, to.z, yaw, pitch);
		if (com.toast.aurapoints.shim.Platform.get().isFakePlayer(p)) p.moveTo(to.x, to.y, to.z, yaw, pitch);
		p.fallDistance = 0;
	}

	/** An invisible light block (no collision) where there is air. */
	static void glow(ServerLevel level, Trials.Trial t, int f, int l, int dy, int lvl) {
		if (air(level, t, f, l, dy)) t.set(level, f, l, dy, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, lvl));
	}
}
