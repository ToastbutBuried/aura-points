package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraSounds;
import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.Props;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weeping Statues (5.49.0): a ruined crypt garden. Stone statues step toward the players, but only while no one looks at
 * them (a player's view cone plus a clear line of sight; a glance into one of the wall mirrors that shows the statue
 * counts too). Four sigils wait on plinths in the mausoleums around the wall: lift one (a hold-and-release check while it rises),
 * carry it to the altar in the middle and set it in. Watchers hold their gaze while carriers walk. A statue that reaches
 * you takes the sigil you carry back to its plinth = a strike (on Hardcore the touch is lethal). Extreme+: the lights
 * gutter now and then, and in the dark the statues move whoever looks.
 */
final class TrialStatues extends Trials.Trial {

	static final int R = 31;
	private static final int[] STEP_EVERY = {30, 24, 18, 15, 12}, LIFT = {40, 60, 70, 80, 90};
	private static final double[] STEP_LEN = {1.2, 1.4, 1.6, 1.8, 2.0};
	private static final String[] GLYPHS = {"ᛟ", "ᛉ", "ᛞ", "ᛗ"};
	static final double SEE_DOT = 0.72, TOUCH = 1.3;

	/** A mausoleum: its middle (f, l) and the way out of it, toward the garden (nf, nl). */
	record Alcove(int f, int l, int nf, int nl) {
		int pf() {
			return f - nf * 2;
		}

		int pl() {
			return l - nl * 2;
		}
	}

	/** A wall mirror: its face's middle and the way it faces (world). */
	record Mirror(Vec3 c, Vec3 n) {
	}

	static final class Statue {
		double f, l;
		int g;
		float yaw;
		final List<Display.BlockDisplay> parts = new ArrayList<>();
		boolean reaching, decoy;
		long nextStep, stunUntil;
		int stuck;
	}

	static final class Sigil {
		final int n, rgb;
		int alcove;
		/** 0 on its plinth, 1 being lifted, 2 carried, 3 set in the altar. */
		int state;
		UUID carrier;
		Vec3 liftFrom;
		long liftDone;
		/** Lifted by a skill check (else by standing still until {@link #liftDone}). */
		boolean check;
		Display.BlockDisplay crystal;
		Display.TextDisplay tag;

		Sigil(int n, int rgb) {
			this.n = n;
			this.rgb = rgb;
		}
	}

	final List<Alcove> alcoves = new ArrayList<>();
	final List<Mirror> mirrors = new ArrayList<>();
	final List<Statue> statues = new ArrayList<>();
	final List<Sigil> sigils = new ArrayList<>();
	private final List<BlockPos> candles = new ArrayList<>();
	private final Map<UUID, Long> immune = new HashMap<>();
	private boolean[][] taken;
	/** Dread (ticks of staring) per player, and until when a player's eyes have given (their gaze holds nothing). */
	private final Map<UUID, Integer> dread = new HashMap<>();
	private final Map<UUID, Long> blinkUntil = new HashMap<>();
	long moveFrom = Long.MAX_VALUE;
	private long nextFlicker, flickerUntil, nextHint;
	private int baseStatues, extraStatues;
	private boolean glowing;
	private RandomSource rnd;

	TrialStatues(Rifts.Run run, int index) {
		super(run, index, Trials.Kind.STATUES);
	}

	@Override
	String name() {
		return "Weeping Statues";
	}

	@Override
	String hint() {
		return "The statues only move while no one looks at them - a glance in a wall mirror counts. But no one can stare for long: dread builds, and when your eyes give the stone moves. "
				+ "Lift the four sigils in the mausoleums (hold right-click, let go in the green) and set them in the altar. A touch takes your sigil.";
	}

	@Override
	int arenaHalf() {
		return 42;
	}

	// ---------------------------------------------------------------
	// Frame helpers
	// ---------------------------------------------------------------

	Vec3 wpos(double f, double l, double y) {
		return new Vec3(a.getX() + 0.5 + fx * f + lx * l, y, a.getZ() + 0.5 + fz * f + lz * l);
	}

	Vec3 statuePos(Statue s) {
		return wpos(s.f, s.l, Rifts.FLOOR + 1 + s.g);
	}

	BlockPos plinthPos(Alcove al) {
		return arena(al.pf(), al.pl(), 1);
	}

	BlockPos socketPos(int n) {
		int[][] o = {{2, 0}, {0, 2}, {-2, 0}, {0, -2}};
		return arena(o[n][0], o[n][1], 3);
	}

	private boolean free(ServerLevel level, BlockPos p) {
		return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
	}

	/** The ground height (dy of the block stood on) at a column within a step of {@code cur}; -99 = no footing. */
	int ground(ServerLevel level, int f, int l, int cur) {
		if (Math.abs(f) > R - 1 || Math.abs(l) > R - 1) return -99;
		for (int g = Math.min(3, cur + 1); g >= Math.max(0, cur - 2); g--) {
			BlockPos p = arena(f, l, g);
			if (!free(level, p) && free(level, p.above()) && free(level, p.above(2))) return g;
		}
		return -99;
	}

	private static float yawTo(Vec3 from, Vec3 to) {
		return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
	}

