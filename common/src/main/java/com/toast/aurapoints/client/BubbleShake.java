package com.toast.aurapoints.client;

import com.toast.aurapoints.network.BubblePacket;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.bus.api.EventPriority;
import com.toast.aurapoints.shim.neoforge.client.event.RenderFrameEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;
import org.joml.Vector3f;

import java.util.Random;

/**
 * CLIENT ONLY. The Abyssal Bubble's skill check: while it is held, the camera really turns by
 * itself - a slow drift plus faster jitter, growing every second - and the player has to fight it
 * with the mouse. The server pops the bubble when the look drifts past BREAK_DEGREES from where it
 * started. Applied every frame (not every tick) so it moves smoothly.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class BubbleShake {

	private BubbleShake() {
	}

	private static final Random RANDOM = new Random();
	/** the bubble the offsets below belong to (its start time) */
	private static long shaking = -1;
	private static double lastYaw, lastPitch;
	private static final double[] PHASE = new double[6];

	@SubscribeEvent
	public static void onRenderFrame(RenderFrameEvent.Pre event) {
		Minecraft mc = Minecraft.getInstance();
		BubblePacket b = ClientAuraState.bubble;
		if (b == null || mc.player == null) {
			shaking = -1;
			return;
		}
		long since = ClientAuraState.bubbleSince;
		if (shaking != since) {
			shaking = since;
			lastYaw = lastPitch = 0;
			for (int i = 0; i < PHASE.length; i++) PHASE[i] = RANDOM.nextDouble() * Math.PI * 2;
		}
		if (mc.isPaused()) return;

		double t = (System.currentTimeMillis() - since) / 1000.0;
		double amp = b.shakeStart() + b.shakePerSecond() * t;
		double yaw = amp * noise(t, 0);
		double pitch = amp * 0.6 * noise(t * 1.13, 3);
		// Entity.turn scales by 0.15 (mouse units), so hand it degrees / 0.15
		mc.player.turn((yaw - lastYaw) / 0.15, (pitch - lastPitch) / 0.15);
		lastYaw = yaw;
		lastPitch = pitch;
	}

	/** -1..1: a slow drift that pulls far, plus quicker shakes on top. */
	private static double noise(double t, int p) {
		return 0.55 * Math.sin(t * 2 * Math.PI * 0.35 + PHASE[p])
				+ 0.30 * Math.sin(t * 2 * Math.PI * 1.7 + PHASE[p + 1])
				+ 0.15 * Math.sin(t * 2 * Math.PI * 4.3 + PHASE[p + 2]);
	}

	/** The FOV the world was last drawn with (sprinting and the bubble's slowness change it). */
	private static double fov = 70;

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onComputeFov(ViewportEvent.ComputeFov event) {
		if (event.usedConfiguredFov()) fov = event.getFOV();
	}

	/**
	 * Where the bubble's start direction is on screen, in GUI pixels relative to the screen
	 * center: {x, y, behind} (behind = 1 if it is behind the camera), or null without a bubble.
	 * Also the pixels per unit of tan(angle), for sizing the allowed zone: out[3].
	 */
	public static float[] target(int guiHeight) {
		Minecraft mc = Minecraft.getInstance();
		BubblePacket b = ClientAuraState.bubble;
		if (b == null || mc.player == null) return null;
		var camera = mc.gameRenderer.getMainCamera();
		Vec3 d = Vec3.directionFromRotation(b.startPitch(), b.startYaw());
		Vector3f look = camera.getLookVector(), up = camera.getUpVector(), left = camera.getLeftVector();
		double x = d.x * left.x() + d.y * left.y() + d.z * left.z();
		double y = d.x * up.x() + d.y * up.y() + d.z * up.z();
		double z = d.x * look.x() + d.y * look.y() + d.z * look.z();
		float scale = (float) ((guiHeight / 2.0) / Math.tan(Math.toRadians(fov) / 2));
		if (z < 0.05) {
			// behind (or nearly sideways): just the direction, pushed far out so it gets clamped to the edge
			double len = Math.max(1e-6, Math.hypot(x, y));
			return new float[]{(float) (-x / len * 10000), (float) (-y / len * 10000), 1, scale};
		}
		return new float[]{(float) (-x / z * scale), (float) (-y / z * scale), 0, scale};
	}

	/** 0 = still on target, 1 = about to break: how far the look has drifted from where the bubble opened. */
	public static float strain() {
		Minecraft mc = Minecraft.getInstance();
		BubblePacket b = ClientAuraState.bubble;
		if (b == null || mc.player == null) return 0;
		Vec3 start = Vec3.directionFromRotation(b.startPitch(), b.startYaw());
		double dot = Math.max(-1.0, Math.min(1.0, mc.player.getLookAngle().dot(start)));
		return (float) (Math.toDegrees(Math.acos(dot)) / Math.max(1.0, b.breakDegrees()));
	}
}
