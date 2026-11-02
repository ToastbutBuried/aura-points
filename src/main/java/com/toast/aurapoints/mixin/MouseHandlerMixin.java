package com.toast.aurapoints.mixin;

import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CLIENT ONLY. Frozen by an Ice Spike: the mouse doesn't turn the camera (the movement is thrown away, so nothing jumps after). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void aurapoints$frozen(double movementTime, CallbackInfo ci) {
		if (ClientAuraState.isFrozen()) ci.cancel();
	}
}
