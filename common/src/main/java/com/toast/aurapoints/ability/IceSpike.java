package com.toast.aurapoints.ability;

import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.mixin.DisplayAccessor;
import com.toast.aurapoints.mixin.ItemDisplayAccessor;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ice Spike, Frost's active. Hold the key: a spike of ice grows over the right shoulder (arm drawn
 * back like a javelin, ICE_AIM pose) and charges over FULL_CHARGE_TICKS - the HUD charge bar shows
 * it. Let go to throw (it goes by itself after MAX_HOLD_TICKS). The spike is server side only, a
 * short streak of ice particles that flies straight to the crosshair: damage, speed and Slowness grow
 * with the charge; fully charged it freezes the target like powder snow (frost overlay, shaking,
 * freeze damage) for FREEZE_TICKS - or until something hits them, which breaks them free with a short
 * Speed boost. Any spike hit gives the thrower Speed to chase. In memory only.
 */
public final class IceSpike {

	private IceSpike() {
	}

	private static final int ICE_BLUE = 0xA8E4FF;
	private static final int ICE_DEEP = 0x5FB8F0;
	private static final int FLAKE_WHITE = 0xF4FBFF;

	/** player -> tick the charge started */
	private static final Map<UUID, Long> CHARGING = new HashMap<>();

	private static final class Spike {
		final ServerPlayer owner;
		final ServerLevel level;
		final Vec3 dir;
		final double speed;
		final double charge;
		final boolean full;
		Vec3 pos;
		double flown;
		int ticks;
		/** the frost shard model flying with it (null if none) */
		Display.ItemDisplay shard;

		Spike(ServerPlayer owner, Vec3 pos, Vec3 dir, double charge) {
			this.owner = owner;
			this.level = owner.serverLevel();
			this.pos = pos;
			this.dir = dir;
			this.charge = charge;
			this.full = charge >= 1.0 - 1.0e-6;
			this.speed = Math.max(0.2, AuraConfig.ICE_SPIKE_SPEED_MIN + (AuraConfig.ICE_SPIKE_SPEED_MAX - AuraConfig.ICE_SPIKE_SPEED_MIN) * charge);
		}
	}

	private static final List<Spike> SPIKES = new ArrayList<>();

	/** A frozen entity: when it thaws, and the way it faced when it froze (it is held facing that way). */
	private static final class Frozen {
		long until;
		final float yRot, xRot, headRot, bodyRot;

		Frozen(LivingEntity e, long until) {
			this.until = until;
			this.yRot = e.getYRot();
			this.xRot = e.getXRot();
			this.headRot = e.getYHeadRot();
			this.bodyRot = e.yBodyRot;
		}
	}

	private static final Map<LivingEntity, Frozen> FROZEN = new HashMap<>();
	private static final ResourceLocation FROZEN_ID = ResourceLocation.fromNamespaceAndPath("aurapoints", "ice_spike_frozen");

	/** True while the Ice Spike freeze holds this entity (it can't move, look, attack, use items or abilities). */
	public static boolean isFrozen(net.minecraft.world.entity.Entity entity) {
		return entity instanceof LivingEntity living && !FROZEN.isEmpty() && FROZEN.containsKey(living);
	}

	public static boolean isCharging(ServerPlayer player) {
		return CHARGING.containsKey(player.getUUID());
	}

	private static double charge(long since, long now) {
		return Math.max(0.0, Math.min(1.0, (now - since) / (double) Math.max(1, AuraConfig.ICE_SPIKE_FULL_CHARGE_TICKS)));
	}

	/** In the raised right hand of the ICE_AIM pose (arm up past the head, body twisted), turned with the body. */
	private static Vec3 handPos(ServerPlayer player) {
		double yaw = Math.toRadians(player.yBodyRot);
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		return player.position().add(0, (player.isCrouching() ? 1.43 : 1.73), 0).add(right.scale(0.3)).add(forward.scale(-0.05));
	}

	// ---- the frost shard: an item display with the ice_spike_shard model (tip = the model's +Y, 16 px long, centered on the block center) ----

	private static final String TAG = "aurapoints_ice_spike";
	/** shard displays that belong to this run of the server; tagged ones that aren't are leftovers from a crash */
	private static final java.util.Set<UUID> LIVE = new java.util.HashSet<>();
	/** player -> the shard forming in the hand while charging */
	private static final Map<UUID, Display.ItemDisplay> HELD = new HashMap<>();
	private static final Vector3f TIP = new Vector3f(0, 1, 0);

