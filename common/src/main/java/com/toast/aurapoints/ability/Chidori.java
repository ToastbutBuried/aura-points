package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Chidori, Thunder's second active - the lightning blade. Hold the key: lightning gathers in your right
 * hand, held low at your side (CHIDORI_HOLD pose, the left hand gripping the wrist), chirping like a
 * thousand birds; you walk slower and it grows over CHARGE_TICKS (HUD bar). Let go: you lunge straight
 * where you look - no steering once you go, tunnel vision - and the first mob or player in your path is
 * run through: damage by charge, Charged (for Wrath of Zeus), paralyzed, a spear of lightning bursting out
 * of their back. Let go too early (under MIN_CHARGE) and it fizzles on a short cooldown. Held past
 * MAX_HOLD_TICKS, it goes off on its own. In memory only.
 */
public final class Chidori {

	private Chidori() {
	}

	private static final ResourceLocation SLOW_ID = ResourceLocation.fromNamespaceAndPath("aurapoints", "chidori_charge");

	/** player -> tick the charge started */
	private static final Map<UUID, Long> CHARGING = new HashMap<>();

	private static final class Lunge {
		final Vec3 dir;
		final double speed;
		final double charge;
		int ticksLeft;
		Vec3 lastHand;

		Lunge(Vec3 dir, double speed, double charge, int ticks) {
			this.dir = dir;
			this.speed = speed;
			this.charge = charge;
			this.ticksLeft = ticks;
		}
	}

	private static final Map<UUID, Lunge> LUNGING = new HashMap<>();

	public static boolean isCharging(ServerPlayer player) {
		return CHARGING.containsKey(player.getUUID());
	}

	public static boolean isLunging(ServerPlayer player) {
		return LUNGING.containsKey(player.getUUID());
	}

	/** Ticks a full Chidori charge takes (Surge faster). */
	public static int chargeTicks(ServerPlayer player) {
		return Branch.is(player, Branch.SURGE) ? AuraConfig.THUNDER_SURGE_CHIDORI_CHARGE_TICKS : AuraConfig.CHIDORI_CHARGE_TICKS;
	}

	private static double charge(ServerPlayer player, long since, long now) {
		return Mth.clamp((now - since) / (double) Math.max(1, chargeTicks(player)), 0.0, 1.0);
	}

	/** The right hand, held low at the side. */
	private static Vec3 hand(ServerPlayer player) {
		double yaw = Math.toRadians(player.yBodyRot);
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		return player.position().add(0, player.isCrouching() ? 0.55 : 0.7, 0).add(right.scale(0.5)).add(forward.scale(0.15));
	}

	// ---------------------------------------------------------------
	// Keys
	// ---------------------------------------------------------------

	public static boolean press(ServerPlayer player, long now) {
		if (CHARGING.containsKey(player.getUUID()) || LUNGING.containsKey(player.getUUID()) || Thunderbolt.isActive(player)) return false;
		if (!AuraAbilities.checkCooldown(player, Ability.CHIDORI, AuraConfig.CHIDORI_COOLDOWN_TICKS, now)) return false;
		CHARGING.put(player.getUUID(), now);
		var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null && AuraConfig.CHIDORI_CHARGE_SLOWNESS > 0 && speed.getModifier(SLOW_ID) == null) {
			speed.addTransientModifier(new AttributeModifier(SLOW_ID, -Math.min(1.0, AuraConfig.CHIDORI_CHARGE_SLOWNESS), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.CHIDORI_HOLD);
		NetworkHandler.sendFireBending(player, true, 0f, 1f);
		ServerLevel level = player.serverLevel();
		Vec3 h = hand(player);
		Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, h.x, h.y, h.z, 12, 0.1, 0.1, 0.1, 0.2);
		level.playSound(null, h.x, h.y, h.z, AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.4f, 2.0f);
		return true;
	}

