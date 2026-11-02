package com.toast.aurapoints.client;

import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * CLIENT ONLY. Fireborn's lava sight: with the camera in lava, the orange fog opens up to FIREBORN_LAVA_SIGHT
 * blocks (vanilla shows about 1, 5 with Fire Resistance), so a Fire user can find their way through it.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class LavaSight {

	private LavaSight() {
	}

	@SubscribeEvent
	public static void onFog(ViewportEvent.RenderFog event) {
		if (event.getType() != FogType.LAVA || !ClientAuraState.lavaSight()) return;
		var player = Minecraft.getInstance().player;
		if (player == null || player.isSpectator()) return;
		float end = Math.max(event.getFarPlaneDistance(), ClientAuraState.lavaSight);
		event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), end * 0.25f));
		event.setFarPlaneDistance(end);
		event.setCanceled(true); // NeoForge only applies the new distances when the event is cancelled
	}
}
