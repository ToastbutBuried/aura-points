package com.toast.aurapoints.fabric.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mod's HUD layers on Fabric: drawn right after the vanilla part they were registered above (mixin/client/GuiMixin),
 * or after everything; each one a layer higher, like NeoForge's layered HUD.
 */
public final class GuiLayers {
	private GuiLayers() {
	}

	private static final Map<ResourceLocation, List<LayeredDraw.Layer>> ABOVE = new LinkedHashMap<>();
	private static final List<LayeredDraw.Layer> TOP = new ArrayList<>();

	static void add(ResourceLocation above, LayeredDraw.Layer layer) {
		if (above == null) TOP.add(layer);
		else ABOVE.computeIfAbsent(above, k -> new ArrayList<>()).add(layer);
	}

	public static void renderAbove(ResourceLocation vanilla, GuiGraphics g, DeltaTracker delta) {
		List<LayeredDraw.Layer> layers = ABOVE.get(vanilla);
		if (layers != null) render(layers, g, delta);
	}

	public static void renderTop(GuiGraphics g, DeltaTracker delta) {
		render(TOP, g, delta);
	}

	private static void render(List<LayeredDraw.Layer> layers, GuiGraphics g, DeltaTracker delta) {
		g.pose().pushPose();
		for (LayeredDraw.Layer layer : layers) {
			g.pose().translate(0.0F, 0.0F, LayeredDraw.Z_SEPARATION);
			layer.render(g, delta);
		}
		g.pose().popPose();
	}
}
