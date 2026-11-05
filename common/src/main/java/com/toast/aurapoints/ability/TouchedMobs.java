package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Aura-touched mobs: TOUCHED_CHANCE of natural hostile spawns carry an aura. They glow in its color
 * for everyone (a scoreboard team per aura sets the outline color), wear its halo, are called
 * "Fire-touched Zombie" and so on, have TOUCHED_HEALTH_MULTIPLIER times the health and one small
 * ability of their aura. Killing one pays TOUCHED_KILL_POINTS Aura Points and mastery XP.
 *
 * The aura is saved on the mob (persistent data "aurapoints_touched"), so it survives chunk reloads
 * and restarts; everything else here is rebuilt when the mob joins a level.
 */
public final class TouchedMobs {

	private TouchedMobs() {
	}

	private static final String TAG = "aurapoints_touched";
	private static final String TEAM_PREFIX = "aurapoints_touched_";
	private static final ResourceLocation HEALTH_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "touched_health");

	/** Loaded touched mobs -> their aura. */
	private static final Map<Mob, AuraType> TOUCHED = new HashMap<>();
	/** Mob -> server tick its ability is ready again. */
	private static final Map<Mob, Long> READY = new HashMap<>();

	/** The aura of a touched mob, or null. */
	public static AuraType auraOf(Entity e) {
		if (!(e instanceof Mob mob)) return null;
		AuraType loaded = TOUCHED.get(mob);
		if (loaded != null) return loaded;
		CompoundTag data = com.toast.aurapoints.shim.Platform.get().persistentData(mob);
		return data.contains(TAG) ? AuraType.byId(data.getString(TAG)) : null;
	}

	// ---------------------------------------------------------------
	// Spawning
	// ---------------------------------------------------------------

	/** FinalizeSpawnEvent: a natural hostile spawn may roll an aura. */
	public static void onFinalizeSpawn(Mob mob, MobSpawnType type) {
		if (!AuraConfig.TOUCHED_ENABLED || type != MobSpawnType.NATURAL || !(mob instanceof Enemy)) return;
		if (mob.getRandom().nextDouble() >= AuraConfig.TOUCHED_CHANCE) return;
		AuraType[] auras = AuraType.values();
		touch(mob, auras[mob.getRandom().nextInt(auras.length)]);
	}

	/** Gives {@code mob} an aura (also /aura touched). Takes effect at once if it is already in a level. */
	public static void touch(Mob mob, AuraType aura) {
		com.toast.aurapoints.shim.Platform.get().persistentData(mob).putString(TAG, aura.id());
		var health = mob.getAttribute(Attributes.MAX_HEALTH);
		if (health != null && AuraConfig.TOUCHED_HEALTH_MULTIPLIER > 1.0) {
			health.removeModifier(HEALTH_ID);
			health.addPermanentModifier(new AttributeModifier(HEALTH_ID, AuraConfig.TOUCHED_HEALTH_MULTIPLIER - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
			mob.setHealth(mob.getMaxHealth());
		}
		mob.setCustomName(Component.literal(aura.displayName + "-touched ").append(mob.getType().getDescription()).withStyle(aura.color));
		if (mob.level() instanceof ServerLevel && !mob.isRemoved() && com.toast.aurapoints.shim.Platform.get().isAddedToLevel(mob)) onJoin(mob);
	}

	/** EntityJoinLevelEvent: a touched mob (new or loaded) starts being tracked, with its outline. */
	public static void onJoin(Entity e) {
		if (!(e instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) return;
		CompoundTag data = com.toast.aurapoints.shim.Platform.get().persistentData(mob);
		if (!data.contains(TAG)) return;
		AuraType aura = AuraType.byId(data.getString(TAG));
		if (aura == null) return;
		TOUCHED.put(mob, aura);
		READY.putIfAbsent(mob, level.getServer().getTickCount() + 40L + mob.getRandom().nextInt(60));
		mob.setGlowingTag(AuraConfig.TOUCHED_OUTLINE);
		if (AuraConfig.TOUCHED_OUTLINE) {
			Scoreboard board = level.getServer().getScoreboard();
			PlayerTeam team = team(board, aura);
			if (board.getPlayersTeam(mob.getScoreboardName()) == null) board.addPlayerToTeam(mob.getScoreboardName(), team);
		}
	}

	/** EntityLeaveLevelEvent: forgotten; killed or discarded for good also leaves its team (no stale scoreboard entries). */
	public static void onLeave(Entity e) {
		if (!(e instanceof Mob mob) || TOUCHED.remove(mob) == null) return;
		READY.remove(mob);
		var reason = mob.getRemovalReason();
		if (reason != null && reason.shouldDestroy() && mob.getServer() != null) {
			Scoreboard board = mob.getServer().getScoreboard();
			PlayerTeam team = board.getPlayersTeam(mob.getScoreboardName());
			if (team != null && team.getName().startsWith(TEAM_PREFIX)) board.removePlayerFromTeam(mob.getScoreboardName(), team);
		}
	}

	private static PlayerTeam team(Scoreboard board, AuraType aura) {
		String name = TEAM_PREFIX + aura.id();
		PlayerTeam team = board.getPlayerTeam(name);
		if (team == null) {
			team = board.addPlayerTeam(name);
			team.setDisplayName(Component.literal(aura.displayName + "-touched"));
		}
		team.setColor(aura.color);
		return team;
	}

	// ---------------------------------------------------------------
	// The kill
	// ---------------------------------------------------------------

	/** LivingDeathEvent: whoever killed a touched mob gets Aura Points and mastery XP. */
	public static void onDeath(LivingEntity dead, net.minecraft.world.damagesource.DamageSource source) {
		AuraType aura = auraOf(dead);
		if (aura == null) return;
		ServerPlayer killer = source.getEntity() instanceof ServerPlayer p ? p
				: dead.getKillCredit() instanceof ServerPlayer p ? p : null;
		if (killer == null || killer.isSpectator() || AuraAbilities.progressPaused(killer)) return;
		if (AuraConfig.TOUCHED_KILL_POINTS > 0) AuraCurrency.add(killer, AuraConfig.TOUCHED_KILL_POINTS);
		Mastery.addXp(killer, AuraConfig.TOUCHED_KILL_MASTERY_XP);
		AuraStats.add(killer, AuraStats.TOUCHED_KILLS, 1);
		com.toast.aurapoints.cosmetic.Weekly.onTouchedKill(killer, aura);
		killer.displayClientMessage(Component.literal("Slain: ").withStyle(net.minecraft.ChatFormatting.GRAY)
				.append(dead.getDisplayName()).append(Component.literal(AuraConfig.TOUCHED_KILL_POINTS > 0
						? "  +" + AuraConfig.TOUCHED_KILL_POINTS + " Aura" : "").withStyle(net.minecraft.ChatFormatting.GOLD)), true);
		ServerLevel level = (ServerLevel) dead.level();
		Vec3 c = AuraFx.chest(dead);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(aura.rgb, 1.6f), 40, 0.6, 0.05);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.TOTEM_OF_UNDYING, 20, 0.4, 0.3);
		AuraFx.sound(level, dead, AuraSounds.UI_STUNT.value(), 0.8f, 1.2f);
	}

	// ---------------------------------------------------------------
	// Every tick: halo and ability
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		if (TOUCHED.isEmpty()) return;
		long now = server.getTickCount();
		boolean halo = now % 4 == 0;
		boolean think = now % 10 == 0;
		// Snapshot: an ability can add or remove touched mobs (summons, kills) while we loop.
		for (var entry : new java.util.ArrayList<>(TOUCHED.entrySet())) {
			Mob mob = entry.getKey();
			if (mob.isRemoved()) {
				TOUCHED.remove(mob);
				READY.remove(mob);
				continue;
			}
			if (!mob.isAlive() || !(mob.level() instanceof ServerLevel level)) continue;
			AuraType aura = entry.getValue();
			if (halo && !mob.isInvisible()) AuraHalo.mobHalo(level, mob, aura, now);
			if (think && AuraConfig.TOUCHED_ABILITIES && now >= READY.getOrDefault(mob, 0L)) {
				LivingEntity target = mob.getTarget();
				if (target != null && target.isAlive() && !(target instanceof Player p && (p.isCreative() || p.isSpectator()))
						&& target.level() == level && mob.distanceTo(target) <= AuraConfig.TOUCHED_ABILITY_RANGE && mob.hasLineOfSight(target)
						&& !IceSpike.isFrozen(mob) && use(level, mob, aura, target)) {
					READY.put(mob, now + Math.max(20, AuraConfig.TOUCHED_ABILITY_COOLDOWN_TICKS));
				}
			}
		}
	}

	/** The aura's mob-sized ability on {@code t}. False if it had nothing to do (out of its own reach). */
	private static boolean use(ServerLevel level, Mob mob, AuraType aura, LivingEntity t) {
		float dmg = AuraConfig.TOUCHED_ABILITY_DAMAGE;
		var src = level.damageSources().mobAttack(mob);
		double dist = mob.distanceTo(t);
		Vec3 c = AuraFx.chest(mob), tc = AuraFx.chest(t);
		switch (aura) {
			case SHADOW -> { // Shadow Step: appears behind the target
				Vec3 back = t.position().subtract(Vec3.directionFromRotation(0, t.getYRot()).scale(1.6));
				if (!level.noCollision(mob, mob.getBoundingBox().move(back.subtract(mob.position())))) return false;
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.LARGE_SMOKE, 20, 0.3, 0.02);
				mob.teleportTo(back.x, back.y, back.z);
				mob.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, t.getEyePosition());
				AuraFx.sound(level, mob, AuraSounds.ENDERMAN_TELEPORT.value(), 0.8f, 0.7f);
			}
			case WATER -> { // Tidal Slam: up close, a wave that throws back
				if (dist > 4) return false;
				t.hurt(src, dmg);
				t.knockback(1.2, mob.getX() - t.getX(), mob.getZ() - t.getZ());
				AuraFx.ring(level, mob.getX(), mob.getY() + 0.2, mob.getZ(), 2.5, ParticleTypes.SPLASH, 24);
				AuraFx.sound(level, mob, AuraSounds.GENERIC_SPLASH.value(), 1.0f, 0.8f);
			}
			case FIRE -> { // Fire Bolt: a small fireball
				Vec3 dir = tc.subtract(c).normalize();
				var ball = new net.minecraft.world.entity.projectile.SmallFireball(level, mob, dir);
				ball.setPos(c.x + dir.x, c.y, c.z + dir.z);
				level.addFreshEntity(ball);
				AuraFx.sound(level, mob, AuraSounds.BLAZE_SHOOT.value(), 1.0f, 1.0f);
			}
			case WIND -> { // Gust Leap: jumps at the target
				if (!mob.onGround() || dist < 3) return false;
				Vec3 to = t.position().subtract(mob.position());
				Vec3 flat = new Vec3(to.x, 0, to.z).normalize().scale(Math.min(1.4, dist * 0.12));
				mob.setDeltaMovement(flat.x, 0.55, flat.z);
				mob.hurtMarked = true;
				AuraFx.burst(level, mob.getX(), mob.getY() + 0.2, mob.getZ(), ParticleTypes.GUST, 2, 0.2, 0);
				AuraFx.sound(level, mob, AuraSounds.WIND_CHARGE_BURST.value(), 0.8f, 1.2f);
			}
			case FROST -> { // Frost Nova: chills everything close
				if (dist > 4) return false;
				t.hurt(src, dmg * 0.75f);
				t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1), mob);
				t.setTicksFrozen(Math.max(t.getTicksFrozen(), t.getTicksRequiredToFreeze()));
				AuraFx.ring(level, mob.getX(), mob.getY() + 0.3, mob.getZ(), 3, ParticleTypes.SNOWFLAKE, 30);
				AuraFx.sound(level, mob, AuraSounds.PLAYER_HURT_FREEZE.value(), 1.0f, 1.0f);
			}
			case VERDANT -> { // Vine Snare: roots and poisons
				Reactions.root(t, level.getServer().getTickCount() + 20);
				t.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0), mob);
				AuraFx.line(level, c, tc, ParticleTypes.COMPOSTER, (int) (dist * 3));
				AuraFx.sound(level, t, AuraSounds.MANGROVE_ROOTS_PLACE.value(), 1.0f, 0.8f);
			}
			case THUNDER -> { // Spark: a bolt from the sky (looks only, the damage is the mob's)
				var bolt = EntityType.LIGHTNING_BOLT.create(level);
				if (bolt != null) {
					bolt.moveTo(t.getX(), t.getY(), t.getZ());
					bolt.setVisualOnly(true);
					level.addFreshEntity(bolt);
				}
				t.hurt(src, dmg);
			}
			case RESONANCE -> { // Shriek: darkness on everyone around
				for (Player p : level.getEntitiesOfClass(Player.class, mob.getBoundingBox().inflate(8), p -> !p.isSpectator() && !p.isCreative())) {
					p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0), mob);
				}
				AuraFx.ring(level, mob.getX(), mob.getEyeY(), mob.getZ(), 1.5, ParticleTypes.SONIC_BOOM, 6);
				AuraFx.sound(level, mob, AuraSounds.WARDEN_TENDRIL_CLICKS.value(), 1.2f, 0.8f);
			}
			case EARTH -> { // Upheaval: the ground throws the target up
				if (dist > 5 || !t.onGround()) return false;
				t.hurt(src, dmg * 0.75f);
				t.setDeltaMovement(t.getDeltaMovement().add(0, 0.7, 0));
				t.hurtMarked = true;
				var below = level.getBlockState(t.blockPosition().below());
				AuraFx.burst(level, t.getX(), t.getY() + 0.1, t.getZ(),
						new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, below), 30, 0.5, 0.1);
				AuraFx.sound(level, t, AuraSounds.MACE_SMASH_GROUND.value(), 1.0f, 0.9f);
			}
			case LIGHT -> { // Flash: blinds the target, heals itself
				t.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0), mob);
				mob.heal(dmg);
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.END_ROD, 25, 0.4, 0.15);
				AuraFx.sound(level, mob, AuraSounds.BEACON_POWER_SELECT.value(), 1.0f, 1.6f);
			}
			case BLOOD -> { // Leech: takes health from the target
				if (dist > 6) return false;
				if (t.hurt(src, dmg * 0.75f)) mob.heal(dmg * 0.75f);
				AuraFx.line(level, tc, c, AuraFx.dust(0xB0121B, 1.0f), (int) (dist * 4));
				AuraFx.sound(level, mob, AuraSounds.WARDEN_HEARTBEAT.value(), 1.0f, 1.2f);
			}
		}
		return true;
	}

	public static void clearAll() {
		TOUCHED.clear();
		READY.clear();
	}
}
