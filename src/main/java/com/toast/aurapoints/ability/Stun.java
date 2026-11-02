package com.toast.aurapoints.ability;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Being busy with a big move. WINDUP: slowed while getting ready (e.g. the Inferno Slam's arms going
 * up). STUN: can't move, jump, attack or use abilities for a moment after it. Both are attribute
 * modifiers (the client sees them at once), cleared when they run out, on death and on logout.
 * In memory only.
 */
public final class Stun {

	private Stun() {
	}

	private static final ResourceLocation WINDUP_ID = ResourceLocation.fromNamespaceAndPath("aurapoints", "windup");
	private static final ResourceLocation STUN_ID = ResourceLocation.fromNamespaceAndPath("aurapoints", "stun");

	/** player -> tick the stun ends */
	private static final Map<UUID, Long> STUNNED = new HashMap<>();
	/** players winding up */
	private static final Map<UUID, Boolean> WINDING = new HashMap<>();

	/** True while winding up or stunned: ability keys do nothing then. */
	public static boolean isBusy(ServerPlayer player) {
		return STUNNED.containsKey(player.getUUID()) || WINDING.containsKey(player.getUUID());
	}

	public static boolean isStunned(ServerPlayer player) {
		return STUNNED.containsKey(player.getUUID());
	}

	/** Starts or ends the windup slowness ({@code slowness} 0.6 = 60% slower). */
	public static void windup(ServerPlayer player, boolean on, double slowness) {
		var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) speed.removeModifier(WINDUP_ID);
		if (!on) {
			WINDING.remove(player.getUUID());
			return;
		}
		WINDING.put(player.getUUID(), true);
		if (speed != null && slowness > 0) {
			speed.addTransientModifier(new AttributeModifier(WINDUP_ID, -Math.min(1.0, slowness), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	/** Stuns the player for {@code ticks} ticks (0 = not at all). */
	public static void stun(ServerPlayer player, int ticks) {
		MinecraftServer server = player.getServer();
		if (ticks <= 0 || server == null) return;
		STUNNED.put(player.getUUID(), (long) server.getTickCount() + ticks);
		com.toast.aurapoints.network.NetworkHandler.sendFx(player, com.toast.aurapoints.network.FxPacket.STUNNED, ticks, "");
		modifiers(player, true);
	}

	private static void modifiers(ServerPlayer player, boolean on) {
		var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		var jump = player.getAttribute(Attributes.JUMP_STRENGTH);
		if (speed != null) speed.removeModifier(STUN_ID);
		if (jump != null) jump.removeModifier(STUN_ID);
		if (!on) return;
		if (speed != null) speed.addTransientModifier(new AttributeModifier(STUN_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		if (jump != null) jump.addTransientModifier(new AttributeModifier(STUN_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	/** Every server tick: ends stuns that ran out, and shows a dazed swirl over stunned heads. */
	public static void tick(MinecraftServer server) {
		if (STUNNED.isEmpty()) return;
		long now = server.getTickCount();
		for (Iterator<Map.Entry<UUID, Long>> it = STUNNED.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null) {
				it.remove();
				continue;
			}
			if (now >= e.getValue() || e.getValue() - now > 20 * 60 || !player.isAlive()) {
				it.remove();
				modifiers(player, false);
				continue;
			}
			if (now % 3 == 0) {
				double a = now * 0.6;
				AuraFx.particles(player.serverLevel(), ParticleTypes.SMOKE, player.getX() + Math.cos(a) * 0.4,
						player.getY() + player.getBbHeight() + 0.25, player.getZ() + Math.sin(a) * 0.4, 1, 0, 0, 0, 0);
				AuraFx.particles(player.serverLevel(), ParticleTypes.CRIT, player.getX() - Math.cos(a) * 0.4,
						player.getY() + player.getBbHeight() + 0.25, player.getZ() - Math.sin(a) * 0.4, 1, 0, 0, 0, 0);
			}
		}
	}

	public static void onLogout(ServerPlayer player) {
		STUNNED.remove(player.getUUID());
		WINDING.remove(player.getUUID());
	}
}
