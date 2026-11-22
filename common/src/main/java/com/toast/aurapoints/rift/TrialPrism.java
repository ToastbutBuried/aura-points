package com.toast.aurapoints.rift;

import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.Props;
import com.toast.aurapoints.mixin.BlockDisplayAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Prism Chamber (5.49.0): a ruined light temple. A beam leaves its source and runs along the floor's inlaid lanes; mirrors
 * on the lane crossings turn 45 degrees per right-click on their base (sneak turns back), so they pass, block or bend
 * the beam. Coloured prisms tint it; the numbered receivers on the rim must be fed light of their colour, in order, for
 * two seconds each. A wrong receiver overloads (evoker fangs, two mirrors snap back) = a strike; it crackles first, so
 * pull the beam away or block it with your body (it burns). Sealed mirrors can only be turned from their rune stone on
 * the galleries above, linked by a rune line. Hard+: a second beam, and the two must never cross. Extreme+: mirrors drift
 * back toward their start every half minute unless someone stands near them.
 */
final class TrialPrism extends Trials.Trial {

	// --- the lane grid (arena frame): nodes at f = F0 + i*S, l = L0 + j*S; i/j = -1 and NI/NJ are the rim
	static final int S = 4, NI = 9, NJ = 7, F0 = -16, L0 = -12, BEAM_DY = 2;
	private static final int W = NJ + 2, N = (NI + 2) * (NJ + 2);
	private static final int[] DI = {1, 0, -1, 0}, DJ = {0, 1, 0, -1};
	private static final int NONE = 0, MIRROR = 1, FILTER = 2, SOURCE = 3, RECEIVER = 4;
	private static final int END_WALL = 0, END_RECV = 1, END_PLAYER = 2, END_CROSS = 3, END_BLOCK = 4;
	/** Charge time on a receiver before it lights (or overloads), and on a crossing before it overloads. */
	static final int CHARGE = 40, CROSS_CHARGE = 60;

	private static final int[] RECV = {2, 3, 3, 4, 4}, FILT = {0, 1, 2, 2, 3}, DECOY = {2, 3, 4, 5, 6};
	private static final int[] MIN_T = {1, 2, 2, 3, 3}, MAX_T = {3, 3, 4, 5, 5};
	private static final int[] LOCK = {1, 2, 3, 3, 4}, LOCK_SOLO = {0, 1, 1, 2, 2};
	private static final int[] FILTER_RGB = {0x3BD8D8, 0xD83BD8, 0xF28A2E, 0x8A3BD8};
	private static final String[] FILTER_NAME = {"cyan", "magenta", "amber", "violet"};
	private static final int BEAM_B = 0xF0F0F0;
	private static final String[] GLYPHS = {"ᚠ", "ᚢ", "ᚦ", "ᚨ", "ᚱ", "ᚲ"};
	private static final int[] GLYPH_RGB = {0xFF6B6B, 0xFFD166, 0x7BE495, 0x5BC0EB, 0xC77DFF, 0xFFFFFF};
	private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI"};

	static final class Mirror {
		final int i, j;
		int state, home;
		boolean locked;
		int runeF, runeL, glyph;
		Display.BlockDisplay panel, core;
		long driftAt;

		Mirror(int i, int j) {
			this.i = i;
			this.j = j;
		}
	}

	record Filter(int i, int j, int rgb) {
	}

	record Source(int i, int j, int dir, int rgb) {
	}

	static final class Receiver {
		final int i, j, rgb;
		boolean lit;
		int charge;
		Display.TextDisplay tag;

		Receiver(int i, int j, int rgb) {
			this.i = i;
			this.j = j;
			this.rgb = rgb;
		}
	}

	static final class Beam {
		final List<Vec3> pts = new ArrayList<>();
		final List<Integer> cols = new ArrayList<>();
		final List<Integer> nodes = new ArrayList<>();
		int end, recv = -1, col;
		ServerPlayer who;
	}

	final List<Mirror> mirrors = new ArrayList<>();
	final List<Filter> filters = new ArrayList<>();
	final List<Source> sources = new ArrayList<>();
	final List<Receiver> receivers = new ArrayList<>();
	private final int[] kind = new int[N], idx = new int[N];
	/** solution[k] = mirror states that bring receiver k its light (receivers before k lit). */
	int[][] solution = new int[0][];
	/** Each receiver's path while generating: {node key, direction in, required state (-1 pass, -2 filter)}. */
	private final List<List<int[]>> paths = new ArrayList<>();
	private final Set<Integer> crossed = new HashSet<>();
	/** The next receiver to light. */
	int step;
	Beam[] beams = new Beam[0];
	private boolean dirty = true;
	private long lastTrace;
	private int crossCharge;
	private final Map<UUID, Long> burnAt = new HashMap<>();
	private final List<List<Display.BlockDisplay>> segs = new ArrayList<>();
	private final List<String> segSig = new ArrayList<>();
	private final List<Display> pieceDisplays = new ArrayList<>();
	private final List<BlockPos> runeBlocks = new ArrayList<>();
	private boolean solo;

	TrialPrism(Rifts.Run run, int index) {
		super(run, index, Trials.Kind.PRISM);
	}

	@Override
	String name() {
		return "Prism Chamber";
	}

	@Override
	String hint() {
		return "Turn the mirrors (right-click their base, sneak to turn back) to bring the light to the numbered receivers in order, "
				+ "through the prism of their colour. A crackling receiver is the wrong one - pull the beam away or stand in it. Sealed mirrors turn from their rune stone on the galleries.";
	}

	@Override
	int arenaHalf() {
		return 46;
	}

	// ---------------------------------------------------------------
	// Grid helpers
	// ---------------------------------------------------------------

	static int key(int i, int j) {
		return (i + 1) * W + (j + 1);
	}

	static int ki(int key) {
		return key / W - 1;
	}

	static int kj(int key) {
		return key % W - 1;
	}

	static int nf(int i) {
		return F0 + i * S;
	}

	static int nl(int j) {
		return L0 + j * S;
	}

	static boolean insideGrid(int i, int j) {
		return i >= 0 && i < NI && j >= 0 && j < NJ;
	}

	/** The rim node in front of the entrance stays free (it is the doorway's lane). */
	static boolean door(int i, int j) {
		return i == -1 && j == NJ / 2;
	}

	Vec3 node(int i, int j) {
		return Vec3.atCenterOf(arena(nf(i), nl(j), BEAM_DY));
	}

	static int reflect(int state, int d) {
		return switch (state) {
			case 0 -> d % 2 == 0 ? d : -1;
			case 2 -> d % 2 == 1 ? d : -1;
			case 1 -> d ^ 1;
			default -> 3 - d;
		};
	}

	static int pass(int d) {
		return d % 2 == 0 ? 0 : 2;
	}

	int baseColour(int beam) {
		return beam == 0 ? rgb : BEAM_B;
	}

	BlockPos runePos(Mirror m) {
		return arena(m.runeF, m.runeL, 7);
	}

	BlockPos basePos(Mirror m) {
		return arena(nf(m.i), nl(m.j), 1);
	}

	int[] states() {
		int[] s = new int[mirrors.size()];
		for (int k = 0; k < s.length; k++) s[k] = mirrors.get(k).state;
		return s;
	}

	@Override
	Vec3 plinth() {
		return Vec3.atBottomCenterOf(arena(23, 0, 2)).add(0, 0.3, 0);
	}

