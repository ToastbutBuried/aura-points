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

	/** The Mage's Last Prism: the aim turns slower while it channels (ManaPacket.turn). Two ModifyArg, not ModifyArgs (that one fails on NeoForge). */
	@org.spongepowered.asm.mixin.injection.ModifyArg(method = "turnPlayer",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), index = 0)
	private double aurapoints$slowYaw(double yaw) {
		return yaw * ClientAuraState.manaTurn;
	}

	@org.spongepowered.asm.mixin.injection.ModifyArg(method = "turnPlayer",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), index = 1)
	private double aurapoints$slowPitch(double pitch) {
		return pitch * ClientAuraState.manaTurn;
	}
}
