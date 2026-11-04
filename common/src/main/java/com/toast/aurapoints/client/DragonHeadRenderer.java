package com.toast.aurapoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.toast.aurapoints.geo.DragonHead;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;
import software.bernie.geckolib.util.Color;

/**
 * CLIENT ONLY. Draws the dragon head with the entity's own pitch applied to the model's root bone, whose pivot is the
 * rider's seat (dragon/gen.py SEAT): the head tips around the rider instead of through them. GeckoLib already scales
 * living entities by their generic.scale attribute; the eyes and glowing tips come from dragon_head_glowmask.png.
 * The RIDER's view is eased: in first person the mane along the top of the skull and the nose horn (the parts at eye
 * level of a big dragon) are not drawn at all, and the whole head can be made see-through with the client option
 * dragonTransparency. With a shader pack (Iris/Oculus: detected, or forced by dragonShaderSupport) alpha blending can't be
 * trusted, so the transparency hides the head's parts step by step instead. Nobody else's view changes.
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

	/** Is this bone in the rider's way in first person? The mane (mane0..mane5) and the nose horn: not drawn at all, so it works with shader packs too. */
	private static boolean blocksView(String bone) {
		return bone.startsWith("mane") || bone.equals("nose_horn");
	}

	/** Is the local player riding this head? */
	private static boolean ridden(DragonHead head) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && head.hasPassenger(mc.player);
	}

	private static boolean firstPersonRider(DragonHead head) {
		return ridden(head) && Minecraft.getInstance().options.getCameraType().isFirstPerson();
	}

	/** The head's alpha for the local player: 1 for everyone but the rider, who picks it in the client config. */
	private static float headAlpha(DragonHead head) {
		return ridden(head) ? 1f - (float) (double) com.toast.aurapoints.config.AuraClientConfig.DRAGON_TRANSPARENCY.get() : 1f;
	}

	private static java.lang.reflect.Method irisInUse;
	private static Object irisApi;
	private static boolean irisChecked;

	/** A shader pack is on (Iris, or Oculus which has the same API)? Best effort by reflection; false if neither is there. */
	private static boolean irisShaders() {
		if (!irisChecked) {
			irisChecked = true;
			for (ClassLoader loader : new ClassLoader[]{DragonHeadRenderer.class.getClassLoader(), Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader()}) {
				try {
					Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false, loader);
					irisApi = c.getMethod("getInstance").invoke(null);
					irisInUse = c.getMethod("isShaderPackInUse");
					break;
				} catch (Throwable ignored) {
					// not this loader / no Iris
				}
			}
		}
		if (irisInUse == null) return false;
		try {
			return Boolean.TRUE.equals(irisInUse.invoke(irisApi));
		} catch (Throwable t) {
			return false;
		}
	}

	/** Shader mode: no alpha blending at all (packs handle it unpredictably), the transparency hides parts instead. */
	private static boolean shaderMode() {
		return com.toast.aurapoints.config.AuraClientConfig.DRAGON_SHADER_SUPPORT.get() || irisShaders();
	}

	/**
	 * From which transparency a bone is not drawn in shader mode (a bone's children go with it, so a parent is never
	 * hidden before its children): decoration first, the skull last (that is the whole head).
	 */
	private static float hideAt(String bone) {
		if (bone.startsWith("mane") || bone.equals("nose_horn") || bone.startsWith("brow_spike") || bone.startsWith("tine")) return 0.2f;
		if (bone.startsWith("horn") || bone.startsWith("whisker") || bone.startsWith("beard") || bone.startsWith("fin")) return 0.4f;
		if (bone.startsWith("brow") || bone.equals("jaw")) return 0.6f;
		if (bone.equals("snout")) return 0.7f;
		if (bone.equals("skull")) return 0.8f;
		return Float.MAX_VALUE;
	}

	@Override
	public Color getRenderColor(DragonHead head, float partialTick, int packedLight) {
		float a = headAlpha(head);
		return a >= 0.999f || shaderMode() ? super.getRenderColor(head, partialTick, packedLight) : Color.ofRGBA(1f, 1f, 1f, a);
	}

	/** Alpha blending needs the translucent render type, used only while the rider picked a transparency and no shader pack is on. */
	@Override
	public RenderType getRenderType(DragonHead head, ResourceLocation texture, MultiBufferSource bufferSource, float partialTick) {
		if (headAlpha(head) < 0.999f && !shaderMode()) return RenderType.entityTranslucent(texture);
		return super.getRenderType(head, texture, bufferSource, partialTick);
	}

	@Override
	public void renderRecursively(PoseStack poseStack, DragonHead head, GeoBone bone, RenderType renderType, MultiBufferSource bufferSource,
			VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		if (ridden(head)) {
			String name = bone.getName();
			if (blocksView(name) && firstPersonRider(head)) return;
			float t = 1f - headAlpha(head);
			if (t > 0.001f && shaderMode() && t >= hideAt(name)) return;
		}
		super.renderRecursively(poseStack, head, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
	}
}
