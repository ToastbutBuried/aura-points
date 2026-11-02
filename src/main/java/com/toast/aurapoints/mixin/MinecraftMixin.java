package com.toast.aurapoints.mixin;

import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CLIENT ONLY. Fire Bending's fire sight: entities the server picked are drawn with an outline. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
	private void aurapoints$fireSight(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (ClientAuraState.isHighlighted(entity.getId())) cir.setReturnValue(true);
	}
}
