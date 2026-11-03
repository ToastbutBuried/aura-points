package com.toast.aurapoints.neoforge;

import com.toast.aurapoints.shim.neoforge.client.event.RenderLevelStageEvent.Stage;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import static com.toast.aurapoints.neoforge.NeoBridge.bridge;

/** NeoForge client events -> shim events (see NeoBridge). Only loaded on the client. */
final class NeoClientBridge {
	private NeoClientBridge() {
	}

	private static Stage stage(RenderLevelStageEvent.Stage s) {
		if (s == RenderLevelStageEvent.Stage.AFTER_SKY) return Stage.AFTER_SKY;
		if (s == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return Stage.AFTER_SOLID_BLOCKS;
		if (s == RenderLevelStageEvent.Stage.AFTER_ENTITIES) return Stage.AFTER_ENTITIES;
		if (s == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return Stage.AFTER_BLOCK_ENTITIES;
		if (s == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return Stage.AFTER_TRANSLUCENT_BLOCKS;
		if (s == RenderLevelStageEvent.Stage.AFTER_PARTICLES) return Stage.AFTER_PARTICLES;
		if (s == RenderLevelStageEvent.Stage.AFTER_WEATHER) return Stage.AFTER_WEATHER;
		if (s == RenderLevelStageEvent.Stage.AFTER_LEVEL) return Stage.AFTER_LEVEL;
		return null;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static void register(IEventBus mod, IEventBus game) {
		// --- mod bus: client setup and registration ---
		bridge(mod, FMLClientSetupEvent.class, com.toast.aurapoints.shim.fml.event.lifecycle.FMLClientSetupEvent.class,
				e -> new com.toast.aurapoints.shim.fml.event.lifecycle.FMLClientSetupEvent(e::enqueueWork), NeoBridge::none);
		bridge(mod, RegisterKeyMappingsEvent.class, com.toast.aurapoints.shim.neoforge.client.event.RegisterKeyMappingsEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.RegisterKeyMappingsEvent(e::register), NeoBridge::none);
		bridge(mod, RegisterGuiLayersEvent.class, com.toast.aurapoints.shim.neoforge.client.event.RegisterGuiLayersEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.RegisterGuiLayersEvent((above, id, layer) -> {
					if (above == null) e.registerAboveAll(id, layer);
					else e.registerAbove(above, id, layer);
				}), NeoBridge::none);
		bridge(mod, EntityRenderersEvent.RegisterLayerDefinitions.class, com.toast.aurapoints.shim.neoforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions(e::registerLayerDefinition), NeoBridge::none);
		bridge(mod, EntityRenderersEvent.RegisterRenderers.class, com.toast.aurapoints.shim.neoforge.client.event.EntityRenderersEvent.RegisterRenderers.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.EntityRenderersEvent.RegisterRenderers(
						new com.toast.aurapoints.shim.neoforge.client.event.EntityRenderersEvent.RendererSink() {
							@Override
							public <T extends net.minecraft.world.entity.Entity> void accept(net.minecraft.world.entity.EntityType<? extends T> type,
									net.minecraft.client.renderer.entity.EntityRendererProvider<T> provider) {
								e.registerEntityRenderer(type, provider);
							}
						}), NeoBridge::none);
		bridge(mod, ModelEvent.RegisterAdditional.class, com.toast.aurapoints.shim.neoforge.client.event.ModelEvent.RegisterAdditional.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ModelEvent.RegisterAdditional(e::register), NeoBridge::none);
		bridge(mod, RegisterClientExtensionsEvent.class, com.toast.aurapoints.shim.neoforge.client.extensions.common.RegisterClientExtensionsEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.extensions.common.RegisterClientExtensionsEvent((ext, items) ->
						e.registerItem(new IClientItemExtensions() {
							@Override
							public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
								return ext.getCustomRenderer();
							}
						}, items)), NeoBridge::none);

		// --- game bus ---
		bridge(game, ClientTickEvent.Pre.class, com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent.Pre.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent.Pre(), NeoBridge::none);
		bridge(game, ClientTickEvent.Post.class, com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent.Post.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent.Post(), NeoBridge::none);
		bridge(game, RenderFrameEvent.Pre.class, com.toast.aurapoints.shim.neoforge.client.event.RenderFrameEvent.Pre.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.RenderFrameEvent.Pre(e.getPartialTick()), NeoBridge::none);
		bridge(game, RenderFrameEvent.Post.class, com.toast.aurapoints.shim.neoforge.client.event.RenderFrameEvent.Post.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.RenderFrameEvent.Post(e.getPartialTick()), NeoBridge::none);
		bridge(game, ClientPlayerNetworkEvent.LoggingIn.class, com.toast.aurapoints.shim.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn(e.getPlayer()), NeoBridge::none);
		bridge(game, ClientPlayerNetworkEvent.LoggingOut.class, com.toast.aurapoints.shim.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut(e.getPlayer()), NeoBridge::none);
		bridge(game, RegisterClientCommandsEvent.class, com.toast.aurapoints.shim.neoforge.client.event.RegisterClientCommandsEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.RegisterClientCommandsEvent(e.getDispatcher(), e.getBuildContext()), NeoBridge::none);
		bridge(game, ContainerScreenEvent.Render.Background.class, com.toast.aurapoints.shim.neoforge.client.event.ContainerScreenEvent.Render.Background.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ContainerScreenEvent.Render.Background(e.getContainerScreen(), e.getGuiGraphics(), e.getMouseX(), e.getMouseY()), NeoBridge::none);
		bridge(game, ScreenEvent.Init.Post.class, com.toast.aurapoints.shim.neoforge.client.event.ScreenEvent.Init.Post.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ScreenEvent.Init.Post(e.getScreen()), NeoBridge::none);
		bridge(game, InputEvent.InteractionKeyMappingTriggered.class, com.toast.aurapoints.shim.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered(e.isAttack() ? 0 : e.isUseItem() ? 1 : 2,
						e.getKeyMapping(), e.getHand(), e.shouldSwingHand()),
				(s, e) -> {
					if (s.shouldSwingHand() != e.shouldSwingHand()) e.setSwingHand(s.shouldSwingHand());
					NeoBridge.cancel(s, e);
				});
		bridge(game, MovementInputUpdateEvent.class, com.toast.aurapoints.shim.neoforge.client.event.MovementInputUpdateEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.MovementInputUpdateEvent(e.getEntity(), e.getInput()), NeoBridge::none);
		bridge(game, PlaySoundEvent.class, com.toast.aurapoints.shim.neoforge.client.event.sound.PlaySoundEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.sound.PlaySoundEvent(e.getEngine(), e.getName(), e.getOriginalSound(), e.getSound()),
				(s, e) -> {
					if (s.getSound() != e.getSound()) e.setSound(s.getSound());
				});
		bridge(game, RenderLevelStageEvent.class, com.toast.aurapoints.shim.neoforge.client.event.RenderLevelStageEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.RenderLevelStageEvent(stage(e.getStage()), e.getLevelRenderer(), e.getPoseStack(),
						e.getModelViewMatrix(), e.getProjectionMatrix(), e.getPartialTick(), e.getCamera()), NeoBridge::none);
		bridge(game, (Class) RenderLivingEvent.Pre.class, com.toast.aurapoints.shim.neoforge.client.event.RenderLivingEvent.Pre.class,
				e -> {
					RenderLivingEvent.Pre r = (RenderLivingEvent.Pre) e;
					return new com.toast.aurapoints.shim.neoforge.client.event.RenderLivingEvent.Pre(r.getEntity(), r.getRenderer(), r.getPartialTick(),
							r.getPoseStack(), r.getMultiBufferSource(), r.getPackedLight());
				}, NeoBridge::cancel);
		bridge(game, (Class) RenderLivingEvent.Post.class, com.toast.aurapoints.shim.neoforge.client.event.RenderLivingEvent.Post.class,
				e -> {
					RenderLivingEvent.Post r = (RenderLivingEvent.Post) e;
					return new com.toast.aurapoints.shim.neoforge.client.event.RenderLivingEvent.Post(r.getEntity(), r.getRenderer(), r.getPartialTick(),
							r.getPoseStack(), r.getMultiBufferSource(), r.getPackedLight());
				}, NeoBridge::none);
		bridge(game, RenderPlayerEvent.Pre.class, com.toast.aurapoints.shim.neoforge.client.event.RenderPlayerEvent.Pre.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.RenderPlayerEvent.Pre(e.getEntity(), e.getRenderer(), e.getPartialTick(),
						e.getPoseStack(), e.getMultiBufferSource(), e.getPackedLight()), NeoBridge::cancel);
		bridge(game, RenderPlayerEvent.Post.class, com.toast.aurapoints.shim.neoforge.client.event.RenderPlayerEvent.Post.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.RenderPlayerEvent.Post(e.getEntity(), e.getRenderer(), e.getPartialTick(),
						e.getPoseStack(), e.getMultiBufferSource(), e.getPackedLight()), NeoBridge::none);
		bridge(game, ViewportEvent.ComputeCameraAngles.class, com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent.ComputeCameraAngles.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent.ComputeCameraAngles(e.getCamera(), e.getPartialTick(), e.getYaw(), e.getPitch(), e.getRoll()),
				(s, e) -> {
					if (s.getYaw() != e.getYaw()) e.setYaw(s.getYaw());
					if (s.getPitch() != e.getPitch()) e.setPitch(s.getPitch());
					if (s.getRoll() != e.getRoll()) e.setRoll(s.getRoll());
				});
		bridge(game, ViewportEvent.ComputeFogColor.class, com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent.ComputeFogColor.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent.ComputeFogColor(e.getCamera(), e.getPartialTick(), e.getRed(), e.getGreen(), e.getBlue()),
				(s, e) -> {
					if (s.getRed() != e.getRed()) e.setRed(s.getRed());
					if (s.getGreen() != e.getGreen()) e.setGreen(s.getGreen());
					if (s.getBlue() != e.getBlue()) e.setBlue(s.getBlue());
				});
		bridge(game, ViewportEvent.ComputeFov.class, com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent.ComputeFov.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent.ComputeFov(e.getCamera(), e.getPartialTick(), e.getFOV(), e.usedConfiguredFov()),
				(s, e) -> {
					if (s.getFOV() != e.getFOV()) e.setFOV(s.getFOV());
				});
		bridge(game, ViewportEvent.RenderFog.class, com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent.RenderFog.class,
				e -> new com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent.RenderFog(e.getCamera(), e.getMode(), e.getType(), e.getFarPlaneDistance(),
						e.getNearPlaneDistance(), e.getFogShape()),
				(s, e) -> {
					if (s.getFarPlaneDistance() != e.getFarPlaneDistance()) e.setFarPlaneDistance(s.getFarPlaneDistance());
					if (s.getNearPlaneDistance() != e.getNearPlaneDistance()) e.setNearPlaneDistance(s.getNearPlaneDistance());
					if (s.getFogShape() != e.getFogShape()) e.setFogShape(s.getFogShape());
					NeoBridge.cancel(s, e);
				});
	}
}
