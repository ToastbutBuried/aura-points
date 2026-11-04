package com.toast.aurapoints.ability;

import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.mixin.BlockDisplayAccessor;
import com.toast.aurapoints.mixin.DisplayAccessor;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ultimates: one per aura (Eclipse, Tidal Ascension, Phoenix, Tempest, Permafrost, Overgrowth, Judgement), on the
 * Ultimate key, unlocked by the aura's Awakening (an FTB quest after Mastery V, whose reward runs /aura awaken - see
 * {@link #awaken}). Each is a short scripted move ticked here, with a full-body animation (BendingAnimPacket
 * GESTURE_ULTIMATE, client/UltimateGestures) and a long shared cooldown. Damage goes through Handhelds.hurt (vsMob),
 * particles through AuraFx; nothing breaks blocks, and the displays they grow are removed again.
 */
public final class Ultimates {

	private Ultimates() {
	}

	/** In gesture-variant order (BendingAnimPacket.GESTURE_ULTIMATE). */
	public static final Ability[] ORDER = {Ability.ECLIPSE, Ability.TIDAL_ASCENSION, Ability.PHOENIX, Ability.TEMPEST,
			Ability.PERMAFROST, Ability.OVERGROWTH, Ability.JUDGEMENT, Ability.TECTONIC_RIFT, Ability.SUNBURST, Ability.BLOOD_MOON, Ability.SINGULARITY};

	public static Ability of(AuraType aura) {
		if (aura == null) return null;
		return switch (aura) {
			case SHADOW -> Ability.ECLIPSE;
			case WATER -> Ability.TIDAL_ASCENSION;
			case FIRE -> Ability.PHOENIX;
			case WIND -> Ability.TEMPEST;
			case FROST -> Ability.PERMAFROST;
			case VERDANT -> Ability.OVERGROWTH;
			case THUNDER -> Ability.JUDGEMENT;
			case EARTH -> Ability.TECTONIC_RIFT;
			case LIGHT -> Ability.SUNBURST;
			case BLOOD -> Ability.BLOOD_MOON;
			default -> null;
		};
	}

	/** The Ultimate for an aura with a branch (Dark swaps Eclipse for Singularity). */
	public static Ability of(AuraType aura, Branch branch) {
		if (aura != null && branch != null && branch.aura == aura && branch.ultimate() != null) return branch.ultimate();
		return of(aura);
	}

	/** The aura an Ultimate belongs to, or null if it isn't one. */
	public static AuraType auraOf(Ability ability) {
		for (AuraType aura : AuraType.values()) if (of(aura) == ability) return aura;
		for (Branch b : Branch.values()) if (b.ultimate() == ability) return b.aura;
		return null;
	}

