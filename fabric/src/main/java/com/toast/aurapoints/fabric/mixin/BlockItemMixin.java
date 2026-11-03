package com.toast.aurapoints.fabric.mixin;

import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.event.level.BlockEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** BlockEvent.EntityPlaceEvent for a block a player placed (server side; the mod only listens, never cancels). */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

	@Inject(method = "place", at = @At("RETURN"))
	private void aurapoints$placed(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (context.getLevel().isClientSide() || !cir.getReturnValue().consumesAction() || context.getPlayer() == null) return;
		var pos = context.getClickedPos();
		AuraBus.BUS.post(new BlockEvent.EntityPlaceEvent(context.getLevel(), pos, context.getLevel().getBlockState(pos), context.getPlayer()));
	}
}
