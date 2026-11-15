package com.toast.aurapoints.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.HashSet;
import java.util.Set;

import static com.toast.aurapoints.arena.Arena.FLOOR;
import static com.toast.aurapoints.arena.Arena.RING_FLOOR;
import static com.toast.aurapoints.arena.Arena.RING_R;
import static com.toast.aurapoints.arena.Arena.RING_Z;

/**
 * The duel ring's looks outside the fighting space (ArenaBuilder.buildRingFrame calls {@link #build} last): a rounded
 * hull of masonry under the rim with glow studs and hanging soul lanterns, a plinth and a cornice round the back
 * wall, and a pier with a lantern or finial every 15 degrees. Cosmetic only: it fills air, never replaces a block,
 * and stays outside radius BACK_R + 0.5 above the hull. Also the pit floor's default checker ({@link #floor}).
 */
final class RingDecor {

	private RingDecor() {
	}

	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	/** The stands' sizes, as in ArenaBuilder (SPECTATOR_R = RING_R + 5, STAND_ROWS = 3, BACK_R = SPECTATOR_R + STAND_ROWS + 1). Keep in sync. */
	private static final int STAND_ROWS = 3, BACK_R = RING_R + 5 + STAND_ROWS + 1;
	/** The hull's bowl: radius at the rim and how many layers deep it goes. */
	private static final double HULL_R = BACK_R + 1.5;
	private static final int HULL_DEPTH = 15, PIERS = 24;

	/** The pit floor's default checker (layouts and the pads, rings and centre overrule it): dark against mid grey, so it reads in a fight. */
	static BlockState floor(int x, int dz) {
		return ((x + dz & 1) == 0 ? Blocks.POLISHED_BLACKSTONE : Blocks.POLISHED_BASALT).defaultBlockState();
	}

	static void build(ServerLevel level) {
		hull(level);
		rimDress(level);
	}

	// ---- helpers ----------------------------------------------------------------------------

	private static void put(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		if (level.getBlockState(pos) != state) level.setBlock(pos, state, FLAGS);
	}

	private static void putAir(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		if (level.getBlockState(pos).isAir()) level.setBlock(pos, state, FLAGS);
	}

	private static void putAir(ServerLevel level, int x, int y, int z, Block block) {
		putAir(level, x, y, z, block.defaultBlockState());
	}

