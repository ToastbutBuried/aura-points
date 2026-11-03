package com.toast.aurapoints.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.toast.aurapoints.client.AuraNametag;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CLIENT ONLY. Whenever a player's name tag is drawn, their aura line goes above it (client/AuraNametag). */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {

	@Inject(method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
			at = @At("HEAD"))
	private void aurapoints$auraLine(AbstractClientPlayer player, Component name, PoseStack pose, MultiBufferSource buffers, int light,
									 float partialTick, CallbackInfo ci) {
		AuraNametag.render(player, pose, buffers, light, partialTick);
	}
}
