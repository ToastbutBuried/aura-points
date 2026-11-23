package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraFx;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Resonance Dig (5.49.0, replaces the Tuning Fork hunt): an excavation site on a floating island with a grid of 3x3
 * soil tiles (9x9 tiles, 11x11 on Hard/Extreme, 13x13 on Hardcore) and resonance pylons around it. Standing on a
 * pylon's plate shows you (only you) a ring of particles at the shard's distance from it, so players split up and call
 * out readings; the rings cross at the shard. Right-click a tile = probe (how many of its 8 neighbours are cursed, a
 * floating number that fades after a while); sneak + right-click = dig. A cursed dig collapses the tile into a pit full
 * of silverfish and husks (a strike); an empty dig is a strike and blanks the probes for 30 s. Dig up the shard to solve.
 * <p>
 * Tiers: Easy 3 sharp rings; Medium 3 blurred rings; Hard 2 pylons (two crossings); Extreme a decoy (every pylon shows a
 * second ring to a cursed tile) and the shard moves after each strike; Hardcore numbers instead of rings. Solo: each
 * reading takes a 10 s attunement on the plate and lasts 10 s.
 */
final class TrialDig extends Trials.Trial {

	static final int PITCH = 4;
	/** Pylon ring colours (aqua, magenta, gold) and names. */
	static final int[] PYLON_RGB = {0x43E8E0, 0xE04BD8, 0xF2C43A};
	static final String[] PYLON_NAME = {"I", "II", "III"};
	static final int BLANK_TICKS = 600, SOLO_CHANNEL = 200, SOLO_READING = 200;

	final int n, half, radius;
	final boolean[][] cursed;
	/** 0 untouched, 1 dug (empty hole), 2 collapsed pit. */
	final int[][] state;
	/** Shard and decoy tiles (decoy -1 below Extreme). */
	int si, sj, di = -1, dj = -1;
	/** Pylon plate centres (f, l); live = how many of them work. */
	final int[][] pylons = new int[3][2];
	final int live;
	final Map<Integer, Probe> probes = new HashMap<>();
	long blankUntil;
	private boolean blanked;
	final Map<UUID, Long> channelStart = new HashMap<>(), readingUntil = new HashMap<>();
	private final Map<UUID, Long> clickCd = new HashMap<>();
	private List<ServerPlayer> lastHere = List.of();
	private int hintShown;

	static final class Probe {
		Display.TextDisplay d;
		int count;
		long until;
	}

	TrialDig(Rifts.Run run, int index) {
		super(run, index, Trials.Kind.DIG);
		this.n = tier >= 4 ? 13 : tier >= 2 ? 11 : 9;
		this.half = (n * PITCH - 1) / 2;
		this.radius = half + 9;
		this.cursed = new boolean[n][n];
		this.state = new int[n][n];
		this.live = tier == 2 ? 2 : 3;
		for (int k = 0; k < 3; k++) {
			double ang = Math.toRadians(k * 120);
			pylons[k][0] = (int) Math.round(Math.cos(ang) * radius);
			pylons[k][1] = (int) Math.round(Math.sin(ang) * radius);
		}
	}

	@Override
	String name() {
		return "Resonance Dig";
	}

	@Override
	String hint() {
		return solo() ? "Attune on a pylon (stand on its plate 10 s): a ring shows the shard's distance. Right-click a tile to probe for cursed ground, sneak + right-click to dig."
				: "Split up on the pylons: each of you sees a ring at the shard's distance - call them out, the rings cross at the shard. Right-click a tile to probe, sneak + right-click to dig.";
	}

	@Override
	int arenaHalf() {
		return 56;
	}

	private boolean solo() {
		return lastHere.size() <= 1;
	}

	int cursedCount() {
		return switch (tier) {
			case 0 -> 8;
			case 1 -> 12;
			case 2 -> 18;
			case 3 -> 22;
			default -> 30;
		};
	}

	/** Ring blur half-width in blocks. */
	double blur() {
		return switch (tier) {
			case 0 -> 0.2;
			case 1 -> 1.4;
			case 2 -> 1.6;
			default -> 1.3;
		};
	}

	/** How long a probe's number stays up. */
	long probeLife() {
		return switch (tier) {
			case 0 -> 20L * 600;
			case 1 -> 20L * 90;
			case 2 -> 20L * 60;
			default -> 20L * 45;
		};
	}

	// --- grid frame

	/** Centre (f or l) of tile index i. */
	int cen(int i) {
		return -half + 1 + i * PITCH;
	}

	/** Tile index of an arena coordinate, -1 on a walkway or outside. */
	int tileOf(int x) {
		int k = x + half;
		if (k < 0 || k >= n * PITCH - 1 || k % PITCH == 3) return -1;
		return k / PITCH;
	}

