package com.toast.aurapoints.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** ViewportEvent.ComputeFov on the field of view GameRenderer works out. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@ModifyReturnValue(method = "getFov", at = @At("RETURN"))
	private double aurapoints$fov(double fov, Camera camera, float partialTick, boolean useConfiguredFov) {
		return AuraBus.BUS.post(new ViewportEvent.ComputeFov(camera, partialTick, fov, useConfiguredFov)).getFOV();
	}
}
