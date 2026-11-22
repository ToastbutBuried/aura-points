package com.toast.aurapoints.rift;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Shattered Reliquary (5.47.0): the hand-designed monument every rift run takes place in. Designed in
 * tools/reliquary/reliquary.py (git-ignored) and shipped as 32x32 column tiles in data/aurapoints/structure/reliquary/
 * plus anchors.json (every point the code needs: spawn, vote plates, nave door, sockets, wings, crypt...).
 * <p>
 * Placed by a {@link Job} over many ticks while the gate is still forming, one tile per tick: its columns are cleared
 * (only chunk sections that hold anything), then the tile is placed with the role blocks swapped for the aura's:
 * lime wool = accent, yellow wool = glow, white stained glass pane = the aura's glass, orange wool = the crypt moat,
 * light blue wool = secondary accent. Wing colours (blue, lime, yellow, red concrete) are the same for every aura.
 * Coordinates: origin = the floor block under the altar; x east, z south, y up.
 */
public final class Monument {

	private Monument() {
	}

	/** Where the origin sits in world height (the build reaches 69 below and 116 above). */
	public static final int ORIGIN_Y = 100;

	// ---------------------------------------------------------------
	// Anchors
	// ---------------------------------------------------------------

	public record Tile(String name, BlockPos min) {
	}

	/** All the build's points, relative to the origin. */
	public static final class Anchors {
		public BlockPos spawn, altar, doorMin, doorMax;
		public final List<BlockPos> plates = new ArrayList<>(), sockets = new ArrayList<>(), wings = new ArrayList<>(), wingDoors = new ArrayList<>(),
				cryptSpots = new ArrayList<>();
		public final List<int[]> wingDirs = new ArrayList<>();
		public final List<int[]> cryptPillars = new ArrayList<>();
		public final List<Tile> tiles = new ArrayList<>();
		public int hall, cryptFloor, cryptRadius, collapseRadius, gallery;
		public BlockPos boundsMin, boundsMax;
	}

	private static Anchors anchors;

	private static BlockPos pos(JsonArray a) {
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}

	public static Anchors anchors() {
		if (anchors != null) return anchors;
		Anchors a = new Anchors();
		try (InputStream in = Monument.class.getResourceAsStream("/data/aurapoints/structure/reliquary/anchors.json")) {
			if (in == null) throw new IllegalStateException("reliquary anchors.json missing");
			JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			a.spawn = pos(o.getAsJsonArray("spawn"));
			a.altar = pos(o.getAsJsonArray("altar"));
			JsonArray door = o.getAsJsonArray("naveDoor");
			a.doorMin = pos(door.get(0).getAsJsonArray());
			a.doorMax = pos(door.get(1).getAsJsonArray());
			for (var e : o.getAsJsonArray("plates")) a.plates.add(pos(e.getAsJsonArray()));
			for (var e : o.getAsJsonArray("sockets")) a.sockets.add(pos(e.getAsJsonArray()));
			for (var e : o.getAsJsonArray("wings")) {
				JsonArray w = e.getAsJsonArray();
				a.wings.add(pos(w));
				a.wingDirs.add(new int[]{w.get(3).getAsInt(), w.get(4).getAsInt()});
			}
			for (var e : o.getAsJsonArray("wingDoors")) a.wingDoors.add(pos(e.getAsJsonArray()));
			for (var e : o.getAsJsonArray("cryptSpots")) a.cryptSpots.add(pos(e.getAsJsonArray()));
			for (var e : o.getAsJsonArray("cryptPillars")) {
				JsonArray p = e.getAsJsonArray();
				a.cryptPillars.add(new int[]{p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()});
			}
			a.hall = o.get("hall").getAsInt();
			a.cryptFloor = o.get("cryptFloor").getAsInt();
			a.cryptRadius = o.get("cryptRadius").getAsInt();
			a.collapseRadius = o.get("collapseRadius").getAsInt();
			a.gallery = o.get("gallery").getAsInt();
			JsonArray bounds = o.getAsJsonArray("bounds");
			a.boundsMin = pos(bounds.get(0).getAsJsonArray());
			a.boundsMax = pos(bounds.get(1).getAsJsonArray());
			for (var e : o.getAsJsonArray("tiles")) {
				JsonObject t = e.getAsJsonObject();
				a.tiles.add(new Tile(t.get("name").getAsString(), pos(t.getAsJsonArray("min"))));
			}
		} catch (Exception e) {
			throw new IllegalStateException("can't read the reliquary anchors", e);
		}
		anchors = a;
		return a;
	}