	// ---------------------------------------------------------------
	// Tracing
	// ---------------------------------------------------------------

	Beam trace(int b, int[] st, List<ServerPlayer> players) {
		Source s = sources.get(b);
		int i = s.i(), j = s.j(), d = s.dir(), col = s.rgb();
		Beam out = new Beam();
		out.pts.add(node(i, j));
		out.nodes.add(key(i, j));
		Set<Integer> seen = new HashSet<>();
		for (int n = 0; n < 90; n++) {
			int ni = i + DI[d], nj = j + DJ[d];
			if (ni < -1 || ni > NI || nj < -1 || nj > NJ) {
				out.end = END_WALL;
				out.col = col;
				return out;
			}
			Vec3 a = out.pts.get(out.pts.size() - 1), to = node(ni, nj);
			if (players != null) {
				ServerPlayer hit = null;
				Vec3 hp = null;
				double best = Double.MAX_VALUE;
				for (ServerPlayer p : players) {
					if (p.isSpectator()) continue;
					Optional<Vec3> c = p.getBoundingBox().inflate(0.05).clip(a, to);
					if (c.isPresent() && c.get().distanceToSqr(a) < best) {
						best = c.get().distanceToSqr(a);
						hit = p;
						hp = c.get();
					}
				}
				if (hit != null) {
					out.pts.add(hp);
					out.cols.add(col);
					out.end = END_PLAYER;
					out.who = hit;
					out.col = col;
					return out;
				}
			}
			out.cols.add(col);
			out.pts.add(to);
			int k = key(ni, nj);
			out.nodes.add(k);
			i = ni;
			j = nj;
			out.col = col;
			if (!insideGrid(i, j)) {
				if (kind[k] == RECEIVER) {
					out.end = END_RECV;
					out.recv = idx[k];
				} else out.end = END_WALL;
				return out;
			}
			switch (kind[k]) {
				case MIRROR -> {
					int nd = reflect(st[idx[k]], d);
					if (nd < 0) {
						out.end = END_BLOCK;
						return out;
					}
					d = nd;
				}
				case FILTER -> {
					col = filters.get(idx[k]).rgb();
					out.col = col;
				}
				default -> {
				}
			}
			if (!seen.add(k * 4 + d)) {
				out.end = END_BLOCK;
				return out;
			}
		}
		out.end = END_BLOCK;
		return out;
	}

	private static void truncate(Beam b, int n) {
		while (b.pts.size() > n + 1) b.pts.remove(b.pts.size() - 1);
		while (b.cols.size() > n) b.cols.remove(b.cols.size() - 1);
		while (b.nodes.size() > n + 1) b.nodes.remove(b.nodes.size() - 1);
		b.end = END_CROSS;
		b.recv = -1;
		b.who = null;
	}

	Beam[] traceAll(int[] st, List<ServerPlayer> players) {
		Beam[] out = new Beam[sources.size()];
		for (int b = 0; b < out.length; b++) out[b] = trace(b, st, players);
		if (out.length == 2) {
			Map<Integer, Integer> at = new HashMap<>();
			for (int n = 1; n < out[0].nodes.size(); n++) at.putIfAbsent(out[0].nodes.get(n), n);
			for (int n = 1; n < out[1].nodes.size(); n++) {
				Integer ia = at.get(out[1].nodes.get(n));
				if (ia != null) {
					truncate(out[0], ia);
					truncate(out[1], n);
					break;
				}
			}
		}
		return out;
	}

	/** With {@code st} and receivers before k lit, receiver k gets its colour and nothing else goes wrong. */
	boolean validFor(int k, int[] st) {
		boolean got = false;
		for (Beam b : traceAll(st, null)) {
			if (b.end == END_CROSS) return false;
			if (b.end != END_RECV || b.recv < k) continue;
			if (b.recv == k && b.col == receivers.get(k).rgb) got = true;
			else return false;
		}
		return got;
	}

	/** No receiver fed, nothing crossing: a safe starting layout. */
	boolean calm(int[] st) {
		for (Beam b : traceAll(st, null)) if (b.end == END_CROSS || b.end == END_RECV) return false;
		return true;
	}

	// ---------------------------------------------------------------
	// Generating a layout (model only; placePieces puts it in the world)
	// ---------------------------------------------------------------

	private void clearModel() {
		mirrors.clear();
		filters.clear();
		sources.clear();
		receivers.clear();
		paths.clear();
		crossed.clear();
		Arrays.fill(kind, NONE);
		Arrays.fill(idx, 0);
	}

	private List<int[]> rim() {
		List<int[]> per = new ArrayList<>();
		for (int j = 0; j < NJ; j++) {
			per.add(new int[]{-1, j, 0});
			per.add(new int[]{NI, j, 2});
		}
		for (int i = 0; i < NI; i++) {
			per.add(new int[]{i, -1, 1});
			per.add(new int[]{i, NJ, 3});
		}
		per.removeIf(p -> door(p[0], p[1]));
		return per;
	}

	boolean generate(RandomSource r, int nRecv) {
		clearModel();
		List<int[]> per = rim();
		int nSrc = tier >= 2 ? 2 : 1;
		int[] a = per.get(r.nextInt(per.size()));
		addPiece(a[0], a[1], SOURCE, sources.size());
		sources.add(new Source(a[0], a[1], a[2], baseColour(0)));
		if (nSrc == 2) {
			for (int t = 0; t < 40; t++) {
				int[] b = per.get(r.nextInt(per.size()));
				if (b[2] == a[2] || kind[key(b[0], b[1])] != NONE) continue;
				addPiece(b[0], b[1], SOURCE, sources.size());
				sources.add(new Source(b[0], b[1], b[2], baseColour(1)));
				break;
			}
			if (sources.size() < 2) return false;
		}
		int nFilt = Math.min(FILT[tier], nRecv - 1);
		int[] pool = FILTER_RGB.clone();
		for (int k = pool.length - 1; k > 0; k--) {
			int o = r.nextInt(k + 1), t = pool[k];
			pool[k] = pool[o];
			pool[o] = t;
		}
		for (int k = 0; k < nRecv; k++) {
			int beam = nSrc == 2 ? k % 2 : 0;
			int need = k >= 1 && k <= nFilt ? pool[k - 1] : baseColour(beam);
			boolean ok = false;
			for (int t = 0; t < 60 && !ok; t++) ok = walk(k, beam, need, r);
			if (!ok) return false;
		}
		// decoys: more mirrors on free crossings
		for (int n = 0, t = 0; n < DECOY[tier] && t < 200; t++) {
			int i = r.nextInt(NI), j = r.nextInt(NJ);
			if (kind[key(i, j)] != NONE) continue;
			addPiece(i, j, MIRROR, mirrors.size());
			mirrors.add(new Mirror(i, j));
			n++;
		}
		// the solutions
		solution = new int[nRecv][];
		int[] prev = new int[mirrors.size()];
		for (int m = 0; m < prev.length; m++) prev[m] = r.nextInt(4);
		for (int k = 0; k < nRecv; k++) {
			int[] st = prev.clone();
			Set<Integer> on = new HashSet<>();
			for (int[] rec : paths.get(k)) {
				if (kind[rec[0]] != MIRROR) continue;
				int m = idx[rec[0]];
				st[m] = rec[2] >= 0 ? rec[2] : pass(rec[1]);
				on.add(m);
			}
			boolean ok = validFor(k, st);
			for (int t = 0; t < 40 && !ok; t++) {
				for (int m = 0; m < st.length; m++) if (!on.contains(m)) st[m] = r.nextInt(4);
				ok = validFor(k, st);
			}
			if (!ok) return false;
			solution[k] = st;
			prev = st;
		}
		// the scramble everyone starts from (and mirrors snap back to)
		int[] home = null;
		for (int t = 0; t < 120 && home == null; t++) {
			int[] h = new int[mirrors.size()];
			int diff = 0;
			for (int m = 0; m < h.length; m++) {
				h[m] = r.nextInt(4);
				if (h[m] != solution[0][m]) diff++;
			}
			if (diff >= Math.min(3, h.length) && calm(h)) home = h;
		}
		if (home == null) return false;
		for (int m = 0; m < mirrors.size(); m++) {
			mirrors.get(m).state = home[m];
			mirrors.get(m).home = home[m];
		}
		// sealed mirrors: the ones the solutions turn at, turned from a gallery
		Set<Integer> turning = new HashSet<>();
		for (List<int[]> path : paths) for (int[] rec : path) if (rec[2] == 1 || rec[2] == 3) turning.add(idx[rec[0]]);
		List<Integer> cand = new ArrayList<>(turning);
		for (int k = cand.size() - 1; k > 0; k--) {
			int o = r.nextInt(k + 1), t = cand.get(k);
			cand.set(k, cand.get(o));
			cand.set(o, t);
		}
		int nLock = Math.min(cand.size(), solo ? LOCK_SOLO[tier] : LOCK[tier]);
		Set<Long> used = new HashSet<>();
		for (int n = 0; n < nLock; n++) {
			Mirror m = mirrors.get(cand.get(n));
			m.locked = true;
			m.glyph = n % GLYPHS.length;
			// the rune stone stands on the gallery across the hall, level with the mirror
			int side = m.j <= NJ / 2 ? 1 : -1;
			int f = Math.max(-14, Math.min(22, nf(m.i)));
			while (used.contains(((long) f << 8) | (side & 0xFF))) f += 2;
			used.add(((long) f << 8) | (side & 0xFF));
			m.runeF = Math.min(24, f);
			m.runeL = side * 23;
		}
		return true;
	}

