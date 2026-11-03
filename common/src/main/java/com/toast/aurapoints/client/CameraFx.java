package com.toast.aurapoints.client;

import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * CLIENT ONLY. Camera feel for your own big moves: a shake that rattles out (trauma squared, so small
 * hits stay subtle) and a quick zoom punch. Started by your own ability gestures (the pose packets the
 * server already sends), timed to where each move lands. Strength and on/off in the client config;
 * reduced motion turns both off. The Abyssal Bubble's own shake lives in BubbleShake and stays.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class CameraFx {

	private CameraFx() {
	}

	/** Shake in the making (0..1.5) and the zoom punch (fraction of the FOV), easing back to 0. */
	private static float trauma = 0, kick = 0;
	private static long lastFrame = 0;
	/** The pose packet time of the last own move seen, so each starts its effect once. */
	private static long lastSince = -1;
	private static int tick = 0;

	private record Pending(int due, float trauma, float kick) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();

	/** A shake of {@code amount} (0.3 light, 1 heavy) and a zoom punch of {@code kick} (0.05 = 5% wider), {@code delay} ticks from now. */
	public static void add(int delay, float amount, float kick) {
		PENDING.add(new Pending(tick + Math.max(0, delay), amount, kick));
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		tick++;
		if (mc.player == null) {
			PENDING.clear();
			trauma = kick = 0;
			lastSince = -1;
			return;
		}
		long[] s = ClientAuraState.bendState(mc.player.getId());
		if (s != null && s[1] != lastSince) {
			lastSince = s[1];
			if (s[0] == BendingAnimPacket.GESTURE) onGesture((int) s[2], (int) s[3]);
			// Inferno Slam: the impact comes at the end of the windup
			else if (s[0] == BendingAnimPacket.SLAM) add((int) Math.max(2, s[2]), 1.3f, 0.06f);
		}
		for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
			Pending p = it.next();
			if (tick < p.due) continue;
			it.remove();
			trauma = Math.min(1.5f, trauma + p.trauma);
			kick = Math.max(kick, p.kick);
		}
	}

	/** Where each move's weight lands (ticks into the gesture) and how hard. */
	private static void onGesture(int kind, int variant) {
		switch (kind) {
			case BendingAnimPacket.GESTURE_SHOCKWAVE -> add(2, 0.75f, 0.04f);
			case BendingAnimPacket.GESTURE_MAELSTROM -> add(4, 1.0f, 0.05f);
			case BendingAnimPacket.GESTURE_DIVE_LAND -> add(0, 1.0f, 0.05f);
			case BendingAnimPacket.GESTURE_HEAT_WAVE -> add(2, 0.5f, 0.04f);
			case BendingAnimPacket.GESTURE_THUNDERBOLT -> {
				if (variant == 2) add(13, 1.2f, 0.06f);
				else add(variant == 0 ? 6 : 2, 0.3f, 0.02f);
			}
			case BendingAnimPacket.GESTURE_WINTERS_GATE -> add(5, 0.45f, 0f);
			case BendingAnimPacket.GESTURE_ENTANGLE -> add(3, 0.25f, 0f);
			case BendingAnimPacket.GESTURE_PLANT -> add(4, 0.2f, 0f);
			case BendingAnimPacket.GESTURE_DASH -> add(0, 0.12f, 0.07f);
			case BendingAnimPacket.GESTURE_PHANTOM_STEP -> add(1, 0.3f, 0.03f);
			case BendingAnimPacket.GESTURE_DOUBLE_JUMP -> add(0, 0f, 0.035f);
			case BendingAnimPacket.GESTURE_WIND_CHARGE -> add(variant == 2 ? 2 : 1, variant == 2 ? 0.4f : 0.15f, 0.025f);
			case BendingAnimPacket.GESTURE_FIREBALL -> add(3, 0.15f, 0.015f);
			case BendingAnimPacket.GESTURE_CHIDORI -> add(1, 0.35f, 0.09f);
			case BendingAnimPacket.GESTURE_STORM_EYE -> add(4, 0.25f, 0.02f);
			case BendingAnimPacket.GESTURE_ICE_SPIKE -> add(2, 0.2f, 0.02f);
			case BendingAnimPacket.GESTURE_LIGHT -> add(variant == 0 ? 6 : 4, 0.15f, 0.02f);
			case BendingAnimPacket.GESTURE_BLOOD -> add(variant == 0 ? 4 : 3, variant == 0 ? 0.25f : 0.12f, variant == 0 ? 0.04f : 0.01f);
			case BendingAnimPacket.GESTURE_VOID -> add(variant == 1 ? 2 : 6, 0.2f, 0.02f);
			case BendingAnimPacket.GESTURE_EARTH -> {
				if (variant == 2) add(7, 0.65f, 0.03f);
				else add(variant == 0 ? 5 : 4, variant == 0 ? 0.35f : 0.2f, 0.02f);
			}
			// the Ultimates: a punch as each goes off, and the big moment
			case BendingAnimPacket.GESTURE_ULTIMATE -> {
				add(0, 0.4f, 0.06f);
				switch (variant) {
					case 1 -> add(22, 1.3f, 0.08f);
					case 2 -> add(6, 0.5f, 0.08f);
					case 4 -> {
						add(8, 0.9f, 0.04f);
						add(70, 1.2f, 0.05f);
					}
					case 6 -> add(46, 1.5f, 0.08f);
					case 7 -> {
						add(8, 1.0f, 0.06f);
						add(20, 1.4f, 0.05f);
					}
					case 8 -> add(140, 1.1f, 0.07f);
					case 9 -> add(4, 0.6f, 0.03f);
					case 10 -> {
						add(2, 0.5f, 0.03f);
						add(92, 1.6f, 0.08f);
					}
					default -> {
					}
				}
			}
			default -> {
			}
		}
	}

	private static boolean off() {
		return AuraClientConfig.calm();
	}

	@SubscribeEvent
	public static void onAngles(ViewportEvent.ComputeCameraAngles event) {
		long now = System.currentTimeMillis();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		trauma = Math.max(0f, trauma - dt * 1.7f);
		kick = Math.max(0f, kick - dt * 0.35f);
		float strength = (float) (double) AuraClientConfig.CAMERA_SHAKE.get();
		if (trauma <= 0 || strength <= 0 || off()) return;
		float shake = trauma * trauma * strength;
		float t = now / 1000f;
		// three out-of-step wobbles, so it never looks like a pattern
		event.setYaw(event.getYaw() + shake * 2.2f * (Mth.sin(t * 37f) * 0.6f + Mth.sin(t * 61f) * 0.4f));
		event.setPitch(event.getPitch() + shake * 1.8f * (Mth.sin(t * 43f + 1.3f) * 0.6f + Mth.sin(t * 71f) * 0.4f));
		event.setRoll(event.getRoll() + shake * 1.4f * Mth.sin(t * 29f + 2.1f));
	}

	@SubscribeEvent
	public static void onFov(ViewportEvent.ComputeFov event) {
		if (kick <= 0 || !event.usedConfiguredFov() || !AuraClientConfig.FOV_KICK.get() || off()) return;
		// out fast, back slowly: the punch is the sharp start of the kick
		event.setFOV(event.getFOV() * (1.0 + Math.min(0.12f, kick)));
	}
}
