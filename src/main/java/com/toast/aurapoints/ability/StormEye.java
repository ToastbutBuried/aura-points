package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Eye of the Storm (Wind, Stormcaller Glaive). The glaive spins out of the hand (an item display of the
 * held weapon) and becomes the eye of a tornado - a spiral funnel of cloud and wind - that sweeps along the
 * ground where you looked, sucking in everything around it. At the end (or at a wall too high to climb) it
 * bursts and hurls everyone away, then the glaive flies back into your hand.
 */
final class StormEye {

	private StormEye() {
	}

	private static final int WHITE = 0xEEF9FF, SKY = 0x9FD8F5;
	private static final double FUNNEL_HEIGHT = 6.0;

	private static final class Storm {
		final UUID owner;
		final ServerLevel level;
		final Vec3 dir;
		final RootCanvas canvas;
		final Display.ItemDisplay glaive;
		Vec3 pos;
		int age;
		boolean returning;

		Storm(UUID owner, ServerLevel level, Vec3 pos, Vec3 dir, RootCanvas canvas, Display.ItemDisplay glaive) {
			this.owner = owner;
			this.level = level;
			this.pos = pos;
			this.dir = dir;
			this.canvas = canvas;
			this.glaive = glaive;
		}
	}

	private static final List<Storm> STORMS = new ArrayList<>();

