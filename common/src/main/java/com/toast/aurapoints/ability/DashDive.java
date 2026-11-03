package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dash Dive, the Wind aura's follow-up to Dash (Shadow's Dash has none): right after a Dash, if you are high
 * enough above the ground, pressing Dash again (cooldown or not) slams you
 * straight down. Landing from the dive takes no fall damage and sends a weak
 * shockwave out in front of you. One dive per dash - the dive itself never
 * opens another one.
 */
public final class DashDive {

	private DashDive() {
	}

	/** dashTick: when the dash happened; hinted: the "dash again" hint was shown; diving: the dive is on its way down. */
	private static final class State {
		final long dashTick;
		boolean hinted;
		boolean diving;
		long diveTick;
		/** height the dive started at, for the landing damage */
		double diveY;

		State(long dashTick) {
			this.dashTick = dashTick;
		}
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	/** Called after every normal Dash: opens the dive window, for Wind players only. */
	public static void dashed(ServerPlayer player, AuraPlayerData data, long now) {
		if (data.aura != AuraType.WIND) return;
		STATES.put(player.getUUID(), new State(now));
	}

	/** True while the player is falling from a dive (fall damage is skipped then). */
	public static boolean isDiving(ServerPlayer player) {
		State s = STATES.get(player.getUUID());
		return s != null && s.diving;
	}

	/** Blocks between the player's feet and the ground below (up to 64). */
	private static double heightAboveGround(ServerPlayer player) {
		Vec3 from = player.position();
		Vec3 to = from.subtract(0, 64, 0);
		HitResult hit = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
		return hit.getType() == HitResult.Type.MISS ? 64 : from.y - hit.getLocation().y;
	}

	private static boolean canDive(ServerPlayer player, State s, long now) {
		return s != null && !s.diving && now - s.dashTick >= 0 && now - s.dashTick <= AuraConfig.DASH_DIVE_WINDOW_TICKS
				&& !player.onGround() && !player.isInWater() && !player.isFallFlying()
				&& heightAboveGround(player) >= AuraConfig.DASH_DIVE_MIN_HEIGHT;
	}

	/**
	 * Called when the Dash key is pressed. Returns true if that press became a
	 * dive (so the normal Dash must not run).
	 */
	public static boolean tryDive(ServerPlayer player, long now) {
		State s = STATES.get(player.getUUID());
		if (!canDive(player, s, now)) return false;

		Vec3 look = player.getLookAngle();
		player.setDeltaMovement(look.x * 0.25, -AuraConfig.DASH_DIVE_SPEED, look.z * 0.25);
		player.hurtMarked = true;
		player.fallDistance = 0.0f;
		s.diving = true;
		s.diveTick = now;
		s.diveY = player.getY();
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.DIVE);

		ServerLevel level = player.serverLevel();
		AuraFx.burst(level, player.getX(), player.getY() + 0.8, player.getZ(), AuraFx.dust(AuraType.WIND.rgb, 1.6f), 18, 0.35, 0.0);
		AuraFx.burst(level, player.getX(), player.getY() + 0.8, player.getZ(), ParticleTypes.GUST, 1, 0.0, 0.0);
		AuraFx.burst(level, player.getX(), player.getY() + 0.8, player.getZ(), ParticleTypes.LARGE_SMOKE, 8, 0.3, 0.02);
		AuraFx.sound(level, player, AuraSounds.PHANTOM_SWOOP.value(), 0.9f, 0.8f);
		AuraFx.trail(player, AuraFx.dust(AuraType.WIND.rgb, 1.8f), 4, 20);
		return true;
	}

	/** Every server tick for players with Dash: the hint, landing, and cleaning up old windows. */
	public static void tick(ServerPlayer player, long now) {
		State s = STATES.get(player.getUUID());
		if (s == null) return;
		if (!player.isAlive()) {
			// died mid-dash/dive: no landing shockwave at the respawn point
			STATES.remove(player.getUUID());
			if (s.diving) NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
			return;
		}

		if (s.diving) {
			if (player.onGround() || player.isInWater()) {
				STATES.remove(player.getUUID());
				NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
				NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_DIVE_LAND, 0);
				player.fallDistance = 0.0f;
				landingShockwave(player, Math.max(0, s.diveY - player.getY()));
				// the slam costs more: Dash recharges slower after a dive, counted from the landing
				AuraAbilities.startCooldown(player, AuraAbilities.Ability.DASH, AuraConfig.DASH_DIVE_COOLDOWN_TICKS, now);
			} else if (now - s.diveTick > 200 || now < s.diveTick) {
				STATES.remove(player.getUUID()); // never landed (flew off, teleported) - give up
				NetworkHandler.sendBendAnim(player, BendingAnimPacket.NONE);
			}
			return;
		}

