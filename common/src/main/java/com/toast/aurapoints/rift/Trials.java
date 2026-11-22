package com.toast.aurapoints.rift;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.item.RelicShardItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The four trial rooms of a rift realm (5.46.0, Relic Trials). Each trial island holds one Relic Shard behind a puzzle;
 * mobs only come as the price of a mistake (or as pressure while it stays unsolved). Five kinds, four picked per run:
 * <ul>
 * <li>{@link Runes}: pillars light up in an order - hit them in that order;</li>
 * <li>{@link Lights}: a 4x4 grid of lamps, each press flips itself and its neighbours - light them all;</li>
 * <li>{@link Hunt}: the shard is buried in one of many rubble mounds; the Tuning Fork rings higher the closer it is;</li>
 * <li>{@link Memory}: a path over the void glows for a moment, then hides - walk it;</li>
 * <li>{@link Carry}: the shard is heavy; carry it over a crumbling path while the aura pushes you around.</li>
 * </ul>
 * Coordinates are in each island's own frame: f = forward (away from the hub), l = to the side.
 */
final class Trials {

	private Trials() {
	}

	enum Kind {
		RUNES, LIGHTS, HUNT, MEMORY, CARRY
	}

	static Trial make(Kind kind, Rifts.Run run, int index) {
		return switch (kind) {
			case RUNES -> new Runes(run, index);
			case LIGHTS -> new Lights(run, index);
			case HUNT -> new Hunt(run, index);
			case MEMORY -> new Memory(run, index);
			case CARRY -> new Carry(run, index);
		};
	}

	static final String DISPLAY_TAG = "aurapoints_rift_trial";

	// ---------------------------------------------------------------
	// The base
	// ---------------------------------------------------------------

	abstract static class Trial {
		final Rifts.Run run;
		final int index;
		final Kind kind;
		final BlockPos c;
		final int fx, fz, lx, lz;
		final RealmBuilder.Palette pal;
		boolean started, solved;
		/** The shard is out in the realm (dropped, carried or waiting on a plinth) and must be kept from getting lost. */
		boolean shardOut;
		final List<Display> displays = new ArrayList<>();

		Trial(Rifts.Run run, int index, Kind kind) {
			this.run = run;
			this.index = index;
			this.kind = kind;
			this.c = run.island(index);
			this.fx = RealmBuilder.DIRS[index][0];
			this.fz = RealmBuilder.DIRS[index][1];
			this.lx = -fz;
			this.lz = fx;
			this.pal = RealmBuilder.palette(run.aura);
		}

		/** A block in the island's frame; dy is relative to the floor (0 = the floor itself, 1 = standing height). */
		BlockPos at(int f, int l, int dy) {
			return new BlockPos(c.getX() + fx * f + lx * l, Rifts.FLOOR + dy, c.getZ() + fz * f + lz * l);
		}

		/** The island-frame (f, l) of a world position. */
		double f(Vec3 p) {
			return (p.x - c.getX() - 0.5) * fx + (p.z - c.getZ() - 0.5) * fz;
		}

		double l(Vec3 p) {
			return (p.x - c.getX() - 0.5) * lx + (p.z - c.getZ() - 0.5) * lz;
		}

		void set(ServerLevel level, int f, int l, int dy, Block b) {
			BlockPos p = at(f, l, dy);
			RealmBuilder.set(level, p.getX(), p.getY(), p.getZ(), b);
		}

		/** Air in the island's floor and underside (a hole you can fall through). */
		void hole(ServerLevel level, int f, int l) {
			for (int dy = -18; dy <= 2; dy++) set(level, f, l, dy, Blocks.AIR);
		}

		boolean contains(Vec3 p) {
			double dx = p.x - (c.getX() + 0.5), dz = p.z - (c.getZ() + 0.5);
			return dx * dx + dz * dz <= Mth.square(RealmBuilder.ISLAND_R + 1.5) && p.y > Rifts.FLOOR - 30;
		}

		/** Where the shard waits / comes back to if it gets lost. */
		Vec3 plinth() {
			return Vec3.atBottomCenterOf(at(0, 0, 1)).add(0, 0.2, 0);
		}