	static int key(int i, int j) {
		return i * 64 + j;
	}

	boolean in(int i, int j) {
		return i >= 0 && j >= 0 && i < n && j < n;
	}

	int neighbours(int i, int j) {
		int c = 0;
		for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) if ((a != 0 || b != 0) && in(i + a, j + b) && cursed[i + a][j + b]) c++;
		return c;
	}

	Vec3 tileCentre(int i, int j, double dy) {
		return DigDecor.v(this, cen(i), cen(j), dy);
	}

	// ---------------------------------------------------------------
	// Build
	// ---------------------------------------------------------------

	@Override
	void build(ServerLevel level, RandomSource r) {
		int isl = radius + 7;
		// the island and its approach causeway from the way back
		DigDecor.island(level, this, r, 0, 0, isl, isl, 20, pal.floor());
		DigDecor.slab(level, this, backF() - 4, -isl + 3, -3, 3, 4, pal.floor());
		for (int f = backF() - 2; f <= -isl + 2; f++) {
			for (int l : new int[]{-4, 4}) {
				DigDecor.slab(level, this, f, f, l, l, 3, pal.edge());
				if (Math.floorMod(f, 3) != 0) set(level, f, l, 1, pal.edge());
			}
			if (Math.floorMod(f - backF(), 7) == 3) for (int l : new int[]{-6, 6}) {
				DigDecor.slab(level, this, f - 1, f + 1, l - 1, l + 1, 3, pal.edge());
				DigDecor.brazier(level, this, f, l, 1);
			}
		}
		// a paved ring around the dig with a darker border
		for (int f = -half - 3; f <= half + 3; f++) {
			for (int l = -half - 3; l <= half + 3; l++) {
				boolean border = Math.abs(f) > half || Math.abs(l) > half;
				set(level, f, l, 0, border ? (Math.abs(f) == half + 3 || Math.abs(l) == half + 3 ? pal.accent() : pal.edge()) : pal.edge());
				for (int dy = -1; dy >= -3; dy--) set(level, f, l, dy, pal.base());
			}
		}
		for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) soil(level, i, j);
		// the gantry: four corner towers with beams along two sides, chain lanterns over the field's edge
		int g = half + 3;
		for (int[] c : new int[][]{{-g, -g}, {-g, g}, {g, -g}, {g, g}}) DigDecor.pillar(level, this, r, c[0], c[1], 0, 10, false);
		for (int f : new int[]{-g, g}) {
			for (int l = -g + 2; l <= g - 2; l++) {
				set(level, f, l, 11, pal.edge());
				if (Math.floorMod(l, 6) == 0 && Math.abs(l) < g - 2) DigDecor.chainLantern(level, this, f, l, 11, 2 + (Math.abs(l) / 6) % 2);
			}
		}
		// hidden light over the field so the pits and numbers read at night
		for (int f = -half; f <= half; f += 8) for (int l = -half; l <= half; l += 8) DigDecor.glow(level, this, f, l, 5, 12);
		// the pylons
		for (int k = 0; k < 3; k++) pylon(level, r, k, k < live);
		// a ring of broken colonnade between the pylons, with wing banners
		for (int k = 0; k < 6; k++) {
			double ang = Math.toRadians(30 + k * 60);
			int f = (int) Math.round(Math.cos(ang) * (radius + 1)), l = (int) Math.round(Math.sin(ang) * (radius + 1));
			if (f < -radius + 4 && Math.abs(l) < 9) continue; // keep the causeway clear
			boolean broken = r.nextInt(3) != 0;
			DigDecor.pillar(level, this, r, f, l, 0, 13 + r.nextInt(4), broken);
			int df = -Integer.signum(f), dl = -Integer.signum(l);
			if (Math.abs(f) >= Math.abs(l)) dl = 0;
			else df = 0;
			if (!broken) DigDecor.banner(level, this, f + df * 2, l + dl * 2, 7, df, dl);
		}
		// spoil heaps, crates and toppled column drums on the plaza
		for (int k = 0; k < 7; k++) {
			double ang = r.nextDouble() * Math.PI * 2;
			int d = half + 5 + r.nextInt(3);
			int f = (int) Math.round(Math.cos(ang) * d), l = (int) Math.round(Math.sin(ang) * d);
			if (f < -half - 2 && Math.abs(l) < 7) continue;
			if (nearPylon(f, l, 5)) continue;
			heap(level, r, f, l);
		}
		for (int k = 0; k < 5; k++) {
			double ang = r.nextDouble() * Math.PI * 2;
			int d = half + 6 + r.nextInt(4);
			int f = (int) Math.round(Math.cos(ang) * d), l = (int) Math.round(Math.sin(ang) * d);
			if ((f < -half - 2 && Math.abs(l) < 7) || nearPylon(f, l, 5)) continue;
			if (r.nextBoolean()) {
				int len = 3 + r.nextInt(2);
				boolean alongF = r.nextBoolean();
				for (int s = 0; s < len; s++) set(level, f + (alongF ? s : 0), l + (alongF ? 0 : s), 1, s == 0 || s == len - 1 ? pal.accent() : pal.edge());
			} else {
				set(level, f, l, 1, Blocks.BARREL);
				if (r.nextBoolean()) set(level, f + 1, l, 1, Blocks.BARREL);
				if (r.nextBoolean()) set(level, f, l, 2, Blocks.BARREL);
			}
		}
		// braziers on the plaza corners
		for (int[] c : new int[][]{{-half - 7, -half - 7}, {-half - 7, half + 7}, {half + 7, -half - 7}, {half + 7, half + 7}})
			if (!nearPylon(c[0], c[1], 5)) DigDecor.brazier(level, this, c[0], c[1], 1);
		// floating debris around the island
		for (int k = 0; k < 18; k++) {
			double ang = r.nextDouble() * Math.PI * 2;
			int d = isl + 6 + r.nextInt(14);
			int f = (int) Math.round(Math.cos(ang) * d), l = (int) Math.round(Math.sin(ang) * d);
			if (Math.abs(f) > arenaHalf() - 3 || Math.abs(l) > arenaHalf() - 3) continue;
			if (f < -isl && Math.abs(l) < 9) continue;
			DigDecor.debris(level, this, r, f, l, -12 + r.nextInt(30), 2 + r.nextInt(4));
		}
		label(level, DigDecor.v(this, -isl + 1, 0, 3.2), Component.literal("Probe: right-click  ·  Dig: sneak + right-click").withStyle(ChatFormatting.GRAY), 0.9f);
		roll(r);
	}

	private boolean nearPylon(int f, int l, int d) {
		for (int[] p : pylons) if (Math.abs(p[0] - f) <= d && Math.abs(p[1] - l) <= d) return true;
		return false;
	}

	/** One untouched soil tile: a clustered mix of the aura's base stone and coarse dirt over solid ground. */
	private void soil(ServerLevel level, int i, int j) {
		int cf = cen(i), cl = cen(j);
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				int f = cf + a, l = cl + b;
				Block top = DigDecor.hash(i * 3 + (a + 1) / 2, j * 3 + (b + 1) / 2, 11) < 34 ? Blocks.COARSE_DIRT : pal.base();
				set(level, f, l, 0, top);
				set(level, f, l, -1, pal.base());
				set(level, f, l, -2, pal.base());
			}
		}
	}

	private void heap(ServerLevel level, RandomSource r, int f, int l) {
		int rad = 2 + r.nextInt(2);
		for (int a = -rad; a <= rad; a++) {
			for (int b = -rad; b <= rad; b++) {
				double d = Math.sqrt(a * a + b * b);
				int h = (int) Math.round((rad - d) * 0.9 + r.nextFloat() * 0.6);
				for (int dy = 1; dy <= h; dy++) set(level, f + a, l + b, dy, dy == h && r.nextInt(3) == 0 ? Blocks.COARSE_DIRT : dy == h ? Blocks.GRAVEL : pal.base());
			}
		}
	}

	/** A resonance pylon: a raised plate to stand on, an obelisk behind it with an amethyst crown. Dead pylons are broken and dark. */
	private void pylon(ServerLevel level, RandomSource r, int k, boolean on) {
		int pf = pylons[k][0], pl = pylons[k][1];
		double len = Math.max(1, Math.sqrt(pf * pf + pl * pl));
		int uf = (int) Math.round(pf / len * 3), ul = (int) Math.round(pl / len * 3);
		for (int a = -3; a <= 3; a++) for (int b = -3; b <= 3; b++) {
			set(level, pf + a, pl + b, 0, Math.abs(a) == 3 || Math.abs(b) == 3 ? pal.accent() : pal.edge());
			for (int dy = -1; dy >= -3; dy--) set(level, pf + a, pl + b, dy, pal.base());
		}
		for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) set(level, pf + a, pl + b, 1, Math.abs(a) <= 1 && Math.abs(b) <= 1 ? (a == 0 && b == 0 && on ? pal.light() : pal.accent()) : pal.edge());
		for (int a : new int[]{-2, 2}) for (int b : new int[]{-2, 2}) {
			set(level, pf + a, pl + b, 2, pal.edge());
			set(level, pf + a, pl + b, 3, on ? pal.light() : pal.base());
		}
		// the obelisk stands one step outward of the plate
		int of = pf + uf + Integer.signum(uf), ol = pl + ul + Integer.signum(ul);
		int h = on ? 11 : 4 + r.nextInt(2);
		for (int dy = 1; dy <= h; dy++) set(level, of, ol, dy, dy % 4 == 0 ? pal.accent() : pal.edge());
		if (on) {
			set(level, of, ol, h + 1, Blocks.AMETHYST_BLOCK);
			set(level, of, ol, h + 2, Blocks.AMETHYST_CLUSTER);
			label(level, DigDecor.v(this, of, ol, h + 4.2), Component.literal("Pylon " + PYLON_NAME[k]).withStyle(s -> s.withColor(PYLON_RGB[k]).withBold(true)), 1.6f);
		} else {
			for (int q = 0; q < 4; q++) set(level, of + r.nextInt(5) - 2, ol + r.nextInt(5) - 2, 1, r.nextBoolean() ? Blocks.AMETHYST_BLOCK : pal.edge());
		}
	}

	/** A new layout: cursed tiles, the shard (and decoy). Tiles themselves are rebuilt by the caller. */
	private void roll(RandomSource r) {
		for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
			cursed[i][j] = false;
			state[i][j] = 0;
		}
		si = 1 + r.nextInt(n - 2);
		sj = 1 + r.nextInt(n - 2);
		// the crossing is blurred: put two or three traps right next to the shard so probing matters (not on Easy)
		int near = tier == 0 ? 0 : 2 + (tier >= 3 ? 1 : 0);
		List<int[]> around = new ArrayList<>();
		for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) if ((a != 0 || b != 0) && in(si + a, sj + b)) around.add(new int[]{si + a, sj + b});
		for (int k = 0; k < near && !around.isEmpty(); k++) {
			int[] t = around.remove(r.nextInt(around.size()));
			cursed[t[0]][t[1]] = true;
		}
		int want = cursedCount(), tries = 0;
		int have = near;
		while (have < want && tries++ < 4000) {
			int i = r.nextInt(n), j = r.nextInt(n);
			if (cursed[i][j] || (i == si && j == sj)) continue;
			cursed[i][j] = true;
			have++;
		}
		di = dj = -1;
		if (tier >= 3) {
			// the decoy: a cursed tile far enough from the shard that its rings cross somewhere else
			for (int t = 0; t < 400; t++) {
				int i = r.nextInt(n), j = r.nextInt(n);
				if (Math.abs(i - si) + Math.abs(j - sj) < n / 2) continue;
				cursed[i][j] = true;
				di = i;
				dj = j;
				break;
			}
		}
	}

	/** Extreme+: the shard burrows to another untouched, uncursed tile after a strike. */
	private void moveShard(ServerLevel level, RandomSource r) {
		for (int t = 0; t < 400; t++) {
			int i = r.nextInt(n), j = r.nextInt(n);
			if (cursed[i][j] || state[i][j] != 0 || (i == si && j == sj)) continue;
			si = i;
			sj = j;
			for (ServerPlayer p : lastHere) p.displayClientMessage(Component.literal("You hear the shard burrow away under the soil...").withStyle(ChatFormatting.DARK_PURPLE), true);
			Vec3 c = tileCentre(i, j, 0.5);
			level.playSound(null, c.x, c.y, c.z, AuraSounds.ROOTED_DIRT_BREAK.value(), SoundSource.BLOCKS, 1.5f, 0.5f);
			return;
		}
	}

	// ---------------------------------------------------------------
	// Play
	// ---------------------------------------------------------------

	@Override
	void begin(ServerLevel level, List<ServerPlayer> here) {
		lastHere = here;
	}

	@Override
	RiftCam intro() {
		Vec3 mid = DigDecor.v(this, 0, 0, 0);
		int[] p0 = pylons[0];
		return RiftCam.shot(rgb).title(name(), run.difficulty.label).skippable(true)
				.key(0, DigDecor.v(this, -radius - 14, 0, 30), mid)
				.key(45, DigDecor.v(this, -1, 0, half * 2.2 + 8), DigDecor.v(this, 0.5, 0, 0))
				.key(95, DigDecor.v(this, p0[0] + 2, p0[1], 4.5), DigDecor.v(this, 0, 0, 1))
				.key(140, DigDecor.v(this, -half - 4, half + 6, 5), DigDecor.v(this, 2, -2, 0));
	}

	@Override
	void tick(ServerLevel level, List<ServerPlayer> here, long now) {
		lastHere = here;
		if (solved) return;
		if (now % 8 == 0) pylonTick(level, here, now);
		if (now % 20 == 0) {
			if (blanked && now >= blankUntil) {
				blanked = false;
				for (Probe pr : probes.values()) setProbeText(pr);
				for (ServerPlayer p : here) p.displayClientMessage(Component.literal("The ground settles: the probes read again.").withStyle(ChatFormatting.AQUA), true);
			}
			probes.entrySet().removeIf(e -> {
				if (now < e.getValue().until) return false;
				dropDisplay(e.getValue().d);
				return true;
			});
		}
		if (now % 40 == 0 && hintsAllowed()) hints(level, here);
	}

	private boolean onPlate(ServerPlayer p, int k) {
		Vec3 pos = p.position();
		double f = af(pos) - pylons[k][0], l = al(pos) - pylons[k][1];
		double dy = pos.y - Rifts.FLOOR;
		return Math.abs(f) <= 1.7 && Math.abs(l) <= 1.7 && dy > 1.8 && dy < 2.8;
	}

	int standingOn(ServerPlayer p) {
		for (int k = 0; k < live; k++) if (onPlate(p, k)) return k;
		return -1;
	}

	private void pylonTick(ServerLevel level, List<ServerPlayer> here, long now) {
		boolean solo = here.size() <= 1;
		for (ServerPlayer p : here) {
			int k = standingOn(p);
			UUID id = p.getUUID();
			if (k < 0) {
				channelStart.remove(id);
				readingUntil.remove(id);
				continue;
			}
			if (solo) {
				// alone, every reading costs a 10 s attunement on the plate and lasts 10 s
				Long until = readingUntil.get(id);
				if (until == null || now >= until) {
					if (until != null) readingUntil.remove(id);
					Long start = channelStart.get(id);
					if (start == null) channelStart.put(id, start = now);
					long t = now - start;
					if (t < SOLO_CHANNEL) {
						int pct = (int) (100 * t / SOLO_CHANNEL);
						p.displayClientMessage(Component.literal("Attuning to Pylon " + PYLON_NAME[k] + "... " + pct + "%").withStyle(s -> s.withColor(PYLON_RGB[k])), true);
						Vec3 c = DigDecor.v(this, pylons[k][0], pylons[k][1], 2.2);
						level.sendParticles(p, ParticleTypes.ENCHANT, true, c.x, c.y + 1, c.z, 6, 1, 0.6, 1, 0.4);
						continue;
					}
					channelStart.remove(id);
					readingUntil.put(id, now + SOLO_READING);
					p.playNotifySound(AuraSounds.AMETHYST_BLOCK_RESONATE.value(), SoundSource.BLOCKS, 1.2f, 1f);
				}
			}
			showReading(level, p, k, now);
		}
	}

	/** Pylon k's reading for p: a ring (or on Hardcore numbers) to the shard and, on Extreme+, the decoy. */
	private void showReading(ServerLevel level, ServerPlayer p, int k, long now) {
		double pf = pylons[k][0], pl = pylons[k][1];
		List<Double> dists = new ArrayList<>();
		dists.add(Math.hypot(cen(si) - pf, cen(sj) - pl));
		if (di >= 0) dists.add(Math.hypot(cen(di) - pf, cen(dj) - pl));
		if (tier >= 4) {
			dists.sort(Double::compare);
			StringBuilder b = new StringBuilder("Pylon " + PYLON_NAME[k] + " hums: ");
			for (int q = 0; q < dists.size(); q++) b.append(q > 0 ? "  ·  " : "").append(Math.round(dists.get(q))).append(" blocks");
			p.displayClientMessage(Component.literal(b.toString()).withStyle(s -> s.withColor(PYLON_RGB[k])), true);
			if (now % 40 == 0) p.playNotifySound(AuraSounds.AMETHYST_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1f, 0.8f);
			return;
		}
		p.displayClientMessage(Component.literal("Pylon " + PYLON_NAME[k] + " resonates - follow its ring").withStyle(s -> s.withColor(PYLON_RGB[k])), true);
		var dust = AuraFx.dust(PYLON_RGB[k], 1.6f);
		RandomSource r = level.random;
		double lim = half + 1.5;
		for (double d : dists) {
			int pts = (int) Math.min(220, Math.max(40, d * Math.PI * 2 / 0.9));
			for (int q = 0; q < pts; q++) {
				double ang = Math.PI * 2 * q / pts + (now % 16) * 0.004;
				double rr = d + (r.nextDouble() * 2 - 1) * blur();
				double f = pf + Math.cos(ang) * rr, l = pl + Math.sin(ang) * rr;
				if (Math.abs(f) > lim || Math.abs(l) > lim) continue;
				Vec3 at = DigDecor.v(this, f, l, 1.25);
				level.sendParticles(p, dust, true, at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
		}
		if (now % 40 == 0) p.playNotifySound(AuraSounds.AMETHYST_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1f, 0.6f + k * 0.3f);
	}

	@Override
	boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
		if (solved) return false;
		Vec3 c = Vec3.atCenterOf(pos);
		int f = (int) Math.round(af(c)), l = (int) Math.round(al(c));
		int dy = pos.getY() - Rifts.FLOOR;
		int i = tileOf(f), j = tileOf(l);
		if (i < 0 || j < 0 || dy > 0 || dy < -3) return false;
		long now = level.getGameTime();
		Long cd = clickCd.get(p.getUUID());
		if (cd != null && now < cd) return true;
		clickCd.put(p.getUUID(), now + 5);
		if (p.isShiftKeyDown()) dig(level, p, i, j, now);
		else probe(level, p, i, j, now);
		return true;
	}

	private void probe(ServerLevel level, ServerPlayer p, int i, int j, long now) {
		Vec3 c = tileCentre(i, j, 1);
		if (blanked) {
			p.displayClientMessage(Component.literal("The probe crackles - the ground is too restless to read.").withStyle(ChatFormatting.DARK_GRAY), true);
			p.playNotifySound(AuraSounds.FIRE_EXTINGUISH.value(), SoundSource.BLOCKS, 0.6f, 1.6f);
			return;
		}
		Probe pr = probes.get(key(i, j));
		if (pr == null) {
			pr = new Probe();
			pr.count = neighbours(i, j);
			pr.d = label(level, c.add(0, 0.7, 0), Component.empty(), 1.8f);
			probes.put(key(i, j), pr);
		}
		pr.until = now + probeLife();
		setProbeText(pr);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.8f, 0.7f + pr.count * 0.15f);
		level.sendParticles(ParticleTypes.ENCHANT, c.x, c.y, c.z, 12, 0.8, 0.3, 0.8, 0.5);
	}

	private void setProbeText(Probe pr) {
		if (pr.d == null) return;
		if (blanked) {
			com.toast.aurapoints.arena.Holograms.setTextPublic(pr.d, Component.literal("?").withStyle(ChatFormatting.DARK_GRAY));
			return;
		}
		ChatFormatting col = switch (pr.count) {
			case 0 -> ChatFormatting.GRAY;
			case 1 -> ChatFormatting.AQUA;
			case 2 -> ChatFormatting.GREEN;
			case 3 -> ChatFormatting.YELLOW;
			case 4 -> ChatFormatting.GOLD;
			default -> ChatFormatting.RED;
		};
		com.toast.aurapoints.arena.Holograms.setTextPublic(pr.d, Component.literal(String.valueOf(pr.count)).withStyle(col, ChatFormatting.BOLD));
	}

	private void dropDisplay(Display.TextDisplay d) {
		if (d == null) return;
		d.discard();
		displays.remove(d);
	}

	private void dig(ServerLevel level, ServerPlayer p, int i, int j, long now) {
		if (state[i][j] != 0) {
			p.displayClientMessage(Component.literal("Already dug.").withStyle(ChatFormatting.GRAY), true);
			return;
		}
		if (i == si && j == sj && SkillChecks.enabled()) {
			// something holds it: pull it free
			if (SkillChecks.busy(p)) return;
			Vec3 at = tileCentre(i, j, 1.2);
			SkillChecks.start(p, "Pull the shard free", rgb, tier, at, true, () -> !solved && state[i][j] == 0 && i == si && j == sj, res -> {
				if (res == SkillChecks.GOOD) digNow(level, p, i, j, now);
				else if (res != SkillChecks.CANCEL) p.displayClientMessage(Component.literal("Something is down there - the soil slides back, dig again").withStyle(ChatFormatting.AQUA), true);
			});
			return;
		}
		digNow(level, p, i, j, now);
	}

	private void digNow(ServerLevel level, ServerPlayer p, int i, int j, long now) {
		Vec3 c = tileCentre(i, j, 0.5);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COARSE_DIRT.defaultBlockState()), c.x, c.y + 0.5, c.z, 40, 1, 0.3, 1, 0.1);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.ROOTED_DIRT_BREAK.value(), SoundSource.BLOCKS, 1.2f, 0.8f);
		if (i == si && j == sj) {
			hole(level, i, j);
			state[i][j] = 1;
			set(level, cen(i), cen(j), -2, pal.light());
			level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 0.5, c.z, 40, 0.5, 1.5, 0.5, 0.05);
			solve(level, tileCentre(i, j, 0.3));
			return;
		}
		if (cursed[i][j]) {
			collapse(level, i, j);
			state[i][j] = 2;
			p.displayClientMessage(Component.literal("Cursed ground! It gives way...").withStyle(ChatFormatting.DARK_RED), true);
			strikeAt(level, p, c);
			return;
		}
		hole(level, i, j);
		state[i][j] = 1;
		strikeAt(level, p, c);
		if (!solved && strikes != 0) {
			blanked = true;
			blankUntil = now + BLANK_TICKS;
			for (Probe pr : probes.values()) setProbeText(pr);
			for (ServerPlayer q : lastHere) q.displayClientMessage(Component.literal("Only soil - the resonance scatters, probes blank for 30 s").withStyle(ChatFormatting.GRAY), true);
		}
	}

	private void strikeAt(ServerLevel level, ServerPlayer p, Vec3 at) {
		List<ServerPlayer> here = lastHere.isEmpty() ? List.of(p) : lastHere;
		strike(level, p, at, here);
		if (!solved && tier >= 3 && strikes != 0) moveShard(level, random != null ? random : level.random);
	}

	/** An empty dig: the top layer comes out (a 1-deep hole you can step in and out of). */
	private void hole(ServerLevel level, int i, int j) {
		int cf = cen(i), cl = cen(j);
		for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
			set(level, cf + a, cl + b, 0, Blocks.AIR);
			set(level, cf + a, cl + b, -1, Blocks.COARSE_DIRT);
		}
	}

	/** A cursed dig: the tile falls 2 deep into a bone-strewn pit (one corner step to climb out) and things crawl out. */
	private void collapse(ServerLevel level, int i, int j) {
		int cf = cen(i), cl = cen(j);
		for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
			boolean step = a == -1 && b == -1;
			set(level, cf + a, cl + b, 0, Blocks.AIR);
			set(level, cf + a, cl + b, -1, step ? pal.base() : Blocks.AIR);
			set(level, cf + a, cl + b, -2, DigDecor.hash(cf + a, cl + b, 5) < 30 ? Blocks.BONE_BLOCK : Blocks.SOUL_SOIL);
		}
		Vec3 c = tileCentre(i, j, -1);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.8f, 0.6f);
		level.sendParticles(ParticleTypes.SOUL, c.x, c.y + 1, c.z, 20, 1, 0.5, 1, 0.03);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, pal.base().defaultBlockState()), c.x, c.y + 1.5, c.z, 60, 1.2, 0.6, 1.2, 0.2);
		int silver = 2 + tier / 2;
		for (int k = 0; k < silver; k++) addMob(level, EntityType.SILVERFISH, c);
		if (tier >= 1) addMob(level, EntityType.HUSK, c);
	}

	private void addMob(ServerLevel level, EntityType<? extends Mob> type, Vec3 at) {
		Mob m = Rifts.spawnMob(level, run, type, at, 1);
		if (m != null) run.mobs.add(m);
	}

	private void hints(ServerLevel level, List<ServerPlayer> here) {
		int lvl = totalStrikes - (tier == 0 ? 1 : 2) + 1;
		if (lvl > hintShown) {
			hintShown = lvl;
			String msg = lvl == 1 ? "Hint: the soil shimmers near the shard." : "Hint: soul fire marks the cursed tiles beside the shard.";
			for (ServerPlayer p : here) p.sendSystemMessage(Component.literal("✦ " + msg).withStyle(ChatFormatting.AQUA));
		}
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				int i = si + a, j = sj + b;
				if (!in(i, j) || state[i][j] != 0) continue;
				Vec3 c = tileCentre(i, j, 1.1);
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y, c.z, 3, 1, 0.1, 1, 0);
				if (lvl >= 2 && cursed[i][j]) level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y, c.z, 6, 0.6, 0.2, 0.6, 0.01);
			}
		}
	}

	// ---------------------------------------------------------------
	// The strike ladder
	// ---------------------------------------------------------------

	@Override
	void onReact(ServerLevel level, Vec3 at) {
		// the site shudders: dust shaken off the gantry and a rumble
		for (int k = 0; k < 4; k++) {
			Vec3 c = DigDecor.v(this, (k < 2 ? -1 : 1) * (half + 3), (k % 2 == 0 ? -1 : 1) * (half + 3), 10);
			level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, pal.base().defaultBlockState()), c.x, c.y, c.z, 30, 1.5, 0.5, 1.5, 0);
		}
		level.playSound(null, at.x, at.y, at.z, AuraSounds.DEEPSLATE_BREAK.value(), SoundSource.BLOCKS, 2f, 0.53f);
	}

	@Override
	void onHazard(ServerLevel level, Vec3 at) {
		// a tremor wipes every probe (partial reset) and husks claw out of the soil
		for (Probe pr : probes.values()) dropDisplay(pr.d);
		probes.clear();
		for (ServerPlayer p : lastHere) p.displayClientMessage(Component.literal("A tremor - every probe reading is lost!").withStyle(ChatFormatting.RED), true);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.WARDEN_DIG.value(), SoundSource.HOSTILE, 1.5f, 0.8f);
		punish(level, at, 1 + tier / 2);
	}

	@Override
	void onReset(ServerLevel level, RandomSource r) {
		for (Probe pr : probes.values()) dropDisplay(pr.d);
		probes.clear();
		blanked = false;
		blankUntil = 0;
		roll(r);
		for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) soil(level, i, j);
		// anyone standing in a pit is lifted out with the soil
		for (ServerPlayer p : lastHere) {
			if (p.getY() < Rifts.FLOOR + 0.9 && Math.abs(af(p.position())) <= half && Math.abs(al(p.position())) <= half) DigDecor.tp(p, level, new Vec3(p.getX(), Rifts.FLOOR + 1, p.getZ()), p.getYRot(), p.getXRot());
		}
	}

	@Override
	Vec3 plinth() {
		return tileCentre(si, sj, 1).add(0, 0.2, 0);
	}

	@Override
	void release(ServerPlayer p) {
		channelStart.remove(p.getUUID());
		readingUntil.remove(p.getUUID());
		clickCd.remove(p.getUUID());
	}

	// ---------------------------------------------------------------
	// Dev test
	// ---------------------------------------------------------------

	@Override
	void devPlay(MinecraftServer server, ServerLevel level, ServerPlayer fake, Consumer<String> log, Runnable done) {
		long now = level.getGameTime();
		List<ServerPlayer> here = List.of(fake);
		tick(level, here, now);
		// a pylon plate: solo attunement first, then the reading
		Vec3 plate = DigDecor.v(this, pylons[0][0], pylons[0][1], 2);
		fake.moveTo(plate.x, plate.y, plate.z);
		fake.setOnGround(true);
		log.accept("dig: on pylon I " + (standingOn(fake) == 0) + " (must be true)");
		long t = (now / 8 + 1) * 8;
		tick(level, here, t);
		log.accept("dig: attuning, no reading yet " + !readingUntil.containsKey(fake.getUUID()) + " (must be true)");
		tick(level, here, t + SOLO_CHANNEL + 8);
		log.accept("dig: after 10 s attuned " + readingUntil.containsKey(fake.getUUID()) + " (must be true)");
		// probe a tile next to the shard
		int pi = si > 0 ? si - 1 : si + 1, pj = sj;
		fake.setShiftKeyDown(false);
		use(level, fake, arena(cen(pi), cen(pj), 0));
		Probe pr = probes.get(key(pi, pj));
		log.accept("dig: probe shows " + (pr == null ? "none" : pr.count) + " (must be " + neighbours(pi, pj) + ")");
		// an empty dig: a strike and blank probes
		int[] empty = find(false);
		clickCd.clear();
		fake.setShiftKeyDown(true);
		use(level, fake, arena(cen(empty[0]), cen(empty[1]), 0));
		log.accept("dig: empty dig -> strikes " + strikes + " (must be 1), probes blank " + blanked + " (must be true)");
		clickCd.clear();
		fake.setShiftKeyDown(false);
		int before = probes.size();
		int[] other = find(false);
		use(level, fake, arena(cen(other[0]), cen(other[1]), 0));
		log.accept("dig: probe while blank adds none " + (probes.size() == before) + " (must be true)");
		// a cursed dig: a pit and a second strike
		int[] bad = find(true);
		clickCd.clear();
		fake.setShiftKeyDown(true);
		int mobs = run.mobs.size();
		use(level, fake, arena(cen(bad[0]), cen(bad[1]), 0));
		boolean pit = level.getBlockState(arena(cen(bad[0]), cen(bad[1]), 0)).isAir() && level.getBlockState(arena(cen(bad[0]) + 1, cen(bad[1]) + 1, -1)).isAir();
		log.accept("dig: cursed dig -> strikes " + strikes + " (must be 2), pit " + pit + " (must be true), mobs " + (run.mobs.size() > mobs) + " (must be true)");
		// the shard
		clickCd.clear();
		use(level, fake, arena(cen(si), cen(sj), 0));
		boolean chk = SkillChecks.devAnswer(fake, SkillChecks.LATE);
		log.accept("dig: shard's check opened " + chk + " (must be true), late release -> solved " + solved + " (must be false), strikes " + strikes + " (must be 2)");
		clickCd.clear();
		use(level, fake, arena(cen(si), cen(sj), 0));
		SkillChecks.devAnswer(fake, SkillChecks.GOOD);
		fake.setShiftKeyDown(false);
		log.accept("dig: shard dug -> solved " + solved + " (must be true)");
		for (Mob m : run.mobs) m.discard();
		run.mobs.clear();
		done.run();
	}

	/** An untouched tile, cursed or not, that is neither the shard nor the decoy. */
	private int[] find(boolean wantCursed) {
		for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
			if (state[i][j] != 0 || (i == si && j == sj) || (i == di && j == dj)) continue;
			if (cursed[i][j] == wantCursed) return new int[]{i, j};
		}
		return new int[]{0, 0};
	}
}
