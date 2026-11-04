package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.geo.ShadowClone;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The Shadow aura's Phantom branch: a Dash leaves a shadow clone (the Umbral Ambush figure, no strike) where the dash
 * began. For PHANTOM_DECOY_TICKS mobs that were hunting the dasher near it lose them. Players can still see through it.
 */
public final class PhantomDecoy {

	private PhantomDecoy() {
	}

	private static final class Decoy {
		final ServerPlayer owner;
		final ServerLevel level;
		final Vec3 pos;
		final ShadowClone model;
		int ticksLeft;

		Decoy(ServerPlayer owner, ServerLevel level, Vec3 pos, ShadowClone model, int ticks) {
			this.owner = owner;
			this.level = level;
			this.pos = pos;
			this.model = model;
			this.ticksLeft = ticks;
		}
	}

	private static final List<Decoy> DECOYS = new ArrayList<>();
	private static final double RADIUS = 16.0;

	/** Phantom Dash: a clone where {@code from} is, facing the way the dasher faces. */
	public static void spawn(ServerPlayer owner, Vec3 from) {
		if (!Branch.is(owner, Branch.PHANTOM) || AuraConfig.PHANTOM_DECOY_TICKS <= 0) return;
		for (Iterator<Decoy> it = DECOYS.iterator(); it.hasNext(); ) {
			Decoy d = it.next();
			if (d.owner == owner) {
				end(d);
				it.remove();
			}
		}
		ServerLevel level = owner.serverLevel();
		ShadowClone model = AuraPoints.SHADOW_CLONE.get().create(level);
		if (model != null) {
			model.moveTo(from.x, from.y, from.z, owner.getYRot(), 0);
			model.setYHeadRot(owner.getYRot());
			level.addFreshEntity(model);
		}
		DECOYS.add(new Decoy(owner, level, from, model, AuraConfig.PHANTOM_DECOY_TICKS));
		AuraFx.particles(level, ParticleTypes.SMOKE, from.x, from.y + 1.0, from.z, 14, 0.3, 0.5, 0.3, 0.02);
		loseTrack(owner, from);
	}

	public static int count() {
		return DECOYS.size();
	}

	public static void clear() {
		for (Decoy d : DECOYS) end(d);
		DECOYS.clear();
	}

	private static void end(Decoy d) {
		if (d.model != null) {
			d.model.setState(ShadowClone.GONE);
			d.model.discard();
		}
	}

	/** Mobs near the clone that were hunting the owner lose them. */
	private static void loseTrack(ServerPlayer owner, Vec3 at) {
		ServerLevel level = owner.serverLevel();
		AABB box = new AABB(at.x - RADIUS, at.y - 6, at.z - RADIUS, at.x + RADIUS, at.y + 6, at.z + RADIUS);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
			if (mob.getTarget() == owner) {
				mob.setTarget(null);
				mob.setLastHurtByMob(null);
			}
		}
	}

	/** Every server tick: counts the clones down, and every 5 ticks makes hunters lose the owner again. */
	public static void tickAll(MinecraftServer server) {
		if (DECOYS.isEmpty()) return;
		boolean pulse = server.getTickCount() % 5 == 0;
		for (Iterator<Decoy> it = DECOYS.iterator(); it.hasNext(); ) {
			Decoy d = it.next();
			if (d.owner.isRemoved() || --d.ticksLeft <= 0) {
				end(d);
				it.remove();
				continue;
			}
			if (pulse) loseTrack(d.owner, d.pos);
		}
	}
}