	private void addPiece(int i, int j, int k, int index) {
		kind[key(i, j)] = k;
		idx[key(i, j)] = index;
	}

	/** A random walk of the beam from its source to a new receiver k on the rim, placing mirrors and its prism. */
	private boolean walk(int k, int beam, int need, RandomSource r) {
		Source s = sources.get(beam);
		int i = s.i(), j = s.j(), d = s.dir();
		boolean wantF = need != s.rgb(), passed = false;
		int turns = 0, straight = 0, minT = MIN_T[tier], maxT = MAX_T[tier];
		List<int[]> rec = new ArrayList<>();
		List<Integer> added = new ArrayList<>();
		Set<Integer> seen = new HashSet<>();
		for (int n = 0; n < 50; n++) {
			int dIn = d, ni = i + DI[d], nj = j + DJ[d], key = key(ni, nj);
			if (!insideGrid(ni, nj)) {
				if (turns >= minT && (!wantF || passed) && kind[key] == NONE && !door(ni, nj)) {
					addPiece(ni, nj, RECEIVER, receivers.size());
					receivers.add(new Receiver(ni, nj, need));
					paths.add(rec);
					crossed.addAll(seen);
					return true;
				}
				undo(added);
				return false;
			}
			if (!seen.add(key)) {
				undo(added);
				return false;
			}
			switch (kind[key]) {
				case MIRROR -> {
					int st;
					if (turns < maxT && r.nextFloat() < 0.55f) {
						st = r.nextBoolean() ? 1 : 3;
						d = reflect(st, d);
						turns++;
						straight = 0;
					} else {
						st = pass(d);
						straight++;
					}
					rec.add(new int[]{key, dIn, st});
				}
				case NONE -> {
					if (wantF && !passed && turns >= 1 && !crossed.contains(key) && r.nextFloat() < 0.4f) {
						addPiece(ni, nj, FILTER, filters.size());
						filters.add(new Filter(ni, nj, need));
						added.add(key);
						passed = true;
						rec.add(new int[]{key, dIn, -2});
						straight++;
					} else if (turns < maxT && straight >= 1 && r.nextFloat() < 0.25f + 0.12f * straight) {
						addPiece(ni, nj, MIRROR, mirrors.size());
						mirrors.add(new Mirror(ni, nj));
						added.add(key);
						int st = r.nextBoolean() ? 1 : 3;
						d = reflect(st, d);
						turns++;
						straight = 0;
						rec.add(new int[]{key, dIn, st});
					} else {
						straight++;
						rec.add(new int[]{key, dIn, -1});
					}
				}
				default -> {
					undo(added);
					return false;
				}
			}
			i = ni;
			j = nj;
		}
		undo(added);
		return false;
	}

	private void undo(List<Integer> added) {
		for (int n = added.size() - 1; n >= 0; n--) {
			int key = added.get(n);
			if (kind[key] == MIRROR) mirrors.remove(mirrors.size() - 1);
			else if (kind[key] == FILTER) filters.remove(filters.size() - 1);
			kind[key] = NONE;
		}
	}

	private void newLayout(RandomSource r) {
		solo = run.players.size() <= 1;
		int nRecv = RECV[tier];
		for (int t = 0; t < 400; t++) {
			if (t > 0 && t % 100 == 0 && nRecv > 1) nRecv--;
			if (generate(r, nRecv)) return;
		}
		com.toast.aurapoints.AuraPoints.LOGGER.warn("[rift] Prism Chamber: no layout found");
	}

	// ---------------------------------------------------------------
	// Building
	// ---------------------------------------------------------------

	/** The floor: lane inlays (edge), crossing sockets (accent), lights in every other cell, clustered floor and base. */
	Block floorAt(int f, int l) {
		int rf = Math.floorMod(f - F0, S), rl = Math.floorMod(l - L0, S);
		if (f >= nf(-1) && f <= nf(NI) && l >= nl(-1) && l <= nl(NJ)) {
			if (rf == 0 && rl == 0) return pal.accent();
			if (rf == 0 || rl == 0) return pal.edge();
			if (rf == 2 && rl == 2 && RuinKit.hash(f, l, 1) % 2 == 0) return pal.light();
		}
		return RuinKit.hash(Math.floorDiv(f, 3), Math.floorDiv(l, 3), 3) < 24 ? pal.base() : pal.floor();
	}

