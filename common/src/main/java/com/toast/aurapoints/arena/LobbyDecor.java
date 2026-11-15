package com.toast.aurapoints.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static com.toast.aurapoints.arena.Arena.FLOOR;

/**
 * The lobby deck's finish (map 14): a paved aura wheel (the twelve aura colors in concrete, split like the glass ring), a
 * paved field instead of the grey checker, a lamp avenue along the east and west spokes and planter beds round the wheel.
 * Runs at the end of ArenaBuilder.buildLobby and only ever replaces the plain checker floor / paving, so the
 * fountain, trees, pedestals, beacons and rail stay as they are. tools/lobby_decor_spec.py is the preview of this class.
 */
final class LobbyDecor {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	/** One concrete per ArenaFx.GLASS entry, in the same order (the wheel's sectors match the glass ring). */
	private static final Block[] CONCRETE = {Blocks.PURPLE_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.WHITE_CONCRETE,
			Blocks.CYAN_CONCRETE, Blocks.LIME_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.BROWN_CONCRETE,
			Blocks.WHITE_CONCRETE, Blocks.RED_CONCRETE, Blocks.BLACK_CONCRETE};
	private static final Block[] FLOWERS = {Blocks.POPPY, Blocks.CORNFLOWER, Blocks.ALLIUM, Blocks.AZURE_BLUET};

	private LobbyDecor() {
	}

	static void build(ServerLevel level) {
		wheelAndField(level);
		lampAvenue(level);
		plantersBeds(level);
	}

	private static BlockState at(ServerLevel level, int x, int y, int z) {
		return level.getBlockState(new BlockPos(x, y, z));
	}

	private static void set(ServerLevel level, int x, int y, int z, Block block) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = block.defaultBlockState();
		if (level.getBlockState(pos) != state) level.setBlock(pos, state, FLAGS);
	}

	private static boolean checker(ServerLevel level, int x, int z) {
		BlockState s = at(level, x, FLOOR, z);
		return s.is(Blocks.POLISHED_ANDESITE) || s.is(Blocks.SMOOTH_STONE);
	}

	/** The decor's own floor blocks (plus the leftover checker at the edges): where a bed or a post may stand. */
	private static boolean paved(ServerLevel level, int x, int z) {
		BlockState s = at(level, x, FLOOR, z);
		return s.is(Blocks.STONE_BRICKS) || s.is(Blocks.POLISHED_ANDESITE) || s.is(Blocks.CRACKED_STONE_BRICKS) || s.is(Blocks.ANDESITE)
				|| s.is(Blocks.QUARTZ_BRICKS) || s.is(Blocks.SMOOTH_STONE);
	}

	private static boolean airAbove(ServerLevel level, int x, int z) {
		for (int k = 1; k <= 3; k++) if (!at(level, x, FLOOR + k, z).isAir()) return false;
		return true;
	}

	private static int sector(int x, int z) {
		double a = Math.atan2(z, x) + Math.PI;
		return (int) (a / (2 * Math.PI) * 12) % 12;
	}

	private static Block paving(int x, int z) {
		int hx = Math.floorDiv(x, 2), hz = Math.floorDiv(z, 2);
		int h = Math.floorMod(hx * 31 + hz * 17 + hx * hz * 7, 11);
		return h <= 5 ? Blocks.STONE_BRICKS : h <= 8 ? Blocks.POLISHED_ANDESITE : h == 9 ? Blocks.CRACKED_STONE_BRICKS : Blocks.ANDESITE;
	}

	private static void wheelAndField(ServerLevel level) {
		for (int x = -21; x <= 21; x++) {
			for (int z = -21; z <= 21; z++) {
				if (!checker(level, x, z)) continue;
				double d = Math.sqrt(x * x + z * z);
				if (d <= 6.6 || d > 20.5) continue;
				Block f;
				if (d <= 7.6) f = Blocks.QUARTZ_BRICKS;
				else if (d <= 8.6) f = Blocks.STONE_BRICKS;
				else if (d <= 9.2) f = Blocks.QUARTZ_BRICKS;
				else if (d <= 12.2) {
					double ang = Math.atan2(z, x) + Math.PI;
					double edge = (ang / (2 * Math.PI) * 12) % 1.0;
					f = edge < 0.05 || edge > 0.95 ? Blocks.SMOOTH_QUARTZ : CONCRETE[sector(x, z)];
				} else if (d <= 12.9) f = Blocks.QUARTZ_BRICKS;
				else if (d > 19.3 && d <= 20.1) f = Blocks.QUARTZ_BRICKS;
				else f = paving(x, z);
				set(level, x, FLOOR, z, f);
			}
		}
	}

	/** Wall posts with lanterns, every four blocks, both sides of the east and west spokes. */
	private static void lampAvenue(ServerLevel level) {
		for (int sx : new int[]{-1, 1}) {
			for (int ax : new int[]{13, 17, 20}) {
				for (int sz : new int[]{-2, 2}) {
					int x = sx * ax;
					if (!checker(level, x, sz) && !paved(level, x, sz)) continue;
					if (!airAbove(level, x, sz)) continue;
					set(level, x, FLOOR, sz, Blocks.POLISHED_ANDESITE);
					set(level, x, FLOOR + 1, sz, Blocks.STONE_BRICK_WALL);
					set(level, x, FLOOR + 2, sz, Blocks.STONE_BRICK_WALL);
					set(level, x, FLOOR + 3, sz, Blocks.LANTERN);
				}
			}
		}
	}

	/** A 3x3 of plain paving with a free ring round it. */
	private static boolean bedFits(ServerLevel level, int cx, int cz) {
		for (int x = cx - 2; x <= cx + 2; x++) {
			for (int z = cz - 2; z <= cz + 2; z++) {
				if (!airAbove(level, x, z)) return false;
				if (Math.abs(x - cx) <= 1 && Math.abs(z - cz) <= 1 && !paved(level, x, z)) return false;
			}
		}
		return true;
	}

	/** Beds (moss, flowers, a flowering azalea, lantern posts on the corners), greedy round the wheel, at least 7 apart. */
	private static void plantersBeds(ServerLevel level) {
		List<int[]> placed = new ArrayList<>();
		for (int step = 0; step < 24; step++) {
			double a = Math.toRadians(step * 15 + 7.5);
			for (double r : new double[]{17.5, 15.5, 13.5, 18.5}) {
				int cx = (int) Math.round(r * Math.cos(a)), cz = (int) Math.round(r * Math.sin(a));
				boolean near = false;
				for (int[] p : placed) if (Math.abs(cx - p[0]) < 7 && Math.abs(cz - p[1]) < 7) near = true;
				if (near || !bedFits(level, cx, cz)) continue;
				placed.add(new int[]{cx, cz});
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						int x = cx + dx, z = cz + dz;
						if (dx == 0 && dz == 0) {
							set(level, x, FLOOR, z, Blocks.MOSS_BLOCK);
							set(level, x, FLOOR + 1, z, Blocks.FLOWERING_AZALEA);
						} else if (dx != 0 && dz != 0) {
							set(level, x, FLOOR, z, Blocks.STONE_BRICKS);
							set(level, x, FLOOR + 1, z, Blocks.STONE_BRICK_WALL);
							set(level, x, FLOOR + 2, z, Blocks.LANTERN);
						} else {
							set(level, x, FLOOR, z, Blocks.MOSS_BLOCK);
							set(level, x, FLOOR + 1, z, FLOWERS[Math.floorMod(cx + cz + dx + dz, 4)]);
						}
					}
				}
				break;
			}
		}
	}
}
