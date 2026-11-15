package com.toast.aurapoints.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.arena.AltarRitual;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * CLIENT ONLY. The awakening ritual seen by the one it is for (FxPacket.RITUAL, arena/AltarRitual): about 20 s through
 * a camera that is not you. Five shots, cut between them, timed to the server's phases: the pull back from your eyes
 * (approach), the dolly in to your face (gathering), a high sweeping arc (vision), a crane up from the floor while you
 * rise (ascension), and the wide reveal that eases back into your eyes.
 *
 * How: mixin/CameraMixin asks {@link #pose} every frame and, during the show, puts the camera there and marks it
 * detached (so your own body is drawn; you stay the camera entity - with another entity as the camera, vanilla and
 * Fabric don't draw the local player at all). A screen of our own is open meanwhile - it takes the keyboard and
 * mouse (so you can't walk off), survives the hidden HUD (options.hideGui, which also hides the hand, the crosshair
 * and the sidebar) and draws the letterbox bars, the flash and the title. Reduced motion: still shots instead of
 * moving ones. Ends by itself on death, leaving the world, a stop from the server, or a little after its length.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class RitualCutscene {

	private RitualCutscene() {
	}

	/** One camera position: at tick t, x y z relative to where you stand on the dais (feet), the field of view (1 = yours), and whether the shot starts here (a cut). */
	private record Key(int t, double x, double y, double z, float fov, boolean cut) {
	}

	private static final Key[] KEYS = {
			// approach: out of your eyes, back along the bridge, round to your left
			new Key(0, -0.4, 1.62, 0.0, 1.0f, true),
			new Key(30, -4.0, 1.4, 0.0, 1.0f, false),
			new Key(AltarRitual.GATHER, -5.7, 1.1, 4.1, 1.0f, false),
			// gathering: in to your face
			new Key(AltarRitual.VISION, -1.8, 1.65, 0.6, 0.75f, false),
			// vision: over the pillars, half way round
			new Key(AltarRitual.VISION, -6.5, 5.2, -4.7, 1.1f, true),
			new Key(195, 0.0, 6.5, -8.0, 1.12f, false),
			new Key(230, 7.5, 6.5, -2.5, 1.12f, false),
			new Key(AltarRitual.ASCEND, 6.0, 5.5, 5.0, 1.05f, false),
			// ascension: from the floor, looking up
			new Key(AltarRitual.ASCEND, -2.6, 0.3, 1.9, 1.0f, true),
			new Key(300, -2.9, 0.6, 2.1, 0.85f, false),
			new Key(AltarRitual.REVEAL, -3.6, 2.6, 2.6, 0.7f, false),
			// reveal: wide, pulling out, then home
			new Key(AltarRitual.REVEAL, -5.0, 5.2, 0.0, 1.15f, true),
			new Key(370, -8.5, 6.0, 0.0, 1.0f, false),
			new Key(AltarRitual.END, -0.4, 1.62, 0.0, 1.0f, false),
	};

	/** Ticks the camera takes out of and back into your eyes. */
	private static final int BLEND = 22;

	private static boolean active;
	private static int t, length;
	private static int rgb = 0xFFC83D;
	private static String ultimate = "";
	private static boolean replay;
	private static Vec3 altar = Vec3.ZERO;
	private static boolean hadHideGui;

	public static boolean active() {
		return active;
	}

	/** FxPacket.RITUAL: value = length in ticks (0 = stop), text = "aura;Ultimate;replay;x;y;z". */
	public static void onPacket(int value, String text) {
		Minecraft mc = Minecraft.getInstance();
		if (value <= 0) {
			stop(mc);
			return;
		}
		if (mc.player == null || mc.level == null) return;
		String[] part = text.split(";");
		if (part.length < 6) return;
		try {
			altar = new Vec3(Double.parseDouble(part[3]), Double.parseDouble(part[4]), Double.parseDouble(part[5]));
		} catch (NumberFormatException e) {
			return;
		}
		if (active) stop(mc);
		AuraType aura = AuraType.byId(part[0]);
		rgb = aura == null ? 0xFFC83D : aura.rgb;
		ultimate = part[1];
		replay = "1".equals(part[2]);
		length = value;
		t = 0;
		hadHideGui = mc.options.hideGui;
		active = true;
		mc.setScreen(new RitualScreen());
	}

	private static void stop(Minecraft mc) {
		if (!active) return;
		active = false;
		mc.options.hideGui = hadHideGui;
		if (mc.screen instanceof RitualScreen) mc.setScreen(null);
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (!active) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || !mc.player.isAlive()) {
			stop(mc);
			return;
		}
		// something else took our screen away (a death screen, a menu the server opened): the show is over for this viewer
		if (!(mc.screen instanceof RitualScreen)) {
			stop(mc);
			return;
		}
		if (mc.isPaused()) return;
		t++;
		if (t > length + 10) {
			stop(mc);
			return;
		}
		mc.options.hideGui = true;
		// the weight of the big moments
		if (t == AltarRitual.GATHER) CameraFx.add(0, 0.35f, 0f);
		if (t > AltarRitual.ASCEND && t < AltarRitual.REVEAL && t % 10 == 0) CameraFx.add(0, 0.12f + (t - AltarRitual.ASCEND) * 0.004f, 0f);
		if (t == AltarRitual.REVEAL) CameraFx.add(0, 1.3f, 0f);
	}

	/** Where the camera is right now: {x, y, z, yaw, pitch}, or null when no ritual runs (mixin/CameraMixin, every frame). */
	public static double[] pose(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (!active || mc.player == null) return null;
		double now = Mth.clamp(t + partial, 0, length);
		int i = segment((int) now);
		Key a = KEYS[i], b = KEYS[Math.min(i + 1, KEYS.length - 1)];
		double u = b.t == a.t ? 0 : Mth.clamp((now - a.t) / (double) (b.t - a.t), 0, 1);
		Vec3 pos;
		if (AuraClientConfig.calm()) {
			// reduced motion: each shot is held from its middle key
			int first = i, last = i + 1;
			while (first > 0 && !KEYS[first].cut) first--;
			while (last < KEYS.length - 1 && !KEYS[last].cut) last++;
			if (KEYS[last].cut && last > first) last--;
			Key m = KEYS[(first + last + 1) / 2];
			pos = new Vec3(m.x, m.y, m.z);
		} else {
			// Catmull-Rom through the shot's keys (its ends stand in for the missing neighbours)
			Key p0 = i > 0 && !a.cut ? KEYS[i - 1] : a, p3 = i + 2 < KEYS.length && !KEYS[i + 2].cut && !b.cut ? KEYS[i + 2] : b;
			pos = new Vec3(spline(p0.x, a.x, b.x, p3.x, u), spline(p0.y, a.y, b.y, p3.y, u), spline(p0.z, a.z, b.z, p3.z, u));
		}
		pos = pos.add(altar);
		Vec3 me = mc.player.getPosition(partial);
		// what it looks at: your chest, your face in the close-up
		double face = Mth.clamp(1.0 - Math.abs(now - AltarRitual.VISION + 10) / 45.0, 0, 1);
		Vec3 d = me.add(0, 1.15 + 0.4 * face, 0).subtract(pos);
		float yaw = (float) (Mth.atan2(d.z, d.x) * 180 / Math.PI) - 90f;
		float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI);
		// out of your eyes at the start, back into them at the end
		double home = Math.max(1.0 - smooth(now / BLEND), smooth((now - (length - BLEND)) / BLEND));
		if (home > 0) {
			pos = pos.lerp(me.add(0, mc.player.getEyeHeight(), 0), home);
			yaw = yaw + Mth.wrapDegrees(mc.player.getViewYRot(partial) - yaw) * (float) home;
			pitch = Mth.lerp((float) home, pitch, mc.player.getViewXRot(partial));
		}
		return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
	}

	/** The index of the key the shot at {@code tick} runs from (the later one where two share a tick: that is the cut). */
	private static int segment(int tick) {
		int i = 0;
		for (int k = 0; k < KEYS.length - 1; k++) if (KEYS[k].t <= tick) i = k;
		return i;
	}

	private static double spline(double p0, double p1, double p2, double p3, double u) {
		double u2 = u * u, u3 = u2 * u;
		return 0.5 * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
	}

	private static double smooth(double x) {
		x = Mth.clamp(x, 0, 1);
		return x * x * (3 - 2 * x);
	}

	private static float time(float partial) {
		return t + partial;
	}

	@SubscribeEvent
	public static void onFov(ViewportEvent.ComputeFov event) {
		if (!active) return;
		float now = time((float) event.getPartialTick());
		int i = segment((int) now);
		Key a = KEYS[i], b = KEYS[Math.min(i + 1, KEYS.length - 1)];
		float u = b.t == a.t ? 0 : Mth.clamp((now - a.t) / (b.t - a.t), 0f, 1f);
		float f = AuraClientConfig.calm() ? 1f : Mth.lerp(u, a.fov, b.fov);
		event.setFOV(event.getFOV() * f);
	}

	@SubscribeEvent
	public static void onAngles(ViewportEvent.ComputeCameraAngles event) {
		if (!active || AuraClientConfig.calm()) return;
		// the sweep banks a little
		float now = time((float) event.getPartialTick());
		if (now > AltarRitual.VISION && now < AltarRitual.ASCEND) {
			float k = Mth.sin((now - AltarRitual.VISION) / (AltarRitual.ASCEND - AltarRitual.VISION) * Mth.PI);
			event.setRoll(event.getRoll() - 6f * k);
		}
	}

	/** 0..1: how far the sky and fog have turned to the aura's color (the vision and the ascent). */
	public static float tintStrength() {
		if (!active) return 0f;
		float in = Mth.clamp((t - (AltarRitual.VISION - 15)) / 30f, 0f, 1f), out = Mth.clamp((AltarRitual.REVEAL + 25 - t) / 25f, 0f, 1f);
		float s = Math.min(in, out);
		return s * s * (3 - 2 * s);
	}

	/** The sky under the ritual: dark, in the aura's color (mixin/ClientLevelMixin). */
	public static Vec3 tintSky(Vec3 sky, float s) {
		double r = ((rgb >> 16) & 0xFF) / 255.0 * 0.4, g = ((rgb >> 8) & 0xFF) / 255.0 * 0.4, b = (rgb & 0xFF) / 255.0 * 0.4;
		return new Vec3(Mth.lerp(s * 0.9, sky.x, r), Mth.lerp(s * 0.9, sky.y, g), Mth.lerp(s * 0.9, sky.z, b));
	}

	@SubscribeEvent
	public static void onFogColor(ViewportEvent.ComputeFogColor event) {
		float s = tintStrength();
		if (s <= 0) return;
		event.setRed(Mth.lerp(s * 0.8f, event.getRed(), ((rgb >> 16) & 0xFF) / 255f * 0.45f));
		event.setGreen(Mth.lerp(s * 0.8f, event.getGreen(), ((rgb >> 8) & 0xFF) / 255f * 0.45f));
		event.setBlue(Mth.lerp(s * 0.8f, event.getBlue(), (rgb & 0xFF) / 255f * 0.45f));
	}

	/** The screen over the show: bars, flash, title; it swallows every key (sneak skips a replay). */
	private static final class RitualScreen extends Screen {

		RitualScreen() {
			super(Component.literal("Awakening"));
		}

		@Override
		protected void init() {
			// no pointer over the picture
			InputConstants.grabOrReleaseMouse(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR_HIDDEN, minecraft.getWindow().getScreenWidth() / 2.0, minecraft.getWindow().getScreenHeight() / 2.0);
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}

		@Override
		public boolean shouldCloseOnEsc() {
			return false;
		}

		@Override
		public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
			// nothing: no blur, no dimming
		}

		@Override
		public boolean keyPressed(int key, int scan, int modifiers) {
			if (replay && minecraft != null && minecraft.player != null && minecraft.options.keyShift.matches(key, scan)) {
				minecraft.player.connection.sendCommand("aura altar skip");
			}
			return true;
		}

		@Override
		public boolean mouseClicked(double x, double y, int button) {
			return true;
		}

		@Override
		public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
			if (!active) return;
			float now = time(partial);
			int w = width, h = height;
			// letterbox: slides in, slides out
			float bars = (float) Math.min(smooth(now / 20.0), smooth((length - now) / 20.0));
			int bar = Math.round(h * 0.125f * bars);
			if (bar > 0) {
				g.fill(0, 0, w, bar, 0xFF000000);
				g.fill(0, h - bar, w, h, 0xFF000000);
			}
			// the flash of the reveal
			float since = now - AltarRitual.REVEAL;
			if (since >= 0 && since < 18) {
				int alpha = (int) (255 * (1f - since / 18f) * (AuraClientConfig.calm() ? 0.45f : 1f));
				g.fill(0, 0, w, h, alpha << 24 | 0xFFFFFF);
			}
			// the title
			if (since > 3 && now < length - 6) {
				float fade = Math.min(Mth.clamp((since - 3) / 8f, 0f, 1f), Mth.clamp((length - 6 - now) / 10f, 0f, 1f));
				int alpha = Math.max(4, (int) (255 * fade));
				g.pose().pushPose();
				g.pose().translate(w / 2f, h * 0.30f, 0);
				g.pose().pushPose();
				g.pose().scale(4f, 4f, 1f);
				Component title = Component.literal("AWAKENED").withStyle(net.minecraft.ChatFormatting.BOLD);
				g.drawString(font, title, -font.width(title) / 2, 0, alpha << 24 | 0xFFC83D, true);
				g.pose().popPose();
				g.pose().translate(0, 44f, 0);
				g.pose().scale(2f, 2f, 1f);
				g.drawString(font, ultimate, -font.width(ultimate) / 2, 0, alpha << 24 | (rgb & 0xFFFFFF), true);
				g.pose().popPose();
			}
			if (replay && now > 30 && now < 110) {
				Component hint = Component.literal("Sneak to skip");
				g.drawString(font, hint, w - font.width(hint) - 8, h - Math.max(bar, 4) + (bar > 12 ? (bar - 8) / 2 : -12), 0x80FFFFFF, false);
			}
		}
	}
}
