package com.toast.aurapoints.mixin;

import com.toast.aurapoints.AccessorySlots;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The accessory slots go after the vanilla ones (index 46+), so no vanilla slot index moves. */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin {

	@Inject(method = "<init>", at = @At("TAIL"))
	private void aurapoints$addAccessorySlots(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
		AbstractContainerMenuInvoker menu = (AbstractContainerMenuInvoker) (AbstractContainerMenu) (Object) this;
		AccessorySlots.addSlots(owner, menu::aurapoints$addSlot);
	}
}
