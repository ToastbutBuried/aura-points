package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Thunderbolt of Zeus, Thunder's active - after Fortnite's mythic, three throws per use:
 *  1. press: you leap into the air and hang there (camera free, no walking) and hurl a bolt at your
 *     crosshair - a hitscan line of jagged, flickering lightning that arcs on to nearby enemies;
 *  2. press again: a quick bolt from the other hand;
 *  3. press again: Wrath of Zeus - arms to the sky, a storm cloud gathers over where you aim (it
 *     follows your crosshair, and its ring shows the blast) and pours lightning into your hand, then
 *     you slam it down: real lightning strikes there, more bolts rain around it, and everything in
 *     the blast is hurt, thrown and paralyzed.
 * Every bolt hit leaves the target Charged (crackling); Wrath hits harder per charge and paralyzes
 * longer. The combo ends after the third throw, or when you wait too long between throws; then you
 * drift down (slow falling) and the cooldown starts. In memory only; hovering is NoGravity plus
 * Rooted, and the NoGravity is cleared on logout, death, server stop and (tag) next login.
 */
public final class Thunderbolt {

	private Thunderbolt() {
	}

	/** Tag on a hovering player, so a crash mid-hover can't leave them floating forever (cleared on login). */
	private static final String HOVER_TAG = "aurapoints_thunder_hover";

	static final int CORE = 0xF4FBFF, GLOW = 0x7FC8FF, DEEP = 0x3D6BFF, GOLD = 0xFFE347, CLOUD = 0x2A2E38;

	private static final class Combo {
		final long started;
		int thrown;
		long lastThrow;
		long nextAllowed;
		/** Throw on its way (0-based stage), and when it goes off; -1 = none. */
		int pending = -1;
		long fireAt;
		boolean hovering;
		/** Wrath: where the storm is gathering (follows the crosshair during the windup). */
		Vec3 wrathAt;
		LivingEntity wrathTarget;

		Combo(long started) {
			this.started = started;
		}
	}

	private static final Map<UUID, Combo> COMBOS = new HashMap<>();
	/** Charged targets: entity -> {charges, tick they fade} */
	private static final Map<LivingEntity, long[]> CHARGED = new HashMap<>();

	public static boolean isActive(ServerPlayer player) {
		return COMBOS.containsKey(player.getUUID());
	}

	// ---------------------------------------------------------------
	// Keys
	// ---------------------------------------------------------------

