package com.toast.aurapoints.fabric.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.client.event.InputEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RenderFrameEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Frame start/end, the attack/use/pick key clicks (InteractionKeyMappingTriggered) and a left click at nothing. */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
	@Shadow
	public LocalPlayer player;
	@Shadow
	public HitResult hitResult;
	@Shadow
	@Final
	public Options options;
	@Shadow
	@Final
	public ParticleEngine particleEngine;
	@Shadow
	@Final
	private DeltaTracker.Timer timer;

	private static InputEvent.InteractionKeyMappingTriggered aurapoints$click(int button, KeyMapping key, InteractionHand hand) {
		return AuraBus.BUS.post(new InputEvent.InteractionKeyMappingTriggered(button, key, hand, true));
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
	private void aurapoints$framePre(boolean tick, CallbackInfo ci) {
		AuraBus.BUS.post(new RenderFrameEvent.Pre(timer));
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER))
	private void aurapoints$framePost(boolean tick, CallbackInfo ci) {
		AuraBus.BUS.post(new RenderFrameEvent.Post(timer));
	}

	@Inject(method = "startAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/HitResult;getType()Lnet/minecraft/world/phys/HitResult$Type;", ordinal = 0), cancellable = true)
	private void aurapoints$attackClick(CallbackInfoReturnable<Boolean> cir) {
		var event = aurapoints$click(0, options.keyAttack, InteractionHand.MAIN_HAND);
		if (event.isCanceled()) {
			if (event.shouldSwingHand()) player.swing(InteractionHand.MAIN_HAND);
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "startAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;resetAttackStrengthTicker()V", shift = At.Shift.AFTER))
	private void aurapoints$leftClickEmpty(CallbackInfoReturnable<Boolean> cir) {
		AuraBus.BUS.post(new PlayerInteractEvent.LeftClickEmpty(player));
	}

	@Inject(method = "continueAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/BlockHitResult;getDirection()Lnet/minecraft/core/Direction;", ordinal = 0), cancellable = true)
	private void aurapoints$holdAttack(boolean down, CallbackInfo ci) {
		var event = aurapoints$click(0, options.keyAttack, InteractionHand.MAIN_HAND);
		if (event.isCanceled()) {
			if (event.shouldSwingHand() && hitResult instanceof BlockHitResult block) {
				particleEngine.crack(block.getBlockPos(), block.getDirection());
				player.swing(InteractionHand.MAIN_HAND);
			}
			ci.cancel();
		}
	}

	@Inject(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;", ordinal = 0), cancellable = true)
	private void aurapoints$useClick(CallbackInfo ci, @Local InteractionHand hand) {
		var event = aurapoints$click(1, options.keyUse, hand);
		if (event.isCanceled()) {
			if (event.shouldSwingHand()) player.swing(hand);
			ci.cancel();
		}
	}

	@Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
	private void aurapoints$pickClick(CallbackInfo ci) {
		if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) return;
		if (aurapoints$click(2, options.keyPickItem, InteractionHand.MAIN_HAND).isCanceled()) ci.cancel();
	}
}
