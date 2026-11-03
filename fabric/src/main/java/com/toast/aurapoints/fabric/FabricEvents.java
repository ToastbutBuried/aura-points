package com.toast.aurapoints.fabric;

import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.fml.event.lifecycle.FMLCommonSetupEvent;
import com.toast.aurapoints.shim.neoforge.event.AddReloadListenerEvent;
import com.toast.aurapoints.shim.neoforge.event.BuildCreativeModeTabContentsEvent;
import com.toast.aurapoints.shim.neoforge.event.RegisterCommandsEvent;
import com.toast.aurapoints.shim.neoforge.event.ServerChatEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.EntityAttributeCreationEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.EntityLeaveLevelEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent;
import com.toast.aurapoints.shim.neoforge.event.level.BlockEvent;
import com.toast.aurapoints.shim.neoforge.event.server.ServerStartedEvent;
import com.toast.aurapoints.shim.neoforge.event.server.ServerStoppedEvent;
import com.toast.aurapoints.shim.neoforge.event.server.ServerStoppingEvent;
import com.toast.aurapoints.shim.neoforge.event.tick.ServerTickEvent;
import com.toast.aurapoints.shim.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Fabric API callbacks -> the mod's events (the ones Fabric has no callback for come from fabric/mixin). */
final class FabricEvents {
	private FabricEvents() {
	}

	static void register() {
		// --- start-up: registration is already done (Platform.register registers right away) ---
		AuraBus.BUS.post(new FMLCommonSetupEvent(Runnable::run));
		AuraBus.BUS.post(new EntityAttributeCreationEvent(FabricDefaultAttributeRegistry::register));
		AuraBus.BUS.post(new RegisterPayloadHandlersEvent(version -> new FabricPayloads()));
		for (var key : BuiltInRegistries.CREATIVE_MODE_TAB.registryKeySet()) {
			ItemGroupEvents.modifyEntriesEvent(key).register(entries ->
					AuraBus.BUS.post(new BuildCreativeModeTabContentsEvent(key, (ItemStack stack) -> entries.accept(stack))));
		}

		// --- data reload: the listeners are asked for once and reused on every /reload ---
		List<PreparableReloadListener> reload = new ArrayList<>();
		AuraBus.BUS.post(new AddReloadListenerEvent(reload::add));
		for (int i = 0; i < reload.size(); i++) {
			PreparableReloadListener inner = reload.get(i);
			ResourceLocation id = ResourceLocation.fromNamespaceAndPath("aurapoints", "reload_" + i);
			ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new IdentifiableResourceReloadListener() {
				@Override
				public ResourceLocation getFabricId() {
					return id;
				}

				@Override
				public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager, ProfilerFiller prep, ProfilerFiller apply,
						Executor background, Executor game) {
					return inner.reload(barrier, manager, prep, apply, background, game);
				}

				@Override
				public String getName() {
					return inner.getName();
				}
			});
		}

		// --- server life cycle, ticks, commands, chat ---
		ServerLifecycleEvents.SERVER_STARTING.register(server -> FabricPlatform.server = server);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> AuraBus.BUS.post(new ServerStartedEvent(server)));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> AuraBus.BUS.post(new ServerStoppingEvent(server)));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			AuraBus.BUS.post(new ServerStoppedEvent(server));
			FabricPlatform.server = null;
		});
		ServerTickEvents.START_SERVER_TICK.register(server -> AuraBus.BUS.post(new ServerTickEvent.Pre(server)));
		ServerTickEvents.END_SERVER_TICK.register(server -> AuraBus.BUS.post(new ServerTickEvent.Post(server)));
		CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) ->
				AuraBus.BUS.post(new RegisterCommandsEvent(dispatcher, environment, context)));
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
				!AuraBus.BUS.post(new ServerChatEvent(sender, message.signedContent(), message.decoratedContent())).isCanceled());

		// --- entities (joining a level: mixin/PersistentEntitySectionManagerMixin) ---
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> AuraBus.BUS.post(new EntityLeaveLevelEvent(entity, level)));

		// --- players ---
		// logging in and out: mixin/PlayerListMixin (where NeoForge fires them)
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
				AuraBus.BUS.post(new PlayerEvent.PlayerRespawnEvent(newPlayer, alive)));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
				AuraBus.BUS.post(new PlayerEvent.PlayerChangedDimensionEvent(player, origin.dimension(), destination.dimension())));
		EntityTrackingEvents.START_TRACKING.register((target, player) ->
				AuraBus.BUS.post(new PlayerEvent.StartTracking(player, target)));

		// --- clicks (both sides, like NeoForge's PlayerInteractEvent) ---
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (hit != null) return InteractionResult.PASS; // the "interact at" half; NeoForge's EntityInteract is the other one
			return AuraBus.BUS.post(new PlayerInteractEvent.EntityInteract(player, hand, entity)).isCanceled() ? InteractionResult.FAIL : InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) ->
				AuraBus.BUS.post(new PlayerInteractEvent.RightClickBlock(player, hand, hit.getBlockPos())).isCanceled() ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) ->
				AuraBus.BUS.post(new PlayerInteractEvent.RightClickItem(player, hand)).isCanceled()
						? InteractionResultHolder.fail(player.getItemInHand(hand)) : InteractionResultHolder.pass(player.getItemInHand(hand)));
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
				AuraBus.BUS.post(new PlayerInteractEvent.LeftClickBlock(player, pos, direction,
						PlayerInteractEvent.LeftClickBlock.Action.START))
						.isCanceled() ? InteractionResult.FAIL : InteractionResult.PASS);

		// --- blocks ---
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				!AuraBus.BUS.post(new BlockEvent.BreakEvent(level, pos, state, player)).isCanceled());
	}

	/** Brigadier's selection for Fabric's environment (the same enum). */
	static Commands.CommandSelection selection(Commands.CommandSelection environment) {
		return environment;
	}
}
