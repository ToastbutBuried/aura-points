package com.toast.aurapoints.fabric.mixin.client;

import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.client.event.MovementInputUpdateEvent;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MovementInputUpdateEvent: right after the keys were read into the movement input. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Shadow
	public Input input;

	@Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER))
	private void aurapoints$movementInput(CallbackInfo ci) {
		AuraBus.BUS.post(new MovementInputUpdateEvent((LocalPlayer) (Object) this, input));
	}
}
