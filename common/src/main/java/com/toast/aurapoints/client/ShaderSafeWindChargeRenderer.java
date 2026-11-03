package com.toast.aurapoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.toast.aurapoints.config.AuraClientConfig;
import net.minecraft.client.model.WindChargeModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.WindChargeRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;

/**
 * CLIENT ONLY. Replaces the vanilla wind charge renderer. A held Air Bending charge is drawn
 * shifted onto the hand (AirChargeSmoothing). With shaderSafeWindCharges off it is otherwise
 * exactly vanilla. On, the model is drawn with the ordinary translucent entity render type
 * instead of vanilla's breeze_wind one (scrolling texture), which many shader packs draw black.
 */
public class ShaderSafeWindChargeRenderer extends WindChargeRenderer {

	private static final float MIN_CAMERA_DISTANCE_SQUARED = Mth.square(3.5F);
	private final WindChargeModel model;

	public ShaderSafeWindChargeRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.model = new WindChargeModel(context.bakeLayer(ModelLayers.WIND_CHARGE));
	}

	@Override
	public void render(AbstractWindCharge charge, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
		// a held Air Bending charge glides onto the hand drawn here (AirChargeSmoothing)
		net.minecraft.world.phys.Vec3 shift = AirChargeSmoothing.offset(charge, partialTicks);
		pose.pushPose();
		pose.translate(shift.x, shift.y, shift.z);
		try {
			draw(charge, yaw, partialTicks, pose, buffers, light);
		} finally {
			pose.popPose();
		}
	}

	private void draw(AbstractWindCharge charge, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
		if (!AuraClientConfig.SHADER_SAFE_WIND_CHARGES.get()) {
			super.render(charge, yaw, partialTicks, pose, buffers, light);
			return;
		}
		// same as vanilla: not drawn in your face for its first 2 ticks
		if (charge.tickCount < 2 && this.entityRenderDispatcher.camera.getEntity().distanceToSqr(charge) < MIN_CAMERA_DISTANCE_SQUARED) return;
		float age = charge.tickCount + partialTicks;
		this.model.setupAnim(charge, 0.0F, 0.0F, age, 0.0F, 0.0F);
		this.model.renderToBuffer(pose, buffers.getBuffer(RenderType.entityTranslucent(getTextureLocation(charge))), light, OverlayTexture.NO_OVERLAY);
	}
}
