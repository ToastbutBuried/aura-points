package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Air Bending, Wind's take on Fire Bending, on the Super Wind Charge key. Hold the key
 * (see WindLaunch): a real wind charge ({@link AirBendingCharge}) forms in front of you and
 * follows your look, wind specks circling it. Swinging it around charges it (degrees turned,
 * like Fire Bending) and the circling grows, up to AIR_BENDING_FULL_SIZE. Once charged to
 * AIR_BENDING_READY_DEGREES, the next press fires it; where it hits, a huge burst hurls
 * everything around far away and high up - the stronger, the more it was charged.
 *
 * The charge HUD reuses Fire Bending's bar (drawn in wind colors for Wind). Everything lives
 * in memory only; the charge entity is never saved.
 */
public final class AirBending {

	private AirBending() {
	}

	private static final int GALE = 0xE8FBFF;

	private static final class Ball {
		final long startTick;
		Vec3 lastLook;
		double degrees;
		boolean readyAnnounced;
		boolean fullAnnounced;
		byte pose;
		AirBendingCharge charge;
		/** 0 = in front, 1 = fully up above the raised hand (fully charged) */
		double raise;
		/** the hand position last sent with AIR_RAISED, so a config reload re-aims the arm at once */
		long sentHand;

		Ball(long startTick, Vec3 look) {
			this.startTick = startTick;
			this.lastLook = look;
		}
	}

	private static final Map<UUID, Ball> BALLS = new HashMap<>();

	public static boolean isHolding(ServerPlayer player) {
		return BALLS.containsKey(player.getUUID());
	}

	private static double charge(Ball b) {
		return Math.min(1.0, b.degrees / Math.max(1.0, AuraConfig.AIR_BENDING_FULL_DEGREES));
	}

	private static boolean ready(Ball b) {
		return b.degrees >= AuraConfig.AIR_BENDING_READY_DEGREES;
	}

	private static boolean full(Ball b) {
		return b.degrees >= AuraConfig.AIR_BENDING_FULL_DEGREES - 1.0e-6;
	}

