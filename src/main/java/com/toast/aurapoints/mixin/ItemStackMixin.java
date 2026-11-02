package com.toast.aurapoints.mixin;

import com.toast.aurapoints.arena.Arena;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Nothing wears out in the Aura Arena: every durability loss (tools, weapons, armor, shields, elytra) goes through
 * this hurtAndBreak overload, the other two call it. Applies to the whole arena dimension, every mode.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

	@Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
			at = @At("HEAD"), cancellable = true)
	private void aurapoints$noWearInArena(int amount, ServerLevel level, LivingEntity entity, Consumer<Item> onBreak, CallbackInfo ci) {
		if (level.dimension() == Arena.KEY) ci.cancel();
	}
}
