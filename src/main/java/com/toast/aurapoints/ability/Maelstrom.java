package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Maelstrom (Water, Leviathan's Anchor). The anchor slams down a few blocks ahead; kraken tentacles
 * (geo/KrakenTentacle, drawn by GeckoLib, facing the middle) burst out of the ground in a ring and every enemy within
 * MAELSTROM_RADIUS is dragged into a whirlpool round that spot, slowed. When the pull ends a giant tentacle
 * rears up behind the ring and slams down on the middle.
 */
final class Maelstrom {

	private Maelstrom() {
	}

	/** The tentacle model is this tall standing (blocks), and the ring's are drawn this much bigger. */
	private static final double MODEL_LENGTH = 4.8, SMALL_SCALE = 0.95;
	private static final int GIANT_RAISE_TICKS = 14, GIANT_SLAM_TICKS = 5, SINK_TICKS = 10;
	private static final int TEAL = 0x1F9C9C, FOAM = 0xC8FFF4;

	private static final class Tentacle {
		final Vec3 base;
		final com.toast.aurapoints.geo.KrakenTentacle model;

		Tentacle(Vec3 base, com.toast.aurapoints.geo.KrakenTentacle model) {
			this.base = base;
			this.model = model;
		}

		void set(int state) {
			if (model != null) model.setState(state);
		}
	}

	private static final class Pool {
		final UUID owner;
		final ServerLevel level;
		final Vec3 center;
		final List<Tentacle> tentacles = new ArrayList<>();
		Tentacle giant;
		Vec3 giantBase;
		int age;

		Pool(UUID owner, ServerLevel level, Vec3 center) {
			this.owner = owner;
			this.level = level;
			this.center = center;
		}

		void discard() {
			for (Tentacle t : tentacles) if (t.model != null) t.model.discard();
			if (giant != null && giant.model != null) giant.model.discard();
		}
	}

	private static final List<Pool> POOLS = new ArrayList<>();

	static boolean start(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 look = player.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0, look.z);
		flat = flat.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : flat.normalize();
		Vec3 ahead = player.position().add(flat.scale(3.0));
		double y = RootCanvas.surfaceY(level, ahead, 2, 6);
		Vec3 center = Double.isNaN(y) ? player.position() : new Vec3(ahead.x, y, ahead.z);

		Pool pool = new Pool(player.getUUID(), level, center);
		int n = Math.max(0, AuraConfig.MAELSTROM_TENTACLES);
		double ring = AuraConfig.MAELSTROM_RADIUS * 0.7;
		for (int i = 0; i < n; i++) {
			double a = Math.PI * 2 * i / n + level.random.nextDouble() * 0.5;
			Vec3 at = center.add(Math.cos(a) * ring, 0, Math.sin(a) * ring);
			double gy = RootCanvas.surfaceY(level, at, 3, 6);
			if (!Double.isNaN(gy)) at = new Vec3(at.x, gy, at.z);
			pool.tentacles.add(tentacle(pool, at, SMALL_SCALE));
		}
		// the giant comes up behind the middle, as seen from the caster
		Vec3 back = center.add(flat.scale(AuraConfig.MAELSTROM_RADIUS * 0.9));
		double by = RootCanvas.surfaceY(level, back, 3, 8);
		pool.giantBase = Double.isNaN(by) ? new Vec3(back.x, center.y, back.z) : new Vec3(back.x, by, back.z);
		POOLS.add(pool);

		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_MAELSTROM, 0);
		AuraFx.sound(level, player, AuraSounds.SWEET_IMPACT.value(), 1.2f, 0.8f);
		level.playSound(null, center.x, center.y, center.z, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), SoundSource.PLAYERS, 1.2f, 0.7f);
		level.playSound(null, center.x, center.y, center.z, AuraSounds.ELDER_GUARDIAN_CURSE.value(), SoundSource.PLAYERS, 0.6f, 1.4f);
		level.playSound(null, center.x, center.y, center.z, AuraSounds.GENERIC_SPLASH.value(), SoundSource.PLAYERS, 1.5f, 0.6f);
		var crack = new BlockParticleOption(ParticleTypes.BLOCK, RootCanvas.groundBlock(level, center));
		for (double r = 1; r <= AuraConfig.MAELSTROM_RADIUS; r += 1.5) AuraFx.ring(level, center.x, center.y + 0.1, center.z, r, crack, (int) (r * 5));
		return true;
	}

	/** A tentacle at {@code base}, its front (suckers) turned to the middle of the pool. */
	private static Tentacle tentacle(Pool pool, Vec3 base, double scale) {
		var model = com.toast.aurapoints.AuraPoints.KRAKEN_TENTACLE.get().create(pool.level);
		if (model != null) {
			Vec3 to = pool.center.subtract(base);
			float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
			model.moveTo(base.x, base.y, base.z, yaw, 0);
			model.setYHeadRot(yaw);
			model.setScale((float) scale);
			pool.level.addFreshEntity(model);
		}
		return new Tentacle(base, model);
	}

	static void tickAll(MinecraftServer server) {
		if (POOLS.isEmpty()) return;
		for (Iterator<Pool> it = POOLS.iterator(); it.hasNext(); ) {
			Pool pool = it.next();
			boolean done;
			try {
				done = tick(server, pool);
			} catch (Exception e) {
				AuraPoints.LOGGER.error("Maelstrom failed", e);
				done = true;
			}
			if (done) {
				pool.discard();
				it.remove();
			}
		}
	}

	private static boolean tick(MinecraftServer server, Pool pool) {
		ServerPlayer owner = Handhelds.player(server, pool.owner);
		int age = pool.age++;
		int pull = Math.max(1, AuraConfig.MAELSTROM_PULL_TICKS);
		int end = pull + GIANT_SLAM_TICKS + SINK_TICKS + 6;
		if (age > end || owner == null) return true;
		ServerLevel level = pool.level;
		Vec3 c = pool.center;

		// the tentacles writhe by themselves (the clients animate them); they sink at the end
		int sinkAt = pull + GIANT_SLAM_TICKS + 4;
		for (Tentacle t : pool.tentacles) {
			if (t.model != null) t.model.keepAlive();
			if (age == sinkAt) t.set(com.toast.aurapoints.geo.KrakenTentacle.GONE);
		}

		// the whirlpool
		if (age < pull) {
			double spin = age * 0.35;
			for (int k = 0; k < 3; k++) {
				double r = AuraConfig.MAELSTROM_RADIUS * (0.25 + 0.3 * k);
				for (int i = 0; i < 6; i++) {
					double a = spin * (1.4 - k * 0.3) + Math.PI * 2 * i / 6 + k;
					double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
					AuraFx.particles(level, ParticleTypes.SPLASH, x, c.y + 0.2, z, 2, 0.1, 0, 0.1, 0);
					AuraFx.particles(level, AuraFx.dust(k == 0 ? FOAM : TEAL, 1.4f), x, c.y + 0.15, z, 1, 0, 0, 0, 0);
				}
			}
			if (age % 3 == 0) AuraFx.particles(level, ParticleTypes.BUBBLE_COLUMN_UP, c.x, c.y + 0.3, c.z, 8, 1.0, 0.2, 1.0, 0.1);
			if (age % 20 == 0) level.playSound(null, c.x, c.y, c.z, AuraSounds.BUBBLE_COLUMN_WHIRLPOOL_INSIDE.value(), SoundSource.PLAYERS, 1.5f, 0.7f);

			for (LivingEntity e : Handhelds.enemies(owner, level, c.add(0, 1, 0), AuraConfig.MAELSTROM_RADIUS + 1)) {
				Vec3 to = c.subtract(e.position()).multiply(1, 0, 1);
				double dist = to.length();
				Vec3 in = dist < 1.0e-3 ? Vec3.ZERO : to.scale(1 / dist);
				Vec3 round = new Vec3(-in.z, 0, in.x);
				double pullIn = AuraConfig.MAELSTROM_PULL_STRENGTH * Math.min(1.0, dist / 1.5);
				Vec3 v = e.getDeltaMovement().multiply(0.6, 1, 0.6).add(in.scale(pullIn)).add(round.scale(AuraConfig.MAELSTROM_SPIN_STRENGTH * Math.min(1.0, dist / 2.0)));
				e.setDeltaMovement(v.x, Math.min(v.y, 0.1), v.z);
				e.hurtMarked = true;
				if (AuraConfig.MAELSTROM_SLOW_AMPLIFIER >= 0 && !e.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) {
					e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, pull - age + 5, AuraConfig.MAELSTROM_SLOW_AMPLIFIER, false, true, true));
				}
			}
		}

		// the giant: rears up over the last part of the pull, then slams down on the middle. Drawn big enough that the
		// slam reaches just past the middle.
		int raiseAt = Math.max(0, pull - GIANT_RAISE_TICKS);
		if (age == raiseAt) {
			double reach = pool.giantBase.multiply(1, 0, 1).distanceTo(c.multiply(1, 0, 1)) + 1.0;
			pool.giant = tentacle(pool, pool.giantBase, Math.max(1.0, reach / MODEL_LENGTH));
			pool.giant.set(com.toast.aurapoints.geo.KrakenTentacle.RAISE);
			level.playSound(null, pool.giantBase.x, pool.giantBase.y, pool.giantBase.z, AuraSounds.WARDEN_EMERGE.value(), SoundSource.PLAYERS, 1.2f, 1.3f);
		}
		if (pool.giant != null) {
			if (pool.giant.model != null) pool.giant.model.keepAlive();
			if (age == pull) pool.giant.set(com.toast.aurapoints.geo.KrakenTentacle.SLAM);
			if (age == pull + GIANT_SLAM_TICKS) slam(owner, pool);
			if (age == sinkAt) pool.giant.set(com.toast.aurapoints.geo.KrakenTentacle.GONE);
		}
		return false;
	}

	private static void slam(ServerPlayer owner, Pool pool) {
		ServerLevel level = pool.level;
		Vec3 c = pool.center;
		for (LivingEntity e : Handhelds.enemies(owner, level, c.add(0, 0.8, 0), AuraConfig.MAELSTROM_SLAM_RADIUS)) {
			e.invulnerableTime = 0;
			Handhelds.hurt(owner, e, AuraConfig.MAELSTROM_DAMAGE);
			e.setDeltaMovement(e.getDeltaMovement().add(0, AuraConfig.MAELSTROM_KNOCKUP, 0));
			e.hurtMarked = true;
		}
		level.playSound(null, c.x, c.y, c.z, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), SoundSource.PLAYERS, 2.0f, 0.5f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.PLAYER_SPLASH_HIGH_SPEED.value(), SoundSource.PLAYERS, 2.0f, 0.6f);
		AuraFx.particles(level, ParticleTypes.EXPLOSION, c.x, c.y + 0.5, c.z, 3, 1.0, 0.3, 1.0, 0);
		AuraFx.particles(level, ParticleTypes.SPLASH, c.x, c.y + 0.5, c.z, 200, 2.5, 0.6, 2.5, 0.4);
		AuraFx.particles(level, ParticleTypes.BUBBLE_POP, c.x, c.y + 0.5, c.z, 60, 2.0, 0.5, 2.0, 0.2);
		var crack = new BlockParticleOption(ParticleTypes.BLOCK, RootCanvas.groundBlock(level, c));
		AuraFx.particles(level, crack, c.x, c.y + 0.2, c.z, 80, 2.0, 0.2, 2.0, 0.2);
		AuraFx.ring(level, c.x, c.y + 0.2, c.z, AuraConfig.MAELSTROM_SLAM_RADIUS, AuraFx.dust(FOAM, 2.0f), 40);
	}

	static void clearAll() {
		for (Pool p : POOLS) p.discard();
		POOLS.clear();
	}
}
