package com.toast.aurapoints.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.toast.aurapoints.fabric.FabricHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The mob griefing checks that name their entity as an argument: mob explosions (the source), trampled crops and eggs. */
public final class GriefingSitesMixin {
	private GriefingSitesMixin() {
	}

	@Mixin(Level.class)
	public abstract static class Explosions {
		@WrapOperation(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;ZLnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/Holder;)Lnet/minecraft/world/level/Explosion;",
				at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
		private boolean aurapoints$mobGriefing(GameRules rules, GameRules.Key<GameRules.BooleanValue> key, Operation<Boolean> original,
				@Local(argsOnly = true) Entity source) {
			boolean vanilla = original.call(rules, key);
			return key == GameRules.RULE_MOBGRIEFING ? FabricHooks.canGriefEntity(source, vanilla) : vanilla;
		}
	}

	@Mixin(CropBlock.class)
	public abstract static class Crops {
		@WrapOperation(method = "entityInside", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
		private boolean aurapoints$mobGriefing(GameRules rules, GameRules.Key<GameRules.BooleanValue> key, Operation<Boolean> original,
				@Local(argsOnly = true) Entity entity) {
			boolean vanilla = original.call(rules, key);
			return key == GameRules.RULE_MOBGRIEFING ? FabricHooks.canGriefEntity(entity, vanilla) : vanilla;
		}
	}

	@Mixin(TurtleEggBlock.class)
	public abstract static class TurtleEggs {
		@WrapOperation(method = "canDestroyEgg", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
		private boolean aurapoints$mobGriefing(GameRules rules, GameRules.Key<GameRules.BooleanValue> key, Operation<Boolean> original,
				@Local(argsOnly = true) Entity entity) {
			boolean vanilla = original.call(rules, key);
			return key == GameRules.RULE_MOBGRIEFING ? FabricHooks.canGriefEntity(entity, vanilla) : vanilla;
		}
	}
}
