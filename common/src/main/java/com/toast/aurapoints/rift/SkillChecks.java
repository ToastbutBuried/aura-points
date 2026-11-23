package com.toast.aurapoints.rift;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.FxPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Hold-and-release skill checks (5.52): hold Use on something, a bar fills faster and faster, let go inside the bright
 * zone. The client draws and judges it (client/SkillCheckHud), so ping costs no hit; the server only takes an answer to
 * the player's own open check, once, not sooner than the bar could have got there. One check per player at a time.
 */
public final class SkillChecks {

	private SkillChecks() {
	}

	public static final int EARLY = 0, GOOD = 1, LATE = 2, CANCEL = 3;

	/** Ticks the bar takes, and the zone's width, per tier (Easy .. Hardcore). */
	private static final int[] TICKS = {54, 50, 46, 42, 38};
	private static final float[] WIDTH = {0.24f, 0.20f, 0.17f, 0.15f, 0.13f};
	private static final double RANGE = 6;

	private static final class Open {
		int id, ticks;
		/** Game tick (looks) and wall clock ms (the client counts real time, a lagging server does not). */
		long start, ms;
		float lo;
		Vec3 at;
		boolean rub;
		IntConsumer result;
		BooleanSupplier still;
		ServerPlayer player;
	}

	private static final Map<UUID, Open> OPEN = new HashMap<>();
	private static final Map<UUID, Long> REST = new HashMap<>();
	/** Bad releases in a row: the zone widens after two. */
	private static final Map<UUID, Integer> MISSES = new HashMap<>();
	private static int nextId = 1;

	static boolean enabled() {
		return AuraConfig.RIFT_SKILL_CHECKS;
	}

	static boolean busy(ServerPlayer p) {
		return OPEN.containsKey(p.getUUID());
	}

	/**
	 * Starts a check for {@code p} at {@code at}. {@code rub}: sparks and scraping meanwhile (else a low hum);
	 * {@code still} ends it as {@link #CANCEL} when false. False = not started (one is running, or a breather).
	 */
	static boolean start(ServerPlayer p, String label, int rgb, int tier, Vec3 at, boolean rub, BooleanSupplier still, IntConsumer result) {
		long now = p.level().getGameTime();
		if (busy(p) || now < REST.getOrDefault(p.getUUID(), 0L) || RiftCam.watching(p)) return false;
		int k = Math.max(0, Math.min(4, tier)), miss = MISSES.getOrDefault(p.getUUID(), 0);
		float w = WIDTH[k] + Math.min(0.16f, 0.06f * Math.max(0, miss - 1));
		Open o = new Open();
		o.id = nextId++;
		o.ticks = TICKS[k];
		o.start = now;
		o.ms = System.currentTimeMillis();
		o.lo = 0.52f + p.getRandom().nextFloat() * (0.94f - w - 0.52f);
		o.at = at;
		o.rub = rub;
		o.still = still;
		o.result = result;
		o.player = p;
		OPEN.put(p.getUUID(), o);
		if (!com.toast.aurapoints.shim.Platform.get().isFakePlayer(p))
			NetworkHandler.sendFx(p, FxPacket.SKILL_CHECK, o.ticks, String.format(Locale.ROOT, "%d\u001F%06X\u001F%.3f\u001F%.3f\u001F%s", o.id, rgb & 0xFFFFFF, o.lo, o.lo + w, label));
		return true;
	}

	/** The bar's fill (0..1) {@code u} of the way through; shared with the client. */
	public static double fill(double u) {
		return Math.pow(Math.max(0, Math.min(1, u)), 1.6);
	}

	/** RiftInputPacket.SKILL. */
	public static void answer(ServerPlayer p, int id, int value) {
		Open o = OPEN.get(p.getUUID());
		if (o == null || o.id != id) return;
		double dt = (System.currentTimeMillis() - o.ms) / 50.0;
		int r = value == EARLY || value == GOOD || value == LATE ? value : CANCEL;
		// a good release can't come before the bar is at the zone (4 ticks of slack)
		if (r == GOOD && fill((dt + 4) / o.ticks) < o.lo && !com.toast.aurapoints.shim.Platform.get().isFakePlayer(p)) r = EARLY;
		close(p, o, r);
	}

	private static void close(ServerPlayer p, Open o, int r) {
		OPEN.remove(p.getUUID());
		REST.put(p.getUUID(), p.level().getGameTime() + 6);
		if (r == GOOD) MISSES.remove(p.getUUID());
		else if (r != CANCEL) MISSES.merge(p.getUUID(), 1, Integer::sum);
		if (p.level() instanceof ServerLevel level) {
			if (r == GOOD) RiftSfx.good(level, o.at);
			else if (r != CANCEL) RiftSfx.bad(level, o.at);
		}
		o.result.accept(r);
	}

	/** Every server tick (Rifts.tickAll). */
	static void tick() {
		if (OPEN.isEmpty()) return;
		for (Open o : new java.util.ArrayList<>(OPEN.values())) {
			ServerPlayer p = o.player;
			long dt = p.level().getGameTime() - o.start;
			if (p.isRemoved() || !p.isAlive() || p.isSpectator() || !Rifts.in(p) || p.position().distanceTo(o.at) > RANGE || !o.still.getAsBoolean()) {
				if (!p.isRemoved() && !com.toast.aurapoints.shim.Platform.get().isFakePlayer(p)) NetworkHandler.sendFx(p, FxPacket.SKILL_CHECK, 0, "");
				close(p, o, CANCEL);
				continue;
			}
			if (dt > o.ticks + 60) {
				close(p, o, LATE);
				continue;
			}
			if (p.level() instanceof ServerLevel level && dt % 4 == 0) {
				float t = (float) fill(dt / (double) o.ticks);
				RiftSfx.hold(level, o.at, t, o.rub);
				if (o.rub) level.sendParticles(ParticleTypes.ELECTRIC_SPARK, o.at.x, o.at.y, o.at.z, 1 + (int) (t * 7), 0.2, 0.2, 0.2, 0.08 + t * 0.15);
				if (o.rub && t > 0.4f) level.sendParticles(ParticleTypes.SMOKE, o.at.x, o.at.y, o.at.z, 1, 0.1, 0.1, 0.1, 0.01);
			}
		}
	}

	/** DEV: answers the fake's open check. */
	static boolean devAnswer(ServerPlayer p, int value) {
		Open o = OPEN.get(p.getUUID());
		REST.remove(p.getUUID());
		if (o == null) return false;
		answer(p, o.id, value);
		REST.remove(p.getUUID());
		return true;
	}

	static int devId(ServerPlayer p) {
		Open o = OPEN.get(p.getUUID());
		return o == null ? -1 : o.id;
	}

	/** Logout, leaving the rift. */
	static void forget(UUID id) {
		OPEN.remove(id);
		REST.remove(id);
		MISSES.remove(id);
	}

	static void clear() {
		OPEN.clear();
		REST.clear();
		MISSES.clear();
	}
}
