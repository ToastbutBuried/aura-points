package com.toast.aurapoints.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.toast.aurapoints.fabric.FabricHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge's EntityMobGriefingEvent at every place NeoForge asks it (and nowhere else): the mobGriefing rule in these
 * methods goes through FabricHooks.canGrief, which works out the entity like NeoForge does.
 */
@Mixin(value = {Mob.class, LivingEntity.class, SmallFireball.class, LargeFireball.class, Projectile.class, Ravager.class, WitherBoss.class,
		SnowGolem.class, Piglin.class, Allay.class, RemoveBlockGoal.class, EatBlockGoal.class, HarvestFarmland.class},
		targets = {"net.minecraft.world.entity.monster.EnderMan$EndermanLeaveBlockGoal", "net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal",
				"net.minecraft.world.entity.monster.Silverfish$SilverfishMergeWithStoneGoal", "net.minecraft.world.entity.monster.Silverfish$SilverfishWakeUpFriendsGoal",
				"net.minecraft.world.entity.monster.Evoker$EvokerWololoSpellGoal", "net.minecraft.world.entity.animal.Rabbit$RaidGardenGoal",
				"net.minecraft.world.entity.animal.Fox$FoxEatBerriesGoal"})
public abstract class MobGriefingMixin {

	@WrapOperation(method = {"aiStep", "createWitherRose", "onHitBlock", "onHit", "mayInteract", "customServerAiStep", "wantsToPickUp", "canUse", "tick",
			"onReachedTarget", "checkExtraStartConditions"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"),
			require = 0)
	private boolean aurapoints$mobGriefing(GameRules rules, GameRules.Key<GameRules.BooleanValue> key, Operation<Boolean> original) {
		boolean vanilla = original.call(rules, key);
		return key == GameRules.RULE_MOBGRIEFING ? FabricHooks.canGrief(this, vanilla) : vanilla;
	}
}
