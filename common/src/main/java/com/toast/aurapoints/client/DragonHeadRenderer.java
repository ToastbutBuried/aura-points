package com.toast.aurapoints.client;

import com.toast.aurapoints.geo.AuraGeoRenderer;
import com.toast.aurapoints.geo.DragonHead;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/**
 * CLIENT ONLY. Draws the dragon head with the entity's own pitch applied to the model's root bone, whose pivot is the
 * rider's seat (dragon/gen.py SEAT): the head tips around the rider instead of through them.
 */
public class DragonHeadRenderer extends AuraGeoRenderer<DragonHead> {

	public DragonHeadRenderer(EntityRendererProvider.Context context) {
		super(context, new DefaultedEntityGeoModel<DragonHead>(ResourceLocation.fromNamespaceAndPath("aurapoints", "dragon_head")) {
			@Override
			public void setCustomAnimations(DragonHead head, long instanceId, AnimationState<DragonHead> state) {
				GeoBone root = getAnimationProcessor().getBone("root");
				if (root != null) root.setRotX(-Mth.lerp(state.getPartialTick(), head.xRotO, head.getXRot()) * Mth.DEG_TO_RAD);
			}
		}, true);
	}

	/**
	 * GeckoLib hands non-living entities a yaw of 0, so every other ability model keeps facing -Z. The head follows its
	 * real Rotation: base turns by 180 - yaw, AuraGeoRenderer.preRender by another 180, so pass yaw + 180 to land on
	 * model forward (-Z) = the entity's forward at every yaw.
	 */
	@Override
	protected void applyRotations(DragonHead head, com.mojang.blaze3d.vertex.PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
		super.applyRotations(head, poseStack, ageInTicks, Mth.rotLerp(partialTick, head.yRotO, head.getYRot()) + 180f, partialTick, nativeScale);
	}
}
