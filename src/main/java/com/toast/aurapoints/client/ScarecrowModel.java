package com.toast.aurapoints.client;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/**
 * CLIENT ONLY. The training dummy as a scarecrow: a burlap sack head under a straw hat, a patched tunic,
 * straw arms with tufts for hands, standing on one pole. The parts keep the humanoid names and joints, so the
 * dummy's pose and wobble (TrainingDummyRenderer) and the armor layer work unchanged. Texture:
 * textures/entity/scarecrow.png (64x64, box UV as laid out below).
 */
public final class ScarecrowModel {

	private ScarecrowModel() {
	}

	public static final ModelLayerLocation LAYER = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("aurapoints", "scarecrow"), "main");
	public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("aurapoints", "textures/entity/scarecrow.png");

	public static LayerDefinition createBodyLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4.5f, -9f, -4.5f, 9, 9, 9), PartPose.ZERO);
		root.addOrReplaceChild("hat", CubeListBuilder.create()
				.texOffs(0, 18).addBox(-6f, -9.5f, -6f, 12, 1, 12)
				.texOffs(36, 0).addBox(-3.5f, -13f, -3.5f, 7, 4, 7), PartPose.ZERO);
		root.addOrReplaceChild("body", CubeListBuilder.create()
				.texOffs(0, 32).addBox(-4f, 0f, -2.5f, 8, 12, 5)
				.texOffs(0, 50).addBox(-4.5f, 10f, -3f, 9, 2, 6), PartPose.ZERO);
		root.addOrReplaceChild("right_arm", CubeListBuilder.create()
				.texOffs(26, 32).addBox(-2.5f, -2f, -1.5f, 3, 11, 3)
				.texOffs(46, 32).addBox(-3f, 8f, -2f, 4, 2, 4), PartPose.offset(-5f, 2f, 0f));
		root.addOrReplaceChild("left_arm", CubeListBuilder.create().mirror()
				.texOffs(26, 32).addBox(-0.5f, -2f, -1.5f, 3, 11, 3)
				.texOffs(46, 32).addBox(-1f, 8f, -2f, 4, 2, 4), PartPose.offset(5f, 2f, 0f));
		// the pole stands in the middle (the right leg's joint is 1.9 px off center) and goes 2 px into the ground
		root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(38, 32).addBox(0.9f, 0f, -1f, 2, 14, 2), PartPose.offset(-1.9f, 12f, 0f));
		root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9f, 12f, 0f));
		return LayerDefinition.create(mesh, 64, 64);
	}
}
