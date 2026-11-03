package com.toast.aurapoints.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.toast.aurapoints.client.EffectModels;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CLIENT ONLY. Ability effect blocks are drawn with the mod's own models (client/EffectModels). */
@Mixin(DisplayRenderer.BlockDisplayRenderer.class)
public abstract class BlockDisplayRendererMixin {

	@Inject(method = "renderInner(Lnet/minecraft/world/entity/Display$BlockDisplay;Lnet/minecraft/world/entity/Display$BlockDisplay$BlockRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
			at = @At("HEAD"), cancellable = true)
	private void aurapoints$effectModels(Display.BlockDisplay entity, Display.BlockDisplay.BlockRenderState state, PoseStack pose,
			MultiBufferSource buffers, int light, float partialTick, CallbackInfo ci) {
		if (EffectModels.renderBlock(state.blockState(), pose, buffers, light)) ci.cancel();
	}
}
