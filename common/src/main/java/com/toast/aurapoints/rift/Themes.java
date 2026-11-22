package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The Reliquary dressed per element (5.48.0): every structural block of the build (it is designed in deepslate, tuff
 * and blackstone) swapped for the aura's own family, keeping a stair's facing, a slab's type, a wall's connections.
 * Wind: a white-and-gold palace on clouds. Fire: a blackstone and basalt forge. Frost: an ice palace. Water: a
 * prismarine temple. Verdant: an overgrown temple. Thunder: a copper storm citadel. Earth: a sandstone canyon temple.
 * Light: a quartz and gold sanctum. Blood: a nether-brick keep. Shadow: the dark cathedral as designed.
 * The sky, fog and daylight of each realm are client/RiftSky's.
 */
final class Themes {

	private Themes() {
	}

	/** The build's structural blocks, by role (the keys every theme fills). */
	private static final String[] KEYS = {
			"tuff", "deepslate", "cobbled_deepslate", "blackstone",                        // rock: surface, core, rubble, deep
			"deepslate_bricks", "cracked_deepslate_bricks",                                // walls
			"deepslate_tiles", "cracked_deepslate_tiles",                                  // roofs, aisle floors, arches
			"polished_deepslate", "chiseled_deepslate", "chiseled_tuff_bricks", "chiseled_polished_blackstone", // trim, carving
			"tuff_bricks", "polished_tuff", "polished_blackstone_bricks", "polished_blackstone", // floors, runner
			"coarse_dirt", "calcite", "bone_block",                                        // ground speckle, statues, bones
			"deepslate_tile_stairs", "deepslate_tile_slab", "deepslate_brick_slab", "polished_deepslate_slab",
			"deepslate_brick_wall", "polished_deepslate_wall", "tuff_wall", "polished_deepslate_stairs"};

	private static final Map<AuraType, Map<Block, Block>> THEMES = new EnumMap<>(AuraType.class);

	private static void theme(AuraType a, String... to) {
		Map<Block, Block> m = new HashMap<>();
		for (int i = 0; i < KEYS.length && i < to.length; i++) {
			Block from = block(KEYS[i]), dest = block(to[i]);
			if (from != Blocks.AIR && dest != Blocks.AIR && from != dest) m.put(from, dest);
		}
		THEMES.put(a, m);
	}

