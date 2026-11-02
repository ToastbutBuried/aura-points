package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chain Strike (Thunder, Zeus Gauntlets). You turn into lightning (invisible, weightless) and flash from
 * enemy to enemy - a bolt drawn from where you were to where you land beside the next one - hitting each,
 * up to CHAIN_STRIKE_TARGETS: the first is the one nearest your crosshair, each next the nearest new one to
 * the last. On the last one a giant bolt comes down. Every hit leaves its target Charged for Wrath of Zeus.
 */
final class ChainStrike {

	private ChainStrike() {
	}

	private static final class Chain {
		final Set<LivingEntity> hit = new HashSet<>();
		LivingEntity next;
		int hops, wait;
		int afterglow = -1;
		/** An Invisibility the player already had (a potion, Vanish): it comes back after the chain. */
		MobEffectInstance invisBefore;
		final long started;

		Chain(LivingEntity first, MobEffectInstance invisBefore, long started) {
			this.next = first;
			this.invisBefore = invisBefore;
			this.started = started;
		}
	}

	/** On the player while weightless, so a crash mid-chain can be undone on the next login (NoGravity is saved). */
	private static final String TAG = "aurapoints_chain_strike";

	private static final Map<UUID, Chain> CHAINS = new HashMap<>();

	static boolean start(ServerPlayer player) {
		if (CHAINS.containsKey(player.getUUID())) return false;
		ServerLevel level = player.serverLevel();
		// the enemy closest to the crosshair, within 25 degrees, that you can see
		LivingEntity first = null;
		double best = Math.cos(Math.toRadians(25));
		Vec3 eye = player.getEyePosition(), look = player.getLookAngle();
		for (LivingEntity e : Handhelds.enemies(player, level, eye, AuraConfig.CHAIN_STRIKE_RANGE)) {
			Vec3 to = AuraFx.chest(e).subtract(eye);
			double dot = to.normalize().dot(look);
			if (dot > best && sees(level, player, eye, AuraFx.chest(e))) {
				best = dot;
				first = e;
			}
		}
		if (first == null) {
			player.displayClientMessage(Component.literal("Aim at an enemy within " + Math.round(AuraConfig.CHAIN_STRIKE_RANGE) + " blocks.")
					.withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		MobEffectInstance invis = player.getEffect(MobEffects.INVISIBILITY);
		CHAINS.put(player.getUUID(), new Chain(first, invis == null ? null : new MobEffectInstance(invis), player.serverLevel().getServer().getTickCount()));
		player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, AuraConfig.CHAIN_STRIKE_TARGETS * AuraConfig.CHAIN_STRIKE_HOP_TICKS + 10, 0, false, false, false));
		player.setNoGravity(true);
		player.addTag(TAG);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 1.0f, 1.6f);
		Thunderbolt.impact(level, AuraFx.chest(player), 1.0f);
		return true;
	}

	private static boolean sees(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
	}

	static void tickAll(MinecraftServer server) {
		if (CHAINS.isEmpty()) return;
		for (Iterator<Map.Entry<UUID, Chain>> it = CHAINS.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			ServerPlayer player = Handhelds.player(server, entry.getKey());
			boolean done;
			try {
				done = player == null || !player.isAlive() || tick(player, entry.getValue());
			} catch (Exception e) {
				AuraPoints.LOGGER.error("Chain Strike failed", e);
				done = true;
			}
			if (done) {
				if (player != null) land(player, entry.getValue());
				it.remove();
			}
		}
	}

	private static boolean tick(ServerPlayer player, Chain c) {
		player.setDeltaMovement(Vec3.ZERO);
		player.fallDistance = 0;
		if (c.afterglow >= 0) return --c.afterglow < 0;
		if (c.wait-- > 0) return false;
		ServerLevel level = player.serverLevel();
		LivingEntity target = c.next;
		if (target == null || !target.isAlive() || target.level() != level) {
			target = nearestNew(player, c, player.position(), AuraConfig.CHAIN_STRIKE_HOP_RANGE);
			if (target == null) return true;
		}
		hop(player, c, target);
		c.hit.add(target);
		c.hops++;
		boolean last = c.hops >= Math.max(1, AuraConfig.CHAIN_STRIKE_TARGETS);
		c.next = last ? null : nearestNew(player, c, target.position(), AuraConfig.CHAIN_STRIKE_HOP_RANGE);
		if (last || c.next == null) {
			finale(player, target);
			c.afterglow = 6;
			return false;
		}
		c.wait = Math.max(1, AuraConfig.CHAIN_STRIKE_HOP_TICKS) - 1;
		return false;
	}

	private static LivingEntity nearestNew(ServerPlayer player, Chain c, Vec3 from, double range) {
		LivingEntity best = null;
		double bd = Double.MAX_VALUE;
		ServerLevel level = player.serverLevel();
		for (LivingEntity e : Handhelds.enemies(player, level, from.add(0, 1, 0), range)) {
			if (c.hit.contains(e)) continue;
			double d = e.distanceToSqr(from);
			if (d < bd && sees(level, player, from.add(0, 1, 0), AuraFx.chest(e))) {
				bd = d;
				best = e;
			}
		}
		return best;
	}

	/** Flash from here to just beside the target, facing it, and hit it. */
	private static void hop(ServerPlayer player, Chain c, LivingEntity target) {
		ServerLevel level = player.serverLevel();
		Vec3 from = AuraFx.chest(player);
		Vec3 away = player.position().subtract(target.position()).multiply(1, 0, 1);
		away = away.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : away.normalize();
		Vec3 spot = target.position().add(away.scale(target.getBbWidth() / 2 + 0.9));
		if (!level.noCollision(player, player.getBoundingBox().move(spot.subtract(player.position())))) spot = target.position().add(0, target.getBbHeight() + 0.1, 0);
		Vec3 face = AuraFx.chest(target).subtract(spot.add(0, player.getEyeHeight(), 0));
		float yaw = (float) Math.toDegrees(Math.atan2(-face.x, face.z));
		float pitch = (float) Math.toDegrees(-Math.atan2(face.y, Math.sqrt(face.x * face.x + face.z * face.z)));
		player.stopRiding();
		player.connection.teleport(spot.x, spot.y, spot.z, yaw, pitch);

		Thunderbolt.bolt(level, from, AuraFx.chest(target), 0.9f, 2, 2);
		Thunderbolt.impact(level, AuraFx.chest(target), 1.0f);
		target.invulnerableTime = 0;
		Handhelds.hurt(player, target, AuraConfig.CHAIN_STRIKE_DAMAGE);
		Thunderbolt.charge(target, 1);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_CHIDORI, 0);
		level.playSound(null, spot.x, spot.y, spot.z, AuraSounds.LIGHTNING_BOLT_IMPACT.value(), SoundSource.PLAYERS, 1.2f, 1.4f);
		level.playSound(null, spot.x, spot.y, spot.z, AuraSounds.PLAYER_ATTACK_STRONG.value(), SoundSource.PLAYERS, 1.0f, 1.2f);
	}

	/** The giant bolt on the last one: more damage to it, a splash round it, everyone paralyzed. */
	private static void finale(ServerPlayer player, LivingEntity target) {
		ServerLevel level = player.serverLevel();
		Vec3 at = target.position();
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
		if (bolt != null) {
			bolt.moveTo(at.x, at.y, at.z);
			bolt.setVisualOnly(true);
			level.addFreshEntity(bolt);
		}
		Thunderbolt.bolt(level, at.add(0, 24, 0), at, 1.6f, 4, 3);
		Thunderbolt.impact(level, at.add(0, 0.5, 0), 2.0f);
		target.invulnerableTime = 0;
		Handhelds.hurt(player, target, AuraConfig.CHAIN_STRIKE_FINAL_DAMAGE);
		paralyze(target);
		for (LivingEntity e : Handhelds.enemies(player, level, at.add(0, 1, 0), AuraConfig.CHAIN_STRIKE_FINAL_RADIUS)) {
			if (e == target) continue;
			e.invulnerableTime = 0;
			Handhelds.hurt(player, e, AuraConfig.CHAIN_STRIKE_FINAL_SPLASH_DAMAGE);
			paralyze(e);
		}
		AuraFx.particles(level, ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.2, at.z, 60, 1.5, 0.2, 1.5, 0.4);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.LIGHTNING_BOLT_THUNDER.value(), SoundSource.PLAYERS, 2.0f, 1.0f);
	}

	private static void paralyze(LivingEntity e) {
		int ticks = AuraConfig.CHAIN_STRIKE_PARALYZE_TICKS;
		if (ticks <= 0) return;
		if (e instanceof ServerPlayer p) Stun.stun(p, ticks);
		else e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 5, false, true, true));
	}

	/** Back to flesh: gravity, visible, no fall damage from the trip. */
	private static void land(ServerPlayer player, Chain chain) {
		player.setNoGravity(false);
		player.removeTag(TAG);
		player.removeEffect(MobEffects.INVISIBILITY);
		// an Invisibility from before the chain keeps running for whatever it had left
		MobEffectInstance before = chain.invisBefore;
		if (before != null && player.getServer() != null) {
			int left = before.isInfiniteDuration() ? MobEffectInstance.INFINITE_DURATION
					: before.getDuration() - (int) (player.getServer().getTickCount() - chain.started);
			if (left == MobEffectInstance.INFINITE_DURATION || left > 0) {
				player.addEffect(new MobEffectInstance(before.getEffect(), left, before.getAmplifier(), before.isAmbient(), before.isVisible(), before.showIcon()));
			}
		}
		player.fallDistance = 0;
		ServerLevel level = player.serverLevel();
		Vec3 c = AuraFx.chest(player);
		AuraFx.particles(level, ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 20, 0.3, 0.5, 0.3, 0.2);
	}

	/** Server stop: nothing to do - the players still log out after this, and onLogout gives them their gravity back. */
	static void clearAll() {
	}

	static void onLogout(ServerPlayer player) {
		Chain c = CHAINS.remove(player.getUUID());
		if (c != null) land(player, c);
	}

	/** A crash mid-chain left the tag (and NoGravity) on the player: take both off. */
	static void onLogin(ServerPlayer player) {
		if (player.getTags().contains(TAG)) {
			player.removeTag(TAG);
			player.setNoGravity(false);
		}
	}
}
