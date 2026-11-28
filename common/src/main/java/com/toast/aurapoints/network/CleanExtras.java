package com.toast.aurapoints.network;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.client.ClientCooldowns;
import com.toast.aurapoints.client.HudColors;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * CLIENT ONLY. The Clean HUD's extras: the chain glow on the slot that chains next (FxPacket.CHAIN), the hit streak
 * counter (FxPacket.DAMAGE), the buff ribbon over the slots, the danger cue, the aura's name for a moment after it
 * changes, and the charge ring round the crosshair (charge bar, charged casts, the Mage orb).
 */
public final class CleanExtras {

	private CleanExtras() {
	}

	private static final int GOLD = 0xFFC83D;
	private static final long STREAK_MILLIS = 3500;

	// the chain: which abilities chain next, until when; when a chain fired and how long its empower lasts
	private static final Set<Ability> CHAIN_NEXT = EnumSet.noneOf(Ability.class);
	private static long chainFrom = 0, chainUntil = 0, chainHitAt = 0, empowerFrom = 0, empowerUntil = 0;
	// the streak
	private static int streak = 0, shattered = 0;
	private static long lastHit = 0, streakPop = 0, shatterAt = 0;
	// stunned until (FxPacket.STUNNED)
	private static long stunnedFrom = 0, stunnedUntil = 0;
	// the charge ring: when each key went down, the last charge, when the ring was let go
	private static final long[] KEY_DOWN = new long[ClientKeyHandler.KEYS.length];
	private static float lastCharge = 0;
	private static long releaseAt = 0, fullSince = 0;

	static void clear() {
		CHAIN_NEXT.clear();
		chainUntil = empowerUntil = stunnedUntil = 0;
		streak = 0;
		shatterAt = 0;
		lastCharge = 0;
	}

	// ---------------------------------------------------------------
	// Packets (ScreenFx)
	// ---------------------------------------------------------------

	/** FxPacket.CHAIN: value = ticks the window stays open, text = "next abilities,comma separated;empower ticks;the ability that fired it". */
	public static void onChain(int windowTicks, String text) {
		long now = System.currentTimeMillis();
		String[] parts = text.split(";", -1);
		CHAIN_NEXT.clear();
		if (!parts[0].isEmpty()) for (String name : parts[0].split(",")) {
			try {
				CHAIN_NEXT.add(Ability.valueOf(name));
			} catch (IllegalArgumentException ignored) {
				// a newer server's ability: skip it
			}
		}
		chainFrom = now;
		chainUntil = now + windowTicks * 50L;
		int empower = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 0;
		if (empower > 0) {
			chainHitAt = now;
			lastChained = null;
			if (parts.length > 2 && !parts[2].isEmpty()) {
				try {
					lastChained = Ability.valueOf(parts[2]);
				} catch (IllegalArgumentException ignored) {
					// unknown here
				}
			}
			empowerFrom = now;
			empowerUntil = now + empower * 50L;
		}
	}

	/** FxPacket.DAMAGE: one of your hits; a gap longer than STREAK_MILLIS ends the streak (it shatters). */
	public static void onHit() {
		long now = System.currentTimeMillis();
		if (now - lastHit > STREAK_MILLIS) {
			if (streak >= 2) shatter(now);
			streak = 0;
		}
		streak++;
		lastHit = now;
		streakPop = now;
	}

	public static void onStunned(int ticks) {
		long now = System.currentTimeMillis();
		stunnedFrom = now;
		stunnedUntil = now + ticks * 50L;
	}

	private static void shatter(long now) {
		shattered = streak;
		shatterAt = now;
	}

	// ---------------------------------------------------------------
	// On the panel
	// ---------------------------------------------------------------

