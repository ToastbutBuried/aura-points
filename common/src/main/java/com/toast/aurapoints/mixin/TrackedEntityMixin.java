package com.toast.aurapoints.mixin;

import com.toast.aurapoints.ability.DragonSegments;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * How far from a player the server still sends an entity: its type's tracking range (10 chunks = 160 blocks for item
 * displays). The shadow dragon's body is a long chain of item displays, so its segments get a longer range
 * (DragonSegments.trackingBlocks); everything else keeps the vanilla one. The server's view-distance still caps it.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {

	@Shadow
	@org.spongepowered.asm.mixin.Final
	Entity entity;

	@Inject(method = "getEffectiveRange", at = @At("RETURN"), cancellable = true)
	private void aurapoints$longerSegments(CallbackInfoReturnable<Integer> cir) {
		int want = DragonSegments.trackingBlocks(entity);
		if (want > cir.getReturnValueI()) cir.setReturnValue(want);
	}
}
