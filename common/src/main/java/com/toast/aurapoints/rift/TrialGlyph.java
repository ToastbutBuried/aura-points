package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraFx;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Glyph Vault (5.49.0): an asymmetric "keep talking" puzzle. One building, two sealed rooms that can't see each other
 * (a tower between them) but are close enough to shout: the Vault, where an Operator turns 4-5 rune pillars through
 * their glyphs and sees the signs (moon lamps, the brazier's flame, the Seal over the door), and the Library, where
 * the Readers find each pillar's rules on wall tablets and in the Codex on a lectern. The first rule that holds decides
 * a pillar's glyph. The Operator pulls the lever to submit; a wrong answer is a strike.
 * <p>
 * Tiers: Easy 4 pillars / 6 glyphs / 2 rules each, Medium adds the Seal and all 8 glyphs, Hard 5 pillars, 3 rules and
 * rules that name other pillars (some only in the Codex), Extreme rules with two conditions and the tablets rewrite
 * themselves after every strike. Alone, a swap lever in each room carries you to the other one (the clock runs on).
 */
final class TrialGlyph extends Trials.Trial {

	static final String[] GLYPH = {"☀", "☾", "✦", "◉", "⚡", "❄", "☠", "♛"};
	static final String[] GNAME = {"Sun", "Moon", "Star", "Eye", "Bolt", "Frost", "Skull", "Crown"};
	private static final String[] ROMAN = {"I", "II", "III", "IV", "V"};
	private static final String[] WORD = {"zero", "one", "two", "three"};
	private static final String[] FLAME = {"the brazier burns red", "the brazier burns blue", "the brazier is cold"};
	private static final Block[] BANNER = {Blocks.BLUE_WALL_BANNER, Blocks.LIME_WALL_BANNER, Blocks.YELLOW_WALL_BANNER, Blocks.RED_WALL_BANNER};
	private static final Block[] CARPET = {Blocks.BLUE_CARPET, Blocks.LIME_CARPET, Blocks.YELLOW_CARPET, Blocks.RED_CARPET};

	/** The vault's and the library's middles (arena l); both rooms span f -16..14 (walls included). */
	static final int VAULT = 20, LIB = -20, BACK = -16, FRONT = 14;
	private static final int[] MOON_L = {16, 20, 24};
	/** Tablet niches in the library (f, l of the niche's middle column). */
	private static final int[][] SPOTS = {{-9, -29}, {-1, -29}, {7, -29}, {-9, -11}, {-1, -11}, {7, -11}};

	enum C {MOONS_MIN, MOONS_EQ, FLAME, SEAL, PILLAR}

	record Cond(C type, int a, int b) {
	}

	record Rule(List<Cond> all, int glyph) {
	}

	final int n, glyphs;
	final int[] glyph, start, target;
	int moons, flame, seal;
	final List<List<Rule>> rules = new ArrayList<>();
	int[] otherwise;
	/** Which tablet niche shows which pillar (-1 = a faded tablet); pillars only in the Codex on Hard and up. */
	private final int[] spotOf = new int[SPOTS.length];

	private final Display.TextDisplay[] glyphText, numeralText;
	private final Display.TextDisplay[] moonText = new Display.TextDisplay[3];
	private final Display.TextDisplay[] tablet = new Display.TextDisplay[SPOTS.length];
	private Display.TextDisplay sealText;
	private long leverUntil, flashUntil, submitCd;
	private boolean soloTold;
	private final boolean[] flash;

	TrialGlyph(Rifts.Run run, int index) {
		super(run, index, Trials.Kind.GLYPH);
		this.n = tier <= 1 ? 4 : 5;
		this.glyphs = tier == 0 ? 6 : 8;
		this.glyph = new int[n];
		this.start = new int[n];
		this.target = new int[n];
		this.otherwise = new int[n];
		this.glyphText = new Display.TextDisplay[n];
		this.numeralText = new Display.TextDisplay[n];
		this.flash = new boolean[n];
	}

	@Override
	String name() {
		return "Glyph Vault";
	}

	@Override
	String hint() {
		return "Split up: an Operator in the Vault turns the pillars and reads out the signs, the Readers in the Library find each pillar's rules. Pull the Vault lever when every pillar is right.";
	}

	// ---------------------------------------------------------------
	// Positions
	// ---------------------------------------------------------------

	int pf(int i) {
		return 6 - (int) Math.round(Math.abs(i - (n - 1) / 2.0));
	}

	int pl(int i) {
		return VAULT + (int) Math.round((i - (n - 1) / 2.0) * 4);
	}

	BlockPos pillarPos(int i) {
		return arena(pf(i), pl(i), 2);
	}

	BlockPos leverPos() {
		return arena(-6, VAULT, 2);
	}

	BlockPos swapVault() {
		return arena(-13, VAULT + 7, 2);
	}

	BlockPos swapLibrary() {
		return arena(-13, LIB - 7, 2);
	}

	@Override
	Vec3 plinth() {
		return Vec3.atBottomCenterOf(arena(-6, VAULT, 3)).add(0, 0.2, 0);
	}

	boolean inVault(Vec3 p) {
		double f = af(p), l = al(p);
		return f > BACK && f < FRONT && l > VAULT - 10 && l < VAULT + 10;
	}

	// ---------------------------------------------------------------
	// Building
	// ---------------------------------------------------------------

	@Override
	void build(ServerLevel level, RandomSource r) {
		Block banner = BANNER[index % 4];
		// the forecourt
		for (int f = -36; f <= BACK; f++)
			for (int l = -32; l <= 32; l++) {
				boolean court = f >= -30;
				if (!court && Math.abs(l) > 3) continue;
				Block b = Math.abs(l) <= 2 || (court && (l == VAULT || l == LIB || f == -24)) ? pal.accent() : pal.floor();
				if (court && (Math.abs(l) == 32 || f == -30) && Math.abs(l) > 3) b = pal.edge();
				set(level, f, l, 0, b);
			}
		// forecourt rim, braziers and broken columns
		for (int l = -32; l <= 32; l++) {
			if (Math.abs(l) <= 3) continue;
			if (TrialDecor.hash(-30, l, 1) % 5 != 0) set(level, -30, l, 1, pal.edge());
		}
		for (int f = -30; f <= BACK; f++) {
			for (int s : new int[]{-32, 32}) if (TrialDecor.hash(f, s, 1) % 5 != 0) set(level, f, s, 1, pal.edge());
		}
		for (int[] q : new int[][]{{-27, 8}, {-27, -8}, {-20, 8}, {-20, -8}, {-27, 28}, {-27, -28}}) TrialDecor.brazier(level, this, q[0], q[1]);
		for (int[] q : new int[][]{{-24, 14}, {-24, -14}, {-24, 26}, {-24, -26}}) {
			int h = 3 + TrialDecor.hash(q[0], q[1], 2) % 6;
			for (int y = 1; y <= h; y++) set(level, q[0], q[1], y, y == 1 ? pal.accent() : pal.edge());
			set(level, q[0] + 1, q[1] + 1, 1, pal.edge()); // a fallen drum
		}
		// the whole building's floor and shell
		for (int f = BACK; f <= FRONT; f++)
			for (int l = -30; l <= 30; l++) set(level, f, l, 0, pal.floor());
		room(level, VAULT);
		room(level, LIB);
		// the facade between the rooms, the back wall, and the Hush (the tower that keeps them apart)
		for (int l = -9; l <= 9; l++)
			for (int y = 1; y <= 9; y++) {
				set(level, BACK, l, y, pal.edge());
				set(level, FRONT, l, y, pal.edge());
			}
		hush(level);
		// buttresses and banners on the facade
		for (int l : new int[]{-30, -25, -15, -10, 10, 15, 25, 30}) {
			for (int y = 1; y <= 10; y++) set(level, BACK - 1, l, y, y == 10 ? pal.accent() : pal.base());
			set(level, BACK - 2, l, 1, pal.base());
		}
		for (int l : new int[]{-27, -13, -5, 5, 13, 27}) TrialDecor.banner(level, this, BACK, l, 7, -1, 0, banner);
		label(level, Vec3.atCenterOf(arena(BACK - 2, VAULT, 6)), Component.literal("The Vault").withStyle(s -> s.withColor(rgb).withBold(true)), 1.4f);
		label(level, Vec3.atCenterOf(arena(BACK - 2, LIB, 6)), Component.literal("The Library").withStyle(s -> s.withColor(rgb).withBold(true)), 1.4f);
		vaultInside(level);
		libraryInside(level);
		// rock under it all, debris around
		TrialDecor.underside(level, this, -37, FRONT + 1, -33, 33, (f, l) -> {
			double edge = Math.min(Math.min(f + 37, FRONT + 1 - f), 33 - Math.abs(l));
			return (int) (2 + Math.min(14, edge * 0.7) + TrialDecor.hash(f, l, 0) % 3);
		});
		TrialDecor.debris(level, this, r, 12, 33, 39, -14, 16, (f, l) -> f >= -42 && f <= FRONT + 5 && Math.abs(l) <= 37);
		roll(r, true);
		showAll(level);
	}

	/** A room's shell: walls, a lit ceiling with a parapet, a door in the facade. */
	private void room(ServerLevel level, int lc) {
		for (int f = BACK; f <= FRONT; f++) {
			for (int l = lc - 10; l <= lc + 10; l++) {
				boolean wall = f == BACK || f == FRONT || Math.abs(l - lc) == 10;
				if (wall) for (int y = 1; y <= 9; y++) set(level, f, l, y, pal.edge());
				boolean inner = !wall;
				set(level, f, l, 10, inner && Math.floorMod(f, 4) == 0 && Math.floorMod(l - lc, 4) == 2 ? pal.light() : pal.edge());
				if (wall && (f + l) % 2 == 0) set(level, f, l, 11, pal.edge());
			}
		}
		// the door: 3 wide, 3 tall, an accent frame
		for (int l = lc - 1; l <= lc + 1; l++) for (int y = 1; y <= 3; y++) set(level, BACK, l, y, Blocks.AIR);
		for (int l = lc - 2; l <= lc + 2; l++) set(level, BACK, l, 4, pal.accent());
		for (int y = 1; y <= 3; y++) {
			set(level, BACK, lc - 2, y, pal.accent());
			set(level, BACK, lc + 2, y, pal.accent());
		}
	}

	private void hush(ServerLevel level) {
		for (int f = -10; f <= 8; f++)
			for (int l = -6; l <= 6; l++) {
				boolean wall = f == -10 || f == 8 || Math.abs(l) == 6;
				if (!wall) continue;
				boolean corner = (f == -10 || f == 8) && Math.abs(l) == 6;
				for (int y = 1; y <= 22; y++) {
					boolean window = !corner && y >= 13 && y <= 17 && (Math.abs(l) <= 1 || Math.abs(f + 1) <= 1);
					set(level, f, l, y, corner ? pal.base() : window ? pal.gate() : y % 6 == 0 ? pal.accent() : pal.edge());
				}
			}
		// a stepped crown
		for (int k = 0; k < 4; k++)
			for (int f = -10 + k; f <= 8 - k; f++)
				for (int l = -6 + k; l <= 6 - k; l++) set(level, f, l, 23 + k, k == 3 ? pal.accent() : pal.edge());
		set(level, -1, 0, 27, pal.light());
		for (int y = 28; y <= 31; y++) set(level, -1, 0, y, pal.edge());
		set(level, -1, 0, 32, pal.light());
	}

	private void vaultInside(ServerLevel level) {
		int lc = VAULT;
		// a floor ring around the brazier, pilasters on the walls
		for (int f = -8; f <= 8; f++)
			for (int l = lc - 8; l <= lc + 8; l++) {
				double d = Math.sqrt(f * f + (l - lc) * (l - lc));
				if (d >= 5.5 && d < 6.6) set(level, f, l, 0, pal.accent());
			}
		for (int f : new int[]{-11, -3, 10})
			for (int s : new int[]{lc - 9, lc + 9}) for (int y = 1; y <= 9; y++) set(level, f, s, y, y % 4 == 1 ? pal.light() : pal.accent());
		// the pillars (rune faces at dy 2)
		for (int i = 0; i < n; i++) {
			set(level, pf(i), pl(i), 1, pal.edge());
			set(level, pf(i), pl(i), 2, pal.accent());
			set(level, pf(i), pl(i), 3, pal.edge());
			set(level, pf(i), pl(i), 0, pal.accent());
		}
		// the moon lamps in the back wall, in accent frames
		for (int ml : MOON_L) {
			for (int y = 5; y <= 7; y++) for (int l = ml - 1; l <= ml + 1; l++) set(level, FRONT, l, y, pal.accent());
		}
		// the brazier
		for (int[] q : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) set(level, q[0], lc + q[1], 1, pal.edge());
		// the lever on its pedestal
		set(level, -6, lc, 1, pal.accent());
		set(level, -6, lc, 2, lever(false));
		label(level, Vec3.atBottomCenterOf(arena(-6, lc, 3)).add(0, 0.5, 0), Component.literal("Seal the answer").withStyle(ChatFormatting.GRAY), 0.6f);
		// the Seal over the door, inside
		for (int l = lc - 2; l <= lc + 2; l++) for (int y = 5; y <= 7; y++) set(level, BACK, l, y, pal.accent());
		// the solo swap lever
		swapLever(level, swapVault());
		// lanterns
		for (int f : new int[]{-10, 10}) TrialDecor.lantern(level, this, f, lc, 10, 2, true);
	}

	private void libraryInside(ServerLevel level) {
		int lc = LIB;
		// bookshelves on the long walls and the back, with the tablet niches
		for (int f = BACK + 1; f <= FRONT - 1; f++)
			for (int s : new int[]{lc - 9, lc + 9})
				for (int y = 1; y <= 7; y++) set(level, f, s, y, y == 7 ? pal.accent() : Blocks.BOOKSHELF);
		for (int l = lc - 8; l <= lc + 8; l++) for (int y = 1; y <= 7; y++) set(level, FRONT - 1, l, y, y == 7 ? pal.accent() : Blocks.BOOKSHELF);
		for (int[] sp : SPOTS) {
			for (int df = -3; df <= 3; df++)
				for (int y = 1; y <= 6; y++) {
					boolean frame = Math.abs(df) == 3 || y == 1 || y == 6;
					set(level, sp[0] + df, sp[1], y, frame ? pal.accent() : Blocks.AIR);
				}
			set(level, sp[0], sp[1], 7, pal.light());
		}
		// a carpet down the middle, two reading desks with candles, the lecterns
		for (int f = BACK + 1; f <= FRONT - 2; f++) for (int l = lc - 1; l <= lc + 1; l++) set(level, f, l, 1, CARPET[index % 4]);
		for (int s : new int[]{lc - 5, lc + 5})
			for (int f = -9; f <= 9; f++) {
				if (Math.abs(f) <= 1) continue;
				set(level, f, s, 1, pal.accent());
				if (Math.floorMod(f, 3) == 0)
					set(level, f, s, 2, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 1 + Math.floorMod(f * 7, 3)).setValue(CandleBlock.LIT, true));
			}
		lectern(level, -4, lc);
		lectern(level, 6, lc);
		label(level, Vec3.atBottomCenterOf(arena(-4, lc, 2)).add(0, 0.6, 0), Component.literal("Codex").withStyle(s -> s.withColor(rgb)), 0.6f);
		label(level, Vec3.atBottomCenterOf(arena(6, lc, 2)).add(0, 0.6, 0), Component.literal("Lexicon").withStyle(s -> s.withColor(rgb)), 0.6f);
		swapLever(level, swapLibrary());
		for (int f : new int[]{-10, 0, 10}) TrialDecor.lantern(level, this, f, lc, 10, 2, false);
	}

	private BlockState lever(boolean on) {
		return Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, TrialDecor.dir(this, -1, 0)).setValue(LeverBlock.POWERED, on);
	}

	private void swapLever(ServerLevel level, BlockPos top) {
		level.setBlock(top.below(), pal.base().defaultBlockState(), 2 | 16);
		level.setBlock(top, lever(false), 2 | 16);
		label(level, Vec3.atBottomCenterOf(top.above()).add(0, 0.3, 0), Component.literal("Swap rooms (alone)").withStyle(ChatFormatting.DARK_GRAY), 0.5f);
	}

	private void lectern(ServerLevel level, int f, int l) {
		set(level, f, l, 1, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, TrialDecor.dir(this, -1, 0)).setValue(LecternBlock.HAS_BOOK, true));
	}

	// ---------------------------------------------------------------
	// The puzzle
	// ---------------------------------------------------------------

	/** New signs (when {@code signs}), new rules, new start glyphs. */
	void roll(RandomSource r, boolean signs) {
		if (signs) {
			moons = r.nextInt(4);
			flame = r.nextInt(3);
			seal = r.nextInt(glyphs);
		}
		rules.clear();
		for (int i = 0; i < n; i++) {
			List<Rule> best = null;
			for (int tries = 0; tries < 8; tries++) {
				List<Rule> rs = pillarRules(i, r);
				best = rs;
				if (winner(i, rs) >= 0 || r.nextInt(3) == 0) break;
			}
			rules.add(best);
			target[i] = answer(i, best);
		}
		for (int i = 0; i < n; i++) {
			start[i] = r.nextInt(glyphs);
			if (start[i] == target[i]) start[i] = (start[i] + 1 + r.nextInt(glyphs - 1)) % glyphs;
			glyph[i] = start[i];
		}
		// tablets: shuffled niches; on Hard and up pillars II and IV are only in the Codex
		List<Integer> spots = new ArrayList<>();
		for (int k = 0; k < SPOTS.length; k++) spots.add(k);
		Collections.shuffle(spots, new java.util.Random(r.nextLong()));
		java.util.Arrays.fill(spotOf, -1);
		int k = 0;
		for (int i = 0; i < n; i++) if (!inCodex(i)) spotOf[spots.get(k++)] = i;
	}

	boolean inCodex(int i) {
		return tier >= 2 && (i == 1 || i == 3);
	}

	/** A pillar's rules: 2 (3 from Hard) conditional rules with their own glyphs, then "otherwise". */
	private List<Rule> pillarRules(int i, RandomSource r) {
		int count = tier <= 1 ? 2 : 3;
		List<Integer> pool = new ArrayList<>();
		for (int g = 0; g < glyphs; g++) pool.add(g);
		Collections.shuffle(pool, new java.util.Random(r.nextLong()));
		List<Rule> out = new ArrayList<>();
		for (int k = 0; k < count; k++) {
			List<Cond> all = new ArrayList<>();
			// no rule may be unreachable (an earlier rule always fires first) or impossible: a sign must never read like a mistake
			for (int attempt = 0; attempt < 40; attempt++) {
				all = new ArrayList<>();
				all.add(cond(i, r));
				if (tier >= 3 && r.nextBoolean()) {
					Cond c2;
					do c2 = cond(i, r); while (c2.type == all.get(0).type);
					all.add(c2);
				}
				if (!shadowed(out, all) && !contradicts(all)) break;
			}
			out.add(new Rule(all, pool.get(k)));
		}
		otherwise[i] = pool.get(count);
		return out;
	}

	/** Condition x being true makes y true. */
	private static boolean implies(Cond x, Cond y) {
		if (x.equals(y)) return true;
		return y.type == C.MOONS_MIN && (x.type == C.MOONS_MIN || x.type == C.MOONS_EQ) && x.a >= y.a;
	}

	/** An earlier rule whose every condition follows from these: it always wins, so this rule could never fire. */
	private static boolean shadowed(List<Rule> earlier, List<Cond> all) {
		for (Rule e : earlier) if (e.all.stream().allMatch(ec -> all.stream().anyMatch(c -> implies(c, ec)))) return true;
		return false;
	}

	/** Two conditions that cannot both hold (exactly 0 moons and at least 2 moons). */
	private static boolean contradicts(List<Cond> all) {
		for (Cond a : all) for (Cond b : all) if (a.type == C.MOONS_EQ && b.type == C.MOONS_MIN && a.a < b.a) return true;
		return false;
	}

	private Cond cond(int i, RandomSource r) {
		List<C> types = new ArrayList<>(List.of(C.MOONS_MIN, C.MOONS_EQ, C.FLAME));
		if (tier >= 1) types.add(C.SEAL);
		if (tier >= 2 && i > 0) types.add(C.PILLAR);
		C t = types.get(r.nextInt(types.size()));
		return switch (t) {
			case MOONS_MIN -> new Cond(t, 1 + r.nextInt(3), 0);
			case MOONS_EQ -> new Cond(t, r.nextInt(4), 0);
			case FLAME -> new Cond(t, r.nextInt(3), 0);
			// half the time the true seal, so seal rules hold now and then
			case SEAL -> new Cond(t, r.nextBoolean() ? seal : r.nextInt(glyphs), 0);
			case PILLAR -> {
				int j = r.nextInt(i);
				yield new Cond(t, j, r.nextBoolean() ? target[j] : r.nextInt(glyphs));
			}
		};
	}

	boolean holds(Cond c) {
		return switch (c.type) {
			case MOONS_MIN -> moons >= c.a;
			case MOONS_EQ -> moons == c.a;
			case FLAME -> flame == c.a;
			case SEAL -> seal == c.a;
			case PILLAR -> target[c.a] == c.b;
		};
	}

	/** The first rule of pillar i that holds, -1 = none (otherwise). */
	private int winner(int i, List<Rule> rs) {
		for (int k = 0; k < rs.size(); k++) if (rs.get(k).all.stream().allMatch(this::holds)) return k;
		return -1;
	}

	private int answer(int i, List<Rule> rs) {
		int w = winner(i, rs);
		return w >= 0 ? rs.get(w).glyph : otherwise[i];
	}

	// ---------------------------------------------------------------
	// Text
	// ---------------------------------------------------------------

	private MutableComponent glyphName(int g) {
		return Component.literal(GLYPH[g]).withStyle(s -> s.withColor(rgb).withBold(true)).append(Component.literal(" " + GNAME[g]).withStyle(ChatFormatting.WHITE));
	}

	private MutableComponent condText(Cond c) {
		return switch (c.type) {
			case MOONS_MIN -> Component.literal(c.a == 1 ? "a moon is lit" : "at least " + WORD[c.a] + " moons are lit");
			case MOONS_EQ -> Component.literal(c.a == 0 ? "no moon is lit" : "exactly " + WORD[c.a] + (c.a == 1 ? " moon is lit" : " moons are lit"));
			case FLAME -> Component.literal(FLAME[c.a]);
			case SEAL -> Component.literal("the Seal shows ").append(glyphName(c.a));
			case PILLAR -> Component.literal("pillar " + ROMAN[c.a] + " is set to ").append(glyphName(c.b));
		};
	}

	/** Pillar i's rules as tablet text. */
	MutableComponent rulesText(int i) {
		MutableComponent t = Component.literal("Pillar " + ROMAN[i]).withStyle(s -> s.withColor(rgb).withBold(true));
		List<Rule> rs = rules.get(i);
		for (int k = 0; k < rs.size(); k++) {
			Rule rule = rs.get(k);
			MutableComponent line = Component.literal("\n" + (k == 0 ? "If " : "Else if ")).withStyle(ChatFormatting.GRAY);
			for (int c = 0; c < rule.all.size(); c++) {
				if (c > 0) line.append(Component.literal(" and ").withStyle(ChatFormatting.GRAY));
				line.append(condText(rule.all.get(c)).withStyle(ChatFormatting.WHITE));
			}
			line.append(Component.literal(": ").withStyle(ChatFormatting.GRAY)).append(glyphName(rule.glyph));
			t.append(line);
		}
		t.append(Component.literal("\nOtherwise: ").withStyle(ChatFormatting.GRAY)).append(glyphName(otherwise[i]));
		return t;
	}

	private void showAll(ServerLevel level) {
		for (int i = 0; i < n; i++) showPillar(level, i);
		showSigns(level);
		showRules(level);
	}

	private void showPillar(ServerLevel level, int i) {
		MutableComponent c = Component.literal(GLYPH[glyph[i]]).withStyle(s -> s.withColor(flash[i] ? 0xFF4040 : rgb).withBold(true));
		if (glyphText[i] == null) {
			glyphText[i] = label(level, Vec3.atBottomCenterOf(arena(pf(i), pl(i), 4)).add(0, 0.15, 0), c, 2.6f);
			numeralText[i] = label(level, Vec3.atBottomCenterOf(arena(pf(i), pl(i), 5)).add(0, 0.7, 0), pillarTag(i), 1.1f);
		} else {
			TrialDecor.text(glyphText[i], c);
			if (numeralText[i] != null) TrialDecor.text(numeralText[i], pillarTag(i));
		}
	}

	/** "Pillar II - Skull": which pillar, and the name of the glyph it shows now. */
	private Component pillarTag(int i) {
		return Component.literal("Pillar " + ROMAN[i] + " - ").withStyle(ChatFormatting.GRAY).append(Component.literal(GNAME[glyph[i]]).withStyle(ChatFormatting.WHITE));
	}

	private void showSigns(ServerLevel level) {
		for (int k = 0; k < 3; k++) {
			boolean lit = k < moons;
			set(level, FRONT, MOON_L[k], 6, lit ? pal.light() : pal.base());
			Component c = Component.literal("☾").withStyle(s -> s.withColor(lit ? rgb : 0x3A3A44).withBold(true));
			if (moonText[k] == null) moonText[k] = label(level, Vec3.atBottomCenterOf(arena(FRONT - 1, MOON_L[k], 5)).add(0, 0.2, 0), c, 2.4f);
			else TrialDecor.text(moonText[k], c);
		}
		BlockState fire = flame == 1 ? Blocks.SOUL_CAMPFIRE.defaultBlockState() : Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, flame == 0);
		set(level, 0, VAULT, 1, fire);
		Component s = Component.literal("THE SEAL\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal(GLYPH[seal] + "\n").withStyle(st -> st.withColor(rgb).withBold(true)))
				.append(Component.literal(GNAME[seal]).withStyle(ChatFormatting.WHITE));
		if (sealText == null) sealText = label(level, Vec3.atBottomCenterOf(arena(BACK + 1, VAULT, 5)).add(0, 0.2, 0), s, 2.2f);
		else TrialDecor.text(sealText, s);
	}

	private void showRules(ServerLevel level) {
		for (int k = 0; k < SPOTS.length; k++) {
			int i = spotOf[k];
			Component c;
			if (i >= 0) c = rulesText(i);
			else c = Component.literal(tier >= 2 ? "The ink here has run.\nThe rest is copied in the Codex." : "The ink here has faded.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
			if (tablet[k] == null) {
				int side = SPOTS[k][1] < LIB ? 1 : -1;
				Vec3 at = Vec3.atBottomCenterOf(arena(SPOTS[k][0], SPOTS[k][1] + side, 3)).add(0, 0.1, 0);
				tablet[k] = TrialDecor.panel(level, this, at, c, 0.45f);
			} else TrialDecor.text(tablet[k], c);
		}
		book(level, -4, codex());
		book(level, 6, lexicon());
	}

	private ItemStack codex() {
		List<Component> pages = new ArrayList<>();
		pages.add(Component.literal("The Glyph Vault\n\n").withStyle(ChatFormatting.BOLD)
				.append(Component.literal("The Operator turns the Vault's pillars (use; sneak to turn back) and pulls the lever to seal the answer.\n\nEach pillar has its rules. The first rule that holds decides its glyph.").withStyle(ChatFormatting.RESET)));
		pages.add(Component.literal("Signs only the Vault shows:\n\n- the moon lamps (0 to 3 lit)\n- the brazier: red, blue or cold" + (tier >= 1 ? "\n- the Seal over the door" : "")));
		for (int i = 0; i < n; i++) if (inCodex(i)) pages.add(rulesText(i));
		return writtenBook("Codex", pages);
	}

	private ItemStack lexicon() {
		MutableComponent t = Component.literal("The glyph wheel\n(the order a pillar turns)\n\n");
		for (int g = 0; g < glyphs; g++) t.append(Component.literal(GLYPH[g] + " " + GNAME[g] + "\n"));
		return writtenBook("Lexicon", List.of(t));
	}

	private ItemStack writtenBook(String title, List<Component> pages) {
		ItemStack s = new ItemStack(Items.WRITTEN_BOOK);
		List<Filterable<Component>> ps = new ArrayList<>();
		for (Component c : pages) ps.add(Filterable.passThrough(c));
		s.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), "the Vault", 0, ps, true));
		return s;
	}

	private void book(ServerLevel level, int f, ItemStack stack) {
		if (level.getBlockEntity(arena(f, LIB, 1)) instanceof LecternBlockEntity le) le.setBook(stack);
	}

	// ---------------------------------------------------------------
	// Ticking and input
	// ---------------------------------------------------------------

	@Override
	void tick(ServerLevel level, List<ServerPlayer> here, long now) {
		if (now % 10 != 0) return;
		if (leverUntil != 0 && now >= leverUntil) {
			leverUntil = 0;
			level.setBlock(leverPos(), lever(false), 2 | 16);
		}
		if (flashUntil != 0 && now >= flashUntil) {
			flashUntil = 0;
			java.util.Arrays.fill(flash, false);
			for (int i = 0; i < n; i++) showPillar(level, i);
		}
		if (!soloTold && here.size() == 1) {
			soloTold = true;
			here.get(0).sendSystemMessage(Component.literal("Alone? The swap levers by each door carry you between the Vault and the Library - the clock keeps running.").withStyle(ChatFormatting.GRAY));
		}
		if (now % 20 == 0 && !solved) {
			for (int i = 0; i < n; i++) {
				Vec3 c = Vec3.atCenterOf(pillarPos(i));
				level.sendParticles(ParticleTypes.ENCHANT, c.x, c.y + 1.6, c.z, 4, 0.3, 0.3, 0.3, 0.4);
			}
			for (int k = 0; k < moons; k++) {
				Vec3 c = Vec3.atCenterOf(arena(FRONT - 1, MOON_L[k], 6));
				level.sendParticles(AuraFx.dust(rgb, 1f), c.x, c.y, c.z, 2, 0.3, 0.3, 0.3, 0);
			}
		}
	}

	@Override
	boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
		for (int i = 0; i < n; i++) {
			if (!TrialDecor.at(this, pos, pf(i), pl(i), 1, 3)) continue;
			if (!solved) turn(level, p, i, p.isShiftKeyDown() ? -1 : 1);
			return true;
		}
		if (pos.equals(leverPos()) || pos.equals(leverPos().below())) {
			if (!solved) submit(level, p);
			return true;
		}
		if (pos.equals(swapVault()) || pos.equals(swapVault().below()) || pos.equals(swapLibrary()) || pos.equals(swapLibrary().below())) {
			swap(level, p, pos.getX() == swapVault().getX() && pos.getZ() == swapVault().getZ());
			return true;
		}
		return false; // the lecterns open as usual
	}

	void turn(ServerLevel level, ServerPlayer p, int i, int by) {
		glyph[i] = Math.floorMod(glyph[i] + by, glyphs);
		showPillar(level, i);
		Vec3 c = Vec3.atCenterOf(pillarPos(i));
		level.playSound(null, c.x, c.y, c.z, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.5f, 1.5f);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8f, 0.6f + glyph[i] * 0.12f);
		level.sendParticles(AuraFx.dust(rgb, 1.2f), c.x, c.y + 1.8, c.z, 8, 0.3, 0.3, 0.3, 0);
	}

	void submit(ServerLevel level, ServerPlayer p) {
		long now = level.getGameTime();
		if (now < submitCd) return;
		submitCd = now + 30;
		level.setBlock(leverPos(), lever(true), 2 | 16);
		leverUntil = now + 20;
		Vec3 at = Vec3.atBottomCenterOf(leverPos());
		level.playSound(null, at.x, at.y, at.z, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 1f, 0.6f);
		List<Integer> wrong = new ArrayList<>();
		for (int i = 0; i < n; i++) if (glyph[i] != target[i]) wrong.add(i);
		if (wrong.isEmpty()) {
			level.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.5f, 1.2f);
			for (int i = 0; i < n; i++) AuraFx.line(level, Vec3.atCenterOf(pillarPos(i)).add(0, 2, 0), plinth().add(0, 1, 0), AuraFx.dust(rgb, 1.3f), 20);
			solve(level, plinth().add(0, 0.8, 0));
			return;
		}
		List<ServerPlayer> here = TrialDecor.inArena(level, this);
		strike(level, p, at, here);
		if (strikes == 0) return; // a full reset: nothing left to hint at
		int h = hintsAllowed() ? 1 + totalStrikes - (tier == 0 ? 1 : 2) : 0;
		if (h >= 1) {
			MutableComponent m = Component.literal(wrong.size() + (wrong.size() == 1 ? " pillar is" : " pillars are") + " wrong").withStyle(ChatFormatting.GOLD);
			if (h >= 2) {
				StringBuilder b = new StringBuilder();
				for (int i : wrong) b.append(b.length() == 0 ? "" : ", ").append(ROMAN[i]);
				m.append(Component.literal(": " + b).withStyle(ChatFormatting.YELLOW));
				for (int i : wrong) flash[i] = true;
				flashUntil = now + 100;
				for (int i = 0; i < n; i++) showPillar(level, i);
			}
			for (ServerPlayer q : here) q.sendSystemMessage(m);
		}
	}

	/** Solo only: the lever carries you to the other room's lever. */
	void swap(ServerLevel level, ServerPlayer p, boolean fromVault) {
		if (TrialDecor.inArena(level, this).size() > 1) {
			p.displayClientMessage(Component.literal("The lever won't move - it only answers one who is alone").withStyle(ChatFormatting.GRAY), true);
			return;
		}
		Vec3 e = Vec3.atBottomCenterOf(arena(-12, fromVault ? LIB - 5 : VAULT + 5, 1));
		AuraFx.burst(level, p.getX(), p.getY() + 1, p.getZ(), ParticleTypes.REVERSE_PORTAL, 20, 0.4, 0.1);
		if (com.toast.aurapoints.shim.Platform.get().isFakePlayer(p)) p.moveTo(e.x, e.y, e.z, forwardYaw(), 0f);
		else p.teleportTo(level, e.x, e.y, e.z, forwardYaw(), 0f);
		p.fallDistance = 0;
		level.playSound(null, e.x, e.y, e.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 1.3f);
	}

	// ---------------------------------------------------------------
	// The strike ladder
	// ---------------------------------------------------------------

	private void rewrite(ServerLevel level) {
		RandomSource r = random != null ? random : level.random;
		int[] keep = glyph.clone();
		roll(r, false);
		System.arraycopy(keep, 0, glyph, 0, n);
		showAll(level);
		for (ServerPlayer p : TrialDecor.inArena(level, this))
			p.displayClientMessage(Component.literal("The tablets rewrite themselves").withStyle(ChatFormatting.LIGHT_PURPLE), true);
	}

	@Override
	void onReact(ServerLevel level, Vec3 at) {
		// the moon lamps gutter for a moment
		for (int k = 0; k < 3; k++) {
			Vec3 c = Vec3.atCenterOf(arena(FRONT - 1, MOON_L[k], 6));
			level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 6, 0.3, 0.3, 0.3, 0.01);
		}
		if (tier >= 3) rewrite(level);
	}

	@Override
	void onHazard(ServerLevel level, Vec3 at) {
		punish(level, Vec3.atBottomCenterOf(arena(0, VAULT - 5, 1)), 2 + tier / 2);
		// partial reset: the pillars turn back to where they started
		System.arraycopy(start, 0, glyph, 0, n);
		if (tier >= 3) rewrite(level);
		else for (int i = 0; i < n; i++) showPillar(level, i);
	}

	@Override
	void onReset(ServerLevel level, RandomSource r) {
		roll(r, true);
		java.util.Arrays.fill(flash, false);
		showAll(level);
	}

	// ---------------------------------------------------------------
	// The intro
	// ---------------------------------------------------------------

	@Override
	RiftCam intro() {
		return RiftCam.shot(rgb).title(name(), run.difficulty.label).skippable(true)
				.key(0, Vec3.atCenterOf(arena(-42, 0, 22)), Vec3.atCenterOf(arena(-6, 0, 8)))
				.key(36, Vec3.atCenterOf(arena(-30, 0, 9)), Vec3.atCenterOf(arena(-16, 0, 6)))
				.cut(40, Vec3.atCenterOf(arena(-13, LIB, 5)), Vec3.atCenterOf(arena(4, LIB - 9, 3)))
				.key(80, Vec3.atCenterOf(arena(3, LIB + 3, 6)), Vec3.atCenterOf(arena(-1, LIB - 9, 3)))
				.cut(84, Vec3.atCenterOf(arena(-13, VAULT, 5)), Vec3.atCenterOf(arena(5, VAULT, 4)))
				.key(140, Vec3.atCenterOf(arena(-2, VAULT, 6)), Vec3.atCenterOf(arena(6, VAULT, 4)));
	}

	// ---------------------------------------------------------------
	// Dev test
	// ---------------------------------------------------------------

	@Override
	void devPlay(net.minecraft.server.MinecraftServer server, ServerLevel level, ServerPlayer fake, java.util.function.Consumer<String> log, Runnable done) {
		long now = level.getGameTime();
		// solo swap: library -> vault
		Vec3 lib = Vec3.atBottomCenterOf(arena(-12, LIB - 5, 1));
		fake.moveTo(lib.x, lib.y, lib.z);
		tick(level, List.of(fake), now);
		use(level, fake, swapLibrary());
		log.accept("glyph: swap lever from the library -> in the vault " + inVault(fake.position()) + " (must be true)");
		// a wrong submit (every pillar starts off its answer)
		submitCd = 0;
		use(level, fake, leverPos());
		log.accept("glyph: wrong submit -> strikes " + strikes + " (must be 1), solved " + solved + " (must be false)");
		// sneak turns back
		int g0 = glyph[0];
		fake.setShiftKeyDown(true);
		use(level, fake, pillarPos(0));
		fake.setShiftKeyDown(false);
		log.accept("glyph: sneak turn -> pillar I back one " + (glyph[0] == Math.floorMod(g0 - 1, glyphs)) + " (must be true)");
		// the rules decide the targets: check one by hand against the solver
		log.accept("glyph: pillar I target " + GNAME[target[0]] + " = rules " + GNAME[answer(0, rules.get(0))] + " (must match)");
		// turn every pillar to its answer through the real input
		for (int i = 0; i < n; i++) for (int k = 0; k < glyphs && glyph[i] != target[i]; k++) use(level, fake, pillarPos(i));
		boolean all = true;
		for (int i = 0; i < n; i++) all &= glyph[i] == target[i];
		log.accept("glyph: pillars turned to their answers " + all + " (must be true)");
		AuraFx.later(server, 35, () -> {
			use(level, fake, leverPos());
			log.accept("glyph: right submit -> solved " + solved + " (must be true), strikes " + strikes + " (must be 1)");
			done.run();
		});
	}
}
