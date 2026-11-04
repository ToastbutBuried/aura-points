package com.toast.aurapoints.geo;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A GeckoLib-drawn ability model (the viper head, the shadow clone, ...): one entity instead of a heap of block
 * displays. The ability that owns it moves it every tick on the server and picks its state; every client plays the
 * animations itself, so they stay smooth whatever the ping.
 * <ul>
 * <li>Never saved (the type is noSave), no physics, no gravity, can't be pushed, hit or picked - hitboxes stay the
 * ability's own (an Interaction, like before).</li>
 * <li>{@link #state}: a small number the subclass maps to its animations (0 = its idle).</li>
 * <li>{@link #scale}: drawn this much bigger (the renderer reads it).</li>
 * <li>Watchdog: the owner calls {@link #keepAlive} every tick; one that isn't kept alive for KEEPALIVE_TICKS
 * (its ability crashed or forgot it) removes itself.</li>
 * </ul>
 */
public abstract class AuraGeoEntity extends Entity implements GeoEntity {

	private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(AuraGeoEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(AuraGeoEntity.class, EntityDataSerializers.FLOAT);
	private static final int KEEPALIVE_TICKS = 40;

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
	private int unattended;

	protected AuraGeoEntity(EntityType<? extends AuraGeoEntity> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(STATE, 0);
		builder.define(SCALE, 1f);
	}

	public int state() {
		return entityData.get(STATE);
	}

	public void setState(int state) {
		entityData.set(STATE, state);
	}

	public float scale() {
		return entityData.get(SCALE);
	}

	public void setScale(float scale) {
		entityData.set(SCALE, scale);
	}

	/** CLIENT: the state the "action" controller last saw. */
	private int lastAction = -1;

	/**
	 * For the "action" controller's predicate: the state, and the controller is reset whenever it changed - GeckoLib
	 * won't replay a finished one-shot (a shot, a burst, a hit) when the predicate hands it the same animation again.
	 */
	public int actionState(software.bernie.geckolib.animation.AnimationState<?> anim) {
		int s = state();
		if (s != lastAction) {
			lastAction = s;
			anim.getController().forceAnimationReset();
		}
		return s;
	}

	/** False for an entity that lives on its own (the dragon head): no keepalive needed. */
	protected boolean watchdog() {
		return true;
	}

	/** The owning ability still runs this one. */
	public void keepAlive() {
		unattended = 0;
	}

	@Override
	public void tick() {
		super.tick();
		if (!level().isClientSide && watchdog() && ++unattended > KEEPALIVE_TICKS) discard();
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
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
