package com.toast.aurapoints.fabric.mixin.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.toast.aurapoints.fabric.client.Emissive;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.util.GsonHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.lang.reflect.Type;

/**
 * NeoForge's per-face "neoforge_data": {"block_light", "sky_light"} in item models (the glowing parts of the weapons,
 * the frost shard, ...): read with the face, carried onto its baked quad, and drawn at least that bright.
 */
public final class EmissiveFacesMixin {
	private EmissiveFacesMixin() {
	}

	@Mixin(BlockElementFace.class)
	public abstract static class Face implements Emissive {
		@Unique
		private int aurapoints$light;

		@Override
		public int aurapoints$light() {
			return aurapoints$light;
		}

		@Override
		public void aurapoints$setLight(int light) {
			aurapoints$light = light;
		}
	}

	@Mixin(BakedQuad.class)
	public abstract static class Quad implements Emissive {
		@Unique
		private int aurapoints$light;

		@Override
		public int aurapoints$light() {
			return aurapoints$light;
		}

		@Override
		public void aurapoints$setLight(int light) {
			aurapoints$light = light;
		}
	}

	@Mixin(targets = "net.minecraft.client.renderer.block.model.BlockElementFace$Deserializer")
	public abstract static class Read {
		@ModifyReturnValue(method = "deserialize(Lcom/google/gson/JsonElement;Ljava/lang/reflect/Type;Lcom/google/gson/JsonDeserializationContext;)Lnet/minecraft/client/renderer/block/model/BlockElementFace;",
				at = @At("RETURN"))
		private BlockElementFace aurapoints$readLight(BlockElementFace face, JsonElement json, Type type, JsonDeserializationContext context) {
			JsonObject o = json.getAsJsonObject();
			if (o.has("neoforge_data")) {
				JsonObject data = GsonHelper.getAsJsonObject(o, "neoforge_data");
				int block = GsonHelper.getAsInt(data, "block_light", 0), sky = GsonHelper.getAsInt(data, "sky_light", 0);
				if (block > 0 || sky > 0) ((Emissive) (Object) face).aurapoints$setLight(LightTexture.pack(block, sky) | 1);
			}
			return face;
		}
	}

	@Mixin(BlockModel.class)
	public abstract static class Bake {
		@ModifyReturnValue(method = "bakeFace", at = @At("RETURN"))
		private static BakedQuad aurapoints$bakeLight(BakedQuad quad, BlockElement element, BlockElementFace face, TextureAtlasSprite sprite,
				Direction direction, ModelState state) {
			int light = ((Emissive) (Object) face).aurapoints$light();
			if (light != 0) ((Emissive) quad).aurapoints$setLight(light);
			return quad;
		}
	}

	@Mixin(ItemRenderer.class)
	public abstract static class Draw {
		@WrapOperation(method = "renderQuadList", at = @At(value = "INVOKE",
				target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;FFFFII)V"))
		private void aurapoints$drawLight(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad, float r, float g, float b, float a, int light, int overlay,
				Operation<Void> original) {
			int baked = ((Emissive) quad).aurapoints$light();
			if (baked != 0) {
				light = LightTexture.pack(Math.max(LightTexture.block(light), LightTexture.block(baked)), Math.max(LightTexture.sky(light), LightTexture.sky(baked)));
			}
			original.call(consumer, pose, quad, r, g, b, a, light, overlay);
		}
	}
}
