package com.toast.aurapoints.geo;

import net.minecraft.core.NonNullList;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A model a datapack drives (Motion, Rotation, /tp, /data, /attribute ... generic.scale): a saved LivingEntity without AI.
 * No gravity, flies through blocks and fluids, can't be clicked, hit, pushed or picked, wears nothing. Only /kill and the
 * void remove it.
 */
public abstract class DatapackPuppet extends LivingEntity implements GeoEntity {

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
	private final NonNullList<ItemStack> noArmor = NonNullList.withSize(4, ItemStack.EMPTY);

	protected DatapackPuppet(EntityType<? extends DatapackPuppet> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return LivingEntity.createLivingAttributes();
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}

	@Override
	public void tick() {
		noPhysics = true;
		super.tick();
	}

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

	/** Never steered by a rider: the datapack moves it. */
	@Override
	public LivingEntity getControllingPassenger() {
		return null;
	}
}