	public static int variant(Ability ability) {
		for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == ability) return i;
		return 0;
	}

	private static final String TAG = "aurapoints_ultimate";
	/** On a player while an Ultimate holds them up (no gravity): taken off again on login after a crash. */
	private static final String FLY_TAG = "aurapoints_ult_flying";

	/** A running Ultimate. */
	private static final class Run {
		final UUID id;
		final Ability ability;
		final long started;
		int t;
		/** what each one keeps */
		final List<LivingEntity> targets = new ArrayList<>();
		final Set<LivingEntity> hit = new HashSet<>();
		final Map<LivingEntity, Integer> lastTouch = new HashMap<>();
		final List<Display.BlockDisplay> displays = new ArrayList<>();
		final List<Transformation> shown = new ArrayList<>(), hidden = new ArrayList<>();
		Vec3 point, dir;
		double baseY;
		int phaseAt = -1, struck;
		boolean diving;

		Run(UUID id, Ability ability, long started) {
			this.id = id;
			this.ability = ability;
			this.started = started;
		}
	}

	private static final Map<UUID, Run> RUNS = new HashMap<>();
	/** Players coming down after an Ultimate held them up: no fall damage until they land. */
	private static final Map<UUID, Integer> LANDING = new HashMap<>();
	/** The displays of running Ultimates (anything else with the tag is a leftover from a crash). */
	private static final Set<UUID> LIVE = new HashSet<>();

	// ---------------------------------------------------------------
	// The Ultimate key
	// ---------------------------------------------------------------

	public static void press(ServerPlayer player, long now) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
		Ability ult = of(data.aura, Branch.current(data));
		if (ult == null) return;
		if (!data.awakened.contains(data.aura)) {
			player.displayClientMessage(Component.literal("Your aura isn't awakened yet - complete its Awakening in the quest book (needs Mastery V).")
					.withStyle(ChatFormatting.GRAY), true);
			return;
		}
		if (!AuraAbilities.hasAbility(data, ult) || RUNS.containsKey(player.getUUID())) return;
		if (player.isSpectator() || IceSpike.isFrozen(player) || Stun.isBusy(player) || Entanglement.isEntangled(player) || VoidAura.isNulled(player)
				|| com.toast.aurapoints.arena.Duel.holdsStill(player)) return;
		if (!AuraAbilities.checkCooldown(player, ult, ult.cooldownTicks(), now)) return;
		// Blood Moon also needs a (full) blood bar
		if (ult == Ability.BLOOD_MOON && !AuraAbilities.cooldownsDisabled(player) && !Blood.moonReady(player)) return;
		float boost = AuraFx.boost;
		AuraFx.boost = Mastery.particleBoost(player);
		try {
			start(player, ult, now);
		} finally {
			AuraFx.boost = boost;
		}
		AuraAbilities.markUsed(player, ult, now);
	}

	private static void start(ServerPlayer player, Ability ult, long now) {
		ServerLevel level = player.serverLevel();
		AuraType aura = auraOf(ult);
		Run run = new Run(player.getUUID(), ult, now);
		RUNS.put(player.getUUID(), run);
		com.toast.aurapoints.AuraPoints.LOGGER.info("[ultimate] {} used {}", player.getGameProfile().getName(), ult.displayName);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_ULTIMATE, variant(ult));
		// its name low on the screen, briefly, so it doesn't cover the move itself
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(3, 26, 8));
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
				Component.literal("✦ " + ult.displayName + " ✦").withStyle(s -> s.withColor(aura.rgb).withBold(true))));
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.empty()));
		sound(level, player.position(), SoundEvents.BEACON_POWER_SELECT, 1.2f, 0.6f);
		switch (ult) {
			case ECLIPSE -> startEclipse(player, run);
			case TIDAL_ASCENSION -> startTidal(player, run);
			case PHOENIX -> startPhoenix(player, run);
			case TEMPEST -> startTempest(player, run);
			case PERMAFROST -> startPermafrost(player, run);
			case OVERGROWTH -> startOvergrowth(player, run);
			case JUDGEMENT -> startJudgement(player, run);
			case TECTONIC_RIFT -> startRift(player, run);
			case SUNBURST -> startSunburst(player, run);
			case BLOOD_MOON -> startBloodMoon(player, run);
			case SINGULARITY -> startSingularity(player, run);
			default -> {
			}
		}
	}

	/** Every server tick. */
	public static void tickAll(MinecraftServer server) {
		if (!LANDING.isEmpty()) {
			for (Iterator<Map.Entry<UUID, Integer>> it = LANDING.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
				if (p == null) {
					it.remove();
					continue;
				}
				p.fallDistance = 0;
				e.setValue(e.getValue() + 1);
				if (p.onGround() || p.isInWater() || e.getValue() > 200) it.remove();
			}
		}
		if (RUNS.isEmpty()) return;
		for (Iterator<Run> it = new ArrayList<>(RUNS.values()).iterator(); it.hasNext(); ) {
			Run run = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(run.id);
			if (p == null || !p.isAlive() || p.isSpectator()) {
				end(p, run);
				continue;
			}
			run.t++;
			boolean done;
			float boost = AuraFx.boost;
			AuraFx.boost = Mastery.particleBoost(p);
			try {
				done = switch (run.ability) {
					case ECLIPSE -> eclipse(p, run);
					case TIDAL_ASCENSION -> tidal(p, run);
					case PHOENIX -> phoenix(p, run);
					case TEMPEST -> tempest(p, run);
					case PERMAFROST -> permafrost(p, run);
					case OVERGROWTH -> overgrowth(p, run);
					case JUDGEMENT -> judgement(p, run);
					case TECTONIC_RIFT -> rift(p, run);
					case SUNBURST -> sunburst(p, run);
					case BLOOD_MOON -> bloodMoon(p, run);
					case SINGULARITY -> singularity(p, run);
					default -> true;
				};
			} catch (Exception ex) {
				com.toast.aurapoints.AuraPoints.LOGGER.error("Ultimate {} failed", run.ability, ex);
				done = true;
			} finally {
				AuraFx.boost = boost;
			}
			if (done) end(p, run);
		}
	}

	/** Over (or cut short): gravity back, roots let go, displays gone. */
	private static void end(ServerPlayer p, Run run) {
		RUNS.remove(run.id);
		if (p != null && p.getTags().contains(FLY_TAG)) {
			p.removeTag(FLY_TAG);
			p.setNoGravity(false);
			p.fallDistance = 0;
			LANDING.put(p.getUUID(), 0);
		}
		for (LivingEntity e : run.hit) if (run.ability == Ability.OVERGROWTH && Rooted.isRooted(e)) Rooted.unlock(e);
		for (Display.BlockDisplay d : run.displays) {
			LIVE.remove(d.getUUID());
			d.discard();
		}
	}

	private static void hold(ServerPlayer p) {
		p.setNoGravity(true);
		p.addTag(FLY_TAG);
		p.fallDistance = 0;
	}

	private static void move(ServerPlayer p, Vec3 velocity) {
		p.setDeltaMovement(velocity);
		p.hurtMarked = true;
		p.fallDistance = 0;
	}

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
	}

	private static Vec3 flat(Vec3 v) {
		Vec3 f = new Vec3(v.x, 0, v.z);
		return f.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : f.normalize();
	}

	/** The ground under {@code at} (within 16 blocks), or {@code at} itself. */
	private static Vec3 ground(ServerLevel level, Vec3 at, Entity self) {
		HitResult hit = level.clip(new ClipContext(at.add(0, 0.5, 0), at.add(0, -16, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
		return hit.getType() == HitResult.Type.MISS ? at : hit.getLocation();
	}

	// ---------------------------------------------------------------
	// Shadow - Eclipse
	// ---------------------------------------------------------------

	private static void startEclipse(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		double range = Math.max(1, AuraConfig.ULT_ECLIPSE_RANGE);
		for (ServerPlayer other : level.players()) {
			if (other.distanceToSqr(p) <= range * range * 2.25 && Handhelds.enemy(p, other)) {
				other.addEffect(new MobEffectInstance(MobEffects.DARKNESS, Math.max(20, AuraConfig.ULT_ECLIPSE_DARKNESS_TICKS), 0, false, false, true));
			}
		}
		p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false, false));
		List<LivingEntity> enemies = Handhelds.enemies(p, level, AuraFx.chest(p), range);
		enemies.sort((a, b) -> Double.compare(a.distanceToSqr(p), b.distanceToSqr(p)));
		for (int i = 0; i < Math.min(Math.max(1, AuraConfig.ULT_ECLIPSE_TARGETS), enemies.size()); i++) run.targets.add(enemies.get(i));
		Vec3 c = AuraFx.chest(p);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.SQUID_INK, 8, 0.5, 0.08);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(0x5A2A8C, 1.6f), 30, 0.8, 0);
		sound(level, c, SoundEvents.WARDEN_SONIC_CHARGE, 1.0f, 1.4f);
		sound(level, c, SoundEvents.ENDERMAN_SCREAM, 0.4f, 0.6f);
	}

	private static boolean eclipse(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		if (run.targets.isEmpty()) {
			// nobody to hunt: a burst of shadow round you instead
			if (run.t == 6) {
				for (LivingEntity e : Handhelds.enemies(p, level, AuraFx.chest(p), 6)) Handhelds.hurt(p, e, AuraConfig.ULT_ECLIPSE_DAMAGE * 0.5f);
				AuraFx.ring(level, p.getX(), p.getY() + 0.3, p.getZ(), 4, ParticleTypes.SQUID_INK, 40);
				sound(level, p.position(), SoundEvents.WITHER_SHOOT, 0.5f, 0.6f);
			}
			return run.t >= 10;
		}
		if (run.t % 6 == 0 && run.struck < run.targets.size()) {
			LivingEntity target = run.targets.get(run.struck++);
			if (target.isAlive() && target.distanceToSqr(p) < 32 * 32) {
				Vec3 from = AuraFx.chest(p);
				Vec3 behind = target.position().subtract(flat(target.getLookAngle()).scale(1.3));
				if (level.noCollision(p, p.getBoundingBox().move(behind.subtract(p.position())))) {
					double dx = target.getX() - behind.x, dz = target.getZ() - behind.z;
					float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
					p.teleportTo(level, behind.x, behind.y, behind.z, yaw, 10f);
				}
				AuraFx.line(level, from, AuraFx.chest(p), AuraFx.dust(0x2A1040, 1.6f), 16);
				Handhelds.hurt(p, target, AuraConfig.ULT_ECLIPSE_DAMAGE);
				Vec3 c = AuraFx.chest(target);
				AuraFx.particles(level, ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 2, 0.3, 0.2, 0.3, 0);
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.SQUID_INK, 3, 0.3, 0.05);
				AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(0x9B4FE0, 1.3f), 18, 0.5, 0);
				sound(level, c, SoundEvents.PLAYER_ATTACK_SWEEP, 1.0f, 0.8f);
				sound(level, c, SoundEvents.ENDERMAN_TELEPORT, 0.6f, 1.4f);
			}
		}
		if (run.struck >= run.targets.size() && run.t % 6 == 4) {
			Vec3 c = AuraFx.chest(p);
			AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.SMOKE, 12, 0.4, 0.03);
			p.removeEffect(MobEffects.INVISIBILITY);
			return true;
		}
		return run.t > 60;
	}

	// ---------------------------------------------------------------
	// Water - Tidal Ascension
	// ---------------------------------------------------------------

	private static void startTidal(ServerPlayer p, Run run) {
		hold(p);
		run.baseY = p.getY();
		sound(p.serverLevel(), p.position(), SoundEvents.TRIDENT_RIPTIDE_3.value(), 1.2f, 0.8f);
		sound(p.serverLevel(), p.position(), SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, 1.5f, 0.8f);
	}

	private static boolean tidal(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		if (run.t <= 14) move(p, new Vec3(0, 0.42, 0));
		else if (run.t < 20) move(p, Vec3.ZERO);
		if (run.t < 20) {
			// the column of water under you
			for (double y = run.baseY; y < p.getY(); y += 0.45) {
				double a = run.t * 0.6 + y * 1.3;
				AuraFx.particles(level, AuraFx.dust(0x2E9BFF, 1.4f), p.getX() + Math.cos(a) * 0.55, y, p.getZ() + Math.sin(a) * 0.55, 1, 0, 0, 0, 0);
				AuraFx.particles(level, ParticleTypes.SPLASH, p.getX(), y, p.getZ(), 2, 0.3, 0.1, 0.3, 0.1);
			}
			AuraFx.particles(level, ParticleTypes.BUBBLE_POP, p.getX(), p.getY(), p.getZ(), 4, 0.4, 0.2, 0.4, 0.05);
		}
		if (run.t == 20) {
			p.removeTag(FLY_TAG);
			p.setNoGravity(false);
			move(p, new Vec3(0, -2.0, 0));
			sound(level, p.position(), SoundEvents.PLAYER_SPLASH_HIGH_SPEED, 1.2f, 0.7f);
		}
		if (run.t > 20 && run.phaseAt < 0) {
			p.fallDistance = 0;
			if (p.onGround() || p.isInWater() || run.t > 70) {
				run.phaseAt = run.t;
				run.point = p.position();
				p.heal(Math.max(0, AuraConfig.ULT_TIDAL_HEAL));
				sound(level, run.point, SoundEvents.GENERIC_SPLASH, 2.5f, 0.6f);
				sound(level, run.point, SoundEvents.TRIDENT_THUNDER.value(), 0.4f, 1.6f);
				AuraFx.burst(level, run.point.x, run.point.y + 0.5, run.point.z, ParticleTypes.SPLASH, 80, 1.0, 0.4);
			}
		}
		if (run.phaseAt >= 0) {
			// the ring wave rolling out
			int w = run.t - run.phaseAt;
			double radius = Math.max(1, AuraConfig.ULT_TIDAL_RADIUS);
			double r = 1 + (radius - 1) * Math.min(1, w / 10.0);
			AuraFx.ring(level, run.point.x, run.point.y + 0.3, run.point.z, r, AuraFx.dust(0x5FC8FF, 1.8f), (int) (r * 6));
			AuraFx.ring(level, run.point.x, run.point.y + 0.8, run.point.z, r, ParticleTypes.SPLASH, (int) (r * 4));
			AuraFx.ring(level, run.point.x, run.point.y + 0.1, run.point.z, r - 0.4, ParticleTypes.BUBBLE_POP, (int) (r * 3));
			for (LivingEntity e : Handhelds.enemies(p, level, run.point, r + 0.6)) {
				if (!run.hit.add(e)) continue;
				Handhelds.hurt(p, e, AuraConfig.ULT_TIDAL_DAMAGE);
				Vec3 out = flat(e.position().subtract(run.point)).scale(AuraConfig.ULT_TIDAL_KNOCKBACK);
				e.setDeltaMovement(e.getDeltaMovement().add(out.x, 0.45, out.z));
				e.hurtMarked = true;
				e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
			}
			return w >= 10;
		}
		return false;
	}

	// ---------------------------------------------------------------
	// Fire - Phoenix
	// ---------------------------------------------------------------

	private static void startPhoenix(ServerPlayer p, Run run) {
		hold(p);
		p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, AuraConfig.ULT_PHOENIX_TICKS + 80, 0, false, false, true));
		ServerLevel level = p.serverLevel();
		AuraFx.ring(level, p.getX(), p.getY() + 0.2, p.getZ(), 1.5, ParticleTypes.FLAME, 40);
		sound(level, p.position(), SoundEvents.BLAZE_SHOOT, 1.2f, 0.7f);
		sound(level, p.position(), SoundEvents.FIRECHARGE_USE, 1.0f, 0.6f);
	}

	private static boolean phoenix(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		Vec3 chest = AuraFx.chest(p);
		if (!run.diving) {
			if (run.t <= 6) {
				move(p, new Vec3(0, 0.5, 0));
			} else {
				Vec3 dir = p.getLookAngle().normalize();
				double speed = Math.max(0.2, AuraConfig.ULT_PHOENIX_SPEED);
				HitResult wall = level.clip(new ClipContext(chest, chest.add(dir.scale(speed + 0.6)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
				if (p.isShiftKeyDown() || wall.getType() != HitResult.Type.MISS || run.t > AuraConfig.ULT_PHOENIX_TICKS) {
					run.diving = true;
					run.phaseAt = run.t;
					run.dir = flat(dir);
					sound(level, chest, SoundEvents.BLAZE_SHOOT, 1.0f, 0.5f);
				} else {
					move(p, dir.scale(speed));
				}
			}
			// trail and wings of flame
			AuraFx.particles(level, ParticleTypes.FLAME, chest.x, chest.y, chest.z, 6, 0.25, 0.3, 0.25, 0.02);
			AuraFx.particles(level, AuraFx.dust(0xFF8A2A, 1.6f), chest.x, chest.y, chest.z, 4, 0.3, 0.3, 0.3, 0);
			if (run.t % 3 == 0) AuraFx.particles(level, ParticleTypes.LAVA, chest.x, chest.y, chest.z, 1, 0.2, 0.2, 0.2, 0);
			Vec3 right = flat(p.getLookAngle()).cross(new Vec3(0, 1, 0)).normalize();
			double flap = Math.sin(run.t * 0.5) * 0.35;
			for (int side = -1; side <= 1; side += 2) {
				for (int i = 1; i <= 7; i++) {
					double out = 0.3 + i * 0.28;
					Vec3 w = chest.add(right.scale(side * out)).add(0, Math.sin(i / 7.0 * Math.PI) * 0.45 + flap * i / 7.0, 0);
					AuraFx.particles(level, i % 2 == 0 ? ParticleTypes.FLAME : AuraFx.dust(0xFFD040, 1.2f), w.x, w.y, w.z, 1, 0, 0, 0, 0);
				}
			}
			if (run.t % 6 == 0) sound(level, chest, SoundEvents.BLAZE_BURN, 0.6f, 1.2f);
			// burning whoever you pass
			for (LivingEntity e : Handhelds.enemies(p, level, chest, 2.5)) {
				Integer last = run.lastTouch.get(e);
				if (last != null && run.t - last < 10) continue;
				run.lastTouch.put(e, run.t);
				Handhelds.hurt(p, e, AuraConfig.ULT_PHOENIX_TOUCH_DAMAGE);
				e.setRemainingFireTicks(100);
			}
			return false;
		}
		// the dive
		move(p, new Vec3(run.dir.x * 0.3, -1.6, run.dir.z * 0.3));
		AuraFx.particles(level, ParticleTypes.FLAME, chest.x, chest.y + 0.5, chest.z, 10, 0.3, 0.6, 0.3, 0.05);
		if (p.onGround() || p.isInWater() || run.t - run.phaseAt > 40) {
			Vec3 at = p.position();
			double radius = Math.max(1, AuraConfig.ULT_PHOENIX_BLAST_RADIUS);
			AuraFx.particles(level, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
			AuraFx.burst(level, at.x, at.y + 0.5, at.z, ParticleTypes.FLAME, 45, radius * 0.3, 0.22);
			AuraFx.burst(level, at.x, at.y + 0.5, at.z, ParticleTypes.LAVA, 12, 1.0, 0);
			for (int k = 1; k <= 3; k++) AuraFx.ring(level, at.x, at.y + 0.2, at.z, radius * k / 3, ParticleTypes.FLAME, (int) (radius * k * 4));
			for (LivingEntity e : Handhelds.enemies(p, level, at, radius)) {
				double d = AuraFx.chest(e).distanceTo(at);
				Handhelds.hurt(p, e, (float) (AuraConfig.ULT_PHOENIX_BLAST_DAMAGE * (1 - 0.5 * Math.min(1, d / radius))));
				e.setRemainingFireTicks(120);
				Vec3 out = flat(e.position().subtract(at));
				e.setDeltaMovement(e.getDeltaMovement().add(out.x * 0.8, 0.5, out.z * 0.8));
				e.hurtMarked = true;
			}
			sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), 1.5f, 0.9f);
			sound(level, at, SoundEvents.FIRECHARGE_USE, 1.2f, 0.5f);
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------
	// Wind - Tempest
	// ---------------------------------------------------------------

	private static void startTempest(ServerPlayer p, Run run) {
		p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 25, 1, false, false, false));
		sound(p.serverLevel(), p.position(), SoundEvents.BREEZE_SHOOT, 1.4f, 0.6f);
		sound(p.serverLevel(), p.position(), SoundEvents.ELYTRA_FLYING, 0.6f, 1.4f);
	}

	private static boolean tempest(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		int ticks = Math.max(20, AuraConfig.ULT_TEMPEST_TICKS);
		if (run.t == 25) p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, ticks + 60, 0, false, false, false));
		if (run.point == null || run.t % 5 == 0) run.point = ground(level, p.position(), p);
		Vec3 base = new Vec3(p.getX(), run.point.y, p.getZ());
		double top = Math.max(p.getY() + 3, base.y + 6);
		// the funnel: rings widening as they rise, turning - wide enough at the bottom to see you in the eye
		for (int layer = 0; layer <= 10; layer++) {
			double h = base.y + (top - base.y) * layer / 10.0, r = 1.4 + layer * 0.35;
			for (int k = 0; k < 2; k++) {
				double a = run.t * 0.55 + layer * 0.4 + k * Math.PI;
				AuraFx.particles(level, AuraFx.dust(layer % 2 == 0 ? 0xDDF3FF : 0xB8C4CC, 1.0f), base.x + Math.cos(a) * r, h, base.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
			}
			if ((run.t + layer) % 5 == 0) {
				double a = run.t * 0.55 + layer;
				AuraFx.particles(level, ParticleTypes.CLOUD, base.x + Math.cos(a) * r, h, base.z + Math.sin(a) * r, 1, 0, 0, 0, 0.01);
			}
		}
		double radius = Math.max(2, AuraConfig.ULT_TEMPEST_RADIUS);
		if (run.t < ticks) {
			for (LivingEntity e : Handhelds.enemies(p, level, base.add(0, 2, 0), radius)) {
				Vec3 to = new Vec3(base.x - e.getX(), 0, base.z - e.getZ());
				double d = Math.max(0.5, to.length());
				Vec3 in = to.scale(1 / d);
				Vec3 swirl = new Vec3(-in.z, 0, in.x);
				Vec3 v = e.getDeltaMovement().scale(0.6).add(in.scale(0.12)).add(swirl.scale(0.22)).add(0, e.getY() < top - 1 ? 0.09 : 0.0, 0);
				e.setDeltaMovement(v);
				e.hurtMarked = true;
				e.fallDistance = 0;
				if (run.t % 20 == 0) Handhelds.hurt(p, e, AuraConfig.ULT_TEMPEST_DAMAGE);
				run.hit.add(e);
			}
			if (run.t % 20 == 0) sound(level, base, SoundEvents.ELYTRA_FLYING, 0.5f, 1.5f);
			return false;
		}
		// the end: everything flung away
		for (LivingEntity e : Handhelds.enemies(p, level, base.add(0, 2, 0), radius + 2)) {
			Vec3 out = flat(e.position().subtract(base));
			e.setDeltaMovement(out.x * 1.4, 0.9, out.z * 1.4);
			e.hurtMarked = true;
			Handhelds.hurt(p, e, AuraConfig.ULT_TEMPEST_FLING_DAMAGE);
		}
		AuraFx.burst(level, base.x, base.y + 2, base.z, ParticleTypes.CLOUD, 60, 2.0, 0.3);
		sound(level, base, SoundEvents.WIND_CHARGE_BURST.value(), 1.6f, 0.7f);
		return true;
	}

	// ---------------------------------------------------------------
	// Frost - Permafrost
	// ---------------------------------------------------------------

	private static void startPermafrost(ServerPlayer p, Run run) {
		run.point = p.position();
		sound(p.serverLevel(), run.point, SoundEvents.PLAYER_HURT_FREEZE, 1.2f, 0.6f);
		sound(p.serverLevel(), run.point, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.5f, 0.5f);
	}

	private static boolean permafrost(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		double radius = Math.max(2, AuraConfig.ULT_PERMAFROST_RADIUS);
		int freeze = Math.max(20, AuraConfig.ULT_PERMAFROST_FREEZE_TICKS);
		Vec3 c = run.point;
		if (run.t <= 10) {
			double r = radius * run.t / 10.0;
			AuraFx.ring(level, c.x, c.y + 0.15, c.z, r, ParticleTypes.SNOWFLAKE, (int) (r * 6) + 4);
			AuraFx.ring(level, c.x, c.y + 0.1, c.z, r, AuraFx.dust(0xBDEBFF, 1.6f), (int) (r * 5) + 4);
		}
		if (run.t == 4) {
			// the spikes, hidden for now
			var random = p.getRandom();
			for (int i = 0; i < 16; i++) {
				double a = random.nextDouble() * Math.PI * 2, d = 2 + random.nextDouble() * (radius - 2);
				Vec3 at = ground(level, c.add(Math.cos(a) * d, 1.5, Math.sin(a) * d), p);
				Vec3 lean = new Vec3(Math.cos(a), 0, Math.sin(a)).scale(0.25 + random.nextDouble() * 0.3).add(0, 1, 0);
				float w = 0.45f + random.nextFloat() * 0.4f, h = 1.4f + random.nextFloat() * 2.2f;
				BlockState block = random.nextInt(3) == 0 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
				spawnDisplay(level, run, at, block, spike(Vec3.ZERO, lean, w, 0.001f), spike(Vec3.ZERO, lean, w, h));
			}
		}
		if (run.t == 6) grow(run, 5);
		if (run.t == 10) {
			for (LivingEntity e : Handhelds.enemies(p, level, c.add(0, 1, 0), radius)) {
				IceSpike.freeze(e, run.started + run.t, freeze);
				Handhelds.hurt(p, e, 2f);
				run.targets.add(e);
			}
			sound(level, c, SoundEvents.GLASS_PLACE, 1.5f, 0.6f);
		}
		if (run.t > 10 && run.t < 10 + freeze && run.t % 3 == 0) {
			var random = p.getRandom();
			for (int i = 0; i < 4; i++) {
				double a = random.nextDouble() * Math.PI * 2, d = random.nextDouble() * radius;
				AuraFx.particles(level, ParticleTypes.SNOWFLAKE, c.x + Math.cos(a) * d, c.y + 0.3 + random.nextDouble() * 2, c.z + Math.sin(a) * d, 1, 0, 0, 0, 0.01);
			}
		}
		if (run.t == 10 + freeze) {
			for (LivingEntity e : run.targets) {
				if (!e.isAlive() || e.distanceToSqr(c) > radius * radius * 2) continue;
				Handhelds.hurt(p, e, AuraConfig.ULT_PERMAFROST_SHATTER_DAMAGE);
				Vec3 ec = AuraFx.chest(e);
				AuraFx.burst(level, ec.x, ec.y, ec.z, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()), 30, 0.5, 0.2);
			}
			for (Display.BlockDisplay d : run.displays) {
				AuraFx.burst(level, d.getX(), d.getY() + 1, d.getZ(), new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()), 14, 0.3, 0.15);
			}
			sound(level, c, SoundEvents.GLASS_BREAK, 1.6f, 0.6f);
			sound(level, c, SoundEvents.GLASS_BREAK, 1.2f, 0.9f);
			return true;
		}
		return false;
	}

	/** An ice spike from {@code base}, leaning along {@code dir}, {@code w} thick and {@code h} tall. */
	private static Transformation spike(Vec3 base, Vec3 dir, float w, float h) {
		Vec3 n = dir.normalize();
		Quaternionf rot = new Quaternionf().rotationTo(0f, 1f, 0f, (float) n.x, (float) n.y, (float) n.z);
		Vector3f offset = rot.transform(new Vector3f(w / 2f, 0f, w / 2f));
		return new Transformation(new Vector3f((float) base.x, (float) base.y - 0.2f, (float) base.z).sub(offset), rot, new Vector3f(w, h, w), null);
	}

	// ---------------------------------------------------------------
	// Verdant - Overgrowth
	// ---------------------------------------------------------------

	private static void startOvergrowth(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		Vec3 back = flat(p.getLookAngle()).scale(-2.5);
		run.point = ground(level, p.position().add(back).add(0, 1, 0), p);
		BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState(), leaves = Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState(),
				leaves2 = Blocks.AZALEA_LEAVES.defaultBlockState();
		// the trunk and three branches, then the crown
		spawnDisplay(level, run, run.point, log, box(0, 0, 0, 0.001f, 0.001f), box(0, 0, 0, 1.3f, 7.5f));
		for (int i = 0; i < 3; i++) {
			double a = i * Math.PI * 2 / 3 + 0.4;
			Vec3 dir = new Vec3(Math.cos(a), 0.9, Math.sin(a));
			spawnDisplay(level, run, run.point, log, spike(new Vec3(0, 4.5, 0), dir, 0.001f, 0.001f), spike(new Vec3(0, 4.5, 0), dir, 0.6f, 3.0f));
		}
		double[][] crown = {{0, 7.5, 0, 4.2}, {1.8, 6.6, 0.6, 3.0}, {-1.6, 6.8, -0.9, 3.0}, {0.4, 6.4, -2.0, 2.8}, {-0.8, 6.2, 1.9, 2.8}, {0, 9.2, 0, 2.6}};
		for (int i = 0; i < crown.length; i++) {
			double[] b = crown[i];
			float s = (float) b[3];
			spawnDisplay(level, run, run.point, i % 2 == 0 ? leaves : leaves2, box(b[0], b[1], b[2], 0.001f, 0.001f), box(b[0], b[1] - s / 2, b[2], s, s));
		}
		sound(level, run.point, SoundEvents.ROOTED_DIRT_BREAK, 1.4f, 0.5f);
		sound(level, run.point, SoundEvents.BONE_MEAL_USE, 1.2f, 0.8f);
	}

	/** A box {@code w} wide and {@code h} tall standing on (x, y, z) relative to the display. */
	private static Transformation box(double x, double y, double z, float w, float h) {
		return new Transformation(new Vector3f((float) x - w / 2f, (float) y, (float) z - w / 2f), null, new Vector3f(w, h, w), null);
	}

	private static boolean overgrowth(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		int ticks = Math.max(40, AuraConfig.ULT_OVERGROWTH_TICKS), rootTicks = Math.max(20, AuraConfig.ULT_OVERGROWTH_ROOT_TICKS);
		Vec3 base = run.point;
		if (run.t == 1) grow(run, 25);
		if (run.t == 8) {
			for (LivingEntity e : Handhelds.enemies(p, level, AuraFx.chest(p), Math.max(2, AuraConfig.ULT_OVERGROWTH_RADIUS))) {
				Rooted.lock(e, e.position(), false);
				run.hit.add(e);
			}
			sound(level, base, SoundEvents.ROOTED_DIRT_PLACE, 1.4f, 0.6f);
		}
		if (run.t >= 8 && run.t <= 8 + rootTicks && run.t % 10 == 0) {
			for (LivingEntity e : run.hit) {
				if (!e.isAlive() || !Rooted.isRooted(e)) continue;
				AuraFx.line(level, base.add(0, 0.3, 0), e.position().add(0, 0.3, 0), AuraFx.dust(0x3FA34D, 1.3f), 18);
				AuraFx.particles(level, ParticleTypes.HAPPY_VILLAGER, e.getX(), e.getY() + 0.3, e.getZ(), 4, 0.4, 0.2, 0.4, 0);
				if (run.t % 20 == 0) Handhelds.hurt(p, e, AuraConfig.ULT_OVERGROWTH_THORN_DAMAGE);
			}
		}
		if (run.t == 8 + rootTicks) {
			for (LivingEntity e : run.hit) if (Rooted.isRooted(e)) Rooted.unlock(e);
		}
		// petals falling from the crown; they heal you near the tree
		if (run.t > 20 && run.t < ticks) {
			var random = p.getRandom();
			for (int i = 0; i < 4; i++) {
				double a = random.nextDouble() * Math.PI * 2, d = random.nextDouble() * 3.5;
				AuraFx.particles(level, ParticleTypes.CHERRY_LEAVES, base.x + Math.cos(a) * d, base.y + 6 + random.nextDouble() * 2, base.z + Math.sin(a) * d, 1, 0, 0, 0, 0);
			}
			if (run.t % 20 == 0 && p.position().distanceToSqr(base) < 100) {
				p.heal(Math.max(0, AuraConfig.ULT_OVERGROWTH_HEAL));
				AuraFx.particles(level, ParticleTypes.HEART, p.getX(), p.getY() + 2.1, p.getZ(), 1, 0.2, 0.1, 0.2, 0);
			}
		}
		if (run.t == ticks) {
			wither(run, 20);
			sound(level, base, SoundEvents.AZALEA_LEAVES_BREAK, 1.4f, 0.6f);
		}
		if (run.t > ticks && run.t % 3 == 0) {
			AuraFx.particles(level, ParticleTypes.FALLING_SPORE_BLOSSOM, base.x, base.y + 6, base.z, 6, 2.0, 1.0, 2.0, 0);
		}
		return run.t >= ticks + 20;
	}

	// ---------------------------------------------------------------
	// Thunder - Judgement
	// ---------------------------------------------------------------

	private static void startJudgement(ServerPlayer p, Run run) {
		hold(p);
		sound(p.serverLevel(), p.position(), SoundEvents.TRIDENT_THUNDER.value(), 1.0f, 0.7f);
		AuraFx.burst(p.serverLevel(), p.getX(), p.getY() + 0.2, p.getZ(), ParticleTypes.ELECTRIC_SPARK, 40, 1.0, 0.3);
	}

	private static boolean judgement(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		int bolts = Math.max(1, AuraConfig.ULT_JUDGEMENT_BOLTS);
		int finalAt = 12 + 4 * bolts + 6;
		if (run.t <= 8) move(p, new Vec3(0, 0.5 * (1 - run.t / 9.0), 0));
		else if (run.t < finalAt + 6) move(p, Vec3.ZERO);
		Vec3 chest = AuraFx.chest(p);
		AuraFx.particles(level, ParticleTypes.ELECTRIC_SPARK, chest.x, chest.y, chest.z, 3, 0.5, 0.7, 0.5, 0.05);
		AuraFx.ring(level, p.getX(), p.getY() - 0.2, p.getZ(), 1.2, AuraFx.dust(0xFFE347, 1.2f), 10);
		if (run.t >= 12 && run.t < 12 + 4 * bolts && (run.t - 12) % 4 == 0) {
			List<LivingEntity> enemies = Handhelds.enemies(p, level, chest, Math.max(4, AuraConfig.ULT_JUDGEMENT_RANGE));
			// spread the bolts: someone not struck yet if there is one, else anyone in range
			List<LivingEntity> fresh = enemies.stream().filter(e -> !run.hit.contains(e)).toList();
			if (!fresh.isEmpty()) enemies = fresh;
			Vec3 spot;
			LivingEntity target = null;
			if (!enemies.isEmpty()) {
				target = enemies.get(p.getRandom().nextInt(enemies.size()));
				spot = target.position();
			} else {
				double a = p.getRandom().nextDouble() * Math.PI * 2, d = 3 + p.getRandom().nextDouble() * 6;
				spot = ground(level, p.position().add(Math.cos(a) * d, 0, Math.sin(a) * d), p);
			}
			lightning(level, spot);
			Thunderbolt.bolt(level, chest, spot.add(0, 1, 0), 0.4f, 2, 2);
			Thunderbolt.impact(level, spot.add(0, 0.5, 0), 0.8f);
			if (target != null) {
				run.hit.add(target);
				Handhelds.hurt(p, target, AuraConfig.ULT_JUDGEMENT_BOLT_DAMAGE);
			}
			for (LivingEntity e : Handhelds.enemies(p, level, spot.add(0, 1, 0), 1.6)) {
				if (e != target) Handhelds.hurt(p, e, AuraConfig.ULT_JUDGEMENT_BOLT_DAMAGE * 0.5f);
			}
		}
		if (run.t == finalAt) {
			Vec3 eye = p.getEyePosition();
			HitResult aim = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(40)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			Vec3 spot = aim.getType() == HitResult.Type.MISS ? ground(level, eye.add(p.getLookAngle().scale(12)), p) : aim.getLocation();
			for (int k = 0; k < 3; k++) lightning(level, spot.add((k - 1) * 0.8, 0, (k % 2) * 0.8 - 0.4));
			Thunderbolt.bolt(level, spot.add(0, 30, 0), spot, 1.2f, 5, 4);
			Thunderbolt.impact(level, spot.add(0, 0.5, 0), 2.0f);
			double radius = Math.max(1, AuraConfig.ULT_JUDGEMENT_FINAL_RADIUS);
			for (LivingEntity e : Handhelds.enemies(p, level, spot.add(0, 1, 0), radius)) {
				Handhelds.hurt(p, e, AuraConfig.ULT_JUDGEMENT_FINAL_DAMAGE);
				Vec3 out = flat(e.position().subtract(spot));
				e.setDeltaMovement(e.getDeltaMovement().add(out.x * 0.6, 0.8, out.z * 0.6));
				e.hurtMarked = true;
			}
			sound(level, spot, SoundEvents.LIGHTNING_BOLT_THUNDER, 3.0f, 0.7f);
			sound(level, spot, SoundEvents.LIGHTNING_BOLT_IMPACT, 2.0f, 0.8f);
		}
		return run.t >= finalAt + 6;
	}

	// ---------------------------------------------------------------
	// Earth - Tectonic Rift
	// ---------------------------------------------------------------

	private static final BlockState[] RIFT_ROCKS = {Blocks.STONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(), Blocks.TUFF.defaultBlockState(),
			Blocks.COBBLED_DEEPSLATE.defaultBlockState()};

	private static BlockState surface(ServerLevel level, Vec3 at) {
		return Earth.rock(level.getBlockState(net.minecraft.core.BlockPos.containing(at.x, at.y - 0.5, at.z)));
	}

	private static void startRift(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		run.dir = flat(p.getLookAngle());
		Earth.dust(level, ground(level, p.position(), p), surface(level, ground(level, p.position(), p)), 30, 0.7);
		// the crack opens a step in front of you, not under your feet
		run.point = ground(level, p.position().add(run.dir.scale(1.2)).add(0, 1, 0), p);
		sound(level, run.point, SoundEvents.WARDEN_EMERGE, 1.3f, 0.9f);
	}

	private static boolean rift(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		double length = Math.max(4, AuraConfig.EARTH_RIFT_LENGTH), width = Math.max(0.5, AuraConfig.EARTH_RIFT_WIDTH), speed = 1.5;
		int start = 8, crackEnd = start + (int) Math.ceil(length / speed), crumbleAt = crackEnd + 40;
		// the spikes spawned last tick rise now (a display needs to be there a tick before it can ease)
		for (int i = run.struck; i < run.displays.size(); i++) set(run.displays.get(i), run.shown.get(i), 2);
		run.struck = run.displays.size();
		if (run.t == start) {
			sound(level, run.point, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), 1.8f, 0.5f);
			sound(level, run.point, SoundEvents.GENERIC_EXPLODE.value(), 0.8f, 0.5f);
			AuraFx.particles(level, ParticleTypes.EXPLOSION, run.point.x, run.point.y + 0.3, run.point.z, 1, 0, 0, 0, 0);
		}
		if (run.t >= start && run.t <= crackEnd) {
			double d0 = Math.max(0, (run.t - start - 1) * speed), d1 = Math.min(length, (run.t - start) * speed);
			Vec3 side = new Vec3(-run.dir.z, 0, run.dir.x);
			var random = p.getRandom();
			// the crack itself, spitting dirt
			for (double d = d0; d <= d1; d += 0.5) {
				Vec3 at = ground(level, run.point.add(run.dir.scale(d)).add(0, 2, 0), p);
				BlockState rock = surface(level, at);
				AuraFx.particles(level, new BlockParticleOption(ParticleTypes.DUST_PILLAR, rock), at.x, at.y + 0.1, at.z, 2, 0.15, 0, 0.15, 0.05);
				AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, rock), at.x, at.y + 0.2, at.z, 3, 0.3, 0.1, 0.3, 0.1);
			}
			// jagged spikes either side of it, leaning out; a cluster of big ones at the far end
			Vec3 front = run.point.add(run.dir.scale(d1));
			boolean last = run.t == crackEnd;
			for (int k = 0; k < (last ? 6 : 2); k++) {
				double s = (k % 2 == 0 ? 1 : -1) * (0.45 + random.nextDouble() * (last ? 1.2 : 0.7));
				Vec3 at = ground(level, front.add(side.scale(s)).add(run.dir.scale(random.nextDouble() * (last ? 1.6 : 0.8) - 0.4)).add(0, 2, 0), p);
				BlockState rock = random.nextInt(3) == 0 ? surface(level, at) : RIFT_ROCKS[random.nextInt(RIFT_ROCKS.length)];
				Vec3 lean = side.scale(Math.signum(s) * (0.2 + random.nextDouble() * 0.35)).add(run.dir.scale(random.nextDouble() * 0.3 - 0.1)).add(0, 1, 0);
				float w = 0.6f + random.nextFloat() * 0.5f, h = (last ? 3.2f : 1.6f) + random.nextFloat() * (last ? 2.2f : 1.6f);
				spawnDisplay(level, run, at, rock, spike(Vec3.ZERO, lean, w, 0.001f), spike(Vec3.ZERO, lean, w, h));
			}
			// whoever the crack runs under goes up
			for (LivingEntity e : Handhelds.enemies(p, level, front.add(0, 1, 0), width + speed + 2)) {
				if (run.hit.contains(e)) continue;
				Vec3 rel = e.position().subtract(run.point);
				double along = rel.dot(run.dir), across = rel.dot(side);
				if (along < d0 - 1 || along > d1 + 0.6 || Math.abs(across) > width || Math.abs(rel.y) > 3) continue;
				run.hit.add(e);
				Handhelds.hurt(p, e, AuraConfig.EARTH_RIFT_DAMAGE);
				Vec3 out = side.scale(Math.signum(across) * 0.3);
				e.setDeltaMovement(e.getDeltaMovement().scale(0.3).add(out.x, AuraConfig.EARTH_RIFT_LAUNCH, out.z));
				e.hurtMarked = true;
			}
			sound(level, front, AuraSounds.MACE_SMASH_GROUND.value(), 1.0f, 0.55f + random.nextFloat() * 0.15f);
			if (run.t % 2 == 0) sound(level, front, SoundEvents.DRIPSTONE_BLOCK_BREAK, 1.2f, 0.5f);
			if (last) {
				AuraFx.particles(level, ParticleTypes.EXPLOSION_EMITTER, front.x, front.y + 0.5, front.z, 1, 0, 0, 0, 0);
				sound(level, front, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), 2.0f, 0.6f);
				sound(level, front, SoundEvents.GENERIC_EXPLODE.value(), 1.0f, 0.7f);
			}
		}
		if (run.t == crumbleAt) {
			wither(run, 14);
			for (Display.BlockDisplay d : run.displays) {
				AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, ((BlockDisplayAccessor) d).aurapoints$getBlockState()), d.getX(), d.getY() + 1, d.getZ(), 8, 0.3, 0.6, 0.3, 0.05);
			}
			sound(level, run.point.add(run.dir.scale(length / 2)), SoundEvents.GRAVEL_BREAK, 1.6f, 0.5f);
		}
		return run.t >= crumbleAt + 15;
	}

	// ---------------------------------------------------------------
	// Light - Sunburst
	// ---------------------------------------------------------------

	private static final int SUN_RISE = 20;

	/** The sun at {@code t}: a cube of {@code size} turned about two axes, {@code lift} blocks over where it started. */
	private static Transformation sunCube(float size, float lift, float angle) {
		Quaternionf rot = new Quaternionf().rotateY(angle).rotateX(angle * 0.6f + 0.6f);
		return Props.cube(new Vector3f(0, lift, 0), size, rot);
	}

	private static float sunLift(int t) {
		double e = Math.min(1, t / (double) SUN_RISE);
		return (float) (5.5 * e * e * (3 - 2 * e));
	}

	private static void startSunburst(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		run.point = p.position().add(0, 2.4, 0);
		// two glowing cubes turned against each other: their corners make the rays
		spawnDisplay(level, run, run.point, Blocks.OCHRE_FROGLIGHT.defaultBlockState(), sunCube(0.001f, 0, 0), sunCube(1.35f, 0, 0));
		spawnDisplay(level, run, run.point, Blocks.SHROOMLIGHT.defaultBlockState(), sunCube(0.001f, 0, 0), sunCube(1.2f, 0, 0.8f));
		for (Display.BlockDisplay d : run.displays) ((DisplayAccessor) d).aurapoints$setBrightnessOverride(new Brightness(15, 15));
		sound(level, run.point, SoundEvents.BEACON_ACTIVATE, 1.5f, 0.8f);
		sound(level, run.point, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.5f, 0.6f);
	}

	private static boolean sunburst(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		int burn = Math.max(20, AuraConfig.LIGHT_SUN_TICKS), burstAt = SUN_RISE + burn;
		float lift = sunLift(run.t);
		Vec3 sun = run.point.add(0, lift, 0);
		// the sun rises and turns, swelling a little
		if (run.t % 5 == 1 && run.t < burstAt) {
			float angle = run.t * 0.07f, swell = 1f + 0.08f * Mth.sin(run.t * 0.3f);
			set(run.displays.get(0), sunCube(1.35f * swell, sunLift(run.t + 5), angle + 0.35f), 5);
			set(run.displays.get(1), sunCube(1.2f * swell, sunLift(run.t + 5), -angle + 0.45f), 5);
		}
		var random = p.getRandom();
		if (run.t < burstAt) {
			AuraFx.particles(level, ParticleTypes.END_ROD, sun.x, sun.y, sun.z, 2, 0.7, 0.7, 0.7, 0.03);
			if (run.t % 4 == 0) AuraFx.ring(level, sun.x, sun.y, sun.z, 1.7, AuraFx.dust(Light.GOLD, 1.2f), 14);
			// rays down onto the ground round it
			if (run.t % 3 == 0) {
				double a = random.nextDouble() * Math.PI * 2, d = 1 + random.nextDouble() * 6;
				Vec3 g = ground(level, new Vec3(sun.x + Math.cos(a) * d, sun.y, sun.z + Math.sin(a) * d), p);
				AuraFx.line(level, sun, g, AuraFx.dust(Light.WHITE_GOLD, 0.8f), 10);
			}
		}
		// lances of light at the enemies around
		int every = Math.max(2, AuraConfig.LIGHT_SUN_LANCE_TICKS);
		if (run.t > SUN_RISE && run.t < burstAt && (run.t - SUN_RISE) % every == 0) {
			List<LivingEntity> foes = Handhelds.enemies(p, level, sun, Math.max(4, AuraConfig.LIGHT_SUN_RANGE));
			foes.removeIf(e -> Light.friend(p, e) || level.clip(new ClipContext(sun, AuraFx.chest(e), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS);
			if (!foes.isEmpty()) {
				// the ones it hit least first
				foes.sort((a, b) -> Integer.compare(run.lastTouch.getOrDefault(a, 0), run.lastTouch.getOrDefault(b, 0)));
				LivingEntity target = foes.get(0);
				run.lastTouch.merge(target, 1, Integer::sum);
				Vec3 to = AuraFx.chest(target).subtract(sun);
				var lance = Props.spawn(level, sun, Blocks.WHITE_CONCRETE.defaultBlockState(), Props.along(Vec3.ZERO, to, 0.18f, (float) to.length()), 5, 0);
				Props.glow(lance);
				var halo = Props.spawn(level, sun, Blocks.YELLOW_STAINED_GLASS.defaultBlockState(), Props.along(Vec3.ZERO, to, 0.4f, (float) to.length()), 4, 0);
				Props.glow(halo);
				Handhelds.hurt(p, target, AuraConfig.LIGHT_SUN_LANCE_DAMAGE * (Light.undead(target) ? (float) Math.max(1, AuraConfig.LIGHT_UNDEAD_MULTIPLIER) : 1f));
				if (Light.undead(target)) target.igniteForSeconds(3);
				Vec3 c = AuraFx.chest(target);
				AuraFx.particles(level, ParticleTypes.END_ROD, c.x, c.y, c.z, 12, 0.2, 0.3, 0.2, 0.12);
				AuraFx.particles(level, AuraFx.dust(Light.GOLD, 1.4f), c.x, c.y, c.z, 10, 0.3, 0.4, 0.3, 0);
				sound(level, c, SoundEvents.AMETHYST_BLOCK_BREAK, 1.2f, 1.5f);
				sound(level, c, SoundEvents.FIREWORK_ROCKET_BLAST, 0.7f, 1.8f);
			}
		}
		// warmth for the friends near it
		if (run.t > SUN_RISE && run.t < burstAt && run.t % 20 == 0) {
			for (ServerPlayer other : level.players()) {
				if (!Light.friend(p, other) || other.distanceToSqr(sun.x, sun.y - lift, sun.z) > 12 * 12 || other.getHealth() >= other.getMaxHealth()) continue;
				other.heal(Math.max(0, AuraConfig.LIGHT_SUN_HEAL));
				AuraFx.particles(level, ParticleTypes.HEART, other.getX(), other.getY() + 2.1, other.getZ(), 1, 0.2, 0.1, 0.2, 0);
			}
		}
		if (run.t == burstAt - 6) {
			for (int i = 0; i < 2; i++) set(run.displays.get(i), sunCube(i == 0 ? 2.3f : 2.1f, lift, i == 0 ? run.t * 0.07f : -run.t * 0.07f + 0.8f), 6);
			sound(level, sun, SoundEvents.BEACON_POWER_SELECT, 1.6f, 1.4f);
		}
		if (run.t == burstAt) {
			// it bursts: light everywhere, the enemies near it blinded and burnt
			for (Display.BlockDisplay d : run.displays) {
				LIVE.remove(d.getUUID());
				d.discard();
			}
			run.displays.clear();
			run.shown.clear();
			run.hidden.clear();
			AuraFx.particles(level, ParticleTypes.FLASH, sun.x, sun.y, sun.z, 1, 0, 0, 0, 0);
			AuraFx.burst(level, sun.x, sun.y, sun.z, ParticleTypes.END_ROD, 80, 1.5, 0.35);
			AuraFx.burst(level, sun.x, sun.y, sun.z, AuraFx.dust(Light.GOLD, 1.8f), 60, 2.5, 0);
			double r = Math.max(1, AuraConfig.LIGHT_SUN_BURST_RADIUS);
			Vec3 below = new Vec3(sun.x, sun.y - lift, sun.z);
			for (LivingEntity e : Handhelds.enemies(p, level, below.add(0, 1, 0), r)) {
				if (Light.friend(p, e)) continue;
				Handhelds.hurt(p, e, AuraConfig.LIGHT_SUN_BURST_DAMAGE);
				e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0), p);
				if (Light.undead(e)) e.igniteForSeconds(4);
			}
			sound(level, sun, SoundEvents.GENERIC_EXPLODE.value(), 1.0f, 1.6f);
			sound(level, sun, SoundEvents.AMETHYST_BLOCK_RESONATE, 2.0f, 1.8f);
			sound(level, sun, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.5f, 1.0f);
		}
		return run.t >= burstAt + 4;
	}

	// ---------------------------------------------------------------
	// Blood - Blood Moon
	// ---------------------------------------------------------------

	private static void startBloodMoon(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		int ticks = Math.max(40, AuraConfig.BLOOD_MOON_TICKS);
		Blood.MOON.put(p.getUUID(), (long) p.getServer().getTickCount() + ticks);
		if (!AuraAbilities.cooldownsDisabled(p)) Blood.spendBlood(p, Blood.moonCost());
		// the sky goes red for everyone near
		double range = Math.max(8, AuraConfig.BLOOD_MOON_SKY_RANGE);
		for (ServerPlayer other : level.players()) {
			if (other.distanceToSqr(p) <= range * range) NetworkHandler.sendFx(other, com.toast.aurapoints.network.FxPacket.BLOOD_MOON, ticks, "");
		}
		Vec3 c = AuraFx.chest(p);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(Blood.BRIGHT, 1.6f), 50, 1.2, 0);
		sound(level, c, SoundEvents.WITHER_SPAWN, 0.6f, 0.6f);
		sound(level, c, SoundEvents.WARDEN_HEARTBEAT, 2.0f, 0.7f);
		sound(level, c, SoundEvents.WOLF_HOWL, 1.2f, 0.7f);
	}

	private static boolean bloodMoon(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		int ticks = Math.max(40, AuraConfig.BLOOD_MOON_TICKS);
		var random = p.getRandom();
		// blood mist rising round you, a heartbeat every second
		if (run.t % 2 == 0 && !p.isInvisible()) {
			double a = random.nextDouble() * Math.PI * 2, d = 0.6 + random.nextDouble() * 1.2;
			AuraFx.particles(level, AuraFx.dust(run.t % 4 == 0 ? Blood.CRIMSON : Blood.BRIGHT, 1.1f), p.getX() + Math.cos(a) * d, p.getY() + 0.2 + random.nextDouble() * 1.6,
					p.getZ() + Math.sin(a) * d, 1, 0, 0.05, 0, 0);
		}
		if (run.t % 20 == 0) {
			sound(level, p.position(), SoundEvents.WARDEN_HEARTBEAT, 1.2f, 0.8f);
			Blood.moonBleed(p, Math.max(1, AuraConfig.BLOOD_MOON_RADIUS), 40);
		}
		return run.t >= ticks;
	}

	// ---------------------------------------------------------------
	// Void - Singularity
	// ---------------------------------------------------------------

	/** The hole's heart: a black cube tumbling, {@code size} wide. */
	private static Transformation holeCube(float size, float angle, float tilt) {
		return Props.cube(new Vector3f(), size, new Quaternionf().rotateY(angle).rotateX(tilt).rotateZ(angle * 0.5f));
	}

	private static void startSingularity(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		// where the crosshair lands (or as far as it reaches), a little off the ground
		Vec3 aim = Handhelds.aim(p, Math.max(4, AuraConfig.VOID_HOLE_RANGE));
		Vec3 back = aim.subtract(p.getEyePosition());
		if (back.length() > 2) aim = aim.subtract(back.normalize().scale(1.0));
		Vec3 floor = ground(level, aim.add(0, 0.5, 0), p);
		run.point = new Vec3(aim.x, Math.max(aim.y, floor.y + 1.8), aim.z);
		// the black heart (two cubes turned against each other) in a violet haze
		spawnDisplay(level, run, run.point, Blocks.BLACK_CONCRETE.defaultBlockState(), holeCube(0.001f, 0, 0), holeCube(1.7f, 0, 0.5f));
		spawnDisplay(level, run, run.point, Blocks.BLACK_CONCRETE.defaultBlockState(), holeCube(0.001f, 0.8f, 0), holeCube(1.5f, 0.8f, -0.4f));
		spawnDisplay(level, run, run.point, Blocks.PURPLE_STAINED_GLASS.defaultBlockState(), holeCube(0.001f, 0.4f, 0), holeCube(2.7f, 0.4f, 0.2f));
		sound(level, run.point, SoundEvents.WARDEN_SONIC_CHARGE, 1.6f, 0.5f);
		sound(level, run.point, SoundEvents.END_PORTAL_SPAWN, 0.7f, 1.6f);
	}

	private static boolean singularity(ServerPlayer p, Run run) {
		ServerLevel level = p.serverLevel();
		int hold = Math.max(20, AuraConfig.VOID_HOLE_TICKS), collapseAt = 6 + hold;
		Vec3 c = run.point;
		if (run.t == 2) grow(run, 6);
		// it turns, and pulses a little
		if (run.t > 6 && run.t < collapseAt && run.t % 4 == 0) {
			float a = run.t * 0.12f, pulse = 1f + 0.06f * Mth.sin(run.t * 0.4f);
			set(run.displays.get(0), holeCube(1.7f * pulse, a, 0.5f + a * 0.3f), 4);
			set(run.displays.get(1), holeCube(1.5f * pulse, -a + 0.8f, -0.4f - a * 0.2f), 4);
			set(run.displays.get(2), holeCube(2.7f + 0.25f * Mth.sin(run.t * 0.25f), a * 0.5f + 0.4f, 0.2f), 4);
		}
		var random = p.getRandom();
		double radius = Math.max(2, AuraConfig.VOID_HOLE_RADIUS);
		if (run.t > 4 && run.t < collapseAt) {
			// the accretion disc: specks wheeling round and falling in
			for (int i = 0; i < 3; i++) {
				double a = run.t * 0.35 + i * Math.PI * 2 / 3, r = 2.8 + 0.4 * Math.sin(run.t * 0.2 + i);
				AuraFx.particles(level, AuraFx.dust(i == 0 ? VoidAura.VIOLET : 0xC080FF, 1.2f), c.x + Math.cos(a) * r, c.y + Math.sin(a * 2) * 0.15, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
			}
			for (int i = 0; i < 4; i++) {
				double a = random.nextDouble() * Math.PI * 2, r = 2 + random.nextDouble() * (radius - 2), y = (random.nextDouble() - 0.4) * 3;
				Vec3 from = c.add(Math.cos(a) * r, y, Math.sin(a) * r);
				Vec3 in = c.subtract(from).normalize().scale(0.6);
				AuraFx.particles(level, ParticleTypes.REVERSE_PORTAL, from.x, from.y, from.z, 0, in.x, in.y, in.z, 1.0);
			}
			if (run.t % 10 == 0) AuraFx.ring(level, c.x, c.y, c.z, 3.2, ParticleTypes.REVERSE_PORTAL, 24);
			// everyone near is dragged in, harder the closer they are; at its heart they are ground down
			for (LivingEntity e : Handhelds.enemies(p, level, c, radius)) {
				if (!VoidAura.pullable(p, e)) continue;
				Vec3 to = c.subtract(AuraFx.chest(e));
				double d = to.length();
				if (d > 1.2) {
					Vec3 pull = to.normalize().scale(AuraConfig.VOID_HOLE_PULL * (0.5 + 0.5 * (1 - d / radius)));
					e.setDeltaMovement(e.getDeltaMovement().scale(0.6).add(pull.x, pull.y + 0.03, pull.z));
				} else {
					e.setDeltaMovement(e.getDeltaMovement().scale(0.3));
				}
				e.hurtMarked = true;
				e.fallDistance = 0;
				if (d < 2.5 && run.t % 10 == 0) Handhelds.hurt(p, e, AuraConfig.VOID_HOLE_GRIND_DAMAGE);
			}
			if (run.t % 20 == 0) sound(level, c, SoundEvents.PORTAL_AMBIENT, 1.4f, 0.5f);
			if (run.t % 30 == 10) sound(level, c, SoundEvents.WARDEN_HEARTBEAT, 1.5f, 0.5f);
		}
		if (run.t == collapseAt) {
			// it caves in on itself...
			for (int i = 0; i < run.displays.size(); i++) set(run.displays.get(i), holeCube(0.001f, i * 0.8f, 0), 5);
			sound(level, c, SoundEvents.BEACON_DEACTIVATE, 2.0f, 0.5f);
			sound(level, c, SoundEvents.WARDEN_SONIC_CHARGE, 1.6f, 1.4f);
		}
		if (run.t == collapseAt + 6) {
			// ...and blows apart
			for (Display.BlockDisplay d : run.displays) {
				LIVE.remove(d.getUUID());
				d.discard();
			}
			run.displays.clear();
			run.shown.clear();
			run.hidden.clear();
			AuraFx.particles(level, ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
			AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.REVERSE_PORTAL, 120, 2.0, 0.6);
			AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(VoidAura.VIOLET, 2.0f), 60, 3.0, 0);
			AuraFx.particles(level, ParticleTypes.SQUID_INK, c.x, c.y, c.z, 20, 0.6, 0.6, 0.6, 0.15);
			double blast = Math.max(1, AuraConfig.VOID_HOLE_BLAST_RADIUS);
			for (LivingEntity e : Handhelds.enemies(p, level, c, blast)) {
				Handhelds.hurt(p, e, AuraConfig.VOID_HOLE_BLAST_DAMAGE);
				Vec3 out = AuraFx.chest(e).subtract(c);
				out = out.lengthSqr() < 1.0e-4 ? new Vec3(0, 1, 0) : out.normalize();
				e.setDeltaMovement(out.x * 1.4, Math.max(0.6, out.y * 1.4 + 0.5), out.z * 1.4);
				e.hurtMarked = true;
			}
			sound(level, c, SoundEvents.GENERIC_EXPLODE.value(), 2.0f, 0.6f);
			sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 0.8f, 1.6f);
		}
		return run.t >= collapseAt + 8;
	}

	/** A bolt that only looks the part: no fire, no damage of its own. */
	private static void lightning(ServerLevel level, Vec3 at) {
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
		if (bolt == null) return;
		bolt.setVisualOnly(true);
		bolt.moveTo(at.x, at.y, at.z);
		level.addFreshEntity(bolt);
	}

	// ---------------------------------------------------------------
	// Displays (ice spikes, the tree)
	// ---------------------------------------------------------------

	private static void spawnDisplay(ServerLevel level, Run run, Vec3 at, BlockState block, Transformation hiddenT, Transformation shownT) {
		Display.BlockDisplay d = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
		d.setPos(at.x, at.y, at.z);
		((BlockDisplayAccessor) d).aurapoints$setBlockState(block);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setWidth(16f);
		acc.aurapoints$setHeight(16f);
		acc.aurapoints$setViewRange(2.0f);
		acc.aurapoints$setBrightnessOverride(new Brightness(Math.max(8, level.getMaxLocalRawBrightness(net.minecraft.core.BlockPos.containing(at.add(0, 1, 0)))), 15));
		acc.aurapoints$setTransformation(hiddenT);
		d.addTag(TAG);
		LIVE.add(d.getUUID());
		level.addFreshEntity(d);
		run.displays.add(d);
		run.hidden.add(hiddenT);
		run.shown.add(shownT);
	}

	private static void grow(Run run, int ticks) {
		for (int i = 0; i < run.displays.size(); i++) set(run.displays.get(i), run.shown.get(i), ticks);
	}

	private static void wither(Run run, int ticks) {
		for (int i = 0; i < run.displays.size(); i++) set(run.displays.get(i), run.hidden.get(i), ticks);
	}

	private static void set(Display.BlockDisplay d, Transformation t, int interpolate) {
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setTransformation(t);
		acc.aurapoints$setInterpolationDuration(interpolate);
		d.getEntityData().set(DisplayAccessor.aurapoints$startDelta(), 0, true);
	}

	/** An Ultimate's display left over from a crash: removed as it loads. */
	public static boolean isLeftover(Entity e) {
		return e instanceof Display.BlockDisplay && e.getTags().contains(TAG) && !LIVE.contains(e.getUUID());
	}

	// ---------------------------------------------------------------
	// Awakening (/aura awaken - the Awakening quest's reward)
	// ---------------------------------------------------------------

	/** Awakens (or un-awakens) the aura for the player; awakening for the first time plays the ceremony. */
	public static boolean awaken(ServerPlayer player, AuraType aura, boolean on) {
		MinecraftServer server = player.getServer();
		if (server == null || of(aura) == null) return false;
		AuraState state = AuraState.get(server);
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		boolean had = data.awakened.contains(aura);
		if (on) data.awakened.add(aura);
		else data.awakened.remove(aura);
		state.markDirty();
		NetworkHandler.syncOwned(player, data);
		if (on && !had) ceremony(player, aura);
		return on != had;
	}

	/** Lifted into a beam of the aura's light, the Ultimate's name in the sky, then set down again. */
	private static void ceremony(ServerPlayer player, AuraType aura) {
		MinecraftServer server = player.getServer();
		Ability ult = of(aura, Branch.current(player));
		hold(player);
		AuraFx.helix(player, AuraFx.dust(aura.rgb, 1.6f), 60, 1.1, 3.0);
		AuraFx.helix(player, AuraFx.dust(0xFFC83D, 1.3f), 60, 1.5, 3.2);
		sound(player.serverLevel(), player.position(), SoundEvents.BEACON_ACTIVATE, 1.5f, 0.8f);
		for (int t = 0; t <= 70; t++) {
			final int tick = t;
			AuraFx.later(server, t, () -> {
				if (!AuraFx.alive(player)) return;
				ServerLevel level = player.serverLevel();
				if (tick < 30) move(player, new Vec3(0, 0.13, 0));
				else if (tick < 60) move(player, Vec3.ZERO);
				if (tick % 2 == 0) {
					for (int y = 0; y < 24; y += 2) AuraFx.particles(level, ParticleTypes.END_ROD, player.getX(), player.getY() - 3 + y, player.getZ(), 1, 0.15, 0.6, 0.15, 0);
				}
				if (tick == 30) {
					AuraFx.title(player, Component.literal("AWAKENED").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
							Component.empty().append(AuraLabel.of(aura)).append(Component.literal("  " + ult.displayName).withStyle(ChatFormatting.YELLOW)));
					player.sendSystemMessage(Component.literal("✦ Ultimate unlocked: " + ult.displayName + " - press ").withStyle(ChatFormatting.YELLOW)
							.append(Component.keybind("key.aurapoints.ultimate").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
							.append(Component.literal(" while you hold " + aura.displayName + ".").withStyle(ChatFormatting.YELLOW)));
					sound(level, player.position(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
					sound(level, player.position(), SoundEvents.TOTEM_USE, 0.7f, 1.3f);
					AuraFx.burst(level, player.getX(), player.getY() + 1, player.getZ(), ParticleTypes.TOTEM_OF_UNDYING, 60, 0.6, 0.5);
					server.getPlayerList().broadcastSystemMessage(Component.literal("✦ " + player.getGameProfile().getName() + " has awakened ")
							.withStyle(ChatFormatting.GOLD).append(AuraLabel.of(aura)).append(Component.literal(" and unlocked " + ult.displayName + "!")
									.withStyle(ChatFormatting.GOLD)), false);
				}
				if (tick == 60) {
					player.removeTag(FLY_TAG);
					player.setNoGravity(false);
					LANDING.put(player.getUUID(), 0);
				}
			});
		}
	}

	// ---------------------------------------------------------------
	// Lifecycle
	// ---------------------------------------------------------------

	/** A crash mid-Ultimate left NoGravity on: take it off. */
	public static void onLogin(ServerPlayer player) {
		if (player.getTags().contains(FLY_TAG)) {
			player.removeTag(FLY_TAG);
			player.setNoGravity(false);
		}
	}

	public static void onLogout(ServerPlayer player) {
		Run run = RUNS.get(player.getUUID());
		if (run != null) end(player, run);
		LANDING.remove(player.getUUID());
	}

	public static boolean isRunning(ServerPlayer player) {
		return RUNS.containsKey(player.getUUID());
	}

	public static void clearAll(MinecraftServer server) {
		for (Run run : new ArrayList<>(RUNS.values())) end(server.getPlayerList().getPlayer(run.id), run);
		RUNS.clear();
		LANDING.clear();
		LIVE.clear();
	}
}
