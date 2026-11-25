package com.toast.aurapoints.geo;

import com.toast.aurapoints.AuraTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * The shadow eastern dragon's head: a real, saved entity a datapack drives with its Motion and Rotation tags (and
 * /ride, /tp, /data modify, /attribute). Nothing here is player-interactable: it can't be clicked, hit, pushed or picked.
 * <ul>
 * <li>A LivingEntity (no AI) so {@code /attribute @e[type=aurapoints:dragon_head,limit=1] minecraft:generic.scale base set 3}
 * works: model, hitbox and seat all scale with it.</li>
 * <li>Motion is vanilla Motion: the normal living-entity travel moves it every tick (no gravity, passes through blocks
 * and fluids, vanilla air drag 0.91/0.98), and clients interpolate it like any mob instead of snapping per tick.</li>
 * <li>Seat: the passenger sits at a fixed point above the skull computed from the position, the YAW and the scale only.
 * The renderer pitches the model around that very point (the root bone's pivot), so the skull surface under the rider
 * never moves and the head can't swallow them when it looks up.</li>
 * <li>Scoreboard: the head's own score in the objective {@value #COLLISION_OBJECTIVE} is 1 while its box, swept along
 * this tick's Motion (where that Motion takes it next tick), overlaps a block (or a solid entity) or a living entity
 * that isn't its rider, else 0: the datapack learns about a hit one tick before it happens. Ignored blocks: water and
 * anything else without a collision shape, #aurapoints:dragon_passable (leaves by default), and thin blocks (1 block
 * wide along any axis, see {@link #hitsBlock}). Skipped when the objective doesn't exist.</li>
 * <li>/kill and the void remove it; nothing else hurts it.</li>
 * </ul>
 */
public class DragonHead extends DatapackPuppet {

	public static final String COLLISION_OBJECTIVE = "pluxshadowserpentcollision";
	/** The seat in blocks at scale 1, relative to the entity's position: above the skull, a little behind its center. Keep in sync with dragon/gen.py SEAT. */
	public static final double SEAT_UP = 0.875, SEAT_BACK = 0.125;

	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.dragon_head.idle");

	public DragonHead(EntityType<? extends DragonHead> type, Level level) {
		super(type, level);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "idle", 0, state -> state.setAndContinue(IDLE)));
	}

	@Override
	public void tick() {
		// the Motion the datapack wrote for this tick, before vanilla drag shrinks it: the best guess for the next tick
		Vec3 motion = getDeltaMovement();
		super.tick();
		if (level().isClientSide) return;
		updateCollisionScore(motion);
	}

	private void updateCollisionScore(Vec3 motion) {
		Scoreboard board = level().getScoreboard();
		Objective objective = board.getObjective(COLLISION_OBJECTIVE);
		if (objective == null) return;
		int want = colliding(getBoundingBox().expandTowards(motion)) ? 1 : 0;
		var info = board.getPlayerScoreInfo(this, objective);
		if (info == null || info.value() != want) board.getOrCreatePlayerScore(this, objective).set(want);
	}

	private boolean colliding(AABB box) {
		if (hitsBlock(box) || !level().getEntityCollisions(this, box).isEmpty()) return true;
		return !level().getEntities(this, box, e -> e instanceof LivingEntity && !e.isSpectator() && e.isAlive()
				&& !e.isPassengerOfSameVehicle(this) && !hasIndirectPassenger(e)).isEmpty();
	}

	/**
	 * True if the box overlaps a block that counts as a wall: one with a collision shape (water and air have none), not
	 * in #aurapoints:dragon_passable (leaves by default) and not "thin", i.e. not 1 block wide along any axis (a 1-thick
	 * wall, floor, roof, fence, pane, pillar or tree trunk). A block is thick when, along each of X, Y and Z, at least one
	 * neighbour on that axis is a wall-candidate block too.
	 */
	private boolean hitsBlock(AABB box) {
		Level level = level();
		CollisionContext context = CollisionContext.of(this);
		VoxelShape boxShape = Shapes.create(box);
		BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
		for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
				Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
			if (!blocking(level, pos, context)) continue;
			VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos, context).move(pos.getX(), pos.getY(), pos.getZ());
			if (!Shapes.joinIsNotEmpty(shape, boxShape, BooleanOp.AND)) continue;
			if (!thin(level, pos, neighbour, context)) return true;
		}
		return false;
	}

	private static boolean thin(Level level, BlockPos pos, BlockPos.MutableBlockPos neighbour, CollisionContext context) {
		for (Direction.Axis axis : Direction.Axis.values()) {
			Direction plus = Direction.get(Direction.AxisDirection.POSITIVE, axis);
			if (!blocking(level, neighbour.setWithOffset(pos, plus), context)
					&& !blocking(level, neighbour.setWithOffset(pos, plus.getOpposite()), context)) return true;
		}
		return false;
	}

	/** Has a collision shape and isn't whitelisted. Unloaded chunks count as open air. */
	private static boolean blocking(Level level, BlockPos pos, CollisionContext context) {
		if (!level.isLoaded(pos)) return false;
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && !state.is(AuraTags.DRAGON_PASSABLE) && !state.getCollisionShape(level, pos, context).isEmpty();
	}

	// ------------------------------------------------------------ rider

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty();
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		// yaw only, never pitch: this is what keeps the seat anchored while the head looks up and down
		return new Vec3(0, SEAT_UP * scale, -SEAT_BACK * scale).yRot(-getYRot() * ((float) Math.PI / 180F));
	}
}
