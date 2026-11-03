package com.toast.aurapoints.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import com.toast.aurapoints.ability.SureFooted;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.common.damagesource.DamageContainer;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDamageEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDeathEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDropsEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingEntityUseItemEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingExperienceDropEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingFallEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingHealEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingIncomingDamageEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingKnockBackEvent;
import com.toast.aurapoints.fabric.DropCapture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * The NeoForge living-entity events, fired where NeoForge fires them: incoming damage (after the invulnerability,
 * death and fire-resistance checks), damage pre/post (after armor + Protection / once health has changed), death,
 * drops, experience, fall, heal, knockback, starting to use an item, visibility; plus Sure-Footed's ice friction.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin implements DropCapture {
	@Shadow
	protected Player lastHurtByPlayer;

	@Shadow
	public abstract ItemStack getItemInHand(InteractionHand hand);

	@Shadow
	public abstract boolean isUsingItem();

	@Unique
	private List<LivingIncomingDamageEvent.ReductionModifier> aurapoints$reductions = List.of();
	@Unique
	private List<ItemEntity> aurapoints$capture;
	@Unique
	private float aurapoints$healthDamage;
	@Unique
	private boolean aurapoints$damaged;

	@Override
	public List<ItemEntity> aurapoints$capture() {
		return aurapoints$capture;
	}

	// --- Sure-Footed: Frost players walk on ice as if it were stone ---

	@ModifyExpressionValue(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
	private float aurapoints$sureFooted(float friction) {
		return SureFooted.friction((LivingEntity) (Object) this, friction);
	}

	// --- incoming damage ---

	@Inject(method = "hurt", at = @At("HEAD"))
	private void aurapoints$hurtStart(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		aurapoints$reductions = List.of();
	}

	@Inject(method = "hurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isSleeping()Z", ordinal = 0), cancellable = true)
	private void aurapoints$incomingDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true) LocalFloatRef amountRef) {
		LivingIncomingDamageEvent event = AuraBus.BUS.post(new LivingIncomingDamageEvent((LivingEntity) (Object) this, source, amountRef.get()));
		if (event.isCanceled()) {
			cir.setReturnValue(false);
			return;
		}
		amountRef.set(event.getAmount());
		aurapoints$reductions = List.copyOf(event.reductionModifiers());
	}

	/** An ENCHANTMENTS reduction override (e.g. abilities that ignore Protection) works on the protection value. */
	@ModifyExpressionValue(method = "getDamageAfterMagicAbsorb", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;getDamageProtection(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;)F"))
	private float aurapoints$enchantReduction(float protection) {
		for (var m : aurapoints$reductions) {
			if (m.type() == DamageContainer.Reduction.ENCHANTMENTS) protection = m.function().modify(null, protection);
		}
		return protection;
	}

	// --- damage pre (after armor and Protection, before absorption) / post ---

	@ModifyExpressionValue(method = "actuallyHurt", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/LivingEntity;getDamageAfterMagicAbsorb(Lnet/minecraft/world/damagesource/DamageSource;F)F"))
	private float aurapoints$damagePre(float damage, @Local(argsOnly = true) DamageSource source) {
		aurapoints$damaged = true;
		return AuraBus.BUS.post(new LivingDamageEvent.Pre((LivingEntity) (Object) this, source, damage, damage)).getNewDamage();
	}

	@ModifyExpressionValue(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
	private float aurapoints$healthDamage(float healthDamage) {
		aurapoints$healthDamage = healthDamage;
		return healthDamage;
	}

	@Inject(method = "actuallyHurt", at = @At("TAIL"))
	private void aurapoints$damagePost(DamageSource source, float amount, CallbackInfo ci) {
		if (!aurapoints$damaged) return;
		aurapoints$damaged = false;
		AuraBus.BUS.post(new LivingDamageEvent.Post((LivingEntity) (Object) this, source, amount, aurapoints$healthDamage));
	}

	// --- death, drops, experience ---

	@Inject(method = "die", at = @At("HEAD"), cancellable = true)
	private void aurapoints$death(DamageSource source, CallbackInfo ci) {
		if (AuraBus.BUS.post(new LivingDeathEvent((LivingEntity) (Object) this, source)).isCanceled()) ci.cancel();
	}

	@Inject(method = "dropAllDeathLoot", at = @At("HEAD"))
	private void aurapoints$captureDrops(ServerLevel level, DamageSource source, CallbackInfo ci) {
		aurapoints$capture = new ArrayList<>();
	}

	@Inject(method = "dropAllDeathLoot", at = @At("TAIL"))
	private void aurapoints$drops(ServerLevel level, DamageSource source, CallbackInfo ci) {
		List<ItemEntity> drops = aurapoints$capture;
		aurapoints$capture = null;
		if (drops == null) return;
		LivingDropsEvent event = AuraBus.BUS.post(new LivingDropsEvent((LivingEntity) (Object) this, source, drops));
		if (!event.isCanceled()) for (ItemEntity e : drops) level.addFreshEntity(e);
	}

	@ModifyExpressionValue(method = "dropExperience", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/LivingEntity;getExperienceReward(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)I"))
	private int aurapoints$experience(int reward) {
		LivingEntity self = (LivingEntity) (Object) this;
		LivingExperienceDropEvent event = AuraBus.BUS.post(new LivingExperienceDropEvent(self, lastHurtByPlayer, reward));
		return event.isCanceled() ? 0 : event.getDroppedExperience();
	}

	// --- fall, heal, knockback ---

	@Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
	private void aurapoints$fall(float distance, float multiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir,
			@Local(argsOnly = true, ordinal = 0) LocalFloatRef distanceRef, @Local(argsOnly = true, ordinal = 1) LocalFloatRef multiplierRef) {
		LivingFallEvent event = AuraBus.BUS.post(new LivingFallEvent((LivingEntity) (Object) this, distance, multiplier));
		if (event.isCanceled()) {
			cir.setReturnValue(false);
			return;
		}
		distanceRef.set(event.getDistance());
		multiplierRef.set(event.getDamageMultiplier());
	}

	@Inject(method = "heal", at = @At("HEAD"), cancellable = true)
	private void aurapoints$heal(float amount, CallbackInfo ci, @Local(argsOnly = true) LocalFloatRef amountRef) {
		LivingHealEvent event = AuraBus.BUS.post(new LivingHealEvent((LivingEntity) (Object) this, amount));
		if (event.isCanceled() || event.getAmount() <= 0) {
			ci.cancel();
			return;
		}
		amountRef.set(event.getAmount());
	}

	@Inject(method = "knockback", at = @At("HEAD"), cancellable = true)
	private void aurapoints$knockback(double strength, double x, double z, CallbackInfo ci,
			@Local(argsOnly = true, ordinal = 0) com.llamalad7.mixinextras.sugar.ref.LocalDoubleRef strengthRef,
			@Local(argsOnly = true, ordinal = 1) com.llamalad7.mixinextras.sugar.ref.LocalDoubleRef xRef,
			@Local(argsOnly = true, ordinal = 2) com.llamalad7.mixinextras.sugar.ref.LocalDoubleRef zRef) {
		LivingKnockBackEvent event = AuraBus.BUS.post(new LivingKnockBackEvent((LivingEntity) (Object) this, (float) strength, x, z));
		if (event.isCanceled()) {
			ci.cancel();
			return;
		}
		strengthRef.set(event.getStrength());
		xRef.set(event.getRatioX());
		zRef.set(event.getRatioZ());
	}

	// --- using items, visibility ---

	@Inject(method = "startUsingItem", at = @At("HEAD"), cancellable = true)
	private void aurapoints$useStart(InteractionHand hand, CallbackInfo ci) {
		ItemStack stack = getItemInHand(hand);
		if (stack.isEmpty() || isUsingItem()) return;
		LivingEntity self = (LivingEntity) (Object) this;
		if (AuraBus.BUS.post(new LivingEntityUseItemEvent.Start(self, stack, stack.getUseDuration(self))).isCanceled()) ci.cancel();
	}

	@ModifyReturnValue(method = "getVisibilityPercent", at = @At("RETURN"))
	private double aurapoints$visibility(double visibility, Entity looking) {
		return visibility * AuraBus.BUS.post(new LivingEvent.LivingVisibilityEvent((LivingEntity) (Object) this, looking, 1.0)).getVisibilityModifier();
	}
}