	/** The world position of the monument's origin for a run slot. */
	public static BlockPos origin(int slot) {
		return new BlockPos(slot * 1024, ORIGIN_Y, 0);
	}

	public static Vec3 world(BlockPos origin, BlockPos rel) {
		return Vec3.atBottomCenterOf(origin.offset(rel));
	}

	// ---------------------------------------------------------------
	// The aura's materials
	// ---------------------------------------------------------------

	private static Map<Block, BlockState> roles(AuraType aura) {
		RealmBuilder.Palette p = RealmBuilder.palette(aura);
		Map<Block, BlockState> m = new HashMap<>();
		m.put(Blocks.LIME_WOOL, accent(aura).defaultBlockState());
		m.put(Blocks.YELLOW_WOOL, p.light().defaultBlockState());
		m.put(Blocks.ORANGE_WOOL, hazard(aura).defaultBlockState());
		m.put(Blocks.LIGHT_BLUE_WOOL, accent2(aura).defaultBlockState());
		m.put(Blocks.WHITE_STAINED_GLASS_PANE, pane(p.gate()).defaultBlockState());
		return m;
	}

	static Block accent(AuraType a) {
		return switch (a) {
			case FIRE -> Blocks.GILDED_BLACKSTONE;
			case WATER -> Blocks.DARK_PRISMARINE;
			case WIND -> Blocks.SMOOTH_QUARTZ;
			case FROST -> Blocks.BLUE_ICE;
			case VERDANT -> Blocks.MOSS_BLOCK;
			case THUNDER -> Blocks.WAXED_CUT_COPPER;
			case EARTH -> Blocks.MUD_BRICKS;
			case LIGHT -> Blocks.GOLD_BLOCK;
			case SHADOW -> Blocks.CRYING_OBSIDIAN;
			case BLOOD -> Blocks.RED_NETHER_BRICKS;
			default -> Blocks.CHISELED_STONE_BRICKS;
		};
	}

	static Block accent2(AuraType a) {
		return switch (a) {
			case FIRE -> Blocks.MAGMA_BLOCK;
			case WATER -> Blocks.PRISMARINE;
			case WIND -> Blocks.CALCITE;
			case FROST -> Blocks.PACKED_ICE;
			case VERDANT -> Blocks.MOSSY_COBBLESTONE;
			case THUNDER -> Blocks.YELLOW_GLAZED_TERRACOTTA;
			case EARTH -> Blocks.PACKED_MUD;
			case LIGHT -> Blocks.QUARTZ_BLOCK;
			case SHADOW -> Blocks.OBSIDIAN;
			case BLOOD -> Blocks.NETHER_WART_BLOCK;
			default -> Blocks.STONE;
		};
	}

	/** The crypt moat (it hurts in the boss fight, M3). */
	static Block hazard(AuraType a) {
		return switch (a) {
			case FIRE -> Blocks.MAGMA_BLOCK;
			case WATER -> Blocks.DARK_PRISMARINE;
			case WIND -> Blocks.CALCITE;
			case FROST -> Blocks.POWDER_SNOW;
			case VERDANT -> Blocks.MOSS_BLOCK;
			case THUNDER -> Blocks.WAXED_OXIDIZED_COPPER;
			case EARTH -> Blocks.MUD;
			case LIGHT -> Blocks.GLOWSTONE;
			case SHADOW -> Blocks.SCULK;
			case BLOOD -> Blocks.CRIMSON_NYLIUM;
			default -> Blocks.MAGMA_BLOCK;
		};
	}

	private static Block pane(Block glass) {
		ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(glass);
		Block pane = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_pane"));
		return pane == Blocks.AIR ? Blocks.GLASS_PANE : pane;
	}

