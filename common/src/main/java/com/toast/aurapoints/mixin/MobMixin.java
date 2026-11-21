package com.toast.aurapoints.mixin;

import com.toast.aurapoints.arena.Waves;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cloaked Shadow players are never a mob's target. Wave Survival mobs never turn on each other: a stray arrow or blast can still hurt another wave mob, but it can't make
 * it the new target (HurtByTargetGoal, zombies calling for help, ...), so they keep after the players.
 */
@Mixin(Mob.class)
public abstract class MobMixin {

	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void aurapoints$noWaveInfighting(LivingEntity target, CallbackInfo ci) {
		Mob self = (Mob) (Object) this;
		if (target != null && target != self && !self.level().isClientSide() && Waves.isWaveMob(self) && Waves.isWaveMob(target)) ci.cancel();
	}

	/** A cloaked Shadow player can't be taken as a target at all, not even by a mob they just hit (5.42.1). */
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void aurapoints$notCloaked(LivingEntity target, CallbackInfo ci) {
		if (target instanceof net.minecraft.server.level.ServerPlayer p && com.toast.aurapoints.ability.ShadowCloak.isCloaked(p)) ci.cancel();
	}
}
