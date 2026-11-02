package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Sunfall (Fire, Phoenix Fangs). Point at a spot: a red glow spreads in the sky above it and embers rain
 * down as a warning, SUNFALL_DELAY_TICKS later a meteor (a tumbling magma block display with a blazing
 * trail) crashes in at an angle - damage falling off toward SUNFALL_RADIUS, knockback, burning - and
 * leaves a glowing crater of magma that keeps burning whoever stands in it, with real fires around it.
 */
final class Sunfall {

	private Sunfall() {
	}

	private static final int FALL_TICKS = 12;
	private static final int RED = 0xC8140A, EMBER = 0xFF7A1A, SKY = 0x8A0A0A;

	private static final class Strike {
		final UUID owner;
		final ServerLevel level;
		final Vec3 target;
		final Vec3 from;
		final RootCanvas canvas;
		Display.BlockDisplay meteor, core;
		int age;
		boolean landed;

		Strike(UUID owner, ServerLevel level, Vec3 target, Vec3 from) {
			this.owner = owner;
			this.level = level;
			this.target = target;
			this.from = from;
			this.canvas = new RootCanvas(level, BlockPos.containing(target).above());
			this.canvas.light = new Brightness(15, 15); // it glows
		}
	}

	private static final List<Strike> STRIKES = new ArrayList<>();

	static boolean start(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 aim = Handhelds.aim(player, AuraConfig.SUNFALL_RANGE);
		double y = RootCanvas.surfaceY(level, aim, 1, 24);
		Vec3 target = Double.isNaN(y) ? aim : new Vec3(aim.x, y, aim.z);
		// it comes in from behind the caster, over their shoulder
		Vec3 flat = new Vec3(player.getLookAngle().x, 0, player.getLookAngle().z);
		flat = flat.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : flat.normalize();
		Vec3 from = target.add(flat.scale(-12)).add(0, 32, 0);
		STRIKES.add(new Strike(player.getUUID(), level, target, from));

		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_THUNDERBOLT, 2);
		level.playSound(null, target.x, target.y, target.z, AuraSounds.BEACON_ACTIVATE.value(), SoundSource.PLAYERS, 2.0f, 0.5f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.BLAZE_SHOOT.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
		return true;
	}

	static void tickAll(MinecraftServer server) {
		if (STRIKES.isEmpty()) return;
		for (Iterator<Strike> it = STRIKES.iterator(); it.hasNext(); ) {
			Strike s = it.next();
			boolean done;
			try {
				done = tick(server, s);
			} catch (Exception e) {
				AuraPoints.LOGGER.error("Sunfall failed", e);
				done = true;
			}
			if (done) {
				s.canvas.discardAll();
				it.remove();
			}
		}
	}