	private static BlockState stair(Block block, Direction facing, Half half) {
		return block.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half);
	}

	private static int hash(int x, int y, int z) {
		return Math.floorMod((x * 73856093) ^ (z * 19349663) ^ (y * 83492791), 97);
	}

	// ---- under the rim ------------------------------------------------------------------------

	/** A bowl of masonry under the back wall (dark and tiled at the top, rock lower), studs and lantern chains. */
	private static void hull(ServerLevel level) {
		int top = RING_FLOOR - 2;
		for (int k = 0; k < HULL_DEPTH - 1; k++) {
			int y = top - k;
			double t = (k + 1) / (double) HULL_DEPTH;
			double r = HULL_R * Math.sqrt(Math.max(0.0, 1 - t * t));
			int ri = (int) Math.ceil(r);
			for (int x = -ri; x <= ri; x++) {
				for (int dz = -ri; dz <= ri; dz++) {
					if (Math.sqrt(x * x + dz * dz) > r) continue;
					int h = hash(x, y, dz + RING_Z);
					Block m;
					if (k <= 1) m = h % 3 == 0 ? Blocks.POLISHED_BLACKSTONE_BRICKS : Blocks.DEEPSLATE_TILES;
					else if (k <= 4) m = new Block[]{Blocks.DEEPSLATE_BRICKS, Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_TILES, Blocks.COBBLED_DEEPSLATE}[h % 4];
					else m = new Block[]{Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.ANDESITE, Blocks.COBBLED_DEEPSLATE, Blocks.STONE, Blocks.DEEPSLATE}[h % 7];
					putAir(level, x, y, dz + RING_Z, m);
				}
			}
			if (k == 2) {
				// glow studs on the belly, every 12 degrees
				for (int n = 0; n < 30; n++) {
					double a = n * Math.PI / 15;
					int x = (int) Math.round(Math.cos(a) * (r - 0.6)), dz = (int) Math.round(Math.sin(a) * (r - 0.6));
					if (!level.getBlockState(new BlockPos(x, y, dz + RING_Z)).isAir()) put(level, x, y, dz + RING_Z, Blocks.SEA_LANTERN.defaultBlockState());
				}
			}
		}
		// eight soul lanterns on chains from the underside
		for (int n = 0; n < 8; n++) {
			double a = n * Math.PI / 4 + Math.PI / 8;
			int x = (int) Math.round(Math.cos(a) * (HULL_R - 13)), z = (int) Math.round(Math.sin(a) * (HULL_R - 13)) + RING_Z;
			int low = Integer.MAX_VALUE;
			for (int y = RING_FLOOR - 2; y >= FLOOR - 27; y--) if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) low = y;
			if (low == Integer.MAX_VALUE) continue;
			for (int j = 1; j <= 4; j++) put(level, x, low - j, z, Blocks.CHAIN.defaultBlockState());
			put(level, x, low - 5, z, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		}
	}

	// ---- round the back wall ------------------------------------------------------------------

	private static boolean wallAdjacent(int x, int dz) {
		return Math.hypot(x + 1, dz) <= BACK_R + 0.5 || Math.hypot(x - 1, dz) <= BACK_R + 0.5
				|| Math.hypot(x, dz + 1) <= BACK_R + 0.5 || Math.hypot(x, dz - 1) <= BACK_R + 0.5;
	}

	/** A plinth step at the wall's foot, an upside-down stair cornice under its top, and a pier with a lantern or finial every 15 degrees. */
	private static void rimDress(ServerLevel level) {
		int ri = BACK_R + 2;
		Set<Long> piers = new HashSet<>();
		for (int n = 0; n < PIERS; n++) {
			double a = (n + 0.5) * 2 * Math.PI / PIERS;
			double bestDa = Double.MAX_VALUE;
			int bx = 0, bz = 0;
			for (int x = -ri; x <= ri; x++) {
				for (int dz = -ri; dz <= ri; dz++) {
					double d = Math.sqrt(x * x + dz * dz);
					if (d <= BACK_R + 0.5 || d > BACK_R + 1.5 || !wallAdjacent(x, dz)) continue;
					double da = (Math.atan2(dz, x) - a) % (2 * Math.PI);
					if (da < 0) da += 2 * Math.PI;
					da = Math.min(da, 2 * Math.PI - da);
					if (da < bestDa) {
						bestDa = da;
						bx = x;
						bz = dz;
					}
				}
			}
			if (bestDa == Double.MAX_VALUE || bz < 0 && Math.abs(bx) <= 5) continue;   // not on the beacon tower's side
			piers.add(key(bx, bz));
		}
		int cap = FLOOR + STAND_ROWS + 4;
		for (int x = -ri; x <= ri; x++) {
			for (int dz = -ri; dz <= ri; dz++) {
				double d = Math.sqrt(x * x + dz * dz);
				if (d <= BACK_R + 0.5 || d > BACK_R + 1.5 || !wallAdjacent(x, dz)) continue;
				int z = RING_Z + dz;
				Direction inward = Direction.getNearest(x, 0, dz).getOpposite();
				putAir(level, x, RING_FLOOR - 1, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				if (piers.contains(key(x, dz))) {
					for (int y = RING_FLOOR; y < RING_FLOOR + 3; y++) putAir(level, x, y, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					for (int y = RING_FLOOR + 3; y < cap; y++) putAir(level, x, y, z, Blocks.STONE_BRICKS);
					putAir(level, x, cap, z, Blocks.CHISELED_STONE_BRICKS);
					putAir(level, x, cap + 1, z, ((x + dz) & 1) == 0 ? Blocks.LANTERN : Blocks.STONE_BRICK_WALL);
				} else {
					putAir(level, x, RING_FLOOR, z, stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, inward, Half.BOTTOM));
					putAir(level, x, FLOOR + STAND_ROWS + 3, z, stair(Blocks.STONE_BRICK_STAIRS, inward, Half.TOP));
				}
			}
		}
	}

	private static long key(int x, int dz) {
		return ((long) x << 32) ^ (dz & 0xFFFFFFFFL);
	}
}
