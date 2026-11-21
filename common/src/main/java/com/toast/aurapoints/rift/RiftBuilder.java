package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a rift instance (5.41.0): ROOMS round floating rooms and a bigger boss room in a line along +z, joined
 * by 3-wide bridges, in the rift's aura palette. Each room has a low rim, four lit pillars and a glass gate
 * across its exit bridge that opens when the room is clear. Same footprint for every aura, so a slot is simply
 * built over again for the next run.
 */
final class RiftBuilder {

	private RiftBuilder() {
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

	private static void set(ServerLevel level, int x, int y, int z, Block b) {
		set(level, x, y, z, b.defaultBlockState());
	}

	private static void set(ServerLevel level, int x, int y, int z, BlockState s) {
		level.setBlock(new BlockPos(x, y, z), s, 2);
	}

	static void build(ServerLevel level, Rifts.Run run) {
		Palette p = palette(run.aura);
		RandomSource r = RandomSource.create(run.id * 31L);
		int y = Rifts.FLOOR;
		for (int room = 0; room <= Rifts.ROOMS; room++) {
			Vec3 c = run.center(room);
			int cx = (int) Math.floor(c.x), cz = (int) Math.floor(c.z), rad = run.radius(room);
			// the floor disk, with an underside that tapers off
			for (int dx = -rad - 1; dx <= rad + 1; dx++) {
				for (int dz = -rad - 1; dz <= rad + 1; dz++) {
					double d = Math.sqrt(dx * dx + dz * dz);
					for (int up = 1; up <= 5; up++) set(level, cx + dx, y + up, cz + dz, Blocks.AIR);
					if (d > rad + 0.5) continue;
					set(level, cx + dx, y, cz + dz, r.nextInt(6) == 0 ? p.accent : (d < 2.5 ? p.accent : p.floor));
					int depth = (int) Math.round((1 - d / (rad + 0.5)) * 5);
					for (int k = 1; k <= depth; k++) set(level, cx + dx, y - k, cz + dz, p.base);
					// the rim: a low wall with gaps for the bridges
					if (d > rad - 0.5 && Math.abs(dx) > 1) set(level, cx + dx, y + 1, cz + dz, p.edge);
				}
			}
			// four lit pillars
			int pr = rad - 2;
			for (int[] o : new int[][]{{pr, pr}, {-pr, pr}, {pr, -pr}, {-pr, -pr}}) {
				int px = cx + (int) Math.round(o[0] * 0.7), pz = cz + (int) Math.round(o[1] * 0.7);
				for (int k = 1; k <= 3; k++) set(level, px, y + k, pz, p.edge);
				set(level, px, y + 4, pz, p.light);
			}
			set(level, cx, y - 1, cz, p.light);
			// the bridge to the next room, and its gate
			if (room < Rifts.ROOMS) {
				Vec3 n = run.center(room + 1);
				int from = cz + rad, to = (int) Math.floor(n.z) - run.radius(room + 1);
				for (int z = from; z <= to; z++) {
					for (int dx = -1; dx <= 1; dx++) {
						set(level, cx + dx, y, z, dx == 0 ? p.accent : p.floor);
						set(level, cx + dx, y - 1, z, p.base);
						for (int up = 1; up <= 4; up++) set(level, cx + dx, y + up, z, Blocks.AIR);
					}
				}
				closeGate(level, run, room);
			}
		}
	}

	private static int gateZ(Rifts.Run run, int room) {
		return (int) Math.floor(run.center(room).z) + run.radius(room) + 1;
	}

	static void closeGate(ServerLevel level, Rifts.Run run, int room) {
		Palette p = palette(run.aura);
		int cx = (int) Math.floor(run.center(room).x), z = gateZ(run, room);
		for (int dx = -1; dx <= 1; dx++) for (int up = 1; up <= 3; up++) set(level, cx + dx, Rifts.FLOOR + up, z, p.gate);
	}

	static void openGate(ServerLevel level, Rifts.Run run, int room) {
		int cx = (int) Math.floor(run.center(room).x), z = gateZ(run, room);
		for (int dx = -1; dx <= 1; dx++) {
			for (int up = 1; up <= 3; up++) {
				set(level, cx + dx, Rifts.FLOOR + up, z, Blocks.AIR);
				level.sendParticles(ParticleTypes.END_ROD, cx + dx + 0.5, Rifts.FLOOR + up + 0.5, z + 0.5, 3, 0.2, 0.2, 0.2, 0.02);
			}
		}
	}
}
