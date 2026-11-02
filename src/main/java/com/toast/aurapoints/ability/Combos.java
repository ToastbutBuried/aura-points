package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.FxPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Team combos. Some abilities leave a zone for a moment: Fire (Heat Wave embers, a Fireball's fire, the
 * held Fire Bending orb), the Abyssal Bubble while it is held, and poison clouds (Entanglement's cloud, the
 * Toxic Cloud reaction). When an ALLY's ability passes through such a zone (Allies: same FTB party or arena
 * side), the zone is used up and turns into a combo:
 * <ul>
 * <li>Fire + Wind burst = Fire Tornado (pulls in, burns, flings everyone out at the end)</li>
 * <li>Bubble + Thunder = Electrified Bubble (zaps and paralyzes everyone inside)</li>
 * <li>Poison cloud + Wind burst = Toxic Gale (the cloud is blown along as a moving wall of poison)</li>
 * <li>Bubble + Ice Spike = Glacier (everyone inside frozen solid, then it shatters)</li>
 * <li>Fire + Earth Quake = Magma Field (molten ground: slows and burns)</li>
 * <li>Bubble + Fire = Boiling Bubble (scalding steam: burns and blinds)</li>
 * <li>Bubble + Prism Beam = Prism Burst (the beam splits into rays at the enemies around)</li>
 * </ul>
 * A combo only hurts what both players could hit and never either player's allies. The trigger player
 * deals the damage. The first enemy it touches pays both players (stats, weekly; Aura + mastery XP outside
 * the arena, once per COMBO_REWARD_COOLDOWN_TICKS). All switchable in aurapoints-combos.json. In memory only.
 */
public final class Combos {

	private Combos() {
	}

	public enum Kind {
		FIRE, BUBBLE, POISON
	}

	public enum Combo {
		FIRE_TORNADO("Fire Tornado", "🌪", 0xFF7A1F, Kind.FIRE, AuraType.WIND),
		ELECTRIFIED_BUBBLE("Electrified Bubble", "⚡", 0x7FC8FF, Kind.BUBBLE, AuraType.THUNDER),
		TOXIC_GALE("Toxic Gale", "☠", 0x7FCC3A, Kind.POISON, AuraType.WIND),
		GLACIER("Glacier", "❄", 0xA8E4FF, Kind.BUBBLE, AuraType.FROST),
		MAGMA_FIELD("Magma Field", "🌋", 0xD8481A, Kind.FIRE, AuraType.EARTH),
		BOILING_BUBBLE("Boiling Bubble", "♨", 0xDDE6EE, Kind.BUBBLE, AuraType.FIRE),
		PRISM_BURST("Prism Burst", "✦", 0xFFE9A8, Kind.BUBBLE, AuraType.LIGHT);

		public final String displayName;
		final String symbol;
		final int rgb;
		final Kind zone;
		final AuraType trigger;

		Combo(String displayName, String symbol, int rgb, Kind zone, AuraType trigger) {
			this.displayName = displayName;
			this.symbol = symbol;
			this.rgb = rgb;
			this.zone = zone;
			this.trigger = trigger;
		}

		public String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		public boolean enabled() {
			return switch (this) {
				case FIRE_TORNADO -> AuraConfig.COMBO_TORNADO_ENABLED;
				case ELECTRIFIED_BUBBLE -> AuraConfig.COMBO_SHOCK_ENABLED;
				case TOXIC_GALE -> AuraConfig.COMBO_GALE_ENABLED;
				case GLACIER -> AuraConfig.COMBO_GLACIER_ENABLED;
				case MAGMA_FIELD -> AuraConfig.COMBO_MAGMA_ENABLED;
				case BOILING_BUBBLE -> AuraConfig.COMBO_BOIL_ENABLED;
				case PRISM_BURST -> AuraConfig.COMBO_PRISM_ENABLED;
			};
		}

		static Combo of(Kind zone, AuraType trigger) {
			for (Combo c : values()) if (c.zone == zone && c.trigger == trigger) return c;
			return null;
		}
	}

	// ---------------------------------------------------------------
	// Zones
	// ---------------------------------------------------------------

