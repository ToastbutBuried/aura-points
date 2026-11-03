package com.toast.aurapoints.shim;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;


/** Client-only loader services. Never touch on a server. */
public interface ClientPlatform {
	static ClientPlatform get() {
		ClientPlatform p = Holder.instance;
		if (p == null) throw new IllegalStateException("Aura Points: no ClientPlatform set (the loader entry point sets it first)");
		return p;
	}

	/** Called once, first thing, by the loader entry point. */
	static void set(ClientPlatform platform) {
		Holder.instance = platform;
	}

	final class Holder {
		private Holder() {
		}

		private static volatile ClientPlatform instance;
	}

	boolean isNameplateInRenderDistance(Entity entity, double squareDistance);

	/** The id an extra (non block/item) model is loaded and looked up under. */
	ModelResourceLocation standaloneModel(ResourceLocation id);

	void renderModel(ModelBlockRenderer renderer, PoseStack.Pose pose, VertexConsumer consumer, BlockState state, BakedModel model,
			float red, float green, float blue, int light, int overlay, RenderType renderType);

	/** A key that only works (and only conflicts) in game, not in screens. */
	KeyMapping inGameKey(String name, InputConstants.Type type, int key, String category);

	/** Would these two keys clash if bound to the same button? */
	boolean keysConflict(KeyMapping a, KeyMapping b);

	/** The mod list's config button for the client settings (NeoForge's own screen; Fabric: Mod Menu, if installed). */
	void registerConfigScreen();
}
