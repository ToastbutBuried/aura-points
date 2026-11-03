package com.toast.aurapoints.fabric.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.ClientPlatform;
import com.toast.aurapoints.shim.fml.event.lifecycle.FMLClientSetupEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ClientPlayerNetworkEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.EntityRenderersEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ModelEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterClientCommandsEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterGuiLayersEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterKeyMappingsEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RenderLevelStageEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ScreenEvent;
import com.toast.aurapoints.shim.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

/** Fabric client entry point: client services, then the client registration and events. */
public final class AuraFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// like on NeoForge, Player Animator is required on every client (the server doesn't need it)
		if (!FabricLoader.getInstance().isModLoaded("playeranimator")) {
			throw new IllegalStateException("Aura Points needs Player Animator (playeranimator) installed on the client.");
		}

		AuraBus.BUS.post(new RegisterKeyMappingsEvent(KeyBindingHelper::registerKeyBinding));
		AuraBus.BUS.post(new RegisterGuiLayersEvent((above, id, layer) -> GuiLayers.add(above, layer)));
		AuraBus.BUS.post(new EntityRenderersEvent.RegisterLayerDefinitions((layer, definition) ->
				EntityModelLayerRegistry.registerModelLayer(layer, definition::get)));
		AuraBus.BUS.post(new EntityRenderersEvent.RegisterRenderers(new EntityRenderersEvent.RendererSink() {
			@Override
			@SuppressWarnings("unchecked")
			public <T extends Entity> void accept(EntityType<? extends T> type, EntityRendererProvider<T> provider) {
				EntityRendererRegistry.register((EntityType<T>) type, provider);
			}
		}));
		List<ModelResourceLocation> models = new ArrayList<>();
		AuraBus.BUS.post(new ModelEvent.RegisterAdditional(models::add));
		ModelLoadingPlugin.register(ctx -> ctx.addModels(models.stream().map(ModelResourceLocation::id).toList()));
		AuraBus.BUS.post(new RegisterClientExtensionsEvent((ext, items) -> {
			for (var item : items) {
				BuiltinItemRendererRegistry.INSTANCE.register(item, (stack, mode, pose, buffers, light, overlay) -> {
					var renderer = ext.getCustomRenderer();
					if (renderer != null) renderer.renderByItem(stack, mode, pose, buffers, light, overlay);
				});
			}
		}));

		ClientTickEvents.START_CLIENT_TICK.register(mc -> AuraBus.BUS.post(new ClientTickEvent.Pre()));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> AuraBus.BUS.post(new ClientTickEvent.Post()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> AuraBus.BUS.post(new ClientPlayerNetworkEvent.LoggingIn(mc.player)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> AuraBus.BUS.post(new ClientPlayerNetworkEvent.LoggingOut(mc.player)));
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> AuraBus.BUS.post(new ScreenEvent.Init.Post(screen)));
		WorldRenderEvents.AFTER_ENTITIES.register(ctx -> AuraBus.BUS.post(new RenderLevelStageEvent(RenderLevelStageEvent.Stage.AFTER_ENTITIES,
				ctx.worldRenderer(), new PoseStack(), ctx.positionMatrix(), ctx.projectionMatrix(), Minecraft.getInstance().getTimer(), ctx.camera())));
		ClientCommandRegistrationCallback.EVENT.register(AuraFabricClient::clientCommands);

		AuraBus.BUS.post(new FMLClientSetupEvent(Runnable::run));
	}

	/**
	 * The mod's client commands are written for NeoForge's CommandSourceStack dispatcher; each top-level command is
	 * offered to Fabric under the same name and runs the original (Aura Points' client commands take no arguments
	 * and don't read their source).
	 */
	private static void clientCommands(CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> fabric,
			CommandBuildContext context) {
		CommandDispatcher<CommandSourceStack> ours = new CommandDispatcher<>();
		AuraBus.BUS.post(new RegisterClientCommandsEvent(ours, context));
		for (CommandNode<CommandSourceStack> node : ours.getRoot().getChildren()) {
			var command = node.getCommand();
			if (command == null) continue;
			fabric.register(ClientCommandManager.literal(node.getName()).executes(ctx -> command.run(null)));
		}
	}
}
