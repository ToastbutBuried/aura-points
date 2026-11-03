package com.toast.aurapoints;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.FxPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the screen effects that need the server to notice something (FxPacket):
 *  - hit confirm: an ability's hit (or a fireball / wind charge of yours), not plain melee or arrows;
 *  - points toast: the aura_points score went up, whatever raised it (quests, bosses, datapacks, commands).
 * The roll reel, stunt popups, stuns and lifesteal pulses are sent where they happen.
 */
@EventBusSubscriber(modid = AuraPoints.MOD_ID)
public final class ScreenFxHooks {

	private ScreenFxHooks() {
	}

	/** The entity an ability's hurt() just went into (marked in the incoming-damage event), and the tick. */
	private static Entity abilityVictim = null;
	private static long abilityTick = -1;
	/** The aura_points score last seen per player (absent = not seen yet, no toast for the first look). */
	private static final Map<UUID, Integer> POINTS = new HashMap<>();

	/** This damage is an ability's (AuraAbilities.vsMob marked it): the hit confirm may show for it. */
	public static void abilityHit(Entity victim) {
		abilityVictim = victim;
		abilityTick = victim.level().getGameTime();
	}

	@SubscribeEvent
	public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		POINTS.remove(event.getEntity().getUUID());
	}

	/**
	 * After damage was dealt (LivingDamageEvent.Post): confirmed for an ability's hit, or a projectile of the
	 * player's that isn't an arrow (fireballs, wind charges). Plain melee, sweeps, Thorns and arrows have
	 * their own feedback and stay out.
	 */
	public static void onDamageDealt(ServerPlayer attacker, LivingEntity victim, DamageSource source, float damage) {
		if (damage <= 0 || victim == attacker) return;
		boolean ability = abilityVictim == victim && abilityTick == victim.level().getGameTime();
		if (ability) abilityVictim = null;
		Entity direct = source.getDirectEntity();
		boolean projectile = direct != null && direct != attacker && !(direct instanceof AbstractArrow);
		if (!ability && !projectile) return;
		int flags = (victim.isDeadOrDying() ? 1 : 0) | (victim instanceof Player ? 2 : 0);
		NetworkHandler.sendFx(attacker, FxPacket.HIT, flags, "");
	}

	/** Call every server tick: watches everyone's aura points a few times a second. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) return;
		Objective objective = server.getScoreboard().getObjective(AuraConfig.CURRENCY_OBJECTIVE);
		if (objective == null) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ReadOnlyScoreInfo info = server.getScoreboard().getPlayerScoreInfo(player, objective);
			int now = info == null ? 0 : info.value();
			Integer before = POINTS.put(player.getUUID(), now);
			if (before != null && now > before) NetworkHandler.sendFx(player, FxPacket.POINTS, now - before, String.valueOf(now));
		}
	}
}
