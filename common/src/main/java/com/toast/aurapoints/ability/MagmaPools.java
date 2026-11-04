package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The Fire aura's Magma branch: pools of heat left by Fireball hits, Heat Wave and the Inferno Slam. Enemies
 * standing in one burn and take a little damage every half second; the owner standing in it gets Resistance.
 * A pool is only particles and damage - no lava or any other block is placed, so there is nothing to clean up.
 * Pools live in memory (a restart clears them).
 */
public final class MagmaPools {

	private MagmaPools() {
	}

	private static final class Pool {
		final ResourceKey<Level> dimension;
		final Vec3 pos;
		final double radius;
		final ServerPlayer owner;
		int ticksLeft;

		Pool(ResourceKey<Level> dimension, Vec3 pos, double radius, ServerPlayer owner, int ticks) {
			this.dimension = dimension;
			this.pos = pos;
			this.radius = radius;
			this.owner = owner;
			this.ticksLeft = ticks;
		}
	}

	private static final List<Pool> POOLS = new ArrayList<>();
	/** The most pools one player keeps at once; the oldest fades first. */
	private static final int MAX_PER_OWNER = 8;

	/** A pool of the given radius (blocks) at {@code pos}. Only for a Magma-branch owner: others get nothing. */
	public static void add(ServerPlayer owner, Vec3 pos, double radius) {
		if (!Branch.is(owner, Branch.MAGMA) || AuraConfig.FIRE_MAGMA_POOL_TICKS <= 0 || radius <= 0) return;
		int mine = 0;
		for (Pool p : POOLS) if (p.owner == owner) mine++;
		if (mine >= MAX_PER_OWNER) {
			for (Iterator<Pool> it = POOLS.iterator(); it.hasNext(); ) {
				if (it.next().owner == owner) {
					it.remove();
					break;
				}
			}
		}
		POOLS.add(new Pool(owner.serverLevel().dimension(), pos, radius, owner, AuraConfig.FIRE_MAGMA_POOL_TICKS));
		ServerLevel level = owner.serverLevel();
		AuraFx.particles(level, ParticleTypes.LAVA, pos.x, pos.y + 0.2, pos.z, 8, radius * 0.4, 0.05, radius * 0.4, 0.0);
	}

	public static int count() {
		return POOLS.size();
	}

	public static void clear() {
		POOLS.clear();
	}

	/** Every server tick: looks, then (twice a second) the burn on enemies and Resistance on the owner. */
	public static void tickAll(MinecraftServer server) {
		if (POOLS.isEmpty()) return;
		long tick = server.getTickCount();
		boolean pulse = tick % Math.max(1, AuraConfig.FIRE_MAGMA_POOL_INTERVAL_TICKS) == 0;
		for (Iterator<Pool> it = POOLS.iterator(); it.hasNext(); ) {
			Pool pool = it.next();
			ServerLevel level = server.getLevel(pool.dimension);
			if (level == null || pool.owner.isRemoved() || --pool.ticksLeft <= 0) {
				it.remove();
				continue;
			}
			if (tick % 3 == 0) look(level, pool);
			if (pulse) pulse(level, pool);
		}
	}

	private static void look(ServerLevel level, Pool pool) {
		var random = level.getRandom();
		for (int i = 0; i < 4; i++) {
			double a = random.nextDouble() * Math.PI * 2, d = Math.sqrt(random.nextDouble()) * pool.radius;
			double x = pool.pos.x + Math.cos(a) * d, z = pool.pos.z + Math.sin(a) * d;
			AuraFx.particles(level, i == 0 ? ParticleTypes.LAVA : i == 1 ? ParticleTypes.FLAME : AuraFx.dust(0xFF6A1A, 1.3f),
					x, pool.pos.y + 0.1, z, 1, 0.05, 0.02, 0.05, i == 1 ? 0.01 : 0.0);
		}
		if (pool.ticksLeft % 12 == 0) AuraFx.ring(level, pool.pos.x, pool.pos.y + 0.05, pool.pos.z, pool.radius, AuraFx.dust(0xFFB020, 1.0f), (int) (pool.radius * 8));
	}

	private static void pulse(ServerLevel level, Pool pool) {
		AABB box = new AABB(pool.pos.x - pool.radius, pool.pos.y - 0.6, pool.pos.z - pool.radius,
				pool.pos.x + pool.radius, pool.pos.y + 1.8, pool.pos.z + pool.radius);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
			double dx = e.getX() - pool.pos.x, dz = e.getZ() - pool.pos.z;
			if (dx * dx + dz * dz > pool.radius * pool.radius) continue;
			if (e == pool.owner) {
				pool.owner.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, AuraConfig.FIRE_MAGMA_POOL_INTERVAL_TICKS * 3,
						AuraConfig.FIRE_MAGMA_RESISTANCE_AMPLIFIER, true, false, true));
				continue;
			}
			if (!Handhelds.enemy(pool.owner, e)) continue;
			e.igniteForSeconds(AuraConfig.FIRE_MAGMA_POOL_BURN_SECONDS);
			if (AuraConfig.FIRE_MAGMA_POOL_DAMAGE > 0) {
				e.hurt(level.damageSources().playerAttack(pool.owner), AuraAbilities.vsMob(e, AuraConfig.FIRE_MAGMA_POOL_DAMAGE));
			}
		}
	}
}
