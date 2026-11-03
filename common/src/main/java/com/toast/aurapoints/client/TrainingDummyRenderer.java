package com.toast.aurapoints.client;

import com.toast.aurapoints.arena.TrainingDummy;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** The Aura Arena's training dummy: a straw scarecrow, arms out, that tips over on its pole away from a hit and springs back (and wears the hall's armor, if any). */
public class TrainingDummyRenderer extends HumanoidMobRenderer<TrainingDummy, HumanoidModel<TrainingDummy>> {

	public TrainingDummyRenderer(EntityRendererProvider.Context context) {
		super(context, new Model(context.bakeLayer(ScarecrowModel.LAYER)), 0.4f);
		// the armor the hall's "Dummy armor" button puts on (it follows the arms-out pose)
		addLayer(new net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer<>(this,
				new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
				new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
	}

	/** The whole dummy tips over on its pole, away from the hit, and springs back (TrainingDummy.wobble). */
	@Override
	protected void setupRotations(TrainingDummy dummy, com.mojang.blaze3d.vertex.PoseStack pose, float bob, float yBodyRot, float partialTick, float scale) {
		float w = dummy.wobble(partialTick, 0f);
		if (w != 0f) {
			double r = Math.toRadians(dummy.hitYaw());
			float dx = (float) -Math.sin(r), dz = (float) Math.cos(r);
			// rotating about (dz, 0, -dx) tips the top toward (dx, 0, dz)
			pose.mulPose(new org.joml.Quaternionf().rotationAxis(w, dz, 0f, -dx));
		}
		super.setupRotations(dummy, pose, bob, yBodyRot, partialTick, scale);
	}

	@Override
	public ResourceLocation getTextureLocation(TrainingDummy dummy) {
		return ScarecrowModel.TEXTURE;
	}

	private static final class Model extends HumanoidModel<TrainingDummy> {

		Model(net.minecraft.client.model.geom.ModelPart root) {
			super(root);
		}

		@Override
		public void setupAnim(TrainingDummy dummy, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
			// the whole dummy tips with a hit (the renderer's setupRotations); the head and the floppy arms follow late
			float partial = ageInTicks - dummy.tickCount;
			float lag = dummy.wobble(partial, 2.5f), flop = Math.abs(dummy.wobble(partial, 1.5f));
			head.xRot = 0.15f + lag * 0.6f;
			head.yRot = 0f;
			head.zRot = lag * 0.5f;
			hat.copyFrom(head);
			body.xRot = 0f;
			body.yRot = 0f;
			body.zRot = 0f;
			// arms straight out to the sides, like on a pole; a hit makes them flap
			rightArm.xRot = 0f;
			rightArm.yRot = 0f;
			rightArm.zRot = Mth.HALF_PI - 0.12f - flop * 0.9f;
			leftArm.xRot = 0f;
			leftArm.yRot = 0f;
			leftArm.zRot = -Mth.HALF_PI + 0.12f + flop * 0.9f;
			rightLeg.xRot = 0f;
			rightLeg.yRot = 0f;
			rightLeg.zRot = 0.02f;
			leftLeg.xRot = 0f;
			leftLeg.yRot = 0f;
			leftLeg.zRot = -0.02f;
		}
	}
}