	static boolean start(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 look = player.getLookAngle();
		Vec3 dir = new Vec3(look.x, 0, look.z);
		dir = dir.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : dir.normalize();
		Vec3 start = player.position().add(dir.scale(1.5));
		double y = RootCanvas.surfaceY(level, start, 2, 4);
		if (!Double.isNaN(y)) start = new Vec3(start.x, y, start.z);

		RootCanvas canvas = new RootCanvas(level, BlockPos.containing(player.getEyePosition()));
		Display.ItemDisplay glaive = canvas.item(player.getMainHandItem(), start.add(0, 1.5, 0));
		STORMS.add(new Storm(player.getUUID(), level, start, dir, canvas, glaive));

		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_STORM_EYE, 0);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.2f, 1.2f);
		level.playSound(null, start.x, start.y, start.z, AuraSounds.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.5f, 0.6f);
		return true;
	}

	static void tickAll(MinecraftServer server) {
		if (STORMS.isEmpty()) return;
		for (Iterator<Storm> it = STORMS.iterator(); it.hasNext(); ) {
			Storm s = it.next();
			boolean done;
			try {
				done = tick(server, s);
			} catch (Exception e) {
				AuraPoints.LOGGER.error("Eye of the Storm failed", e);
				done = true;
			}
			if (done) {
				s.canvas.discardAll();
				it.remove();
			}
		}
	}

	private static boolean tick(MinecraftServer server, Storm s) {
		ServerPlayer owner = Handhelds.player(server, s.owner);
		int age = s.age++;
		if (owner == null || owner.level() != s.level || age > AuraConfig.STORM_EYE_TICKS + 200) return true;
		ServerLevel level = s.level;

		if (s.returning) {
			// back into the hand, spinning
			Vec3 hand = AuraFx.chest(owner);
			Vec3 at = s.glaive.position();
			Vec3 to = hand.subtract(at);
			double d = to.length();
			if (d < 1.2) {
				level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), AuraSounds.TRIDENT_RETURN.value(), SoundSource.PLAYERS, 1.0f, 1.3f);
				return true;
			}
			Vec3 next = at.add(to.scale(Math.min(1.0, 1.4 / d)));
			RootCanvas.moveTo(s.glaive, next);
			RootCanvas.place(s.glaive, next, new Quaternionf().rotateY(age * 1.1f).rotateX((float) Math.PI / 2), 1.2f, 1);
			AuraFx.particles(level, AuraFx.dust(WHITE, 0.9f), next.x, next.y, next.z, 2, 0.1, 0.1, 0.1, 0);
			return false;
		}

		// sweep along the ground; climbs up to two blocks, a higher wall ends it there
		Vec3 ahead = s.pos.add(s.dir.scale(AuraConfig.STORM_EYE_SPEED));
		double y = RootCanvas.surfaceY(level, ahead, 2, 6);
		boolean blocked = Double.isNaN(y) || y > s.pos.y + 2.01;
		if (!blocked) s.pos = new Vec3(ahead.x, y, ahead.z);
		Vec3 p = s.pos;

		// the funnel: a spiral widening upward, plus the glaive spinning flat in its eye
		double spin = age * 0.55;
		for (int k = 0; k <= 10; k++) {
			double h = FUNNEL_HEIGHT * k / 10.0;
			double r = 0.35 + 0.28 * k;
			for (int i = 0; i < 3; i++) {
				double a = spin + k * 0.45 + Math.PI * 2 * i / 3;
				double x = p.x + Math.cos(a) * r, z = p.z + Math.sin(a) * r;
				AuraFx.particles(level, k % 3 == 0 ? ParticleTypes.CLOUD : AuraFx.dust(k % 2 == 0 ? WHITE : SKY, 1.6f), x, p.y + h, z, 1, 0, 0, 0, 0);
			}
		}
		if (age % 4 == 0) AuraFx.particles(level, ParticleTypes.GUST, p.x, p.y + 1.0, p.z, 1, 0.6, 0.6, 0.6, 0);
		if (age % 15 == 0) level.playSound(null, p.x, p.y, p.z, AuraSounds.BREEZE_IDLE_AIR.value(), SoundSource.PLAYERS, 1.6f, 0.6f);
		Vec3 eye = p.add(0, 1.6, 0);
		RootCanvas.moveTo(s.glaive, eye);
		RootCanvas.place(s.glaive, eye, new Quaternionf().rotateY(age * 1.3f).rotateX((float) Math.PI / 2), 1.4f, 1);

		// the pull: inward, round and up; in the eye, lifted and battered
		double pr = AuraConfig.STORM_EYE_PULL_RADIUS;
		for (LivingEntity e : Handhelds.enemies(owner, level, p.add(0, 1.5, 0), pr + 1.5)) {
			Vec3 to = p.subtract(e.position()).multiply(1, 0, 1);
			double d = to.length();
			Vec3 in = d < 1.0e-3 ? Vec3.ZERO : to.scale(1 / d);
			Vec3 round = new Vec3(-in.z, 0, in.x);
			Vec3 v = e.getDeltaMovement().multiply(0.7, 1, 0.7)
					.add(in.scale(AuraConfig.STORM_EYE_PULL_STRENGTH * Math.min(1.0, d / 1.2)))
					.add(round.scale(0.12))
					.add(s.dir.scale(AuraConfig.STORM_EYE_SPEED * 0.5));
			double lift = d < 1.6 ? (e.getY() < p.y + 2.5 ? 0.12 : 0.02) : 0.0;
			e.setDeltaMovement(v.x, Math.max(v.y, lift), v.z);
			e.fallDistance = 0;
			e.hurtMarked = true;
			if (d < 1.6 && age % 10 == 0) {
				e.invulnerableTime = 0;
				Handhelds.hurt(owner, e, AuraConfig.STORM_EYE_CORE_DAMAGE);
			}
		}

		if (blocked || age >= AuraConfig.STORM_EYE_TICKS) burst(owner, s);
		return false;
	}

	private static void burst(ServerPlayer owner, Storm s) {
		s.returning = true;
		ServerLevel level = s.level;
		Vec3 p = s.pos.add(0, 1.0, 0);
		double r = AuraConfig.STORM_EYE_BLAST_RADIUS;
		for (LivingEntity e : Handhelds.enemies(owner, level, p, r + 1)) {
			Vec3 away = e.position().subtract(s.pos).multiply(1, 0, 1);
			away = away.lengthSqr() < 1.0e-4 ? s.dir : away.normalize();
			e.invulnerableTime = 0;
			Handhelds.hurt(owner, e, AuraConfig.STORM_EYE_BLAST_DAMAGE);
			e.setDeltaMovement(away.scale(AuraConfig.STORM_EYE_BLAST_STRENGTH).add(0, AuraConfig.STORM_EYE_BLAST_LIFT, 0));
			e.hurtMarked = true;
		}
		AuraFx.particles(level, ParticleTypes.GUST_EMITTER_LARGE, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		AuraFx.particles(level, ParticleTypes.EXPLOSION, p.x, p.y + 1, p.z, 4, 1.5, 1.5, 1.5, 0);
		AuraFx.particles(level, ParticleTypes.CLOUD, p.x, p.y + 1, p.z, 80, 2.0, 2.0, 2.0, 0.3);
		AuraFx.ring(level, p.x, s.pos.y + 0.2, p.z, r, AuraFx.dust(WHITE, 2.0f), 48);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 2.5f, 0.5f);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 2.0f, 0.7f);
	}

	static void clearAll() {
		for (Storm s : STORMS) s.canvas.discardAll();
		STORMS.clear();
	}
}
