package com.toast.aurapoints.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.toast.aurapoints.AccessorySlots;
import net.minecraft.world.inventory.SlotRange;
import net.minecraft.world.inventory.SlotRanges;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** accessory.0 .. accessory.11 and accessory.* for /item and execute if items (added before the name lookup is built). */
@Mixin(SlotRanges.class)
public abstract class SlotRangesMixin {

	@SuppressWarnings("unchecked")
	@ModifyExpressionValue(method = "<clinit>", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/Util;make(Ljava/lang/Object;Ljava/util/function/Consumer;)Ljava/lang/Object;"))
	private static Object aurapoints$addAccessorySlots(Object slots) {
		AccessorySlots.addSlotNames((List<SlotRange>) slots);
		return slots;
	}
}
