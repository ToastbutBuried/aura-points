package com.toast.aurapoints.network;

import com.mojang.blaze3d.systems.RenderSystem;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.client.ClientCooldowns;
import com.toast.aurapoints.client.HudColors;
import com.toast.aurapoints.client.HudLayout;
import com.toast.aurapoints.client.HudParticles;
import com.toast.aurapoints.config.AuraClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientPlayerNetworkEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterGuiLayersEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RenderPlayerEvent;
import com.toast.aurapoints.shim.neoforge.client.gui.VanillaGuiLayers;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * CLIENT ONLY. The cooldown panel (left of the hotbar by default; anchor, offset, size, opacity and
 * color in the client config, or dragged around with /aurahud): one slot per active ability you own,
 * framed in the aura's color, with the aura's own little effects running along the frame.
 *  - cooling down: a dark shade that sweeps away clockwise, a bright edge leading it, the time left;
 *  - ready: a softly pulsing border; the moment it comes off cooldown the slot pops, flashes white and
 *    a ring bursts out of it (the chime comes from ClientCooldowns, which also times all of this);
 *  - pressed: the slot punches in; pressed too early: it shakes and flashes red.
 * The panel slides in when you join or your aura changes. The keybind is shown small in each corner.
 *
 * Also the charge bar (Fire Bending and friends) and the cooldown pips around the crosshair.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class ClientAuraHud {

	private ClientAuraHud() {
	}

	private static final int SLOT = 22;
	private static final int GAP = 3;
	private static final int PAD = 4;
	private static final long FLASH_MILLIS = 450;
	private static final long POP_MILLIS = 320;
	private static final long PUNCH_MILLIS = 180;
	private static final long DENY_MILLIS = 320;
	private static final long SLIDE_MILLIS = 450;

	/** The Ultimate's slot: framed in gold. */
	private static final int ULTIMATE_RGB = 0xFFC83D;

	/** Flight-set slots get a sky-blue frame while gliding. */
	private static final int FLIGHT_RGB = 0x9FE3FF;

	/** The frame effects (panel coordinates) and the charge bar's sparks (screen coordinates). */
	private static final HudParticles FRAME_FX = new HudParticles();
	private static final HudParticles BAR_FX = new HudParticles();
	/** The ready moment each slot's ring burst was already spawned for. */
	private static final Map<Ability, Long> RING_DONE = new EnumMap<>(Ability.class);
	/** The panel slides in when the aura changes. */
	private static AuraType slidAura = null;
	private static long slideSince = 0;

	@SubscribeEvent
	public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAbove(VanillaGuiLayers.HOTBAR,
				ResourceLocation.fromNamespaceAndPath("aurapoints", "ability_cooldowns"), ClientAuraHud::render);
		event.registerAbove(VanillaGuiLayers.HOTBAR,
				ResourceLocation.fromNamespaceAndPath("aurapoints", "fire_bending_charge"), ClientAuraHud::renderFireCharge);
		event.registerAbove(VanillaGuiLayers.CROSSHAIR,
				ResourceLocation.fromNamespaceAndPath("aurapoints", "bubble_stability"), ClientAuraHud::renderBubbleStability);
		event.registerAbove(VanillaGuiLayers.CROSSHAIR,
				ResourceLocation.fromNamespaceAndPath("aurapoints", "crosshair_pips"), ClientAuraHud::renderPips);
	}

	@SubscribeEvent
	public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
		ClientAuraState.resetAll();
		com.toast.aurapoints.client.AirChargeSmoothing.clear();
		ClientCooldowns.clear();
		FRAME_FX.clear();
		BAR_FX.clear();
		RING_DONE.clear();
		slidAura = null;
	}

	/**
	 * Shadow cloak: a cloaked player who is invisible isn't drawn at all - no armor,
	 * held items, arrows or name tag. You still see yourself unless /aura selfhide is on.
	 */
	@SubscribeEvent
	public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
		var target = event.getEntity();
		if (target == Minecraft.getInstance().player) {
			// Yourself (F5): only with /aura selfhide on.
			if (ClientAuraState.selfHide && ClientAuraState.aura == AuraType.SHADOW && target.isInvisible()) event.setCanceled(true);
			return;
		}
		if (!ClientAuraState.isCloaked(target.getId())) return;
		// Only while really invisible, so the cloak can't outlast the effect. The server alone turns it off:
		// its packet can arrive a tick before the invisible flag, so this side must not clear it.
		if (target.isInvisible()) event.setCanceled(true);
	}

	public static List<Ability> shownAbilities(AuraType aura) {
		List<Ability> out = new ArrayList<>();
		for (Ability a : aura.abilities) {
			// Ice Shell is a passive, but it has a rebuild timer worth showing
			if (ICON_ORDER.contains(a) && !ClientAuraState.isDisabled(a) && (ClientAuraState.isOwned(a) || a == Ability.ICE_SHELL)) out.add(a);
		}
		// an awakened aura's Ultimate (the server lists it with the owned abilities)
		Ability ult = com.toast.aurapoints.ability.Ultimates.of(aura);
		if (ult != null && ClientAuraState.isOwned(ult) && !ClientAuraState.isDisabled(ult)) out.add(ult);
		return out;
	}

	/** The ability of the handheld weapon in your hand, if it is your aura's (it gets its own panel right of the hotbar). */
	private static Ability heldAbility(Minecraft mc, AuraType aura) {
		if (!com.toast.aurapoints.AuraCommand.isOwner(mc.player)) return null;
		Ability held = com.toast.aurapoints.ability.Handhelds.held(mc.player);
		return held != null && com.toast.aurapoints.ability.Handhelds.auraOf(held) == aura && !ClientAuraState.isDisabled(held) ? held : null;
	}

	private static boolean calm() {
		return AuraClientConfig.calm();
	}

	private static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		AuraType aura = ClientAuraState.aura;
		if (aura == null || mc.player == null || mc.options.hideGui || mc.player.isSpectator()) {
			if (aura == null) slidAura = null;
			return;
		}
		long now = System.currentTimeMillis();
		int rgb = HudColors.of(aura);
		if (aura != slidAura) {
			slidAura = aura;
			slideSince = now;
			FRAME_FX.clear();
		}
		float slide = calm() ? 1f : Mth.clamp((now - slideSince) / (float) SLIDE_MILLIS, 0f, 1f);
		float alpha = (float) (double) AuraClientConfig.HUD_OPACITY.get() * Math.min(1f, slide * 1.6f);
		boolean fromTop = AuraClientConfig.HUD_ANCHOR.get().name().startsWith("TOP");
		// ease out with a little overshoot, from just off the nearest edge
		float back = 1f - easeOutBack(slide);
		float slideY = back * 36f * (fromTop ? -1 : 1);

		RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
		try {
			int mainRight = -1, mainTop = 0;
			List<Ability> abilities = shownAbilities(aura);
			if (!abilities.isEmpty()) {
				// Gliding with an elytra: Dash and Double Jump show their flight set and its cooldowns.
				boolean flying = mc.player.isFallFlying() && abilities.stream().anyMatch(ClientAuraState::hasFlightVersion);
				String label = "✦ " + aura.displayName + (flying ? " · Flight" : "") + " ✦";
				int w = width(mc, label, abilities.size()), h = height();
				int[] at = HudLayout.panel(mc, g.guiWidth(), g.guiHeight(), w, h);
				panel(g, mc, aura, label, abilities, flying, rgb, at[0], at[1] + slideY, w, now, alpha, true);
				mainRight = at[0] + Math.round(w * HudLayout.scale());
				mainTop = at[1];
			}

			// the handheld weapon: its own panel right of the hotbar (past the offhand slot or the attack indicator),
			// or right beside the main panel when that one was moved
			Ability held = heldAbility(mc, aura);
			if (held != null) {
				String label = "⚔ " + mc.player.getMainHandItem().getHoverName().getString();
				int w = width(mc, label, 1), h = height();
				int x, y;
				if (AuraClientConfig.HUD_ANCHOR.get() == AuraClientConfig.Anchor.HOTBAR && AuraClientConfig.HUD_OFFSET_X.get() == 0
						&& AuraClientConfig.HUD_OFFSET_Y.get() == 0) {
					boolean offhandLeft = mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
					x = g.guiWidth() / 2 + 91 + 6;
					if (!offhandLeft) x += 29;
					else if (mc.options.attackIndicator().get() == net.minecraft.client.AttackIndicatorStatus.HOTBAR) x += 24;
					y = g.guiHeight() - 1 - Math.round(h * HudLayout.scale());
				} else if (mainRight >= 0) {
					x = mainRight + 6;
					y = mainTop;
				} else {
					int[] at = HudLayout.panel(mc, g.guiWidth(), g.guiHeight(), w, h);
					x = at[0];
					y = at[1];
				}
				int[] at = HudLayout.clampPanel(g.guiWidth(), g.guiHeight(), w, h, x, y);
				panel(g, mc, aura, label, List.of(held), false, rgb, at[0], at[1] + slideY, w, now, alpha, false);
			}
		} finally {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	private static float easeOutBack(float t) {
		float c1 = 1.70158f, c3 = c1 + 1;
		float u = t - 1;
		return 1 + c3 * u * u * u + c1 * u * u;
	}

	public static int width(Minecraft mc, String label, int slots) {
		int slotsWidth = slots * SLOT + (slots - 1) * GAP;
		int labelWidth = AuraClientConfig.HUD_LABEL.get() ? (int) Math.ceil(mc.font.width(label) * 0.75f) + 12 : 0;
		return Math.max(PAD * 2 + slotsWidth, labelWidth);
	}

	public static int height() {
		return PAD * 2 + SLOT;
	}

	/**
	 * One panel of ability slots, its body's top-left at ({@code x}, {@code y}) on screen, {@code w} wide
	 * before scaling. Drawn in its own coordinates, scaled about that corner.
	 */
	private static void panel(GuiGraphics g, Minecraft mc, AuraType aura, String label, List<Ability> abilities, boolean flying, int rgb,
							  float x, float y, int width, long now, float alpha, boolean frameFx) {
		Font font = mc.font;
		float s = HudLayout.scale();
		int slotsWidth = abilities.size() * SLOT + (abilities.size() - 1) * GAP;
		int slotsLeftOffset = (width - slotsWidth) / 2;
		int height = height();

		g.pose().pushPose();
		g.pose().translate(x, y, 0);
		g.pose().scale(s, s, 1);
		drawFrame(g, 0, 0, width, height, rgb);

		// the aura's effects along the frame (main panel only, so a handheld's panel stays plain)
		if (frameFx && AuraClientConfig.HUD_EFFECTS.get() && !calm()) {
			float rate = aura == AuraType.FIRE ? 9f : aura == AuraType.THUNDER ? 4f : aura == AuraType.WIND ? 3f : 5f;
			FRAME_FX.frame(aura, 0, 0, width, height, rgb, rate, now);
		}
		if (frameFx) FRAME_FX.draw(g, now, alpha);

		// Label above, in the aura color with a shine running through it now and then, slightly smaller than normal text.
		if (AuraClientConfig.HUD_LABEL.get()) {
			g.pose().pushPose();
			g.pose().translate(width / 2.0f, -8, 0);
			g.pose().scale(0.75f, 0.75f, 1);
			shimmerText(g, font, label, -font.width(label) / 2, 0, rgb, now);
			g.pose().popPose();
		}

		for (int i = 0; i < abilities.size(); i++) {
			Ability ability = abilities.get(i);
			int sx = slotsLeftOffset + i * (SLOT + GAP);
			int sy = PAD;
			boolean flight = flying && ClientAuraState.hasFlightVersion(ability);
			int slotRgb = flight ? FLIGHT_RGB : com.toast.aurapoints.ability.Ultimates.auraOf(ability) != null ? ULTIMATE_RGB : rgb;
			drawSlot(g, mc, font, aura, ability, flight, sx, sy, slotRgb, now, frameFx);
		}
		g.pose().popPose();
	}

	/** The panel body: dark gradient, aura-colored frame, a bright accent line and little corner gems. */
	public static void drawFrame(GuiGraphics g, int left, int top, int width, int height, int rgb) {
		int right = left + width, bottom = top + height;
		g.fillGradient(left, top, right, bottom, HudColors.panelTop(), HudColors.panelBottom());
		g.renderOutline(left, top, width, height, 0xB0000000 | rgb);
		g.fill(left + 2, top + 1, right - 2, top + 2, 0xFF000000 | rgb);
		g.fillGradient(left + 1, top + 2, right - 1, top + 6, 0x40000000 | rgb, 0x00000000 | rgb);
		for (int[] c : new int[][]{{left - 1, top - 1}, {right - 2, top - 1}, {left - 1, bottom - 2}, {right - 2, bottom - 2}}) {
			g.fill(c[0], c[1], c[0] + 3, c[1] + 3, 0xFF000000 | rgb);
			g.fill(c[0] + 1, c[1] + 1, c[0] + 2, c[1] + 2, 0xFFFFFFFF);
		}
	}

	/** Text in one color with a white shine sweeping across it every few seconds (reduced motion: plain). */
	private static void shimmerText(GuiGraphics g, Font font, String text, int x, int y, int rgb, long now) {
		if (calm()) {
			g.drawString(font, text, x, y, 0xFF000000 | rgb, true);
			return;
		}
		int total = font.width(text);
		float band = ((now % 3800) / 3800f) * (total + 60) - 30;
		int cx = x;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			String ch = new String(Character.toChars(cp));
			int cw = font.width(ch);
			float d = Math.abs(cx - x + cw / 2f - band);
			float t = Math.max(0f, 1f - d / 14f);
			g.drawString(font, ch, cx, y, 0xFF000000 | HudColors.mix(rgb, 0xFFFFFF, t * 0.85f), true);
			cx += cw;
			i += Character.charCount(cp);
		}
	}

	private static void drawSlot(GuiGraphics g, Minecraft mc, Font font, AuraType aura, Ability ability, boolean flight, int sx, int sy, int rgb,
								 long now, boolean rings) {
		AuraAbilities.Slot slot = flight ? AuraAbilities.Slot.FLIGHT : null;
		long[] cd = ability == Ability.ICE_SHELL ? null : ClientAuraState.cooldown(ability, slot);
		boolean cooling = cd != null && now < cd[1];
		// Blood Moon also waits on the blood bar: until it is full the slot isn't ready (no pop, ring or glint)
		float blood = ability == Ability.BLOOD_MOON ? ClientAuraState.bloodMoonFill() : 1f;
		boolean thirsty = blood < 1f;

		// how the slot moves: the ready pop, the press punch, the too-early shake
		float scale = 1f, shakeX = 0f;
		float deniedFlash = 0f;
		Long readyAt = ability == Ability.ICE_SHELL ? (Long) ClientCooldowns.shellWholeAt() : ClientCooldowns.readyAt(ability, flight);
		if (thirsty) readyAt = null;
		if (!calm()) {
			if (readyAt != null && now - readyAt < POP_MILLIS && !cooling) {
				float t = (now - readyAt) / (float) POP_MILLIS;
				scale += 0.2f * Mth.sin(t * Mth.PI) * (1 - t * 0.3f);
			}
			Long pressed = ClientCooldowns.pressedAt(ability);
			if (pressed != null && now - pressed < PUNCH_MILLIS) {
				float t = (now - pressed) / (float) PUNCH_MILLIS;
				scale -= 0.14f * Mth.sin(t * Mth.PI);
			}
		}
		Long denied = ClientCooldowns.deniedAt(ability);
		if (denied != null && now - denied < DENY_MILLIS) {
			float t = (now - denied) / (float) DENY_MILLIS;
			if (!calm()) shakeX = Mth.sin(t * 38f) * 2.2f * (1 - t);
			deniedFlash = 1 - t;
		}
		// the ring bursting out of a slot that just came off cooldown (once per cooldown)
		if (rings && readyAt != null && !readyAt.equals(RING_DONE.get(ability)) && now - readyAt < 200 && !calm()) {
			RING_DONE.put(ability, readyAt);
			FRAME_FX.ring(sx + SLOT / 2f, sy + SLOT / 2f, SLOT * 0.9f, rgb);
		}

		g.pose().pushPose();
		g.pose().translate(sx + SLOT / 2f + shakeX, sy + SLOT / 2f, 0);
		g.pose().scale(scale, scale, 1);
		g.pose().translate(-SLOT / 2f, -SLOT / 2f, 0);
		int x0 = 0, y0 = 0;

		// Slot frame
		g.fill(x0, y0, x0 + SLOT, y0 + SLOT, 0xFF2C2C38);
		g.fill(x0 + 1, y0 + 1, x0 + SLOT - 1, y0 + SLOT - 1, 0xFF101016);
		g.renderItem(com.toast.aurapoints.ability.Handhelds.auraOf(ability) != null ? mc.player.getMainHandItem() : icon(ability), x0 + 3, y0 + 3);
		if (ability == Ability.ICE_SHELL) {
			drawIceShell(g, font, x0, y0, rgb, now);
			g.pose().popPose();
			return;
		}

		g.pose().pushPose();
		g.pose().translate(0, 0, 200); // above the item icon
		if (thirsty) {
			// the blood bar: crimson rising from the bottom of the slot
			int inner = SLOT - 2, h = Math.round(inner * blood);
			if (h > 0) g.fill(x0 + 1, y0 + 1 + inner - h, x0 + SLOT - 1, y0 + SLOT - 1, 0x908B0A14);
			if (h > 0 && h < inner) g.fill(x0 + 1, y0 + 1 + inner - h, x0 + SLOT - 1, y0 + 2 + inner - h, 0xD0E0202E);
		}
		if (cooling) {
			float frac = (cd[1] - now) / (float) Math.max(1, cd[1] - cd[0]);
			// the shade sweeps away clockwise from the top as the cooldown runs out, a bright edge leading it
			sweep(g, x0 + 1, y0 + 1, SLOT - 2, frac, 0xB4000000, 0xE0000000 | HudColors.mix(rgb, 0xFFFFFF, 0.35f));
			float secs = (cd[1] - now) / 1000f;
			String text = switch (AuraClientConfig.COOLDOWN_TEXT.get()) {
				case DECIMAL -> secs >= 10 ? String.valueOf((int) Math.ceil(secs)) : String.format("%.1f", secs);
				case SECONDS -> String.valueOf((int) Math.ceil(secs));
				case OFF -> "";
			};
			if (!text.isEmpty()) {
				// the last seconds count down in warm yellow, and the last one breathes
				int color = secs < 3 ? 0xFFFFE066 : 0xFFFFFFFF;
				float tscale = secs < 1 && !calm() ? 1f + 0.12f * Mth.sin(now / 60f) : 1f;
				g.pose().pushPose();
				g.pose().translate(x0 + SLOT / 2f, y0 + SLOT / 2f - 0.5f, 0);
				g.pose().scale(tscale, tscale, 1);
				g.drawString(font, text, -font.width(text) / 2, -4, color, true);
				g.pose().popPose();
			}
			g.renderOutline(x0, y0, SLOT, SLOT, 0xFF3A3A48);
		} else if (thirsty) {
			// off cooldown but not enough blood: a dim frame and how full the bar is
			g.renderOutline(x0, y0, SLOT, SLOT, 0xFF4A2A30);
			String text = Math.round(blood * 100) + "%";
			g.pose().pushPose();
			g.pose().translate(x0 + SLOT / 2f, y0 + SLOT / 2f, 0);
			g.pose().scale(0.6f, 0.6f, 1);
			g.drawString(font, text, -font.width(text) / 2, -4, 0xFFFF9090, true);
			g.pose().popPose();
		} else {
			// Ready: a pulsing border in the aura color, plus a fading white flash right after the cooldown ends.
			double pulse = calm() ? 0.85 : 0.6 + 0.4 * Math.sin(now / 260.0);
			int alpha = (int) (pulse * 255) << 24;
			g.renderOutline(x0, y0, SLOT, SLOT, alpha | rgb);
			g.renderOutline(x0 - 1, y0 - 1, SLOT + 2, SLOT + 2, ((int) (pulse * 90) << 24) | rgb);
			// a ready Ultimate: a steady gold edge, and a glint running round the frame
			if (com.toast.aurapoints.ability.Ultimates.auraOf(ability) != null) {
				g.renderOutline(x0 + 1, y0 + 1, SLOT - 2, SLOT - 2, 0x70000000 | rgb);
				if (!calm()) {
					int edge = SLOT - 1, run = (int) ((now / 30) % (edge * 4));
					for (int k = 0; k < 4; k++) {
						int p = Math.floorMod(run - k, edge * 4), side = p / edge, o = p % edge;
						int gx = switch (side) { case 0 -> o; case 1 -> edge; case 2 -> edge - o; default -> 0; };
						int gy = switch (side) { case 0 -> 0; case 1 -> o; case 2 -> edge; default -> edge - o; };
						g.fill(x0 + gx, y0 + gy, x0 + gx + 1, y0 + gy + 1, ((255 - k * 55) << 24) | HudColors.mix(rgb, 0xFFFFFF, 0.6f));
					}
				}
			}
			if (readyAt != null && now - readyAt < FLASH_MILLIS) {
				int a = (int) (170 * (1 - (now - readyAt) / (float) FLASH_MILLIS));
				g.fill(x0 + 1, y0 + 1, x0 + SLOT - 1, y0 + SLOT - 1, (a << 24) | 0xFFFFFF);
			}
			Long pressed = ClientCooldowns.pressedAt(ability);
			if (pressed != null && now - pressed < PUNCH_MILLIS) {
				int a = (int) (110 * (1 - (now - pressed) / (float) PUNCH_MILLIS));
				g.fill(x0 + 1, y0 + 1, x0 + SLOT - 1, y0 + SLOT - 1, (a << 24) | rgb);
			}
		}
		if (deniedFlash > 0) {
			int red = HudColors.denied();
			g.fill(x0 + 1, y0 + 1, x0 + SLOT - 1, y0 + SLOT - 1, HudColors.argb(0.45f * deniedFlash, red));
			g.renderOutline(x0, y0, SLOT, SLOT, HudColors.argb(deniedFlash, red));
		}

		// Wind launch (hold Dash) recharging on its own: a thin sky-blue bar along the bottom that shrinks to the left.
		if (!flight) {
			long[] launch = ClientAuraState.cooldown(ability, AuraAbilities.Slot.LAUNCH);
			if (launch != null && now < launch[1]) {
				float frac = (launch[1] - now) / (float) Math.max(1, launch[1] - launch[0]);
				int w = Math.max(1, Math.round((SLOT - 2) * frac));
				g.fill(x0 + 1, y0 + SLOT - 3, x0 + SLOT - 1, y0 + SLOT - 1, 0xC0000000);
				g.fill(x0 + 1, y0 + SLOT - 3, x0 + 1 + w, y0 + SLOT - 1, 0xFF000000 | FLIGHT_RGB);
			}
		}

		// Keybind, tiny, bottom-right corner.
		if (AuraClientConfig.HUD_KEYBINDS.get()) {
			String key = keyLabel(ability);
			boolean unbound = key == null;
			if (unbound) key = "?";
			g.pose().translate(x0 + SLOT - 1, y0 + SLOT - 1, 0);
			g.pose().scale(0.5f, 0.5f, 1);
			g.drawString(font, key, -font.width(key) - 1, -8, unbound ? 0xFFFF5555 : 0xFFDDDDDD, true);
		}
		g.pose().popPose();
		g.pose().popPose();
	}

	/**
	 * A square ({@code x}, {@code y}, {@code size}) shaded over {@code frac} of a turn: the shaded part
	 * runs clockwise from wherever the cleared part ends to 12 o'clock, so it sweeps away clockwise.
	 * Drawn as runs of whole pixels per row; the leading edge is a bright line from the middle out.
	 */
	public static void sweep(GuiGraphics g, int x, int y, int size, float frac, int shade, int edge) {
		if (frac <= 0) return;
		if (frac >= 0.999f) {
			g.fill(x, y, x + size, y + size, shade);
			return;
		}
		float cx = x + size / 2f, cy = y + size / 2f;
		double from = (1 - frac) * Math.PI * 2;
		for (int j = 0; j < size; j++) {
			float py = y + j + 0.5f;
			int run = -1;
			for (int i = 0; i <= size; i++) {
				boolean in = false;
				if (i < size) {
					float px = x + i + 0.5f;
					double a = Math.atan2(px - cx, -(py - cy));
					if (a < 0) a += Math.PI * 2;
					in = a >= from;
				}
				if (in && run < 0) run = i;
				else if (!in && run >= 0) {
					g.fill(x + run, y + j, x + i, y + j + 1, shade);
					run = -1;
				}
			}
		}
		// the leading edge
		float dx = (float) Math.sin(from), dy = (float) -Math.cos(from);
		float reach = size * 0.75f;
		for (float r = 0; r < reach; r += 0.5f) {
			int px = (int) Math.floor(cx + dx * r), py = (int) Math.floor(cy + dy * r);
			if (px < x || py < y || px >= x + size || py >= y + size) break;
			g.fill(px, py, px + 1, py + 1, edge);
		}
	}

	/**
	 * Ice Shell: one segment per layer along the bottom of the slot - bright while standing, part-lit
	 * while cracked, dim while one grows back. With no layers left the icon is shaded and the time
	 * until the next one shows. Whole: the slot pulses like a ready ability.
	 */
	private static void drawIceShell(GuiGraphics g, Font font, int sx, int sy, int rgb, long now) {
		IceShellPacket shell = ClientAuraState.iceShell;
		int max = shell == null ? 3 : Math.max(1, shell.maxLayers());
		int layers = shell == null ? max : shell.layers();
		float top = shell == null ? 1f : shell.top();
		boolean whole = layers >= max && top >= 0.999f;
		long shellWholeAt = ClientCooldowns.shellWholeAt();

		g.pose().pushPose();
		g.pose().translate(0, 0, 200); // above the item icon
		if (layers == 0) {
			g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0xB4000000);
			float secs = (shell == null ? 0 : shell.nextTicks()) / 20f;
			if (secs > 0 && AuraClientConfig.COOLDOWN_TEXT.get() != AuraClientConfig.CooldownText.OFF) {
				String text = secs >= 10 || AuraClientConfig.COOLDOWN_TEXT.get() == AuraClientConfig.CooldownText.SECONDS
						? String.valueOf((int) Math.ceil(secs)) : String.format("%.1f", secs);
				g.drawString(font, text, sx + SLOT / 2 - font.width(text) / 2, sy + SLOT / 2 - 6, 0xFFFFFFFF, true);
			}
		}
		// the layers
		int inner = SLOT - 4, gap = 1;
		int segW = Math.max(1, (inner - gap * (max - 1)) / max);
		int y0 = sy + SLOT - 5, y1 = sy + SLOT - 2;
		for (int i = 0; i < max; i++) {
			int x0 = sx + 2 + i * (segW + gap), x1 = x0 + segW;
			g.fill(x0, y0, x1, y1, 0xE0101828);
			if (i < layers - 1) {
				g.fill(x0, y0, x1, y1, 0xFF000000 | rgb);
			} else if (i == layers - 1) {
				int w = Math.max(1, Math.round(segW * top));
				g.fill(x0, y0, x0 + w, y1, 0xFF000000 | rgb);
			} else if (i == layers && shell != null && shell.rebuild() > 0) {
				int w = Math.round(segW * shell.rebuild());
				if (w > 0) g.fill(x0, y0, x0 + w, y1, 0x70000000 | rgb);
			}
		}
		if (whole) {
			double pulse = calm() ? 0.85 : 0.6 + 0.4 * Math.sin(now / 260.0);
			g.renderOutline(sx, sy, SLOT, SLOT, ((int) (pulse * 255) << 24) | rgb);
			g.renderOutline(sx - 1, sy - 1, SLOT + 2, SLOT + 2, ((int) (pulse * 90) << 24) | rgb);
			if (now - shellWholeAt < FLASH_MILLIS) {
				int a = (int) (170 * (1 - (now - shellWholeAt) / (float) FLASH_MILLIS));
				g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, (a << 24) | 0xFFFFFF);
			}
		} else {
			g.renderOutline(sx, sy, SLOT, SLOT, 0xFF3A3A48);
		}
		g.pose().popPose();
	}

	/** The bar eases toward the server's value, so the 2-tick updates don't look steppy. */
	private static float shownCharge = 0;
	/** When the charge last crossed the ready notch (the snap), and whether it was past it last frame. */
	private static long snapAt = 0;
	private static boolean wasReady = false;
	private static long lastSpark = 0;

	/**
	 * Fire Bending charge: a vertical bar (left edge by default, or right / by the crosshair). It fills from the
	 * bottom with sparks spraying off the fill line; a white notch marks where the orb can be shot - crossing
	 * it the bar snaps wide with a flash, and past it burns brighter and pulses, trembling once full.
	 * Only drawn while an orb is held.
	 */
	private static void renderFireCharge(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!ClientAuraState.fireBending || mc.player == null || mc.options.hideGui || mc.player.isSpectator()) {
			shownCharge = 0;
			wasReady = false;
			BAR_FX.clear();
			return;
		}
		float target = ClientAuraState.fireCharge;
		shownCharge += (target - shownCharge) * 0.35f;
		float readyAt = ClientAuraState.fireReadyAt;
		boolean ready = target >= readyAt;
		long now = System.currentTimeMillis();
		if (ready && !wasReady) snapAt = now;
		wasReady = ready;
		Font font = mc.font;
		// the same bar serves Air Bending (Wind) and Ice Spike (Frost), in their colors
		boolean wind = ClientAuraState.aura == AuraType.WIND;
		boolean frost = ClientAuraState.aura == AuraType.FROST;
		// Mjölnir spinning: its own icon and words, in thunder colors
		boolean hammer = mc.player.getMainHandItem().is(com.toast.aurapoints.AuraPoints.MJOLNIR_ITEM.get());
		boolean thunder = hammer || ClientAuraState.aura == AuraType.THUNDER;
		boolean resonance = !hammer && ClientAuraState.aura == AuraType.RESONANCE;
		if (hammer) {
			wind = false;
			frost = false;
		}
		AuraType colorOf = resonance ? AuraType.RESONANCE : wind ? AuraType.WIND : frost ? AuraType.FROST : thunder ? AuraType.THUNDER : AuraType.FIRE;
		int rgb = AuraClientConfig.PALETTE.get() == AuraClientConfig.Palette.OFF
				? (resonance ? AuraType.RESONANCE.rgb : wind ? AuraType.WIND.rgb : frost ? AuraType.FROST.rgb : thunder ? 0x7FC8FF : AuraType.FIRE.rgb) & 0xFFFFFF
				: HudColors.palette(colorOf);

		int w = HudLayout.BAR_W, h = HudLayout.BAR_H;
		int[] at = HudLayout.bar(g.guiWidth(), g.guiHeight());
		float s = HudLayout.chargeScale();
		boolean full = target >= 0.999f;
		float tremble = full && !calm() ? (float) Math.sin(now / 23.0) * 0.8f : 0f;
		float snap = calm() ? 0f : Math.max(0f, 1f - (now - snapAt) / 260f);

		g.pose().pushPose();
		g.pose().translate(at[0] + tremble, at[1], 0);
		g.pose().scale(s, s, 1);
		int left = 0, top = 0;
		int grow = Math.round(2 * snap);

		// frame like the cooldown panel: dark body, colored outline (snapping wide as the notch is crossed)
		g.fillGradient(left - 3 - grow, top - 3, left + w + 3 + grow, top + h + 3, HudColors.panelTop(), HudColors.panelBottom());
		g.renderOutline(left - 3 - grow, top - 3, w + 6 + grow * 2, h + 6, 0xB0000000 | rgb);
		g.fill(left, top, left + w, top + h, 0xFF101016);

		int fill = Math.round(h * Math.min(1f, shownCharge));
		if (fill > 0) {
			double pulse = ready ? 0.75 + 0.25 * Math.sin(now / 90.0) : 1.0;
			int a = (int) (255 * pulse) << 24;
			// fire: deep red at the bottom, orange, then hot yellow at full charge. wind: grey-blue to white
			if (resonance) g.fillGradient(left, top + h - fill, left + w, top + h, a | 0xE040FF, a | 0x1A0526);
			else if (wind) g.fillGradient(left, top + h - fill, left + w, top + h, a | 0xF4FFFF, a | 0x4A7A90);
			else if (frost) g.fillGradient(left, top + h - fill, left + w, top + h, a | 0xF4FBFF, a | 0x2F6FA8);
			// thunder: deep storm blue at the bottom up to white-hot blue, with sparks flickering through it
			else if (thunder) {
				g.fillGradient(left, top + h - fill, left + w, top + h, a | 0xE8F6FF, a | 0x1E3FBF);
				java.util.Random flicker = new java.util.Random(now / 60);
				for (int i = 0; i < 1 + fill / 25; i++) {
					int sy = top + h - 1 - flicker.nextInt(Math.max(1, fill));
					g.fill(left + flicker.nextInt(w - 2), sy, left + w - flicker.nextInt(3), sy + 1, 0xC0FFFFFF);
				}
			}
			else g.fillGradient(left, top + h - fill, left + w, top + h, a | 0xFFD040, a | 0xC0200A);
			// with a colorblind palette the fill takes the palette color over its top, so it reads the same as the frame
			if (AuraClientConfig.PALETTE.get() != AuraClientConfig.Palette.OFF) {
				g.fillGradient(left, top + h - fill, left + w, top + h - fill / 2, 0xA0000000 | rgb, 0x00000000 | rgb);
			}
			g.fill(left, top + h - fill, left + w, top + h - fill + 1, 0xFFFFFFFF);
			// sparks off the rising fill line, more as it climbs
			if (!calm() && fill < h && now - lastSpark > (ready ? 45 : 90)) {
				lastSpark = now;
				BAR_FX.sparks(left + 1 + (float) Math.random() * (w - 2), top + h - fill, ready ? 2 : 1, rgb);
			}
		}
		if (snap > 0) g.fill(left, top, left + w, top + h, HudColors.argb(0.55f * snap, 0xFFFFFF));
		BAR_FX.draw(g, now, 1f);
		// "ready" notch
		int notchY = top + h - Math.round(h * readyAt);
		g.fill(left - 3 - grow, notchY, left + w + 3 + grow, notchY + 1, ready ? 0xFFFFFFFF : 0xFFAAAAAA);
		if (ready) {
			double glow = calm() ? 0.7 : 0.5 + 0.5 * Math.sin(now / 120.0);
			g.renderOutline(left - 4 - grow, top - 4, w + 8 + grow * 2, h + 8, ((int) (glow * 200) << 24) | rgb);
		}

		// small icon above, status text below
		g.renderItem(hammer ? mc.player.getMainHandItem() : icon(resonance ? Ability.ABYSSAL_GRASP : wind ? Ability.SUPER_WIND_CHARGE : frost ? Ability.ICE_SPIKE : thunder ? Ability.CHIDORI : Ability.FIRE_BENDING), left + w / 2 - 8, top - 22);
		String text = ready ? (resonance ? "MAX REACH" : hammer ? "CHARGED!" : wind ? "BLAST!" : frost ? "FREEZE!" : thunder ? "CHIDORI!" : "FIRE!") : Math.round(100 * Math.min(1f, target / Math.max(0.01f, readyAt))) + "%";
		int color = ready ? (resonance ? 0xFFE080FF : wind || frost || thunder ? 0xFF9FE3FF : 0xFFFFE066) : 0xFFFFFFFF;
		float textScale = 0.75f * (1f + 0.35f * snap);
		g.pose().pushPose();
		g.pose().translate(left + w / 2.0f, top + h + 6, 0);
		g.pose().scale(textScale, textScale, 1);
		g.drawString(font, text, -font.width(text) / 2, 0, color, true);
		g.pose().popPose();
		g.pose().popPose();
	}

	/**
	 * Cooldown pips under the crosshair, one per ability in the panel: a dim dot filling up while it
	 * cools down, a bright one in the aura color when ready, flaring for a moment when it comes back.
	 * First person only, and only where the crosshair itself is drawn.
	 */
	private static void renderPips(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		AuraType aura = ClientAuraState.aura;
		if (aura == null || !AuraClientConfig.CROSSHAIR_PIPS.get() || mc.player == null || mc.options.hideGui
				|| mc.player.isSpectator() || !mc.options.getCameraType().isFirstPerson() || ClientAuraState.bubble != null) return;
		List<Ability> abilities = shownAbilities(aura);
		abilities.removeIf(a -> a == Ability.ICE_SHELL);
		if (abilities.isEmpty()) return;
		long now = System.currentTimeMillis();
		int rgb = HudColors.of(aura);
		boolean flying = mc.player.isFallFlying();
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		int n = abilities.size();
		for (int i = 0; i < n; i++) {
			Ability a = abilities.get(i);
			boolean flight = flying && ClientAuraState.hasFlightVersion(a);
			long[] cd = ClientAuraState.cooldown(a, flight ? AuraAbilities.Slot.FLIGHT : null);
			// spread along an arc under the crosshair
			double ang = Math.toRadians(90 + (i - (n - 1) / 2.0) * 16);
			int px = Math.round(cx + (float) Math.cos(ang) * 11), py = Math.round(cy + (float) Math.sin(ang) * 11);
			boolean cooling = cd != null && now < cd[1];
			float blood = a == Ability.BLOOD_MOON ? ClientAuraState.bloodMoonFill() : 1f;
			if (cooling || blood < 1f) {
				// cooling down, or Blood Moon still filling its blood bar
				float done = cooling ? 1f - (cd[1] - now) / (float) Math.max(1, cd[1] - cd[0]) : blood;
				g.fill(px - 1, py - 1, px + 1, py + 1, HudColors.argb(0.25f + 0.35f * done, 0x9A9AA8));
			} else {
				Long readyAt = ClientCooldowns.readyAt(a, flight);
				float flare = readyAt == null || calm() ? 0f : Math.max(0f, 1f - (now - readyAt) / 500f);
				int r = flare > 0.4f ? 2 : 1;
				g.fill(px - r, py - r, px + r, py + r, 0xFF000000 | HudColors.mix(rgb, 0xFFFFFF, flare));
			}
		}
	}

	/**
	 * Abyssal Bubble aim guide. A glowing ring marks where you looked when the bubble opened (the
	 * spot to stay on) and a dotted circle around it the allowed zone - your crosshair has to stay
	 * inside. A dotted line leads from the crosshair back to the target; if the target is off screen,
	 * an arrow at the edge points to it. Everything pales and then flashes red as you near the edge.
	 * Under it: how long the bubble has been held, out of its maximum.
	 */
	private static void renderBubbleStability(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		var bubble = ClientAuraState.bubble;
		if (bubble == null || mc.player == null || mc.options.hideGui) return;
		float strain = Math.min(1f, com.toast.aurapoints.client.BubbleShake.strain());
		long now = System.currentTimeMillis();
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		boolean flash = (now / 120) % 2 == 0;
		int rgb = strain < 0.5f ? 0x2E9BFF : strain < 0.8f ? 0xC8F0FF : (flash ? 0xFF4040 : 0xFFA0A0);

		float[] t = com.toast.aurapoints.client.BubbleShake.target(g.guiHeight());
		if (t != null) {
			float tx = cx + t[0], ty = cy + t[1];
			int margin = 12;
			boolean onScreen = t[2] == 0 && tx >= margin && tx <= g.guiWidth() - margin && ty >= margin && ty <= g.guiHeight() - margin;
			if (onScreen) {
				// allowed zone around the target
				float zone = (float) (t[3] * Math.tan(Math.toRadians(bubble.breakDegrees())));
				int dots = Math.max(24, Math.min(96, (int) (zone / 3)));
				int zoneColor = ((strain > 0.8f ? 0xE0 : 0x90) << 24) | rgb;
				for (int i = 0; i < dots; i++) {
					double a = Math.PI * 2 * i / dots + now / 3000.0;
					int px = Math.round(tx + (float) Math.cos(a) * zone), py = Math.round(ty + (float) Math.sin(a) * zone);
					g.fill(px, py, px + 1, py + 1, zoneColor);
				}
				// dotted line from the crosshair back to the target
				float dx = tx - cx, dy = ty - cy;
				float dist = (float) Math.hypot(dx, dy);
				if (dist > 8) {
					for (float s = 6; s < dist - 6; s += 5) {
						int px = Math.round(cx + dx * s / dist), py = Math.round(cy + dy * s / dist);
						g.fill(px, py, px + 1, py + 1, 0xA0000000 | rgb);
					}
				}
				// the target: a pulsing ring with a dot in the middle
				double pulse = 0.5 + 0.5 * Math.sin(now / 150.0);
				int r = 5 + (int) Math.round(pulse);
				for (int i = 0; i < 20; i++) {
					double a = Math.PI * 2 * i / 20;
					int px = Math.round(tx + (float) Math.cos(a) * r), py = Math.round(ty + (float) Math.sin(a) * r);
					g.fill(px, py, px + 1, py + 1, 0xFF000000 | rgb);
				}
				int ix = Math.round(tx), iy = Math.round(ty);
				g.fill(ix - 1, iy - 1, ix + 1, iy + 1, 0xFFFFFFFF);
			} else {
				// off screen (or behind you): an arrow on the edge pointing at it
				double ang = Math.atan2(t[1], t[0]);
				float ex = cx + (float) Math.cos(ang) * (cx - margin), ey = cy + (float) Math.sin(ang) * (cy - margin);
				ex = Math.max(margin, Math.min(g.guiWidth() - margin, ex));
				ey = Math.max(margin, Math.min(g.guiHeight() - margin, ey));
				for (int i = 0; i < 6; i++) {
					float back = i * 1.5f, wing = (6 - i) * 0.9f;
					float bx = ex - (float) Math.cos(ang) * back, by = ey - (float) Math.sin(ang) * back;
					float wx = -(float) Math.sin(ang) * wing, wy = (float) Math.cos(ang) * wing;
					g.fill(Math.round(bx - wx), Math.round(by - wy), Math.round(bx - wx) + 1, Math.round(by - wy) + 1, 0xFF000000 | rgb);
					g.fill(Math.round(bx + wx), Math.round(by + wy), Math.round(bx + wx) + 1, Math.round(by + wy) + 1, 0xFF000000 | rgb);
				}
				g.fill(Math.round(ex) - 1, Math.round(ey) - 1, Math.round(ex) + 1, Math.round(ey) + 1, 0xFFFFFFFF);
			}
		}

		// strain bar and time under the crosshair
		int w = 50, h = 3;
		int left = cx - w / 2;
		int top = cy + 14;
		g.fill(left - 1, top - 1, left + w + 1, top + h + 1, 0xC0000000);
		int fill = Math.round(w * strain);
		if (fill > 0) g.fill(left, top, left + fill, top + h, 0xFF000000 | rgb);
		float held = Math.min(bubble.maxSeconds(), (now - ClientAuraState.bubbleSince) / 1000f);
		String text = strain >= 0.8f ? "HOLD STEADY!" : String.format("%.1f / %.0fs", held, bubble.maxSeconds());
		g.pose().pushPose();
		g.pose().translate(cx, top + h + 3, 0);
		g.pose().scale(0.6f, 0.6f, 1);
		g.drawString(mc.font, text, -mc.font.width(text) / 2, 0, strain >= 0.8f ? 0xFF000000 | rgb : 0xFFBFE6FF, true);
		g.pose().popPose();
	}

	/** Short name of the key that triggers the ability, or null if it isn't bound. */
	public static String keyLabel(Ability ability) {
		if (ability == Ability.DOUBLE_JUMP) return "2x Jump";
		if (ability == Ability.ICE_SHELL) return "";
		if (ability == Ability.THORN_SENTINEL) return "Sneak+RMB";
		if (com.toast.aurapoints.ability.Handhelds.auraOf(ability) != null) return "RMB";
		if (com.toast.aurapoints.ability.Ultimates.auraOf(ability) != null) {
			KeyMapping ultKey = ClientKeyHandler.KEYS[com.toast.aurapoints.network.AbilityKeyPacket.ULTIMATE];
			if (ultKey.isUnbound()) return null;
			String name = ultKey.getTranslatedKeyMessage().getString();
			return name.length() > 8 ? name.substring(0, 8) : name;
		}
		AuraType aura = ClientAuraState.aura;
		int index = aura == null ? -1 : aura.keySlots().indexOf(ability);
		if (index < 0) return null;
		KeyMapping key = ClientKeyHandler.KEYS[index];
		if (key.isUnbound()) return null;
		String name = key.getTranslatedKeyMessage().getString();
		return name.length() > 8 ? name.substring(0, 8) : name;
	}

	/**
	 * The ability icons: the ability_icon item, custom_model_data = position here + 1
	 * (models/item/ability_icon.json). Only ever append: the numbers are baked into the models.
	 */
	private static final java.util.List<Ability> ICON_ORDER = java.util.List.of(
			Ability.ECLIPSE,
			Ability.TIDAL_ASCENSION,
			Ability.PHOENIX,
			Ability.TEMPEST,
			Ability.PERMAFROST,
			Ability.OVERGROWTH,
			Ability.JUDGEMENT,
			Ability.TECTONIC_RIFT,
			Ability.PILLAR_LIFT,
			Ability.BOULDER_TOSS,
			Ability.QUAKE,
			Ability.SUNBURST,
			Ability.RADIANT_PULSE,
			Ability.SOLAR_FLASH,
			Ability.PRISM_BEAM,
			Ability.BLOOD_MOON,
			Ability.HEMORRHAGE,
			Ability.BLOOD_TETHER,
			Ability.CRIMSON_SACRIFICE,
			Ability.SINGULARITY,
			Ability.VOID_PULL,
			Ability.RIFT_STEP,
			Ability.NULL_ORB,
			Ability.SHOCKWAVE,
			Ability.HEALING_TIDE,
			Ability.ABYSSAL_BUBBLE,
			Ability.VANISH,
			Ability.DASH,
			Ability.FIREBALL,
			Ability.HEAT_WAVE,
			Ability.FIRE_BENDING,
			Ability.DOUBLE_JUMP,
			Ability.SUPER_WIND_CHARGE,
			Ability.ICE_SPIKE,
			Ability.VINE_HOOK,
			Ability.ENTANGLEMENT,
			Ability.VERDANT_VIPER,
			Ability.ICE_SHELL,
			Ability.THUNDERBOLT,
			Ability.CHIDORI,
			Ability.SONIC_BLOSSOM,
			Ability.LISTENING_TREE,
			Ability.ABYSSAL_GRASP);
	private static final Map<Ability, ItemStack> ICONS = new EnumMap<>(Ability.class);

	/** The panel's icon for an ability (the HUD editor's preview uses them too). */
	public static ItemStack icon(Ability ability) {
		int n = ICON_ORDER.indexOf(ability);
		if (n < 0) return ItemStack.EMPTY;
		return ICONS.computeIfAbsent(ability, a -> {
			ItemStack stack = new ItemStack(com.toast.aurapoints.AuraPoints.ABILITY_ICON.get());
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(n + 1));
			return stack;
		});
	}
}
