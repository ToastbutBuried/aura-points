package com.toast.aurapoints.mixin;

import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fire sight outlines are fire-colored instead of the usual white/team color (client side only). */
@Mixin(Entity.class)
public abstract class EntityMixin {

	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
	private void aurapoints$fireSightColor(CallbackInfoReturnable<Integer> cir) {
		Entity self = (Entity) (Object) this;
		if (self.level().isClientSide() && ClientAuraState.isHighlighted(self.getId())) cir.setReturnValue(ClientAuraState.aura == AuraType.RESONANCE ? AuraType.RESONANCE.rgb : AuraType.FIRE.rgb);
	}
}
