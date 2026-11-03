package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraFx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static com.toast.aurapoints.arena.Arena.RING_FLOOR;
import static com.toast.aurapoints.arena.Arena.RING_Z;

/**
 * The duel ring's moving cover: two sets of four pillars - on the diagonals and across the middle -
 * and only one set stands at a time. Mid-fight, every SHIFT_TICKS the standing set sinks into the floor
 * and the other one grinds up (warned SHIFT_WARNING ticks ahead by dust over its spots). Anyone standing
 * where a pillar comes up rides it up instead of being shut inside.
 */
final class RingPillars {

	private RingPillars() {
	}

	static final int HEIGHT = 6;
	private static final int SHIFT_TICKS = 20 * 20, SHIFT_WARNING = 40, STEP_TICKS = 3;

	/** Pillar columns of each set, as {x, offset from the ring's centre}. */
	private static final List<int[]> DIAGONAL = new ArrayList<>(), CROSS = new ArrayList<>();

	static {
		for (int sx : new int[]{-1, 1}) {
			for (int sz : new int[]{-1, 1}) {
				for (int a : new int[]{10, 11}) for (int b : new int[]{10, 11}) DIAGONAL.add(new int[]{sx * a, sz * b});
			}
		}
		for (int s : new int[]{-1, 1}) {
			for (int a : new int[]{12, 13}) {
				for (int b : new int[]{0, 1}) {
					CROSS.add(new int[]{s * a, b});   // east and west
					CROSS.add(new int[]{b, s * a});   // north and south
				}
			}
		}
	}

	/** 0 = the diagonal set stands, 1 = the cross. Heights of each set, and where they're going. */
	private static int standing = 0;
	private static final int[] height = {HEIGHT, 0}, target = {HEIGHT, 0};
	private static long nextShift = Long.MAX_VALUE;

	static boolean isPillar(int x, int dz) {
		return contains(DIAGONAL, x, dz) || contains(CROSS, x, dz);
	}

	/** For the ring as built (and reset each round): the diagonal set up, the cross down. */
	static boolean standsAtStart(int x, int dz) {
		return contains(DIAGONAL, x, dz);
	}

	private static boolean contains(List<int[]> set, int x, int dz) {
		for (int[] c : set) if (c[0] == x && c[1] == dz) return true;
		return false;
	}

	static BlockState block(int x, int dz, int level) {
		boolean diagonal = contains(DIAGONAL, x, dz);
		if (level == HEIGHT) return (diagonal ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.CHISELED_DEEPSLATE).defaultBlockState();
		return (diagonal ? Blocks.POLISHED_DEEPSLATE : Blocks.DEEPSLATE_TILES).defaultBlockState();
	}

	/** A new round: back as built (ArenaBuilder.resetRing places the blocks), next shift a while away. */
	static void reset(long now) {
		standing = 0;
		height[0] = target[0] = HEIGHT;
		height[1] = target[1] = 0;
		nextShift = now + SHIFT_TICKS;
	}

	/** Between rounds and after a duel: no shifting. */
	static void stop() {
		nextShift = Long.MAX_VALUE;
	}

	/** Mid-round, every tick. */
	static void tick(ServerLevel level, long now) {
		if (!RingLayout.current.hasPillars()) return;
		int rising = 1 - standing;
		// the warning: dust curling up where the next set will come out
		if (nextShift != Long.MAX_VALUE && now >= nextShift - SHIFT_WARNING && now < nextShift && now % 4 == 0) {
			for (int[] c : rising == 0 ? DIAGONAL : CROSS) {
				AuraFx.particles(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, c[0] + 0.5, RING_FLOOR + 1.1, RING_Z + c[1] + 0.5, 1, 0.2, 0, 0.2, 0.01);
				AuraFx.particles(level, AuraFx.dust(0xFF4A12, 1.2f), c[0] + 0.5, RING_FLOOR + 1.1, RING_Z + c[1] + 0.5, 2, 0.3, 0.1, 0.3, 0);
			}
		}
		if (now == nextShift) {
			target[standing] = 0;
			target[rising] = HEIGHT;
			standing = rising;
			nextShift = now + SHIFT_TICKS;
			ArenaFx.sound(level, new Vec3(0.5, RING_FLOOR + 2, RING_Z + 0.5), SoundEvents.PISTON_EXTEND, 1.5f, 0.5f);
		}
		if (now % STEP_TICKS != 0) return;
		for (int set = 0; set < 2; set++) {
			if (height[set] == target[set]) continue;
			List<int[]> columns = set == 0 ? DIAGONAL : CROSS;
			if (height[set] > target[set]) {
				// sinking: the top layer goes
				for (int[] c : columns) level.setBlock(new BlockPos(c[0], RING_FLOOR + height[set], RING_Z + c[1]), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				height[set]--;
				// the old top is now the cap again
				if (height[set] > 0) for (int[] c : columns) level.setBlock(new BlockPos(c[0], RING_FLOOR + height[set], RING_Z + c[1]), block(c[0], c[1], HEIGHT), Block.UPDATE_CLIENTS);
			} else {
				height[set]++;
				int y = RING_FLOOR + height[set];
				for (int[] c : columns) {
					BlockPos pos = new BlockPos(c[0], y, RING_Z + c[1]);
					// whoever stands there rides up on it
					for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos))) {
						if (e.getY() < y + 1) e.teleportTo(e.getX(), y + 1, e.getZ());
					}
					level.setBlock(pos, block(c[0], c[1], HEIGHT), Block.UPDATE_CLIENTS);
					if (height[set] > 1) level.setBlock(pos.below(), block(c[0], c[1], 0), Block.UPDATE_CLIENTS);
				}
			}
			for (int[] c : columns) {
				AuraFx.particles(level, ParticleTypes.LARGE_SMOKE, c[0] + 0.5, RING_FLOOR + 1.2, RING_Z + c[1] + 0.5, 1, 0.4, 0.1, 0.4, 0.01);
			}
			if (height[set] % 2 == 0) {
				int[] c = columns.get(0);
				ArenaFx.sound(level, new Vec3(c[0], RING_FLOOR + 1, RING_Z + c[1]), SoundEvents.GRINDSTONE_USE, 0.8f, 0.6f);
			}
		}
	}
}
