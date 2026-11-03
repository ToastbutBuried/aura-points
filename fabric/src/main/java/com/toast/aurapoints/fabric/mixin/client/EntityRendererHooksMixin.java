package com.toast.aurapoints.fabric.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.client.event.RenderLivingEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RenderPlayerEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RenderLivingEvent / RenderPlayerEvent around drawing an entity (Pre canceled: not drawn). */
public final class EntityRendererHooksMixin {
	private EntityRendererHooksMixin() {
	}

	@Mixin(LivingEntityRenderer.class)
	public abstract static class Living {
		@Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
				at = @At("HEAD"), cancellable = true)
		@SuppressWarnings({"unchecked", "rawtypes"})
		private void aurapoints$pre(LivingEntity entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
			if (AuraBus.BUS.post(new RenderLivingEvent.Pre(entity, (LivingEntityRenderer) (Object) this, partial, pose, buffers, light)).isCanceled()) ci.cancel();
		}

		@Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
				at = @At("TAIL"))
		@SuppressWarnings({"unchecked", "rawtypes"})
		private void aurapoints$post(LivingEntity entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
			AuraBus.BUS.post(new RenderLivingEvent.Post(entity, (LivingEntityRenderer) (Object) this, partial, pose, buffers, light));
		}
	}

	@Mixin(PlayerRenderer.class)
	public abstract static class Player {
		@Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
				at = @At("HEAD"), cancellable = true)
		private void aurapoints$pre(AbstractClientPlayer player, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
			if (AuraBus.BUS.post(new RenderPlayerEvent.Pre(player, (PlayerRenderer) (Object) this, partial, pose, buffers, light)).isCanceled()) ci.cancel();
		}

		@Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
				at = @At("TAIL"))
		private void aurapoints$post(AbstractClientPlayer player, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
			AuraBus.BUS.post(new RenderPlayerEvent.Post(player, (PlayerRenderer) (Object) this, partial, pose, buffers, light));
		}
	}
}
