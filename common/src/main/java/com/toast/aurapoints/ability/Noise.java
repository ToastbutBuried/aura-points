package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * How loud every player and mob is (0..100), for Resonance: its Echolocation, Sonic Blossoms, Listening Trees and
 * Sonic Shriek all read it. Only kept up near a listener (a Resonance player or one of their plants), so a server
 * without one does no work. Things you keep doing hold a floor while you do them (walking, sprinting, eating or
 * drawing a bow, holding an ability key); things that happen add a burst (jumping, landing, building, hitting, getting
 * hit, using an ability, opening doors and chests). It drains every tick. Sneaking or standing still adds nothing.
 */
public final class Noise {

	private Noise() {
	}

	private static final class Ear {
		float level;
		Vec3 last;
		boolean wasOnGround = true;
		float lastFall;
	}

	private static final Map<LivingEntity, Ear> EARS = new HashMap<>();
	/** True while any listener is around (bursts are only recorded then). */
	private static boolean listening = false;

	public static float get(LivingEntity e) {
		Ear ear = EARS.get(e);
		return ear == null ? 0f : ear.level;
	}

	/** Dirge: entities pinned loud until this game tick. */
	private static final Map<LivingEntity, Long> DEAF = new HashMap<>();

	/** Deafened: for {@code ticks} the entity's noise stays at NOISE_DEAF_LEVEL however still or sneaky it is. */
	public static void deafen(LivingEntity e, int ticks) {
		if (ticks <= 0 || e.getServer() == null) return;
		DEAF.put(e, (long) e.getServer().getTickCount() + ticks);
		Ear ear = EARS.computeIfAbsent(e, k -> new Ear());
		ear.level = Math.max(ear.level, AuraConfig.NOISE_DEAF_LEVEL);
	}

	public static boolean isDeaf(LivingEntity e) {
		Long until = DEAF.get(e);
		return until != null && e.getServer() != null && until > e.getServer().getTickCount();
	}

	public static boolean isLoud(LivingEntity e) {
		return get(e) >= AuraConfig.NOISE_LOUD;
	}

	/** A burst of noise (an event): only counts while someone is listening. */
	public static void add(LivingEntity e, float amount) {
		if (!listening || amount <= 0 || e.level().isClientSide()) return;
		Ear ear = EARS.computeIfAbsent(e, k -> new Ear());
		ear.level = Math.min(100f, ear.level + amount);
	}

	/** Every server tick: who is near a listener, what they keep doing, and the drain. */
	public static void tick(MinecraftServer server) {
		var listeners = Resonance.listeners(server);
		listening = !listeners.isEmpty();
		if (!listening) {
			EARS.clear();
			return;
		}
		DEAF.entrySet().removeIf(d -> !d.getKey().isAlive() || d.getValue() <= server.getTickCount());
		double r = AuraConfig.NOISE_LISTEN_RANGE;
		Set<LivingEntity> near = new HashSet<>();
		for (Resonance.Listener l : listeners) {
			near.addAll(l.level().getEntitiesOfClass(LivingEntity.class, new AABB(l.at(), l.at()).inflate(r),
					e -> e.isAlive() && !e.isSpectator()));
		}
		for (LivingEntity e : near) {
			Ear ear = EARS.computeIfAbsent(e, k -> new Ear());
			float floor = floor(e, ear);
			ear.level = Math.max(0f, ear.level - AuraConfig.NOISE_DECAY_PER_TICK);
			ear.level = Math.min(100f, Math.max(ear.level, floor));
			ear.last = e.position();
		}
		for (Iterator<Map.Entry<LivingEntity, Ear>> it = EARS.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			LivingEntity e = entry.getKey();
			if (!e.isAlive() || e.isRemoved()) {
				it.remove();
				continue;
			}
			if (!near.contains(e)) {
				Ear ear = entry.getValue();
				ear.level -= AuraConfig.NOISE_DECAY_PER_TICK;
				if (ear.level <= 0) it.remove();
			}
		}
	}

	/** What the entity keeps doing this tick (plus jump and landing bursts, which need last tick's state). */
	private static float floor(LivingEntity e, Ear ear) {
		float floor = 0f;
		Vec3 now = e.position();
		double moved = ear.last == null ? 0 : Math.hypot(now.x - ear.last.x, now.z - ear.last.z);
		boolean sneaking = e instanceof Player p && p.isCrouching();
		if (moved > 0.02 && moved < 4.0 && !sneaking) floor = e.isSprinting() ? AuraConfig.NOISE_SPRINT : AuraConfig.NOISE_WALK;
		if (e.isUsingItem()) floor = Math.max(floor, AuraConfig.NOISE_USE_ITEM);
		if (isDeaf(e)) floor = Math.max(floor, AuraConfig.NOISE_DEAF_LEVEL);
		if (e instanceof ServerPlayer p && (com.toast.aurapoints.network.NetworkHandler.holdsAbilityKey(p) || Mjolnir.isSpinning(p))) {
			floor = Math.max(floor, AuraConfig.NOISE_ABILITY_HOLD);
		}
		boolean onGround = e.onGround();
		if (ear.wasOnGround && !onGround && ear.last != null && now.y > ear.last.y + 0.05 && !e.isInWater()) {
			ear.level += AuraConfig.NOISE_JUMP;
		}
		if (!ear.wasOnGround && onGround && ear.lastFall > 0.6f) {
			ear.level += AuraConfig.NOISE_LAND + AuraConfig.NOISE_LAND_PER_BLOCK * ear.lastFall;
		}
		ear.wasOnGround = onGround;
		ear.lastFall = e.fallDistance;
		return floor;
	}

	public static void clearAll() {
		EARS.clear();
		listening = false;
	}
}
