package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Builds a rift realm (5.46.0, Relic Trials) in a run's slot of the aurapoints:rift dimension: the hub island with the
 * altar in the middle, four trial islands at the compass points joined to it by bridges, tapered rocky undersides,
 * floating debris and the aura's own set dressing (Fire basalt and lava, Frost ice spikes, Verdant trees...). Every
 * run gets its own seeded layout. The slot is cleared first (only chunk sections that hold anything are walked), so a
 * slot can be built over again whatever an older run or an older version left there.
 */
final class RealmBuilder {

	private RealmBuilder() {
	}

	record Palette(Block floor, Block accent, Block edge, Block light, Block gate, Block base) {
	}

	static Palette palette(AuraType aura) {
		return switch (aura) {
			case FIRE -> new Palette(Blocks.BLACKSTONE, Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.SHROOMLIGHT, Blocks.ORANGE_STAINED_GLASS, Blocks.BASALT);
			case WATER -> new Palette(Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.PRISMARINE, Blocks.SEA_LANTERN, Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.DARK_PRISMARINE);
			case WIND -> new Palette(Blocks.CALCITE, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_PILLAR, Blocks.PEARLESCENT_FROGLIGHT, Blocks.WHITE_STAINED_GLASS, Blocks.DIORITE);
			case FROST -> new Palette(Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SNOW_BLOCK, Blocks.SEA_LANTERN, Blocks.CYAN_STAINED_GLASS, Blocks.PACKED_ICE);
			case VERDANT -> new Palette(Blocks.MOSS_BLOCK, Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_STONE_BRICKS, Blocks.OCHRE_FROGLIGHT, Blocks.LIME_STAINED_GLASS, Blocks.ROOTED_DIRT);
			case THUNDER -> new Palette(Blocks.WAXED_CUT_COPPER, Blocks.YELLOW_GLAZED_TERRACOTTA, Blocks.POLISHED_DEEPSLATE, Blocks.GLOWSTONE, Blocks.YELLOW_STAINED_GLASS, Blocks.DEEPSLATE);
			case EARTH -> new Palette(Blocks.PACKED_MUD, Blocks.MUD_BRICKS, Blocks.DRIPSTONE_BLOCK, Blocks.SHROOMLIGHT, Blocks.BROWN_STAINED_GLASS, Blocks.TERRACOTTA);
			case LIGHT -> new Palette(Blocks.SMOOTH_QUARTZ, Blocks.GOLD_BLOCK, Blocks.QUARTZ_BRICKS, Blocks.GLOWSTONE, Blocks.YELLOW_STAINED_GLASS, Blocks.CALCITE);
			case SHADOW -> new Palette(Blocks.DEEPSLATE_TILES, Blocks.OBSIDIAN, Blocks.POLISHED_DEEPSLATE, Blocks.PEARLESCENT_FROGLIGHT, Blocks.PURPLE_STAINED_GLASS, Blocks.COBBLED_DEEPSLATE);
			case BLOOD -> new Palette(Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS, Blocks.CHISELED_NETHER_BRICKS, Blocks.SHROOMLIGHT, Blocks.RED_STAINED_GLASS, Blocks.NETHERRACK);
			default -> new Palette(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.SEA_LANTERN, Blocks.GLASS, Blocks.STONE);
		};
	}

	/** The aura's set dressing: a spire block, its cap, and an optional "growth" scattered on the islands. */
	private record Dressing(Block spire, Block cap, Block growth) {
	}

	private static Dressing dressing(AuraType aura) {
		return switch (aura) {
			case FIRE -> new Dressing(Blocks.BASALT, Blocks.MAGMA_BLOCK, null);
			case WATER -> new Dressing(Blocks.PRISMARINE, Blocks.SEA_LANTERN, Blocks.GLOW_LICHEN);
			case WIND -> new Dressing(Blocks.QUARTZ_PILLAR, Blocks.WHITE_WOOL, null);
			case FROST -> new Dressing(Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SNOW);
			case VERDANT -> new Dressing(Blocks.OAK_LOG, Blocks.AZALEA_LEAVES, Blocks.SHORT_GRASS);
			case THUNDER -> new Dressing(Blocks.WAXED_COPPER_BLOCK, Blocks.LIGHTNING_ROD, null);
			case EARTH -> new Dressing(Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE, null);
			case LIGHT -> new Dressing(Blocks.QUARTZ_PILLAR, Blocks.GLOWSTONE, null);
			case SHADOW -> new Dressing(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, null);
			case BLOOD -> new Dressing(Blocks.CRIMSON_STEM, Blocks.NETHER_WART_BLOCK, Blocks.CRIMSON_ROOTS);
			default -> new Dressing(Blocks.STONE_BRICKS, Blocks.SEA_LANTERN, null);
		};
	}