	/** A zone someone left: where (may move), how big, until when, and how it is used up. */
	public static final class Zone {
		final UUID owner;
		final ServerLevel level;
		final Kind kind;
		final Supplier<Vec3> center;
		final double radius;
		final long until;
		final BooleanSupplier alive;
		final Runnable consume;
		/** Static zones draw a faint outline so allies can see them (the bubble, orb and clouds show themselves). */
		final boolean drawn;
		boolean gone;

		Zone(UUID owner, ServerLevel level, Kind kind, Supplier<Vec3> center, double radius, long until, BooleanSupplier alive, Runnable consume, boolean drawn) {
			this.owner = owner;
			this.level = level;
			this.kind = kind;
			this.center = center;
			this.radius = radius;
			this.until = until;
			this.alive = alive;
			this.consume = consume;
			this.drawn = drawn;
		}

		/** Ends the zone without a combo (its ability ended on its own). */
		public void end() {
			gone = true;
		}
	}

	private static final List<Zone> ZONES = new ArrayList<>();

	/** A zone that stays put for {@code ticks}. {@code consume} (may be null) runs when a combo uses it up. */
	public static Zone zone(ServerPlayer owner, Kind kind, Vec3 at, double radius, int ticks, Runnable consume, boolean drawn) {
		if (!AuraConfig.COMBO_ENABLED || ticks <= 0 || radius <= 0 || owner.getServer() == null) return null;
		Zone z = new Zone(owner.getUUID(), owner.serverLevel(), kind, () -> at, radius, owner.getServer().getTickCount() + ticks, () -> true, consume, drawn);
		ZONES.add(z);
		return z;
	}

	/** A zone that moves ({@code center}) and lasts while {@code alive}, at most {@code maxTicks}. */
	public static Zone follow(ServerPlayer owner, Kind kind, Supplier<Vec3> center, double radius, int maxTicks, BooleanSupplier alive, Runnable consume) {
		if (!AuraConfig.COMBO_ENABLED || radius <= 0 || owner.getServer() == null) return null;
		Zone z = new Zone(owner.getUUID(), owner.serverLevel(), kind, center, radius, owner.getServer().getTickCount() + Math.max(1, maxTicks), alive, consume, false);
		ZONES.add(z);
		return z;
	}

	// ---------------------------------------------------------------
	// Triggers
	// ---------------------------------------------------------------

	/** An ability of {@code by} reaching {@code r} around {@code at}. */
	public static boolean at(ServerPlayer by, Vec3 at, double r) {
		return pass(by, at, at, r, null);
	}

	/**
	 * An ability of {@code by} passing along {@code from} -> {@code to}, {@code r} thick ({@code dir}: where it
	 * blows, null = along the path). Sets off at most one combo; true when it did.
	 */
	public static boolean pass(ServerPlayer by, Vec3 from, Vec3 to, double r, Vec3 dir) {
		if (!AuraConfig.COMBO_ENABLED || ZONES.isEmpty() || by == null || by.getServer() == null) return false;
		MinecraftServer server = by.getServer();
		AuraType aura = AuraState.get(server).getOrCreate(by.getUUID()).aura;
		if (aura == null) return false;
		long now = server.getTickCount();
		Zone best = null;
		double bestD = Double.MAX_VALUE;
		for (Zone z : ZONES) {
			if (z.gone || z.until <= now || z.level != by.serverLevel() || z.owner.equals(by.getUUID())) continue;
			Combo combo = Combo.of(z.kind, aura);
			if (combo == null || !combo.enabled() || !z.alive.getAsBoolean()) continue;
			Vec3 c = z.center.get();
			double d = segmentDistance(c, from, to);
			if (d > z.radius + r || d >= bestD) continue;
			ServerPlayer owner = player(server, z.owner);
			if (owner == null || !Allies.allied(owner, by)) continue;
			best = z;
			bestD = d;
		}
		if (best == null) return false;
		Vec3 blow = dir != null ? dir : to.subtract(from);
		if (blow.lengthSqr() < 1.0e-6) blow = by.getLookAngle();
		start(Combo.of(best.kind, aura), best, player(server, best.owner), by, blow.multiply(1, 0, 1));
		return true;
	}

