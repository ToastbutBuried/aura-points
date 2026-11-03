package com.toast.aurapoints.fabric.client;

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

public final class FabricClientPlatform implements ClientPlatform {
	/** Fabric's variant for extra models added with ModelLoadingPlugin.addModels. */
	static final String EXTRA_MODEL_VARIANT = "fabric_resource";

	@Override
	public boolean isNameplateInRenderDistance(Entity entity, double squareDistance) {
		return !(squareDistance > 4096.0);
	}

	@Override
	public ModelResourceLocation standaloneModel(ResourceLocation id) {
		return new ModelResourceLocation(id, EXTRA_MODEL_VARIANT);
	}

	@Override
	public void renderModel(ModelBlockRenderer renderer, PoseStack.Pose pose, VertexConsumer consumer, BlockState state, BakedModel model,
			float red, float green, float blue, int light, int overlay, RenderType renderType) {
		renderer.renderModel(pose, consumer, state, model, red, green, blue, light, overlay);
	}

	@Override
	public KeyMapping inGameKey(String name, InputConstants.Type type, int key, String category) {
		return new KeyMapping(name, type, key, category);
	}

	@Override
	public boolean keysConflict(KeyMapping a, KeyMapping b) {
		return true; // plain Minecraft has no key contexts: any two keys on one button clash
	}

	@Override
	public void registerConfigScreen() {
		// Mod Menu finds it through the "modmenu" entry point (AuraModMenu)
	}
}
