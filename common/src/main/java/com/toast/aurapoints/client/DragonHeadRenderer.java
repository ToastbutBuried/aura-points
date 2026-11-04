package com.toast.aurapoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.toast.aurapoints.geo.DragonHead;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * CLIENT ONLY. Draws the dragon head with the entity's own pitch applied to the model's root bone, whose pivot is the
 * rider's seat (dragon/gen.py SEAT): the head tips around the rider instead of through them. GeckoLib already scales
 * living entities by their generic.scale attribute; the eyes and glowing tips come from dragon_head_glowmask.png.
 */
public class DragonHeadRenderer extends GeoEntityRenderer<DragonHead> {

	public DragonHeadRenderer(EntityRendererProvider.Context context) {
		super(context, new DefaultedEntityGeoModel<DragonHead>(ResourceLocation.fromNamespaceAndPath("aurapoints", "dragon_head")) {
			@Override
			public void setCustomAnimations(DragonHead head, long instanceId, AnimationState<DragonHead> state) {
				GeoBone root = getAnimationProcessor().getBone("root");
				if (root != null) root.setRotX(-Mth.lerp(state.getPartialTick(), head.xRotO, head.getXRot()) * Mth.DEG_TO_RAD);
			}
		});
		this.shadowRadius = 0;
		addRenderLayer(new AutoGlowingGeoLayer<>(this));
	}

	@Override
	public void preRender(PoseStack poseStack, DragonHead head, BakedGeoModel model, MultiBufferSource bufferSource, VertexConsumer buffer,
			boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		// the model faces -Z in Blockbench (its "front" view); GeckoLib's entity turn leaves that facing backwards
		if (!isReRender) poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
		super.preRender(poseStack, head, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
	}

	/**
	 * Turn by the entity's own Rotation (not the body yaw GeckoLib hands living entities, which lags behind): base
	 * turns by 180 - yaw, preRender by another 180, so pass yaw + 180 to land on model forward (-Z) = the entity's
	 * forward at every yaw. No death tilt or shake: the head never dies or freezes.
	 */
	@Override
	protected void applyRotations(DragonHead head, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
		poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180f - (Mth.rotLerp(partialTick, head.yRotO, head.getYRot()) + 180f)));
	}
}
