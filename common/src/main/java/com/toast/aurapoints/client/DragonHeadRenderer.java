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
 * dragonTransparency, and optionally be left out in first person (dragonHideFirstPerson). Transparency is real alpha
 * blending. Some shader packs cannot blend entities: Photon turns blending off and disables its translucent entity
 * program on 1.21.1 (its shaders.properties), so there the head is drawn as a cutout with a dithered copy of its texture
 * instead (a checker of fully transparent texels, denser the higher the transparency) when dragonShaderSupport is on.
 * Nobody else's view changes.
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

	/** Dither mode (config dragonShaderSupport): no alpha blending, holes instead. For shader packs that cannot blend entities. */
	private static boolean shaderMode() {
		return com.toast.aurapoints.config.AuraClientConfig.DRAGON_SHADER_SUPPORT.get();
	}

	private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("aurapoints", "textures/entity/dragon_head.png");
	/** Dither steps (of 16: how many of the 4x4 pattern's cells are cut out) -> the texture with that many texels gone. */
	private static final java.util.Map<Integer, ResourceLocation> DITHERED = new java.util.HashMap<>();
	/** The 4x4 Bayer matrix: the order the cells of the pattern disappear in, so the holes spread evenly. */
	private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

	/** The head's texture with {@code steps}/16 of its texels made fully transparent in an even pattern (null if it can't be made). */
	private static ResourceLocation dithered(int steps) {
		ResourceLocation have = DITHERED.get(steps);
		if (have != null) return have;
		try (java.io.InputStream in = Minecraft.getInstance().getResourceManager().open(TEXTURE);
				com.mojang.blaze3d.platform.NativeImage image = com.mojang.blaze3d.platform.NativeImage.read(in)) {
			com.mojang.blaze3d.platform.NativeImage out = new com.mojang.blaze3d.platform.NativeImage(image.getWidth(), image.getHeight(), false);
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					out.setPixelRGBA(x, y, BAYER[(y & 3) * 4 + (x & 3)] < steps ? 0 : image.getPixelRGBA(x, y));
				}
			}
			ResourceLocation id = ResourceLocation.fromNamespaceAndPath("aurapoints", "dragon_head_dither_" + steps);
			Minecraft.getInstance().getTextureManager().register(id, new net.minecraft.client.renderer.texture.DynamicTexture(out));
			DITHERED.put(steps, id);
			return id;
		} catch (Exception e) {
			com.toast.aurapoints.AuraPoints.LOGGER.error("Could not build the dragon's dithered texture", e);
			DITHERED.put(steps, TEXTURE);
			return TEXTURE;
		}
	}

	@Override
	public Color getRenderColor(DragonHead head, float partialTick, int packedLight) {
		float a = headAlpha(head);
		return a >= 0.999f || shaderMode() ? super.getRenderColor(head, partialTick, packedLight) : Color.ofRGBA(1f, 1f, 1f, a);
	}

	/**
	 * While the rider picked a transparency: normally alpha blending (the translucent render type); in shader mode a plain
	 * cutout (culling the inside faces) of the dithered texture. The glow layer keeps the original texture, so the eyes still glow.
	 */
	@Override
	public RenderType getRenderType(DragonHead head, ResourceLocation texture, MultiBufferSource bufferSource, float partialTick) {
		float a = headAlpha(head);
		if (a < 0.999f) {
			if (!shaderMode()) return RenderType.entityTranslucent(texture);
			int steps = Math.min(15, Math.round((1f - a) * 16f));
			if (steps > 0) return RenderType.entityCutout(dithered(steps));
		}
		return super.getRenderType(head, texture, bufferSource, partialTick);
	}

	@Override
	public void renderRecursively(PoseStack poseStack, DragonHead head, GeoBone bone, RenderType renderType, MultiBufferSource bufferSource,
			VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
		if (firstPersonRider(head)) {
			// the whole head is left out if the rider asked for it (the root bone holds everything), else just the parts in the way
			if (com.toast.aurapoints.config.AuraClientConfig.DRAGON_HIDE_FIRST_PERSON.get() ? "root".equals(bone.getName()) : blocksView(bone.getName())) return;
		}
		super.renderRecursively(poseStack, head, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
	}
}
