package com.toast.aurapoints.fabric.mixin;

import com.toast.aurapoints.arena.Arena;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** Nothing wears out in the Aura Arena (vanilla's hurtAndBreak; the NeoForge build has its own copy). */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

	@Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
			at = @At("HEAD"), cancellable = true)
	private void aurapoints$noWearInArena(int amount, ServerLevel level, ServerPlayer player, Consumer<Item> onBreak, CallbackInfo ci) {
		if (level.dimension() == Arena.KEY) ci.cancel();
	}
}
