package com.toast.aurapoints.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CLIENT ONLY. The sky turns red under a Blood Moon (client/BloodMoonSky) and to the aura's color in the awakening ritual (client/RitualCutscene). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

	@Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
	private void aurapoints$bloodMoonSky(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
		float s = com.toast.aurapoints.client.BloodMoonSky.strength();
		if (s > 0) cir.setReturnValue(com.toast.aurapoints.client.BloodMoonSky.tint(cir.getReturnValue(), s));
		float r = com.toast.aurapoints.client.RitualCutscene.tintStrength();
		if (r > 0) cir.setReturnValue(com.toast.aurapoints.client.RitualCutscene.tintSky(cir.getReturnValue(), r));
	}
}
