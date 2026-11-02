package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.BendingAnimPacket.Idle;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Idle animations: a player who stands still for IDLE_START_SECONDS gets a little animation of their
 * aura (BendingAnimPacket.Idle; drawn by client/IdleGestures and client/IdleFx), then another every
 * IDLE_GAP_MIN..MAX_SECONDS while they stay put. Three in four are their aura's own, the rest the
 * shared ones; auras without their own idles and no aura only get the shared ones. Anything the player does - moving,
 * looking around, swinging, an ability pose - starts the wait over; every client also stops an idle
 * the moment its player moves, so nothing has to be cancelled from here.
 */
public final class IdleAnimations {

	private IdleAnimations() {
	}

	/** Per player: where they stood and looked when they last did something, since when, and when an idle may come. */
	private static final class Watch {
		double x, y, z;
		float yaw, pitch;
		long idleSince, nextAllowed;
		int last = -1;
	}

	private static final Map<UUID, Watch> WATCH = new HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();

	/** Every server tick, for every player. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		if (!AuraConfig.IDLE_ENABLED || com.toast.aurapoints.DevIdleGallery.on()) return;
		Watch w = WATCH.get(player.getUUID());
		if (w == null) {
			w = new Watch();
			anchor(w, player, now);
			WATCH.put(player.getUUID(), w);
			return;
		}
		if (busy(player, data) || moved(w, player)) {
			anchor(w, player, now);
			return;
		}
		if (now - w.idleSince < Math.max(1, AuraConfig.IDLE_START_SECONDS) * 20L || now < w.nextAllowed) return;
		Idle idle = pick(data.aura, w.last);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_IDLE, idle.ordinal());
		w.last = idle.ordinal();
		int gapMin = Math.max(1, AuraConfig.IDLE_GAP_MIN_SECONDS), gapMax = Math.max(gapMin, AuraConfig.IDLE_GAP_MAX_SECONDS);
		w.nextAllowed = now + idle.length + (gapMin + RANDOM.nextInt(gapMax - gapMin + 1)) * 20L;
	}

	/** An ability was used (AuraHooks.abilityUsed): the wait starts over. */
	public static void markActive(ServerPlayer player) {
		Watch w = WATCH.get(player.getUUID());
		if (w != null && player.getServer() != null) anchor(w, player, player.getServer().getTickCount());
	}

	private static void anchor(Watch w, ServerPlayer p, long now) {
		w.x = p.getX();
		w.y = p.getY();
		w.z = p.getZ();
		w.yaw = p.getYRot();
		w.pitch = p.getXRot();
		w.idleSince = now;
		w.nextAllowed = 0;
	}

	private static boolean moved(Watch w, ServerPlayer p) {
		double dx = p.getX() - w.x, dy = p.getY() - w.y, dz = p.getZ() - w.z;
		return dx * dx + dz * dz > 0.15 * 0.15 || Math.abs(dy) > 0.15
				|| Math.abs(Mth.wrapDegrees(p.getYRot() - w.yaw)) > 3 || Math.abs(p.getXRot() - w.pitch) > 3;
	}

	/** Doing something an idle mustn't play over. */
	private static boolean busy(ServerPlayer p, AuraPlayerData data) {
		return !p.onGround() || p.isInWaterOrBubble() || p.isInLava() || p.isPassenger() || p.isSleeping() || p.isFallFlying()
				|| p.isSpectator() || p.getAbilities().flying || p.swinging || p.isUsingItem() || p.hurtTime > 0 || p.isCrouching()
				|| Poses.current(p) != BendingAnimPacket.NONE || data.locked || AuraAbilities.allLocked
				|| com.toast.aurapoints.arena.Duel.isIn(p) || com.toast.aurapoints.arena.Spectate.watching(p);
	}

	/** Three in four from the aura's own (if it has any), else a shared one - never the same twice running. */
	private static Idle pick(AuraType aura, int last) {
		List<Idle> own = Idle.of(aura), shared = Idle.shared();
		List<Idle> pool = !own.isEmpty() && RANDOM.nextFloat() < 0.75f ? own : shared;
		Idle idle = pool.get(RANDOM.nextInt(pool.size()));
		for (int i = 0; i < 5 && idle.ordinal() == last; i++) idle = pool.get(RANDOM.nextInt(pool.size()));
		return idle;
	}

	public static void forget(ServerPlayer player) {
		WATCH.remove(player.getUUID());
	}

	public static void clearAll() {
		WATCH.clear();
	}
}