	/** Over the main panel (panel space, unscaled): the aura's name for 2 s after a change, the buff ribbon, the streak. */
	static void panelTop(GuiGraphics g, Font font, AuraType aura, int rgb, int width, long now, long auraSince) {
		boolean calm = CleanHud.calm();
		long since = now - auraSince;
		if (since >= 0 && since < 2400) {
			float a = since < 300 ? since / 300f : since > 1800 ? 1 - (since - 1800) / 600f : 1f;
			CleanHud.small(g, font, aura.displayName, width / 2f, -17, 0.75f, HudColors.argb(Math.max(0.05f, a), HudColors.mix(rgb, 0xFFFFFF, 0.25f)));
		}
		// the ribbon: our own timed states, each in a little ring that empties with its time
		List<Buff> buffs = buffs(now);
		for (int k = 0; k < buffs.size(); k++) {
			Buff b = buffs.get(k);
			float left = Mth.clamp((b.until - now) / (float) Math.max(1, b.until - b.from), 0f, 1f);
			float pop = calm ? 1f : 1f + 0.4f * Math.max(0, 1 - (now - b.from) / 250f);
			g.pose().pushPose();
			g.pose().translate(CleanHud.PAD + 3 + k * 12, -7, 0);
			g.pose().scale(pop, pop, 1);
			CleanHud.round(g, -5, -5, 10, 10, 0x990A0A14);
			CleanHud.arc(g, -0.5f, -0.5f, 4.5f, left, HudColors.argb(0.95f, b.rgb));
			CleanHud.small(g, font, b.label, 0, -2, 0.5f, 0xFF000000 | b.rgb);
			g.pose().popPose();
		}
		// the streak, top right; it shatters when it ends
		if (streak >= 2 && now - lastHit > STREAK_MILLIS) {
			shatter(now);
			streak = 0;
		}
		if (streak >= 2) {
			String text = "×" + streak;
			float p = calm ? 1f : 1f + 0.5f * Math.max(0, 1 - (now - streakPop) / 180f);
			g.pose().pushPose();
			g.pose().translate(width - 2, -8, 0);
			g.pose().scale(p, p, 1);
			g.drawString(font, text, -font.width(text), -4, 0xFF000000 | HudColors.mix(GOLD, 0xFFFFFF, 0.3f), true);
			g.pose().popPose();
		}
		if (shatterAt > 0 && now - shatterAt < 600) {
			float k = (now - shatterAt) / 600f;
			if (calm) {
				String text = "×" + shattered;
				g.drawString(font, text, width - 2 - font.width(text), -12, HudColors.argb(Math.max(0.05f, 1 - k), GOLD), true);
			} else for (int i = 0; i < 9; i++) {
				double a = i / 9.0 * Math.PI * 2 + 0.3;
				float v = 14 + (i * 7) % 11;
				int px = (int) Math.floor(width - 6 + Math.cos(a) * v * k), py = (int) Math.floor(-8 + Math.sin(a) * v * k + 8 * k * k);
				g.fill(px, py, px + 2, py + 2, HudColors.argb(1 - k, GOLD));
			}
		}
	}

	private record Buff(String label, int rgb, long from, long until) {
	}

	private static long frozenFrom = 0, invisFrom = 0;

