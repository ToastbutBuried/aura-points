package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.FxPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The Blue Bell (dropped by the Frost Bear, item/BlueBellItem). The bell flies out as an item display and strikes the
 * nearest enemies; every strike is more violent (damage, speed, particles, a higher ring, a shorter wait). After the
 * last strike it rises over you and a BLIZZARD rages: whiteout on every nearby client, enemies slowed, frozen, hurt.
 */
public final class BlueBell {

	private BlueBell() {
	}

	private enum Stage {HUNT, BLIZZARD, RETURN}

	private static final class Flight {
		final UUID owner;
		final ServerLevel level;
		final RootCanvas canvas;
		final Display.ItemDisplay bell;
		Vec3 pos;
		Stage stage = Stage.HUNT;
		int age, strikes, wait = 6, idle, blizzard;

		Flight(UUID owner, ServerLevel level, RootCanvas canvas, Display.ItemDisplay bell, Vec3 pos) {
			this.owner = owner;
			this.level = level;
			this.canvas = canvas;
			this.bell = bell;
			this.pos = pos;
		}
	}

	private static final List<Flight> FLIGHTS = new ArrayList<>();

	public static boolean active(ServerPlayer player) {
		for (Flight f : FLIGHTS) if (f.owner.equals(player.getUUID())) return true;
		return false;
	}