	/** Swaps the role blocks while a tile is placed (keeps a pane's connections). */
	private static final class Remap extends StructureProcessor {
		private final Map<Block, BlockState> map;

		Remap(Map<Block, BlockState> map) {
			this.map = map;
		}

		@Override
		public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos, StructureTemplate.StructureBlockInfo raw,
				StructureTemplate.StructureBlockInfo info, StructurePlaceSettings settings) {
			BlockState to = map.get(info.state().getBlock());
			if (to == null) return info;
			if (to.hasProperty(IronBarsBlock.NORTH) && info.state().hasProperty(IronBarsBlock.NORTH)) {
				to = to.setValue(IronBarsBlock.NORTH, info.state().getValue(IronBarsBlock.NORTH))
						.setValue(IronBarsBlock.EAST, info.state().getValue(IronBarsBlock.EAST))
						.setValue(IronBarsBlock.SOUTH, info.state().getValue(IronBarsBlock.SOUTH))
						.setValue(IronBarsBlock.WEST, info.state().getValue(IronBarsBlock.WEST));
			}
			return new StructureTemplate.StructureBlockInfo(info.pos(), to, info.nbt());
		}

		@Override
		protected StructureProcessorType<?> getType() {
			// never serialised: only used while placing
			return StructureProcessorType.NOP;
		}
	}

	// ---------------------------------------------------------------
	// Building
	// ---------------------------------------------------------------

	/** A monument being placed, one tile per tick. */
	public static final class Job {
		final ServerLevel level;
		final BlockPos origin;
		final AuraType aura;
		private int next;
		private final StructurePlaceSettings settings;

		Job(ServerLevel level, int slot, AuraType aura) {
			this.level = level;
			this.origin = origin(slot);
			this.aura = aura;
			this.settings = new StructurePlaceSettings().setIgnoreEntities(true).addProcessor(new Remap(roles(aura)));
		}

		public boolean done() {
			return next >= anchors().tiles.size();
		}

		/** 0..1 */
		public float progress() {
			return next / (float) Math.max(1, anchors().tiles.size());
		}

		/** Places the next tile. */
		public void step() {
			if (done()) return;
			Tile t = anchors().tiles.get(next++);
			BlockPos at = origin.offset(t.min());
			clearColumns(level, at.getX(), at.getZ(), 32);
			var template = level.getStructureManager().get(ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "reliquary/" + t.name()));
			if (template.isEmpty()) {
				AuraPoints.LOGGER.error("reliquary tile {} missing", t.name());
				return;
			}
			template.get().placeInWorld(level, at, at, settings, RandomSource.create(1L), 2 | 16);
		}

		/** Everything at once (dev tests). */
		public void finish() {
			while (!done()) step();
		}
	}

	public static Job start(ServerLevel level, int slot, AuraType aura) {
		return new Job(level, slot, aura);
	}

	/** Air in the columns x0..x0+size, z0..z0+size (only sections that hold anything are walked). */
	static void clearColumns(ServerLevel level, int x0, int z0, int size) {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int cx = x0 >> 4; cx <= (x0 + size - 1) >> 4; cx++) {
			for (int cz = z0 >> 4; cz <= (z0 + size - 1) >> 4; cz++) {
				LevelChunk chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				for (int si = 0; si < sections.length; si++) {
					if (sections[si].hasOnlyAir()) continue;
					int baseY = level.getSectionYFromSectionIndex(si) << 4;
					for (int dx = 0; dx < 16; dx++) {
						int x = (cx << 4) + dx;
						if (x < x0 || x >= x0 + size) continue;
						for (int dz = 0; dz < 16; dz++) {
							int z = (cz << 4) + dz;
							if (z < z0 || z >= z0 + size) continue;
							for (int dy = 0; dy < 16; dy++) {
								if (sections[si].getBlockState(dx, dy, dz).isAir()) continue;
								level.setBlock(new BlockPos(x, baseY + dy, z), air, 2 | 16);
							}
						}
					}
				}
			}
		}
	}

	/** The pre-5.47 island realm and anything else in the slot outside the monument's tiles. */
	static void clearOld(ServerLevel level, int slot) {
		RealmBuilder.clear(level, slot * 1024);
	}
}
