package com.toast.aurapoints.rift;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Small set pieces shared by the Choir of Bells and the Glyph Vault (5.49.0): rock undersides, floating debris,
 * banners and lanterns in a trial's arena frame, a text panel with a backdrop, and the players standing in the arena.
 */
final class TrialDecor {

	private TrialDecor() {
	}

	/** The world direction of an arena-frame step (df, dl). */
	static Direction dir(Trials.Trial t, int df, int dl) {
		int dx = t.fx * df + t.lx * dl, dz = t.fz * df + t.lz * dl;
		if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
		return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/** A cheap deterministic hash of an arena cell (0..96). */
	static int hash(int f, int l, int dy) {
		return Math.floorMod(f * 73856093 ^ l * 19349663 ^ dy * 83492791, 97);
	}

	/**
	 * A rocky underside below every built floor cell in the box f0..f1 x l0..l1: {@code depth(f, l)} blocks of the
	 * palette's base, the deepest third in its edge stone.
	 */
	static void underside(ServerLevel level, Trials.Trial t, int f0, int f1, int l0, int l1, java.util.function.IntBinaryOperator depth) {
		for (int f = f0; f <= f1; f++) {
			for (int l = l0; l <= l1; l++) {
				if (level.getBlockState(t.arena(f, l, 0)).isAir()) continue;
				int d = Math.max(1, depth.applyAsInt(f, l));
				for (int k = 1; k <= d; k++) t.set(level, f, l, -k, k > d * 2 / 3 && d > 4 ? t.pal.edge() : t.pal.base());
			}
		}
	}

	/**
	 * {@code n} floating rocks in the ring rMin..rMax around the arena middle, between dy0 and dy1; never with their
	 * middle where {@code keepOut(f, l)} says so (walkways, the build itself).
	 */
	static void debris(ServerLevel level, Trials.Trial t, RandomSource r, int n, int rMin, int rMax, int dy0, int dy1, java.util.function.BiPredicate<Integer, Integer> keepOut) {
		for (int k = 0, tries = 0; k < n && tries < n * 8; tries++) {
			double a = r.nextDouble() * Math.PI * 2, d = rMin + r.nextDouble() * (rMax - rMin);
			int cf = (int) Math.round(Math.cos(a) * d), cl = (int) Math.round(Math.sin(a) * d), cy = dy0 + r.nextInt(Math.max(1, dy1 - dy0));
			if (keepOut.test(cf, cl)) continue;
			k++;
			int rad = 1 + r.nextInt(3);
			for (int f = -rad; f <= rad; f++)
				for (int l = -rad; l <= rad; l++)
					for (int y = -rad; y <= 1; y++) {
						double q = f * f + l * l + (y < 0 ? y * y * 2.2 : 0);
						if (q > rad * rad + 0.6) continue;
						Block b = y == 1 ? (q > rad * rad - 1 ? Blocks.AIR : t.pal.floor()) : y == 0 ? t.pal.floor() : t.pal.base();
						if (b != Blocks.AIR) t.set(level, cf + f, cl + l, cy + y, b);
					}
			// a broken column on some, a light under others
			if (r.nextInt(3) == 0) {
				int h = 1 + r.nextInt(4);
				for (int y = 1; y <= h; y++) t.set(level, cf, cl, cy + y, t.pal.edge());
			} else if (r.nextInt(2) == 0) {
				t.set(level, cf, cl, cy - rad - 1, t.pal.light());
			}
		}
	}

	/** A wall banner on the face (df, dl) of the block at (f, l, dy). */
	static void banner(ServerLevel level, Trials.Trial t, int f, int l, int dy, int df, int dl, Block banner) {
		t.set(level, f + df, l + dl, dy, banner.defaultBlockState().setValue(WallBannerBlock.FACING, dir(t, df, dl)));
	}

	/** A lantern on {@code chain} chain links under the block at (f, l, dy). */
	static void lantern(ServerLevel level, Trials.Trial t, int f, int l, int dy, int chain, boolean soul) {
		for (int k = 1; k <= chain; k++) t.set(level, f, l, dy - k, Blocks.CHAIN);
		t.set(level, f, l, dy - chain - 1, (soul ? Blocks.SOUL_LANTERN : Blocks.LANTERN).defaultBlockState().setValue(LanternBlock.HANGING, true));
	}

	/** A brazier: edge stone, an accent bowl, the palette's light on top. */
	static void brazier(ServerLevel level, Trials.Trial t, int f, int l) {
		t.set(level, f, l, 1, t.pal.edge());
		t.set(level, f, l, 2, t.pal.accent());
		t.set(level, f, l, 3, t.pal.light());
	}

	/** A text display with a dark backdrop (a tablet, a sign), kept in the trial's displays. */
	static Display.TextDisplay panel(ServerLevel level, Trials.Trial t, Vec3 at, Component text, float scale) {
		Display.TextDisplay d = com.toast.aurapoints.arena.Holograms.makePublic(level, at, text, scale, 0xA0101018);
		if (d == null) return null;
		d.addTag(Trials.DISPLAY_TAG);
		level.addFreshEntity(d);
		t.displays.add(d);
		return d;
	}

	static void text(Display.TextDisplay d, Component text) {
		if (d != null) com.toast.aurapoints.arena.Holograms.setTextPublic(d, text);
	}

	/** Run players standing in the arena right now (real or dev). */
	static List<ServerPlayer> inArena(ServerLevel level, Trials.Trial t) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : t.inside) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p == null) p = Rifts.TEST_PLAYERS.get(id);
			if (p != null && t.inArena(p.position())) out.add(p);
		}
		return out;
	}

	static boolean fake(ServerPlayer p) {
		return com.toast.aurapoints.shim.Platform.get().isFakePlayer(p);
	}

	/** Same column (arena f, l) and inside dy0..dy1. */
	static boolean at(Trials.Trial t, BlockPos pos, int f, int l, int dy0, int dy1) {
		BlockPos b = t.arena(f, l, 0);
		return pos.getX() == b.getX() && pos.getZ() == b.getZ() && pos.getY() >= Rifts.FLOOR + dy0 && pos.getY() <= Rifts.FLOOR + dy1;
	}
}