	static final int HUB_R = 15, ISLAND_R = 12, DIST = 48, PEDESTAL = 6;
	/** The four directions to the trial islands, in trial order: north, east, south, west (x, z). */
	static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

	static void set(ServerLevel level, int x, int y, int z, Block b) {
		set(level, x, y, z, b.defaultBlockState());
	}

	static void set(ServerLevel level, int x, int y, int z, BlockState s) {
		// no neighbour updates: nothing pops off, and lit blocks stay as they are
		level.setBlock(new BlockPos(x, y, z), s, 2 | 16);
	}

	// ---------------------------------------------------------------
	// Clearing
	// ---------------------------------------------------------------

	/** Air in the slot's box: x ±CLEAR around the slot, z -CLEAR..CLEAR_Z_MAX (the pre-5.46 room line ran along +z). */
	private static final int CLEAR = 90, CLEAR_Z_MAX = 150;

	static void clear(ServerLevel level, int slotX) {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int cx = (slotX - CLEAR) >> 4; cx <= (slotX + CLEAR) >> 4; cx++) {
			for (int cz = (-CLEAR) >> 4; cz <= CLEAR_Z_MAX >> 4; cz++) {
				LevelChunk chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				for (int si = 0; si < sections.length; si++) {
					if (sections[si].hasOnlyAir()) continue;
					int baseY = level.getSectionYFromSectionIndex(si) << 4;
					for (int dx = 0; dx < 16; dx++) {
						for (int dz = 0; dz < 16; dz++) {
							for (int dy = 0; dy < 16; dy++) {
								if (sections[si].getBlockState(dx, dy, dz).isAir()) continue;
								level.setBlock(new BlockPos((cx << 4) + dx, baseY + dy, (cz << 4) + dz), air, 2 | 16);
							}
						}
					}
				}
			}
		}
	}

	// ---------------------------------------------------------------
	// Building
	// ---------------------------------------------------------------

	static void build(ServerLevel level, Rifts.Run run) {
		Palette p = palette(run.aura);
		Dressing dr = dressing(run.aura);
		RandomSource r = RandomSource.create(run.id * 7919L + run.aura.ordinal());
		BlockPos hub = run.hub();
		clear(level, hub.getX());
		island(level, r, p, hub, HUB_R, true);
		for (int i = 0; i < 4; i++) {
			BlockPos c = run.island(i);
			island(level, r, p, c, ISLAND_R, false);
			bridge(level, p, hub, c, DIRS[i]);
		}
		altar(level, p, run);
		// spires and growths around the islands' rims
		for (int i = -1; i < 4; i++) {
			BlockPos c = i < 0 ? hub : run.island(i);
			int rad = i < 0 ? HUB_R : ISLAND_R;
			int spires = 3 + r.nextInt(3);
			for (int k = 0; k < spires; k++) {
				double a = r.nextDouble() * Math.PI * 2;
				int x = c.getX() + (int) Math.round(Math.cos(a) * (rad - 1.5)), z = c.getZ() + (int) Math.round(Math.sin(a) * (rad - 1.5));
				if (nearBridge(run, x, z)) continue;
				spire(level, dr, x, Rifts.FLOOR + 1, z, 3 + r.nextInt(5));
			}
			if (dr.growth != null) {
				for (int k = 0; k < rad * 2; k++) {
					double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * (rad - 2);
					int x = c.getX() + (int) Math.round(Math.cos(a) * d), z = c.getZ() + (int) Math.round(Math.sin(a) * d);
					if (level.getBlockState(new BlockPos(x, Rifts.FLOOR + 1, z)).isAir() && level.getBlockState(new BlockPos(x, Rifts.FLOOR, z)).isSolid())
						set(level, x, Rifts.FLOOR + 1, z, dr.growth);
				}
			}
		}
		// floating debris: small rocky islets drifting around the realm
		for (int k = 0; k < 14; k++) {
			double a = r.nextDouble() * Math.PI * 2, d = 26 + r.nextDouble() * 50;
			int x = hub.getX() + (int) Math.round(Math.cos(a) * d), z = hub.getZ() + (int) Math.round(Math.sin(a) * d);
			int y = Rifts.FLOOR - 12 + r.nextInt(30);
			if (Math.abs(y - Rifts.FLOOR) < 6 && nearAnyIsland(run, x, z, 6)) continue;
			blob(level, r, p, x, y, z, 1 + r.nextInt(3));
		}
	}

	/** A disk of floor with a tapering rocky underside, a low broken rim, lit posts. */
	private static void island(ServerLevel level, RandomSource r, Palette p, BlockPos c, int rad, boolean hub) {
		int y = Rifts.FLOOR;
		for (int dx = -rad - 1; dx <= rad + 1; dx++) {
			for (int dz = -rad - 1; dz <= rad + 1; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				// a ragged edge
				double edge = rad + 0.5 + Math.sin(Math.atan2(dz, dx) * 5 + c.getX()) * 0.6;
				if (d > edge) continue;
				int x = c.getX() + dx, z = c.getZ() + dz;
				Block top = r.nextInt(7) == 0 ? p.accent : p.floor;
				set(level, x, y, z, top);
				double t = 1 - d / edge;
				int depth = 1 + (int) Math.round(t * t * (hub ? 14 : 10) + r.nextInt(3));
				for (int k = 1; k <= depth; k++) set(level, x, y - k, z, k > depth - 2 && r.nextInt(3) == 0 ? p.edge : p.base);
				// the rim: low wall pieces with gaps
				if (d > edge - 1 && r.nextInt(3) != 0) set(level, x, y + 1, z, p.edge);
			}
		}
		// the hub: a raised outer terrace, so the arena has an edge to it
		if (hub) {
			for (int dx = -rad; dx <= rad; dx++) {
				for (int dz = -rad; dz <= rad; dz++) {
					double d = Math.sqrt(dx * dx + dz * dz);
					if (d < rad - 3.5 || d > rad - 0.5) continue;
					set(level, c.getX() + dx, y + 1, c.getZ() + dz, d < rad - 2.5 ? p.edge : p.floor);
				}
			}
		}
		// rocky mounds around the rim (outside the trials' working area)
		int mounds = hub ? 4 : 3 + r.nextInt(3);
		for (int k = 0; k < mounds; k++) {
			double a = r.nextDouble() * Math.PI * 2, dist = rad - 3.5 + r.nextDouble() * 2;
			int mx = c.getX() + (int) Math.round(Math.cos(a) * dist), mz = c.getZ() + (int) Math.round(Math.sin(a) * dist);
			int mr = 2 + r.nextInt(2), mh = 1 + r.nextInt(3);
			for (int dx = -mr; dx <= mr; dx++) {
				for (int dz = -mr; dz <= mr; dz++) {
					double t = 1 - Math.sqrt(dx * dx + dz * dz) / (mr + 0.5);
					if (t <= 0) continue;
					int h = (int) Math.ceil(t * mh);
					for (int up = 1; up <= h; up++) set(level, mx + dx, y + up, mz + dz, up == h ? (r.nextInt(4) == 0 ? p.accent : p.floor) : p.base);
				}
			}
		}
		// lit posts at the four diagonals
		int pr = rad - 3;
		for (int[] o : new int[][]{{1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) {
			int px = c.getX() + (int) Math.round(o[0] * pr * 0.7), pz = c.getZ() + (int) Math.round(o[1] * pr * 0.7);
			int h = hub ? 5 : 3;
			for (int k = 1; k <= h; k++) set(level, px, y + k, pz, p.edge);
			set(level, px, y + h + 1, pz, p.light);
		}
	}

	private static void bridge(ServerLevel level, Palette p, BlockPos hub, BlockPos island, int[] dir) {
		int y = Rifts.FLOOR;
		int px = -dir[1], pz = dir[0];
		for (int s = HUB_R - 2; s <= DIST - ISLAND_R + 2; s++) {
			int bx = hub.getX() + dir[0] * s, bz = hub.getZ() + dir[1] * s;
			for (int w = -1; w <= 1; w++) {
				int x = bx + px * w, z = bz + pz * w;
				set(level, x, y, z, w == 0 ? p.accent : p.floor);
				set(level, x, y - 1, z, p.base);
				for (int up = 1; up <= 4; up++) set(level, x, y + up, z, Blocks.AIR);
			}
			// railings every few blocks, with a light
			if (s % 6 == 0) {
				for (int w : new int[]{-2, 2}) {
					set(level, bx + px * w, y, bz + pz * w, p.edge);
					set(level, bx + px * w, y + 1, bz + pz * w, p.edge);
					set(level, bx + px * w, y + 2, bz + pz * w, p.light);
				}
			}
		}
	}

	/** The altar on the hub: a stepped dais, a focus stone in the middle, a pedestal toward each trial island. */
	private static void altar(ServerLevel level, Palette p, Rifts.Run run) {
		BlockPos c = run.hub();
		int y = Rifts.FLOOR;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d > 4.5) continue;
				set(level, c.getX() + dx, y, c.getZ() + dz, d < 1.5 ? p.light : d < 3 ? p.accent : p.edge);
				if (d < 3) set(level, c.getX() + dx, y + 1, c.getZ() + dz, d < 1.5 ? p.accent : p.floor);
			}
		}
		set(level, c.getX(), y + 1, c.getZ(), p.light);
		for (int i = 0; i < 4; i++) {
			BlockPos ped = run.pedestal(i);
			set(level, ped.getX(), y + 1, ped.getZ(), p.edge);
			set(level, ped.getX(), y + 2, ped.getZ(), p.accent);
			set(level, ped.getX(), y, ped.getZ(), p.light);
		}
	}

	private static void spire(ServerLevel level, Dressing dr, int x, int y, int z, int h) {
		for (int k = 0; k < h; k++) set(level, x, y + k, z, dr.spire);
		BlockState cap = dr.cap.defaultBlockState();
		if (dr.cap == Blocks.POINTED_DRIPSTONE) {
			cap = cap.setValue(net.minecraft.world.level.block.PointedDripstoneBlock.TIP_DIRECTION, net.minecraft.core.Direction.UP);
		}
		set(level, x, y + h, z, cap);
		if (dr.cap == Blocks.AZALEA_LEAVES) {
			for (int dx = -2; dx <= 2; dx++)
				for (int dz = -2; dz <= 2; dz++)
					for (int dy = -1; dy <= 1; dy++)
						if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy) <= 3 && level.getBlockState(new BlockPos(x + dx, y + h + dy, z + dz)).isAir())
							set(level, x + dx, y + h + dy, z + dz, Blocks.AZALEA_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
		}
	}

	private static void blob(ServerLevel level, RandomSource r, Palette p, int x, int y, int z, int rad) {
		for (int dx = -rad; dx <= rad; dx++)
			for (int dz = -rad; dz <= rad; dz++)
				for (int dy = -rad; dy <= 0; dy++)
					if (dx * dx + dz * dz + dy * dy * 2 <= rad * rad + r.nextInt(2)) set(level, x + dx, y + dy, z + dz, dy == 0 ? p.floor : p.base);
	}

	private static boolean nearBridge(Rifts.Run run, int x, int z) {
		BlockPos h = run.hub();
		return Math.abs(x - h.getX()) <= 2 || Math.abs(z - h.getZ()) <= 2;
	}

	private static boolean nearAnyIsland(Rifts.Run run, int x, int z, int margin) {
		BlockPos h = run.hub();
		if (Mth.square(x - h.getX()) + Mth.square(z - h.getZ()) < Mth.square(HUB_R + margin)) return true;
		for (int i = 0; i < 4; i++) {
			BlockPos c = run.island(i);
			if (Mth.square(x - c.getX()) + Mth.square(z - c.getZ()) < Mth.square(ISLAND_R + margin)) return true;
		}
		return Math.abs(x - h.getX()) <= 3 + margin || Math.abs(z - h.getZ()) <= 3 + margin;
	}
}
