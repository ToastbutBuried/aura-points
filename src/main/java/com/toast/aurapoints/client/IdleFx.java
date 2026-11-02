package com.toast.aurapoints.client;

import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.BendingAnimPacket.Idle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CLIENT ONLY. The particles and sounds of the idle animations (IdleGestures), on the same timeline:
 * every client plays them itself for every player it sees idling, at that player's hands as the
 * animation has them right now (ArmPose), so they always sit on the hand and never lag.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class IdleFx {

	private IdleFx() {
	}

	/** Per idling player: which idle, the last tick done, and what an idle keeps (a doodle's points, a whirl's start). */
	private static final class State {
		int idle = -1, lastT = -1;
		final List<Vec3> points = new ArrayList<>();
		Vec3 anchor;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();

	private static DustParticleOptions dust(int rgb, float size) {
		return new DustParticleOptions(new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f), size);
	}

	private static final DustParticleOptions EMBER = dust(0xFF8A2A, 0.6f), TRACE = dust(0xFF7A1A, 0.35f), TRACE_HOT = dust(0xFFD040, 0.3f), FIRE_DUST = dust(0xFFB040, 0.8f), WATER = dust(0x5FC8FF, 0.7f),
			BUBBLE = dust(0x9FE6FF, 1.1f), BUBBLE_SKIN = dust(0xBFF0FF, 0.55f), AIR = dust(0xF4FBFF, 0.5f), FROST = dust(0xBDEBFF, 0.7f),
			ICE = dust(0xE8FAFF, 0.9f), VINE = dust(0x3FA34D, 0.6f), LEAF = dust(0x7CD957, 1.0f), BREATH = dust(0xEEF4FA, 0.55f),
			VORTEX = dust(0xDDF6FF, 0.45f), SHADOW_DUST = dust(0x2A1040, 0.8f);
	private static final DustParticleOptions[] PETALS = {dust(0xE0303A, 0.9f), dust(0xFFD640, 0.9f), dust(0x5A8CFF, 0.9f), dust(0xFF9AD5, 0.9f), dust(0xFFFFFF, 0.9f)};

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.isPaused()) return;
		if (!AuraClientConfig.SHOW_IDLE_ANIMATIONS.get()) {
			STATES.clear();
			return;
		}
		Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
		for (AbstractClientPlayer p : mc.level.players()) {
			BendingAnimation anim = BendingAnimation.of(p);
			int idle = anim == null ? -1 : anim.idleVariant(), t = anim == null ? -1 : anim.idleTick();
			if (idle < 0 || t < 0 || p.isInvisible() || p.isSpectator() || p.distanceToSqr(camera) > 48 * 48) {
				STATES.remove(p.getId());
				continue;
			}
			// your own idle in first person with the hands not shown: no effects in front of your face
			if (p == mc.player && mc.options.getCameraType().isFirstPerson() && !AuraClientConfig.IDLE_FIRST_PERSON.get()) continue;
			State st = STATES.computeIfAbsent(p.getId(), k -> new State());
			if (st.idle != idle || t < st.lastT) {
				st.idle = idle;
				st.lastT = t - 1;
				st.points.clear();
				st.anchor = null;
			}
			// every tick since the last one (at most a few, if this client stuttered)
			for (int k = Math.max(st.lastT + 1, t - 3); k <= t; k++) play(mc, p, st, Idle.byId(idle), k);
			st.lastT = t;
		}
		STATES.keySet().removeIf(id -> mc.level.getEntity(id) == null);
	}

	/** Shadow's Flicker: the figure blinks out for a frame here and there. */
	private static final Set<Integer> FLICKER = Set.of(28, 29, 34, 39, 40, 46);

	@SubscribeEvent
	public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
		if (!(event.getEntity() instanceof AbstractClientPlayer p) || !AuraClientConfig.SHOW_IDLE_ANIMATIONS.get()) return;
		BendingAnimation anim = BendingAnimation.of(p);
		if (anim != null && anim.idleVariant() == Idle.SHADOW_FLICKER.ordinal() && FLICKER.contains(anim.idleTick())) event.setCanceled(true);
	}

	// ---------------------------------------------------------------
	// The idles' effects, tick by tick
	// ---------------------------------------------------------------

	private static void play(Minecraft mc, AbstractClientPlayer p, State st, Idle idle, int t) {
		if (idle == null) return;
		RandomSource r = p.getRandom();
		Vec3 fwd = ArmPose.forward(p, 1f), right = ArmPose.right(p, 1f), feet = ArmPose.feet(p, 1f);
		switch (idle) {
			case FIRE_DOODLE -> {
				if (t == 8) sound(mc, p, SoundEvents.FIRECHARGE_USE, 0.15f, 1.8f);
				// the fingertip's path, three points a tick so the line is unbroken
				if (t >= 8 && t <= 39) for (float f = 0f; f < 1f; f += 1 / 3f) st.points.add(ArmPose.fingertip(p, true, f));
				if (t >= 8 && t < 62) {
					// the heart hangs in the air, flickering
					for (int i = t % 2; i < st.points.size(); i += 2) {
						Vec3 q = st.points.get(i);
						add(mc, r.nextInt(4) == 0 ? TRACE_HOT : TRACE, q, 0, 0, 0);
						if (r.nextInt(14) == 0) add(mc, ParticleTypes.SMALL_FLAME, q, 0, 0.002, 0);
					}
				}
				if (t == 62) {
					for (Vec3 q : st.points) add(mc, ParticleTypes.SMOKE, q, fwd.x * 0.02, 0.01, fwd.z * 0.02);
					st.points.clear();
					sound(mc, p, SoundEvents.FIRE_EXTINGUISH, 0.3f, 1.5f);
				}
			}
			case FIRE_HOT_HANDS -> {
				Vec3 hand = ArmPose.hand(p, true, 1f);
				if (t >= 12 && t < 40) {
					int n = 1 + (t - 12) / 7;
					for (int i = 0; i < n; i++) add(mc, ParticleTypes.FLAME, jitter(hand, r, 0.07), 0, 0.01, 0);
					if (t % 3 == 0) add(mc, ParticleTypes.SMALL_FLAME, jitter(hand, r, 0.05), 0, 0.02, 0);
				}
				if (t == 40) {
					for (int i = 0; i < 14; i++) add(mc, ParticleTypes.FLAME, jitter(hand, r, 0.15), r.nextGaussian() * 0.03, 0.02 + r.nextDouble() * 0.03, r.nextGaussian() * 0.03);
					for (int i = 0; i < 3; i++) add(mc, ParticleTypes.LAVA, hand, 0, 0, 0);
					sound(mc, p, SoundEvents.FIRECHARGE_USE, 0.35f, 1.0f);
				}
				if (t == 41) sound(mc, p, SoundEvents.PLAYER_HURT_ON_FIRE, 0.35f, 1.35f);
				if (t > 40 && t < 82) {
					for (int i = 0; i < 2; i++) add(mc, ParticleTypes.FLAME, jitter(hand, r, 0.08), 0, 0.01, 0);
					if (t % 4 == 0) add(mc, ParticleTypes.SMOKE, jitter(hand, r, 0.08), 0, 0.02, 0);
				}
				if (t >= 84 && t <= 90) {
					// blowing on it: breath from the mouth onto the hand, the flame dying
					breath(mc, ArmPose.mouth(p, 1f), hand.subtract(ArmPose.mouth(p, 1f)).normalize(), 2, r);
					if (t < 88) add(mc, ParticleTypes.FLAME, jitter(hand, r, 0.05), 0, 0.01, 0);
				}
				if (t == 90) {
					for (int i = 0; i < 6; i++) add(mc, ParticleTypes.SMOKE, jitter(hand, r, 0.06), 0, 0.03, 0);
					sound(mc, p, SoundEvents.FIRE_EXTINGUISH, 0.35f, 1.3f);
				}
				if (t > 90 && t < 106 && t % 2 == 0) add(mc, ParticleTypes.SMOKE, jitter(hand, r, 0.05), 0, 0.03, 0);
				if (t == 99) breath(mc, ArmPose.mouth(p, 1f), fwd.add(0, -0.4, 0).normalize(), 4, r);
			}
			case FIRE_JUGGLE -> {
				Vec3 ball = juggleBall(p, t, 1f);
				// a thin trail of embers behind it while it flies
				if (ball != null && juggleFlying(t) && t % 2 == 0) add(mc, TRACE, ball, 0, 0, 0);
				if (t == 13 || t == 31 || t == 49) sound(mc, p, SoundEvents.FIRECHARGE_USE, 0.12f, 1.9f);
				if (t == 70) {
					Vec3 fist = ArmPose.hand(p, false, 1f);
					for (int i = 0; i < 8; i++) add(mc, ParticleTypes.SMOKE, jitter(fist, r, 0.08), 0, 0.03, 0);
					sound(mc, p, SoundEvents.FIRE_EXTINGUISH, 0.25f, 1.7f);
				}
			}
			case WATER_BUBBLES -> {
				Vec3 cup = ArmPose.fingertip(p, true, 1f).add(ArmPose.fingertip(p, false, 1f)).scale(0.5);
				if (t == 14) sound(mc, p, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.25f, 1.5f);
				if (t >= 14 && t <= 34 && t % 2 == 0) {
					Vec3 b = jitter(cup, r, 0.05);
					add(mc, BUBBLE, b, fwd.x * 0.4 + r.nextGaussian() * 0.05, 0.3, fwd.z * 0.4 + r.nextGaussian() * 0.05);
				}
				if (t >= 34 && t < 46) {
					// the big one rises to where the finger will poke it
					Vec3 target = feet.add(0, 1.92 * p.getScale(), 0).add(fwd.scale(0.44)).add(right.scale(0.3));
					if (st.anchor == null) st.anchor = cup;
					double u = Math.min(1, (t - 34) / 10.0);
					u = u * u * (3 - 2 * u);
					sphere(mc, st.anchor.lerp(target, u), 0.16, r);
				}
				if (t == 46) {
					Vec3 tip = ArmPose.fingertip(p, true, 1f);
					for (int i = 0; i < 12; i++) add(mc, ParticleTypes.SPLASH, jitter(tip, r, 0.15), 0, 0.1, 0);
					for (int i = 0; i < 6; i++) add(mc, ParticleTypes.BUBBLE_POP, jitter(tip, r, 0.12), 0, 0, 0);
					sound(mc, p, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, 0.6f, 1.2f);
				}
			}
			case WATER_FLOW -> {
				if (t == 10 || t == 50) sound(mc, p, SoundEvents.WATER_AMBIENT, 0.12f, 1.4f);
				if (t >= 8 && t <= 86) {
					for (boolean side : new boolean[]{true, false}) {
						Vec3 tip = ArmPose.fingertip(p, side, 1f);
						add(mc, WATER, tip, 0, 0, 0);
						if (t % 4 == (side ? 0 : 2)) add(mc, ParticleTypes.FALLING_WATER, tip, 0, 0, 0);
					}
				}
			}
			case WATER_WET_DOG -> {
				if (t == 10) sound(mc, p, SoundEvents.WOLF_SHAKE, 0.5f, 1.1f);
				if (t >= 10 && t <= 40) {
					Vec3 chest = feet.add(0, 1.15 * p.getScale(), 0);
					for (int i = 0; i < 4; i++) {
						double a = r.nextDouble() * Math.PI * 2;
						Vec3 o = new Vec3(Math.cos(a) * 0.35, (r.nextDouble() - 0.5) * 0.7, Math.sin(a) * 0.35);
						add(mc, ParticleTypes.SPLASH, chest.add(o), o.x * 0.5, 0.12, o.z * 0.5);
					}
				}
				if (t >= 46 && t <= 57 && t % 2 == 0) {
					add(mc, ParticleTypes.FALLING_WATER, ArmPose.fingertip(p, true, 1f), 0, 0, 0);
					add(mc, ParticleTypes.FALLING_WATER, ArmPose.fingertip(p, false, 1f), 0, 0, 0);
				}
			}
			case WIND_WHIRL -> {
				if (t == 14) sound(mc, p, SoundEvents.BREEZE_IDLE_AIR, 0.3f, 1.6f);
				if (t >= 12 && t < 56) {
					Vec3 c = ArmPose.fingertip(p, true, 1f).add(0, 0.12, 0);
					double rad = 0.06 + 0.25 * Math.min(1, (t - 12) / 40.0);
					whirl(mc, c, rad, t, 3, r);
					st.anchor = c;
				}
				if (t == 56) sound(mc, p, SoundEvents.BREEZE_SHOOT, 0.3f, 1.5f);
				if (t >= 56 && t <= 76 && st.anchor != null) {
					// flicked away to the right, growing as it goes
					Vec3 dir = right.add(fwd).normalize();
					Vec3 c = st.anchor.add(dir.scale((t - 56) * 0.22)).add(0, (t - 56) * 0.02, 0);
					whirl(mc, c, 0.31 + (t - 56) * 0.02, t, 5, r);
				}
			}
			case WIND_LEAN -> {
				if (t >= 14 && t <= 95 && t % 6 == 0) {
					Vec3 from = feet.add(right.scale(-1.6)).add(fwd.scale(r.nextDouble() - 0.5)).add(0, 0.5 + r.nextDouble() * 1.3, 0);
					add(mc, ParticleTypes.CLOUD, from, right.x * 0.09, 0, right.z * 0.09);
				}
				if (t >= 14 && t <= 95 && t % 3 == 0) {
					// the cushion of air behind the back
					add(mc, AIR, feet.add(0, 1.0 + r.nextDouble() * 0.5, 0).add(fwd.scale(-0.45)).add(right.scale((r.nextDouble() - 0.5) * 0.5)), 0, 0.1, 0);
				}
			}
			case WIND_HAT -> {
				if (t == 9) sound(mc, p, SoundEvents.WIND_CHARGE_BURST.value(), 0.25f, 0.8f);
				if (t >= 8 && t <= 32) {
					// streaks of wind rushing past, right to left
					for (int i = 0; i < 2; i++) {
						Vec3 from = feet.add(right.scale(1.8 - r.nextDouble() * 3.6)).add(fwd.scale(r.nextDouble() * 1.6 - 0.8)).add(0, 0.4 + r.nextDouble() * 1.6, 0);
						for (int k = 0; k < 4; k++) add(mc, AIR, from.add(right.scale(k * 0.12)), 0, 0, 0);
					}
					if (t % 3 == 0) {
						Vec3 from = feet.add(right.scale(2.0)).add(0, 0.6 + r.nextDouble() * 1.4, 0);
						add(mc, ParticleTypes.CLOUD, from, -right.x * 0.6, 0, -right.z * 0.6);
					}
				}
			}
			case FROST_BREATH -> {
				Vec3 cup = ArmPose.fingertip(p, true, 1f).add(ArmPose.fingertip(p, false, 1f)).scale(0.5);
				if (t >= 16 && t <= 19 || t >= 30 && t <= 33 || t >= 44 && t <= 47) {
					breath(mc, cup, fwd, 2, r);
					if (t % 2 == 0) add(mc, ParticleTypes.SNOWFLAKE, jitter(cup, r, 0.1), 0, 0, 0);
				}
				if (t >= 57 && t <= 86 && t % 3 == 0) {
					add(mc, ParticleTypes.SNOWFLAKE, jitter(cup, r, 0.12), 0, -0.02, 0);
					sound(mc, p, SoundEvents.WOOL_HIT, 0.12f, 1.6f);
				}
			}
			case FROST_SHIVER -> {
				if (t >= 12 && t <= 60 && t % 2 == 0) sound(mc, p, SoundEvents.NOTE_BLOCK_HAT.value(), 0.08f, 2.0f);
				if (t >= 10 && t <= 63) {
					Vec3 head = p.getEyePosition(1f);
					double a = r.nextDouble() * Math.PI * 2;
					add(mc, ParticleTypes.SNOWFLAKE, head.add(Math.cos(a) * 0.6, 0.3 + r.nextDouble() * 0.4, Math.sin(a) * 0.6), 0, -0.02, 0);
				}
				if (t >= 12 && t <= 60 && t % 10 == 0) breath(mc, ArmPose.mouth(p, 1f), fwd, 3, r);
			}
			case FROST_SNOWFLAKE -> {
				Vec3 palm = ArmPose.hand(p, true, 1f).add(0, 0.12, 0);
				if (t >= 12 && t <= 48) {
					// three arms of a spiral turning over the palm, drawn in as it goes
					double shrink = 1 - (t - 12) / 40.0;
					for (int arm = 0; arm < 3; arm++) {
						for (int j = 0; j < 4; j++) {
							double rad = (0.1 + 0.17 * j) * shrink, a = t * 0.35 + arm * Math.PI * 2 / 3 + j * 0.55;
							add(mc, VORTEX, palm.add(Math.cos(a) * rad, 0.04 * j, Math.sin(a) * rad), 0, 0, 0);
						}
					}
					if (t % 3 == 0) add(mc, ParticleTypes.SNOWFLAKE, jitter(palm.add(0, 0.25, 0), r, 0.2), 0, -0.02, 0);
				}
				if (t >= 40 && t < 56) {
					// the crystal forming: a slowly turning six-point star
					for (int k = 0; k < 6; k++) {
						double a = t * 0.15 + k * Math.PI / 3;
						add(mc, k % 2 == 0 ? ICE : FROST, palm.add(right.scale(Math.cos(a) * 0.1)).add(0, Math.sin(a) * 0.1, 0), 0, 0, 0);
					}
				}
				if (t == 56) {
					Vec3 fist = ArmPose.hand(p, true, 1f);
					for (int i = 0; i < 14; i++) add(mc, ParticleTypes.ITEM_SNOWBALL, fist, r.nextGaussian() * 0.12, r.nextDouble() * 0.15, r.nextGaussian() * 0.12);
					for (int i = 0; i < 10; i++) add(mc, ParticleTypes.SNOWFLAKE, fist, r.nextGaussian() * 0.05, r.nextDouble() * 0.05, r.nextGaussian() * 0.05);
					sound(mc, p, SoundEvents.GLASS_BREAK, 0.3f, 1.7f);
				}
			}
			case SHADOW_PUPPET -> {
				Vec3 hands = ArmPose.fingertip(p, true, 1f).add(ArmPose.fingertip(p, false, 1f)).scale(0.5);
				if (t >= 20 && t <= 44 && t % 2 == 0) add(mc, ParticleTypes.SMOKE, jitter(hands, r, 0.05), 0, 0.01, 0);
				if (t == 48) sound(mc, p, SoundEvents.WOLF_HOWL, 0.12f, 1.6f);
				if (t == 60) {
					// the shadow runs off, leaving a wisp
					Vec3 at = wolfCentre(p, 1f, t);
					for (int i = 0; i < 8; i++) add(mc, ParticleTypes.SMOKE, jitter(at, r, 0.4), 0, 0.02, 0);
				}
			}
			case SHADOW_PARANOID -> {
				if (t >= 10 && t <= 64 && t % 2 == 0) {
					double a = r.nextDouble() * Math.PI * 2;
					add(mc, ParticleTypes.SMOKE, feet.add(Math.cos(a) * 0.5, 0.05, Math.sin(a) * 0.5), 0, 0.005, 0);
				}
				if (t == 70) breath(mc, ArmPose.mouth(p, 1f), fwd.add(0, -0.3, 0).normalize(), 4, r);
			}
			case SHADOW_FLICKER -> {
				if (t == 12) sound(mc, p, SoundEvents.SOUL_ESCAPE.value(), 0.4f, 0.8f);
				if (t >= 12 && t <= 52) {
					for (int k = 0; k < 2; k++) {
						double a = t * 0.5 + k * Math.PI, h = ((t - 12) * 0.06) % 2.0;
						Vec3 q = feet.add(Math.cos(a) * 0.5, h * p.getScale(), Math.sin(a) * 0.5);
						add(mc, SHADOW_DUST, q, 0, 0, 0);
						if (t % 3 == k) add(mc, ParticleTypes.SMOKE, q, 0, 0.01, 0);
					}
				}
				if (FLICKER.contains(t)) sound(mc, p, SoundEvents.PHANTOM_FLAP, 0.1f, 1.8f);
				if (t == 52) {
					for (int i = 0; i < 10; i++) add(mc, ParticleTypes.SMOKE, feet.add(0, r.nextDouble() * 1.8, 0), r.nextGaussian() * 0.06, 0.02, r.nextGaussian() * 0.06);
					for (int i = 0; i < 12; i++) add(mc, SHADOW_DUST, feet.add(r.nextGaussian() * 0.3, r.nextDouble() * 1.8, r.nextGaussian() * 0.3), 0, 0, 0);
				}
			}
			case VERDANT_SPROUT -> {
				// under the right palm, pressed to the ground
				Vec3 spot = feet.add(fwd.scale(0.7)).add(right.scale(0.3)).add(0, 0.05, 0);
				if (t >= 16 && t <= 40) {
					add(mc, ParticleTypes.HAPPY_VILLAGER, jitter(spot, r, 0.3), 0, 0, 0);
					double rad = 0.2 + 0.6 * (t - 16) / 24.0;
					for (int k = 0; k < 8; k++) {
						double a = k * Math.PI / 4 + t * 0.05;
						add(mc, VINE, spot.add(Math.cos(a) * rad, 0.02, Math.sin(a) * rad), 0, 0, 0);
					}
				}
				if (t == 28) {
					for (int i = 0; i < 12; i++) add(mc, ParticleTypes.CHERRY_LEAVES, jitter(spot.add(0, 0.4, 0), r, 0.35), 0, 0, 0);
					for (int i = 0; i < 6; i++) add(mc, ParticleTypes.SPORE_BLOSSOM_AIR, jitter(spot.add(0, 0.3, 0), r, 0.3), 0, 0, 0);
					sound(mc, p, SoundEvents.FLOWERING_AZALEA_PLACE, 0.6f, 1.2f);
				}
				if (t >= 28 && t <= 84 && t % 2 == 0) {
					// five little flowers stay up a while
					for (int k = 0; k < 5; k++) {
						double a = k * Math.PI * 2 / 5 + 0.3;
						Vec3 f = spot.add(Math.cos(a) * 0.35, 0, Math.sin(a) * 0.35);
						add(mc, PETALS[k], f.add(0, 0.14, 0), 0, 0, 0);
						add(mc, VINE, f.add(0, 0.05, 0), 0, 0, 0);
					}
				}
				if (t == 55) sound(mc, p, SoundEvents.SNIFFER_SNIFFING, 0.25f, 1.6f);
				if (t == 60) {
					add(mc, ParticleTypes.HEART, p.getEyePosition(1f).add(0, 0.5, 0), 0, 0, 0);
					add(mc, ParticleTypes.HEART, p.getEyePosition(1f).add(fwd.scale(0.3)).add(0, 0.35, 0), 0, 0, 0);
				}
			}
			case VERDANT_STRETCH -> {
				if (t >= 15 && t <= 60 && t % 2 == 0) {
					double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble();
					Vec3 q = feet.add(Math.cos(a) * d, (2.3 + r.nextDouble() * 0.6) * p.getScale(), Math.sin(a) * d);
					add(mc, LEAF, q, 0, -0.1, 0);
					if (r.nextInt(3) == 0) add(mc, ParticleTypes.FALLING_SPORE_BLOSSOM, q, 0, 0, 0);
				}
			}
			case VERDANT_VINE -> {
				if (t >= 12 && t <= 80 && t % 2 == 0) {
					// a vine curling up the left arm from the fingers, leaves every so often
					double grown = Math.min(1, (t - 12) / 36.0);
					float mid = ArmPose.middle(p, false);
					for (int i = 0; i <= 24; i++) {
						double s = i / 24.0;
						if (s > grown) break;
						double a = s * 14;
						Vec3 q = ArmPose.point(p, false, 1f, mid + (float) Math.cos(a) * 2.6f, 10.5f - (float) s * 11.5f, (float) Math.sin(a) * 2.6f);
						add(mc, i % 6 == 3 ? LEAF : VINE, q, 0, 0, 0);
					}
				}
				if (t == 55 || t == 61 || t == 67) {
					Vec3 pat = ArmPose.point(p, false, 1f, ArmPose.middle(p, false), 6f, -2.5f);
					add(mc, ParticleTypes.HAPPY_VILLAGER, jitter(pat, r, 0.08), 0, 0, 0);
					add(mc, ParticleTypes.HAPPY_VILLAGER, jitter(pat, r, 0.08), 0, 0, 0);
					sound(mc, p, SoundEvents.MOSS_HIT, 0.3f, 1.4f);
				}
			}
			case THUNDER_ZAP -> {
				Vec3 hands = ArmPose.hand(p, true, 1f).add(ArmPose.hand(p, false, 1f)).scale(0.5);
				if (t >= 10 && t <= 37) {
					int n = 1 + (t - 10) / 9;
					for (int i = 0; i < n; i++) add(mc, ParticleTypes.ELECTRIC_SPARK, jitter(hands, r, 0.08), 0, 0, 0);
				}
				if (t == 38) {
					add(mc, ParticleTypes.FLASH, hands, 0, 0, 0);
					for (int i = 0; i < 20; i++) add(mc, ParticleTypes.ELECTRIC_SPARK, hands, r.nextGaussian() * 0.3, r.nextGaussian() * 0.3, r.nextGaussian() * 0.3);
					for (int i = 0; i < 4; i++) add(mc, ParticleTypes.END_ROD, hands, r.nextGaussian() * 0.1, r.nextGaussian() * 0.1, r.nextGaussian() * 0.1);
					sound(mc, p, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.12f, 1.9f);
				}
				if (t == 39) sound(mc, p, SoundEvents.PLAYER_HURT, 0.3f, 1.4f);
				if (t >= 42 && t <= 61) add(mc, ParticleTypes.ELECTRIC_SPARK, jitter(ArmPose.hand(p, true, 1f), r, 0.06), 0, 0, 0);
			}
			case THUNDER_KNUCKLES -> {
				if (t == 18 || t == 24 || t == 30) {
					Vec3 hands = ArmPose.hand(p, true, 1f).add(ArmPose.hand(p, false, 1f)).scale(0.5);
					for (int i = 0; i < 6; i++) add(mc, ParticleTypes.ELECTRIC_SPARK, jitter(hands, r, 0.1), 0, 0, 0);
					sound(mc, p, SoundEvents.BONE_BLOCK_BREAK, 0.35f, 1.9f);
				}
				if (t == 58 || t == 62) {
					Vec3 neck = feet.add(0, 1.45 * p.getScale(), 0);
					for (int i = 0; i < 5; i++) add(mc, ParticleTypes.ELECTRIC_SPARK, jitter(neck, r, 0.12), 0, 0, 0);
					sound(mc, p, SoundEvents.BONE_BLOCK_BREAK, 0.3f, 1.5f);
				}
			}
			case THUNDER_OVERCHARGE -> {
				if (t >= 12 && t <= 61) {
					int n = 1 + (t - 12) / 10;
					for (int i = 0; i < n; i++) {
						boolean side = r.nextBoolean();
						Vec3 q = ArmPose.point(p, side, 1f, ArmPose.middle(p, side) + (r.nextFloat() - 0.5f) * 4.5f, -1 + r.nextFloat() * 12, (r.nextFloat() - 0.5f) * 4.5f);
						add(mc, ParticleTypes.ELECTRIC_SPARK, q, 0, 0, 0);
						if (t >= 30 && t % 3 == 0 && i == 0) add(mc, ParticleTypes.END_ROD, q, 0, 0.01, 0);
					}
				}
				if (t == 62) {
					Vec3 chest = feet.add(0, 1.2 * p.getScale(), 0).add(fwd.scale(0.2));
					add(mc, ParticleTypes.FLASH, chest, 0, 0, 0);
					for (int i = 0; i < 30; i++) add(mc, ParticleTypes.ELECTRIC_SPARK, chest, r.nextGaussian() * 0.35, r.nextGaussian() * 0.35, r.nextGaussian() * 0.35);
					sound(mc, p, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.12f, 1.5f);
				}
			}
			case WAITING -> {
				if (t == 12 || t == 16) sound(mc, p, SoundEvents.UI_BUTTON_CLICK.value(), 0.1f, t == 12 ? 2.0f : 1.7f);
				if (t >= 42 && t <= 82 && (t - 42) % 8 == 0) {
					var below = p.level().getBlockState(p.blockPosition().below());
					sound(mc, p, below.getSoundType().getStepSound(), 0.15f, 1.1f);
				}
			}
			default -> {
			}
		}
	}

	/** {from, to, starts in the right hand} for each toss. */
	private static final int[][] FLIGHTS = {{13, 23, 1}, {31, 41, 0}, {49, 59, 1}};

	private static boolean juggleFlying(float t) {
		for (int[] f : FLIGHTS) if (t >= f[0] && t <= f[1]) return true;
		return false;
	}

	/** The juggled flame {@code t} ticks in: in a hand, or flying between them in a low arc (null when there's none). */
	private static Vec3 juggleBall(AbstractClientPlayer p, float t, float partial) {
		for (int[] f : FLIGHTS) {
			if (t >= f[0] && t <= f[1]) {
				Vec3 a = palm(p, f[2] == 1, partial), b = palm(p, f[2] != 1, partial);
				double u = (t - f[0]) / (double) (f[1] - f[0]);
				return a.lerp(b, u).add(0, 0.35 * 4 * u * (1 - u), 0);
			}
		}
		if (t >= 8 && t < 13 || t > 41 && t < 49) return palm(p, true, partial);
		if (t > 23 && t < 31 || t > 59 && t < 70) return palm(p, false, partial);
		return null;
	}

	private static Vec3 palm(AbstractClientPlayer p, boolean right, float partial) {
		return ArmPose.fingertip(p, right, partial).add(0, 0.1, 0);
	}

	/** A wisp of breath from {@code from}, drifting along {@code dir}. */
	private static void breath(Minecraft mc, Vec3 from, Vec3 dir, int count, RandomSource r) {
		for (int i = 0; i < count; i++) add(mc, BREATH, jitter(from, r, 0.04), dir.x * 0.5 + r.nextGaussian() * 0.05, dir.y * 0.5 + 0.05, dir.z * 0.5 + r.nextGaussian() * 0.05);
	}

	// ---------------------------------------------------------------
	// Shadow Puppet: the wolf's shadow on the ground in front, its jaw going with the hands, howling, then running off
	// ---------------------------------------------------------------

	private static final net.minecraft.resources.ResourceLocation[] WOLF = {
			net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("aurapoints", "textures/misc/shadow_wolf_closed.png"),
			net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("aurapoints", "textures/misc/shadow_wolf_open.png"),
			net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("aurapoints", "textures/misc/shadow_wolf_howl.png")};

	private static final net.minecraft.world.item.ItemStack FIRE_CHARGE = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FIRE_CHARGE);

	/** Where the shadow lies, {@code t} ticks in: in front of the feet, running off at the end. */
	private static Vec3 wolfCentre(AbstractClientPlayer p, float partial, float t) {
		Vec3 fwd = ArmPose.forward(p, partial);
		return ArmPose.feet(p, partial).add(fwd.scale(2.2 + Math.max(0, t - 58) * 0.12)).add(0, 0.02, 0);
	}

	@SubscribeEvent
	public static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
		if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !AuraClientConfig.SHOW_IDLE_ANIMATIONS.get()) return;
		float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
		Vec3 cam = event.getCamera().getPosition();
		var pose = event.getPoseStack();
		var buffers = mc.renderBuffers().bufferSource();
		boolean drew = false;
		for (AbstractClientPlayer p : mc.level.players()) {
			BendingAnimation anim = BendingAnimation.of(p);
			if (anim != null && anim.idleVariant() == Idle.FIRE_JUGGLE.ordinal() && !p.isInvisible() && p.distanceToSqr(cam) < 48 * 48) {
				float t = anim.idleTick() + partial;
				Vec3 ball = juggleBall(p, t, partial);
				if (ball != null) {
					pose.pushPose();
					pose.translate(ball.x - cam.x, ball.y - cam.y, ball.z - cam.z);
					pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(t * 25f));
					pose.scale(0.32f, 0.32f, 0.32f);
					mc.getItemRenderer().renderStatic(FIRE_CHARGE, net.minecraft.world.item.ItemDisplayContext.FIXED, 0xF000F0,
							net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0);
					pose.popPose();
					drew = true;
				}
			}
			if (anim == null || anim.idleVariant() != Idle.SHADOW_PUPPET.ordinal() || p.isInvisible()) continue;
			float t = anim.idleTick() + partial;
			if (t < 16 || t > 66 || p.distanceToSqr(cam) > 48 * 48) continue;
			float alpha = Math.max(0, Math.min(1, Math.min((t - 16) / 6f, (66 - t) / 6f)));
			// the jaw snaps with the hands (open on every other 4 ticks), the snout up for the howl
			int frame = t >= 46 && t < 60 ? 2 : t >= 20 && t < 44 && ((int) (t - 20) / 4) % 2 == 0 ? 1 : 0;
			Vec3 c = wolfCentre(p, partial, t), fwd = ArmPose.forward(p, partial), right = ArmPose.right(p, partial);
			var vc = buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityTranslucent(WOLF[frame]));
			var last = pose.last();
			double w = 2.0, d = 1.5;
			// counter-clockwise seen from above, so the face that's drawn is the top one
			corner(vc, last, c.add(right.scale(-w / 2)).add(fwd.scale(d / 2)), cam, 0, 0, alpha);
			corner(vc, last, c.add(right.scale(-w / 2)).add(fwd.scale(-d / 2)), cam, 0, 1, alpha);
			corner(vc, last, c.add(right.scale(w / 2)).add(fwd.scale(-d / 2)), cam, 1, 1, alpha);
			corner(vc, last, c.add(right.scale(w / 2)).add(fwd.scale(d / 2)), cam, 1, 0, alpha);
			drew = true;
		}
		if (drew) buffers.endBatch();
	}

	private static void corner(com.mojang.blaze3d.vertex.VertexConsumer vc, com.mojang.blaze3d.vertex.PoseStack.Pose pose, Vec3 at, Vec3 cam,
							   float u, float v, float alpha) {
		vc.addVertex(pose, (float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z)).setColor(1f, 1f, 1f, alpha).setUv(u, v)
				.setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0f, 1f, 0f);
	}

	/** A bubble's skin: a ring of light dots round {@code c}, turning. */
	private static void sphere(Minecraft mc, Vec3 c, double radius, RandomSource r) {
		for (int k = 0; k < 10; k++) {
			Vec3 d = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().scale(radius);
			add(mc, BUBBLE_SKIN, c.add(d), 0, 0, 0);
		}
		add(mc, dust(0xFFFFFF, 0.5f), c.add(-radius * 0.4, radius * 0.5, 0), 0, 0, 0);
	}

	/** A tiny whirlwind: dots going round a rising spiral. */
	private static void whirl(Minecraft mc, Vec3 c, double radius, int t, int count, RandomSource r) {
		for (int k = 0; k < count; k++) {
			double a = t * 0.8 + k * Math.PI * 2 / count;
			add(mc, AIR, c.add(Math.cos(a) * radius, k * 0.06, Math.sin(a) * radius), 0, 0, 0);
		}
		if (t % 4 == 0) add(mc, ParticleTypes.CLOUD, c, 0, 0.01, 0);
	}

	private static Vec3 jitter(Vec3 v, RandomSource r, double by) {
		return v.add((r.nextDouble() - 0.5) * 2 * by, (r.nextDouble() - 0.5) * 2 * by, (r.nextDouble() - 0.5) * 2 * by);
	}

	private static void add(Minecraft mc, ParticleOptions particle, Vec3 at, double vx, double vy, double vz) {
		mc.level.addParticle(particle, at.x, at.y, at.z, vx, vy, vz);
	}

	private static void sound(Minecraft mc, AbstractClientPlayer p, SoundEvent sound, float volume, float pitch) {
		float scale = AuraClientConfig.ABILITY_VOLUME.get().floatValue();
		if (scale <= 0) return;
		mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), sound, SoundSource.PLAYERS, volume * scale, pitch, false);
	}
}
