package com.toast.aurapoints.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.toast.aurapoints.shim.ClientPlatform;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

final class NeoClientPlatform implements ClientPlatform {
	private final ModContainer container;

	NeoClientPlatform(ModContainer container) {
		this.container = container;
	}

	@Override
	public boolean isNameplateInRenderDistance(Entity entity, double squareDistance) {
		return ClientHooks.isNameplateInRenderDistance(entity, squareDistance);
	}

	@Override
	public ModelResourceLocation standaloneModel(ResourceLocation id) {
		return ModelResourceLocation.standalone(id);
	}

	@Override
	public void renderModel(ModelBlockRenderer renderer, PoseStack.Pose pose, VertexConsumer consumer, BlockState state, BakedModel model,
			float red, float green, float blue, int light, int overlay, RenderType renderType) {
		renderer.renderModel(pose, consumer, state, model, red, green, blue, light, overlay, ModelData.EMPTY, renderType);
	}

	@Override
	public KeyMapping inGameKey(String name, InputConstants.Type type, int key, String category) {
		return new KeyMapping(name, KeyConflictContext.IN_GAME, type, key, category);
	}

	@Override
	public boolean keysConflict(KeyMapping a, KeyMapping b) {
		return a.getKeyConflictContext().conflicts(b.getKeyConflictContext());
	}

	@Override
	public void registerConfigScreen() {
		container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
	}
}
