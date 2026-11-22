package com.toast.aurapoints.rift;

import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.Props;
import com.toast.aurapoints.mixin.DisplayAccessor;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Small building and display helpers for the trial arenas (5.49.0, Prism Chamber and Weeping Statues): stairs that
 * match the aura palette, pillars, braziers, rubble, floating debris, and block-display shapes. Everything works in the
 * trial's arena frame (f forward, l to the side, dy from the floor).
 */
final class RuinKit {

	private RuinKit() {
	}

	/** Deterministic noise 0..99 for a frame position (same every rebuild of the same layout). */
	static int hash(int a, int b, int c) {
		return Math.floorMod(a * 73856093 ^ b * 19349663 ^ c * 83492791, 100);
	}

	/** The world direction of a frame delta (df, dl). */
	static Direction dir(Trials.Trial t, int df, int dl) {
		Direction d = Direction.fromDelta(t.fx * df + t.lx * dl, 0, t.fz * df + t.lz * dl);
		return d != null ? d : Direction.NORTH;
	}

	private static Block stairsOf(Block b) {
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
		String p = id.getPath();
		List<String> tries = new ArrayList<>();
		tries.add(p + "_stairs");
		if (p.endsWith("s")) tries.add(p.substring(0, p.length() - 1) + "_stairs");
		if (p.endsWith("_block")) tries.add(p.substring(0, p.length() - 6) + "_stairs");
		for (String t : tries) {
			var found = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), t));
			if (found.isPresent() && found.get() instanceof StairBlock) return found.get();
		}
		return null;
	}

	/** Stairs in the palette's stone (edge, floor, base or accent - the first that has stairs), going up toward {@code up}. */
	static BlockState stairs(RealmBuilder.Palette p, Direction up) {
		for (Block b : new Block[]{p.edge(), p.floor(), p.base(), p.accent()}) {
			Block s = stairsOf(b);
			if (s != null) return s.defaultBlockState().setValue(StairBlock.FACING, up);
		}
		return Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, up);
	}

	static BlockState candle(int n, boolean lit) {
		return Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, Math.max(1, Math.min(4, n))).setValue(BlockStateProperties.LIT, lit);
	}

	static BlockState hangingLantern(boolean soul) {
		return (soul ? Blocks.SOUL_LANTERN : Blocks.LANTERN).defaultBlockState().setValue(BlockStateProperties.HANGING, true);
	}

	static Block wallBanner(int wing) {
		return switch (wing) {
			case 1 -> Blocks.LIME_WALL_BANNER;
			case 2 -> Blocks.YELLOW_WALL_BANNER;
			case 3 -> Blocks.RED_WALL_BANNER;
			default -> Blocks.BLUE_WALL_BANNER;
		};
	}

	/** A banner hung on the face of a wall, facing (df, dl). */
	static void banner(Trials.Trial t, ServerLevel level, int f, int l, int dy, int df, int dl) {
		t.set(level, f, l, dy, wallBanner(t.index).defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, dir(t, df, dl)));
	}

	/** A pillar: a plus-shaped plinth, a shaft of edge stone, a plus-shaped capital and a light on top (unless broken). */
	static void pillar(Trials.Trial t, ServerLevel level, int f, int l, int dy0, int h, boolean broken, RandomSource r) {
		RealmBuilder.Palette p = t.pal;
		int top = broken ? Math.max(2, h - 2 - r.nextInt(Math.max(1, h / 2))) : h;
		for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) t.set(level, f + o[0], l + o[1], dy0, p.base());
		for (int k = 0; k < top; k++) t.set(level, f, l, dy0 + k, k == 0 ? p.base() : p.edge());
		if (broken) {
			// a few drums lie around the foot
			for (int k = 0; k < 2; k++) {
				int df = r.nextInt(5) - 2, dl = r.nextInt(5) - 2;
				if (Math.abs(df) + Math.abs(dl) >= 2) t.set(level, f + df, l + dl, dy0, r.nextBoolean() ? p.edge() : p.base());
			}
			return;
		}
		for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) t.set(level, f + o[0], l + o[1], dy0 + h - 1, p.accent());
		t.set(level, f, l, dy0 + h, p.light());
	}

	/** A brazier: edge stone, an accent bowl and a fire on top. */
	static void brazier(Trials.Trial t, ServerLevel level, int f, int l, int dy, boolean soul) {
		t.set(level, f, l, dy, t.pal.edge());
		t.set(level, f, l, dy + 1, t.pal.accent());
		t.set(level, f, l, dy + 2, (soul ? Blocks.SOUL_CAMPFIRE : Blocks.CAMPFIRE).defaultBlockState());
	}

	/** A floating chunk of rock (floor on top, base under) drifting over the void. */
	static void debris(Trials.Trial t, ServerLevel level, RandomSource r, int f, int l, int dy, int rad) {
		for (int df = -rad; df <= rad; df++)
			for (int dl = -rad; dl <= rad; dl++)
				for (int d = -rad - 1; d <= 0; d++)
					if (df * df + dl * dl + d * d * 2 <= rad * rad + r.nextInt(2))
						t.set(level, f + df, l + dl, dy + d, d == 0 ? (r.nextInt(5) == 0 ? t.pal.accent() : t.pal.floor()) : t.pal.base());
	}

	/** A tapered rock underside below a floor column: deeper toward the middle ({@code t01} 1 = the middle, 0 = the rim). */
	static void underside(Trials.Trial t, ServerLevel level, int f, int l, double t01, int maxDepth) {
		int depth = 1 + (int) Math.round(t01 * t01 * maxDepth) + hash(f, l, 7) % 3;
		for (int k = 1; k <= depth; k++) t.set(level, f, l, -k, k >= depth - 1 && hash(f, l, k) < 40 ? t.pal.edge() : t.pal.base());
	}

	// ---------------------------------------------------------------
	// Displays
	// ---------------------------------------------------------------

	/** A block display kept by the trial (tagged, removed with it). */
	static Display.BlockDisplay prop(Trials.Trial t, ServerLevel level, Vec3 at, BlockState state, Transformation tr, int smooth, boolean glow) {
		Display.BlockDisplay d = Props.spawn(level, at, state, tr, -1, smooth);
		if (glow) Props.glow(d);
		d.addTag(Trials.DISPLAY_TAG);
		t.displays.add(d);
		return d;
	}

	static void drop(Trials.Trial t, Display d) {
		if (d == null) return;
		t.displays.remove(d);
		if (d instanceof Display.BlockDisplay b) Props.remove(b);
		else d.discard();
	}

	/** Culling box of a long display (a beam segment): drawn whenever any part of it may be on screen. */
	static void bigBounds(Display.BlockDisplay d, float size) {
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setWidth(Math.max(8f, size * 2f));
		acc.aurapoints$setHeight(Math.max(8f, size * 2f));
	}

	/** An axis-aligned box from (x, y, z) (its min corner, relative to the display) of size w x h x d. */
	static Transformation box(float x, float y, float z, float w, float h, float d) {
		return new Transformation(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(w, h, d), null);
	}

	/** A box w x h x d centred on {@code c} (relative to the display), turned by {@code rot} about its middle. */
	static Transformation centred(Vector3f c, float w, float h, float d, Quaternionf rot) {
		Vector3f t = new Quaternionf(rot).transform(new Vector3f(-w / 2f, -h / 2f, -d / 2f)).add(c);
		return new Transformation(t, new Quaternionf(rot), new Vector3f(w, h, d), null);
	}

	/** A square rod {@code w} thick from {@code a} to {@code b} (relative to the display). */
	static Transformation rod(Vector3f a, Vector3f b, float w) {
		Vector3f dir = new Vector3f(b).sub(a);
		float len = dir.length();
		if (len < 1.0e-4f) return box(a.x, a.y, a.z, 0f, 0f, 0f);
		dir.div(len);
		Quaternionf rot = new Quaternionf().rotationTo(0f, 1f, 0f, dir.x, dir.y, dir.z);
		Vector3f off = rot.transform(new Vector3f(w / 2f, 0f, w / 2f));
		return new Transformation(new Vector3f(a).sub(off), rot, new Vector3f(w, len, w), null);
	}

	/** Players inside the trial's arena right now (from its {@code inside} set). */
	static List<ServerPlayer> arenaPlayers(Trials.Trial t, ServerLevel level) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : t.inside) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p == null) p = Rifts.TEST_PLAYERS.get(id);
			if (p != null && t.inArena(p.position())) out.add(p);
		}
		return out;
	}

	/** Stained glass of the nearest colour (for beam and filter displays). */
	static Block glassFor(int rgb) {
		int[][] table = {
				{0x3B6FD8, 0}, {0x7BD33A, 1}, {0xF2C43A, 2}, {0xD83B3B, 3}, {0xF0F0F0, 4},
				{0x3BD8D8, 5}, {0xD83BD8, 6}, {0xF28A2E, 7}, {0x8A3BD8, 8}};
		Block[] glass = {Blocks.BLUE_STAINED_GLASS, Blocks.LIME_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS, Blocks.RED_STAINED_GLASS, Blocks.WHITE_STAINED_GLASS,
				Blocks.CYAN_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS};
		int best = 4;
		long bestD = Long.MAX_VALUE;
		for (int[] e : table) {
			int c = e[0];
			long dr = ((c >> 16) & 255) - ((rgb >> 16) & 255), dg = ((c >> 8) & 255) - ((rgb >> 8) & 255), db = (c & 255) - (rgb & 255);
			long d = dr * dr + dg * dg + db * db;
			if (d < bestD) {
				bestD = d;
				best = e[1];
			}
		}
		return glass[best];
	}
}
