package com.toast.aurapoints.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Elemental relics in loot chests (5.40.0, ability/Relics): after a container is filled from a loot table
 * whose random sequence is one of RELIC_CHEST_TABLES (vanilla chest tables carry their own id there),
 * a relic may be put into an empty slot. Works the same on both loaders.
 */
@Mixin(LootTable.class)
public abstract class LootTableMixin {

	@Shadow
	@Final
	private Optional<ResourceLocation> randomSequence;

	@Inject(method = "fill", at = @At("TAIL"))
	private void aurapoints$relic(Container container, LootParams params, long seed, CallbackInfo ci) {
		randomSequence.ifPresent(id -> com.toast.aurapoints.ability.Relics.onChestFilled(container, id, params.getLevel()));
	}
}
