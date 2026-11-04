package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraCommand;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import com.mojang.math.Transformation;
import com.toast.aurapoints.mixin.DisplayAccessor;
import com.toast.aurapoints.mixin.ItemDisplayAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Mjölnir (test phase, owner only - see item/MjolnirItem). Hold the Mjölnir Swing key (own rebindable key) with it in your main hand: you
 * whirl it at your side (MJOLNIR_SPIN pose), faster and faster, wind whooshing, sparks on its path; after
 * SPIN_TICKS lightning strikes the hammer and it is Charged for CHARGED_TICKS. While charged, the
 * Frost-style streak counts: STREAK_HITS full-strength hits in a row (each within STREAK_WINDOW_TICKS of the
 * last), and the last one calls a thunder strike down on the target - STRIKE_DAMAGE to it, SPLASH_DAMAGE
 * around it, everyone thrown and paralyzed. That uses up the charge. Right-click throws it (see below).
 * In memory only.
 */
public final class Mjolnir {

	private Mjolnir() {
	}

	/** spinning player -> tick the spin started */
	private static final Map<UUID, Long> SPINNING = new HashMap<>();
	/** charged player -> tick the charge fades */
	private static final Map<UUID, Long> CHARGED = new HashMap<>();
	/** streak: player -> {hits, tick of the last hit} */
	private static final Map<UUID, long[]> STREAK = new HashMap<>();

	public static boolean holding(ServerPlayer player) {
		return player.getMainHandItem().is(AuraPoints.MJOLNIR_ITEM.get());
	}

	/** The Mjölnir Swing key works while its worthy wielder holds the hammer (a release counts while still spinning it). */
	public static boolean handlesKey(ServerPlayer player, boolean down) {
		if (!down) return SPINNING.containsKey(player.getUUID());
		return holding(player) && AuraCommand.isOwner(player);
	}

	public static boolean isSpinning(ServerPlayer player) {
		return SPINNING.containsKey(player.getUUID());
	}

