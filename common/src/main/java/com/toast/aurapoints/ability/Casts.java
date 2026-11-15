package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.MossBlock;
import net.minecraft.world.level.block.NyliumBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
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
		SMELT_LEFT.remove(id);
		PHASES.remove(id);
		PATHS.remove(id);
		RUSHES.remove(id);
		SPRINGS.removeIf(s -> s.owner.equals(id));
	}

	/** Server stop: nothing carries into the next world. */
	public static void clear() {
		CHARGING.clear();
		CLOAKS.clear();
		SMELT_LEFT.clear();
		PHASES.clear();
		PATHS.clear();
		RUSHES.clear();
		SPRINGS.clear();
	}

	public static void tickAll(MinecraftServer server) {
		long now = server.getTickCount();
		if (!CHARGING.isEmpty()) tickCharges(server, now);
		if (!CLOAKS.isEmpty()) tickCloaks(server, now);
		if (!SPRINGS.isEmpty()) tickSprings(now);
		if (!PHASES.isEmpty()) tickPhases(server, now);
		if (!PATHS.isEmpty()) tickPaths(server, now);
		if (!RUSHES.isEmpty()) tickRushes(server, now);
	}

	private static AuraType aura(ServerPlayer player) {
		return player.getServer() == null ? null : AuraState.get(player.getServer()).getOrCreate(player.getUUID()).aura;
	}

	private static void tickCharges(MinecraftServer server, long now) {
		for (Iterator<Map.Entry<UUID, Charge>> it = CHARGING.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Charge> e = it.next();
			ServerPlayer p = Combos.player(server, e.getKey());
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
			// Wind's sneak Dash is the Tailwind; for Shadow the Dive comes first, as on a normal Dash press
			case DASH -> sneak && aura(player) == AuraType.WIND ? tailwind(player, now)
					: DashDive.tryDive(player, now) || AuraAbilities.dash(player, now, mode);
			case VANISH -> AuraAbilities.vanish(player, now, mode);
			case SUPER_WIND_CHARGE -> gust(player, now);
			case ICE_SPIKE -> frostPath(player, now);
			// mid-combo the key throws the next bolt, whatever the cast
			case THUNDERBOLT -> Thunderbolt.isActive(player) ? Thunderbolt.press(player, now) : sneak ? staticRush(player, now) : chainLightning(player, now);
			case CHIDORI -> lightningStep(player, now);
			case VINE_HOOK -> sneak ? harvestHook(player, now)
					: VineHook.press(player, now, AuraConfig.VINE_HOOK_CHARGED_RANGE_FACTOR, AuraConfig.VINE_HOOK_CHARGED_DAMAGE_FACTOR);
			case ENTANGLEMENT -> sneak ? overgrowth(player, now) : thicket(player, now);
			// a viper out: the key calls it back, as always
			case VERDANT_VIPER -> VerdantViper.isSteering(player) ? VerdantViper.press(player, now) : venomSpit(player, now);
			case PILLAR_LIFT -> Earth.pillar(player, now, mode);
			case BOULDER_TOSS -> Earth.boulder(player, now, true);
			case QUAKE -> sneak ? tunnel(player, now) : Earth.quake(player, now, AuraConfig.EARTH_QUAKE_CHARGED_RADIUS_FACTOR, AuraConfig.EARTH_QUAKE_CHARGED_DAMAGE_FACTOR);
			case RADIANT_PULSE -> sneak ? guidingLight(player, now) : Light.pulse(player, now, mode);
			case SOLAR_FLASH -> Light.flash(player, now, mode);
			case RIFT_STEP -> VoidAura.anchor(player, now);
			case PRISM_BEAM -> mendingBeam(player, now);
			case HEMORRHAGE -> sneak ? transfusion(player, now) : Blood.hemorrhage(player, now, mode);
			case BLOOD_TETHER -> sneak ? lifeline(player, now) : Blood.tether(player, now, mode);
			case CRIMSON_SACRIFICE -> Blood.sacrifice(player, now, mode);
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
	/** Forge: items a running cloak may still smelt. */
	private static final Map<UUID, Integer> SMELT_LEFT = new HashMap<>();
	private static final String SMELTED_TAG = "aurapoints_smelted";

	/** Flame Cloak (sneak + Fire Bending with no flame in hand): Fire Resistance, and enemies close by catch fire. A held flame is shot as usual. */
	private static boolean flameCloak(ServerPlayer player, long now) {
		if (FireBending.isHolding(player)) return FireBending.press(player, now);
		if (!AuraAbilities.checkCooldown(player, Ability.FIRE_BENDING, AuraConfig.FIRE_BENDING_COOLDOWN_TICKS, now)) return false;
		int ticks = AuraConfig.FIRE_BENDING_CLOAK_TICKS;
		CLOAKS.put(player.getUUID(), now + ticks);
		SMELT_LEFT.put(player.getUUID(), Math.max(0, AuraConfig.FIRE_BENDING_CLOAK_SMELT_ITEMS));
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
			ServerPlayer p = Combos.player(server, e.getKey());
			if (p == null || !p.isAlive() || now >= e.getValue()) {
				it.remove();
				SMELT_LEFT.remove(e.getKey());
				continue;
			}
			ServerLevel level = p.serverLevel();
			if (now % 10 == 0) forge(p, level);
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

	/** Forge: dropped items next to a cloaked Fire player are smelted where they lie (furnace recipes), up to the cloak's budget. */
	private static void forge(ServerPlayer p, ServerLevel level) {
		int left = SMELT_LEFT.getOrDefault(p.getUUID(), 0);
		double r = AuraConfig.FIRE_BENDING_CLOAK_SMELT_RADIUS;
		if (left <= 0 || r <= 0) return;
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(r), i -> i.isAlive() && !i.getTags().contains(SMELTED_TAG))) {
			if (left <= 0) break;
			ItemStack stack = item.getItem();
			SingleRecipeInput input = new SingleRecipeInput(stack);
			var recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, input, level);
			if (recipe.isEmpty()) continue;
			ItemStack one = recipe.get().value().assemble(input, level.registryAccess());
			if (one.isEmpty()) continue;
			int n = Math.min(stack.getCount(), left);
			left -= n;
			int out = one.getCount() * n;
			while (out > 0) {
				ItemStack made = one.copyWithCount(Math.min(out, one.getMaxStackSize()));
				out -= made.getCount();
				ItemEntity drop = new ItemEntity(level, item.getX(), item.getY(), item.getZ(), made);
				drop.setDeltaMovement(Vec3.ZERO);
				drop.addTag(SMELTED_TAG);
				drop.setDefaultPickUpDelay();
				level.addFreshEntity(drop);
			}
			if (n >= stack.getCount()) item.discard();
			else {
				stack.shrink(n);
				item.setItem(stack);
			}
			AuraFx.burst(level, item.getX(), item.getY() + 0.2, item.getZ(), ParticleTypes.FLAME, 8, 0.15, 0.02);
			AuraFx.particles(level, ParticleTypes.SMOKE, item.getX(), item.getY() + 0.3, item.getZ(), 4, 0.1, 0.1, 0.1, 0.01);
			level.playSound(null, item.getX(), item.getY(), item.getZ(), net.minecraft.sounds.SoundEvents.FURNACE_FIRE_CRACKLE, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.2f);
		}
		SMELT_LEFT.put(p.getUUID(), left);
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
			ServerPlayer p = Combos.player(server, e.getKey());
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

	// ---- Wind ----

	/** Tailwind (sneak + Dash, Wind): Speed for a while, at a share of the Dash cooldown. */
	private static boolean tailwind(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.DASH, AuraConfig.DASH_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_DASH, 0);
		player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, AuraConfig.WIND_TAILWIND_TICKS, AuraConfig.WIND_TAILWIND_AMPLIFIER, false, false, true));
		ServerLevel level = player.serverLevel();
		AuraFx.burst(level, player.getX(), player.getY() + 0.5, player.getZ(), ParticleTypes.CLOUD, 25, 0.5, 0.08);
		AuraFx.trail(player, AuraFx.dust(AuraType.WIND.rgb, 1.3f), 3, 20);
		AuraFx.sound(level, player, AuraSounds.SWEET_WHOOSH.value(), 0.9f, 1.3f);
		AuraFx.sound(level, player, AuraSounds.BREEZE_JUMP.value(), 0.6f, 1.4f);
		player.displayClientMessage(Component.literal("✦ Tailwind ✦").withStyle(ChatFormatting.AQUA), true);
		AuraStats.add(player, AuraStats.DASH_USES, 1);
		used(player, Ability.DASH, now, AuraConfig.WIND_TAILWIND_COOLDOWN_FACTOR);
		return true;
	}

	/** Gust (sneak + Super Wind Charge): a push on yourself, or the boat you ride, along your look; no fall damage from it. */
	private static boolean gust(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.SUPER_WIND_CHARGE, AuraConfig.SUPER_WIND_CHARGE_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_WIND_CHARGE, 0);
		Entity moved = player.getVehicle() != null ? player.getVehicle() : player;
		Vec3 push = player.getLookAngle().scale(AuraConfig.WIND_GUST_PUSH);
		moved.setDeltaMovement(moved.getDeltaMovement().add(push));
		moved.hurtMarked = true;
		if (moved == player) {
			WindLaunch.markLaunched(player, now);
			player.fallDistance = 0;
		}
		ServerLevel level = player.serverLevel();
		Vec3 back = player.position().add(0, 0.6, 0).subtract(push.normalize().scale(0.8));
		AuraFx.burst(level, back.x, back.y, back.z, ParticleTypes.CLOUD, 30, 0.4, 0.15);
		AuraFx.burst(level, back.x, back.y, back.z, AuraFx.dust(AuraType.WIND.rgb, 1.4f), 12, 0.5, 0.0);
		AuraFx.sound(level, player, AuraSounds.BREEZE_JUMP.value(), 1.0f, 0.9f);
		AuraFx.sound(level, player, AuraSounds.SWEET_WHOOSH.value(), 1.0f, 0.8f);
		AuraStats.add(player, AuraStats.SUPER_WIND_CHARGE_USES, 1);
		used(player, Ability.SUPER_WIND_CHARGE, now, AuraConfig.WIND_GUST_COOLDOWN_FACTOR);
		return true;
	}

	// ---- Frost ----

	/** Frost Path holders and the tick each path ends. */
	private static final Map<UUID, Long> PATHS = new HashMap<>();

	/** Frost Path (sneak + Ice Spike): for a while, still water around your feet freezes under you (frosted ice, which melts again). */
	private static boolean frostPath(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.ICE_SPIKE, AuraConfig.ICE_SPIKE_COOLDOWN_TICKS, now)) return false;
		PATHS.put(player.getUUID(), now + AuraConfig.FROST_PATH_TICKS);
		ServerLevel level = player.serverLevel();
		AuraFx.ring(level, player.getX(), player.getY() + 0.1, player.getZ(), 1.2, ParticleTypes.SNOWFLAKE, 20);
		AuraFx.sound(level, player, AuraSounds.POWDER_SNOW_STEP.value(), 1.0f, 0.7f);
		AuraFx.sound(level, player, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), 0.6f, 1.8f);
		player.displayClientMessage(Component.literal("✦ Frost Path ✦").withStyle(ChatFormatting.AQUA), true);
		used(player, Ability.ICE_SPIKE, now, AuraConfig.FROST_PATH_COOLDOWN_FACTOR);
		return true;
	}

	private static void tickPaths(MinecraftServer server, long now) {
		for (Iterator<Map.Entry<UUID, Long>> it = PATHS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Long> e = it.next();
			ServerPlayer p = Combos.player(server, e.getKey());
			if (p == null || !p.isAlive() || now >= e.getValue()) {
				it.remove();
				continue;
			}
			ServerLevel level = p.serverLevel();
			BlockPos feet = p.blockPosition();
			// swimming: the ice forms at the feet and lifts you onto it; else under the block you stand on
			boolean swimming = p.isInWater();
			BlockPos layer = swimming ? feet : feet.below();
			int r = Math.max(0, AuraConfig.FROST_PATH_RADIUS);
			boolean froze = false;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos pos = layer.offset(dx, 0, dz);
					BlockState s = level.getBlockState(pos);
					if (!s.is(Blocks.WATER) || !s.getFluidState().isSource()) continue;
					level.setBlockAndUpdate(pos, Blocks.FROSTED_ICE.defaultBlockState());
					level.scheduleTick(pos, Blocks.FROSTED_ICE, Mth.nextInt(level.random, 60, 120));
					froze = true;
				}
			}
			int douse = AuraConfig.FROST_PATH_DOUSE_RADIUS;
			if (douse > 0 && now % 4 == 0) {
				if (p.getRemainingFireTicks() > 0) p.clearFire();
				for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-douse, -1, -douse), feet.offset(douse, 1, douse))) {
					if (!level.getBlockState(pos).is(BlockTags.FIRE)) continue;
					level.removeBlock(pos, false);
					level.levelEvent(null, 1009, pos, 0);
					AuraFx.particles(level, ParticleTypes.SNOWFLAKE, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 4, 0.2, 0.1, 0.2, 0.01);
				}
			}
			if (froze && swimming) {
				p.setDeltaMovement(p.getDeltaMovement().x, 0.45, p.getDeltaMovement().z);
				p.hurtMarked = true;
			}
			if (froze || now % 4 == 0) {
				AuraFx.particles(level, ParticleTypes.SNOWFLAKE, p.getX(), p.getY() + 0.1, p.getZ(), froze ? 6 : 1, 0.6, 0.05, 0.6, 0.01);
			}
		}
	}

	// ---- Thunder ----

	/** Static Rush (sneak + Thunderbolt): Speed for a while. */
	private static boolean staticRush(ServerPlayer player, long now) {
		if (Chidori.isCharging(player) || Chidori.isLunging(player)) return false;
		if (!AuraAbilities.checkCooldown(player, Ability.THUNDERBOLT, AuraConfig.THUNDERBOLT_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_THUNDERBOLT, 1);
		player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, AuraConfig.THUNDERBOLT_RUSH_TICKS, AuraConfig.THUNDERBOLT_RUSH_AMPLIFIER, false, false, true));
		ServerLevel level = player.serverLevel();
		AuraFx.ring(level, player.getX(), player.getY() + 0.1, player.getZ(), 0.9, ParticleTypes.ELECTRIC_SPARK, 20);
		AuraFx.trail(player, AuraFx.dust(0xFFE347, 1.2f), 3, 20);
		AuraFx.sound(level, player, AuraSounds.TRIDENT_THUNDER.value(), 0.5f, 1.9f);
		player.displayClientMessage(Component.literal("✦ Static Rush ✦").withStyle(ChatFormatting.YELLOW), true);
		RUSHES.put(player.getUUID(), new double[]{now + AuraConfig.THUNDERBOLT_RUSH_TICKS, player.getX(), player.getZ()});
		used(player, Ability.THUNDERBOLT, now, AuraConfig.THUNDERBOLT_RUSH_COOLDOWN_FACTOR);
		return true;
	}

	/** Static Rush holders: {end tick, x and z a tick ago} - while they ride, the rush drives the minecart or boat. */
	private static final Map<UUID, double[]> RUSHES = new HashMap<>();

	private static void tickRushes(MinecraftServer server, long now) {
		for (Iterator<Map.Entry<UUID, double[]>> it = RUSHES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, double[]> e = it.next();
			ServerPlayer p = Combos.player(server, e.getKey());
			double[] st = e.getValue();
			if (p == null || !p.isAlive() || now >= st[0]) {
				it.remove();
				continue;
			}
			double moved = Math.hypot(p.getX() - st[1], p.getZ() - st[2]);
			st[1] = p.getX();
			st[2] = p.getZ();
			if (p.getVehicle() != null) drive(p, p.getVehicle(), moved, now);
		}
	}

	/** One tick of Static Rush on the minecart or boat {@code ride} (public for the dev test: fake players can't ride). */
	public static void drive(ServerPlayer p, Entity ride, double moved, long now) {
		double push = AuraConfig.THUNDERBOLT_RUSH_VEHICLE_PUSH;
		if (push <= 0) return;
		Vec3 v = ride.getDeltaMovement();
		if (ride instanceof AbstractMinecart) {
			// along the way it rolls; standing still, along your look
			Vec3 flat = new Vec3(v.x, 0, v.z);
			Vec3 dir = flat.lengthSqr() > 1.0e-4 ? flat.normalize() : flatLook(p);
			ride.setDeltaMovement(v.add(dir.scale(push)));
		} else if (ride instanceof Boat && ride.getControllingPassenger() == p && now % 10 == 0) {
			// the rider's client moves a boat: a velocity packet now and then, never slower than it already goes
			double speed = Math.max(moved, push * 10);
			Vec3 dir = flatLook(p);
			ride.setDeltaMovement(dir.x * speed, v.y, dir.z * speed);
			ride.hurtMarked = true;
		} else return;
		if (now % 3 == 0) {
			AuraFx.particles(p.serverLevel(), ParticleTypes.ELECTRIC_SPARK, ride.getX(), ride.getY() + 0.2, ride.getZ(), 3, 0.4, 0.1, 0.4, 0.05);
		}
	}

	private static Vec3 flatLook(ServerPlayer p) {
		Vec3 look = p.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0, look.z);
		return flat.lengthSqr() > 1.0e-6 ? flat.normalize() : Vec3.ZERO;
	}

	/** Chain Lightning (hold Thunderbolt): a bolt from the hand at the first enemy in range, jumping on to the nearest others; no leap or hover. */
	private static boolean chainLightning(ServerPlayer player, long now) {
		if (Chidori.isCharging(player) || Chidori.isLunging(player)) return false;
		if (!AuraAbilities.checkCooldown(player, Ability.THUNDERBOLT, AuraConfig.THUNDERBOLT_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_THUNDERBOLT, 0);
		ServerLevel level = player.serverLevel();
		Vec3 look = player.getLookAngle();
		Vec3 from = player.getEyePosition().add(look.scale(0.5)).add(0, -0.25, 0);
		VineHook.Trace aim = VineHook.trace(player, look, AuraConfig.THUNDERBOLT_CHAIN_RANGE);
		AuraStats.add(player, AuraStats.THUNDERBOLT_THROWS, 1);
		if (aim.target() == null) {
			bolt(level, from, aim.tip());
			AuraFx.sound(level, player, AuraSounds.TRIDENT_THUNDER.value(), 0.8f, 1.5f);
			used(player, Ability.THUNDERBOLT, now, CastMode.CHARGED);
			return true;
		}
		List<LivingEntity> hit = new ArrayList<>();
		LivingEntity cur = aim.target();
		Vec3 at = from;
		AuraStunts.ACTIVE_ABILITY = Ability.THUNDERBOLT;
		try {
			while (cur != null && hit.size() < 1 + Math.max(0, AuraConfig.THUNDERBOLT_CHAINBOLT_TARGETS)) {
				hit.add(cur);
				Vec3 c = AuraFx.chest(cur);
				bolt(level, at, c);
				at = c;
				cur.hurt(level.damageSources().playerAttack(player), AuraAbilities.vsMob(cur, AuraConfig.THUNDERBOLT_CHAIN_DAMAGE));
				Reactions.applyMark(cur, Reactions.Mark.CHARGED);
				AuraStats.add(player, AuraStats.THUNDERBOLT_HITS, 1);
				LivingEntity next = null;
				double best = Double.MAX_VALUE;
				for (LivingEntity e : Handhelds.enemies(player, level, c, AuraConfig.THUNDERBOLT_CHAINBOLT_RADIUS)) {
					if (hit.contains(e)) continue;
					double d = e.distanceToSqr(cur);
					if (d < best) {
						best = d;
						next = e;
					}
				}
				cur = next;
			}
		} finally {
			AuraStunts.ACTIVE_ABILITY = null;
		}
		AuraFx.sound(level, player, AuraSounds.LIGHTNING_BOLT_THUNDER.value(), 0.7f, 1.6f);
		AuraFx.sound(level, player, AuraSounds.TRIDENT_THUNDER.value(), 0.9f, 1.3f);
		used(player, Ability.THUNDERBOLT, now, CastMode.CHARGED);
		return true;
	}

	/** One jagged bolt between two points: sparks along the line and a bright flash at the far end. */
	private static void bolt(ServerLevel level, Vec3 from, Vec3 to) {
		int n = (int) Math.max(6, from.distanceTo(to) * 4);
		AuraFx.line(level, from, to, ParticleTypes.ELECTRIC_SPARK, n);
		AuraFx.line(level, from, to, AuraFx.dust(0xFFE347, 1.1f), n / 2);
		AuraFx.burst(level, to.x, to.y, to.z, ParticleTypes.ELECTRIC_SPARK, 12, 0.3, 0.1);
	}

	/** Lightning Step (sneak + Chidori): a blink along your look that stops short of blocks. */
	private static boolean lightningStep(ServerPlayer player, long now) {
		if (Chidori.isCharging(player) || Chidori.isLunging(player) || Thunderbolt.isActive(player)) return false;
		if (!AuraAbilities.checkCooldown(player, Ability.CHIDORI, AuraConfig.CHIDORI_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition(), dir = player.getLookAngle();
		double dist = AuraConfig.CHIDORI_STEP_DISTANCE;
		HitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(dist)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double d = hit.getType() == HitResult.Type.MISS ? dist : Math.max(0, hit.getLocation().distanceTo(eye) - 0.8);
		Vec3 start = player.position();
		Vec3 aimed = start.add(dir.scale(d));
		// aimed into the ground or a wall: the same spot at your own height, then one block up, else stay
		Vec3 dest = null;
		for (Vec3 c : new Vec3[]{aimed, new Vec3(aimed.x, start.y, aimed.z), new Vec3(aimed.x, start.y + 1, aimed.z)}) {
			if (level.noCollision(player, player.getBoundingBox().move(c.subtract(start)))) {
				dest = c;
				break;
			}
		}
		if (dest == null) {
			player.displayClientMessage(Component.literal("No room to step there.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_CHIDORI, 0);
		AuraFx.burst(level, start.x, start.y + 1.0, start.z, ParticleTypes.ELECTRIC_SPARK, 20, 0.4, 0.1);
		AuraFx.line(level, start.add(0, 1, 0), dest.add(0, 1, 0), AuraFx.dust(0xFFE347, 1.3f), (int) Math.max(6, d * 3));
		player.teleportTo(dest.x, dest.y, dest.z);
		player.fallDistance = 0;
		AuraFx.burst(level, dest.x, dest.y + 1.0, dest.z, ParticleTypes.ELECTRIC_SPARK, 20, 0.4, 0.1);
		AuraFx.sound(level, player, AuraSounds.TRIDENT_THUNDER.value(), 0.7f, 2.0f);
		AuraFx.sound(level, player, AuraSounds.ENDERMAN_TELEPORT.value(), 0.4f, 1.8f);
		used(player, Ability.CHIDORI, now, AuraConfig.CHIDORI_STEP_COOLDOWN_FACTOR);
		return true;
	}

	// ---- Verdant ----

	/** Harvest Hook (sneak + Vine Hook): dropped items and animals in front of you are drawn in. */
	private static boolean harvestHook(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.VINE_HOOK, AuraConfig.VINE_HOOK_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_VINE_HOOK, 0);
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition(), look = player.getLookAngle();
		double range = AuraConfig.VINE_HOOK_HARVEST_RANGE;
		Vec3 to = player.position().add(0, 1.0, 0);
		int pulled = 0;
		for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(range),
				e -> (e instanceof ItemEntity || e instanceof Animal) && e.isAlive() && e.distanceToSqr(player) <= range * range)) {
			// only what's in front of you
			if (e.position().subtract(eye).normalize().dot(look) < 0.3) continue;
			Vec3 pull = to.subtract(e.position()).normalize().scale(e instanceof ItemEntity ? 0.9 : 1.1);
			e.setDeltaMovement(pull.add(0, 0.25, 0));
			e.hurtMarked = true;
			if (e instanceof ItemEntity item) item.setNoPickUpDelay();
			AuraFx.line(level, e.position().add(0, 0.3, 0), to, ParticleTypes.COMPOSTER, 8);
			pulled++;
		}
		AuraFx.sound(level, player, AuraSounds.CAVE_VINES_PICK_BERRIES.value(), 1.0f, 0.8f);
		AuraFx.sound(level, player, AuraSounds.FISHING_BOBBER_RETRIEVE.value(), 0.8f, 1.1f);
		player.displayClientMessage(Component.literal(pulled == 0 ? "Nothing in reach to harvest." : "✦ Harvest Hook: " + pulled + " ✦").withStyle(ChatFormatting.DARK_GREEN), true);
		used(player, Ability.VINE_HOOK, now, AuraConfig.VINE_HOOK_HARVEST_COOLDOWN_FACTOR);
		return true;
	}

	/** Overgrowth (sneak + Entanglement): crops, saplings and the like around the aimed spot grow as if bonemealed. */
	private static boolean overgrowth(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.ENTANGLEMENT, AuraConfig.ENTANGLE_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = player.serverLevel();
		VineHook.Trace aim = VineHook.trace(player, player.getLookAngle(), AuraConfig.ENTANGLE_RANGE);
		Vec3 at = aim.target() != null ? aim.target().position() : aim.tip();
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_ENTANGLE, 0);
		BlockPos center = BlockPos.containing(at);
		int r = Math.max(0, AuraConfig.ENTANGLE_GROW_RADIUS), grown = 0;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					if (dx * dx + dz * dz > r * r) continue;
					BlockPos pos = center.offset(dx, dy, dz);
					BlockState s = level.getBlockState(pos);
					// not the ground itself: grass, moss and nylium would sprout a lawn
					if (!(s.getBlock() instanceof BonemealableBlock b) || s.getBlock() instanceof GrassBlock || s.getBlock() instanceof MossBlock
							|| s.getBlock() instanceof NyliumBlock) continue;
					if (!b.isValidBonemealTarget(level, pos, s) || level.random.nextDouble() >= AuraConfig.ENTANGLE_GROW_CHANCE) continue;
					if (!b.isBonemealSuccess(level, level.random, pos, s)) continue;
					b.performBonemeal(level, level.random, pos, s);
					level.levelEvent(1505, pos, 8);
					grown++;
				}
			}
		}
		AuraFx.ring(level, at.x, at.y + 0.2, at.z, r, ParticleTypes.HAPPY_VILLAGER, r * 8);
		AuraFx.sound(level, player, AuraSounds.AZALEA_LEAVES_BREAK.value(), 1.0f, 0.8f);
		AuraFx.sound(level, player, AuraSounds.CAVE_VINES_PICK_BERRIES.value(), 0.8f, 1.3f);
		player.displayClientMessage(Component.literal(grown == 0 ? "Nothing there to grow." : "✦ Overgrowth: " + grown + " grew ✦").withStyle(ChatFormatting.DARK_GREEN), true);
		used(player, Ability.ENTANGLEMENT, now, AuraConfig.ENTANGLE_GROW_COOLDOWN_FACTOR);
		return true;
	}

	/** Thicket (hold Entanglement): the trap, with more beside it. */
	private static boolean thicket(ServerPlayer player, long now) {
		if (!Entanglement.press(player, now, AuraConfig.ENTANGLE_CHARGED_EXTRA_TRAPS, AuraConfig.ENTANGLE_CHARGED_SPREAD)) return false;
		// Entanglement.press started the normal cooldown; the charged factor goes on top
		used(player, Ability.ENTANGLEMENT, now, CastMode.CHARGED);
		return true;
	}

	/** Venom Spit (sneak + Verdant Viper): a quick spit at the first enemy in range, poison and a little damage, and you stay free. */
	private static boolean venomSpit(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.VERDANT_VIPER, AuraConfig.VIPER_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = player.serverLevel();
		Vec3 look = player.getLookAngle();
		Vec3 from = player.getEyePosition().add(look.scale(0.4)).add(0, -0.15, 0);
		VineHook.Trace aim = VineHook.trace(player, look, AuraConfig.VIPER_SPIT_RANGE);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_VIPER, 0);
		AuraFx.line(level, from, aim.tip(), AuraFx.dust(0x4FB848, 1.2f), (int) Math.max(6, from.distanceTo(aim.tip()) * 3));
		AuraFx.sound(level, player, AuraSounds.CAT_HISS.value(), 1.0f, 1.2f);
		LivingEntity target = aim.target();
		if (target != null) {
			AuraStunts.ACTIVE_ABILITY = Ability.VERDANT_VIPER;
			try {
				target.hurt(level.damageSources().playerAttack(player), AuraAbilities.vsMob(target, AuraConfig.VIPER_SPIT_DAMAGE));
			} finally {
				AuraStunts.ACTIVE_ABILITY = null;
			}
			Entanglement.poison(target, player, AuraConfig.VIPER_POISON_AMPLIFIER, AuraConfig.VIPER_SPIT_POISON_TICKS);
			Vec3 c = AuraFx.chest(target);
			AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(0x4FB848, 1.4f), 14, 0.3, 0.0);
			AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.ITEM_SLIME, 8, 0.3, 0.05);
			AuraStats.add(player, AuraStats.VIPER_BITES, 1);
		}
		used(player, Ability.VERDANT_VIPER, now, AuraConfig.VIPER_SPIT_COOLDOWN_FACTOR);
		return true;
	}

	// ---- Earth ----

	/** Tunnel (sneak + Quake): a 3x3 passage ahead through plain stone, dirt, sand and gravel. Not in the arena. */
	private static boolean tunnel(ServerPlayer player, long now) {
		if (com.toast.aurapoints.arena.Arena.in(player)) {
			player.displayClientMessage(Component.literal("Not in the arena.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		if (!AuraAbilities.checkCooldown(player, Ability.QUAKE, AuraConfig.EARTH_QUAKE_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = player.serverLevel();
		Vec3 look = player.getLookAngle();
		Direction facing = Direction.getNearest(look.x, 0, look.z);
		Direction side = facing.getClockWise();
		BlockPos feet = player.blockPosition();
		int broken = 0;
		for (int step = 1; step <= Math.max(1, AuraConfig.EARTH_TUNNEL_LENGTH); step++) {
			BlockPos base = feet.relative(facing, step);
			for (int s = -1; s <= 1; s++) {
				for (int y = 0; y <= 2; y++) {
					BlockPos pos = base.relative(side, s).above(y);
					if (!tunnelable(level.getBlockState(pos))) continue;
					level.destroyBlock(pos, AuraConfig.EARTH_TUNNEL_DROPS, player);
					broken++;
				}
			}
		}
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_EARTH, 2);
		Vec3 mouth = Vec3.atCenterOf(feet.relative(facing, 1).above());
		AuraFx.particles(level, ParticleTypes.CLOUD, mouth.x, mouth.y, mouth.z, 20, 0.6, 0.8, 0.6, 0.05);
		AuraFx.sound(level, player, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), 1.2f, 0.6f);
		AuraFx.sound(level, player, net.minecraft.sounds.SoundEvents.WARDEN_DIG, 1.0f, 0.9f);
		player.displayClientMessage(Component.literal(broken == 0 ? "Nothing to dig through there." : "✦ Tunnel: " + broken + " blocks ✦").withStyle(ChatFormatting.GOLD), true);
		used(player, Ability.QUAKE, now, AuraConfig.EARTH_TUNNEL_COOLDOWN_FACTOR);
		return true;
	}

	/** What the Tunnel digs through: the plain ground, never anything built. */
	private static boolean tunnelable(BlockState s) {
		return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL);
	}

	// ---- Light ----

	/** Guiding Light (sneak + Radiant Pulse): Night Vision for you and every friend in the pulse's reach. */
	private static boolean guidingLight(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.RADIANT_PULSE, AuraConfig.LIGHT_PULSE_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_LIGHT, 0);
		ServerLevel level = player.serverLevel();
		double r = Math.max(1, AuraConfig.LIGHT_PULSE_RADIUS);
		Vec3 c = AuraFx.chest(player);
		int lit = 0;
		for (ServerPlayer e : level.getEntitiesOfClass(ServerPlayer.class, new AABB(c, c).inflate(r),
				e -> e.isAlive() && !e.isSpectator() && AuraFx.chest(e).distanceToSqr(c) <= r * r && Light.friend(player, e))) {
			e.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, AuraConfig.LIGHT_GUIDING_TICKS, 0, false, false, true));
			AuraFx.helix(e, AuraFx.dust(0xFFF1A8, 1.0f), 20, 0.6, 2.0);
			lit++;
		}
		AuraFx.ring(level, c.x, player.getY() + 0.3, c.z, r, ParticleTypes.END_ROD, (int) (r * 4) + 6);
		AuraFx.sound(level, player, net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, 1.0f, 1.8f);
		player.displayClientMessage(Component.literal("✦ Guiding Light: " + lit + " ✦").withStyle(ChatFormatting.YELLOW), true);
		used(player, Ability.RADIANT_PULSE, now, AuraConfig.LIGHT_GUIDING_COOLDOWN_FACTOR);
		return true;
	}

	/** The first other friendly player along the look within {@code range}, in sight, or null. */
	private static ServerPlayer allyInSights(ServerPlayer p, double range) {
		ServerLevel level = p.serverLevel();
		Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
		ServerPlayer best = null;
		double bestAlong = Double.MAX_VALUE;
		for (ServerPlayer e : level.getEntitiesOfClass(ServerPlayer.class, p.getBoundingBox().inflate(range),
				e -> e != p && e.isAlive() && !e.isSpectator() && Light.friend(p, e))) {
			Vec3 to = AuraFx.chest(e).subtract(eye);
			double along = to.dot(look);
			if (along < 0 || along > range || along >= bestAlong) continue;
			if (to.subtract(look.scale(along)).length() > 1.5) continue;
			if (level.clip(new ClipContext(eye, AuraFx.chest(e), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) continue;
			best = e;
			bestAlong = along;
		}
		return best;
	}

	/** Your own tamed animal along the look within {@code range}, in sight, or null. */
	private static LivingEntity petInSights(ServerPlayer p, double range) {
		ServerLevel level = p.serverLevel();
		Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
		LivingEntity best = null;
		double bestAlong = Double.MAX_VALUE;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(range),
				e -> e.isAlive() && e instanceof net.minecraft.world.entity.OwnableEntity own && p.getUUID().equals(own.getOwnerUUID()))) {
			Vec3 to = AuraFx.chest(e).subtract(eye);
			double along = to.dot(look);
			if (along < 0 || along > range || along >= bestAlong) continue;
			if (to.subtract(look.scale(along)).length() > 1.5) continue;
			if (level.clip(new ClipContext(eye, AuraFx.chest(e), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) continue;
			best = e;
			bestAlong = along;
		}
		return best;
	}

	/** Mending Beam (sneak + Prism Beam): the ally in your sights is healed and given Regeneration. */
	private static boolean mendingBeam(ServerPlayer player, long now) {
		if (Light.isBeaming(player)) return false;
		if (!AuraAbilities.checkCooldown(player, Ability.PRISM_BEAM, AuraConfig.LIGHT_BEAM_COOLDOWN_TICKS, now)) return false;
		ServerPlayer ally = allyInSights(player, Math.max(4, AuraConfig.LIGHT_BEAM_RANGE));
		if (ally == null) {
			player.displayClientMessage(Component.literal("No ally in your sights to mend.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		ServerLevel level = player.serverLevel();
		ally.heal(Math.max(0, AuraConfig.LIGHT_MEND_HEAL));
		if (AuraConfig.LIGHT_MEND_REGEN_TICKS > 0) ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, AuraConfig.LIGHT_MEND_REGEN_TICKS, 0), player);
		Vec3 from = player.getEyePosition().add(player.getLookAngle().scale(0.5)).add(0, -0.2, 0), to = AuraFx.chest(ally);
		AuraFx.line(level, from, to, AuraFx.dust(0xFFF1A8, 1.3f), (int) Math.max(8, from.distanceTo(to) * 4));
		AuraFx.line(level, from, to, ParticleTypes.END_ROD, (int) Math.max(4, from.distanceTo(to) * 2));
		AuraFx.burst(level, to.x, to.y + 0.4, to.z, ParticleTypes.HEART, 3, 0.4, 0.0);
		AuraFx.helix(ally, AuraFx.dust(0xFFF1A8, 1.0f), 20, 0.6, 2.0);
		AuraFx.sound(level, player, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 1.5f);
		AuraFx.sound(level, player, net.minecraft.sounds.SoundEvents.BEACON_POWER_SELECT, 0.8f, 1.6f);
		player.displayClientMessage(Component.literal("✦ Mending Beam: " + ally.getGameProfile().getName() + " ✦").withStyle(ChatFormatting.YELLOW), true);
		used(player, Ability.PRISM_BEAM, now, AuraConfig.LIGHT_MEND_COOLDOWN_FACTOR);
		return true;
	}

	// ---- Blood ----

	/** Transfusion (sneak + Hemorrhage): every bleeding enemy nearby stops bleeding, takes a hit, and heals you. */
	private static boolean transfusion(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.HEMORRHAGE, AuraConfig.BLOOD_HEMO_COOLDOWN_TICKS, now)) return false;
		List<LivingEntity> bleeding = Blood.bleedingNear(player, AuraConfig.BLOOD_TRANSFUSION_RADIUS);
		if (bleeding.isEmpty()) {
			player.displayClientMessage(Component.literal("Nobody bleeding within reach.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_BLOOD, 0);
		ServerLevel level = player.serverLevel();
		Vec3 to = AuraFx.chest(player);
		AuraStunts.ACTIVE_ABILITY = Ability.HEMORRHAGE;
		try {
			for (LivingEntity e : bleeding) {
				Blood.stopBleeding(e);
				Handhelds.hurt(player, e, AuraConfig.BLOOD_TRANSFUSION_DAMAGE);
				player.heal(Math.max(0, AuraConfig.BLOOD_TRANSFUSION_HEAL));
				Vec3 from = AuraFx.chest(e);
				AuraFx.line(level, from, to, AuraFx.dust(0xB0121B, 1.2f), (int) Math.max(8, from.distanceTo(to) * 3));
			}
		} finally {
			AuraStunts.ACTIVE_ABILITY = null;
		}
		AuraFx.burst(level, to.x, to.y, to.z, AuraFx.dust(0xFF3B4A, 1.4f), 20, 0.5, 0.0);
		AuraFx.sound(level, player, net.minecraft.sounds.SoundEvents.WARDEN_HEARTBEAT, 1.4f, 1.1f);
		AuraFx.sound(level, player, net.minecraft.sounds.SoundEvents.BOTTLE_FILL, 1.0f, 0.7f);
		player.displayClientMessage(Component.literal("✦ Transfusion: " + bleeding.size() + " ✦").withStyle(ChatFormatting.RED), true);
		used(player, Ability.HEMORRHAGE, now, AuraConfig.BLOOD_TRANSFUSION_COOLDOWN_FACTOR);
		return true;
	}

	/** Lifeline (sneak + Blood Tether): the ally in your sights gets Regeneration and Resistance. */
	private static boolean lifeline(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.BLOOD_TETHER, AuraConfig.BLOOD_TETHER_COOLDOWN_TICKS, now)) return false;
		LivingEntity ally = allyInSights(player, Math.max(2, AuraConfig.BLOOD_TETHER_RANGE));
		if (ally == null && AuraConfig.BLOOD_LIFELINE_PETS) ally = petInSights(player, Math.max(2, AuraConfig.BLOOD_TETHER_RANGE));
		if (ally == null) {
			player.displayClientMessage(Component.literal("No ally in your sights for a lifeline.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_BLOOD, 1);
		int ticks = Math.max(20, AuraConfig.BLOOD_LIFELINE_TICKS);
		ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, Math.max(0, AuraConfig.BLOOD_LIFELINE_REGEN_AMPLIFIER)), player);
		ally.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, ticks, 0), player);
		ServerLevel level = player.serverLevel();
		Vec3 from = AuraFx.chest(player), to = AuraFx.chest(ally);
		AuraFx.line(level, from, to, AuraFx.dust(0xFF3B4A, 1.4f), (int) Math.max(8, from.distanceTo(to) * 3));
		AuraFx.burst(level, to.x, to.y + 0.4, to.z, ParticleTypes.HEART, 3, 0.4, 0.0);
		AuraFx.sound(level, player, net.minecraft.sounds.SoundEvents.WARDEN_HEARTBEAT, 1.3f, 1.4f);
		AuraFx.sound(level, player, net.minecraft.sounds.SoundEvents.CHAIN_PLACE, 0.8f, 0.8f);
		player.displayClientMessage(Component.literal("✦ Lifeline: " + ally.getName().getString() + " ✦").withStyle(ChatFormatting.RED), true);
		used(player, Ability.BLOOD_TETHER, now, AuraConfig.BLOOD_LIFELINE_COOLDOWN_FACTOR);
		return true;
	}

	// ---------------------------------------------------------------
	// Lore
	// ---------------------------------------------------------------

	/** The extra casts of an ability for the Aura Book and /aura info, one line each; empty without any (or with casts off). {@code aura}: whose kit it shows (Dash differs for Wind). */
	public static List<String> describe(Ability ability, AuraType aura) {
		List<String> out = new ArrayList<>(2);
		if (!AuraConfig.CAST_VARIANTS_ENABLED) return out;
		String sneak = sneak(ability, aura), charged = aura == AuraType.WIND && ability == Ability.DASH ? null : charged(ability);
		if (sneak != null) {
			// most sneak casts name their own cooldown share; the general factor only matters for the rest
			boolean ownShare = ability == Ability.DASH || ability == Ability.ABYSSAL_BUBBLE || ability == Ability.SUPER_WIND_CHARGE || ability == Ability.ICE_SPIKE
					|| ability == Ability.THUNDERBOLT || ability == Ability.CHIDORI || ability == Ability.VINE_HOOK || ability == Ability.ENTANGLEMENT || ability == Ability.VERDANT_VIPER
					|| ability == Ability.PILLAR_LIFT || ability == Ability.QUAKE || ability == Ability.RADIANT_PULSE || ability == Ability.SOLAR_FLASH || ability == Ability.PRISM_BEAM
					|| ability == Ability.HEMORRHAGE || ability == Ability.BLOOD_TETHER || ability == Ability.CRIMSON_SACRIFICE || ability == Ability.RIFT_STEP;
			out.add("Sneak + key (Mastery " + Mastery.roman(AuraConfig.CAST_SNEAK_MASTERY) + "): " + sneak
					+ (AuraConfig.CAST_SNEAK_COOLDOWN_FACTOR != 1.0 && !ownShare ? " " + pct(AuraConfig.CAST_SNEAK_COOLDOWN_FACTOR) + "% cooldown." : ""));
		}
		if (charged != null) {
			out.add("Hold the key (Mastery " + Mastery.roman(AuraConfig.CAST_CHARGED_MASTERY) + "): " + charged
					+ (AuraConfig.CAST_CHARGED_COOLDOWN_FACTOR != 1.0 ? " " + pct(AuraConfig.CAST_CHARGED_COOLDOWN_FACTOR) + "% cooldown." : ""));
		}
		return out;
	}

	/** The short name of an ability's sneak or charged cast for this aura ("Spread Shot"), or null when it has none. */
	public static String variantName(Ability ability, AuraType aura, CastMode mode) {
		if (mode == CastMode.TAP || !CastMode.has(ability, mode)) return null;
		String text = mode == CastMode.SNEAK ? sneak(ability, aura) : aura == AuraType.WIND && ability == Ability.DASH ? null : charged(ability);
		if (text == null) return null;
		int cut = text.indexOf(" - ");
		String name = cut > 0 ? text.substring(0, cut) : text;
		int note = name.indexOf(" (");
		return note > 0 ? name.substring(0, note) : name;
	}

	private static int pct(double factor) {
		return (int) Math.round(factor * 100);
	}

	private static String secs(int ticks) {
		return AuraAbilities.fmt(ticks / 20f) + "s";
	}

	private static String sneak(Ability a, AuraType aura) {
		if (a == Ability.DASH && aura == AuraType.WIND) {
			return "Tailwind - Speed " + Mastery.roman(AuraConfig.WIND_TAILWIND_AMPLIFIER + 1) + " for " + secs(AuraConfig.WIND_TAILWIND_TICKS) + ", "
					+ pct(AuraConfig.WIND_TAILWIND_COOLDOWN_FACTOR) + "% cooldown.";
		}
		return switch (a) {
			case SUPER_WIND_CHARGE -> "Gust - a push on yourself (or the boat you ride) along your look, no fall damage, " + pct(AuraConfig.WIND_GUST_COOLDOWN_FACTOR) + "% cooldown.";
			case ICE_SPIKE -> "Frost Path - for " + secs(AuraConfig.FROST_PATH_TICKS) + " still water freezes under your feet (it melts again)"
					+ (AuraConfig.FROST_PATH_DOUSE_RADIUS > 0 ? " and fire around you goes out, " : ", ")
					+ pct(AuraConfig.FROST_PATH_COOLDOWN_FACTOR) + "% cooldown.";
			case THUNDERBOLT -> "Static Rush - Speed " + Mastery.roman(AuraConfig.THUNDERBOLT_RUSH_AMPLIFIER + 1) + " for " + secs(AuraConfig.THUNDERBOLT_RUSH_TICKS) + ", "
					+ (AuraConfig.THUNDERBOLT_RUSH_VEHICLE_PUSH > 0 ? "and it drives the minecart or boat you ride, " : "") + pct(AuraConfig.THUNDERBOLT_RUSH_COOLDOWN_FACTOR) + "% cooldown.";
			case CHIDORI -> "Lightning Step - a blink " + AuraAbilities.fmt((float) AuraConfig.CHIDORI_STEP_DISTANCE) + " blocks along your look, "
					+ pct(AuraConfig.CHIDORI_STEP_COOLDOWN_FACTOR) + "% cooldown.";
			case VINE_HOOK -> "Harvest Hook - dropped items and animals within " + AuraAbilities.fmt((float) AuraConfig.VINE_HOOK_HARVEST_RANGE)
					+ " blocks in front of you are drawn to you, " + pct(AuraConfig.VINE_HOOK_HARVEST_COOLDOWN_FACTOR) + "% cooldown.";
			case ENTANGLEMENT -> "Overgrowth - crops and saplings within " + AuraConfig.ENTANGLE_GROW_RADIUS + " blocks of the aimed spot grow as if bonemealed, "
					+ pct(AuraConfig.ENTANGLE_GROW_COOLDOWN_FACTOR) + "% cooldown.";
			case VERDANT_VIPER -> "Venom Spit - a quick spit at the first enemy within " + AuraAbilities.fmt((float) AuraConfig.VIPER_SPIT_RANGE) + " blocks: "
					+ AuraAbilities.fmt(AuraConfig.VIPER_SPIT_DAMAGE) + " damage and Poison " + secs(AuraConfig.VIPER_SPIT_POISON_TICKS) + ", you stay free to move, "
					+ pct(AuraConfig.VIPER_SPIT_COOLDOWN_FACTOR) + "% cooldown.";
			case PILLAR_LIFT -> "Catapult - a pillar under yourself that throws you " + pct(AuraConfig.EARTH_CATAPULT_LAUNCH_FACTOR) + "% as high, "
					+ pct(AuraConfig.EARTH_CATAPULT_COOLDOWN_FACTOR) + "% cooldown.";
			case QUAKE -> "Tunnel - digs a 3x3 passage " + AuraConfig.EARTH_TUNNEL_LENGTH + " blocks ahead through plain stone, dirt, sand and gravel (not in the arena), "
					+ pct(AuraConfig.EARTH_TUNNEL_COOLDOWN_FACTOR) + "% cooldown.";
			case RADIANT_PULSE -> "Guiding Light - Night Vision " + secs(AuraConfig.LIGHT_GUIDING_TICKS) + " for you and every friend in the pulse's reach, "
					+ pct(AuraConfig.LIGHT_GUIDING_COOLDOWN_FACTOR) + "% cooldown.";
			case SOLAR_FLASH -> "Spotlight - no damage or blindness, but Glowing " + pct(AuraConfig.LIGHT_SPOTLIGHT_GLOW_FACTOR) + "% as long, "
					+ pct(AuraConfig.LIGHT_SPOTLIGHT_COOLDOWN_FACTOR) + "% cooldown.";
			case PRISM_BEAM -> "Mending Beam - the ally in your sights is healed " + AuraAbilities.fmt(AuraConfig.LIGHT_MEND_HEAL) + " and gets Regeneration "
					+ secs(AuraConfig.LIGHT_MEND_REGEN_TICKS) + ", " + pct(AuraConfig.LIGHT_MEND_COOLDOWN_FACTOR) + "% cooldown.";
			case HEMORRHAGE -> "Transfusion - every bleeding enemy within " + AuraAbilities.fmt((float) AuraConfig.BLOOD_TRANSFUSION_RADIUS) + " blocks stops bleeding, takes "
					+ AuraAbilities.fmt(AuraConfig.BLOOD_TRANSFUSION_DAMAGE) + " and heals you " + AuraAbilities.fmt(AuraConfig.BLOOD_TRANSFUSION_HEAL) + ", "
					+ pct(AuraConfig.BLOOD_TRANSFUSION_COOLDOWN_FACTOR) + "% cooldown.";
			case RIFT_STEP -> "Rift Anchor - marks where you stand for " + secs(AuraConfig.VOID_ANCHOR_TICKS) + "; sneak + key again within "
					+ Math.round(AuraConfig.VOID_ANCHOR_RANGE) + " blocks steps you back to it (not in the arena), " + pct(AuraConfig.VOID_ANCHOR_COOLDOWN_FACTOR) + "% cooldown on the return.";
			case BLOOD_TETHER -> "Lifeline - the ally " + (AuraConfig.BLOOD_LIFELINE_PETS ? "or pet of yours " : "") + "in your sights gets Regeneration " + Mastery.roman(AuraConfig.BLOOD_LIFELINE_REGEN_AMPLIFIER + 1) + " and Resistance I for "
					+ secs(AuraConfig.BLOOD_LIFELINE_TICKS) + ", " + pct(AuraConfig.BLOOD_LIFELINE_COOLDOWN_FACTOR) + "% cooldown.";
			case CRIMSON_SACRIFICE -> "Pinprick - " + pct(AuraConfig.BLOOD_PINPRICK_COST_FACTOR) + "% of the cost for " + pct(AuraConfig.BLOOD_PINPRICK_HITS_FACTOR)
					+ "% of the hits, " + pct(AuraConfig.BLOOD_PINPRICK_COOLDOWN_FACTOR) + "% cooldown.";
			case FIREBALL -> "Spread Shot - " + AuraConfig.FIREBALL_SPREAD_COUNT + " small fireballs in a fan, "
					+ pct(AuraConfig.FIREBALL_SPREAD_DAMAGE_FACTOR) + "% damage each.";
			case HEAT_WAVE -> "Flame Ward - a ring of fire " + AuraAbilities.fmt((float) AuraConfig.HEAT_WAVE_WARD_RADIUS) + " blocks around you, "
					+ pct(AuraConfig.HEAT_WAVE_WARD_DAMAGE_FACTOR) + "% damage, no knockback.";
			case FIRE_BENDING -> "Flame Cloak (with no flame in hand) - Fire Resistance for " + secs(AuraConfig.FIRE_BENDING_CLOAK_TICKS)
					+ "; enemies within " + AuraAbilities.fmt((float) AuraConfig.FIRE_BENDING_CLOAK_RADIUS) + " blocks catch fire"
					+ (AuraConfig.FIRE_BENDING_CLOAK_SMELT_ITEMS > 0 ? ", and up to " + AuraConfig.FIRE_BENDING_CLOAK_SMELT_ITEMS + " dropped items next to you are smelted." : ".");
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
			case THUNDERBOLT -> "Chain Lightning - a bolt from the hand at the first enemy within " + AuraAbilities.fmt((float) AuraConfig.THUNDERBOLT_CHAIN_RANGE)
					+ " blocks that jumps to up to " + AuraConfig.THUNDERBOLT_CHAINBOLT_TARGETS + " more, " + AuraAbilities.fmt(AuraConfig.THUNDERBOLT_CHAIN_DAMAGE)
					+ " damage each, all left Charged. No leap.";
			case VINE_HOOK -> "Long Hook - " + pct(AuraConfig.VINE_HOOK_CHARGED_RANGE_FACTOR) + "% range, " + pct(AuraConfig.VINE_HOOK_CHARGED_DAMAGE_FACTOR) + "% damage.";
			case ENTANGLEMENT -> "Thicket - " + (1 + AuraConfig.ENTANGLE_CHARGED_EXTRA_TRAPS) + " traps side by side.";
			case PILLAR_LIFT -> "Titan Pillar - " + AuraAbilities.fmt(AuraConfig.EARTH_PILLAR_CHARGED_HEIGHT) + " blocks tall, stands " + secs(AuraConfig.EARTH_PILLAR_CHARGED_HOLD_TICKS)
					+ ", " + pct(AuraConfig.EARTH_PILLAR_CHARGED_DAMAGE_FACTOR) + "% damage, " + pct(AuraConfig.EARTH_PILLAR_CHARGED_LAUNCH_FACTOR) + "% launch.";
			case BOULDER_TOSS -> "Great Boulder - " + pct(AuraConfig.EARTH_BOULDER_CHARGED_SCALE) + "% size, " + pct(AuraConfig.EARTH_BOULDER_CHARGED_DAMAGE_FACTOR) + "% damage, "
					+ pct(AuraConfig.EARTH_BOULDER_CHARGED_SPLASH_FACTOR) + "% splash (hold to pick it up, press again to throw).";
			case QUAKE -> "Great Quake - " + pct(AuraConfig.EARTH_QUAKE_CHARGED_RADIUS_FACTOR) + "% range, " + pct(AuraConfig.EARTH_QUAKE_CHARGED_DAMAGE_FACTOR) + "% damage.";
			case RADIANT_PULSE -> "Radiant Burst - " + pct(AuraConfig.LIGHT_PULSE_CHARGED_FACTOR) + "% range, heal and undead damage.";
			case SOLAR_FLASH -> "Nova - " + pct(AuraConfig.LIGHT_FLASH_CHARGED_ANGLE_FACTOR) + "% cone, blinds " + pct(AuraConfig.LIGHT_FLASH_CHARGED_BLIND_FACTOR) + "% as long, "
					+ pct(AuraConfig.LIGHT_FLASH_CHARGED_DAMAGE_FACTOR) + "% damage.";
			case HEMORRHAGE -> "Deep Cut - " + pct(AuraConfig.BLOOD_HEMO_CHARGED_DAMAGE_FACTOR) + "% damage, bleeds " + pct(AuraConfig.BLOOD_HEMO_CHARGED_BLEED_FACTOR) + "% as long.";
			case BLOOD_TETHER -> "Leech - drains " + pct(AuraConfig.BLOOD_TETHER_CHARGED_DRAIN_FACTOR) + "% and heals you " + pct(AuraConfig.BLOOD_TETHER_CHARGED_HEAL_FACTOR) + "% as much.";
			default -> null;
		};
	}
}
