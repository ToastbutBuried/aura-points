package com.toast.aurapoints.client;

import com.toast.aurapoints.mixin.GameRendererInvoker;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;

/**
 * CLIENT ONLY. Whether the mod's crosshair marks (cooldown arcs/pips, charge ring, bubble mark) may draw now: only when
 * the vanilla crosshair was drawn this frame (mixin GuiCrosshairMixin) and the view isn't zoomed in (Zoomify, OK Zoomer,
 * the spyglass: the field of view drops well under the setting). A bow's draw only narrows it a little and keeps them.
 */
public final class CrosshairState {

	private CrosshairState() {
	}

	private static long drawnAt = 0;

	public static void mark() {
		drawnAt = System.nanoTime();
	}

	public static boolean shown(DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		// drawn in this frame (the layers draw right after it; 20 ms covers any frame order)
		if (System.nanoTime() - drawnAt > 20_000_000L) return false;
		if (mc.player.isScoping()) return false;
		try {
			double fov = ((GameRendererInvoker) mc.gameRenderer).aurapoints$getFov(mc.gameRenderer.getMainCamera(), delta.getGameTimeDeltaPartialTick(true), true);
			return fov >= mc.options.fov().get() * 0.7;
		} catch (RuntimeException e) {
			return true;
		}
	}
}
