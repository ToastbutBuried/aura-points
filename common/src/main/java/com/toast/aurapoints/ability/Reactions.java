package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.FxPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Elemental reactions. An ability hit leaves its aura's element on the target as a mark for
 * REACTION_MARK_TICKS (Water: Wet, Fire: Burning, Frost: Chilled, Verdant: Poisoned, Thunder: Charged;
 * Wind leaves none). A later ability hit whose element matches a mark sets off a reaction and uses the
 * mark up. Water/rain, fire, Poison and powder snow count as marks too (REACTION_ENVIRONMENT_MARKS).
 * Enemies only (Handhelds.enemy), one reaction per target per REACTION_COOLDOWN_TICKS, each one
 * switchable in aurapoints-reactions.json. Nothing here is saved.
 */
public final class Reactions {

	private Reactions() {
	}

	/** The element an ability hit carries: its attacker's aura. */
	enum Element {
		WATER(Mark.WET), FIRE(Mark.BURNING), FROST(Mark.CHILLED), VERDANT(Mark.POISONED), THUNDER(Mark.CHARGED), WIND(null);

		/** The mark this element leaves, or null. */
		final Mark leaves;

		Element(Mark leaves) {
			this.leaves = leaves;
		}

		static Element of(AuraType aura) {
			if (aura == null) return null;
			return switch (aura) {
				case WATER -> WATER;
				case FIRE -> FIRE;
				case FROST -> FROST;
				case VERDANT -> VERDANT;
				case THUNDER -> THUNDER;
				case WIND -> WIND;
				default -> null;
			};
		}
	}

	public enum Mark {
		WET(0x2E9BFF), BURNING(0xFF5A1F), CHILLED(0xA8E4FF), POISONED(0x4FB848), CHARGED(0xFFE347);

		final int rgb;

		Mark(int rgb) {
			this.rgb = rgb;
		}

		/** The world puts this mark on by itself right now (never used up by a reaction). */
		boolean environment(LivingEntity e) {
			return switch (this) {
				case WET -> e.isInWaterOrRain() || e.isInWaterRainOrBubble();
				case BURNING -> e.isOnFire();
				case CHILLED -> e.getTicksFrozen() > 0;
				case POISONED -> e.hasEffect(MobEffects.POISON);
				case CHARGED -> false;
			};
		}
	}

	/** For the Aura Book: the name of the mark this aura's hits leave, or null (Wind and the auras without a reaction element). */
	public static String markLeftBy(AuraType aura) {
		Element e = Element.of(aura);
		if (e == null || e.leaves == null) return null;
		return e.leaves.name().charAt(0) + e.leaves.name().substring(1).toLowerCase(java.util.Locale.ROOT);
	}

	/** Checked in this order: the first enabled one whose mark the target has goes off. */
	public enum Reaction {
		FREEZE("Freeze", "❄", 0xA8E4FF, Mark.WET, Element.FROST),
		CONDUCT("Conduct", "⚡", 0xFFE347, Mark.WET, Element.THUNDER),
		FIRESTORM("Firestorm", "🔥", 0xFF7A1F, Mark.BURNING, Element.WIND),
		TOXIC_CLOUD("Toxic Cloud", "☠", 0x7FCC3A, Mark.POISONED, Element.FIRE),
		STEAM("Steam", "♨", 0xDDE6EE, Mark.BURNING, Element.WATER),
		MELT("Melt", "✹", 0xFF9A5A, Mark.CHILLED, Element.FIRE),
		OVERGROWTH("Overgrowth", "☘", 0x4FB848, Mark.WET, Element.VERDANT),
		STORM("Storm", "☈", 0xCFE8FF, Mark.CHARGED, Element.WIND);

		public final String displayName;
		final String symbol;
		final int rgb;
		final Mark mark;
		final Element trigger;

		Reaction(String displayName, String symbol, int rgb, Mark mark, Element trigger) {
			this.displayName = displayName;
			this.symbol = symbol;
			this.rgb = rgb;
			this.mark = mark;
			this.trigger = trigger;
		}

		public String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		/** For the Aura Book: the icon character, the colour, the mark it needs and the aura whose hit sets it off. */
		public String symbol() {
			return symbol;
		}

		public int rgb() {
			return rgb;
		}

		public String markName() {
			return mark.name().charAt(0) + mark.name().substring(1).toLowerCase(java.util.Locale.ROOT);
		}

