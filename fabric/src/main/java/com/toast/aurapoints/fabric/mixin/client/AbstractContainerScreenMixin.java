package com.toast.aurapoints.fabric.mixin.client;

import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.client.event.ContainerScreenEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ContainerScreenEvent.Render.Background: after the background, before slots and widgets. */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

	@Inject(method = "renderBackground", at = @At("TAIL"))
	private void aurapoints$background(GuiGraphics g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
		AuraBus.BUS.post(new ContainerScreenEvent.Render.Background((AbstractContainerScreen<?>) (Object) this, g, mouseX, mouseY));
	}
}