	public static boolean press(ServerPlayer player, long now) {
		Combo c = COMBOS.get(player.getUUID());
		if (c == null) {
			if (Chidori.isCharging(player) || Chidori.isLunging(player)) return false;
			if (!AuraAbilities.checkCooldown(player, Ability.THUNDERBOLT, AuraConfig.THUNDERBOLT_COOLDOWN_TICKS, now)) return false;
			c = new Combo(now);
			COMBOS.put(player.getUUID(), c);
			leap(player);
			queue(player, c, 0, now + Math.max(0, AuraConfig.THUNDERBOLT_FIRST_THROW_DELAY_TICKS));
			return true;
		}
		if (c.pending >= 0 || c.thrown >= 3 || now < c.nextAllowed) return false;
		if (Chidori.isCharging(player) || Chidori.isLunging(player)) return false;
		if (c.thrown == 1) {
			queue(player, c, 1, now + 2);
		} else {
			// Wrath of Zeus: wind up, the storm gathers
			queue(player, c, 2, now + Math.max(1, AuraConfig.THUNDERBOLT_WRATH_WINDUP_TICKS));
			ServerLevel level = player.serverLevel();
			level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.BEACON_POWER_SELECT.value(), SoundSource.PLAYERS, 1.2f, 0.7f);
			level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.RESPAWN_ANCHOR_CHARGE.value(), SoundSource.PLAYERS, 1.0f, 1.4f);
			level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.LIGHTNING_BOLT_THUNDER.value(), SoundSource.PLAYERS, 0.6f, 1.6f);
		}
		return true;
	}

	private static void queue(ServerPlayer player, Combo c, int stage, long at) {
		c.pending = stage;
		c.fireAt = at;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_THUNDERBOLT, stage);
	}

	/** Up into the air with a crack and a ring of sparks; the hover starts at the top (tickAll). */
	private static void leap(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 v = player.getDeltaMovement();
		player.setDeltaMovement(v.x * 0.3, AuraConfig.THUNDERBOLT_LEAP_VELOCITY, v.z * 0.3);
		player.hurtMarked = true;
		player.fallDistance = 0;
		Vec3 f = player.position();
		for (int i = 0; i < 20; i++) {
			double a = Math.PI * 2 * i / 20;
			send(level, ParticleTypes.ELECTRIC_SPARK, f.x + Math.cos(a) * 0.9, f.y + 0.1, f.z + Math.sin(a) * 0.9, 1, 0, 0.05, 0, 0.05);
		}
		send(level, AuraFx.dust(GLOW, 1.4f), f.x, f.y + 0.2, f.z, 14, 0.5, 0.1, 0.5, 0);
		send(level, ParticleTypes.CLOUD, f.x, f.y + 0.1, f.z, 8, 0.4, 0.05, 0.4, 0.03);
		level.playSound(null, f.x, f.y, f.z, AuraSounds.BREEZE_JUMP.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
		level.playSound(null, f.x, f.y, f.z, AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.5f, 2.0f);
	}

	// ---------------------------------------------------------------
	// Ticking
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		long now = server.getTickCount();
		tickCharged(now);
		if (COMBOS.isEmpty()) return;
		for (Iterator<Map.Entry<UUID, Combo>> it = COMBOS.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			Combo c = e.getValue();
			if (player == null) {
				it.remove();
				continue;
			}
			// grabbed, frozen or knocked out of it: the storm breaks off (Entanglement keeps its own hold)
			boolean entangled = Entanglement.isEntangled(player);
			boolean lost = !AuraAbilities.hasAbility(AuraState.get(server).getOrCreate(player.getUUID()), Ability.THUNDERBOLT);
			if (lost || !player.isAlive() || player.isSpectator() || entangled || IceSpike.isFrozen(player) || Stun.isStunned(player)) {
				it.remove();
				finish(player, c, now, !entangled);
				continue;
			}
			try {
				if (!tick(player, c, now)) {
					it.remove();
					finish(player, c, now, true);
				}
			} catch (Exception ex) {
				com.toast.aurapoints.AuraPoints.LOGGER.error("Thunderbolt tick failed", ex);
				it.remove();
				finish(player, c, now, true);
			}
		}
	}

	/** False once the combo is over. */
	private static boolean tick(ServerPlayer player, Combo c, long now) {
		ServerLevel level = player.serverLevel();
		// the hover: once the leap tops out (or it's been long enough), hang where you are
		if (!c.hovering && AuraConfig.THUNDERBOLT_HOVER && now - c.started >= Math.max(1, AuraConfig.THUNDERBOLT_LEAP_TICKS)) {
			c.hovering = true;
			player.setNoGravity(true);
			player.addTag(HOVER_TAG);
			player.setDeltaMovement(Vec3.ZERO);
			player.hurtMarked = true;
			Rooted.lock(player, player.position(), false);
		}
		if (c.hovering) player.fallDistance = 0;

		// storm crackle around the body while it lasts
		if (now % 2 == 0) {
			double a = now * 0.45;
			for (int i = 0; i < 2; i++) {
				double ang = a + i * Math.PI;
				double y = player.getY() + 0.3 + (Math.sin(now * 0.3 + i) + 1) * 0.7;
				send(level, ParticleTypes.ELECTRIC_SPARK, player.getX() + Math.cos(ang) * 0.75, y, player.getZ() + Math.sin(ang) * 0.75, 1, 0, 0, 0, 0.02);
			}
			if (c.hovering) send(level, AuraFx.dust(CLOUD, 2.2f), player.getX(), player.getY() - 0.35, player.getZ(), 2, 0.45, 0.05, 0.45, 0);
		}
		if (now % 5 == 0) charges(player, c);

		if (c.pending == 2) windup(player, c, now);
		if (c.pending >= 0 && now >= c.fireAt) {
			int stage = c.pending;
			c.pending = -1;
			c.thrown++;
			c.lastThrow = now;
			c.nextAllowed = now + Math.max(0, AuraConfig.THUNDERBOLT_THROW_GAP_TICKS);
			if (stage < 2) throwBolt(player, stage);
			else wrath(player, c);
			charges(player, c);
		}
		if (c.pending >= 0) return true;
		if (c.thrown >= 3) return now - c.lastThrow < 8; // let the slam play out before dropping
		return c.thrown == 0 || now - c.lastThrow <= Math.max(1, AuraConfig.THUNDERBOLT_COMBO_TICKS);
	}

	/** The combo is over: back down gently, then the cooldown. */
	private static void finish(ServerPlayer player, Combo c, long now, boolean unroot) {
		if (c.hovering) {
			player.setNoGravity(false);
			player.removeTag(HOVER_TAG);
			if (unroot) Rooted.unlock(player);
		}
		if (player.isAlive()) {
			player.fallDistance = 0;
			if (AuraConfig.THUNDERBOLT_DESCENT_TICKS > 0 && !player.onGround()) {
				player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, AuraConfig.THUNDERBOLT_DESCENT_TICKS, 0, false, false, true));
			}
			player.displayClientMessage(Component.empty(), true);
		}
		int ticks = AuraConfig.THUNDERBOLT_COOLDOWN_TICKS;
		if (inStorm(player)) ticks = (int) Math.round(ticks * (1.0 - Math.max(0, Math.min(1, AuraConfig.STORMBORN_COOLDOWN_REDUCTION))));
		AuraAbilities.startCooldown(player, Ability.THUNDERBOLT, ticks, now);
	}

	/** "⚡ ⚡ ⚡" on the action bar: the throws left, the next one lit. */
	private static void charges(ServerPlayer player, Combo c) {
		MutableComponent line = Component.literal("Thunderbolt  ").withStyle(ChatFormatting.YELLOW);
		for (int i = 0; i < 3; i++) {
			boolean used = i < c.thrown;
			ChatFormatting color = used ? ChatFormatting.DARK_GRAY : i == 2 ? ChatFormatting.GOLD : ChatFormatting.AQUA;
			line.append(Component.literal(i == 2 ? "⚡⚡ " : "⚡ ").withStyle(color));
		}
		if (c.pending == 2) line.append(Component.literal(" WRATH").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		player.displayClientMessage(line, true);
	}

	// ---------------------------------------------------------------
	// Throws 1 and 2: hitscan bolts that arc on
	// ---------------------------------------------------------------

	record Aim(LivingEntity target, Vec3 tip) {
	}

	static Aim aim(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition();
		Vec3 dir = player.getLookAngle().normalize();
		Vec3 far = eye.add(dir.scale(AuraConfig.THUNDERBOLT_RANGE));
		HitResult block = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 end = block.getType() == HitResult.Type.MISS ? far : block.getLocation();
		double hitbox = Math.max(0, AuraConfig.THUNDERBOLT_HITBOX);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, player, eye, end,
				new AABB(eye, end).inflate(hitbox + 1.0), e -> VineHook.canHook(player, e), (float) hitbox);
		if (hit != null) return new Aim((LivingEntity) hit.getEntity(), AuraFx.chest(hit.getEntity()));
		return new Aim(null, end);
	}

	/** Where the hand is: a little in front, to the side and below the eyes. */
	static Vec3 hand(ServerPlayer player, boolean left) {
		double yaw = Math.toRadians(player.getYRot());
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)).scale(left ? -0.4 : 0.4);
		return player.getEyePosition().add(player.getLookAngle().scale(0.55)).add(right).add(0, -0.3, 0);
	}

	private static void throwBolt(ServerPlayer player, int stage) {
		ServerLevel level = player.serverLevel();
		Aim aim = aim(player);
		Vec3 from = hand(player, stage == 1);
		bolt(level, from, aim.tip(), stage == 0 ? 1.0f : 0.8f, stage == 0 ? 3 : 2, 3);
		Combos.pass(player, from, aim.tip(), 0.3, null);
		impact(level, aim.tip(), 0.6f);
		level.playSound(null, from.x, from.y, from.z, AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 1.0f, stage == 0 ? 1.5f : 1.9f);
		level.playSound(null, aim.tip().x, aim.tip().y, aim.tip().z, AuraSounds.LIGHTNING_BOLT_IMPACT.value(), SoundSource.PLAYERS, 1.2f, 1.3f);
		AuraStats.add(player, AuraStats.THUNDERBOLT_THROWS, 1);
		if (aim.target() != null) {
			float damage = stage == 0 ? AuraConfig.THUNDERBOLT_DAMAGE : AuraConfig.THUNDERBOLT_QUICK_DAMAGE;
			zap(player, aim.target(), damage);
			charge(aim.target(), 1);
			chain(player, aim.target(), damage * AuraConfig.THUNDERBOLT_CHAIN_DAMAGE_FRACTION, AuraConfig.THUNDERBOLT_CHAIN_TARGETS);
		}
	}

	/** Arcs from {@code from} on to up to {@code hops} more enemies near it, one after the other. */
	static void chain(ServerPlayer owner, LivingEntity from, float damage, int hops) {
		if (hops <= 0 || damage <= 0) return;
		ServerLevel level = owner.serverLevel();
		Set<LivingEntity> hit = new HashSet<>();
		hit.add(from);
		LivingEntity at = from;
		double r = AuraConfig.THUNDERBOLT_CHAIN_RADIUS;
		for (int i = 0; i < hops; i++) {
			LivingEntity next = null;
			double best = Double.MAX_VALUE;
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, at.getBoundingBox().inflate(r), e -> VineHook.canHook(owner, e))) {
				if (hit.contains(e)) continue;
				double d = e.distanceToSqr(at);
				if (d <= r * r && d < best) {
					best = d;
					next = e;
				}
			}
			if (next == null) return;
			bolt(level, AuraFx.chest(at), AuraFx.chest(next), 0.55f, 1, 2);
			level.playSound(null, next.getX(), next.getY(), next.getZ(), AuraSounds.LIGHTNING_BOLT_IMPACT.value(), SoundSource.PLAYERS, 0.6f, 1.8f);
			zap(owner, next, damage);
			charge(next, 1);
			hit.add(next);
			at = next;
		}
	}

	// ---------------------------------------------------------------
	// Throw 3: Wrath of Zeus
	// ---------------------------------------------------------------

	/** Where Wrath lands for this aim: on the target, or on the ground under the crosshair. */
	private static Vec3 ground(ServerPlayer player, Aim aim) {
		if (aim.target() != null) return aim.target().position();
		Vec3 tip = aim.tip().subtract(player.getLookAngle().scale(0.3));
		HitResult down = player.serverLevel().clip(new ClipContext(tip, tip.add(0, -12, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return down.getType() == HitResult.Type.MISS ? tip : down.getLocation();
	}

	/** Raised hand: above the right shoulder. */
	private static Vec3 raisedHand(ServerPlayer player) {
		double yaw = Math.toRadians(player.getYRot());
		return player.position().add(-Math.cos(yaw) * 0.35, player.getBbHeight() + 0.75, -Math.sin(yaw) * 0.35);
	}

	/** Every tick of the windup: the cloud over the aim, the blast ring under it, lightning into the hand. */
	private static void windup(ServerPlayer player, Combo c, long now) {
		ServerLevel level = player.serverLevel();
		Aim aim = aim(player);
		c.wrathTarget = aim.target();
		c.wrathAt = ground(player, aim);
		Vec3 p = c.wrathAt;
		int total = Math.max(1, AuraConfig.THUNDERBOLT_WRATH_WINDUP_TICKS);
		double grow = 1.0 - Math.max(0, c.fireAt - now) / (double) total; // 0 -> 1
		double r = AuraConfig.THUNDERBOLT_WRATH_RADIUS;
		RandomSource rnd = player.getRandom();

		// the storm cloud, swelling and darkening
		double cy = p.y + AuraConfig.THUNDERBOLT_WRATH_CLOUD_HEIGHT;
		double cr = 0.8 + grow * r * 0.8;
		for (int i = 0; i < 10; i++) {
			double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * cr;
			send(level, AuraFx.dust(i % 3 == 0 ? 0x454B5C : CLOUD, 3.5f), p.x + Math.cos(a) * d, cy + rnd.nextGaussian() * 0.25, p.z + Math.sin(a) * d, 1, 0, 0, 0, 0);
		}
		if (rnd.nextFloat() < 0.5f) {
			double a = rnd.nextDouble() * Math.PI * 2, d = rnd.nextDouble() * cr;
			send(level, ParticleTypes.FLASH, p.x + Math.cos(a) * d, cy, p.z + Math.sin(a) * d, 1, 0, 0, 0, 0);
		}
		// the blast ring on the ground (fair warning)
		if (now % 2 == 0) {
			int pts = 28;
			double spin = now * 0.12;
			for (int i = 0; i < pts; i++) {
				double a = spin + Math.PI * 2 * i / pts;
				send(level, i % 2 == 0 ? ParticleTypes.ELECTRIC_SPARK : AuraFx.dust(GOLD, 0.9f),
						p.x + Math.cos(a) * r, p.y + 0.1, p.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
			}
		}
		// the hand fills with lightning; near the end the cloud strikes it
		Vec3 hand = raisedHand(player);
		send(level, ParticleTypes.ELECTRIC_SPARK, hand.x, hand.y, hand.z, 3 + (int) (grow * 6), 0.15, 0.15, 0.15, 0.08);
		send(level, AuraFx.dust(GOLD, (float) (0.6 + grow)), hand.x, hand.y, hand.z, 2, 0.08, 0.08, 0.08, 0);
		if (now % 3 == 0) {
			level.playSound(null, hand.x, hand.y, hand.z, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), SoundSource.PLAYERS, 0.7f, (float) (0.8 + grow * 1.2));
		}
		if (c.fireAt - now == 3) {
			bolt(level, hand.add(0, 6, 0), hand, 0.9f, 2, 2);
			level.playSound(null, hand.x, hand.y, hand.z, AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 1.0f, 1.2f);
		}
	}

	private static void wrath(ServerPlayer player, Combo c) {
		ServerLevel level = player.serverLevel();
		if (c.wrathAt == null) {
			Aim aim = aim(player);
			c.wrathTarget = aim.target();
			c.wrathAt = ground(player, aim);
		}
		Vec3 p = c.wrathAt;
		double r = AuraConfig.THUNDERBOLT_WRATH_RADIUS;
		boolean storm = inStorm(player);
		MinecraftServer server = level.getServer();

		// the slam: the hand's lightning straight down at it, and the sky answers
		Vec3 hand = raisedHand(player);
		Combos.pass(player, p, p, r * 0.5, null);
		bolt(level, hand, p.add(0, 0.6, 0), 1.6f, 5, 4);
		skyBolt(level, p);
		impact(level, p.add(0, 0.5, 0), 1.6f);
		send(level, ParticleTypes.EXPLOSION, p.x, p.y + 0.5, p.z, 3, 0.8, 0.3, 0.8, 0);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.4f, 1.3f);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.LIGHTNING_BOLT_IMPACT.value(), SoundSource.PLAYERS, 2.0f, 0.7f);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.SWEET_IMPACT.value(), SoundSource.PLAYERS, 1.3f, 0.85f);
		level.playSound(null, hand.x, hand.y, hand.z, AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 1.6f, 0.8f);
		// the shockwave rolling out along the ground
		for (int f = 1; f <= 6; f++) {
			final double rr = r * f / 6.0;
			AuraFx.later(server, f, () -> {
				int pts = (int) Math.max(12, rr * 9);
				for (int i = 0; i < pts; i++) {
					double a = Math.PI * 2 * i / pts;
					double x = p.x + Math.cos(a) * rr, z = p.z + Math.sin(a) * rr;
					send(level, i % 3 == 0 ? AuraFx.dust(GOLD, 1.2f) : AuraFx.dust(GLOW, 1.0f), x, p.y + 0.15, z, 1, 0, 0, 0, 0);
					if (i % 4 == 0) send(level, ParticleTypes.ELECTRIC_SPARK, x, p.y + 0.2, z, 1, 0, 0.1, 0, 0.1);
				}
			});
		}

		// the blast: harder in the middle, and harder still on every charge they carry
		AuraStats.add(player, AuraStats.THUNDERBOLT_THROWS, 1);
		int hits = 0;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(r, r * 0.75, r), e -> VineHook.canHook(player, e))) {
			double d = Math.sqrt(e.distanceToSqr(p));
			if (d > r && e != c.wrathTarget) continue;
			double t = Math.min(1.0, d / Math.max(0.1, r));
			float damage = (float) (AuraConfig.THUNDERBOLT_WRATH_DAMAGE * (1.0 - t * (1.0 - AuraConfig.THUNDERBOLT_WRATH_EDGE_FRACTION)));
			int charges = takeCharges(e);
			damage += charges * AuraConfig.THUNDERBOLT_OVERLOAD_DAMAGE;
			zap(player, e, damage);
			hits++;
			// thrown out and up
			Vec3 out = e.position().subtract(p).multiply(1, 0, 1);
			out = out.lengthSqr() < 1.0e-4 ? Vec3.ZERO : out.normalize();
			double kb = AuraConfig.THUNDERBOLT_WRATH_KNOCKBACK * (1.0 - t * 0.5);
			e.setDeltaMovement(e.getDeltaMovement().add(out.x * kb, AuraConfig.THUNDERBOLT_WRATH_LIFT, out.z * kb));
			e.hurtMarked = true;
			// paralyzed, longer for every charge
			int para = AuraConfig.THUNDERBOLT_PARALYZE_TICKS + charges * AuraConfig.THUNDERBOLT_PARALYZE_PER_CHARGE_TICKS;
			if (para > 0) {
				if (e instanceof ServerPlayer victim) Stun.stun(victim, para);
				else e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, para, 9, false, false, true));
			}
			Vec3 ch = AuraFx.chest(e);
			send(level, ParticleTypes.ELECTRIC_SPARK, ch.x, ch.y, ch.z, 20 + charges * 15, 0.3, 0.5, 0.3, 0.25);
			if (charges > 0) {
				send(level, ParticleTypes.FLASH, ch.x, ch.y, ch.z, 1, 0, 0, 0, 0);
				level.playSound(null, ch.x, ch.y, ch.z, AuraSounds.FIREWORK_ROCKET_TWINKLE.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
			}
		}
		if (hits > 0) AuraStats.add(player, AuraStats.THUNDERBOLT_WRATH_HITS, hits);

		// more bolts rain down around it, one after another (a thunderstorm brings more)
		int extra = AuraConfig.THUNDERBOLT_WRATH_EXTRA_BOLTS + (storm ? AuraConfig.STORMBORN_EXTRA_BOLTS : 0);
		RandomSource rnd = player.getRandom();
		for (int i = 0; i < extra; i++) {
			double a = Math.PI * 2 * i / Math.max(1, extra) + rnd.nextDouble() * 0.6;
			double d = r * (0.45 + rnd.nextDouble() * 0.45);
			Vec3 at = p.add(Math.cos(a) * d, 0, Math.sin(a) * d);
			AuraFx.later(server, 3 + i * 3 + rnd.nextInt(3), () -> {
				if (!AuraFx.alive(player)) return;
				HitResult down = level.clip(new ClipContext(at.add(0, 3, 0), at.add(0, -8, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
				Vec3 g = down.getType() == HitResult.Type.MISS ? at : down.getLocation();
				skyBolt(level, g);
				impact(level, g.add(0, 0.4, 0), 0.9f);
				for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(g, g).inflate(AuraConfig.THUNDERBOLT_WRATH_EXTRA_RADIUS),
						e -> VineHook.canHook(player, e))) {
					zap(player, e, AuraConfig.THUNDERBOLT_WRATH_EXTRA_DAMAGE);
				}
			});
		}
	}

	/** A bolt out of the sky onto {@code at}: vanilla lightning (no fire, no damage of its own) or ours if switched off. */
	private static void skyBolt(ServerLevel level, Vec3 at) {
		if (AuraConfig.THUNDERBOLT_VANILLA_LIGHTNING) {
			LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
			if (bolt != null) {
				bolt.moveTo(at.x, at.y, at.z);
				bolt.setVisualOnly(true);
				level.addFreshEntity(bolt);
				return;
			}
		}
		bolt(level, at.add(0, AuraConfig.THUNDERBOLT_WRATH_CLOUD_HEIGHT, 0), at, 1.4f, 3, 3);
	}

	// ---------------------------------------------------------------
	// Charged targets
	// ---------------------------------------------------------------

	static void charge(LivingEntity e, int amount) {
		MinecraftServer server = e.getServer();
		if (server == null || amount <= 0) return;
		long until = server.getTickCount() + AuraConfig.THUNDERBOLT_CHARGE_TICKS;
		long[] c = CHARGED.computeIfAbsent(e, k -> new long[2]);
		c[0] = Math.min(Math.max(1, AuraConfig.THUNDERBOLT_MAX_CHARGES), c[0] + amount);
		c[1] = until;
	}

	private static int takeCharges(LivingEntity e) {
		long[] c = CHARGED.remove(e);
		return c == null ? 0 : (int) c[0];
	}

	private static void tickCharged(long now) {
		if (CHARGED.isEmpty()) return;
		for (Iterator<Map.Entry<LivingEntity, long[]>> it = CHARGED.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			LivingEntity ent = e.getKey();
			if (!ent.isAlive() || ent.isRemoved() || now >= e.getValue()[1] || e.getValue()[1] - now > 20 * 60) {
				it.remove();
				continue;
			}
			// charged: it crackles, more with every charge
			if (now % 4 == 0 && ent.level() instanceof ServerLevel level) {
				Vec3 ch = AuraFx.chest(ent);
				int n = (int) e.getValue()[0];
				AuraFx.particles(level, ParticleTypes.ELECTRIC_SPARK, ch.x, ch.y, ch.z, n * 2, ent.getBbWidth() * 0.4, ent.getBbHeight() * 0.35, ent.getBbWidth() * 0.4, 0.05);
				if (now % 12 == 0) AuraFx.particles(level, AuraFx.dust(GLOW, 0.8f), ch.x, ch.y, ch.z, n, 0.25, 0.4, 0.25, 0);
			}
		}
	}

	// ---------------------------------------------------------------
	// Stormborn
	// ---------------------------------------------------------------

	/** In a thunderstorm, out under the sky. */
	static boolean inStorm(ServerPlayer player) {
		if (player.getServer() == null) return false;
		if (!AuraAbilities.hasAbility(AuraState.get(player.getServer()).getOrCreate(player.getUUID()), Ability.STORMBORN)) return false;
		return player.level().isThundering() && player.level().isRainingAt(player.blockPosition().above());
	}

	/** Thunder ability damage on one target (ignores the hurt cooldown, so every bolt of a combo lands). */
	static void zap(ServerPlayer owner, LivingEntity target, float damage) {
		if (damage <= 0 || !target.isAlive()) return;
		if (inStorm(owner)) damage *= (float) (1.0 + AuraConfig.STORMBORN_DAMAGE_BONUS);
		target.invulnerableTime = 0;
		target.hurt(owner.serverLevel().damageSources().playerAttack(owner), AuraAbilities.vsMob(target, damage));
		AuraStats.add(owner, AuraStats.THUNDERBOLT_HITS, 1);
	}

	/** Stormborn: lightning can't hurt you. */
	public static boolean stormbornBlocks(AuraPlayerData data, net.minecraft.world.damagesource.DamageSource source) {
		return AuraAbilities.hasAbility(data, Ability.STORMBORN) && source.is(net.minecraft.world.damagesource.DamageTypes.LIGHTNING_BOLT);
	}

	// ---------------------------------------------------------------
	// Drawing lightning
	// ---------------------------------------------------------------

	/** Sends to everyone within 160 blocks with the long-distance flag, so a 48-block bolt shows end to end. */
	static void send(ServerLevel level, ParticleOptions particle, double x, double y, double z, int count, double dx, double dy, double dz, double speed) {
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(x, y, z) < 160 * 160) AuraFx.particles(level, p, particle, true, x, y, z, count, dx, dy, dz, speed);
		}
	}

	/**
	 * A jagged bolt from {@code from} to {@code to}: a white-hot core in a blue glow with sparks, and
	 * {@code forks} side branches. Redrawn freshly jagged for {@code frames} ticks, so it flickers.
	 */
	static void bolt(ServerLevel level, Vec3 from, Vec3 to, float thick, int forks, int frames) {
		MinecraftServer server = level.getServer();
		for (int f = 0; f < Math.max(1, frames); f++) {
			final boolean fading = f > 0;
			if (f == 0) boltFrame(level, from, to, thick, forks, false);
			else AuraFx.later(server, f, () -> boltFrame(level, from, to, thick * (fading ? 0.8f : 1f), Math.max(0, forks - 1), true));
		}
	}

	private static void boltFrame(ServerLevel level, Vec3 from, Vec3 to, float thick, int forks, boolean fading) {
		Vec3 d = to.subtract(from);
		double len = d.length();
		if (len < 0.1) return;
		RandomSource rnd = level.getRandom();
		List<Vec3> pts = jagged(from, to, Math.min(0.6, 0.15 + len * 0.05) * thick, rnd);
		Vec3 mid = from.lerp(to, 0.5);
		List<ServerPlayer> viewers = new ArrayList<>();
		double reach = 160 + len / 2;
		for (ServerPlayer p : level.players()) if (p.distanceToSqr(mid) < reach * reach) viewers.add(p);
		if (viewers.isEmpty()) return;

		var core = AuraFx.dust(CORE, Math.min(4f, 0.9f * thick));
		var glow = AuraFx.dust(fading ? DEEP : GLOW, Math.min(4f, 1.6f * thick));
		double step = 0.22;
		int n = 0;
		for (int i = 0; i < pts.size() - 1; i++) {
			Vec3 a = pts.get(i), b = pts.get(i + 1);
			int steps = Math.max(1, (int) (a.distanceTo(b) / step));
			for (int s = 0; s < steps; s++, n++) {
				Vec3 q = a.lerp(b, (double) s / steps);
				if (!fading || n % 2 == 0) emit(level, viewers, core, q, 0, 0);
				if (n % 3 == 0) emit(level, viewers, glow, q, 0.04, 0);
				if (n % 6 == 0) emit(level, viewers, ParticleTypes.ELECTRIC_SPARK, q, 0.05, 0.06);
				if (!fading && n % 14 == 0) emit(level, viewers, ParticleTypes.END_ROD, q, 0.05, 0.01);
			}
		}
		// forks off the main line
		for (int k = 0; k < forks && pts.size() > 2; k++) {
			Vec3 start = pts.get(1 + rnd.nextInt(pts.size() - 2));
			Vec3 dir = d.normalize().add(rnd.nextGaussian() * 0.8, rnd.nextGaussian() * 0.8, rnd.nextGaussian() * 0.8).normalize();
			Vec3 end = start.add(dir.scale((0.8 + rnd.nextDouble() * 1.8) * thick));
			List<Vec3> fp = jagged(start, end, 0.18 * thick, rnd);
			var thin = AuraFx.dust(CORE, Math.max(0.3f, 0.55f * thick));
			for (int i = 0; i < fp.size() - 1; i++) {
				Vec3 a = fp.get(i), b = fp.get(i + 1);
				int steps = Math.max(1, (int) (a.distanceTo(b) / 0.25));
				for (int s = 0; s < steps; s++) emit(level, viewers, thin, a.lerp(b, (double) s / steps), 0, 0);
			}
			emit(level, viewers, ParticleTypes.ELECTRIC_SPARK, end, 0.05, 0.05);
		}
	}

	private static void emit(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions particle, Vec3 q, double spread, double speed) {
		for (ServerPlayer p : viewers) AuraFx.particles(level, p, particle, true, q.x, q.y, q.z, 1, spread, spread, spread, speed);
	}

	/** Points from a to b, kinked sideways at random every ~1.4 blocks (the ends stay put). */
	private static List<Vec3> jagged(Vec3 a, Vec3 b, double amp, RandomSource rnd) {
		Vec3 d = b.subtract(a);
		double len = d.length();
		Vec3 dir = d.scale(1.0 / len);
		Vec3 u = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : dir.cross(new Vec3(0, 1, 0)).normalize();
		Vec3 v = dir.cross(u).normalize();
		int segs = Math.max(2, (int) (len / 1.4));
		List<Vec3> pts = new ArrayList<>(segs + 1);
		pts.add(a);
		for (int i = 1; i < segs; i++) {
			double taper = Math.sin(Math.PI * i / segs); // calmer near the ends
			Vec3 base = a.add(d.scale((double) i / segs));
			pts.add(base.add(u.scale(rnd.nextGaussian() * amp * taper)).add(v.scale(rnd.nextGaussian() * amp * taper)));
		}
		pts.add(b);
		return pts;
	}

	/** Where a bolt lands: a flash, a spray of sparks. */
	static void impact(ServerLevel level, Vec3 at, float size) {
		send(level, ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		send(level, ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, (int) (16 * size), 0.2 * size, 0.2 * size, 0.2 * size, 0.3 * size);
		send(level, AuraFx.dust(GLOW, 1.3f * size), at.x, at.y, at.z, (int) (6 * size), 0.3 * size, 0.3 * size, 0.3 * size, 0);
	}

	// ---------------------------------------------------------------
	// Static Charge (passive)
	// ---------------------------------------------------------------

	private static final Map<UUID, Integer> STATIC = new HashMap<>();

	/** A melee attack is about to land: charged hits build static; every STATIC_CHARGE_HITS-th one arcs. */
	public static void onAttack(ServerPlayer player, Entity target) {
		MinecraftServer server = player.getServer();
		if (server == null || !(target instanceof LivingEntity living) || !living.isAlive()) return;
		if (!AuraAbilities.hasAbility(AuraState.get(server).getOrCreate(player.getUUID()), Ability.STATIC_CHARGE)) return;
		if (player.getAttackStrengthScale(0.5f) < 0.9f) return;
		int need = Math.max(1, AuraConfig.STATIC_CHARGE_HITS);
		int n = STATIC.merge(player.getUUID(), 1, Integer::sum);
		ServerLevel level = player.serverLevel();
		Vec3 ch = AuraFx.chest(living);
		if (n < need) {
			AuraFx.particles(level, ParticleTypes.ELECTRIC_SPARK, ch.x, ch.y, ch.z, 3 + n * 2, 0.2, 0.3, 0.2, 0.1);
			return;
		}
		STATIC.remove(player.getUUID());
		// a tick later, after the hit itself: the static discharges into it and arcs on
		AuraFx.later(server, 1, () -> {
			if (!AuraFx.alive(player) || !living.isAlive()) return;
			bolt(level, hand(player, false), AuraFx.chest(living), 0.6f, 1, 2);
			impact(level, AuraFx.chest(living), 0.6f);
			level.playSound(null, living.getX(), living.getY(), living.getZ(), AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.6f, 2.0f);
			zap(player, living, AuraConfig.STATIC_CHARGE_DAMAGE);
			charge(living, 1);
			chain(player, living, AuraConfig.STATIC_CHARGE_DAMAGE, AuraConfig.STATIC_CHARGE_ARC_TARGETS);
		});
	}

	// ---------------------------------------------------------------
	// Cleanup
	// ---------------------------------------------------------------

	public static void onLogout(ServerPlayer player) {
		Combo c = COMBOS.remove(player.getUUID());
		STATIC.remove(player.getUUID());
		if (c != null && c.hovering) Rooted.unlock(player);
		player.setNoGravity(false);
		player.removeTag(HOVER_TAG);
	}

	/** A crash mid-hover left the tag (and NoGravity) on the player: take both off. */
	public static void onLogin(ServerPlayer player) {
		if (player.getTags().contains(HOVER_TAG)) {
			player.removeTag(HOVER_TAG);
			player.setNoGravity(false);
		}
	}

	public static void clearAll(MinecraftServer server) {
		for (UUID id : new ArrayList<>(COMBOS.keySet())) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) onLogout(p);
		}
		COMBOS.clear();
		CHARGED.clear();
		STATIC.clear();
	}
}