	@Override
	void build(ServerLevel level, RandomSource r) {
		// the hall's floor and its rocky underside
		for (int f = -26; f <= 26; f++) {
			for (int l = -26; l <= 26; l++) {
				set(level, f, l, 0, floorAt(f, l));
				RuinKit.underside(this, level, f, l, 1 - Math.max(Math.abs(f), Math.abs(l)) / 28.0, 14);
			}
		}
		causeway(level, r);
		walls(level, r);
		for (int side : new int[]{-1, 1}) gallery(level, side);
		vault(level, r);
		apse(level);
		// braziers in the corners and at the door, rubble in the cells (never on a lane)
		for (int[] c : new int[][]{{23, 18}, {23, -18}, {-23, 18}, {-23, -18}, {-24, 5}, {-24, -5}}) RuinKit.brazier(this, level, c[0], c[1], 1, false);
		for (int n = 0; n < 16; n++) {
			int f = nf(r.nextInt(NI - 1)) + (r.nextBoolean() ? 1 : 3), l = nl(r.nextInt(NJ - 1)) + (r.nextBoolean() ? 1 : 3);
			set(level, f, l, 1, r.nextBoolean() ? pal.base() : pal.edge());
			if (r.nextInt(3) == 0) set(level, f, l, 2, pal.base());
		}
		// floating debris over the void
		for (int n = 0; n < 18; n++) {
			double ang = r.nextDouble() * Math.PI * 2, dist = 32 + r.nextDouble() * 11;
			int f = (int) Math.round(Math.cos(ang) * dist), l = (int) Math.round(Math.sin(ang) * dist);
			if (Math.max(Math.abs(f), Math.abs(l)) < 30 || (f < -26 && Math.abs(l) < 7)) continue;
			RuinKit.debris(this, level, r, f, l, -16 + r.nextInt(36), 1 + r.nextInt(3));
		}
		newLayout(r);
		placePieces(level);
	}

	private void causeway(ServerLevel level, RandomSource r) {
		for (int f = -46; f <= -27; f++) {
			for (int l = -2; l <= 2; l++) {
				if (Math.abs(l) == 2 && f < -30 && RuinKit.hash(f, l, 4) < 14) continue; // a chipped edge
				set(level, f, l, 0, l == 0 ? pal.accent() : pal.floor());
				set(level, f, l, -1, pal.base());
				if (Math.abs(l) <= 1) set(level, f, l, -2, pal.base());
			}
			if (Math.floorMod(f, 6) == 0) {
				for (int l : new int[]{-3, 3}) {
					set(level, f, l, 0, pal.edge());
					set(level, f, l, -1, pal.base());
					set(level, f, l, 1, pal.edge());
					set(level, f, l, 2, pal.light());
				}
			}
		}
		// a broken arch over the approach, its keystone drifting loose above the gap
		for (int l : new int[]{-4, 4}) {
			for (int dy = -2; dy <= 9; dy++) set(level, -36, l, dy, dy <= 0 ? pal.base() : pal.edge());
			set(level, -36, l, 10, pal.accent());
		}
		for (int l = -4; l <= 4; l++) if (l < 0 || l > 2) set(level, -36, l, 10, l == -4 || l == 4 ? pal.accent() : pal.edge());
		set(level, -36, 1, 13, pal.accent());
		set(level, -36, 2, 12, pal.edge());
	}

	private void walls(ServerLevel level, RandomSource r) {
		// side walls (l = +-26), the back wall with the door (f = -26), the front wall with a rose window (f = 26)
		for (int side : new int[]{-1, 1}) {
			for (int f = -26; f <= 26; f++) {
				int l = side * 26;
				int bay = Math.floorDiv(f + 26, 8);
				int top = 15 - (RuinKit.hash(bay, side, 2) < 30 ? 4 + RuinKit.hash(bay, side, 3) % 3 : 0) - (RuinKit.hash(f, side, 9) < 15 ? 1 : 0);
				boolean pilaster = Math.floorMod(f, 4) == 0;
				boolean window = Math.floorMod(f, 8) == 4 && Math.abs(f) <= 22;
				for (int dy = 1; dy <= top; dy++) {
					Block b = dy <= 2 || (dy <= 4 && RuinKit.hash(f, dy, side) < 50) ? pal.base() : pilaster ? pal.base() : pal.edge();
					if (window && dy >= 9 && dy <= 12) b = pal.gate();
					set(level, f, l, dy, b);
				}
				set(level, f, l, top + 1, pilaster ? pal.light() : pal.accent());
				if (top < 13) {
					// the fallen part lies at the wall's foot
					set(level, f, l - side, 1, RuinKit.hash(f, 1, side) < 50 ? pal.base() : pal.edge());
					if (RuinKit.hash(f, 2, side) < 30) set(level, f, l - side * 2, 1, pal.edge());
				}
				if (Math.floorMod(f, 8) == 0 && top >= 13 && Math.abs(f) <= 24) RuinKit.banner(this, level, f, l - side, 11, 0, -side);
			}
		}
		for (int l = -26; l <= 26; l++) {
			// back wall
			int topB = 15 - (RuinKit.hash(l, 5, 2) < 20 ? 3 : 0);
			for (int dy = 1; dy <= topB; dy++) {
				boolean doorway = Math.abs(l) <= 2 && dy <= 5 || Math.abs(l) <= 1 && dy == 6;
				set(level, -26, l, dy, doorway ? Blocks.AIR : dy <= 2 || Math.floorMod(l, 4) == 0 ? pal.base() : pal.edge());
			}
			set(level, -26, l, topB + 1, Math.floorMod(l, 4) == 0 ? pal.light() : pal.accent());
			// front wall: taller, with the rose window
			for (int dy = 1; dy <= 20; dy++) {
				double dl = l, dz = dy - 12, d = Math.sqrt(dl * dl + dz * dz);
				Block b = dy <= 2 || Math.floorMod(l, 4) == 0 ? pal.base() : pal.edge();
				if (d <= 6.6) {
					b = d > 5.4 ? pal.accent() : (l == 0 || dy == 12 || Math.abs(l) == Math.abs(dy - 12)) && d > 1.2 ? pal.edge() : d <= 1.2 ? pal.light() : pal.gate();
				}
				set(level, 26, l, dy, b);
			}
			set(level, 26, l, 21, Math.floorMod(l, 4) == 0 ? pal.light() : pal.accent());
		}
		// the door frame and a banner either side of the rose
		for (int dy = 1; dy <= 6; dy++) for (int l : new int[]{-3, 3}) set(level, -26, l, dy, pal.accent());
		for (int l = -2; l <= 2; l++) set(level, -26, l, 7, pal.accent());
		RuinKit.banner(this, level, 25, -9, 14, -1, 0);
		RuinKit.banner(this, level, 25, 9, 14, -1, 0);
	}

	/** A raised gallery along one side (l = side * 20..25, floor dy 6) on arches, with stairs up at the door end. */
	private void gallery(ServerLevel level, int side) {
		for (int f = -17; f <= 25; f++) {
			for (int w = 20; w <= 25; w++) set(level, f, side * w, 6, w == 20 ? pal.accent() : pal.floor());
			set(level, f, side * 20, 7, pal.edge());
			if (Math.floorMod(f, 8) == 0) set(level, f, side * 20, 8, pal.light());
			int m = Math.floorMod(f, 4);
			if (m == 0) for (int dy = 1; dy <= 5; dy++) set(level, f, side * 20, dy, pal.edge());
			else if (m == 1 || m == 3) set(level, f, side * 20, 5, pal.edge());
			// lights in the gallery floor's underside
			if (m == 2) set(level, f, side * 23, 5, pal.light());
		}
		var up = RuinKit.stairs(pal, RuinKit.dir(this, 1, 0));
		for (int f = -22; f <= -18; f++) {
			int dy = f + 23;
			for (int w = 21; w <= 23; w++) {
				for (int k = 1; k < dy; k++) set(level, f, side * w, k, pal.base());
				set(level, f, side * w, dy, up);
			}
			for (int k = 1; k <= dy; k++) set(level, f, side * 20, k, pal.edge());
		}
	}

