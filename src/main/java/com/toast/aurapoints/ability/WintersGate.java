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
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Winter's Gate (Frost, Absolute Zero). A wall of ice blocks (block displays, a jagged top, sharp spikes
 * on its far side) rises from the ground a few blocks ahead, across your view. While it stands it
 * swallows every projectile that flies into it and holds back whoever walks into it - they freeze solid
 * (Ice Spike's freeze) and are put back on their own side. When its time is up it shatters: the blocks
 * fly apart as shards and hurt everyone near it.
 */
final class WintersGate {

	private WintersGate() {
	}

	private static final int RISE_TICKS = 6, SHATTER_TICKS = 12;
	private static final double THICK = 0.7;
	private static final int ICE_BLUE = 0x9FE3FF;

	private static final class Piece {
		final Display.BlockDisplay d;
		final Vec3 c;
		final Vector3f size;
		final Quaternionf rot;

		Piece(Display.BlockDisplay d, Vec3 c, Vector3f size, Quaternionf rot) {
			this.d = d;
			this.c = c;
			this.size = size;
			this.rot = rot;
		}
	}

	private static final class Gate {
		final UUID owner;
		final ServerLevel level;
		final Vec3 base, fwd, right;
		final float yaw;
		final RootCanvas canvas;
		final List<Piece> pieces = new ArrayList<>();
		final Set<Entity> frozen = new HashSet<>();
		double top;
		int age;

		Gate(UUID owner, ServerLevel level, Vec3 base, Vec3 fwd, float yaw) {
			this.owner = owner;
			this.level = level;
			this.base = base;
			this.fwd = fwd;
			this.right = new Vec3(-fwd.z, 0, fwd.x);
			this.yaw = yaw;
			this.canvas = new RootCanvas(level, BlockPos.containing(base).above(2));
		}

		/** {@code p} in the wall's own frame: x along it, y up from its foot, z through it (toward the far side). */
		Vec3 local(Vec3 p) {
			Vec3 d = p.subtract(base);
			return new Vec3(d.dot(right), d.y, d.dot(fwd));
		}

		boolean inside(AABB box, double margin) {
			// the box's corners spread along the wall's axes
			Vec3 c = local(box.getCenter());
			double hx = (Math.abs(right.x) * box.getXsize() + Math.abs(right.z) * box.getZsize()) / 2;
			double hz = (Math.abs(fwd.x) * box.getXsize() + Math.abs(fwd.z) * box.getZsize()) / 2;
			double half = AuraConfig.WINTERS_GATE_WIDTH / 2.0;
			return Math.abs(c.x) < half + hx + margin && Math.abs(c.z) < THICK / 2 + hz + margin
					&& box.maxY > base.y - 0.5 && box.minY < top + margin;
		}
	}

	private static final List<Gate> GATES = new ArrayList<>();

	static boolean start(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 look = player.getLookAngle();
		Vec3 fwd = new Vec3(look.x, 0, look.z);
		fwd = fwd.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : fwd.normalize();
		Vec3 at = player.position().add(fwd.scale(AuraConfig.WINTERS_GATE_DISTANCE));
		double y = RootCanvas.surfaceY(level, at, 2, 6);
		Vec3 base = Double.isNaN(y) ? at : new Vec3(at.x, y, at.z);
		float yaw = (float) Math.toDegrees(Math.atan2(-fwd.x, fwd.z));
		Gate g = new Gate(player.getUUID(), level, base, fwd, yaw);
		build(g);
		GATES.add(g);

		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_WINTERS_GATE, 0);
		level.playSound(null, base.x, base.y, base.z, AuraSounds.GLASS_PLACE.value(), SoundSource.PLAYERS, 2.0f, 0.5f);
		level.playSound(null, base.x, base.y, base.z, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), SoundSource.PLAYERS, 2.0f, 0.6f);
		level.playSound(null, base.x, base.y, base.z, AuraSounds.POWDER_SNOW_PLACE.value(), SoundSource.PLAYERS, 2.0f, 0.5f);
		var ice = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState());
		AuraFx.particles(level, ice, base.x, base.y + 0.3, base.z, 60, AuraConfig.WINTERS_GATE_WIDTH / 3.0, 0.2, 0.5, 0.2);
		return true;
	}

	/** The blocks: a grid along the wall with a jagged top, then spikes on the far face and along the top. */
	private static void build(Gate g) {
		var rnd = g.level.random;
		int w = Math.max(1, AuraConfig.WINTERS_GATE_WIDTH), h = Math.max(1, AuraConfig.WINTERS_GATE_HEIGHT);
		Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-g.yaw));
		g.top = g.base.y;
		for (int col = 0; col < w; col++) {
			double x = col - (w - 1) / 2.0;
			int rows = h + (col == 0 || col == w - 1 ? -1 : rnd.nextInt(3) - 1);
			// the wall dips into the ground where the ground does
			Vec3 foot = g.base.add(g.right.scale(x));
			double gy = RootCanvas.surfaceY(g.level, foot, 2, 4);
			double y0 = Double.isNaN(gy) ? g.base.y : gy;
			for (int row = 0; row < Math.max(1, rows); row++) {
				BlockState block = row == 0 ? Blocks.PACKED_ICE.defaultBlockState()
						: rnd.nextInt(5) == 0 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.ICE.defaultBlockState();
				Vec3 c = new Vec3(foot.x, y0 + row + 0.5, foot.z).add(g.fwd.scale((rnd.nextDouble() - 0.5) * 0.15));
				Quaternionf rot = new Quaternionf(turn).rotateZ((rnd.nextFloat() - 0.5f) * 0.12f);
				float s = 1.02f + rnd.nextFloat() * 0.08f;
				piece(g, block, c, new Vector3f(s, s, (float) THICK), rot, col, row);
				g.top = Math.max(g.top, y0 + row + 1);
				// spikes on the far side, angled out toward the enemy
				if (rnd.nextInt(3) == 0) spike(g, c.add(g.fwd.scale(THICK / 2)), g.fwd.add(g.right.scale(rnd.nextGaussian() * 0.3)).add(0, rnd.nextGaussian() * 0.3, 0), 0.9f + rnd.nextFloat() * 0.6f, col, row);
			}
			// and a spike or two growing out of the top
			Vec3 topC = new Vec3(foot.x, y0 + Math.max(1, rows), foot.z);
			spike(g, topC, new Vec3(rnd.nextGaussian() * 0.25, 1, rnd.nextGaussian() * 0.25).add(g.fwd.scale(0.3)), 0.8f + rnd.nextFloat() * 0.8f, col, rows);
		}
	}

	private static void piece(Gate g, BlockState block, Vec3 c, Vector3f size, Quaternionf rot, int col, int row) {
		var d = g.canvas.piece(block, new Vec3(c.x, g.base.y - 1, c.z));
		g.pieces.add(new Piece(d, c, size, rot));
		// rises out of the ground column by column from the middle out
		int delay = 1 + (int) Math.abs(col - (AuraConfig.WINTERS_GATE_WIDTH - 1) / 2.0) + row / 2;
		RootCanvas.cube(d, new Vec3(c.x, g.base.y - 0.6, c.z), rot, new Vector3f(size.x, 0.05f, size.z), 0);
		AuraFx.later(g.level.getServer(), delay, () -> {
			if (!d.isRemoved()) RootCanvas.cube(d, c, rot, size, RISE_TICKS);
		});
	}

	private static void spike(Gate g, Vec3 from, Vec3 dir, float len, int col, int row) {
		var d = g.canvas.piece(Blocks.PACKED_ICE.defaultBlockState(), from);
		Vec3 to = from.add(dir.normalize().scale(len));
		Vec3 mid = from.add(to).scale(0.5);
		g.pieces.add(new Piece(d, mid, new Vector3f(0.16f, len, 0.16f), new Quaternionf()));
		int delay = 2 + RISE_TICKS + (int) Math.abs(col - (AuraConfig.WINTERS_GATE_WIDTH - 1) / 2.0) + row / 2;
		AuraFx.later(g.level.getServer(), delay, () -> {
			if (!d.isRemoved()) RootCanvas.stretch(d, from, to, 0.16f, 1.0f, 4);
		});
	}

	static void tickAll(MinecraftServer server) {
		if (GATES.isEmpty()) return;
		for (Iterator<Gate> it = GATES.iterator(); it.hasNext(); ) {
			Gate g = it.next();
			boolean done;
			try {
				done = tick(server, g);
			} catch (Exception e) {
				AuraPoints.LOGGER.error("Winter's Gate failed", e);
				done = true;
			}
			if (done) {
				g.canvas.discardAll();
				it.remove();
			}
		}
	}

	private static boolean tick(MinecraftServer server, Gate g) {
		ServerPlayer owner = Handhelds.player(server, g.owner);
		int age = g.age++;
		int life = Math.max(RISE_TICKS, AuraConfig.WINTERS_GATE_TICKS);
		if (age > life + SHATTER_TICKS) return true;
		if (owner == null) return true;
		ServerLevel level = g.level;
		long now = server.getTickCount();

		if (age < life) {
			double half = AuraConfig.WINTERS_GATE_WIDTH / 2.0 + 2;
			Vec3 mid = g.base.add(0, (g.top - g.base.y) / 2, 0);
			AABB area = new AABB(mid, mid).inflate(half, (g.top - g.base.y) / 2 + 2, half);
			// projectiles die in the ice
			for (Projectile p : level.getEntitiesOfClass(Projectile.class, area, p -> g.inside(p.getBoundingBox().expandTowards(p.getDeltaMovement()), 0.1))) {
				var ice = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
				AuraFx.particles(level, ice, p.getX(), p.getY(), p.getZ(), 12, 0.15, 0.15, 0.15, 0.1);
				level.playSound(null, p.getX(), p.getY(), p.getZ(), AuraSounds.GLASS_HIT.value(), SoundSource.PLAYERS, 1.0f, 1.4f);
				p.discard();
			}
			// whoever touches it freezes, and is put back on their own side
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, e -> Handhelds.enemy(owner, e) && g.inside(e.getBoundingBox(), 0.15))) {
				if (g.frozen.add(e)) {
					IceSpike.freeze(e, now, AuraConfig.WINTERS_GATE_FREEZE_TICKS);
					Vec3 c = AuraFx.chest(e);
					AuraFx.particles(level, ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 20, 0.3, 0.5, 0.3, 0.05);
				}
				Vec3 l = g.local(e.position());
				double side = l.z >= 0 ? 1 : -1;
				double out = side * (THICK / 2 + e.getBbWidth() / 2 + 0.2) - l.z;
				Vec3 to = e.position().add(g.fwd.scale(out));
				if (e instanceof ServerPlayer p) {
					p.connection.teleport(to.x, to.y, to.z, p.getYRot(), p.getXRot());
				} else {
					e.teleportTo(to.x, to.y, to.z);
				}
				e.setDeltaMovement(g.fwd.scale(side * 0.2));
				e.hurtMarked = true;
			}
			if (age % 6 == 0) {
				AuraFx.particles(level, ParticleTypes.SNOWFLAKE, g.base.x, (g.base.y + g.top) / 2, g.base.z, 6,
						Math.abs(g.right.x) * AuraConfig.WINTERS_GATE_WIDTH / 2.5 + 0.2, (g.top - g.base.y) / 3, Math.abs(g.right.z) * AuraConfig.WINTERS_GATE_WIDTH / 2.5 + 0.2, 0.01);
			}
		}

		if (age == life) shatter(owner, g);
		return false;
	}

	/** Every block bursts outward as a spinning shard and sinks away; everyone near takes the shards. */
	private static void shatter(ServerPlayer owner, Gate g) {
		ServerLevel level = g.level;
		var rnd = level.random;
		Vec3 mid = g.base.add(0, (g.top - g.base.y) / 2, 0);
		for (Piece p : g.pieces) {
			Vec3 out = p.c.subtract(mid).add(g.fwd.scale(rnd.nextBoolean() ? 1.5 : -1.5)).normalize();
			Vec3 to = p.c.add(out.scale(3 + rnd.nextDouble() * 3)).add(0, -1.5 - rnd.nextDouble(), 0);
			Quaternionf tumble = new Quaternionf(p.rot).rotateXYZ(rnd.nextFloat() * 4, rnd.nextFloat() * 4, rnd.nextFloat() * 4);
			RootCanvas.cube(p.d, to, tumble, new Vector3f(0.25f + rnd.nextFloat() * 0.2f), SHATTER_TICKS);
		}
		double r = AuraConfig.WINTERS_GATE_SHARD_RADIUS;
		double half = AuraConfig.WINTERS_GATE_WIDTH / 2.0;
		for (LivingEntity e : Handhelds.enemies(owner, level, mid, r + half)) {
			// distance to the wall itself, not its middle
			Vec3 l = g.local(AuraFx.chest(e));
			double dx = Math.max(0, Math.abs(l.x) - half);
			if (Math.sqrt(dx * dx + l.z * l.z) > r) continue;
			e.invulnerableTime = 0;
			Handhelds.hurt(owner, e, AuraConfig.WINTERS_GATE_SHARD_DAMAGE);
			Vec3 c = AuraFx.chest(e);
			AuraFx.particles(level, ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 10, 0.3, 0.4, 0.3, 0.05);
		}
		var ice = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
		AuraFx.particles(level, ice, mid.x, mid.y, mid.z, 200, half / 1.5, (g.top - g.base.y) / 2, half / 1.5, 0.4);
		AuraFx.particles(level, ParticleTypes.SNOWFLAKE, mid.x, mid.y, mid.z, 80, half, 1.5, half, 0.2);
		AuraFx.particles(level, AuraFx.dust(ICE_BLUE, 1.6f), mid.x, mid.y, mid.z, 40, half, 1.2, half, 0);
		level.playSound(null, mid.x, mid.y, mid.z, AuraSounds.GLASS_BREAK.value(), SoundSource.PLAYERS, 2.5f, 0.6f);
		level.playSound(null, mid.x, mid.y, mid.z, AuraSounds.AMETHYST_CLUSTER_BREAK.value(), SoundSource.PLAYERS, 2.0f, 0.7f);
		level.playSound(null, mid.x, mid.y, mid.z, AuraSounds.PLAYER_HURT_FREEZE.value(), SoundSource.PLAYERS, 1.5f, 0.6f);
	}

	static void clearAll() {
		for (Gate g : GATES) g.canvas.discardAll();
		GATES.clear();
	}
}
