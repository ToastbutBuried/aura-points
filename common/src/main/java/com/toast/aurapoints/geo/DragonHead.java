package com.toast.aurapoints.geo;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * The shadow eastern dragon's head: a real, saved entity a datapack drives with its Motion and Rotation tags (and
 * /ride, /tp, /data modify). Nothing here is player-interactable: it can't be clicked, hit, pushed or picked.
 * <ul>
 * <li>Motion is applied every server tick as plain movement (no gravity, drag or collision), so a datapack can write it.</li>
 * <li>Seat: the passenger sits at a fixed point above the skull computed from the position and the YAW only. The
 * renderer pitches the model around that very point (the root bone's pivot), so the skull surface under the rider
 * never moves and the head can't swallow them when it looks up.</li>
 * <li>Scoreboard: the head's own score in the objective {@value #COLLISION_OBJECTIVE} is 1 while its box overlaps a
 * block (or a solid entity) or a living entity that isn't its rider, else 0. Skipped when the objective doesn't exist.</li>
 * </ul>
 */
public class DragonHead extends AuraGeoEntity {

	public static final String COLLISION_OBJECTIVE = "pluxshadowserpentcollision";
	/** The seat in blocks, relative to the entity's position: above the skull, a little behind its center. Keep in sync with dragon/gen.py SEAT. */
	public static final double SEAT_UP = 0.875, SEAT_BACK = 0.125;

	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.dragon_head.idle");

	public DragonHead(EntityType<? extends DragonHead> type, Level level) {
		super(type, level);
	}

	@Override
	protected boolean watchdog() {
		return false;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "idle", 0, state -> state.setAndContinue(IDLE)));
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide) return;
		Vec3 motion = getDeltaMovement();
		if (motion.lengthSqr() > 1.0E-12) setPos(getX() + motion.x, getY() + motion.y, getZ() + motion.z);
		updateCollisionScore();
	}

	private void updateCollisionScore() {
		Scoreboard board = level().getScoreboard();
		Objective objective = board.getObjective(COLLISION_OBJECTIVE);
		if (objective == null) return;
		int want = colliding() ? 1 : 0;
		var info = board.getPlayerScoreInfo(this, objective);
		if (info == null || info.value() != want) board.getOrCreatePlayerScore(this, objective).set(want);
	}

	private boolean colliding() {
		AABB box = getBoundingBox();
		if (!level().noCollision(this, box)) return true;
		return !level().getEntities(this, box, e -> e instanceof LivingEntity && !e.isSpectator() && e.isAlive()
				&& !e.isPassengerOfSameVehicle(this) && !hasIndirectPassenger(e)).isEmpty();
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty();
	}

	/** Not steered by its rider: the datapack moves it. */
	@Override
	public net.minecraft.world.entity.LivingEntity getControllingPassenger() {
		return null;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		// yaw only, never pitch: this is what keeps the seat anchored while the head looks up and down
		return new Vec3(0, SEAT_UP, -SEAT_BACK).yRot(-getYRot() * ((float) Math.PI / 180F));
	}
}
