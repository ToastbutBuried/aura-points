package com.toast.aurapoints.fabric.mixin;

import com.toast.aurapoints.fabric.FabricHooks;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FinalizeSpawnEvent (canceled: the mob keeps its plain spawn), and whose AI is running (for the mob griefing check). */
@Mixin(Mob.class)
public abstract class MobMixin {
	@Unique
	private Mob aurapoints$aiBefore;

	@Inject(method = "finalizeSpawn", at = @At("HEAD"), cancellable = true)
	private void aurapoints$finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType type, SpawnGroupData data,
			CallbackInfoReturnable<SpawnGroupData> cir) {
		if (AuraBus.BUS.post(new FinalizeSpawnEvent((Mob) (Object) this, level, type)).isCanceled()) cir.setReturnValue(data);
	}

	@Inject(method = "serverAiStep", at = @At("HEAD"))
	private void aurapoints$aiStart(CallbackInfo ci) {
		aurapoints$aiBefore = FabricHooks.enterAi((Mob) (Object) this);
	}

	@Inject(method = "serverAiStep", at = @At("RETURN"))
	private void aurapoints$aiEnd(CallbackInfo ci) {
		FabricHooks.leaveAi(aurapoints$aiBefore);
		aurapoints$aiBefore = null;
	}
}
