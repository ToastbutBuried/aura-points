package com.toast.aurapoints.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * CLIENT ONLY. A Blood Moon nearby (FxPacket.BLOOD_MOON): the sky and the fog bleed to a deep red for a while
 * (the sky through mixin/ClientLevelMixin), with a dark red rim round the screen; it fades in and out.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class BloodMoonSky {

	private BloodMoonSky() {
	}

	private static final long FADE = 1500;
	private static long from = 0, until = 0;

	public static void start(int ticks) {
		long now = System.currentTimeMillis();
		if (now > until) from = now;
		until = now + ticks * 50L;
	}

	/** 0..1, how red it is now. */
	public static float strength() {
		long now = System.currentTimeMillis();
		if (now >= until + FADE || until == 0) return 0f;
		float in = Mth.clamp((now - from) / (float) FADE, 0f, 1f), out = Mth.clamp((until + FADE - now) / (float) FADE, 0f, 1f);
		float s = Math.min(in, out);
		return s * s * (3 - 2 * s);
	}

	/** The sky's color under the moon. */
	public static Vec3 tint(Vec3 sky, float s) {
		double dim = 0.35 + 0.65 * (sky.x + sky.y + sky.z) / 3;
		return new Vec3(Mth.lerp(s * 0.85, sky.x, 0.38 * dim + 0.06), Mth.lerp(s * 0.85, sky.y, 0.02 * dim), Mth.lerp(s * 0.85, sky.z, 0.045 * dim));
	}

	@SubscribeEvent
	public static void onFogColor(ViewportEvent.ComputeFogColor event) {
		float s = strength();
		if (s <= 0) return;
		event.setRed(Mth.lerp(s * 0.8f, event.getRed(), 0.34f));
		event.setGreen(Mth.lerp(s * 0.8f, event.getGreen(), 0.03f));
		event.setBlue(Mth.lerp(s * 0.8f, event.getBlue(), 0.045f));
	}

	@SubscribeEvent
	public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath("aurapoints", "blood_moon"), BloodMoonSky::render);
	}

	private static void render(GuiGraphics g, DeltaTracker delta) {
		float s = strength();
		if (s <= 0 || Minecraft.getInstance().options.hideGui) return;
		int w = g.guiWidth(), h = g.guiHeight();
		g.fill(0, 0, w, h, HudColors.argb(0.08f * s, 0x8A0010));
		StatusOverlays.edges(g, w, h, 0x4A0008, 0.5f * s, Math.round(h * 0.16f));
	}
}
