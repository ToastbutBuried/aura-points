package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Umbral Ambush (Shadow, Soul Reaper). You melt into shadow and step out right behind the nearest enemy
 * (facing their back), invisible for a few seconds. Where you stood, a shadow clone - a hooded, purple-eyed
 * figure of shadow (geo/ShadowClone, drawn by GeckoLib) - stays behind, glides at enemies near it and strikes them. Your next melee hit
 * within UMBRAL_BONUS_TICKS deals UMBRAL_BONUS_DAMAGE more.
 */
public final class UmbralAmbush {

	private UmbralAmbush() {
	}

	private static final int SHADOW = 0x7B2FBE;

	/** player -> tick until which their next melee hit carries the bonus */
	private static final Map<UUID, Long> BONUS = new HashMap<>();

	/** Ticks the clone takes to melt into the ground (its vanish animation) before it is gone. */
	private static final int VANISH_TICKS = 12;

	private static final class Clone {
		final UUID owner;
		final ServerLevel level;
		final com.toast.aurapoints.geo.ShadowClone model;
		Vec3 pos;
		float yaw;
		int age, swing, goneAt = -1;
		boolean moved;

		Clone(UUID owner, ServerLevel level, Vec3 pos, float yaw) {
			this.owner = owner;
			this.level = level;
			this.pos = pos;
			this.yaw = yaw;
			this.model = com.toast.aurapoints.AuraPoints.SHADOW_CLONE.get().create(level);
			if (model != null) {
				model.moveTo(pos.x, pos.y, pos.z, yaw, 0);
				model.setYHeadRot(yaw);
				level.addFreshEntity(model);
			}
		}

		void discard() {
			if (model != null) model.discard();
		}
	}

	private static final List<Clone> CLONES = new ArrayList<>();

