package com.toast.aurapoints.fabric.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ViewportEvent.ComputeFogColor (before the sky is cleared to it) and RenderFog (canceled = use the event's distances). */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Shadow
	private static float fogRed;
	@Shadow
	private static float fogGreen;
	@Shadow
	private static float fogBlue;

	@Inject(method = "setupColor", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clearColor(FFFF)V", ordinal = 1))
	private static void aurapoints$fogColor(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darken, CallbackInfo ci) {
		var event = AuraBus.BUS.post(new ViewportEvent.ComputeFogColor(camera, partialTick, fogRed, fogGreen, fogBlue));
		fogRed = event.getRed();
		fogGreen = event.getGreen();
		fogBlue = event.getBlue();
	}

	@Inject(method = "setupFog", at = @At("TAIL"))
	private static void aurapoints$renderFog(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean thickFog, float partialTick, CallbackInfo ci) {
		var event = AuraBus.BUS.post(new ViewportEvent.RenderFog(camera, mode, camera.getFluidInCamera(), RenderSystem.getShaderFogEnd(),
				RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogShape()));
		if (event.isCanceled()) {
			RenderSystem.setShaderFogStart(event.getNearPlaneDistance());
			RenderSystem.setShaderFogEnd(event.getFarPlaneDistance());
			RenderSystem.setShaderFogShape(event.getFogShape());
		}
	}
}
