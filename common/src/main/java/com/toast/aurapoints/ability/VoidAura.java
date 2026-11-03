package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
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
 * Void: Void Pull, Rift Step and Null Orb on the Ability keys, Event Horizon and Entropy always on (its
 * Ultimate, Singularity, is in Ultimates). Named VoidAura so it never stands in for java.lang.Void in this package.
 */
public final class VoidAura {

	private VoidAura() {
	}

	static final int VIOLET = 0x7A2BD6, DEEP = 0x2A0A4A;

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
	}

	/** Purple specks streaming from {@code from} into {@code to}. */
	static void stream(ServerLevel level, Vec3 from, Vec3 to, int points) {
		for (int i = 0; i <= points; i++) {
			Vec3 at = from.lerp(to, i / (double) points);
			AuraFx.particles(level, i % 2 == 0 ? ParticleTypes.REVERSE_PORTAL : AuraFx.dust(i % 4 == 1 ? VIOLET : DEEP, 0.9f), at.x, at.y, at.z, 1, 0.05, 0.05, 0.05, 0);
		}
	}

	// ---------------------------------------------------------------
	// Void Pull
	// ---------------------------------------------------------------

	static boolean pull(ServerPlayer p, long now) {
		if (!AuraAbilities.checkCooldown(p, Ability.VOID_PULL, AuraConfig.VOID_PULL_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = p.serverLevel();
		double range = Math.max(2, AuraConfig.VOID_PULL_RANGE);
		double cos = Math.cos(Math.toRadians(Math.max(5, AuraConfig.VOID_PULL_ANGLE) / 2));
		Vec3 eye = p.getEyePosition(), look = p.getLookAngle(), c = AuraFx.chest(p);
		int pulled = 0;
		for (LivingEntity e : Handhelds.enemies(p, level, eye, range)) {
			Vec3 to = AuraFx.chest(e).subtract(eye);
			if (to.lengthSqr() < 1.0e-4 || to.normalize().dot(look) < cos) continue;
			if (level.clip(new ClipContext(eye, AuraFx.chest(e), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) continue;
			Handhelds.hurt(p, e, AuraConfig.VOID_PULL_DAMAGE);
			// yanked to land about a step in front of you
			Vec3 back = c.subtract(AuraFx.chest(e));
			double d = back.length();
			Vec3 v = back.normalize().scale(Math.min(2.2, Math.max(0.4, (d - 1.5) * 0.24 * AuraConfig.VOID_PULL_STRENGTH)));
			e.setDeltaMovement(v.x, Math.max(0.25, v.y + 0.25), v.z);
			e.hurtMarked = true;
			stream(level, AuraFx.chest(e), c, (int) (d * 2));
			pulled++;
		}
		Vec3 hand = Light.pointingHand(p);
		AuraFx.burst(level, hand.x, hand.y, hand.z, ParticleTypes.REVERSE_PORTAL, 30, 0.5, 0.15);
		AuraFx.particles(level, AuraFx.dust(VIOLET, 1.3f), hand.x, hand.y, hand.z, 12, 0.3, 0.3, 0.3, 0);
		sound(level, hand, SoundEvents.ENDERMAN_TELEPORT, 1.0f, 0.5f);
		sound(level, hand, SoundEvents.WARDEN_SONIC_CHARGE, 0.6f, 1.6f);
		if (pulled > 0) sound(level, c, SoundEvents.CHORUS_FRUIT_TELEPORT, 1.0f, 0.6f);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_VOID, 0);
		AuraAbilities.markUsed(p, Ability.VOID_PULL, now);
		return true;
	}

	// ---------------------------------------------------------------
	// Rift Step
	// ---------------------------------------------------------------

	private static final class Rift {
		final UUID owner;
		final ServerLevel level;
		final Vec3 at;
		final List<Display.BlockDisplay> parts = new ArrayList<>();
		int t;

		Rift(UUID owner, ServerLevel level, Vec3 at) {
			this.owner = owner;
			this.level = level;
			this.at = at;
		}
	}

	private static final List<Rift> RIFTS = new ArrayList<>();

	static boolean riftStep(ServerPlayer p, long now) {
		if (!AuraAbilities.checkCooldown(p, Ability.RIFT_STEP, AuraConfig.VOID_RIFT_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = p.serverLevel();
		Vec3 look = p.getLookAngle(), eye = p.getEyePosition();
		double range = Math.max(2, AuraConfig.VOID_RIFT_RANGE);
		HitResult wall = level.clip(new ClipContext(eye, eye.add(look.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		double far = wall.getType() == HitResult.Type.MISS ? range : Math.max(0, eye.distanceTo(wall.getLocation()) - 0.7);
		Vec3 target = null;
		// the farthest spot along your view with room for you; looking down at the ground, it steps up onto it
		search:
		for (double d = far; d >= 1.5; d -= 0.5) {
			Vec3 feet = eye.add(look.scale(d)).subtract(0, p.getEyeHeight(), 0);
			for (double up = 0; up <= 1.5; up += 0.25) {
				Vec3 at = feet.add(0, up, 0);
				if (level.noCollision(p, p.getBoundingBox().move(at.subtract(p.position())))) {
					target = at;
					break search;
				}
			}
		}
		if (target == null) {
			p.displayClientMessage(Component.literal("No room to step through to.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		Vec3 from = p.position();
		openRift(p, level, from.add(0, 1.0, 0), look);
		p.teleportTo(level, target.x, target.y, target.z, p.getYRot(), p.getXRot());
		p.fallDistance = 0;
		p.setDeltaMovement(p.getDeltaMovement().scale(0.3));
		p.hurtMarked = true;
		Vec3 out = AuraFx.chest(p);
		AuraFx.burst(level, out.x, out.y, out.z, ParticleTypes.REVERSE_PORTAL, 40, 0.6, 0.2);
		AuraFx.particles(level, AuraFx.dust(DEEP, 1.5f), out.x, out.y, out.z, 16, 0.4, 0.6, 0.4, 0);
		sound(level, from, SoundEvents.ENDERMAN_TELEPORT, 1.0f, 0.7f);
		sound(level, out, SoundEvents.ENDERMAN_TELEPORT, 1.0f, 1.1f);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_VOID, 1);
		AuraAbilities.markUsed(p, Ability.RIFT_STEP, now);
		return true;
	}

	/** A jagged black tear standing in the air, turned across {@code look}. */
	private static void openRift(ServerPlayer p, ServerLevel level, Vec3 at, Vec3 look) {
		Rift r = new Rift(p.getUUID(), level, at);
		int life = Math.max(20, AuraConfig.VOID_RIFT_TICKS) + 12;
		float yaw = (float) Math.atan2(look.x, look.z);
		// three slivers, leaning a little apart: a crack in the air
		float[][] slivers = {{0, 0, 2.3f, 0.45f, 0}, {-0.16f, 0.15f, 1.6f, 0.3f, 0.24f}, {0.16f, -0.1f, 1.7f, 0.28f, -0.26f}};
		for (int i = 0; i < slivers.length; i++) {
			float[] s = slivers[i];
			Quaternionf rot = new Quaternionf().rotateY(yaw).rotateZ(s[4]);
			var hidden = new com.mojang.math.Transformation(new Quaternionf(rot).transform(new Vector3f(-0.01f, -s[2] / 2f, -0.04f)).add(s[0], s[1], 0),
					rot, new Vector3f(0.02f, s[2], 0.08f), null);
			var shown = new com.mojang.math.Transformation(new Quaternionf(rot).transform(new Vector3f(-s[3] / 2f, -s[2] / 2f, -0.04f)).add(s[0], s[1], 0),
					rot, new Vector3f(s[3], s[2], 0.08f), null);
			var d = Props.spawn(level, at, i == 0 ? Blocks.BLACK_CONCRETE.defaultBlockState() : Blocks.CRYING_OBSIDIAN.defaultBlockState(), hidden, life, 0);
			if (i > 0) Props.glow(d);
			Props.setLater(d, 1, shown, 4);
			Props.setLater(d, life - 10, hidden, 8);
			r.parts.add(d);
		}
		RIFTS.add(r);
	}

	/** Returns false when the rift has closed. */
	private static boolean tickRift(MinecraftServer server, Rift r) {
		r.t++;
		ServerLevel level = r.level;
		int life = Math.max(20, AuraConfig.VOID_RIFT_TICKS);
		if (r.t > life) return false;
		var random = level.getRandom();
		AuraFx.particles(level, ParticleTypes.PORTAL, r.at.x, r.at.y, r.at.z, 3, 0.15, 0.8, 0.15, 0.4);
		if (r.t % 3 == 0) AuraFx.particles(level, AuraFx.dust(VIOLET, 0.8f), r.at.x + (random.nextDouble() - 0.5) * 0.4, r.at.y + (random.nextDouble() - 0.5) * 2, r.at.z + (random.nextDouble() - 0.5) * 0.4, 1, 0, 0, 0, 0);
		if (r.t % 20 == 0) sound(level, r.at, SoundEvents.PORTAL_AMBIENT, 0.25f, 1.6f);
		ServerPlayer owner = server.getPlayerList().getPlayer(r.owner);
		if (owner == null || r.t % Math.max(2, AuraConfig.VOID_RIFT_HIT_TICKS) != 0) return true;
		for (LivingEntity e : Handhelds.enemies(owner, level, r.at, 1.4)) {
			Handhelds.hurt(owner, e, AuraConfig.VOID_RIFT_DAMAGE);
			e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1), owner);
			Vec3 c = AuraFx.chest(e);
			AuraFx.particles(level, ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 10, 0.3, 0.4, 0.3, 0.05);
		}
		return true;
	}

	// ---------------------------------------------------------------
	// Null Orb
	// ---------------------------------------------------------------

	private static final class Orb {
		final UUID owner;
		final ServerLevel level;
		final Display.BlockDisplay core, shell;
		Vec3 pos;
		final Vec3 vel;
		int t;
		double flown;

		Orb(UUID owner, ServerLevel level, Display.BlockDisplay core, Display.BlockDisplay shell, Vec3 pos, Vec3 vel) {
			this.owner = owner;
			this.level = level;
			this.core = core;
			this.shell = shell;
			this.pos = pos;
			this.vel = vel;
		}
	}

	private static final List<Orb> ORBS = new ArrayList<>();
	/** Players whose abilities a Null Orb switched off, until (server tick). */
	private static final Map<UUID, Long> NULLED = new HashMap<>();

	/** Hit by a Null Orb: no abilities for a moment. */
	public static boolean isNulled(ServerPlayer p) {
		Long until = NULLED.get(p.getUUID());
		return until != null && p.getServer() != null && until > p.getServer().getTickCount();
	}

	private static com.mojang.math.Transformation orbShape(float size, float angle) {
		return Props.cube(new Vector3f(), size, new Quaternionf().rotateY(angle).rotateX(angle * 0.7f + 0.5f));
	}

	static boolean nullOrb(ServerPlayer p, long now) {
		if (!AuraAbilities.checkCooldown(p, Ability.NULL_ORB, AuraConfig.VOID_ORB_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = p.serverLevel();
		Vec3 start = p.getEyePosition().add(p.getLookAngle().scale(1.0)).subtract(0, 0.25, 0);
		Display.BlockDisplay core = Props.spawn(level, start, Blocks.CRYING_OBSIDIAN.defaultBlockState(), orbShape(0.5f, 0), -1, 2);
		Display.BlockDisplay shell = Props.spawn(level, start, Blocks.PURPLE_STAINED_GLASS.defaultBlockState(), orbShape(0.85f, 0.6f), -1, 2);
		Props.glow(core);
		ORBS.add(new Orb(p.getUUID(), level, core, shell, start, p.getLookAngle().scale(Math.max(0.1, AuraConfig.VOID_ORB_SPEED))));
		AuraFx.burst(level, start.x, start.y, start.z, ParticleTypes.REVERSE_PORTAL, 20, 0.3, 0.1);
		sound(level, start, SoundEvents.ILLUSIONER_CAST_SPELL, 1.0f, 0.7f);
		sound(level, start, SoundEvents.BEACON_DEACTIVATE, 0.8f, 0.6f);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_VOID, 2);
		AuraAbilities.markUsed(p, Ability.NULL_ORB, now);
		return true;
	}

	/** Returns false when the orb is gone. */
	private static boolean tickOrb(MinecraftServer server, Orb o) {
		ServerPlayer p = server.getPlayerList().getPlayer(o.owner);
		ServerLevel level = o.level;
		o.t++;
		Vec3 next = o.pos.add(o.vel);
		HitResult wall = level.clip(new ClipContext(o.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, o.core));
		Vec3 end = wall.getType() == HitResult.Type.MISS ? next : wall.getLocation();
		LivingEntity hit = null;
		if (p != null) {
			var entityHit = ProjectileUtil.getEntityHitResult(level, p, o.pos, end, new AABB(o.pos, end).inflate(0.8), e -> Handhelds.enemy(p, e), 0.45f);
			if (entityHit != null && entityHit.getEntity() instanceof LivingEntity l) hit = l;
		}
		if (p == null || hit != null || wall.getType() != HitResult.Type.MISS || (o.flown += o.vel.length()) > Math.max(4, AuraConfig.VOID_ORB_RANGE)) {
			burstOrb(p, o, hit != null ? AuraFx.chest(hit) : end, hit);
			return false;
		}
		o.pos = next;
		Props.move(o.core, next);
		Props.move(o.shell, next);
		if (o.t % 2 == 0) {
			Props.set(o.core, orbShape(0.5f, o.t * 0.25f), 2);
			Props.set(o.shell, orbShape(0.85f + 0.06f * (float) Math.sin(o.t * 0.5), -o.t * 0.18f + 0.6f), 2);
		}
		AuraFx.particles(level, ParticleTypes.REVERSE_PORTAL, next.x, next.y, next.z, 3, 0.2, 0.2, 0.2, 0.02);
		if (o.t % 2 == 0) AuraFx.particles(level, AuraFx.dust(DEEP, 1.1f), next.x, next.y, next.z, 2, 0.25, 0.25, 0.25, 0);
		if (o.t % 10 == 0) sound(level, next, SoundEvents.BEACON_AMBIENT, 0.6f, 0.5f);
		return true;
	}

	private static void burstOrb(ServerPlayer p, Orb o, Vec3 at, LivingEntity hit) {
		Props.remove(o.core);
		Props.remove(o.shell);
		ServerLevel level = o.level;
		if (p != null) {
			for (LivingEntity e : Handhelds.enemies(p, level, at, 1.8)) {
				Handhelds.hurt(p, e, e == hit ? AuraConfig.VOID_ORB_DAMAGE : AuraConfig.VOID_ORB_DAMAGE * 0.5f);
				int ticks = Math.max(20, AuraConfig.VOID_ORB_NULL_TICKS);
				if (e instanceof ServerPlayer victim) {
					NULLED.put(victim.getUUID(), (long) level.getServer().getTickCount() + ticks);
					victim.displayClientMessage(Component.literal("Nulled - your aura is silent for a moment").withStyle(ChatFormatting.DARK_PURPLE), true);
				} else {
					e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 1), p);
				}
			}
		}
		AuraFx.burst(level, at.x, at.y, at.z, ParticleTypes.REVERSE_PORTAL, 50, 0.8, 0.25);
		AuraFx.burst(level, at.x, at.y, at.z, AuraFx.dust(VIOLET, 1.5f), 30, 0.8, 0);
		AuraFx.particles(level, ParticleTypes.SQUID_INK, at.x, at.y, at.z, 6, 0.3, 0.3, 0.3, 0.05);
		sound(level, at, SoundEvents.ENDER_EYE_DEATH, 1.2f, 0.6f);
		sound(level, at, SoundEvents.GLASS_BREAK, 0.8f, 0.5f);
	}

	// ---------------------------------------------------------------
	// Passives
	// ---------------------------------------------------------------

	private static final ResourceLocation ENTROPY = ResourceLocation.fromNamespaceAndPath("aurapoints", "void_entropy");
	/** entity -> server tick its Entropy wears off */
	private static final Map<LivingEntity, Long> DECAYING = new HashMap<>();

	/** Event Horizon: projectiles (not your own) near you slow to a crawl. Every server tick for every player. */
	public static void tickPassives(ServerPlayer p, AuraPlayerData data, long now) {
		if (!AuraAbilities.hasAbility(data, Ability.EVENT_HORIZON) || !p.isAlive() || p.isSpectator()) return;
		double r = Math.max(1, AuraConfig.VOID_HORIZON_RADIUS);
		for (Projectile pr : p.serverLevel().getEntitiesOfClass(Projectile.class, p.getBoundingBox().inflate(r), pr -> pr.getOwner() != p && !pr.onGround())) {
			Vec3 v = pr.getDeltaMovement();
			if (v.lengthSqr() < 0.09) continue;
			pr.setDeltaMovement(v.scale(Math.max(0.1, Math.min(1, AuraConfig.VOID_HORIZON_SLOW))));
			pr.hurtMarked = true;
			AuraFx.particles(p.serverLevel(), ParticleTypes.REVERSE_PORTAL, pr.getX(), pr.getY(), pr.getZ(), 3, 0.1, 0.1, 0.1, 0.02);
		}
	}

	/** Entropy: a hit of yours (any kind) wears the target's armor down for a while. */
	public static void onHit(ServerPlayer attacker, AuraPlayerData data, LivingEntity victim) {
		if (!AuraAbilities.hasAbility(data, Ability.ENTROPY) || victim == attacker || attacker.getServer() == null) return;
		AttributeInstance armor = victim.getAttribute(Attributes.ARMOR);
		if (armor == null) return;
		boolean fresh = armor.getModifier(ENTROPY) == null;
		armor.addOrUpdateTransientModifier(new AttributeModifier(ENTROPY, -Math.max(0, AuraConfig.VOID_ENTROPY_ARMOR), AttributeModifier.Operation.ADD_VALUE));
		DECAYING.put(victim, (long) attacker.getServer().getTickCount() + Math.max(10, AuraConfig.VOID_ENTROPY_TICKS));
		if (fresh) {
			Vec3 c = AuraFx.chest(victim);
			AuraFx.particles(attacker.serverLevel(), AuraFx.dust(VIOLET, 0.9f), c.x, c.y, c.z, 8, 0.3, 0.4, 0.3, 0);
			sound(attacker.serverLevel(), c, SoundEvents.ARMOR_EQUIP_CHAIN.value(), 0.6f, 0.5f);
		}
	}

	// ---------------------------------------------------------------
	// Lifecycle
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		if (!ORBS.isEmpty()) {
			for (Orb o : new ArrayList<>(ORBS)) {
				boolean alive;
				try {
					alive = tickOrb(server, o);
				} catch (Exception ex) {
					com.toast.aurapoints.AuraPoints.LOGGER.error("Null Orb failed", ex);
					Props.remove(o.core);
					Props.remove(o.shell);
					alive = false;
				}
				if (!alive) ORBS.remove(o);
			}
		}
		if (!RIFTS.isEmpty()) RIFTS.removeIf(r -> !tickRift(server, r));
		if (!DECAYING.isEmpty()) {
			long now = server.getTickCount();
			for (Iterator<Map.Entry<LivingEntity, Long>> it = DECAYING.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				if (e.getValue() > now && e.getKey().isAlive()) continue;
				AttributeInstance armor = e.getKey().getAttribute(Attributes.ARMOR);
				if (armor != null) armor.removeModifier(ENTROPY);
				it.remove();
			}
		}
		if (!NULLED.isEmpty()) NULLED.values().removeIf(until -> until <= server.getTickCount());
	}

	public static void onLogout(ServerPlayer p) {
		NULLED.remove(p.getUUID());
		AttributeInstance armor = p.getAttribute(Attributes.ARMOR);
		if (armor != null) armor.removeModifier(ENTROPY);
		DECAYING.remove(p);
	}

	public static void clearAll() {
		ORBS.clear();
		RIFTS.clear();
		NULLED.clear();
		for (LivingEntity e : DECAYING.keySet()) {
			AttributeInstance armor = e.getAttribute(Attributes.ARMOR);
			if (armor != null) armor.removeModifier(ENTROPY);
		}
		DECAYING.clear();
	}

	/** Players inside the Singularity's reach (Ultimates). */
	static boolean pullable(ServerPlayer owner, LivingEntity e) {
		return Handhelds.enemy(owner, e) && !(e instanceof Player p && p.getAbilities().flying);
	}
}