	private boolean clear(ServerLevel level, Vec3 a, Vec3 b, ServerPlayer p) {
		return level.clip(new ClipContext(a, b, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
	}

	// ---------------------------------------------------------------
	// Building
	// ---------------------------------------------------------------

	private boolean isTaken(int f, int l) {
		return Math.abs(f) > R || Math.abs(l) > R || taken[f + R][l + R];
	}

	private void take(int f, int l, int pad) {
		for (int df = -pad; df <= pad; df++)
			for (int dl = -pad; dl <= pad; dl++)
				if (Math.abs(f + df) <= R && Math.abs(l + dl) <= R) taken[f + df + R][l + dl + R] = true;
	}

	@Override
	void build(ServerLevel level, RandomSource r) {
		this.rnd = r;
		taken = new boolean[2 * R + 1][2 * R + 1];
		// the garden floor: clustered floor and base, an accent path from the gate to the altar ring
		for (int f = -R; f <= R; f++) {
			for (int l = -R; l <= R; l++) {
				double d = Math.sqrt(f * f + l * l);
				Block b = RuinKit.hash(Math.floorDiv(f, 3), Math.floorDiv(l, 3), 11) < 28 ? pal.base() : pal.floor();
				if (f < -8 && Math.abs(l) <= 1) b = pal.accent();
				if (d >= 8 && d < 9) b = pal.accent();
				if (RuinKit.hash(f, l, 12) < 4) b = pal.edge();
				set(level, f, l, 0, b);
				RuinKit.underside(this, level, f, l, 1 - Math.max(Math.abs(f), Math.abs(l)) / (R + 2.0), 12);
			}
		}
		causeway(level);
		perimeter(level);
		alcoves.clear();
		int[][] spots = {{28, -16, -1, 0}, {28, 0, -1, 0}, {28, 16, -1, 0}, {-12, 28, 0, -1}, {10, 28, 0, -1}, {-12, -28, 0, 1}, {10, -28, 0, 1}, {-28, 16, 1, 0}, {-28, -16, 1, 0}};
		for (int[] s : spots) {
			Alcove al = new Alcove(s[0], s[1], s[2], s[3]);
			alcoves.add(al);
			alcove(level, al);
		}
		wallMirrors(level);
		dais(level);
		field(level, r);
		// floating debris and broken masonry around the garden
		for (int n = 0; n < 16; n++) {
			double ang = r.nextDouble() * Math.PI * 2, dist = 35 + r.nextDouble() * 6;
			int f = (int) Math.round(Math.cos(ang) * dist), l = (int) Math.round(Math.sin(ang) * dist);
			if (f < -31 && Math.abs(l) < 7) continue;
			RuinKit.debris(this, level, r, f, l, -14 + r.nextInt(28), 1 + r.nextInt(3));
		}
		newLayout(level, r);
	}

	private void causeway(ServerLevel level) {
		for (int f = -41; f <= -R - 1; f++) {
			for (int l = -2; l <= 2; l++) {
				set(level, f, l, 0, l == 0 ? pal.accent() : pal.floor());
				set(level, f, l, -1, pal.base());
			}
			if (Math.floorMod(f, 4) == 0) for (int l : new int[]{-3, 3}) {
				set(level, f, l, 0, pal.edge());
				set(level, f, l, 1, pal.edge());
				set(level, f, l, 2, RuinKit.candle(3, true));
				candles.add(arena(f, l, 2));
			}
		}
	}

	/** The broken garden wall, pilasters with lights, the gate toward the way back. */
	private void perimeter(ServerLevel level) {
		for (int k = -R; k <= R; k++) {
			for (int[] w : new int[][]{{R, k}, {-R, k}, {k, R}, {k, -R}}) {
				int f = w[0], l = w[1];
				boolean gate = f == -R && Math.abs(l) <= 2;
				int along = Math.abs(f) == R ? l : f;
				boolean pilaster = Math.floorMod(along, 6) == 0;
				int top = 6 - (RuinKit.hash(f, l, 21) < 25 ? 1 + RuinKit.hash(f, l, 22) % 3 : 0);
				if (pilaster) top = 7;
				for (int dy = 1; dy <= top; dy++) {
					if (gate && dy <= 4) {
						set(level, f, l, dy, Blocks.AIR);
						continue;
					}
					set(level, f, l, dy, dy <= 1 || pilaster ? pal.base() : pal.edge());
				}
				if (pilaster && !gate) set(level, f, l, top + 1, pal.light());
				else if (!gate && top >= 6) set(level, f, l, top + 1, pal.accent());
			}
		}
		for (int dy = 1; dy <= 6; dy++) for (int l : new int[]{-3, 3}) set(level, -R, l, dy, pal.accent());
		for (int l = -3; l <= 3; l++) set(level, -R, l, 5, pal.accent());
		set(level, -R, 0, 6, pal.light());
		RuinKit.banner(this, level, -R + 1, -4, 4, 1, 0);
		RuinKit.banner(this, level, -R + 1, 4, 4, 1, 0);
	}

	private int[] alc(Alcove al, int d, int s) {
		return new int[]{al.f() + al.nf() * d - al.nl() * s, al.l() + al.nl() * d + al.nf() * s};
	}

	/** A small mausoleum against the wall: walls, a roof with a ridge, a dark doorway, the plinth at the back, candles and webs. */
	private void alcove(ServerLevel level, Alcove al) {
		for (int d = -3; d <= 3; d++) {
			for (int s = -3; s <= 3; s++) {
				int[] p = alc(al, d, s);
				take(p[0], p[1], 0);
				boolean wall = Math.abs(s) == 3 || d == -3 || d == 3;
				set(level, p[0], p[1], 0, wall ? pal.base() : pal.floor());
				for (int dy = 1; dy <= 4; dy++) {
					boolean door = d == 3 && Math.abs(s) <= 1 && dy <= 3;
					set(level, p[0], p[1], dy, wall && !door ? (dy == 1 ? pal.base() : pal.edge()) : Blocks.AIR);
				}
				set(level, p[0], p[1], 5, pal.base());
				if (s == 0) set(level, p[0], p[1], 6, pal.accent());
			}
		}
		// the doorway's lintel and two lights on its posts
		for (int s = -1; s <= 1; s++) {
			int[] p = alc(al, 3, s);
			set(level, p[0], p[1], 4, pal.accent());
		}
		for (int s : new int[]{-2, 2}) {
			int[] p = alc(al, 4, s);
			set(level, p[0], p[1], 1, pal.edge());
			set(level, p[0], p[1], 2, RuinKit.candle(2, true));
			candles.add(arena(p[0], p[1], 2));
		}
		// keep the path in front clear
		for (int d = 4; d <= 6; d++) for (int s = -2; s <= 2; s++) {
			int[] p = alc(al, d, s);
			if (Math.abs(p[0]) <= R && Math.abs(p[1]) <= R) take(p[0], p[1], 0);
		}
		set(level, al.pf(), al.pl(), 1, pal.accent());
		for (int s : new int[]{-2, 2}) {
			int[] back = alc(al, -2, s), front = alc(al, 2, s);
			set(level, back[0], back[1], 1, RuinKit.candle(4, true));
			candles.add(arena(back[0], back[1], 1));
			set(level, front[0], front[1], 1, RuinKit.candle(1, true));
			candles.add(arena(front[0], front[1], 1));
			set(level, back[0], back[1], 4, Blocks.COBWEB);
		}
		set(level, al.f(), al.l(), 4, RuinKit.hangingLantern(true));
	}

	/** Mirrors on the inner face of the wall: an iron face in an accent frame with a pale sheen display. */
	private void wallMirrors(ServerLevel level) {
		mirrors.clear();
		List<int[]> at = new ArrayList<>();
		for (int f : new int[]{-22, -2, 20}) {
			at.add(new int[]{f, R, 0, -1});
			at.add(new int[]{f, -R, 0, 1});
		}
		at.add(new int[]{R, -8, -1, 0});
		at.add(new int[]{R, 8, -1, 0});
		for (int[] m : at) {
			int f = m[0], l = m[1], nf = m[2], nl = m[3];
			int sf = -nl, sl = nf; // along the wall
			for (int s = -2; s <= 2; s++) {
				for (int dy = 1; dy <= 5; dy++) {
					int wf = f + sf * s, wl = l + sl * s;
					boolean face = Math.abs(s) <= 1 && dy >= 2 && dy <= 4;
					set(level, wf, wl, dy, face ? Blocks.IRON_BLOCK : pal.accent());
				}
			}
			set(level, f, l, 6, pal.light());
			Vec3 n = new Vec3(fx * nf + lx * nl, 0, fz * nf + lz * nl);
			Vec3 c = wpos(f + nf * 0.5, l + nl * 0.5, Rifts.FLOOR + 3.5);
			mirrors.add(new Mirror(c, n));
			// the sheen: a thin pane of glass just off the face
			Vec3 along = new Vec3(fx * sf + lx * sl, 0, fz * sf + lz * sl);
			float w = (float) (Math.abs(along.x) * 2.9 + Math.abs(n.x) * 0.04), d = (float) (Math.abs(along.z) * 2.9 + Math.abs(n.z) * 0.04);
			RuinKit.prop(this, level, c.add(n.scale(0.03)), Blocks.WHITE_STAINED_GLASS.defaultBlockState(), RuinKit.centred(new Vector3f(), w, 2.9f, d, new Quaternionf()), 0, true);
			take(f + nf, l + nl, 2);
			take(f + nf * 3, l + nl * 3, 2);
		}
	}

	/** The altar: a stepped round dais, four sockets around a glowing heart. */
	private void dais(ServerLevel level) {
		for (int f = -7; f <= 7; f++) {
			for (int l = -7; l <= 7; l++) {
				double d = Math.sqrt(f * f + l * l);
				if (d > 6.5) continue;
				set(level, f, l, 1, d > 5.5 ? pal.edge() : pal.floor());
				if (d <= 3.6) set(level, f, l, 2, d > 2.6 ? pal.edge() : pal.accent());
			}
		}
		take(0, 0, 9);
		set(level, 0, 0, 3, pal.light());
		for (int n = 0; n < 4; n++) {
			BlockPos s = socketPos(n);
			RealmBuilder.set(level, s.getX(), s.getY(), s.getZ(), pal.edge());
			int col = Reliquary.WING_RGB[n];
			String g = GLYPHS[n];
			label(level, Vec3.atBottomCenterOf(s).add(0, 1.9, 0), Component.literal(g).withStyle(st -> st.withColor(col).withBold(true)), 1.2f);
		}
		// four static mourners on the dais corners (Hard+ they look just like the real ones)
		for (int[] c : new int[][]{{4, 4}, {4, -4}, {-4, 4}, {-4, -4}}) {
			set(level, c[0], c[1], 2, pal.edge());
			set(level, c[0], c[1], 3, RuinKit.candle(3, true));
			candles.add(arena(c[0], c[1], 3));
		}
	}

	/** Tombs, gravestones, broken columns and screen walls spread over the garden: they break lines of sight. */
	private void field(ServerLevel level, RandomSource r) {
		// the gate lane stays open
		for (int f = -R; f <= -20; f++) for (int l = -3; l <= 3; l++) take(f, l, 0);
		int screens = 0, tombs = 0, columns = 0, graves = 0;
		for (int t = 0; t < 900; t++) {
			int f = r.nextInt(2 * R - 7) - (R - 4), l = r.nextInt(2 * R - 7) - (R - 4);
			int type = r.nextInt(10);
			if (type < 3 && screens < 14) {
				boolean alongF = r.nextBoolean();
				if (!areaFree(f, l, alongF ? 3 : 1, alongF ? 1 : 3)) continue;
				for (int k = -2; k <= 2; k++) {
					int sf = alongF ? f + k : f, sl = alongF ? l : l + k;
					int h = Math.abs(k) == 2 ? 1 + r.nextInt(2) : 2 + r.nextInt(2);
					for (int dy = 1; dy <= h; dy++) set(level, sf, sl, dy, dy == 1 ? pal.base() : pal.edge());
					if (h >= 3 && r.nextInt(3) == 0) set(level, sf, sl, h + 1, pal.accent());
				}
				take(f, l, 3);
				screens++;
			} else if (type < 6 && tombs < 22) {
				boolean alongF = r.nextBoolean();
				if (!areaFree(f, l, alongF ? 2 : 1, alongF ? 1 : 2)) continue;
				for (int k = -1; k <= 1; k++) set(level, alongF ? f + k : f, alongF ? l : l + k, 1, k == 0 ? pal.accent() : pal.edge());
				// the headstone and maybe a candle or a skull
				int hf = alongF ? f - 2 : f, hl = alongF ? l : l - 2;
				set(level, hf, hl, 1, pal.edge());
				set(level, hf, hl, 2, pal.edge());
				if (r.nextInt(3) == 0) {
					set(level, f, l, 2, RuinKit.candle(1 + r.nextInt(3), true));
					candles.add(arena(f, l, 2));
				} else if (r.nextInt(4) == 0) {
					set(level, hf, hl, 3, Blocks.SKELETON_SKULL.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, r.nextInt(16)));
				}
				take(f, l, 2);
				tombs++;
			} else if (type < 8 && columns < 12) {
				if (!areaFree(f, l, 2, 2)) continue;
				RuinKit.pillar(this, level, f, l, 1, 4 + r.nextInt(4), r.nextInt(3) != 0, r);
				take(f, l, 3);
				columns++;
			} else if (graves < 18) {
				if (!areaFree(f, l, 1, 1)) continue;
				set(level, f, l, 1, pal.edge());
				if (r.nextBoolean()) set(level, f, l, 2, pal.base());
				take(f, l, 1);
				graves++;
			}
		}
	}

	private boolean areaFree(int f, int l, int hf, int hl) {
		for (int df = -hf; df <= hf; df++) for (int dl = -hl; dl <= hl; dl++) if (isTaken(f + df, l + dl)) return false;
		return true;
	}

	// ---------------------------------------------------------------
	// Layout: statues and sigils (rebuilt on a reset)
	// ---------------------------------------------------------------

	private int statueCount() {
		int players = Math.max(1, run.players.size());
		int step = AuraConfig.RIFT_STATUES_TIER_STEP;
		int n = Math.max(3, AuraConfig.RIFT_STATUES_BASE) + (step > 0 ? tier / step : 0) + (players - 1) * Math.max(0, AuraConfig.RIFT_STATUES_PER_PLAYER);
		return Math.min(Math.max(3, Math.min(10, AuraConfig.RIFT_STATUES_MAX)), n);
	}

	private void newLayout(ServerLevel level, RandomSource r) {
		for (Statue s : statues) for (Display.BlockDisplay d : s.parts) RuinKit.drop(this, d);
		statues.clear();
		for (Sigil s : sigils) {
			RuinKit.drop(this, s.crystal);
			RuinKit.drop(this, s.tag);
		}
		sigils.clear();
		glowing = false;
		dread.clear();
		blinkUntil.clear();
		extraStatues = 0;
		baseStatues = statueCount();
		// four of the mausoleums hold the sigils
		List<Integer> order = new ArrayList<>();
		for (int k = 0; k < alcoves.size(); k++) order.add(k);
		java.util.Collections.shuffle(order, new java.util.Random(r.nextLong()));
		for (int n = 0; n < 4; n++) {
			Sigil s = new Sigil(n, Reliquary.WING_RGB[n]);
			s.alcove = order.get(n);
			sigils.add(s);
			showOnPlinth(level, s);
		}
		for (int k = 0; k < baseStatues; k++) addStatue(level, r, false, List.of());
		if (tier >= 2) for (int k = 0; k < 2; k++) addStatue(level, r, true, List.of());
	}

	private Vec3 plinthTop(Sigil s) {
		return Vec3.atBottomCenterOf(plinthPos(alcoves.get(s.alcove))).add(0, 1.35, 0);
	}

	private void showOnPlinth(ServerLevel level, Sigil s) {
		s.state = 0;
		s.carrier = null;
		Vec3 at = plinthTop(s);
		if (s.crystal == null) {
			Quaternionf rot = new Quaternionf().rotationY((float) Math.PI / 4f).rotateX((float) Math.toRadians(35));
			s.crystal = RuinKit.prop(this, level, at, RuinKit.glassFor(s.rgb).defaultBlockState(), RuinKit.centred(new Vector3f(), 0.45f, 0.45f, 0.45f, rot), 2, true);
		} else Props.move(s.crystal, at);
		if (s.tag == null) {
			int col = s.rgb;
			s.tag = label(level, at.add(0, 0.6, 0), Component.literal(GLYPHS[s.n]).withStyle(st -> st.withColor(col).withBold(true)), 1.3f);
		} else s.tag.setPos(at.x, at.y + 0.6, at.z);
	}

	/** A free spot for a statue: open ground, away from the gate, the altar, players and other statues. */
	private double[] spawnSpot(ServerLevel level, RandomSource r, List<ServerPlayer> far) {
		for (int t = 0; t < 300; t++) {
			int f = r.nextInt(2 * R - 9) - (R - 5), l = r.nextInt(2 * R - 9) - (R - 5);
			if (Math.sqrt(f * f + l * l) < 10 || (f < -14 && Math.abs(l) < 10)) continue;
			if (ground(level, f, l, 0) != 0) continue;
			boolean ok = true;
			for (Statue s : statues) if (Math.hypot(s.f - f, s.l - l) < 6) ok = false;
			for (ServerPlayer p : far) if (Math.hypot(af(p.position()) - f, al(p.position()) - l) < 18) ok = false;
			if (ok) return new double[]{f, l};
		}
		return new double[]{R - 6, 0};
	}

	private void addStatue(ServerLevel level, RandomSource r, boolean decoy, List<ServerPlayer> far) {
		Statue s = new Statue();
		s.decoy = decoy;
		double[] at;
		if (decoy) {
			// mourners on the dais ring, on the axes between the sockets' arms
			int[][] ring = {{5, 0}, {0, 5}, {-5, 0}, {0, -5}};
			int k = 0;
			for (Statue o : statues) if (o.decoy) k++;
			int[] c = ring[(k * 2 + (int) (Math.abs(run.id) % 2)) % 4];
			at = new double[]{c[0], c[1]};
		} else at = spawnSpot(level, r, far);
		s.f = at[0];
		s.l = at[1];
		s.g = decoy ? 1 : 0;
		Vec3 pos = statuePos(s);
		s.yaw = yawTo(pos, Vec3.atCenterOf(arena(0, 0, 1)));
		BlockState robe = Blocks.POLISHED_ANDESITE.defaultBlockState(), stone = Blocks.ANDESITE.defaultBlockState(), head = Blocks.STONE.defaultBlockState();
		Transformation[] t = pose(false);
		BlockState[] look = {robe, stone, robe, head, stone, stone, stone, stone, stone, stone};
		for (int k = 0; k < t.length; k++) {
			Display.BlockDisplay d = RuinKit.prop(this, level, pos, look[k], t[k], 3, false);
			d.setYRot(s.yaw);
			s.parts.add(d);
		}
		statues.add(s);
	}

	/** The ten parts: robe, hem, torso, head, two folded wings, upper arms and forearms (over the face, or reaching out). */
	static Transformation[] pose(boolean reach) {
		Transformation[] t = new Transformation[10];
		t[0] = RuinKit.box(-0.32f, 0f, -0.22f, 0.64f, 1.15f, 0.44f);
		t[1] = RuinKit.box(-0.4f, 0f, -0.28f, 0.8f, 0.22f, 0.56f);
		t[2] = RuinKit.box(-0.3f, 1.15f, -0.18f, 0.6f, 0.6f, 0.36f);
		t[3] = RuinKit.centred(new Vector3f(0f, reach ? 1.96f : 1.93f, reach ? 0.04f : 0.06f), 0.42f, 0.42f, 0.42f, new Quaternionf().rotationX(reach ? 0f : 0.35f));
		t[4] = RuinKit.centred(new Vector3f(0.34f, 1.45f, -0.34f), 0.08f, 1.3f, 0.62f, new Quaternionf().rotationY(-0.45f));
		t[5] = RuinKit.centred(new Vector3f(-0.34f, 1.45f, -0.34f), 0.08f, 1.3f, 0.62f, new Quaternionf().rotationY(0.45f));
		for (int side = 0; side < 2; side++) {
			float sx = side == 0 ? 1f : -1f;
			Vector3f shoulder = new Vector3f(0.34f * sx, 1.7f, 0f);
			Vector3f elbow = reach ? new Vector3f(0.3f * sx, 1.62f, 0.45f) : new Vector3f(0.3f * sx, 1.3f, 0.16f);
			Vector3f hand = reach ? new Vector3f(0.26f * sx, 1.55f, 0.95f) : new Vector3f(0.09f * sx, 1.98f, 0.27f);
			t[6 + side] = RuinKit.rod(shoulder, elbow, 0.17f);
			t[8 + side] = RuinKit.rod(elbow, hand, 0.15f);
		}
		return t;
	}

	private void place(Statue s, boolean reach) {
		Vec3 pos = statuePos(s);
		for (Display.BlockDisplay d : s.parts) {
			Props.move(d, pos);
			d.setYRot(s.yaw);
		}
		if (reach != s.reaching) {
			s.reaching = reach;
			Transformation[] t = pose(reach);
			for (int k = 6; k < 10 && k < s.parts.size(); k++) Props.set(s.parts.get(k), t[k], 0);
			Props.set(s.parts.get(3), t[3], 0);
		}
	}

	// ---------------------------------------------------------------
	// Seeing
	// ---------------------------------------------------------------

	/** Someone sees statue {@code s}: straight (a view cone and a clear line), or reflected in a wall mirror they look at. */
	boolean watched(ServerLevel level, Statue s, List<ServerPlayer> here) {
		return watched(level, s, here, level.getGameTime());
	}

	boolean watched(ServerLevel level, Statue s, List<ServerPlayer> here, long now) {
		for (ServerPlayer p : here) {
			if (p.isSpectator() || now < blinkUntil.getOrDefault(p.getUUID(), 0L)) continue;
			if (seenBy(level, s, p)) return true;
		}
		return false;
	}

	/** Player {@code p} sees the statue (straight or in a mirror), whatever their eyes are doing. */
	boolean seenBy(ServerLevel level, Statue s, ServerPlayer p) {
		Vec3 pos = statuePos(s);
		Vec3[] pts = {pos.add(0, 1.3, 0), pos.add(0, 1.95, 0)};
		{
			Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
			for (Vec3 pt : pts) {
				Vec3 v = pt.subtract(eye);
				double d = v.length();
				if (d < 0.6) return true;
				if (d > 72 || look.dot(v) / d < SEE_DOT) continue;
				if (clear(level, eye, pt, p)) return true;
			}
			for (Mirror m : mirrors) {
				Vec3 v = m.c().subtract(eye);
				double d = v.length();
				if (d > 28 || d < 0.5 || look.dot(v) / d < 0.88 || eye.subtract(m.c()).dot(m.n()) < 0.3) continue;
				if (!clear(level, eye, m.c().add(m.n().scale(0.35)), p)) continue;
				Vec3 refl = look.subtract(m.n().scale(2 * look.dot(m.n())));
				Vec3 w = pts[0].subtract(m.c());
				double wl = w.length();
				if (wl > 30 || w.dot(m.n()) <= 0 || w.dot(refl) / wl < 0.8) continue;
				if (clear(level, m.c().add(m.n().scale(0.6)), pts[0], p)) return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------
	// Playing
	// ---------------------------------------------------------------

	@Override
	void begin(ServerLevel level, List<ServerPlayer> here) {
		long now = level.getGameTime();
		moveFrom = now + 180;
		for (int k = 0; k < statues.size(); k++) statues.get(k).nextStep = moveFrom + k * 5L;
		nextFlicker = now + 600 + level.random.nextInt(300);
	}

	ServerPlayer carrierOf(Sigil s, List<ServerPlayer> here) {
		if (s.carrier == null) return null;
		for (ServerPlayer p : here) if (p.getUUID().equals(s.carrier)) return p;
		return null;
	}

	Sigil carried(ServerPlayer p) {
		for (Sigil s : sigils) if ((s.state == 1 || s.state == 2) && p.getUUID().equals(s.carrier)) return s;
		return null;
	}

	@Override
	void tick(ServerLevel level, List<ServerPlayer> here, long now) {
		if (solved) {
			// solved in the dark: the candles come back on
			if (flickerUntil != 0) {
				candles(level, true);
				flickerUntil = 0;
			}
			return;
		}
		tickSigils(level, here, now);
		if (now >= moveFrom && now % 5 == 0) tickDread(level, here, now);
		if (tier >= 3) tickFlicker(level, here, now);
		boolean dark = tier >= 3 && now < flickerUntil;
		if (now >= moveFrom) {
			for (Statue s : statues) {
				if (s.decoy || now < s.nextStep) continue;
				s.nextStep = now + (dark ? 12 : STEP_EVERY[tier]);
				if (now < s.stunUntil) continue;
				if (!dark && watched(level, s, here, now)) continue;
				if (stepStatue(level, s, here, now)) return;
			}
		}
		if (now >= nextHint) {
			nextHint = now + 60;
			hints(level, here);
		}
	}

	/** Ticks of staring a player can take: RIFT_STATUES_STARE_SECONDS, 10% less per tier above Easy (0 = dread is off). */
	int dreadLimit() {
		double sec = AuraConfig.RIFT_STATUES_STARE_SECONDS;
		return sec <= 0 ? 0 : Math.max(60, (int) Math.round(sec * 20 * (1 - 0.1 * tier)));
	}

	/**
	 * Dread (every 5 ticks): keeping a statue in view builds it, looking away drains it. Past 60% the heart beats and the
	 * eyes ache; at the limit the eyes give: for RIFT_STATUES_BLINK_SECONDS this player's gaze holds nothing and the stone
	 * steps. A lone watcher cannot freeze the statues for ever, and neither can a team's designated watcher.
	 */
	private void tickDread(ServerLevel level, List<ServerPlayer> here, long now) {
		int limit = dreadLimit();
		if (limit <= 0) return;
		for (ServerPlayer p : here) {
			UUID id = p.getUUID();
			if (p.isSpectator() || now < blinkUntil.getOrDefault(id, 0L)) continue;
			boolean sees = false;
			for (Statue s : statues) if (!s.decoy && seenBy(level, s, p)) {
				sees = true;
				break;
			}
			int d = dread.getOrDefault(id, 0);
			d = sees ? d + 5 : Math.max(0, d - (int) Math.round(5 * Math.max(0, AuraConfig.RIFT_STATUES_DREAD_RECOVERY)));
			if (d >= limit) {
				d = 0;
				int blink = Math.max(10, (int) Math.round(AuraConfig.RIFT_STATUES_BLINK_SECONDS * 20));
				blinkUntil.put(id, now + blink);
				p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, blink + 10, 0, false, false));
				p.playNotifySound(AuraSounds.WARDEN_HEARTBEAT.value(), SoundSource.AMBIENT, 1.4f, 0.6f);
				p.displayClientMessage(Component.literal("Your eyes give - the stone moves").withStyle(ChatFormatting.RED), true);
				for (Statue s : statues) s.nextStep = Math.min(s.nextStep, now + 3);
			} else if (sees && d >= limit * 0.6 && now % 20 == 0) {
				double f = (double) d / limit;
				p.playNotifySound(AuraSounds.WARDEN_HEARTBEAT.value(), SoundSource.AMBIENT, 0.8f, (float) (0.7 + f * 0.5));
				p.displayClientMessage(Component.literal(f < 0.85 ? "Your eyes ache - look away" : "You cannot hold it much longer...").withStyle(f < 0.85 ? ChatFormatting.GRAY : ChatFormatting.DARK_RED), true);
			}
			dread.put(id, d);
		}
	}

	private void lifted(ServerLevel level, Sigil s, ServerPlayer p) {
		s.state = 2;
		Vec3 at = plinthTop(s);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), SoundSource.BLOCKS, 1.4f, 1.2f);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.ARMOR_EQUIP_GOLD.value(), SoundSource.BLOCKS, 1f, 0.8f);
		level.sendParticles(AuraFx.dust(s.rgb, 1.5f), at.x, at.y + 0.5, at.z, 20, 0.3, 0.4, 0.3, 0);
		p.displayClientMessage(Component.literal("You carry the sigil " + GLYPHS[s.n] + " - bring it to the altar").withStyle(ChatFormatting.GREEN), true);
	}

	/** The lift's release: good = carried, bad = it sinks back with a scrape and the nearest statue takes a step (never into a touch). */
	private void liftResult(ServerLevel level, Sigil s, ServerPlayer p, int res) {
		if (s.state != 1 || !p.getUUID().equals(s.carrier)) return;
		if (res == SkillChecks.GOOD) {
			lifted(level, s, p);
			return;
		}
		Vec3 at = plinthTop(s);
		showOnPlinth(level, s);
		if (res == SkillChecks.CANCEL) return;
		RiftSfx.scrape(level, at);
		p.displayClientMessage(Component.literal("The sigil grinds back - something heard that").withStyle(ChatFormatting.GOLD), true);
		Statue near = null;
		double best = Double.MAX_VALUE;
		for (Statue st : statues) {
			double d = statuePos(st).distanceTo(p.position());
			if (d < best && d > 5) {
				best = d;
				near = st;
			}
		}
		if (near != null) stepStatue(level, near, RuinKit.arenaPlayers(this, level), level.getGameTime());
	}

	private void tickSigils(ServerLevel level, List<ServerPlayer> here, long now) {
		for (Sigil s : sigils) {
			if (s.state == 1) {
				ServerPlayer p = carrierOf(s, here);
				if (p == null || p.position().distanceTo(s.liftFrom) > 1.0) {
					if (p != null) p.displayClientMessage(Component.literal("You moved - the sigil sinks back").withStyle(ChatFormatting.GRAY), true);
					showOnPlinth(level, s);
					continue;
				}
				Vec3 at = plinthTop(s);
				double t = Math.max(0, Math.min(1, 1 - (s.liftDone - now) / (double) LIFT[tier]));
				if (s.crystal != null) Props.move(s.crystal, at.add(0, t * 0.6, 0));
				if (now % 4 == 0) level.sendParticles(AuraFx.dust(s.rgb, 1f), at.x, at.y, at.z, 3, 0.2, 0.3, 0.2, 0);
				if (!s.check && now % 10 == 0) p.displayClientMessage(Component.literal("Lifting the sigil " + GLYPHS[s.n] + " - hold still " + "■".repeat((int) (t * 10)) + "□".repeat(10 - (int) (t * 10))).withStyle(ChatFormatting.AQUA), true);
				if (!s.check && now >= s.liftDone) lifted(level, s, p);
			} else if (s.state == 2) {
				ServerPlayer p = carrierOf(s, here);
				if (p == null) {
					showOnPlinth(level, s);
					continue;
				}
				Vec3 at = p.position().add(0, 2.5, 0);
				if (s.crystal != null) Props.move(s.crystal, at);
				if (s.tag != null) s.tag.setPos(at.x, at.y + 0.5, at.z);
				if (now % 5 == 0) level.sendParticles(AuraFx.dust(s.rgb, 0.9f), at.x, at.y - 0.3, at.z, 2, 0.25, 0.25, 0.25, 0);
			}
		}
	}

	/** One step toward the nearest player (a carrier first). True = it touched someone and the layout may have changed. */
	private boolean stepStatue(ServerLevel level, Statue s, List<ServerPlayer> here, long now) {
		ServerPlayer target = null;
		double best = Double.MAX_VALUE;
		for (ServerPlayer p : here) {
			if (p.isSpectator()) continue;
			double d = Math.hypot(af(p.position()) - s.f, al(p.position()) - s.l);
			if (carried(p) != null && d < 20) d -= 12;
			if (d < best) {
				best = d;
				target = p;
			}
		}
		if (target == null) return false;
		double tf = af(target.position()), tl = al(target.position());
		double df = tf - s.f, dl = tl - s.l, dist = Math.hypot(df, dl);
		if (dist < 0.01) return false;
		double len = Math.min(STEP_LEN[tier] * (dist > 14 ? 2 : 1), Math.max(0, dist - 0.8));
		boolean moved = false;
		if (len > 0.2) {
			double ang = Math.atan2(dl, df);
			double[] tries = s.stuck >= 4 ? new double[]{Math.PI / 2, -Math.PI / 2, Math.PI * 0.75, -Math.PI * 0.75, 0} : new double[]{0, 0.5, -0.5, 1.0, -1.0, 1.5, -1.5};
			if (s.stuck >= 4 && rnd != null && rnd.nextBoolean()) for (int k = 0; k < tries.length; k++) tries[k] = -tries[k];
			for (double off : tries) {
				double nf = s.f + Math.cos(ang + off) * len, nl = s.l + Math.sin(ang + off) * len;
				int g = ground(level, (int) Math.round(nf), (int) Math.round(nl), s.g);
				if (g == -99) continue;
				double mf = (s.f + nf) / 2, ml = (s.l + nl) / 2;
				if (len > 1.4 && ground(level, (int) Math.round(mf), (int) Math.round(ml), s.g) == -99) continue;
				boolean crowd = false;
				for (Statue o : statues) if (o != s && Math.hypot(o.f - nf, o.l - nl) < 1.2) crowd = true;
				if (crowd) continue;
				s.f = nf;
				s.l = nl;
				s.g = g;
				moved = true;
				s.stuck = off == 0 ? 0 : s.stuck;
				break;
			}
			if (!moved) s.stuck++;
		}
		Vec3 pos = statuePos(s);
		s.yaw = yawTo(pos, target.position());
		place(s, dist < 8);
		if (moved) level.playSound(null, pos.x, pos.y, pos.z, AuraSounds.GRINDSTONE_USE.value(), SoundSource.HOSTILE, 0.9f, 0.45f + level.random.nextFloat() * 0.1f);
		for (ServerPlayer p : here) {
			if (p.isSpectator()) continue;
			Vec3 pp = p.position();
			double h = Math.hypot(af(pp) - s.f, al(pp) - s.l);
			if (h <= TOUCH && Math.abs(pp.y - pos.y) < 2.5) {
				Long im = immune.get(p.getUUID());
				if (im != null && now < im) continue;
				touch(level, s, p, here, now);
				return true;
			}
		}
		return false;
	}

	/** The stone reaches someone: their sigil goes home, the statue crumbles and reforms far off, a strike. */
	private void touch(ServerLevel level, Statue s, ServerPlayer p, List<ServerPlayer> here, long now) {
		immune.put(p.getUUID(), now + 60);
		Vec3 pos = statuePos(s);
		level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 1, pos.z, 20, 0.4, 0.8, 0.4, 0.02);
		level.playSound(null, pos.x, pos.y, pos.z, AuraSounds.STONE_BREAK.value(), SoundSource.HOSTILE, 1.5f, 0.5f);
		level.playSound(null, p.getX(), p.getY(), p.getZ(), AuraSounds.WARDEN_ATTACK_IMPACT.value(), SoundSource.HOSTILE, 1.2f, 0.8f);
		Sigil held = carried(p);
		if (held != null) {
			showOnPlinth(level, held);
			p.displayClientMessage(Component.literal("The stone took your sigil back to its tomb").withStyle(ChatFormatting.RED), true);
		}
		Vec3 push = p.position().subtract(pos).multiply(1, 0, 1);
		if (push.lengthSqr() > 1.0e-4) {
			p.knockback(0.8, -push.x, -push.z);
			p.hurtMarked = true;
		}
		p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1, false, false));
		// it reforms in the dark, far from everyone
		double[] to = spawnSpot(level, rnd != null ? rnd : level.random, here);
		s.f = to[0];
		s.l = to[1];
		s.g = 0;
		s.stunUntil = now + 80;
		s.stuck = 0;
		place(s, false);
		strike(level, p, p.position(), here);
		if (run.difficulty.oneLife()) Rifts.knockoutFromTrial(p);
	}

	@Override
	boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
		if (solved) return false;
		long now = level.getGameTime();
		for (Sigil s : sigils) {
			if (!pos.equals(plinthPos(alcoves.get(s.alcove)))) continue;
			if (s.state != 0) return true;
			if (carried(p) != null) {
				p.displayClientMessage(Component.literal("You can carry only one sigil").withStyle(ChatFormatting.GRAY), true);
				return true;
			}
			if (SkillChecks.busy(p)) return true;
			s.check = SkillChecks.enabled();
			if (s.check && !SkillChecks.start(p, "Lift the sigil " + GLYPHS[s.n], s.rgb, tier, plinthTop(s), false, () -> s.state == 1 && !solved, res -> liftResult(level, s, p, res))) return true;
			s.state = 1;
			s.carrier = p.getUUID();
			s.liftFrom = p.position();
			s.liftDone = now + (s.check ? 54 : LIFT[tier]);
			level.playSound(null, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, AuraSounds.BEACON_AMBIENT.value(), SoundSource.BLOCKS, 1.5f, 1.4f);
			return true;
		}
		for (Alcove al : alcoves) {
			if (pos.equals(plinthPos(al))) {
				p.displayClientMessage(Component.literal("This plinth is empty").withStyle(ChatFormatting.GRAY), true);
				return true;
			}
		}
		boolean altar = pos.equals(arena(0, 0, 3));
		for (int n = 0; n < 4; n++) if (pos.equals(socketPos(n))) altar = true;
		if (!altar) return false;
		Sigil s = carried(p);
		if (s == null || s.state != 2) {
			int left = 0;
			for (Sigil o : sigils) if (o.state != 3) left++;
			p.displayClientMessage(Component.literal(left + " sigil(s) still missing from the altar").withStyle(ChatFormatting.GRAY), true);
			return true;
		}
		setIn(level, s);
		int placed = 0;
		for (Sigil o : sigils) if (o.state == 3) placed++;
		if (placed >= sigils.size()) {
			Vec3 at = Vec3.atBottomCenterOf(arena(0, 0, 4)).add(0, 0.3, 0);
			for (Statue st : statues) {
				Vec3 sp = statuePos(st);
				level.sendParticles(ParticleTypes.CLOUD, sp.x, sp.y + 1, sp.z, 12, 0.3, 0.8, 0.3, 0.02);
			}
			level.playSound(null, at.x, at.y, at.z, AuraSounds.PLAYER_LEVELUP.value(), SoundSource.PLAYERS, 1.2f, 1.2f);
			solve(level, at);
		} else {
			actionBar(RuinKit.arenaPlayers(this, level), "Sigil " + GLYPHS[s.n] + " set - " + (sigils.size() - placed) + " to go", ChatFormatting.AQUA);
		}
		return true;
	}

	private void setIn(ServerLevel level, Sigil s) {
		s.state = 3;
		s.carrier = null;
		Vec3 at = Vec3.atBottomCenterOf(socketPos(s.n)).add(0, 1.4, 0);
		if (s.crystal != null) Props.move(s.crystal, at);
		if (s.tag != null) s.tag.setPos(at.x, at.y + 0.5, at.z);
		BlockPos sp = socketPos(s.n);
		RealmBuilder.set(level, sp.getX(), sp.getY(), sp.getZ(), pal.light());
		level.playSound(null, at.x, at.y, at.z, AuraSounds.END_PORTAL_FRAME_FILL.value(), SoundSource.BLOCKS, 1.5f, 1f);
		level.sendParticles(AuraFx.dust(s.rgb, 1.5f), at.x, at.y, at.z, 20, 0.2, 0.6, 0.2, 0);
	}

	private void unset(ServerLevel level, Sigil s) {
		BlockPos sp = socketPos(s.n);
		RealmBuilder.set(level, sp.getX(), sp.getY(), sp.getZ(), pal.edge());
		showOnPlinth(level, s);
	}

	@Override
	void release(ServerPlayer p) {
		immune.remove(p.getUUID());
		Sigil s = carried(p);
		if (s != null && p.level() instanceof ServerLevel level) showOnPlinth(level, s);
	}

	private void candles(ServerLevel level, boolean lit) {
		for (BlockPos c : candles) {
			BlockState st = level.getBlockState(c);
			if (st.hasProperty(BlockStateProperties.LIT)) RealmBuilder.set(level, c.getX(), c.getY(), c.getZ(), st.setValue(BlockStateProperties.LIT, lit));
		}
	}

	/** Extreme+: the candles gutter out for two seconds now and then; in the dark the statues move whoever looks. */
	private void tickFlicker(ServerLevel level, List<ServerPlayer> here, long now) {
		if (flickerUntil != 0 && now >= flickerUntil) {
			candles(level, true);
			flickerUntil = 0;
		}
		if (now == nextFlicker - 30) {
			for (ServerPlayer p : here) p.playNotifySound(AuraSounds.CANDLE_EXTINGUISH.value(), SoundSource.AMBIENT, 1f, 0.6f);
			for (int k = 0; k < candles.size(); k += 3) {
				BlockPos c = candles.get(k);
				level.sendParticles(ParticleTypes.SMOKE, c.getX() + 0.5, c.getY() + 0.7, c.getZ() + 0.5, 3, 0.1, 0.1, 0.1, 0.01);
			}
		}
		if (now >= nextFlicker) {
			flickerUntil = now + 40;
			nextFlicker = now + 360 + level.random.nextInt(240);
			candles(level, false);
			for (ServerPlayer p : here) {
				p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0, false, false));
				p.playNotifySound(AuraSounds.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 1f, 0.7f);
			}
			for (Statue s : statues) s.nextStep = Math.min(s.nextStep, now + 6);
		}
	}

	/** 1: the sigils still out shine up a column; 2: the statues glow through walls. */
	private void hints(ServerLevel level, List<ServerPlayer> here) {
		if (!hintsAllowed()) return;
		int h = totalStrikes - (tier == 0 ? 1 : 2) >= 1 ? 2 : 1;
		for (Sigil s : sigils) {
			if (s.state != 0) continue;
			Vec3 at = plinthTop(s);
			for (int k = 0; k < 12; k++) level.sendParticles(AuraFx.dust(s.rgb, 1.2f), at.x, at.y + k * 0.9, at.z, 1, 0.05, 0.1, 0.05, 0);
		}
		actionBar(here, "Do not stare too long: take turns watching, and look away to calm your dread", ChatFormatting.AQUA);
		if (h >= 2 && !glowing) {
			glowing = true;
			for (Statue s : statues) if (!s.decoy) for (Display.BlockDisplay d : s.parts) d.setGlowingTag(true);
		}
	}

	// --- the strike ladder

	@Override
	void onReact(ServerLevel level, Vec3 at) {
		candles(level, false);
		flickerUntil = level.getGameTime() + 20;
		if (tier < 3) {
			var server = level.getServer();
			AuraFx.later(server, 20, () -> candles(level, true));
		}
	}

	@Override
	void onHazard(ServerLevel level, Vec3 at) {
		punish(level, at, 2 + tier / 2);
		// one sigil comes off the altar, and another statue rises
		for (Sigil s : sigils) {
			if (s.state == 3) {
				unset(level, s);
				break;
			}
		}
		if (extraStatues < 2) {
			extraStatues++;
			addStatue(level, rnd != null ? rnd : level.random, false, RuinKit.arenaPlayers(this, level));
			Statue s = statues.get(statues.size() - 1);
			s.nextStep = level.getGameTime() + 60;
			if (glowing) for (Display.BlockDisplay d : s.parts) d.setGlowingTag(true);
		}
	}

	@Override
	void onReset(ServerLevel level, RandomSource r) {
		for (Sigil s : sigils) if (s.state == 3) {
			BlockPos sp = socketPos(s.n);
			RealmBuilder.set(level, sp.getX(), sp.getY(), sp.getZ(), pal.edge());
		}
		boolean wasGlowing = glowing;
		newLayout(level, r);
		long now = level.getGameTime();
		for (int k = 0; k < statues.size(); k++) statues.get(k).nextStep = now + 100 + k * 5L;
		if (wasGlowing) {
			glowing = true;
			for (Statue s : statues) if (!s.decoy) for (Display.BlockDisplay d : s.parts) d.setGlowingTag(true);
		}
	}

	@Override
	Vec3 plinth() {
		return Vec3.atBottomCenterOf(arena(0, 0, 4)).add(0, 0.3, 0);
	}

	@Override
	void remove() {
		super.remove();
		statues.clear();
		sigils.clear();
	}

	// --- the camera

	@Override
	RiftCam intro() {
		Vec3 mid = Vec3.atCenterOf(arena(0, 0, 2));
		RiftCam cam = RiftCam.shot(rgb).title(name(), run.difficulty.label).skippable(true)
				.key(0, Vec3.atCenterOf(arena(-38, 0, 4)), mid)
				.key(40, Vec3.atCenterOf(arena(-26, 3, 3)), mid);
		Statue s = null;
		for (Statue o : statues) if (!o.decoy) {
			s = o;
			break;
		}
		if (s != null) {
			Vec3 pos = statuePos(s), head = pos.add(0, 1.9, 0);
			Vec3 face = Vec3.directionFromRotation(0, s.yaw);
			cam.cut(41, head.add(face.scale(3.2)).add(0, 0.2, 0), head).key(80, head.add(face.scale(2.0)).add(0, 0.1, 0), head);
		}
		if (!sigils.isEmpty()) {
			Alcove al = alcoves.get(sigils.get(0).alcove);
			Vec3 pl = plinthTop(sigils.get(0));
			Vec3 door = Vec3.atCenterOf(arena(al.f() + al.nf() * 5, al.l() + al.nl() * 5, 2));
			cam.cut(81, door, pl).key(115, Vec3.atCenterOf(arena(al.f() + al.nf() * 2, al.l() + al.nl() * 2, 2)), pl);
		}
		return cam.cut(116, Vec3.atCenterOf(arena(-14, 0, 22)), mid).key(160, Vec3.atCenterOf(arena(8, 16, 20)), mid);
	}

	// ---------------------------------------------------------------
	// DEV
	// ---------------------------------------------------------------

	private long devNow;

	private void devTicks(ServerLevel level, ServerPlayer fake, int n) {
		for (int k = 0; k < n && !solved; k++) tick(level, List.of(fake), ++devNow);
	}

	private void devStand(ServerPlayer fake, double f, double l, int dy, Vec3 lookAt) {
		Vec3 p = wpos(f, l, Rifts.FLOOR + dy);
		float yaw = lookAt == null ? 0f : yawTo(p, lookAt);
		float pitch = 0f;
		if (lookAt != null) {
			Vec3 eye = p.add(0, fake.getEyeHeight(), 0), v = lookAt.subtract(eye);
			pitch = (float) -Math.toDegrees(Math.atan2(v.y, Math.hypot(v.x, v.z)));
		}
		fake.moveTo(p.x, p.y, p.z, yaw, pitch);
		fake.setYHeadRot(yaw);
		fake.setOnGround(true);
	}

	private void devFreeze(boolean on) {
		for (Statue s : statues) s.stunUntil = on ? Long.MAX_VALUE : 0;
	}

	@Override
	void devPlay(net.minecraft.server.MinecraftServer server, ServerLevel level, ServerPlayer fake, java.util.function.Consumer<String> log, Runnable done) {
		// use() stamps the real game time, so the dev clock starts there and runs ahead of it
		devNow = level.getGameTime();
		moveFrom = 0;
		nextFlicker = Long.MAX_VALUE;
		nextHint = Long.MAX_VALUE;
		long real = statues.stream().filter(st -> !st.decoy).count();
		log.accept("statues: tier " + tier + ", statues " + real + " (must be " + baseStatues + "), sigils " + sigils.size() + " (must be 4), mirrors " + mirrors.size());
		devFreeze(true);
		Statue s = null;
		for (Statue o : statues) if (!o.decoy) {
			s = o;
			break;
		}
		if (s == null) {
			log.accept("statues: no statue (must not happen)");
			done.run();
			return;
		}
		// 1. in the gate lane: the statue 7 ahead, the player looking back at the gate
		s.stunUntil = 0;
		s.f = -21;
		s.l = 0;
		s.g = ground(level, -21, 0, 0);
		place(s, false);
		devStand(fake, -28, 0, 1, Vec3.atCenterOf(arena(-40, 0, 2)));
		double d0 = Math.hypot(s.f + 28, s.l);
		s.nextStep = 0;
		devTicks(level, fake, STEP_EVERY[tier] + 2);
		double d1 = Math.hypot(s.f + 28, s.l);
		log.accept("statues: unseen -> moved closer " + (d1 < d0 - 0.5) + " (must be true) " + String.format("%.1f -> %.1f", d0, d1));
		// 2. look at it: frozen
		devStand(fake, -28, 0, 1, statuePos(s).add(0, 1.3, 0));
		log.accept("statues: watched check " + watched(level, s, List.of(fake)) + " (must be true)");
		double f1 = s.f;
		devTicks(level, fake, STEP_EVERY[tier] * 3);
		log.accept("statues: watched -> frozen " + (Math.abs(s.f - f1) < 1.0e-6) + " (must be true)");
		// 2b. dread: staring for the limit makes the eyes give - the statue steps although it is watched
		if (dreadLimit() > 0) {
			s.stunUntil = 0;
			s.nextStep = 0;
			dread.clear();
			blinkUntil.clear();
			double f2 = s.f, l2 = s.l;
			devTicks(level, fake, dreadLimit() - 10);
			boolean heldBefore = Math.abs(s.f - f2) < 1.0e-6 && Math.abs(s.l - l2) < 1.0e-6;
			devTicks(level, fake, dreadLimit() / 2 + 40);
			boolean moved = Math.hypot(s.f - f2, s.l - l2) > 0.5;
			log.accept("statues: dread -> held before the limit " + heldBefore + " (must be true), moved after " + moved + " (must be true)");
			// looking away drains it
			devStand(fake, -28, 0, 1, Vec3.atCenterOf(arena(-40, 0, 2)));
			devTicks(level, fake, dreadLimit());
			log.accept("statues: dread drained looking away " + (dread.getOrDefault(fake.getUUID(), 0) == 0) + " (must be true)");
			devStand(fake, -28, 0, 1, statuePos(s).add(0, 1.3, 0));
			s.stunUntil = Long.MAX_VALUE;
			dread.clear();
			blinkUntil.clear();
			// the statue reached the player on its way: not part of the ladder test below
			strikes = 0;
			totalStrikes = 0;
			for (var m : run.mobs) m.discard();
			run.mobs.clear();
		}
		// 3. lift sigil 0, then a touch from behind takes it back and strikes
		Sigil g = sigils.get(0);
		Alcove al = alcoves.get(g.alcove);
		Vec3 pl = Vec3.atCenterOf(plinthPos(al));
		s.stunUntil = Long.MAX_VALUE;
		devStand(fake, al.pf() + al.nf(), al.pl() + al.nl(), 1, pl);
		use(level, fake, plinthPos(al));
		int stBefore = strikes;
		boolean bad = SkillChecks.devAnswer(fake, SkillChecks.EARLY);
		log.accept("statues: bad release -> check was open " + bad + " (must be true), sigil back " + (g.state == 0) + " (must be true), strikes unchanged " + (strikes == stBefore) + " (must be true)");
		use(level, fake, plinthPos(al));
		SkillChecks.devAnswer(fake, SkillChecks.GOOD);
		devTicks(level, fake, 2);
		s.stunUntil = 0;
		log.accept("statues: lift -> carried " + (g.state == 2) + " (must be true)");
		s.f = al.f() + al.nf();
		s.l = al.l() + al.nl();
		s.g = 0;
		s.nextStep = 0;
		place(s, false);
		devTicks(level, fake, STEP_EVERY[tier] + 2);
		log.accept("statues: touched -> strikes " + strikes + " (must be 1), sigil back on its plinth " + (g.state == 0) + " (must be true)");
		// 4. the solve, statues held still
		devFreeze(true);
		for (Sigil sg : sigils) {
			Alcove a2 = alcoves.get(sg.alcove);
			devStand(fake, a2.pf() + a2.nf(), a2.pl() + a2.nl(), 1, Vec3.atCenterOf(plinthPos(a2)));
			// the last one the old way, with the checks switched off
			boolean off = sg == sigils.get(sigils.size() - 1);
			com.toast.aurapoints.config.AuraConfig.RIFT_SKILL_CHECKS = !off;
			use(level, fake, plinthPos(a2));
			if (!off) SkillChecks.devAnswer(fake, SkillChecks.GOOD);
			devTicks(level, fake, off ? LIFT[tier] + 2 : 2);
			com.toast.aurapoints.config.AuraConfig.RIFT_SKILL_CHECKS = true;
			devStand(fake, -4.5, 0, 2, Vec3.atCenterOf(arena(0, 0, 3)));
			devTicks(level, fake, 2);
			use(level, fake, socketPos(sg.n));
			log.accept("statues: sigil " + sg.n + " set " + (sg.state == 3) + " (must be true)");
		}
		log.accept("statues: solved " + solved + " (must be true), strikes total " + totalStrikes + " (must be 1)");
		AuraFx.later(server, 5, done);
	}
}
