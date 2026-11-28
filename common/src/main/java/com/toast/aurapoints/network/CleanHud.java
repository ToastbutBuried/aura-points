package com.toast.aurapoints.network;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.client.ClientCooldowns;
import com.toast.aurapoints.client.HudColors;
import com.toast.aurapoints.config.AuraClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * CLIENT ONLY. The Clean HUD style (5.66.0, client config hud.style = CLEAN, the default): no frame, gems or label.
 * Icons float in a row; a dark rounded backing shows only behind slots that wait (cooldown, not enough mana/blood,
 * low health). Everything essential moves: the ready pop and ring, the almost-ready ramp, the Ultimate filling like a
 * vessel, a small motion in the aura's own style on ready slots. Reduced motion ({@code calm()}) stills all of it.
 * Layout, anchors, split panel and the handheld panel are ClientAuraHud's; this class only draws inside a panel.
 * Mock-up the look was picked from: vault Workflow/5.65 Check and GUI Rework §13.
 */
final class CleanHud {

	private CleanHud() {
	}

	static final int SLOT = 22, GAP = 3, PAD = 4;
	static final int GOLD = 0xFFC83D, FLIGHT_RGB = 0x9FE3FF;
	private static final long POP_MILLIS = 450, PUNCH_MILLIS = 160, DENY_MILLIS = 350, FLARE_MILLIS = 800;

	/** When each Ultimate last became ready (for the screen-edge flare, once per cooldown). */
	private static final Map<Ability, Long> FLARED = new EnumMap<>(Ability.class);
	private static long flareAt = 0;

	static boolean calm() {
		return AuraClientConfig.calm();
	}

	static void clear() {
		FLARED.clear();
		flareAt = 0;
		CleanExtras.clear();
		CleanMana.clear();
	}

	/** Slot width of a Clean panel: no label to make room for. */
	static int width(int slots) {
		return PAD * 2 + slots * SLOT + (slots - 1) * GAP;
	}

	/**
	 * One Clean panel at ({@code x}, {@code y}) on screen, scaled by the HUD scale about that corner.
	 * {@code main}: the aura's own panel (the extras ride on it: aura name, buff ribbon, streak).
	 */
	static void panel(GuiGraphics g, Minecraft mc, AuraType aura, List<Ability> abilities, boolean flying, int rgb,
					  float x, float y, int width, long now, boolean main, long auraSince, float scale) {
		Font font = mc.font;
		int slotsWidth = abilities.size() * SLOT + (abilities.size() - 1) * GAP;
		int left = (width - slotsWidth) / 2;
		g.pose().pushPose();
		g.pose().translate(x, y, 0);
		g.pose().scale(scale, scale, 1);
		if (main) CleanExtras.panelTop(g, font, aura, rgb, width, now, auraSince);
		for (int i = 0; i < abilities.size(); i++) {
			Ability ability = abilities.get(i);
			boolean flight = flying && ClientAuraState.hasFlightVersion(ability);
			boolean ult = com.toast.aurapoints.ability.Ultimates.auraOf(ability) != null;
			int slotRgb = flight ? FLIGHT_RGB : ult ? GOLD : rgb;
			slot(g, mc, font, aura, ability, flight, left + i * (SLOT + GAP), PAD, slotRgb, rgb, now, i);
		}
		g.pose().popPose();
	}

