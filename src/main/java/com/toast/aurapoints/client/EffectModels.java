package com.toast.aurapoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.HashMap;
import java.util.Map;

/**
 * CLIENT ONLY. The ability effects' own materials. The server builds effects from block displays of vanilla blocks
 * (so they show for anyone); the client draws the blocks below with the mod's own models instead
 * (mixin/BlockDisplayRendererMixin). Shapes stay the same, and natural ground blocks (dirt, stone, sand...) are never
 * swapped, since abilities copy the terrain with them.
 */
public final class EffectModels {

	private EffectModels() {
	}

	/** A replacement material: its model, an optional full-bright overlay (glowing cracks...), how it's drawn. */
	private record Fx(ModelResourceLocation model, ModelResourceLocation glow, boolean translucent, boolean bright) {
	}

	private static final Map<Block, Fx> FX = new HashMap<>();

	private static ModelResourceLocation model(String name) {
		return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("aurapoints", "block/fx/" + name));
	}

	private static void fx(Block block, String name, boolean overlay, boolean translucent, boolean bright) {
		FX.put(block, new Fx(model(name), overlay ? model(name + "_glow") : null, translucent, bright));
	}

	static {
		// Frost: Winter's Gate, Ice Shell, Permafrost
		fx(Blocks.ICE, "frost_ice", false, true, false);
		fx(Blocks.PACKED_ICE, "frost_ice_packed", false, false, false);
		fx(Blocks.BLUE_ICE, "frost_ice_deep", true, false, false);
		// Fire: the Sunfall meteor, Phoenix
		fx(Blocks.MAGMA_BLOCK, "meteor_rock", true, false, false);
		fx(Blocks.SHROOMLIGHT, "ember_core", false, false, true);
		// Water: Maelstrom's kraken tentacles, Tidal Ascension
		fx(Blocks.PRISMARINE, "kraken_skin", false, false, false);
		fx(Blocks.PRISMARINE_BRICKS, "kraken_suckers", false, false, false);
		fx(Blocks.DARK_PRISMARINE, "kraken_dark", false, false, false);
		// Shadow: the Umbral Ambush clone, Eclipse; Void
		fx(Blocks.BLACK_CONCRETE, "shadow_matter", true, false, false);
		fx(Blocks.BLACK_WOOL, "shadow_cloth", false, false, false);
		fx(Blocks.PURPLE_CONCRETE, "shadow_eye", false, false, true);
		fx(Blocks.CRYING_OBSIDIAN, "void_rift", true, false, false);
		fx(Blocks.PURPLE_STAINED_GLASS, "void_glass", false, true, true);
		// Light
		fx(Blocks.WHITE_CONCRETE, "light_core", false, false, true);
		fx(Blocks.YELLOW_STAINED_GLASS, "light_glass", false, true, true);
		// Verdant: roots, Thorn Sentinel, Overgrowth, the viper
		fx(Blocks.MANGROVE_ROOTS, "living_roots", false, false, false);
		fx(Blocks.ROOTED_DIRT, "living_soil", false, false, false);
		fx(Blocks.OAK_LOG, "living_bark", false, false, false);
		fx(Blocks.DARK_OAK_LOG, "elder_bark", false, false, false);
		fx(Blocks.MOSS_BLOCK, "verdant_moss", false, false, false);
		fx(Blocks.MOSS_CARPET, "verdant_moss_carpet", false, false, false);
		fx(Blocks.AZALEA_LEAVES, "verdant_leaves", false, false, false);
		fx(Blocks.FLOWERING_AZALEA_LEAVES, "verdant_bloom", true, false, false);
		fx(Blocks.BAMBOO_BLOCK, "viper_stem", false, false, false);
		fx(Blocks.BONE_BLOCK, "viper_tooth", false, false, false);
		fx(Blocks.RED_TERRACOTTA, "viper_maw", false, false, false);
		fx(Blocks.BLACKSTONE, "withered_vine", false, false, false);
		// Earth: boulders
		fx(Blocks.COBBLESTONE, "earth_boulder", false, false, false);
		fx(Blocks.MOSSY_COBBLESTONE, "earth_boulder_mossy", false, false, false);
		fx(Blocks.COBBLED_DEEPSLATE, "earth_boulder_deep", false, false, false);
	}

	public static void registerModels(ModelEvent.RegisterAdditional event) {
		for (Fx fx : FX.values()) {
			event.register(fx.model);
			if (fx.glow != null) event.register(fx.glow);
		}
	}

	/** BlockDisplayRenderer hook: draws a block display's block with its effect model. False = not an effect material. */
	public static boolean renderBlock(BlockState state, PoseStack pose, MultiBufferSource buffers, int light) {
		Fx fx = FX.get(state.getBlock());
		if (fx == null) return false;
		var models = Minecraft.getInstance().getModelManager();
		BakedModel model = models.getModel(fx.model);
		if (model == models.getMissingModel()) return false;
		pose.pushPose();
		if (state.hasProperty(RotatedPillarBlock.AXIS)) { // logs, bamboo and bone blocks lie on their side
			Direction.Axis axis = state.getValue(RotatedPillarBlock.AXIS);
			if (axis != Direction.Axis.Y) {
				pose.translate(0.5, 0.5, 0.5);
				pose.mulPose(axis == Direction.Axis.X ? Axis.ZP.rotationDegrees(90) : Axis.XP.rotationDegrees(90));
				pose.translate(-0.5, -0.5, -0.5);
			}
		}
		var renderer = Minecraft.getInstance().getBlockRenderer().getModelRenderer();
		RenderType type = fx.translucent ? Sheets.translucentCullBlockSheet() : Sheets.cutoutBlockSheet();
		renderer.renderModel(pose.last(), buffers.getBuffer(type), state, model, 1f, 1f, 1f, fx.bright ? LightTexture.FULL_BRIGHT : light,
				OverlayTexture.NO_OVERLAY, ModelData.EMPTY, type);
		if (fx.glow != null) {
			BakedModel glow = models.getModel(fx.glow);
			if (glow != models.getMissingModel()) {
				RenderType cut = Sheets.cutoutBlockSheet();
				renderer.renderModel(pose.last(), buffers.getBuffer(cut), state, glow, 1f, 1f, 1f, LightTexture.FULL_BRIGHT,
						OverlayTexture.NO_OVERLAY, ModelData.EMPTY, cut);
			}
		}
		pose.popPose();
		return true;
	}
}
