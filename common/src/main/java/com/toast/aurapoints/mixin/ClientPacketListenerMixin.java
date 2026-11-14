package com.toast.aurapoints.mixin;

import com.toast.aurapoints.config.AuraClientConfig;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CLIENT ONLY. The "Effect level" setting drops a share of the particle packets the server sends (ability effects). */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

	@Inject(method = "handleParticleEvent", at = @At("HEAD"), cancellable = true)
	private void aurapoints$effectLevel(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		float keep = AuraClientConfig.effectKeep();
		// the packet is first seen on the network thread and then re-run on the game thread: decide only once
		if (keep < 1f && net.minecraft.client.Minecraft.getInstance().isSameThread() && Math.random() >= keep) ci.cancel();
	}
}
