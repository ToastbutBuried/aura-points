package com.toast.aurapoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.toast.aurapoints.geo.FrostBear;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * CLIENT ONLY. The Frost Bear: geo/entity/frost_bear.geo.json, a texture per variant (textures/entity/frost_bear_N.png),
 * and the "spike_man" bone shown only in the first stage. generic.scale is applied by GeckoLib itself. Models face -Z.
 */
public class FrostBearRenderer extends GeoEntityRenderer<FrostBear> {
	private static final ResourceLocation[] SKINS = new ResourceLocation[FrostBear.VARIANTS];

	static {
		for (int i = 0; i < SKINS.length; i++) SKINS[i] = ResourceLocation.fromNamespaceAndPath("aurapoints", "textures/entity/frost_bear_" + i + ".png");
	}

	public FrostBearRenderer(EntityRendererProvider.Context context) {
		super(context, new DefaultedEntityGeoModel<FrostBear>(ResourceLocation.fromNamespaceAndPath("aurapoints", "frost_bear")) {
			@Override
			public ResourceLocation getTextureResource(FrostBear bear) {
				return SKINS[bear.variant()];
			}
		});
		this.shadowRadius = 1.3f;
		// frost_bear_N_glowmask.png: the eyes, the ice crystals and the antlers shine
		addRenderLayer(new software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<>(this));
	}

	@Override
	public void preRender(PoseStack poseStack, FrostBear bear, BakedGeoModel model, MultiBufferSource bufferSource, VertexConsumer buffer,
			boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		// no extra turn: a living GeckoLib entity is already turned 180 - yaw, which suits a model that faces -Z
		boolean spiked = bear.phase() < FrostBear.MAD; // the spike man is in the bear until it is thrown out (his eject animation plays him out)
		model.getBone("spike_man").ifPresent(bone -> bone.setHidden(!spiked));
		super.preRender(poseStack, bear, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
	}
}
