package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Vine Hook, Verdant's first active. Two roots - real block models, see VineRoots - tear out of the
 * ground left and right of the player and arc over toward the crosshair (VINE_HOOK_RANGE); if they
 * caught a mob or player, they coil around it, it takes VINE_HOOK_DAMAGE and is yanked
 * toward you (a jump of their own can't stop it, they land about in front of you). A miss still
 * starts the cooldown.
 */
public final class VineHook {

	private VineHook() {
	}


	/** What the roots reach for along {@code dir} from the eyes: the first mob or player, else the block or full range. */
	record Trace(LivingEntity target, Vec3 tip) {
	}

	static Trace trace(ServerPlayer player, Vec3 dir) {
		return trace(player, dir, AuraConfig.VINE_HOOK_RANGE);
	}

	static Trace trace(ServerPlayer player, Vec3 dir, double range) {
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition();
		// a typo'd range (say 40000) would search entities over a huge box and stall the server
		Vec3 far = eye.add(dir.normalize().scale(Math.min(range, AuraConfig.MAX_TRACE_RANGE)));
		HitResult block = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 end = block.getType() == HitResult.Type.MISS ? far : block.getLocation();
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, player, eye, end,
				new AABB(eye, end).inflate(AuraConfig.VINE_HOOK_HITBOX + 1.0), e -> canHook(player, e), (float) AuraConfig.VINE_HOOK_HITBOX);
		if (hit != null) return new Trace((LivingEntity) hit.getEntity(), hit.getLocation());
		// a near miss: whoever is closest to the line of aim, within GRAB_RADIUS of it, still gets caught
		double r = Math.max(0, AuraConfig.VINE_HOOK_GRAB_RADIUS);
		LivingEntity best = null;
		Vec3 bestAt = null;
		double bestAlong = Double.MAX_VALUE;
		if (r > 0) {
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(eye, end).inflate(r + 1), e -> canHook(player, e))) {
				var at = e.getBoundingBox().inflate(r).clip(eye, end);
				if (at.isEmpty()) continue;
				double along = at.get().distanceToSqr(eye);
				if (along < bestAlong) {
					bestAlong = along;
					best = e;
					bestAt = AuraFx.chest(e);
				}
			}
		}
		return best != null ? new Trace(best, bestAt) : new Trace(null, end);
	}

	public static boolean press(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.VINE_HOOK, AuraConfig.VINE_HOOK_COOLDOWN_TICKS, now)) return false;
		Vec3 look = player.getLookAngle().normalize();
		Trace aim = trace(player, look);

		// both hands reach out for the target while the roots grow, then yank back on the catch
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_VINE_HOOK, 0);

		// two roots rip straight up out of the ground left and right of the player, then lash at the target;
		// until they arrive the aim can still be dragged a little (VineRoots)
		double yaw = Math.toRadians(player.getYRot());
		Vec3 side = new Vec3(Math.cos(yaw), 0, Math.sin(yaw)).scale(AuraConfig.VINE_HOOK_ROOT_SIDE);
		Vec3 front = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)).scale(AuraConfig.VINE_HOOK_ROOT_FORWARD);
		Vec3 base = player.position().add(front);
		VineRoots.start(player, new Vec3[]{base.add(side), base.subtract(side)}, aim.target(), aim.tip(), look);
		AuraAbilities.markUsed(player, Ability.VINE_HOOK, now);
		return true;
	}

	/** The roots reached nothing: a rustle where they whipped. */
	static void miss(ServerLevel level, Vec3 tip) {
		AuraFx.burst(level, tip.x, tip.y, tip.z, ParticleTypes.COMPOSTER, 8, 0.2, 0.0);
		level.playSound(null, tip.x, tip.y, tip.z, AuraSounds.AZALEA_LEAVES_BREAK.value(), SoundSource.PLAYERS, 0.8f, 1.0f);
	}


	static boolean canHook(ServerPlayer owner, Entity e) {
		return Handhelds.enemy(owner, e); // the shared rule: party protection, viper steerers untouchable
	}

	/** The catch: damage and a burst of leaves - VineRoots carries the target in (through any block). */
	static void yank(ServerPlayer player, LivingEntity target) {
		ServerLevel level = player.serverLevel();
		AuraStats.add(player, AuraStats.VINE_HOOK_PULLS, 1);
		// no pull-then-cage in one breath: Entanglement waits a moment after a catch
		if (AuraConfig.VINE_HOOK_ENTANGLE_LOCK_TICKS > 0) {
			AuraAbilities.atLeastCooldown(player, Ability.ENTANGLEMENT, AuraConfig.VINE_HOOK_ENTANGLE_LOCK_TICKS, level.getServer().getTickCount());
		}
		if (AuraConfig.VINE_HOOK_DAMAGE > 0) {
			target.hurt(level.damageSources().playerAttack(player), AuraAbilities.vsMob(target, AuraConfig.VINE_HOOK_DAMAGE));
		}
		Vec3 c = AuraFx.chest(target);
		var leaves = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.defaultBlockState());
		AuraFx.burst(level, c.x, c.y, c.z, leaves, 20, 0.3, 0.1);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.COMPOSTER, 10, 0.3, 0.0);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.CAVE_VINES_PICK_BERRIES.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.FISHING_BOBBER_RETRIEVE.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
	}

}