	/** Broken ribs of the vault over the hall, lanterns hanging from the whole ones, masonry floating where they broke. */
	private void vault(ServerLevel level, RandomSource r) {
		for (int f = -20; f <= 20; f += 8) {
			boolean broken = RuinKit.hash(f, 0, 5) < 45;
			int gap = 3 + RuinKit.hash(f, 1, 5) % 9;
			for (int l = -25; l <= 25; l++) {
				if (broken && Math.abs(l - 2) < gap) continue;
				double t = l / 26.0;
				int y = 15 + (int) Math.round(9 * Math.sqrt(1 - t * t));
				set(level, f, l, y, pal.edge());
				set(level, f, l, y + 1, Math.abs(l) % 6 == 0 ? pal.accent() : pal.edge());
			}
			if (broken) RuinKit.debris(this, level, r, f, 2, 28 + r.nextInt(4), 1);
			for (int l : new int[]{-10, 10}) {
				if (broken && Math.abs(l - 2) < gap) continue;
				double t = l / 26.0;
				int y = 15 + (int) Math.round(9 * Math.sqrt(1 - t * t));
				for (int dy = y - 1; dy >= 16; dy--) set(level, f, l, dy, Blocks.CHAIN);
				set(level, f, l, 15, RuinKit.hangingLantern(false));
			}
		}
	}

	/** Where the freed shard rises: a dais under the rose window between two tall lit pillars. */
	private void apse(ServerLevel level) {
		for (int f = 22; f <= 25; f++) for (int l = -3; l <= 3; l++) set(level, f, l, 1, Math.abs(l) == 3 || f == 25 ? pal.edge() : pal.accent());
		set(level, 23, 0, 1, pal.light());
		for (int l : new int[]{-5, 5}) RuinKit.pillar(this, level, 23, l, 1, 12, false, RandomSource.create(l));
	}

	// ---------------------------------------------------------------
	// Pieces (rebuilt on a new layout)
	// ---------------------------------------------------------------

	private Transformation panelT(int state, float w, float h, float d) {
		double th = Math.toRadians(state * 45.0);
		double ux = Math.cos(th) * fx + Math.sin(th) * lx, uz = Math.cos(th) * fz + Math.sin(th) * lz;
		Quaternionf rot = new Quaternionf().rotationY((float) Math.atan2(-uz, ux));
		return RuinKit.centred(new Vector3f(0f, 0f, 0f), w, h, d, rot);
	}

	private void placePieces(ServerLevel level) {
		for (Mirror m : mirrors) {
			set(level, nf(m.i), nl(m.j), 0, pal.light());
			set(level, nf(m.i), nl(m.j), 1, m.locked ? Blocks.REINFORCED_DEEPSLATE : pal.accent());
			Vec3 c = node(m.i, m.j);
			m.panel = RuinKit.prop(this, level, c, Blocks.WHITE_STAINED_GLASS.defaultBlockState(), panelT(m.state, 1.4f, 1.0f, 0.1f), 0, true);
			m.core = RuinKit.prop(this, level, c, Blocks.IRON_BLOCK.defaultBlockState(), panelT(m.state, 1.2f, 0.85f, 0.04f), 0, true);
			pieceDisplays.add(m.panel);
			pieceDisplays.add(m.core);
			if (m.locked) {
				int col = GLYPH_RGB[m.glyph];
				Component g = Component.literal(GLYPHS[m.glyph]).withStyle(s -> s.withColor(col).withBold(true));
				pieceDisplays.add(label(level, c.add(0, 1.0, 0), g, 1.6f));
				BlockPos rp = runePos(m);
				RealmBuilder.set(level, rp.getX(), rp.getY(), rp.getZ(), Blocks.LODESTONE);
				runeBlocks.add(rp);
				pieceDisplays.add(label(level, Vec3.atBottomCenterOf(rp).add(0, 1.3, 0), g, 1.6f));
			}
		}
		for (Filter fl : filters) {
			set(level, nf(fl.i()), nl(fl.j()), 1, pal.edge());
			Quaternionf rot = new Quaternionf().rotationY((float) Math.PI / 4f).rotateX((float) Math.toRadians(35));
			Display.BlockDisplay d = RuinKit.prop(this, level, node(fl.i(), fl.j()), RuinKit.glassFor(fl.rgb()).defaultBlockState(), RuinKit.centred(new Vector3f(), 0.7f, 0.7f, 0.7f, rot), 0, true);
			pieceDisplays.add(d);
		}
		for (Source s : sources) {
			set(level, nf(s.i()), nl(s.j()), 1, pal.edge());
			set(level, nf(s.i()), nl(s.j()), 2, pal.light());
			set(level, nf(s.i()), nl(s.j()), 3, pal.accent());
		}
		for (int k = 0; k < receivers.size(); k++) {
			Receiver rc = receivers.get(k);
			set(level, nf(rc.i), nl(rc.j), 1, pal.edge());
			set(level, nf(rc.i), nl(rc.j), 2, RuinKit.glassFor(rc.rgb));
			set(level, nf(rc.i), nl(rc.j), 3, pal.accent());
			int col = rc.rgb;
			rc.tag = label(level, Vec3.atBottomCenterOf(arena(nf(rc.i), nl(rc.j), 4)).add(0, 0.3, 0), Component.literal(ROMAN[k]).withStyle(s -> s.withColor(col).withBold(true)), 2.0f);
			pieceDisplays.add(rc.tag);
		}
		step = 0;
		crossCharge = 0;
		dirty = true;
	}

	private void clearPieces(ServerLevel level) {
		for (int key = 0; key < N; key++) {
			if (kind[key] == NONE) continue;
			int f = nf(ki(key)), l = nl(kj(key));
			set(level, f, l, 0, floorAt(f, l));
			for (int dy = 1; dy <= 3; dy++) set(level, f, l, dy, Blocks.AIR);
		}
		for (BlockPos p : runeBlocks) RealmBuilder.set(level, p.getX(), p.getY(), p.getZ(), Blocks.AIR);
		runeBlocks.clear();
		for (Display d : pieceDisplays) RuinKit.drop(this, d);
		pieceDisplays.clear();
		for (List<Display.BlockDisplay> list : segs) for (Display.BlockDisplay d : list) RuinKit.drop(this, d);
		segs.clear();
		segSig.clear();
	}

	// ---------------------------------------------------------------
	// Playing
	// ---------------------------------------------------------------

	@Override
	void begin(ServerLevel level, List<ServerPlayer> here) {
		long now = level.getGameTime();
		for (Mirror m : mirrors) m.driftAt = now + driftEvery() + 200;
		dirty = true;
	}

	private int driftEvery() {
		return tier >= 4 ? 450 : 600;
	}

	@Override
	void tick(ServerLevel level, List<ServerPlayer> here, long now) {
		if (solved || receivers.isEmpty()) return;
		if (dirty || now % 3 == 0) {
			dirty = false;
			if (retrace(level, here, now)) return;
		}
		if (now % 5 == 0) drawBeams(level, here);
		if (now % 20 == 0) cables(level, here, false, null);
		if (tier >= 3 && now % 20 == 0) drift(level, here, now);
		if (now % 40 == 0) hints(level, here);
	}

