package com.toast.aurapoints.client;

import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;

/**
 * CLIENT ONLY. Echolocation's dim sight, a lighter Blindness: a violet fog closing in from ECHO_FOG_START to
 * ECHO_FOG_END blocks (sky included), but no sprint ban and no black-out. The outlines of what you hear show
 * through it (they are drawn after the fog).
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class EchoFog {

	private EchoFog() {
	}

	private static final float R = 0.045f, G = 0.012f, B = 0.07f;

	private static boolean active() {
		var player = Minecraft.getInstance().player;
		return player != null && !player.isSpectator() && ClientAuraState.echoFog();
	}

	@SubscribeEvent
	public static void onFog(ViewportEvent.RenderFog event) {
		if (!active()) return;
		float end = Math.min(event.getFarPlaneDistance(), ClientAuraState.echoFogEnd);
		float start = Math.min(Math.min(event.getNearPlaneDistance(), ClientAuraState.echoFogStart), end * 0.9f);
		event.setNearPlaneDistance(start);
		event.setFarPlaneDistance(end);
		event.setCanceled(true); // NeoForge only applies the new distances when the event is cancelled
	}

	/** Charging Abyssal Grasp: the view narrows with the charge. */
	@SubscribeEvent
	public static void onFov(ViewportEvent.ComputeFov event) {
		if (ClientAuraState.aura != com.toast.aurapoints.ability.AuraType.RESONANCE || !ClientAuraState.fireBending) return;
		float c = Math.max(0f, Math.min(1f, ClientAuraState.fireCharge));
		event.setFOV(event.getFOV() * (1.0 - (1.0 - ClientAuraState.graspZoom) * c));
	}

	@SubscribeEvent
	public static void onFogColor(ViewportEvent.ComputeFogColor event) {
		if (!active()) return;
		event.setRed(R);
		event.setGreen(G);
		event.setBlue(B);
	}
}