	private static List<Buff> buffs(long now) {
		List<Buff> out = new ArrayList<>(4);
		if (now < empowerUntil) out.add(new Buff("C", GOLD, empowerFrom, empowerUntil));
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && ClientAuraState.aura == AuraType.SHADOW) {
			var inv = mc.player.getEffect(MobEffects.INVISIBILITY);
			if (inv != null && !inv.isInfiniteDuration()) {
				if (invisFrom == 0) invisFrom = now;
				out.add(new Buff("V", 0xB07BFF, invisFrom, now + inv.getDuration() * 50L));
			} else invisFrom = 0;
		}
		if (ClientAuraState.isFrozen()) {
			if (frozenFrom == 0) frozenFrom = now;
			out.add(new Buff("F", 0xA8E4FF, frozenFrom, Math.max(now + 1, ClientAuraState.frozenUntil)));
		} else frozenFrom = 0;
		if (now < stunnedUntil) out.add(new Buff("S", 0xFFE066, stunnedFrom, stunnedUntil));
		return out;
	}

	/** 0 = fine; else how strongly the slots tint red right now (low health, stunned, frozen). */
	static float danger(Minecraft mc, long now) {
		if (mc.player == null) return 0f;
		boolean low = mc.player.getHealth() < mc.player.getMaxHealth() * 0.3f;
		boolean held = now < stunnedUntil || ClientAuraState.isFrozen();
		if (!low && !held) return 0f;
		return CleanHud.calm() ? 0.6f : 0.45f + 0.35f * Mth.sin(now / 140f);
	}

	/** In slot space: the gold edge, a countdown running round it and a little arrow on a slot that chains next. */
	static void chainMark(GuiGraphics g, Ability ability, long now) {
		if (now >= chainUntil || !CHAIN_NEXT.contains(ability)) {
			// the chain fired: a gold ring bursts off every slot that was waiting for it
			if (chainHitAt > 0 && now - chainHitAt < 400 && !CleanHud.calm() && ability == lastChained) {
				float k = (now - chainHitAt) / 400f;
				CleanHud.ring(g, 0, 0, 12 + 10 * (1 - (1 - k) * (1 - k)), HudColors.argb(1 - k, GOLD));
			}
			return;
		}
		boolean calm = CleanHud.calm();
		float left = (chainUntil - now) / (float) Math.max(1, chainUntil - chainFrom);
		// the countdown: the edge's pixels from 12 o'clock clockwise, as many as there is time left
		int per = 4 * 22, lit = Math.round(per * left);
		int col = HudColors.argb(0.95f, GOLD);
		for (int p = 0; p < lit; p++) {
			int q = (p + 11) % per, side = q / 22, o = q % 22;
			int x = switch (side) { case 0 -> -12 + o; case 1 -> 11; case 2 -> 11 - o; default -> -12; };
			int y = switch (side) { case 0 -> -12; case 1 -> -12 + o; case 2 -> 11; default -> 11 - o; };
			g.fill(x, y, x + 1, y + 1, col);
		}
		if (!calm) CleanHud.roundOutline(g, -11, -11, 22, 22, HudColors.argb(0.4f + 0.2f * Mth.sin(now / 80f), GOLD));
		int bob = calm ? 0 : Math.round(Mth.sin(now / 125f) * 1.2f);
		// a small arrow pointing down at the slot
		for (int r = 0; r < 3; r++) g.fill(-2 + r, -19 + r + bob, 3 - r, -18 + r + bob, col);
	}

	private static Ability lastChained = null;

	// ---------------------------------------------------------------
	// The crosshair pips (Clean): thin arc segments under the crosshair
	// ---------------------------------------------------------------

	/**
	 * One short curved segment per ability on an arc under the crosshair (outside the charge ring): a dim track that
	 * fills along its length while cooling down (brighter in the last 20 %), a full bright segment when ready that
	 * flashes white and pops out a pixel as it comes back. The Ultimate is gold; a Mage spell short of mana fills blue.
	 */
	static void pips(GuiGraphics g, List<Ability> abilities, boolean flying, int rgb, long now) {
		boolean calm = CleanHud.calm();
		float cx = g.guiWidth() / 2f, cy = g.guiHeight() / 2f;
		int n = abilities.size();
		double span = Math.toRadians(18), gap = Math.toRadians(6);
		double total = n * span + (n - 1) * gap, start = Math.PI / 2 + total / 2;
		for (int i = 0; i < n; i++) {
			Ability a = abilities.get(i);
			boolean flight = flying && ClientAuraState.hasFlightVersion(a);
			boolean ult = com.toast.aurapoints.ability.Ultimates.auraOf(a) != null;
			int col = ult ? GOLD : rgb;
			long[] cd = ClientAuraState.cooldown(a, flight ? com.toast.aurapoints.ability.AuraAbilities.Slot.FLIGHT : null);
			boolean cooling = cd != null && now < cd[1];
			float fill = a == Ability.BLOOD_MOON ? ClientAuraState.bloodMoonFill() : 1f;
			int cost = ClientAuraState.mana >= 0 ? ClientAuraState.manaCost(a) : 0;
			if (cost > 0) fill = Math.min(1f, ClientAuraState.mana / cost);
			// the segment runs right to left under the crosshair (slot 1 on the left, as on the HUD)
			double from = start - i * (span + gap), to = from - span;
			float r = 13f;
			if (cooling || fill < 1f) {
				float done = cooling ? 1f - (cd[1] - now) / (float) Math.max(1, cd[1] - cd[0]) : fill;
				float ramp = cooling && done > 0.8f ? (done - 0.8f) / 0.2f : 0f;
				int lit = cooling ? HudColors.argb(0.5f + 0.4f * ramp, HudColors.mix(col, 0xFFFFFF, 0.2f + 0.4f * ramp))
						: HudColors.argb(0.7f, cost > 0 ? 0x5A78FF : 0xC01020);
				segment(g, cx, cy, r, from, to, 1f, 0x40A0A0B0);
				segment(g, cx, cy, r, from, to, done, lit);
			} else {
				Long readyAt = ClientCooldowns.readyAt(a, flight);
				float flare = readyAt == null || calm ? 0f : Math.max(0f, 1f - (now - readyAt) / 450f);
				float pop = flare > 0 ? (float) Math.sin(flare * Math.PI) : 0f;
				int c = HudColors.argb(0.95f, HudColors.mix(col, 0xFFFFFF, flare * 0.8f));
				segment(g, cx, cy, r + pop, from, to, 1f, c);
				if (flare > 0.3f) segment(g, cx, cy, r + 1 + pop, from, to, 1f, HudColors.argb(flare * 0.6f, 0xFFFFFF));
			}
		}
	}

	/** Pixels along an arc of radius {@code r} from angle {@code from} toward {@code to}, the first {@code frac} of it. */
	private static void segment(GuiGraphics g, float cx, float cy, float r, double from, double to, float frac, int color) {
		if (frac <= 0) return;
		double len = Math.abs(to - from) * r;
		int steps = Math.max(2, (int) Math.ceil(len * 2));
		int n = Math.round(steps * Math.min(1f, frac));
		int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
		for (int k = 0; k <= n && k <= steps; k++) {
			double a = from + (to - from) * k / steps;
			int px = (int) Math.floor(cx + Math.cos(a) * r), py = (int) Math.floor(cy + Math.sin(a) * r);
			if (px == lastX && py == lastY) continue;
			lastX = px;
			lastY = py;
			g.fill(px, py, px + 1, py + 1, color);
		}
	}

	// ---------------------------------------------------------------
	// The charge ring round the crosshair
	// ---------------------------------------------------------------

	/** GUI layer above the crosshair. Clean style only. */
	static void renderCross(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!AuraClientConfig.clean() || mc.player == null || mc.options.hideGui || mc.player.isSpectator()
				|| !com.toast.aurapoints.client.CrosshairState.shown(delta)) return;
		long now = System.currentTimeMillis();
		boolean calm = CleanHud.calm();
		float charge = charge(mc, now);
		float cx = g.guiWidth() / 2f, cy = g.guiHeight() / 2f;
		if (charge > 0) {
			boolean full = charge >= 0.999f;
			if (full && fullSince == 0) fullSince = now;
			if (!full) fullSince = 0;
			int rgb = ClientAuraState.aura == null ? 0xFFFFFF : HudColors.of(ClientAuraState.aura);
			CleanHud.ring(g, cx, cy, 10, 0x2EFFFFFF);
			CleanHud.arc(g, cx, cy, 10, charge, full ? 0xF2FFFFFF : HudColors.argb(0.95f, HudColors.mix(rgb, 0xFFFFFF, 0.35f)));
			// the ready notch of the charge bar (Fire Bending & co): where it can be let go
			if (ClientAuraState.fireBending && ClientAuraState.fireReadyAt > 0 && ClientAuraState.fireReadyAt < 1) {
				double a = -Math.PI / 2 + ClientAuraState.fireReadyAt * Math.PI * 2;
				int px = (int) Math.floor(cx + Math.cos(a) * 12), py = (int) Math.floor(cy + Math.sin(a) * 12);
				g.fill(px, py, px + 1, py + 1, 0xFFFFFFFF);
			}
			if (full && !calm) {
				// it hums: a white ring ticks outward twice a second
				float tick = ((now - fullSince) % 500) / 500f;
				CleanHud.ring(g, cx, cy, 10 + 3 * tick, HudColors.argb(0.6f * (1 - tick), 0xFFFFFF));
			}
		} else if (lastCharge > 0.15f) {
			releaseAt = now;
		}
		lastCharge = charge;
		if (releaseAt > 0 && now - releaseAt < 350 && !calm) {
			float k = (now - releaseAt) / 350f;
			CleanHud.ring(g, cx, cy, 10 + 12 * (1 - (1 - k) * (1 - k) * (1 - k)), HudColors.argb(1 - k, 0xFFFFFF));
		}
	}

	/** 0..1 while something charges: the charge bar (Fire/Wind/Frost/Thunder holds), a charged cast, the Mage orb. */
	private static float charge(Minecraft mc, long now) {
		if (ClientAuraState.fireBending) return Mth.clamp(ClientAuraState.fireCharge, 0f, 1f);
		AuraType aura = ClientAuraState.aura;
		if (aura == null || mc.screen != null) {
			java.util.Arrays.fill(KEY_DOWN, 0);
			return 0;
		}
		float best = 0;
		int mastery = ClientAuraState.playerMastery(mc.player.getId());
		for (int i = 0; i < AuraType.KEY_SLOTS; i++) {
			boolean down = ClientKeyHandler.KEYS[i].isDown();
			if (!down) {
				KEY_DOWN[i] = 0;
				continue;
			}
			if (KEY_DOWN[i] == 0) KEY_DOWN[i] = now;
			Ability a = aura.slot(i, ClientAuraState.branch);
			if (a == null || !ClientAuraState.isOwned(a)) continue;
			long[] cd = ClientAuraState.cooldown(a, null);
			if (cd != null && now < cd[1] && a != Ability.ARCANE_ORB) continue;
			long held = now - KEY_DOWN[i];
			if (a == Ability.ARCANE_ORB) {
				// the orb only grows in a staff's hand
				if (!(mc.player.getMainHandItem().getItem() instanceof com.toast.aurapoints.item.ApprenticeStaffItem)
						&& !(mc.player.getOffhandItem().getItem() instanceof com.toast.aurapoints.item.ApprenticeStaffItem)) continue;
				best = Math.max(best, Mth.clamp(held / (AuraConfig.MAGE_ORB_MAX_HOLD_TICKS * 50f), 0f, 1f));
			} else if (AuraConfig.CAST_VARIANTS_ENABLED && CastMode.has(a, CastMode.CHARGED) && mastery >= AuraConfig.CAST_CHARGED_MASTERY
					&& !mc.player.isShiftKeyDown() && held > 120) {
				// a tap stays a tap: the ring only shows once the press is clearly a hold
				best = Math.max(best, Mth.clamp(held / (AuraConfig.CAST_CHARGE_TICKS * 50f), 0f, 1f));
			}
		}
		return best;
	}
}
