package com.toast.aurapoints.rift;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Hall of Echoes (5.49.0, replaces Memory Path): a ruined colonnade over the void with a floor of 13 x 25 tiles (3x3
 * blocks each) in three stages, split by checkpoint bridges. Each stage hides a path (8 -> 12 -> 16 tiles on Medium);
 * every other tile breaks under you (a strike) and you fall - walkers are caught and put back on the stage's
 * checkpoint. The next stage's tiles only form once the last one is crossed.
 * <p>
 * With two or more players one is the Seer, up on a perch beside the stage: only the Seer sees the path glow (per-player
 * block updates) and calls it out to the Walkers. The perch moves with each stage; in stage 3 a different player becomes
 * the Seer. Alone, an echo plays: the camera looks straight down on the stage while the path glows for a few seconds
 * (shorter on higher tiers, mirrored on Extreme+); the echo pad on each checkpoint replays it.
 * <p>
 * Tiers: longer paths from Hard; on Hard+ the Seer's sight flickers; Extreme: path tiles crumble 10 s after the first
 * step; Hardcore: the void is lethal (a fall knocks you out).
 */
final class TrialEchoes extends Trials.Trial {

	static final int T = 3, COLS = 13, ROWS = 25, F0 = -38, L0 = -19;
	/** Rows of each stage (inclusive); checkpoint rows sit between them (6 and 15). */
	static final int[][] STAGES = {{0, 5}, {7, 14}, {16, 24}};
	static final int[] PERCH_F = {-30, -6, 23};
	static final int PERCH_DY = 13, PAD_L = -14, PLINTH_F = 44;

	int stage;
	final List<int[]> path = new ArrayList<>();
	final Set<Integer> pathSet = new HashSet<>();
	/** Broken tiles -> when they come back; path tiles that crumble (Extreme) -> when. */
	final Map<Integer, Long> broken = new HashMap<>(), crumbleAt = new HashMap<>();
	UUID seer, firstSeer;
	final Set<BlockPos> seerShown = new HashSet<>();
	boolean seerOn;
	private long nextSeerSend;
	UUID soloViewer;
	final List<BlockPos> soloShown = new ArrayList<>();
	long soloUntil, soloAutoAt = -1, padNext;
	private int hintShown;
	private List<ServerPlayer> lastHere = List.of();
	private long lastNow;

	TrialEchoes(Rifts.Run run, int index) {
		super(run, index, Trials.Kind.ECHOES);
	}

	@Override
	String name() {
		return "Hall of Echoes";
	}

	@Override
	String hint() {
		return lastHere.size() <= 1 ? "Watch the echo from above: the path glows for a moment. Walk it - every other tile breaks. The echo pad on each checkpoint replays it."
				: "The Seer on the perch sees the path glow - call it out! Walkers: step only where the Seer says. In the last stage someone else sees.";
	}

	@Override
	int arenaHalf() {
		return 60;
	}

	int[] lengths() {
		return switch (tier) {
			case 0 -> new int[]{7, 10, 12};
			case 1 -> new int[]{8, 12, 16};
			case 2 -> new int[]{9, 14, 18};
			default -> new int[]{10, 15, 20};
		};
	}

	/** How long the solo echo shows the path. */
	int showTicks() {
		return switch (tier) {
			case 0 -> 140;
			case 1 -> 110;
			case 2 -> 85;
			case 3 -> 70;
			default -> 60;
		};
	}

	boolean mirrored() {
		return tier >= 3;
	}

	// --- frame

	static int key(int row, int col) {
		return row * 32 + col;
	}

	int rowF(int row) {
		return F0 + row * T + 1;
	}

	int colL(int col) {
		return L0 + col * T + 1;
	}

	/** Where walkers stand at checkpoint s (0 = the start plaza). */
	int cpF(int s) {
		return s == 0 ? F0 - 3 : rowF(STAGES[s][0] - 1);
	}

	Vec3 checkpoint(int s) {
		return DigDecor.v(this, cpF(Math.min(s, 2)), 0, 1);
	}

	Vec3 perch(int s) {
		return DigDecor.v(this, PERCH_F[s], -23, PERCH_DY + 1);
	}

	float perchYaw() {
		return (float) Math.toDegrees(Math.atan2(-lx, lz));
	}

	// ---------------------------------------------------------------
	// Build
	// ---------------------------------------------------------------

