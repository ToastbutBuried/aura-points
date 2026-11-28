package com.toast.aurapoints.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** CLIENT ONLY. The field of view this frame (zoom mods and the spyglass lower it): CrosshairState. */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {

	@Invoker("getFov")
	double aurapoints$getFov(Camera camera, float partialTick, boolean useFovSetting);
}
