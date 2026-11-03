package com.toast.aurapoints.neoforge;

import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * NeoForge events -> shim events (common and server side). Each bridge listens at exactly the priorities the mod's
 * listeners use, builds the shim event from the NeoForge one, posts it to that priority's listeners and writes every
 * result (cancel, amounts, names, ...) straight back, so the NeoForge event ends up as it did before the split.
 */
final class NeoBridge {
	private NeoBridge() {
	}

	static <N extends net.neoforged.bus.api.Event, S extends Event> void bridge(IEventBus bus, Class<N> neo, Class<S> shim,
			Function<N, S> in, BiConsumer<S, N> out) {
		for (var p : AuraBus.BUS.priorities(shim)) {
			bus.addListener(EventPriority.valueOf(p.name()), false, neo, e -> {
				S s = in.apply(e);
				AuraBus.BUS.post(p, s);
				out.accept(s, e);
			});
		}
	}

	/** Shim cancel -> NeoForge cancel. */
	static <S extends Event, N extends net.neoforged.bus.api.Event> void cancel(S s, N e) {
		if (s.isCanceled() && e instanceof ICancellableEvent c) c.setCanceled(true);
	}

	static <S extends Event, N extends net.neoforged.bus.api.Event> void none(S s, N e) {
	}

	static void register(IEventBus mod, IEventBus game) {
		// --- mod bus: setup and registration ---
		bridge(mod, FMLCommonSetupEvent.class, com.toast.aurapoints.shim.fml.event.lifecycle.FMLCommonSetupEvent.class,
				e -> new com.toast.aurapoints.shim.fml.event.lifecycle.FMLCommonSetupEvent(e::enqueueWork), NeoBridge::none);
		bridge(mod, EntityAttributeCreationEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.EntityAttributeCreationEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.EntityAttributeCreationEvent(e::put), NeoBridge::none);
		bridge(mod, BuildCreativeModeTabContentsEvent.class, com.toast.aurapoints.shim.neoforge.event.BuildCreativeModeTabContentsEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.BuildCreativeModeTabContentsEvent(e.getTabKey(), e::accept), NeoBridge::none);
		bridge(mod, RegisterPayloadHandlersEvent.class, com.toast.aurapoints.shim.neoforge.network.event.RegisterPayloadHandlersEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.network.event.RegisterPayloadHandlersEvent(v -> new NeoPayloads(e.registrar(v))), NeoBridge::none);

		// --- game bus: server life cycle, ticks, commands, chat ---
		bridge(game, AddReloadListenerEvent.class, com.toast.aurapoints.shim.neoforge.event.AddReloadListenerEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.AddReloadListenerEvent(e::addListener), NeoBridge::none);
		bridge(game, ServerStartedEvent.class, com.toast.aurapoints.shim.neoforge.event.server.ServerStartedEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.server.ServerStartedEvent(e.getServer()), NeoBridge::none);
		bridge(game, ServerStoppingEvent.class, com.toast.aurapoints.shim.neoforge.event.server.ServerStoppingEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.server.ServerStoppingEvent(e.getServer()), NeoBridge::none);
		bridge(game, ServerStoppedEvent.class, com.toast.aurapoints.shim.neoforge.event.server.ServerStoppedEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.server.ServerStoppedEvent(e.getServer()), NeoBridge::none);
		bridge(game, ServerTickEvent.Pre.class, com.toast.aurapoints.shim.neoforge.event.tick.ServerTickEvent.Pre.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.tick.ServerTickEvent.Pre(e.getServer()), NeoBridge::none);
		bridge(game, ServerTickEvent.Post.class, com.toast.aurapoints.shim.neoforge.event.tick.ServerTickEvent.Post.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.tick.ServerTickEvent.Post(e.getServer()), NeoBridge::none);
		bridge(game, RegisterCommandsEvent.class, com.toast.aurapoints.shim.neoforge.event.RegisterCommandsEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.RegisterCommandsEvent(e.getDispatcher(), e.getCommandSelection(), e.getBuildContext()), NeoBridge::none);
		bridge(game, ServerChatEvent.class, com.toast.aurapoints.shim.neoforge.event.ServerChatEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.ServerChatEvent(e.getPlayer(), e.getRawText(), e.getMessage()),
				(s, e) -> {
					if (s.getMessage() != e.getMessage()) e.setMessage(s.getMessage());
					cancel(s, e);
				});

		// --- entities ---
		bridge(game, EntityJoinLevelEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.EntityJoinLevelEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.EntityJoinLevelEvent(e.getEntity(), e.getLevel(), e.loadedFromDisk()), NeoBridge::cancel);
		bridge(game, EntityLeaveLevelEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.EntityLeaveLevelEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.EntityLeaveLevelEvent(e.getEntity(), e.getLevel()), NeoBridge::none);
		bridge(game, EntityMobGriefingEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.EntityMobGriefingEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.EntityMobGriefingEvent(e.getEntity()),
				(s, e) -> {
					if (s.canGriefOrNull() != null) e.setCanGrief(s.canGriefOrNull());
				});
		bridge(game, EntityTravelToDimensionEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.EntityTravelToDimensionEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.EntityTravelToDimensionEvent(e.getEntity(), e.getDimension()), NeoBridge::cancel);
		bridge(game, FinalizeSpawnEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.living.FinalizeSpawnEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.FinalizeSpawnEvent(e.getEntity(), e.getLevel(), e.getSpawnType()), NeoBridge::cancel);

		// --- living: damage, death, drops ---
		bridge(game, LivingIncomingDamageEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingIncomingDamageEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingIncomingDamageEvent(e.getEntity(), e.getSource(), e.getAmount()),
				(s, e) -> {
					if (s.getAmount() != e.getAmount()) e.setAmount(s.getAmount());
					for (var m : s.reductionModifiers()) {
						e.addReductionModifier(DamageContainer.Reduction.valueOf(m.type().name()), (container, reduction) -> m.function().modify(container, reduction));
					}
					cancel(s, e);
				});
		bridge(game, LivingDamageEvent.Pre.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDamageEvent.Pre.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDamageEvent.Pre(e.getEntity(), e.getSource(), e.getOriginalDamage(), e.getNewDamage()),
				(s, e) -> {
					if (s.getNewDamage() != e.getNewDamage()) e.setNewDamage(s.getNewDamage());
				});
		bridge(game, LivingDamageEvent.Post.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDamageEvent.Post.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDamageEvent.Post(e.getEntity(), e.getSource(), e.getOriginalDamage(), e.getNewDamage()), NeoBridge::none);
		bridge(game, LivingDeathEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDeathEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDeathEvent(e.getEntity(), e.getSource()), NeoBridge::cancel);
		bridge(game, LivingDropsEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDropsEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDropsEvent(e.getEntity(), e.getSource(), e.getDrops()), NeoBridge::cancel);
		bridge(game, LivingExperienceDropEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingExperienceDropEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingExperienceDropEvent(e.getEntity(), e.getAttackingPlayer(), e.getDroppedExperience()),
				(s, e) -> {
					if (s.getDroppedExperience() != e.getDroppedExperience()) e.setDroppedExperience(s.getDroppedExperience());
					cancel(s, e);
				});
		bridge(game, LivingFallEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingFallEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingFallEvent(e.getEntity(), e.getDistance(), e.getDamageMultiplier()),
				(s, e) -> {
					if (s.getDistance() != e.getDistance()) e.setDistance(s.getDistance());
					if (s.getDamageMultiplier() != e.getDamageMultiplier()) e.setDamageMultiplier(s.getDamageMultiplier());
					cancel(s, e);
				});
		bridge(game, LivingHealEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingHealEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingHealEvent(e.getEntity(), e.getAmount()),
				(s, e) -> {
					if (s.getAmount() != e.getAmount()) e.setAmount(s.getAmount());
					cancel(s, e);
				});
		bridge(game, LivingKnockBackEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingKnockBackEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingKnockBackEvent(e.getEntity(), e.getStrength(), e.getRatioX(), e.getRatioZ()),
				(s, e) -> {
					if (s.getStrength() != e.getStrength()) e.setStrength(s.getStrength());
					if (s.getRatioX() != e.getRatioX()) e.setRatioX(s.getRatioX());
					if (s.getRatioZ() != e.getRatioZ()) e.setRatioZ(s.getRatioZ());
					cancel(s, e);
				});
		bridge(game, LivingEntityUseItemEvent.Start.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingEntityUseItemEvent.Start.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingEntityUseItemEvent.Start(e.getEntity(), e.getItem(), e.getDuration()),
				(s, e) -> {
					if (s.getDuration() != e.getDuration()) e.setDuration(s.getDuration());
					cancel(s, e);
				});
		bridge(game, LivingEvent.LivingVisibilityEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.living.LivingEvent.LivingVisibilityEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.living.LivingEvent.LivingVisibilityEvent(e.getEntity(), e.getLookingEntity(), 1.0),
				(s, e) -> {
					if (s.getVisibilityModifier() != 1.0) e.modifyVisibility(s.getVisibilityModifier());
				});

		// --- players ---
		bridge(game, AttackEntityEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.player.AttackEntityEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.AttackEntityEvent(e.getEntity(), e.getTarget()), NeoBridge::cancel);
		bridge(game, PlayerEvent.NameFormat.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.NameFormat.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.NameFormat(e.getEntity(), e.getUsername(), e.getDisplayname()),
				(s, e) -> {
					if (s.getDisplayname() != e.getDisplayname()) e.setDisplayname(s.getDisplayname());
				});
		bridge(game, PlayerEvent.TabListNameFormat.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.TabListNameFormat.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.TabListNameFormat(e.getEntity(), e.getDisplayName()),
				(s, e) -> {
					if (s.getDisplayName() != e.getDisplayName()) e.setDisplayName(s.getDisplayName());
				});
		bridge(game, PlayerEvent.PlayerChangedDimensionEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent(e.getEntity(), e.getFrom(), e.getTo()), NeoBridge::none);
		bridge(game, PlayerEvent.PlayerLoggedInEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(e.getEntity()), NeoBridge::none);
		bridge(game, PlayerEvent.PlayerLoggedOutEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(e.getEntity()), NeoBridge::none);
		bridge(game, PlayerEvent.PlayerRespawnEvent.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent(e.getEntity(), e.isEndConquered()), NeoBridge::none);
		bridge(game, PlayerEvent.StartTracking.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.StartTracking.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent.StartTracking(e.getEntity(), e.getTarget()), NeoBridge::none);
		bridge(game, PlayerInteractEvent.EntityInteract.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(e.getEntity(), e.getHand(), e.getTarget()), NeoBridge::cancel);
		bridge(game, PlayerInteractEvent.RightClickBlock.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(e.getEntity(), e.getHand(), e.getPos()), NeoBridge::cancel);
		bridge(game, PlayerInteractEvent.RightClickItem.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem(e.getEntity(), e.getHand()), NeoBridge::cancel);
		bridge(game, PlayerInteractEvent.LeftClickBlock.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock(e.getEntity(), e.getPos(), e.getFace(),
						com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.valueOf(e.getAction().name())), NeoBridge::cancel);
		bridge(game, PlayerInteractEvent.LeftClickEmpty.class, com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty(e.getEntity()), NeoBridge::none);

		// --- blocks ---
		bridge(game, BlockEvent.BreakEvent.class, com.toast.aurapoints.shim.neoforge.event.level.BlockEvent.BreakEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.level.BlockEvent.BreakEvent(e.getLevel(), e.getPos(), e.getState(), e.getPlayer()), NeoBridge::cancel);
		bridge(game, BlockEvent.EntityPlaceEvent.class, com.toast.aurapoints.shim.neoforge.event.level.BlockEvent.EntityPlaceEvent.class,
				e -> new com.toast.aurapoints.shim.neoforge.event.level.BlockEvent.EntityPlaceEvent(e.getLevel(), e.getPos(), e.getPlacedBlock(), e.getEntity()), NeoBridge::cancel);
	}
}
