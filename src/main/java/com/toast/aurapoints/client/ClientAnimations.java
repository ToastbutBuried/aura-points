package com.toast.aurapoints.client;

import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** CLIENT ONLY. Gives every player the ability animation layers (Player Animator). */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class ClientAnimations {

	private ClientAnimations() {
	}

	public static final ResourceLocation BENDING = ResourceLocation.fromNamespaceAndPath("aurapoints", "fire_bending");

	@SubscribeEvent
	public static void onClientSetup(FMLClientSetupEvent event) {
		// high priority: drawn over most other animations while bending
		PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(BENDING, 1000, BendingAnimation::new);
		// the client settings get the NeoForge config screen (Mods > Aura Points > Config)
		event.enqueueWork(() -> net.neoforged.fml.ModList.get().getModContainerById("aurapoints").ifPresent(container ->
				container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
						net.neoforged.neoforge.client.gui.ConfigurationScreen::new)));
	}

	/** The effect materials (client/EffectModels), loaded next to the block and item models. */
	@SubscribeEvent
	public static void onRegisterModels(net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional event) {
		EffectModels.registerModels(event);
	}

	/** The training dummy's scarecrow model. */
	@SubscribeEvent
	public static void onRegisterLayers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions event) {
		event.registerLayerDefinition(ScarecrowModel.LAYER, ScarecrowModel::createBodyLayer);
	}

	/** Wind charges drawn by ShaderSafeWindChargeRenderer (vanilla unless shaderSafeWindCharges is on). */
	@SubscribeEvent
	public static void onRegisterRenderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
		event.registerEntityRenderer(net.minecraft.world.entity.EntityType.WIND_CHARGE, ShaderSafeWindChargeRenderer::new);
		event.registerEntityRenderer(com.toast.aurapoints.AuraPoints.TRAINING_DUMMY.get(), TrainingDummyRenderer::new);
		event.registerEntityRenderer(com.toast.aurapoints.AuraPoints.ARENA_CAMERA.get(), net.minecraft.client.renderer.entity.NoopRenderer::new);
		event.registerEntityRenderer(com.toast.aurapoints.AuraPoints.ARENA_NPC.get(), ArenaNpcRenderer::new);
		event.registerEntityRenderer(com.toast.aurapoints.AuraPoints.VIPER_HEAD.get(), ctx -> new com.toast.aurapoints.geo.AuraGeoRenderer<>(ctx, "verdant_viper"));
		event.registerEntityRenderer(com.toast.aurapoints.AuraPoints.SHADOW_CLONE.get(), ctx -> new com.toast.aurapoints.geo.AuraGeoRenderer<>(ctx, "shadow_clone", true));
		event.registerEntityRenderer(com.toast.aurapoints.AuraPoints.SENTINEL_TREE.get(), ctx -> new com.toast.aurapoints.geo.AuraGeoRenderer<>(ctx, "thorn_sentinel"));
		event.registerEntityRenderer(com.toast.aurapoints.AuraPoints.KRAKEN_TENTACLE.get(), ctx -> new com.toast.aurapoints.geo.AuraGeoRenderer<>(ctx, "maelstrom_tentacle"));
		event.registerEntityRenderer(com.toast.aurapoints.AuraPoints.SONIC_BLOSSOM.get(), ctx -> new com.toast.aurapoints.geo.AuraGeoRenderer<>(ctx, "sonic_blossom", true));
		event.registerEntityRenderer(com.toast.aurapoints.AuraPoints.LISTENING_TREE.get(), ctx -> new com.toast.aurapoints.geo.AuraGeoRenderer<>(ctx, "listening_tree", true));
	}
}