		public int markRgb() {
			return mark.rgb;
		}

		public AuraType triggerAura() {
			return AuraType.valueOf(trigger.name());
		}

		/** What it does, with the numbers as configured. */
		public String effect() {
			return switch (this) {
				case FREEZE -> "Freezes the target for " + secs(AuraConfig.REACTION_FREEZE_TICKS) + "s and deals " + dmg(AuraConfig.REACTION_FREEZE_DAMAGE) + ".";
				case CONDUCT -> dmg(AuraConfig.REACTION_CONDUCT_DAMAGE) + ", and it arcs on to up to " + AuraConfig.REACTION_CONDUCT_CHAIN_MAX
						+ " wet enemies within " + Math.round(AuraConfig.REACTION_CONDUCT_CHAIN_RADIUS) + " blocks.";
				case FIRESTORM -> "A burst of fire in " + Math.round(AuraConfig.REACTION_FIRESTORM_RADIUS) + " blocks: " + dmg(AuraConfig.REACTION_FIRESTORM_DAMAGE)
						+ " and everyone burns for " + AuraConfig.REACTION_FIRESTORM_FIRE_SECONDS + "s.";
				case TOXIC_CLOUD -> "A poison cloud (" + Math.round(AuraConfig.REACTION_TOXIC_RADIUS) + " blocks, " + secs(AuraConfig.REACTION_TOXIC_TICKS) + "s): "
						+ dmg(AuraConfig.REACTION_TOXIC_DAMAGE) + " and Poison.";
				case STEAM -> "Scalding steam in " + Math.round(AuraConfig.REACTION_STEAM_RADIUS) + " blocks: " + dmg(AuraConfig.REACTION_STEAM_DAMAGE)
						+ " and blindness for " + secs(AuraConfig.REACTION_STEAM_BLIND_TICKS) + "s.";
				case MELT -> "The hit deals " + Math.round(AuraConfig.REACTION_MELT_MULTIPLIER * 100) + "% damage.";
				case OVERGROWTH -> "Roots hold the target for " + secs(AuraConfig.REACTION_OVERGROWTH_ROOT_TICKS) + "s and deal " + dmg(AuraConfig.REACTION_OVERGROWTH_DAMAGE) + ".";
				case STORM -> "A gust in " + Math.round(AuraConfig.REACTION_STORM_RADIUS) + " blocks lifts enemies and deals " + dmg(AuraConfig.REACTION_STORM_DAMAGE) + ".";
			};
		}

		private static String secs(int ticks) {
			return String.format(java.util.Locale.ROOT, "%.1f", ticks / 20.0).replace(".0", "");
		}

		private static String dmg(float amount) {
			return String.format(java.util.Locale.ROOT, "%.1f", amount).replace(".0", "") + " damage";
		}

		public boolean enabled() {
			return switch (this) {
				case FREEZE -> AuraConfig.REACTION_FREEZE_ENABLED;
				case CONDUCT -> AuraConfig.REACTION_CONDUCT_ENABLED;
				case FIRESTORM -> AuraConfig.REACTION_FIRESTORM_ENABLED;
				case TOXIC_CLOUD -> AuraConfig.REACTION_TOXIC_ENABLED;
				case STEAM -> AuraConfig.REACTION_STEAM_ENABLED;
				case MELT -> AuraConfig.REACTION_MELT_ENABLED;
				case OVERGROWTH -> AuraConfig.REACTION_OVERGROWTH_ENABLED;
				case STORM -> AuraConfig.REACTION_STORM_ENABLED;
			};
		}
	}

	/** One target's marks (mark -> tick it runs out) and when it can react again. */
	private static final class Marks {
		final EnumMap<Mark, Long> until = new EnumMap<>(Mark.class);
		long readyAt;
	}