	private static boolean tick(MinecraftServer server, Strike s) {
		ServerPlayer owner = Handhelds.player(server, s.owner);
		int age = s.age++;
		int delay = Math.max(FALL_TICKS, AuraConfig.SUNFALL_DELAY_TICKS);
		if (owner == null || age > delay + AuraConfig.SUNFALL_CRATER_TICKS) return true;
		ServerLevel level = s.level;
		Vec3 t = s.target;
		double r = AuraConfig.SUNFALL_RADIUS;

		if (!s.landed) {
			// the sky over it burns red, embers fall, the ground ring marks where it hits
			double spread = Math.min(1.0, age / 10.0) * r * 1.6;
			AuraFx.particles(level, AuraFx.dust(SKY, 3.5f), t.x, t.y + 22, t.z, 14, spread, 1.5, spread, 0);
			AuraFx.particles(level, AuraFx.dust(RED, 2.5f), t.x, t.y + 18, t.z, 6, spread * 0.7, 2.0, spread * 0.7, 0);
			AuraFx.particles(level, ParticleTypes.FALLING_LAVA, t.x, t.y + 16, t.z, 3, spread * 0.6, 1.0, spread * 0.6, 0);
			if (age % 2 == 0) AuraFx.ring(level, t.x, t.y + 0.15, t.z, r, AuraFx.dust(RED, 1.5f), 36);
			if (age % 4 == 0) AuraFx.ring(level, t.x, t.y + 0.15, t.z, r * (age % 16) / 16.0, AuraFx.dust(EMBER, 1.0f), 20);
			if (age % 10 == 0) level.playSound(null, t.x, t.y, t.z, AuraSounds.FIRE_AMBIENT.value(), SoundSource.PLAYERS, 2.0f, 0.5f);

			int fallStart = delay - FALL_TICKS;
			if (age == fallStart) {
				s.meteor = s.canvas.piece(Blocks.MAGMA_BLOCK.defaultBlockState(), s.from);
				s.core = s.canvas.piece(Blocks.SHROOMLIGHT.defaultBlockState(), s.from);
				level.playSound(null, t.x, t.y + 10, t.z, AuraSounds.GHAST_SHOOT.value(), SoundSource.PLAYERS, 3.0f, 0.4f);
			}
			if (age >= fallStart) {
				double f = Math.min(1.0, (age - fallStart + 1) / (double) FALL_TICKS);
				Vec3 at = s.from.add(t.subtract(s.from).scale(f * f));
				Quaternionf spin = new Quaternionf().rotationXYZ(age * 0.5f, age * 0.3f, age * 0.2f);
				RootCanvas.moveTo(s.meteor, at);
				RootCanvas.moveTo(s.core, at);
				RootCanvas.cube(s.meteor, at, spin, new Vector3f(2.4f), 1);
				RootCanvas.cube(s.core, at, new Quaternionf(spin).rotateY(0.8f), new Vector3f(1.6f), 1);
				// a trail of fire and smoke behind it
				Vec3 back = s.from.subtract(t).normalize();
				for (int i = 0; i < 4; i++) {
					Vec3 p = at.add(back.scale(i * 0.9));
					AuraFx.particles(level, ParticleTypes.FLAME, p.x, p.y, p.z, 6, 0.5, 0.5, 0.5, 0.05);
					AuraFx.particles(level, ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 3, 0.6, 0.6, 0.6, 0.02);
					AuraFx.particles(level, ParticleTypes.LAVA, p.x, p.y, p.z, 1, 0.4, 0.4, 0.4, 0);
				}
				if (f >= 1.0) land(owner, s);
			}
			return false;
		}

		// the crater: magma glows, lava bubbles, it burns whoever stands in it
		if (age % 3 == 0) {
			AuraFx.particles(level, ParticleTypes.LAVA, t.x, t.y + 0.3, t.z, 2, r * 0.35, 0.1, r * 0.35, 0);
			AuraFx.particles(level, ParticleTypes.FLAME, t.x, t.y + 0.3, t.z, 4, r * 0.4, 0.1, r * 0.4, 0.01);
			AuraFx.particles(level, ParticleTypes.SMOKE, t.x, t.y + 0.5, t.z, 3, r * 0.4, 0.2, r * 0.4, 0.02);
		}
		if (age % 20 == 0) {
			for (LivingEntity e : Handhelds.enemies(owner, level, t.add(0, 0.5, 0), r * 0.6)) {
				if (e.getY() > t.y + 1.5) continue;
				Handhelds.hurt(owner, e, AuraConfig.SUNFALL_CRATER_DAMAGE);
				e.igniteForSeconds(Math.max(1, AuraConfig.SUNFALL_BURN_SECONDS));
			}
			level.playSound(null, t.x, t.y, t.z, AuraSounds.LAVA_AMBIENT.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
		}
		int left = delay + AuraConfig.SUNFALL_CRATER_TICKS - age;
		if (left == 10) for (var d : s.canvas.pieces) if (d instanceof Display dd) RootCanvas.hide(dd, t.add(0, -0.6, 0), 10);
		return false;
	}

	private static void land(ServerPlayer owner, Strike s) {
		s.landed = true;
		ServerLevel level = s.level;
		Vec3 t = s.target;
		double r = AuraConfig.SUNFALL_RADIUS;
		s.canvas.remove(s.meteor);
		s.canvas.remove(s.core);

		for (LivingEntity e : Handhelds.enemies(owner, level, t.add(0, 0.8, 0), r + 0.5)) {
			double d = Math.min(1.0, AuraFx.chest(e).distanceTo(t) / r);
			float dmg = AuraConfig.SUNFALL_DAMAGE * (float) (1.0 - d * (1.0 - AuraConfig.SUNFALL_EDGE_DAMAGE_FRACTION));
			e.invulnerableTime = 0;
			Handhelds.hurt(owner, e, dmg);
			e.igniteForSeconds(Math.max(1, AuraConfig.SUNFALL_BURN_SECONDS));
			Vec3 away = e.position().subtract(t).multiply(1, 0, 1);
			away = away.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 0) : away.normalize();
			double k = AuraConfig.SUNFALL_KNOCKBACK * (1.0 - d * 0.5);
			e.setDeltaMovement(e.getDeltaMovement().add(away.scale(k)).add(0, 0.35 + 0.2 * k, 0));
			e.hurtMarked = true;
		}

		AuraFx.particles(level, ParticleTypes.EXPLOSION_EMITTER, t.x, t.y + 0.5, t.z, 1, 0, 0, 0, 0);
		AuraFx.particles(level, ParticleTypes.FLAME, t.x, t.y + 0.5, t.z, 150, r * 0.4, 0.5, r * 0.4, 0.25);
		AuraFx.particles(level, ParticleTypes.LAVA, t.x, t.y + 0.5, t.z, 40, r * 0.3, 0.3, r * 0.3, 0);
		AuraFx.particles(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, t.x, t.y + 0.5, t.z, 30, r * 0.3, 0.3, r * 0.3, 0.05);
		var dirt = new BlockParticleOption(ParticleTypes.BLOCK, RootCanvas.groundBlock(level, t));
		AuraFx.particles(level, dirt, t.x, t.y + 0.4, t.z, 120, r * 0.4, 0.3, r * 0.4, 0.3);
		AuraFx.ring(level, t.x, t.y + 0.2, t.z, r, ParticleTypes.FLAME, 60);
		level.playSound(null, t.x, t.y, t.z, AuraSounds.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 3.0f, 0.6f);
		level.playSound(null, t.x, t.y, t.z, AuraSounds.DRAGON_FIREBALL_EXPLODE.value(), SoundSource.PLAYERS, 2.0f, 0.7f);

		// the crater: glowing magma plates sunk into the ground, a molten middle
		var rnd = level.random;
		Vec3 mid = t.add(0, -0.3, 0);
		var m = s.canvas.piece(Blocks.MAGMA_BLOCK.defaultBlockState(), mid);
		RootCanvas.cube(m, mid, new Quaternionf().rotateY(rnd.nextFloat() * 3f), new Vector3f(2.2f, 0.7f, 2.2f), 4);
		int plates = 10;
		for (int i = 0; i < plates; i++) {
			double a = Math.PI * 2 * i / plates + rnd.nextDouble() * 0.4;
			double d = r * (0.35 + rnd.nextDouble() * 0.3);
			Vec3 p = t.add(Math.cos(a) * d, 0, Math.sin(a) * d);
			double gy = RootCanvas.surfaceY(level, p, 2, 3);
			if (Double.isNaN(gy)) continue;
			p = new Vec3(p.x, gy - 0.25, p.z);
			var plate = s.canvas.piece((i % 3 == 0 ? Blocks.SHROOMLIGHT : Blocks.MAGMA_BLOCK).defaultBlockState(), p);
			// tilted up toward the outside, like a torn rim
			Quaternionf tilt = new Quaternionf().rotateY((float) -a).rotateZ(0.35f + rnd.nextFloat() * 0.3f);
			float size = 0.8f + rnd.nextFloat() * 0.6f;
			RootCanvas.cube(plate, p, tilt, new Vector3f(size, 0.35f, size), 4);
		}
		AuraFire.scatter(level, owner, t, r * 0.9, AuraConfig.SUNFALL_FIRES);
	}

	static void clearAll() {
		for (Strike s : STRIKES) s.canvas.discardAll();
		STRIKES.clear();
	}
}