	private static void slot(GuiGraphics g, Minecraft mc, Font font, AuraType aura, Ability ability, boolean flight, int sx, int sy,
							 int rgb, int auraRgb, long now, int index) {
		AuraAbilities.Slot slotKind = flight ? AuraAbilities.Slot.FLIGHT : null;
		long[] cd = ability == Ability.ICE_SHELL ? null : ClientAuraState.cooldown(ability, slotKind);
		boolean cooling = cd != null && now < cd[1];
		float frac = cooling ? (cd[1] - now) / (float) Math.max(1, cd[1] - cd[0]) : 0f;
		float secs = cooling ? (cd[1] - now) / 1000f : 0f;
		// Blood Moon waits on the blood bar, a Mage spell on its mana: "thirsty" until there is enough
		float fill = ability == Ability.BLOOD_MOON ? ClientAuraState.bloodMoonFill() : 1f;
		int cost = ClientAuraState.mana >= 0 ? ClientAuraState.manaCost(ability) : 0;
		if (cost > 0) fill = Math.min(1f, ClientAuraState.mana / cost);
		boolean manaSlot = cost > 0, thirsty = fill < 1f && !cooling;
		boolean ult = com.toast.aurapoints.ability.Ultimates.auraOf(ability) != null;
		boolean ready = !cooling && !thirsty;
		boolean calm = calm();

		// how it moves: the ready pop, the press punch, the too-early shake, a nervous last second
		float scale = 1f, shakeX = 0f, denied = 0f;
		Long readyAt = ability == Ability.ICE_SHELL ? (Long) ClientCooldowns.shellWholeAt() : ClientCooldowns.readyAt(ability, flight);
		boolean popping = readyAt != null && ready && now - readyAt < POP_MILLIS;
		if (!calm) {
			if (popping) {
				float t = (now - readyAt) / (float) POP_MILLIS;
				scale += 0.25f * Mth.sin(t * Mth.PI) * (1 - t * 0.3f);
			}
			Long pressed = ClientCooldowns.pressedAt(ability);
			if (pressed != null && now - pressed < PUNCH_MILLIS) scale -= 0.1f * Mth.sin((now - pressed) / (float) PUNCH_MILLIS * Mth.PI);
			if (cooling && secs < 1f) scale += 0.03f * Mth.sin(now / 55f);
		}
		Long deniedAt = ClientCooldowns.deniedAt(ability);
		if (deniedAt != null && now - deniedAt < DENY_MILLIS) {
			float t = (now - deniedAt) / (float) DENY_MILLIS;
			denied = 1 - t;
			if (!calm) shakeX = Mth.sin(t * 40f) * 2f * (1 - t);
		}
		// an Ultimate that just came back: one flare along the screen edge
		if (ult && popping && !readyAt.equals(FLARED.get(ability))) {
			FLARED.put(ability, readyAt);
			flareAt = now;
		}

		g.pose().pushPose();
		g.pose().translate(sx + SLOT / 2f + shakeX, sy + SLOT / 2f, 0);
		g.pose().scale(scale, scale, 1);
		// slot space: (0,0) is the slot's middle, the slot runs -11..11

		float danger = CleanExtras.danger(mc, now);
		// the backing: only behind a slot that waits (or everywhere while in danger)
		if (cooling || thirsty || danger > 0) {
			int back = danger > 0 ? HudColors.argb(0.5f + 0.15f * danger, HudColors.mix(0x0A0A14, 0x9A0C10, danger)) : 0x940A0A14;
			round(g, -11, -11, 22, 22, back);
		}
		// ready: a soft glow in the slot's colour that breathes
		if (ready && ability != Ability.ICE_SHELL) {
			float breath = calm ? 0.22f : 0.18f + 0.06f * Mth.sin(now / 420f + index);
			glow(g, rgb, breath);
		}
		// the Ultimate: a vessel filling from the bottom with a moving surface, and its thin gold ring
		if (ult) {
			if (cooling) {
				float level = 1f - frac;
				int col = HudColors.argb(0.32f, GOLD);
				for (int px = -10; px < 10; px++) {
					float wave = calm ? 0 : Mth.sin(px * 0.6f + now / 200f) * 0.8f;
					int top = Math.round(10 - 20 * level + wave);
					if (top < 10) g.fill(px, Math.max(-10, top), px + 1, 10, col);
				}
			}
			float ring = cooling ? 0.35f : calm ? 0.95f : 0.75f + 0.25f * Mth.sin(now / 250f);
			roundOutline(g, -11, -11, 22, 22, HudColors.argb(ring, GOLD));
			if (ready && !calm) roundOutline(g, -12, -12, 24, 24, HudColors.argb(0.25f + 0.2f * Mth.sin(now / 250f), GOLD));
		}
		// a small motion in the aura's own style while ready
		if (ready && !calm && ability != Ability.ICE_SHELL) AuraIdle.draw(g, aura, auraRgb, now, index);

		// the icon (a handheld shows the item in hand)
		g.renderItem(com.toast.aurapoints.ability.Handhelds.auraOf(ability) != null ? mc.player.getMainHandItem() : ClientAuraHud.icon(ability), -8, -8);
		if (ability == Ability.ICE_SHELL) {
			ClientAuraHud.drawIceShell(g, font, -11, -11, rgb, now);
			g.pose().popPose();
			return;
		}

		g.pose().pushPose();
		g.pose().translate(0, 0, 200); // above the item icon
		if (thirsty) {
			// what it still needs fills the icon from below
			int h = Math.round(20 * fill);
			g.fill(-10, -10, 10, 10, 0x50000008);
			if (h > 0) g.fill(-10, 10 - h, 10, 10, manaSlot ? 0x485A78FF : 0x48C01020);
			String text = manaSlot ? String.valueOf(cost) : Math.round(fill * 100) + "%";
			small(g, font, text, 0, -3, 0.75f, manaSlot ? 0xFF9FB4FF : 0xFFFF9090);
		}
		if (cooling) {
			g.fill(-10, -10, 10, 10, 0x40000008);
			// the last 20 % brightens toward ready
			float ramp = frac < 0.2f ? 1f - frac / 0.2f : 0f;
			int edge = HudColors.argb(0.6f + 0.4f * ramp, HudColors.mix(rgb, 0xFFFFFF, 0.3f + 0.5f * ramp));
			ClientAuraHud.sweep(g, -10, -10, 20, frac, 0x8C04040A, edge);
			if (ramp > 0) {
				float flick = calm ? 1f : 0.7f + 0.3f * Mth.sin(now / 50f);
				roundOutline(g, -11, -11, 22, 22, HudColors.argb((0.25f + 0.6f * ramp) * flick, rgb));
			}
			String text = switch (AuraClientConfig.COOLDOWN_TEXT.get()) {
				case DECIMAL -> secs >= 3 ? String.valueOf((int) Math.ceil(secs)) : String.format("%.1f", secs);
				case SECONDS -> String.valueOf((int) Math.ceil(secs));
				case OFF -> "";
			};
			if (!text.isEmpty()) {
				int col = secs < 3 ? HudColors.mix(0xFFE066, 0xFFFFFF, ramp * 0.4f) : 0xEDEDF5;
				small(g, font, text, 0, -3, 0.75f, 0xFF000000 | col);
			}
		}
		if (denied > 0) roundOutline(g, -11, -11, 22, 22, HudColors.argb(denied, HudColors.denied()));
		// the ring bursting out as it comes back
		if (popping && !calm) {
			float t = (now - readyAt) / (float) POP_MILLIS, e = 1 - (1 - t) * (1 - t) * (1 - t);
			ring(g, 0, 0, 11 + 9 * e, HudColors.argb(1 - t, HudColors.mix(rgb, 0xFFFFFF, 0.5f)));
		}
		// a sneak or charged cast is open to you: a tiny glowing corner dot
		if (!cooling && hasVariant(mc, ability)) {
			float p = calm ? 1f : 0.6f + 0.4f * Mth.sin(now / 330f + index);
			g.fill(8, -10, 10, -8, HudColors.argb(p, HudColors.mix(rgb, 0xFFFFFF, 0.5f)));
		}
		// the chain's next link
		CleanExtras.chainMark(g, ability, now);
		// Wind's launch recharging on its own: a thin strip along the bottom
		if (!flight) {
			long[] launch = ClientAuraState.cooldown(ability, AuraAbilities.Slot.LAUNCH);
			if (launch != null && now < launch[1]) {
				float lf = (launch[1] - now) / (float) Math.max(1, launch[1] - launch[0]);
				g.fill(-10, 8, 10, 10, 0xC0000000);
				g.fill(-10, 8, -10 + Math.max(1, Math.round(20 * lf)), 10, 0xFF000000 | FLIGHT_RGB);
			}
		}
		// the key: always there but faint, bright while the player list key (Tab) is held
		if (AuraClientConfig.HUD_KEYBINDS.get()) {
			String key = ClientAuraHud.keyLabel(ability);
			boolean unbound = key == null;
			if (unbound) key = "?";
			// short names keep the hint inside the slot ("Left Alt" -> "LAlt")
			key = key.replace("Left ", "L").replace("Right ", "R");
			boolean peek = mc.options.keyPlayerList.isDown();
			int col = unbound ? 0xFF5555 : 0xE0E0EA;
			g.pose().pushPose();
			g.pose().translate(10, 10, 0);
			g.pose().scale(0.5f, 0.5f, 1);
			g.drawString(font, key, -font.width(key), -8, HudColors.argb(peek || unbound ? 1f : 0.4f, col), true);
			g.pose().popPose();
		}
		g.pose().popPose();
		g.pose().popPose();
	}

