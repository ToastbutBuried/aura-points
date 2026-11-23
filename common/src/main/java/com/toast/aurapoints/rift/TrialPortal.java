package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraSounds;
import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.Props;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
	/** Half width and height of this portal's opening (a trial's own big gate is larger), and whether it has its own arch. */
	final int w, h;
	private final List<Display.BlockDisplay> curtain = new ArrayList<>();
	private final Map<UUID, Long> cooldown = new HashMap<>();
	/** Closed portals show no curtain and don't move anyone (the arena is still being built). */
	boolean open;

	TrialPortal(BlockPos base, int nx, int nz, int rgb) {
		this(base, nx, nz, rgb, W, H);
	}

	/** A portal in a gate that already stands there: only the curtain, half width {@code w}, {@code h} tall. */
	TrialPortal(BlockPos base, int nx, int nz, int rgb, int w, int h) {
		this.w = w;
		this.h = h;
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

	/** The kinds of arch stones. */
	static final int POST = 0, LINTEL = 1, CROWN = 2, LAMP = 3;

	/** One block of the arch, where it stands and which kind of stone it is. */
	record Piece(BlockPos pos, Block block, int kind) {
	}

	/** The four stones of an aura's arch, always four different blocks. */
	static Block[] stones(RealmBuilder.Palette pal) {
		Block[] want = {pal.edge(), pal.base(), pal.accent(), pal.light()};
		Block[] spare = {pal.floor(), Blocks.CHISELED_STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.CHISELED_DEEPSLATE, Blocks.BRICKS};
		List<Block> out = new ArrayList<>();
		for (Block b : want) {
			Block use = b;
			for (int k = 0; k < spare.length && (out.contains(use) || (out.size() < 3 && use == pal.light())); k++) use = spare[k];
			out.add(use);
		}
		return out.toArray(new Block[0]);
	}

	private Piece piece(int s, int y, int kind, Block[] st) {
		return new Piece(new BlockPos(base.getX() + sx() * s, base.getY() + y, base.getZ() + sz() * s), st[kind], kind);
	}

	/** The arch in building order: the posts rise together, then the feet with their lamps, the lintel, the crown and its lamp. */
	List<Piece> frame(RealmBuilder.Palette pal) {
		Block[] st = stones(pal);
		List<Piece> out = new ArrayList<>();
		for (int y = 1; y <= H; y++) for (int side : new int[]{-1, 1}) out.add(piece(side * (W + 1), y, POST, st));
		for (int s : new int[]{-W - 2, W + 2}) out.add(piece(s, 1, POST, st));
		for (int s : new int[]{-W - 2, W + 2}) out.add(piece(s, 2, LAMP, st));
		for (int s : new int[]{-W - 1, W + 1, -W, W, 0}) out.add(piece(s, H + 1, LINTEL, st));
		for (int s : new int[]{-W, W, 0}) out.add(piece(s, H + 2, CROWN, st));
		out.add(piece(0, H + 3, LAMP, st));
		return out;
	}

	/** Where a piece sits in the arch: {along, up from the floor}. */
	int[] slot(Piece p) {
		return new int[]{(p.pos().getX() - base.getX()) * sx() + (p.pos().getZ() - base.getZ()) * sz(), p.pos().getY() - base.getY()};
	}

	/** A world point {@code along} the opening, {@code up} from the floor's top, {@code out} in front of it. */
	Vec3 point(double along, double up, double out) {
		return Vec3.atBottomCenterOf(base.above()).add(sx() * along + nx * out, up, sz() * along + nz * out);
	}

	/** The floor strip, an empty opening, and every piece of the arch that is not {@code missing}. */
	void buildBroken(ServerLevel level, RealmBuilder.Palette pal, java.util.Set<BlockPos> missing) {
		for (int s = -W - 1; s <= W + 1; s++) RealmBuilder.set(level, base.getX() + sx() * s, base.getY(), base.getZ() + sz() * s, pal.accent());
		for (int s = -W; s <= W; s++) for (int y = 1; y <= H; y++) RealmBuilder.set(level, base.getX() + sx() * s, base.getY() + y, base.getZ() + sz() * s, Blocks.AIR);
		for (Piece p : frame(pal)) RealmBuilder.set(level, p.pos().getX(), p.pos().getY(), p.pos().getZ(), missing.contains(p.pos()) ? Blocks.AIR : p.block());
	}

	void setOpen(ServerLevel level, boolean on, Block glass) {
		if (on == open) return;
		open = on;
		for (Display.BlockDisplay d : curtain) Props.remove(d);
		curtain.clear();
		if (!on) return;
		Vec3 c = Vec3.atBottomCenterOf(base.above());
		// a bright slit first, torn open sideways over a second
		sheet(level, c, glass.defaultBlockState(), 2 * w + 1, h, 0.12f, 0f);
		// a second, thinner sheet of the aura's light for the shimmer
		sheet(level, c, Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), 2 * w + 0.7f, h - 0.3f, 0.04f, 0.15f);
		level.sendParticles(ParticleTypes.FLASH, c.x, c.y + h / 2.0, c.z, 1, 0, 0, 0, 0);
		level.playSound(null, c.x, c.y + 2, c.z, AuraSounds.BEACON_ACTIVATE.value(), SoundSource.BLOCKS, 1.2f, 1.4f);
		level.playSound(null, c.x, c.y + 2, c.z, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), SoundSource.BLOCKS, 1.6f, 0.6f);
	}

	private Transformation sheetT(float wide, float tall, float depth, float dy) {
		boolean alongX = sx() != 0;
		Vector3f scale = alongX ? new Vector3f(wide, tall, depth) : new Vector3f(depth, tall, wide);
		return new Transformation(new Vector3f(-scale.x / 2f, dy, -scale.z / 2f), new Quaternionf(), scale, null);
	}

	private void sheet(ServerLevel level, Vec3 c, BlockState block, float wide, float tall, float depth, float dy) {
		Display.BlockDisplay d = Props.spawn(level, c, block, sheetT(0.1f, tall, depth, dy), -1, 3);
		Props.glow(d);
		d.addTag(Trials.DISPLAY_TAG);
		RuinKit.bigBounds(d, Math.max(wide, tall));
		curtain.add(d);
		AuraFx.later(level.getServer(), 3, () -> {
			if (!d.isRemoved()) Props.set(d, sheetT(wide, tall, depth, dy), 20);
		});
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
		return Math.abs(along) <= w + 0.5 && Math.abs(across) <= 0.6 && dy >= -0.5 && dy < h;
	}

	/** Particles every few ticks; moves players who walk in. True = someone went through. */
	boolean tick(ServerLevel level, List<ServerPlayer> near, long now) {
		if (!open || to == null) return false;
		Vec3 c = Vec3.atBottomCenterOf(base.above());
		if (now % 4 == 0) {
			level.sendParticles(AuraFx.dust(rgb, 1.2f), c.x, c.y + h / 2.0, c.z, 2 + 2 * w, sx() * w * 0.9 + 0.05, h / 3.0, sz() * w * 0.9 + 0.05, 0);
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y + 0.4, c.z, 2 * w, sx() * w * 0.9, 0.1, sz() * w * 0.9, 0.01);
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
			if (com.toast.aurapoints.shim.Platform.get().isFakePlayer(p)) p.moveTo(e.x, e.y, e.z, to.exitYaw(), 0f); // dev tests: no connection to teleport through
			else p.teleportTo(level, e.x, e.y, e.z, to.exitYaw(), 0f);
			p.fallDistance = 0;
			RiftSfx.passage(level, e);
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
