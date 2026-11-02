package com.toast.aurapoints.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** DEV ONLY (aurapoints.icetest): third person, no HUD, behind, in front and from the side by turns every 100 ticks. */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class DevIceTestClient {

	private DevIceTestClient() {
	}

	@SubscribeEvent
	public static void onTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (!com.toast.aurapoints.DevIceTest.on() || mc.player == null) return;
		mc.options.hideGui = true;
		int view = (mc.player.tickCount / 100) % 3;
		CameraType want = view == 0 ? CameraType.THIRD_PERSON_BACK : view == 1 ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON;
		net.minecraft.world.entity.Entity cam = mc.player;
		if (view == 2) for (var e : mc.level.entitiesForRendering()) if (e.getCustomName() != null && e.getCustomName().getString().equals("aurapoints_icecam")) cam = e;
		if (mc.options.getCameraType() != want || mc.getCameraEntity() != cam) {
			mc.options.setCameraType(want);
			mc.setCameraEntity(cam);
			com.toast.aurapoints.AuraPoints.LOGGER.info("[icetest] camera {}", view == 2 ? (cam == mc.player ? "SIDE_MISSING" : "SIDE") : want);
		}
	}
}