	/** Throws the bell; false = one is already out. */
	public static boolean start(ServerPlayer player) {
		if (active(player)) return false;
		ServerLevel level = player.serverLevel();
		Vec3 at = AuraFx.chest(player).add(player.getLookAngle().scale(0.8));
		RootCanvas canvas = new RootCanvas(level, BlockPos.containing(player.getEyePosition()));
		Display.ItemDisplay bell = canvas.item(new ItemStack(AuraPoints.BLUE_BELL.get()), at);
		FLIGHTS.add(new Flight(player.getUUID(), level, canvas, bell, at));
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.5f, 1.2f);
		return true;
	}

	public static void tickAll(MinecraftServer server) {
		if (FLIGHTS.isEmpty()) return;
		for (Iterator<Flight> it = FLIGHTS.iterator(); it.hasNext(); ) {
			Flight f = it.next();
			boolean done;
			try {
				done = tick(server, f);
			} catch (Exception e) {
				AuraPoints.LOGGER.error("Blue Bell failed", e);
				done = true;
			}
			if (done) {
				f.canvas.discardAll();
				it.remove();
			}
		}
	}

	public static void clearAll() {
		for (Flight f : FLIGHTS) f.canvas.discardAll();
		FLIGHTS.clear();
	}

	private static boolean tick(MinecraftServer server, Flight f) {
		ServerPlayer owner = Handhelds.player(server, f.owner);
		int age = f.age++;
		int limit = AuraConfig.BLUE_BELL_STRIKES * 120 + AuraConfig.BLUE_BELL_BLIZZARD_TICKS + 400;
		if (owner == null || owner.level() != f.level || !owner.isAlive() || age > limit) return true;
		return switch (f.stage) {
			case HUNT -> hunt(owner, f, age);
			case BLIZZARD -> blizzard(owner, f);
			case RETURN -> back(owner, f);
		};
	}

	private static void show(Flight f, int age, float scale, float tilt) {
		RootCanvas.moveTo(f.bell, f.pos);
		RootCanvas.place(f.bell, f.pos, new Quaternionf().rotateY(age * 0.45f).rotateZ(tilt), scale, 1);
	}

	private static boolean hunt(ServerPlayer owner, Flight f, int age) {
		ServerLevel level = f.level;
		if (f.wait > 0) {
			f.wait--;
			// hovering by the owner's shoulder, swinging
			Vec3 home = AuraFx.chest(owner).add(0, 0.9 + Math.sin(age * 0.2) * 0.15, 0);
			if (f.strikes == 0 || f.idle > 0) f.pos = f.pos.add(home.subtract(f.pos).scale(0.25));
			show(f, age, 1.5f, (float) Math.sin(age * 0.5) * 0.25f);
			return false;
		}
		LivingEntity target = nearest(owner, f);
		if (target == null) {
			f.idle++;
			Vec3 home = AuraFx.chest(owner).add(Math.cos(age * 0.12) * 1.6, 0.9, Math.sin(age * 0.12) * 1.6);
			f.pos = f.pos.add(home.subtract(f.pos).scale(0.2));
			show(f, age, 1.5f, (float) Math.sin(age * 0.5) * 0.25f);
			return f.idle > AuraConfig.BLUE_BELL_IDLE_TICKS;
		}
		f.idle = 0;
		Vec3 goal = AuraFx.chest(target);
		Vec3 to = goal.subtract(f.pos);
		double d = to.length();
		double speed = AuraConfig.BLUE_BELL_SPEED * (1.0 + AuraConfig.BLUE_BELL_SPEED_GROWTH * f.strikes) + 0.4;
		if (d <= Math.max(1.0, speed * 0.6)) {
			strike(owner, f, target);
			return false;
		}
		f.pos = f.pos.add(to.scale(Math.min(1.0, speed / d)));
		show(f, age, 1.5f + 0.08f * f.strikes, 0.4f);
		AuraFx.particles(level, AuraPoints.FROST_SHARD.get(), f.pos.x, f.pos.y, f.pos.z, 1 + f.strikes / 2, 0.15, 0.15, 0.15, 0.02);
		return false;
	}

	private static LivingEntity nearest(ServerPlayer owner, Flight f) {
		LivingEntity best = null;
		double bd = Double.MAX_VALUE;
		for (LivingEntity e : Handhelds.enemies(owner, f.level, owner.position(), AuraConfig.BLUE_BELL_RANGE)) {
			double d = AuraFx.chest(e).distanceToSqr(f.pos);
			if (d < bd) {
				bd = d;
				best = e;
			}
		}
		return best;
	}

	/** One strike; number {@code f.strikes} (0-based) of STRIKES. Everything about it grows with the number. */
	private static void strike(ServerPlayer owner, Flight f, LivingEntity target) {
		ServerLevel level = f.level;
		int n = f.strikes++;
		float k = n / (float) Math.max(1, AuraConfig.BLUE_BELL_STRIKES - 1); // 0..1
		Vec3 at = AuraFx.chest(target);
		Vec3 away = at.subtract(f.pos);
		away = away.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : away.normalize();
		Handhelds.hurt(owner, target, AuraConfig.BLUE_BELL_DAMAGE_BASE + AuraConfig.BLUE_BELL_DAMAGE_STEP * n);
		target.setDeltaMovement(target.getDeltaMovement().add(away.scale(0.3 + 0.7 * k).add(0, 0.2 + 0.4 * k, 0)));
		target.hurtMarked = true;
		target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30 + n * 10, 1));
		if (n >= AuraConfig.BLUE_BELL_STRIKES / 2) target.setTicksFrozen(Math.min(target.getTicksRequiredToFreeze() + 40, target.getTicksFrozen() + 40 + n * 10));
		// louder, higher, bigger every time
		level.playSound(null, at.x, at.y, at.z, SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.6f + k, 0.8f + 0.9f * k);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.GLASS_BREAK.value(), SoundSource.PLAYERS, 0.8f + k, 1.4f - 0.5f * k);
		AuraFx.burst(level, at.x, at.y, at.z, AuraPoints.FROST_SHARD.get(), 8 + n * 5, 0.2, 0.25 + 0.3 * k);
		AuraFx.burst(level, at.x, at.y, at.z, AuraPoints.FROST_BURST.get(), 1 + n / 2, 0.1, 0.0);
		if (n >= 3) AuraFx.burst(level, at.x, at.y, at.z, AuraPoints.SNOW_GUST.get(), 3 + n * 2, 0.5, 0.08);
		if (n >= 5) AuraFx.ring(level, at.x, at.y - 0.6, at.z, 1.5 + n * 0.3, AuraPoints.FROST_SHARD.get(), 14);
		// bounce off, up and back; the wait shrinks
		f.pos = f.pos.add(away.scale(-1.2)).add(0, 0.8, 0);
		f.wait = Math.max(AuraConfig.BLUE_BELL_MIN_DELAY_TICKS, Math.round(AuraConfig.BLUE_BELL_DELAY_TICKS * (1f - 0.8f * k)));
		if (f.strikes >= AuraConfig.BLUE_BELL_STRIKES) {
			f.stage = Stage.BLIZZARD;
			f.blizzard = Math.max(40, AuraConfig.BLUE_BELL_BLIZZARD_TICKS);
			level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 3.0f, 0.6f);
			level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.POWDER_SNOW_BREAK, SoundSource.PLAYERS, 2.0f, 0.5f);
			double range = AuraConfig.BLUE_BELL_BLIZZARD_RADIUS + 40;
			for (ServerPlayer p : level.players()) {
				if (p.distanceToSqr(owner) <= range * range) NetworkHandler.sendFx(p, FxPacket.BLIZZARD, f.blizzard, "");
			}
		}
	}

	private static boolean blizzard(ServerPlayer owner, Flight f) {
		ServerLevel level = f.level;
		int left = f.blizzard--;
		double r = AuraConfig.BLUE_BELL_BLIZZARD_RADIUS;
		Vec3 c = owner.position();
		// the bell climbs over the owner and rings
		Vec3 top = c.add(0, 4.5 + Math.sin(f.age * 0.3) * 0.3, 0);
		f.pos = f.pos.add(top.subtract(f.pos).scale(0.2));
		show(f, f.age, 2.6f, (float) Math.sin(f.age * 0.9) * 0.35f);
		if (f.age % 10 == 0) level.playSound(null, f.pos.x, f.pos.y, f.pos.z, SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 2.0f, 0.5f + (left % 20) * 0.02f);
		if (f.age % 6 == 0) level.playSound(null, c.x, c.y, c.z, SoundEvents.POWDER_SNOW_STEP, SoundSource.PLAYERS, 1.2f, 0.5f);
		// driving snow all over the area, streaming one way
		for (int i = 0; i < 6; i++) {
			double a = level.random.nextDouble() * Math.PI * 2, d = Math.sqrt(level.random.nextDouble()) * r;
			double x = c.x + Math.cos(a) * d, z = c.z + Math.sin(a) * d, y = c.y + level.random.nextDouble() * 6;
			AuraFx.particles(level, ParticleTypes.SNOWFLAKE, x, y, z, 0, 1.0, -0.15, 0.35, 0.6);
		}
		AuraFx.particles(level, AuraPoints.SNOW_GUST.get(), c.x, c.y + 2, c.z, 3, r * 0.5, 2.5, r * 0.5, 0.05);
		if (f.age % 4 == 0) AuraFx.particles(level, AuraPoints.FROST_SHARD.get(), c.x, c.y + 3, c.z, 2, r * 0.5, 2.0, r * 0.5, 0.1);
		if (left % 20 == 0) {
			for (LivingEntity e : Handhelds.enemies(owner, level, c, r)) {
				Handhelds.hurt(owner, e, AuraConfig.BLUE_BELL_BLIZZARD_DAMAGE);
				e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, AuraConfig.BLUE_BELL_BLIZZARD_SLOW_AMPLIFIER));
				e.setTicksFrozen(Math.min(e.getTicksRequiredToFreeze() + 60, e.getTicksFrozen() + 70));
			}
		}
		if (left <= 0) {
			f.stage = Stage.RETURN;
			AuraFx.burst(level, f.pos.x, f.pos.y, f.pos.z, AuraPoints.FROST_BURST.get(), 4, 0.3, 0.0);
		}
		return false;
	}

	private static boolean back(ServerPlayer owner, Flight f) {
		Vec3 hand = AuraFx.chest(owner);
		Vec3 to = hand.subtract(f.pos);
		double d = to.length();
		if (d < 1.2) {
			f.level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.0f, 1.8f);
			return true;
		}
		f.pos = f.pos.add(to.scale(Math.min(1.0, 1.2 / d)));
		show(f, f.age, 1.8f, 0.3f);
		AuraFx.particles(f.level, AuraPoints.FROST_SHARD.get(), f.pos.x, f.pos.y, f.pos.z, 1, 0.1, 0.1, 0.1, 0.02);
		return false;
	}
}