	/** Key released: lunge (or fizzle if it isn't charged enough). */
	public static void release(ServerPlayer player, long now) {
		Long since = CHARGING.remove(player.getUUID());
		if (since == null) return;
		double charge = charge(player, since, now);
		endCharge(player);
		ServerLevel level = player.serverLevel();
		Vec3 h = hand(player);
		if (charge < AuraConfig.CHIDORI_MIN_CHARGE) {
			// not enough: it sputters out
			Thunderbolt.send(level, ParticleTypes.SMOKE, h.x, h.y, h.z, 6, 0.1, 0.1, 0.1, 0.02);
			Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, h.x, h.y, h.z, 4, 0.1, 0.1, 0.1, 0.05);
			level.playSound(null, h.x, h.y, h.z, AuraSounds.FIRE_EXTINGUISH.value(), SoundSource.PLAYERS, 0.5f, 1.8f);
			AuraAbilities.startCooldown(player, Ability.CHIDORI, AuraConfig.CHIDORI_FIZZLE_COOLDOWN_TICKS, now);
			return;
		}
		Vec3 look = player.getLookAngle();
		// mostly along the ground: the look's pitch counts only partly
		Vec3 dir = new Vec3(look.x, look.y * AuraConfig.CHIDORI_PITCH_FACTOR, look.z).normalize();
		double speed = Mth.lerp(charge, AuraConfig.CHIDORI_MIN_SPEED, AuraConfig.CHIDORI_MAX_SPEED);
		double distance = Mth.lerp(charge, AuraConfig.CHIDORI_MIN_DISTANCE, AuraConfig.CHIDORI_MAX_DISTANCE);
		Lunge l = new Lunge(dir, speed, charge, Math.max(1, (int) Math.ceil(distance / speed)));
		l.lastHand = h;
		LUNGING.put(player.getUUID(), l);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_CHIDORI, 0);
		AuraAbilities.markUsed(player, Ability.CHIDORI, now);
		AuraStats.add(player, AuraStats.CHIDORI_LUNGES, 1);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.0f, 1.5f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.7f, 1.8f);
		Thunderbolt.send(level, ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1, player.getZ(), 10, 0.3, 0.05, 0.3, 0.08);
	}

	private static void endCharge(ServerPlayer player) {
		var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) speed.removeModifier(SLOW_ID);
		NetworkHandler.sendFireBending(player, false, 0, 0);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
	}

	/** Gone without a lunge or cooldown (ability lost, death, logout, frozen). */
	private static void drop(ServerPlayer player) {
		if (CHARGING.remove(player.getUUID()) != null) endCharge(player);
		LUNGING.remove(player.getUUID());
	}

	// ---------------------------------------------------------------
	// Ticking
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		if (CHARGING.isEmpty() && LUNGING.isEmpty()) return;
		long now = server.getTickCount();
		AuraState state = AuraState.get(server);
		for (UUID id : new java.util.ArrayList<>(CHARGING.keySet())) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) {
				CHARGING.remove(id);
				continue;
			}
			tickCharge(p, state.getOrCreate(id), now);
		}
		for (Iterator<Map.Entry<UUID, Lunge>> it = LUNGING.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || !p.isAlive() || p.isSpectator() || IceSpike.isFrozen(p) || Stun.isStunned(p) || Entanglement.isEntangled(p)) {
				it.remove();
				continue;
			}
			if (!tickLunge(p, e.getValue())) it.remove();
		}
	}

	private static void tickCharge(ServerPlayer player, AuraPlayerData data, long now) {
		Long since = CHARGING.get(player.getUUID());
		if (!AuraAbilities.hasAbility(data, Ability.CHIDORI) || !player.isAlive() || player.isSpectator()
				|| IceSpike.isFrozen(player) || Stun.isStunned(player) || Entanglement.isEntangled(player)) {
			drop(player);
			return;
		}
		if (now < since) since = now;
		if (now - since >= AuraConfig.CHIDORI_MAX_HOLD_TICKS) {
			release(player, now);
			return;
		}
		double charge = charge(player, since, now);
		boolean full = charge >= 1.0 - 1.0e-6;
		if (now % 2 == 0) NetworkHandler.sendFireBending(player, true, (float) charge, 1f);

		ServerLevel level = player.serverLevel();
		RandomSource rnd = player.getRandom();
		Vec3 h = hand(player);
		// the blade: a white-hot knot in the hand, sparks pouring off it
		Thunderbolt.send(level, AuraFx.dust(Thunderbolt.CORE, (float) (0.5 + charge * 0.9)), h.x, h.y, h.z, 2, 0.04, 0.04, 0.04, 0);
		Thunderbolt.send(level, AuraFx.dust(Thunderbolt.GLOW, (float) (0.8 + charge * 1.2)), h.x, h.y, h.z, 2, 0.12, 0.12, 0.12, 0);
		Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, h.x, h.y, h.z, 2 + (int) (charge * 8), 0.12, 0.12, 0.12, 0.12 + charge * 0.15);
		// arcs lashing out of it, longer and more of them as it grows
		int arcs = full ? 3 : 1 + (charge > 0.5 ? 1 : 0);
		for (int i = 0; i < arcs; i++) {
			if (rnd.nextFloat() > 0.35f + charge * 0.5f) continue;
			Vec3 d = new Vec3(rnd.nextGaussian(), rnd.nextGaussian() * 0.6, rnd.nextGaussian()).normalize();
			Vec3 to = h.add(d.scale(0.5 + charge * 1.1 * rnd.nextDouble() + 0.2));
			Thunderbolt.bolt(level, h, to, 0.35f + (float) charge * 0.15f, 0, 1);
		}
		// fully charged: the hand drags lightning over the ground
		if (full) {
			HitResult down = level.clip(new ClipContext(h, h.add(0, -2.5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (down.getType() != HitResult.Type.MISS && rnd.nextFloat() < 0.6f) {
				Vec3 g = down.getLocation().add(0, 0.05, 0);
				Thunderbolt.bolt(level, h, g.add(rnd.nextGaussian() * 0.4, 0, rnd.nextGaussian() * 0.4), 0.4f, 0, 1);
				Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, g.x, g.y, g.z, 3, 0.2, 0, 0.2, 0.1);
			}
		}
		// a thousand birds
		if (now % 2 == 0) {
			float pitch = 1.5f + rnd.nextFloat() * 0.5f;
			level.playSound(null, h.x, h.y, h.z, AuraSounds.PARROT_AMBIENT.value(), SoundSource.PLAYERS, 0.25f + (float) charge * 0.25f, pitch);
			level.playSound(null, h.x, h.y, h.z, AuraSounds.AMETHYST_CLUSTER_HIT.value(), SoundSource.PLAYERS, 0.3f + (float) charge * 0.3f, 1.4f + rnd.nextFloat() * 0.6f);
		}
		if (full && CHARGING.get(player.getUUID()) + chargeTicks(player) == now) {
			level.playSound(null, h.x, h.y, h.z, AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
			Thunderbolt.impact(level, h, 0.6f);
		}
	}

	/** One tick of the lunge; false once it's over. */
	private static boolean tickLunge(ServerPlayer player, Lunge l) {
		ServerLevel level = player.serverLevel();
		player.fallDistance = 0;
		Vec3 chest = AuraFx.chest(player);
		Vec3 step = l.dir.scale(l.speed);

		// who's in the way this tick: the closest along the path
		double reach = l.speed + 0.8;
		Vec3 end = chest.add(l.dir.scale(reach));
		double hb = AuraConfig.CHIDORI_HITBOX;
		Combos.pass(player, chest, end, hb, l.dir);
		LivingEntity target = null;
		double best = Double.MAX_VALUE;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(chest, end).inflate(hb + 1), e -> VineHook.canHook(player, e))) {
			var at = e.getBoundingBox().inflate(hb).clip(chest, end);
			if (at.isEmpty()) continue;
			double d = at.get().distanceToSqr(chest);
			if (d < best) {
				best = d;
				target = e;
			}
		}
		if (target != null) {
			pierce(player, l, target);
			return false;
		}
		// a wall: it crashes into it
		HitResult wall = level.clip(new ClipContext(chest, chest.add(step), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (wall.getType() != HitResult.Type.MISS) {
			Vec3 w = wall.getLocation();
			Thunderbolt.impact(level, w, 0.8f);
			level.playSound(null, w.x, w.y, w.z, AuraSounds.LIGHTNING_BOLT_IMPACT.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
			player.setDeltaMovement(Vec3.ZERO);
			player.hurtMarked = true;
			return false;
		}

		player.setDeltaMovement(step.x, step.y + (player.onGround() ? 0.05 : 0), step.z);
		player.hurtMarked = true;
		// the streak: lightning from where the hand was to where it is, and an afterimage
		Vec3 h = hand(player).add(step);
		Thunderbolt.bolt(level, l.lastHand, h, 0.5f, 1, 2);
		Thunderbolt.send(level, AuraFx.dust(Thunderbolt.DEEP, 1.6f), chest.x, chest.y - 0.3, chest.z, 4, 0.2, 0.5, 0.2, 0);
		Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, player.getX(), player.getY() + 0.05, player.getZ(), 3, 0.2, 0, 0.2, 0.15);
		l.lastHand = h;
		if (--l.ticksLeft <= 0) {
			// ran out of reach: skid to a stop with the blade fading
			player.setDeltaMovement(step.scale(0.25));
			player.hurtMarked = true;
			Thunderbolt.send(level, ParticleTypes.SMOKE, h.x, h.y, h.z, 6, 0.1, 0.1, 0.1, 0.02);
			return false;
		}
		return true;
	}

	/** Run through: damage, charges, paralysis, and the lightning spear out of their back. */
	private static void pierce(ServerPlayer player, Lunge l, LivingEntity target) {
		ServerLevel level = player.serverLevel();
		Vec3 c = AuraFx.chest(target);
		float damage = (float) Mth.lerp(l.charge, AuraConfig.CHIDORI_MIN_DAMAGE, AuraConfig.CHIDORI_MAX_DAMAGE);
		boolean full = l.charge >= 1.0 - 1.0e-6;
		Thunderbolt.zap(player, target, damage);
		int charges = Branch.is(player, Branch.CONDUCTOR) ? AuraConfig.THUNDER_CONDUCTOR_CHIDORI_CHARGES : AuraConfig.CHIDORI_CHARGES;
		Thunderbolt.charge(target, full ? charges : Math.max(1, charges - 1));
		// Conductor: the lightning runs on from the one it pierced
		if (Branch.is(player, Branch.CONDUCTOR)) Thunderbolt.chain(player, target, damage * AuraConfig.THUNDER_CONDUCTOR_CHIDORI_ARC_FRACTION, AuraConfig.THUNDER_CONDUCTOR_CHIDORI_ARC_TARGETS);
		AuraStats.add(player, AuraStats.CHIDORI_HITS, 1);
		int para = (int) Math.round(Mth.lerp(l.charge, AuraConfig.CHIDORI_PARALYZE_MIN_TICKS, AuraConfig.CHIDORI_PARALYZE_MAX_TICKS));
		if (para > 0) {
			if (target instanceof ServerPlayer victim) Stun.stun(victim, para);
			else target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, para, 9, false, false, true));
		}
		// they're shoved on along the line, you stop dead at them
		target.setDeltaMovement(target.getDeltaMovement().add(l.dir.x * AuraConfig.CHIDORI_KNOCKBACK, 0.25, l.dir.z * AuraConfig.CHIDORI_KNOCKBACK));
		target.hurtMarked = true;
		player.setDeltaMovement(l.dir.scale(0.1));
		player.hurtMarked = true;

		Thunderbolt.bolt(level, hand(player), c, 0.9f, 2, 2);
		Thunderbolt.bolt(level, c, c.add(l.dir.scale(full ? 4.5 : 2.5)), full ? 1.3f : 0.9f, full ? 4 : 2, 3);
		Thunderbolt.impact(level, c, full ? 1.4f : 1.0f);
		Thunderbolt.send(level, AuraFx.dust(Thunderbolt.CORE, 2.0f), c.x, c.y, c.z, 12, 0.25, 0.4, 0.25, 0);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.PLAYER_ATTACK_CRIT.value(), SoundSource.PLAYERS, 1.2f, 0.8f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 1.2f, full ? 1.0f : 1.4f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.LIGHTNING_BOLT_IMPACT.value(), SoundSource.PLAYERS, 1.5f, 1.0f);
	}

	// ---------------------------------------------------------------
	// Cleanup
	// ---------------------------------------------------------------

	public static void onLogout(ServerPlayer player) {
		drop(player);
	}

	public static void clearAll() {
		CHARGING.clear();
		LUNGING.clear();
	}
}