		if (now - s.dashTick > AuraConfig.DASH_DIVE_WINDOW_TICKS || now < s.dashTick) {
			STATES.remove(player.getUUID());
			return;
		}
		if (!s.hinted && canDive(player, s, now)) {
			s.hinted = true;
			player.displayClientMessage(Component.literal("✦ Dash again to dive! ✦").withStyle(ChatFormatting.DARK_PURPLE), true);
			AuraFx.sound(player.serverLevel(), player, AuraSounds.AMETHYST_BLOCK_CHIME.value(), 0.6f, 0.6f);
		}
	}

	/** The weak shockwave in front of the landing spot: slight damage and knockback, lots of dark dust. */
	/** Base damage plus DASH_DIVE_DAMAGE_PER_BLOCK for every block dived, never more than DASH_DIVE_DAMAGE_MAX. */
	static float diveDamage(double blocksFallen) {
		double dmg = AuraConfig.DASH_DIVE_SHOCKWAVE_DAMAGE + Math.max(0, blocksFallen) * AuraConfig.DASH_DIVE_DAMAGE_PER_BLOCK;
		return (float) Math.min(Math.max(AuraConfig.DASH_DIVE_SHOCKWAVE_DAMAGE, AuraConfig.DASH_DIVE_DAMAGE_MAX), dmg);
	}

	private static void landingShockwave(ServerPlayer player, double blocksFallen) {
		float damage = diveDamage(blocksFallen);
		ServerLevel level = player.serverLevel();
		AuraFx.sound(level, player, AuraSounds.SWEET_IMPACT.value(), 1.1f, 0.9f);
		Vec3 look = player.getLookAngle().multiply(1, 0, 1);
		if (look.lengthSqr() < 1.0e-4) look = new Vec3(0, 0, 1);
		Vec3 c = player.position().add(look.normalize().scale(AuraConfig.DASH_DIVE_SHOCKWAVE_FORWARD));
		double r = AuraConfig.DASH_DIVE_SHOCKWAVE_RADIUS;
		Combos.pass(player, c, c, r, look);

		List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
				new net.minecraft.world.phys.AABB(c, c).inflate(r, 2.0, r),
				e -> Handhelds.enemy(player, e) && e.position().distanceToSqr(c) <= r * r);
		for (LivingEntity target : targets) {
			target.hurt(level.damageSources().playerAttack(player), AuraAbilities.vsMob(target, damage));
			target.knockback(AuraConfig.DASH_DIVE_SHOCKWAVE_KNOCKBACK, c.x - target.getX(), c.z - target.getZ());
			Vec3 tc = AuraFx.chest(target);
			AuraFx.burst(level, tc.x, tc.y, tc.z, ParticleTypes.CRIT, 6, 0.25, 0.15);
		}

		AuraFx.burst(level, c.x, c.y + 0.1, c.z, ParticleTypes.LARGE_SMOKE, 14, r / 3, 0.02);
		AuraFx.burst(level, player.getX(), player.getY() + 0.1, player.getZ(), ParticleTypes.POOF, 12, 0.4, 0.05);
		MinecraftServer server = player.getServer();
		double cx = c.x, cy = c.y + 0.15, cz = c.z;
		for (int step = 1; step <= 4; step++) {
			final double radius = r * step / 4.0;
			AuraFx.later(server, step, () -> {
				AuraFx.ring(level, cx, cy, cz, radius, AuraFx.dust(AuraType.WIND.rgb, 1.4f), (int) (radius * 8));
				AuraFx.ring(level, cx, cy, cz, radius, ParticleTypes.CLOUD, (int) (radius * 5));
			});
		}
		level.playSound(null, c.x, c.y, c.z, AuraSounds.MACE_SMASH_GROUND.value(), net.minecraft.sounds.SoundSource.PLAYERS, 0.9f, 0.8f);
	}

	public static void onLogout(ServerPlayer player) {
		STATES.remove(player.getUUID());
	}
}
