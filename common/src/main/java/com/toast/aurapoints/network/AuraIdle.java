package com.toast.aurapoints.network;

import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.client.HudColors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * CLIENT ONLY. Clean HUD: the small motion a READY slot makes in its aura's own style (1-2 px, never over the icon's
 * middle). Drawn in slot space: (0,0) is the slot's middle, the slot runs -11..11. Reduced motion skips it (caller).
 */
final class AuraIdle {

	private AuraIdle() {
	}

	static void draw(GuiGraphics g, AuraType aura, int rgb, long now, int index) {
		if (aura == null) return;
		float t = now / 1000f + index * 0.37f;
		switch (aura) {
			case FIRE -> {
				// embers rising off the top edge
				for (int k = 0; k < 3; k++) {
					float ph = frac(t * 0.9f + k / 3f);
					int x = -6 + ((k * 7 + index * 5) % 12) + Math.round(Mth.sin(ph * 9 + k) * 1.2f), y = -10 - Math.round(ph * 7);
					dot(g, x, y, HudColors.argb(1 - ph, HudColors.mix(0xFF5A1F, 0xFFE066, 1 - ph)));
				}
			}
			case FROST -> {
				// frost creeping in from the corners and melting back
				float grow = 0.5f + 0.5f * Mth.sin(t * 1.4f);
				int len = 1 + Math.round(grow * 4), col = HudColors.argb(0.75f, 0xDDF6FF);
				for (int k = 0; k < len; k++) {
					dot(g, -11 + k, -11, col);
					dot(g, -11, -11 + k, col);
					dot(g, 10 - k, 10, col);
					dot(g, 10, 10 - k, col);
				}
			}
			case THUNDER -> {
				// a short spark jumping along an edge a few times a second
				int step = (int) (t * 6);
				if (step % 3 != 0) {
					int side = (step * 7 + index) % 4, at = -8 + (step * 13) % 16;
					int col = HudColors.argb(0.9f, 0xFFF6B0);
					for (int k = 0; k < 3; k++) {
						int j = k % 2 == 0 ? 0 : 1;
						switch (side) {
							case 0 -> dot(g, at + k, -12 + j, col);
							case 1 -> dot(g, 11 - j, at + k, col);
							case 2 -> dot(g, at + k, 11 - j, col);
							default -> dot(g, -12 + j, at + k, col);
						}
					}
				}
			}
			case WATER -> {
				// a ripple spreading out from the slot
				float ph = frac(t / 1.6f);
				CleanHud.ring(g, 0, 0, 9 + ph * 5, HudColors.argb(0.5f * (1 - ph), HudColors.mix(rgb, 0xFFFFFF, 0.4f)));
			}
			case SHADOW -> {
				// smoke drifting up and to the left
				for (int k = 0; k < 2; k++) {
					float ph = frac(t * 0.5f + k / 2f);
					int x = -8 + k * 9 - Math.round(ph * 3), y = -10 - Math.round(ph * 6), r = 1 + Math.round(ph * 2);
					g.fill(x - r, y - r, x + r, y + r, HudColors.argb(0.35f * (1 - ph), 0x1E0C30));
				}
			}
			case MAGE, RESONANCE -> {
				// two runes orbiting the slot
				for (int k = 0; k < 2; k++) {
					double a = t * 1.3 + k * Math.PI;
					dot(g, (int) Math.floor(Math.cos(a) * 11.5), (int) Math.floor(Math.sin(a) * 11.5), HudColors.argb(0.85f, HudColors.mix(rgb, 0xFFFFFF, 0.55f)));
				}
			}
			case BLOOD -> {
				// a heartbeat: lub-dub, then rest
				float ph = frac(t / 1.1f);
				float beat = ph < 0.1f ? ph / 0.1f : ph < 0.2f ? 1 - (ph - 0.1f) / 0.1f : ph < 0.3f ? (ph - 0.2f) / 0.1f * 0.7f : ph < 0.42f ? 0.7f * (1 - (ph - 0.3f) / 0.12f) : 0f;
				if (beat > 0) CleanHud.roundOutline(g, -12, -12, 24, 24, HudColors.argb(0.6f * beat, 0xE0202E));
			}
			case VERDANT -> {
				// a leaf swaying under the slot
				int x = Math.round(Mth.sin(t * 1.6f) * 6), col = HudColors.argb(0.9f, 0x7EDC5A);
				dot(g, x, 12, col);
				dot(g, x + 1, 12, col);
				dot(g, x + (Mth.sin(t * 1.6f) > 0 ? 2 : -1), 11, HudColors.argb(0.9f, 0x4FB848));
			}
			case EARTH -> {
				// dust trickling off the bottom edge
				for (int k = 0; k < 2; k++) {
					float ph = frac(t * 0.7f + k / 2f);
					dot(g, -5 + k * 8 + index % 3, 11 + Math.round(ph * 5), HudColors.argb(0.8f * (1 - ph), 0xB08A5C));
				}
			}
			case LIGHT -> {
				// a glint crossing the slot now and then
				float ph = frac(t / 3f) * 3f;
				if (ph < 1f) {
					int d = Math.round(-22 + ph * 44);
					for (int k = -11; k < 11; k++) {
						int y = d - k;
						if (y >= -11 && y < 11) dot(g, k, y, HudColors.argb(0.45f * Mth.sin(ph * Mth.PI), 0xFFFFFF));
					}
				}
			}
			case WIND -> {
				// three motes swirling round at changing distances
				for (int k = 0; k < 3; k++) {
					double a = t * 3.2 + k * 2.1;
					float r = 11 + Mth.sin(t * 2 + k) * 1.5f;
					dot(g, (int) Math.floor(Math.cos(a) * r), (int) Math.floor(Math.sin(a) * r), HudColors.argb(0.7f, 0xF2FAFF));
				}
			}
			default -> {
			}
		}
	}

	private static float frac(float v) {
		return v - (float) Math.floor(v);
	}

	private static void dot(GuiGraphics g, int x, int y, int color) {
		g.fill(x, y, x + 1, y + 1, color);
	}
}