	private static final Map<LivingEntity, Marks> MARKS = new HashMap<>();
	/** Overgrowth: rooted target -> tick it is let go. */
	private static final Map<LivingEntity, Long> ROOTED = new HashMap<>();
	private static final ResourceLocation ROOT_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "reaction_overgrowth");

	private record Cloud(ServerPlayer owner, ServerLevel level, Vec3 at, long until) {
	}

	private static final List<Cloud> CLOUDS = new ArrayList<>();

	/** True while a reaction deals its own damage: those hits carry no element and set off nothing. */
	private static boolean reacting = false;

	// ---------------------------------------------------------------
	// The hit
	// ---------------------------------------------------------------

	/**
	 * In the incoming-damage event, for an ability hit by a player: sets off a reaction (changing the
	 * damage of this hit) or leaves the hit's element as a mark.
	 */
	public static void onAbilityHit(LivingIncomingDamageEvent event, ServerPlayer attacker, AuraPlayerData data) {
		if (reacting || !AuraConfig.REACTION_ENABLED || event.getAmount() <= 0) return;
		Element element = Element.of(data.aura);
		LivingEntity victim = event.getEntity();
		MinecraftServer server = attacker.getServer();
		if (element == null || server == null || !Handhelds.enemy(attacker, victim)) return;
		long now = server.getTickCount();
		Marks marks = MARKS.get(victim);
		if (marks == null || marks.readyAt <= now) {
			for (Reaction r : Reaction.values()) {
				if (r.trigger != element || !r.enabled() || !has(victim, marks, r.mark, now)) continue;
				if (marks == null) MARKS.put(victim, marks = new Marks());
				marks.until.remove(r.mark);
				marks.readyAt = now + Math.max(0, AuraConfig.REACTION_COOLDOWN_TICKS);
				react(r, event, attacker, victim);
				return;
			}
		}
		if (element.leaves != null && AuraConfig.REACTION_MARK_TICKS > 0) {
			if (marks == null) MARKS.put(victim, marks = new Marks());
			marks.until.put(element.leaves, now + AuraConfig.REACTION_MARK_TICKS);
		}
	}

	/** True if the target carries this mark right now (a hit left it, or the world puts it on, like freezing). */
	public static boolean marked(LivingEntity e, Mark mark) {
		return has(e, MARKS.get(e), mark, e.level().getGameTime());
	}

	private static boolean has(LivingEntity e, Marks marks, Mark mark, long now) {
		if (marks != null) {
			Long until = marks.until.get(mark);
			if (until != null && until > now) return true;
		}
		return AuraConfig.REACTION_ENVIRONMENT_MARKS && mark.environment(e);
	}

	private static void react(Reaction r, LivingIncomingDamageEvent event, ServerPlayer attacker, LivingEntity victim) {
		ServerLevel level = attacker.serverLevel();
		MinecraftServer server = attacker.getServer();
		Vec3 c = AuraFx.chest(victim);
		switch (r) {
			case FREEZE -> {
				bonus(event, victim, AuraConfig.REACTION_FREEZE_DAMAGE);
				// a tick later: this very hit would break the freeze again (IceSpike.onDamaged)
				AuraFx.later(server, 1, () -> {
					if (victim.isAlive()) IceSpike.freeze(victim, server.getTickCount(), AuraConfig.REACTION_FREEZE_TICKS);
				});
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.SNOWFLAKE, 30, 0.5, 0.05);
				AuraFx.sound(level, victim, AuraSounds.GLASS_BREAK.value(), 1.0f, 1.4f);
			}
			case CONDUCT -> {
				bonus(event, victim, AuraConfig.REACTION_CONDUCT_DAMAGE);
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.ELECTRIC_SPARK, 25, 0.5, 0.2);
				AuraFx.sound(level, victim, AuraSounds.TRIDENT_THUNDER.value(), 0.6f, 1.6f);
				long now = server.getTickCount();
				List<LivingEntity> wet = new ArrayList<>(Handhelds.enemies(attacker, level, c, AuraConfig.REACTION_CONDUCT_CHAIN_RADIUS));
				wet.removeIf(e -> e == victim || !has(e, MARKS.get(e), Mark.WET, now));
				wet.sort(Comparator.comparingDouble(e -> e.distanceToSqr(victim)));
				int n = Math.min(wet.size(), Math.max(0, AuraConfig.REACTION_CONDUCT_CHAIN_MAX));
				Vec3 from = c;
				for (int i = 0; i < n; i++) {
					LivingEntity next = wet.get(i);
					Vec3 to = AuraFx.chest(next);
					AuraFx.line(level, from, to, ParticleTypes.ELECTRIC_SPARK, (int) Math.max(4, from.distanceTo(to) * 3));
					from = to;
					Marks m = MARKS.get(next);
					if (m != null) m.until.remove(Mark.WET);
				}
				List<LivingEntity> chained = List.copyOf(wet.subList(0, n));
				// next tick, outside this hit's own damage event
				AuraFx.later(server, 1, () -> {
					if (!AuraFx.alive(attacker)) return;
					for (LivingEntity e : chained) hurt(attacker, e, AuraConfig.REACTION_CONDUCT_CHAIN_DAMAGE);
				});
			}
			case FIRESTORM -> {
				bonus(event, victim, AuraConfig.REACTION_FIRESTORM_DAMAGE);
				List<LivingEntity> around = Handhelds.enemies(attacker, level, c, AuraConfig.REACTION_FIRESTORM_RADIUS);
				for (LivingEntity e : around) if (!e.fireImmune()) e.igniteForSeconds(AuraConfig.REACTION_FIRESTORM_FIRE_SECONDS);
				spiral(level, victim.position(), AuraConfig.REACTION_FIRESTORM_RADIUS, ParticleTypes.FLAME);
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.LAVA, 8, 0.6, 0.1);
				AuraFx.sound(level, victim, AuraSounds.BREEZE_WIND_CHARGE_BURST.value(), 1.0f, 0.7f);
				AuraFx.sound(level, victim, AuraSounds.BLAZE_SHOOT.value(), 1.0f, 0.8f);
				List<LivingEntity> others = new ArrayList<>(around);
				others.remove(victim);
				AuraFx.later(server, 1, () -> {
					if (!AuraFx.alive(attacker)) return;
					for (LivingEntity e : others) hurt(attacker, e, AuraConfig.REACTION_FIRESTORM_DAMAGE);
				});
			}
			case TOXIC_CLOUD -> {
				bonus(event, victim, AuraConfig.REACTION_TOXIC_DAMAGE);
				if (AuraConfig.REACTION_TOXIC_TICKS > 0) {
					Cloud cloud = new Cloud(attacker, level, victim.position(), server.getTickCount() + AuraConfig.REACTION_TOXIC_TICKS);
					CLOUDS.add(cloud);
					Combos.zone(attacker, Combos.Kind.POISON, cloud.at().add(0, 0.6, 0), Math.max(0.5, AuraConfig.REACTION_TOXIC_RADIUS),
							AuraConfig.REACTION_TOXIC_TICKS, () -> CLOUDS.remove(cloud), false);
				}
				AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(r.rgb, 2.0f), 30, 1.0, 0.0);
				AuraFx.sound(level, victim, AuraSounds.PUFFER_FISH_BLOW_UP.value(), 1.0f, 0.6f);
			}
			case STEAM -> {
				bonus(event, victim, AuraConfig.REACTION_STEAM_DAMAGE);
				victim.clearFire();
				if (AuraConfig.REACTION_STEAM_BLIND_TICKS > 0) {
					for (LivingEntity e : Handhelds.enemies(attacker, level, c, AuraConfig.REACTION_STEAM_RADIUS)) {
						e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, AuraConfig.REACTION_STEAM_BLIND_TICKS, 0, false, true, true), attacker);
					}
				}
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.CLOUD, 40, 0.8, 0.08);
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.WHITE_SMOKE, 20, 0.6, 0.05);
				AuraFx.sound(level, victim, AuraSounds.FIRE_EXTINGUISH.value(), 1.2f, 0.9f);
			}
			case MELT -> {
				event.setAmount((float) (event.getAmount() * Math.max(1.0, AuraConfig.REACTION_MELT_MULTIPLIER)));
				victim.setTicksFrozen(0);
				AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.FALLING_WATER, 25, 0.5, 0.0);
				AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(r.rgb, 1.4f), 20, 0.5, 0.0);
				AuraFx.sound(level, victim, AuraSounds.LAVA_POP.value(), 1.2f, 1.2f);
			}
			case OVERGROWTH -> {
				bonus(event, victim, AuraConfig.REACTION_OVERGROWTH_DAMAGE);
				root(victim, server.getTickCount() + Math.max(0, AuraConfig.REACTION_OVERGROWTH_ROOT_TICKS));
				AuraFx.ring(level, victim.getX(), victim.getY() + 0.1, victim.getZ(), victim.getBbWidth() + 0.4, ParticleTypes.COMPOSTER, 16);
				AuraFx.burst(level, c.x, victim.getY() + 0.3, c.z, ParticleTypes.SPORE_BLOSSOM_AIR, 20, 0.5, 0.0);
				AuraFx.sound(level, victim, AuraSounds.MANGROVE_ROOTS_PLACE.value(), 1.2f, 0.8f);
			}
			case STORM -> {
				bonus(event, victim, AuraConfig.REACTION_STORM_DAMAGE);
				List<LivingEntity> around = Handhelds.enemies(attacker, level, c, AuraConfig.REACTION_STORM_RADIUS);
				for (LivingEntity e : around) {
					e.setDeltaMovement(e.getDeltaMovement().add(0, AuraConfig.REACTION_STORM_LIFT, 0));
					e.hurtMarked = true;
				}
				spiral(level, victim.position(), AuraConfig.REACTION_STORM_RADIUS, ParticleTypes.ELECTRIC_SPARK);
				AuraFx.burst(level, c.x, c.y + 0.5, c.z, ParticleTypes.GUST, 3, 0.5, 0.0);
				AuraFx.sound(level, victim, AuraSounds.LIGHTNING_BOLT_IMPACT.value(), 0.8f, 1.3f);
				AuraFx.sound(level, victim, AuraSounds.WIND_CHARGE_BURST.value(), 1.0f, 0.8f);
				List<LivingEntity> others = new ArrayList<>(around);
				others.remove(victim);
				AuraFx.later(server, 1, () -> {
					if (!AuraFx.alive(attacker)) return;
					for (LivingEntity e : others) hurt(attacker, e, AuraConfig.REACTION_STORM_DAMAGE);
				});
			}
		}
		feedback(r, attacker);
	}

	/** Extra damage on the hit that set the reaction off (ability scaling, like the hit itself). */
	private static void bonus(LivingIncomingDamageEvent event, LivingEntity victim, float damage) {
		if (damage > 0) event.setAmount(event.getAmount() + AuraAbilities.scaled(victim, damage));
	}

	/** A reaction's own damage on someone else: an ability hit that leaves no mark and sets nothing off. */
	static void hurt(ServerPlayer owner, LivingEntity target, float damage) {
		if (!Handhelds.enemy(owner, target)) return;
		reacting = true;
		try {
			target.invulnerableTime = 0;
			Handhelds.hurt(owner, target, damage);
		} finally {
			reacting = false;
		}
	}

	private static void feedback(Reaction r, ServerPlayer attacker) {
		attacker.displayClientMessage(Component.literal(r.symbol + " " + r.displayName + "!")
				.withStyle(s -> s.withColor(TextColor.fromRgb(r.rgb)).withBold(true)), true);
		var stat = AuraStats.reaction(r.id());
		if (stat != null) AuraStats.add(attacker, stat, 1);
		// the first of each is a stunt
		MinecraftServer server = attacker.getServer();
		if (server == null || AuraAbilities.progressPaused(attacker)) return;
		var holder = server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "reaction/" + r.id()));
		if (holder != null && !attacker.getAdvancements().getOrStartProgress(holder).isDone()
				&& attacker.getAdvancements().award(holder, "done")) {
			NetworkHandler.sendFx(attacker, FxPacket.STUNT, 0, r.id() + "_reaction");
			AuraStunts.xp(attacker, false);
		}
	}

	/** A rising ring of particles over {@code radius}. */
	private static void spiral(ServerLevel level, Vec3 at, double radius, net.minecraft.core.particles.ParticleOptions particle) {
		int points = 28;
		for (int i = 0; i < points; i++) {
			double t = (double) i / points;
			double a = t * Math.PI * 4;
			double r = 0.5 + radius * t;
			AuraFx.particles(level, particle, at.x + Math.cos(a) * r, at.y + 0.2 + t * 2.0, at.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
		}
	}

	// ---------------------------------------------------------------
	// Overgrowth roots: own modifiers, so they never cut another root (Entanglement) short
	// ---------------------------------------------------------------

	static void root(LivingEntity e, long until) {
		if (until <= 0) return;
		if (e.isPassenger()) e.stopRiding();
		var speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
		var jump = e.getAttribute(Attributes.JUMP_STRENGTH);
		if (speed != null && speed.getModifier(ROOT_ID) == null) speed.addTransientModifier(new AttributeModifier(ROOT_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		if (jump != null && jump.getModifier(ROOT_ID) == null) jump.addTransientModifier(new AttributeModifier(ROOT_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		ROOTED.merge(e, until, Math::max);
	}

	private static void unroot(LivingEntity e) {
		var speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
		var jump = e.getAttribute(Attributes.JUMP_STRENGTH);
		if (speed != null) speed.removeModifier(ROOT_ID);
		if (jump != null) jump.removeModifier(ROOT_ID);
	}

	// ---------------------------------------------------------------
	// Every tick
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		long now = server.getTickCount();
		if (!MARKS.isEmpty()) {
			boolean show = now % 5 == 0;
			for (Iterator<Map.Entry<LivingEntity, Marks>> it = MARKS.entrySet().iterator(); it.hasNext(); ) {
				var entry = it.next();
				LivingEntity e = entry.getKey();
				Marks m = entry.getValue();
				m.until.values().removeIf(t -> t <= now);
				if (e.isRemoved() || !e.isAlive() || (m.until.isEmpty() && m.readyAt <= now)) {
					it.remove();
					continue;
				}
				if (show && e.level() instanceof ServerLevel level) {
					Vec3 c = AuraFx.chest(e);
					double w = e.getBbWidth() * 0.6;
					for (Mark mark : m.until.keySet()) AuraFx.particles(level, AuraFx.dust(mark.rgb, 0.9f), c.x, c.y, c.z, 2, w, e.getBbHeight() * 0.35, w, 0);
				}
			}
		}
		if (!ROOTED.isEmpty()) {
			for (Iterator<Map.Entry<LivingEntity, Long>> it = ROOTED.entrySet().iterator(); it.hasNext(); ) {
				var entry = it.next();
				LivingEntity e = entry.getKey();
				if (e.isRemoved() || entry.getValue() <= now) {
					unroot(e);
					it.remove();
				} else if (now % 4 == 0 && e.level() instanceof ServerLevel level) {
					AuraFx.ring(level, e.getX(), e.getY() + 0.1, e.getZ(), e.getBbWidth() * 0.7, AuraFx.dust(0x4FB848, 1.0f), 6);
				}
			}
		}
		if (!CLOUDS.isEmpty()) {
			for (Iterator<Cloud> it = CLOUDS.iterator(); it.hasNext(); ) {
				Cloud cloud = it.next();
				if (cloud.until <= now || !AuraFx.alive(cloud.owner) || cloud.owner.serverLevel() != cloud.level) {
					it.remove();
					continue;
				}
				double r = Math.max(0.5, AuraConfig.REACTION_TOXIC_RADIUS);
				if (now % 3 == 0) {
					AuraFx.particles(cloud.level, AuraFx.dust(Reaction.TOXIC_CLOUD.rgb, 1.8f), cloud.at.x, cloud.at.y + 0.6, cloud.at.z, 6, r * 0.5, 0.4, r * 0.5, 0);
				}
				if (now % 10 == 0) {
					for (LivingEntity e : Handhelds.enemies(cloud.owner, cloud.level, cloud.at.add(0, 0.6, 0), r)) {
						e.addEffect(new MobEffectInstance(MobEffects.POISON, Math.max(1, AuraConfig.REACTION_TOXIC_POISON_TICKS),
								Math.max(0, AuraConfig.REACTION_TOXIC_POISON_AMPLIFIER), false, true, true), cloud.owner);
					}
				}
			}
		}
	}

	public static void clearAll() {
		for (LivingEntity e : ROOTED.keySet()) unroot(e);
		ROOTED.clear();
		MARKS.clear();
		CLOUDS.clear();
	}

	/** For the dev test: one ability hit of {@code damage} from {@code owner}. */
	public static void devHit(ServerPlayer owner, LivingEntity target, float damage) {
		target.invulnerableTime = 0;
		Handhelds.hurt(owner, target, damage);
	}

	/** For the dev test: whether Overgrowth holds {@code e}. */
	public static boolean isRooted(LivingEntity e) {
		return ROOTED.containsKey(e);
	}

	/** For the dev test: the marks on {@code e} right now, as text. */
	public static String describe(LivingEntity e, long now) {
		Marks m = MARKS.get(e);
		StringBuilder out = new StringBuilder();
		for (Mark mark : Mark.values()) {
			if (has(e, m, mark, now)) out.append(out.isEmpty() ? "" : ",").append(mark.name());
		}
		return out.isEmpty() ? "none" : out.toString();
	}
}
