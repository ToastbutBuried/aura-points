package com.toast.aurapoints.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterGuiLayersEvent;
import com.toast.aurapoints.shim.neoforge.client.gui.VanillaGuiLayers;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * CLIENT ONLY. What other auras do to you, shown around the screen's edges (under the HUD):
 *  - frozen by an Ice Spike: frost creeping in from the edges;
 *  - caught in Entanglement: roots growing up from the bottom corners, thrashing while you struggle;
 *  - stunned: a purple flicker and little stars circling the crosshair;
 *  - inside someone else's Abyssal Bubble: a deep blue tint with the edges welling in and out;
 *  - healed by your Lifesteal: a short red pulse.
 * Each fades in and out instead of popping. Strength in the client config.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class StatusOverlays {

	private StatusOverlays() {
	}

	private static final ResourceLocation FROST = ResourceLocation.withDefaultNamespace("textures/misc/powder_snow_outline.png");

	/** How much of each effect shows (0..1, eased toward on/off every tick), last tick and now. */
	private static float frost, frostO, roots, rootsO, bubble, bubbleO, stun, stunO;
	private static long stunUntil = 0, lifestealAt = 0;
	private static float lifestealAmount = 0;
	/** The root strands for the current catch (grown fresh each time). */
	private static List<float[][]> strands = List.of();
	private static boolean wasRooted = false;

	public static void stunned(int ticks) {
		stunUntil = System.currentTimeMillis() + ticks * 50L;
	}

	public static void lifesteal(float amount) {
		lifestealAt = System.currentTimeMillis();
		lifestealAmount = amount;
	}

	@SubscribeEvent
	public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath("aurapoints", "status_overlays"), StatusOverlays::render);
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		frostO = frost;
		rootsO = roots;
		bubbleO = bubble;
		stunO = stun;
		if (mc.player == null) {
			frost = roots = bubble = stun = 0;
			stunUntil = 0;
			wasRooted = false;
			return;
		}
		long now = System.currentTimeMillis();
		boolean entangled = ClientAuraState.rooted && ClientAuraState.mash;
		if (entangled && !wasRooted) strands = grow(new Random(now));
		wasRooted = entangled;
		frost = approach(frost, ClientAuraState.isFrozen() ? 1 : 0, ClientAuraState.isFrozen() ? 0.25f : 0.1f);
		roots = approach(roots, entangled ? 1 : 0, entangled ? 0.12f : 0.2f);
		boolean inBubble = ClientAuraState.bubblePull != null && now - ClientAuraState.bubblePullAt < 200;
		bubble = approach(bubble, inBubble ? 1 : 0, 0.15f);
		stun = approach(stun, now < stunUntil ? 1 : 0, 0.3f);
	}

	private static float approach(float v, float target, float step) {
		return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
	}

	private static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !AuraClientConfig.STATUS_OVERLAYS.get()) return;
		float pt = delta.getGameTimeDeltaPartialTick(false);
		float strength = (float) (double) AuraClientConfig.OVERLAY_STRENGTH.get();
		boolean calm = AuraClientConfig.calm();
		long now = System.currentTimeMillis();
		int w = g.guiWidth(), h = g.guiHeight();

		float bubbleA = Mth.lerp(pt, bubbleO, bubble) * strength;
		if (bubbleA > 0.01f) {
			g.fill(0, 0, w, h, HudColors.argb(0.12f * bubbleA, 0x0B3B8C));
			float well = calm ? 0.5f : 0.5f + 0.5f * Mth.sin(now / 420f);
			edges(g, w, h, 0x1C6FE0, 0.5f * bubbleA, Math.round(h * (0.12f + 0.06f * well)));
		}

		float frostA = Mth.lerp(pt, frostO, frost) * strength;
		if (frostA > 0.01f) {
			RenderSystem.enableBlend();
			RenderSystem.setShaderColor(1f, 1f, 1f, frostA);
			g.blit(FROST, 0, 0, -90, 0f, 0f, w, h, w, h);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			float shimmer = calm ? 0.6f : 0.6f + 0.4f * Mth.sin(now / 300f);
			edges(g, w, h, 0xBFEFFF, 0.35f * frostA * shimmer, Math.round(h * 0.1f));
		}

		float rootsA = Mth.lerp(pt, rootsO, roots) * strength;
		if (rootsA > 0.01f) {
			edges(g, w, h, 0x2A1A0C, 0.45f * rootsA, Math.round(h * 0.12f));
			drawRoots(g, w, h, rootsA, calm ? 0 : now);
		}

		float stunA = Mth.lerp(pt, stunO, stun) * strength;
		if (stunA > 0.01f) {
			float flicker = calm ? 0.7f : 0.55f + 0.45f * Math.abs(Mth.sin(now / 70f) * Mth.sin(now / 173f));
			edges(g, w, h, 0x8A2BE2, 0.45f * stunA * flicker, Math.round(h * 0.14f));
			// dazed stars going round the crosshair
			int cx = w / 2, cy = h / 2;
			for (int i = 0; i < 3; i++) {
				double a = (calm ? 0 : now / 260.0) + i * Math.PI * 2 / 3;
				int sx = Math.round(cx + (float) Math.cos(a) * 16), sy = Math.round(cy - 14 + (float) Math.sin(a) * 5);
				int c = HudColors.argb(stunA, i == 0 ? 0xFFF27A : 0xFFFFFF);
				g.fill(sx - 1, sy, sx + 2, sy + 1, c);
				g.fill(sx, sy - 1, sx + 1, sy + 2, c);
			}
		}

		long since = now - lifestealAt;
		if (since < 450) {
			float t = since / 450f;
			float a = Math.min(0.55f, 0.18f + lifestealAmount * 0.1f) * (1 - t) * (1 - t) * strength;
			edges(g, w, h, 0xB0101A, a, Math.round(h * (0.1f + 0.04f * (1 - t))));
		}
	}

	/** A soft band of color fading in from all four edges, {@code depth} pixels deep. */
	static void edges(GuiGraphics g, int w, int h, int rgb, float alpha, int depth) {
		if (alpha <= 0.005f || depth <= 0) return;
		int solid = HudColors.argb(alpha, rgb), clear = HudColors.argb(0, rgb);
		g.fillGradient(0, 0, w, depth, solid, clear);
		g.fillGradient(0, h - depth, w, h, clear, solid);
		// the sides: the same gradient turned on its side
		g.pose().pushPose();
		g.pose().translate(0, h, 0);
		g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-90));
		g.fillGradient(0, 0, h, depth, solid, clear);
		g.fillGradient(0, w - depth, h, w, clear, solid);
		g.pose().popPose();
	}

	/**
	 * Root strands for one catch, in screen fractions: each a list of points {x, y, thickness} from the
	 * bottom edge (mostly from the corners) winding up and in, forking now and then.
	 */
	private static List<float[][]> grow(Random r) {
		List<float[][]> out = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			boolean left = i % 2 == 0;
			float x = left ? r.nextFloat() * 0.28f : 1f - r.nextFloat() * 0.28f;
			float y = 1.02f;
			float dir = (float) Math.toRadians(left ? -60 - r.nextFloat() * 25 : -120 + r.nextFloat() * 25);
			int n = 7 + r.nextInt(5);
			float[][] pts = new float[n][];
			float thick = 3.2f + r.nextFloat() * 1.5f;
			for (int k = 0; k < n; k++) {
				pts[k] = new float[]{x, y, thick * (1f - k / (float) n * 0.75f)};
				dir += (r.nextFloat() - 0.5f) * 0.7f;
				float step = 0.025f + r.nextFloat() * 0.02f;
				x += (float) Math.cos(dir) * step * 0.6f;
				y += (float) Math.sin(dir) * step;
			}
			out.add(pts);
		}
		return out;
	}

	/** The strands grown {@code amount} of the way, swaying while you struggle. */
	private static void drawRoots(GuiGraphics g, int w, int h, float amount, long now) {
		for (int s = 0; s < strands.size(); s++) {
			float[][] pts = strands.get(s);
			float shown = amount * (pts.length - 1);
			for (int k = 0; k < pts.length - 1 && k < shown; k++) {
				float part = Math.min(1f, shown - k);
				float sway = now == 0 ? 0 : Mth.sin(now / 160f + s * 1.7f + k * 0.6f) * 0.006f * k;
				float x0 = (pts[k][0] + sway) * w, y0 = pts[k][1] * h;
				float x1 = Mth.lerp(part, pts[k][0], pts[k + 1][0] + sway) * w, y1 = Mth.lerp(part, pts[k][1], pts[k + 1][1]) * h;
				line(g, x0, y0, x1, y1, pts[k][2], 0xF0000000 | 0x3B2412, 0xC0000000 | 0x7A5230);
			}
		}
	}

	/** A thick line as a run of squares, with a lighter streak along it. */
	private static void line(GuiGraphics g, float x0, float y0, float x1, float y1, float thick, int body, int light) {
		float len = (float) Math.hypot(x1 - x0, y1 - y0);
		int steps = Math.max(1, (int) Math.ceil(len));
		int t = Math.max(1, Math.round(thick));
		for (int i = 0; i <= steps; i++) {
			float x = Mth.lerp(i / (float) steps, x0, x1), y = Mth.lerp(i / (float) steps, y0, y1);
			int px = Math.round(x - t / 2f), py = Math.round(y - t / 2f);
			g.fill(px, py, px + t, py + t, body);
			if (t >= 3) g.fill(px + 1, py, px + 2, py + 1, light);
		}
	}
}