	public static void press(ServerPlayer player, long now) {
		if (SPINNING.containsKey(player.getUUID()) || IceSpike.isFrozen(player) || Stun.isBusy(player)) return;
		SPINNING.put(player.getUUID(), now);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.MJOLNIR_SPIN);
		NetworkHandler.sendFireBending(player, true, isCharged(player, now) ? 1f : 0f, 1f);
		player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.ARMOR_EQUIP_NETHERITE.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
	}

	public static void release(ServerPlayer player) {
		if (SPINNING.remove(player.getUUID()) == null) return;
		NetworkHandler.sendFireBending(player, false, 0, 0);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
	}

	static boolean isCharged(ServerPlayer player, long now) {
		Long until = CHARGED.get(player.getUUID());
		return until != null && now < until;
	}

	/** Where the hammer head is on its circle at the side: around a point before the right shoulder. */
	private static Vec3 headAt(ServerPlayer player, double angle) {
		double yaw = Math.toRadians(player.yBodyRot);
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 center = player.position().add(0, 1.15, 0).add(right.scale(0.55)).add(forward.scale(0.35));
		// a circle in the plane of forward and up, like a windmill at your side
		return center.add(forward.scale(Math.cos(angle) * 0.85)).add(0, Math.sin(angle) * 0.85, 0);
	}

	public static void tickAll(MinecraftServer server) {
		long now = server.getTickCount();
		tickFlights(server);
		tickRides(server);
		if (!SPINNING.isEmpty()) {
			for (Iterator<Map.Entry<UUID, Long>> it = SPINNING.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
				if (p == null) {
					it.remove();
					continue;
				}
				if (!p.isAlive() || !holding(p) || IceSpike.isFrozen(p) || Stun.isStunned(p)) {
					it.remove();
					NetworkHandler.sendFireBending(p, false, 0, 0);
					NetworkHandler.sendBendAnim(p, BendingAnimPacket.NONE);
					continue;
				}
				spin(p, e.getValue(), now);
			}
		}
		// charged: the hammer crackles and the streak shows on the action bar
		for (Iterator<Map.Entry<UUID, Long>> it = CHARGED.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || now >= e.getValue() || e.getValue() - now > 20 * 600 || !p.isAlive()) {
				it.remove();
				STREAK.remove(e.getKey());
				if (p != null && p.isAlive()) {
					p.displayClientMessage(Component.literal("Mjölnir's charge fades.").withStyle(ChatFormatting.GRAY), true);
				}
				continue;
			}
			if (!holding(p)) continue;
			if (now % 3 == 0 && !isSpinning(p)) {
				Vec3 h = Thunderbolt.hand(p, false);
				AuraFx.particles(p.serverLevel(), ParticleTypes.ELECTRIC_SPARK, h.x, h.y + 0.25, h.z, 3, 0.15, 0.2, 0.15, 0.06);
				if (p.getRandom().nextFloat() < 0.25f) {
					Vec3 d = new Vec3(p.getRandom().nextGaussian(), p.getRandom().nextGaussian(), p.getRandom().nextGaussian()).normalize();
					Thunderbolt.bolt(p.serverLevel(), h.add(0, 0.25, 0), h.add(0, 0.25, 0).add(d.scale(0.7)), 0.35f, 0, 1);
				}
			}
			if (now % 10 == 0) streakBar(p, now);
		}
	}

	private static void spin(ServerPlayer player, long since, long now) {
		ServerLevel level = player.serverLevel();
		int full = Math.max(1, AuraConfig.MJOLNIR_SPIN_TICKS);
		double t = (now - since) / 20.0;
		double charge = Mth.clamp((now - since) / (double) full, 0, 1);
		boolean charged = isCharged(player, now);
		if (now % 2 == 0) NetworkHandler.sendFireBending(player, true, (float) (charged ? 1.0 : charge), 1f);

		// the same angle the client's arm uses (BendingAnimation): speeding up, then steady
		double angle = spinAngle(t);
		double speed = spinSpeed(t);
		// sparks and wind along the head's path, a short trail behind it
		for (int k = 0; k < 3; k++) {
			Vec3 at = headAt(player, angle - k * 0.35);
			if (k == 0) Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1 + (int) (charge * 3), 0.05, 0.05, 0.05, 0.05 + charge * 0.1);
			Thunderbolt.send(level, AuraFx.dust(k == 0 ? Thunderbolt.CORE : Thunderbolt.GLOW, (float) (0.6 + charge * 0.6)), at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
		if (charge > 0.5 && now % 3 == 0) {
			Vec3 at = headAt(player, angle);
			Thunderbolt.send(level, ParticleTypes.CLOUD, at.x, at.y, at.z, 1, 0.1, 0.1, 0.1, 0.02);
		}
		// a whoosh every half turn, higher as it speeds up
		double perHalfTurn = Math.PI / Math.max(0.05, speed);
		if ((now - since) % Math.max(2, Math.round(perHalfTurn)) == 0) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.PLAYER_ATTACK_SWEEP.value(), SoundSource.PLAYERS,
					0.4f + (float) charge * 0.4f, 0.6f + (float) charge * 0.8f);
		}

		// fully wound up: the sky answers and strikes the hammer
		if (now - since == full) {
			Vec3 at = headAt(player, angle);
			if (AuraConfig.MJOLNIR_SKY_LIGHTNING) {
				LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
				if (bolt != null) {
					bolt.moveTo(player.getX(), player.getY(), player.getZ());
					bolt.setVisualOnly(true);
					level.addFreshEntity(bolt);
				}
			}
			Thunderbolt.bolt(level, at.add(0, 12, 0), at, 1.4f, 3, 3);
			Thunderbolt.impact(level, at, 1.2f);
			level.playSound(null, at.x, at.y, at.z, AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 1.6f, 1.0f);
			level.playSound(null, at.x, at.y, at.z, AuraSounds.BEACON_POWER_SELECT.value(), SoundSource.PLAYERS, 1.0f, 1.4f);
			CHARGED.put(player.getUUID(), now + AuraConfig.MJOLNIR_CHARGED_TICKS);
			STREAK.remove(player.getUUID());
			player.displayClientMessage(Component.literal("⚡ Mjölnir is charged! ⚡").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), true);
		}
	}

	/** Spin speed in radians per tick after {@code t} seconds: from SPIN_START up to SPIN_MAX over SPIN_TICKS. */
	public static double spinSpeed(double t) {
		double ramp = Math.max(0.05, AuraConfig.MJOLNIR_SPIN_TICKS / 20.0);
		return Mth.lerp(Math.min(1, t / ramp), 0.25, 1.1);
	}

	/** The integral of spinSpeed: the hammer's angle after {@code t} seconds (radians). */
	public static double spinAngle(double t) {
		double ramp = Math.max(0.05, AuraConfig.MJOLNIR_SPIN_TICKS / 20.0);
		double a = 0.25 * 20, b = 1.1 * 20; // per second
		if (t <= ramp) return a * t + (b - a) * t * t / (2 * ramp);
		return a * ramp + (b - a) * ramp / 2 + b * (t - ramp);
	}

	private static void streakBar(ServerPlayer player, long now) {
		long[] s = STREAK.get(player.getUUID());
		int hits = s == null || now - s[1] > AuraConfig.MJOLNIR_STREAK_WINDOW_TICKS ? 0 : (int) s[0];
		MutableComponent line = Component.literal("Mjölnir  ").withStyle(ChatFormatting.AQUA);
		int need = Math.max(1, AuraConfig.MJOLNIR_STREAK_HITS);
		for (int i = 0; i < need; i++) {
			line.append(Component.literal(i == need - 1 ? "⚡ " : "◆ ").withStyle(i < hits ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
		}
		player.displayClientMessage(line, true);
	}

	/** A melee attack is about to land (AttackEntityEvent). */
	public static void onAttack(ServerPlayer player, Entity target, long now) {
		if (!holding(player) || !AuraCommand.isOwner(player) || !isCharged(player, now)) return;
		if (!(target instanceof LivingEntity living) || !living.isAlive()) return;
		if (player.getAttackStrengthScale(0.5f) < 0.9f) {
			STREAK.remove(player.getUUID());
			streakBar(player, now);
			return;
		}
		long[] s = STREAK.get(player.getUUID());
		if (s == null || now - s[1] > AuraConfig.MJOLNIR_STREAK_WINDOW_TICKS || now < s[1]) s = new long[]{0, now};
		s[0]++;
		s[1] = now;
		STREAK.put(player.getUUID(), s);
		ServerLevel level = player.serverLevel();
		Vec3 c = AuraFx.chest(living);
		int need = Math.max(1, AuraConfig.MJOLNIR_STREAK_HITS);
		if (s[0] < need) {
			Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 6 + (int) s[0] * 6, 0.2, 0.3, 0.2, 0.15);
			level.playSound(null, c.x, c.y, c.z, AuraSounds.ANVIL_LAND.value(), SoundSource.PLAYERS, 0.35f, 1.6f + s[0] * 0.15f);
			streakBar(player, now);
			return;
		}
		// the last hit of the streak: thunder, a tick later (after the hit itself)
		STREAK.remove(player.getUUID());
		CHARGED.remove(player.getUUID());
		streakBar(player, now);
		AuraFx.later(level.getServer(), 1, () -> {
			if (AuraFx.alive(player)) strike(player, living);
		});
	}

	private static void strike(ServerPlayer player, LivingEntity target) {
		ServerLevel level = player.serverLevel();
		Vec3 p = target.position();
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
		if (bolt != null) {
			bolt.moveTo(p.x, p.y, p.z);
			bolt.setVisualOnly(true);
			level.addFreshEntity(bolt);
		}
		Vec3 c = AuraFx.chest(target);
		Thunderbolt.bolt(level, c.add(0, 14, 0), c, 1.8f, 5, 4);
		Thunderbolt.impact(level, c, 1.8f);
		Thunderbolt.send(level, ParticleTypes.EXPLOSION, p.x, p.y + 0.5, p.z, 2, 0.4, 0.2, 0.4, 0);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.LIGHTNING_BOLT_IMPACT.value(), SoundSource.PLAYERS, 2.0f, 0.8f);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), SoundSource.PLAYERS, 1.4f, 0.8f);
		// a ring rolling out over the ground
		MinecraftServer server = level.getServer();
		double r = AuraConfig.MJOLNIR_SPLASH_RADIUS;
		for (int f = 1; f <= 5; f++) {
			final double rr = r * f / 5.0;
			AuraFx.later(server, f, () -> {
				int pts = (int) Math.max(12, rr * 10);
				for (int i = 0; i < pts; i++) {
					double a = Math.PI * 2 * i / pts;
					Thunderbolt.send(level, i % 3 == 0 ? ParticleTypes.ELECTRIC_SPARK : AuraFx.dust(Thunderbolt.GLOW, 1.1f),
							p.x + Math.cos(a) * rr, p.y + 0.15, p.z + Math.sin(a) * rr, 1, 0, 0.05, 0, 0.05);
				}
			});
		}

		Thunderbolt.zap(player, target, AuraConfig.MJOLNIR_STRIKE_DAMAGE);
		hurl(target, p, 0.2);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(r), e -> VineHook.canHook(player, e))) {
			if (e == target || e.distanceToSqr(p) > r * r) continue;
			Thunderbolt.zap(player, e, AuraConfig.MJOLNIR_SPLASH_DAMAGE);
			Thunderbolt.bolt(level, c, AuraFx.chest(e), 0.6f, 1, 2);
			hurl(e, p, AuraConfig.MJOLNIR_KNOCKBACK);
		}
	}

	private static void hurl(LivingEntity e, Vec3 from, double kb) {
		Vec3 out = e.position().subtract(from).multiply(1, 0, 1);
		out = out.lengthSqr() < 1.0e-4 ? Vec3.ZERO : out.normalize();
		e.setDeltaMovement(e.getDeltaMovement().add(out.x * kb, 0.4, out.z * kb));
		e.hurtMarked = true;
		int para = AuraConfig.MJOLNIR_PARALYZE_TICKS;
		if (para > 0) {
			if (e instanceof ServerPlayer victim) Stun.stun(victim, para);
			else e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, para, 9, false, false, true));
		}
	}

	// ---------------------------------------------------------------
	// Throwing: right-click. It flies spinning at the crosshair, hits the first mob or player (a charged
	// hammer brings the thunder down on them) or a block, then comes back to the hand - straight through
	// walls, hitting whoever is in its way. The stack is out of the inventory meanwhile and always comes
	// back: caught, or on logout / server stop given back, or dropped where the hammer is if you died.
	// ---------------------------------------------------------------

	private static final String TAG = "aurapoints_mjolnir";
	/** Hammers in flight right now (a tagged display that isn't one of these is a leftover from a crash). */
	private static final java.util.Set<UUID> LIVE = new java.util.HashSet<>();

	private static final class Flight {
		final UUID owner;
		final ItemStack stack;
		final int slot;
		final Display.ItemDisplay display;
		final boolean charged;
		final java.util.Set<LivingEntity> hit = new java.util.HashSet<>();
		Vec3 pos, dir;
		double speed, travelled;
		boolean back;
		int age;

		Flight(UUID owner, ItemStack stack, int slot, Display.ItemDisplay display, boolean charged, Vec3 pos, Vec3 dir, double speed) {
			this.owner = owner;
			this.stack = stack;
			this.slot = slot;
			this.display = display;
			this.charged = charged;
			this.pos = pos;
			this.dir = dir;
			this.speed = speed;
		}
	}

	private static final Map<UUID, Flight> FLYING = new HashMap<>();

	public static boolean isLeftover(Entity e) {
		return e.getTags().contains(TAG) && !LIVE.contains(e.getUUID());
	}

	/** Right-click with Mjölnir. False if it can't be thrown now. */
	public static boolean throwHammer(ServerPlayer player) {
		if (!AuraCommand.isOwner(player) || !holding(player) || FLYING.containsKey(player.getUUID())
				|| IceSpike.isFrozen(player) || Stun.isBusy(player)) return false;
		long now = player.getServer().getTickCount();
		boolean spun = SPINNING.containsKey(player.getUUID());
		if (spun) release(player);
		boolean charged = isCharged(player, now);
		if (charged) {
			CHARGED.remove(player.getUUID());
			STREAK.remove(player.getUUID());
		}

		ServerLevel level = player.serverLevel();
		int slot = player.getInventory().selected;
		ItemStack stack = player.getMainHandItem().copy();
		player.getInventory().setItem(slot, ItemStack.EMPTY);

		Vec3 from = Thunderbolt.hand(player, false);
		Display.ItemDisplay d = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		d.setPos(from.x, from.y, from.z);
		((ItemDisplayAccessor) d).aurapoints$setItemStack(stack.copy());
		((ItemDisplayAccessor) d).aurapoints$setItemTransform(ItemDisplayContext.FIXED);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setPosRotInterpolationDuration(1);
		acc.aurapoints$setViewRange(4.0f);
		d.addTag(TAG);
		LIVE.add(d.getUUID());
		level.addFreshEntity(d);

		double speed = spun ? AuraConfig.MJOLNIR_THROW_SPEED * AuraConfig.MJOLNIR_SPUN_THROW_BOOST : AuraConfig.MJOLNIR_THROW_SPEED;
		Flight f = new Flight(player.getUUID(), stack, slot, d, charged, from, player.getLookAngle().normalize(), speed);
		FLYING.put(player.getUUID(), f);
		turn(f);

		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_THUNDERBOLT, 0);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.PLAYER_ATTACK_SWEEP.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
		if (charged) level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
		return true;
	}

	/**
	 * Where the head points in the item's own model: the mace texture runs from the butt (bottom left) to the
	 * head (top right). The display renderer's half turn and the item model's "fixed" half turn cancel out, so
	 * this is also the direction in the display's transformation space.
	 */
	private static final Vector3f HEAD = new Vector3f(9.5f, 10.5f, 0f).normalize();

	/** Head first along the flight - a hammer flies straight, it doesn't tumble (and on the way back it points at you). */
	private static void turn(Flight f) {
		Quaternionf q = new Quaternionf().rotationTo(HEAD, new Vector3f((float) f.dir.x, (float) f.dir.y, (float) f.dir.z));
		DisplayAccessor acc = (DisplayAccessor) f.display;
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), q, new Vector3f(1.3f), null));
		acc.aurapoints$setInterpolationDuration(1);
		f.display.getEntityData().set(DisplayAccessor.aurapoints$startDelta(), 0, true);
	}

	private static void tickFlights(MinecraftServer server) {
		if (FLYING.isEmpty()) return;
		for (Iterator<Map.Entry<UUID, Flight>> it = FLYING.entrySet().iterator(); it.hasNext(); ) {
			Flight f = it.next().getValue();
			ServerPlayer owner = server.getPlayerList().getPlayer(f.owner);
			boolean done;
			try {
				done = owner == null || !owner.isAlive() || owner.level() != f.display.level() || ++f.age > 400 ? abort(f, owner) : fly(f, owner);
			} catch (Exception e) {
				AuraPoints.LOGGER.error("Mjölnir flight failed", e);
				done = abort(f, owner);
			}
			if (done) it.remove();
		}
	}

	/** One tick in the air; true once caught. */
	private static boolean fly(Flight f, ServerPlayer owner) {
		ServerLevel level = (ServerLevel) f.display.level();
		Vec3 from = f.pos;
		if (f.back) {
			Vec3 hand = Thunderbolt.hand(owner, false);
			Vec3 to = hand.subtract(from);
			f.speed = Math.min(AuraConfig.MJOLNIR_THROW_SPEED * 1.6, f.speed + 0.12);
			if (to.length() <= f.speed + 0.6) {
				catchIt(f, owner);
				return true;
			}
			f.dir = to.normalize();
		}
		Vec3 next = from.add(f.dir.scale(f.speed));

		if (!f.back) {
			// out: the first block or body in the way stops it
			HitResult block = level.clip(new ClipContext(from, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, f.display));
			Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
			LivingEntity target = firstAlong(level, owner, from, end, f);
			if (target != null) {
				impact(f, owner, target);
				next = AuraFx.chest(target).subtract(f.dir.scale(0.4));
				f.back = true;
				f.speed = 0.3;
			} else if (block.getType() != HitResult.Type.MISS) {
				next = end.subtract(f.dir.scale(0.2));
				Thunderbolt.impact(level, end, 0.6f);
				level.playSound(null, end.x, end.y, end.z, AuraSounds.MACE_SMASH_GROUND.value(), SoundSource.PLAYERS, 1.0f, 0.9f);
				f.back = true;
				f.speed = 0.3;
			} else if ((f.travelled += f.speed) >= AuraConfig.MJOLNIR_THROW_RANGE) {
				f.back = true;
				f.speed = 0.3;
			}
		} else {
			// back: through anything, knocking into whoever is in the way
			LivingEntity e = firstAlong(level, owner, from, next, f);
			if (e != null) {
				f.hit.add(e);
				Thunderbolt.zap(owner, e, AuraConfig.MJOLNIR_RETURN_DAMAGE);
				e.setDeltaMovement(e.getDeltaMovement().add(f.dir.x * 0.6, 0.25, f.dir.z * 0.6));
				e.hurtMarked = true;
				level.playSound(null, e.getX(), e.getY(), e.getZ(), AuraSounds.MACE_SMASH_AIR.value(), SoundSource.PLAYERS, 0.9f, 1.1f);
			}
		}

		f.pos = next;
		f.display.setPos(next.x, next.y, next.z);
		turn(f);
		// the trail: crackling, and a deep hum
		Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, next.x, next.y, next.z, f.charged ? 4 : 2, 0.1, 0.1, 0.1, 0.05);
		Thunderbolt.send(level, AuraFx.dust(f.charged ? Thunderbolt.CORE : Thunderbolt.GLOW, 1.0f), from.x, from.y, from.z, 1, 0.05, 0.05, 0.05, 0);
		if (f.charged && f.age % 2 == 0) Thunderbolt.bolt(level, from, next, 0.45f, 0, 1);
		if (f.age % 4 == 0) level.playSound(null, next.x, next.y, next.z, AuraSounds.PLAYER_ATTACK_SWEEP.value(), SoundSource.PLAYERS, 0.5f, 0.5f);
		return false;
	}

	/** The closest mob or player (not hit yet) on the segment, with a little margin. */
	private static LivingEntity firstAlong(ServerLevel level, ServerPlayer owner, Vec3 a, Vec3 b, Flight f) {
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(1.0), e -> VineHook.canHook(owner, e) && !f.hit.contains(e))) {
			var at = e.getBoundingBox().inflate(0.4).clip(a, b);
			if (at.isEmpty()) continue;
			double d = at.get().distanceToSqr(a);
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		return best;
	}

	/** The hammer hits someone on the way out. */
	private static void impact(Flight f, ServerPlayer owner, LivingEntity target) {
		ServerLevel level = (ServerLevel) f.display.level();
		f.hit.add(target);
		Vec3 c = AuraFx.chest(target);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), SoundSource.PLAYERS, 1.2f, 1.0f);
		if (f.charged) {
			strike(owner, target); // the thunder (its damage, splash and paralysis)
		} else {
			Thunderbolt.zap(owner, target, AuraConfig.MJOLNIR_THROW_DAMAGE);
			Thunderbolt.impact(level, c, 0.8f);
			target.setDeltaMovement(target.getDeltaMovement().add(f.dir.x * AuraConfig.MJOLNIR_KNOCKBACK, 0.3, f.dir.z * AuraConfig.MJOLNIR_KNOCKBACK));
			target.hurtMarked = true;
		}
	}

	/** Back in the hand. */
	private static void catchIt(Flight f, ServerPlayer owner) {
		removeDisplay(f);
		giveBack(owner, f.stack, f.slot);
		ServerLevel level = owner.serverLevel();
		Vec3 h = Thunderbolt.hand(owner, false);
		Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, h.x, h.y, h.z, 12, 0.2, 0.2, 0.2, 0.15);
		level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), AuraSounds.TRIDENT_RETURN.value(), SoundSource.PLAYERS, 1.2f, 0.7f);
		level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), AuraSounds.ARMOR_EQUIP_NETHERITE.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
		owner.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
	}

	/** The owner is gone, dead or elsewhere: give it back right away, or (dead) drop it where it is. True = done. */
	private static boolean abort(Flight f, ServerPlayer owner) {
		removeDisplay(f);
		if (owner != null && owner.isAlive()) {
			giveBack(owner, f.stack, f.slot);
		} else {
			ServerLevel level = (ServerLevel) f.display.level();
			net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(level, f.pos.x, f.pos.y, f.pos.z, f.stack);
			item.setUnlimitedLifetime();
			level.addFreshEntity(item);
		}
		return true;
	}

	private static void giveBack(ServerPlayer owner, ItemStack stack, int slot) {
		var inv = owner.getInventory();
		if (inv.getItem(slot).isEmpty()) inv.setItem(slot, stack);
		else if (inv.getSelected().isEmpty()) inv.setItem(inv.selected, stack);
		else if (!inv.add(stack)) owner.drop(stack, false);
	}

	private static void removeDisplay(Flight f) {
		LIVE.remove(f.display.getUUID());
		f.display.discard();
	}

	// ---------------------------------------------------------------
	// Flight: spinning, then HOLD left-click - you're carried where you point, hammer first. The heading follows
	// your aim every tick (FLIGHT_STEERING), so you can change direction mid-flight; you only stop once you let go
	// of the button (the client reports it: AbilityKeyPacket.MJOLNIR_ATTACK_HELD), swap the hammer out of your hand,
	// or FLIGHT_MAX_TICKS run out. A wall just stops you against it (you can steer away, still flying). Anyone in
	// the way gets hit (a charged hammer brings the thunder down on the first). No fall damage until you land.
	// NoGravity while flying, tagged so a crash mid-flight can't leave you floating (cleared on login).
	// ---------------------------------------------------------------

	private static final String FLY_TAG = "aurapoints_mjolnir_flight";

	private static final class Ride {
		Vec3 dir;
		final double speed;
		final java.util.Set<LivingEntity> hit = new java.util.HashSet<>();
		boolean charged;
		/** Left mouse let go (or the hold packet said so). */
		boolean released;
		/** Pressed against a wall (the slam effect plays once per contact). */
		boolean pressed;
		int age;
		/** Flight over: waiting to touch down (no fall damage meanwhile). */
		boolean landing;
		int landingTicks;

		Ride(Vec3 dir, double speed, boolean charged) {
			this.dir = dir;
			this.speed = speed;
			this.charged = charged;
		}
	}

	private static final Map<UUID, Ride> RIDING = new HashMap<>();
	private static final Map<UUID, Long> FLIGHT_READY = new HashMap<>();

	public static boolean isFlying(ServerPlayer player) {
		Ride r = RIDING.get(player.getUUID());
		return r != null && !r.landing;
	}

	/** The client's attack button went down or up while it holds Mjölnir: letting go ends a flight. */
	public static void attackHeld(ServerPlayer player, boolean down) {
		Ride r = RIDING.get(player.getUUID());
		if (r != null && !r.landing && !down) r.released = true;
	}

	/** A left-click (at the air, a block or a mob). True if it started a flight - the click itself is then cancelled. */
	public static boolean onLeftClick(ServerPlayer player) {
		if (!SPINNING.containsKey(player.getUUID()) || !holding(player) || !AuraCommand.isOwner(player) || isFlying(player)) return false;
		long now = player.getServer().getTickCount();
		Long ready = FLIGHT_READY.get(player.getUUID());
		if (ready != null && now < ready && ready - now < 20 * 60) {
			player.displayClientMessage(Component.literal(String.format("Mjölnir flight: %.1fs", (ready - now) / 20.0)).withStyle(ChatFormatting.GRAY), true);
			return true;
		}
		release(player);
		boolean charged = isCharged(player, now);
		if (charged) {
			CHARGED.remove(player.getUUID());
			STREAK.remove(player.getUUID());
		}
		ServerLevel level = player.serverLevel();
		double speed = Math.max(0.3, AuraConfig.MJOLNIR_FLIGHT_SPEED);
		RIDING.put(player.getUUID(), new Ride(player.getLookAngle().normalize(), speed, charged));
		player.setNoGravity(true);
		player.addTag(FLY_TAG);
		player.fallDistance = 0;
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.MJOLNIR_FLY);
		Vec3 f = player.position();
		Thunderbolt.send(level, ParticleTypes.CLOUD, f.x, f.y + 0.1, f.z, 14, 0.4, 0.05, 0.4, 0.1);
		Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, f.x, f.y + 0.5, f.z, 20, 0.4, 0.4, 0.4, 0.2);
		level.playSound(null, f.x, f.y, f.z, AuraSounds.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.2f, 0.8f);
		level.playSound(null, f.x, f.y, f.z, AuraSounds.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.8f, 1.4f);
		return true;
	}

	private static void tickRides(MinecraftServer server) {
		if (RIDING.isEmpty()) return;
		long now = server.getTickCount();
		for (Iterator<Map.Entry<UUID, Ride>> it = RIDING.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Ride r = e.getValue();
			if (p == null) {
				it.remove();
				continue;
			}
			if (r.landing) {
				p.fallDistance = 0;
				if (p.onGround() || p.isInWater() || ++r.landingTicks > 200) it.remove();
				continue;
			}
			if (!p.isAlive() || p.isSpectator() || IceSpike.isFrozen(p) || Stun.isStunned(p) || Entanglement.isEntangled(p)
					|| r.released || !holding(p) || ++r.age > Math.max(1, AuraConfig.MJOLNIR_FLIGHT_MAX_TICKS)) {
				endFlight(p, r, now);
				if (!p.isAlive() || p.isSpectator()) it.remove();
				continue;
			}
			try {
				ride(p, r);
			} catch (Exception ex) {
				AuraPoints.LOGGER.error("Mjölnir flight failed", ex);
				endFlight(p, r, now);
				it.remove();
			}
		}
	}

	private static void ride(ServerPlayer player, Ride r) {
		ServerLevel level = player.serverLevel();
		player.fallDistance = 0;
		// steer: the heading swings toward where you look
		Vec3 look = player.getLookAngle().normalize();
		Vec3 steered = r.dir.lerp(look, Mth.clamp(AuraConfig.MJOLNIR_FLIGHT_STEERING, 0.05, 1.0));
		r.dir = steered.lengthSqr() < 1.0e-4 ? look : steered.normalize();
		Vec3 chest = AuraFx.chest(player);
		Vec3 step = r.dir.scale(r.speed);
		Vec3 ahead = chest.add(r.dir.scale(r.speed + 1.0));
		// whoever is in the way gets the hammer
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(chest, ahead).inflate(1.0), e -> VineHook.canHook(player, e) && !r.hit.contains(e))) {
			if (e.getBoundingBox().inflate(0.6).clip(chest, ahead).isEmpty()) continue;
			r.hit.add(e);
			if (r.charged) {
				r.charged = false;
				strike(player, e);
			} else {
				Thunderbolt.zap(player, e, AuraConfig.MJOLNIR_FLIGHT_DAMAGE);
				Thunderbolt.impact(level, AuraFx.chest(e), 0.7f);
				// knocked aside, out of the flight line
				Vec3 side = e.position().subtract(player.position()).multiply(1, 0, 1);
				side = side.lengthSqr() < 1.0e-4 ? r.dir : side.normalize();
				e.setDeltaMovement(e.getDeltaMovement().add(side.x * AuraConfig.MJOLNIR_KNOCKBACK + r.dir.x * 0.5, 0.35, side.z * AuraConfig.MJOLNIR_KNOCKBACK + r.dir.z * 0.5));
				e.hurtMarked = true;
			}
			level.playSound(null, e.getX(), e.getY(), e.getZ(), AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), SoundSource.PLAYERS, 1.0f, 1.1f);
		}
		// a wall: slam into it (once) and hang there - still flying, steer away or let go
		HitResult wall = level.clip(new ClipContext(chest, chest.add(step).add(r.dir.scale(0.4)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (wall.getType() != HitResult.Type.MISS) {
			if (!r.pressed) {
				r.pressed = true;
				Vec3 w = wall.getLocation();
				Thunderbolt.impact(level, w, 1.0f);
				level.playSound(null, w.x, w.y, w.z, AuraSounds.MACE_SMASH_GROUND.value(), SoundSource.PLAYERS, 1.2f, 0.9f);
			}
			player.setDeltaMovement(Vec3.ZERO);
			player.hurtMarked = true;
			return;
		}
		r.pressed = false;
		player.setDeltaMovement(step);
		player.hurtMarked = true;
		// the hammer leading: lightning off its head, wind behind
		Vec3 head = Thunderbolt.hand(player, false).add(r.dir.scale(0.8));
		Thunderbolt.send(level, ParticleTypes.ELECTRIC_SPARK, head.x, head.y, head.z, 4, 0.1, 0.1, 0.1, 0.1);
		Thunderbolt.send(level, AuraFx.dust(Thunderbolt.GLOW, 1.3f), chest.x, chest.y, chest.z, 3, 0.25, 0.4, 0.25, 0);
		Thunderbolt.send(level, ParticleTypes.CLOUD, chest.x, chest.y - 0.4, chest.z, 1, 0.2, 0.2, 0.2, 0.01);
		if (player.getRandom().nextFloat() < 0.5f) Thunderbolt.bolt(level, head, head.add(r.dir.scale(1.2)).add(player.getRandom().nextGaussian() * 0.3, player.getRandom().nextGaussian() * 0.3, player.getRandom().nextGaussian() * 0.3), 0.4f, 0, 1);
		if (r.age % 4 == 0) level.playSound(null, chest.x, chest.y, chest.z, AuraSounds.ELYTRA_FLYING.value(), SoundSource.PLAYERS, 0.15f, 1.6f);
	}

	/** Out of the air: gravity back, a little momentum, no fall damage until the ground. The flight cooldown starts now. */
	private static void endFlight(ServerPlayer player, Ride r, long now) {
		player.setNoGravity(false);
		player.removeTag(FLY_TAG);
		player.setDeltaMovement(r.pressed ? Vec3.ZERO : r.dir.scale(Math.min(0.4, r.speed * 0.25)));
		player.hurtMarked = true;
		player.fallDistance = 0;
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
		FLIGHT_READY.put(player.getUUID(), now + AuraConfig.MJOLNIR_FLIGHT_COOLDOWN_TICKS);
		r.landing = true;
	}

	/** A crash mid-flight left NoGravity on: take it off. */
	public static void onLogin(ServerPlayer player) {
		if (player.getTags().contains(FLY_TAG)) {
			player.removeTag(FLY_TAG);
			player.setNoGravity(false);
		}
	}

	public static void onLogout(ServerPlayer player) {
		Ride ride = RIDING.remove(player.getUUID());
		if (ride != null) {
			player.setNoGravity(false);
			player.removeTag(FLY_TAG);
		}
		FLIGHT_READY.remove(player.getUUID());
		SPINNING.remove(player.getUUID());
		CHARGED.remove(player.getUUID());
		STREAK.remove(player.getUUID());
		Flight f = FLYING.remove(player.getUUID());
		if (f != null) abort(f, player);
	}

	/** Server stopping: every hammer in the air goes back to its owner first. */
	public static void clearAll(MinecraftServer server) {
		for (UUID id : new java.util.ArrayList<>(RIDING.keySet())) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				p.setNoGravity(false);
				p.removeTag(FLY_TAG);
			}
		}
		RIDING.clear();
		for (Flight f : new java.util.ArrayList<>(FLYING.values())) abort(f, server.getPlayerList().getPlayer(f.owner));
		FLYING.clear();
	}
}
