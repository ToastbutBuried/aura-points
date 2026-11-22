package com.toast.aurapoints.rift;

import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.Props;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A trial portal (5.49.0): a stone arch with a tinted shimmering curtain. Two of them link a wing's antechamber to its
 * trial arena beyond the monument. Walking into the curtain moves you to the other side, facing away from it.
 * Built from blocks (the arch) and block displays (the curtain), so it never acts like a vanilla portal.
 */
final class TrialPortal {

	/** Inner opening: 3 wide, 4 tall. */
	static final int W = 1, H = 4;

	/** The floor block under the opening's middle; the curtain faces (nx, nz) = the way you walk out of it. */
	final BlockPos base;
	final int nx, nz;
	final int rgb;
	TrialPortal to;
	private final List<Display.BlockDisplay> curtain = new ArrayList<>();
	private final Map<UUID, Long> cooldown = new HashMap<>();
	/** Closed portals show no curtain and don't move anyone (the arena is still being built). */
	boolean open;

	TrialPortal(BlockPos base, int nx, int nz, int rgb) {
		this.base = base;
		this.nx = nx;
		this.nz = nz;
		this.rgb = rgb;
	}

	/** Side axis (along the opening). */
	private int sx() {
		return -nz;
	}

	private int sz() {
		return nx;
	}

	/** The arch: palette edge stone, accent keystone, light blocks in the feet; the opening is cleared. */
	void build(ServerLevel level, RealmBuilder.Palette pal) {
		for (int s = -W - 1; s <= W + 1; s++) {
			for (int y = 1; y <= H + 1; y++) {
				int x = base.getX() + sx() * s, z = base.getZ() + sz() * s;
				boolean frame = Math.abs(s) == W + 1 || y == H + 1;
				RealmBuilder.set(level, x, base.getY() + y, z, frame ? pal.edge() : Blocks.AIR);
			}
			RealmBuilder.set(level, base.getX() + sx() * s, base.getY(), base.getZ() + sz() * s, pal.accent());
		}
		// a stepped crown and two lit feet
		for (int s = -W; s <= W; s++) RealmBuilder.set(level, base.getX() + sx() * s, base.getY() + H + 2, base.getZ() + sz() * s, s == 0 ? pal.accent() : pal.edge());
		RealmBuilder.set(level, base.getX(), base.getY() + H + 3, base.getZ(), pal.light());
		for (int s : new int[]{-W - 2, W + 2}) {
			int x = base.getX() + sx() * s, z = base.getZ() + sz() * s;
			RealmBuilder.set(level, x, base.getY() + 1, z, pal.edge());
			RealmBuilder.set(level, x, base.getY() + 2, z, pal.light());
		}
	}

	void setOpen(ServerLevel level, boolean on, Block glass) {
		if (on == open) return;
		open = on;
		for (Display.BlockDisplay d : curtain) Props.remove(d);
		curtain.clear();
		if (!on) return;
		Vec3 c = Vec3.atBottomCenterOf(base.above());
		float w = 2 * W + 1, depth = 0.12f;
		boolean alongX = sx() != 0;
		Vector3f scale = alongX ? new Vector3f(w, H, depth) : new Vector3f(depth, H, w);
		Vector3f t = new Vector3f(-scale.x / 2f, 0f, -scale.z / 2f);
		Display.BlockDisplay d = Props.spawn(level, c, glass.defaultBlockState(), new Transformation(t, new Quaternionf(), scale, null), -1, 3);
		Props.glow(d);
		d.addTag(Trials.DISPLAY_TAG);
		curtain.add(d);
		// a second, thinner sheet of the aura's light for the shimmer
		Vector3f scale2 = alongX ? new Vector3f(w - 0.3f, H - 0.3f, 0.04f) : new Vector3f(0.04f, H - 0.3f, w - 0.3f);
		Vector3f t2 = new Vector3f(-scale2.x / 2f, 0.15f, -scale2.z / 2f);
		Display.BlockDisplay d2 = Props.spawn(level, c, Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), new Transformation(t2, new Quaternionf(), scale2, null), -1, 3);
		Props.glow(d2);
		d2.addTag(Trials.DISPLAY_TAG);
		curtain.add(d2);
		level.playSound(null, c.x, c.y + 2, c.z, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.2f, 1.4f);
	}

	/** Where someone coming out of this portal stands. */
	Vec3 exit() {
		return Vec3.atBottomCenterOf(base.above()).add(nx * 2.5, 0, nz * 2.5);
	}

	float exitYaw() {
		return (float) (Math.toDegrees(Math.atan2(-nx, nz)));
	}

	boolean touches(Vec3 p) {
		double dx = p.x - (base.getX() + 0.5), dz = p.z - (base.getZ() + 0.5);
		double along = dx * sx() + dz * sz(), across = dx * nx + dz * nz;
		double dy = p.y - (base.getY() + 1);
		return Math.abs(along) <= W + 0.5 && Math.abs(across) <= 0.6 && dy >= -0.5 && dy < H;
	}

	/** Particles every few ticks; moves players who walk in. True = someone went through. */
	boolean tick(ServerLevel level, List<ServerPlayer> near, long now) {
		if (!open || to == null) return false;
		Vec3 c = Vec3.atBottomCenterOf(base.above());
		if (now % 4 == 0) {
			level.sendParticles(AuraFx.dust(rgb, 1.2f), c.x, c.y + H / 2.0, c.z, 4, sx() * 0.9 + 0.05, H / 3.0, sz() * 0.9 + 0.05, 0);
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y + 0.4, c.z, 2, sx() * 0.9, 0.1, sz() * 0.9, 0.01);
		}
		boolean moved = false;
		for (ServerPlayer p : near) {
			if (!touches(p.position())) continue;
			Long cd = cooldown.get(p.getUUID());
			if (cd != null && now < cd) continue;
			to.cooldown.put(p.getUUID(), now + 40);
			cooldown.put(p.getUUID(), now + 40);
			Vec3 e = to.exit();
			AuraFx.burst(level, p.getX(), p.getY() + 1, p.getZ(), ParticleTypes.REVERSE_PORTAL, 20, 0.4, 0.1);
			p.teleportTo(level, e.x, e.y, e.z, to.exitYaw(), 0f);
			p.fallDistance = 0;
			level.playSound(null, e.x, e.y, e.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.4f, 0.7f);
			moved = true;
		}
		return moved;
	}

	void remove() {
		for (Display.BlockDisplay d : curtain) Props.remove(d);
		curtain.clear();
		open = false;
	}
}
