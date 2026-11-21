package com.toast.aurapoints.ability;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "In combat" (5.38.0): the server tick of a player's last hit dealt to or taken from a living thing.
 * Fed from AuraPoints.onPostDamage; falls, fire and the like don't count. Loadout swaps ask it.
 */
public final class CombatTag {

	private CombatTag() {
	}

	private static final Map<UUID, Long> LAST = new HashMap<>();

	/** A hit landed: {@code victim} took it from {@code attacker} (either may be no player). */
	public static void onHit(LivingEntity victim, Entity attacker) {
		if (!(attacker instanceof LivingEntity) || attacker == victim || victim.getServer() == null) return;
		long now = victim.getServer().getTickCount();
		if (victim instanceof ServerPlayer p) LAST.put(p.getUUID(), now);
		if (attacker instanceof ServerPlayer p) LAST.put(p.getUUID(), now);
	}

	/** Ticks since the player's last hit (Long.MAX_VALUE if none). */
	public static long ticksSince(ServerPlayer player) {
		Long last = LAST.get(player.getUUID());
		return last == null || player.getServer() == null ? Long.MAX_VALUE : player.getServer().getTickCount() - last;
	}

	public static boolean inCombat(ServerPlayer player, int seconds) {
		return ticksSince(player) < seconds * 20L;
	}

	/** Dev test: as if the player was just hit. */
	public static void devMark(ServerPlayer player) {
		if (player.getServer() != null) LAST.put(player.getUUID(), (long) player.getServer().getTickCount());
	}

	public static void forget(UUID id) {
		LAST.remove(id);
	}
}
