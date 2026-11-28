package com.toast.aurapoints.network;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.client.ClientCooldowns;
import com.toast.aurapoints.client.HudColors;
import com.toast.aurapoints.config.AuraClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * CLIENT ONLY. The Mage's mana bar in the Clean style: a rounded bar with the number only (no "/max"; "∞" when free)
 * and a tiny level badge. It lives: a white ghost of each spend lags and then drains, light bands rise through the fill,
 * a bright edge while it refills, a slow breath when full, a red shake when a spell is too dear, a flash and a badge pop
 * on a level up. While a spell key is held its cost is hatched on the bar (red when it can't be paid).
 * Places as the Legacy bar: LEFT / RIGHT standing up, HOTBAR lying above the hearts, OFF.
 */
final class CleanMana {

	private CleanMana() {
	}

	private static final int MANA_A = 0x6FE7FF, MANA_B = 0x4A5CFF;
	private static float ghost = -1, last = -1;
	private static long ghostHold = 0, lastFrame = 0, gainAt = 0, levelAt = 0;
	private static int lastLevel = -1;

	static void clear() {
		ghost = last = -1;
		lastLevel = -1;
		levelAt = 0;
	}

	static void render(GuiGraphics g, Minecraft mc, AuraClientConfig.ManaBarPlace place) {
		long now = System.currentTimeMillis();
		boolean calm = AuraClientConfig.calm();
		float mana = ClientAuraState.mana;
		int max = Math.max(1, ClientAuraState.manaMax);
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		// the ghost: jumps to where the mana was on a spend, waits a moment, then drains to the real value
		if (last < 0) ghost = last = mana;
		if (mana < last - 0.01f) {
			if (now >= ghostHold) ghost = Math.max(ghost, last);
			ghostHold = now + 350;
		} else if (mana > last + 0.01f) gainAt = now;
		last = mana;
		if (now >= ghostHold) ghost = Math.max(mana, ghost - 40 * dt);
		if (ghost < mana || calm) ghost = mana;
		if (lastLevel >= 0 && ClientAuraState.manaLevel > lastLevel) levelAt = now;
		lastLevel = ClientAuraState.manaLevel;

		Long denied = ClientCooldowns.lastDenied();
		float deny = denied == null || now - denied > 400 ? 0f : 1f - (now - denied) / 400f;
		float frac = Mth.clamp(mana / max, 0f, 1f), gfrac = Mth.clamp(ghost / max, 0f, 1f);
		boolean full = mana >= max - 0.01f, infinite = ClientAuraState.manaInfinite;
		int heldCost = heldCost(mc);

		Font font = mc.font;
		float s = (float) (double) AuraClientConfig.MANA_SCALE.get();
		boolean upright = place != AuraClientConfig.ManaBarPlace.HOTBAR;
		int bw = upright ? 4 : 81, bh = upright ? 56 : 3;
		int gw = g.guiWidth(), gh = g.guiHeight();
		float x, y;
		if (place == AuraClientConfig.ManaBarPlace.LEFT) {
			x = 9;
			y = gh / 2f - bh * s / 2f + 20;
		} else if (place == AuraClientConfig.ManaBarPlace.RIGHT) {
			x = gw - 9 - bw * s;
			y = gh / 2f - bh * s / 2f + 20;
		} else {
			x = gw / 2f - bw * s / 2f;
			y = gh - 60 - bh * s;
		}
		x = Mth.clamp(x + AuraClientConfig.MANA_OFFSET_X.get(), 2, gw - 2 - bw * s);
		y = Mth.clamp(y + AuraClientConfig.MANA_OFFSET_Y.get(), 12 * s, gh - 12 * s - bh * s);
		if (deny > 0 && !calm) x += Mth.sin((now - denied) / 12f) * 1.6f * deny;

		g.pose().pushPose();
		g.pose().translate(x, y, 0);
		g.pose().scale(s, s, 1);
		// breathing when full
		if (full && !calm && !infinite) {
			float b = 0.5f + 0.5f * Mth.sin(now / 450f);
			CleanHud.round(g, -3, -3, bw + 6, bh + 6, HudColors.argb(0.08f + 0.1f * b, MANA_A));
			CleanHud.round(g, -2, -2, bw + 4, bh + 4, HudColors.argb(0.12f + 0.15f * b, MANA_A));
		}
		// the well and its edge (red on a refused spell)
		CleanHud.round(g, -1, -1, bw + 2, bh + 2, 0xB80A0A1A);
		CleanHud.roundOutline(g, -1, -1, bw + 2, bh + 2, deny > 0 ? HudColors.argb(0.5f + 0.5f * deny, HudColors.denied())
				: HudColors.argb(0.35f, HudColors.of(AuraType.MAGE)));
		// the ghost, then the fill
		segment(g, upright, bw, bh, frac, gfrac, 0xD8EBF5FF);
		if (frac > 0) {
			if (upright) {
				int fh = Math.round(bh * frac);
				g.fillGradient(0, bh - fh, bw, bh, 0xFF000000 | MANA_A, 0xFF000000 | MANA_B);
			} else {
				int fw = Math.round(bw * frac);
				for (int i = 0; i < fw; i++) g.fill(i, 0, i + 1, bh, 0xFF000000 | HudColors.mix(MANA_B, MANA_A, i / (float) Math.max(1, bw)));
			}
			if (!calm) {
				// light bands rising (or running) through the fill
				int len = upright ? bh : bw;
				float filled = len * frac;
				for (int k = 0; k < 2; k++) {
					float pos = (now / 1000f * 26 + k * len * 0.55f) % (len + 14) - 7;
					float from = Math.max(0, pos), to = Math.min(filled, pos + (k == 0 ? 3 : 2));
					if (to > from) segment(g, upright, bw, bh, from / len, to / len, HudColors.argb(k == 0 ? 0.28f : 0.14f, 0xFFFFFF));
				}
			}
			// refilling: a bright edge at the top of the fill
			if (now - gainAt < 120 && !full) {
				float a = calm ? 0.7f : 0.6f + 0.4f * Mth.sin(now / 50f);
				int len = upright ? bh : bw;
				float edge = frac - 1f / len;
				segment(g, upright, bw, bh, Math.max(0, edge), frac, HudColors.argb(a, 0xFFFFFF));
			}
		}
		// the cost of the spell whose key is held, hatched
		boolean dear = false;
		if (heldCost > 0 && !infinite) {
			float c = Math.min(1f, heldCost / (float) max);
			dear = heldCost > mana + 1.0e-3f;
			float from = dear ? 0 : Math.max(0, frac - c), to = dear ? c : frac;
			hatch(g, upright, bw, bh, from, to, dear ? 0xE6FF5A5A : 0xC0FFFFFF, dear ? 0x59FF4646 : 0x2EFFFFFF);
		}
		// level up: the whole bar flashes
		if (now - levelAt < 500) CleanHud.round(g, -1, -1, bw + 2, bh + 2, HudColors.argb(0.8f * (1 - (now - levelAt) / 500f), 0xFFFFFF));

		// the number, and the level badge
		String have = infinite ? "∞" : String.valueOf((int) Math.floor(mana));
		int numCol = dear ? 0xFFFF9A9A : deny > 0 ? 0xFF000000 | HudColors.mix(0xB8F2FF, HudColors.denied(), deny) : 0xFFB8F2FF;
		float pop = calm ? 1f : 1f + 0.6f * Math.max(0, 1 - (now - levelAt) / 300f);
		String lv = "Lv" + ClientAuraState.manaLevel;
		int lvCol = now - levelAt < 1000 ? 0xFF000000 | HudColors.mix(0x9AA0C8, 0xFFFFFF, 1 - (now - levelAt) / 1000f) : 0xFF9AA0C8;
		if (upright) {
			g.drawString(font, have, bw / 2 - font.width(have) / 2, -10, numCol, true);
			CleanHud.small(g, font, lv, bw / 2f, bh + 4, 0.5f * pop, lvCol);
		} else {
			CleanHud.small(g, font, have, bw / 2f, -8, 0.75f, numCol);
			g.pose().pushPose();
			g.pose().translate(bw + 3, -1, 0);
			g.pose().scale(0.5f * pop, 0.5f * pop, 1);
			g.drawString(font, lv, 0, 0, lvCol, true);
			g.pose().popPose();
		}
		g.pose().popPose();
	}

	/** The mana cost of the Mage spell whose key is down right now (0: none). */
	private static int heldCost(Minecraft mc) {
		AuraType aura = ClientAuraState.aura;
		if (aura == null || mc.screen != null) return 0;
		for (int i = 0; i < ClientKeyHandler.KEYS.length; i++) {
			if (!ClientKeyHandler.KEYS[i].isDown()) continue;
			Ability a = i == AbilityKeyPacket.ULTIMATE ? com.toast.aurapoints.ability.Ultimates.of(aura, ClientAuraState.branch)
					: i < AuraType.KEY_SLOTS ? aura.slot(i, ClientAuraState.branch) : null;
			if (a == null) continue;
			int cost = ClientAuraState.manaCost(a);
			if (cost > 0) return cost;
		}
		return 0;
	}

	/** Fills the part of the bar between {@code from} and {@code to} (0 = empty end, 1 = full end). */
	private static void segment(GuiGraphics g, boolean upright, int bw, int bh, float from, float to, int color) {
		if (to <= from) return;
		if (upright) {
			int y0 = Math.round(bh * (1 - to)), y1 = Math.round(bh * (1 - from));
			if (y1 > y0) g.fill(0, y0, bw, y1, color);
		} else {
			int x0 = Math.round(bw * from), x1 = Math.round(bw * to);
			if (x1 > x0) g.fill(x0, 0, x1, bh, color);
		}
	}

	/** Diagonal hatching over a part of the bar, on a tinted ground. */
	private static void hatch(GuiGraphics g, boolean upright, int bw, int bh, float from, float to, int lines, int ground) {
		segment(g, upright, bw, bh, from, to, ground);
		if (upright) {
			int y0 = Math.round(bh * (1 - to)), y1 = Math.round(bh * (1 - from));
			for (int y = y0; y < y1; y++) for (int x = 0; x < bw; x++) if (Math.floorMod(x + y, 3) == 0) g.fill(x, y, x + 1, y + 1, lines);
		} else {
			int x0 = Math.round(bw * from), x1 = Math.round(bw * to);
			for (int x = x0; x < x1; x++) for (int y = 0; y < bh; y++) if (Math.floorMod(x + y, 3) == 0) g.fill(x, y, x + 1, y + 1, lines);
		}
	}
}