	private static Block block(String id) {
		return BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(id));
	}

	static {
		theme(AuraType.WIND,
				"snow_block", "white_wool", "calcite", "white_wool",
				"quartz_bricks", "calcite",
				"smooth_quartz", "polished_diorite",
				"quartz_pillar", "chiseled_quartz_block", "chiseled_quartz_block", "gold_block",
				"smooth_quartz", "quartz_bricks", "gold_block", "gold_block",
				"snow_block", "smooth_quartz", "white_wool",
				"smooth_quartz_stairs", "smooth_quartz_slab", "quartz_slab", "smooth_quartz_slab",
				"diorite_wall", "diorite_wall", "diorite_wall", "quartz_stairs");
		theme(AuraType.FIRE,
				"smooth_basalt", "blackstone", "blackstone", "basalt",
				"polished_blackstone_bricks", "cracked_polished_blackstone_bricks",
				"polished_blackstone", "blackstone",
				"polished_basalt", "chiseled_polished_blackstone", "chiseled_polished_blackstone", "gilded_blackstone",
				"polished_blackstone_bricks", "polished_blackstone", "gilded_blackstone", "gilded_blackstone",
				"smooth_basalt", "polished_basalt", "bone_block",
				"polished_blackstone_brick_stairs", "polished_blackstone_slab", "polished_blackstone_brick_slab", "polished_blackstone_slab",
				"polished_blackstone_brick_wall", "polished_blackstone_wall", "blackstone_wall", "polished_blackstone_stairs");
		theme(AuraType.FROST,
				"snow_block", "packed_ice", "snow_block", "blue_ice",
				"packed_ice", "snow_block",
				"light_blue_terracotta", "white_terracotta",
				"packed_ice", "blue_ice", "blue_ice", "blue_ice",
				"polished_diorite", "smooth_quartz", "blue_ice", "blue_ice",
				"snow_block", "packed_ice", "snow_block",
				"smooth_quartz_stairs", "smooth_quartz_slab", "quartz_slab", "smooth_quartz_slab",
				"diorite_wall", "diorite_wall", "diorite_wall", "quartz_stairs");
		theme(AuraType.WATER,
				"dead_tube_coral_block", "dark_prismarine", "prismarine", "dead_brain_coral_block",
				"prismarine_bricks", "prismarine",
				"dark_prismarine", "prismarine",
				"dark_prismarine", "sea_lantern", "sea_lantern", "sea_lantern",
				"prismarine_bricks", "dark_prismarine", "sea_lantern", "sea_lantern",
				"sand", "prismarine", "bone_block",
				"dark_prismarine_stairs", "dark_prismarine_slab", "prismarine_brick_slab", "dark_prismarine_slab",
				"prismarine_wall", "prismarine_wall", "prismarine_wall", "prismarine_brick_stairs");
		theme(AuraType.VERDANT,
				"moss_block", "rooted_dirt", "mossy_cobblestone", "stone",
				"mossy_stone_bricks", "cracked_stone_bricks",
				"stone_bricks", "mossy_cobblestone",
				"polished_andesite", "chiseled_stone_bricks", "chiseled_stone_bricks", "moss_block",
				"stone_bricks", "mossy_stone_bricks", "moss_block", "moss_block",
				"moss_block", "mossy_stone_bricks", "moss_block",
				"mossy_stone_brick_stairs", "mossy_stone_brick_slab", "stone_brick_slab", "mossy_stone_brick_slab",
				"mossy_stone_brick_wall", "mossy_stone_brick_wall", "mossy_cobblestone_wall", "stone_brick_stairs");
		theme(AuraType.THUNDER,
				"tuff", "deepslate", "cobbled_deepslate", "blackstone",
				"waxed_oxidized_cut_copper", "waxed_weathered_cut_copper",
				"deepslate_tiles", "cracked_deepslate_tiles",
				"waxed_copper_block", "waxed_chiseled_copper", "waxed_chiseled_copper", "yellow_glazed_terracotta",
				"waxed_cut_copper", "polished_deepslate", "yellow_glazed_terracotta", "gold_block",
				"coarse_dirt", "waxed_oxidized_copper", "bone_block",
				"waxed_oxidized_cut_copper_stairs", "waxed_oxidized_cut_copper_slab", "waxed_cut_copper_slab", "polished_deepslate_slab",
				"deepslate_brick_wall", "polished_deepslate_wall", "tuff_wall", "waxed_cut_copper_stairs");
		theme(AuraType.EARTH,
				"terracotta", "orange_terracotta", "sandstone", "brown_terracotta",
				"smooth_sandstone", "sandstone",
				"cut_red_sandstone", "red_sandstone",
				"smooth_red_sandstone", "chiseled_sandstone", "chiseled_red_sandstone", "chiseled_red_sandstone",
				"cut_sandstone", "smooth_sandstone", "chiseled_red_sandstone", "red_sandstone",
				"red_sand", "sandstone", "bone_block",
				"red_sandstone_stairs", "red_sandstone_slab", "sandstone_slab", "smooth_sandstone_slab",
				"sandstone_wall", "red_sandstone_wall", "sandstone_wall", "smooth_sandstone_stairs");
		theme(AuraType.LIGHT,
				"calcite", "white_terracotta", "calcite", "yellow_terracotta",
				"quartz_bricks", "calcite",
				"smooth_quartz", "polished_diorite",
				"quartz_pillar", "gold_block", "gold_block", "gold_block",
				"quartz_bricks", "smooth_quartz", "gold_block", "gold_block",
				"calcite", "gold_block", "calcite",
				"smooth_sandstone_stairs", "smooth_sandstone_slab", "quartz_slab", "smooth_quartz_slab",
				"diorite_wall", "diorite_wall", "diorite_wall", "quartz_stairs");
		theme(AuraType.BLOOD,
				"netherrack", "netherrack", "nether_wart_block", "blackstone",
				"red_nether_bricks", "cracked_nether_bricks",
				"nether_bricks", "cracked_nether_bricks",
				"nether_bricks", "chiseled_nether_bricks", "chiseled_nether_bricks", "redstone_block",
				"nether_bricks", "red_nether_bricks", "redstone_block", "crimson_planks",
				"crimson_nylium", "nether_wart_block", "bone_block",
				"red_nether_brick_stairs", "red_nether_brick_slab", "nether_brick_slab", "red_nether_brick_slab",
				"red_nether_brick_wall", "nether_brick_wall", "nether_brick_wall", "nether_brick_stairs");
		THEMES.put(AuraType.SHADOW, Map.of());
	}

	/** The aura's block for {@code state}, its properties carried over; null = keep. */
	static BlockState swap(AuraType aura, BlockState state) {
		Map<Block, Block> m = THEMES.get(aura);
		if (m == null) return null;
		Block to = m.get(state.getBlock());
		if (to == null) return null;
		BlockState out = to.defaultBlockState();
		for (Property<?> p : state.getProperties()) out = copy(out, state, p);
		return out;
	}

	private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, Property<T> p) {
		return to.hasProperty(p) ? to.setValue(p, from.getValue(p)) : to;
	}
}
