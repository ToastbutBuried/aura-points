package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraFx;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Choir of Bells (5.49.0, the Rune Sequence's successor). An open rotunda over the void with four bell towers in its
 * quadrants (Blue, Green, Gold, Red) and the Conductor's Stone in the middle. The choir sings a song of 5-11 notes,
 * some of them chords (two bells at once, to be rung within a second of each other), and the players ring it back.
 * <p>
 * Each player only hears (and sees) the notes of the bells handed to them for that playback, so the group has to put
 * the song together by talking; alone you hear every bell, the song is one note shorter and sung twice. Hard and up
 * sing it backwards (ring it forwards), Extreme and up slip in false notes marked with soul fire that must be skipped.
 * 2 rounds on Easy, 3 above, each longer. A wrong bell is a strike; strike 2 brings Blindness pulses and a ghost that
 * adds its own note to the song until it is killed.
 */
final class TrialBells extends Trials.Trial {

	static final String[] NAMES = {"Blue", "Green", "Gold", "Red"};
	static final int[] RGB = {0x4A8CFF, 0x5BD35A, 0xF2C43A, 0xE04848};
	private static final Block[] CONCRETE = {Blocks.BLUE_CONCRETE, Blocks.LIME_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.RED_CONCRETE};
	private static final Block[] GLASS = {Blocks.BLUE_STAINED_GLASS, Blocks.LIME_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS, Blocks.RED_STAINED_GLASS};
	private static final Block[] BANNER = {Blocks.BLUE_WALL_BANNER, Blocks.LIME_WALL_BANNER, Blocks.YELLOW_WALL_BANNER, Blocks.RED_WALL_BANNER};
	/** A rising four-note chord: every bell sounds different. */
	private static final float[] PITCH = {0.63f, 0.79f, 0.94f, 1.26f};
	/** The bell towers (arena f, l): the four quadrants of the rotunda. */
	static final int[][] TOWER = {{12, -12}, {12, 12}, {-12, 12}, {-12, -12}};
	private static final int R = 20, CHORD_TICKS = 22;

	/** One note: bell a, a second bell b for a chord (-1 = none); false notes must be skipped, the ghost's is its own. */
	record Note(int a, int b, boolean fake, boolean ghost) {
		int mask() {
			return (1 << a) | (b >= 0 ? 1 << b : 0);
		}
	}

	/** The song in ringing order (false notes included: they are sung, never rung). */
	final List<Note> song = new ArrayList<>();
	int round, step;
	final int rounds;
	/** Bells of the current chord rung so far, and when the first of them was rung. */
	private int pending;
	private long pendingAt;
	/** Playback: start tick (-1 = not playing), the last note index sung, the pass (solo hears it twice). */
	long playAt = -1;
	private int played = -1, pass;
	boolean listening;
	private int replays;
	/** Who hears which bells this playback (bit mask per player). */
	final Map<UUID, Integer> hears = new HashMap<>();
	private Mob ghost;
	private long blindUntil, nextBlind, idleSince;
	private List<ServerPlayer> lastHere = List.of();
	/** The Hard+ rules (backwards, false notes) were announced. */
	private boolean told;
	/** Dev test: notes 2 ticks apart. */
	boolean dev;

	TrialBells(Rifts.Run run, int index) {
		super(run, index, Trials.Kind.BELLS);
		this.rounds = tier == 0 ? 2 : 3;
	}

	@Override
	String name() {
		return "Choir of Bells";
	}

	@Override
	String hint() {
		return "The choir sings - but each of you hears only some of its bells. Put the song together and ring it back (chords: two bells at once). The Conductor's Stone sings again.";
	}

	// ---------------------------------------------------------------
	// Geometry
	// ---------------------------------------------------------------

	BlockPos bellPos(int i) {
		return arena(TOWER[i][0], TOWER[i][1], 4);
	}

	private BlockPos stone() {
		return arena(0, 0, 2);
	}

	@Override
	Vec3 plinth() {
		return Vec3.atBottomCenterOf(arena(0, 0, 3)).add(0, 0.2, 0);
	}

	private Direction bellFacing() {
		return TrialDecor.dir(this, 1, 0);
	}

	@Override
	void build(ServerLevel level, RandomSource r) {
		// the rotunda floor: spokes to the towers, two rings, a rim
		for (int f = -R; f <= R; f++) {
			for (int l = -R; l <= R; l++) {
				double d = Math.sqrt(f * f + l * l);
				if (d > R + 0.5) continue;
				Block b = d >= R - 0.5 ? pal.edge() : d < 4 || (d >= 9 && d < 10.2) || (Math.abs(Math.abs(f) - Math.abs(l)) <= 0 && d > 4) ? pal.accent() : pal.floor();
				set(level, f, l, 0, b);
			}
		}
		// the nave from the way back to the rotunda
		for (int f = -36; f <= -R + 1; f++) {
			for (int l = -3; l <= 3; l++) set(level, f, l, 0, Math.abs(l) == 3 ? pal.accent() : pal.floor());
		}
		for (int f = -31; f <= -22; f += 4) {
			for (int s : new int[]{-4, 4}) {
				for (int y = 0; y <= 7; y++) set(level, f, s, y, y == 0 || y == 7 ? pal.accent() : pal.edge());
				set(level, f, s, 8, pal.light());
			}
			for (int l = -3; l <= 3; l++) set(level, f, l, 7, pal.edge());
			set(level, f, -3, 6, pal.edge());
			set(level, f, 3, 6, pal.edge());
			TrialDecor.lantern(level, this, f, 0, 7, 1, false);
		}
		// the colonnade: 16 columns on the rim, a broken ring beam over them
		for (int k = 0; k < 16; k++) {
			double a = Math.PI * 2 * k / 16;
			int f = (int) Math.round(Math.cos(a) * (R - 1)), l = (int) Math.round(Math.sin(a) * (R - 1));
			if (f < -R + 4 && Math.abs(l) < 5) continue; // the nave's way in
			if (nearTower(f, l, 4)) continue;
			for (int y = 1; y <= 9; y++) set(level, f, l, y, y == 1 || y == 9 ? pal.accent() : pal.edge());
			if (k % 2 == 0) set(level, f, l, 10, pal.light());
		}
		for (int k = 0; k < 160; k++) {
			double a = Math.PI * 2 * k / 160;
			int f = (int) Math.round(Math.cos(a) * (R - 1)), l = (int) Math.round(Math.sin(a) * (R - 1));
			if (nearTower(f, l, 4) || (f < -R + 4 && Math.abs(l) < 5)) continue;
			int h = TrialDecor.hash(f, l, 3);
			if (h < 22) {
				// a fallen piece of the beam on the floor inside
				if (h < 6) set(level, (int) (f * 0.85), (int) (l * 0.85), 1, pal.edge());
				continue;
			}
			if (!level.getBlockState(arena(f, l, 10)).isAir()) continue;
			set(level, f, l, 10, pal.edge());
			if (h % 11 == 0 && level.getBlockState(arena(f, l, 9)).isAir()) TrialDecor.lantern(level, this, f, l, 10, 1, false);
		}
		// the rim parapet (gaps at the nave and the towers)
		for (int f = -R; f <= R; f++) {
			for (int l = -R; l <= R; l++) {
				double d = Math.sqrt(f * f + l * l);
				if (d < R - 0.5 || d > R + 0.5) continue;
				if ((f < 0 && Math.abs(l) <= 3) || nearTower(f, l, 4)) continue;
				if (level.getBlockState(arena(f, l, 1)).isAir()) set(level, f, l, 1, pal.edge());
			}
		}
		for (int i = 0; i < 4; i++) tower(level, i);
		// the Conductor's Stone on its dais
		for (int f = -3; f <= 3; f++)
			for (int l = -3; l <= 3; l++) if (f * f + l * l <= 8) set(level, f, l, 1, f * f + l * l <= 2 ? pal.edge() : pal.accent());
		set(level, 0, 0, 2, Blocks.LODESTONE);
		label(level, Vec3.atBottomCenterOf(arena(0, 0, 4)).add(0, 0.6, 0), Component.literal("Conductor's Stone").withStyle(ChatFormatting.GRAY), 0.7f);
		// four low braziers around the dais
		for (int[] q : new int[][]{{5, 0}, {-5, 0}, {0, 5}, {0, -5}}) {
			set(level, q[0], q[1], 1, pal.edge());
			set(level, q[0], q[1], 2, pal.light());
		}
		// the rose window, floating behind the choir
		roseWindow(level, 25, 13, 9);
		// hidden light over the open floor
		for (int f = -R + 2; f <= R - 2; f += 6)
			for (int l = -R + 2; l <= R - 2; l += 6)
				if (f * f + l * l < (R - 2) * (R - 2) && level.getBlockState(arena(f, l, 1)).isAir())
					set(level, f, l, 1, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 11));
		// the rock under it all, and debris drifting around
		TrialDecor.underside(level, this, -R - 1, R + 1, -R - 1, R + 1, (f, l) -> {
			double d = Math.sqrt(f * f + l * l);
			return (int) (3 + Math.max(0, R + 0.5 - d) * 0.6 + TrialDecor.hash(f, l, 0) % 3);
		});
		TrialDecor.underside(level, this, -36, -R, -3, 3, (f, l) -> 2 + TrialDecor.hash(f, l, 1) % 3);
		TrialDecor.debris(level, this, r, 14, 27, 37, -14, 18, (f, l) -> (f < -16 && Math.abs(l) <= 9) || (f > 17 && Math.abs(l) <= 14));
		newSong(r);
	}

	private boolean nearTower(int f, int l, int d) {
		for (int[] t : TOWER) if (Math.abs(f - t[0]) <= d && Math.abs(l - t[1]) <= d) return true;
		return false;
	}

	/** A bell tower: coloured dais, four columns with arches, a beam the bell hangs from, a glass lantern roof, a spire. */
	private void tower(ServerLevel level, int i) {
		int tf = TOWER[i][0], tl = TOWER[i][1];
		for (int df = -3; df <= 3; df++) {
			for (int dl = -3; dl <= 3; dl++) {
				boolean rim = Math.abs(df) == 3 || Math.abs(dl) == 3;
				set(level, tf + df, tl + dl, 0, rim ? pal.edge() : Math.abs(df) <= 1 && Math.abs(dl) <= 1 ? CONCRETE[i] : pal.accent());
				// the arches between the columns
				if (rim) {
					// an arch in each side: 3 wide up to dy 4, shoulders at dy 5, solid from dy 6
					set(level, tf + df, tl + dl, 7, pal.edge());
					set(level, tf + df, tl + dl, 6, pal.edge());
					if (Math.abs(df) >= 2 && Math.abs(dl) >= 2) set(level, tf + df, tl + dl, 5, pal.edge());
				} else {
					set(level, tf + df, tl + dl, 7, Math.abs(df) <= 1 && Math.abs(dl) <= 1 ? pal.light() : pal.edge());
				}
			}
		}
		for (int sf : new int[]{-3, 3})
			for (int sl : new int[]{-3, 3}) {
				for (int y = 1; y <= 6; y++) set(level, tf + sf, tl + sl, y, y == 1 ? pal.accent() : pal.edge());
				// banners on the outer faces
				int of = Integer.signum(tf) == Integer.signum(sf) ? sf / 3 : 0, ol = Integer.signum(tl) == Integer.signum(sl) ? sl / 3 : 0;
				if (of != 0) TrialDecor.banner(level, this, tf + sf, tl + sl, 5, of, 0, BANNER[i]);
				if (ol != 0) TrialDecor.banner(level, this, tf + sf, tl + sl, 5, 0, ol, BANNER[i]);
			}
		// the beam and the bell
		for (int dl = -3; dl <= 3; dl++) set(level, tf, tl + dl, 5, pal.edge());
		set(level, tf, tl, 4, Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.CEILING).setValue(BellBlock.FACING, bellFacing()));
		// the roof: a stained glass lantern in the bell's colour, then a spire
		for (int df = -2; df <= 2; df++)
			for (int dl = -2; dl <= 2; dl++)
				set(level, tf + df, tl + dl, 8, Math.abs(df) == 2 || Math.abs(dl) == 2 ? pal.edge() : GLASS[i]);
		for (int df = -1; df <= 1; df++) for (int dl = -1; dl <= 1; dl++) set(level, tf + df, tl + dl, 9, pal.accent());
		set(level, tf, tl, 10, pal.light());
		for (int y = 11; y <= 13; y++) set(level, tf, tl, y, pal.edge());
		set(level, tf, tl, 14, GLASS[i]);
		label(level, Vec3.atBottomCenterOf(arena(tf, tl, 15)).add(0, 0.3, 0), Component.literal(NAMES[i] + " Bell").withStyle(s -> s.withColor(RGB[i]).withBold(true)), 1.6f);
	}

	/** A round window standing upright across the arena's forward axis at f, centred at dy, radius rad. */
	private void roseWindow(ServerLevel level, int f, int cy, int rad) {
		for (int l = -rad; l <= rad; l++) {
			for (int y = cy - rad; y <= cy + rad; y++) {
				int dy = y - cy;
				double d = Math.sqrt(l * l + dy * dy);
				if (d > rad + 0.4) continue;
				boolean spoke = l == 0 || dy == 0 || Math.abs(l) == Math.abs(dy);
				Block b = d < 1.5 ? pal.light() : d > rad - 1.1 || (spoke && d < rad - 1) || (d > rad / 2.0 - 0.5 && d < rad / 2.0 + 0.5) ? pal.edge() : pal.gate();
				set(level, f, l, y, b);
			}
		}
		// its broken plinth
		for (int df = -2; df <= 2; df++)
			for (int l = -6; l <= 6; l++) {
				int top = cy - rad - 1 + (TrialDecor.hash(df, l, 9) % 4 == 0 ? -1 : 0);
				for (int y = top - 3; y <= top; y++) set(level, f + df, l, y, y == top ? pal.accent() : pal.base());
			}
	}

	// ---------------------------------------------------------------
	// The song
	// ---------------------------------------------------------------

	private int length(boolean solo) {
		int n = Math.min(11, 5 + tier / 2 + 2 * round);
		return solo ? Math.max(4, n - 1) : n;
	}

	/** A new song for the current round. */
	void newSong(RandomSource r) {
		song.clear();
		boolean solo = run.players.size() <= 1;
		int n = length(solo);
		int chords = Math.min(n / 3, (tier >= 1 ? 1 : 0) + round);
		List<Integer> idx = new ArrayList<>();
		for (int k = 1; k < n; k++) idx.add(k);
		Collections.shuffle(idx, new java.util.Random(r.nextLong()));
		java.util.Set<Integer> chordAt = new java.util.HashSet<>(idx.subList(0, Math.min(chords, idx.size())));
		int last = -1;
		for (int k = 0; k < n; k++) {
			int a;
			do a = r.nextInt(4); while (a == last);
			int b = -1;
			if (chordAt.contains(k)) {
				do b = r.nextInt(4); while (b == a);
			}
			song.add(new Note(a, b, false, false));
			last = b < 0 ? a : -1;
		}
		int fakes = tier >= 3 ? 1 + (tier - 3) + round / 2 : 0;
		for (int k = 0; k < fakes; k++) song.add(1 + r.nextInt(song.size()), new Note(r.nextInt(4), -1, true, false));
		step = 0;
		pending = 0;
		listening = false;
		replays = tier <= 1 ? -1 : tier == 2 ? 2 : 1;
	}

	/** The notes to ring, in order (no false notes). */
	List<Note> expected() {
		List<Note> out = new ArrayList<>();
		for (Note n : song) if (!n.fake) out.add(n);
		return out;
	}

	/** The playback order: backwards on Hard and up. */
	private List<Note> playback() {
		List<Note> out = new ArrayList<>(song);
		if (tier >= 2) Collections.reverse(out);
		return out;
	}

	private int gap() {
		return dev ? 2 : tier >= 4 ? 13 : 16;
	}

	/** Hands the bells out for a playback: alone you hear all four, else they are split (and rotate every round). */
	private void assign(List<ServerPlayer> here) {
		hears.clear();
		List<ServerPlayer> ps = new ArrayList<>(here);
		ps.sort(java.util.Comparator.comparing(ServerPlayer::getUUID));
		int n = ps.size();
		for (int k = 0; k < n; k++) {
			int mask = 0;
			if (n == 1) mask = 15;
			else for (int b = 0; b < 4; b++) if ((b + round) % n == k) mask |= 1 << b;
			if (mask == 0) mask = 1 << ((k + round) % 4);
			hears.put(ps.get(k).getUUID(), mask);
		}
	}

	private void startPlayback(ServerLevel level, List<ServerPlayer> here, long now) {
		assign(here);
		played = -1;
		pass = 0;
		for (ServerPlayer p : here) {
			int mask = hears.getOrDefault(p.getUUID(), 0);
			MutableComponent m = Component.literal("You hear ").withStyle(ChatFormatting.GRAY);
			boolean first = true;
			for (int b = 0; b < 4; b++) {
				if ((mask & (1 << b)) == 0) continue;
				if (!first) m.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
				first = false;
				int bb = b;
				m.append(Component.literal(NAMES[b]).withStyle(s -> s.withColor(RGB[bb]).withBold(true)));
				// a ring of the bell's colour only this player sees
				Vec3 c = Vec3.atCenterOf(bellPos(b));
				for (int k = 0; k < 16; k++) {
					double a = Math.PI * 2 * k / 16;
					level.sendParticles(p, AuraFx.dust(RGB[b], 1.4f), true, c.x + Math.cos(a) * 1.6, c.y - 3.2, c.z + Math.sin(a) * 1.6, 1, 0, 0, 0, 0);
				}
			}
			p.displayClientMessage(m, true);
			if (!told) {
				if (tier >= 2) p.sendSystemMessage(Component.literal("The choir sings backwards: ring its song from the last note you heard to the first.").withStyle(ChatFormatting.LIGHT_PURPLE));
				if (tier >= 3) p.sendSystemMessage(Component.literal("Notes wreathed in soul fire are false: never ring them.").withStyle(ChatFormatting.DARK_AQUA));
			}
		}
		told = true;
	}

	/** Sings one note to everyone who hears its bells. */
	private void sing(ServerLevel level, List<ServerPlayer> here, Note n) {
		for (ServerPlayer p : here) {
			int mask = hears.getOrDefault(p.getUUID(), 0);
			for (int b : new int[]{n.a, n.b}) {
				if (b < 0 || (mask & (1 << b)) == 0) continue;
				ring(level, p, b, n.fake, n.ghost);
			}
		}
	}

	/** The bell {@code b} swings and sounds for {@code p} alone. */
	private void ring(ServerLevel level, ServerPlayer p, int b, boolean fake, boolean ghostNote) {
		BlockPos pos = bellPos(b);
		Vec3 c = Vec3.atCenterOf(pos);
		if (!TrialDecor.fake(p)) {
			p.connection.send(new ClientboundBlockEventPacket(pos, Blocks.BELL, 1, bellFacing().get3DDataValue()));
			long seed = level.random.nextLong();
			p.connection.send(new ClientboundSoundPacket(holder(SoundEvents.BELL_BLOCK), SoundSource.BLOCKS, c.x, c.y, c.z, 3f, PITCH[b], seed));
			p.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_BELL, SoundSource.BLOCKS, c.x, c.y, c.z, 3f, PITCH[b] * 1.5f, seed));
			if (fake) p.connection.send(new ClientboundSoundPacket(holder(SoundEvents.SOUL_ESCAPE.value()), SoundSource.BLOCKS, c.x, c.y, c.z, 3f, 0.8f, seed));
			if (ghostNote) p.connection.send(new ClientboundSoundPacket(holder(SoundEvents.VEX_CHARGE), SoundSource.HOSTILE, c.x, c.y, c.z, 3f, 0.6f, seed));
		}
		level.sendParticles(p, AuraFx.dust(RGB[b], 2.2f), true, c.x, c.y - 0.6, c.z, 18, 0.7, 0.6, 0.7, 0);
		level.sendParticles(p, ParticleTypes.NOTE, true, c.x, c.y + 0.9, c.z, 3, 0.5, 0.3, 0.5, PITCH[b] - 0.5);
		if (fake) level.sendParticles(p, ParticleTypes.SOUL_FIRE_FLAME, true, c.x, c.y - 1, c.z, 30, 0.8, 1.2, 0.8, 0.02);
		if (ghostNote) level.sendParticles(p, ParticleTypes.SCULK_SOUL, true, c.x, c.y - 0.5, c.z, 10, 0.6, 0.6, 0.6, 0.02);
		heardDev++;
	}

	/** Dev test: bell notes sung to anyone. */
	int heardDev;

	private static Holder<SoundEvent> holder(SoundEvent s) {
		return BuiltInRegistries.SOUND_EVENT.wrapAsHolder(s);
	}

	// ---------------------------------------------------------------
	// Ticking
	// ---------------------------------------------------------------

	@Override
	void begin(ServerLevel level, List<ServerPlayer> here) {
		playAt = level.getGameTime() + (dev ? 1 : introLength() + 20);
	}

	private int introLength() {
		return 150;
	}

	@Override
	void tick(ServerLevel level, List<ServerPlayer> here, long now) {
		lastHere = here;
		if (solved) return;
		// the playback
		if (playAt >= 0 && now >= playAt) {
			if (hears.isEmpty()) startPlayback(level, here, now);
			List<Note> order = playback();
			int at = (int) ((now - playAt) / gap());
			while (played < at && played < order.size() - 1) {
				played++;
				sing(level, here, order.get(played));
			}
			if (at >= order.size() + 1) {
				boolean solo = here.size() <= 1;
				if (solo && pass == 0) {
					pass = 1;
					played = -1;
					playAt = now + (dev ? 2 : 30);
					actionBar(here, "Once more...", ChatFormatting.GRAY);
				} else {
					playAt = -1;
					played = -1;
					pass = 0;
					listening = true;
					idleSince = now;
					actionBar(here, "Your turn: " + expected().size() + " notes", ChatFormatting.AQUA);
				}
			}
		}
		// a chord falls apart if its second bell comes too late
		if (pending != 0 && now - pendingAt > CHORD_TICKS) {
			wrong(level, null, -1, now, "The chord fell apart");
		}
		if (now % 10 == 0) {
			// the ghost: its note stays in the song while it lives
			if (ghost != null && (!ghost.isAlive() || ghost.isRemoved())) {
				ghost = null;
				boolean had = song.removeIf(Note::ghost);
				if (had) {
					actionBar(here, "The ghost's note fades from the song", ChatFormatting.DARK_AQUA);
					if (listening && step >= expected().size()) roundDone(level, here, now);
				}
			}
			if (now < blindUntil && now >= nextBlind) {
				nextBlind = now + 70;
				for (ServerPlayer p : here) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 35, 0, false, false));
			}
		}
		if (now % 20 == 0) {
			for (int b = 0; b < 4; b++) {
				Vec3 c = Vec3.atCenterOf(bellPos(b));
				level.sendParticles(AuraFx.dust(RGB[b], 1.0f), c.x, c.y - 0.8, c.z, 2, 0.4, 0.3, 0.4, 0);
			}
			hints(level, here, now);
		}
	}

	/** 0 none, 1 progress on the action bar, 2 also the next bell sparkles when you are stuck. */
	int hintLevel() {
		if (!hintsAllowed()) return 0;
		return 1 + totalStrikes - (tier == 0 ? 1 : 2);
	}

	private void hints(ServerLevel level, List<ServerPlayer> here, long now) {
		int h = hintLevel();
		if (h <= 0 || !listening) return;
		List<Note> exp = expected();
		if (step >= exp.size()) return;
		Note n = exp.get(step);
		actionBar(here, "Note " + (step + 1) + " of " + exp.size() + (n.b >= 0 ? " - a chord" : ""), ChatFormatting.AQUA);
		if (h >= 2 && now - idleSince > 200 && now % 40 == 0) {
			for (int b : new int[]{n.a, n.b}) {
				if (b < 0) continue;
				Vec3 c = Vec3.atCenterOf(bellPos(b));
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y - 1, c.z, 12, 0.6, 0.8, 0.6, 0);
			}
		}
	}

	// ---------------------------------------------------------------
	// Input
	// ---------------------------------------------------------------

	int bellAt(BlockPos pos) {
		for (int i = 0; i < 4; i++) if (pos.equals(bellPos(i)) || pos.equals(bellPos(i).above())) return i;
		return -1;
	}

	@Override
	boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
		long now = level.getGameTime();
		if (pos.equals(stone()) || pos.equals(stone().below())) {
			if (solved) return true;
			if (!listening) {
				p.displayClientMessage(Component.literal("The choir is still singing").withStyle(ChatFormatting.GRAY), true);
			} else if (replays == 0) {
				p.displayClientMessage(Component.literal("The stone is silent - ring what you heard").withStyle(ChatFormatting.GRAY), true);
			} else {
				if (replays > 0) replays--;
				listening = false;
				step = 0;
				pending = 0;
				playAt = now + 20;
				played = -1;
				pass = 0;
				hears.clear();
				level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.LODESTONE_COMPASS_LOCK, SoundSource.BLOCKS, 1f, 0.7f);
				actionBar(lastHere, replays > 0 ? "The choir sings again (" + replays + " left)" : "The choir sings again", ChatFormatting.GRAY);
			}
			return true;
		}
		int b = bellAt(pos);
		if (b < 0) return false;
		BlockPos bp = bellPos(b);
		// a rung bell is heard by everyone
		level.blockEvent(bp, Blocks.BELL, 1, bellFacing().get3DDataValue());
		level.playSound(null, bp, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2f, PITCH[b]);
		level.playSound(null, bp, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 1.5f, PITCH[b] * 1.5f);
		Vec3 c = Vec3.atCenterOf(bp);
		level.sendParticles(AuraFx.dust(RGB[b], 1.8f), c.x, c.y - 0.6, c.z, 10, 0.6, 0.5, 0.6, 0);
		if (solved) return true;
		if (playAt >= 0) {
			p.displayClientMessage(Component.literal("Listen first").withStyle(ChatFormatting.GRAY), true);
			return true;
		}
		if (!listening) return true;
		List<Note> exp = expected();
		if (step >= exp.size()) return true;
		Note n = exp.get(step);
		int bit = 1 << b;
		if ((n.mask() & bit) == 0) {
			wrong(level, p, b, now, "Wrong bell");
			return true;
		}
		if ((pending & bit) != 0) return true;
		if (pending == 0) pendingAt = now;
		pending |= bit;
		idleSince = now;
		if (pending == n.mask()) {
			pending = 0;
			step++;
			level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.2f, 1.2f + step * 0.05f);
			if (step >= exp.size()) roundDone(level, lastHere, now);
		}
		return true;
	}

	private void roundDone(ServerLevel level, List<ServerPlayer> here, long now) {
		listening = false;
		round++;
		Vec3 s = plinth();
		level.playSound(null, s.x, s.y, s.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.2f, 1.2f + round * 0.1f);
		for (int b = 0; b < 4; b++) {
			Vec3 c = Vec3.atCenterOf(bellPos(b));
			level.blockEvent(bellPos(b), Blocks.BELL, 1, bellFacing().get3DDataValue());
			AuraFx.line(level, c, s, AuraFx.dust(RGB[b], 1.2f), 24);
		}
		if (round >= rounds) {
			if (ghost != null) ghost.discard();
			solve(level, s.add(0, 1.0, 0));
			return;
		}
		newSong(random != null ? random : level.random);
		playAt = now + (dev ? 2 : 60);
		played = -1;
		pass = 0;
		hears.clear();
		for (ServerPlayer p : here) AuraFx.title(p, Component.literal("Round " + (round + 1) + " of " + rounds).withStyle(s2 -> s2.withColor(rgb).withBold(true)),
				Component.literal("the choir grows: " + expected().size() + " notes").withStyle(ChatFormatting.GRAY));
	}

	private void wrong(ServerLevel level, ServerPlayer p, int b, long now, String why) {
		pending = 0;
		step = 0;
		listening = false;
		Vec3 at = b >= 0 ? Vec3.atBottomCenterOf(arena(TOWER[b][0], TOWER[b][1], 1)) : plinth();
		level.playSound(null, at.x, at.y, at.z, SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.BLOCKS, 2f, 0.5f);
		actionBar(lastHere, why + " - the choir starts over", ChatFormatting.RED);
		strike(level, p, at, lastHere);
		if (!solved && playAt < 0) {
			playAt = now + (dev ? 2 : 70);
			played = -1;
			pass = 0;
			hears.clear();
		}
	}

	// ---------------------------------------------------------------
	// The strike ladder
	// ---------------------------------------------------------------

	@Override
	void onReact(ServerLevel level, Vec3 at) {
		// every bell clangs out of tune
		for (int b = 0; b < 4; b++) {
			BlockPos bp = bellPos(b);
			level.blockEvent(bp, Blocks.BELL, 1, bellFacing().get3DDataValue());
			level.playSound(null, bp, SoundEvents.BELL_RESONATE, SoundSource.BLOCKS, 1.5f, 0.5f + b * 0.07f);
		}
	}

	@Override
	void onHazard(ServerLevel level, Vec3 at) {
		long now = level.getGameTime();
		blindUntil = now + 320;
		nextBlind = now;
		if (ghost == null || !ghost.isAlive()) {
			Vec3 c = Vec3.atBottomCenterOf(arena(0, 0, 6));
			ghost = Rifts.spawnMob(level, run, EntityType.VEX, c, 3);
			if (ghost != null) {
				run.mobs.add(ghost);
				if (ghost instanceof net.minecraft.world.entity.monster.Vex v) v.setLimitedLife(20 * 90);
				ghost.setCustomName(Component.literal("Choir Ghost").withStyle(ChatFormatting.DARK_AQUA));
				RandomSource r = random != null ? random : level.random;
				song.removeIf(Note::ghost);
				song.add(new Note(r.nextInt(4), -1, false, true));
				actionBar(lastHere, "A ghost joins the choir - its note stays in the song until it falls", ChatFormatting.DARK_AQUA);
			}
		}
		if (tier >= 2) punish(level, at, tier / 2);
	}

	@Override
	void onReset(ServerLevel level, RandomSource r) {
		if (ghost != null) ghost.discard();
		ghost = null;
		blindUntil = 0;
		round = 0;
		newSong(r);
		playAt = level.getGameTime() + (dev ? 2 : 90);
		played = -1;
		pass = 0;
		hears.clear();
	}

	// ---------------------------------------------------------------
	// The intro
	// ---------------------------------------------------------------

	@Override
	RiftCam intro() {
		Vec3 mid = Vec3.atCenterOf(arena(0, 0, 3));
		Vec3 bell = Vec3.atCenterOf(bellPos(1));
		return RiftCam.shot(rgb).title(name(), run.difficulty.label).skippable(true)
				.key(0, Vec3.atCenterOf(arena(-40, 0, 16)), mid)
				.key(45, Vec3.atCenterOf(arena(-6, 0, 40)), Vec3.atCenterOf(arena(0, 0, 0)))
				.key(95, Vec3.atCenterOf(arena(5, 6, 3)), bell)
				.key(136, Vec3.atCenterOf(arena(-15, 0, 5)), Vec3.atCenterOf(arena(6, 0, 4)));
	}

	// ---------------------------------------------------------------
	// Dev test
	// ---------------------------------------------------------------

	@Override
	void devPlay(net.minecraft.server.MinecraftServer server, ServerLevel level, ServerPlayer fake, java.util.function.Consumer<String> log, Runnable done) {
		dev = true;
		heardDev = 0;
		playAt = level.getGameTime();
		played = -1;
		pass = 0;
		hears.clear();
		tick(level, List.of(fake), level.getGameTime());
		log.accept("bells: solo hears all bells " + (hears.getOrDefault(fake.getUUID(), 0) == 15) + " (must be true), song " + expected().size() + " notes, rounds " + rounds);
		waitListening(server, level, fake, 0, () -> {
			log.accept("bells: playback heard by the soloist " + (heardDev > 0) + " (must be true), listening " + listening + " (must be true)");
			// a wrong bell
			Note n = expected().get(0);
			int wrongBell = 0;
			while ((n.mask() & (1 << wrongBell)) != 0) wrongBell++;
			use(level, fake, bellPos(wrongBell));
			log.accept("bells: wrong bell -> strikes " + strikes + " (must be 1), listening " + listening + " (must be false)");
			waitListening(server, level, fake, 0, () -> {
				// a second mistake: the ghost adds its note; killing it takes the note back
				int before = expected().size();
				Note n2 = expected().get(0);
				int wb = 0;
				while ((n2.mask() & (1 << wb)) != 0) wb++;
				use(level, fake, bellPos(wb));
				log.accept("bells: second wrong bell -> strikes " + strikes + " (must be 2), ghost " + (ghost != null) + " (must be true), notes " + expected().size() + " (must be " + (before + 1) + ")");
				if (ghost != null) ghost.discard();
				AuraFx.later(server, 12, () -> {
					tick(level, List.of(fake), (level.getGameTime() / 10) * 10 + 10);
					log.accept("bells: ghost gone -> notes " + expected().size() + " (must be " + before + ")");
					playRounds(server, level, fake, log, done, 0);
				});
			});
		});
	}

	private void playRounds(net.minecraft.server.MinecraftServer server, ServerLevel level, ServerPlayer fake, java.util.function.Consumer<String> log, Runnable done, int guard) {
		waitListening(server, level, fake, 0, () -> {
			int r0 = round;
			heardDev = 0;
			for (Note n : expected()) {
				use(level, fake, bellPos(n.a));
				if (n.b >= 0) use(level, fake, bellPos(n.b));
			}
			log.accept("bells: rang round " + (r0 + 1) + " -> round " + round + " (must be " + (r0 + 1) + "), strikes " + strikes + " (must be 2)");
			if (solved || guard > 5) {
				dev = false;
				done.run();
			} else playRounds(server, level, fake, log, done, guard + 1);
		});
	}

	/** Waits (ticking the trial itself as well) until the choir hands over, then runs {@code then}. */
	private void waitListening(net.minecraft.server.MinecraftServer server, ServerLevel level, ServerPlayer fake, int tries, Runnable then) {
		if (listening || solved || tries > 80) {
			then.run();
			return;
		}
		AuraFx.later(server, 4, () -> {
			tick(level, List.of(fake), level.getGameTime());
			waitListening(server, level, fake, tries + 1, then);
		});
	}
}
