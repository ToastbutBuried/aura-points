package com.toast.aurapoints.geo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * CLIENT ONLY. Draws an {@link AuraGeoEntity} from assets/aurapoints/geo/entity/NAME.geo.json,
 * animations/entity/NAME.animation.json and textures/entity/NAME.png, scaled by the entity's own scale. Models are
 * built facing -Z (Blockbench's front view), like the viper's jaws.
 */
public class AuraGeoRenderer<T extends AuraGeoEntity> extends GeoEntityRenderer<T> {

	public AuraGeoRenderer(EntityRendererProvider.Context context, String name) {
		this(context, name, false);
	}

	/** {@code glow}: textures/entity/NAME_glowmask.png marks pixels drawn full-bright (the shadow clone's eyes). */
	public AuraGeoRenderer(EntityRendererProvider.Context context, String name, boolean glow) {
		super(context, new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath("aurapoints", name)));
		this.shadowRadius = 0;
		if (glow) addRenderLayer(new software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<>(this));
	}

	@Override
	public void preRender(PoseStack poseStack, T animatable, BakedGeoModel model, MultiBufferSource bufferSource, VertexConsumer buffer,
			boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		if (!isReRender) {
			float s = animatable.scale();
			poseStack.scale(s, s, s);
			// our models face -Z in Blockbench (its "front" view); GeckoLib's entity turn leaves that facing backwards
			poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
		}
		super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
	}
}
