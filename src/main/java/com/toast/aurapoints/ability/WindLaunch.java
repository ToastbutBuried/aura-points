package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraAbilities.Slot;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Wind's hold keys. Server side only; the key press/release comes from NetworkHandler.
 *  - Dash: a tap dashes (on release; a dive or the elytra boost where those apply), holding it for
 *    WIND_LAUNCH_HOLD_TICKS blasts the player straight up and shoves nearby mobs and players away -
 *    or, gliding with an elytra, boosts them forward.
 *  - Super Wind Charge: a tap throws the charge (on release), holding it for AIR_BENDING_HOLD_TICKS
 *    starts Air Bending; while the wind ball is held, a press fires it.
 */
public final class WindLaunch {

	private WindLaunch() {
	}

	/** Per key: who is holding it, and since which tick. Removed on release or when the hold completes. */
	private static final Map<Ability, Map<UUID, Long>> HELD = new EnumMap<>(Ability.class);
	/** Players thrown up by a launch or their own Air Bending blast: their landing takes no fall damage (if configured). */
	private static final Map<UUID, Long> LAUNCHED = new HashMap<>();

	static {
		HELD.put(Ability.DASH, new HashMap<>());
		HELD.put(Ability.SUPER_WIND_CHARGE, new HashMap<>());
	}

	/** True if this key press goes through here (tap/hold) instead of firing the ability at once. */
	public static boolean handlesKey(AuraPlayerData data, Ability ability) {
		return switch (ability) {
			case DASH -> AuraConfig.WIND_LAUNCH_ENABLED && data.aura == AuraType.WIND;
			case SUPER_WIND_CHARGE -> AuraConfig.AIR_BENDING_ENABLED;
			default -> false;
		};
	}

	public static void press(ServerPlayer player, Ability ability, long now) {
		// entangled or steering a viper: no Wind keys either (tryActivate's rule)
		if (heldInPlace(player)) return;
		// holding the wind ball: the press fires it
		if (ability == Ability.SUPER_WIND_CHARGE && AirBending.isHolding(player)) {
			AirBending.press(player, now);
			return;
		}
		HELD.get(ability).put(player.getUUID(), now);
	}

	/** Released before the hold completed: it was a tap. Frozen or stunned, the tap does nothing. */
	public static void release(ServerPlayer player, Ability ability, long now) {
		if (HELD.get(ability).remove(player.getUUID()) == null) return;
		if (IceSpike.isFrozen(player) || Stun.isBusy(player)) return;
		AuraAbilities.tryActivate(player, ability, now);
	}

	private static boolean heldInPlace(ServerPlayer player) {
		return VerdantViper.isSteering(player) || Entanglement.isEntangled(player);
	}

	/** Lets go of any held Dash / Super Wind Charge key without firing it (frozen, stunned). */
	public static void cancelHolds(ServerPlayer player) {
		for (Map<UUID, Long> m : HELD.values()) m.remove(player.getUUID());
	}

	public static void forget(ServerPlayer player) {
		for (Map<UUID, Long> m : HELD.values()) m.remove(player.getUUID());
		LAUNCHED.remove(player.getUUID());
	}

	/** Marks a player as thrown up: their next landing takes no fall damage. */
	static void markLaunched(ServerPlayer player, long now) {
		LAUNCHED.put(player.getUUID(), now);
	}

	/** True once, for the landing after a launch. */
	public static boolean consumeLanding(ServerPlayer player) {
		return AuraConfig.WIND_LAUNCH_NO_FALL_DAMAGE && LAUNCHED.remove(player.getUUID()) != null;
	}