	public static boolean isLeftover(Entity e) {
		return e.getTags().contains(TAG) && !LIVE.contains(e.getUUID());
	}

	/** Shard length in blocks for a charge, the same as the old particle spike. */
	private static float shardLength(double charge) {
		return (float) (0.25 + 0.65 * charge);
	}

	private static Display.ItemDisplay spawnShard(ServerLevel level, Vec3 at) {
		Display.ItemDisplay d = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		d.setPos(at.x, at.y, at.z);
		((ItemDisplayAccessor) d).aurapoints$setItemStack(new ItemStack(com.toast.aurapoints.AuraPoints.ICE_SPIKE_SHARD.get()));
		((ItemDisplayAccessor) d).aurapoints$setItemTransform(ItemDisplayContext.FIXED);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setPosRotInterpolationDuration(1);
		acc.aurapoints$setViewRange(2.0f);
		acc.aurapoints$setBrightnessOverride(new Brightness(15, 15)); // ice that glows a little, even at night
		d.addTag(TAG);
		LIVE.add(d.getUUID());
		level.addFreshEntity(d);
		return d;
	}

	/** Centers the shard on {@code center}, tip along {@code dir}, {@code length} blocks long. */
	private static void poseShard(Display.ItemDisplay d, Vec3 center, Vec3 dir, float length) {
		d.setPos(center.x, center.y, center.z);
		Quaternionf q = new Quaternionf().rotationTo(TIP, new Vector3f((float) dir.x, (float) dir.y, (float) dir.z));
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), q, new Vector3f(length), null));
		acc.aurapoints$setInterpolationDuration(1);
		d.getEntityData().set(DisplayAccessor.aurapoints$startDelta(), 0, true);
	}

	private static void removeShard(Display.ItemDisplay d) {
		if (d == null) return;
		LIVE.remove(d.getUUID());
		d.discard();
	}

	/** Server stopping: no shard outlives its spike. */
	public static void clearAll() {
		HELD.values().forEach(IceSpike::removeShard);
		HELD.clear();
		SPIKES.forEach(s -> removeShard(s.shard));
		SPIKES.clear();
	}

	/** Key pressed: the spike starts forming. */
	public static boolean press(ServerPlayer player, long now) {
		if (CHARGING.containsKey(player.getUUID())) return false;
		if (!AuraAbilities.checkCooldown(player, Ability.ICE_SPIKE, AuraConfig.ICE_SPIKE_COOLDOWN_TICKS, now)) return false;
		CHARGING.put(player.getUUID(), now);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.ICE_AIM);
		NetworkHandler.sendFireBending(player, true, 0f, 1f);
		ServerLevel level = player.serverLevel();
		Vec3 h = handPos(player);
		removeShard(HELD.remove(player.getUUID()));
		Display.ItemDisplay shard = spawnShard(level, h);
		poseShard(shard, h, player.getLookAngle(), shardLength(0));
		HELD.put(player.getUUID(), shard);
		AuraFx.burst(level, h.x, h.y, h.z, ParticleTypes.SNOWFLAKE, 8, 0.2, 0.02);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.POWDER_SNOW_STEP.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.AMETHYST_BLOCK_RESONATE.value(), SoundSource.PLAYERS, 0.5f, 1.6f);
		return true;
	}

	/** Key released: throw it. */
	public static void release(ServerPlayer player, long now) {
		Long since = CHARGING.remove(player.getUUID());
		if (since == null) return;
		double charge = charge(since, now);
		NetworkHandler.sendFireBending(player, false, 0, 0);
		// the arm leaves the held pose (NONE first, so it doesn't come back after) and whips forward
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_ICE_SPIKE, 0);
		AuraAbilities.markUsed(player, Ability.ICE_SPIKE, now);
		AuraStats.add(player, AuraStats.ICE_SPIKE_THROWS, 1);
		int delay = Math.max(0, AuraConfig.ICE_SPIKE_THROW_DELAY_TICKS);
		if (delay == 0) launch(player, charge);
		else AuraFx.later(player.getServer(), delay, () -> {
			if (AuraFx.alive(player) && player.isAlive()) launch(player, charge);
			else removeShard(HELD.remove(player.getUUID()));
		});
	}

	/** Gone without a throw or cooldown (ability lost, death, logout). */
	static void drop(ServerPlayer player) {
		if (CHARGING.remove(player.getUUID()) == null) return;
		removeShard(HELD.remove(player.getUUID()));
		NetworkHandler.sendFireBending(player, false, 0, 0);
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
	}

	private static void launch(ServerPlayer player, double charge) {
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition();
		Vec3 dir = player.getLookAngle().normalize();
		Spike spike = new Spike(player, eye, dir, charge);
		// the shard leaves the hand and flies with the spike, its tip just ahead of the face for the first tick
		spike.shard = HELD.remove(player.getUUID());
		if (spike.shard == null || spike.shard.isRemoved()) spike.shard = spawnShard(level, eye);
		float length = shardLength(charge);
		poseShard(spike.shard, eye.add(dir.scale(1.2 - length / 2)), dir, length);
		SPIKES.add(spike);
		Vec3 h = eye.add(dir.scale(0.8));
		AuraFx.burst(level, h.x, h.y, h.z, ParticleTypes.SNOWFLAKE, 10, 0.15, 0.05);
		AuraFx.burst(level, h.x, h.y, h.z, AuraFx.dust(FLAKE_WHITE, 1.0f), 6, 0.15, 0.0);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.0f, 1.3f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.PLAYER_HURT_FREEZE.value(), SoundSource.PLAYERS, 0.6f, 1.5f);
	}

	/** Every server tick per player: the forming spike, and the auto-throw. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		Long since = CHARGING.get(player.getUUID());
		if (since == null) return;
		if (!AuraAbilities.hasAbility(data, Ability.ICE_SPIKE) || !player.isAlive() || player.isSpectator()) {
			drop(player);
			return;
		}
		if (now < since) since = now;
		if (now - since >= AuraConfig.ICE_SPIKE_MAX_HOLD_TICKS) {
			release(player, now);
			return;
		}
		double charge = charge(since, now);
		boolean full = charge >= 1.0 - 1.0e-6;
		if (now % 2 == 0) NetworkHandler.sendFireBending(player, true, (float) charge, 1f);

		// the spike: a short shard of ice in the raised hand, pointing where the player looks, longer as it charges
		ServerLevel level = player.serverLevel();
		Vec3 h = handPos(player);
		Vec3 look = player.getLookAngle();
		double length = shardLength(charge);
		Display.ItemDisplay shard = HELD.get(player.getUUID());
		if (shard != null) poseShard(shard, h.add(look.scale(0.15 * length)), look, (float) length);
		if (now % 3 == 0) AuraFx.particles(level, ParticleTypes.SNOWFLAKE, h.x, h.y, h.z, 1, 0.25, 0.25, 0.25, 0.0);
		if (full && now % 4 == 0) AuraFx.particles(level, ParticleTypes.END_ROD, h.x, h.y, h.z, 1, 0.1, 0.1, 0.1, 0.0);
		long held = now - since;
		if (held == AuraConfig.ICE_SPIKE_FULL_CHARGE_TICKS) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.AMETHYST_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.2f, 1.8f);
			level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.GLASS_PLACE.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
			AuraFx.burst(level, h.x, h.y, h.z, AuraFx.dust(FLAKE_WHITE, 1.2f), 10, 0.25, 0.0);
			player.displayClientMessage(Component.literal("❄ Fully charged - it will freeze! ❄").withStyle(ChatFormatting.AQUA), true);
		} else if (!full && held % 6 == 0) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.POWDER_SNOW_STEP.value(), SoundSource.PLAYERS, 0.4f,
					(float) (0.8 + charge * 0.8));
		}
	}

	/** The shared rule (Handhelds.enemy): party protection and viper steerers included. */
	private static boolean canHit(ServerPlayer owner, Entity e) {
		return Handhelds.enemy(owner, e);
	}

	/** Every server tick: the spikes in flight, and the frozen targets. */
	public static void tickAll(MinecraftServer server) {
		long now = server.getTickCount();
		if (!SPIKES.isEmpty()) {
			for (Iterator<Spike> it = SPIKES.iterator(); it.hasNext(); ) {
				Spike s = it.next();
				if (!AuraFx.alive(s.owner) || s.owner.serverLevel() != s.level || s.ticks++ > 200 || step(s)) {
					removeShard(s.shard);
					it.remove();
				}
			}
		}
		if (!FROZEN.isEmpty()) {
			for (Iterator<Map.Entry<LivingEntity, Frozen>> it = FROZEN.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				LivingEntity target = e.getKey();
				Frozen f = e.getValue();
				if (!target.isAlive() || target.isRemoved() || now >= f.until) {
					it.remove();
					unlock(target); // the frost on screen then thaws by itself, a little each tick
					continue;
				}
				// vanilla takes 2 off every tick outside powder snow, so this stays at full freeze
				target.setTicksFrozen(target.getTicksRequiredToFreeze() + 2);
				if (AuraConfig.ICE_SPIKE_FREEZE_LOCK && !(target instanceof Player)) hold(target, f);
				if (now % 5 == 0) {
					Vec3 c = AuraFx.chest(target);
					AuraFx.particles((ServerLevel) target.level(), ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 2, 0.3, 0.4, 0.3, 0.0);
				}
			}
		}
	}

	/** One tick of flight. Returns true when the spike is done. */
	private static boolean step(Spike s) {
		ServerLevel level = s.level;
		Vec3 from = s.pos;
		double step = Math.min(s.speed, AuraConfig.ICE_SPIKE_RANGE - s.flown);
		if (step <= 0.01) {
			fizzle(level, from);
			return true;
		}
		Vec3 to = from.add(s.dir.scale(step));
		HitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, s.owner));
		if (block.getType() != HitResult.Type.MISS) to = block.getLocation();
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(level, s.owner, from, to,
				new AABB(from, to).inflate(AuraConfig.ICE_SPIKE_HITBOX + 1.0), e -> canHit(s.owner, e),
				(float) AuraConfig.ICE_SPIKE_HITBOX);
		Vec3 end = entity != null ? entity.getLocation() : to;
		draw(s, from, end);
		if (Combos.pass(s.owner, from, end, AuraConfig.ICE_SPIKE_HITBOX, null)) {
			removeShard(s.shard);
			return true;
		}
		if (s.shard != null) {
			// tip at the head of the spike, but never closer than 1.2 blocks to the thrower's eyes
			double ahead = s.flown + from.distanceTo(end);
			Vec3 tip = ahead < 1.2 ? end.add(s.dir.scale(1.2 - ahead)) : end;
			float length = shardLength(s.charge);
			poseShard(s.shard, tip.subtract(s.dir.scale(length / 2)), s.dir, length);
		}
		if (entity != null) {
			hit(s, (LivingEntity) entity.getEntity());
			return true;
		}
		if (block.getType() != HitResult.Type.MISS) {
			shatter(level, to, s.full);
			return true;
		}
		s.pos = to;
		s.flown += step;
		if (s.flown >= AuraConfig.ICE_SPIKE_RANGE - 0.01) {
			fizzle(level, to);
			return true;
		}
		return false;
	}

	/** A thin frosty trail behind the spike (the spike itself is the frost shard display, plus dust at the head); nothing right at the thrower's face. */
	private static void draw(Spike s, Vec3 from, Vec3 to) {
		ServerLevel level = s.level;
		double startClear = Math.max(0.0, 1.2 - s.flown);
		Vec3 a = from.add(s.dir.scale(Math.min(startClear, from.distanceTo(to))));
		double trail = a.distanceTo(to);
		if (trail > 0.05) {
			AuraFx.line(level, a, to, AuraFx.dust(ICE_BLUE, s.full ? 0.9f : 0.7f), (int) Math.ceil(trail * 2));
			AuraFx.particles(level, ParticleTypes.SNOWFLAKE, (a.x + to.x) / 2, (a.y + to.y) / 2, (a.z + to.z) / 2, s.full ? 2 : 1, 0.1, 0.1, 0.1, 0.0);
		}
		double len = s.full ? 0.9 : 0.6;
		for (int i = 0; i <= 4; i++) {
			Vec3 p = to.subtract(s.dir.scale(len * i / 4.0));
			if (p.distanceTo(s.owner.getEyePosition()) < 1.0) continue;
			AuraFx.particles(level, AuraFx.dust(i == 0 ? FLAKE_WHITE : ICE_DEEP, (float) ((s.full ? 1.3 : 1.0) * (1.0 - i * 0.15))), p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
		if (s.full) AuraFx.particles(level, ParticleTypes.END_ROD, to.x, to.y, to.z, 1, 0.05, 0.05, 0.05, 0.0);
	}

	private static void hit(Spike s, LivingEntity target) {
		ServerPlayer owner = s.owner;
		ServerLevel level = s.level;
		float damage = (float) (AuraConfig.ICE_SPIKE_DAMAGE_MIN + (AuraConfig.ICE_SPIKE_DAMAGE_MAX - AuraConfig.ICE_SPIKE_DAMAGE_MIN) * s.charge);
		AuraStats.add(owner, AuraStats.ICE_SPIKE_HITS, 1);
		target.hurt(level.damageSources().playerAttack(owner), AuraAbilities.vsMob(target, damage));
		if (AuraConfig.ICE_SPIKE_KNOCKBACK > 0) target.knockback(AuraConfig.ICE_SPIKE_KNOCKBACK, -s.dir.x, -s.dir.z);
		if (s.full) {
			freeze(target, level.getServer().getTickCount());
			AuraStats.add(owner, AuraStats.ICE_SPIKE_FREEZES, 1);
			owner.displayClientMessage(Component.literal("❄ Frozen! ❄").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), true);
		} else if (AuraConfig.ICE_SPIKE_SLOW_AMPLIFIER >= 0) {
			int ticks = (int) Math.round(AuraConfig.ICE_SPIKE_SLOW_TICKS_MIN + (AuraConfig.ICE_SPIKE_SLOW_TICKS_MAX - AuraConfig.ICE_SPIKE_SLOW_TICKS_MIN) * s.charge);
			if (ticks > 0) target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, AuraConfig.ICE_SPIKE_SLOW_AMPLIFIER, false, true, true), owner);
		}
		if (AuraConfig.ICE_SPIKE_HOLDER_SPEED_TICKS > 0 && AuraConfig.ICE_SPIKE_HOLDER_SPEED_AMPLIFIER >= 0) {
			owner.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, AuraConfig.ICE_SPIKE_HOLDER_SPEED_TICKS,
					AuraConfig.ICE_SPIKE_HOLDER_SPEED_AMPLIFIER, false, true, true));
		}
		shatter(level, AuraFx.chest(target), s.full);
	}

	/**
	 * After damage: a frozen target hit by something (not the freeze damage) breaks out - freeze and
	 * its Slowness gone at once, a burst of ice, and a short Speed boost.
	 */
	public static void onDamaged(LivingEntity target, net.minecraft.world.damagesource.DamageSource source) {
		if (!AuraConfig.ICE_SPIKE_FREEZE_BREAK_ON_HIT || FROZEN.isEmpty() || !FROZEN.containsKey(target)) return;
		if (source.getEntity() == null || source.is(net.minecraft.world.damagesource.DamageTypes.FREEZE)) return;
		FROZEN.remove(target);
		unlock(target);
		target.setTicksFrozen(0);
		MobEffectInstance slow = target.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
		// only the freeze's own Slowness, never one from somewhere else
		if (slow != null && slow.getAmplifier() == AuraConfig.ICE_SPIKE_FREEZE_SLOW_AMPLIFIER) target.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
		if (AuraConfig.ICE_SPIKE_THAW_SPEED_TICKS > 0 && AuraConfig.ICE_SPIKE_THAW_SPEED_AMPLIFIER >= 0) {
			target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, AuraConfig.ICE_SPIKE_THAW_SPEED_TICKS,
					AuraConfig.ICE_SPIKE_THAW_SPEED_AMPLIFIER, false, true, true));
		}
		shatter((ServerLevel) target.level(), AuraFx.chest(target), true);
	}

	/** The powder snow freeze, held at full for FREEZE_TICKS, plus heavy Slowness; it thaws by itself after. */
	private static void freeze(LivingEntity target, long now) {
		freeze(target, now, AuraConfig.ICE_SPIKE_FREEZE_TICKS);
	}

	/** The same freeze for {@code ticks} (Winter's Gate). */
	static void freeze(LivingEntity target, long now, int ticks) {
		ticks = Math.max(0, ticks);
		if (ticks == 0) return;
		Frozen f = FROZEN.get(target);
		if (f == null) FROZEN.put(target, new Frozen(target, now + ticks));
		else f.until = Math.max(f.until, now + ticks);
		target.setTicksFrozen(target.getTicksRequiredToFreeze() + 2);
		if (AuraConfig.ICE_SPIKE_FREEZE_LOCK) lock(target, ticks);
		if (AuraConfig.ICE_SPIKE_FREEZE_SLOW_AMPLIFIER >= 0) {
			target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, AuraConfig.ICE_SPIKE_FREEZE_SLOW_AMPLIFIER, false, true, true));
		}
		ServerLevel level = (ServerLevel) target.level();
		Vec3 c = AuraFx.chest(target);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(FLAKE_WHITE, 1.6f), 25, 0.5, 0.0);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.PLAYER_HURT_FREEZE.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
	}

	/**
	 * The full lock: no walking or jumping (attribute modifiers, which the player's own client sees
	 * too), no item in use; a frozen player's client is told so it also stops the camera, movement
	 * keys, clicks and hotbar (client/FrozenControls). Attacks, item use and ability keys are also
	 * refused on the server (AuraPoints events, NetworkHandler).
	 */
	private static void lock(LivingEntity target, int ticks) {
		var speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
		var jump = target.getAttribute(Attributes.JUMP_STRENGTH);
		if (speed != null && speed.getModifier(FROZEN_ID) == null) {
			speed.addTransientModifier(new AttributeModifier(FROZEN_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		if (jump != null && jump.getModifier(FROZEN_ID) == null) {
			jump.addTransientModifier(new AttributeModifier(FROZEN_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		target.stopUsingItem();
		if (target instanceof ServerPlayer player) {
			Stun.breakHolds(player); // whatever they were holding falls apart
			NetworkHandler.sendFrozen(player, ticks);
		}
	}

	private static void unlock(LivingEntity target) {
		var speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
		var jump = target.getAttribute(Attributes.JUMP_STRENGTH);
		if (speed != null) speed.removeModifier(FROZEN_ID);
		if (jump != null) jump.removeModifier(FROZEN_ID);
		if (target instanceof ServerPlayer player) NetworkHandler.sendFrozen(player, 0);
	}

	/** Mobs, every tick: no path, no sideways drift, facing where they froze, creepers stop fusing. */
	private static void hold(LivingEntity target, Frozen f) {
		if (target instanceof Mob mob) {
			mob.getNavigation().stop();
			mob.setJumping(false);
		}
		if (target instanceof Creeper creeper) creeper.setSwellDir(-1);
		Vec3 v = target.getDeltaMovement();
		target.setDeltaMovement(0, Math.min(v.y, 0), 0);
		target.setYRot(f.yRot);
		target.setXRot(f.xRot);
		target.setYHeadRot(f.headRot);
		target.setYBodyRot(f.bodyRot);
	}

	private static void shatter(ServerLevel level, Vec3 p, boolean full) {
		var ice = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
		AuraFx.burst(level, p.x, p.y, p.z, ice, full ? 30 : 15, 0.3, 0.15);
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.SNOWFLAKE, full ? 20 : 10, 0.35, 0.05);
		AuraFx.burst(level, p.x, p.y, p.z, AuraFx.dust(ICE_BLUE, 1.3f), full ? 14 : 8, 0.35, 0.0);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.GLASS_BREAK.value(), SoundSource.PLAYERS, full ? 1.0f : 0.7f, 1.4f);
		if (full) level.playSound(null, p.x, p.y, p.z, AuraSounds.AMETHYST_CLUSTER_BREAK.value(), SoundSource.PLAYERS, 1.0f, 0.9f);
	}

	private static void fizzle(ServerLevel level, Vec3 p) {
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.SNOWFLAKE, 8, 0.2, 0.02);
		AuraFx.burst(level, p.x, p.y, p.z, AuraFx.dust(FLAKE_WHITE, 0.8f), 5, 0.2, 0.0);
	}

	public static void onLogout(ServerPlayer player) {
		CHARGING.remove(player.getUUID());
		removeShard(HELD.remove(player.getUUID()));
		if (FROZEN.remove(player) != null) unlock(player);
	}
}
