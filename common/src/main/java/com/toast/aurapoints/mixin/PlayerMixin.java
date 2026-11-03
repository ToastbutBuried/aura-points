package com.toast.aurapoints.mixin;

import com.toast.aurapoints.AccessorySlots;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The accessory slot ids (see SlotRangesMixin) point at the player's accessory items. */
@Mixin(Player.class)
public abstract class PlayerMixin {

	@Inject(method = "getSlot", at = @At("HEAD"), cancellable = true)
	private void aurapoints$accessorySlot(int id, CallbackInfoReturnable<SlotAccess> cir) {
		SlotAccess access = AccessorySlots.slotAccess((Player) (Object) this, id);
		if (access != null) cir.setReturnValue(access);
	}
}
