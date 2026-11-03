package com.toast.aurapoints.fabric.mixin;

import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** EntityJoinLevelEvent on the server, where NeoForge fires it: new entities, and ones loaded with their chunk. */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin<T extends EntityAccess> {

	@Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
	private void aurapoints$join(T access, boolean loadedFromDisk, CallbackInfoReturnable<Boolean> cir) {
		if (access instanceof Entity entity
				&& AuraBus.BUS.post(new EntityJoinLevelEvent(entity, entity.level(), loadedFromDisk)).isCanceled()) {
			cir.setReturnValue(false);
		}
	}
}