		abstract String name();

		abstract String hint();

		abstract void build(ServerLevel level, RandomSource r);

		/** The first player steps onto the island. */
		void begin(ServerLevel level, List<ServerPlayer> here) {
		}

		/** Every server tick while the run lasts; {@code here} = run players standing on this island. */
		abstract void tick(ServerLevel level, List<ServerPlayer> here, long now);

		/** A right-click on a block of this island (main hand). True = it was this trial's. */
		boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
			return false;
		}

		/** Solved: the shard comes out at {@code at} (or, for a shard already out, it is simply good now). */
		void solve(ServerLevel level, Vec3 at) {
			if (solved) return;
			solved = true;
			Rifts.onTrialSolved(level, run, this, at);
		}

		void remove() {
			for (Display d : displays) d.discard();
			displays.clear();
		}

		Display.TextDisplay label(ServerLevel level, Vec3 at, Component text, float scale) {
			Display.TextDisplay d = (Display.TextDisplay) com.toast.aurapoints.arena.Holograms.makePublic(level, at, text, scale, 0);
			if (d == null) return null;
			d.addTag(DISPLAY_TAG);
			level.addFreshEntity(d);
			displays.add(d);
			return d;
		}

		void actionBar(List<ServerPlayer> to, String text, ChatFormatting color) {
			for (ServerPlayer p : to) p.displayClientMessage(Component.literal(text).withStyle(color), true);
		}

