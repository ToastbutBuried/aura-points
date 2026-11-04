package com.toast.aurapoints.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Item displays' private setters, for the thrown Mjölnir (ability/Mjolnir). */
@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayAccessor {
	@Invoker("getItemStack")
	ItemStack aurapoints$getItemStack();
	@Invoker("setItemStack")
	void aurapoints$setItemStack(ItemStack stack);

	@Invoker("setItemTransform")
	void aurapoints$setItemTransform(ItemDisplayContext context);
}