	/** True when this ability has a sneak or charged cast the player's Mastery has opened. */
	private static boolean hasVariant(Minecraft mc, Ability ability) {
		if (!com.toast.aurapoints.config.AuraConfig.CAST_VARIANTS_ENABLED) return false;
		int mastery = ClientAuraState.playerMastery(mc.player.getId());
		return CastMode.has(ability, CastMode.SNEAK) && mastery >= com.toast.aurapoints.config.AuraConfig.CAST_SNEAK_MASTERY
				|| CastMode.has(ability, CastMode.CHARGED) && mastery >= com.toast.aurapoints.config.AuraConfig.CAST_CHARGED_MASTERY;
	}

	/** After the panels: the Ultimate's flare along the bottom and left screen edges. */
	static void flare(GuiGraphics g, long now) {
		if (calm() || now - flareAt >= FLARE_MILLIS) return;
		float k = 1f - (now - flareAt) / (float) FLARE_MILLIS;
		int w = g.guiWidth(), h = g.guiHeight();
		g.fillGradient(0, h - 26, w, h, HudColors.argb(0f, GOLD), HudColors.argb(0.45f * k, GOLD));
		for (int x = 0; x < 18; x++) g.fill(x, 0, x + 1, h, HudColors.argb(0.4f * k * (1 - x / 18f), GOLD));
	}

