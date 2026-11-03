package com.toast.aurapoints.shim.neoforge.client.gui;

import net.minecraft.resources.ResourceLocation;

/** The vanilla HUD layers mod layers are placed above (same ids as NeoForge's). */
public final class VanillaGuiLayers {
	private VanillaGuiLayers() {
	}

	public static final ResourceLocation CAMERA_OVERLAYS = ResourceLocation.withDefaultNamespace("camera_overlays");
	public static final ResourceLocation CROSSHAIR = ResourceLocation.withDefaultNamespace("crosshair");
	public static final ResourceLocation HOTBAR = ResourceLocation.withDefaultNamespace("hotbar");
}
