package com.toast.aurapoints.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.toast.aurapoints.fabric.DropCapture;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Death loot spawned through spawnAtLocation joins the drops list while dying; changing dimension can be stopped. */
@Mixin(Entity.class)
public abstract class EntityMixin {

	@WrapOperation(method = "spawnAtLocation(Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
	private boolean aurapoints$captureDrop(Level level, Entity entity, Operation<Boolean> original) {
		if (this instanceof DropCapture c && c.aurapoints$capture() != null && entity instanceof ItemEntity item) {
			c.aurapoints$capture().add(item);
			return true;
		}
		return original.call(level, entity);
	}

	@Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
	private void aurapoints$travel(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
		if (AuraBus.BUS.post(new EntityTravelToDimensionEvent((Entity) (Object) this, transition.newLevel().dimension())).isCanceled()) {
			cir.setReturnValue(null);
		}
	}
}
