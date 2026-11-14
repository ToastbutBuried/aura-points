package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
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
 * The sneak and charged casts ({@link CastMode}) of the first auras that have them: Fire, Water, Shadow. Two jobs:
 * <ul>
 * <li>Key holds for charged casts. {@link NetworkHandler} calls {@link #beginCharge} when an Ability key goes down on
 * an ability with a CHARGED cast and {@link #finishCharge} when it comes up; that says whether the press was a tap or
 * a charge, and the cast fires then. {@link #tickAll} plays the "charged" cue and drops a charge whose holder is
 * frozen, stunned, dead, or kept the key down too long.</li>
 * <li>The casts themselves ({@link #activate}) and their lasting effects (Flame Cloak, Spring, Phase Dash), ticked by
 * {@link #tickAll}. The casts of abilities that live in AuraAbilities (Shockwave, Vanish, Dash, Healing Tide,
 * Fireball) are a parameter of those methods; the rest are here.</li>
 * </ul>
 * Lore for the Aura Book and /aura info comes from {@link #describe}.
 */
public final class Casts {

	private Casts() {
	}

	// ---------------------------------------------------------------
	// Key holds
	// ---------------------------------------------------------------

	private record Charge(Ability ability, long since) {
	}

	/** Per player, the ability whose key is down for a possible charged cast, and the tick it went down. */
	private static final Map<UUID, Charge> CHARGING = new HashMap<>();

	/** The key went down: wait for the release. False (nothing starts) while the ability is on cooldown, which is said right away. */
	public static boolean beginCharge(ServerPlayer player, Ability ability, long now) {
		if (!AuraAbilities.checkCooldown(player, ability, ability.cooldownTicks(), now)) return false;
		CHARGING.put(player.getUUID(), new Charge(ability, now));
		AuraFx.sound(player.serverLevel(), player, AuraSounds.AMETHYST_BLOCK_CHIME.value(), 0.4f, 0.6f);
		return true;
	}

	/** The key came up: CHARGED if it was down for CAST_CHARGE_TICKS, TAP if shorter, null if nothing was charging. */
	public static CastMode finishCharge(ServerPlayer player, Ability ability, long now) {
		Charge c = CHARGING.get(player.getUUID());
		if (c == null || c.ability != ability) return null;
		CHARGING.remove(player.getUUID());
		return now - c.since >= AuraConfig.CAST_CHARGE_TICKS ? CastMode.CHARGED : CastMode.TAP;
	}

	public static boolean isCharging(ServerPlayer player) {
		return CHARGING.containsKey(player.getUUID());
	}

	public static void onLogout(ServerPlayer player) {
		UUID id = player.getUUID();
		CHARGING.remove(id);
		CLOAKS.remove(id);
		PHASES.remove(id);
		SPRINGS.removeIf(s -> s.owner.equals(id));
	}

	/** Server stop: nothing carries into the next world. */
	public static void clear() {
		CHARGING.clear();
		CLOAKS.clear();
		PHASES.clear();
		SPRINGS.clear();
	}

	public static void tickAll(MinecraftServer server) {
		long now = server.getTickCount();
		if (!CHARGING.isEmpty()) tickCharges(server, now);
		if (!CLOAKS.isEmpty()) tickCloaks(server, now);
		if (!SPRINGS.isEmpty()) tickSprings(now);
		if (!PHASES.isEmpty()) tickPhases(server, now);
	}

	private static void tickCharges(MinecraftServer server, long now) {
		for (Iterator<Map.Entry<UUID, Charge>> it = CHARGING.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Charge> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Charge c = e.getValue();
			long held = now - c.since;
			if (p == null || !p.isAlive() || IceSpike.isFrozen(p) || Stun.isBusy(p) || held > AuraConfig.CAST_CHARGE_MAX_TICKS) {
				it.remove();
				if (p != null && p.isAlive()) p.displayClientMessage(Component.literal("The charge fades.").withStyle(ChatFormatting.GRAY), true);
				continue;
			}
			ServerLevel level = p.serverLevel();
			int rgb = auraColor(server, p);
			if (held == AuraConfig.CAST_CHARGE_TICKS) {
				AuraFx.ring(level, p.getX(), p.getY() + 0.1, p.getZ(), 1.0, AuraFx.dust(rgb, 1.4f), 20);
				AuraFx.sound(level, p, AuraSounds.AMETHYST_BLOCK_CHIME.value(), 0.9f, 1.8f);
				p.displayClientMessage(Component.literal("✦ " + c.ability.displayName + " charged - let go! ✦").withStyle(ChatFormatting.GOLD), true);
			} else if (held % 2 == 0) {
				Vec3 hand = p.getEyePosition().add(p.getLookAngle().scale(0.5)).add(0, -0.3, 0);
				boolean ready = held > AuraConfig.CAST_CHARGE_TICKS;
				AuraFx.particles(level, AuraFx.dust(rgb, ready ? 1.3f : 0.7f), hand.x, hand.y, hand.z, ready ? 3 : 1, 0.12, 0.12, 0.12, 0.0);
			}
		}
	}

	private static int auraColor(MinecraftServer server, ServerPlayer p) {
		AuraType aura = AuraState.get(server).getOrCreate(p.getUUID()).aura;
		return aura == null ? 0xFFFFFF : aura.rgb;
	}

	// ---------------------------------------------------------------
	// The casts
	// ---------------------------------------------------------------

	/** A SNEAK or CHARGED cast of an ability that has it ({@link CastMode#has}); AuraAbilities.activate sends those here. */
	static boolean activate(ServerPlayer player, Ability ability, long now, CastMode mode) {
		boolean sneak = mode == CastMode.SNEAK;
		return switch (ability) {
			case FIREBALL -> AuraAbilities.fireball(player, now, mode);
			case HEAT_WAVE -> sneak ? flameWard(player, now) : infernoWave(player, now);
			case FIRE_BENDING -> flameCloak(player, now);
			case SHOCKWAVE -> AuraAbilities.shockwave(player, now, mode);
			case HEALING_TIDE -> AuraAbilities.healingTide(player, now, mode);
			case ABYSSAL_BUBBLE -> diversBubble(player, now);
			// the Shadow Dive comes first, as on a normal Dash press
			case DASH -> DashDive.tryDive(player, now) || AuraAbilities.dash(player, now, mode);
			case VANISH -> AuraAbilities.vanish(player, now, mode);
			default -> false;
		};
	}

	/** The cooldown after a cast: the ability's normal one times the mode's factor (CAST_SNEAK/CHARGED_COOLDOWN_FACTOR, 1 for TAP). */
	static void used(ServerPlayer player, Ability ability, long now, CastMode mode) {
		used(player, ability, now, mode == CastMode.SNEAK ? AuraConfig.CAST_SNEAK_COOLDOWN_FACTOR
				: mode == CastMode.CHARGED ? AuraConfig.CAST_CHARGED_COOLDOWN_FACTOR : 1.0);
	}

	static void used(ServerPlayer player, Ability ability, long now, double factor) {
		int ticks = (int) Math.round(ability.cooldownTicks() * factor);
		if (ticks == ability.cooldownTicks()) AuraAbilities.markUsed(player, ability, now);
		else AuraAbilities.startCooldown(player, ability, Math.max(1, ticks), now);
	}

	// ---- Fire ----

	/** Spread Shot (sneak + Fireball): FIREBALL_SPREAD_COUNT small fireballs in a fan. The launch, after the throw's wind-up. */
	static void launchSpread(ServerPlayer player) {
		Vec3 look = player.getLookAngle();
		int n = Math.max(1, AuraConfig.FIREBALL_SPREAD_COUNT);
		for (int i = 0; i < n; i++) {
			double deg = (i - (n - 1) / 2.0) * AuraConfig.FIREBALL_SPREAD_DEGREES;
			AuraAbilities.launchFireball(player, look.yRot((float) Math.toRadians(deg)), AuraConfig.FIREBALL_SPREAD_DAMAGE_FACTOR, 1.0, 0);
		}
	}

	/** Meteor (hold Fireball): one slow, heavy fireball that blasts around its impact (AuraFireball.blast). */
	static void launchMeteor(ServerPlayer player) {
		AuraAbilities.launchFireball(player, player.getLookAngle(), AuraConfig.FIREBALL_CHARGED_DAMAGE_FACTOR,
				AuraConfig.FIREBALL_CHARGED_SPEED_FACTOR, AuraConfig.FIREBALL_CHARGED_BLAST_RADIUS);
		AuraFx.sound(player.serverLevel(), player, AuraSounds.GENERIC_EXPLODE.value(), 0.5f, 1.4f);
	}

	/** Flame Ward (sneak + Heat Wave): a ring of fire HEAT_WAVE_WARD_RADIUS around you; what stands inside burns, nothing is pushed. */
	private static boolean flameWard(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.HEAT_WAVE, AuraConfig.HEAT_WAVE_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_HEAT_WAVE, 1);
		ServerLevel world = player.serverLevel();
		double r = AuraConfig.HEAT_WAVE_WARD_RADIUS;
		float damage = AuraConfig.HEAT_WAVE_DAMAGE * AuraConfig.HEAT_WAVE_WARD_DAMAGE_FACTOR;
		List<LivingEntity> burned = AuraAbilities.areaTargets(player, r);
		AuraStunts.ACTIVE_ABILITY = Ability.HEAT_WAVE;
		try {
			for (LivingEntity target : burned) {
				target.igniteForSeconds(AuraConfig.HEAT_WAVE_BURN_SECONDS);
				AuraStunts.fireHit(player, target);
				target.hurt(world.damageSources().playerAttack(player), AuraAbilities.vsMob(target, damage));
				Vec3 c = AuraFx.chest(target);
				AuraFx.burst(world, c.x, c.y, c.z, ParticleTypes.FLAME, 15, 0.3, 0.06);
			}
		} finally {
			AuraStunts.ACTIVE_ABILITY = null;
		}
		AuraStats.add(player, AuraStats.HEAT_WAVE_HITS, burned.size());

		// the ring of fire on the ground
		int points = (int) Math.max(8, r * 7);
		for (int i = 0; i < points; i++) {
			double a = i * Math.PI * 2 / points;
			AuraFire.light(world, player, BlockPos.containing(player.getX() + Math.cos(a) * r, player.getY() + 0.5, player.getZ() + Math.sin(a) * r));
		}
		Combos.zone(player, Combos.Kind.FIRE, player.position(), r, AuraConfig.COMBO_FIRE_ZONE_TICKS, null, true);

		double cx = player.getX(), cy = player.getY() + 0.1, cz = player.getZ();
		AuraFx.ring(world, cx, cy + 0.3, cz, r, ParticleTypes.FLAME, (int) (r * 10));
		AuraFx.ring(world, cx, cy + 0.6, cz, r, AuraFx.dust(0xFFC23A, 2.0f), (int) (r * 8));
		AuraFx.particles(world, ParticleTypes.LARGE_SMOKE, cx, cy + 1.0, cz, 12, r * 0.5, 0.4, r * 0.5, 0.03);
		AuraFx.sound(world, player, AuraSounds.BLAZE_SHOOT.value(), 0.9f, 0.7f);
		AuraFx.sound(world, player, AuraSounds.FIRECHARGE_USE.value(), 1.0f, 0.8f);
		used(player, Ability.HEAT_WAVE, now, CastMode.SNEAK);
		return true;
	}

	/** Inferno Wave (hold Heat Wave): the wave, wider, harder and burning longer (HEAT_WAVE_CHARGED_*). */
	private static boolean infernoWave(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.HEAT_WAVE, AuraConfig.HEAT_WAVE_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_HEAT_WAVE, 0);
		AuraFx.sound(player.serverLevel(), player, AuraSounds.SWEET_IMPACT.value(), 1.0f, 0.9f);
		AuraAbilities.heatWave(player, false, AuraConfig.HEAT_WAVE_CHARGED_RADIUS_FACTOR, AuraConfig.HEAT_WAVE_CHARGED_DAMAGE_FACTOR,
				AuraConfig.HEAT_WAVE_CHARGED_BURN_FACTOR);
		used(player, Ability.HEAT_WAVE, now, CastMode.CHARGED);
		return true;
	}

	/** Flame Cloak holders and the tick each cloak ends. */
	private static final Map<UUID, Long> CLOAKS = new HashMap<>();

	/** Flame Cloak (sneak + Fire Bending with no flame in hand): Fire Resistance, and enemies close by catch fire. A held flame is shot as usual. */
	private static boolean flameCloak(ServerPlayer player, long now) {
		if (FireBending.isHolding(player)) return FireBending.press(player, now);
		if (!AuraAbilities.checkCooldown(player, Ability.FIRE_BENDING, AuraConfig.FIRE_BENDING_COOLDOWN_TICKS, now)) return false;
		int ticks = AuraConfig.FIRE_BENDING_CLOAK_TICKS;
		CLOAKS.put(player.getUUID(), now + ticks);
		player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, ticks, 0, false, false, true));
		ServerLevel level = player.serverLevel();
		AuraFx.helix(player, AuraFx.dust(0xFF7A1A, 1.3f), 30, 0.9, 2.2);
		AuraFx.burst(level, player.getX(), player.getY() + 1.0, player.getZ(), ParticleTypes.FLAME, 40, 0.6, 0.08);
		AuraFx.sound(level, player, AuraSounds.BLAZE_AMBIENT.value(), 0.8f, 1.2f);
		AuraFx.sound(level, player, AuraSounds.FIRECHARGE_USE.value(), 0.9f, 1.0f);
		player.displayClientMessage(Component.literal("✦ Flame Cloak ✦").withStyle(ChatFormatting.GOLD), true);
		used(player, Ability.FIRE_BENDING, now, CastMode.SNEAK);
		return true;
	}

	private static void tickCloaks(MinecraftServer server, long now) {
		for (Iterator<Map.Entry<UUID, Long>> it = CLOAKS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Long> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || !p.isAlive() || now >= e.getValue()) {
				it.remove();
				continue;
			}
			ServerLevel level = p.serverLevel();
			if (now % 2 == 0) {
				// one flame circling up the body
				double a = (now % 20) * Math.PI / 10;
				AuraFx.particles(level, ParticleTypes.FLAME, p.getX() + Math.cos(a) * 0.7, p.getY() + 0.3 + (now % 20) * 0.08,
						p.getZ() + Math.sin(a) * 0.7, 2, 0.05, 0.05, 0.05, 0.01);
			}
			if (now % 5 != 0) continue;
			for (LivingEntity target : Handhelds.enemies(p, level, p.position().add(0, p.getBbHeight() * 0.5, 0), AuraConfig.FIRE_BENDING_CLOAK_RADIUS)) {
				if (target.getRemainingFireTicks() > 0) continue;
				target.igniteForSeconds(AuraConfig.FIRE_BENDING_CLOAK_BURN_SECONDS);
				AuraStunts.fireHit(p, target);
				Vec3 c = AuraFx.chest(target);
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.FLAME, 8, 0.3, 0.05);
			}
		}
	}

	// ---- Water ----

	/** Diver's Bubble (sneak + Abyssal Bubble): a bubble for yourself, Water Breathing and Slow Falling, at a share of the cooldown. */
	private static boolean diversBubble(ServerPlayer player, long now) {
		if (AbyssalBubble.isHolding(player)) return false;
		if (!AuraAbilities.checkCooldown(player, Ability.ABYSSAL_BUBBLE, AuraConfig.BUBBLE_COOLDOWN_TICKS, now)) return false;
		int ticks = AuraConfig.BUBBLE_SELF_TICKS;
		player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, ticks, 0, false, false, true));
		player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, ticks, 0, false, false, true));
		ServerLevel level = player.serverLevel();
		Vec3 c = player.position().add(0, player.getBbHeight() * 0.5, 0);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(0x1B4D6E, 1.8f), 30, 1.0, 0.0);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.BUBBLE_POP, 30, 0.9, 0.05);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.NAUTILUS, 20, 0.9, 0.0);
		AuraFx.sound(level, player, AuraSounds.AMBIENT_UNDERWATER_ENTER.value(), 1.0f, 1.1f);
		AuraFx.sound(level, player, AuraSounds.BUBBLE_COLUMN_UPWARDS_AMBIENT.value(), 0.8f, 1.3f);
		player.displayClientMessage(Component.literal("✦ Diver's Bubble ✦").withStyle(ChatFormatting.DARK_AQUA), true);
		AuraStats.add(player, AuraStats.ABYSSAL_BUBBLE_USES, 1);
		used(player, Ability.ABYSSAL_BUBBLE, now, AuraConfig.BUBBLE_SELF_COOLDOWN_FACTOR);
		return true;
	}

	private record Spring(UUID owner, ServerLevel level, Vec3 at, long until) {
	}

	private static final List<Spring> SPRINGS = new ArrayList<>();

	/** Spring (hold Healing Tide): a healing pool where you stand for HEALING_TIDE_SPRING_TICKS; Regeneration to friends inside every second. */
	static void startSpring(ServerPlayer player, long now) {
		SPRINGS.removeIf(s -> s.owner.equals(player.getUUID()));
		SPRINGS.add(new Spring(player.getUUID(), player.serverLevel(), player.position(), now + AuraConfig.HEALING_TIDE_SPRING_TICKS));
	}

	private static void tickSprings(long now) {
		for (Iterator<Spring> it = SPRINGS.iterator(); it.hasNext(); ) {
			Spring s = it.next();
			if (now >= s.until) {
				it.remove();
				continue;
			}
			double r = AuraConfig.HEALING_TIDE_SPRING_RADIUS;
			if (now % 4 == 0) {
				AuraFx.ring(s.level, s.at.x, s.at.y + 0.1, s.at.z, r, AuraFx.dust(0x3EE6B0, 1.1f), (int) (r * 6));
				AuraFx.particles(s.level, ParticleTypes.BUBBLE_POP, s.at.x, s.at.y + 0.2, s.at.z, 3, r * 0.5, 0.1, r * 0.5, 0.0);
			}
			ServerPlayer owner = s.level.getServer().getPlayerList().getPlayer(s.owner);
			if (now % 20 != 0 || owner == null) continue;
			for (ServerPlayer p : s.level.getEntitiesOfClass(ServerPlayer.class, new AABB(s.at.subtract(r, 1, r), s.at.add(r, 2, r)),
					p -> p.isAlive() && !p.isSpectator() && p.position().distanceToSqr(s.at) <= r * r && Light.friend(owner, p))) {
				p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 25, 0, false, true, true));
				Vec3 c = AuraFx.chest(p);
				AuraFx.burst(s.level, c.x, c.y + 0.4, c.z, ParticleTypes.HEART, 1, 0.3, 0.0);
			}
		}
	}

	// ---- Shadow ----

	private record Phase(long until, Set<UUID> hit) {
	}

	private static final Map<UUID, Phase> PHASES = new HashMap<>();

	/** Phase Dash (hold Dash): for DASH_PHASE_TICKS, enemies the dasher passes through are hurt and blinded, once each. */
	static void startPhase(ServerPlayer player, long now) {
		PHASES.put(player.getUUID(), new Phase(now + AuraConfig.DASH_PHASE_TICKS, new HashSet<>()));
	}

	private static void tickPhases(MinecraftServer server, long now) {
		for (Iterator<Map.Entry<UUID, Phase>> it = PHASES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Phase> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || !p.isAlive() || now >= e.getValue().until) {
				it.remove();
				continue;
			}
			ServerLevel level = p.serverLevel();
			Vec3 c = p.position().add(0, p.getBbHeight() * 0.5, 0);
			AuraFx.particles(level, ParticleTypes.SQUID_INK, c.x, c.y, c.z, 3, 0.3, 0.4, 0.3, 0.02);
			for (LivingEntity target : Handhelds.enemies(p, level, c, 1.4)) {
				if (!e.getValue().hit.add(target.getUUID())) continue;
				target.hurt(level.damageSources().playerAttack(p), AuraAbilities.vsMob(target, AuraConfig.DASH_PHASE_DAMAGE));
				target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, AuraConfig.DASH_PHASE_BLIND_TICKS, 0, false, false, true));
				Vec3 t = AuraFx.chest(target);
				AuraFx.burst(level, t.x, t.y, t.z, ParticleTypes.SQUID_INK, 12, 0.4, 0.05);
				AuraFx.burst(level, t.x, t.y, t.z, AuraFx.dust(AuraType.SHADOW.rgb, 1.4f), 8, 0.4, 0.0);
				AuraFx.sound(level, p, AuraSounds.ENDERMAN_TELEPORT.value(), 0.5f, 1.8f);
			}
		}
	}

	// ---------------------------------------------------------------
	// Lore
	// ---------------------------------------------------------------

	/** The extra casts of an ability for the Aura Book and /aura info, one line each; empty without any (or with casts off). */
	public static List<String> describe(Ability ability) {
		List<String> out = new ArrayList<>(2);
		if (!AuraConfig.CAST_VARIANTS_ENABLED) return out;
		String sneak = sneak(ability), charged = charged(ability);
		if (sneak != null) {
			out.add("Sneak + key (Mastery " + Mastery.roman(AuraConfig.CAST_SNEAK_MASTERY) + "): " + sneak
					+ (AuraConfig.CAST_SNEAK_COOLDOWN_FACTOR != 1.0 && ability != Ability.DASH && ability != Ability.ABYSSAL_BUBBLE
					? " " + pct(AuraConfig.CAST_SNEAK_COOLDOWN_FACTOR) + "% cooldown." : ""));
		}
		if (charged != null) {
			out.add("Hold the key (Mastery " + Mastery.roman(AuraConfig.CAST_CHARGED_MASTERY) + "): " + charged
					+ (AuraConfig.CAST_CHARGED_COOLDOWN_FACTOR != 1.0 ? " " + pct(AuraConfig.CAST_CHARGED_COOLDOWN_FACTOR) + "% cooldown." : ""));
		}
		return out;
	}

	private static int pct(double factor) {
		return (int) Math.round(factor * 100);
	}

	private static String secs(int ticks) {
		return AuraAbilities.fmt(ticks / 20f) + "s";
	}

	private static String sneak(Ability a) {
		return switch (a) {
			case FIREBALL -> "Spread Shot - " + AuraConfig.FIREBALL_SPREAD_COUNT + " small fireballs in a fan, "
					+ pct(AuraConfig.FIREBALL_SPREAD_DAMAGE_FACTOR) + "% damage each.";
			case HEAT_WAVE -> "Flame Ward - a ring of fire " + AuraAbilities.fmt((float) AuraConfig.HEAT_WAVE_WARD_RADIUS) + " blocks around you, "
					+ pct(AuraConfig.HEAT_WAVE_WARD_DAMAGE_FACTOR) + "% damage, no knockback.";
			case FIRE_BENDING -> "Flame Cloak (with no flame in hand) - Fire Resistance for " + secs(AuraConfig.FIRE_BENDING_CLOAK_TICKS)
					+ "; enemies within " + AuraAbilities.fmt((float) AuraConfig.FIRE_BENDING_CLOAK_RADIUS) + " blocks catch fire.";
			case SHOCKWAVE -> "Undertow - pulls enemies in and soaks them (Wet), " + pct(AuraConfig.SHOCKWAVE_PULL_DAMAGE_FACTOR) + "% damage.";
			case HEALING_TIDE -> "Mending Tide - allies only, one Regeneration level higher, " + pct(AuraConfig.HEALING_TIDE_MENDING_RADIUS_FACTOR) + "% range.";
			case ABYSSAL_BUBBLE -> "Diver's Bubble - Water Breathing and Slow Falling for " + secs(AuraConfig.BUBBLE_SELF_TICKS) + ", "
					+ pct(AuraConfig.BUBBLE_SELF_COOLDOWN_FACTOR) + "% cooldown.";
			case DASH -> "Backstep - a short dash backwards, invisible for a moment, " + pct(AuraConfig.DASH_BACKSTEP_COOLDOWN_FACTOR) + "% cooldown.";
			case VANISH -> "Decoy - vanish and leave a shadow clone standing where you were.";
			default -> null;
		};
	}

	private static String charged(Ability a) {
		return switch (a) {
			case FIREBALL -> "Meteor - a slow, heavy fireball (" + pct(AuraConfig.FIREBALL_CHARGED_DAMAGE_FACTOR) + "% damage) that blasts and burns everything within "
					+ AuraAbilities.fmt((float) AuraConfig.FIREBALL_CHARGED_BLAST_RADIUS) + " blocks of the impact.";
			case HEAT_WAVE -> "Inferno Wave - " + pct(AuraConfig.HEAT_WAVE_CHARGED_RADIUS_FACTOR) + "% range, " + pct(AuraConfig.HEAT_WAVE_CHARGED_DAMAGE_FACTOR)
					+ "% damage, burns " + AuraAbilities.fmt(AuraConfig.HEAT_WAVE_CHARGED_BURN_FACTOR) + "x as long.";
			case SHOCKWAVE -> "Tidal Slam - " + pct(AuraConfig.SHOCKWAVE_CHARGED_RADIUS_FACTOR) + "% range, " + pct(AuraConfig.SHOCKWAVE_CHARGED_DAMAGE_FACTOR)
					+ "% damage, " + pct(AuraConfig.SHOCKWAVE_CHARGED_KNOCKBACK_FACTOR) + "% knockback.";
			case HEALING_TIDE -> "Spring - the tide also leaves a healing pool at your feet for " + secs(AuraConfig.HEALING_TIDE_SPRING_TICKS) + ".";
			case DASH -> "Phase Dash - " + pct(AuraConfig.DASH_PHASE_STRENGTH_FACTOR) + "% distance; enemies you pass through take "
					+ AuraAbilities.fmt(AuraConfig.DASH_PHASE_DAMAGE) + " damage and are blinded.";
			case VANISH -> "Deep Vanish - lasts " + pct(AuraConfig.VANISH_CHARGED_DURATION_FACTOR) + "% as long, but gives no Speed.";
			default -> null;
		};
	}
}