	/** Traces the beams; burns blockers, charges receivers and crossings. True = the layout changed under us (overload). */
	private boolean retrace(ServerLevel level, List<ServerPlayer> here, long now) {
		beams = traceAll(states(), here);
		updateSegments(level);
		long dt = lastTrace == 0 ? 3 : Math.max(1, Math.min(10, now - lastTrace));
		lastTrace = now;
		Vec3 cross = null;
		for (Beam b : beams) {
			Vec3 end = b.pts.get(b.pts.size() - 1);
			if (b.end == END_PLAYER && b.who != null) {
				Long t = burnAt.get(b.who.getUUID());
				if (t == null || now >= t) {
					burnAt.put(b.who.getUUID(), now + 10);
					b.who.hurt(level.damageSources().inFire(), 1f + tier * 0.5f);
					b.who.igniteForSeconds(1f);
				}
				level.sendParticles(ParticleTypes.FLAME, end.x, end.y, end.z, 3, 0.1, 0.1, 0.1, 0.01);
			}
			if (b.end == END_CROSS) cross = end;
		}
		if (cross != null) {
			crossCharge += (int) dt;
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, cross.x, cross.y, cross.z, 6, 0.2, 0.2, 0.2, 0.15);
			if (now % 9 < 3) level.playSound(null, cross.x, cross.y, cross.z, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.6f, 1.8f);
			if (crossCharge >= CROSS_CHARGE) {
				crossCharge = 0;
				actionBar(here, "The beams crossed - overload!", ChatFormatting.RED);
				overload(level, cross, here);
				return true;
			}
		} else crossCharge = 0;
		boolean[] touched = new boolean[receivers.size()];
		for (Beam b : beams) {
			if (b.end != END_RECV) continue;
			Receiver rc = receivers.get(b.recv);
			if (rc.lit) continue;
			touched[b.recv] = true;
			rc.charge += (int) dt;
			boolean good = b.recv == step && b.col == rc.rgb;
			Vec3 at = node(rc.i, rc.j);
			if (good) {
				level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.6, at.z, 2 + rc.charge / 10, 0.25, 0.4, 0.25, 0.01);
				if (now % 9 < 3) level.playSound(null, at.x, at.y, at.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1f, 0.6f + rc.charge / 40f);
			} else {
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.5, at.z, 3 + rc.charge / 8, 0.3, 0.4, 0.3, 0.1);
				if (now % 9 < 3) level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.7f, 1.6f);
				if (now % 18 < 3) actionBar(here, "⚠ Receiver " + ROMAN[b.recv] + " is overloading - pull the light away!", ChatFormatting.GOLD);
			}
			if (rc.charge >= CHARGE) {
				rc.charge = 0;
				if (good) {
					light(level, rc, here);
					if (solved) return true;
				} else {
					overload(level, at, here);
					return true;
				}
			}
		}
		for (int k = 0; k < touched.length; k++) if (!touched[k]) receivers.get(k).charge = 0;
		return false;
	}

	private void light(ServerLevel level, Receiver rc, List<ServerPlayer> here) {
		rc.lit = true;
		set(level, nf(rc.i), nl(rc.j), 2, pal.light());
		Vec3 at = node(rc.i, rc.j);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.5f, 1.2f);
		level.sendParticles(AuraFx.dust(rc.rgb, 1.6f), at.x, at.y + 2, at.z, 30, 0.3, 2.5, 0.3, 0);
		int k = receivers.indexOf(rc);
		if (rc.tag != null) com.toast.aurapoints.arena.Holograms.setTextPublic(rc.tag, Component.literal("✔ " + ROMAN[k]).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		step++;
		if (step >= receivers.size()) {
			finish(level, here);
			return;
		}
		actionBar(here, "Receiver " + ROMAN[k] + " lit - " + (receivers.size() - step) + " to go", ChatFormatting.AQUA);
	}

	private void finish(ServerLevel level, List<ServerPlayer> here) {
		Vec3 shard = plinth();
		for (Receiver rc : receivers) {
			Vec3 from = node(rc.i, rc.j).add(0, 1.5, 0);
			AuraFx.line(level, from, shard.add(0, 1, 0), AuraFx.dust(rc.rgb, 1.4f), 30);
		}
		level.playSound(null, shard.x, shard.y, shard.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.2f, 1.3f);
		level.playSound(null, shard.x, shard.y, shard.z, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.5f, 0.8f);
		solve(level, shard.add(0, 0.6, 0));
	}

	/** A wrong receiver (or crossed beams) overloads: fangs burst up around it, two mirrors snap back, a strike. */
	private void overload(ServerLevel level, Vec3 at, List<ServerPlayer> here) {
		int fangs = 5 + tier;
		for (int k = 0; k < fangs; k++) {
			double a = Math.PI * 2 * k / fangs;
			level.addFreshEntity(new EvokerFangs(level, at.x + Math.cos(a) * 1.8, Rifts.FLOOR + 1, at.z + Math.sin(a) * 1.8, (float) Math.toDegrees(a), k * 2, null));
		}
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.8f, 1.6f);
		level.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		List<Mirror> moved = new ArrayList<>();
		for (Mirror m : mirrors) if (m.state != m.home) moved.add(m);
		RandomSource r = level.random;
		for (int n = 0; n < 2 && !moved.isEmpty(); n++) snapHome(level, moved.remove(r.nextInt(moved.size())));
		for (Receiver rc : receivers) rc.charge = 0;
		ServerPlayer who = null;
		double best = Double.MAX_VALUE;
		for (ServerPlayer p : here) if (p.distanceToSqr(at) < best) {
			best = p.distanceToSqr(at);
			who = p;
		}
		dirty = true;
		strike(level, who, at, here);
	}

	private void snapHome(ServerLevel level, Mirror m) {
		m.state = m.home;
		turnDisplays(m);
		Vec3 c = node(m.i, m.j);
		level.sendParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 6, 0.3, 0.3, 0.3, 0.02);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.8f, 0.7f);
	}

	private void turnDisplays(Mirror m) {
		if (m.panel != null) Props.set(m.panel, panelT(m.state, 1.4f, 1.0f, 0.1f), 5);
		if (m.core != null) Props.set(m.core, panelT(m.state, 1.2f, 0.85f, 0.04f), 5);
		dirty = true;
	}

	void turn(ServerLevel level, Mirror m, int by) {
		m.state = Math.floorMod(m.state + by, 4);
		turnDisplays(m);
		m.driftAt = level.getGameTime() + driftEvery();
		Vec3 c = node(m.i, m.j);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.8f, 0.8f);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1f, 0.8f + m.state * 0.15f);
	}

	@Override
	boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
		if (solved) return false;
		for (Mirror m : mirrors) {
			if (pos.equals(basePos(m))) {
				if (m.locked) {
					p.displayClientMessage(Component.literal("Sealed - turn it from its rune stone (" + GLYPHS[m.glyph] + ") on the " + (m.runeL > 0 ? "left" : "right") + " gallery")
							.withStyle(ChatFormatting.GOLD), true);
					cables(level, List.of(p), true, m);
					level.playSound(null, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, SoundEvents.CHAIN_HIT, SoundSource.BLOCKS, 1f, 0.6f);
					return true;
				}
				turn(level, m, p.isShiftKeyDown() ? -1 : 1);
				return true;
			}
			if (m.locked && pos.equals(runePos(m))) {
				turn(level, m, p.isShiftKeyDown() ? -1 : 1);
				// the flash runs along the line for everyone, so the mirror's partner sees what moved
				List<ServerPlayer> all = RuinKit.arenaPlayers(this, level);
				if (!all.contains(p)) all.add(p);
				cables(level, all, true, m);
				return true;
			}
		}
		return false;
	}

	@Override
	void release(ServerPlayer p) {
		burnAt.remove(p.getUUID());
	}

	// --- drawing

	private static double dirKey(Vec3 a, Vec3 b) {
		Vec3 d = b.subtract(a);
		return Math.round(Math.atan2(d.z, d.x) * 100) + (Math.abs(d.y) > 0.01 ? 1000 : 0);
	}

	/** Corner points of a beam (where it turns or changes colour), with each run's colour. */
	private static List<Object[]> runs(Beam b) {
		List<Object[]> out = new ArrayList<>();
		if (b.pts.size() < 2) return out;
		Vec3 start = b.pts.get(0);
		int col = b.cols.get(0);
		double dk = dirKey(b.pts.get(0), b.pts.get(1));
		for (int m = 1; m < b.pts.size() - 1; m++) {
			int c = b.cols.get(m);
			double k = dirKey(b.pts.get(m), b.pts.get(m + 1));
			if (c != col || k != dk) {
				out.add(new Object[]{start, b.pts.get(m), col});
				start = b.pts.get(m);
				col = c;
				dk = k;
			}
		}
		out.add(new Object[]{start, b.pts.get(b.pts.size() - 1), col});
		return out;
	}

	/** The thin glowing glass rods along each beam; reshaped in place when the route changes. */
	private void updateSegments(ServerLevel level) {
		while (segs.size() < beams.length) {
			segs.add(new ArrayList<>());
			segSig.add("");
		}
		for (int b = 0; b < beams.length; b++) {
			List<Object[]> rs = runs(beams[b]);
			StringBuilder sig = new StringBuilder();
			for (Object[] r : rs) sig.append(r[0]).append(r[1]).append(r[2]).append(';');
			if (sig.toString().equals(segSig.get(b))) continue;
			segSig.set(b, sig.toString());
			List<Display.BlockDisplay> list = segs.get(b);
			for (int s = 0; s < rs.size(); s++) {
				Vec3 from = (Vec3) rs.get(s)[0], to = (Vec3) rs.get(s)[1];
				int col = (Integer) rs.get(s)[2];
				Vector3f rel = new Vector3f((float) (to.x - from.x), (float) (to.y - from.y), (float) (to.z - from.z));
				Transformation tr = RuinKit.rod(new Vector3f(), rel, 0.09f);
				BlockState glass = RuinKit.glassFor(col).defaultBlockState();
				Display.BlockDisplay d;
				if (s < list.size()) {
					d = list.get(s);
					Props.move(d, from);
					Props.set(d, tr, 0);
					((BlockDisplayAccessor) d).aurapoints$setBlockState(glass);
				} else {
					d = RuinKit.prop(this, level, from, glass, tr, 0, true);
					list.add(d);
				}
				RuinKit.bigBounds(d, rel.length());
			}
			while (list.size() > rs.size()) RuinKit.drop(this, list.remove(list.size() - 1));
		}
	}

	private void drawBeams(ServerLevel level, List<ServerPlayer> here) {
		for (Beam b : beams) {
			for (Object[] r : runs(b)) {
				Vec3 from = (Vec3) r[0], to = (Vec3) r[1];
				var dust = AuraFx.dust((Integer) r[2], 0.9f);
				double len = from.distanceTo(to);
				int n = Math.max(1, (int) (len / 1.6));
				for (int k = 0; k <= n; k++) {
					Vec3 p = from.lerp(to, k / (double) n);
					for (ServerPlayer pl : here) level.sendParticles(pl, dust, true, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0);
				}
			}
		}
	}

	/** The rune lines from each sealed mirror to its stone (dim); {@code flash} = a bright pulse along one. */
	private void cables(ServerLevel level, List<ServerPlayer> to, boolean flash, Mirror only) {
		for (Mirror m : mirrors) {
			if (!m.locked || (only != null && m != only)) continue;
			Vec3 a = Vec3.atCenterOf(runePos(m)).add(0, 0.6, 0), b = node(m.i, m.j).add(0, 0.6, 0);
			int n = flash ? 28 : 10;
			var dust = AuraFx.dust(GLYPH_RGB[m.glyph], flash ? 1.2f : 0.7f);
			for (int k = 0; k <= n; k++) {
				Vec3 p = a.lerp(b, k / (double) n);
				for (ServerPlayer pl : to) level.sendParticles(pl, flash ? ParticleTypes.END_ROD : dust, true, p.x, p.y, p.z, 1, 0, 0, 0, 0);
			}
		}
	}

	/** Extreme+: mirrors nobody stands by creep one step back toward their start every half minute. */
	private void drift(ServerLevel level, List<ServerPlayer> here, long now) {
		if (devNoDrift) return;
		for (Mirror m : mirrors) {
			if (m.state == m.home) continue;
			Vec3 c = node(m.i, m.j);
			boolean near = false;
			for (ServerPlayer p : here) {
				if (p.position().distanceToSqr(c) < 25 || (m.locked && p.position().distanceToSqr(Vec3.atCenterOf(runePos(m))) < 25)) near = true;
			}
			if (near) {
				m.driftAt = Math.max(m.driftAt, now + 200);
				continue;
			}
			if (now >= m.driftAt - 60 && now < m.driftAt) level.sendParticles(ParticleTypes.SMOKE, c.x, c.y, c.z, 4, 0.3, 0.3, 0.3, 0.01);
			if (now < m.driftAt) continue;
			int diff = Math.floorMod(m.home - m.state, 4);
			m.state = Math.floorMod(m.state + (diff <= 2 ? 1 : -1), 4);
			turnDisplays(m);
			level.playSound(null, c.x, c.y, c.z, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.7f, 0.5f);
			m.driftAt = now + driftEvery();
		}
	}

	/** 0 none, 1 the next receiver and its colour shown, 2 also the first wrong mirror on the way sparkles. */
	private int hintLevel() {
		if (!hintsAllowed()) return 0;
		return totalStrikes - (tier == 0 ? 1 : 2) >= 1 ? 2 : 1;
	}

	private void hints(ServerLevel level, List<ServerPlayer> here) {
		int h = hintLevel();
		if (h == 0 || step >= receivers.size()) return;
		Receiver next = receivers.get(step);
		Vec3 at = node(next.i, next.j);
		for (int k = 0; k < 10; k++) level.sendParticles(AuraFx.dust(next.rgb, 1.3f), at.x, at.y + 1 + k * 0.8, at.z, 1, 0.05, 0.1, 0.05, 0);
		String colour = colourName(next.rgb);
		boolean prism = next.rgb != baseColour(0) && next.rgb != baseColour(1);
		actionBar(here, "Next: receiver " + ROMAN[step] + (prism ? " - its light must pass the " + colour + " prism" : " - " + colour + " light"), ChatFormatting.AQUA);
		if (h < 2 || step >= solution.length) return;
		int[] want = solution[step];
		for (Beam b : traceAll(want, null)) {
			if (b.end != END_RECV || b.recv != step) continue;
			for (int key : b.nodes) {
				if (kind[key] != MIRROR) continue;
				Mirror m = mirrors.get(idx[key]);
				if (m.state != want[idx[key]]) {
					Vec3 c = node(m.i, m.j);
					level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y + 0.4, c.z, 12, 0.4, 0.4, 0.4, 0);
					if (m.locked) {
						Vec3 rp = Vec3.atCenterOf(runePos(m));
						level.sendParticles(ParticleTypes.HAPPY_VILLAGER, rp.x, rp.y + 0.6, rp.z, 12, 0.4, 0.4, 0.4, 0);
					}
					return;
				}
			}
		}
	}

	private String colourName(int c) {
		for (int k = 0; k < FILTER_RGB.length; k++) if (FILTER_RGB[k] == c) return FILTER_NAME[k];
		if (c == BEAM_B) return "white";
		return c == baseColour(0) ? new String[]{"blue", "green", "gold", "red"}[Math.max(0, Math.min(3, index))] : "pale";
	}

	// --- the strike ladder

	@Override
	void onReact(ServerLevel level, Vec3 at) {
		for (Mirror m : mirrors) {
			Vec3 c = node(m.i, m.j);
			level.sendParticles(ParticleTypes.SMOKE, c.x, c.y, c.z, 5, 0.3, 0.3, 0.3, 0.01);
		}
		level.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.5f, 0.6f);
	}

	@Override
	void onHazard(ServerLevel level, Vec3 at) {
		punish(level, at, 2 + tier / 2);
		for (Mirror m : mirrors) if (m.state != m.home) snapHome(level, m);
		// Hard and up: the last lit receiver goes dark again
		if (tier >= 2 && step > 0) {
			Receiver rc = receivers.get(step - 1);
			rc.lit = false;
			step--;
			set(level, nf(rc.i), nl(rc.j), 2, RuinKit.glassFor(rc.rgb));
			if (rc.tag != null) {
				int col = rc.rgb;
				com.toast.aurapoints.arena.Holograms.setTextPublic(rc.tag, Component.literal(ROMAN[step]).withStyle(s -> s.withColor(col).withBold(true)));
			}
		}
	}

	@Override
	void onReset(ServerLevel level, RandomSource r) {
		clearPieces(level);
		newLayout(r);
		placePieces(level);
		long now = level.getGameTime();
		for (Mirror m : mirrors) m.driftAt = now + driftEvery() + 200;
	}

	@Override
	void remove() {
		super.remove();
		pieceDisplays.clear();
		segs.clear();
		segSig.clear();
	}

	// --- the camera

	@Override
	RiftCam intro() {
		Source s = sources.isEmpty() ? null : sources.get(0);
		Vec3 mid = Vec3.atCenterOf(arena(0, 0, 2));
		Vec3 src = s != null ? node(s.i(), s.j()) : mid;
		Vec3 first = receivers.isEmpty() ? mid : node(receivers.get(0).i, receivers.get(0).j);
		return RiftCam.shot(rgb).title(name(), run.difficulty.label).skippable(true)
				.key(0, Vec3.atCenterOf(arena(-38, 0, 8)), mid)
				.key(45, Vec3.atCenterOf(arena(-24, 0, 10)), mid)
				.cut(46, src.add(0, 5, 0).add(mid.subtract(src).normalize().scale(-4)), src)
				.key(85, src.add(0, 9, 0).add(mid.subtract(src).normalize().scale(6)), mid)
				.key(120, Vec3.atCenterOf(arena(0, 18, 14)), first)
				.key(160, Vec3.atCenterOf(arena(2, 0, 40)), Vec3.atCenterOf(arena(3, 0, 0)));
	}

	// ---------------------------------------------------------------
	// DEV
	// ---------------------------------------------------------------

	private long devNow;
	private boolean devNoDrift;

	private void devTicks(ServerLevel level, ServerPlayer fake, int n) {
		for (int k = 0; k < n && !solved; k++) tick(level, List.of(fake), ++devNow);
	}

	/** Clicks the mirrors (base, or rune stone when sealed) until they match {@code want}. */
	private void devApply(ServerLevel level, ServerPlayer fake, int[] want) {
		for (int m = 0; m < mirrors.size() && m < want.length; m++) {
			Mirror mi = mirrors.get(m);
			for (int k = 0; k < 4 && mi.state != want[m]; k++) use(level, fake, mi.locked ? runePos(mi) : basePos(mi));
		}
	}

	@Override
	void devPlay(net.minecraft.server.MinecraftServer server, ServerLevel level, ServerPlayer fake, java.util.function.Consumer<String> log, Runnable done) {
		devNow = level.getGameTime();
		devNoDrift = true;
		long locked = mirrors.stream().filter(m -> m.locked).count();
		log.accept("prism: tier " + tier + ", receivers " + receivers.size() + ", mirrors " + mirrors.size() + ", sealed " + locked + ", prisms " + filters.size()
				+ ", beams " + sources.size() + ", solutions " + solution.length + " (must be " + receivers.size() + ")");
		// stand on a gallery, far above the lanes
		Vec3 safe = Vec3.atBottomCenterOf(arena(-2, 23, 7));
		fake.moveTo(safe.x, safe.y, safe.z, 0f, 0f);
		devTicks(level, fake, 6);
		boolean calmStart = true;
		for (Beam b : beams) if (b.end == END_RECV || b.end == END_CROSS) calmStart = false;
		log.accept("prism: start layout calm " + calmStart + " (must be true)");
		// a sealed mirror can't be turned at its base
		for (Mirror m : mirrors) {
			if (!m.locked) continue;
			int before = m.state;
			use(level, fake, basePos(m));
			log.accept("prism: sealed mirror at its base -> turned " + (m.state != before) + " (must be false)");
			use(level, fake, runePos(m));
			log.accept("prism: sealed mirror from its rune stone -> turned " + (m.state != before) + " (must be true)");
			break;
		}
		// a mistake: feed receiver II first
		if (receivers.size() >= 2) {
			devApply(level, fake, solution[1]);
			devTicks(level, fake, CHARGE + 10);
			log.accept("prism: wrong receiver -> strikes " + strikes + " (must be 1), receiver II lit " + receivers.get(1).lit + " (must be false)");
		}
		// receiver I, with a body in the beam first
		devApply(level, fake, solution[0]);
		devTicks(level, fake, 3);
		Beam toFirst = null;
		for (Beam b : beams) if (b.end == END_RECV && b.recv == 0) toFirst = b;
		if (toFirst != null && toFirst.pts.size() >= 2) {
			Vec3 a = toFirst.pts.get(toFirst.pts.size() - 2), b = toFirst.pts.get(toFirst.pts.size() - 1);
			Vec3 mid = a.lerp(b, 0.5);
			fake.moveTo(mid.x, Rifts.FLOOR + 1, mid.z, 0f, 0f);
			devTicks(level, fake, 6);
			boolean blocked = false;
			for (Beam bb : beams) if (bb.end == END_PLAYER) blocked = true;
			log.accept("prism: body in the beam -> blocked " + blocked + " (must be true), receiver I charge " + receivers.get(0).charge + " (must be 0)");
			fake.moveTo(safe.x, safe.y, safe.z, 0f, 0f);
		} else log.accept("prism: no beam on receiver I with its solution (must not happen)");
		devTicks(level, fake, CHARGE + 10);
		log.accept("prism: receiver I -> lit " + receivers.get(0).lit + " (must be true), next " + step + " (must be 1)");
		for (int k = 1; k < receivers.size(); k++) {
			devApply(level, fake, solution[k]);
			devTicks(level, fake, CHARGE + 10);
			log.accept("prism: receiver " + ROMAN[k] + " -> lit " + receivers.get(k).lit + " (must be true)");
		}
		log.accept("prism: solved " + solved + " (must be true), strikes total " + totalStrikes + " (must be " + (receivers.size() >= 2 ? 1 : 0) + ")");
		AuraFx.later(server, 5, done);
	}
}
