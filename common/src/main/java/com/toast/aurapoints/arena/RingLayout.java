package com.toast.aurapoints.arena;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Locale;

/**
 * The duel ring's floor plans. ArenaBuilder.resetRing builds the current one (pads, centre plate and glass
 * ring stay the same in all); a duel or a group match rolls one at its start and keeps it for every round.
 * Every layout keeps the pads (±PAD_OFFSET), the match spawn circle (r ~17), the Wave start (r 4) and the
 * wave gates' mouths (r 25 on the diagonals) clear to stand on.
 * <ul>
 * <li>PILLARS - the two shifting pillar sets (RingPillars), low cover arcs round the middle and near the rim.</li>
 * <li>SHATTERED - three rings of lava trenches, each crossed by four bridges set off from the last ring's.</li>
 * <li>TERRACES - a raised dais in the middle (ramps from all four sides) and a stepped ring round it.</li>
 * </ul>
 */
public enum RingLayout {
	PILLARS("Pillars"), SHATTERED("Shattered"), TERRACES("Terraces");

	final String displayName;

	RingLayout(String displayName) {
		this.displayName = displayName;
	}

	/** One line for the vote buttons' hover. */
	String blurb() {
		return switch (this) {
			case PILLARS -> "Pillars rise and fall in the ring";
			case SHATTERED -> "A broken floor over lava";
			case TERRACES -> "A raised dais in the middle";
		};
	}

	/** What the ring is built as right now. */
	static RingLayout current = PILLARS;
	/** /aura arena layout: every match uses this one until set back to random (null). */
	static RingLayout forced;

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static RingLayout byId(String id) {
		for (RingLayout l : values()) if (l.id().equals(id)) return l;
		return null;
	}

	/** Wave Survival needs room to run without lava: never SHATTERED. */
	boolean fitsWaves() {
		return this != SHATTERED;
	}

	/** A layout for a new duel or match (the forced one if it fits), made current. */
	static RingLayout roll(RandomSource random, boolean waves) {
		return roll(random, waves, null);
	}

	/** Same, but {@code wanted} (a vote's winner) is used when no layout is forced and it fits. */
	static RingLayout roll(RandomSource random, boolean waves, RingLayout wanted) {
		RingLayout pick = forced;
		if (pick == null && wanted != null && (!waves || wanted.fitsWaves())) pick = wanted;
		if (pick == null || waves && !pick.fitsWaves()) {
			do pick = values()[random.nextInt(values().length)];
			while (waves && !pick.fitsWaves());
		}
		current = pick;
		return pick;
	}

	boolean hasPillars() {
		return this == PILLARS;
	}

	private static final double DAIS_R = 5.5;

	/** Height a player stands at on this column (the floor's top + 1). */
	int standY(double x, double dz) {
		if (this == TERRACES && x * x + dz * dz <= DAIS_R * DAIS_R) return Arena.RING_FLOOR + 3;
		return Arena.RING_FLOOR + 1;
	}

	/** The floor block (at RING_FLOOR) where the layout has its own; null = the ring's usual pattern. */
	BlockState floor(int x, int dz, double d) {
		if (this == SHATTERED && trench(x, dz, d)) return Blocks.LAVA.defaultBlockState();
		if (this == SHATTERED && bridge(x, dz, d)) return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
		return null;
	}

	/** The block {@code h} (1..) above the floor; null = air. Pillars are RingPillars' business. */
	BlockState above(int x, int dz, double d, int h) {
		switch (this) {
			case PILLARS -> {
				// cover arcs, one block high: inner ones open to the pads' line, outer ones on the diagonals
				if (h != 1) return null;
				int inQuarter = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(dz, x))), 90);
				if (d > 5.5 && d <= 6.5 && inQuarter >= 18 && inQuarter <= 72) return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
				// (with a gap on the diagonal itself, where the wave gates' mobs come through)
				if (d > 21.5 && d <= 22.5 && inQuarter >= 30 && inQuarter <= 60 && Math.abs(inQuarter - 45) >= 5) {
					return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
				}
				return null;
			}
			case TERRACES -> {
				if (d <= DAIS_R) return h == 1 ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
						: h == 2 ? (d <= 2.5 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE).defaultBlockState() : null;
				BlockState ramp = ramp(x, dz, h);
				if (ramp != null) return ramp;
				// the stepped ring: a half step, a block, a half step
				if (h == 1 && d > 10.5 && d <= 13.5) {
					if (d > 11.5 && d <= 12.5) return Blocks.POLISHED_DEEPSLATE.defaultBlockState();
					return Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
				}
				return null;
			}
			default -> {
				return null;
			}
		}
	}

	/** The dais' four ramps (3 wide, two steps up toward the middle). */
	private static BlockState ramp(int x, int dz, int h) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			int along = x * side.getStepX() + dz * side.getStepZ();
			int across = x * side.getStepZ() + dz * side.getStepX();
			if (Math.abs(across) > 1) continue;
			BlockState stair = Blocks.POLISHED_BLACKSTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, side.getOpposite());
			if (along == 6) return h == 1 ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState() : h == 2 ? stair : null;
			if (along == 7) return h == 1 ? stair : null;
		}
		return null;
	}

	// SHATTERED: lava rings at these radii; each crossed by four 3-wide bridges, turned 30 degrees per ring
	private static final double[] TRENCH = {9, 14, 21};

	private static int trenchAt(double d) {
		for (int i = 0; i < TRENCH.length; i++) if (d > TRENCH[i] - 0.5 && d <= TRENCH[i] + 0.5) return i;
		return -1;
	}

	private static boolean onBridge(int x, int dz, double d, int ring) {
		double a = Math.atan2(dz, x);
		for (int k = 0; k < 4; k++) {
			double b = Math.toRadians(15 + ring * 30 + k * 90);
			double diff = Math.IEEEremainder(a - b, Math.PI * 2);
			if (Math.abs(Math.sin(diff) * d) <= 1.6 && Math.cos(diff) > 0) return true;
		}
		return false;
	}

	private static boolean trench(int x, int dz, double d) {
		int ring = trenchAt(d);
		return ring >= 0 && !onBridge(x, dz, d, ring);
	}

	private static boolean bridge(int x, int dz, double d) {
		int ring = trenchAt(d);
		return ring >= 0 && onBridge(x, dz, d, ring);
	}
}
