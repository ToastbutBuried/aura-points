package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.HighlightPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fire Bending, the Fire aura's third active. First press: a ball of fire
 * appears in front of you and follows where you look; it burns whatever it
 * touches. Looking around charges it (degrees turned). Once charged to
 * FIRE_BENDING_READY_DEGREES, the next press shoots it - faster and harder the
 * more it was charged, and the closer the target, the more it hurts.
 *
 * The orb and the shot are not entities, just server-side positions drawn with
 * particles, so clients need no renderer. The charge HUD is fed by
 * {@link NetworkHandler#sendFireBending}. Everything lives in memory only.
 */
public final class FireBending {

	private FireBending() {
	}

	private static final int EMBER = 0xFFB020;

	/** A held orb. */
	private static final class Orb {
		final long startTick;
		Vec3 lastLook;
		double degrees;
		boolean readyAnnounced;
		boolean fullAnnounced;
		/** Pose last sent to the clients (BendingAnimPacket state). */
		byte pose;
		/** 0 = in the middle, 1 = fully moved to the right (READY_SIDE) */
		double side;
		/** true while the highlight list sent to the client is not empty */
		boolean highlighting;
		/** target -> last tick the orb burned it */
		final Map<UUID, Long> touched = new HashMap<>();

		Orb(long startTick, Vec3 look) {
			this.startTick = startTick;
			this.lastLook = look;
		}
	}

	/**
	 * A shot in flight. It starts at the eye (so a target closer than the orb is still hit) and
	 * counts the distance it flew itself, so it always ends at FIRE_BENDING_RANGE.
	 */
	private static final class Shot {
		final ServerPlayer owner;
		final ServerLevel level;
		final Vec3 origin;
		final Vec3 dir;
		final double speed;
		final float damageFactor;
		/** Held at the side: where the flame was (the trail starts there), and how far along the aim line it meets it. 0 = from the front. */
		final Vec3 sideStart;
		final double convergeAt;
		Vec3 pos;
		double flown;
		int ticks;

		Shot(ServerPlayer owner, Vec3 origin, Vec3 dir, double speed, float damageFactor, Vec3 sideStart, double convergeAt) {
			this.sideStart = sideStart;
			this.convergeAt = convergeAt;
			this.owner = owner;
			this.level = owner.serverLevel();
			this.origin = origin;
			this.dir = dir;
			this.speed = speed;
			this.damageFactor = damageFactor;
			this.pos = origin;
		}
	}

	/** No trail is drawn this close to the shooter's eyes. */
	private static final double TRAIL_CLEARANCE = 1.0;

	private static final Map<UUID, Orb> ORBS = new HashMap<>();
	private static final List<Shot> SHOTS = new ArrayList<>();

	/** Set while a Fire Bending hit deals its damage, so stunts can tell what the killing blow was. */
	public enum Hit { ORB, SHOT }

	public static Hit HITTING = null;

	public static boolean isHolding(ServerPlayer player) {
		return ORBS.containsKey(player.getUUID());
	}

	/** 0..1 between nothing and FULL_DEGREES. */
	private static double charge(Orb orb) {
		return Math.min(1.0, orb.degrees / Math.max(1.0, AuraConfig.FIRE_BENDING_FULL_DEGREES));
	}

	private static boolean ready(Orb orb) {
		return orb.degrees >= AuraConfig.FIRE_BENDING_READY_DEGREES;
	}

	/** 0 at READY_DEGREES, 1 at FULL_DEGREES: how far past "ready" the charge went. */
	private static double overcharge(Orb orb) {
		double span = AuraConfig.FIRE_BENDING_FULL_DEGREES - AuraConfig.FIRE_BENDING_READY_DEGREES;
		if (span <= 0) return 1.0;
		return Math.max(0.0, Math.min(1.0, (orb.degrees - AuraConfig.FIRE_BENDING_READY_DEGREES) / span));
	}

	private static Vec3 orbPos(ServerPlayer player) {
		return player.getEyePosition().add(player.getLookAngle().scale(AuraConfig.FIRE_BENDING_HOLD_DISTANCE));
	}

	/** The orb's place: in front of the eyes, moved {@code orb.side} of the way to the right hand. */
	private static Vec3 orbPos(ServerPlayer player, Orb orb) {
		return orbPos(player).add(sideOffset(player, orb));
	}

	/** Above the right hand, held at the side (turned with the body, lower while sneaking). */
	private static Vec3 handPos(ServerPlayer player) {
		double yaw = Math.toRadians(player.yBodyRot);
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		double height = AuraConfig.FIRE_BENDING_SIDE_HEIGHT - (player.isCrouching() ? 0.3 : 0.0);
		return player.position().add(0, height, 0)
				.add(right.scale(AuraConfig.FIRE_BENDING_SIDE_RIGHT))
				.add(forward.scale(AuraConfig.FIRE_BENDING_SIDE_FORWARD));
	}

	/** From the spot in front of the eyes toward the hand, by how far the orb has moved aside. */
	private static Vec3 sideOffset(ServerPlayer player, Orb orb) {
		if (orb.side <= 0) return Vec3.ZERO;
		return handPos(player).subtract(orbPos(player)).scale(orb.side);
	}

	private static boolean full(Orb orb) {
		return orb.degrees >= AuraConfig.FIRE_BENDING_FULL_DEGREES - 1.0e-6;
	}

	/**
	 * Where the trail is drawn when the shot is {@code d} blocks along the aim line: from the side it
	 * runs straight from the flame to the aim point, then on along the aim line.
	 */
	private static Vec3 trailPoint(Shot s, double d) {
		Vec3 onLine = s.origin.add(s.dir.scale(d));
		if (s.convergeAt <= 0 || d >= s.convergeAt) return onLine;
		double hold = AuraConfig.FIRE_BENDING_HOLD_DISTANCE;
		double t = Math.max(0.0, (d - hold) / Math.max(0.1, s.convergeAt - hold));
		return s.sideStart.lerp(s.origin.add(s.dir.scale(s.convergeAt)), t);
	}

	/** The key press: summons the orb, or shoots it once charged. */
	public static boolean press(ServerPlayer player, long now) {
		Orb orb = ORBS.get(player.getUUID());
		if (orb == null) {
			if (!AuraAbilities.checkCooldown(player, Ability.FIRE_BENDING, AuraConfig.FIRE_BENDING_COOLDOWN_TICKS, now)) return false;
			orb = new Orb(now, player.getLookAngle());
			ORBS.put(player.getUUID(), orb);
			final Orb held = orb;
			Combos.follow(player, Combos.Kind.FIRE, () -> orbPos(player, held), Math.max(1.5, AuraConfig.FIRE_BENDING_HITBOX + 1.0), 20 * 60,
					() -> ORBS.get(player.getUUID()) == held && AuraFx.alive(player), () -> {
						if (ORBS.get(player.getUUID()) == held) fizzle(player, player.getServer().getTickCount());
					});
			pose(player, orb, BendingAnimPacket.CHARGING);
			ServerLevel level = player.serverLevel();
			Vec3 p = orbPos(player);
			AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.FLAME, 20, 0.2, 0.08);
			AuraFx.burst(level, p.x, p.y, p.z, AuraFx.dust(AuraType.FIRE.rgb, 1.6f), 10, 0.25, 0.0);
			AuraFx.sound(level, player, AuraSounds.FIRECHARGE_USE.value(), 0.8f, 1.3f);
			AuraFx.sound(level, player, AuraSounds.BLAZE_AMBIENT.value(), 0.5f, 1.6f);
			player.displayClientMessage(Component.literal("✦ Swing the flame around to charge it! ✦").withStyle(ChatFormatting.GOLD), true);
			sync(player, orb);
			return true;
		}
		if (!ready(orb)) {
			int pct = (int) (100 * orb.degrees / Math.max(1.0, AuraConfig.FIRE_BENDING_READY_DEGREES));
			player.displayClientMessage(Component.literal("Not charged yet (" + pct + "%) - keep moving it!").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		shoot(player, orb, now);
		return true;
	}

	private static void shoot(ServerPlayer player, Orb orb, long now) {
		ORBS.remove(player.getUUID());
		double t = overcharge(orb);
		double speed = AuraConfig.FIRE_BENDING_SPEED_READY + (AuraConfig.FIRE_BENDING_SPEED_FULL - AuraConfig.FIRE_BENDING_SPEED_READY) * t;
		float factor = (float) (AuraConfig.FIRE_BENDING_READY_DAMAGE_FACTOR + (1.0 - AuraConfig.FIRE_BENDING_READY_DAMAGE_FACTOR) * t);
		Vec3 start = orbPos(player, orb);
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition();
		Vec3 dir = player.getLookAngle().normalize();
		// From the side, the trail runs from the flame to where the crosshair points (a block, or full range).
		double converge = 0;
		if (sideOffset(player, orb).lengthSqr() > 1.0e-4) {
			HitResult aim = level.clip(new ClipContext(eye, eye.add(dir.scale(AuraConfig.FIRE_BENDING_RANGE)),
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			converge = Math.max(AuraConfig.FIRE_BENDING_HOLD_DISTANCE + 0.5,
					aim.getType() == HitResult.Type.MISS ? AuraConfig.FIRE_BENDING_RANGE : aim.getLocation().distanceTo(eye));
		}
		SHOTS.add(new Shot(player, eye, dir, Math.max(0.1, speed), factor, start, converge));

		AuraFx.burst(level, start.x, start.y, start.z, ParticleTypes.FLAME, 30, 0.2, 0.15);
		AuraFx.burst(level, start.x, start.y, start.z, ParticleTypes.LAVA, 4, 0.2, 0.0);
		AuraFx.sound(level, player, AuraSounds.BLAZE_SHOOT.value(), 1.0f, 0.7f);
		AuraFx.sound(level, player, AuraSounds.FIRECHARGE_USE.value(), 1.0f, 0.6f);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.THROW);
		AuraStats.add(player, AuraStats.FIRE_BENDING_SHOTS, 1);
		AuraAbilities.markUsed(player, Ability.FIRE_BENDING, now);
		NetworkHandler.sendFireBending(player, false, 0, 0);
	}

	/**
	 * Inferno Slam: if the player holds a FULLY charged flame, it is used up for the slam - the
	 * arms go up and slam down, and after INFERNO_SLAM_DELAY_TICKS the giant Heat Wave goes off.
	 * Returns false (nothing happens) without a full flame.
	 */
	public static boolean startInfernoSlam(ServerPlayer player, long now) {
		Orb orb = ORBS.get(player.getUUID());
		if (orb == null || !full(orb)) return false;
		ORBS.remove(player.getUUID());
		NetworkHandler.sendFireBending(player, false, 0, 0);
		int delay = Math.max(0, AuraConfig.INFERNO_SLAM_WINDUP_TICKS);
		int stun = Math.max(0, AuraConfig.INFERNO_SLAM_STUN_TICKS);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.SLAM, delay, stun);
		Stun.windup(player, true, AuraConfig.INFERNO_SLAM_WINDUP_SLOWNESS);
		AuraAbilities.markUsed(player, Ability.FIRE_BENDING, now);
		AuraStats.add(player, AuraStats.FIRE_BENDING_SHOTS, 1);

		// the wind-up: the flame rises over the head and swells while the arms come up
		ServerLevel level = player.serverLevel();
		AuraFx.sound(level, player, AuraSounds.BLAZE_AMBIENT.value(), 1.0f, 0.5f);
		AuraFx.sound(level, player, AuraSounds.FIRECHARGE_USE.value(), 1.0f, 0.5f);
		for (int t = 0; t < delay; t++) {
			final float grow = (t + 1f) / Math.max(1, delay);
			AuraFx.later(player.getServer(), t, () -> {
				if (!AuraFx.alive(player)) return;
				ServerLevel l = player.serverLevel();
				Vec3 top = player.position().add(0, player.getBbHeight() + 0.6, 0);
				double size = 0.2 + 0.4 * grow;
				AuraFx.particles(l, ParticleTypes.FLAME, top.x, top.y, top.z, (int) (8 + 20 * grow), size, size, size, 0.02);
				AuraFx.particles(l, AuraFx.dust(EMBER, 2.0f), top.x, top.y, top.z, 4, size, size, size, 0.0);
				AuraFx.particles(l, ParticleTypes.LAVA, top.x, top.y, top.z, 1, size, size, size, 0.0);
			});
		}
		AuraFx.later(player.getServer(), delay, () -> {
			Stun.windup(player, false, 0);
			if (!AuraFx.alive(player) || !player.isAlive()) return;
			AuraAbilities.heatWave(player, true);
			Stun.stun(player, stun);
		});
		player.displayClientMessage(Component.literal("✦ INFERNO SLAM ✦").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), true);
		return true;
	}

	/** The orb runs out without being shot. The cooldown still starts. */
	private static void fizzle(ServerPlayer player, long now) {
		Orb orb = ORBS.remove(player.getUUID());
		ServerLevel level = player.serverLevel();
		Vec3 p = orbPos(player, orb);
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.LARGE_SMOKE, 12, 0.2, 0.03);
		AuraFx.sound(level, player, AuraSounds.FIRE_EXTINGUISH.value(), 0.6f, 1.2f);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
		AuraAbilities.markUsed(player, Ability.FIRE_BENDING, now);
		NetworkHandler.sendFireBending(player, false, 0, 0);
	}

	/** Drops the orb silently, no cooldown (ability lost, death, logout). */
	static void drop(ServerPlayer player) {
		if (ORBS.remove(player.getUUID()) != null) {
			NetworkHandler.sendFireBending(player, false, 0, 0);
			NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
		}
	}

	private static void sync(ServerPlayer player, Orb orb) {
		double full = Math.max(1.0, AuraConfig.FIRE_BENDING_FULL_DEGREES);
		NetworkHandler.sendFireBending(player, true, (float) charge(orb),
				(float) Math.min(1.0, AuraConfig.FIRE_BENDING_READY_DEGREES / full));
	}

	/** Every server tick per player: the held orb follows the look, charges, draws itself and burns what it touches. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		Orb orb = ORBS.get(player.getUUID());
		if (orb == null) return;
		if (!AuraAbilities.hasAbility(data, Ability.FIRE_BENDING) || !player.isAlive() || player.isSpectator()) {
			drop(player);
			return;
		}
		if (now - orb.startTick > AuraConfig.FIRE_BENDING_MAX_HOLD_TICKS || now < orb.startTick) {
			fizzle(player, now);
			return;
		}

		// charge: how far the view turned since last tick (capped, so one flick can't fill it)
		Vec3 look = player.getLookAngle();
		double dot = Math.max(-1.0, Math.min(1.0, look.dot(orb.lastLook)));
		orb.degrees += Math.min(AuraConfig.FIRE_BENDING_MAX_DEGREES_PER_TICK, Math.toDegrees(Math.acos(dot)));
		orb.degrees = Math.min(orb.degrees, AuraConfig.FIRE_BENDING_FULL_DEGREES);
		orb.lastLook = look;

		ServerLevel level = player.serverLevel();
		boolean ready = ready(orb);
		boolean full = full(orb);
		// fully charged: it comes to your hand over 5 ticks (or back out, if the switch was turned off)
		double sideTarget = full && AuraConfig.FIRE_BENDING_FULL_SIDE ? 1.0 : 0.0;
		orb.side += Math.max(-0.2, Math.min(0.2, sideTarget - orb.side));
		pose(player, orb, !full ? BendingAnimPacket.CHARGING
				: AuraConfig.FIRE_BENDING_FULL_SIDE ? BendingAnimPacket.READY_SIDE : BendingAnimPacket.READY);
		Vec3 p = orbPos(player, orb);
		double c = charge(orb);
		if (ready && !orb.readyAnnounced) {
			orb.readyAnnounced = true;
			player.displayClientMessage(Component.literal("✦ Charged! Press again to fire ✦").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
			level.playSound(null, p.x, p.y, p.z, AuraSounds.BLAZE_SHOOT.value(), SoundSource.PLAYERS, 0.6f, 1.8f);
			AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.LAVA, 6, 0.2, 0.0);
		}
		if (full && !orb.fullAnnounced) {
			orb.fullAnnounced = true;
			player.displayClientMessage(Component.literal("✦ Fully charged! ✦").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), true);
			level.playSound(null, p.x, p.y, p.z, AuraSounds.RESPAWN_ANCHOR_CHARGE.value(), SoundSource.PLAYERS, 1.0f, 1.3f);
			level.playSound(null, p.x, p.y, p.z, AuraSounds.FIRECHARGE_USE.value(), SoundSource.PLAYERS, 0.7f, 0.8f);
			AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.FLAME, 8, 0.1, 0.04);
		}

		if (orb.side >= 0.999) {
			// fully charged at the side: the arm itself burns - drawn by each client on the real
			// arm (client/BurningArm), so nothing to send from here
		} else {
			// the orb grows and burns hotter with its charge; soul fire once it can be shot. Moving to
			// the hand, it shrinks into it.
			double size = (0.12 + 0.18 * c) * (1.0 - 0.7 * orb.side);
			AuraFx.particles(level, ParticleTypes.FLAME, p.x, p.y, p.z, 3 + (int) (5 * c), size, size, size, 0.01);
			AuraFx.particles(level, AuraFx.dust(ready ? EMBER : AuraType.FIRE.rgb, 1.0f + (float) c), p.x, p.y, p.z, 2, size, size, size, 0.0);
			if (ready) AuraFx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 1 + (int) (3 * c), size * 0.6, size * 0.6, size * 0.6, 0.01);
			if (now % 4 == 0) AuraFx.particles(level, ParticleTypes.SMOKE, p.x, p.y + 0.2, p.z, 1, 0.05, 0.05, 0.05, 0.01);
		}
		if (now % 10 == 0) level.playSound(null, p.x, p.y, p.z, AuraSounds.FIRE_AMBIENT.value(), SoundSource.PLAYERS, 0.6f, 0.8f + (float) c);

		if (burnTouching(player, orb, p, now) && AuraConfig.FIRE_BENDING_CONTACT_CONSUMES) {
			burst(player, p, now);
			return;
		}
		if (now % 2 == 0) {
			sync(player, orb);
			highlight(player, orb);
		}
	}

	/**
	 * Fire sight: once charged to HIGHLIGHT_DEGREES, sends the ids of the mobs and players in front
	 * of the player (view cone, range, not behind too much wall) - the client draws them glowing.
	 */
	private static void highlight(ServerPlayer player, Orb orb) {
		// never above full charge (the charge stops there), or it could never be reached
		if (orb.degrees < Math.min(AuraConfig.FIRE_BENDING_HIGHLIGHT_DEGREES, AuraConfig.FIRE_BENDING_FULL_DEGREES)) return;
		double range = AuraConfig.FIRE_BENDING_HIGHLIGHT_RANGE;
		double cos = Math.cos(Math.toRadians(AuraConfig.FIRE_BENDING_HIGHLIGHT_ANGLE));
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		List<Integer> ids = new ArrayList<>();
		for (Entity e : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(range),
				e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
			double dist = to.length();
			if (dist > range || dist < 1.0e-3 || to.scale(1 / dist).dot(look) < cos) continue;
			if (wallBlocks(player.serverLevel(), eye, e.getBoundingBox().getCenter()) > AuraConfig.FIRE_BENDING_HIGHLIGHT_MAX_WALL_BLOCKS) continue;
			ids.add(e.getId());
			if (ids.size() >= HighlightPacket.MAX_IDS) break; // more would not fit the packet and kick the player
		}
		if (ids.isEmpty() && !orb.highlighting) return;
		orb.highlighting = !ids.isEmpty();
		NetworkHandler.sendHighlight(player, ids);
	}

	/** Solid blocks a straight line from {@code from} to {@code to} passes through (each block counted once). */
	private static int wallBlocks(ServerLevel level, Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		int steps = (int) Math.ceil(d.length() * 4);
		int count = 0;
		net.minecraft.core.BlockPos last = null;
		for (int i = 1; i < steps; i++) {
			net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(from.add(d.scale((double) i / steps)));
			if (pos.equals(last)) continue;
			last = pos;
			if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
				if (++count > AuraConfig.FIRE_BENDING_HIGHLIGHT_MAX_WALL_BLOCKS) return count;
			}
		}
		return count;
	}

	/** Not spectators, creative players, armor stands, you, your mount or pets (the shared rule). */
	private static boolean canHit(ServerPlayer player, Entity e) {
		return Handhelds.enemy(player, e);
	}

	/** Not a projectile and not melee: Berserker stays out, fire resistance doesn't block it. */
	private static DamageSource damage(ServerPlayer player) {
		var types = player.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		return new DamageSource(types.getHolderOrThrow(DamageTypes.PLAYER_ATTACK), null, player);
	}

	/** The orb touched something and bursts on it: used up, the shorter CONTACT_COOLDOWN starts. */
	private static void burst(ServerPlayer player, Vec3 p, long now) {
		ORBS.remove(player.getUUID());
		ServerLevel level = player.serverLevel();
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.FLAME, 25, 0.3, 0.08);
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.LARGE_SMOKE, 6, 0.2, 0.02);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.FIRECHARGE_USE.value(), SoundSource.PLAYERS, 0.8f, 1.4f);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
		AuraAbilities.startCooldown(player, Ability.FIRE_BENDING, AuraConfig.FIRE_BENDING_CONTACT_COOLDOWN_TICKS, now);
		NetworkHandler.sendFireBending(player, false, 0, 0);
	}

	/** Burns what the orb touches. Returns true if it hit anything this tick. */
	private static boolean burnTouching(ServerPlayer player, Orb orb, Vec3 p, long now) {
		boolean hit = false;
		AABB box = new AABB(p, p).inflate(AuraConfig.FIRE_BENDING_HITBOX);
		ServerLevel level = player.serverLevel();
		for (Entity e : level.getEntities(player, box, e -> canHit(player, e))) {
			LivingEntity target = (LivingEntity) e;
			Long last = orb.touched.get(target.getUUID());
			if (last != null && now - last < AuraConfig.FIRE_BENDING_CONTACT_INTERVAL_TICKS && now >= last) continue;
			orb.touched.put(target.getUUID(), now);
			hit = true;
			AuraStunts.fireHit(player, target);
			target.igniteForSeconds(AuraConfig.FIRE_BENDING_BURN_SECONDS);
			HITTING = Hit.ORB;
			try {
				target.hurt(damage(player), AuraAbilities.vsMob(target, AuraConfig.FIRE_BENDING_CONTACT_DAMAGE));
			} finally {
				HITTING = null;
			}
			Vec3 tc = AuraFx.chest(target);
			AuraFx.burst(level, tc.x, tc.y, tc.z, ParticleTypes.FLAME, 8, 0.25, 0.04);
			if (AuraConfig.FIRE_BENDING_CONTACT_CONSUMES) break; // one target, then it bursts
		}
		return hit;
	}

	/** Damage for a shot that hit after flying from {@code distance} blocks away. */
	public static float shotDamage(double distance, float factor) {
		double f = Math.max(0.0, Math.min(1.0, distance / Math.max(1.0, AuraConfig.FIRE_BENDING_RANGE)));
		return (float) ((AuraConfig.FIRE_BENDING_DAMAGE_CLOSE + (AuraConfig.FIRE_BENDING_DAMAGE_FAR - AuraConfig.FIRE_BENDING_DAMAGE_CLOSE) * f) * factor);
	}

	/** Every server tick: moves each shot in flight one step (block and entity checks cover the whole step). A shot never lives longer than 10 s. */
	public static void tickShots(MinecraftServer server) {
		if (SHOTS.isEmpty()) return;
		for (Iterator<Shot> it = SHOTS.iterator(); it.hasNext(); ) {
			Shot s = it.next();
			if (!AuraFx.alive(s.owner) || s.owner.serverLevel() != s.level || s.ticks++ > 200 || stepShot(s)) it.remove();
		}
	}

	/** Moves one shot one tick. Returns true when it is done (hit something or out of range). */
	private static boolean stepShot(Shot s) {
		ServerLevel level = s.level;
		Vec3 from = s.pos;
		double step = Math.min(s.speed, AuraConfig.FIRE_BENDING_RANGE - s.flown);
		if (step <= 0.01) {
			fizzleShot(level, from);
			return true;
		}
		Vec3 to = from.add(s.dir.scale(step));

		HitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, s.owner));
		if (block.getType() != HitResult.Type.MISS) to = block.getLocation();
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(level, s.owner, from, to,
				new AABB(from, to).inflate(AuraConfig.FIRE_BENDING_HITBOX + 1.0), e -> canHit(s.owner, e),
				(float) AuraConfig.FIRE_BENDING_HITBOX);

		// the trail, minus the bit right in front of the shooter's face
		Vec3 end = entity != null ? entity.getLocation() : to;
		if (Combos.pass(s.owner, from, end, AuraConfig.FIRE_BENDING_HITBOX, null)) return true;
		// (from the side it starts at the orb, bending in toward the crosshair's aim)
		double clearance = s.convergeAt > 0 ? AuraConfig.FIRE_BENDING_HOLD_DISTANCE : TRAIL_CLEARANCE;
		double dEnd = end.distanceTo(s.origin);
		double dFrom = Math.max(s.flown, clearance);
		double trail = dEnd - dFrom;
		if (trail > 0.05) {
			Vec3 a = trailPoint(s, dFrom), b = entity != null ? end : trailPoint(s, dEnd);
			AuraFx.line(level, a, b, ParticleTypes.FLAME, (int) Math.ceil(trail * 3));
			AuraFx.line(level, a, b, AuraFx.dust(EMBER, 1.4f), (int) Math.ceil(trail * 1.5));
		}

		if (entity != null) {
			hitEntity(s, (LivingEntity) entity.getEntity());
			return true;
		}
		if (block.getType() != HitResult.Type.MISS) {
			impactFx(level, to);
			return true;
		}
		s.pos = to;
		s.flown += step;
		if (s.flown >= AuraConfig.FIRE_BENDING_RANGE - 0.01) {
			fizzleShot(level, to);
			return true;
		}
		return false;
	}

	private static void hitEntity(Shot s, LivingEntity target) {
		ServerLevel level = s.level;
		ServerPlayer owner = s.owner;
		double distance = AuraFx.chest(target).distanceTo(s.origin);
		AuraStunts.fireHit(owner, target);
		AuraStats.add(owner, AuraStats.FIRE_BENDING_HITS, 1);
		target.igniteForSeconds(AuraConfig.FIRE_BENDING_BURN_SECONDS);
		HITTING = Hit.SHOT;
		try {
			target.hurt(damage(owner), AuraAbilities.vsMob(target, shotDamage(distance, s.damageFactor)));
		} finally {
			HITTING = null;
		}
		target.knockback(AuraConfig.FIRE_BENDING_KNOCKBACK, -s.dir.x, -s.dir.z);
		impactFx(level, AuraFx.chest(target));
	}

	private static void impactFx(ServerLevel level, Vec3 p) {
		AuraFx.particles(level, ParticleTypes.EXPLOSION, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.FLAME, 30, 0.4, 0.1);
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.LAVA, 5, 0.3, 0.0);
		AuraFx.burst(level, p.x, p.y, p.z, AuraFx.dust(AuraType.FIRE.rgb, 1.8f), 15, 0.5, 0.0);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.6f, 1.5f);
	}

	private static void fizzleShot(ServerLevel level, Vec3 p) {
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.SMOKE, 10, 0.2, 0.02);
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.SMALL_FLAME, 6, 0.2, 0.02);
	}

	/** Sends the orb's pose when it changes. */
	private static void pose(ServerPlayer player, Orb orb, byte pose) {
		if (orb.pose == pose) return;
		orb.pose = pose;
		NetworkHandler.sendBendAnim(player, pose);
	}

	/** The orb pose this player shows (NONE without an orb), for Poses.current. */
	public static byte heldPose(ServerPlayer player) {
		Orb orb = ORBS.get(player.getUUID());
		return orb == null ? BendingAnimPacket.NONE : orb.pose;
	}

	public static void onLogout(ServerPlayer player) {
		ORBS.remove(player.getUUID());
	}
}
