package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Light: Radiant Pulse, Solar Flash and the held Prism Beam on the Ability keys, Halo and Luminous always
 * on (its Ultimate, Sunburst, is in Ultimates). The beam is a pair of glowing block displays (Props) re-aimed every tick.
 */
public final class Light {

	private Light() {
	}

	static final int GOLD = 0xFFD966, WHITE_GOLD = 0xFFF4C8;

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
	}

	/**
	 * Whom the light heals: you, your team, and any other player outside the Aura Arena (inside it players fight, so
	 * there it is you alone). Sunburst's lances leave the same ones alone.
	 */
	static boolean friend(ServerPlayer p, LivingEntity e) {
		if (e == p) return true;
		if (!(e instanceof Player other) || other.isSpectator()) return false;
		if (p.isAlliedTo(other)) return true;
		return !com.toast.aurapoints.arena.Arena.in(p) && !com.toast.aurapoints.arena.Arena.in(other);
	}

	static boolean undead(LivingEntity e) {
		return e.getType().is(EntityTypeTags.UNDEAD);
	}

	/** The right hand of an arm pointed where the player looks (the Prism Beam's pose). */
	static Vec3 pointingHand(ServerPlayer p) {
		float yaw = p.getYRot() * Mth.DEG_TO_RAD;
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 shoulder = p.position().add(0, p.isShiftKeyDown() ? 1.15 : 1.37, 0).add(right.scale(0.31));
		return shoulder.add(p.getLookAngle().scale(0.72));
	}

	// ---------------------------------------------------------------
	// Radiant Pulse
	// ---------------------------------------------------------------

	static boolean pulse(ServerPlayer p, long now) {
		if (!AuraAbilities.checkCooldown(p, Ability.RADIANT_PULSE, AuraConfig.LIGHT_PULSE_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = p.serverLevel();
		double r = Math.max(1, AuraConfig.LIGHT_PULSE_RADIUS);
		Vec3 c = AuraFx.chest(p);
		// everyone around who isn't undead is warmed and healed; the undead burn
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r), e -> e.isAlive() && !e.isSpectator()
				&& AuraFx.chest(e).distanceToSqr(c) <= r * r)) {
			if (undead(e)) {
				if (!Handhelds.enemy(p, e)) continue;
				Handhelds.hurt(p, e, AuraConfig.LIGHT_PULSE_UNDEAD_DAMAGE);
				e.igniteForSeconds(3);
				Vec3 ec = AuraFx.chest(e);
				AuraFx.particles(level, ParticleTypes.FLAME, ec.x, ec.y, ec.z, 8, 0.3, 0.4, 0.3, 0.02);
			} else if (friend(p, e)) {
				e.heal(Math.max(0, AuraConfig.LIGHT_PULSE_HEAL));
				if (AuraConfig.LIGHT_PULSE_REGEN_TICKS > 0) e.addEffect(new MobEffectInstance(MobEffects.REGENERATION, AuraConfig.LIGHT_PULSE_REGEN_TICKS, 0), p);
				AuraFx.particles(level, ParticleTypes.HEART, e.getX(), e.getY() + 2.1, e.getZ(), 1, 0.2, 0.1, 0.2, 0);
				AuraFx.particles(level, AuraFx.dust(GOLD, 1.0f), e.getX(), e.getY() + 1, e.getZ(), 10, 0.3, 0.5, 0.3, 0);
			}
		}
		// the light rolling out, in three rings
		for (int k = 1; k <= 3; k++) {
			final int ring = k;
			AuraFx.later(level.getServer(), k * 2, () -> {
				double rr = r * ring / 3.0;
				AuraFx.ring(level, c.x, p.getY() + 0.3, c.z, rr, AuraFx.dust(ring == 3 ? WHITE_GOLD : GOLD, 1.3f), (int) (rr * 7) + 6);
				AuraFx.ring(level, c.x, p.getY() + 1.0, c.z, rr * 0.9, ParticleTypes.END_ROD, (int) (rr * 2) + 3);
			});
		}
		AuraFx.particles(level, ParticleTypes.END_ROD, c.x, c.y, c.z, 24, 0.2, 0.2, 0.2, 0.14);
		AuraFx.particles(level, ParticleTypes.WAX_OFF, c.x, c.y, c.z, 12, 0.6, 0.6, 0.6, 0);
		sound(level, c, SoundEvents.BEACON_ACTIVATE, 1.0f, 1.6f);
		sound(level, c, SoundEvents.AMETHYST_BLOCK_CHIME, 1.5f, 1.2f);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_LIGHT, 0);
		AuraAbilities.markUsed(p, Ability.RADIANT_PULSE, now);
		return true;
	}

	// ---------------------------------------------------------------
	// Solar Flash
	// ---------------------------------------------------------------

	static boolean flash(ServerPlayer p, long now) {
		if (!AuraAbilities.checkCooldown(p, Ability.SOLAR_FLASH, AuraConfig.LIGHT_FLASH_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = p.serverLevel();
		double range = Math.max(2, AuraConfig.LIGHT_FLASH_RANGE);
		double cos = Math.cos(Math.toRadians(Math.max(5, AuraConfig.LIGHT_FLASH_ANGLE) / 2));
		Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
		for (LivingEntity e : Handhelds.enemies(p, level, eye, range)) {
			Vec3 to = AuraFx.chest(e).subtract(eye);
			if (to.lengthSqr() < 1.0e-4 || to.normalize().dot(look) < cos) continue;
			// only what can see you: nothing through walls
			if (level.clip(new ClipContext(eye, AuraFx.chest(e), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) continue;
			Handhelds.hurt(p, e, AuraConfig.LIGHT_FLASH_DAMAGE);
			e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Math.max(20, AuraConfig.LIGHT_FLASH_BLIND_TICKS), 0), p);
			e.addEffect(new MobEffectInstance(MobEffects.GLOWING, Math.max(20, AuraConfig.LIGHT_FLASH_GLOW_TICKS), 0), p);
			// a mob caught in it loses you for a moment
			if (e instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == p) mob.setTarget(null);
			Vec3 ec = AuraFx.chest(e);
			AuraFx.particles(level, ParticleTypes.END_ROD, ec.x, ec.y + 0.4, ec.z, 6, 0.2, 0.2, 0.2, 0.05);
		}
		// the flash: a burst of light from the palm and a cone of sparks
		Vec3 hand = pointingHand(p);
		AuraFx.particles(level, ParticleTypes.FLASH, hand.x + look.x * 0.6, hand.y + look.y * 0.6, hand.z + look.z * 0.6, 1, 0, 0, 0, 0);
		var random = p.getRandom();
		for (int i = 0; i < 40; i++) {
			Vec3 d = look.add((random.nextDouble() - 0.5) * 0.9, (random.nextDouble() - 0.5) * 0.6, (random.nextDouble() - 0.5) * 0.9).normalize();
			double dist = 1 + random.nextDouble() * (range - 1);
			Vec3 at = hand.add(d.scale(dist));
			AuraFx.particles(level, i % 3 == 0 ? ParticleTypes.END_ROD : AuraFx.dust(i % 2 == 0 ? WHITE_GOLD : GOLD, 1.2f), at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
		sound(level, hand, SoundEvents.FIREWORK_ROCKET_BLAST, 1.2f, 1.5f);
		sound(level, hand, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4f, 1.6f);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_LIGHT, 1);
		AuraAbilities.markUsed(p, Ability.SOLAR_FLASH, now);
		return true;
	}

	// ---------------------------------------------------------------
	// Prism Beam (held)
	// ---------------------------------------------------------------

	private static final ResourceLocation BEAM_SLOW = ResourceLocation.fromNamespaceAndPath("aurapoints", "light_beam");

	private static final class Beam {
		final UUID owner;
		final ServerLevel level;
		final Display.BlockDisplay core, glow;
		int t;

		Beam(UUID owner, ServerLevel level, Display.BlockDisplay core, Display.BlockDisplay glow) {
			this.owner = owner;
			this.level = level;
			this.core = core;
			this.glow = glow;
		}
	}

	private static final Map<UUID, Beam> BEAMS = new HashMap<>();

	public static boolean isBeaming(ServerPlayer p) {
		return BEAMS.containsKey(p.getUUID());
	}

	static boolean beam(ServerPlayer p, long now) {
		if (BEAMS.containsKey(p.getUUID())) return false;
		if (!AuraAbilities.checkCooldown(p, Ability.PRISM_BEAM, AuraConfig.LIGHT_BEAM_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = p.serverLevel();
		Vec3 hand = pointingHand(p);
		var zero = Props.along(Vec3.ZERO, p.getLookAngle(), 0.001f, 0.001f);
		Display.BlockDisplay core = Props.spawn(level, hand, Blocks.WHITE_CONCRETE.defaultBlockState(), zero, -1, 1);
		Display.BlockDisplay glow = Props.spawn(level, hand, Blocks.YELLOW_STAINED_GLASS.defaultBlockState(), zero, -1, 1);
		Props.glow(core);
		Props.glow(glow);
		BEAMS.put(p.getUUID(), new Beam(p.getUUID(), level, core, glow));
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(BEAM_SLOW);
			speed.addTransientModifier(new AttributeModifier(BEAM_SLOW, -0.4, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		NetworkHandler.sendBendAnim(p, BendingAnimPacket.PRISM_AIM);
		sound(level, hand, SoundEvents.BEACON_ACTIVATE, 1.0f, 1.8f);
		return true;
	}

	/** The key let go. */
	public static void beamRelease(ServerPlayer p, long now) {
		Beam b = BEAMS.remove(p.getUUID());
		if (b != null) stop(p, b, now);
	}

	private static void stop(ServerPlayer p, Beam b, long now) {
		Props.remove(b.core);
		Props.remove(b.glow);
		if (p == null) return;
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) speed.removeModifier(BEAM_SLOW);
		NetworkHandler.sendBendAnim(p, BendingAnimPacket.NONE);
		sound(b.level, p.position(), SoundEvents.BEACON_DEACTIVATE, 0.8f, 1.6f);
		AuraAbilities.markUsed(p, Ability.PRISM_BEAM, now);
	}

	/** Returns false when the beam is over. */
	private static boolean tickBeam(MinecraftServer server, Beam b) {
		ServerPlayer p = server.getPlayerList().getPlayer(b.owner);
		long now = server.getTickCount();
		if (p == null || !p.isAlive() || p.isSpectator() || p.level() != b.level
				|| !AuraAbilities.hasAbility(AuraState.get(server).getOrCreate(p.getUUID()), Ability.PRISM_BEAM)
				|| ++b.t > Math.max(10, AuraConfig.LIGHT_BEAM_MAX_TICKS)) {
			stop(p, b, now);
			return false;
		}
		ServerLevel level = b.level;
		Vec3 from = pointingHand(p), look = p.getLookAngle();
		double range = Math.max(4, AuraConfig.LIGHT_BEAM_RANGE);
		Vec3 end = from.add(look.scale(range));
		HitResult wall = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		end = wall.getLocation();
		var entityHit = ProjectileUtil.getEntityHitResult(level, p, from, end, new AABB(from, end).inflate(0.6), e -> Handhelds.enemy(p, e), 0.25f);
		LivingEntity target = entityHit != null && entityHit.getEntity() instanceof LivingEntity l ? l : null;
		if (target != null) end = entityHit.getLocation();
		float len = (float) from.distanceTo(end);
		Combos.pass(p, from, end, 0.25, null);
		// the beam: a white core in a gold glow, thrumming a little
		float pulse = 1f + 0.15f * Mth.sin(b.t * 0.9f);
		Props.move(b.core, from);
		Props.move(b.glow, from);
		Props.set(b.core, Props.along(Vec3.ZERO, look, 0.07f * pulse, len), 1);
		Props.set(b.glow, Props.along(Vec3.ZERO, look, 0.2f * pulse, len), 1);
		AuraFx.particles(level, ParticleTypes.END_ROD, end.x, end.y, end.z, 2, 0.1, 0.1, 0.1, 0.06);
		AuraFx.particles(level, AuraFx.dust(GOLD, 1.0f), end.x, end.y, end.z, 3, 0.15, 0.15, 0.15, 0);
		if (b.t % 3 == 0) {
			double d = level.getRandom().nextDouble() * len;
			Vec3 at = from.add(look.scale(d));
			AuraFx.particles(level, ParticleTypes.WAX_OFF, at.x, at.y, at.z, 1, 0.05, 0.05, 0.05, 0);
		}
		if (target != null && b.t % Math.max(1, AuraConfig.LIGHT_BEAM_HIT_TICKS) == 0) {
			Handhelds.hurt(p, target, AuraConfig.LIGHT_BEAM_DAMAGE * (undead(target) ? (float) Math.max(1, AuraConfig.LIGHT_UNDEAD_MULTIPLIER) : 1f));
			if (undead(target)) target.igniteForSeconds(2);
		}
		if (b.t % 20 == 1) sound(level, from, SoundEvents.BEACON_AMBIENT, 1.2f, 1.7f);
		return true;
	}

	// ---------------------------------------------------------------
	// Passives
	// ---------------------------------------------------------------

	/** Every server tick for every player. */
	public static void tickPassives(ServerPlayer p, AuraPlayerData data, long now) {
		if (now % 10 != 0) return;
		boolean luminous = AuraAbilities.hasAbility(data, Ability.LUMINOUS) && p.isAlive();
		MobEffectInstance sight = p.getEffect(MobEffects.NIGHT_VISION);
		if (luminous) {
			// ambient and without particles, so it reads as the aura's own (and can be told apart to take off again)
			if (sight == null || sight.isAmbient() && sight.getDuration() < 300) {
				p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, true, false, false));
			}
			int every = Math.max(20, AuraConfig.LIGHT_LUMINOUS_PULSE_TICKS);
			if (now % every == 0 && !p.isSpectator()) {
				double r = Math.max(1, AuraConfig.LIGHT_LUMINOUS_RADIUS);
				for (LivingEntity e : Handhelds.enemies(p, p.serverLevel(), AuraFx.chest(p), r)) {
					if (e instanceof Player || e instanceof Enemy) e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, true, false), p);
				}
			}
		} else if (sight != null && sight.isAmbient() && !sight.isVisible() && sight.getDuration() <= 400 && !Nightstalker.wantsVision(p, data)) {
			p.removeEffect(MobEffects.NIGHT_VISION);
		}
		if (AuraAbilities.hasAbility(data, Ability.HALO) && p.isAlive() && now % Math.max(20, AuraConfig.LIGHT_HALO_HEAL_TICKS) == 0
				&& p.getHealth() < p.getMaxHealth() && inSunlight(p)) {
			p.heal(Math.max(0, AuraConfig.LIGHT_HALO_HEAL));
			if (!p.isInvisible()) AuraFx.particles(p.serverLevel(), AuraFx.dust(GOLD, 0.9f), p.getX(), p.getY() + 2.15, p.getZ(), 6, 0.25, 0.05, 0.25, 0);
		}
	}

	static boolean inSunlight(ServerPlayer p) {
		ServerLevel level = p.serverLevel();
		return level.isDay() && !level.isRaining() && level.canSeeSky(BlockPos.containing(p.getEyePosition()));
	}

	// ---------------------------------------------------------------
	// Lifecycle
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		if (BEAMS.isEmpty()) return;
		for (Beam b : new ArrayList<>(BEAMS.values())) {
			boolean alive;
			try {
				alive = tickBeam(server, b);
			} catch (Exception ex) {
				com.toast.aurapoints.AuraPoints.LOGGER.error("Prism Beam failed", ex);
				Props.remove(b.core);
				Props.remove(b.glow);
				alive = false;
			}
			if (!alive) BEAMS.remove(b.owner);
		}
	}

	public static void onLogout(ServerPlayer p) {
		Beam b = BEAMS.remove(p.getUUID());
		if (b != null) stop(p, b, p.getServer() == null ? 0 : p.getServer().getTickCount());
	}

	public static void clearAll() {
		BEAMS.clear();
	}
}