		/** The price of a mistake: {@code n} of the aura's touched mobs at {@code at}. */
		void punish(ServerLevel level, Vec3 at, int n) {
			for (int k = 0; k < n; k++) Rifts.trialMob(level, run, at);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1f, 1.2f);
		}
	}

	// ---------------------------------------------------------------
	// Rune Sequence
	// ---------------------------------------------------------------

	static final class Runes extends Trial {
		private static final int PILLARS = 6, RADIUS = 6;
		private static final String[] GLYPHS = {"ᚠ", "ᚢ", "ᚦ", "ᚨ", "ᚱ", "ᚲ"};
		private static final int[] COLORS = {0xFF6B6B, 0xFFD166, 0x7BE495, 0x5BC0EB, 0xC77DFF, 0xFFFFFF};
		final int[][] pillar = new int[PILLARS][2];
		int[] seq;
		private int step, showAt = -1, showStep, wrongs;
		/** Pillars lit until a tick (game time), to put their cap back. */
		private final long[] litUntil = new long[PILLARS];

		Runes(Rifts.Run run, int index) {
			super(run, index, Kind.RUNES);
		}

		@Override
		String name() {
			return "Rune Sequence";
		}

		@Override
		String hint() {
			return "Watch the runes light up, then strike their pillars in the same order. The echo stone repeats it.";
		}

		@Override
		void build(ServerLevel level, RandomSource r) {
			for (int i = 0; i < PILLARS; i++) {
				// a ring that leaves the side toward the hub open
				double a = Math.PI * 2 * i / PILLARS + Math.PI / PILLARS;
				int f = (int) Math.round(Math.cos(a) * RADIUS), l = (int) Math.round(Math.sin(a) * RADIUS);
				pillar[i][0] = f;
				pillar[i][1] = l;
				set(level, f, l, 1, pal.edge());
				set(level, f, l, 2, pal.edge());
				set(level, f, l, 3, pal.accent());
				label(level, Vec3.atBottomCenterOf(at(f, l, 4)).add(0, 0.2, 0),
						Component.literal(GLYPHS[i]).withStyle(s -> s.withColor(TextColor.fromRgb(COLORS[pillarIndex(f, l)])).withBold(true)), 2.6f);
			}
			// the echo stone in the middle
			set(level, 0, 0, 1, Blocks.LODESTONE);
			label(level, Vec3.atBottomCenterOf(at(0, 0, 2)).add(0, 0.3, 0), Component.literal("Echo Stone").withStyle(ChatFormatting.GRAY), 0.6f);
			int players = Math.max(1, run.players.size());
			seq = new int[Math.min(7, 4 + players - 1)];
			for (int k = 0; k < seq.length; k++) {
				int n;
				do n = r.nextInt(PILLARS); while (k > 0 && n == seq[k - 1]);
				seq[k] = n;
			}
		}

		private int pillarIndex(int f, int l) {
			for (int i = 0; i < PILLARS; i++) if (pillar[i][0] == f && pillar[i][1] == l) return i;
			return 0;
		}

		@Override
		void begin(ServerLevel level, List<ServerPlayer> here) {
			showAt = (int) (level.getGameTime() + 50);
		}

		private void flash(ServerLevel level, int i, long now) {
			set(level, pillar[i][0], pillar[i][1], 3, pal.light());
			litUntil[i] = now + 10;
			Vec3 top = Vec3.atCenterOf(at(pillar[i][0], pillar[i][1], 3));
			level.playSound(null, top.x, top.y, top.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1.4f, 0.6f + i * 0.2f);
			level.sendParticles(AuraFx.dust(COLORS[i], 1.6f), top.x, top.y + 0.8, top.z, 14, 0.25, 0.5, 0.25, 0);
		}

		@Override
		void tick(ServerLevel level, List<ServerPlayer> here, long now) {
			for (int i = 0; i < PILLARS; i++) {
				if (litUntil[i] != 0 && now >= litUntil[i]) {
					litUntil[i] = 0;
					set(level, pillar[i][0], pillar[i][1], 3, pal.accent());
				}
			}
			if (solved || showAt < 0 || now < showAt) return;
			// playing the sequence: one rune every 16 ticks
			if ((now - showAt) % 16 == 0) {
				if (showStep < seq.length) {
					flash(level, seq[showStep], now);
					showStep++;
				} else {
					showAt = -1;
					showStep = 0;
					step = 0;
					actionBar(here, "Your turn: " + seq.length + " runes", ChatFormatting.AQUA);
				}
			}
		}

		@Override
		boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
			if (solved) return false;
			if (pos.equals(at(0, 0, 1))) {
				if (showAt < 0) showAt = (int) level.getGameTime() + 10;
				return true;
			}
			int hit = -1;
			for (int i = 0; i < PILLARS; i++) {
				BlockPos b = at(pillar[i][0], pillar[i][1], 0);
				if (pos.getX() == b.getX() && pos.getZ() == b.getZ() && pos.getY() > Rifts.FLOOR && pos.getY() <= Rifts.FLOOR + 3) hit = i;
			}
			if (hit < 0) return false;
			if (showAt >= 0) return true; // still showing: hands off
			long now = level.getGameTime();
			flash(level, hit, now);
			if (hit == seq[step]) {
				step++;
				if (step >= seq.length) {
					level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.4f);
					solve(level, plinth().add(0, 1.2, 0));
				}
			} else {
				wrongs++;
				step = 0;
				Vec3 at = Vec3.atBottomCenterOf(at(pillar[hit][0], pillar[hit][1], 1));
				level.playSound(null, at.x, at.y, at.z, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 1.5f, 0.5f);
				p.displayClientMessage(Component.literal("Wrong rune - the island stirs").withStyle(ChatFormatting.RED), true);
				punish(level, plinth(), com.toast.aurapoints.config.AuraConfig.RIFT_PUZZLE_FAIL_MOBS);
				showAt = (int) now + 70;
			}
			return true;
		}
	}

	// ---------------------------------------------------------------
	// Lights Out
	// ---------------------------------------------------------------

	static final class Lights extends Trial {
		private static final int N = 4;
		private final boolean[][] lit = new boolean[N][N];
		/** The presses that scrambled it (pressing them again solves it: dev test). */
		final List<int[]> scramble = new ArrayList<>();
		private long nextWatcher;

		Lights(Rifts.Run run, int index) {
			super(run, index, Kind.LIGHTS);
		}

		@Override
		String name() {
			return "Lights of the Rift";
		}

		@Override
		String hint() {
			return "Each tile you touch flips itself and its neighbours. Light all sixteen.";
		}

		int f(int i) {
			return i * 2 - 3;
		}

		@Override
		void build(ServerLevel level, RandomSource r) {
			for (boolean[] row : lit) java.util.Arrays.fill(row, true);
			// scrambled from the solved state, so it can always be solved
			int presses = 5 + r.nextInt(3);
			Set<Integer> done = new HashSet<>();
			while (done.size() < presses) {
				int k = r.nextInt(N * N);
				if (done.add(k)) {
					toggle(k / N, k % N);
					scramble.add(new int[]{k / N, k % N});
				}
			}
			// a few press sets change nothing at all (4x4 has quiet patterns): one more then
			if (allLit()) {
				toggle(1, 1);
				scramble.add(new int[]{1, 1});
			}
			// a raised platform with a tile every other block (easier to aim at)
			for (int a = -4; a <= 4; a++) for (int b = -4; b <= 4; b++) set(level, a, b, 0, pal.edge());
			for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) paint(level, i, j);
			label(level, Vec3.atBottomCenterOf(at(-6, 0, 2)), Component.literal("Light them all").withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb))), 0.9f);
		}

		private void toggle(int i, int j) {
			int[][] n = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
			for (int[] d : n) {
				int a = i + d[0], b = j + d[1];
				if (a >= 0 && a < N && b >= 0 && b < N) lit[a][b] = !lit[a][b];
			}
		}

		private boolean allLit() {
			for (boolean[] row : lit) for (boolean v : row) if (!v) return false;
			return true;
		}

		private void paint(ServerLevel level, int i, int j) {
			set(level, f(i), f(j), 1, lit[i][j] ? pal.light() : pal.base());
		}

		@Override
		void begin(ServerLevel level, List<ServerPlayer> here) {
			nextWatcher = level.getGameTime() + 600;
		}

		@Override
		boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
			if (solved || pos.getY() != Rifts.FLOOR + 1) return false;
			Vec3 v = Vec3.atCenterOf(pos);
			int fi = (int) Math.round(f(v)), li = (int) Math.round(l(v));
			if ((fi + 3) % 2 != 0 || (li + 3) % 2 != 0) return false;
			int i = (fi + 3) / 2, j = (li + 3) / 2;
			if (i < 0 || i >= N || j < 0 || j >= N) return false;
			toggle(i, j);
			for (int a = 0; a < N; a++) for (int b = 0; b < N; b++) paint(level, a, b);
			level.playSound(null, v.x, v.y, v.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.2f, lit[i][j] ? 1.4f : 0.8f);
			if (allLit()) {
				level.playSound(null, v.x, v.y, v.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.2f, 1.2f);
				solve(level, plinth().add(0, 1.2, 0));
			}
			return true;
		}

		@Override
		void tick(ServerLevel level, List<ServerPlayer> here, long now) {
			// a watcher now and then while it stays dark: pressure, not the point
			if (!solved && started && !here.isEmpty() && now >= nextWatcher) {
				nextWatcher = now + 600;
				punish(level, Vec3.atBottomCenterOf(at(-7, 0, 1)), 1);
			}
		}
	}

	// ---------------------------------------------------------------
	// Resonance Hunt
	// ---------------------------------------------------------------

	static final class Hunt extends Trial {
		final List<BlockPos> mounds = new ArrayList<>();
		BlockPos target;
		private final Map<UUID, Long> nextRing = new HashMap<>();

		Hunt(Rifts.Run run, int index) {
			super(run, index, Kind.HUNT);
		}

		@Override
		String name() {
			return "Resonance Hunt";
		}

		@Override
		String hint() {
			return "The shard is buried in one of the mounds. Hold the Tuning Fork: it rings higher the closer you get. Right-click a mound to dig.";
		}

		@Override
		void build(ServerLevel level, RandomSource r) {
			int tries = 0;
			while (mounds.size() < 20 && tries++ < 400) {
				int f = r.nextInt(19) - 9, l = r.nextInt(19) - 9;
				if (f * f + l * l > 90 || (f * f + l * l < 5) || f < -8) continue;
				BlockPos p = at(f, l, 1);
				boolean close = false;
				for (BlockPos m : mounds) if (m.distSqr(p) < 5) close = true;
				if (close) continue;
				mounds.add(p);
				RealmBuilder.set(level, p.getX(), p.getY(), p.getZ(), r.nextBoolean() ? pal.base() : Blocks.GRAVEL);
				if (r.nextInt(3) == 0) RealmBuilder.set(level, p.getX(), p.getY() + 1, p.getZ(), pal.base());
			}
			target = mounds.get(r.nextInt(mounds.size()));
		}

		@Override
		void begin(ServerLevel level, List<ServerPlayer> here) {
			for (ServerPlayer p : here) giveFork(p);
		}

		static void giveFork(ServerPlayer p) {
			if (p.getInventory().contains(new ItemStack(AuraPoints.TUNING_FORK.get()))) return;
			p.getInventory().add(new ItemStack(AuraPoints.TUNING_FORK.get()));
		}

		@Override
		void tick(ServerLevel level, List<ServerPlayer> here, long now) {
			if (solved) return;
			if (now % 40 == 0) for (ServerPlayer p : here) giveFork(p);
			Vec3 t = Vec3.atCenterOf(target);
			for (ServerPlayer p : here) {
				boolean holding = p.getMainHandItem().is(AuraPoints.TUNING_FORK.get()) || p.getOffhandItem().is(AuraPoints.TUNING_FORK.get());
				if (!holding) continue;
				double d = Math.sqrt(p.position().distanceToSqr(t.x, p.getY(), t.z));
				long next = nextRing.getOrDefault(p.getUUID(), 0L);
				if (now < next) continue;
				// closer = higher and quicker
				nextRing.put(p.getUUID(), now + 6 + (long) Math.min(30, d * 1.5));
				float pitch = (float) Mth.clamp(2.0 - d / 16.0 * 1.5, 0.5, 2.0);
				p.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.8f, pitch);
				String word = d < 2.5 ? "BURNING" : d < 5 ? "hot" : d < 9 ? "warm" : d < 14 ? "cool" : "cold";
				ChatFormatting col = d < 2.5 ? ChatFormatting.RED : d < 5 ? ChatFormatting.GOLD : d < 9 ? ChatFormatting.YELLOW : d < 14 ? ChatFormatting.AQUA : ChatFormatting.BLUE;
				p.displayClientMessage(Component.literal("♪ The fork hums " + word).withStyle(col), true);
				level.sendParticles(p, ParticleTypes.NOTE, true, p.getX(), p.getY() + 2.1, p.getZ(), 1, 0, 0, 0, pitch / 2f);
			}
		}

		@Override
		boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
			if (solved) return false;
			BlockPos m = null;
			for (BlockPos b : mounds) if (b.getX() == pos.getX() && b.getZ() == pos.getZ() && pos.getY() >= b.getY() && pos.getY() <= b.getY() + 1) m = b;
			if (m == null) return false;
			mounds.remove(m);
			RealmBuilder.set(level, m.getX(), m.getY() + 1, m.getZ(), Blocks.AIR);
			RealmBuilder.set(level, m.getX(), m.getY(), m.getZ(), Blocks.AIR);
			Vec3 at = Vec3.atBottomCenterOf(m);
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, pal.base().defaultBlockState()), at.x, at.y + 0.5, at.z, 30, 0.3, 0.3, 0.3, 0.1);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1f, 0.8f);
			if (m.equals(target)) {
				solve(level, at.add(0, 0.5, 0));
				return true;
			}
			// a wrong mound: something was waiting in it
			if (level.random.nextBoolean()) {
				punish(level, at, 1);
			} else {
				level.sendParticles(AuraFx.dust(run.aura.rgb, 2f), at.x, at.y + 0.5, at.z, 30, 1, 0.5, 1, 0);
				level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.6f, 1.6f);
				for (ServerPlayer near : level.getEntitiesOfClass(ServerPlayer.class, new net.minecraft.world.phys.AABB(at, at).inflate(2.5))) {
					near.hurt(level.damageSources().magic(), 3f);
					Vec3 push = near.position().subtract(at).normalize().scale(0.8);
					near.setDeltaMovement(push.x, 0.4, push.z);
					near.hurtMarked = true;
				}
			}
			p.displayClientMessage(Component.literal("Only rubble... " + mounds.size() + " mounds left").withStyle(ChatFormatting.GRAY), true);
			return true;
		}
	}

	// ---------------------------------------------------------------
	// Memory Path
	// ---------------------------------------------------------------

	static final class Memory extends Trial {
		static final int W = 2, L0 = -4, L1 = 4, SHOW = 100;
		/** path[f - L0] = l of the safe tile in that row... rows may hold more than one safe tile: a set. */
		final Set<Long> safe = new HashSet<>();
		private final Map<Long, Long> broken = new HashMap<>();
		private long shownUntil, nextShow;

		Memory(Rifts.Run run, int index) {
			super(run, index, Kind.MEMORY);
		}

		@Override
		String name() {
			return "Memory Path";
		}

		@Override
		String hint() {
			return "Stand on the start pad: a safe path lights up for a moment. Remember it and walk across.";
		}

		static long key(int f, int l) {
			return ((long) f << 32) ^ (l & 0xffffffffL);
		}

		@Override
		void build(ServerLevel level, RandomSource r) {
			// the void under the grid and its sides
			for (int f = L0; f <= L1; f++) for (int l = -W - 1; l <= W + 1; l++) hole(level, f, l);
			int l = r.nextInt(W * 2 + 1) - W, f = L0;
			safe.add(key(f, l));
			while (f < L1) {
				int move = r.nextInt(5);
				int nl = move == 0 ? l - 1 : move == 1 ? l + 1 : l;
				if (nl != l && nl >= -W && nl <= W && !safe.contains(key(f, nl))) l = nl;
				else f++;
				safe.add(key(f, l));
			}
			for (int a = L0; a <= L1; a++) for (int b = -W; b <= W; b++) tile(level, a, b, false);
			// the pads
			for (int b = -W; b <= W; b++) {
				set(level, L0 - 1, b, 0, pal.light());
				set(level, L1 + 1, b, 0, pal.light());
			}
			label(level, Vec3.atBottomCenterOf(at(L0 - 2, 0, 2)), Component.literal("Start").withStyle(ChatFormatting.GRAY), 0.8f);
		}

		private void tile(ServerLevel level, int f, int l, boolean show) {
			set(level, f, l, 0, show && safe.contains(key(f, l)) ? pal.light() : pal.accent());
		}

		@Override
		void tick(ServerLevel level, List<ServerPlayer> here, long now) {
			// broken tiles come back
			for (var it = broken.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				if (now < e.getValue()) continue;
				it.remove();
				int f = (int) (e.getKey() >> 32), l = (int) (long) e.getKey();
				tile(level, f, l, now < shownUntil);
			}
			if (shownUntil != 0 && now >= shownUntil) {
				shownUntil = 0;
				for (int a = L0; a <= L1; a++) for (int b = -W; b <= W; b++) if (!broken.containsKey(key(a, b))) tile(level, a, b, false);
			}
			for (ServerPlayer p : here) {
				double pf = f(p.position()), pl = l(p.position());
				int fi = (int) Math.round(pf), li = (int) Math.round(pl);
				// fell through: back to the start
				if (p.getY() < Rifts.FLOOR - 3 && Math.abs(pl) <= W + 1.5 && pf >= L0 - 1 && pf <= L1 + 1) {
					Vec3 s = Vec3.atBottomCenterOf(at(L0 - 2, 0, 1));
					p.teleportTo(level, s.x, s.y, s.z, yawToward(), 0f);
					p.fallDistance = 0;
					p.displayClientMessage(Component.literal("The path forgets you. Try again.").withStyle(ChatFormatting.GRAY), true);
					continue;
				}
				if (solved) continue;
				// on the start pad: show the path (once in a while)
				if (fi == L0 - 1 && Math.abs(li) <= W && p.onGround() && now >= nextShow) {
					nextShow = now + SHOW + 60;
					shownUntil = now + SHOW;
					for (int a = L0; a <= L1; a++) for (int b = -W; b <= W; b++) if (!broken.containsKey(key(a, b))) tile(level, a, b, true);
					level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.5f, 1f);
				}
				// a wrong tile breaks under you
				if (p.onGround() && fi >= L0 && fi <= L1 && Math.abs(li) <= W && !safe.contains(key(fi, li)) && !broken.containsKey(key(fi, li))) {
					broken.put(key(fi, li), now + 60);
					set(level, fi, li, 0, Blocks.AIR);
					Vec3 at = Vec3.atCenterOf(at(fi, li, 0));
					level.playSound(null, at.x, at.y, at.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1f, 0.7f);
					level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, pal.accent().defaultBlockState()), at.x, at.y, at.z, 20, 0.3, 0.1, 0.3, 0.1);
				}
				// across
				if (fi >= L1 + 1 && Math.abs(li) <= W + 1 && p.onGround()) {
					// the grid stays walkable for the others
					for (Long k : safe) broken.remove(k);
					for (int a = L0; a <= L1; a++) for (int b = -W; b <= W; b++) set(level, a, b, 0, pal.floor());
					solve(level, Vec3.atBottomCenterOf(at(L1 + 3, 0, 1)).add(0, 0.6, 0));
				}
			}
		}

		private float yawToward() {
			// facing forward along the island's f axis
			return (float) Math.toDegrees(Math.atan2(-fx, fz));
		}

		@Override
		Vec3 plinth() {
			return Vec3.atBottomCenterOf(at(L1 + 3, 0, 1)).add(0, 0.2, 0);
		}
	}

	// ---------------------------------------------------------------
	// Carry Trial
	// ---------------------------------------------------------------

	static final class Carry extends Trial {
		static final int GAP0 = -3, GAP1 = 4, START = -6, END = 7;
		private final Set<Long> path = new HashSet<>();
		/** Path tiles stepped on -> the tick they crumble; crumbled ones -> the tick they come back. */
		private final Map<Long, Long> crumbling = new HashMap<>(), gone = new HashMap<>();
		private long nextGust, gustAt = -1;
		private int gustSide = 1;

		Carry(Rifts.Run run, int index) {
			super(run, index, Kind.CARRY);
		}

		@Override
		String name() {
			return "Weight of the Relic";
		}

		@Override
		String hint() {
			return "The shard is heavy. Carry it over the crumbling path to the far side - fall, and it goes back to its plinth.";
		}

		private static long key(int f, int l) {
			return ((long) f << 32) ^ (l & 0xffffffffL);
		}

		@Override
		void build(ServerLevel level, RandomSource r) {
			for (int f = GAP0; f <= GAP1; f++) for (int l = -9; l <= 9; l++) hole(level, f, l);
			// a winding two-wide path across the gap
			double phase = r.nextDouble() * Math.PI * 2;
			for (int f = GAP0; f <= GAP1; f++) {
				int mid = (int) Math.round(Math.sin(f * 0.9 + phase) * 3);
				for (int l = mid - 1; l <= mid; l++) {
					path.add(key(f, l));
					set(level, f, l, 0, pal.floor());
					set(level, f, l, -1, pal.base());
				}
			}
			set(level, START, 0, 1, pal.edge());
			set(level, END, 0, 1, pal.light());
			label(level, Vec3.atBottomCenterOf(at(END, 0, 3)), Component.literal("Bring it here").withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb))), 0.8f);
		}

		@Override
		Vec3 plinth() {
			return Vec3.atBottomCenterOf(at(START, 0, 2)).add(0, 0.2, 0);
		}

		@Override
		void begin(ServerLevel level, List<ServerPlayer> here) {
			Rifts.dropShard(level, run, this, plinth());
			nextGust = level.getGameTime() + 80;
		}

		ServerPlayer carrier(List<ServerPlayer> here) {
			for (ServerPlayer p : here) if (Rifts.holdsShard(p, run, index)) return p;
			return null;
		}

		@Override
		void tick(ServerLevel level, List<ServerPlayer> here, long now) {
			for (var it = crumbling.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				if (now < e.getValue()) continue;
				it.remove();
				int f = (int) (e.getKey() >> 32), l = (int) (long) e.getKey();
				set(level, f, l, 0, Blocks.AIR);
				set(level, f, l, -1, Blocks.AIR);
				Vec3 at = Vec3.atCenterOf(at(f, l, 0));
				level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, pal.floor().defaultBlockState()), at.x, at.y, at.z, 12, 0.3, 0.2, 0.3, 0.05);
				level.playSound(null, at.x, at.y, at.z, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.8f, 0.7f);
				gone.put(e.getKey(), now + 90);
			}
			for (var it = gone.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				if (now < e.getValue()) continue;
				it.remove();
				int f = (int) (e.getKey() >> 32), l = (int) (long) e.getKey();
				set(level, f, l, 0, pal.floor());
				set(level, f, l, -1, pal.base());
			}
			ServerPlayer carrier = solved ? null : carrier(here);
			for (ServerPlayer p : here) {
				double pf = f(p.position()), pl = l(p.position());
				int fi = (int) Math.round(pf), li = (int) Math.floor(pl + 0.5);
				// fell: back to the start, the shard back to its plinth
				if (p.getY() < Rifts.FLOOR - 3 && pf >= GAP0 - 1 && pf <= GAP1 + 1) {
					if (!solved && Rifts.holdsShard(p, run, index)) {
						Rifts.takeShard(p, run, index);
						Rifts.dropShard(level, run, this, plinth());
						p.displayClientMessage(Component.literal("The shard slips back to its plinth.").withStyle(ChatFormatting.GOLD), true);
					}
					Vec3 s = Vec3.atBottomCenterOf(at(START - 2, 0, 1));
					p.teleportTo(level, s.x, s.y, s.z, p.getYRot(), 0f);
					p.fallDistance = 0;
					continue;
				}
				// path tiles crumble a moment after you step on them
				if (p.onGround() && path.contains(key(fi, li)) && !crumbling.containsKey(key(fi, li)) && !gone.containsKey(key(fi, li))) {
					crumbling.put(key(fi, li), now + 22);
				}
			}
			if (solved) return;
			if (carrier != null) {
				// heavy
				carrier.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 15, 1, false, false, true));
				carrier.setSprinting(false);
				if (f(carrier.position()) >= END - 1.5 && carrier.onGround()) {
					level.playSound(null, carrier.getX(), carrier.getY(), carrier.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.2f);
					carrier.displayClientMessage(Component.literal("The shard settles: take it to the altar.").withStyle(ChatFormatting.GREEN), true);
					solve(level, null);
					return;
				}
			}
			// the aura gusts across the gap: a warning, then a shove
			if (now >= nextGust && !here.isEmpty()) {
				nextGust = now + 70;
				gustAt = now + 20;
				gustSide = -gustSide;
				for (int f = GAP0; f <= GAP1; f += 2) {
					Vec3 w = Vec3.atCenterOf(at(f, -gustSide * 6, 1));
					level.sendParticles(AuraFx.dust(run.aura.rgb, 1.6f), w.x, w.y, w.z, 6, 0.2, 0.6, 0.2, 0);
				}
				level.playSound(null, c.getX(), Rifts.FLOOR + 2, c.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 1.5f, 0.8f);
			}
			if (gustAt > 0 && now >= gustAt) {
				gustAt = -1;
				level.playSound(null, c.getX(), Rifts.FLOOR + 2, c.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.HOSTILE, 1.5f, 0.7f);
				for (ServerPlayer p : here) {
					double pf = f(p.position());
					if (pf < GAP0 - 0.5 || pf > GAP1 + 0.5) continue;
					p.setDeltaMovement(lx * gustSide * 0.55, 0.25, lz * gustSide * 0.55);
					p.hurtMarked = true;
				}
			}
		}
	}
}
