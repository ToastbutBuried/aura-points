package com.toast.aurapoints.client;

import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.config.AuraClientConfig.Anchor;
import com.toast.aurapoints.config.AuraClientConfig.BarPlace;
import net.minecraft.client.Minecraft;

/**
 * CLIENT ONLY. Where the ability panel and the charge bar go on screen (anchor + offset + scale from
 * the client config). Shared by the HUD and the drag-around editor, so both agree to the pixel.
 */
public final class HudLayout {

	private HudLayout() {
	}

	/** Room above the panel for its label (unscaled). */
	public static final int LABEL_SPACE = 10;
	/** The charge bar's size (unscaled): the bar itself plus its frame, icon above and text below. */
	public static final int BAR_W = 10, BAR_H = 90;

	public static float scale() {
		return (float) (double) AuraClientConfig.HUD_SCALE.get();
	}

	/** The charge bar's scale; by the crosshair it's half size to begin with. */
	public static float chargeScale() {
		float s = (float) (double) AuraClientConfig.CHARGE_SCALE.get();
		return AuraClientConfig.CHARGE_BAR.get() == BarPlace.CROSSHAIR ? s * 0.5f : s;
	}

	/** Top-left of the panel body (below its label) for a panel {@code w} x {@code h} (unscaled), with the offset. */
	public static int[] panel(Minecraft mc, int guiW, int guiH, int w, int h) {
		int[] base = panelBase(mc, guiW, guiH, w, h);
		return clampPanel(guiW, guiH, w, h, base[0] + AuraClientConfig.HUD_OFFSET_X.get(), base[1] + AuraClientConfig.HUD_OFFSET_Y.get());
	}

	/** The same, without the offset (the editor works out the offset from it). */
	public static int[] panelBase(Minecraft mc, int guiW, int guiH, int w, int h) {
		float s = scale();
		int sw = Math.round(w * s), sh = Math.round(h * s), label = Math.round(LABEL_SPACE * s);
		Anchor anchor = AuraClientConfig.HUD_ANCHOR.get();
		return switch (anchor) {
			case HOTBAR -> {
				boolean offhandLeft = mc.player == null || mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
				int right = guiW / 2 - 91 - (offhandLeft ? 29 : 0) - 6;
				yield new int[]{right - sw, guiH - 1 - sh};
			}
			case TOP_LEFT -> new int[]{4, 3 + label};
			case TOP_CENTER -> new int[]{guiW / 2 - sw / 2, 3 + label};
			case TOP_RIGHT -> new int[]{guiW - 4 - sw, 3 + label};
			case BOTTOM_LEFT -> new int[]{4, guiH - 1 - sh};
			case BOTTOM_RIGHT -> new int[]{guiW - 4 - sw, guiH - 1 - sh};
		};
	}

	/** Keeps a panel (and its label) on screen. */
	public static int[] clampPanel(int guiW, int guiH, int w, int h, int x, int y) {
		float s = scale();
		int sw = Math.round(w * s), sh = Math.round(h * s), label = Math.round(LABEL_SPACE * s);
		x = Math.max(1, Math.min(guiW - sw - 1, x));
		y = Math.max(label + 1, Math.min(guiH - sh, y));
		return new int[]{x, y};
	}

	/** Top-left of the charge bar itself (unscaled size BAR_W x BAR_H; its frame, icon and text go around it). */
	public static int[] bar(int guiW, int guiH) {
		int[] base = barBase(guiW, guiH);
		float s = chargeScale();
		int x = base[0] + AuraClientConfig.CHARGE_OFFSET_X.get(), y = base[1] + AuraClientConfig.CHARGE_OFFSET_Y.get();
		x = Math.max(4, Math.min(guiW - 4 - Math.round(BAR_W * s), x));
		y = Math.max(Math.round(24 * s), Math.min(guiH - Math.round((BAR_H + 14) * s), y));
		return new int[]{x, y};
	}

	public static int[] barBase(int guiW, int guiH) {
		float s = chargeScale();
		int h = Math.round(BAR_H * s), w = Math.round(BAR_W * s);
		BarPlace place = AuraClientConfig.CHARGE_BAR.get();
		return switch (place) {
			case LEFT -> new int[]{10, guiH / 2 - h / 2};
			case RIGHT -> new int[]{guiW - 10 - w, guiH / 2 - h / 2};
			// beside the crosshair, out of the way of the bubble guide below it
			case CROSSHAIR -> new int[]{guiW / 2 + 24, guiH / 2 - h / 2};
		};
	}
}