	@Override
	void build(ServerLevel level, RandomSource r) {
		// the start plaza (the way back stands on it) and the end plaza with the shard's plinth and a gate
		DigDecor.island(level, this, r, -50, 0, 11, 19, 14, pal.floor());
		for (int l = -19; l <= 19; l++) set(level, F0 - 1, l, 0, pal.accent());
		DigDecor.island(level, this, r, 46, 0, 9, 18, 14, pal.floor());
		for (int l = -19; l <= 19; l++) set(level, F0 + ROWS * T, l, 0, pal.accent());
		for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) set(level, PLINTH_F + a, b, 1, a == 0 && b == 0 ? pal.light() : pal.edge());
		for (int l : new int[]{-7, 7}) DigDecor.pillar(level, this, r, 51, l, 0, 12, false);
		DigDecor.arch(level, this, r, 51, -7, 7, 9, 16, false);
		DigDecor.banner(level, this, 49, -7, 8, -1, 0);
		DigDecor.banner(level, this, 49, 7, 8, -1, 0);
		for (int l : new int[]{-11, 11}) DigDecor.brazier(level, this, 40, l, 1);
		for (int l : new int[]{-9, 9}) DigDecor.brazier(level, this, -48, l, 1);
		for (int l : new int[]{-14, 14}) DigDecor.brazier(level, this, -42, l, 1);
		// the echo stone on the start plaza
		for (int dy = 1; dy <= 7; dy++) for (int b = -1; b <= 1; b++) set(level, -52, -10 + b, dy, dy == 7 ? pal.accent() : b == 0 && dy % 3 == 0 ? pal.light() : pal.edge());
		DigDecor.banner(level, this, -51, -11, 5, 1, 0);
		DigDecor.banner(level, this, -51, -9, 5, 1, 0);
		label(level, DigDecor.v(this, -50.5, -10, 4.5), Component.literal("Seer: watch from the perch\nWalkers: step only where the Seer says").withStyle(ChatFormatting.GRAY), 0.8f);
		// checkpoint bridges between the stages
		for (int s = 1; s <= 2; s++) {
			int row = STAGES[s][0] - 1, f0 = F0 + row * T;
			DigDecor.slab(level, this, f0, f0 + 2, -25, 25, 4, pal.edge());
			for (int l = -25; l <= 25; l++) set(level, f0 + 1, l, 0, Math.abs(l) % 6 == 0 ? pal.light() : pal.accent());
		}
		// echo pads (used alone) on every checkpoint
		for (int s = 0; s <= 2; s++) {
			set(level, cpF(s), PAD_L, 0, pal.light());
			label(level, DigDecor.v(this, cpF(s), PAD_L, 2.6), Component.literal("Echo pad").withStyle(ChatFormatting.DARK_AQUA), 0.7f);
		}
		// the colonnade: pillars over the void on both sides, pointed arches across, lanterns on chains
		Set<Integer> left = new HashSet<>(), right = new HashSet<>(), brokenRight = new HashSet<>();
		for (int f = -42; f <= 42; f += 12) {
			boolean nearPerch = false;
			for (int pf : PERCH_F) if (Math.abs(f - pf) <= 5) nearPerch = true;
			if (!nearPerch) {
				DigDecor.pillar(level, this, r, f, -23, -14, 34, false);
				DigDecor.hangingFoot(level, this, f, -23, -14, 10);
				DigDecor.banner(level, this, f, -21, 10, 0, 1);
				left.add(f);
			}
			boolean broken = r.nextInt(4) == 0;
			DigDecor.pillar(level, this, r, f, 23, -14, 34, broken);
			DigDecor.hangingFoot(level, this, f, 23, -14, 10);
			if (!broken) DigDecor.banner(level, this, f, 21, 10, 0, -1);
			else brokenRight.add(f);
			right.add(f);
		}
		for (int f : left) {
			if (!right.contains(f)) continue;
			boolean broken = brokenRight.contains(f) || r.nextInt(4) == 0;
			DigDecor.arch(level, this, r, f, -23, 23, 20, 27, broken);
			if (!broken) for (int l : new int[]{-21, 21}) DigDecor.chainLantern(level, this, f, l, 21, 3 + r.nextInt(2));
		}
		for (int s = 0; s < 3; s++) perch(level, r, s);
		// debris drifting beside and under the hall
		for (int k = 0; k < 26; k++) {
			int f = r.nextInt(110) - 55;
			boolean under = k % 3 == 0;
			int l = under ? r.nextInt(37) - 18 : (r.nextBoolean() ? 1 : -1) * (30 + r.nextInt(18));
			int dy = under ? -18 - r.nextInt(9) : -15 + r.nextInt(40);
			DigDecor.debris(level, this, r, f, l, dy, 2 + r.nextInt(4));
		}
		// hidden light over the floor and the plazas
		for (int f = -58; f <= 54; f += 9) for (int l = -18; l <= 18; l += 9) DigDecor.glow(level, this, f, l, 4, 13);
		buildStage(level, 0);
		rollPath(r);
	}

	/** A Seer's perch beside stage s: a railed balcony on a pillar, with a canopy, a lantern and a banner. */
	private void perch(ServerLevel level, RandomSource r, int s) {
		int pf = PERCH_F[s], dy = PERCH_DY;
		for (int f = pf - 3; f <= pf + 3; f++) {
			for (int l = -27; l <= -21; l++) {
				boolean rim = f == pf - 3 || f == pf + 3 || l == -27 || l == -21;
				set(level, f, l, dy, rim ? pal.edge() : pal.floor());
				set(level, f, l, dy - 1, pal.edge());
				if (Math.abs(f - pf) <= 2 && l >= -26 && l <= -22) set(level, f, l, dy - 2, pal.base());
				if (rim && l != -27) {
					set(level, f, l, dy + 1, pal.edge());
					set(level, f, l, dy + 2, Blocks.BARRIER);
				}
			}
		}
		// back wall, corner posts and canopy
		for (int f = pf - 3; f <= pf + 3; f++) for (int y = dy + 1; y <= dy + 5; y++) set(level, f, -27, y, y == dy + 3 ? pal.accent() : pal.edge());
		for (int f : new int[]{pf - 3, pf + 3}) for (int y = dy + 1; y <= dy + 5; y++) set(level, f, -24, y, pal.edge());
		for (int f = pf - 3; f <= pf + 3; f++) for (int l = -27; l <= -24; l++) set(level, f, l, dy + 6, l == -24 ? pal.accent() : pal.edge());
		DigDecor.chainLantern(level, this, pf, -25, dy + 6, 1);
		DigDecor.banner(level, this, pf, -26, dy + 3, 0, 1);
		// its pillar, down into the void
		for (int a = -1; a <= 1; a++) for (int b = -25; b <= -23; b++) for (int y = -10; y <= dy - 2; y++) set(level, pf + a, b, y, a == 0 || (y % 7 == 0) ? pal.edge() : pal.floor());
		DigDecor.hangingFoot(level, this, pf, -24, -10, 8);
		label(level, DigDecor.v(this, pf, -24, dy + 8.5), Component.literal("Seer's Perch").withStyle(s2 -> s2.withColor(rgb).withBold(true)), 1.2f);
	}

	private static final Block[] GLOW = {Blocks.BLUE_CONCRETE, Blocks.LIME_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.RED_CONCRETE};

	/** The path's glow: the wing's colour in concrete, so it stands out on any palette (the light block vanished on white floors). */
	private BlockState glowState() {
		return GLOW[Math.max(0, Math.min(3, index))].defaultBlockState();
	}

	private Block tileBlock(int row, int col) {
		Block alt = pal.base() != pal.floor() ? pal.base() : pal.edge();
		return ((row + col) & 1) == 0 ? pal.floor() : alt;
	}

	private void tile(ServerLevel level, int row, int col, Block b) {
		int f = F0 + row * T, l = L0 + col * T;
		for (int a = 0; a < T; a++) for (int c = 0; c < T; c++) set(level, f + a, l + c, 0, b);
	}

	private void buildStage(ServerLevel level, int s) {
		for (int row = STAGES[s][0]; row <= STAGES[s][1]; row++) for (int col = 0; col < COLS; col++) tile(level, row, col, tileBlock(row, col));
		Vec3 c = DigDecor.v(this, (rowF(STAGES[s][0]) + rowF(STAGES[s][1])) / 2.0, 0, 0.5);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 120, 9, 0.3, 9, 0.05);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 2f, 0.6f);
	}

	private void clearStage(ServerLevel level, int s) {
		for (int row = STAGES[s][0]; row <= STAGES[s][1]; row++) for (int col = 0; col < COLS; col++) tile(level, row, col, Blocks.AIR);
	}

	/** A path across the current stage: one tile per row plus sidesteps, never doubling back. */
	void rollPath(RandomSource r) {
		path.clear();
		pathSet.clear();
		int r0 = STAGES[stage][0], r1 = STAGES[stage][1], rows = r1 - r0 + 1;
		int len = lengths()[stage];
		int[] side = new int[rows];
		// sidesteps never go in the last row: it already leads onto the checkpoint bridge, so exactly one tile of it is lit
		int sideRows = Math.max(1, rows - 1);
		for (int k = 0; k < Math.max(0, len - rows); k++) side[r.nextInt(sideRows)]++;
		int col = 2 + r.nextInt(COLS - 4), carry = 0;
		for (int i = 0; i < rows; i++) {
			add(r0 + i, col);
			if (i == rows - 1) break;
			int want = side[i] + carry, dir = r.nextBoolean() ? 1 : -1;
			if (col + dir * want < 0 || col + dir * want >= COLS) dir = -dir;
			int room = dir > 0 ? COLS - 1 - col : col;
			int go = Math.min(want, room);
			carry = want - go;
			for (int k = 0; k < go; k++) {
				col += dir;
				add(r0 + i, col);
			}
		}
	}

	private void add(int row, int col) {
		path.add(new int[]{row, col});
		pathSet.add(key(row, col));
	}

	// ---------------------------------------------------------------
	// Play
	// ---------------------------------------------------------------

	@Override
	void begin(ServerLevel level, List<ServerPlayer> here) {
		lastHere = here;
		long now = level.getGameTime();
		if (here.size() >= 2) pickSeer(level, here, false);
		else soloAutoAt = now + 170;
	}

	@Override
	RiftCam intro() {
		int pf = PERCH_F[0];
		return RiftCam.shot(rgb).title(name(), run.difficulty.label).skippable(true)
				.key(0, DigDecor.v(this, 0, 0, 100), DigDecor.v(this, 0.3, 0, 0))
				.key(55, DigDecor.v(this, -30, 0, 40), DigDecor.v(this, -29.7, 0, 0))
				.key(105, DigDecor.v(this, pf, -24, 15.6), DigDecor.v(this, pf + 1, 2, 0))
				.key(150, DigDecor.v(this, -50, 0, 3.2), DigDecor.v(this, -25, 0, 1));
	}

	@Override
	void tick(ServerLevel level, List<ServerPlayer> here, long now) {
		lastHere = here;
		lastNow = now;
		for (ServerPlayer p : here) catchFall(level, p);
		if (solved) return;
		restore(level, now);
		if (now % 10 == 0) seerTick(level, here, now);
		if (soloViewer != null && now >= soloUntil) revertSolo(level);
		else if (soloViewer != null && now % 20 == 10) resendSolo(level);
		if (here.size() == 1) {
			ServerPlayer p = here.get(0);
			if (soloAutoAt >= 0 && now >= soloAutoAt) {
				soloAutoAt = -1;
				echo(level, p, now);
			}
			if (now % 5 == 0) pad(level, p, now);
		}
		for (ServerPlayer p : new ArrayList<>(here)) if (walk(level, p, here, now)) break;
		if (now % 20 == 0 && hintsAllowed()) hints(level, here);
	}

	/** A fall below the floor: caught and put back on the checkpoint, or knocked out on Hardcore. */
	private void catchFall(ServerLevel level, ServerPlayer p) {
		double dy = p.getY() - Rifts.FLOOR;
		if (dy > -4) return;
		if (tier >= 4) {
			if (dy < -12) Rifts.knockoutFromTrial(p);
			return;
		}
		Vec3 to = solved ? DigDecor.v(this, PLINTH_F - 4, 0, 1) : checkpoint(stage);
		DigDecor.tp(p, level, to, forwardYaw(), 0f);
		p.displayClientMessage(Component.literal("The echoes catch you. Back to the checkpoint.").withStyle(ChatFormatting.GRAY), true);
		level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 1.2f);
	}

	/** Broken tiles come back; Extreme path tiles crumble on their timer. */
	private void restore(ServerLevel level, long now) {
		for (Iterator<Map.Entry<Integer, Long>> it = broken.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (now < e.getValue()) continue;
			it.remove();
			int row = e.getKey() / 32, col = e.getKey() % 32;
			if (row >= STAGES[stage][0] && row <= STAGES[stage][1]) tile(level, row, col, tileBlock(row, col));
		}
		for (Iterator<Map.Entry<Integer, Long>> it = crumbleAt.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			int row = e.getKey() / 32, col = e.getKey() % 32;
			long left = e.getValue() - now;
			if (left <= 60 && now % 10 == 0) {
				Vec3 c = DigDecor.v(this, rowF(row), colL(col), 0.9);
				level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, tileBlock(row, col).defaultBlockState()), c.x, c.y - 1, c.z, 8, 1, 0.1, 1, 0);
				level.playSound(null, c.x, c.y, c.z, SoundEvents.GRAVEL_HIT, SoundSource.BLOCKS, 0.8f, 0.6f);
			}
			if (left > 0) continue;
			it.remove();
			breakTile(level, row, col, now, 300);
		}
	}

	private void breakTile(ServerLevel level, int row, int col, long now, int back) {
		Block was = tileBlock(row, col);
		tile(level, row, col, Blocks.AIR);
		broken.put(key(row, col), now + back);
		Vec3 c = DigDecor.v(this, rowF(row), colL(col), 0.5);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.2f, 0.6f);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, was.defaultBlockState()), c.x, c.y, c.z, 40, 1, 0.2, 1, 0.1);
	}

	/** A walker's step. True = something big happened (a strike or a stage cleared): stop the loop this tick. */
	private boolean walk(ServerLevel level, ServerPlayer p, List<ServerPlayer> here, long now) {
		if (p.getUUID().equals(seer) || !p.onGround()) return false;
		Vec3 pos = p.position();
		double dy = pos.y - Rifts.FLOOR;
		if (dy < 0.9 || dy > 1.6) return false;
		double u = af(pos) - (F0 - 0.5), w = al(pos) - (L0 - 0.5);
		int row = Mth.floor(u / T), col = Mth.floor(w / T);
		if (row > STAGES[stage][1] && Math.abs(al(pos)) <= 26) {
			stageCleared(level, here, now);
			return true;
		}
		if (row < STAGES[stage][0] || col < 0 || col >= COLS) return false;
		double fu = u - row * T, fw = w - col * T;
		if (fu < 0.3 || fu > T - 0.3 || fw < 0.3 || fw > T - 0.3) return false;
		int k = key(row, col);
		if (broken.containsKey(k)) return false;
		if (!pathSet.contains(k)) {
			if (pushed(p)) return false;
			breakTile(level, row, col, now, 60);
			p.displayClientMessage(Component.literal("A false echo - the tile shatters!").withStyle(ChatFormatting.RED), true);
			strike(level, p, Vec3.atBottomCenterOf(arena(rowF(row), colL(col), 0)), here);
			return true;
		}
		if (tier >= 3 && !crumbleAt.containsKey(k)) crumbleAt.put(k, now + 200);
		return false;
	}

	private void stageCleared(ServerLevel level, List<ServerPlayer> here, long now) {
		revertSeer(level);
		revertSolo(level);
		for (int row = STAGES[stage][0]; row <= STAGES[stage][1]; row++) for (int col = 0; col < COLS; col++) tile(level, row, col, tileBlock(row, col));
		for (int[] t : path) set(level, rowF(t[0]), colL(t[1]), 0, glowState().getBlock());
		broken.clear();
		crumbleAt.clear();
		stage++;
		Vec3 c = checkpoint(stage);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.2f, 0.8f + stage * 0.2f);
		if (stage >= 3) {
			solve(level, DigDecor.v(this, PLINTH_F, 0, 2.2));
			return;
		}
		for (ServerPlayer p : here) com.toast.aurapoints.ability.AuraFx.title(p, Component.literal("Stage " + stage + " / 3 crossed").withStyle(s -> s.withColor(rgb).withBold(true)),
				Component.literal(stage == 2 && here.size() >= 2 ? "the sight passes to another" : "the hall echoes on").withStyle(ChatFormatting.GRAY));
		buildStage(level, stage);
		rollPath(random != null ? random : level.random);
		if (here.size() >= 2) {
			if (stage == 2 || seer == null) pickSeer(level, here, stage == 2);
			else {
				ServerPlayer s = find(level, seer);
				if (s != null) toPerch(s);
			}
		} else if (here.size() == 1) {
			echo(level, here.get(0), now);
		}
	}

	// --- the Seer

	private ServerPlayer find(ServerLevel level, UUID id) {
		if (id == null) return null;
		for (ServerPlayer p : lastHere) if (p.getUUID().equals(id)) return p;
		ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
		return p != null ? p : Rifts.TEST_PLAYERS.get(id);
	}

	private void toPerch(ServerPlayer p) {
		Vec3 to = perch(stage);
		DigDecor.tp(p, (ServerLevel) p.level(), to, perchYaw(), 30f);
	}

	boolean onPerch(ServerPlayer p) {
		Vec3 pos = p.position();
		double l = al(pos);
		return Math.abs(af(pos) - PERCH_F[stage]) <= 4 && l <= -19.5 && l >= -28 && pos.y > Rifts.FLOOR + PERCH_DY - 1;
	}

	/** Picks the Seer (in stage 3 someone other than the first one), sends them up and the old one down. */
	void pickSeer(ServerLevel level, List<ServerPlayer> here, boolean other) {
		revertSeer(level);
		List<ServerPlayer> pool = new ArrayList<>();
		for (ServerPlayer p : here) if (!other || firstSeer == null || !p.getUUID().equals(firstSeer)) pool.add(p);
		if (pool.isEmpty()) pool.addAll(here);
		ServerPlayer old = find(level, seer);
		ServerPlayer pick = pool.get(level.random.nextInt(pool.size()));
		if (old != null && old != pick && onPerch(old)) {
			Vec3 to = checkpoint(stage);
			DigDecor.tp(old, level, to, forwardYaw(), 0f);
		}
		seer = pick.getUUID();
		if (stage < 2) firstSeer = seer;
		toPerch(pick);
		for (ServerPlayer p : here) {
			boolean me = p == pick;
			com.toast.aurapoints.ability.AuraFx.title(p, Component.literal(me ? "You are the Seer" : "You are a Walker").withStyle(s -> s.withColor(rgb).withBold(true)),
					Component.literal(me ? "only you see the path - call it out" : pick.getGameProfile().getName() + " sees the path - listen").withStyle(ChatFormatting.GRAY));
		}
	}

	private void seerTick(ServerLevel level, List<ServerPlayer> here, long now) {
		if (here.size() < 2) {
			if (seer != null) {
				ServerPlayer s = find(level, seer);
				revertSeer(level);
				if (s != null && here.contains(s) && onPerch(s)) {
					Vec3 to = checkpoint(stage);
					DigDecor.tp(s, level, to, forwardYaw(), 0f);
					soloAutoAt = now + 10;
				}
				seer = null;
			}
			return;
		}
		ServerPlayer s = seer == null ? null : find(level, seer);
		if (s == null || !here.contains(s)) {
			pickSeer(level, here, stage == 2);
			s = find(level, seer);
		}
		if (s == null) return;
		boolean visible = onPerch(s) && (tier < 2 || now % 100 < 60);
		if (!visible) {
			if (seerOn) revertSeer(level);
			return;
		}
		if (!seerOn || now >= nextSeerSend) {
			nextSeerSend = now + 20;
			seerOn = true;
			BlockState glow = glowState();
			for (int[] t : path) {
				if (broken.containsKey(key(t[0], t[1]))) continue;
				BlockPos pos = arena(rowF(t[0]), colL(t[1]), 0);
				seerShown.add(pos);
				send(s, pos, glow);
			}
		}
	}

	private void send(ServerPlayer p, BlockPos pos, BlockState state) {
		if (com.toast.aurapoints.shim.Platform.get().isFakePlayer(p) || p.connection == null) return;
		p.connection.send(new ClientboundBlockUpdatePacket(pos, state));
	}

	private void revertSeer(ServerLevel level) {
		ServerPlayer s = find(level, seer);
		if (s != null) for (BlockPos pos : seerShown) send(s, pos, level.getBlockState(pos));
		seerShown.clear();
		seerOn = false;
	}

	// --- alone: the echo from above

	/** The echo: the stage's path glows for p (mirrored on Extreme+) while the camera looks straight down on it. */
	void echo(ServerLevel level, ServerPlayer p, long now) {
		revertSolo(level);
		int show = showTicks();
		BlockState glow = glowState();
		freezeHazards(true);
		for (int[] t : path) {
			int col = mirrored() ? COLS - 1 - t[1] : t[1];
			int f = F0 + t[0] * T, l = L0 + col * T;
			for (int a = 0; a < T; a++) for (int c = 0; c < T; c++) {
				BlockPos pos = arena(f + a, l + c, 0);
				soloShown.add(pos);
				send(p, pos, glow);
			}
		}
		soloViewer = p.getUUID();
		soloUntil = now + show + 12;
		double fa = rowF(STAGES[stage][0]) + 2, fb = rowF(STAGES[stage][1]) - 2;
		RiftCam.shot(rgb).title("The echo", mirrored() ? "mirrored - left is right" : "remember the path").skippable(false)
				.key(0, DigDecor.v(this, fa, 0, 24), DigDecor.v(this, fa + 0.3, 0, 0))
				.key(show, DigDecor.v(this, fb, 0, 24), DigDecor.v(this, fb + 0.3, 0, 0)).send(List.of(p));
		p.playNotifySound(SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.4f, 0.8f);
	}

	private void revertSolo(ServerLevel level) {
		ServerPlayer p = find(level, soloViewer);
		if (p != null) for (BlockPos pos : soloShown) send(p, pos, level.getBlockState(pos));
		soloShown.clear();
		soloViewer = null;
		freezeHazards(false);
	}

	/** The echo's glow again every second: a client that had not loaded those chunks yet (the first time in) missed the first send. */
	private void resendSolo(ServerLevel level) {
		ServerPlayer p = find(level, soloViewer);
		if (p == null) return;
		BlockState glow = glowState();
		for (BlockPos pos : soloShown) send(p, pos, glow);
	}

	/** Knocked about by a mob lately: being pushed onto a tile is not a step. */
	private static boolean pushed(ServerPlayer p) {
		int ts = p.getLastHurtByMobTimestamp();
		return p.hurtTime > 0 || (ts > 0 && p.tickCount - ts < 30);
	}

	private void pad(ServerLevel level, ServerPlayer p, long now) {
		Vec3 pos = p.position();
		if (Math.abs(af(pos) - cpF(stage)) > 1.2 || Math.abs(al(pos) - PAD_L) > 1.2 || Math.abs(pos.y - Rifts.FLOOR - 1) > 0.5) return;
		if (now < padNext || soloViewer != null) {
			if (now % 20 == 0) p.displayClientMessage(Component.literal("The echo is still fading... " + Math.max(1, (padNext - now) / 20) + " s").withStyle(ChatFormatting.DARK_AQUA), true);
			return;
		}
		padNext = now + showTicks() + (tier >= 2 ? 600 : 400);
		echo(level, p, now);
	}

	private void hints(ServerLevel level, List<ServerPlayer> here) {
		int lvl = totalStrikes - (tier == 0 ? 1 : 2) + 1;
		if (lvl > hintShown) {
			hintShown = lvl;
			for (ServerPlayer p : here) p.sendSystemMessage(Component.literal("✦ Hint: the first steps of the path shimmer for everyone.").withStyle(ChatFormatting.AQUA));
		}
		int show = Math.min(path.size(), 1 + 3 * lvl);
		for (int i = 0; i < show; i++) {
			int[] t = path.get(i);
			Vec3 c = DigDecor.v(this, rowF(t[0]), colL(t[1]), 1.1);
			level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 2, 0.6, 0.1, 0.6, 0.01);
		}
	}

	// ---------------------------------------------------------------
	// The strike ladder
	// ---------------------------------------------------------------

	@Override
	void onReact(ServerLevel level, Vec3 at) {
		Vec3 c = DigDecor.v(this, (rowF(STAGES[stage][0]) + rowF(STAGES[stage][1])) / 2.0, 0, 1);
		level.sendParticles(ParticleTypes.SCULK_SOUL, c.x, c.y, c.z, 40, 9, 0.2, 9, 0.02);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1.5f, 0.7f);
	}

	@Override
	void onHazard(ServerLevel level, Vec3 at) {
		// the echoes shift: walkers on this stage are swept back, the path re-rolls, and things wait on the checkpoint
		Vec3 cp = checkpoint(stage);
		for (ServerPlayer p : lastHere) {
			if (p.getUUID().equals(seer)) continue;
			double row = (af(p.position()) - (F0 - 0.5)) / T;
			if (row >= STAGES[stage][0] - 0.2 && row <= STAGES[stage][1] + 0.99) {
				DigDecor.tp(p, level, cp, forwardYaw(), 0f);
			}
			p.displayClientMessage(Component.literal("The echoes shift - a new path!").withStyle(ChatFormatting.RED), true);
		}
		revertSeer(level);
		revertSolo(level);
		crumbleAt.clear();
		rollPath(random != null ? random : level.random);
		punish(level, cp, 2 + tier / 2);
		if (lastHere.size() == 1) {
			soloAutoAt = lastNow + 40;
			freezeHazards(true);
		}
	}

	@Override
	void onReset(ServerLevel level, RandomSource r) {
		revertSeer(level);
		revertSolo(level);
		for (int s = 1; s <= 2; s++) clearStage(level, s);
		broken.clear();
		crumbleAt.clear();
		stage = 0;
		buildStage(level, 0);
		rollPath(r);
		Vec3 cp = checkpoint(0);
		for (ServerPlayer p : lastHere) {
			if (p.getUUID().equals(seer) && lastHere.size() >= 2) toPerch(p);
			else DigDecor.tp(p, level, cp, forwardYaw(), 0f);
		}
		if (lastHere.size() == 1) soloAutoAt = lastNow + 40;
	}

	@Override
	Vec3 plinth() {
		return DigDecor.v(this, PLINTH_F, 0, 2.2);
	}

	@Override
	Vec3 respawn(Vec3 p) {
		return inArena(p) && !solved ? checkpoint(stage) : super.respawn(p);
	}

	@Override
	void release(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		if (p.getUUID().equals(seer)) {
			revertSeer(level);
			seer = null;
		}
		if (p.getUUID().equals(soloViewer)) revertSolo(level);
	}

	// ---------------------------------------------------------------
	// Dev test
	// ---------------------------------------------------------------

	@Override
	void devPlay(MinecraftServer server, ServerLevel level, ServerPlayer fake, Consumer<String> log, Runnable done) {
		List<ServerPlayer> here = List.of(fake);
		long now = level.getGameTime() + 400;
		// alone: the echo plays and lights the path
		soloAutoAt = now;
		tick(level, here, now);
		// the last row of every stage holds exactly one lit tile (many random paths)
		List<int[]> keepPath = new ArrayList<>(path);
		Set<Integer> keepSet = new HashSet<>(pathSet);
		int keepStage = stage;
		boolean oneEnd = true;
		for (int st = 0; st < 3; st++) {
			stage = st;
			for (int k = 0; k < 60; k++) {
				rollPath(level.random);
				if (path.stream().filter(t -> t[0] == STAGES[stage][1]).count() != 1) oneEnd = false;
			}
		}
		stage = keepStage;
		path.clear();
		path.addAll(keepPath);
		pathSet.clear();
		pathSet.addAll(keepSet);
		log.accept("echoes: last row has one tile in 180 paths " + oneEnd + " (must be true)");
		log.accept("echoes: solo echo shows " + soloShown.size() + " blocks (must be " + path.size() * T * T + ")");
		// a wrong tile: it breaks and strikes
		int r0 = STAGES[0][0], wrong = -1;
		for (int c = 0; c < COLS; c++) if (!pathSet.contains(key(r0, c))) wrong = c;
		stand(fake, rowF(r0), colL(wrong), 1);
		tick(level, here, ++now);
		log.accept("echoes: wrong tile -> strikes " + strikes + " (must be 1), tile gone " + level.getBlockState(arena(rowF(r0), colL(wrong), 0)).isAir() + " (must be true)");
		// falling through: caught at the checkpoint
		stand(fake, rowF(r0), colL(wrong), -6);
		tick(level, here, ++now);
		log.accept("echoes: fall caught at the checkpoint " + (fake.position().distanceTo(checkpoint(0)) < 1) + " (must be true)");
		// walk the three stages along their paths
		for (int s = 0; s < 3; s++) {
			int before = totalStrikes;
			for (int[] t : new ArrayList<>(path)) {
				stand(fake, rowF(t[0]), colL(t[1]), 1);
				tick(level, here, ++now);
			}
			log.accept("echoes: stage " + (s + 1) + " walked, no new strike " + (totalStrikes == before) + " (must be true)");
			int nextF = s < 2 ? rowF(STAGES[s][1] + 1) : PLINTH_F - 4;
			stand(fake, nextF, 0, 1);
			tick(level, here, ++now);
			if (s < 2) log.accept("echoes: on to stage " + (stage + 1) + " (must be " + (s + 2) + ")");
		}
		log.accept("echoes: end reached -> solved " + solved + " (must be true)");
		done.run();
	}

	/** Puts the dev fake at an arena-frame spot, standing. */
	private void stand(ServerPlayer p, double f, double l, double dy) {
		Vec3 v = DigDecor.v(this, f, l, dy);
		p.moveTo(v.x, v.y, v.z);
		p.setOnGround(true);
	}
}
