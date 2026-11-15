package com.toast.aurapoints.arena;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static com.toast.aurapoints.arena.Arena.FLOOR;
import static com.toast.aurapoints.arena.Arena.LOBBY_R;
import static com.toast.aurapoints.arena.Arena.PAD_OFFSET;
import static com.toast.aurapoints.arena.Arena.RING_FLOOR;
import static com.toast.aurapoints.arena.Arena.RING_R;
import static com.toast.aurapoints.arena.Arena.RING_Z;

/**
 * Builds the arena map into the (void) arena dimension, in code, the first time and again whenever
 * MAP_VERSION goes up:
 *  - the lobby, a round platform at 0/0: a ring of aura-colored glass in the floor, lamp posts, the
 *    signs, the Top Duelists board and a spinning aura core with orbiting crystals above the spawn;
 *  - the duel ring to the north: a pit with a blackstone floor in one of the RingLayouts, a ring of
 *    aura-colored glass lights, two pads, four barred wave gates on the diagonals, walled in with
 *    barriers, crystals circling overhead; spectators look down into it from the rim and three rows of
 *    stands, a live board over the south rim; a beacon tower behind it;
 *  - the Training Hall to the south: an enclosed, high dojo in zones - the Control Gallery (kiosks with
 *    clickable hanging signs, TrainingHall), the Dummy Yard, the Moving Target lane, the Spar Pit (the
 *    hall's only PvP) and the Shooting Range (lines to 30, a raised platform, a moving target) - with
 *    the DPS record board by the door and a beacon on its roof;
 *  - floating islets along the way between the islands, the whole map in the aurapoints:arena biome
 *    (dusk sky, violet fog);
 *  - (6) the WAVE RECORDS board on the ring's south rim, next to the ring's arrival spot.
 * Every entity it places (dummies, displays) carries the tag of this build, and ones from an older
 * build are dropped when they load (isStale) - so a rebuild never leaves doubles behind.
 */
public final class ArenaBuilder {

	private ArenaBuilder() {
	}

	/** Raise when the layout changes: every server rebuilds the map on its next start. */
	public static final int MAP_VERSION = 15;

	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	/** Barrier walls and roof of the ring go up to here. */
	private static final int RING_TOP = FLOOR + 22;
	/** The ring's flat rim ends here; the stands' rows rise behind it, then the back wall (BACK_R). */
	private static final int SPECTATOR_R = RING_R + 5, STAND_ROWS = 3, BACK_R = SPECTATOR_R + STAND_ROWS + 1;
	private static final Block[] AURA_BANNERS = {Blocks.PURPLE_WALL_BANNER, Blocks.LIGHT_BLUE_WALL_BANNER, Blocks.ORANGE_WALL_BANNER,
			Blocks.WHITE_WALL_BANNER, Blocks.CYAN_WALL_BANNER, Blocks.LIME_WALL_BANNER, Blocks.YELLOW_WALL_BANNER};

	/** The Training Hall: outer walls at these coordinates (Arena.TRAIN_*), walls up to WALL_TOP, ceiling at CEILING. */
	private static final int HX = Arena.TRAIN_X, HZ0 = Arena.TRAIN_Z0, HZ1 = Arena.TRAIN_Z1;
	private static final int WALL_TOP = FLOOR + 13, CEILING = FLOOR + 14;
	/** The zones, front to back (the Spar Pit, Arena.SPAR_Z, between the lane and the range). */
	private static final int YARD_Z = HZ0 + 14, DUMMY_Z = HZ0 + 22, LANE_Z = HZ0 + 34, RANGE_Z = HZ0 + 54;
	private static final int[] DUMMY_X = {-20, -12, -4, 4, 12, 20};

	/** The NPC arc north of the spawn: its radius, and where each lobby NPC's pedestal is. */
	private static final int NPC_ARC = 14;

	static BlockPos pedestal(int i) {
		double a = Math.toRadians(-64 + i * 32);
		return new BlockPos((int) Math.round(Math.sin(a) * NPC_ARC), FLOOR + 1, (int) Math.round(-Math.cos(a) * NPC_ARC));
	}

	/** At server start: build (or rebuild) if this world's map is older than MAP_VERSION. */
	public static void buildIfNeeded(MinecraftServer server) {
		ServerLevel level = Arena.level(server);
		if (level == null) {
			AuraPoints.LOGGER.warn("The aurapoints:arena dimension isn't loaded - no Aura Arena.");
			return;
		}
		AuraState state = AuraState.get(server);
		if (state.arenaMapVersion >= MAP_VERSION) return;
		build(level);
		state.arenaMapVersion = MAP_VERSION;
		state.markDirty();
	}

	public static void build(ServerLevel level) {
		long start = System.currentTimeMillis();
		AuraState state = AuraState.get(level.getServer());
		state.arenaBuildId++;
		state.markDirty();
		// a clean slate (also clears an older layout - before 4 everything sat within 100 of the spawn),
		// and away with what the last build placed (if loaded)
		clear(level, -100, 100);
		clear(level, RING_Z - BACK_R - 6, RING_Z + BACK_R + 6);
		clear(level, RING_Z + BACK_R + 7, -101);
		clear(level, 101, HZ0 - 9);
		clear(level, HZ0 - 8, HZ1 + 6);
		for (Entity e : level.getEntitiesOfClass(Entity.class, bounds(), ArenaBuilder::isStale)) e.discard();
		rebiome(level);
		buildLobby(level);
		buildAltar(level);
		buildRingFrame(level);
		resetRing(level);
		buildHall(level);
		islets(level);
		lobbyIslets(level);
		spawnDummies(level);
		decorate(level);
		placeNpcs(level);
		TrainingHall.writeBoards(level);
		TrainingHall.writeKiosks(level, 0, 0);
		AuraPoints.LOGGER.info("Built the Aura Arena map (version {}, build {}) in {} ms.", MAP_VERSION, state.arenaBuildId, System.currentTimeMillis() - start);
	}

	/** Everything the map covers (for finding its entities). */
	static AABB bounds() {
		return new AABB(-48, 0, RING_Z - BACK_R - 12, 48, 128, HZ1 + 12);
	}

	/** The map's biome (dusk sky, violet fog): set on every chunk it covers - chunks made before 4.x carry the_void. */
	private static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> BIOME = net.minecraft.resources.ResourceKey.create(
			net.minecraft.core.registries.Registries.BIOME, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("aurapoints", "arena"));

	private static void rebiome(ServerLevel level) {
		var biome = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getHolder(BIOME);
		if (biome.isEmpty()) {
			AuraPoints.LOGGER.warn("The aurapoints:arena biome isn't loaded - the arena keeps its old sky.");
			return;
		}
		net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> holder = biome.get();
		var sampler = level.getChunkSource().randomState().sampler();
		java.util.List<net.minecraft.world.level.chunk.ChunkAccess> chunks = new java.util.ArrayList<>();
		for (int cx = -48 >> 4; cx <= 48 >> 4; cx++) {
			for (int cz = (RING_Z - BACK_R - 12) >> 4; cz <= (HZ1 + 12) >> 4; cz++) {
				net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunk(cx, cz);
				chunk.fillBiomesFromNoise((x, y, z, s) -> holder, sampler);
				chunk.setUnsaved(true);
				chunks.add(chunk);
			}
		}
		level.getChunkSource().chunkMap.resendBiomesForChunks(chunks);
	}

	private static void clear(ServerLevel level, int z0, int z1) {
		// round the lobby out to its islets (x -90..90); elsewhere as far east as the portal island (the Altar Hall clears its own volume)
		boolean lobbyBand = z0 >= -100 && z1 <= 100;
		for (int x = lobbyBand ? -90 : -40; x <= (lobbyBand ? 90 : Arena.ISLAND_X + Arena.ISLAND_R + 2); x++) {
			for (int z = z0; z <= z1; z++) {
				for (int y = FLOOR - 28; y <= RING_TOP + 2; y++) set(level, x, y, z, Blocks.AIR.defaultBlockState());
			}
		}
	}

	/**
	 * The rock under an island, tapering down {@code depth} blocks from just below {@code top}: a
	 * circle ({@code power} 2) or a rounded rectangle (4) with half-sizes rx, rz, in a mix of stones.
	 */
	private static void islandBase(ServerLevel level, int cx, int cz, double rx, double rz, int top, int depth, double power) {
		Block[] rock = {Blocks.STONE, Blocks.ANDESITE, Blocks.TUFF, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.STONE, Blocks.DIORITE};
		for (int i = 0; i < depth; i++) {
			double k = Math.pow((i + 1) / (double) depth, 0.8);
			double ax = rx * (1 - k) + 0.5, az = rz * (1 - k) + 0.5;
			int y = top - 1 - i;
			for (int x = (int) -Math.ceil(ax); x <= Math.ceil(ax); x++) {
				for (int z = (int) -Math.ceil(az); z <= Math.ceil(az); z++) {
					if (Math.pow(Math.abs(x) / ax, power) + Math.pow(Math.abs(z) / az, power) > 1) continue;
					int h = Math.floorMod((cx + x) * 73856093 ^ (cz + z) * 19349663 ^ y * 83492791, 97);
					Block b = i == 0 ? Blocks.DIRT : i < 3 && h % 3 == 0 ? Blocks.DIRT : rock[h % rock.length];
					set(level, cx + x, y, cz + z, b);
				}
			}
		}
	}