	/** READY_FACTOR when just ready, 1.0 at full charge. */
	private static double strength(Ball b) {
		double span = AuraConfig.AIR_BENDING_FULL_DEGREES - AuraConfig.AIR_BENDING_READY_DEGREES;
		double t = span <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, (b.degrees - AuraConfig.AIR_BENDING_READY_DEGREES) / span));
		return AuraConfig.AIR_BENDING_READY_FACTOR + (1.0 - AuraConfig.AIR_BENDING_READY_FACTOR) * t;
	}

	/** Radius of the circling specks: START_SIZE with no charge, FULL_SIZE at full charge. */
	private static double size(Ball b) {
		return AuraConfig.AIR_BENDING_START_SIZE + (AuraConfig.AIR_BENDING_FULL_SIZE - AuraConfig.AIR_BENDING_START_SIZE) * charge(b);
	}

	/** In front of the eyes; a big ball is held farther out so it doesn't swallow the view. */
	private static Vec3 ballPos(ServerPlayer player, double size) {
		double dist = Math.max(AuraConfig.AIR_BENDING_HOLD_DISTANCE, size + 1.2);
		return player.getEyePosition().add(player.getLookAngle().scale(dist));
	}

	/** Where the ball is: moved {@code b.raise} of the way from the front onto the raised right hand. */
	private static Vec3 ballPos(ServerPlayer player, Ball b) {
		double size = size(b);
		Vec3 front = ballPos(player, size);
		if (b.raise <= 0) return front;
		return front.lerp(onHand(player, size), b.raise);
	}

	/**
	 * Sitting on the raised hand: the fingertips' middle (arm-local x -1, y 10 px) placed with the same
	 * arm angles every client draws the arm with, lifted a little so the charge rests on top of it.
	 * The wind charge's position is the bottom of its box, so that is where it goes.
	 */
	private static Vec3 onHand(ServerPlayer player, double size) {
		float x = BendingAnimPacket.airArmX(AuraConfig.AIR_BENDING_RAISED_FORWARD_DEGREES);
		float z = (float) Math.toRadians(AuraConfig.AIR_BENDING_RAISED_OUT_DEGREES);
		float drop = (float) Math.max(0, Math.min(20, AuraConfig.AIR_BENDING_RAISED_DROP_PIXELS));
		// the same half-pixel steps the clients get
		float forward = Math.round(Math.max(0, Math.min(10, AuraConfig.AIR_BENDING_RAISED_FORWARD_PIXELS)) * 2) / 2f;
		float right = Math.round(Math.max(0, Math.min(10, AuraConfig.AIR_BENDING_RAISED_RIGHT_PIXELS)) * 2) / 2f;
		Vec3 tip = ArmMath.rightArmPoint(player, x, 0f, z, drop, forward, right, -1f, 10f, 0f);
		return tip.add(0, 0.05 + size * 0.3, 0);
	}

	/** The held charge, (re)spawned if it is gone or in another dimension. */
	private static AirBendingCharge charge(ServerPlayer player, Ball b) {
		Vec3 p = ballPos(player, b);
		if (b.charge == null || b.charge.isRemoved() || b.charge.level() != player.level()) {
			if (b.charge != null) b.charge.discard();
			b.charge = new AirBendingCharge(player, p);
			player.serverLevel().addFreshEntity(b.charge);
		} else {
			b.charge.hold(p);
		}
		return b.charge;
	}

	/**
	 * Wind specks circling {@code c} on three tilted rings (like an atom), {@code size} blocks out.
	 * Kept light: two specks per ring (three once it's big), small, plus a gust now and then;
	 * {@code flying} adds a faint cloud trail. {@code holder}, if set, only gets {@code holderShare}
	 * (0..1) of the specks - everyone else sees them all.
	 */
	static void orbit(ServerLevel level, Vec3 c, double size, long now, boolean flying) {
		orbit(level, c, size, now, flying, null, 1.0);
	}

	static void orbit(ServerLevel level, Vec3 c, double size, long now, boolean flying, ServerPlayer holder, double holderShare) {
		Specks out = new Specks(level, holder, holderShare);
		int per = size >= 0.5 ? 3 : 2;
		double spin = now * 0.45;
		for (int ring = 0; ring < 3; ring++) {
			double tilt = (ring - 1) * Math.PI / 3, ct = Math.cos(tilt), st = Math.sin(tilt);
			double turn = ring * Math.PI * 2 / 3, cs = Math.cos(turn), ss = Math.sin(turn);
			for (int i = 0; i < per; i++) {
				double a = spin * (ring == 1 ? -1 : 1) + i * Math.PI * 2 / per;
				double x = Math.cos(a) * size, z = Math.sin(a) * size;
				double y = -z * st;
				z = z * ct;
				double rx = x * cs - z * ss, rz = x * ss + z * cs;
				out.send(AuraFx.dust(i % 2 == 0 ? GALE : AuraType.WIND.rgb, (float) (0.45 + size * 0.5)),
						c.x + rx, c.y + y, c.z + rz, 0);
			}
		}
		if (now % 8 == 0) out.send(ParticleTypes.SMALL_GUST, c.x, c.y, c.z, 0.05);
		if (flying && now % 2 == 0) out.send(ParticleTypes.CLOUD, c.x, c.y, c.z, 0.05);
	}

	/**
	 * Sends one speck at a time to every player near enough to see it; the holder gets only every
	 * so-many (a steady share of {@code holderShare}), so their own view stays clear.
	 */
	private static final class Specks {
		final ServerLevel level;
		final ServerPlayer holder;
		final double share;
		int sent;

		Specks(ServerLevel level, ServerPlayer holder, double share) {
			this.level = level;
			this.holder = holder;
			this.share = Math.max(0.0, Math.min(1.0, share));
		}

		void send(net.minecraft.core.particles.ParticleOptions particle, double x, double y, double z, double spread) {
			if (holder == null) {
				AuraFx.particles(level, particle, x, y, z, 1, spread, spread, spread, 0.0);
				return;
			}
			// the holder's share: speck n is theirs when n * share crosses a whole number
			boolean holderGets = Math.floor((sent + 1) * share) > Math.floor(sent * share);
			sent++;
			for (ServerPlayer p : level.players()) {
				if (p == holder && !holderGets) continue;
				AuraFx.particles(level, p, particle, false, x, y, z, 1, spread, spread, spread, 0.0);
			}
		}
	}

	/** The hold completed: the ball forms (if Super Wind Charge is off cooldown). */
	public static void start(ServerPlayer player, long now) {
		if (BALLS.containsKey(player.getUUID()) || FireBending.isHolding(player)) return;
		// the running cooldown keeps its own length (a throw's, or a shot's own longer one)
		if (!AuraAbilities.checkCooldown(player, Ability.SUPER_WIND_CHARGE, AuraConfig.SUPER_WIND_CHARGE_COOLDOWN_TICKS, now)) return;
		Ball b = new Ball(now, player.getLookAngle());
		BALLS.put(player.getUUID(), b);
		pose(player, b, BendingAnimPacket.CHARGING);
		ServerLevel level = player.serverLevel();
		Vec3 p = charge(player, b).position();
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.GUST, 1, 0.0, 0.0);
		AuraFx.burst(level, p.x, p.y, p.z, AuraFx.dust(GALE, 1.6f), 14, 0.3, 0.0);
		AuraFx.sound(level, player, AuraSounds.BREEZE_INHALE.value(), 1.0f, 1.0f);
		AuraFx.sound(level, player, AuraSounds.WIND_CHARGE_THROW.value(), 0.6f, 0.5f);
		player.displayClientMessage(Component.literal("✦ Swing the wind around to charge it! ✦").withStyle(ChatFormatting.AQUA), true);
		sync(player, b);
	}

	/** A key press while holding the ball: fires it once charged. */
	public static void press(ServerPlayer player, long now) {
		Ball b = BALLS.get(player.getUUID());
		if (b == null) return;
		if (!ready(b)) {
			int pct = (int) (100 * b.degrees / Math.max(1.0, AuraConfig.AIR_BENDING_READY_DEGREES));
			player.displayClientMessage(Component.literal("Not charged yet (" + pct + "%) - keep moving it!").withStyle(ChatFormatting.GRAY), true);
			return;
		}
		BALLS.remove(player.getUUID());
		ServerLevel level = player.serverLevel();
		AirBendingCharge charge = charge(player, b);
		charge.size = size(b);
		// from wherever it is held (above the hand too) toward what the crosshair points at
		Vec3 eye = player.getEyePosition();
		Vec3 far = eye.add(player.getLookAngle().scale(AuraConfig.AIR_BENDING_RANGE));
		HitResult aim = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 target = aim.getType() == HitResult.Type.MISS ? far : aim.getLocation();
		Vec3 dir = target.subtract(charge.position());
		if (dir.lengthSqr() < 1.0e-4) dir = player.getLookAngle();
		charge.fire(dir.normalize(), Math.max(0.1, AuraConfig.AIR_BENDING_SPEED), strength(b));
		Vec3 start = charge.position();

		AuraFx.burst(level, start.x, start.y, start.z, ParticleTypes.GUST_EMITTER_SMALL, 1, 0.0, 0.0);
		AuraFx.burst(level, start.x, start.y, start.z, AuraFx.dust(GALE, 2.0f), 25, 0.3, 0.0);
		AuraFx.sound(level, player, AuraSounds.BREEZE_SHOOT.value(), 1.2f, 0.5f);
		AuraFx.sound(level, player, AuraSounds.WIND_CHARGE_THROW.value(), 1.0f, 0.4f);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.THROW);
		AuraStats.add(player, AuraStats.SUPER_WIND_CHARGE_USES, 1);
		AuraStunts.superWindCharge(player);
		AuraAbilities.startCooldown(player, Ability.SUPER_WIND_CHARGE, AuraConfig.AIR_BENDING_COOLDOWN_TICKS, now);
		NetworkHandler.sendFireBending(player, false, 0, 0);
	}

	/** Not fired in time: the ball fades, the cooldown still starts. */
	private static void fade(ServerPlayer player, long now) {
		Ball b = BALLS.remove(player.getUUID());
		if (b == null) return;
		Vec3 p = ballPos(player, b);
		if (b.charge != null) b.charge.discard();
		AuraFx.burst(player.serverLevel(), p.x, p.y, p.z, ParticleTypes.CLOUD, 12, 0.3, 0.03);
		AuraFx.sound(player.serverLevel(), player, AuraSounds.BREEZE_IDLE_AIR.value(), 0.8f, 0.6f);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
		AuraAbilities.startCooldown(player, Ability.SUPER_WIND_CHARGE, AuraConfig.AIR_BENDING_COOLDOWN_TICKS, now);
		NetworkHandler.sendFireBending(player, false, 0, 0);
	}

	/** Gone without a cooldown (ability lost, death, logout). */
	static void drop(ServerPlayer player) {
		Ball b = BALLS.remove(player.getUUID());
		if (b != null) {
			if (b.charge != null) b.charge.discard();
			NetworkHandler.sendFireBending(player, false, 0, 0);
			NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
		}
	}

	private static void sync(ServerPlayer player, Ball b) {
		double full = Math.max(1.0, AuraConfig.AIR_BENDING_FULL_DEGREES);
		NetworkHandler.sendFireBending(player, true, (float) charge(b), (float) Math.min(1.0, AuraConfig.AIR_BENDING_READY_DEGREES / full));
	}

	/** Every server tick per player: the held ball follows the look, charges and draws itself. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		Ball b = BALLS.get(player.getUUID());
		if (b == null) return;
		if (!AuraAbilities.hasAbility(data, Ability.SUPER_WIND_CHARGE) || !player.isAlive() || player.isSpectator()) {
			drop(player);
			return;
		}
		if (now - b.startTick > AuraConfig.AIR_BENDING_MAX_HOLD_TICKS || now < b.startTick) {
			fade(player, now);
			return;
		}

		Vec3 look = player.getLookAngle();
		double dot = Math.max(-1.0, Math.min(1.0, look.dot(b.lastLook)));
		b.degrees = Math.min(AuraConfig.AIR_BENDING_FULL_DEGREES,
				b.degrees + Math.min(AuraConfig.AIR_BENDING_MAX_DEGREES_PER_TICK, Math.toDegrees(Math.acos(dot))));
		b.lastLook = look;

		ServerLevel level = player.serverLevel();
		boolean ready = ready(b);
		boolean full = full(b);
		// fully charged: it rises to above the raised right hand over 5 ticks
		double raiseTarget = full && AuraConfig.AIR_BENDING_FULL_RAISED ? 1.0 : 0.0;
		b.raise += Math.max(-0.2, Math.min(0.2, raiseTarget - b.raise));
		pose(player, b, !full ? BendingAnimPacket.CHARGING
				: AuraConfig.AIR_BENDING_FULL_RAISED ? BendingAnimPacket.AIR_RAISED : BendingAnimPacket.READY);
		// the middle of the wind charge itself (its position is the bottom of its box)
		Vec3 p = charge(player, b).getBoundingBox().getCenter();
		double c = charge(b);
		if (ready && !b.readyAnnounced) {
			b.readyAnnounced = true;
			player.displayClientMessage(Component.literal("✦ Charged! Press again to fire ✦").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), true);
			level.playSound(null, p.x, p.y, p.z, AuraSounds.BREEZE_SHOOT.value(), SoundSource.PLAYERS, 0.6f, 1.8f);
		}
		if (full && !b.fullAnnounced) {
			b.fullAnnounced = true;
			player.displayClientMessage(Component.literal("✦ Fully charged! ✦").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD), true);
			level.playSound(null, p.x, p.y, p.z, AuraSounds.BREEZE_CHARGE.value(), SoundSource.PLAYERS, 1.2f, 0.8f);
			level.playSound(null, p.x, p.y, p.z, AuraSounds.RESPAWN_ANCHOR_CHARGE.value(), SoundSource.PLAYERS, 0.7f, 1.6f);
			AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.GUST, 1, 0.0, 0.0);
		}

		// the wind circling the charge grows with it; once raised at full charge the holder sees less of it
		orbit(level, p, size(b), now, false, b.raise > 0 ? player : null, AuraConfig.AIR_BENDING_HOLDER_PARTICLES);
		if (now % 12 == 0) level.playSound(null, p.x, p.y, p.z, AuraSounds.BREEZE_IDLE_AIR.value(), SoundSource.PLAYERS, 0.5f, 0.8f + (float) c);

		if (now % 2 == 0) sync(player, b);
	}

	/** Not projectile, not melee: Berserker stays out. */
	private static DamageSource damage(ServerPlayer player) {
		var types = player.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		return new DamageSource(types.getHolderOrThrow(DamageTypes.PLAYER_ATTACK), null, player);
	}

	/** The burst: everything within BLAST_RADIUS is hurled away and up, hardest at the center. */
	static void blast(ServerPlayer owner, ServerLevel level, Vec3 c, double strength, Vec3 dir) {
		double r = AuraConfig.AIR_BENDING_BLAST_RADIUS;
		long now = level.getServer().getTickCount();
		if (owner != null) Combos.pass(owner, c, c, r, dir);
		for (Entity e : level.getEntities((Entity) null, new AABB(c, c).inflate(r), t -> t == owner || WindLaunch.canShove(owner, t))) {
			Vec3 center = e.getBoundingBox().getCenter();
			double dist = center.distanceTo(c);
			if (dist > r) continue;
			// full force at the center, half at the edge
			double falloff = 1.0 - 0.5 * dist / Math.max(0.1, r);
			double force = strength * falloff;
			Vec3 away = center.subtract(c).multiply(1, 0, 1);
			if (away.lengthSqr() < 1.0e-4) away = dir.multiply(1, 0, 1);
			if (away.lengthSqr() < 1.0e-4) away = new Vec3(1, 0, 0);
			if (e == owner) {
				if (AuraConfig.AIR_BENDING_SELF_FACTOR <= 0) continue;
				force *= AuraConfig.AIR_BENDING_SELF_FACTOR;
				owner.fallDistance = 0.0f;
				WindLaunch.markLaunched(owner, now);
			} else if (e instanceof LivingEntity target && AuraConfig.AIR_BENDING_DAMAGE > 0) {
				target.hurt(damage(owner), AuraAbilities.vsMob(target, (float) (AuraConfig.AIR_BENDING_DAMAGE * strength)));
			}
			Vec3 push = away.normalize().scale(AuraConfig.AIR_BENDING_KNOCKBACK * force).add(0, AuraConfig.AIR_BENDING_LIFT * force, 0);
			e.setDeltaMovement(e.getDeltaMovement().multiply(0.2, 0.0, 0.2).add(push));
			e.hurtMarked = true;
		}

		AuraFx.particles(level, ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.GUST_EMITTER_LARGE, 2, 0.5, 0.0);
		AuraFx.ring(level, c.x, c.y, c.z, r, ParticleTypes.GUST, 16);
		AuraFx.ring(level, c.x, c.y + 0.2, c.z, r * 0.7, ParticleTypes.CLOUD, 30);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(GALE, 2.4f), 50, r * 0.4, 0.0);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(AuraType.WIND.rgb, 2.0f), 30, r * 0.3, 0.0);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 2.0f, 0.5f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.6f, 0.4f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8f, 1.4f);
	}

	private static void pose(ServerPlayer player, Ball b, byte pose) {
		long hand = pose == BendingAnimPacket.AIR_RAISED ? ((long) NetworkHandler.airArmOut() << 32) | NetworkHandler.airArmHold() : 0;
		if (b.pose == pose && b.sentHand == hand) return;
		b.pose = pose;
		b.sentHand = hand;
		NetworkHandler.sendBendAnim(player, pose);
	}

	/** The ball pose this player shows (NONE without a ball), for Poses.current. */
	public static byte heldPose(ServerPlayer player) {
		Ball b = BALLS.get(player.getUUID());
		return b == null ? BendingAnimPacket.NONE : b.pose;
	}

	public static void onLogout(ServerPlayer player) {
		Ball b = BALLS.remove(player.getUUID());
		if (b != null && b.charge != null) b.charge.discard();
	}
}
