package com.toast.aurapoints.geo;

import net.minecraft.core.NonNullList;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

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
 * that isn't its rider, else 0: the datapack learns about a hit one tick before it happens. Skipped when the objective
 * doesn't exist.</li>
 * <li>/kill and the void remove it; nothing else hurts it.</li>
 * </ul>
 */
public class DragonHead extends LivingEntity implements GeoEntity {

	public static final String COLLISION_OBJECTIVE = "pluxshadowserpentcollision";
	/** The seat in blocks at scale 1, relative to the entity's position: above the skull, a little behind its center. Keep in sync with dragon/gen.py SEAT. */
	public static final double SEAT_UP = 0.875, SEAT_BACK = 0.125;

	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.dragon_head.idle");

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
	private final NonNullList<ItemStack> noArmor = NonNullList.withSize(4, ItemStack.EMPTY);

	public DragonHead(EntityType<? extends DragonHead> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return LivingEntity.createLivingAttributes();
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "idle", 0, state -> state.setAndContinue(IDLE)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}

	@Override
	public void tick() {
		// the Motion the datapack wrote for this tick, before vanilla drag shrinks it: the best guess for the next tick
		Vec3 motion = getDeltaMovement();
		noPhysics = true;
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
		if (!level().noCollision(this, box)) return true;
		return !level().getEntities(this, box, e -> e instanceof LivingEntity && !e.isSpectator() && e.isAlive()
				&& !e.isPassengerOfSameVehicle(this) && !hasIndirectPassenger(e)).isEmpty();
	}

	// ------------------------------------------------------------ no interaction, no damage, no physics side effects

	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (!level().isClientSide && source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) discard(); // /kill, the void
		return false;
	}

	/** Never falls, whatever NoGravity a summon or /data writes. */
	@Override
	public boolean isNoGravity() {
		return true;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void pushEntities() {
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean isAffectedByPotions() {
		return false;
	}

	@Override
	protected boolean isAffectedByFluids() {
		return false;
	}

	@Override
	public boolean isPushedByFluid() {
		return false;
	}

	@Override
	public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
		return false;
	}

	@Override
	public boolean fireImmune() {
		return true;
	}

	@Override
	public boolean canBeSeenAsEnemy() {
		return false;
	}

	@Override
	public Iterable<ItemStack> getArmorSlots() {
		return noArmor;
	}

	@Override
	public ItemStack getItemBySlot(EquipmentSlot slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
	}

	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
	}

	// ------------------------------------------------------------ rider

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty();
	}

	/** Not steered by its rider: the datapack moves it. */
	@Override
	public LivingEntity getControllingPassenger() {
		return null;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		// yaw only, never pitch: this is what keeps the seat anchored while the head looks up and down
		return new Vec3(0, SEAT_UP * scale, -SEAT_BACK * scale).yRot(-getYRot() * ((float) Math.PI / 180F));
	}
}