	public static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		for (Map.Entry<Ability, Map<UUID, Long>> key : HELD.entrySet()) {
			Ability ability = key.getKey();
			if (key.getValue().isEmpty()) continue;
			int hold = Math.max(1, ability == Ability.DASH ? AuraConfig.WIND_LAUNCH_HOLD_TICKS : AuraConfig.AIR_BENDING_HOLD_TICKS);
			for (Iterator<Map.Entry<UUID, Long>> it = key.getValue().entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<UUID, Long> e = it.next();
				ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
				// also let go once the ability is gone (locked, switched off) or the player is held in place
				if (player == null || !player.isAlive() || now < e.getValue() || IceSpike.isFrozen(player) || Stun.isBusy(player) || heldInPlace(player)
						|| !AuraAbilities.hasAbility(AuraState.get(server).getOrCreate(player.getUUID()), ability)) {
					it.remove();
					continue;
				}
				long held = now - e.getValue();
				if (held >= hold) {
					it.remove();
					if (ability == Ability.DASH) launch(player, now);
					else AirBending.start(player, now);
				} else if (held >= 2) {
					buildUp(player, ability, held, hold);
				}
			}
		}
		if (!LAUNCHED.isEmpty()) {
			for (Iterator<Map.Entry<UUID, Long>> it = LAUNCHED.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<UUID, Long> e = it.next();
				ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
				if (player == null || !player.isAlive() || (now - e.getValue() > 10 && (player.onGround() || player.isInWater()))
						|| now - e.getValue() > 20 * 20) it.remove();
			}
		}
	}

	/** While the key is held: wind swirling at the feet (launch) or gathering in front of the hands (air bending). */
	private static void buildUp(ServerPlayer player, Ability ability, long held, int hold) {
		ServerLevel level = player.serverLevel();
		double r = 0.8 * (1.0 - (double) held / hold) + 0.3;
		if (ability == Ability.DASH) {
			AuraFx.particles(level, ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1, player.getZ(), 2, r, 0.05, r, 0.02);
		} else {
			Vec3 p = player.getEyePosition().add(player.getLookAngle().scale(AuraConfig.AIR_BENDING_HOLD_DISTANCE));
			// specks drawn in toward where the ball will form
			AuraFx.particles(level, ParticleTypes.CLOUD, p.x, p.y, p.z, 2, r * 0.5, r * 0.5, r * 0.5, 0.0);
			AuraFx.particles(level, AuraFx.dust(AuraType.WIND.rgb, 1.0f), p.x, p.y, p.z, 2, r * 0.6, r * 0.6, r * 0.6, 0.0);
		}
		if (held % 4 == 0) level.playSound(null, player.blockPosition(), AuraSounds.BREEZE_IDLE_AIR.value(), SoundSource.PLAYERS, 0.6f, 0.8f + 0.8f * held / hold);
	}

	private static void launch(ServerPlayer player, long now) {
		if (player.isPassenger()) return;
		if (player.isFallFlying()) {
			elytraBoost(player, now);
			return;
		}
		if (!AuraAbilities.checkCooldown(player, Ability.DASH, Slot.LAUNCH, now)) return;
		ServerLevel level = player.serverLevel();
		Vec3 p = player.position();

		// shove everyone close by away from the player: sideways plus a little lift, no damage
		double radius = AuraConfig.WIND_LAUNCH_RADIUS;
		Combos.pass(player, p, p, radius, player.getLookAngle());
		for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(radius), t -> canShove(player, t))) {
			Vec3 away = e.position().subtract(p).multiply(1, 0, 1);
			if (away.lengthSqr() < 1.0e-4) away = new Vec3(player.getRandom().nextDouble() - 0.5, 0, player.getRandom().nextDouble() - 0.5);
			if (e.distanceTo(player) > radius) continue;
			Vec3 push = away.normalize().scale(AuraConfig.WIND_LAUNCH_KNOCKBACK)
					.add(0, AuraConfig.WIND_LAUNCH_LIFT, 0);
			e.setDeltaMovement(e.getDeltaMovement().add(push));
			e.hurtMarked = true;
		}

		Vec3 v = player.getDeltaMovement();
		player.setDeltaMovement(v.x * 0.5, AuraConfig.WIND_LAUNCH_VELOCITY, v.z * 0.5);
		player.hurtMarked = true;
		player.fallDistance = 0.0f;
		markLaunched(player, now);
		// launch, then tap Dash high up: the Dash Dive slams you back down
		if (AuraConfig.WIND_LAUNCH_ALLOWS_DIVE && player.getServer() != null) {
			DashDive.dashed(player, AuraState.get(player.getServer()).getOrCreate(player.getUUID()), now);
		}

		AuraFx.ring(level, p.x, p.y + 0.1, p.z, radius * 0.5, ParticleTypes.GUST, 10);
		AuraFx.ring(level, p.x, p.y + 0.1, p.z, radius, ParticleTypes.CLOUD, 24);
		AuraFx.burst(level, p.x, p.y + 0.2, p.z, AuraFx.dust(AuraType.WIND.rgb, 1.8f), 30, radius * 0.4, 0.0);
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.GUST_EMITTER_LARGE, 1, 0.0, 0.0);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.2f, 0.7f);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.BREEZE_JUMP.value(), SoundSource.PLAYERS, 1.0f, 0.8f);

		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_DOUBLE_JUMP, player.getRandom().nextInt(2));
		AuraAbilities.startCooldown(player, Ability.DASH, Slot.LAUNCH, AuraConfig.WIND_LAUNCH_COOLDOWN_TICKS, now);
	}

	/** Gliding: a big burst of speed along the look direction instead of the upward launch. Flight set. */
	private static void elytraBoost(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.DASH, Slot.FLIGHT, now)) return;
		ServerLevel level = player.serverLevel();
		Vec3 p = player.position();
		player.setDeltaMovement(player.getDeltaMovement().add(player.getLookAngle().scale(AuraConfig.WIND_LAUNCH_ELYTRA_BOOST)));
		player.hurtMarked = true;

		AuraFx.ring(level, p.x, p.y + 0.5, p.z, 1.5, ParticleTypes.GUST, 8);
		AuraFx.burst(level, p.x, p.y + 0.5, p.z, AuraFx.dust(AuraType.WIND.rgb, 1.8f), 24, 0.6, 0.0);
		AuraFx.trail(player, AuraFx.dust(AuraType.WIND.rgb, 1.6f), 4, 16);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.0f, 1.0f);
		AuraAbilities.startCooldown(player, Ability.DASH, Slot.FLIGHT, AuraConfig.WIND_LAUNCH_ELYTRA_COOLDOWN_TICKS, now);
	}

	static boolean canShove(ServerPlayer player, Entity e) {
		return Handhelds.enemy(player, e);
	}
}
