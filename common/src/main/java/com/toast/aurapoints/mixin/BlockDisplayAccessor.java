package com.toast.aurapoints.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Block displays' private block setter, for the Vine Hook's roots. */
@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayAccessor {

	@Invoker("setBlockState")
	void aurapoints$setBlockState(BlockState state);

	@Invoker("getBlockState")
	BlockState aurapoints$getBlockState();
}
