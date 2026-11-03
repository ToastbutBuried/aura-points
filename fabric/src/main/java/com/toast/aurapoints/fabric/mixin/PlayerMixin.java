package com.toast.aurapoints.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDamageEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDeathEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.player.AttackEntityEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Player's own copies of the NeoForge hooks: attacking, the chat name, damage pre/post and death. */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Unique
	private float aurapoints$healthDamage;
	@Unique
	private boolean aurapoints$damaged;

	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void aurapoints$attack(Entity target, CallbackInfo ci) {
		if (AuraBus.BUS.post(new AttackEntityEvent((Player) (Object) this, target)).isCanceled()) ci.cancel();
	}

	/** PlayerEvent.NameFormat: the name that goes into the team formatting (chat, death messages ...). */
	@ModifyArg(method = "getDisplayName", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/scores/PlayerTeam;formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;"),
			index = 1)
	private Component aurapoints$nameFormat(Component name) {
		Player self = (Player) (Object) this;
		return AuraBus.BUS.post(new PlayerEvent.NameFormat(self, self.getName(), name)).getDisplayname();
	}

	@ModifyExpressionValue(method = "actuallyHurt", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/player/Player;getDamageAfterMagicAbsorb(Lnet/minecraft/world/damagesource/DamageSource;F)F"))
	private float aurapoints$damagePre(float damage, @Local(argsOnly = true) DamageSource source) {
		aurapoints$damaged = true;
		return AuraBus.BUS.post(new LivingDamageEvent.Pre((Player) (Object) this, source, damage, damage)).getNewDamage();
	}

	@ModifyExpressionValue(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
	private float aurapoints$healthDamage(float healthDamage) {
		aurapoints$healthDamage = healthDamage;
		return healthDamage;
	}

	@Inject(method = "actuallyHurt", at = @At("TAIL"))
	private void aurapoints$damagePost(DamageSource source, float amount, CallbackInfo ci) {
		if (!aurapoints$damaged) return;
		aurapoints$damaged = false;
		AuraBus.BUS.post(new LivingDamageEvent.Post((Player) (Object) this, source, amount, aurapoints$healthDamage));
	}

	@Inject(method = "die", at = @At("HEAD"), cancellable = true)
	private void aurapoints$death(DamageSource source, CallbackInfo ci) {
		if (AuraBus.BUS.post(new LivingDeathEvent((Player) (Object) this, source)).isCanceled()) ci.cancel();
	}
}