	static boolean start(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		LivingEntity target = null;
		double best = Double.MAX_VALUE;
		for (LivingEntity e : Handhelds.enemies(player, level, player.getEyePosition(), AuraConfig.UMBRAL_RANGE)) {
			double d = e.distanceToSqr(player);
			if (d < best && sees(player, e)) {
				best = d;
				target = e;
			}
		}
		if (target == null) {
			player.displayClientMessage(Component.literal("No enemy within " + Math.round(AuraConfig.UMBRAL_RANGE) + " blocks to ambush.")
					.withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		Vec3 spot = behind(player, target);
		if (spot == null) {
			player.displayClientMessage(Component.literal("No room behind them.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}

		Vec3 from = player.position();
		CLONES.add(new Clone(player.getUUID(), level, from, player.getYRot()));
		smoke(level, from);

		Vec3 face = AuraFx.chest(target).subtract(spot.add(0, player.getEyeHeight(), 0));
		float yaw = (float) Math.toDegrees(Math.atan2(-face.x, face.z));
		float pitch = (float) Math.toDegrees(-Math.atan2(face.y, Math.sqrt(face.x * face.x + face.z * face.z)));
		player.stopRiding();
		player.connection.teleport(spot.x, spot.y, spot.z, yaw, pitch);
		player.setDeltaMovement(Vec3.ZERO);
		player.fallDistance = 0;
		smoke(level, spot);

		player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, AuraConfig.UMBRAL_INVIS_TICKS, 0, false, false, true));
		BONUS.put(player.getUUID(), (long) player.getServer().getTickCount() + AuraConfig.UMBRAL_BONUS_TICKS);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_VANISH, 1);
		level.playSound(null, from.x, from.y, from.z, AuraSounds.ENDERMAN_TELEPORT.value(), SoundSource.PLAYERS, 0.8f, 0.6f);
		level.playSound(null, spot.x, spot.y, spot.z, AuraSounds.ILLUSIONER_MIRROR_MOVE.value(), SoundSource.PLAYERS, 1.0f, 0.7f);
		level.playSound(null, spot.x, spot.y, spot.z, AuraSounds.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.5f, 0.8f);
		return true;
	}

	private static boolean sees(ServerPlayer player, LivingEntity e) {
		return player.level().clip(new ClipContext(player.getEyePosition(), AuraFx.chest(e), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
				.getType() == HitResult.Type.MISS;
	}

	/** A free spot just behind the target (then beside it, then in front), standing on its level; null if none. */
	private static Vec3 behind(ServerPlayer player, LivingEntity target) {
		Vec3 facing = Vec3.directionFromRotation(0, target.getYRot());
		Vec3 back = facing.scale(-1);
		Vec3 side = new Vec3(-facing.z, 0, facing.x);
		double gap = target.getBbWidth() / 2 + player.getBbWidth() / 2 + 0.4;
		for (Vec3 dir : new Vec3[]{back, back.add(side).normalize(), back.subtract(side).normalize(), side, side.scale(-1), facing}) {
			Vec3 at = target.position().add(dir.scale(gap));
			if (player.level().noCollision(player, player.getDimensions(player.getPose()).makeBoundingBox(at))
					&& player.level().clip(new ClipContext(AuraFx.chest(target), at.add(0, 1, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
					.getType() == HitResult.Type.MISS) return at;
		}
		return null;
	}

	private static void smoke(ServerLevel level, Vec3 at) {
		AuraFx.particles(level, ParticleTypes.LARGE_SMOKE, at.x, at.y + 1, at.z, 30, 0.35, 0.6, 0.35, 0.02);
		AuraFx.particles(level, ParticleTypes.SOUL, at.x, at.y + 1, at.z, 12, 0.4, 0.6, 0.4, 0.03);
		AuraFx.particles(level, ParticleTypes.SQUID_INK, at.x, at.y + 1, at.z, 15, 0.3, 0.5, 0.3, 0.05);
		AuraFx.particles(level, AuraFx.dust(SHADOW, 1.5f), at.x, at.y + 1, at.z, 20, 0.4, 0.6, 0.4, 0);
	}

	/** The bonus on the next melee hit (0 if none): used up by that hit. */
	public static float takeBonus(ServerPlayer attacker, LivingEntity victim) {
		Long until = BONUS.remove(attacker.getUUID());
		if (until == null || attacker.getServer() == null || attacker.getServer().getTickCount() > until) return 0f;
		ServerLevel level = attacker.serverLevel();
		Vec3 c = AuraFx.chest(victim);
		AuraFx.particles(level, ParticleTypes.SOUL, c.x, c.y, c.z, 12, 0.3, 0.4, 0.3, 0.05);
		AuraFx.particles(level, ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 1, 0, 0, 0, 0);
		AuraFx.particles(level, AuraFx.dust(SHADOW, 1.6f), c.x, c.y, c.z, 16, 0.35, 0.45, 0.35, 0);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.WITHER_SKELETON_HURT.value(), SoundSource.PLAYERS, 0.8f, 0.6f);
		attacker.removeEffect(MobEffects.INVISIBILITY); // the ambush is sprung
		return AuraAbilities.scaled(victim, AuraConfig.UMBRAL_BONUS_DAMAGE);
	}

	// ---------------------------------------------------------------
	// The shadow clone
	// ---------------------------------------------------------------

	static void tickAll(MinecraftServer server) {
		if (!BONUS.isEmpty()) BONUS.values().removeIf(until -> until < server.getTickCount() || until > server.getTickCount() + 20 * 60);
		if (CLONES.isEmpty()) return;
		for (Iterator<Clone> it = CLONES.iterator(); it.hasNext(); ) {
			Clone c = it.next();
			boolean done;
			try {
				done = tick(server, c);
			} catch (Exception e) {
				AuraPoints.LOGGER.error("Shadow clone failed", e);
				done = true;
			}
			if (done) {
				c.discard();
				it.remove();
			}
		}
	}

	private static boolean tick(MinecraftServer server, Clone c) {
		ServerPlayer owner = Handhelds.player(server, c.owner);
		int age = c.age++;
		if (owner == null) return true;
		ServerLevel level = c.level;
		// time's up: it melts into the ground (the vanish animation), then it's gone
		if (age >= AuraConfig.UMBRAL_CLONE_TICKS) {
			if (c.goneAt < 0) {
				c.goneAt = age;
				smoke(level, c.pos);
				if (c.model != null) c.model.setState(com.toast.aurapoints.geo.ShadowClone.GONE);
			}
			if (c.model != null) c.model.keepAlive();
			return age - c.goneAt >= VANISH_TICKS;
		}
		c.moved = false;

		// the nearest enemy within reach: glide at it, strike when close
		LivingEntity target = null;
		double best = Double.MAX_VALUE;
		for (LivingEntity e : Handhelds.enemies(owner, level, c.pos.add(0, 1, 0), AuraConfig.UMBRAL_CLONE_REACH)) {
			double d = e.distanceToSqr(c.pos);
			if (d < best) {
				best = d;
				target = e;
			}
		}
		if (target != null) {
			Vec3 to = target.position().subtract(c.pos).multiply(1, 0, 1);
			double d = to.length();
			c.yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
			if (d > 1.3) {
				Vec3 next = c.pos.add(to.scale(Math.min(AuraConfig.UMBRAL_CLONE_SPEED, d - 1.3) / d));
				double y = RootCanvas.surfaceY(level, next, 1, 3);
				if (!Double.isNaN(y)) {
					c.pos = new Vec3(next.x, y, next.z);
					c.moved = true;
				}
			}
			if (d < 2.2 && age % Math.max(1, AuraConfig.UMBRAL_CLONE_ATTACK_TICKS) == 0) {
				c.swing = 8; // the strike animation's length
				target.invulnerableTime = 0;
				Handhelds.hurt(owner, target, AuraConfig.UMBRAL_CLONE_DAMAGE);
				Vec3 hit = AuraFx.chest(target);
				AuraFx.particles(level, ParticleTypes.SWEEP_ATTACK, hit.x, hit.y, hit.z, 1, 0, 0, 0, 0);
				AuraFx.particles(level, ParticleTypes.SOUL, hit.x, hit.y, hit.z, 5, 0.2, 0.3, 0.2, 0.03);
				level.playSound(null, hit.x, hit.y, hit.z, AuraSounds.PLAYER_ATTACK_SWEEP.value(), SoundSource.PLAYERS, 0.8f, 0.6f);
			}
		}
		if (c.swing > 0) c.swing--;
		if (age % 3 == 0) {
			AuraFx.particles(level, ParticleTypes.SOUL, c.pos.x, c.pos.y + 1.2, c.pos.z, 1, 0.3, 0.5, 0.3, 0.01);
			AuraFx.particles(level, AuraFx.dust(SHADOW, 1.0f), c.pos.x, c.pos.y + 0.1, c.pos.z, 2, 0.25, 0.05, 0.25, 0);
		}
		pose(c, age);
		return false;
	}

	/** The figure at its feet, turned to its yaw: striking, walking or hovering (the clients animate it). */
	private static void pose(Clone c, int age) {
		if (c.model == null) return;
		c.model.keepAlive();
		c.model.setPos(c.pos.x, c.pos.y, c.pos.z);
		c.model.setYRot(c.yaw);
		c.model.setYHeadRot(c.yaw);
		c.model.setState(c.swing > 0 ? com.toast.aurapoints.geo.ShadowClone.STRIKE
				: c.moved ? com.toast.aurapoints.geo.ShadowClone.WALK : com.toast.aurapoints.geo.ShadowClone.IDLE);
	}

	static void clearAll() {
		for (Clone c : CLONES) c.discard();
		CLONES.clear();
		BONUS.clear();
	}

	static void onLogout(ServerPlayer player) {
		BONUS.remove(player.getUUID());
	}
}