	/** Closest distance from {@code p} to the segment a-b. */
	static double segmentDistance(Vec3 p, Vec3 a, Vec3 b) {
		Vec3 ab = b.subtract(a);
		double len = ab.lengthSqr();
		if (len < 1.0e-9) return p.distanceTo(a);
		double t = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len));
		return p.distanceTo(a.add(ab.scale(t)));
	}

	// ---------------------------------------------------------------
	// Combos in play
	// ---------------------------------------------------------------

	private abstract static class Effect {
		final Combo combo;
		/** zone owner and trigger player (the one who deals the damage) */
		final ServerPlayer owner, by;
		final ServerLevel level;
		Vec3 c;
		final double r;
		int t;
		boolean paid;
		final Set<LivingEntity> touched = new HashSet<>();

		Effect(Combo combo, ServerPlayer owner, ServerPlayer by, Vec3 c, double r) {
			this.combo = combo;
			this.owner = owner;
			this.by = by;
			this.level = by.serverLevel();
			this.c = c;
			this.r = r;
		}

		/** One tick; false when done. */
		abstract boolean tick(long now);

		boolean enemy(LivingEntity e) {
			if (!Handhelds.enemy(by, e) || !Handhelds.enemy(owner, e)) return false;
			return !(e instanceof ServerPlayer p && (Allies.allied(by, p) || Allies.allied(owner, p)));
		}

		/** Enemies whose middle is within {@code radius} of {@code at}. */
		List<LivingEntity> enemies(Vec3 at, double radius) {
			return level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius),
					e -> enemy(e) && AuraFx.chest(e).distanceToSqr(at) <= radius * radius);
		}

		/** The combo reaches {@code e}: the first enemy ever pays both players. */
		void touch(LivingEntity e) {
			touched.add(e);
			if (!paid) {
				paid = true;
				reward(this);
			}
		}

		void hurt(LivingEntity e, float damage) {
			touch(e);
			if (damage > 0 && AuraFx.alive(by)) Reactions.hurt(by, e, damage);
		}
	}

	private static final List<Effect> EFFECTS = new ArrayList<>();
	/** player -> tick their next combo reward is ready */
	private static final Map<UUID, Long> REWARD_READY = new HashMap<>();

	private static void start(Combo combo, Zone z, ServerPlayer owner, ServerPlayer by, Vec3 dir) {
		z.gone = true;
		Vec3 c = z.center.get();
		double r = z.radius;
		if (z.consume != null) z.consume.run();
		Vec3 d = dir.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : dir.normalize();
		Effect e = switch (combo) {
			case FIRE_TORNADO -> new Tornado(owner, by, c, d);
			case ELECTRIFIED_BUBBLE -> new Shock(owner, by, c, r);
			case TOXIC_GALE -> new Gale(owner, by, c, d);
			case GLACIER -> new Glacier(owner, by, c, r);
			case MAGMA_FIELD -> new Magma(owner, by, c, r);
			case BOILING_BUBBLE -> new Boil(owner, by, c, r);
			case PRISM_BURST -> new Prism(owner, by, c, r);
		};
		EFFECTS.add(e);
		// the call-out: both players, and a burst everyone can see
		Component line = Component.literal(combo.symbol + " " + combo.displayName + "! ")
				.withStyle(s -> s.withColor(TextColor.fromRgb(combo.rgb)).withBold(true));
		owner.displayClientMessage(line.copy().append(Component.literal("with " + by.getGameProfile().getName()).withStyle(s -> s.withBold(false))), true);
		by.displayClientMessage(line.copy().append(Component.literal("with " + owner.getGameProfile().getName()).withStyle(s -> s.withBold(false))), true);
		AuraFx.burst(e.level, c.x, c.y, c.z, AuraFx.dust(combo.rgb, 2.2f), 40, Math.max(1.0, r * 0.5), 0.0);
		AuraFx.burst(e.level, c.x, c.y, c.z, ParticleTypes.FLASH, 1, 0, 0);
		sound(e.level, c, AuraSounds.BEACON_ACTIVATE.value(), 1.2f, 1.6f);
		sound(e.level, c, AuraSounds.GENERIC_EXPLODE.value(), 0.6f, 1.4f);
	}

	/** Stats, the weekly, a stunt and (outside the arena, not too often) Aura and mastery XP, for both players. */
	private static void reward(Effect e) {
		MinecraftServer server = e.by.getServer();
		if (server == null) return;
		long now = server.getTickCount();
		ResourceLocation stat = AuraStats.combo(e.combo.id());
		for (ServerPlayer p : List.of(e.owner, e.by)) {
			if (!AuraFx.alive(p)) continue;
			// counted in the arena too (Wave Survival is where teams fight together): straight to the stat
			if (stat != null) p.awardStat(stat, 1);
			if (AuraAbilities.progressPaused(p)) continue;
			Long ready = REWARD_READY.get(p.getUUID());
			if (ready == null || ready <= now) {
				REWARD_READY.put(p.getUUID(), now + Math.max(0, AuraConfig.COMBO_REWARD_COOLDOWN_TICKS));
				if (AuraConfig.COMBO_AURA_REWARD > 0) AuraCurrency.add(p, AuraConfig.COMBO_AURA_REWARD);
				Mastery.addXp(p, AuraConfig.COMBO_MASTERY_XP);
			}
			var holder = server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "combo/" + e.combo.id()));
			if (holder != null && !p.getAdvancements().getOrStartProgress(holder).isDone() && p.getAdvancements().award(holder, "done")) {
				NetworkHandler.sendFx(p, FxPacket.STUNT, 0, e.combo.id() + "_combo");
				AuraStunts.xp(p, false);
			}
		}
	}

	// ---------------------------------------------------------------
	// The seven
	// ---------------------------------------------------------------

	/** Fire + Wind: a burning tornado drifts along the wind, sucks enemies in, then flings them out. */
	private static final class Tornado extends Effect {
		final Vec3 drift;

		Tornado(ServerPlayer owner, ServerPlayer by, Vec3 c, Vec3 dir) {
			super(Combo.FIRE_TORNADO, owner, by, c, Math.max(1, AuraConfig.COMBO_TORNADO_RADIUS));
			this.drift = dir.scale(Math.max(0, AuraConfig.COMBO_TORNADO_SPEED));
			sound(level, c, AuraSounds.BREEZE_WIND_CHARGE_BURST.value(), 1.4f, 0.6f);
			sound(level, c, AuraSounds.BLAZE_SHOOT.value(), 1.2f, 0.6f);
		}

		@Override
		boolean tick(long now) {
			int life = Math.max(10, AuraConfig.COMBO_TORNADO_TICKS);
			c = c.add(drift);
			// the funnel: a rising spiral of flame, wider at the top
			double spin = t * 0.45;
			for (int i = 0; i < 14; i++) {
				double h = i * 0.45;
				double rr = 0.4 + h * 0.35;
				double a = spin + i * 0.9;
				AuraFx.particles(level, i % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME,
						c.x + Math.cos(a) * rr, c.y + h, c.z + Math.sin(a) * rr, 1, 0, 0, 0, 0);
			}
			if (t % 4 == 0) AuraFx.particles(level, ParticleTypes.LAVA, c.x, c.y + 0.3, c.z, 2, 0.6, 0.2, 0.6, 0);
			if (t % 20 == 0) sound(level, c, AuraSounds.BREEZE_IDLE_AIR.value(), 1.0f, 0.6f);
			if (t + 1 >= life) {
				// the end: everyone inside flung out and hurt
				for (LivingEntity e : enemies(c.add(0, 1, 0), r + 1)) {
					Vec3 out = e.position().subtract(c).multiply(1, 0, 1);
					out = out.lengthSqr() < 1.0e-4 ? drift.normalize() : out.normalize();
					double f = AuraConfig.COMBO_TORNADO_FLING;
					e.setDeltaMovement(out.x * f, 0.5 + f * 0.4, out.z * f);
					e.hurtMarked = true;
					hurt(e, AuraConfig.COMBO_TORNADO_FLING_DAMAGE);
				}
				AuraFx.burst(level, c.x, c.y + 1.5, c.z, ParticleTypes.FLAME, 80, r * 0.6, 0.2);
				sound(level, c, AuraSounds.WIND_CHARGE_BURST.value(), 1.5f, 0.6f);
				return false;
			}
			boolean hit = t % Math.max(1, AuraConfig.COMBO_TORNADO_HIT_TICKS) == 0;
			for (LivingEntity e : enemies(c.add(0, 1, 0), r)) {
				Vec3 in = c.subtract(e.position()).multiply(1, 0, 1);
				double dist = in.length();
				if (dist > 0.3) {
					Vec3 side = new Vec3(-in.z, 0, in.x).normalize();
					Vec3 pull = in.normalize().scale(AuraConfig.COMBO_TORNADO_PULL).add(side.scale(AuraConfig.COMBO_TORNADO_PULL * 0.8));
					Vec3 v = e.getDeltaMovement();
					e.setDeltaMovement(v.x * 0.6 + pull.x, Math.max(v.y, 0.06), v.z * 0.6 + pull.z);
					e.hurtMarked = true;
				}
				touch(e);
				if (hit) {
					if (!e.fireImmune()) e.igniteForSeconds(AuraConfig.COMBO_TORNADO_FIRE_SECONDS);
					hurt(e, AuraConfig.COMBO_TORNADO_DAMAGE);
				}
			}
			return ++t < life;
		}
	}

	/** Bubble + Thunder: the bubble crackles; everyone inside is zapped and paralyzed again and again. */
	private static final class Shock extends Effect {
		Shock(ServerPlayer owner, ServerPlayer by, Vec3 c, double r) {
			super(Combo.ELECTRIFIED_BUBBLE, owner, by, c, r);
			sound(level, c, AuraSounds.TRIDENT_THUNDER.value(), 1.4f, 1.2f);
		}

		@Override
		boolean tick(long now) {
			shell(level, c, r, ParticleTypes.ELECTRIC_SPARK, 18, t);
			if (t % 2 == 0) shell(level, c, r, AuraFx.dust(Combo.ELECTRIFIED_BUBBLE.rgb, 1.2f), 10, t + 7);
			if (t % Math.max(1, AuraConfig.COMBO_SHOCK_HIT_TICKS) == 0) {
				List<LivingEntity> inside = enemies(c, r);
				Vec3 from = c;
				for (LivingEntity e : inside) {
					Vec3 to = AuraFx.chest(e);
					Thunderbolt.bolt(level, from, to, 0.6f, 1, 2);
					from = to;
					hurt(e, AuraConfig.COMBO_SHOCK_DAMAGE);
					Thunderbolt.charge(e, 1);
					paralyze(e, AuraConfig.COMBO_SHOCK_PARALYZE_TICKS);
				}
				if (!inside.isEmpty()) sound(level, c, AuraSounds.LIGHTNING_BOLT_IMPACT.value(), 0.8f, 1.6f);
			}
			return ++t < Math.max(1, AuraConfig.COMBO_SHOCK_TICKS);
		}
	}

	/** Poison cloud + Wind: the cloud rolls along the wind as a wall of poison, hitting each enemy once. */
	private static final class Gale extends Effect {
		final Vec3 dir;

		Gale(ServerPlayer owner, ServerPlayer by, Vec3 c, Vec3 dir) {
			super(Combo.TOXIC_GALE, owner, by, c, Math.max(1, AuraConfig.COMBO_GALE_RADIUS));
			this.dir = dir;
			sound(level, c, AuraSounds.BREEZE_WIND_CHARGE_BURST.value(), 1.2f, 0.8f);
			sound(level, c, AuraSounds.PUFFER_FISH_BLOW_UP.value(), 1.2f, 0.5f);
		}

		@Override
		boolean tick(long now) {
			c = c.add(dir.scale(Math.max(0, AuraConfig.COMBO_GALE_SPEED)));
			// a wall across the wind
			Vec3 across = new Vec3(-dir.z, 0, dir.x);
			for (int i = -4; i <= 4; i++) {
				Vec3 p = c.add(across.scale(r * i / 4.0));
				AuraFx.particles(level, AuraFx.dust(Combo.TOXIC_GALE.rgb, 2.0f), p.x, p.y + 0.4, p.z, 2, 0.3, 0.6, 0.3, 0);
			}
			if (t % 3 == 0) AuraFx.particles(level, ParticleTypes.SNEEZE, c.x, c.y + 0.8, c.z, 4, r * 0.5, 0.4, r * 0.5, 0.02);
			for (LivingEntity e : enemies(c.add(0, 0.8, 0), r)) {
				Vec3 v = e.getDeltaMovement();
				double push = AuraConfig.COMBO_GALE_PUSH;
				e.setDeltaMovement(v.x + dir.x * push * 0.3, v.y, v.z + dir.z * push * 0.3);
				e.hurtMarked = true;
				if (touched.contains(e)) continue;
				e.addEffect(new MobEffectInstance(MobEffects.POISON, Math.max(1, AuraConfig.COMBO_GALE_POISON_TICKS),
						Math.max(0, AuraConfig.COMBO_GALE_POISON_AMPLIFIER), false, true, true), by);
				e.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 80, 0, false, false, true), by);
				hurt(e, AuraConfig.COMBO_GALE_DAMAGE);
			}
			return ++t < Math.max(1, AuraConfig.COMBO_GALE_TICKS);
		}
	}

	/** Bubble + Ice Spike: it freezes into a glacier, everyone inside held fast, then it shatters. */
	private static final class Glacier extends Effect {
		final List<LivingEntity> held = new ArrayList<>();

		Glacier(ServerPlayer owner, ServerPlayer by, Vec3 c, double r) {
			super(Combo.GLACIER, owner, by, c, r);
			sound(level, c, AuraSounds.GLASS_BREAK.value(), 1.2f, 0.6f);
			sound(level, c, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), 1.5f, 0.5f);
		}

		@Override
		boolean tick(long now) {
			int life = Math.max(1, AuraConfig.COMBO_GLACIER_FREEZE_TICKS);
			if (t == 0) {
				for (LivingEntity e : enemies(c, r)) {
					held.add(e);
					touch(e);
					IceSpike.freeze(e, now, life);
				}
			}
			shell(level, c, r, ParticleTypes.SNOWFLAKE, 16, t);
			if (t % 2 == 0) shell(level, c, r, AuraFx.dust(Combo.GLACIER.rgb, 1.8f), 14, t + 5);
			if (t + 1 >= life) {
				for (LivingEntity e : held) if (e.isAlive()) hurt(e, AuraConfig.COMBO_GLACIER_SHATTER_DAMAGE);
				for (LivingEntity e : enemies(c, r + 1)) if (!held.contains(e)) hurt(e, AuraConfig.COMBO_GLACIER_SHATTER_DAMAGE * 0.5f);
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.SNOWFLAKE, 80, r * 0.6, 0.15);
				AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(Combo.GLACIER.rgb, 2.0f), 50, r * 0.6, 0.0);
				sound(level, c, AuraSounds.GLASS_BREAK.value(), 1.6f, 0.8f);
				return false;
			}
			return ++t < life;
		}
	}

	/** Fire + Quake: the ground melts; enemies on it are slowed and burn. */
	private static final class Magma extends Effect {
		Magma(ServerPlayer owner, ServerPlayer by, Vec3 c, double r) {
			super(Combo.MAGMA_FIELD, owner, by, c, Math.max(2, r));
			sound(level, c, AuraSounds.LAVA_POP.value(), 1.5f, 0.6f);
			sound(level, c, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), 1.2f, 0.6f);
		}

		@Override
		boolean tick(long now) {
			Vec3 ground = new Vec3(c.x, Math.floor(c.y) + 0.1, c.z);
			for (int i = 0; i < 6; i++) {
				double a = level.getRandom().nextDouble() * Math.PI * 2, d = Math.sqrt(level.getRandom().nextDouble()) * r;
				AuraFx.particles(level, i % 2 == 0 ? ParticleTypes.LAVA : AuraFx.dust(Combo.MAGMA_FIELD.rgb, 1.6f),
						ground.x + Math.cos(a) * d, ground.y, ground.z + Math.sin(a) * d, 1, 0, 0, 0, 0);
			}
			if (t % 4 == 0) AuraFx.ring(level, ground.x, ground.y, ground.z, r, ParticleTypes.FLAME, (int) Math.max(12, r * 5));
			if (t % 25 == 0) sound(level, c, AuraSounds.LAVA_POP.value(), 1.0f, 0.8f);
			boolean hit = t % Math.max(1, AuraConfig.COMBO_MAGMA_HIT_TICKS) == 0;
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(ground, ground).inflate(r, 2.0, r),
					e -> enemy(e) && e.position().multiply(1, 0, 1).distanceToSqr(ground.multiply(1, 0, 1)) <= r * r)) {
				touch(e);
				e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, Math.max(0, AuraConfig.COMBO_MAGMA_SLOW_AMPLIFIER), false, false, true), by);
				if (hit) {
					if (!e.fireImmune()) e.igniteForSeconds(AuraConfig.COMBO_MAGMA_FIRE_SECONDS);
					hurt(e, AuraConfig.COMBO_MAGMA_DAMAGE);
				}
			}
			return ++t < Math.max(1, AuraConfig.COMBO_MAGMA_TICKS);
		}
	}

	/** Bubble + Fire: the water boils; scalding steam burns and blinds everyone inside. */
	private static final class Boil extends Effect {
		Boil(ServerPlayer owner, ServerPlayer by, Vec3 c, double r) {
			super(Combo.BOILING_BUBBLE, owner, by, c, r);
			sound(level, c, AuraSounds.FIRE_EXTINGUISH.value(), 1.6f, 0.6f);
		}

		@Override
		boolean tick(long now) {
			shell(level, c, r, ParticleTypes.CLOUD, 10, t);
			if (t % 2 == 0) AuraFx.particles(level, ParticleTypes.BUBBLE_POP, c.x, c.y, c.z, 8, r * 0.5, r * 0.5, r * 0.5, 0.02);
			if (t % 3 == 0) AuraFx.particles(level, ParticleTypes.WHITE_SMOKE, c.x, c.y, c.z, 6, r * 0.4, r * 0.4, r * 0.4, 0.03);
			if (t % 20 == 0) sound(level, c, AuraSounds.FIRE_EXTINGUISH.value(), 0.8f, 0.9f);
			if (t % Math.max(1, AuraConfig.COMBO_BOIL_HIT_TICKS) == 0) {
				for (LivingEntity e : enemies(c, r)) {
					if (AuraConfig.COMBO_BOIL_BLIND_TICKS > 0) {
						e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, AuraConfig.COMBO_BOIL_BLIND_TICKS, 0, false, false, true), by);
					}
					hurt(e, AuraConfig.COMBO_BOIL_DAMAGE);
				}
			}
			return ++t < Math.max(1, AuraConfig.COMBO_BOIL_TICKS);
		}
	}

	/** Bubble + Prism Beam: the water splits the light into rays that strike the nearest enemies around. */
	private static final class Prism extends Effect {
		Prism(ServerPlayer owner, ServerPlayer by, Vec3 c, double r) {
			super(Combo.PRISM_BURST, owner, by, c, r);
		}

		@Override
		boolean tick(long now) {
			if (t == 0) {
				List<LivingEntity> near = new ArrayList<>(enemies(c, r + Math.max(0, AuraConfig.COMBO_PRISM_REACH)));
				near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(c)));
				int n = Math.min(near.size(), Math.max(0, AuraConfig.COMBO_PRISM_RAYS));
				int[] colors = {0xFF5A5A, 0xFFB347, 0xFFF06A, 0x6AFF8A, 0x6AC8FF, 0xB06AFF};
				for (int i = 0; i < n; i++) {
					LivingEntity e = near.get(i);
					Vec3 to = AuraFx.chest(e);
					AuraFx.line(level, c, to, AuraFx.dust(colors[i % colors.length], 1.3f), (int) Math.max(6, c.distanceTo(to) * 4));
					AuraFx.line(level, c, to, ParticleTypes.END_ROD, (int) Math.max(3, c.distanceTo(to) * 1.5));
					hurt(e, AuraConfig.COMBO_PRISM_DAMAGE);
					if (AuraConfig.COMBO_PRISM_GLOW_TICKS > 0) e.addEffect(new MobEffectInstance(MobEffects.GLOWING, AuraConfig.COMBO_PRISM_GLOW_TICKS, 0, false, false, true), by);
				}
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.END_ROD, 40, r * 0.4, 0.2);
				sound(level, c, AuraSounds.AMETHYST_BLOCK_CHIME.value(), 2.0f, 1.2f);
				sound(level, c, AuraSounds.BEACON_ACTIVATE.value(), 1.4f, 2.0f);
			}
			shell(level, c, r, AuraFx.dust(Combo.PRISM_BURST.rgb, 1.4f), 14, t);
			return ++t < 10;
		}
	}

	// ---------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------

	private static void paralyze(LivingEntity e, int ticks) {
		if (ticks <= 0) return;
		if (e instanceof ServerPlayer victim) Stun.stun(victim, ticks);
		else e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 9, false, false, true));
	}

	/** {@code n} points scattered over a sphere's surface (golden spiral, turning with {@code seed}). */
	private static void shell(ServerLevel level, Vec3 c, double r, ParticleOptions particle, int n, int seed) {
		double golden = Math.PI * (3 - Math.sqrt(5));
		for (int i = 0; i < n; i++) {
			double y = 1 - (i + 0.5) / n * 2;
			double rad = Math.sqrt(1 - y * y);
			double a = golden * i + seed * 0.37;
			AuraFx.particles(level, particle, c.x + Math.cos(a) * rad * r, c.y + y * r, c.z + Math.sin(a) * rad * r, 1, 0, 0, 0, 0);
		}
	}

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
	}

	// ---------------------------------------------------------------
	// Every tick
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		long now = server.getTickCount();
		if (!ZONES.isEmpty()) {
			for (Iterator<Zone> it = ZONES.iterator(); it.hasNext(); ) {
				Zone z = it.next();
				if (z.gone || z.until <= now || !z.alive.getAsBoolean() || player(server, z.owner) == null) {
					it.remove();
					continue;
				}
				if (z.drawn && now % 4 == 0 && z.kind == Kind.FIRE) {
					Vec3 c = z.center.get();
					AuraFx.ring(z.level, c.x, c.y + 0.1, c.z, z.radius * 0.8, AuraFx.dust(0xFFB020, 1.0f), (int) Math.max(8, z.radius * 4));
					AuraFx.particles(z.level, ParticleTypes.SMALL_FLAME, c.x, c.y + 0.2, c.z, 2, z.radius * 0.4, 0.1, z.radius * 0.4, 0.01);
				}
			}
		}
		if (!EFFECTS.isEmpty()) {
			// copied: an effect's damage can set off another trigger (rare) that adds to the list
			for (Effect e : List.copyOf(EFFECTS)) {
				boolean keep;
				try {
					keep = AuraFx.alive(e.by) && e.by.serverLevel() == e.level && e.tick(now);
				} catch (RuntimeException ex) {
					AuraPoints.LOGGER.error("Team combo {} failed", e.combo, ex);
					keep = false;
				}
				if (!keep) EFFECTS.remove(e);
			}
		}
		if (!REWARD_READY.isEmpty() && now % 200 == 0) REWARD_READY.values().removeIf(t -> t <= now);
	}

	public static void clearAll() {
		ZONES.clear();
		EFFECTS.clear();
		REWARD_READY.clear();
	}

	// ---------------------------------------------------------------
	// Dev test
	// ---------------------------------------------------------------

	/** Fake players of the dev test (not in the player list). */
	public static final Map<UUID, ServerPlayer> TEST_PLAYERS = new HashMap<>();

	private static ServerPlayer player(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null || TEST_PLAYERS.isEmpty() ? p : TEST_PLAYERS.get(id);
	}

	public static int zoneCount() {
		ZONES.removeIf(z -> z.gone);
		return ZONES.size();
	}

	public static int effectCount() {
		return EFFECTS.size();
	}

	/** "fire_tornado,glacier" - the combos running now. */
	public static String running() {
		StringBuilder out = new StringBuilder();
		for (Effect e : EFFECTS) out.append(out.isEmpty() ? "" : ",").append(e.combo.id());
		return out.isEmpty() ? "none" : out.toString();
	}
}
