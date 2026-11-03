package com.toast.aurapoints.client;

import com.toast.aurapoints.arena.ArenaNpc;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

/** The Aura Arena's NPCs: a player model in a vanilla default skin, its kind's armor and held item. */
public class ArenaNpcRenderer extends HumanoidMobRenderer<ArenaNpc, PlayerModel<ArenaNpc>> {

	public ArenaNpcRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
		addLayer(new HumanoidArmorLayer<>(this,
				new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
				new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
	}

	@Override
	public ResourceLocation getTextureLocation(ArenaNpc npc) {
		// arena staff skins in the kind's color (textures/entity/arena_npc/<kind>.png)
		return ResourceLocation.fromNamespaceAndPath("aurapoints", "textures/entity/arena_npc/" + npc.kind().name().toLowerCase(java.util.Locale.ROOT) + ".png");
	}
}
