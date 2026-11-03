package com.toast.aurapoints.fabric.mixin.client;

import com.toast.aurapoints.fabric.client.GuiLayers;
import com.toast.aurapoints.shim.neoforge.client.gui.VanillaGuiLayers;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The mod's HUD layers, right above the vanilla parts NeoForge names (camera overlays, crosshair, hotbar), or above all. */
@Mixin(Gui.class)
public abstract class GuiMixin {

	@Inject(method = "renderCameraOverlays", at = @At("TAIL"))
	private void aurapoints$aboveCameraOverlays(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		GuiLayers.renderAbove(VanillaGuiLayers.CAMERA_OVERLAYS, g, delta);
	}

	@Inject(method = "renderCrosshair", at = @At("TAIL"))
	private void aurapoints$aboveCrosshair(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		GuiLayers.renderAbove(VanillaGuiLayers.CROSSHAIR, g, delta);
	}

	/** Right after the hotbar itself, before the health, food and XP bars (NeoForge's own layers). */
	@Inject(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;guiWidth()I", ordinal = 0))
	private void aurapoints$aboveHotbar(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		GuiLayers.renderAbove(VanillaGuiLayers.HOTBAR, g, delta);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void aurapoints$aboveAll(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		GuiLayers.renderTop(g, delta);
	}
}
