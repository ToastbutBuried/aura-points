package com.toast.aurapoints.client;

import com.mojang.blaze3d.platform.InputConstants;
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

import java.util.ArrayList;
import java.util.List;

/**
 * CLIENT ONLY. Camera shots the server scripts for the rift (5.47.0, FxPacket.RIFT_CAM, rift/RiftCam): the arrival
 * flyover, the doors opening, the Forging, the floor giving way, the Warden's entrance, puzzle intros. Keyframes in
 * world coordinates (position, the point it looks at, field of view, cut), splined like the awakening ritual
 * (client/RitualCutscene shares mixin/CameraMixin). Eases out of your eyes and back. A screen of ours takes the
 * keys meanwhile and draws the letterbox and the title; sneak skips a skippable shot. Your body keeps moving
 * (you can be falling while the camera watches). Reduced motion: each shot held still.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class RiftCamera {

	private RiftCamera() {
	}

	private record Key(int t, Vec3 pos, Vec3 look, float fov, boolean cut) {
	}

	private static final int BLEND = 14;
	private static final List<Key> KEYS = new ArrayList<>();
	private static boolean active, skippable;
	private static int t, length, rgb = 0xFFFFFF;
	private static String shotTitle = "", subtitle = "";
	private static boolean hadHideGui;

	public static boolean active() {
		return active;
	}

	/**
	 * FxPacket.RIFT_CAM: value = length in ticks (0 = stop), text = "rgb hex\u001Fskippable 0|1\u001Ftitle\u001Fsubtitle\u001Fkeys", keys =
	 * "t,x,y,z,lx,ly,lz,fov,cut|...".
	 */
	public static void onPacket(int value, String text) {
		Minecraft mc = Minecraft.getInstance();
		if (value <= 0) {
			stop(mc);
			return;
		}
		if (mc.player == null) return;
		String[] part = text.split("\u001F", -1);
		if (part.length < 5) return;
		List<Key> keys = new ArrayList<>();
		try {
			rgb = Integer.parseInt(part[0], 16);
			for (String k : part[4].split("\\|")) {
				String[] v = k.split(",");
				if (v.length < 9) continue;
				keys.add(new Key(Integer.parseInt(v[0]), new Vec3(Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3])),
						new Vec3(Double.parseDouble(v[4]), Double.parseDouble(v[5]), Double.parseDouble(v[6])), Float.parseFloat(v[7]), "1".equals(v[8])));
			}
		} catch (NumberFormatException e) {
			return;
		}
		if (keys.isEmpty()) return;
		if (active) close(mc); // replaced by this shot: not a "closed" report
		KEYS.clear();
		KEYS.addAll(keys);
		skippable = "1".equals(part[1]);
		shotTitle = part[2];
		subtitle = part[3];
		length = value;
		t = 0;
		hadHideGui = mc.options.hideGui;
		active = true;
		mc.setScreen(new CamScreen());
	}

	private static void stop(Minecraft mc) {
		if (!active) return;
		close(mc);
		// the server must know a skipped shot is over (Hall of Echoes waits for it)
		if (mc.player != null && mc.getConnection() != null)
			com.toast.aurapoints.shim.neoforge.network.PacketDistributor.sendToServer(new com.toast.aurapoints.network.RiftInputPacket(com.toast.aurapoints.network.RiftInputPacket.CAM_CLOSED, 0, 0));
	}

	private static void close(Minecraft mc) {
		if (!active) return;
		active = false;
		mc.options.hideGui = hadHideGui;
		if (mc.screen instanceof CamScreen) mc.setScreen(null);
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (!active) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || !mc.player.isAlive() || !(mc.screen instanceof CamScreen)) {
			stop(mc);
			return;
		}
		if (mc.isPaused()) return;
		if (++t > length) {
			stop(mc);
			return;
		}
		mc.options.hideGui = true;
	}

	private static int segment(double now) {
		int i = 0;
		for (int k = 0; k < KEYS.size() - 1; k++) if (KEYS.get(k).t <= now) i = k;
		return i;
	}

	private static double spline(double p0, double p1, double p2, double p3, double u) {
		double u2 = u * u, u3 = u2 * u;
		return 0.5 * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
	}

	private static Vec3 spline(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
		return new Vec3(spline(p0.x, p1.x, p2.x, p3.x, u), spline(p0.y, p1.y, p2.y, p3.y, u), spline(p0.z, p1.z, p2.z, p3.z, u));
	}

	private static double smooth(double x) {
		x = Mth.clamp(x, 0, 1);
		return x * x * (3 - 2 * x);
	}

	/** {x, y, z, yaw, pitch} for this frame, or null (mixin/CameraMixin). */
	public static double[] pose(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (!active || mc.player == null || KEYS.isEmpty()) return null;
		double now = Mth.clamp(t + partial, 0, length);
		int i = segment(now);
		Key a = KEYS.get(i), b = KEYS.get(Math.min(i + 1, KEYS.size() - 1));
		double u = b.t == a.t ? 0 : Mth.clamp((now - a.t) / (b.t - a.t), 0, 1);
		Vec3 pos, look;
		if (AuraClientConfig.calm()) {
			pos = a.pos;
			look = a.look;
		} else {
			Key p0 = i > 0 && !a.cut ? KEYS.get(i - 1) : a, p3 = i + 2 < KEYS.size() && !KEYS.get(i + 2).cut && !b.cut ? KEYS.get(i + 2) : b;
			if (b.cut) {
				pos = a.pos;
				look = a.look;
			} else {
				pos = spline(p0.pos, a.pos, b.pos, p3.pos, u);
				look = spline(p0.look, a.look, b.look, p3.look, u);
			}
		}
		Vec3 d = look.subtract(pos);
		float yaw = (float) (Mth.atan2(d.z, d.x) * 180 / Math.PI) - 90f;
		float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI);
		double home = Math.max(1.0 - smooth(now / BLEND), smooth((now - (length - BLEND)) / BLEND));
		if (home > 0) {
			pos = pos.lerp(mc.player.getEyePosition(partial), home);
			yaw = yaw + Mth.wrapDegrees(mc.player.getViewYRot(partial) - yaw) * (float) home;
			pitch = Mth.lerp((float) home, pitch, mc.player.getViewXRot(partial));
		}
		return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
	}

	@SubscribeEvent
	public static void onFov(ViewportEvent.ComputeFov event) {
		if (!active || KEYS.isEmpty() || AuraClientConfig.calm()) return;
		double now = t + event.getPartialTick();
		int i = segment(now);
		Key a = KEYS.get(i), b = KEYS.get(Math.min(i + 1, KEYS.size() - 1));
		float u = b.t == a.t || b.cut ? 0 : (float) Mth.clamp((now - a.t) / (b.t - a.t), 0, 1);
		event.setFOV(event.getFOV() * Mth.lerp(u, a.fov, b.fov));
	}

	/** Bars, the title, the skip hint; it swallows every key (sneak skips). */
	private static final class CamScreen extends Screen {

		CamScreen() {
			super(Component.literal("Rift"));
		}

		@Override
		protected void init() {
			InputConstants.grabOrReleaseMouse(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR_HIDDEN, minecraft.getWindow().getScreenWidth() / 2.0,
					minecraft.getWindow().getScreenHeight() / 2.0);
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
		}

		@Override
		public boolean keyPressed(int key, int scan, int modifiers) {
			if (skippable && minecraft != null && minecraft.options.keyShift.matches(key, scan)) stop(minecraft);
			return true;
		}

		@Override
		public boolean mouseClicked(double x, double y, int button) {
			return true;
		}

		@Override
		public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
			if (!active) return;
			float now = t + partial;
			int w = width, h = height;
			float bars = (float) Math.min(smooth(now / 12.0), smooth((length - now) / 12.0));
			int bar = Math.round(h * 0.12f * bars);
			if (bar > 0) {
				g.fill(0, 0, w, bar, 0xFF000000);
				g.fill(0, h - bar, w, h, 0xFF000000);
			}
			if (!shotTitle.isEmpty() && now > 8 && now < length - 6) {
				float fade = Math.min(Mth.clamp((now - 8) / 10f, 0f, 1f), Mth.clamp((length - 6 - now) / 10f, 0f, 1f));
				int alpha = Math.max(4, (int) (255 * fade));
				g.pose().pushPose();
				g.pose().translate(w / 2f, h * 0.72f, 0);
				g.pose().pushPose();
				Component tc = Component.literal(shotTitle).withStyle(net.minecraft.ChatFormatting.BOLD);
				// as big as fits: 3x, smaller for a long title
				float big = AuraTitles.size(h), sc = AuraTitles.fit(font, tc, 3f * big, w);
				g.pose().scale(sc, sc, 1f);
				g.drawString(font, tc, -font.width(tc) / 2, 0, alpha << 24 | (rgb & 0xFFFFFF), true);
				g.pose().popPose();
				if (!subtitle.isEmpty()) {
					g.pose().translate(0, 32f * big, 0);
					float ss = AuraTitles.fit(font, Component.literal(subtitle), 1.5f * big, w);
					g.pose().scale(ss, ss, 1f);
					g.drawString(font, subtitle, -font.width(subtitle) / 2, 0, alpha << 24 | 0xDDDDDD, true);
				}
				g.pose().popPose();
			}
			if (skippable && now > 20) {
				Component hint = Component.literal("Sneak to skip");
				g.drawString(font, hint, w - font.width(hint) - 8, h - Math.max(bar, 4) + (bar > 12 ? (bar - 8) / 2 : -12), 0x80FFFFFF, false);
			}
		}
	}
}
