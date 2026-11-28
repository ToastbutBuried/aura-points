package com.toast.aurapoints.mixin;

import com.toast.aurapoints.client.CrosshairState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CLIENT ONLY. Notes that the vanilla crosshair ran this frame (5.67.1): the mod's crosshair marks only draw with it, so
 * whatever hides the crosshair (Zoomify while zooming, other mods) hides them too. A cancelled call never reaches TAIL.
 */
@Mixin(Gui.class)
public abstract class GuiCrosshairMixin {

	@Inject(method = "renderCrosshair", at = @At("TAIL"))
	private void aurapoints$crosshairDrawn(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		CrosshairState.mark();
	}
}
