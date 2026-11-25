package com.toast.aurapoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.toast.aurapoints.geo.Reaper;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/** CLIENT ONLY. The Shadow reaper: turned by its own yaw (never the lagging body yaw), scaled by generic.scale (GeckoLib does that). */
public class ReaperRenderer extends GeoEntityRenderer<Reaper> {

	public ReaperRenderer(EntityRendererProvider.Context context) {
		super(context, new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath("aurapoints", "reaper")));
		this.shadowRadius = 0;
		addRenderLayer(new AutoGlowingGeoLayer<>(this));
	}

	/** The model faces -Z; no death tilt or shake. */
	@Override
	protected void applyRotations(Reaper reaper, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
		poseStack.mulPose(Axis.YP.rotationDegrees(180f - Mth.rotLerp(partialTick, reaper.yRotO, reaper.getYRot())));
	}

	/** A living entity that is no Mob always wears its type's name; this one only a custom name that is set visible. */
	@Override
	public boolean shouldShowName(Reaper reaper) {
		return reaper.hasCustomName() && reaper.isCustomNameVisible() && super.shouldShowName(reaper);
	}
}
