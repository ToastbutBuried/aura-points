package com.toast.aurapoints.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterGuiLayersEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;
import com.toast.aurapoints.shim.neoforge.client.gui.VanillaGuiLayers;

/**
 * CLIENT ONLY. The Blue Bell's blizzard (FxPacket.BLIZZARD): the fog bleeds to white, a whiteout veil and a pale rim
 * close in on the screen and driving snow streams past the camera; it fades in and out.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class Blizzard {

	private Blizzard() {
	}

	private static final long FADE = 1200;
	private static long from = 0, until = 0;

	public static void start(int ticks) {
		long now = System.currentTimeMillis();
		if (now > until + FADE) from = now;
		until = now + ticks * 50L;
	}

	/** 0..1, how bad it is now. */
	public static float strength() {
		long now = System.currentTimeMillis();
		if (until == 0 || now >= until + FADE) return 0f;
		float in = Mth.clamp((now - from) / (float) FADE, 0f, 1f), out = Mth.clamp((until + FADE - now) / (float) FADE, 0f, 1f);
		float s = Math.min(in, out);
		return s * s * (3 - 2 * s);
	}

	@SubscribeEvent
	public static void onFogColor(ViewportEvent.ComputeFogColor event) {
		float s = strength();
		if (s <= 0) return;
		event.setRed(Mth.lerp(s * 0.85f, event.getRed(), 0.86f));
		event.setGreen(Mth.lerp(s * 0.85f, event.getGreen(), 0.92f));
		event.setBlue(Mth.lerp(s * 0.85f, event.getBlue(), 0.97f));
	}

	/** Driving snow round the camera, all in one direction (the wind). */
	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		float s = strength();
		Minecraft mc = Minecraft.getInstance();
		if (s <= 0 || mc.level == null || mc.player == null || mc.isPaused()) return;
		var r = mc.level.random;
		int n = Math.round(14 * s);
		for (int i = 0; i < n; i++) {
			double x = mc.player.getX() + (r.nextDouble() - 0.5) * 20, y = mc.player.getY() + r.nextDouble() * 8 - 1, z = mc.player.getZ() + (r.nextDouble() - 0.5) * 20;
			mc.level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, 0.55 + r.nextDouble() * 0.25, -0.12, 0.2 + r.nextDouble() * 0.1);
		}
		if (mc.level.getGameTime() % 3 == 0) {
			double x = mc.player.getX() + (r.nextDouble() - 0.5) * 14, y = mc.player.getY() + r.nextDouble() * 4, z = mc.player.getZ() + (r.nextDouble() - 0.5) * 14;
			mc.level.addParticle(com.toast.aurapoints.AuraPoints.SNOW_GUST.get(), x, y, z, 0.5, 0.0, 0.18);
		}
	}

	@SubscribeEvent
	public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath("aurapoints", "blizzard"), Blizzard::render);
	}

	private static void render(GuiGraphics g, DeltaTracker delta) {
		float s = strength();
		if (s <= 0 || Minecraft.getInstance().options.hideGui) return;
		int w = g.guiWidth(), h = g.guiHeight();
		g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb(0.22f * s, 0xE6F4FF));
		StatusOverlays.edges(g, w, h, 0xCFE9FF, 0.55f * s, Math.round(h * 0.2f));
	}
}
