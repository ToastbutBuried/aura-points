package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.CloakPacket;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shadow aura perk: while a Shadow player is invisible (Vanish, Phantom Step,
 * or any other source) they are COMPLETELY hidden - other clients skip drawing
 * them, armor and held items included - and mobs ignore their armor too. To
 * keep them findable, a faint particle outline flickers on their silhouette
 * and they leave a black trail on the floor while moving.
 *
 * The server decides who is cloaked and tells the clients tracking that
 * player with a {@link CloakPacket}; the client still checks the vanilla
 * invisible flag, so the cloak can never outlast the invisibility.
 */
public final class ShadowCloak {

	private ShadowCloak() {
	}

	private static final Set<UUID> CLOAKED = new HashSet<>();
	private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();

	public static boolean shouldCloak(ServerPlayer player, AuraPlayerData data) {
		return data.aura == AuraType.SHADOW && player.hasEffect(MobEffects.INVISIBILITY) && !player.isSpectator();
	}

	public static boolean isCloaked(ServerPlayer player) {
		return CLOAKED.contains(player.getUUID());
	}

	/** Every server tick per player: flips the cloak on/off for watchers, and draws the trail. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		boolean cloak = shouldCloak(player, data);
		boolean was = CLOAKED.contains(player.getUUID());
		if (cloak != was) {
			if (cloak) CLOAKED.add(player.getUUID());
			else CLOAKED.remove(player.getUUID());
			PacketDistributor.sendToPlayersTrackingEntity(player, new CloakPacket(player.getId(), cloak));
		}
		if (!cloak) {
			LAST_POS.remove(player.getUUID());
			return;
		}

		Vec3 pos = player.position();
		if (now % 2 == 0) drawOutline(player, pos);

		// The black trail: only on the ground, only while moving.
		Vec3 last = LAST_POS.put(player.getUUID(), pos);
		if (now % Math.max(1, AuraConfig.SHADOW_TRAIL_INTERVAL_TICKS) != 0 || last == null || !player.onGround()) return;
		double dx = pos.x - last.x, dz = pos.z - last.z;
		if (dx * dx + dz * dz < 0.0004) return;
		var level = player.serverLevel();
		AuraFx.particles(level, AuraFx.dust(0x0A0A0F, 1.5f), pos.x, pos.y + 0.05, pos.z,
				AuraConfig.SHADOW_TRAIL_PARTICLES, 0.22, 0.0, 0.22, 0.0);
		if (now % 6 == 0) AuraFx.particles(level, ParticleTypes.SMOKE, pos.x, pos.y + 0.1, pos.z, 2, 0.15, 0.02, 0.15, 0.005);
	}

	/**
	 * A few dark specks at random points on the player's silhouette: a ring around
	 * the body (0.3 blocks out) from the feet to the shoulders, plus the top of the
	 * head. Drawn often and fading fast, they flicker into a faint outline.
	 */
	private static void drawOutline(ServerPlayer player, Vec3 pos) {
		var level = player.serverLevel();
		var random = player.getRandom();
		double height = player.getBbHeight();
		for (int i = 0; i < AuraConfig.SHADOW_OUTLINE_PARTICLES; i++) {
			double x, y, z;
			if (random.nextInt(5) == 0) { // head
				double a = random.nextDouble() * Math.PI * 2, r = random.nextDouble() * 0.25;
				x = Math.cos(a) * r; z = Math.sin(a) * r; y = height + 0.02;
			} else { // body
				double a = random.nextDouble() * Math.PI * 2;
				x = Math.cos(a) * 0.3; z = Math.sin(a) * 0.3; y = random.nextDouble() * height;
			}
			AuraFx.particles(level, AuraFx.dust(0x1A1024, 0.55f), pos.x + x, pos.y + y, pos.z + z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * A player who starts watching a cloaked player has to be told, or they'd still see the armor.
	 * Not cloaked is sent too, in case the cloak ended while the watcher was out of range.
	 */
	public static void onStartTracking(ServerPlayer watcher, ServerPlayer target) {
		PacketDistributor.sendToPlayer(watcher, new CloakPacket(target.getId(), isCloaked(target)));
	}

	public static void onLogout(ServerPlayer player) {
		CLOAKED.remove(player.getUUID());
		LAST_POS.remove(player.getUUID());
	}
}