	/**
	 * Small floating islets in the void along the way from the lobby to the ring and to the hall, at
	 * different heights (the same scatter on every build): grass and flowers on top, an amethyst cluster
	 * or a lantern on a chain under some.
	 */
	private static void islets(ServerLevel level) {
		net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(0xA0A7L);
		for (int k = 0; k < 16; k++) {
			boolean north = k % 2 == 0;
			// between the lobby (z ±27) and the ring's back wall / the hall's porch, clear of both
			int cz = north ? -40 - random.nextInt(131) : 40 + random.nextInt(146);
			int side = (k / 2) % 2 == 0 ? -1 : 1;
			int r = 2 + random.nextInt(4), depth = 4 + random.nextInt(5);
			int cx = side * (12 + random.nextInt(34 - 12 - r + 1));
			int top = FLOOR - 10 + random.nextInt(25);
			islandBase(level, cx, cz, r, r, top + 1, depth, 2);
			for (int x = -r; x <= r; x++) {
				for (int z = -r; z <= r; z++) {
					if (x * x + z * z > (r + 0.5) * (r + 0.5)) continue;
					if (!level.getBlockState(new BlockPos(cx + x, top, cz + z)).is(Blocks.DIRT)) continue;
					set(level, cx + x, top, cz + z, Blocks.GRASS_BLOCK);
					int h = random.nextInt(9);
					set(level, cx + x, top + 1, cz + z, h == 0 ? Blocks.ALLIUM : h == 1 ? Blocks.CORNFLOWER : h < 4 ? Blocks.SHORT_GRASS : Blocks.AIR);
				}
			}
			if (r >= 4) set(level, cx, top + 1, cz, Blocks.FLOWERING_AZALEA);
			int below = top - depth;
			if (k % 3 == 0) {
				set(level, cx, below, cz, Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(net.minecraft.world.level.block.AmethystClusterBlock.FACING, Direction.DOWN));
			} else if (k % 3 == 1) {
				set(level, cx, below, cz, Blocks.CHAIN);
				set(level, cx, below - 1, cz, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			}
		}
	}

	/**
	 * More islets all round the lobby (5.30, Toast: "spread everywhere, not just towards the hall or the ring"): 26 in a
	 * ring 36..86 blocks out, every direction, clear of the ring and hall walkways (|x| < 14 north and south), the portal
	 * island and its bridge, and each other. Four kinds: grass and flowers, a crystal islet, a small ruin with a broken
	 * column and a lantern, a mossy pond. Same scatter on every build (fixed seed); ArenaBuilder.clear covers the area.
	 */
	private static int lobbyIsletsMade;

	/** Dev test: how many all-round lobby islets the last build placed. */
	public static int lobbyIsletsMade() {
		return lobbyIsletsMade;
	}

	private static void lobbyIslets(ServerLevel level) {
		net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(0x15E7L);
		java.util.List<int[]> placed = new java.util.ArrayList<>();
		int made = 0;
		lobbyIsletsMade = 0;
		for (int tries = 0; made < 26 && tries < 2000; tries++) {
			double a = random.nextDouble() * 2 * Math.PI;
			double dist = 36 + random.nextDouble() * 50;
			int cx = (int) Math.round(Math.cos(a) * dist), cz = (int) Math.round(Math.sin(a) * dist);
			int r = 2 + random.nextInt(4);
			if (Math.abs(cz) > 95 || Math.abs(cx) > 88) continue;
			// the walkways to the ring (north) and the hall (south)
			if (Math.abs(cz) > LOBBY_R && Math.abs(cx) < 14 + r) continue;
			// the corridor islets (islets()) already fill |x| < 40 north and south of the lobby
			if (Math.abs(cz) > 36 && Math.abs(cx) < 40 + r) continue;
			// the portal island and its bridge
			if (cx > LOBBY_R - 4 && cx < Arena.ISLAND_X + Arena.ISLAND_R + 6 + r && Math.abs(cz) < Arena.ISLAND_R + 5 + r) continue;
			boolean free = true;
			for (int[] o : placed) {
				double dx = cx - o[0], dz = cz - o[1];
				if (dx * dx + dz * dz < Math.pow(r + o[2] + 6, 2)) free = false;
			}
			if (!free) continue;
			placed.add(new int[]{cx, cz, r});
			int depth = 4 + random.nextInt(5);
			int top = FLOOR - 14 + random.nextInt(32);
			int kind = made % 4;
			made++;
			lobbyIsletsMade = made;
			islandBase(level, cx, cz, r, r, top + 1, depth, 2);
			for (int x = -r; x <= r; x++) {
				for (int z = -r; z <= r; z++) {
					if (x * x + z * z > (r + 0.5) * (r + 0.5)) continue;
					if (!level.getBlockState(new BlockPos(cx + x, top, cz + z)).is(Blocks.DIRT)) continue;
					int h = random.nextInt(9);
					switch (kind) {
						case 0 -> {
							set(level, cx + x, top, cz + z, Blocks.GRASS_BLOCK);
							set(level, cx + x, top + 1, cz + z, h == 0 ? Blocks.ALLIUM : h == 1 ? Blocks.CORNFLOWER : h == 2 ? Blocks.PINK_PETALS : h < 5 ? Blocks.SHORT_GRASS : Blocks.AIR);
						}
						case 1 -> {
							set(level, cx + x, top, cz + z, h < 3 ? Blocks.AMETHYST_BLOCK : Blocks.CALCITE);
							if (h == 0) set(level, cx + x, top + 1, cz + z, Blocks.AMETHYST_CLUSTER);
							else if (h == 1) set(level, cx + x, top + 1, cz + z, Blocks.MEDIUM_AMETHYST_BUD);
						}
						case 2 -> set(level, cx + x, top, cz + z, h < 3 ? Blocks.CRACKED_STONE_BRICKS : h < 5 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS);
						default -> {
							boolean pond = x * x + z * z <= 1;
							set(level, cx + x, top, cz + z, pond ? Blocks.WATER : Blocks.MOSS_BLOCK);
							if (!pond && h < 2) set(level, cx + x, top + 1, cz + z, Blocks.MOSS_CARPET);
							else if (!pond && h == 2) set(level, cx + x, top + 1, cz + z, Blocks.FERN);
						}
					}
				}
			}
			// what stands on it
			if (kind == 0 && r >= 4) set(level, cx, top + 1, cz, Blocks.FLOWERING_AZALEA);
			if (kind == 1) {
				set(level, cx, top + 1, cz, Blocks.AMETHYST_BLOCK);
				set(level, cx, top + 2, cz, Blocks.AMETHYST_CLUSTER);
			}
			if (kind == 2) {
				int h = 2 + random.nextInt(3);
				for (int y = 1; y <= h; y++) set(level, cx, top + y, cz, y == h ? Blocks.CHISELED_STONE_BRICKS : Blocks.STONE_BRICK_WALL);
				set(level, cx, top + h + 1, cz, Blocks.LANTERN);
				if (r >= 3) set(level, cx + 1, top + 1, cz, Blocks.STONE_BRICK_SLAB);
			}
			// under it: a crystal or a lantern on a chain, now and then
			int below = top - depth;
			if (made % 3 == 0) {
				set(level, cx, below, cz, Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(net.minecraft.world.level.block.AmethystClusterBlock.FACING, Direction.DOWN));
			} else if (made % 3 == 1) {
				set(level, cx, below, cz, Blocks.CHAIN);
				set(level, cx, below - 1, cz, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			}
		}
	}

	// ---------------------------------------------------------------
	// Entities of a build
	// ---------------------------------------------------------------

	private static final String ARENA_TAG = "aurapoints_arena";

	private static String buildTag(MinecraftServer server) {
		return "aurapoints_build_" + AuraState.get(server).arenaBuildId;
	}

	/** Marks an entity as part of the current build. */
	public static void claim(Entity e) {
		e.addTag(ARENA_TAG);
		if (e.getServer() != null) e.addTag(buildTag(e.getServer()));
	}

	/**
	 * Left over from an older build (or a damage number from before a restart): not wanted any more.
	 * Asked when arena entities load (AuraPoints' join hook) and before a rebuild.
	 */
	public static boolean isStale(Entity e) {
		if (e.level().dimension() != Arena.KEY || e.getServer() == null) return false;
		if (e.getTags().contains(TrainingDummy.NUMBER_TAG)) return true;
		if (!(e instanceof TrainingDummy) && !e.getTags().contains(ARENA_TAG)) return false;
		return !e.getTags().contains(buildTag(e.getServer()));
	}

	private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		if (level.getBlockState(pos) != state) level.setBlock(pos, state, FLAGS);
	}

	private static void set(ServerLevel level, int x, int y, int z, Block block) {
		set(level, x, y, z, block.defaultBlockState());
	}

	private static Block auraGlass(int x, int z) {
		double angle = Math.atan2(z, x) + Math.PI;
		return ArenaFx.GLASS[(int) (angle / (2 * Math.PI) * ArenaFx.GLASS.length) % ArenaFx.GLASS.length];
	}

	// ---------------------------------------------------------------
	// Lobby
	// ---------------------------------------------------------------

	/**
	 * The hub: a floating island with a lit plaza - a glass ring and light spokes in the floor, the
	 * NPC arc with its beacon pedestals north of the spawn, seven aura beacons round the edge, a
	 * fountain and the leaderboards to the south, cherry trees, a grassy edge and a lamp-lit rail.
	 */
	private static void buildLobby(ServerLevel level) {
		islandBase(level, 0, 0, LOBBY_R + 1, LOBBY_R + 1, FLOOR, 24, 2);
		for (int x = -LOBBY_R - 1; x <= LOBBY_R + 1; x++) {
			for (int z = -LOBBY_R - 1; z <= LOBBY_R + 1; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > LOBBY_R + 0.5) continue;
				double angle = Math.toDegrees(Math.atan2(z, x));
				boolean spoke = d > 8 && d < 21 && Math.abs(Math.IEEEremainder(angle, 45)) * Math.PI / 180 * d < 0.6;
				Block floor;
				Block below = Blocks.STONE_BRICKS;
				if (d <= 2.5) floor = Blocks.CHISELED_QUARTZ_BLOCK;
				else if (d <= 3.5) floor = Blocks.QUARTZ_BRICKS;
				else if (d > 5.4 && d <= 6.6) {
					floor = auraGlass(x, z);
					below = Blocks.SEA_LANTERN;
				} else if (d > 20.5 && d <= 21.5) floor = Blocks.QUARTZ_BRICKS;
				else if (d > 21.5 && d <= LOBBY_R - 1.5) floor = Blocks.GRASS_BLOCK;
				else if (d > LOBBY_R - 1.5) floor = Blocks.STONE_BRICKS;
				else if (spoke) {
					// light spokes: white glass over sea lanterns
					floor = Blocks.WHITE_STAINED_GLASS;
					below = Blocks.SEA_LANTERN;
				} else floor = (x + z & 1) == 0 ? Blocks.POLISHED_ANDESITE : Blocks.SMOOTH_STONE;
				set(level, x, FLOOR, z, floor);
				set(level, x, FLOOR - 1, z, below);
				// flowers in the grass
				if (floor == Blocks.GRASS_BLOCK) {
					int h = Math.floorMod(x * 31 + z * 17, 11);
					Block flower = h == 0 ? Blocks.POPPY : h == 1 ? Blocks.CORNFLOWER : h == 2 ? Blocks.ALLIUM : h == 3 ? Blocks.OXEYE_DAISY
							: h == 4 ? Blocks.SHORT_GRASS : h == 5 ? Blocks.AZURE_BLUET : Blocks.AIR;
					set(level, x, FLOOR + 1, z, flower);
				}
				// the rail round the edge: quartz slabs, a quartz post with a lantern every few blocks
				if (d > LOBBY_R - 0.5) {
					boolean post = Math.floorMod((int) Math.round(angle), 15) == 0 || Math.abs(Math.IEEEremainder(angle, 15)) < 1.2;
					set(level, x, FLOOR + 1, z, post ? Blocks.QUARTZ_PILLAR : Blocks.SMOOTH_QUARTZ_SLAB);
					if (post) set(level, x, FLOOR + 2, z, Blocks.LANTERN);
				}
			}
		}
		set(level, 0, FLOOR - 1, 0, Blocks.GLOWSTONE);
		// the NPC pedestals: lit quartz with end rods at the corners; behind each (so it doesn't cross their
		// names) a beacon on a hidden iron base with the NPC's glass on it for a colored beam - two for Duels,
		// framing the title
		for (int i = 0; i < NpcKind.LOBBY.length; i++) {
			BlockPos p = pedestal(i);
			set(level, p.getX(), FLOOR, p.getZ(), Blocks.SEA_LANTERN);
			set(level, p.getX(), FLOOR + 1, p.getZ(), Blocks.CHISELED_QUARTZ_BLOCK);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					set(level, p.getX() + dx, FLOOR, p.getZ() + dz, Blocks.QUARTZ_BRICKS);
					if (dx != 0 && dz != 0) set(level, p.getX() + dx, FLOOR + 1, p.getZ() + dz, Blocks.END_ROD);
				}
			}
			double a = Math.toRadians(-64 + i * 32);
			int bx = (int) Math.round(Math.sin(a) * (NPC_ARC + 3)), bz = (int) Math.round(-Math.cos(a) * (NPC_ARC + 3));
			if (NpcKind.LOBBY[i] == NpcKind.DUELS) {
				beacon(level, bx - 5, bz, NpcKind.DUELS.glass);
				beacon(level, bx + 5, bz, NpcKind.DUELS.glass);
			} else {
				beacon(level, bx, bz, NpcKind.LOBBY[i].glass);
			}
		}
		// seven aura beacons round the edge, in the grass
		for (int k = 0; k < 7; k++) {
			double a = Math.toRadians(90 + k * 360.0 / 7);
			beacon(level, (int) Math.round(Math.cos(a) * 23.5), (int) Math.round(Math.sin(a) * 23.5), ArenaFx.GLASS[k]);
		}
		fountain(level, 0, 15);
		cherryTree(level, -15, 9);
		cherryTree(level, 15, 9);
		// lamp posts round the spawn
		for (int sx : new int[]{-4, 4}) for (int sz : new int[]{-4, 4}) {
			set(level, sx, FLOOR + 1, sz, Blocks.QUARTZ_PILLAR);
			set(level, sx, FLOOR + 2, sz, Blocks.QUARTZ_PILLAR);
			set(level, sx, FLOOR + 3, sz, Blocks.END_ROD);
		}
		LobbyDecor.build(level); // the paved wheel, lamp avenue and planter beds (only on the plain checker floor)
	}

	/** A beacon in the floor at (x, z) on a 3x3 iron base below, with {@code glass} on it (its beam's color). */
	private static void beacon(ServerLevel level, int x, int z, Block glass) {
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(level, x + dx, FLOOR - 1, z + dz, Blocks.IRON_BLOCK);
		set(level, x, FLOOR, z, Blocks.BEACON);
		set(level, x, FLOOR + 1, z, glass);
	}

	/** A round basin of still water with a lit quartz column in the middle (ArenaFx sprays it). */
	private static void fountain(ServerLevel level, int cx, int cz) {
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > 4.3) continue;
				if (d > 3.3) {
					set(level, cx + x, FLOOR, cz + z, Blocks.POLISHED_DEEPSLATE);
					set(level, cx + x, FLOOR + 1, cz + z, Blocks.CHISELED_QUARTZ_BLOCK);
				} else {
					set(level, cx + x, FLOOR - 1, cz + z, Blocks.SEA_LANTERN);
					set(level, cx + x, FLOOR, cz + z, Blocks.PRISMARINE_BRICKS);
					set(level, cx + x, FLOOR + 1, cz + z, d <= 0.5 ? Blocks.QUARTZ_PILLAR : Blocks.WATER);
				}
			}
		}
		set(level, cx, FLOOR + 2, cz, Blocks.QUARTZ_PILLAR);
		set(level, cx, FLOOR + 3, cz, Blocks.SEA_LANTERN);
	}

	/** A small cherry tree on a patch of grass. */
	private static void cherryTree(ServerLevel level, int cx, int cz) {
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
			if (Math.abs(x) + Math.abs(z) > 3) continue;
			set(level, cx + x, FLOOR, cz + z, Blocks.GRASS_BLOCK);
			if ((x + z & 1) == 0 && (x != 0 || z != 0)) set(level, cx + x, FLOOR + 1, cz + z, Blocks.PINK_PETALS);
		}
		for (int y = 1; y <= 4; y++) set(level, cx, FLOOR + y, cz, Blocks.CHERRY_LOG);
		BlockState leaves = Blocks.CHERRY_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
		for (int x = -3; x <= 3; x++) for (int y = 0; y <= 3; y++) for (int z = -3; z <= 3; z++) {
			double d = Math.sqrt(x * x + (y - 1) * (y - 1) * 2.0 + z * z);
			if (d > 3.3 || (x == 0 && z == 0 && y < 1)) continue;
			set(level, cx + x, FLOOR + 4 + y, cz + z, leaves);
		}
	}

	/** The lobby's NPCs (and their names in the air), the ones on the other islands, the hub's title and leaderboards. */
	private static void placeNpcs(ServerLevel level) {
		for (int i = 0; i < NpcKind.LOBBY.length; i++) {
			BlockPos p = pedestal(i);
			// facing the spawn
			float yaw = (float) Math.toDegrees(Math.atan2(-p.getZ(), -p.getX())) - 90f;
			npc(level, NpcKind.LOBBY[i], new Vec3(p.getX() + 0.5, FLOOR + 2, p.getZ() + 0.5), yaw);
		}
		npc(level, NpcKind.RING_LOBBY, new Vec3(3.5, FLOOR + 1, RING_Z + RING_R + 3.5), 180f);
		npc(level, NpcKind.HALL_LOBBY, new Vec3(4.5, FLOOR + 1, HZ0 + 2.5), 0f);
		// the keeper waits in the Altar Hall, by the door (north side: the ritual camera swings round the south)
		npc(level, NpcKind.ALTAR, new Vec3(Arena.ALTAR_X - Arena.ROOM_R + 4.5, FLOOR + 1, -3.5), 90f);
		Holograms.spawn(level, new Vec3(Arena.ISLAND_X + 0.5, FLOOR + Arena.PORTAL_H + 5.5, 0.5), Component.literal("✦ AWAKENING ALTAR ✦")
				.withStyle(s -> s.withColor(0xFFC83D).withBold(true)), 2.2f, 0, null);
		// the weekly challenges, across the hall's entrance from its NPC
		Holograms.spawn(level, new Vec3(-4.5, FLOOR + 2.4, HZ0 + 2.5), Component.literal("✦ WEEKLY CHALLENGES ✦"), 0.9f, 0x90000000, "board_weekly");
		Holograms.spawn(level, new Vec3(0.5, FLOOR + 10.5, -NPC_ARC - 1.5), Component.literal("✦ AURA ARENA ✦")
				.withStyle(s -> s.withColor(0xFFC83D).withBold(true)), 3.5f, 0, null);
		Holograms.spawn(level, new Vec3(0.5, FLOOR + 9.3, -NPC_ARC - 1.5), Component.literal("Click an NPC to play!")
				.withStyle(net.minecraft.ChatFormatting.YELLOW), 1.6f, 0, null);
		Holograms.spawn(level, new Vec3(-10.5, FLOOR + 3, 13.5), Component.literal("⚔ TOP DUELISTS"), 1.1f, 0x90000000, "board_duels");
		Holograms.spawn(level, new Vec3(11.5, FLOOR + 3, 13.5), Component.literal("★ DPS RECORDS"), 1.1f, 0x90000000, "board_dps");
		Holograms.spawn(level, new Vec3(-3.5, FLOOR + 3, RING_Z + RING_R + 4.5), Component.literal("☠ WAVE RECORDS"), 1.1f, 0x90000000, "board_waves");
		// what's on in the ring, over its south rim (Holograms.ringBoard)
		Holograms.spawn(level, new Vec3(0.5, FLOOR + 7, RING_Z + RING_R + 2), Component.literal("⚔ THE RING"), 1.4f, 0x90000000, "ring_live");
	}

	private static void npc(ServerLevel level, NpcKind kind, Vec3 at, float yaw) {
		ArenaNpc.spawn(level, kind, at, yaw);
		Holograms.spawn(level, at.add(0, 2.45, 0), kind.title(), 1f, 0, null);
		Holograms.spawn(level, at.add(0, 2.15, 0), Component.literal(""), 0.9f, 0, "live_" + kind.name().toLowerCase(java.util.Locale.ROOT));
	}

	// ---------------------------------------------------------------
	// The duel ring
	// ---------------------------------------------------------------

	/**
	 * Everything but the pit's inside: the wall with its four wave gates, the spectators' rim, the stands
	 * behind it (stairs facing in, a back wall with a band of aura glass and lamps), the barrier cage and
	 * the beacon tower to the north.
	 */
	private static void buildRingFrame(ServerLevel level) {
		for (int x = -BACK_R - 1; x <= BACK_R + 1; x++) {
			for (int dz = -BACK_R - 1; dz <= BACK_R + 1; dz++) {
				double d = Math.sqrt(x * x + dz * dz);
				int z = RING_Z + dz;
				if (d > RING_R + 0.5 && d <= RING_R + 1.5) {
					// the pit wall, its top level with the rim
					for (int y = RING_FLOOR - 1; y <= FLOOR; y++) set(level, x, y, z, y == FLOOR ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
					for (int y = FLOOR + 1; y <= RING_TOP; y++) set(level, x, y, z, Blocks.BARRIER);
				} else if (d > RING_R + 1.5 && d <= BACK_R + 0.5) {
					// the rim and the stands, down to the island's rock under the pit
					for (int y = RING_FLOOR - 1; y < FLOOR - 1; y++) set(level, x, y, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					set(level, x, FLOOR - 1, z, Blocks.STONE_BRICKS);
					int row = (int) Math.ceil(d - SPECTATOR_R - 0.5);
					if (row <= 0) {
						set(level, x, FLOOR, z, (x + dz & 1) == 0 ? Blocks.POLISHED_ANDESITE : Blocks.SMOOTH_STONE);
					} else if (row <= STAND_ROWS) {
						// a row of seats: stairs facing the pit, on stone
						set(level, x, FLOOR, z, Blocks.STONE_BRICKS);
						for (int y = FLOOR + 1; y < FLOOR + row; y++) set(level, x, y, z, Blocks.STONE_BRICKS);
						set(level, x, FLOOR + row, z, Blocks.STONE_BRICK_STAIRS.defaultBlockState()
								.setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.getNearest(x, 0, dz)));
					} else {
						// the back wall: stone, a band of aura glass, battlements
						for (int y = FLOOR; y <= FLOOR + STAND_ROWS + 1; y++) set(level, x, y, z, Blocks.STONE_BRICKS);
						set(level, x, FLOOR + STAND_ROWS + 2, z, auraGlass(x, dz));
						set(level, x, FLOOR + STAND_ROWS + 3, z, Blocks.STONE_BRICKS);
						if ((x + dz & 1) == 0) set(level, x, FLOOR + STAND_ROWS + 4, z, Blocks.STONE_BRICK_SLAB);
					}
				}
				if (d <= RING_R + 1.5) set(level, x, RING_TOP, z, Blocks.BARRIER);
			}
		}
		// lanterns on the back wall, every 30 degrees
		for (int k = 0; k < 12; k++) {
			double a = k * Math.PI / 6 + Math.PI / 12;
			int x = (int) Math.round(Math.cos(a) * BACK_R), dz = (int) Math.round(Math.sin(a) * BACK_R);
			set(level, x, FLOOR + STAND_ROWS + 4, RING_Z + dz, Blocks.LANTERN);
		}
		for (int i = 0; i < 4; i++) waveGate(level, i);
		// the ring's island: rock under the rim
		islandBase(level, 0, RING_Z, BACK_R + 1, BACK_R + 1, RING_FLOOR - 1, 16, 2);
		beaconTower(level, 0, RING_Z - BACK_R - 3);
		RingDecor.build(level);
	}

	/**
	 * Wave gate {@code i} (Waves.gate): a tunnel 3 wide and 2 high into the pit wall under the rim, dark
	 * with a soul lantern at its back, barred (setGates opens it while a wave comes in), two soul-lit posts
	 * on the rim above.
	 */
	private static void waveGate(ServerLevel level, int i) {
		double a = Waves.gateAngle(i), c = Math.cos(a), sn = Math.sin(a);
		for (int x = -BACK_R; x <= BACK_R; x++) {
			for (int dz = -BACK_R; dz <= BACK_R; dz++) {
				double along = x * c + dz * sn, across = -x * sn + dz * c;
				if (Math.abs(across) > 1.6 || along <= RING_R + 0.5 || along > RING_R + 4.5) continue;
				int z = RING_Z + dz;
				set(level, x, RING_FLOOR, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				for (int y = RING_FLOOR + 1; y <= RING_FLOOR + 2; y++) set(level, x, y, z, Blocks.AIR);
				set(level, x, RING_FLOOR + 3, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
		}
		set(level, (int) Math.round(c * (RING_R + 4)), RING_FLOOR + 1, RING_Z + (int) Math.round(sn * (RING_R + 4)), Blocks.SOUL_LANTERN);
		for (int side : new int[]{-1, 1}) {
			double r = RING_R + 2.5;
			int x = (int) Math.round(c * r - sn * side * 2.6), dz = (int) Math.round(sn * r + c * side * 2.6);
			set(level, x, FLOOR + 1, RING_Z + dz, Blocks.POLISHED_BLACKSTONE_WALL);
			set(level, x, FLOOR + 2, RING_Z + dz, Blocks.POLISHED_BLACKSTONE_WALL);
			set(level, x, FLOOR + 3, RING_Z + dz, Blocks.SOUL_LANTERN);
		}
		setGate(level, i, true);
	}

	/** The bars in gate {@code i}'s mouth (the pit wall's band). */
	private static java.util.List<BlockPos> gateBars(int i) {
		double a = Waves.gateAngle(i), c = Math.cos(a), sn = Math.sin(a);
		java.util.List<BlockPos> out = new java.util.ArrayList<>();
		for (int x = -RING_R - 2; x <= RING_R + 2; x++) {
			for (int dz = -RING_R - 2; dz <= RING_R + 2; dz++) {
				double d = Math.sqrt(x * x + dz * dz), along = x * c + dz * sn, across = -x * sn + dz * c;
				if (Math.abs(across) > 1.6 || along <= 0 || d <= RING_R + 0.5 || d > RING_R + 1.5) continue;
				for (int y = RING_FLOOR + 1; y <= RING_FLOOR + 2; y++) out.add(new BlockPos(x, y, RING_Z + dz));
			}
		}
		return out;
	}

	private static void setGate(ServerLevel level, int i, boolean closed) {
		BlockState state = closed ? Blocks.IRON_BARS.defaultBlockState() : Blocks.AIR.defaultBlockState();
		for (BlockPos pos : gateBars(i)) {
			if (level.isLoaded(pos) && !level.getBlockState(pos).is(state.getBlock())) level.setBlock(pos, state, Block.UPDATE_ALL);
		}
	}

	/** The wave gates barred (between waves, and outside Wave Survival) or open (a wave coming in). */
	static void setGates(ServerLevel level, boolean closed) {
		for (int i = 0; i < 4; i++) setGate(level, i, closed);
	}

	/** A stone tower out over the void with a beacon on top (a gold beam to find the island by). */
	private static void beaconTower(ServerLevel level, int cx, int cz) {
		islandBase(level, cx, cz, 3, 3, FLOOR - 6, 6, 2);
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				boolean corner = Math.abs(x) == 2 && Math.abs(z) == 2;
				for (int y = FLOOR - 6; y <= FLOOR + 5; y++) set(level, cx + x, y, cz + z, corner ? Blocks.POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(level, cx + x, FLOOR + 6, cz + z, Math.abs(x) <= 1 && Math.abs(z) <= 1 ? Blocks.GOLD_BLOCK : Blocks.CHISELED_POLISHED_BLACKSTONE);
				if (corner) set(level, cx + x, FLOOR + 7, cz + z, Blocks.SOUL_LANTERN);
			}
		}
		set(level, cx, FLOOR + 7, cz, Blocks.BEACON);
		set(level, cx, FLOOR + 8, cz, Blocks.YELLOW_STAINED_GLASS);
	}

	/**
	 * The pit's inside, as new, in the current RingLayout: floor, glass ring, the layout's own blocks (for
	 * PILLARS the diagonal set up), pads, air above, the wave gates barred - and no leftover items or
	 * arrows. Before every duel round (only blocks that changed are set again).
	 */
	public static void resetRing(ServerLevel level) {
		RingLayout layout = RingLayout.current;
		for (int x = -RING_R; x <= RING_R; x++) {
			for (int dz = -RING_R; dz <= RING_R; dz++) {
				double d = Math.sqrt(x * x + dz * dz);
				if (d > RING_R + 0.5) continue;
				int z = RING_Z + dz;
				set(level, x, RING_FLOOR - 1, z, ArenaFx.isRingGlass(d) ? Blocks.SEA_LANTERN : Blocks.BLACKSTONE);
				set(level, x, RING_FLOOR, z, ringFloor(layout, x, dz, d));
				boolean pillar = layout.hasPillars() && RingPillars.standsAtStart(x, dz);
				for (int y = RING_FLOOR + 1; y < RING_TOP; y++) {
					int h = y - RING_FLOOR;
					BlockState block = pillar && h <= RingPillars.HEIGHT ? RingPillars.block(x, dz, h) : layout.above(x, dz, d, h);
					set(level, x, y, z, block == null ? Blocks.AIR.defaultBlockState() : block);
				}
			}
		}
		setGates(level, true);
		AABB pit = new AABB(-RING_R - 1, RING_FLOOR - 2, RING_Z - RING_R - 1, RING_R + 2, RING_TOP, RING_Z + RING_R + 2);
		for (Entity e : level.getEntitiesOfClass(Entity.class, pit, e -> e instanceof ItemEntity || e instanceof Projectile)) e.discard();
	}

	private static BlockState ringFloor(RingLayout layout, int x, int dz, double d) {
		// the pads: red to the north, blue to the south
		if (Math.abs(x) <= 1 && Math.abs(dz + PAD_OFFSET) <= 1) return (x == 0 && dz == -PAD_OFFSET ? Blocks.RED_GLAZED_TERRACOTTA : Blocks.RED_CONCRETE).defaultBlockState();
		if (Math.abs(x) <= 1 && Math.abs(dz - PAD_OFFSET) <= 1) return (x == 0 && dz == PAD_OFFSET ? Blocks.BLUE_GLAZED_TERRACOTTA : Blocks.BLUE_CONCRETE).defaultBlockState();
		if (d <= 2.5) return Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
		if (ArenaFx.isRingGlass(d)) return auraGlass(x, dz).defaultBlockState();
		BlockState own = layout.floor(x, dz, d);
		if (own != null) return own;
		if (Math.abs(d - 7) < 0.6 || Math.abs(d - 17) < 0.6) return Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
		if (layout.hasPillars() && RingPillars.isPillar(x, dz)) return Blocks.GILDED_BLACKSTONE.defaultBlockState();
		return RingDecor.floor(x, dz);
	}

	// ---------------------------------------------------------------
	// The Training Hall
	// ---------------------------------------------------------------

	private static void buildHall(ServerLevel level) {
		islandBase(level, 0, (HZ0 + HZ1) / 2, HX + 2, (HZ1 - HZ0) / 2.0 + 2, FLOOR - 1, 14, 4);
		// a porch in front of the door
		for (int x = -5; x <= 5; x++) for (int z = HZ0 - 4; z < HZ0; z++) {
			set(level, x, FLOOR - 1, z, Blocks.STONE_BRICKS);
			set(level, x, FLOOR, z, Math.abs(x) <= 1 ? Blocks.POLISHED_DIORITE : Blocks.POLISHED_ANDESITE);
			if (Math.abs(x) == 5 || z == HZ0 - 4) set(level, x, FLOOR + 1, z, (x + z & 1) == 0 ? Blocks.STONE_BRICK_WALL : Blocks.STONE_BRICK_SLAB);
		}
		for (int x : new int[]{-5, 5}) set(level, x, FLOOR + 2, HZ0 - 4, Blocks.LANTERN);
		hallShell(level);
		hallFloor(level);
		controlGallery(level);
		dummyYard(level);
		movingLane(level);
		sparPit(level);
		shootingRange(level);
		hallDetails(level);
		// a beacon on the roof over the door (a gold beam to find the hall by)
		for (int x = -1; x <= 1; x++) for (int z = HZ0 + 1; z <= HZ0 + 3; z++) set(level, x, CEILING, z, Blocks.GOLD_BLOCK);
		set(level, 0, CEILING + 1, HZ0 + 2, Blocks.BEACON);
		set(level, 0, CEILING + 2, HZ0 + 2, Blocks.YELLOW_STAINED_GLASS);
	}

	/** Walls (dark oak pillars, calcite panels with two rows of windows, a log beam), the door, the ceiling with its skylight, battlements. */
	private static void hallShell(ServerLevel level) {
		for (int x = -HX; x <= HX; x++) {
			for (int z = HZ0; z <= HZ1; z++) {
				boolean side = x == -HX || x == HX, end = z == HZ0 || z == HZ1;
				set(level, x, FLOOR - 1, z, Blocks.STONE_BRICKS);
				boolean skylight = Math.abs(x) <= 4 && z >= HZ0 + 5 && z <= HZ1 - 5;
				set(level, x, CEILING, z, skylight ? Blocks.GLASS : Blocks.DARK_OAK_PLANKS);
				if ((side || end) && (x + z & 1) == 0) set(level, x, CEILING + 1, z, Blocks.STONE_BRICK_WALL);
				if (!side && !end) continue;
				set(level, x, FLOOR, z, Blocks.STONE_BRICKS);
				boolean pillar = side ? (z - HZ0) % 6 == 0 || z == HZ1 : (x + HX) % 6 == 0;
				int along = side ? z - HZ0 : x + HX;
				for (int y = FLOOR + 1; y <= WALL_TOP; y++) {
					BlockState block;
					if (pillar) block = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState();
					else if (y == WALL_TOP) block = Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, side ? Direction.Axis.Z : Direction.Axis.X);
					else if (y == FLOOR + 1) block = Blocks.STONE_BRICKS.defaultBlockState();
					else if (window(x, z, along, y)) block = Blocks.GLASS.defaultBlockState();
					else block = Blocks.CALCITE.defaultBlockState();
					set(level, x, y, z, block);
				}
			}
		}
		// the door: 5 wide, 4 high between two pillars, a chiseled lintel, torches, the name above
		for (int x = -2; x <= 2; x++) for (int y = FLOOR + 1; y <= FLOOR + 4; y++) set(level, x, y, HZ0, Blocks.AIR);
		for (int x = -3; x <= 3; x++) set(level, x, FLOOR + 5, HZ0, Blocks.CHISELED_STONE_BRICKS);
		for (int x : new int[]{-3, 3}) set(level, x, FLOOR + 4, HZ0 - 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH));
		set(level, 0, FLOOR + 6, HZ0 - 1, Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.NORTH));
		writeSign(level, new BlockPos(0, FLOOR + 6, HZ0 - 1), "", "TRAINING HALL", "", "");
	}

	/** Two rows of two-high windows in the middle of each wall panel - not by the board and racks at the door, nor in the back wall (targets). */
	private static boolean window(int x, int z, int along, int y) {
		if (y != FLOOR + 3 && y != FLOOR + 4 && y != FLOOR + 8 && y != FLOOR + 9) return false;
		int in = along % 6;
		if (in < 2 || in > 4) return false;
		if (z == HZ1) return false;
		if (z == HZ0) return Math.abs(x) >= 6;
		return z >= HZ0 + 6;
	}

	private static void hallFloor(ServerLevel level) {
		for (int x = -HX + 1; x <= HX - 1; x++) {
			for (int z = HZ0 + 1; z <= HZ1 - 1; z++) {
				Block floor;
				boolean border = Math.abs(x) == HX - 1 || z == HZ0 + 1 || z == HZ1 - 1;
				boolean aisle = Math.abs(x) <= 1;
				if (border) floor = Blocks.DARK_OAK_PLANKS;
				else if (z < YARD_Z) floor = aisle ? Blocks.RED_WOOL : Math.abs(x) == 2 ? Blocks.POLISHED_BLACKSTONE_BRICKS : Blocks.POLISHED_ANDESITE;
				else if (z >= DUMMY_Z - 3 && z <= DUMMY_Z + 3 && nearDummy(x)) floor = Blocks.COARSE_DIRT;
				else if (z == LANE_Z) floor = Blocks.GRAVEL;
				else if (z == LANE_Z - 1 || z == LANE_Z + 1) floor = Blocks.STONE_BRICKS;
				else if (z >= RANGE_Z) {
					int line = z - RANGE_Z;
					floor = line == 1 ? Blocks.WHITE_CONCRETE : line == 11 ? Blocks.YELLOW_CONCRETE : line == 21 ? Blocks.ORANGE_CONCRETE
							: line == 31 ? Blocks.RED_CONCRETE : Blocks.SMOOTH_STONE;
				} else floor = aisle ? Blocks.STRIPPED_SPRUCE_WOOD : Blocks.SPRUCE_PLANKS;
				set(level, x, FLOOR, z, floor);
				for (int y = FLOOR + 1; y < CEILING; y++) set(level, x, y, z, Blocks.AIR);
			}
		}
	}

	private static boolean nearDummy(int x) {
		for (int dx : DUMMY_X) if (Math.abs(x - dx) <= 2) return true;
		return false;
	}

	/** A zone's name on a hanging sign, on chains from the ceiling over the aisle, facing the door. */
	private static void zoneSign(ServerLevel level, int z, String... lines) {
		for (int y = FLOOR + 6; y < CEILING; y++) set(level, 0, y, z, Blocks.CHAIN);
		set(level, 0, FLOOR + 5, z, Blocks.DARK_OAK_HANGING_SIGN.defaultBlockState().setValue(CeilingHangingSignBlock.ROTATION, 8));
		writeSign(level, new BlockPos(0, FLOOR + 5, z), lines);
	}

	/** The kiosks: a beam on two posts, a hanging sign under it for each control, a pedestal below with the item over it. */
	private static void controlGallery(ServerLevel level) {
		int kz = TrainingHall.KIOSK_Z;
		for (int x = -24; x <= 24; x++) {
			set(level, x, FLOOR + 5, kz, Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
		}
		for (int x : new int[]{-25, 25}) {
			for (int y = FLOOR + 1; y <= FLOOR + 5; y++) set(level, x, y, kz, Blocks.STRIPPED_DARK_OAK_LOG);
			set(level, x, FLOOR + 6, kz, Blocks.LANTERN);
		}
		for (TrainingHall.Control control : TrainingHall.Control.values()) {
			BlockPos sign = control.sign(), pedestal = control.pedestal();
			set(level, sign.getX(), sign.getY(), sign.getZ(), Blocks.DARK_OAK_HANGING_SIGN.defaultBlockState()
					.setValue(CeilingHangingSignBlock.ROTATION, 8).setValue(CeilingHangingSignBlock.ATTACHED, true));
			set(level, pedestal.getX(), pedestal.getY(), pedestal.getZ(), Blocks.CHISELED_POLISHED_BLACKSTONE);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx != 0 || dz != 0) set(level, pedestal.getX() + dx, FLOOR, pedestal.getZ() + dz, Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
			}
			set(level, pedestal.getX(), FLOOR, pedestal.getZ(), Blocks.SEA_LANTERN);
		}
		// the gallery's own name, in the middle of the beam
		set(level, 0, FLOOR + 4, kz, Blocks.DARK_OAK_HANGING_SIGN.defaultBlockState()
				.setValue(CeilingHangingSignBlock.ROTATION, 8).setValue(CeilingHangingSignBlock.ATTACHED, true));
		writeSign(level, new BlockPos(0, FLOOR + 4, kz), "CONTROL", "GALLERY", "click a sign", "");
		// the DPS record board on the west wall by the door, on a dark oak backing
		for (int z = TrainingHall.BOARD_1.getZ() - 1; z <= TrainingHall.BOARD_2.getZ() + 1; z++) {
			for (int y = FLOOR + 2; y <= FLOOR + 4; y++) set(level, -HX, y, z, Blocks.DARK_OAK_PLANKS);
		}
		for (BlockPos board : new BlockPos[]{TrainingHall.BOARD_1, TrainingHall.BOARD_2}) {
			set(level, board.getX(), board.getY(), board.getZ(), Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST));
		}
	}

	/** Six stalls: a hay bale for each dummy, log rails between them with lantern posts. */
	private static void dummyYard(ServerLevel level) {
		zoneSign(level, YARD_Z, "⚔", "DUMMY YARD", "hit away!", "");
		for (int x : DUMMY_X) set(level, x, FLOOR, DUMMY_Z, Blocks.HAY_BLOCK);
		for (int x : new int[]{-16, -8, 0, 8, 16}) {
			for (int z = DUMMY_Z - 4; z <= DUMMY_Z + 4; z++) {
				boolean end = z == DUMMY_Z - 4 || z == DUMMY_Z + 4;
				set(level, x, FLOOR + 1, z, end ? Blocks.SPRUCE_FENCE.defaultBlockState()
						: Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
				if (end) set(level, x, FLOOR + 2, z, Blocks.LANTERN);
			}
		}
	}

	private static void movingLane(ServerLevel level) {
		zoneSign(level, LANE_Z - 3, "➜", "MOVING TARGET", "speed: at the", "gallery");
		int reach = (int) TrainingDummy.PATROL_RANGE + 2;
		for (int x = -reach; x <= reach; x++) set(level, x, FLOOR + 1, LANE_Z, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST));
		for (int x : new int[]{-reach - 1, reach + 1}) {
			set(level, x, FLOOR + 1, LANE_Z, Blocks.HAY_BLOCK);
			set(level, x, FLOOR + 2, LANE_Z, Blocks.TARGET);
		}
	}

	/**
	 * The Spar Pit: a sunken circle of sandstone, a low rail round it with a red and a blue way in. The
	 * only place in the hall where players can hit each other (Arena.inSparPit).
	 */
	private static void sparPit(ServerLevel level) {
		int cz = Arena.SPAR_Z, r = Arena.SPAR_R;
		zoneSign(level, cz - r - 3, "⚔", "SPAR PIT", "PvP inside", "the rail only");
		for (int x = -r - 1; x <= r + 1; x++) {
			for (int dz = -r - 1; dz <= r + 1; dz++) {
				double d = Math.sqrt(x * x + dz * dz);
				int z = cz + dz;
				if (d <= r + 0.5) {
					set(level, x, FLOOR - 1, z, d <= 1.5 ? Blocks.CHISELED_SANDSTONE : (x + dz & 1) == 0 ? Blocks.SMOOTH_SANDSTONE : Blocks.CUT_SANDSTONE);
					set(level, x, FLOOR, z, Blocks.AIR);
				} else if (d <= r + 1.5) {
					boolean way = Math.abs(x) <= 1;
					set(level, x, FLOOR, z, way ? (dz < 0 ? Blocks.RED_CONCRETE : Blocks.BLUE_CONCRETE) : Blocks.POLISHED_BLACKSTONE_BRICKS);
					if (!way) set(level, x, FLOOR + 1, z, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB);
				}
			}
		}
		// lamps on the diagonals
		for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
			int x = (int) Math.round(sx * (r + 1) * 0.7071), z = cz + (int) Math.round(sz * (r + 1) * 0.7071);
			set(level, x, FLOOR + 1, z, Blocks.POLISHED_BLACKSTONE_WALL);
			set(level, x, FLOOR + 2, z, Blocks.LANTERN);
		}
	}

	private static void shootingRange(ServerLevel level) {
		zoneSign(level, RANGE_Z - 1, "➶", "SHOOTING RANGE", "lines every", "10 blocks");
		int[][] wallTargets = {{-20, 2}, {-12, 4}, {-5, 1}, {0, 3}, {5, 1}, {12, 4}, {20, 2}, {-16, 6}, {16, 6}, {-8, 9}, {8, 9}, {0, 11}};
		for (int[] t : wallTargets) set(level, t[0], FLOOR + 1 + t[1], HZ1, Blocks.TARGET);
		int[][] standTargets = {{-14, RANGE_Z + 6}, {14, RANGE_Z + 10}, {-6, RANGE_Z + 20}, {7, RANGE_Z + 24}, {-20, RANGE_Z + 24},
				{20, RANGE_Z + 8}, {-10, RANGE_Z + 30}, {12, RANGE_Z + 33}, {0, RANGE_Z + 35}};
		for (int[] t : standTargets) {
			set(level, t[0], FLOOR + 1, t[1], Blocks.HAY_BLOCK);
			set(level, t[0], FLOOR + 2, t[1], Blocks.HAY_BLOCK);
			set(level, t[0], FLOOR + 3, t[1], Blocks.TARGET);
		}
		// distance marks at the side of each line
		standingSign(level, -HX + 2, RANGE_Z + 1, 8, "", "shooting line", "", "");
		standingSign(level, -HX + 2, RANGE_Z + 11, 8, "", "10 blocks", "", "");
		standingSign(level, -HX + 2, RANGE_Z + 21, 8, "", "20 blocks", "", "");
		standingSign(level, -HX + 2, RANGE_Z + 31, 8, "", "30 blocks", "", "");
		// a moving target across the range, on its own rail (its dummy: spawnDummies)
		int reach = (int) TrainingDummy.PATROL_RANGE + 2;
		for (int x = -reach; x <= reach; x++) set(level, x, FLOOR + 1, RANGE_Z + 16, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST));
		for (int x : new int[]{-reach - 1, reach + 1}) set(level, x, FLOOR + 1, RANGE_Z + 16, Blocks.HAY_BLOCK);
		// the raised platform at the line, east side: three steps up, a rail on top
		int pz0 = RANGE_Z + 1, pz1 = RANGE_Z + 4;
		for (int z = pz0; z <= pz1; z++) {
			for (int x = 15; x <= 24; x++) {
				int top = x >= 18 ? 3 : x - 14;
				for (int y = 1; y < top; y++) set(level, x, FLOOR + y, z, Blocks.SPRUCE_PLANKS);
				set(level, x, FLOOR + top, z, x >= 18 ? Blocks.SPRUCE_PLANKS.defaultBlockState()
						: Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.EAST));
				if (x >= 18 && (z == pz0 || z == pz1 || x == 24)) set(level, x, FLOOR + 4, z, Blocks.SPRUCE_SLAB);
			}
		}
	}

	/** Lanterns on chains, banners on the pillars, azalea in the corners. */
	private static void hallDetails(ServerLevel level) {
		for (int x : new int[]{-18, -9, 9, 18}) {
			for (int z = HZ0 + 6; z < HZ1 - 2; z += 10) {
				for (int y = CEILING - 4; y < CEILING; y++) set(level, x, y, z, Blocks.CHAIN);
				set(level, x, CEILING - 5, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			}
		}
		int i = 0;
		for (int z = HZ0 + 6; z < HZ1; z += 6) {
			set(level, -HX + 1, FLOOR + 11, z, AURA_BANNERS[i++ % AURA_BANNERS.length].defaultBlockState().setValue(WallBannerBlock.FACING, Direction.EAST));
			set(level, HX - 1, FLOOR + 11, z, AURA_BANNERS[i++ % AURA_BANNERS.length].defaultBlockState().setValue(WallBannerBlock.FACING, Direction.WEST));
		}
		for (int[] c : new int[][]{{-HX + 1, HZ1 - 1}, {HX - 1, HZ1 - 1}, {HX - 1, HZ0 + 1}, {-HX + 1, HZ0 + 1}}) {
			set(level, c[0], FLOOR, c[1], Blocks.MOSS_BLOCK);
			set(level, c[0], FLOOR + 1, c[1], Blocks.FLOWERING_AZALEA);
		}
	}

	private static void spawnDummies(ServerLevel level) {
		for (int x : DUMMY_X) {
			// facing north, toward the door
			TrainingDummy.spawn(level, AuraPoints.TRAINING_DUMMY.get(), new Vec3(x + 0.5, FLOOR + 1, DUMMY_Z + 0.5), 180f);
		}
		TrainingDummy.spawn(level, AuraPoints.TRAINING_DUMMY.get(), new Vec3(0.5, FLOOR + 1, LANE_Z + 0.5), 180f, true);
		TrainingDummy.spawn(level, AuraPoints.TRAINING_DUMMY.get(), new Vec3(0.5, FLOOR + 1, RANGE_Z + 16.5), 180f, true);
	}

	// ---------------------------------------------------------------
	// Displays: the lobby's core, crystals, the kiosks' items, weapon racks (nothing to take)
	// ---------------------------------------------------------------

	/** Lantern posts on a rim of radius {@code r}: the four diagonals, either side of the west/east openings and north/south. */
	private static int[][] rimPosts(int r) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		int diag = (int) Math.round(r / Math.sqrt(2));
		for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) out.add(new int[]{sx * diag, sz * diag});
		for (int s2 : new int[]{-1, 1}) {
			out.add(new int[]{-r, s2 * 2});
			out.add(new int[]{r, s2 * 2});
			out.add(new int[]{0, s2 * r});
		}
		return out.toArray(new int[0][]);
	}

	/** Both altar areas (5.30): the portal island east of the lobby and the closed Altar Hall far east. */
	private static void buildAltar(ServerLevel level) {
		buildPortalIsland(level);
		buildAltarHall(level);
	}

	/**
	 * The portal island: dark stone, a bridge from a gap in the lobby's rail, a lantern-lit rim wall open west and east,
	 * and in the middle the altar portal - a carved arch with the swirl in it (AltarRitual.spawnPortal), facing the bridge.
	 */
	private static void buildPortalIsland(ServerLevel level) {
		int cx = Arena.ISLAND_X, r = Arena.ISLAND_R;
		islandBase(level, cx, 0, r + 1, r + 1, FLOOR, 16, 2);
		for (int x = -r - 1; x <= r + 1; x++) {
			for (int z = -r - 1; z <= r + 1; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > r + 0.5) continue;
				Block floor, below = Blocks.DEEPSLATE_BRICKS;
				if (d <= 1.5) floor = Blocks.CHISELED_POLISHED_BLACKSTONE;
				else if (d > 2.5 && d <= 3.5) {
					floor = Blocks.PURPLE_STAINED_GLASS;
					below = Blocks.SEA_LANTERN;
				} else if (d > r - 0.5) floor = Blocks.GILDED_BLACKSTONE;
				else floor = (x + z & 1) == 0 ? Blocks.POLISHED_BLACKSTONE_BRICKS : Blocks.POLISHED_DEEPSLATE;
				set(level, cx + x, FLOOR, z, floor);
				set(level, cx + x, FLOOR - 1, z, below);
				for (int y = 1; y <= 8; y++) set(level, cx + x, FLOOR + y, z, Blocks.AIR);
			}
		}
		for (int d = 4; d <= r; d++) for (int sx : new int[]{-1, 1}) set(level, cx + sx * d, FLOOR, 0, d == r ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
		for (int x = -r - 1; x <= r + 1; x++) {
			for (int z = -r - 1; z <= r + 1; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d <= r - 0.5 || d > r + 0.5) continue;
				set(level, cx + x, FLOOR + 1, z, Math.abs(z) <= 1 ? Blocks.AIR : Blocks.POLISHED_BLACKSTONE_BRICK_WALL);
			}
		}
		for (int[] post : rimPosts(r)) {
			set(level, cx + post[0], FLOOR + 1, post[1], Blocks.POLISHED_BLACKSTONE_BRICKS);
			set(level, cx + post[0], FLOOR + 2, post[1], Blocks.SOUL_LANTERN);
		}
		portalArch(level, cx, 0);
		// the bridge from the lobby, and the gap in the lobby's rail it starts at
		for (int x = LOBBY_R - 2; x < cx - r; x++) {
			for (int z = -1; z <= 1; z++) {
				if (x > LOBBY_R) {
					set(level, x, FLOOR, z, z == 0 ? Blocks.QUARTZ_BRICKS : Blocks.SMOOTH_QUARTZ);
					set(level, x, FLOOR - 1, z, z == 0 ? Blocks.SEA_LANTERN : Blocks.STONE_BRICKS);
				}
				set(level, x, FLOOR + 1, z, Blocks.AIR);
				set(level, x, FLOOR + 2, z, Blocks.AIR);
			}
			if (x > LOBBY_R) {
				set(level, x, FLOOR + 1, -2, Blocks.SMOOTH_QUARTZ_SLAB);
				set(level, x, FLOOR + 1, 2, Blocks.SMOOTH_QUARTZ_SLAB);
				set(level, x, FLOOR, -2, Blocks.STONE_BRICKS);
				set(level, x, FLOOR, 2, Blocks.STONE_BRICKS);
			}
		}
		AltarRitual.spawnPortal(level, new Vec3(cx + 0.5, FLOOR + 1, 0.5));
	}

	/**
	 * The portal's arch across the x axis at {@code cx} (5.33): three blocks deep, the swirl (AltarRitual.spawnPortal) in
	 * its middle layer so it sits recessed in a short tunnel; the opening PORTAL_W wide and PORTAL_H high with its corners
	 * rounded off by stairs (no hard rectangle), crying obsidian and gilded courses lining the tunnel, amethyst growing
	 * out of its walls, a hidden light in the opening, chiseled pilasters, a gilded keystone and lanterns on top.
	 */
	private static void portalArch(ServerLevel level, int cx, int cz) {
		int hw = Arena.PORTAL_W / 2, H = Arena.PORTAL_H, fw = hw + 2;
		for (int dx = -1; dx <= 1; dx++) {
			boolean face = dx != 0;
			for (int y = 1; y <= H + 2; y++) {
				for (int z = -fw; z <= fw; z++) {
					boolean opening = Math.abs(z) <= hw && y <= H;
					if (y == H + 2 && Math.abs(z) == fw) continue;
					Block b;
					if (opening) b = Blocks.AIR;
					else if (y == H + 1 && z == 0) b = Blocks.GILDED_BLACKSTONE;
					else if (y == 1 && Math.abs(z) > hw) b = Blocks.CRYING_OBSIDIAN;
					else if (Math.abs(z) == hw + 1 || y == H + 1) b = face ? (y % 3 == 0 ? Blocks.GILDED_BLACKSTONE : Blocks.CRYING_OBSIDIAN) : Blocks.CRYING_OBSIDIAN;
					else if (face && Math.abs(z) == fw && y % 3 == 0) b = Blocks.CHISELED_POLISHED_BLACKSTONE;
					else b = Blocks.POLISHED_BLACKSTONE_BRICKS;
					set(level, cx + dx, FLOOR + y, cz + z, b);
				}
			}
			// round the opening's corners: upside-down stairs at the top, stairs at the foot
			for (int sz : new int[]{-1, 1}) {
				Direction out = sz < 0 ? Direction.NORTH : Direction.SOUTH;
				var top = Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState()
						.setValue(net.minecraft.world.level.block.StairBlock.FACING, out)
						.setValue(net.minecraft.world.level.block.StairBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP);
				set(level, cx + dx, FLOOR + H, cz + sz * hw, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
				set(level, cx + dx, FLOOR + H - 1, cz + sz * hw, top);
				set(level, cx + dx, FLOOR + H, cz + sz * (hw - 1), top);
				set(level, cx + dx, FLOOR + 1, cz + sz * hw, Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState()
						.setValue(net.minecraft.world.level.block.StairBlock.FACING, out));
			}
			// amethyst out of the tunnel's walls, a hidden light in the opening
			if (face) {
				for (int sz : new int[]{-1, 1}) {
					Direction in = sz < 0 ? Direction.SOUTH : Direction.NORTH;
					for (int y : new int[]{3, 6}) {
						set(level, cx + dx, FLOOR + y, cz + sz * hw, Blocks.AMETHYST_CLUSTER.defaultBlockState()
								.setValue(net.minecraft.world.level.block.AmethystClusterBlock.FACING, in));
					}
				}
				set(level, cx + dx, FLOOR + H / 2, cz, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 11));
			}
		}
		// lanterns on top, a chain of crying obsidian down the keystone's face
		set(level, cx, FLOOR + H + 3, cz - fw + 1, Blocks.SOUL_LANTERN);
		set(level, cx, FLOOR + H + 3, cz + fw - 1, Blocks.SOUL_LANTERN);
		set(level, cx, FLOOR + H + 3, cz, Blocks.AMETHYST_CLUSTER);
	}

	/**
	 * The Altar Hall (Arena.ALTAR_X): an offering temple. A closed round hall of inner radius ROOM_R under a stepped dome
	 * with a glass oculus; in the middle a stepped podium (PODIUM_TIERS tiers, stairs on all four sides) with the dais on
	 * top, where the ritual's player stands (Arena.ALTAR). Round it the ten great pillars (2x2 barrier columns inside the
	 * altar_pillar models, AltarRitual.spawnPillars), buttresses along the wall with lanterns, offering fires and pots on
	 * the podium, a carpet from the door, and the exit portal in the west wall.
	 */
	private static void buildAltarHall(ServerLevel level) {
		int cx = Arena.ALTAR_X, R = Arena.ROOM_R, T = Arena.PODIUM_TIERS;
		int wallTop = FLOOR + Arena.HALL_WALL_H;
		for (int x = -R - 3; x <= R + 3; x++) for (int z = -R - 3; z <= R + 3; z++) for (int y = FLOOR - 3; y <= FLOOR + 36; y++) set(level, cx + x, y, z, Blocks.AIR);
		for (int x = -R - 1; x <= R + 1; x++) {
			for (int z = -R - 1; z <= R + 1; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > R + 1.5) continue;
				Block floor, below = Blocks.DEEPSLATE_BRICKS;
				if (d > 6.5 && d <= 7.5) {
					floor = Blocks.PURPLE_STAINED_GLASS;
					below = Blocks.SEA_LANTERN;
				} else if (d > 14.5 && d <= 15.5) floor = Blocks.GILDED_BLACKSTONE;
				else floor = (x + z & 1) == 0 ? Blocks.POLISHED_BLACKSTONE_BRICKS : Blocks.POLISHED_DEEPSLATE;
				set(level, cx + x, FLOOR, z, floor);
				set(level, cx + x, FLOOR - 1, z, below);
				set(level, cx + x, FLOOR - 2, z, Blocks.DEEPSLATE_TILES);
				if (d > R + 0.5) {
					for (int y = FLOOR + 1; y <= wallTop; y++) {
						Block w = y == FLOOR + 1 || y == wallTop ? Blocks.CHISELED_POLISHED_BLACKSTONE
								: y % 4 == 0 ? Blocks.POLISHED_BLACKSTONE_BRICKS : (y & 1) == 0 ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_BLACKSTONE_BRICKS;
						set(level, cx + x, y, z, w);
					}
				}
			}
		}
		// buttresses on the wall, 16 round (none in the door): two wide, gilded courses, a lantern ledge
		for (int i = 0; i < 16; i++) {
			double a = 2 * Math.PI * (i + 0.5) / 16;
			if (Math.abs(Math.cos(a) + 1) < 0.05) continue;
			int x = (int) Math.round(Math.cos(a) * (R - 0.4)), z = (int) Math.round(Math.sin(a) * (R - 0.4));
			for (int y = FLOOR + 1; y <= wallTop - 1; y++) {
				set(level, cx + x, y, z, y % 6 == 0 ? Blocks.GILDED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
			int lx = (int) Math.round(Math.cos(a) * (R - 1.4)), lz = (int) Math.round(Math.sin(a) * (R - 1.4));
			set(level, cx + lx, FLOOR + 7, lz, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB);
			set(level, cx + lx, FLOOR + 8, lz, Blocks.SOUL_LANTERN);
		}
		// the ceiling (5.34): terraces stepping up from the wall to a flat ceiling at HALL_CEIL_H, which the pillars (with their crystals)
		// reach; a chiseled architrave ring over the pillars and a glass oculus over the podium
		double outer = R + 1.5;
		int rows = Arena.HALL_CEIL_H - Arena.HALL_WALL_H;
		for (int x = -R - 1; x <= R + 1; x++) {
			for (int z = -R - 1; z <= R + 1; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > outer) continue;
				int row = rows;
				for (int r = 1; r < rows; r++) if (d > R - 1.5 - 2.0 * (r - 1)) {
					row = r;
					break;
				}
				Block b;
				if (row < rows) b = row % 2 == 0 ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_BLACKSTONE_BRICKS;
				else if (d <= 3.5) b = Blocks.PURPLE_STAINED_GLASS;
				else if (d > Arena.PILLAR_RING - 0.8 && d <= Arena.PILLAR_RING + 0.8) b = Blocks.CHISELED_POLISHED_BLACKSTONE;
				else b = (x + z & 1) == 0 ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_BLACKSTONE_BRICKS;
				set(level, cx + x, wallTop + row, z, b);
			}
		}
		// the podium: T tiers, the top one the dais with its heart; stairs up the middle of each side
		for (int k = 0; k < T; k++) {
			int h = T + 1 - k;
			for (int x = -h; x <= h; x++) {
				for (int z = -h; z <= h; z++) {
					boolean rim = Math.abs(x) == h || Math.abs(z) == h;
					Block b = k == T - 1 && x == 0 && z == 0 ? Blocks.CRYING_OBSIDIAN : rim ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS;
					set(level, cx + x, FLOOR + 1 + k, z, b);
				}
			}
			for (Direction dir : new Direction[]{Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH}) {
				for (int w = -1; w <= 1; w++) {
					int sx = dir.getStepX() * h + (dir.getAxis() == Direction.Axis.Z ? w : 0);
					int sz = dir.getStepZ() * h + (dir.getAxis() == Direction.Axis.X ? w : 0);
					set(level, cx + sx, FLOOR + 1 + k, sz, Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState()
							.setValue(net.minecraft.world.level.block.StairBlock.FACING, dir.getOpposite()));
				}
			}
		}
		// on the podium: candles on the dais' corners, offering pots and soul fires on the lower tiers' corners
		int top = T - 1, th = T + 1 - top;
		for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
			set(level, cx + sx * th, FLOOR + 1 + T, sz * th, Blocks.BLACK_CANDLE.defaultBlockState()
					.setValue(net.minecraft.world.level.block.CandleBlock.CANDLES, 4).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
			set(level, cx + sx * (T), FLOOR + 3, sz * (T), Blocks.DECORATED_POT);
			set(level, cx + sx * (T + 1), FLOOR + 1, sz * (T + 1), Blocks.CHISELED_POLISHED_BLACKSTONE);
			set(level, cx + sx * (T + 1), FLOOR + 2, sz * (T + 1), Blocks.SOUL_CAMPFIRE);
		}
		// carpets from the door (west) and its mirror (east) to the stairs
		for (int d = T + 3; d <= R - 1; d++) {
			for (int sx : new int[]{-1, 1}) for (int z = -1; z <= 1; z++) {
				set(level, cx + sx * d, FLOOR + 1, z, z == 0 ? Blocks.PURPLE_CARPET : Blocks.BLACK_CARPET);
			}
		}
		// light: invisible light blocks (no fixtures, no collision), three layers
		for (int x = -R + 1; x <= R - 1; x += 4) {
			for (int z = -R + 1; z <= R - 1; z += 4) {
				if (x * x + z * z > (R - 1) * (R - 1) || Math.abs(x) <= T + 2 && Math.abs(z) <= T + 2) continue;
				set(level, cx + x, FLOOR + 3, z, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 13));
				set(level, cx + x, FLOOR + 9, z, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 15));
				set(level, cx + x, FLOOR + 14, z, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 14));
				if (x * x + z * z <= 20 * 20) set(level, cx + x, FLOOR + 17, z, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 14));
			}
		}
		HallDecor.build(level, cx, R, T); // floor inlay, plinth, buttresses, cornice, lancet windows, frieze, lantern crown (5.34)
		// the great pillars: a 3x3 barrier column (collision) inside each altar_pillar model
		java.util.List<com.toast.aurapoints.ability.AuraType> auras = AltarRitual.auras();
		for (int i = 0; i < auras.size(); i++) {
			Vec3 p = AltarRitual.pillar(i, auras.size());
			int px = (int) Math.floor(p.x), pz = (int) Math.floor(p.z);
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 1; y < Arena.HALL_CEIL_H; y++) set(level, px + dx, FLOOR + y, pz + dz, Blocks.BARRIER);
		}
		AltarRitual.spawnPillars(level);
		// the exit: an arch in the west wall, its portal back to the island
		int doorX = cx - R - 1;
		portalArch(level, doorX, 0);
		AltarRitual.spawnExitPortal(level, new Vec3(doorX + 0.5, FLOOR + 1, 0.5));
	}

	private static void decorate(ServerLevel level) {
		// the lobby's aura core and the seven crystals round it, the seven over the ring (ArenaFx moves them)
		blockDisplay(level, Blocks.BEACON.defaultBlockState(), ArenaFx.CORE, 1.6f, "aa_core");
		for (int k = 0; k < ArenaFx.ORBS; k++) blockDisplay(level, ArenaFx.GLASS[k].defaultBlockState(), ArenaFx.CORE, 0.5f, "aa_lorb_" + k);
		for (int k = 0; k < ArenaFx.ORBS; k++) blockDisplay(level, ArenaFx.GLASS[k].defaultBlockState(), ArenaFx.RING_SKY, 1.3f, "aa_rorb_" + k);
		// the kiosks' items
		for (TrainingHall.Control c : TrainingHall.Control.values()) {
			itemDisplay(level, c.item, c.itemPos(), 0f, 0f, 0.75f, "aa_kiosk_" + c.ordinal());
		}
		// east wall by the door: blades and a trident, tilted
		Item[] blades = {Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_AXE, Items.TRIDENT};
		for (int k = 0; k < blades.length; k++) itemDisplay(level, blades[k], new Vec3(HX - 0.03, FLOOR + 2.4, HZ0 + 1.6 + k), 90f, 45f, 0.8f, null);
		// west wall at the range: bows and crossbows
		Item[] bows = {Items.BOW, Items.CROSSBOW, Items.BOW, Items.CROSSBOW};
		for (int k = 0; k < bows.length; k++) itemDisplay(level, bows[k], new Vec3(-HX + 1.03, FLOOR + 2.6, RANGE_Z + 3.5 + k * 1.5), -90f, 0f, 0.8f, null);
		// a totem over the DPS records
		itemDisplay(level, Items.TOTEM_OF_UNDYING, new Vec3(-HX + 1.03, FLOOR + 4.5, TrainingHall.BOARD_2.getZ()), -90f, 0f, 0.8f, null);
	}

	/** An item display at {@code at}, facing {@code yaw}, turned {@code tilt} degrees in its own plane; {@code anim} tags it for ArenaFx. */
	private static void itemDisplay(ServerLevel level, Item item, Vec3 at, float yaw, float tilt, float scale, String anim) {
		Display.ItemDisplay display = EntityType.ITEM_DISPLAY.create(level);
		if (display == null) return;
		CompoundTag tag = displayTag(scale, tilt, 0f);
		tag.put("item", new ItemStack(item).save(level.registryAccess()));
		tag.putString("item_display", "fixed");
		spawnDisplay(level, display, tag, at, yaw, anim);
	}

	/** A block display centred on {@code at} (so it spins about its middle), lit up full. */
	private static void blockDisplay(ServerLevel level, BlockState state, Vec3 at, float scale, String anim) {
		Display.BlockDisplay display = EntityType.BLOCK_DISPLAY.create(level);
		if (display == null) return;
		CompoundTag tag = displayTag(scale, 0f, -scale / 2);
		tag.put("block_state", NbtUtils.writeBlockState(state));
		CompoundTag light = new CompoundTag();
		light.putInt("sky", 15);
		light.putInt("block", 15);
		tag.put("brightness", light);
		spawnDisplay(level, display, tag, at, 0f, anim);
	}

	private static CompoundTag displayTag(float scale, float tilt, float offset) {
		CompoundTag tag = new CompoundTag();
		double half = Math.toRadians(tilt) / 2;
		CompoundTag transformation = new CompoundTag();
		transformation.put("left_rotation", floats(0f, 0f, (float) Math.sin(half), (float) Math.cos(half)));
		transformation.put("right_rotation", floats(0f, 0f, 0f, 1f));
		transformation.put("translation", floats(offset, offset, offset));
		transformation.put("scale", floats(scale, scale, scale));
		tag.put("transformation", transformation);
		tag.putInt("teleport_duration", 3);
		tag.putFloat("view_range", 3f);
		return tag;
	}

	private static void spawnDisplay(ServerLevel level, Display display, CompoundTag tag, Vec3 at, float yaw, String anim) {
		display.load(tag);
		display.moveTo(at.x, at.y, at.z, yaw, 0f);
		claim(display);
		if (anim != null) display.addTag(anim);
		level.addFreshEntity(display);
	}

	private static ListTag floats(float... values) {
		ListTag list = new ListTag();
		for (float v : values) list.add(FloatTag.valueOf(v));
		return list;
	}

	// ---------------------------------------------------------------
	// Signs
	// ---------------------------------------------------------------

	private static void standingSign(ServerLevel level, int x, int z, int rotation, String... lines) {
		set(level, x, FLOOR + 1, z, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation));
		if (lines.length > 0) writeSign(level, new BlockPos(x, FLOOR + 1, z), lines);
	}

	/** Text on a sign (both sides - hanging signs show both), glowing, waxed so nobody can edit it. */
	static void writeSign(ServerLevel level, BlockPos pos, String... lines) {
		if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
		SignText text = new SignText();
		for (int i = 0; i < Math.min(4, lines.length); i++) text = text.setMessage(i, Component.literal(lines[i]));
		text = text.setHasGlowingText(true);
		sign.setText(text, true);
		sign.setText(text, false);
		sign.setWaxed(true);
		sign.setChanged();
		level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), Block.UPDATE_CLIENTS);
	}
}
