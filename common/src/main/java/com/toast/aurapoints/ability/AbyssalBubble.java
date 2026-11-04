package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Abyssal Bubble, the Water aura's third active. Hold its key: a sphere of
 * BUBBLE_RADIUS blocks follows you (you walk slower) and everything inside that
 * breathes air runs out of it, then drowns. Holding it shakes your camera harder
 * and harder (client side, see client/BubbleShake): if your crosshair drifts more
 * than BUBBLE_BREAK_DEGREES from where it was when you started, the bubble pops.
 * It ends when you let go, pops, or after BUBBLE_MAX_HOLD_TICKS; the cooldown
 * starts then. Not an entity, just particles; everything lives in memory only.
 */
public final class AbyssalBubble {

	private AbyssalBubble() {
	}

	private static final int ABYSS = 0x123C8C;
	private static final int ABYSS_DEEP = 0x0A1E4A;
	private static final int STRAINED = 0xC8F0FF;
	private static final int RING = 0x3FA9FF;

	private static final ResourceLocation SLOW_ID = ResourceLocation.fromNamespaceAndPath("aurapoints", "abyssal_bubble_slow");

	private static final class Bubble {
		final long startTick;
		Vec3 startLook;
		/** target -> last tick it took drowning damage */
		final Map<UUID, Long> lastHurt = new HashMap<>();

		Bubble(long startTick, Vec3 startLook) {
			this.startTick = startTick;
			this.startLook = startLook;
		}
	}

	private static final Map<UUID, Bubble> BUBBLES = new HashMap<>();

	public static boolean isHolding(ServerPlayer player) {
		return BUBBLES.containsKey(player.getUUID());
	}

