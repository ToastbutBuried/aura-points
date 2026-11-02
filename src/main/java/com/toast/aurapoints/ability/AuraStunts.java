package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.AuraTags;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Aura Trials stunts: killing a monster in a fun way with your aura's
 * abilities grants the advancement aurapoints:stunt/<id> (and
 * aurapoints:stunt/<id>_boss when the monster is in #aurapoints:stunt_bosses).
 * FTB Quests advancement tasks pick those up. The advancement JSONs live in
 * the world datapack.
 *
 * A kill only counts when the killing blow dealt less than the monster's max
 * health, so one-hit kills never do. Recent ability uses are remembered in
 * memory for a few seconds; nothing here is saved.
 */
public final class AuraStunts {

	private AuraStunts() {
	}

	private record Kill(ServerPlayer player, LivingEntity victim, AuraPlayerData data, long now, DamageSource source) {
	}

	private record Mark(UUID attacker, long tick) {
	}

	private enum Stunt {
		// Shadow
		GHOST_STRIKE(AuraType.SHADOW, k -> k.player.hasEffect(MobEffects.INVISIBILITY)),
		DASH_STRIKE(AuraType.SHADOW, k -> within(LAST_DASH, k.player, k.now, 40)),
		PHANTOM_AMBUSH(AuraType.SHADOW, k -> within(LAST_PHANTOM_LANDING, k.player, k.now, 60)),
		BACKSTAB(AuraType.SHADOW, AuraStunts::fromBehind),
		NIGHT_STALKER(AuraType.SHADOW, k -> k.player.level().getMaxLocalRawBrightness(k.player.blockPosition()) <= 4),
		// Water
		TIDAL_TAKEDOWN(AuraType.WATER, k -> marked(SHOCKWAVE_MARKS, k, 100)),
		DEEP_STRIKE(AuraType.WATER, k -> k.player.isInWater()),
		LAST_STAND(AuraType.WATER, k -> within(LAST_HEALING_TIDE, k.player, k.now, 100)
				&& k.player.getHealth() <= k.player.getMaxHealth() * 0.25f),
		WAVE_BREAKER(AuraType.WATER, k -> ACTIVE_ABILITY == Ability.SHOCKWAVE),
		RAIN_DANCER(AuraType.WATER, k -> k.player.isInWaterOrRain() && !k.player.isInWater()), // isInRain() is private
		// Fire
		BURN_NOTICE(AuraType.FIRE, k -> k.victim.isOnFire() && marked(FIRE_MARKS, k, 200)),
		EXECUTIONERS_RAGE(AuraType.FIRE, k -> executionerHit(k)
				&& k.player.getHealth() <= k.player.getMaxHealth() * 0.5f),
		BLAZE_OF_GLORY(AuraType.FIRE, k -> k.player.isOnFire()),
		EMBER_BURST(AuraType.FIRE, k -> ACTIVE_ABILITY == Ability.HEAT_WAVE),
		FIREBALL_SNIPE(AuraType.FIRE, k -> k.source.getDirectEntity() instanceof AuraFireball
				&& k.player.distanceTo(k.victim) >= 20.0f),
		// Fire Bending: kill with the shot from up close, from far away, or by burning it with the held orb
		POINT_BLANK(AuraType.FIRE, k -> FireBending.HITTING == FireBending.Hit.SHOT && k.player.distanceTo(k.victim) <= 4.0f),
		FLAME_LANCE(AuraType.FIRE, k -> FireBending.HITTING == FireBending.Hit.SHOT && k.player.distanceTo(k.victim) >= 15.0f),
		FLAME_DANCER(AuraType.FIRE, k -> FireBending.HITTING == FireBending.Hit.ORB),
		// Wind
		AIR_STRIKE(AuraType.WIND, k -> AuraAbilities.isMidDoubleJump(k.player)),
		SKY_HUNTER(AuraType.WIND, k -> k.player.isFallFlying()),
		FEATHER_STRIKE(AuraType.WIND, k -> !k.player.onGround() && k.player.hasEffect(MobEffects.SLOW_FALLING)
				&& AuraAbilities.hasAbility(k.data, Ability.FEATHERFALL)),
		UPDRAFT(AuraType.WIND, k -> !k.player.onGround() && within(LAST_SUPER_WIND_CHARGE, k.player, k.now, 60)),
		HIGH_GROUND(AuraType.WIND, k -> k.player.getY() - k.victim.getY() >= 10.0);

		final AuraType aura;
		final Predicate<Kill> test;

		Stunt(AuraType aura, Predicate<Kill> test) {
			this.aura = aura;
			this.test = test;
		}

		String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	// ---------------------------------------------------------------
	// Recent ability uses (fed by AuraAbilities)
	// ---------------------------------------------------------------

	private static final Map<UUID, Long> LAST_DASH = new HashMap<>();
	private static final Map<UUID, Long> LAST_PHANTOM_LANDING = new HashMap<>();
	private static final Map<UUID, Long> LAST_HEALING_TIDE = new HashMap<>();
	private static final Map<UUID, Long> LAST_SUPER_WIND_CHARGE = new HashMap<>();
	/** The area ability currently dealing its damage (set only while its hits run), so a kill can tell it was the killing blow. */
	public static Ability ACTIVE_ABILITY = null;
	/** Victim -> who hit it with the ability, and when. */
	private static final Map<UUID, Mark> SHOCKWAVE_MARKS = new HashMap<>();
	private static final Map<UUID, Mark> FIRE_MARKS = new HashMap<>();
	/** Attacker -> the victim their last Executioner-boosted hit landed on, and when. */
	private static final Map<UUID, Mark> LAST_EXECUTIONER_HIT = new HashMap<>();

	private static long now(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		return server == null ? 0 : server.getTickCount();
	}

	public static void dashed(ServerPlayer player) {
		LAST_DASH.put(player.getUUID(), now(player));
	}

	public static void phantomLanded(ServerPlayer player) {
		LAST_PHANTOM_LANDING.put(player.getUUID(), now(player));
	}

	public static void superWindCharge(ServerPlayer player) {
		LAST_SUPER_WIND_CHARGE.put(player.getUUID(), now(player));
	}

	public static void healingTide(ServerPlayer caster) {
		LAST_HEALING_TIDE.put(caster.getUUID(), now(caster));
	}

	public static void shockwaveHit(ServerPlayer player, LivingEntity victim) {
		SHOCKWAVE_MARKS.put(victim.getUUID(), new Mark(player.getUUID(), now(player)));
	}

	public static void fireHit(ServerPlayer player, LivingEntity victim) {
		FIRE_MARKS.put(victim.getUUID(), new Mark(player.getUUID(), now(player)));
	}

	public static void executionerHit(ServerPlayer player, LivingEntity victim) {
		LAST_EXECUTIONER_HIT.put(player.getUUID(), new Mark(victim.getUUID(), now(player)));
	}

	private static boolean within(Map<UUID, Long> map, ServerPlayer player, long now, int ticks) {
		Long t = map.get(player.getUUID());
		return t != null && now - t >= 0 && now - t <= ticks;
	}

	private static boolean marked(Map<UUID, Mark> map, Kill k, int ticks) {
		Mark m = map.get(k.victim.getUUID());
		return m != null && m.attacker.equals(k.player.getUUID()) && k.now - m.tick >= 0 && k.now - m.tick <= ticks;
	}

	/** True if the killing hit itself was boosted by Executioner (same victim, this tick). */
	private static boolean executionerHit(Kill k) {
		Mark m = LAST_EXECUTIONER_HIT.get(k.player.getUUID());
		return m != null && m.attacker.equals(k.victim.getUUID()) && k.now - m.tick >= 0 && k.now - m.tick <= 1;
	}

	/** The killer stands behind the victim: more than 120 degrees away from where it looks. */
	private static boolean fromBehind(Kill k) {
		net.minecraft.world.phys.Vec3 look = k.victim.getViewVector(1.0f).multiply(1, 0, 1);
		net.minecraft.world.phys.Vec3 toKiller = k.player.position().subtract(k.victim.position()).multiply(1, 0, 1);
		if (look.lengthSqr() < 1.0e-4 || toKiller.lengthSqr() < 1.0e-4) return false;
		return look.normalize().dot(toKiller.normalize()) < -0.5;
	}

	/** Drops old entries now and then so the maps never grow. Call every server tick. */
	public static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		if (now % 200 != 0) return;
		for (Map<UUID, Long> map : java.util.List.of(LAST_DASH, LAST_PHANTOM_LANDING, LAST_HEALING_TIDE, LAST_SUPER_WIND_CHARGE)) {
			map.values().removeIf(t -> now - t > 400 || now < t);
		}
		for (Map<UUID, Mark> map : java.util.List.of(SHOCKWAVE_MARKS, FIRE_MARKS, LAST_EXECUTIONER_HIT)) {
			map.values().removeIf(m -> now - m.tick > 400 || now < m.tick);
		}
	}

	// ---------------------------------------------------------------
	// The kill check
	// ---------------------------------------------------------------

	/** Called after damage is dealt; does something only when that damage killed a monster. */
	public static void onDamageDealt(ServerPlayer player, LivingEntity victim, float damage, DamageSource source) {
		if (!victim.isDeadOrDying() || !(victim instanceof Enemy)) return;
		if (damage >= victim.getMaxHealth()) return; // one-hit kill - doesn't count
		if (AuraAbilities.progressPaused(player)) return; // no cooldowns (or the arena) = no stunt credit
		MinecraftServer server = player.getServer();
		if (server == null) return;
		AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
		if (data.aura == null) return;

		Kill kill = new Kill(player, victim, data, server.getTickCount(), source);
		boolean boss = victim.getType().is(AuraTags.STUNT_BOSSES);
		for (Stunt stunt : Stunt.values()) {
			if (stunt.aura != data.aura || !stunt.test.test(kill)) continue;
			grant(player, "stunt/" + stunt.id());
			com.toast.aurapoints.network.NetworkHandler.sendFx(player, com.toast.aurapoints.network.FxPacket.STUNT, boss ? 1 : 0, stunt.id());
			xp(player, boss);
			if (boss) grant(player, "stunt/" + stunt.id() + "_boss");
		}
	}

	/** A stunt's experience reward (STUNT_XP_POINTS, more for a boss). */
	public static void xp(ServerPlayer player, boolean boss) {
		int points = (int) Math.round(Math.max(0, com.toast.aurapoints.config.AuraConfig.STUNT_XP_POINTS)
				* (boss ? Math.max(1.0, com.toast.aurapoints.config.AuraConfig.STUNT_XP_BOSS_MULTIPLIER) : 1.0));
		if (points > 0) player.giveExperiencePoints(points);
	}

	private static void grant(ServerPlayer player, String path) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		AdvancementHolder holder = server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, path));
		if (holder == null) {
			AuraPoints.LOGGER.warn("Advancement aurapoints:{} is missing (a datapack removed it?)", path);
			return;
		}
		player.getAdvancements().award(holder, "done");
	}
}
