package com.toast.aurapoints.rift;

import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Props;
import com.toast.aurapoints.mixin.BlockDisplayAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Skyward Rings (5.49.0): an elytra course through the sky, always one of the trials in Wind, Light and Thunder rifts.
 * A sky palace at the way back holds the launch stone: a right-click lends Trial Wings (the chest item is kept safe),
 * starts the clock and fires you up out of the wind cannon. Rings of glowing bars line a flowing 3D route between
 * floating islands (Wind cloud palaces, Light gold isles, Thunder storm spires); flying through a ring's middle scores
 * it for the whole party and hands the flyer a rocket; the next ring pulses with arrows through it, bonus rings off the
 * line are turning stars round a crystal and worth more, updraft columns (flat double hoops, one climbing) give height
 * back, taken rings shrink to a thin grey hoop. Land on the far
 * pad with the score target met before the clock runs out and the shard comes out of its plinth.
 * <p>
 * Strikes: falling below the course, leaving it or the clock running out. 1 the sky rumbles, 2 gusts (or lightning,
 * or flashes) knock flyers about on every attempt after, 3 a new route.
 * <p>
 * Lending: the original chest item sits in {@link #ORIG} and, as NBT, in the lent elytra's own custom data, so
 * {@link #purgeLent} can give it back after a restart too. Every lent item (wings, rockets) carries {@link #LENT}.
 */
final class TrialRings extends Trials.Trial {

	/** Arena frame: the wind cannon, the launch stone and the spot flyers come back to; the landing pad at the far end. */
	private static final int HALF = 160, UP = 160, TERRACE_F = -HALF + 14, CANNON_F = TERRACE_F + 4, STONE_F = TERRACE_F - 3, HOME_F = TERRACE_F - 5, PAD_F = HALF - 15, PAD_R = 8;
	/** A gliding player's middle above their feet. */
	private static final double BODY = 0.4;
	static final String LENT = "aurapoints_lent";

	/** Lent-out player -> the chest item they wore before (also inside the wings, for after a restart). */
	private static final Map<UUID, ItemStack> ORIG = new HashMap<>();

	private record Theme(Block cloud, Block cloud2, Block rock, Block top, Block trim, Block pillar, Block lamp, Block ring, Block cap, ParticleOptions lift, int style) {
	}

	/** Style 0 cloud palace (Wind), 1 floating isles (Light, anything else), 2 storm spires (Thunder). */
	private static Theme theme(AuraType aura, RealmBuilder.Palette pal) {
		return switch (aura) {
			case WIND -> new Theme(Blocks.WHITE_WOOL, Blocks.SNOW_BLOCK, Blocks.DIORITE, Blocks.CALCITE, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_PILLAR,
					Blocks.PEARLESCENT_FROGLIGHT, Blocks.LIGHT_BLUE_CONCRETE, Blocks.PEARLESCENT_FROGLIGHT, ParticleTypes.CLOUD, 0);
			case LIGHT -> new Theme(Blocks.CALCITE, Blocks.WHITE_WOOL, Blocks.CALCITE, Blocks.SMOOTH_QUARTZ, Blocks.GOLD_BLOCK, Blocks.QUARTZ_PILLAR,
					Blocks.GLOWSTONE, Blocks.ORANGE_CONCRETE, Blocks.GOLD_BLOCK, ParticleTypes.END_ROD, 1);
			case THUNDER -> new Theme(Blocks.GRAY_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.COBBLED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE, Blocks.WAXED_CUT_COPPER,
					Blocks.POLISHED_DEEPSLATE, Blocks.GLOWSTONE, Blocks.CYAN_CONCRETE, Blocks.LIGHTNING_ROD, ParticleTypes.ELECTRIC_SPARK, 2);
			default -> new Theme(pal.base(), pal.edge(), pal.base(), pal.floor(), pal.accent(), pal.edge(), pal.light(), Blocks.LIME_CONCRETE, pal.light(),
					ParticleTypes.END_ROD, 1);
		};
	}

	private static final class Ring {
		final Vec3 c, n;
		final double r;
		final int pts;
		final boolean bonus;
		/** Set by someone flying through; {@code next}: the one to fly through now. */
		boolean taken, next;
		final List<Display.BlockDisplay> bars = new ArrayList<>();
		Display.BlockDisplay crystal;
		double turn;

		Ring(Vec3 c, Vec3 n, double r, int pts, boolean bonus) {
			this.c = c;
			this.n = n;
			this.r = r;
			this.pts = pts;
			this.bonus = bonus;
		}
	}

	/** An updraft column: bottom middle (world) and its top y. */
	private record Draft(Vec3 base, double top) {
	}

	/** A vertical capsule in the arena frame that the route keeps clear of (an island, a spire, a cloud). */
	private record Obstacle(double f, double l, double dy0, double dy1, double r) {
		double dist(Vec3 q) {
			double d = Math.hypot(q.x - f, q.y - l);
			double dv = Math.max(0, Math.max(dy0 - q.z, q.z - dy1));
			return Math.hypot(d, dv) - r;
		}
	}

	private static final class Flyer {
		final long since;
		boolean airborne;
		Vec3 last, vel = Vec3.ZERO;

		Flyer(long since) {
			this.since = since;
		}
	}

	private record Gust(Vec3 at, long due) {
	}

	private Theme th;
	private final List<Ring> rings = new ArrayList<>();
	private final List<Draft> drafts = new ArrayList<>();
	private final List<Display.BlockDisplay> bars = new ArrayList<>();
	private final List<Obstacle> obstacles = new ArrayList<>();
	private final List<BlockPos> spireTops = new ArrayList<>();
	/** The route as world points every ~2 blocks (intro camera, hints). */
	private final List<Vec3> path = new ArrayList<>();
	private final Map<UUID, Flyer> flyers = new HashMap<>();
	private final List<Gust> gusts = new ArrayList<>();
	private boolean attempt, hazard, targetCalled;
	private long attemptEnd, hudMute, nextGust;
	private int score, target;

	TrialRings(Rifts.Run run, int index) {
		super(run, index, Trials.Kind.RINGS);
	}

	@Override
	String name() {
		return "Skyward Rings";
	}

	@Override
	String hint() {
		return "Right-click the launch stone, fly through the rings and land on the far pad with enough points before time runs out.";
	}

	@Override
	int arenaHalf() {
		return HALF;
	}

	@Override
	int arenaUp() {
		return UP;
	}

	// ---------------------------------------------------------------
	// Tier numbers
	// ---------------------------------------------------------------

	private int ringCount() {
		return 12 + tier * 3;
	}

	private int bonusCount() {
		return 2 + tier / 2;
	}

	private double ringR() {
		return new double[]{4.0, 3.5, 3.1, 2.8, 2.5}[tier];
	}

	private int seconds() {
		return new int[]{154, 126, 112, 98, 91}[tier];
	}

	/** Rockets lent at the launch; every ring hands out more. */
	private int rockets() {
		return tier <= 1 ? 3 : 1;
	}

	private int computeTarget() {
		double pct = new double[]{0.6, 0.7, 0.78, 0.85, 0.9}[tier] - (run.players.size() <= 1 ? 0.08 : 0);
		return Math.max(10, (int) Math.round(ringCount() * 10 * pct / 10.0) * 10);
	}

	// ---------------------------------------------------------------
	// Frame helpers
	// ---------------------------------------------------------------

	/** World point of an arena-frame (f, l, dy); dy 0 = the floor block's bottom (FLOOR), so 1 = standing height. */
	private Vec3 w(double f, double l, double dy) {
		return new Vec3(a.getX() + 0.5 + fx * f + lx * l, Rifts.FLOOR + dy, a.getZ() + 0.5 + fz * f + lz * l);
	}

	/** Frame vector (f, l, dy) packed in a Vec3 as (x=f, y=l, z=dy) to world. */
	private Vec3 w(Vec3 fr) {
		return w(fr.x, fr.y, fr.z);
	}

	private Vec3 wdir(Vec3 fr) {
		return new Vec3(fx * fr.x + lx * fr.y, fr.z, fz * fr.x + lz * fr.y);
	}

	private Vec3 home() {
		return Vec3.atBottomCenterOf(arena(HOME_F, 0, 1));
	}

	private BlockPos launchStone() {
		return arena(STONE_F, 0, 1);
	}

	private BlockPos returnStone() {
		return arena(PAD_F + 5, 0, 1);
	}

	@Override
	Vec3 plinth() {
		return Vec3.atBottomCenterOf(arena(PAD_F, 0, 3)).add(0, 0.2, 0);
	}

	private boolean onPad(Vec3 p) {
		double dy = p.y - Rifts.FLOOR;
		return Math.hypot(af(p) - PAD_F, al(p)) <= PAD_R + 0.5 && dy >= 0.5 && dy <= 6;
	}

	private boolean nearHome(Vec3 p) {
		double dy = p.y - Rifts.FLOOR;
		return Math.hypot(af(p) - TERRACE_F, al(p)) <= 12.5 && dy >= 0.5 && dy <= 4;
	}

	// ---------------------------------------------------------------
	// Building
	// ---------------------------------------------------------------

	@Override
	void build(ServerLevel level, RandomSource r) {
		th = theme(run.aura, pal);
		obstacles.clear();
		spireTops.clear();
		palace(level, r);
		pad(level);
		// the first route on an empty sky, then the islands keep clear of it
		route(r, false);
		islands(level, r);
		spawnCourse(level);
		label(level, Vec3.atBottomCenterOf(launchStone()).add(0, 1.6, 0), Component.literal("Launch").withStyle(s -> s.withColor(rgb).withBold(true)), 1.2f);
		label(level, Vec3.atBottomCenterOf(returnStone()).add(0, 1.6, 0), Component.literal("Back to the launch").withStyle(ChatFormatting.GRAY), 0.9f);
		label(level, Vec3.atBottomCenterOf(arena(PAD_F, 0, 7)), Component.literal("Landing").withStyle(s -> s.withColor(rgb).withBold(true)), 2.0f);
	}

	/** Mixes two blocks in small clusters (no speckle). */
	private static Block mix(Block a, Block b, int f, int l, int dy, int every) {
		int h = (f >> 1) * 73856093 ^ (l >> 1) * 19349663 ^ (dy >> 1) * 83492791;
		return Math.floorMod(h, every) == 0 ? b : a;
	}

	/** A puffy cloud: ellipsoid, flatter below than above. */
	private void puff(ServerLevel level, double cf, double cl, double cdy, double rx, double ry, Block a, Block b) {
		for (int f = (int) Math.floor(cf - rx); f <= Math.ceil(cf + rx); f++) {
			for (int l = (int) Math.floor(cl - rx); l <= Math.ceil(cl + rx); l++) {
				for (int dy = (int) Math.floor(cdy - ry * 0.6); dy <= Math.ceil(cdy + ry); dy++) {
					double vy = dy < cdy ? (dy - cdy) / (ry * 0.6) : (dy - cdy) / ry;
					double q = ((f - cf) * (f - cf) + (l - cl) * (l - cl)) / (rx * rx) + vy * vy;
					if (q <= 1) set(level, f, l, dy, mix(a, b, f, l, dy, 5));
				}
			}
		}
	}

	/** A disk of {@code top} with a tapering underside of {@code rock}; rim blocks {@code rim} (null = none). */
	private void isle(ServerLevel level, RandomSource r, int cf, int cl, int dy, int rad, int depth, Block top, Block rock, Block rim) {
		for (int df = -rad - 1; df <= rad + 1; df++) {
			for (int dl = -rad - 1; dl <= rad + 1; dl++) {
				double d = Math.hypot(df, dl);
				double edge = rad + 0.5 + Math.sin(Math.atan2(dl, df) * 5 + cf) * 0.5;
				if (d > edge) continue;
				set(level, cf + df, cl + dl, dy, rim != null && d > edge - 1 && ((df + dl) & 1) == 0 ? rim : top);
				double t = 1 - d / edge;
				int deep = 1 + (int) Math.round(t * t * depth + r.nextInt(2));
				for (int k = 1; k <= deep; k++) set(level, cf + df, cl + dl, dy - k, rock);
			}
		}
	}

	/** The sky palace at the way back: a round terrace with a back colonnade, two towers, the wind cannon, the launch stone. */
	private void palace(ServerLevel level, RandomSource r) {
		int R = 11;
		for (int df = -R - 1; df <= R + 1; df++) {
			for (int dl = -R - 1; dl <= R + 1; dl++) {
				double d = Math.hypot(df, dl);
				if (d > R + 0.5) continue;
				int f = TERRACE_F + df;
				set(level, f, dl, 0, d > 6.4 && d < 7.6 ? pal.accent() : d > R - 0.5 ? th.trim : pal.floor());
				int deep = 1 + (int) Math.round((1 - d / (R + 0.5)) * (1 - d / (R + 0.5)) * 10);
				for (int k = 1; k <= deep; k++) set(level, f, dl, -k, th.rock);
				// a low parapet with gaps at the front (the view) and the back (the portal)
				boolean gap = Math.abs(dl) <= 3;
				if (d > R - 0.5 && !gap) set(level, f, dl, 1, pal.edge());
			}
		}
		// cloud skirt under the rim
		for (int k = 0; k < 10; k++) {
			double ang = k * Math.PI * 2 / 10 + r.nextDouble() * 0.3;
			puff(level, TERRACE_F + Math.cos(ang) * (R - 1), Math.sin(ang) * (R - 1), -2, 3.5 + r.nextDouble() * 1.5, 2.5, th.cloud, th.cloud2);
		}
		// the back colonnade: pillars on an arc behind the stone, joined by an architrave, open over the portal
		double prev = Double.NaN;
		for (int deg = 100; deg <= 260; deg += 20) {
			if (Math.abs(deg - 180) < 30) {
				prev = Double.NaN;
				continue;
			}
			double ang = Math.toRadians(deg);
			int pf = TERRACE_F + (int) Math.round(Math.cos(ang) * 9.5), pl = (int) Math.round(Math.sin(ang) * 9.5);
			for (int dy = 1; dy <= 6; dy++) set(level, pf, pl, dy, th.pillar);
			set(level, pf, pl, 7, th.trim);
			set(level, pf, pl, 8, th.lamp);
			if (!Double.isNaN(prev)) {
				for (double t = prev; t <= ang; t += 0.04) set(level, TERRACE_F + (int) Math.round(Math.cos(t) * 9.5), (int) Math.round(Math.sin(t) * 9.5), 7, th.trim);
			}
			prev = ang;
		}
		// two towers flanking the cannon
		for (int side : new int[]{-1, 1}) {
			int tf = TERRACE_F - 3, tl = side * 8;
			for (int df = -1; df <= 1; df++)
				for (int dl = -1; dl <= 1; dl++) {
					set(level, tf + df, tl + dl, 1, th.trim);
					set(level, tf + df, tl + dl, 2, Math.abs(df) + Math.abs(dl) == 2 ? pal.edge() : th.trim);
				}
			int h = 22;
			for (int dy = 3; dy <= h; dy++) {
				set(level, tf, tl, dy, th.pillar);
				boolean collar = dy % 6 == 0;
				for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
					if (dy < 8 || collar) set(level, tf + o[0], tl + o[1], dy, collar ? th.trim : th.pillar);
			}
			for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) set(level, tf + o[0], tl + o[1], h + 1, th.trim);
			set(level, tf, tl, h + 1, th.lamp);
			set(level, tf, tl, h + 2, th.trim);
			set(level, tf, tl, h + 3, th.cap);
			spireTops.add(arena(tf, tl, h + 3));
			if (th.style == 0) puff(level, tf, tl, h - 6, 3, 1.6, th.cloud, th.cloud2);
		}
		// the wind cannon: a lit 3x3 vent in a ring of trim
		for (int df = -2; df <= 2; df++)
			for (int dl = -2; dl <= 2; dl++)
				set(level, CANNON_F + df, dl, 0, Math.abs(df) <= 1 && Math.abs(dl) <= 1 ? (df == 0 && dl == 0 ? th.lamp : pal.light()) : th.trim);
		set(level, STONE_F, 0, 1, Blocks.LODESTONE);
		set(level, STONE_F, 0, 0, th.trim);
	}

	/** The landing pad at the far end with the shard plinth, lamp posts and the stone home. */
	private void pad(ServerLevel level) {
		for (int df = -PAD_R; df <= PAD_R; df++) {
			for (int dl = -PAD_R; dl <= PAD_R; dl++) {
				double d = Math.hypot(df, dl);
				if (d > PAD_R + 0.5) continue;
				set(level, PAD_F + df, dl, 0, d > 4.4 && d < 5.6 ? pal.accent() : d > PAD_R - 0.5 ? th.trim : pal.floor());
				int deep = 1 + (int) Math.round((1 - d / (PAD_R + 0.5)) * (1 - d / (PAD_R + 0.5)) * 8);
				for (int k = 1; k <= deep; k++) set(level, PAD_F + df, dl, -k, th.rock);
			}
		}
		for (int k = 0; k < 7; k++) {
			double ang = k * Math.PI * 2 / 7;
			puff(level, PAD_F + Math.cos(ang) * (PAD_R - 1), Math.sin(ang) * (PAD_R - 1), -2, 3, 2.2, th.cloud, th.cloud2);
		}
		for (int[] o : new int[][]{{1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) {
			int pf = PAD_F + o[0] * 4, pl = o[1] * 4;
			for (int dy = 1; dy <= 3; dy++) set(level, pf, pl, dy, th.pillar);
			set(level, pf, pl, 4, th.lamp);
		}
		set(level, PAD_F, 0, 1, pal.edge());
		set(level, PAD_F, 0, 2, pal.accent());
		set(level, PAD_F + 5, 0, 1, Blocks.LODESTONE);
	}

	/** Floating islands in the aura's style around (never on) the route, plus cloud banks below or a storm above. */
	private void islands(ServerLevel level, RandomSource r) {
		int want = 26, placed = 0;
		for (int tries = 0; tries < 500 && placed < want; tries++) {
			int cf = -118 + r.nextInt(236), cl = -138 + r.nextInt(277), cdy = -10 + r.nextInt(70);
			int rad = th.style == 2 ? 3 + r.nextInt(2) : 4 + r.nextInt(4);
			int height = th.style == 2 ? 14 + r.nextInt(18) : th.style == 0 ? (int) Math.ceil(rad * 0.6) + 6 : 9;
			Obstacle o = new Obstacle(cf, cl, cdy - rad - 2, cdy + height, rad + 1.5);
			if (!clear(o, 7)) continue;
			obstacles.add(o);
			placed++;
			if (th.style == 0) cloudIsle(level, r, cf, cl, cdy, rad);
			else if (th.style == 2) spire(level, r, cf, cl, cdy, rad, height);
			else goldIsle(level, r, cf, cl, cdy, rad);
		}
		// a sea of cloud far below (Wind, Thunder) - the line you must not fall under is just beneath it
		if (th.style != 1) {
			for (int k = 0; k < 9; k++) {
				double cf = -110 + r.nextInt(220), cl = -125 + r.nextInt(250);
				puff(level, cf, cl, -20, 10 + r.nextInt(6), 2, th.cloud, th.cloud2);
			}
		}
		// Thunder: storm clouds over the course
		if (th.style == 2) {
			for (int k = 0; k < 5; k++) {
				int cf = -110 + r.nextInt(220), cl = -125 + r.nextInt(250), cdy = 100 + r.nextInt(20);
				Obstacle o = new Obstacle(cf, cl, cdy - 3, cdy + 4, 12);
				if (!clear(o, 6)) continue;
				obstacles.add(o);
				puff(level, cf, cl, cdy, 9 + r.nextInt(5), 3.5, Blocks.GRAY_WOOL, Blocks.BLACK_WOOL);
			}
		}
	}

	/** True if the obstacle keeps {@code margin} from the route and the other obstacles. */
	private boolean clear(Obstacle o, double margin) {
		for (Vec3 q : framePath) if (o.dist(q) < margin) return false;
		for (Draft d : drafts) {
			double ddy0 = d.base.y - Rifts.FLOOR, ddy1 = d.top - Rifts.FLOOR;
			if (Math.hypot(o.f - af(d.base), o.l - al(d.base)) < o.r + 6 && o.dy0 < ddy1 + 3 && ddy0 - 3 < o.dy1) return false;
		}
		for (Obstacle b : obstacles) if (Math.hypot(o.f - b.f, o.l - b.l) < o.r + b.r + 3 && o.dy0 < b.dy1 + 3 && b.dy0 < o.dy1 + 3) return false;
		return true;
	}

	private void cloudIsle(ServerLevel level, RandomSource r, int cf, int cl, int cdy, int rad) {
		int n = 3 + r.nextInt(3);
		for (int k = 0; k < n; k++) {
			double ang = r.nextDouble() * Math.PI * 2, d = k == 0 ? 0 : rad * 0.6;
			puff(level, cf + Math.cos(ang) * d, cl + Math.sin(ang) * d, cdy + (k == 0 ? 0 : -1), k == 0 ? rad : rad * 0.6, k == 0 ? rad * 0.6 : rad * 0.4, th.cloud, th.cloud2);
		}
		if (r.nextInt(5) < 2) {
			// a small pavilion on a calcite deck: four pillars, a flat roof, a lamp
			int top = cdy + (int) Math.ceil(rad * 0.6);
			for (int df = -2; df <= 2; df++) for (int dl = -2; dl <= 2; dl++) set(level, cf + df, cl + dl, top, th.top);
			for (int[] o : new int[][]{{2, 2}, {-2, 2}, {2, -2}, {-2, -2}}) for (int dy = 1; dy <= 3; dy++) set(level, cf + o[0], cl + o[1], top + dy, th.pillar);
			for (int df = -2; df <= 2; df++) for (int dl = -2; dl <= 2; dl++) set(level, cf + df, cl + dl, top + 4, Math.abs(df) == 2 || Math.abs(dl) == 2 ? th.trim : th.top);
			set(level, cf, cl, top + 5, th.lamp);
		}
	}

	private void goldIsle(ServerLevel level, RandomSource r, int cf, int cl, int cdy, int rad) {
		isle(level, r, cf, cl, cdy, rad, (int) (rad * 1.4), th.top, th.rock, th.trim);
		int h = 3 + r.nextInt(4);
		for (int dy = 1; dy <= h; dy++) set(level, cf, cl, cdy + dy, th.pillar);
		set(level, cf, cl, cdy + h + 1, th.lamp);
		set(level, cf, cl, cdy + h + 2, th.trim);
		for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) set(level, cf + o[0], cl + o[1], cdy + 1, th.trim);
		if (r.nextBoolean()) puff(level, cf + rad, cl - rad / 2.0, cdy - 3, 2.5, 1.5, th.cloud, th.cloud2);
	}

	private void spire(ServerLevel level, RandomSource r, int cf, int cl, int cdy, int rad, int h) {
		isle(level, r, cf, cl, cdy, rad, rad * 2, th.rock, th.rock, null);
		for (int dy = 1; dy <= h; dy++) {
			set(level, cf, cl, cdy + dy, dy % 5 == 0 ? Blocks.WAXED_COPPER_BLOCK : th.pillar);
			if (dy < h * 0.4) for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) set(level, cf + o[0], cl + o[1], cdy + dy, dy % 5 == 0 ? th.trim : th.pillar);
		}
		set(level, cf, cl, cdy + h + 1, Blocks.LIGHTNING_ROD);
		spireTops.add(arena(cf, cl, cdy + h + 1));
	}

	// ---------------------------------------------------------------
	// The route
	// ---------------------------------------------------------------

	/** The route in the arena frame (x=f, y=l, z=dy), every ~2 blocks. */
	private final List<Vec3> framePath = new ArrayList<>();

	private record Plan(List<Vec3> pts, List<Integer> draftAt, List<Double> draftTop) {
	}

	/** Rolls a route (and its rings and updrafts); with {@code avoid}, the best of 30 tries around the islands. */
	private void route(RandomSource r, boolean avoid) {
		List<Vec3> best = null;
		Plan bestPlan = null;
		double bestBad = Double.MAX_VALUE;
		for (int tries = 0; tries < (avoid ? 30 : 1); tries++) {
			Plan plan = plan(r);
			List<Vec3> s = spline(plan.pts);
			double bad = 0;
			if (avoid) for (Vec3 q : s) for (Obstacle o : obstacles) if (o.dist(q) < 5) bad += 5 - o.dist(q);
			if (bad < bestBad) {
				bestBad = bad;
				best = s;
				bestPlan = plan;
			}
			if (bad == 0) break;
		}
		framePath.clear();
		framePath.addAll(best);
		path.clear();
		for (Vec3 q : best) path.add(w(q));
		// arc length
		double[] arc = new double[best.size()];
		for (int i = 1; i < best.size(); i++) arc[i] = arc[i - 1] + best.get(i).distanceTo(best.get(i - 1));
		double len = arc[arc.length - 1];
		rings.clear();
		int n = ringCount();
		double s0 = 22, s1 = len - 16;
		for (int k = 0; k < n; k++) {
			double s = s0 + (s1 - s0) * k / (n - 1.0);
			int i = at(arc, s);
			rings.add(new Ring(w(best.get(i)), tangent(best, i), ringR(), 10, false));
		}
		for (int k = 0; k < bonusCount(); k++) {
			double s = s0 + (s1 - s0) * (k + 0.5 + r.nextDouble() * 0.5) / (bonusCount() + 0.5);
			int i = at(arc, s);
			Vec3 q = best.get(i), t = best.get(Math.min(best.size() - 1, i + 1)).subtract(best.get(Math.max(0, i - 1)));
			Vec3 side = new Vec3(-t.y, t.x, 0).normalize().scale((r.nextBoolean() ? 1 : -1) * (13 + r.nextInt(8)));
			Vec3 b = q.add(side).add(0, 0, 4 + r.nextInt(5));
			b = new Vec3(clamp(b.x, -HALF + 32, HALF - 32), clamp(b.y, -HALF + 32, HALF - 32), clamp(b.z, 6, UP - 28));
			rings.add(new Ring(w(b), tangent(best, i), ringR(), 25, true));
		}
		drafts.clear();
		for (int k = 0; k < bestPlan.draftAt.size(); k++) {
			Vec3 q = bestPlan.pts.get(bestPlan.draftAt.get(k));
			drafts.add(new Draft(w(q.x, q.y, q.z - 14), Rifts.FLOOR + bestPlan.draftTop.get(k)));
		}
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static int at(double[] arc, double s) {
		int i = 0;
		while (i < arc.length - 1 && arc[i] < s) i++;
		return i;
	}

	private Vec3 tangent(List<Vec3> s, int i) {
		Vec3 t = s.get(Math.min(s.size() - 1, i + 1)).subtract(s.get(Math.max(0, i - 1)));
		return wdir(t).normalize();
	}

	/**
	 * Control points: the cannon's apex, a first point ahead, then a swing from side to side across the sky, losing
	 * height at about 1 in 10 and an updraft wherever it would get too low, then the approach to the pad.
	 */
	private Plan plan(RandomSource r) {
		List<Vec3> pts = new ArrayList<>();
		List<Integer> draftAt = new ArrayList<>();
		List<Double> draftTop = new ArrayList<>();
		pts.add(new Vec3(CANNON_F, 0, 44));
		pts.add(new Vec3(CANNON_F + 22, r.nextInt(21) - 10, 38));
		int k = 3 + tier;
		double h = 38;
		int sign = r.nextBoolean() ? 1 : -1;
		for (int i = 0; i < k; i++) {
			double f = -100 + 205.0 * (i + 0.5) / k + r.nextInt(13) - 6;
			double l = sign * (35 + r.nextInt(72));
			sign = -sign;
			Vec3 prev = pts.get(pts.size() - 1);
			h -= Math.hypot(f - prev.x, l - prev.y) / 10.0;
			if (h < 16) {
				// an updraft at the last point lifts you back up for this leg
				double top = prev.z + 34;
				draftAt.add(pts.size() - 1);
				draftTop.add(top);
				h = top - Math.hypot(f - prev.x, l - prev.y) / 10.0;
			}
			h = clamp(h + r.nextInt(9) - 4, 10, 100);
			pts.add(new Vec3(f, l, h));
		}
		if (draftAt.isEmpty() || tier == 0) {
			// at least one; Easy gets an extra halfway
			int mid = Math.max(1, pts.size() / 2);
			if (!draftAt.contains(mid)) {
				draftAt.add(mid);
				draftTop.add(pts.get(mid).z + 22);
			}
		}
		pts.add(new Vec3(PAD_F - 20, 0, 9));
		pts.add(new Vec3(PAD_F, 0, 3));
		return new Plan(pts, draftAt, draftTop);
	}

	/** Catmull-Rom through the control points, sampled every ~2 blocks. */
	private static List<Vec3> spline(List<Vec3> p) {
		List<Vec3> out = new ArrayList<>();
		for (int i = 0; i < p.size() - 1; i++) {
			Vec3 p0 = p.get(Math.max(0, i - 1)), p1 = p.get(i), p2 = p.get(i + 1), p3 = p.get(Math.min(p.size() - 1, i + 2));
			int steps = Math.max(2, (int) Math.ceil(p1.distanceTo(p2) / 2));
			for (int s = 0; s < steps; s++) {
				double t = s / (double) steps, t2 = t * t, t3 = t2 * t;
				out.add(p1.scale(2).add(p2.subtract(p0).scale(t)).add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(t2))
						.add(p0.scale(-1).add(p1.scale(3)).subtract(p2.scale(3)).add(p3).scale(t3)).scale(0.5));
			}
		}
		out.add(p.get(p.size() - 1));
		return out;
	}

	// ---------------------------------------------------------------
	// Displays
	// ---------------------------------------------------------------

	private static final int BARS = 16;
	private static final float THICK = 0.36f;
	/** The arrows through the next ring, the climbing hoops of the updrafts (with their own column), the labels. */
	private final List<Display.BlockDisplay> arrows = new ArrayList<>();
	private final List<List<Display.BlockDisplay>> climbers = new ArrayList<>();
	private final List<Display.TextDisplay> tags = new ArrayList<>();

	/** Bar {@code i}'s block: the theme's colour, lime for the next ring, amethyst and magenta by turns on a star, grey glass once taken. */
	private BlockState ringBlock(Ring g, int i) {
		if (g.taken || solved) return Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState();
		if (g.bonus) return (i % 2 == 0 ? Blocks.AMETHYST_BLOCK : Blocks.MAGENTA_CONCRETE).defaultBlockState();
		return (g.next ? Blocks.LIME_CONCRETE : th.ring).defaultBlockState();
	}

	private static Vec3[] plane(Vec3 n) {
		Vec3 u = n.cross(new Vec3(0, 1, 0));
		if (u.lengthSqr() < 1.0e-4) u = new Vec3(1, 0, 0);
		u = u.normalize();
		return new Vec3[]{u, n.cross(u).normalize()};
	}

	/** Bar {@code i} of a ring: a round hoop; a bonus ring's are the sixteen edges of an eight-pointed star. */
	private Transformation barT(Ring g, int i, double scale) {
		Vec3[] uv = plane(g.n);
		boolean gone = g.taken || solved;
		double a0 = g.turn + i * Math.PI * 2 / BARS, a1 = g.turn + (i + 1) * Math.PI * 2 / BARS;
		double r = g.r * scale * (gone ? 0.75 : 1);
		// the star's inner corners are the ring you must fly through, its points stick out
		double r0 = g.bonus && !gone ? r * (i % 2 == 0 ? 1.5 : 1.0) : r, r1 = g.bonus && !gone ? r * (i % 2 == 0 ? 1.0 : 1.5) : r;
		Vec3 p0 = uv[0].scale(Math.cos(a0) * r0).add(uv[1].scale(Math.sin(a0) * r0)), p1 = uv[0].scale(Math.cos(a1) * r1).add(uv[1].scale(Math.sin(a1) * r1));
		return rod(p0, p1, gone ? 0.07f : THICK);
	}

	/** A bar from a to b (relative to its display), its ends a little longer so corners close. */
	private static Transformation rod(Vec3 a, Vec3 b, float w) {
		Vec3 d = b.subtract(a).normalize().scale(w / 2);
		Vec3 p = a.subtract(d), q = b.add(d);
		return RuinKit.rod(new Vector3f((float) p.x, (float) p.y, (float) p.z), new Vector3f((float) q.x, (float) q.y, (float) q.z), w);
	}

	private Display.BlockDisplay bar(ServerLevel level, Vec3 at, BlockState s, Transformation t, int smooth) {
		Display.BlockDisplay d = Props.spawn(level, at, s, t, -1, smooth);
		Props.glow(d);
		d.addTag(Trials.DISPLAY_TAG);
		RuinKit.bigBounds(d, 8f);
		((com.toast.aurapoints.mixin.DisplayAccessor) d).aurapoints$setViewRange(6f);
		bars.add(d);
		return d;
	}

	private void spawnCourse(ServerLevel level) {
		for (Ring g : rings) {
			for (int i = 0; i < BARS; i++) g.bars.add(bar(level, g.c, ringBlock(g, i), barT(g, i, 1), 0));
			if (!g.bonus) continue;
			g.crystal = bar(level, g.c, Blocks.AMETHYST_BLOCK.defaultBlockState(), crystalT(0), 0);
			tag(level, g.c.add(0, g.r * 1.5 + 1.1, 0), Component.literal("+" + g.pts).withStyle(s -> s.withColor(0xE58CFF).withBold(true)));
		}
		// the two arrows that fly along with the next ring
		for (int i = 0; i < 4; i++) arrows.add(bar(level, home(), Blocks.LIME_CONCRETE.defaultBlockState(), rod(Vec3.ZERO, new Vec3(0, 0.01, 0), 0.01f), 0));
		BlockState up = Blocks.YELLOW_CONCRETE.defaultBlockState();
		for (Draft d : drafts) {
			Vec3 top = new Vec3(d.base.x, d.top, d.base.z);
			for (Vec3 at : new Vec3[]{d.base, top}) for (double rad : new double[]{3.5, 2.6}) flat(level, at, rad, up, 0, null);
			// arrows up the column's sides
			for (double y = d.base.y + 4; y < d.top - 3; y += 6) {
				for (int k = 0; k < 4; k++) {
					double a = k * Math.PI / 2;
					Vec3 at = new Vec3(d.base.x + Math.cos(a) * 3.4, y, d.base.z + Math.sin(a) * 3.4), side = new Vec3(-Math.sin(a), 0, Math.cos(a)).scale(0.6);
					bar(level, at, up, rod(side.add(0, -0.6, 0), Vec3.ZERO, 0.16f), 0);
					bar(level, at, up, rod(side.scale(-1).add(0, -0.6, 0), Vec3.ZERO, 0.16f), 0);
				}
			}
			List<Display.BlockDisplay> climb = new ArrayList<>();
			flat(level, d.base, 3.0, up, 10, climb);
			climbers.add(climb);
			tag(level, d.base.add(0, 1.6, 0), Component.literal("Updraft ▲").withStyle(s -> s.withColor(0xFFE45C).withBold(true)));
		}
		if (!rings.isEmpty()) tag(level, rings.get(0).c.add(0, rings.get(0).r + 1.2, 0), Component.literal("+10 and a rocket").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		showNext();
	}

	private void tag(ServerLevel level, Vec3 at, Component text) {
		Display.TextDisplay d = label(level, at, text, 3.2f);
		if (d != null) tags.add(d);
	}

	/** A flat hoop (an updraft's). */
	private void flat(ServerLevel level, Vec3 c, double rad, BlockState s, int smooth, List<Display.BlockDisplay> into) {
		for (int i = 0; i < 12; i++) {
			double a0 = i * Math.PI / 6, a1 = (i + 1) * Math.PI / 6;
			Display.BlockDisplay d = bar(level, c, s, rod(new Vec3(Math.cos(a0) * rad, 0, Math.sin(a0) * rad), new Vec3(Math.cos(a1) * rad, 0, Math.sin(a1) * rad), 0.22f), smooth);
			if (into != null) into.add(d);
		}
	}

	private static Transformation crystalT(double turn) {
		return RuinKit.centred(new Vector3f(), 1.0f, 1.0f, 1.0f, new Quaternionf().rotationY((float) turn).rotateXYZ(0.62f, 0f, 0.79f));
	}

	private Ring nextRing() {
		for (Ring g : rings) if (!g.taken && !g.bonus) return g;
		return null;
	}

	/** Marks the ring to fly through now: its colour and the arrows. */
	private void showNext() {
		Ring n = solved ? null : nextRing();
		for (Ring g : rings) {
			boolean is = g == n;
			if (g.next == is) continue;
			g.next = is;
			recolour(g);
		}
		for (int i = 0; i < arrows.size(); i++) {
			Display.BlockDisplay d = arrows.get(i);
			if (n == null) {
				Props.set(d, rod(Vec3.ZERO, new Vec3(0, 0.01, 0), 0.01f), 0);
				continue;
			}
			Props.move(d, n.c);
			Vec3 u = plane(n.n)[0], tip = n.n.scale(i < 2 ? 0.9 : 2.6), tail = n.n.scale(i < 2 ? -0.5 : 1.2).add(u.scale(i % 2 == 0 ? 1.1 : -1.1));
			Props.set(d, rod(tail, tip, 0.2f), 0);
		}
	}

	private void recolour(Ring g) {
		for (int i = 0; i < g.bars.size(); i++) {
			Display.BlockDisplay d = g.bars.get(i);
			((BlockDisplayAccessor) d).aurapoints$setBlockState(ringBlock(g, i));
			Props.set(d, barT(g, i, 1), g.taken ? 8 : 0);
		}
		if (g.crystal != null) Props.set(g.crystal, g.taken || solved ? rod(Vec3.ZERO, new Vec3(0, 0.01, 0), 0.01f) : crystalT(g.turn * 3), 6);
	}

	/** The next ring breathes, the stars turn, the updrafts' hoops climb. */
	private void animate(long now) {
		if (now % 10 != 0) return;
		for (Ring g : rings) {
			if (g.taken) continue;
			if (g.next) for (int i = 0; i < g.bars.size(); i++) Props.set(g.bars.get(i), barT(g, i, now % 20 == 0 ? 1.12 : 1.0), 10);
			else if (g.bonus) {
				g.turn += 0.12;
				for (int i = 0; i < g.bars.size(); i++) Props.set(g.bars.get(i), barT(g, i, 1), 10);
				if (g.crystal != null) Props.set(g.crystal, crystalT(g.turn * 3), 10);
			}
		}
		for (int k = 0; k < climbers.size() && k < drafts.size(); k++) {
			Draft d = drafts.get(k);
			double u = (now % 60) / 50.0;
			Vec3 at = new Vec3(d.base.x, u > 1 ? d.base.y : d.base.y + (d.top - d.base.y) * u, d.base.z);
			for (Display.BlockDisplay b : climbers.get(k)) Props.move(b, at);
		}
	}

	/** Rockets for a ring flown through; with no room in the bags a push forward instead. */
	private void reward(ServerLevel level, ServerPlayer p, int n) {
		ItemStack rk = rocketStack(p, n);
		if (!p.getInventory().add(rk) && !rk.isEmpty()) {
			p.setDeltaMovement(p.getDeltaMovement().add(p.getLookAngle().scale(0.7)));
			p.hurtMarked = true;
		}
		p.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.3f);
	}

	private ItemStack rocketStack(ServerPlayer p, int n) {
		ItemStack rk = mark(new ItemStack(Items.FIREWORK_ROCKET, n), p, null);
		rk.set(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
		return rk;
	}

	private void clearCourse() {
		for (Display.BlockDisplay d : bars) Props.remove(d);
		bars.clear();
		arrows.clear();
		climbers.clear();
		for (Display.TextDisplay d : tags) RuinKit.drop(this, d);
		tags.clear();
		for (Ring g : rings) {
			g.bars.clear();
			g.crystal = null;
		}
	}

	@Override
	void remove() {
		clearCourse();
		super.remove();
	}

	// ---------------------------------------------------------------
	// Lending
	// ---------------------------------------------------------------

	static boolean isLent(ItemStack s) {
		if (s.isEmpty()) return false;
		CustomData cd = s.get(DataComponents.CUSTOM_DATA);
		return cd != null && cd.contains(LENT);
	}

	private static ItemStack mark(ItemStack s, ServerPlayer p, ItemStack orig) {
		CompoundTag tag = new CompoundTag();
		tag.putString(LENT, "rings");
		tag.putUUID("owner", p.getUUID());
		if (orig != null) tag.put("orig", orig.saveOptional(p.registryAccess()));
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	private static boolean hasLent(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (isLent(inv.getItem(i))) return true;
		return isLent(p.containerMenu.getCarried());
	}

	private static boolean wearsWings(ServerPlayer p) {
		ItemStack c = p.getItemBySlot(EquipmentSlot.CHEST);
		return c.is(Items.ELYTRA) && isLent(c);
	}

	/** Trial Wings on, the chest item kept (map + inside the wings), the first rockets. */
	private void lend(ServerPlayer p) {
		purgeLent(p);
		ItemStack orig = p.getItemBySlot(EquipmentSlot.CHEST).copy();
		ORIG.put(p.getUUID(), orig);
		ItemStack wings = mark(new ItemStack(Items.ELYTRA), p, orig);
		wings.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
		wings.set(DataComponents.CUSTOM_NAME, Component.literal("Trial Wings").withStyle(s -> s.withColor(rgb).withItalic(false)));
		p.setItemSlot(EquipmentSlot.CHEST, wings);
		p.getInventory().add(rocketStack(p, rockets()));
	}

	/**
	 * Takes every lent item (wings, rockets) from the inventory, armour and the cursor, and puts the chest item from
	 * before back: from {@link #ORIG}, or after a restart from the wings' own data (only the owner's).
	 */
	static void purgeLent(ServerPlayer p) {
		ItemStack fromWings = null;
		boolean found = false;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!isLent(s)) continue;
			if (fromWings == null) fromWings = stored(p, s);
			inv.setItem(i, ItemStack.EMPTY);
			found = true;
		}
		ItemStack c = p.containerMenu.getCarried();
		if (isLent(c)) {
			if (fromWings == null) fromWings = stored(p, c);
			p.containerMenu.setCarried(ItemStack.EMPTY);
			found = true;
		}
		ItemStack orig = ORIG.remove(p.getUUID());
		if (orig == null) orig = fromWings;
		if (found) p.stopFallFlying();
		if (orig == null || orig.isEmpty()) return;
		if (p.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) p.setItemSlot(EquipmentSlot.CHEST, orig);
		else if (!inv.add(orig)) p.drop(orig, false);
	}

	/** The chest item saved inside lent wings, if they are {@code p}'s own. */
	private static ItemStack stored(ServerPlayer p, ItemStack s) {
		if (!s.is(Items.ELYTRA)) return null;
		CompoundTag tag = s.get(DataComponents.CUSTOM_DATA).copyTag();
		if (!tag.hasUUID("owner") || !tag.getUUID("owner").equals(p.getUUID()) || !tag.contains("orig")) return null;
		return ItemStack.parseOptional(p.registryAccess(), tag.getCompound("orig"));
	}

	// ---------------------------------------------------------------
	// Flights
	// ---------------------------------------------------------------

	@Override
	boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
		long now = level.getGameTime();
		if (pos.equals(returnStone())) {
			if (flyers.containsKey(p.getUUID())) endFlight(p, false);
			if (flyers.isEmpty() && attempt) endAttempt();
			goHome(p);
			return true;
		}
		boolean cannon = Math.abs(af(Vec3.atCenterOf(pos)) - CANNON_F) <= 1.5 && Math.abs(al(Vec3.atCenterOf(pos))) <= 1.5 && pos.getY() == Rifts.FLOOR;
		if (!pos.equals(launchStone()) && !cannon) return false;
		if (solved) {
			p.displayClientMessage(Component.literal("The course is won - the shard waits on the far pad.").withStyle(ChatFormatting.GRAY), true);
			return true;
		}
		if (flyers.containsKey(p.getUUID())) return true;
		if (!attempt) {
			attempt = true;
			score = 0;
			targetCalled = false;
			target = computeTarget();
			attemptEnd = now + seconds() * 20L;
			nextGust = now + 60;
			for (Ring g : rings) {
				g.taken = false;
				recolour(g);
			}
			showNext();
		}
		lend(p);
		flyers.put(p.getUUID(), new Flyer(now));
		Vec3 c = Vec3.atBottomCenterOf(arena(CANNON_F, 0, 1));
		place(p, c, forwardYaw(), -20f);
		p.fallDistance = 0;
		p.setDeltaMovement(fx * 0.35, 3.2, fz * 0.35);
		p.hurtMarked = true;
		level.playSound(null, c.x, c.y, c.z, AuraSounds.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.6f, 0.7f);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.2f, 0.6f);
		level.sendParticles(th.lift, c.x, c.y + 0.5, c.z, 40, 0.8, 1.5, 0.8, 0.2);
		AuraFx.title(p, Component.literal("Fly!").withStyle(s -> s.withColor(rgb).withBold(true)),
				Component.literal("score " + target + " and land on the far pad - " + seconds() + " s").withStyle(ChatFormatting.GRAY));
		return true;
	}

	/** Takes the wings back (and the chest item returns); {@code home}: back to the launch terrace. */
	private void endFlight(ServerPlayer p, boolean home) {
		flyers.remove(p.getUUID());
		purgeLent(p);
		p.fallDistance = 0;
		if (home) goHome(p);
	}

	private void goHome(ServerPlayer p) {
		Vec3 h = home();
		place(p, h, forwardYaw(), 0f);
		p.setDeltaMovement(Vec3.ZERO);
		p.hurtMarked = true;
		p.fallDistance = 0;
	}

	/** Moves {@code p} within the rift; fake players (dev tests) have no connection, so teleportTo can't move them. */
	private static void place(ServerPlayer p, Vec3 to, float yaw, float pitch) {
		if (com.toast.aurapoints.shim.Platform.get().isFakePlayer(p)) {
			p.moveTo(to.x, to.y, to.z, yaw, pitch);
			return;
		}
		p.teleportTo((ServerLevel) p.level(), to.x, to.y, to.z, yaw, pitch);
	}

	/** The attempt is over: score back to 0, rings back to their colour. */
	private void endAttempt() {
		attempt = false;
		score = 0;
		gusts.clear();
		for (Ring g : rings) {
			g.taken = false;
			recolour(g);
		}
		showNext();
	}

	private void endAll(ServerLevel level, boolean home) {
		for (UUID id : new ArrayList<>(flyers.keySet())) {
			ServerPlayer p = find(level, id);
			flyers.remove(id);
			if (p != null) endFlight(p, home);
		}
	}

	private static ServerPlayer find(ServerLevel level, UUID id) {
		ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
		return p != null ? p : Rifts.TEST_PLAYERS.get(id);
	}

	@Override
	void release(ServerPlayer p) {
		if (flyers.containsKey(p.getUUID())) endFlight(p, false);
		else purgeLent(p);
		if (flyers.isEmpty() && attempt) endAttempt();
	}

	/** A flyer fell, left the course or crashed: wings back, home, a strike. */
	private void crash(ServerLevel level, ServerPlayer p, List<ServerPlayer> here, long now, String why) {
		Vec3 at = p.position();
		endFlight(p, true);
		if (flyers.isEmpty()) endAttempt();
		hudMute = now + 40;
		p.sendSystemMessage(Component.literal("✦ " + why + " - the wings are taken back.").withStyle(ChatFormatting.RED));
		strike(level, p, at.y < Rifts.FLOOR - 20 ? home() : at, here);
	}

	// ---------------------------------------------------------------
	// Tick
	// ---------------------------------------------------------------

	@Override
	void tick(ServerLevel level, List<ServerPlayer> here, long now) {
		ambient(level, here, now);
		if (!here.isEmpty() && !solved) animate(now);
		// flyers that left the arena for the antechamber, or went offline
		for (UUID id : new ArrayList<>(flyers.keySet())) {
			boolean in = false;
			for (ServerPlayer p : here) if (p.getUUID().equals(id)) in = true;
			if (in) continue;
			ServerPlayer p = find(level, id);
			if (p != null) endFlight(p, false);
			else flyers.remove(id);
			if (flyers.isEmpty() && attempt) endAttempt();
		}
		for (ServerPlayer p : here) {
			Flyer fl = flyers.get(p.getUUID());
			if (fl != null) fly(level, p, fl, here, now);
		}
		if (attempt && !solved && now >= attemptEnd) {
			endAll(level, true);
			endAttempt();
			hudMute = now + 40;
			for (ServerPlayer p : here) AuraFx.title(p, Component.literal("Time's up").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal("back to the launch").withStyle(ChatFormatting.GRAY));
			strike(level, null, home(), here);
		}
		if (now % 5 == 0 && now >= hudMute) hud(here, now);
	}

	private void fly(ServerLevel level, ServerPlayer p, Flyer fl, List<ServerPlayer> here, long now) {
		if (!hasLent(p)) {
			// the wings are gone (purged elsewhere, or dropped): this flight is over, the chest item comes back
			flyers.remove(p.getUUID());
			purgeLent(p);
			if (flyers.isEmpty() && attempt) endAttempt();
			return;
		}
		Vec3 pos = p.position(), body = pos.add(0, BODY, 0);
		p.fallDistance = 0;
		fl.vel = fl.last == null ? Vec3.ZERO : body.subtract(fl.last);
		if (!p.onGround()) fl.airborne = true;
		long age = now - fl.since;
		// glide on its own at the top of the launch, and again whenever the wings fold in mid-air
		if (fl.airborne && !p.onGround() && !p.isFallFlying() && wearsWings(p) && age >= 6 && fl.vel.y < 0.05) p.startFallFlying();
		if (fl.last != null && !solved) rings(level, p, fl.last, body, here, now);
		fl.last = body;
		double dy = pos.y - Rifts.FLOOR, f = af(pos), l = al(pos);
		if (solved) {
			if (dy < -25) endFlight(p, true);
			else if (p.onGround() && age > 20) endFlight(p, false);
			return;
		}
		if (dy < -25 || (p.onGround() && fl.airborne && dy < -6)) {
			crash(level, p, here, now, "You fell below the course");
			return;
		}
		if (Math.abs(f) > HALF - 4 || Math.abs(l) > HALF - 4 || dy > UP - 3) {
			crash(level, p, here, now, "You left the course");
			return;
		}
		// soft walls: the wind turns you back near the edges and the ceiling
		Vec3 push = Vec3.ZERO;
		if (Math.abs(f) > HALF - 14) push = push.add(wdir(new Vec3(-Math.signum(f) * 0.14, 0, 0)));
		if (Math.abs(l) > HALF - 24) push = push.add(wdir(new Vec3(0, -Math.signum(l) * 0.14, 0)));
		if (dy > UP - 20) push = push.add(0, -0.14, 0);
		for (Draft d : drafts) {
			if (Math.hypot(pos.x - d.base.x, pos.z - d.base.z) <= 3.6 && pos.y >= d.base.y - 2 && pos.y <= d.top) {
				push = push.add(0, Math.min(0.3, 1.15 - fl.vel.y), 0);
				if (now % 8 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.25f, 1.6f);
			}
		}
		if (push.lengthSqr() > 1.0e-6) {
			p.setDeltaMovement(fl.vel.add(push));
			p.hurtMarked = true;
		}
		if (onPad(pos) && attempt) {
			if (score >= target) {
				win(level, here, now);
				return;
			}
			if (now % 40 == 0) p.displayClientMessage(Component.literal("Score " + score + "/" + target + " - not enough yet: an updraft takes you back up").withStyle(ChatFormatting.GOLD), true);
		}
		if (p.onGround() && fl.airborne && age > 20 && nearHome(pos)) {
			endFlight(p, false);
			p.displayClientMessage(Component.literal("Flight ended").withStyle(ChatFormatting.GRAY), true);
			if (flyers.isEmpty() && attempt) endAttempt();
		}
	}

	/** Rings the segment {@code a -> b} went through. */
	private void rings(ServerLevel level, ServerPlayer p, Vec3 from, Vec3 to, List<ServerPlayer> here, long now) {
		if (!attempt || from.distanceToSqr(to) > 144) return;
		for (Ring g : rings) {
			if (g.taken) continue;
			double d0 = g.n.dot(from.subtract(g.c)), d1 = g.n.dot(to.subtract(g.c));
			Vec3 hit;
			if (Math.abs(d1) < 0.3) hit = to;
			else if ((d0 < 0) != (d1 < 0)) hit = from.add(to.subtract(from).scale(d0 / (d0 - d1)));
			else continue;
			if (hit.distanceTo(g.c) > g.r + 0.35) continue;
			g.taken = true;
			score += g.pts;
			recolour(g);
			showNext();
			reward(level, p, g.bonus ? 2 : 1);
			int n = taken();
			Vec3 ear = p.position();
			if (g.bonus) {
				RiftSfx.play(level, ear, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.6f, 0.9f);
				for (int k = 0; k < 3; k++) RiftSfx.play(level, ear, SoundEvents.NOTE_BLOCK_HARP, 1.6f, RiftSfx.note(k * 2 + 1));
				level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, Blocks.AMETHYST_BLOCK.defaultBlockState()), g.c.x, g.c.y, g.c.z, 40, 0.6, 0.6, 0.6, 0.2);
			} else {
				RiftSfx.play(level, ear, SoundEvents.NOTE_BLOCK_CHIME, 1.8f, RiftSfx.note(n - 1));
				RiftSfx.play(level, ear, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
			}
			RiftSfx.play(level, ear, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.35f, 1.7f);
			level.sendParticles(ParticleTypes.FLASH, g.c.x, g.c.y, g.c.z, 1, 0, 0, 0, 0);
			level.sendParticles(AuraFx.dust(g.bonus ? 0xE58CFF : 0x7CFF6B, 1.8f), g.c.x, g.c.y, g.c.z, 40, g.r * 0.5, g.r * 0.5, g.r * 0.5, 0);
			if (g.bonus) p.displayClientMessage(Component.literal("Bonus ring +" + g.pts + ", two rockets").withStyle(ChatFormatting.LIGHT_PURPLE), true);
			if (score >= target && !targetCalled) {
				targetCalled = true;
				for (ServerPlayer q : here) AuraFx.title(q, Component.literal("Target reached").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), Component.literal("land on the far pad").withStyle(ChatFormatting.GRAY));
				level.playSound(null, g.c.x, g.c.y, g.c.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.3f);
			}
		}
	}

	private int taken() {
		int n = 0;
		for (Ring g : rings) if (g.taken && !g.bonus) n++;
		return n;
	}

	private void win(ServerLevel level, List<ServerPlayer> here, long now) {
		attempt = false;
		gusts.clear();
		for (ServerPlayer q : here) q.displayClientMessage(Component.literal("Course complete: " + score + " points").withStyle(ChatFormatting.GREEN), true);
		hudMute = now + 100;
		solve(level, plinth());
		for (Ring g : rings) recolour(g);
		showNext();
		// grounded flyers give the wings back at once, the rest when they land
		for (ServerPlayer q : here) if (flyers.containsKey(q.getUUID()) && q.onGround()) endFlight(q, false);
	}

	private void hud(List<ServerPlayer> here, long now) {
		if (solved) return;
		if (attempt) {
			int main = 0;
			for (Ring g : rings) if (!g.bonus) main++;
			long left = Math.max(0, attemptEnd - now) / 20;
			String text = "Rings " + taken() + "/" + main + "  Score " + score + "/" + target + "  Time " + left / 60 + ":" + String.format("%02d", left % 60);
			actionBar(here, text, score >= target ? ChatFormatting.GREEN : left <= 10 ? ChatFormatting.RED : ChatFormatting.AQUA);
		} else if (now % 20 == 0) {
			List<ServerPlayer> near = new ArrayList<>();
			for (ServerPlayer p : here) if (nearHome(p.position())) near.add(p);
			actionBar(near, "Right-click the launch stone to fly - score " + computeTarget() + " in " + seconds() + " s", ChatFormatting.GRAY);
		}
	}

	/** Updraft and ring particles, the pad's beacon, storm lightning, hazard gusts, hints, lost wings. */
	private void ambient(ServerLevel level, List<ServerPlayer> here, long now) {
		if (here.isEmpty()) return;
		if (now % 4 == 0) {
			for (Draft d : drafts) {
				for (int k = 0; k < 3; k++) {
					double y = d.base.y + level.random.nextDouble() * (d.top - d.base.y);
					double ang = level.random.nextDouble() * Math.PI * 2, rr = level.random.nextDouble() * 3;
					for (ServerPlayer p : here)
						level.sendParticles(p, th.lift, true, d.base.x + Math.cos(ang) * rr, y, d.base.z + Math.sin(ang) * rr, 0, 0, 1, 0, 0.4);
				}
			}
		}
		if (now % 10 == 0 && !solved) {
			// the next three rings sparkle in their middle; bonus rings in purple
			int shown = 0;
			for (Ring g : rings) {
				if (g.taken) continue;
				if (!g.bonus && shown++ >= 3) continue;
				for (ServerPlayer p : here) level.sendParticles(p, g.bonus ? AuraFx.dust(0xB070FF, 1.6f) : ParticleTypes.END_ROD, true, g.c.x, g.c.y, g.c.z, 2, 0.2, 0.2, 0.2, 0.01);
			}
			Vec3 pc = Vec3.atBottomCenterOf(arena(PAD_F, 0, 3));
			for (ServerPlayer p : here) for (int k = 0; k < 6; k++) level.sendParticles(p, AuraFx.dust(rgb, 2f), true, pc.x, pc.y + k * 4, pc.z, 1, 0.1, 1.5, 0.1, 0);
		}
		if (th.style == 2 && now % 30 == 0 && level.random.nextInt(5) == 0 && !spireTops.isEmpty()) bolt(level, Vec3.atBottomCenterOf(spireTops.get(level.random.nextInt(spireTops.size())).above()));
		if (hazard && attempt && !flyers.isEmpty() && now >= nextGust) {
			nextGust = now + 40 + level.random.nextInt(40) - tier * 5;
			List<UUID> ids = new ArrayList<>(flyers.keySet());
			Flyer fl = flyers.get(ids.get(level.random.nextInt(ids.size())));
			if (fl.last != null) {
				Vec3 at = fl.last.add(fl.vel.scale(14)).add(level.random.nextGaussian() * 2, level.random.nextGaussian(), level.random.nextGaussian() * 2);
				gusts.add(new Gust(at, now + 12));
				level.sendParticles(th.style == 2 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.WHITE_ASH, at.x, at.y, at.z, 30, 1.5, 1.5, 1.5, 0.05);
			}
		}
		for (int i = gusts.size() - 1; i >= 0; i--) {
			Gust g = gusts.get(i);
			if (now < g.due) continue;
			gusts.remove(i);
			gust(level, g.at, here);
		}
		if (hintsAllowed() && now % 8 == 0) {
			Ring next = null;
			for (Ring g : rings) if (!g.taken && !g.bonus) {
				next = g;
				break;
			}
			if (next != null) for (ServerPlayer p : here) {
				if (!flyers.containsKey(p.getUUID())) continue;
				Vec3 from = p.position().add(0, 1, 0), d = next.c.subtract(from);
				double len = d.length();
				for (int k = 1; k <= 12 && k * 2 < len; k++) {
					Vec3 q = from.add(d.scale(k * 2 / len));
					level.sendParticles(p, AuraFx.dust(rgb, 0.7f), false, q.x, q.y, q.z, 1, 0, 0, 0, 0);
				}
			}
		}
		if (now % 20 == 0) {
			Vec3 lo = w(-HALF, -HALF, -arenaDown()), hi = w(HALF, HALF, UP);
			for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(lo, hi), e -> isLent(e.getItem()))) e.discard();
		}
	}

	private void bolt(ServerLevel level, Vec3 at) {
		LightningBolt b = EntityType.LIGHTNING_BOLT.create(level);
		if (b == null) return;
		b.moveTo(at.x, at.y, at.z);
		b.setVisualOnly(true);
		level.addFreshEntity(b);
	}

	/** Strike 2's hazard going off at {@code at}: the aura's blast (looks only) and a shove for flyers close by. */
	private void gust(ServerLevel level, Vec3 at, List<ServerPlayer> here) {
		switch (th.style) {
			case 2 -> bolt(level, at);
			case 0 -> {
				level.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 1, 0, 0, 0, 0);
				level.playSound(null, at.x, at.y, at.z, AuraSounds.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.HOSTILE, 1.6f, 0.7f);
			}
			default -> {
				level.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
				level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 40, 0.5, 0.5, 0.5, 0.3);
				level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 2f, 1.6f);
			}
		}
		for (ServerPlayer p : here) {
			Flyer fl = flyers.get(p.getUUID());
			if (fl == null) continue;
			Vec3 d = p.position().subtract(at);
			double dist = d.length();
			if (dist > 7) continue;
			Vec3 away = new Vec3(d.x, 0, d.z);
			away = away.lengthSqr() < 1.0e-4 ? new Vec3(fx, 0, fz) : away.normalize();
			p.setDeltaMovement(fl.vel.add(away.scale(1.0 - dist / 10)).add(0, -0.25, 0));
			p.hurtMarked = true;
		}
	}

	// ---------------------------------------------------------------
	// Strikes
	// ---------------------------------------------------------------

	@Override
	void onReact(ServerLevel level, Vec3 at) {
		Vec3 h = home();
		level.playSound(null, h.x, h.y + 10, h.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 0.8f, 1.4f);
		level.sendParticles(th.lift, h.x, h.y + 3, h.z, 40, 4, 2, 4, 0.05);
	}

	/** Strike 2: no mobs (they'd fall off the sky), the course turns rough: hazards on every attempt from now on. */
	@Override
	void onHazard(ServerLevel level, Vec3 at) {
		hazard = true;
		gust(level, at, List.of());
		for (UUID id : inside) {
			ServerPlayer p = find(level, id);
			if (p != null) p.sendSystemMessage(Component.literal("✦ The sky turns rough: " + (th.style == 2 ? "lightning" : th.style == 0 ? "gusts" : "flares") + " now strike along the course.").withStyle(ChatFormatting.GOLD));
		}
	}

	@Override
	void onReset(ServerLevel level, RandomSource r) {
		endAll(level, true);
		endAttempt();
		hazard = false;
		clearCourse();
		route(r, true);
		spawnCourse(level);
	}

	// ---------------------------------------------------------------
	// Intro
	// ---------------------------------------------------------------

	@Override
	RiftCam intro() {
		if (path.size() < 8) return null;
		RiftCam cam = RiftCam.shot(rgb).title(name(), run.difficulty.label).skippable(true);
		Vec3 start = w(TERRACE_F - 14, 0, 14);
		cam.key(0, start, w(CANNON_F, 0, 20));
		int keys = 6;
		for (int k = 1; k <= keys; k++) {
			int i = Math.min(path.size() - 1, (int) ((path.size() - 1) * (k - 0.5) / keys));
			int j = Math.min(path.size() - 1, i + 14);
			Vec3 at = path.get(i), look = path.get(j);
			cam.key(k * 28, at.add(0, 5, 0).subtract(look.subtract(at).normalize().scale(6)), look);
		}
		Vec3 pad = Vec3.atCenterOf(arena(PAD_F, 0, 2));
		cam.key((keys + 1) * 28, w(PAD_F - 18, 10, 14), pad);
		return cam;
	}

	// ---------------------------------------------------------------
	// Dev test
	// ---------------------------------------------------------------

	@Override
	void devPlay(net.minecraft.server.MinecraftServer server, ServerLevel level, ServerPlayer fake, java.util.function.Consumer<String> log, Runnable done) {
		long[] now = {level.getGameTime() + 1};
		Runnable tick = () -> tick(level, List.of(fake), now[0]++);
		fake.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		int main = ringCount();
		log.accept("rings: " + main + " rings + " + bonusCount() + " bonus, " + drafts.size() + " updrafts, " + obstacles.size() + " islands, rings built " + (rings.size() == main + bonusCount()) + " (must be true)");
		devRoutes(log);
		// 1: launch, two rings, then a fall
		use(level, fake, launchStone());
		log.accept("rings: launch -> wings on " + wearsWings(fake) + " (must be true), chest kept " + ORIG.containsKey(fake.getUUID()) + " (must be true), attempt " + attempt + " (must be true), target " + target);
		fake.setOnGround(false);
		tick.run();
		int expect = 0;
		for (int k = 0; k < 2; k++) {
			through(fake, rings.get(k), tick);
			expect += 10;
			log.accept("rings: through ring " + (k + 1) + " -> score " + score + " (must be " + expect + "), rockets " + devRockets(fake) + " (must be " + (rockets() + k + 1) + "), next ring marked " + (rings.get(k + 1).next && !rings.get(k).next) + " (must be true)");
		}
		Vec3 low = w(0, 0, -30);
		fake.moveTo(low.x, low.y, low.z);
		tick.run();
		log.accept("rings: fall -> strikes " + strikes + " (must be 1), wings gone " + !hasLent(fake) + " (must be true), chest back "
				+ fake.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE) + " (must be true), attempt " + attempt + " (must be false), score " + score + " (must be 0)");
		// 2: a restart in mid-flight: the map is gone, the wings still know the chest item
		use(level, fake, launchStone());
		ORIG.clear();
		purgeLent(fake);
		log.accept("rings: restore from the wings' own data -> chest back " + fake.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE) + " (must be true), wings gone " + !hasLent(fake) + " (must be true)");
		tick.run();
		log.accept("rings: purged flight dropped -> flying " + flyers.containsKey(fake.getUUID()) + " (must be false), strikes " + strikes + " (must be 1)");
		// 3: the full course, bonus rings too, and the landing
		use(level, fake, launchStone());
		fake.setOnGround(false);
		tick.run();
		log.accept("rings: relaunch -> on the cannon " + (Math.abs(af(fake.position()) - CANNON_F) < 2) + " (must be true), flying " + flyers.containsKey(fake.getUUID())
				+ " (must be true), attempt " + attempt + " (must be true), strikes " + strikes + " (must be 1)");
		expect = 0;
		for (int k = 0; k < rings.size(); k++) {
			Ring g = rings.get(k);
			through(fake, g, tick);
			expect += g.pts;
			if (k < 3 || k >= rings.size() - 2) log.accept("rings: through " + (g.bonus ? "bonus " : "") + "ring " + (k + 1) + " -> score " + score + " (must be " + expect + ")");
		}
		log.accept("rings: all rings -> score " + score + " (must be " + expect + "), target met " + (score >= target) + " (must be true)");
		for (int k = 0; k < 25; k++) tick.run();
		Vec3 pad = Vec3.atBottomCenterOf(arena(PAD_F - 3, 2, 1));
		fake.moveTo(pad.x, pad.y, pad.z);
		fake.setOnGround(true);
		tick.run();
		log.accept("rings: land on the pad -> solved " + solved + " (must be true), wings gone " + !hasLent(fake) + " (must be true), chest back "
				+ fake.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE) + " (must be true)");
		fake.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
		done.run();
	}

	private int devAt;

	/** Dev: hangs {@code p} in front of the next ring, a bonus ring and an updraft in turn, looking at it. */
	void devLook(ServerPlayer p) {
		Ring bonus = null;
		for (Ring g : rings) if (g.bonus) bonus = g;
		Vec3 at = devAt == 0 ? rings.get(0).c : devAt == 1 && bonus != null ? bonus.c : devAt == 2 ? drafts.get(0).base.add(0, 8, 0) : rings.get(2).c;
		Vec3 n = devAt == 0 ? rings.get(0).n : devAt == 1 && bonus != null ? bonus.n : new Vec3(1, 0, 0);
		devAt++;
		Vec3 from = at.subtract(n.scale(13)).add(0, 2, 0), d = at.subtract(from);
		p.teleportTo((ServerLevel) p.level(), from.x, from.y - 1.6, from.z, (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
		p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.LEVITATION, 60, 255, false, false));
	}

	private static int devRockets(ServerPlayer p) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (isLent(p.getInventory().getItem(i)) && p.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) n += p.getInventory().getItem(i).getCount();
		return n;
	}

	/** Dev: 200 routes: every ring well inside the sky and above the line you must not fall under. */
	private void devRoutes(java.util.function.Consumer<String> log) {
		List<Ring> keepR = new ArrayList<>(rings);
		List<Draft> keepD = new ArrayList<>(drafts);
		List<Vec3> keepP = new ArrayList<>(path), keepF = new ArrayList<>(framePath);
		int out = 0;
		double minGap = 1e9, sumGap = 0, gaps = 0, top = 0;
		for (int s = 0; s < 200; s++) {
			route(RandomSource.create(31L * s + 5), false);
			Ring prev = null;
			for (Ring g : rings) {
				double f = af(g.c), l = al(g.c), dy = g.c.y - Rifts.FLOOR;
				if (Math.abs(f) + g.r > HALF - 24 && !(f > PAD_F - 30) || Math.abs(l) + g.r > HALF - 24 || dy < 1 || dy > UP - 20) out++;
				top = Math.max(top, dy);
				if (!g.bonus && prev != null) {
					minGap = Math.min(minGap, g.c.distanceTo(prev.c));
					sumGap += g.c.distanceTo(prev.c);
					gaps++;
				}
				if (!g.bonus) prev = g;
			}
			for (Draft d : drafts) if (d.top > Rifts.FLOOR + UP - 20) out++;
		}
		log.accept("rings: 200 routes, out of the sky " + out + " (must be 0), gap average " + (int) (sumGap / gaps) + " least " + (int) minGap + ", highest ring " + (int) top);
		rings.clear();
		rings.addAll(keepR);
		drafts.clear();
		drafts.addAll(keepD);
		path.clear();
		path.addAll(keepP);
		framePath.clear();
		framePath.addAll(keepF);
	}

	/** Dev: moves {@code p} through ring {@code g} along its normal, a tick on each side. */
	private static void through(ServerPlayer p, Ring g, Runnable tick) {
		Vec3 a = g.c.subtract(g.n.scale(1.5)).subtract(0, BODY, 0), b = g.c.add(g.n.scale(1.5)).subtract(0, BODY, 0);
		p.moveTo(a.x, a.y, a.z);
		tick.run();
		p.moveTo(b.x, b.y, b.z);
		tick.run();
	}
}