	// ---------------------------------------------------------------
	// Little drawing helpers (whole GUI pixels)
	// ---------------------------------------------------------------

	/** A filled box with its corners cut (radius 2). */
	static void round(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x + 2, y, x + w - 2, y + 1, color);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, color);
		g.fill(x, y + 2, x + w, y + h - 2, color);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, color);
		g.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
	}

	/** The outline of {@link #round}. */
	static void roundOutline(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x + 2, y, x + w - 2, y + 1, color);
		g.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
		g.fill(x, y + 2, x + 1, y + h - 2, color);
		g.fill(x + w - 1, y + 2, x + w, y + h - 2, color);
		g.fill(x + 1, y + 1, x + 2, y + 2, color);
		g.fill(x + w - 2, y + 1, x + w - 1, y + 2, color);
		g.fill(x + 1, y + h - 2, x + 2, y + h - 1, color);
		g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color);
	}

	/** A soft square glow round the slot's middle: three layers fading out. */
	private static void glow(GuiGraphics g, int rgb, float a) {
		round(g, -13, -13, 26, 26, HudColors.argb(a * 0.3f, rgb));
		round(g, -11, -11, 22, 22, HudColors.argb(a * 0.45f, rgb));
		round(g, -8, -8, 16, 16, HudColors.argb(a * 0.5f, rgb));
	}

	/** A one-pixel circle of radius {@code r} round ({@code cx}, {@code cy}). */
	static void ring(GuiGraphics g, float cx, float cy, float r, int color) {
		int steps = Math.max(16, Math.round(r * 6.3f));
		for (int k = 0; k < steps; k++) {
			double a = k * Math.PI * 2 / steps;
			int px = (int) Math.floor(cx + Math.cos(a) * r), py = (int) Math.floor(cy + Math.sin(a) * r);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}

	/** Part of a circle, clockwise from 12 o'clock over {@code frac} of a turn. */
	static void arc(GuiGraphics g, float cx, float cy, float r, float frac, int color) {
		int steps = Math.max(16, Math.round(r * 6.3f));
		int n = Math.round(steps * Mth.clamp(frac, 0f, 1f));
		for (int k = 0; k < n; k++) {
			double a = -Math.PI / 2 + k * Math.PI * 2 / steps;
			int px = (int) Math.floor(cx + Math.cos(a) * r), py = (int) Math.floor(cy + Math.sin(a) * r);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}

	/** Text centred on {@code x} at {@code scale}, its top at {@code y}. */
	static void small(GuiGraphics g, Font font, String text, float x, float y, float scale, int color) {
		g.pose().pushPose();
		g.pose().translate(x, y, 0);
		g.pose().scale(scale, scale, 1);
		g.drawString(font, text, -font.width(text) / 2, 0, color, true);
		g.pose().popPose();
	}
}