	/** Key pressed: opens the bubble. */
	public static boolean press(ServerPlayer player, long now) {
		if (BUBBLES.containsKey(player.getUUID())) return false;
		if (!AuraAbilities.checkCooldown(player, Ability.ABYSSAL_BUBBLE, AuraConfig.BUBBLE_COOLDOWN_TICKS, now)) return false;
		Vec3 look = player.getLookAngle();
		Bubble bubble = new Bubble(now, look);
		BUBBLES.put(player.getUUID(), bubble);
		Combos.follow(player, Combos.Kind.BUBBLE, () -> center(player), AuraConfig.BUBBLE_RADIUS, AuraConfig.BUBBLE_MAX_HOLD_TICKS + 20,
				() -> BUBBLES.get(player.getUUID()) == bubble && AuraFx.alive(player), () -> {
					if (BUBBLES.get(player.getUUID()) == bubble) end(player, player.getServer().getTickCount(), false);
				});
		slow(player, true);
		resist(player);

		ServerLevel level = player.serverLevel();
		Vec3 c = center(player);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(ABYSS, 2.0f), 40, 1.2, 0.0);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.SQUID_INK, 20, 0.6, 0.05);
		for (int i = 0; i < 30; i++) shellPoint(level, player, c, i, 30, now, ParticleTypes.NAUTILUS);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.AMBIENT_UNDERWATER_ENTER.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.ELDER_GUARDIAN_AMBIENT.value(), SoundSource.PLAYERS, 0.7f, 0.6f);
		player.displayClientMessage(Component.literal("✦ Hold steady - don't let the bubble break! ✦").withStyle(ChatFormatting.DARK_AQUA), true);
		NetworkHandler.sendBubble(player, true, player.getYRot(), player.getXRot());
		NetworkHandler.sendBendAnim(player, com.toast.aurapoints.network.BendingAnimPacket.BUBBLE_HOLD);
		AuraStats.add(player, AuraStats.ABYSSAL_BUBBLE_USES, 1);
		return true;
	}

	/**
	 * The holder took a hit: knockback and the jolt shouldn't pop it, so the spot to hold steady on
	 * moves to where they look now (BUBBLE_HIT_RECENTERS), and their client is told.
	 */
	public static void onHurt(ServerPlayer player) {
		Bubble b = BUBBLES.get(player.getUUID());
		if (b == null || !AuraConfig.BUBBLE_HIT_RECENTERS) return;
		b.startLook = player.getLookAngle();
		NetworkHandler.sendBubble(player, true, player.getYRot(), player.getXRot());
	}

	/** Key released: the bubble fades out normally. */
	public static void release(ServerPlayer player, long now) {
		if (BUBBLES.containsKey(player.getUUID())) end(player, now, false);
	}

	/** Ends the bubble; {@code popped} = it broke (louder, more particles). The cooldown starts either way. */
	private static void end(ServerPlayer player, long now, boolean popped) {
		BUBBLES.remove(player.getUUID());
		slow(player, false);
		NetworkHandler.sendBubble(player, false, 0, 0);
		// the hold pose ends (NONE first, so it isn't resumed after), then the pop / release gesture
		NetworkHandler.sendBendAnim(player, com.toast.aurapoints.network.BendingAnimPacket.NONE);
		NetworkHandler.sendGesture(player, popped ? com.toast.aurapoints.network.BendingAnimPacket.GESTURE_BUBBLE_POP
				: com.toast.aurapoints.network.BendingAnimPacket.GESTURE_BUBBLE_RELEASE, 0);
		ServerLevel level = player.serverLevel();
		Vec3 c = center(player);
		if (popped) {
			for (int i = 0; i < 80; i++) shellPoint(level, player, c, i, 80, now, ParticleTypes.BUBBLE_POP);
			AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(STRAINED, 1.6f), 40, 2.0, 0.0);
			level.playSound(null, c.x, c.y, c.z, AuraSounds.GLASS_BREAK.value(), SoundSource.PLAYERS, 1.0f, 1.5f);
			level.playSound(null, c.x, c.y, c.z, AuraSounds.BUBBLE_COLUMN_BUBBLE_POP.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
			level.playSound(null, c.x, c.y, c.z, AuraSounds.PLAYER_SPLASH_HIGH_SPEED.value(), SoundSource.PLAYERS, 0.7f, 1.2f);
			player.displayClientMessage(Component.literal("The bubble broke!").withStyle(ChatFormatting.RED), true);
		} else {
			for (int i = 0; i < 40; i++) shellPoint(level, player, c, i, 40, now, ParticleTypes.BUBBLE_POP);
			level.playSound(null, c.x, c.y, c.z, AuraSounds.AMBIENT_UNDERWATER_EXIT.value(), SoundSource.PLAYERS, 1.0f, 0.9f);
		}
		AuraAbilities.markUsed(player, Ability.ABYSSAL_BUBBLE, now);
	}

	/** Gone without a trace and without a cooldown (ability lost, death, logout). */
	static void drop(ServerPlayer player) {
		if (BUBBLES.remove(player.getUUID()) != null) {
			slow(player, false);
			NetworkHandler.sendBubble(player, false, 0, 0);
			NetworkHandler.sendBendAnim(player, com.toast.aurapoints.network.BendingAnimPacket.NONE);
		}
	}

	private static void slow(ServerPlayer player, boolean on) {
		var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) return;
		speed.removeModifier(SLOW_ID);
		if (on && AuraConfig.BUBBLE_SLOWNESS > 0) {
			speed.addTransientModifier(new AttributeModifier(SLOW_ID, -Math.min(1.0, AuraConfig.BUBBLE_SLOWNESS),
					AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	/**
	 * Resistance for the holder only. Topped up every few ticks while holding, so it runs out
	 * about BUBBLE_RESISTANCE_AFTER_TICKS after the bubble ends; never weakens a stronger Resistance.
	 */
	private static void resist(ServerPlayer player) {
		if (AuraConfig.BUBBLE_RESISTANCE_AMPLIFIER < 0) return;
		player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE,
				Math.max(0, AuraConfig.BUBBLE_RESISTANCE_AFTER_TICKS) + RESIST_REFRESH_TICKS,
				AuraConfig.BUBBLE_RESISTANCE_AMPLIFIER, false, false, true));
	}

	private static final int RESIST_REFRESH_TICKS = 5;

	private static Vec3 center(ServerPlayer player) {
		return player.position().add(0, player.getBbHeight() * 0.5, 0);
	}

	/** Degrees between where the player looks now and where they looked when the bubble opened. */
	private static double drift(ServerPlayer player, Bubble b) {
		double dot = Math.max(-1.0, Math.min(1.0, player.getLookAngle().dot(b.startLook)));
		return Math.toDegrees(Math.acos(dot));
	}

	/**
	 * Point {@code i} of {@code n} on the bubble's shell (a slowly turning Fibonacci sphere).
	 * NAUTILUS particles fly from the spawn offset to the given position, so for those the
	 * position is the center and the offset the shell point: they stream inward.
	 */
	private static void shellPoint(ServerLevel level, ServerPlayer player, Vec3 c, int i, int n, long now,
								   net.minecraft.core.particles.ParticleOptions particle) {
		double r = AuraConfig.BUBBLE_RADIUS;
		double y = 1 - (i + 0.5) * 2.0 / n;
		double ring = Math.sqrt(Math.max(0, 1 - y * y));
		double a = i * 2.39996323 + now * 0.05;
		double x = Math.cos(a) * ring * r, z = Math.sin(a) * ring * r, dy = y * r;
		if (particle == ParticleTypes.NAUTILUS) {
			AuraFx.particles(level, particle, c.x, c.y, c.z, 0, x, dy, z, 1.0);
		} else {
			AuraFx.particles(level, particle, c.x + x, c.y + dy, c.z + z, 1, 0.05, 0.05, 0.05, 0.0);
		}
	}

	/** Everyone the bubble works on at all: not you, your mount or your pets, nor creative players. */
	private static boolean affects(ServerPlayer player, LivingEntity e) {
		return Handhelds.enemy(player, e);
	}

	private static boolean canDrown(ServerPlayer player, LivingEntity e) {
		if (!affects(player, e)) return false;
		// the bubble isn't real water: every monster drowns in it, undead and sea monsters too
		if (e instanceof Enemy) return true;
		return com.toast.aurapoints.shim.Platform.get().canDrownInWater(e) && !MobEffectUtil.hasWaterBreathing(e);
	}

	/** Drowning that counts as the bender's doing (kill credit), and goes through armor like drowning does. */
	private static DamageSource damage(ServerPlayer player) {
		var types = player.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		return new DamageSource(types.getHolderOrThrow(DamageTypes.DROWN), null, player);
	}

	/** Every server tick per player: the shell, the drowning, and the checks that end it. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		Bubble b = BUBBLES.get(player.getUUID());
		if (b == null) return;
		if (!AuraAbilities.hasAbility(data, Ability.ABYSSAL_BUBBLE) || !player.isAlive() || player.isSpectator()) {
			drop(player);
			return;
		}
		if (now < b.startTick || now - b.startTick >= AuraConfig.BUBBLE_MAX_HOLD_TICKS) {
			end(player, now, false);
			return;
		}
		double strain = drift(player, b) / Math.max(1.0, AuraConfig.BUBBLE_BREAK_DEGREES);
		if (strain > 1.0) {
			end(player, now, true);
			return;
		}

		if ((now - b.startTick) % RESIST_REFRESH_TICKS == 0) resist(player);

		ServerLevel level = player.serverLevel();
		Vec3 c = center(player);
		double r = AuraConfig.BUBBLE_RADIUS;

		// The shell: a dense skin of big blue specks (they linger, so it reads as a surface), three
		// bright rings turning around it so the round shape shows from any angle, and glowing motes
		// that stay visible in the dark. It pales and cracks as the bubble strains.
		int rgb = lerpRgb(ABYSS, STRAINED, (float) Math.max(0, (strain - 0.5) * 2));
		int ringRgb = lerpRgb(RING, STRAINED, (float) Math.max(0, (strain - 0.5) * 2));
		int n = 160;
		for (int i = (int) (now % 2); i < n; i += 2) {
			shellPoint(level, player, c, i, n, now, AuraFx.dust(i % 5 == 0 ? ABYSS_DEEP : rgb, 2.2f));
		}
		if (now % 2 == 0) {
			double spin = now * 0.06;
			greatCircle(level, c, r, 0.0, spin, 56, AuraFx.dust(ringRgb, 1.7f));
			greatCircle(level, c, r, Math.PI / 3, -spin * 1.3, 56, AuraFx.dust(ringRgb, 1.7f));
			greatCircle(level, c, r, -Math.PI / 3, spin * 0.8, 56, AuraFx.dust(ringRgb, 1.7f));
		}
		for (int i = 0; i < 6; i++) shellPoint(level, player, c, player.getRandom().nextInt(n), n, now, ParticleTypes.GLOW);
		// where it meets the ground: a dark rim
		if (now % 4 == 0) AuraFx.ring(level, c.x, player.getY() + 0.1, c.z, Math.sqrt(Math.max(0, r * r - Math.pow(c.y - player.getY(), 2))),
				AuraFx.dust(ABYSS_DEEP, 2.5f), 40);
		if (strain > 0.7) {
			for (int i = 0; i < 10; i++) shellPoint(level, player, c, player.getRandom().nextInt(n), n, now, ParticleTypes.ELECTRIC_SPARK);
		}
		if (now % 2 == 0) shellPoint(level, player, c, player.getRandom().nextInt(24), 24, now, ParticleTypes.NAUTILUS);
		if ((now - b.startTick) % 30 == 0) {
			level.playSound(null, c.x, c.y, c.z, AuraSounds.CONDUIT_AMBIENT_SHORT.value(), SoundSource.PLAYERS, 1.0f, 0.7f);
		}

		// The undertow: everyone inside drifts toward you and walking away is sluggish. Players
		// move themselves (client/BubblePull, so it stays smooth); mobs are moved here.
		// one look around for both the undertow and the drowning
		List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(r + 1), e -> affects(player, e));
		if (AuraConfig.BUBBLE_PULL_STRENGTH > 0 || AuraConfig.BUBBLE_OUTWARD_DRAG > 0) {
			for (LivingEntity e : near) {
				if (e.position().add(0, e.getBbHeight() * 0.5, 0).distanceToSqr(c) > r * r) continue;
				// Leviathan drags harder: mobs get the bigger strength, players an extra push from here (the client pull keeps its own numbers)
				boolean leviathan = Branch.is(player, Branch.LEVIATHAN);
				if (e instanceof ServerPlayer target) {
					NetworkHandler.sendBubblePull(target, c.x, c.z);
					if (leviathan && !target.isPassenger()) {
						e.setDeltaMovement(undertow(e.position(), e.getDeltaMovement(), c.x, c.z, AuraConfig.WATER_LEVIATHAN_BUBBLE_PULL, 0));
						e.hurtMarked = true;
					}
				} else if (!e.isPassenger()) {
					e.setDeltaMovement(undertow(e.position(), e.getDeltaMovement(), c.x, c.z,
							leviathan ? AuraConfig.BUBBLE_PULL_STRENGTH + AuraConfig.WATER_LEVIATHAN_BUBBLE_PULL : AuraConfig.BUBBLE_PULL_STRENGTH, AuraConfig.BUBBLE_OUTWARD_DRAG));
				}
			}
		}

		// The drowning: air runs out fast (vanilla refills 4 a tick out of water, so that is taken
		// on top), then drowning damage every BUBBLE_DAMAGE_INTERVAL_TICKS.
		for (LivingEntity e : near) {
			if (!e.isAlive() || e.getEyePosition().distanceToSqr(c) > r * r || !canDrown(player, e)) continue;
			int air = Math.max(0, e.getAirSupply() - AuraConfig.BUBBLE_AIR_DRAIN_PER_TICK - 4);
			e.setAirSupply(air);
			Vec3 head = e.getEyePosition();
			if (now % 3 == 0) {
				// air pulled out of their lungs, streaming to the bender
				AuraFx.particles(level, ParticleTypes.NAUTILUS, c.x, c.y, c.z, 0, head.x - c.x, head.y - c.y, head.z - c.z, 1.0);
				AuraFx.particles(level, ParticleTypes.BUBBLE_POP, head.x, head.y, head.z, 2, 0.15, 0.1, 0.15, 0.02);
			}
			if (air > 0) continue;
			Long last = b.lastHurt.get(e.getUUID());
			if (last != null && now - last < AuraConfig.BUBBLE_DAMAGE_INTERVAL_TICKS && now >= last) continue;
			b.lastHurt.put(e.getUUID(), now);
			if (e.hurt(damage(player), AuraAbilities.vsMob(e, AuraConfig.BUBBLE_DAMAGE))) {
				AuraStats.add(player, AuraStats.ABYSSAL_BUBBLE_HITS, 1);
				AuraFx.burst(level, head.x, head.y, head.z, AuraFx.dust(ABYSS_DEEP, 1.2f), 8, 0.25, 0.0);
			}
		}
	}

	/**
	 * The undertow on one entity's motion, sideways only: {@code drag} of its speed away from the
	 * middle is taken off, then {@code pull} is added toward the middle (less right at the middle,
	 * so nobody jitters on top of the bender). Shared by the server (mobs) and client (players).
	 */
	public static Vec3 undertow(Vec3 pos, Vec3 motion, double centerX, double centerZ, double pull, double drag) {
		double dx = centerX - pos.x, dz = centerZ - pos.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist < 1.0E-4) return motion;
		dx /= dist;
		dz /= dist;
		double mx = motion.x, mz = motion.z;
		double outward = -(mx * dx + mz * dz);
		if (outward > 0) {
			double cut = outward * Math.max(0.0, Math.min(1.0, drag));
			mx += dx * cut;
			mz += dz * cut;
		}
		double p = Math.max(0.0, pull) * Math.min(1.0, dist / 1.5);
		return new Vec3(mx + dx * p, motion.y, mz + dz * p);
	}

	/** A ring all the way around the bubble, tilted by {@code tilt} about the X axis and turned by {@code spin} about Y. */
	private static void greatCircle(ServerLevel level, Vec3 c, double r, double tilt, double spin, int points,
									net.minecraft.core.particles.ParticleOptions particle) {
		double ct = Math.cos(tilt), st = Math.sin(tilt), cs = Math.cos(spin), ss = Math.sin(spin);
		for (int i = 0; i < points; i++) {
			double a = Math.PI * 2 * i / points;
			double x = Math.cos(a) * r, z = Math.sin(a) * r;
			double y = -z * st;
			z = z * ct;
			double rx = x * cs - z * ss, rz = x * ss + z * cs;
			AuraFx.particles(level, particle, c.x + rx, c.y + y, c.z + rz, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private static int lerpRgb(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return (r << 16) | (g << 8) | bl;
	}

	public static void onLogout(ServerPlayer player) {
		BUBBLES.remove(player.getUUID());
	}
}
