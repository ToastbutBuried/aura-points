package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Blood: Hemorrhage, Blood Tether and Crimson Sacrifice on the Ability keys, Bloodlust and Thin Blood
 * always on (its Ultimate, Blood Moon, is in Ultimates and uses {@link #bleed} and {@link #moonUntil}). Bleeding is
 * damage over time that doesn't knock its target about, and every bleeding tick fills the owner's blood bar, which
 * Blood Moon needs and Crimson Sacrifice spends.
 */
public final class Blood {

	private Blood() {
	}

	static final int CRIMSON = 0x8B0A14, BRIGHT = 0xE0202E;
	/** Drops of blood: red dust falling. */
	static final ParticleOptions DROPS = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.NETHER_WART_BLOCK.defaultBlockState());

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
	}

	/** Ability damage that leaves the target's motion as it was (no knockback): bleeding, draining. */
	static void hurtQuietly(ServerPlayer owner, LivingEntity e, float damage) {
		// it neither waits for nor hands out the target's hit cooldown, so melee in between still lands
		Vec3 motion = e.getDeltaMovement();
		int frames = e.invulnerableTime;
		e.invulnerableTime = 0;
		Handhelds.hurt(owner, e, damage);
		e.invulnerableTime = frames;
		e.setDeltaMovement(motion);
	}

	private static LivingEntity aimed(ServerPlayer p, double range) {
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getLookAngle().scale(range));
		HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		Vec3 to = block.getLocation();
		var hit = ProjectileUtil.getEntityHitResult(p.level(), p, eye, to, new AABB(eye, to).inflate(1.0), e -> Handhelds.enemy(p, e), 0.6f);
		return hit != null && hit.getEntity() instanceof LivingEntity l ? l : null;
	}

	// ---------------------------------------------------------------
	// Bleeding
	// ---------------------------------------------------------------

	private static final class Bleed {
		final UUID owner;
		int left;

		Bleed(UUID owner, int left) {
			this.owner = owner;
			this.left = left;
		}
	}

	private static final Map<LivingEntity, Bleed> BLEEDING = new HashMap<>();

	/** Makes {@code e} bleed for {@code ticks} (a fresh cut only ever lengthens it). */
	public static void bleed(ServerPlayer owner, LivingEntity e, int ticks) {
		if (ticks <= 0 || !e.isAlive()) return;
		Bleed b = BLEEDING.get(e);
		if (b == null) BLEEDING.put(e, new Bleed(owner.getUUID(), ticks));
		else b.left = Math.max(b.left, ticks);
	}

	public static boolean isBleeding(LivingEntity e) {
		return BLEEDING.containsKey(e);
	}

	private static void tickBleeding(MinecraftServer server) {
		for (Iterator<Map.Entry<LivingEntity, Bleed>> it = BLEEDING.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			LivingEntity e = entry.getKey();
			Bleed b = entry.getValue();
			if (!e.isAlive() || e.isRemoved() || --b.left <= 0) {
				it.remove();
				continue;
			}
			ServerLevel level = (ServerLevel) e.level();
			if (b.left % 4 == 0 && !e.isInvisible()) {
				AuraFx.particles(level, DROPS, e.getX(), e.getY() + e.getBbHeight() * 0.6, e.getZ(), 2, e.getBbWidth() * 0.3, 0.2, e.getBbWidth() * 0.3, 0);
			}
			if (b.left % Math.max(5, AuraConfig.BLOOD_BLEED_EVERY_TICKS) == 0) {
				ServerPlayer owner = server.getPlayerList().getPlayer(b.owner);
				if (owner != null) {
					hurtQuietly(owner, e, AuraConfig.BLOOD_BLEED_DAMAGE);
					if (Branch.is(owner, Branch.SANGUINE)) owner.heal(AuraConfig.BLOOD_BLEED_DAMAGE * (float) AuraConfig.BLOOD_SANGUINE_BLEED_LIFESTEAL);
					if (moonUntil(owner) <= server.getTickCount()) addBlood(owner, gain(e));
				} else e.hurt(level.damageSources().magic(), AuraConfig.BLOOD_BLEED_DAMAGE);
				AuraFx.particles(level, AuraFx.dust(CRIMSON, 1.1f), e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ(), 6, 0.25, 0.3, 0.25, 0);
			}
		}
	}

	// ---------------------------------------------------------------
	// The blood bar
	// ---------------------------------------------------------------

	/** What one bleeding tick on {@code e} puts in the bar: players most, monsters less, anything else least. */
	private static int gain(LivingEntity e) {
		if (e instanceof Player) return AuraConfig.BLOOD_BAR_PLAYER;
		if (e instanceof Enemy) return AuraConfig.BLOOD_BAR_MONSTER;
		return AuraConfig.BLOOD_BAR_MOB;
	}

	private static AuraPlayerData data(ServerPlayer p) {
		return AuraState.get(p.getServer()).getOrCreate(p.getUUID());
	}

	public static int bar(ServerPlayer p) {
		return data(p).bloodBar;
	}

	/** Sets the bar (clamped to 0..MAX), saves it and tells the player's client when it changed. */
	private static void setBar(ServerPlayer p, int blood) {
		AuraPlayerData data = data(p);
		blood = Math.max(0, Math.min(barMax(p), blood));
		if (blood == data.bloodBar) return;
		data.bloodBar = blood;
		AuraState.get(p.getServer()).markDirty();
		NetworkHandler.sendBloodBar(p, blood);
	}

	public static void addBlood(ServerPlayer p, int amount) {
		if (amount > 0 && Branch.is(p, Branch.SANGUINE)) amount = Math.max(1, (int) Math.round(amount * AuraConfig.BLOOD_SANGUINE_BAR_FACTOR));
		if (amount > 0) setBar(p, bar(p) + amount);
	}

	static void spendBlood(ServerPlayer p, int amount) {
		if (amount > 0) setBar(p, bar(p) - amount);
	}

	/** Dying empties the bar. */
	public static void onDeath(ServerPlayer p) {
		setBar(p, 0);
	}

	/** How much the blood bar holds: Sanguine's holds enough for its dearer Blood Moon. */
	public static int barMax(ServerPlayer p) {
		int max = Math.max(1, AuraConfig.BLOOD_BAR_MAX);
		return Branch.is(p, Branch.SANGUINE) ? Math.max(max, AuraConfig.BLOOD_SANGUINE_MOON_COST) : max;
	}

	/** What Blood Moon takes from the bar: the config value, never more than the bar can hold. */
	public static int moonCost(ServerPlayer p) {
		Branch b = Branch.current(p);
		int cost = b == Branch.SANGUINE ? AuraConfig.BLOOD_SANGUINE_MOON_COST : b == Branch.SACRIFICIAL ? AuraConfig.BLOOD_SACRIFICIAL_MOON_COST : AuraConfig.BLOOD_BAR_MOON_COST;
		return Math.max(0, Math.min(cost, barMax(p)));
	}

	/** True if the bar holds enough for Blood Moon; otherwise tells the player how much is missing. */
	static boolean moonReady(ServerPlayer p) {
		int need = moonCost(p), have = bar(p);
		if (have >= need) return true;
		p.displayClientMessage(Component.literal("Not enough blood (" + have + "/" + need + ") - make them bleed.").withStyle(ChatFormatting.DARK_RED), true);
		return false;
	}

	// ---------------------------------------------------------------
	// Hemorrhage: a lunging cut
	// ---------------------------------------------------------------

	private static final class Lunge {
		final UUID owner;
		final Vec3 dir;
		int t;

		Lunge(UUID owner, Vec3 dir) {
			this.owner = owner;
			this.dir = dir;
		}
	}

	private static final Map<UUID, Lunge> LUNGES = new HashMap<>();

	static boolean hemorrhage(ServerPlayer p, long now) {
		if (LUNGES.containsKey(p.getUUID())) return false;
		if (!AuraAbilities.checkCooldown(p, Ability.HEMORRHAGE, AuraConfig.BLOOD_HEMO_COOLDOWN_TICKS, now)) return false;
		Vec3 look = p.getLookAngle();
		Vec3 dir = new Vec3(look.x, Math.max(-0.25, Math.min(0.3, look.y)), look.z).normalize();
		p.setDeltaMovement(dir.scale(Math.max(0.3, AuraConfig.BLOOD_HEMO_SPEED)).add(0, p.onGround() ? 0.12 : 0, 0));
		p.hurtMarked = true;
		LUNGES.put(p.getUUID(), new Lunge(p.getUUID(), dir));
		sound(p.serverLevel(), p.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 0.8f, 0.6f);
		sound(p.serverLevel(), p.position(), SoundEvents.PHANTOM_FLAP, 0.8f, 1.4f);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_BLOOD, 0);
		AuraAbilities.markUsed(p, Ability.HEMORRHAGE, now);
		return true;
	}

	/** Returns false when the lunge is over. */
	private static boolean tickLunge(MinecraftServer server, Lunge l) {
		ServerPlayer p = server.getPlayerList().getPlayer(l.owner);
		if (p == null || !p.isAlive() || ++l.t > 8) return false;
		ServerLevel level = p.serverLevel();
		p.fallDistance = 0;
		Vec3 c = AuraFx.chest(p);
		AuraFx.particles(level, AuraFx.dust(CRIMSON, 1.2f), c.x, c.y, c.z, 3, 0.2, 0.3, 0.2, 0);
		LivingEntity hit = null;
		double best = Double.MAX_VALUE;
		for (LivingEntity e : Handhelds.enemies(p, level, c.add(l.dir.scale(0.8)), 1.8)) {
			double d = e.distanceToSqr(p);
			if (AuraFx.chest(e).subtract(c).dot(l.dir) > -0.4 && d < best) {
				best = d;
				hit = e;
			}
		}
		if (hit == null) return true;
		Handhelds.hurt(p, hit, AuraConfig.BLOOD_HEMO_DAMAGE);
		if (Branch.is(p, Branch.SANGUINE)) p.heal(AuraConfig.BLOOD_HEMO_DAMAGE * (float) AuraConfig.BLOOD_SANGUINE_HEMO_LIFESTEAL);
		bleed(p, hit, AuraConfig.BLOOD_BLEED_TICKS);
		Vec3 hc = AuraFx.chest(hit);
		AuraFx.particles(level, ParticleTypes.SWEEP_ATTACK, hc.x, hc.y, hc.z, 1, 0, 0, 0, 0);
		AuraFx.burst(level, hc.x, hc.y, hc.z, AuraFx.dust(BRIGHT, 1.3f), 20, 0.4, 0);
		AuraFx.particles(level, DROPS, hc.x, hc.y, hc.z, 12, 0.3, 0.3, 0.3, 0);
		sound(level, hc, SoundEvents.PLAYER_ATTACK_CRIT, 1.0f, 0.7f);
		sound(level, hc, SoundEvents.SWEET_BERRY_BUSH_BREAK, 1.2f, 0.6f);
		// the cut stops you short
		p.setDeltaMovement(p.getDeltaMovement().scale(0.2));
		p.hurtMarked = true;
		return false;
	}

	// ---------------------------------------------------------------
	// Blood Tether
	// ---------------------------------------------------------------

	private static final class Tether {
		final UUID owner;
		final LivingEntity target;
		int t, unseen;

		Tether(UUID owner, LivingEntity target) {
			this.owner = owner;
			this.target = target;
		}
	}

	private static final Map<UUID, Tether> TETHERS = new HashMap<>();

	static boolean tether(ServerPlayer p, long now) {
		if (TETHERS.containsKey(p.getUUID())) return false;
		if (!AuraAbilities.checkCooldown(p, Ability.BLOOD_TETHER, AuraConfig.BLOOD_TETHER_COOLDOWN_TICKS, now)) return false;
		LivingEntity target = aimed(p, Math.max(2, AuraConfig.BLOOD_TETHER_RANGE));
		if (target == null) {
			p.displayClientMessage(Component.literal("Nobody in your sights to tether.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		TETHERS.put(p.getUUID(), new Tether(p.getUUID(), target));
		ServerLevel level = p.serverLevel();
		AuraFx.line(level, AuraFx.chest(p), AuraFx.chest(target), AuraFx.dust(BRIGHT, 1.4f), 24);
		sound(level, target.position(), SoundEvents.WARDEN_HEARTBEAT, 1.5f, 1.2f);
		sound(level, p.position(), SoundEvents.CHAIN_PLACE, 1.0f, 0.6f);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_BLOOD, 1);
		AuraAbilities.markUsed(p, Ability.BLOOD_TETHER, now);
		return true;
	}

	/** Returns false when the tether has snapped or run out. */
	private static boolean tickTether(MinecraftServer server, Tether th) {
		ServerPlayer p = server.getPlayerList().getPlayer(th.owner);
		LivingEntity e = th.target;
		double range = Math.max(2, AuraConfig.BLOOD_TETHER_RANGE) + 4;
		if (p == null || !p.isAlive() || p.isSpectator() || !e.isAlive() || e.isRemoved() || e.level() != p.level()
				|| e.distanceToSqr(p) > range * range || ++th.t > Math.max(20, AuraConfig.BLOOD_TETHER_TICKS)) {
			if (p != null) snap(p, th);
			return false;
		}
		ServerLevel level = p.serverLevel();
		Vec3 from = AuraFx.chest(e), to = AuraFx.chest(p);
		boolean seen = level.clip(new ClipContext(to, from, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
		th.unseen = seen ? 0 : th.unseen + 1;
		if (th.unseen > 20) {
			snap(p, th);
			return false;
		}
		// the tether: a sagging crimson thread, and a pulse of blood running along it to you
		if (th.t % 2 == 0) {
			int points = Math.max(6, (int) (from.distanceTo(to) * 3));
			for (int i = 0; i <= points; i++) {
				double f = i / (double) points;
				Vec3 at = from.lerp(to, f).add(0, -Math.sin(f * Math.PI) * 0.5, 0);
				AuraFx.particles(level, AuraFx.dust(CRIMSON, 0.7f), at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
		}
		int every = Math.max(4, AuraConfig.BLOOD_TETHER_EVERY_TICKS);
		double f = (th.t % every) / (double) every;
		Vec3 pulse = from.lerp(to, f).add(0, -Math.sin(f * Math.PI) * 0.5, 0);
		AuraFx.particles(level, AuraFx.dust(BRIGHT, 1.5f), pulse.x, pulse.y, pulse.z, 2, 0.05, 0.05, 0.05, 0);
		if (th.t % every == 0) {
			if (Branch.is(p, Branch.SACRIFICIAL)) {
				// the tether costs you: it snaps rather than take the last of your blood
				float cost = Math.max(0, AuraConfig.BLOOD_SACRIFICIAL_TETHER_COST);
				if (p.getHealth() <= cost + 1 && !p.getAbilities().invulnerable) {
					snap(p, th);
					return false;
				}
				if (!p.getAbilities().invulnerable) p.setHealth(p.getHealth() - cost);
				hurtQuietly(p, e, AuraConfig.BLOOD_SACRIFICIAL_TETHER_DRAIN);
			} else {
				hurtQuietly(p, e, AuraConfig.BLOOD_TETHER_DRAIN);
				p.heal(AuraConfig.BLOOD_TETHER_DRAIN * (float) Math.max(0, AuraConfig.BLOOD_TETHER_HEAL_FRACTION));
			}
			AuraFx.particles(level, DROPS, from.x, from.y, from.z, 6, 0.25, 0.25, 0.25, 0);
			sound(level, to, SoundEvents.WARDEN_HEARTBEAT, 0.9f, 1.3f);
		}
		return true;
	}

	private static void snap(ServerPlayer p, Tether th) {
		Vec3 a = AuraFx.chest(p), b = AuraFx.chest(th.target);
		Vec3 mid = a.lerp(b, 0.5);
		AuraFx.particles(p.serverLevel(), DROPS, mid.x, mid.y, mid.z, 10, 0.6, 0.2, 0.6, 0);
		sound(p.serverLevel(), mid, SoundEvents.CHAIN_BREAK, 0.9f, 0.7f);
	}

	// ---------------------------------------------------------------
	// Crimson Sacrifice
	// ---------------------------------------------------------------

	/** player -> {hits left, ticks left} */
	private static final Map<UUID, int[]> EMPOWERED = new HashMap<>();

	public static boolean isEmpowered(ServerPlayer p) {
		return EMPOWERED.containsKey(p.getUUID());
	}

	public static boolean sacrifice(ServerPlayer p, long now) {
		if (!AuraAbilities.checkCooldown(p, Ability.CRIMSON_SACRIFICE, AuraConfig.BLOOD_SACRIFICE_COOLDOWN_TICKS, now)) return false;
		// blood from the bar pays part of the price: a full SACRIFICE_SPEND takes MAX_REDUCTION off
		// Sacrificial pays the full price in health: the bar neither reduces it nor is spent
		boolean flat = Branch.is(p, Branch.SACRIFICIAL);
		int full = flat ? 0 : Math.max(0, AuraConfig.BLOOD_BAR_SACRIFICE_SPEND);
		int spend = Math.min(bar(p), full);
		double cut = full == 0 ? 0 : spend / (double) full * Math.max(0, Math.min(1, AuraConfig.BLOOD_SACRIFICE_MAX_REDUCTION));
		float cost = Math.max(0, Branch.is(p, Branch.SACRIFICIAL) ? AuraConfig.BLOOD_SACRIFICIAL_SACRIFICE_COST : AuraConfig.BLOOD_SACRIFICE_COST) * (float) (1 - cut);
		if (p.getHealth() <= cost + 1 && !p.getAbilities().invulnerable) {
			p.displayClientMessage(Component.literal("Not enough blood left to give.").withStyle(ChatFormatting.RED), true);
			return false;
		}
		if (!p.getAbilities().invulnerable) p.setHealth(p.getHealth() - cost);
		spendBlood(p, spend);
		EMPOWERED.put(p.getUUID(), new int[]{Math.max(1, Branch.is(p, Branch.SACRIFICIAL) ? AuraConfig.BLOOD_SACRIFICIAL_SACRIFICE_HITS : AuraConfig.BLOOD_SACRIFICE_HITS), Math.max(20, AuraConfig.BLOOD_SACRIFICE_TICKS)});
		ServerLevel level = p.serverLevel();
		Vec3 c = AuraFx.chest(p);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(BRIGHT, 1.5f), 30, 0.6, 0);
		AuraFx.particles(level, DROPS, c.x, c.y, c.z, 20, 0.4, 0.4, 0.4, 0);
		AuraFx.helix(p, AuraFx.dust(CRIMSON, 1.2f), 24, 0.7, 2.2);
		sound(level, c, SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH, 1.0f, 0.7f);
		sound(level, c, SoundEvents.WARDEN_HEARTBEAT, 1.5f, 0.9f);
		sound(level, c, SoundEvents.EVOKER_PREPARE_ATTACK, 0.7f, 1.4f);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_BLOOD, 2);
		AuraAbilities.markUsed(p, Ability.CRIMSON_SACRIFICE, now);
		return true;
	}

	// ---------------------------------------------------------------
	// Hits and kills
	// ---------------------------------------------------------------

	/** Blood Moon's end, per caster (server tick count). */
	static final Map<UUID, Long> MOON = new HashMap<>();

	public static long moonUntil(ServerPlayer p) {
		return MOON.getOrDefault(p.getUUID(), 0L);
	}

	/**
	 * The attacker's hit landing (the incoming-damage event): a sacrificed fist hits double; under the Blood Moon
	 * part of every hit comes back as health. Returns the new amount.
	 */
	public static float onHit(ServerPlayer attacker, LivingEntity victim, float amount, boolean melee) {
		if (amount <= 0) return amount;
		int[] power = EMPOWERED.get(attacker.getUUID());
		if (melee && power != null) {
			amount *= (float) Math.max(1, sacrificeMultiplier(attacker));
			if (--power[0] <= 0) EMPOWERED.remove(attacker.getUUID());
			Vec3 c = AuraFx.chest(victim);
			ServerLevel level = attacker.serverLevel();
			AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(BRIGHT, 1.4f), 18, 0.4, 0);
			AuraFx.particles(level, ParticleTypes.CRIT, c.x, c.y, c.z, 10, 0.3, 0.3, 0.3, 0.2);
			sound(level, c, SoundEvents.PLAYER_ATTACK_CRIT, 1.0f, 0.6f);
		}
		if (attacker.getServer() != null && moonUntil(attacker) > attacker.getServer().getTickCount()) {
			attacker.heal(amount * (float) Math.max(0, AuraConfig.BLOOD_MOON_LIFESTEAL));
			Vec3 a = AuraFx.chest(victim), b = AuraFx.chest(attacker);
			AuraFx.line(attacker.serverLevel(), a, b, AuraFx.dust(BRIGHT, 0.9f), 10);
		}
		return amount;
	}

	/** What a kill heals: Sanguine more, Sacrificial less. */
	public static float lustHeal(AuraPlayerData data) {
		Branch b = Branch.current(data);
		return b == Branch.SANGUINE ? AuraConfig.BLOOD_SANGUINE_LUST_HEAL : b == Branch.SACRIFICIAL ? AuraConfig.BLOOD_SACRIFICIAL_LUST_HEAL : AuraConfig.BLOOD_LUST_HEAL;
	}

	/** Crimson Sacrifice's damage multiplier for the owner. */
	public static double sacrificeMultiplier(ServerPlayer p) {
		Branch b = Branch.current(p);
		return b == Branch.SANGUINE ? AuraConfig.BLOOD_SANGUINE_SACRIFICE_MULTIPLIER : b == Branch.SACRIFICIAL ? AuraConfig.BLOOD_SACRIFICIAL_SACRIFICE_MULTIPLIER : AuraConfig.BLOOD_SACRIFICE_MULTIPLIER;
	}

	/** Bloodlust: something the player killed. */
	public static void onKill(ServerPlayer killer, AuraPlayerData data, LivingEntity dead) {
		if (!AuraAbilities.hasAbility(data, Ability.BLOODLUST)) return;
		killer.heal(Math.max(0, lustHeal(data)));
		ServerLevel level = killer.serverLevel();
		Vec3 a = AuraFx.chest(dead), b = AuraFx.chest(killer);
		AuraFx.line(level, a, b, AuraFx.dust(BRIGHT, 1.2f), 16);
		AuraFx.particles(level, DROPS, a.x, a.y, a.z, 14, 0.3, 0.3, 0.3, 0);
		sound(level, b, SoundEvents.WARDEN_HEARTBEAT, 0.8f, 1.5f);
	}

	// ---------------------------------------------------------------
	// Passives
	// ---------------------------------------------------------------

	/** Thin Blood: faster when low. Every server tick for every player. */
	public static void tickPassives(ServerPlayer p, AuraPlayerData data, long now) {
		if (now % 10 != 0) return;
		if (!AuraAbilities.hasAbility(data, Ability.THIN_BLOOD) || !p.isAlive()) return;
		if (p.getHealth() / p.getMaxHealth() <= (Branch.current(data) == Branch.SACRIFICIAL ? AuraConfig.BLOOD_SACRIFICIAL_THIN_THRESHOLD : AuraConfig.BLOOD_THIN_THRESHOLD)) {
			p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 30, Math.max(0, AuraConfig.BLOOD_THIN_SPEED_AMPLIFIER), true, false, true));
			if (!p.isInvisible() && now % 20 == 0) AuraFx.particles(p.serverLevel(), DROPS, p.getX(), p.getY() + 1, p.getZ(), 2, 0.2, 0.3, 0.2, 0);
		}
	}

	// ---------------------------------------------------------------
	// Lifecycle
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		if (!BLEEDING.isEmpty()) tickBleeding(server);
		if (!LUNGES.isEmpty()) LUNGES.values().removeIf(l -> !tickLunge(server, l));
		if (!TETHERS.isEmpty()) {
			for (Tether th : new ArrayList<>(TETHERS.values())) if (!tickTether(server, th)) TETHERS.remove(th.owner);
		}
		if (!EMPOWERED.isEmpty()) {
			for (Iterator<Map.Entry<UUID, int[]>> it = EMPOWERED.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
				if (p == null || --e.getValue()[1] <= 0) {
					it.remove();
					continue;
				}
				// red light curling round the fists while it lasts
				if (e.getValue()[1] % 3 == 0 && !p.isInvisible()) {
					Vec3 c = AuraFx.chest(p);
					double a = e.getValue()[1] * 0.5;
					AuraFx.particles(p.serverLevel(), AuraFx.dust(BRIGHT, 0.8f), c.x + Math.cos(a) * 0.5, c.y - 0.2, c.z + Math.sin(a) * 0.5, 1, 0, 0, 0, 0);
				}
			}
		}
	}

	public static void onLogout(ServerPlayer p) {
		LUNGES.remove(p.getUUID());
		TETHERS.remove(p.getUUID());
		EMPOWERED.remove(p.getUUID());
		MOON.remove(p.getUUID());
		BLEEDING.remove(p);
	}

	public static void clearAll() {
		BLEEDING.clear();
		LUNGES.clear();
		TETHERS.clear();
		EMPOWERED.clear();
		MOON.clear();
	}

	/** Enemies around the moon's caster: let them bleed. */
	static void moonBleed(ServerPlayer p, double radius, int ticks) {
		List<LivingEntity> foes = Handhelds.enemies(p, p.serverLevel(), AuraFx.chest(p), radius);
		for (LivingEntity e : foes) bleed(p, e, ticks);
	}
}
