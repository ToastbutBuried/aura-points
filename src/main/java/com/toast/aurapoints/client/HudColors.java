package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.config.AuraClientConfig.Palette;

/**
 * CLIENT ONLY. The colors the HUD draws each aura in: the aura's own, a colorblind-friendly palette,
 * or the player's custom color (client config). Particles in the world keep the real aura colors.
 */
public final class HudColors {

	private HudColors() {
	}

	/** The HUD color (0xRRGGBB) for this aura, honoring the custom color and the palette. */
	public static int of(AuraType aura) {
		if (aura == null) return 0xFFFFFF;
		Integer custom = custom();
		return custom != null ? custom : palette(aura);
	}

	/** The palette color only (the roll reel shows every aura, so the custom color doesn't apply there). */
	public static int palette(AuraType aura) {
		Palette p = AuraClientConfig.PALETTE.get();
		return switch (p) {
			case OFF -> aura.rgb & 0xFFFFFF;
			// Okabe-Ito: tells apart under deuteranopia and protanopia
			case RED_GREEN -> switch (aura) {
				case SHADOW -> 0xCC79A7;
				case WATER -> 0x0072B2;
				case FIRE -> 0xE69F00;
				case WIND -> 0xEEEEEE;
				case FROST -> 0x56B4E9;
				case VERDANT -> 0xF0E442;
				case THUNDER -> 0xFFD966;
				case RESONANCE -> 0xB07AD9;
				case EARTH -> 0xA6761D;
				case LIGHT -> 0xFFF3C4;
				case BLOOD -> 0xD55E00;
				case VOID -> 0x9370C0;
			};
			// no blue-against-yellow pairs (tritanopia)
			case BLUE_YELLOW -> switch (aura) {
				case SHADOW -> 0xE7298A;
				case WATER -> 0x1F78B4;
				case FIRE -> 0xD7301F;
				case WIND -> 0xF0F0F0;
				case FROST -> 0x8FD3E8;
				case VERDANT -> 0x33A02C;
				case THUNDER -> 0xFF7F00;
				case RESONANCE -> 0x8E5AC8;
				case EARTH -> 0xA6761D;
				case LIGHT -> 0xFFF0B0;
				case BLOOD -> 0xE31A1C;
				case VOID -> 0x6A3D9A;
			};
			case HIGH_CONTRAST -> switch (aura) {
				case SHADOW -> 0xC040FF;
				case WATER -> 0x00A8FF;
				case FIRE -> 0xFF3A00;
				case WIND -> 0xFFFFFF;
				case FROST -> 0x80FFFF;
				case VERDANT -> 0x40FF40;
				case THUNDER -> 0xFFFF00;
				case RESONANCE -> 0xFF30FF;
				case EARTH -> 0xFFB060;
				case LIGHT -> 0xFFFFC0;
				case BLOOD -> 0xFF2020;
				case VOID -> 0xB060FF;
			};
		};
	}

	/** The custom HUD color from the config, or null for none. */
	private static Integer custom() {
		String s = AuraClientConfig.HUD_COLOR.get();
		if (s == null || s.isBlank()) return null;
		try {
			return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Panel body colors: darker and more solid in high contrast. */
	public static int panelTop() {
		return AuraClientConfig.PALETTE.get() == Palette.HIGH_CONTRAST ? 0xF0000000 : 0xD0141420;
	}

	public static int panelBottom() {
		return AuraClientConfig.PALETTE.get() == Palette.HIGH_CONTRAST ? 0xF0000000 : 0xD0050508;
	}

	/** "Not ready" flash: red, or magenta-ish for the red-green palette (red reads as dark there). */
	public static int denied() {
		return AuraClientConfig.PALETTE.get() == Palette.RED_GREEN ? 0xFF3DA5 : 0xFF3030;
	}

	/** Mixes two 0xRRGGBB colors, t = 0 gives a. */
	public static int mix(int a, int b, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return (r << 16) | (g << 8) | bl;
	}

	/** 0xAARRGGBB from an alpha 0..1 and 0xRRGGBB. */
	public static int argb(float alpha, int rgb) {
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
		return (a << 24) | (rgb & 0xFFFFFF);
	}
}
