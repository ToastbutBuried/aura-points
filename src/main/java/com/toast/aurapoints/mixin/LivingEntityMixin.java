package com.toast.aurapoints.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.toast.aurapoints.ability.SureFooted;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Sure-Footed: Frost players walk on ice as if it were stone (both sides, see SureFooted). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

	@ModifyExpressionValue(method = "travel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getFriction(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)F"))
	private float aurapoints$sureFooted(float friction) {
		return SureFooted.friction((LivingEntity) (Object) this, friction);
	}
}
