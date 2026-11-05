package com.toast.aurapoints;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraStats;
import com.toast.aurapoints.ability.AuraStunts;
import com.toast.aurapoints.ability.ShadowCloak;
import com.toast.aurapoints.ability.DashDive;
import com.toast.aurapoints.shim.fml.event.lifecycle.FMLCommonSetupEvent;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.loot.AuraConditions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import com.toast.aurapoints.shim.bus.api.IEventBus;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.event.BuildCreativeModeTabContentsEvent;
import com.toast.aurapoints.shim.neoforge.event.RegisterCommandsEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDeathEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingFallEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingIncomingDamageEvent;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDamageEvent;
import com.toast.aurapoints.shim.neoforge.event.server.ServerStartedEvent;
import com.toast.aurapoints.shim.neoforge.event.tick.ServerTickEvent;
import com.toast.aurapoints.shim.neoforge.registries.DeferredRegister;
import com.toast.aurapoints.shim.neoforge.registries.DeferredItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class AuraPoints {

	public static final String MOD_ID = "aurapoints";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
	public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
	public static final DeferredRegister<net.minecraft.world.entity.EntityType<?>> ENTITY_TYPES =
			DeferredRegister.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, MOD_ID);

	/** Frost's own particles (client/FrostParticle): shard = flying ice splinter, burst = flash ring, gust = drifting snow. */
	public static final DeferredRegister<net.minecraft.core.particles.ParticleType<?>> PARTICLE_TYPES =
			DeferredRegister.create(net.minecraft.core.registries.Registries.PARTICLE_TYPE, MOD_ID);
	public static final java.util.function.Supplier<net.minecraft.core.particles.SimpleParticleType> FROST_SHARD =
			PARTICLE_TYPES.register("frost_shard", () -> new net.minecraft.core.particles.SimpleParticleType(false) {});
	public static final java.util.function.Supplier<net.minecraft.core.particles.SimpleParticleType> FROST_BURST =
			PARTICLE_TYPES.register("frost_burst", () -> new net.minecraft.core.particles.SimpleParticleType(false) {});
	public static final java.util.function.Supplier<net.minecraft.core.particles.SimpleParticleType> SNOW_GUST =
			PARTICLE_TYPES.register("snow_gust", () -> new net.minecraft.core.particles.SimpleParticleType(false) {});

	/** The Frost Bear boss (geo/FrostBear): saved, summonable, scales with generic.scale. */
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.geo.FrostBear>> FROST_BEAR =
			ENTITY_TYPES.register("frost_bear", () -> net.minecraft.world.entity.EntityType.Builder
					.<com.toast.aurapoints.geo.FrostBear>of(com.toast.aurapoints.geo.FrostBear::new, net.minecraft.world.entity.MobCategory.MONSTER)
					.sized(1.4f, 1.5f).fireImmune().clientTrackingRange(16).updateInterval(2).build("frost_bear"));

	/** The Aura Arena's training dummy (arena/TrainingDummy). */
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.arena.TrainingDummy>> TRAINING_DUMMY =
			ENTITY_TYPES.register("training_dummy", () -> net.minecraft.world.entity.EntityType.Builder
					.of(com.toast.aurapoints.arena.TrainingDummy::new, net.minecraft.world.entity.MobCategory.MISC)
					.sized(0.6f, 1.95f).clientTrackingRange(8).updateInterval(1).build("training_dummy"));

	/** The Aura Arena's clickable NPCs (arena/ArenaNpc). */
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.arena.ArenaNpc>> ARENA_NPC =
			ENTITY_TYPES.register("arena_npc", () -> net.minecraft.world.entity.EntityType.Builder
					.<com.toast.aurapoints.arena.ArenaNpc>of(com.toast.aurapoints.arena.ArenaNpc::new, net.minecraft.world.entity.MobCategory.MISC)
					.sized(0.6f, 1.8f).clientTrackingRange(10).updateInterval(2).build("arena_npc"));

	/** The Aura Arena's duel-intro camera (arena/ArenaCamera): drawn as nothing. */
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.arena.ArenaCamera>> ARENA_CAMERA =
			ENTITY_TYPES.register("arena_camera", () -> net.minecraft.world.entity.EntityType.Builder
					.<com.toast.aurapoints.arena.ArenaCamera>of(com.toast.aurapoints.arena.ArenaCamera::new, net.minecraft.world.entity.MobCategory.MISC)
					.sized(0.1f, 0.1f).noSave().clientTrackingRange(16).updateInterval(1).build("arena_camera"));

	/** Verdant Viper's head, drawn by GeckoLib (geo/ViperHead). */
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.geo.ViperHead>> VIPER_HEAD =
			ENTITY_TYPES.register("viper_head", () -> net.minecraft.world.entity.EntityType.Builder
					.<com.toast.aurapoints.geo.ViperHead>of(com.toast.aurapoints.geo.ViperHead::new, net.minecraft.world.entity.MobCategory.MISC)
					.sized(0.5f, 0.75f).noSave().noSummon().fireImmune().clientTrackingRange(10).updateInterval(1).build("viper_head"));
	/** The shadow eastern dragon's head (geo/DragonHead): a saved, summonable LivingEntity (generic.scale works) a datapack drives by Motion/Rotation; no player interaction. */
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.geo.DragonHead>> DRAGON_HEAD =
			ENTITY_TYPES.register("dragon_head", () -> net.minecraft.world.entity.EntityType.Builder
					.<com.toast.aurapoints.geo.DragonHead>of(com.toast.aurapoints.geo.DragonHead::new, net.minecraft.world.entity.MobCategory.MISC)
					.sized(1.0f, 1.0f).fireImmune().clientTrackingRange(10).updateInterval(1).build("dragon_head"));
	/** Umbral Ambush's shadow clone, the Thorn Sentinel's tree and Maelstrom's tentacles, drawn by GeckoLib (geo/). */
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.geo.ShadowClone>> SHADOW_CLONE =
			ENTITY_TYPES.register("shadow_clone", () -> net.minecraft.world.entity.EntityType.Builder
					.<com.toast.aurapoints.geo.ShadowClone>of(com.toast.aurapoints.geo.ShadowClone::new, net.minecraft.world.entity.MobCategory.MISC)
					.sized(0.6f, 1.9f).noSave().noSummon().fireImmune().clientTrackingRange(10).updateInterval(1).build("shadow_clone"));
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.geo.SentinelTree>> SENTINEL_TREE =
			ENTITY_TYPES.register("sentinel_tree", () -> net.minecraft.world.entity.EntityType.Builder
					.<com.toast.aurapoints.geo.SentinelTree>of(com.toast.aurapoints.geo.SentinelTree::new, net.minecraft.world.entity.MobCategory.MISC)
					.sized(1.0f, 3.4f).noSave().noSummon().fireImmune().clientTrackingRange(10).updateInterval(1).build("sentinel_tree"));
	/** Resonance's Sonic Blossom and Listening Tree, drawn by GeckoLib (geo/). */
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.geo.SonicBlossom>> SONIC_BLOSSOM =
			ENTITY_TYPES.register("sonic_blossom", () -> net.minecraft.world.entity.EntityType.Builder
					.<com.toast.aurapoints.geo.SonicBlossom>of(com.toast.aurapoints.geo.SonicBlossom::new, net.minecraft.world.entity.MobCategory.MISC)
					.sized(0.6f, 0.8f).noSave().noSummon().fireImmune().clientTrackingRange(10).updateInterval(1).build("sonic_blossom"));
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.geo.ListeningTree>> LISTENING_TREE =
			ENTITY_TYPES.register("listening_tree", () -> net.minecraft.world.entity.EntityType.Builder
					.<com.toast.aurapoints.geo.ListeningTree>of(com.toast.aurapoints.geo.ListeningTree::new, net.minecraft.world.entity.MobCategory.MISC)
					.sized(1.0f, 3.8f).noSave().noSummon().fireImmune().clientTrackingRange(10).updateInterval(1).build("listening_tree"));
	public static final java.util.function.Supplier<net.minecraft.world.entity.EntityType<com.toast.aurapoints.geo.KrakenTentacle>> KRAKEN_TENTACLE =
			ENTITY_TYPES.register("kraken_tentacle", () -> net.minecraft.world.entity.EntityType.Builder
					.<com.toast.aurapoints.geo.KrakenTentacle>of(com.toast.aurapoints.geo.KrakenTentacle::new, net.minecraft.world.entity.MobCategory.MISC)
					.sized(0.7f, 4.8f).noSave().noSummon().fireImmune().clientTrackingRange(12).updateInterval(1).build("kraken_tentacle"));

	// Looks for Resonance's plants and tentacles (block displays / client drawing only): never placed, no item, no drops.
	public static final com.toast.aurapoints.shim.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block> RESONANCE_BARK = BLOCKS.registerBlock(
			"resonance_bark", net.minecraft.world.level.block.RotatedPillarBlock::new, look());
	public static final com.toast.aurapoints.shim.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block> RESONANCE_FLESH = BLOCKS.registerBlock(
			"resonance_flesh", net.minecraft.world.level.block.Block::new, look());
	public static final com.toast.aurapoints.shim.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block> RESONANCE_GLOW = BLOCKS.registerBlock(
			"resonance_glow", net.minecraft.world.level.block.Block::new, look().lightLevel(s -> 10));
	/** The Sonic Blossom's petals and heart (item displays). */
	public static final DeferredItem<Item> RESONANCE_PETAL = ITEMS.registerSimpleItem("resonance_petal");
	public static final DeferredItem<Item> RESONANCE_BUD = ITEMS.registerSimpleItem("resonance_bud");

	private static net.minecraft.world.level.block.state.BlockBehaviour.Properties look() {
		return net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().noLootTable().strength(-1.0f, 3600000.0f);
	}

	public static final DeferredItem<Item> ROKAKA_FRUIT_ITEM = ITEMS.registerItem(
			"rokaka_fruit",
			com.toast.aurapoints.item.RokakaFruitItem::new,
			new Item.Properties()
	);

	public static final DeferredItem<Item> BOSS_SIGIL_ITEM = ITEMS.registerItem(
			"boss_sigil",
			com.toast.aurapoints.item.BossSigilItem::new,
			new Item.Properties()
	);
	/** Opens the in-game balance editor; owner + whitelist only (/aura balance). */
	public static final DeferredItem<Item> BALANCE_TOME = ITEMS.registerItem(
			"balance_tome",
			com.toast.aurapoints.item.BalanceTomeItem::new,
			new Item.Properties()
	);

	/** The player menu + tutorial (menu/AuraBook); every player gets one, /aura book gives another. */
	public static final DeferredItem<Item> AURA_BOOK = ITEMS.registerItem(
			"aura_book",
			com.toast.aurapoints.item.AuraBookItem::new,
			new Item.Properties()
	);

	/** Wakes the Frost Bear (item/FrostBearSigilItem). */
	public static final DeferredItem<Item> FROST_BEAR_SIGIL = ITEMS.registerItem("frost_bear_sigil", com.toast.aurapoints.item.FrostBearSigilItem::new, new Item.Properties());
	/** Dropped by the Frost Bear when it flees; flies and strikes, then a blizzard (item/BlueBellItem). */
	public static final DeferredItem<Item> BLUE_BELL = ITEMS.registerItem("blue_bell", com.toast.aurapoints.item.BlueBellItem::new, new Item.Properties());

	/** Not obtainable: only the frost shard model the Frost aura's Ice Spike shows in the hand and in flight (ability/IceSpike). */
	public static final DeferredItem<Item> ICE_SPIKE_SHARD = ITEMS.registerSimpleItem("ice_spike_shard");
	/** Not obtainable: the shadow dragon's body segments, shown by datapacks as item displays (16 px long on Z, 8x8 cross-section). */
	public static final DeferredItem<Item> DRAGON_SEGMENT_A = ITEMS.registerSimpleItem("dragon_segment_a");
	public static final DeferredItem<Item> DRAGON_SEGMENT_B = ITEMS.registerSimpleItem("dragon_segment_b");
	/** Not obtainable: the Thorn Sentinel seed in flight . */
	public static final DeferredItem<Item> THORN_SEED = ITEMS.registerSimpleItem("thorn_seed");
	/** Not obtainable: the HUD's ability icons, one per custom_model_data (network/ClientAuraHud.icon). */
	public static final DeferredItem<Item> ABILITY_ICON = ITEMS.registerSimpleItem("ability_icon");

	/** Test phase, owner only (/aura mjolnir). */
	public static final DeferredItem<Item> MJOLNIR_ITEM = ITEMS.registerItem(
			"mjolnir",
			com.toast.aurapoints.item.MjolnirItem::new,
			new Item.Properties()
	);

	// The handheld weapons, one per aura (ability/Handhelds). Damage = total attack damage, speed = attacks per second.
	public static final DeferredItem<Item> LEVIATHANS_ANCHOR = handheld("leviathans_anchor", AuraAbilities.Ability.MAELSTROM, 9.0, 0.9,
			"A giant anchor on a glowing chain of kraken tentacles.");
	public static final DeferredItem<Item> PHOENIX_FANGS = handheld("phoenix_fangs", AuraAbilities.Ability.SUNFALL, 5.0, 2.6,
			"Daggers like phoenix talons, their blades glowing like lava.");
	public static final DeferredItem<Item> STORMCALLER_GLAIVE = handheld("stormcaller_glaive", AuraAbilities.Ability.EYE_OF_THE_STORM, 7.0, 1.4,
			"A throwing blade with a ring of wind around it.");
	public static final DeferredItem<Item> SOUL_REAPER = handheld("soul_reaper", AuraAbilities.Ability.UMBRAL_AMBUSH, 8.0, 1.2,
			"A scythe with a blade of pure shadow; little souls drift all around it.");
	public static final DeferredItem<Item> ABSOLUTE_ZERO = handheld("absolute_zero", AuraAbilities.Ability.WINTERS_GATE, 8.0, 1.4,
			"A great sword of ice that never melts; cold mist runs down it.");
	public static final DeferredItem<Item> ZEUS_GAUNTLETS = handheld("zeus_gauntlets", AuraAbilities.Ability.CHAIN_STRIKE, 6.0, 2.2,
			"Golden gauntlets, lightning crackling over the fists.");
	public static final DeferredItem<Item> WORLD_TREE_BOW = ITEMS.registerItem(
			"world_tree_bow",
			com.toast.aurapoints.item.WorldTreeBowItem::new,
			new Item.Properties()
	);

	private static DeferredItem<Item> handheld(String name, AuraAbilities.Ability ability, double damage, double speed, String look) {
		return ITEMS.registerItem(name, props -> new com.toast.aurapoints.item.HandheldItem(props, ability, damage, speed, look), new Item.Properties());
	}

	/** Every handheld weapon, in aura order. */
	public static java.util.List<DeferredItem<Item>> handhelds() {
		return java.util.List.of(SOUL_REAPER, LEVIATHANS_ANCHOR, PHOENIX_FANGS, STORMCALLER_GLAIVE, ABSOLUTE_ZERO, WORLD_TREE_BOW, ZEUS_GAUNTLETS);
	}

	/** Called once by the loader layer, before registration (NeoForge: the mod constructor; Fabric: onInitialize). */
	public static void init(IEventBus modEventBus) {
		// per-player client settings (config/aurapoints-client.toml); only loaded on clients
		com.toast.aurapoints.shim.Platform.get().registerClientConfig(com.toast.aurapoints.config.AuraClientConfig.SPEC, "aurapoints-client.toml");
		ITEMS.register(modEventBus);
		BLOCKS.register(modEventBus);
		ENTITY_TYPES.register(modEventBus);
		PARTICLE_TYPES.register(modEventBus);
		modEventBus.addListener(com.toast.aurapoints.shim.neoforge.event.entity.EntityAttributeCreationEvent.class, event -> {
			event.put(TRAINING_DUMMY.get(), com.toast.aurapoints.arena.TrainingDummy.createAttributes().build());
			event.put(ARENA_NPC.get(), com.toast.aurapoints.arena.ArenaNpc.createAttributes().build());
			event.put(DRAGON_HEAD.get(), com.toast.aurapoints.geo.DragonHead.createAttributes().build());
			event.put(FROST_BEAR.get(), com.toast.aurapoints.geo.FrostBear.createAttributes().build());
		});
		com.toast.aurapoints.ability.AuraSounds.SOUNDS.register(modEventBus);
		AuraConditions.CONDITIONS.register(modEventBus);
		AuraStats.STATS.register(modEventBus);
		modEventBus.addListener(BuildCreativeModeTabContentsEvent.class, AuraPoints::addCreative);
		modEventBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(AuraStats::createStats));

		// Game-bus event handlers (combat, ticks, commands) live in the nested GameEvents class and the other
		// @EventBusSubscriber classes; the loader layer registers them all (shim/AuraBus).

		LOGGER.info("Aura Points initialized (skill tree categories '{}', '{}' and '{}' ship in data/puffish_skills).",
				AuraConfig.PUFFISH_CATEGORY_MAIN, AuraConfig.PUFFISH_CATEGORY_ABILITIES, AuraConfig.PUFFISH_CATEGORY_PVP);
	}

	/** A vanilla creative tab's key (CreativeModeTabs keeps them private outside NeoForge). */
	private static net.minecraft.resources.ResourceKey<net.minecraft.world.item.CreativeModeTab> tab(String name) {
		return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB,
				net.minecraft.resources.ResourceLocation.withDefaultNamespace(name));
	}

	private static void addCreative(BuildCreativeModeTabContentsEvent event) {
		if (event.getTabKey().equals(tab("ingredients"))) {
			event.accept(ROKAKA_FRUIT_ITEM);
			event.accept(BOSS_SIGIL_ITEM);
			event.accept(FROST_BEAR_SIGIL);
			event.accept(BLUE_BELL);
		}
		if (event.getTabKey().equals(tab("tools_and_utilities"))) event.accept(AURA_BOOK);

	}

	// ---------------------------------------------------------------
	// Game-bus events: ticks, combat, commands
	// ---------------------------------------------------------------

	@EventBusSubscriber(modid = MOD_ID)
	public static class GameEvents {

		/** Balance values: (re)read config/aurapoints-*.json at startup and on every /reload. */
		@SubscribeEvent
		public static void onAddReloadListeners(com.toast.aurapoints.shim.neoforge.event.AddReloadListenerEvent event) {
			event.addListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) resources -> {
				com.toast.aurapoints.config.AuraBalance.load();
				com.toast.aurapoints.config.CommandRules.load();
			});
		}

		/** Creates the Aura currency objective up front so datapacks can add to it right away. */
		@SubscribeEvent
		public static void onServerStarted(ServerStartedEvent event) {
			AuraAbilities.allLocked = AuraState.get(event.getServer()).allLocked;
			AuraCurrency.objective(event.getServer());
			AuraHooks.createObjectives(event.getServer());
			com.toast.aurapoints.arena.ArenaBuilder.buildIfNeeded(event.getServer());
			Tickets.load();
		}

		/** Ticket mode: the next chat message becomes the ticket. */
		@SubscribeEvent
		public static void onServerChat(com.toast.aurapoints.shim.neoforge.event.ServerChatEvent event) {
			Tickets.onChat(event);
		}

		// -------------------------------------------------------
		// Every tick: landing gives the double jump back.
		// Every 10 ticks: Berserker refresh. Berserker's Aura is a live
		// attack-damage modifier tracking current health %, refreshed every
		// 0.5s - responsive without recomputing every tick.
		// -------------------------------------------------------

		@SubscribeEvent
		public static void onServerTick(ServerTickEvent.Post event) {
			MinecraftServer server = event.getServer();
			boolean berserkerTick = server.getTickCount() % 10 == 0;

			AuraFx.tick(server);
			AuraStunts.tick(server);
			ScreenFxHooks.tick(server);
			com.toast.aurapoints.menu.Tutorial.tick(server);
			com.toast.aurapoints.ability.FireBending.tickShots(server);
			com.toast.aurapoints.ability.AuraFire.tick(server);
			com.toast.aurapoints.ability.MagmaPools.tickAll(server);
			com.toast.aurapoints.ability.PhantomDecoy.tickAll(server);
			com.toast.aurapoints.ability.Stun.tick(server);
			com.toast.aurapoints.ability.WindLaunch.tick(server);
			com.toast.aurapoints.ability.IceSpike.tickAll(server);
			com.toast.aurapoints.ability.VineRoots.tickAll(server);
			com.toast.aurapoints.ability.Entanglement.tickAll(server);
			com.toast.aurapoints.ability.VerdantViper.tickAll(server);
			com.toast.aurapoints.ability.Thunderbolt.tickAll(server);
			com.toast.aurapoints.ability.Chidori.tickAll(server);
			com.toast.aurapoints.ability.Mjolnir.tickAll(server);
			com.toast.aurapoints.ability.Handhelds.tickAll(server);
			com.toast.aurapoints.ability.Resonance.tickAll(server);
			com.toast.aurapoints.ability.Rooted.tick(server);
			com.toast.aurapoints.arena.Arena.tick(server);
			com.toast.aurapoints.ability.Ultimates.tickAll(server);
			com.toast.aurapoints.ability.Earth.tickAll(server);
			com.toast.aurapoints.ability.Light.tickAll(server);
			com.toast.aurapoints.ability.Blood.tickAll(server);
			com.toast.aurapoints.ability.VoidAura.tickAll(server);
			com.toast.aurapoints.ability.Reactions.tickAll(server);
			com.toast.aurapoints.ability.Combos.tickAll(server);
			com.toast.aurapoints.ability.TouchedMobs.tickAll(server);
			com.toast.aurapoints.cosmetic.Weekly.tick(server);

			AuraState state = AuraState.get(server);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				AuraAbilities.tickDoubleJump(player);
				AuraPlayerData data = state.getOrCreate(player.getUUID());
				AuraAbilities.tickPassives(player, data, server.getTickCount());
				com.toast.aurapoints.ability.Earth.tickPassives(player, data, server.getTickCount());
				com.toast.aurapoints.ability.Light.tickPassives(player, data, server.getTickCount());
				com.toast.aurapoints.ability.Blood.tickPassives(player, data, server.getTickCount());
				com.toast.aurapoints.ability.VoidAura.tickPassives(player, data, server.getTickCount());
				AuraHooks.syncCooldowns(player, server.getTickCount());
				com.toast.aurapoints.ability.IdleAnimations.tick(player, data, server.getTickCount());
				com.toast.aurapoints.cosmetic.Cosmetics.tick(player, data, server.getTickCount());
				if (berserkerTick) {
					boolean hasBerserker = AuraAbilities.hasAbility(data, AuraAbilities.Ability.BERSERKER);
					AuraAbilities.refreshBerserkerModifier(player, hasBerserker);
				}
			}
			// last: every particle the abilities sent this tick, one bundle per player
			AuraFx.flushParticles();
		}

		// -------------------------------------------------------
		// Incoming-damage modifiers: Iron Skin (victim), Executioner and
		// Gale Rider (attacker). LivingIncomingDamageEvent fires BEFORE
		// damage is finalized and for every damage type, magic included,
		// so this is the right place to adjust the amount.
		// -------------------------------------------------------

		@SubscribeEvent
		public static void onIncomingDamage(LivingIncomingDamageEvent event) {
			// steering a Verdant Viper: nothing can hurt you (the void and /kill excepted)
			if (com.toast.aurapoints.ability.VerdantViper.isSteering(event.getEntity())
					&& !event.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
				event.setCanceled(true);
				return;
			}
			// ability damage goes through protection enchantments (armor points still count)
			boolean abilityHit = AuraAbilities.takeAbilityHit(event.getEntity());
			// the Aura Arena: players only get hurt by their opponent in a running duel round
			com.toast.aurapoints.arena.Arena.noteHit(event.getEntity(), abilityHit);
			if (com.toast.aurapoints.arena.Arena.blocksDamage(event.getEntity(), event.getSource())) {
				event.setCanceled(true);
				return;
			}
			// party protection (switch): abilities never hurt someone in your FTB Teams party
			if (abilityHit && AuraConfig.COMBO_PARTY_PROTECTION && event.getEntity() instanceof ServerPlayer partyVictim
					&& event.getSource().getEntity() instanceof ServerPlayer partyAttacker
					&& com.toast.aurapoints.ability.Allies.party(partyAttacker, partyVictim)) {
				event.setCanceled(true);
				return;
			}
			if (abilityHit) ScreenFxHooks.abilityHit(event.getEntity());
			if (abilityHit && AuraConfig.ABILITY_IGNORES_PROTECTION) {
				event.addReductionModifier(com.toast.aurapoints.shim.neoforge.common.damagesource.DamageContainer.Reduction.ENCHANTMENTS, (container, reduction) -> 0f);
			}
			// a frozen mob's (or player's) own hits do nothing
			if (event.getSource().getDirectEntity() != null && com.toast.aurapoints.ability.IceSpike.isFrozen(event.getSource().getDirectEntity())) {
				event.setCanceled(true);
				return;
			}
			if (event.getEntity() instanceof ServerPlayer victim && victim.getServer() != null) {
				AuraPlayerData data = AuraState.get(victim.getServer()).getOrCreate(victim.getUUID());
				// Stormborn: lightning does nothing
				if (com.toast.aurapoints.ability.Thunderbolt.stormbornBlocks(data, event.getSource())) {
					event.setCanceled(true);
					return;
				}
				// Fireborn: fire of any kind does nothing (lava excepted)
				if (AuraAbilities.hasAbility(data, AuraAbilities.Ability.FIREBORN) && AuraAbilities.fireborn(event.getSource())) {
					event.setCanceled(true);
					if (!victim.isInLava()) victim.clearFire();
					return;
				}
				// Grounded (Earth): softer landings
				if (event.getSource().is(net.minecraft.tags.DamageTypeTags.IS_FALL)) {
					event.setAmount(com.toast.aurapoints.ability.Earth.fallDamage(data, event.getAmount()));
				}
				if (AuraAbilities.hasAbility(data, AuraAbilities.Ability.IRON_SKIN) && event.getAmount() > 0) {
					float before = event.getAmount();
					event.setAmount(AuraAbilities.applyIronSkinReduction(victim, before));
					AuraStats.add(victim, AuraStats.IRON_SKIN_BLOCKED, Math.round((before - event.getAmount()) * 10));
					AuraAbilities.ironSkinFx(victim);
				}
			}

			// Attacker bonuses work on mobs too, so they count in PvE.
			applyAttackerBonuses(event, abilityHit);
		}

		private static void applyAttackerBonuses(LivingIncomingDamageEvent event, boolean abilityHit) {
			Entity attackerEntity = event.getSource().getEntity();
			if (!(attackerEntity instanceof ServerPlayer attacker)) return;

			MinecraftServer server = attacker.getServer();
			if (server == null) return;
			AuraPlayerData attackerData = AuraState.get(server).getOrCreate(attacker.getUUID());

			var victim = event.getEntity();
			boolean melee = event.getSource().getDirectEntity() == attacker;

			// Aura Trials counter: melee hits landed while Berserker is at half strength or more.
			if (melee && AuraAbilities.hasAbility(attackerData, AuraAbilities.Ability.BERSERKER)
					&& AuraAbilities.berserkerMultiplier(attacker) >= 1.0 + (AuraConfig.BERSERKER_MAX_DAMAGE_MULTIPLIER - 1.0) * 0.5) {
				AuraStats.add(attacker, AuraStats.BERSERKER_HITS, 1);
			}

			if (AuraAbilities.hasAbility(attackerData, AuraAbilities.Ability.EXECUTIONER)
					&& AuraAbilities.executionerApplies(victim.getHealth(), victim.getMaxHealth())) {
				// Berserker only lives in melee damage (it is an attack-damage modifier).
				double berserker = melee && AuraAbilities.hasAbility(attackerData, AuraAbilities.Ability.BERSERKER)
						? AuraAbilities.berserkerMultiplier(attacker) : 1.0;
				event.setAmount(AuraAbilities.applyExecutionerBonus(event.getAmount(), berserker));
				AuraAbilities.executionerFx(attacker, victim, berserker > 1.0001);
			}

			// Virulence: poisoned targets take more
			double virulence = com.toast.aurapoints.ability.Branch.current(attackerData) == com.toast.aurapoints.ability.Branch.VENOMWEAVER
					? AuraConfig.VERDANT_VENOM_VIRULENCE_BONUS : AuraConfig.VIRULENCE_BONUS;
			if (virulence > 0 && victim.hasEffect(net.minecraft.world.effect.MobEffects.POISON)
					&& AuraAbilities.hasAbility(attackerData, AuraAbilities.Ability.VIRULENCE)) {
				event.setAmount((float) (event.getAmount() * (1.0 + virulence)));
				AuraStats.add(attacker, AuraStats.VIRULENCE_HITS, 1);
				AuraFx.particles((net.minecraft.server.level.ServerLevel) victim.level(), net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
						victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(), 3, 0.25, 0.3, 0.25, 0.0);
			}

			// Conductor: a Charged target passes part of the hit on
			com.toast.aurapoints.ability.Thunderbolt.spread(attacker, victim, event.getAmount());

			// Brittle (Frostbite branch): Chilled targets take more
			if (AuraConfig.BRITTLE_BONUS > 0 && AuraAbilities.hasAbility(attackerData, AuraAbilities.Ability.BRITTLE)
					&& com.toast.aurapoints.ability.Reactions.marked(victim, com.toast.aurapoints.ability.Reactions.Mark.CHILLED)) {
				event.setAmount((float) (event.getAmount() * (1.0 + AuraConfig.BRITTLE_BONUS)));
				AuraFx.particles((net.minecraft.server.level.ServerLevel) victim.level(), net.minecraft.core.particles.ParticleTypes.SNOWFLAKE,
						victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(), 4, 0.25, 0.3, 0.25, 0.01);
			}

			// Scorch (Blue Flame branch): burning targets take more
			if (AuraConfig.SCORCH_BONUS > 0 && victim.isOnFire() && AuraAbilities.hasAbility(attackerData, AuraAbilities.Ability.SCORCH)) {
				event.setAmount((float) (event.getAmount() * (1.0 + AuraConfig.SCORCH_BONUS)));
				AuraFx.particles((net.minecraft.server.level.ServerLevel) victim.level(), net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME,
						victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(), 3, 0.25, 0.3, 0.25, 0.01);
			}

			// Umbral Ambush: the hit right after it
			if (melee) {
				float bonus = com.toast.aurapoints.ability.UmbralAmbush.takeBonus(attacker, victim);
				if (bonus > 0) event.setAmount(event.getAmount() + bonus);
			}

			// Shatter: the combo's finishing hit
			if (AuraAbilities.hasAbility(attackerData, AuraAbilities.Ability.SHATTER)) {
				float bonus = com.toast.aurapoints.ability.Shatter.bonus(attacker, victim, melee);
				if (bonus > 0) event.setAmount(event.getAmount() + bonus);
			}

			// Blood: a sacrificed fist hits double; under the Blood Moon your hits heal you
			event.setAmount(com.toast.aurapoints.ability.Blood.onHit(attacker, victim, event.getAmount(), melee));
			// Void's Entropy: whatever you hit loses armor for a while
			com.toast.aurapoints.ability.VoidAura.onHit(attacker, attackerData, victim);

			// Flow: harder hits in the rain, harder still in water
			if (AuraAbilities.hasAbility(attackerData, AuraAbilities.Ability.FLOW) && event.getAmount() > 0) {
				event.setAmount(AuraAbilities.flowDamage(attacker, event.getAmount()));
			}

			// Elemental reactions: an ability hit leaves its element as a mark, or sets off a reaction with one already there
			if (abilityHit) com.toast.aurapoints.ability.Reactions.onAbilityHit(event, attacker, attackerData);

			// Elemental matchups between players: Fire beats Frost, Water beats Fire
			if (victim instanceof ServerPlayer victimPlayer && victimPlayer != attacker && attackerData.aura != null && event.getAmount() > 0) {
				AuraPlayerData victimData = AuraState.get(server).getOrCreate(victimPlayer.getUUID());
				if (victimData.aura != null) {
					var attackerBranch = com.toast.aurapoints.ability.Branch.current(attackerData);
					var victimBranch = com.toast.aurapoints.ability.Branch.current(victimData);
					if (com.toast.aurapoints.ability.AuraType.beats(attackerData.aura, attackerBranch, victimData.aura, victimBranch)) {
						event.setAmount((float) (event.getAmount() * AuraConfig.AURA_EFFECTIVE_MULTIPLIER));
						AuraAbilities.effectiveFx(attacker, victimPlayer, attackerData.aura);
					} else if (com.toast.aurapoints.ability.AuraType.beats(victimData.aura, victimBranch, attackerData.aura, attackerBranch)) {
						event.setAmount((float) (event.getAmount() * AuraConfig.AURA_RESISTED_MULTIPLIER));
					}
				}
			}

			// Gale Rider: also counts for arrows and charges that land while the shooter still glides.
			if (AuraAbilities.galeActive(attacker) && AuraAbilities.hasAbility(attackerData, AuraAbilities.Ability.GALE_RIDER)) {
				event.setAmount(event.getAmount() * AuraConfig.GALE_RIDER_DAMAGE_MULTIPLIER);
				AuraAbilities.galeRiderHitFx(attacker, victim);
			}
		}

		// -------------------------------------------------------
		// Shadow cloak: armor doesn't make a cloaked player easier for mobs to
		// spot (vanilla invisibility gets weaker with every armor piece), and
		// new watchers learn the player is cloaked; the cloak itself ticks in
		// AuraAbilities.tickPassives.
		// -------------------------------------------------------

		@SubscribeEvent
		public static void onVisibility(com.toast.aurapoints.shim.neoforge.event.entity.living.LivingEvent.LivingVisibilityEvent event) {
			if (!(event.getEntity() instanceof ServerPlayer player) || !ShadowCloak.isCloaked(player)) return;
			float coverage = player.getArmorCoverPercentage();
			if (coverage > 0.1f) event.modifyVisibility(0.1 / coverage); // as if wearing no armor
		}

		/** Stunned players can't attack (Inferno Slam recovery). Every other attack feeds the Shatter combo. */
		@SubscribeEvent
		public static void onAttack(com.toast.aurapoints.shim.neoforge.event.entity.player.AttackEntityEvent event) {
			if (!(event.getEntity() instanceof ServerPlayer player)) return;
			// an arena NPC: a left click works like a right click
			if (event.getTarget() instanceof com.toast.aurapoints.arena.ArenaNpc npc) {
				npc.kind().use(player, npc);
				event.setCanceled(true);
				return;
			}
			AuraHooks.m1(player);
			if (com.toast.aurapoints.ability.Stun.isStunned(player) || com.toast.aurapoints.ability.IceSpike.isFrozen(player)) {
				event.setCanceled(true);
				return;
			}
			// spinning Mjölnir: the click takes off instead of hitting
			if (com.toast.aurapoints.ability.Mjolnir.onLeftClick(player)) {
				event.setCanceled(true);
				return;
			}
			// Verdant's hitboxes: a green root of a cocoon, or a viper
			if (event.getTarget() instanceof net.minecraft.world.entity.Interaction box) {
				if (com.toast.aurapoints.ability.Entanglement.onHitbox(player, box) || com.toast.aurapoints.ability.VerdantViper.onHitbox(player, box)
						|| com.toast.aurapoints.ability.Resonance.onHitbox(player, box)) {
					event.setCanceled(true);
					return;
				}
			}
			if (player.getServer() != null) com.toast.aurapoints.ability.Shatter.onAttack(player, event.getTarget(), player.getServer().getTickCount());
			com.toast.aurapoints.ability.Thunderbolt.onAttack(player, event.getTarget());
			if (player.getServer() != null) com.toast.aurapoints.ability.Mjolnir.onAttack(player, event.getTarget(), player.getServer().getTickCount());
		}

		/** Hitting a block breaks the Shatter combo (a swing at nothing is reported by the client). Frozen players can't break blocks. */
		@SubscribeEvent
		public static void onLeftClickBlock(com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
			if (com.toast.aurapoints.ability.IceSpike.isFrozen(event.getEntity())
					|| (event.getEntity() instanceof ServerPlayer flyer && com.toast.aurapoints.ability.Mjolnir.isFlying(flyer))) {
				// (also while flying with Mjölnir: the held left mouse must not dig up what you are looking at)
				event.setCanceled(true);
				return;
			}
			if (event.getEntity() instanceof ServerPlayer player
					&& event.getAction() == com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.START) {
				AuraHooks.m1(player);
				if (com.toast.aurapoints.ability.Mjolnir.onLeftClick(player)) {
					event.setCanceled(true);
					return;
				}
				com.toast.aurapoints.ability.Shatter.reset(player);
			}
		}

		// -------------------------------------------------------
		// Frozen by an Ice Spike: nothing can be used, and a frozen mob can't hurt anyone
		// (not even with an arrow it lets go of) - the client blocks the keys too.
		// -------------------------------------------------------

		@SubscribeEvent
		public static void onRightClickItem(com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
			if (com.toast.aurapoints.ability.IceSpike.isFrozen(event.getEntity())) event.setCanceled(true);
		}

		@SubscribeEvent
		public static void onRightClickBlock(com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
			if (com.toast.aurapoints.ability.IceSpike.isFrozen(event.getEntity())) {
				event.setCanceled(true);
				return;
			}
			// the Aura Arena's Training Hall buttons (main hand only, or each would go off twice)
			if (event.getEntity() instanceof ServerPlayer player && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
					&& com.toast.aurapoints.arena.Arena.in(player)) {
				com.toast.aurapoints.arena.TrainingHall.press(player, event.getPos());
			}
			// doors, trapdoors, gates and chests creak (Resonance)
			if (!event.getLevel().isClientSide()) {
				var block = event.getLevel().getBlockState(event.getPos()).getBlock();
				if (block instanceof net.minecraft.world.level.block.DoorBlock || block instanceof net.minecraft.world.level.block.TrapDoorBlock
						|| block instanceof net.minecraft.world.level.block.FenceGateBlock || block instanceof net.minecraft.world.level.block.ChestBlock
						|| block instanceof net.minecraft.world.level.block.BarrelBlock || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
					com.toast.aurapoints.ability.Noise.add(event.getEntity(), AuraConfig.NOISE_DOOR);
				}
			}
		}

		/** Building and digging are loud (Resonance). */
		@SubscribeEvent
		public static void onBlockPlaced(com.toast.aurapoints.shim.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
			if (event.getEntity() instanceof net.minecraft.world.entity.LivingEntity e) com.toast.aurapoints.ability.Noise.add(e, AuraConfig.NOISE_BLOCK);
		}

		@SubscribeEvent
		public static void onBlockBroken(com.toast.aurapoints.shim.neoforge.event.level.BlockEvent.BreakEvent event) {
			com.toast.aurapoints.ability.Noise.add(event.getPlayer(), AuraConfig.NOISE_BLOCK);
		}

		@SubscribeEvent
		public static void onEntityInteract(com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
			if (com.toast.aurapoints.ability.IceSpike.isFrozen(event.getEntity())) event.setCanceled(true);
			else if (event.getTarget() instanceof com.toast.aurapoints.arena.ArenaNpc npc && event.getEntity() instanceof ServerPlayer player) {
				if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) npc.kind().use(player, npc);
				event.setCanceled(true);
			}
		}

		/** Vine Hook roots left over from a crash mid-animation are removed as soon as they load. */
		@SubscribeEvent
		public static void onVineRootLoads(com.toast.aurapoints.shim.neoforge.event.entity.EntityJoinLevelEvent event) {
			if (!event.getLevel().isClientSide() && (com.toast.aurapoints.ability.VineRoots.isLeftover(event.getEntity())
					|| com.toast.aurapoints.ability.Entanglement.isLeftover(event.getEntity())
					|| com.toast.aurapoints.ability.Mjolnir.isLeftover(event.getEntity())
					|| com.toast.aurapoints.ability.IceSpike.isLeftover(event.getEntity())
					|| com.toast.aurapoints.ability.Ultimates.isLeftover(event.getEntity())
					|| com.toast.aurapoints.ability.Earth.isLeftover(event.getEntity()))) event.setCanceled(true);
		}

		/** The shadow dragon's body (item displays holding its segments) is drawn from further away. */
		@SubscribeEvent
		public static void onDisplayJoins(com.toast.aurapoints.shim.neoforge.event.entity.EntityJoinLevelEvent event) {
			if (!event.getLevel().isClientSide()) com.toast.aurapoints.ability.DragonSegments.onJoin(event.getEntity());
		}

		/** Steering a Verdant Viper: no knockback, no healing, no eating or drinking. */
		@SubscribeEvent
		public static void onKnockback(com.toast.aurapoints.shim.neoforge.event.entity.living.LivingKnockBackEvent event) {
			if (com.toast.aurapoints.ability.VerdantViper.isSteering(event.getEntity())) event.setCanceled(true);
		}

		@SubscribeEvent
		public static void onHeal(com.toast.aurapoints.shim.neoforge.event.entity.living.LivingHealEvent event) {
			if (com.toast.aurapoints.ability.VerdantViper.isSteering(event.getEntity())) event.setCanceled(true);
		}

		@SubscribeEvent
		public static void onUseItemStart(com.toast.aurapoints.shim.neoforge.event.entity.living.LivingEntityUseItemEvent.Start event) {
			if (com.toast.aurapoints.ability.VerdantViper.isSteering(event.getEntity())) event.setCanceled(true);
		}

		@SubscribeEvent
		public static void onServerStopping(com.toast.aurapoints.shim.neoforge.event.server.ServerStoppingEvent event) {
			com.toast.aurapoints.ability.VineRoots.clearAll();
			com.toast.aurapoints.ability.Entanglement.clearAll();
			com.toast.aurapoints.ability.VerdantViper.clearAll();
			com.toast.aurapoints.ability.Thunderbolt.clearAll(event.getServer());
			com.toast.aurapoints.ability.Chidori.clearAll();
			com.toast.aurapoints.ability.Mjolnir.clearAll(event.getServer());
			com.toast.aurapoints.ability.IceSpike.clearAll();
			com.toast.aurapoints.ability.Handhelds.clearAll();
			com.toast.aurapoints.ability.Resonance.clearAll();
			com.toast.aurapoints.ability.Rooted.clearAll();
			com.toast.aurapoints.arena.Arena.clearAll();
			com.toast.aurapoints.ability.IdleAnimations.clearAll();
			com.toast.aurapoints.ability.Ultimates.clearAll(event.getServer());
			com.toast.aurapoints.ability.Light.clearAll();
			com.toast.aurapoints.ability.Blood.clearAll();
			com.toast.aurapoints.ability.VoidAura.clearAll();
			com.toast.aurapoints.ability.Earth.clearAll();
			com.toast.aurapoints.ability.Reactions.clearAll();
			com.toast.aurapoints.ability.Combos.clearAll();
			com.toast.aurapoints.ability.TouchedMobs.clearAll();
			// last, so nothing the clean-ups above scheduled carries over into the next world
			AuraFx.clearAll();
		}

		@SubscribeEvent
		public static void onServerStopped(com.toast.aurapoints.shim.neoforge.event.server.ServerStoppedEvent event) {
			AuraState.forget();
		}

		@SubscribeEvent
		public static void onFrozenShoots(com.toast.aurapoints.shim.neoforge.event.entity.EntityJoinLevelEvent event) {
			if (!event.getLevel().isClientSide() && event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile
					&& com.toast.aurapoints.ability.IceSpike.isFrozen(projectile.getOwner())) event.setCanceled(true);
		}

		@SubscribeEvent
		public static void onStartTracking(PlayerEvent.StartTracking event) {
			if (event.getEntity() instanceof ServerPlayer watcher && event.getTarget() instanceof ServerPlayer target) {
				ShadowCloak.onStartTracking(watcher, target);
				if (target.getServer() != null) {
					com.toast.aurapoints.network.NetworkHandler.sendPlayerAuraTo(watcher, target,
							AuraState.get(target.getServer()).getOrCreate(target.getUUID()));
				}
				com.toast.aurapoints.ability.Poses.onStartTracking(watcher, target);
			}
		}

		/** Accessories drop with the rest of the inventory. */
		@SubscribeEvent
		public static void onDrops(com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDropsEvent event) {
			if (event.getEntity() instanceof ServerPlayer player) AccessorySlots.onDrops(player, event.getDrops());
		}

		/** Respawned: nothing from the old life holds on (a stun, wind-up, freeze or root the client still believes in). */
		@SubscribeEvent
		public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
			if (!(event.getEntity() instanceof ServerPlayer player) || event.isEndConquered()) return;
			com.toast.aurapoints.ability.Stun.onLogout(player);
			if (!com.toast.aurapoints.ability.Rooted.isRooted(player)) {
				com.toast.aurapoints.shim.neoforge.network.PacketDistributor.sendToPlayer(player, new com.toast.aurapoints.network.RootedPacket(false, false));
			}
			com.toast.aurapoints.network.NetworkHandler.sendFrozen(player, 0);
			// dying empties the blood bar (respawn, not the death event: the arena cancels its deaths)
			com.toast.aurapoints.ability.Blood.onDeath(player);
			// dying also breaks the Ice Shell: the new life gets a whole one
			com.toast.aurapoints.ability.IceShell.onDeath(player);
		}

		@SubscribeEvent
		public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
			if (event.getEntity() instanceof ServerPlayer player) {
				com.toast.aurapoints.arena.Arena.onLogout(player);
				com.toast.aurapoints.ability.IdleAnimations.forget(player);
				com.toast.aurapoints.ability.Nightstalker.forget(player);
				com.toast.aurapoints.ability.Ultimates.onLogout(player);
				com.toast.aurapoints.ability.Earth.onLogout(player);
				com.toast.aurapoints.ability.Light.onLogout(player);
				com.toast.aurapoints.ability.Blood.onLogout(player);
				com.toast.aurapoints.menu.Tutorial.onLogout(player);
				com.toast.aurapoints.ability.VoidAura.onLogout(player);
				ShadowCloak.onLogout(player);
				AuraAbilities.forgetVanish(player);
				AuraAbilities.forgetAir(player);
				com.toast.aurapoints.item.RokakaFruitItem.forget(player);
				DashDive.onLogout(player);
				com.toast.aurapoints.ability.FireBending.onLogout(player);
				com.toast.aurapoints.ability.AirBending.onLogout(player);
				com.toast.aurapoints.ability.AbyssalBubble.onLogout(player);
				com.toast.aurapoints.ability.IceSpike.onLogout(player);
				com.toast.aurapoints.ability.IceShell.onLogout(player);
				com.toast.aurapoints.ability.Shatter.onLogout(player);
				com.toast.aurapoints.ability.Stun.onLogout(player);
				com.toast.aurapoints.ability.WindLaunch.forget(player);
				com.toast.aurapoints.ability.Poses.onLogout(player);
				com.toast.aurapoints.ability.Entanglement.onLogout(player);
				com.toast.aurapoints.ability.VerdantViper.onLogout(player);
				com.toast.aurapoints.ability.Thunderbolt.onLogout(player);
				com.toast.aurapoints.ability.Chidori.onLogout(player);
				com.toast.aurapoints.ability.Mjolnir.onLogout(player);
				com.toast.aurapoints.ability.Handhelds.onLogout(player);
				com.toast.aurapoints.ability.Resonance.onLogout(player);
				com.toast.aurapoints.ability.Rooted.unlock(player);
				AuraHooks.forgetCooldowns(player);
				com.toast.aurapoints.network.NetworkHandler.forgetSwings(player);
				Tickets.onLogout(player);
			}
		}

		// -------------------------------------------------------
		// Post-damage: Lifesteal (needs the FINAL damage amount).
		// -------------------------------------------------------

		/** Ice Shell: after armor and Protection, before absorption hearts. */
		@SubscribeEvent
		public static void onDamagePre(LivingDamageEvent.Pre event) {
			if (!(event.getEntity() instanceof ServerPlayer victim) || victim.getServer() == null || event.getNewDamage() <= 0) return;
			AuraPlayerData data = AuraState.get(victim.getServer()).getOrCreate(victim.getUUID());
			if (AuraAbilities.hasAbility(data, AuraAbilities.Ability.ICE_SHELL)) {
				event.setNewDamage(com.toast.aurapoints.ability.IceShell.absorb(victim, event.getSource(), event.getNewDamage(),
						victim.getServer().getTickCount()));
			}
		}

		/** The Aura Arena: a hit that would kill a dueller is a knockout instead (last, after every other change to the damage). */
		@SubscribeEvent(priority = com.toast.aurapoints.shim.bus.api.EventPriority.LOWEST)
		public static void onDamageLethal(LivingDamageEvent.Pre event) {
			if (event.getNewDamage() > 0 && com.toast.aurapoints.arena.Arena.catchLethal(event.getEntity(), event.getSource(), event.getNewDamage())) {
				event.setNewDamage(0);
			}
		}

		/** Nobody dies in the Aura Arena (before anything else hears of the death: no drops, no gravestone). */
		@SubscribeEvent(priority = com.toast.aurapoints.shim.bus.api.EventPriority.HIGHEST)
		public static void onArenaDeath(LivingDeathEvent event) {
			if (com.toast.aurapoints.arena.Arena.catchDeath(event.getEntity(), event.getSource())) event.setCanceled(true);
		}

		/** Arena entities from an older build of the map (or stray damage numbers) don't come back when their chunk loads; the animated ones start moving. */
		@SubscribeEvent
		public static void onArenaEntityLoads(com.toast.aurapoints.shim.neoforge.event.entity.EntityJoinLevelEvent event) {
			if (event.getLevel().isClientSide()) return;
			if (event.loadedFromDisk() && com.toast.aurapoints.arena.ArenaBuilder.isStale(event.getEntity())) event.setCanceled(true);
			else if (com.toast.aurapoints.arena.Arena.in(event.getEntity()) && com.toast.aurapoints.arena.Waves.onJoin(event.getEntity(), event.loadedFromDisk())) event.setCanceled(true);
			else {
				com.toast.aurapoints.arena.ArenaFx.track(event.getEntity());
				com.toast.aurapoints.arena.Holograms.track(event.getEntity());
			}
		}

		/** Wave Survival mobs drop nothing (else the ring is a farm). */
		@SubscribeEvent(priority = com.toast.aurapoints.shim.bus.api.EventPriority.HIGH)
		public static void onWaveDrops(com.toast.aurapoints.shim.neoforge.event.entity.living.LivingDropsEvent event) {
			if (com.toast.aurapoints.arena.Waves.isWaveMob(event.getEntity())) event.setCanceled(true);
		}

		@SubscribeEvent
		public static void onWaveXp(com.toast.aurapoints.shim.neoforge.event.entity.living.LivingExperienceDropEvent event) {
			if (com.toast.aurapoints.arena.Waves.isWaveMob(event.getEntity())) event.setCanceled(true);
		}

		/** Nothing in the Aura Arena breaks or sets fire to the map: creepers, blazes, ravagers, evokers. */
		@SubscribeEvent
		public static void onArenaGrief(com.toast.aurapoints.shim.neoforge.event.entity.EntityMobGriefingEvent event) {
			if (event.getEntity() != null && com.toast.aurapoints.arena.Arena.in(event.getEntity())) event.setCanGrief(false);
		}

		/** Titles (cosmetic): in front of the name in chat and in the tab list. */
		@SubscribeEvent
		public static void onNameFormat(PlayerEvent.NameFormat event) {
			if (event.getEntity() instanceof ServerPlayer player) {
				net.minecraft.network.chat.Component titled = com.toast.aurapoints.cosmetic.Cosmetics.titled(player, event.getDisplayname());
				if (titled != null) event.setDisplayname(titled);
			}
		}

		@SubscribeEvent
		public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
			if (event.getEntity() instanceof ServerPlayer player) {
				net.minecraft.network.chat.Component base = event.getDisplayName() != null ? event.getDisplayName()
						: net.minecraft.network.chat.Component.literal(player.getGameProfile().getName());
				net.minecraft.network.chat.Component titled = com.toast.aurapoints.cosmetic.Cosmetics.titled(player, base);
				if (titled != null) event.setDisplayName(titled);
			}
		}

		/** Aura-touched mobs: a natural hostile spawn may roll an aura; touched ones are tracked while loaded. */
		@SubscribeEvent
		public static void onFinalizeSpawn(com.toast.aurapoints.shim.neoforge.event.entity.living.FinalizeSpawnEvent event) {
			com.toast.aurapoints.ability.TouchedMobs.onFinalizeSpawn(event.getEntity(), event.getSpawnType());
		}

		@SubscribeEvent
		public static void onTouchedJoin(com.toast.aurapoints.shim.neoforge.event.entity.EntityJoinLevelEvent event) {
			if (!event.getLevel().isClientSide() && !event.isCanceled()) com.toast.aurapoints.ability.TouchedMobs.onJoin(event.getEntity());
		}

		@SubscribeEvent
		public static void onTouchedLeave(com.toast.aurapoints.shim.neoforge.event.entity.EntityLeaveLevelEvent event) {
			if (!event.getLevel().isClientSide()) com.toast.aurapoints.ability.TouchedMobs.onLeave(event.getEntity());
		}

		@SubscribeEvent
		public static void onTravelToDimension(com.toast.aurapoints.shim.neoforge.event.entity.EntityTravelToDimensionEvent event) {
			// into the arena by any route: remember where they came from, for /aura leave
			if (event.getEntity() instanceof ServerPlayer player && event.getDimension() == com.toast.aurapoints.arena.Arena.KEY) {
				com.toast.aurapoints.arena.Arena.rememberReturn(player);
			}
		}

		@SubscribeEvent
		public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
			if (event.getEntity() instanceof ServerPlayer player) com.toast.aurapoints.arena.Arena.onChangedDimension(player, event.getFrom(), event.getTo());
		}

		@SubscribeEvent
		public static void onPostDamage(LivingDamageEvent.Post event) {
			com.toast.aurapoints.arena.TrainingDummy.onDamaged(event.getEntity(), event.getSource(), event.getNewDamage());
			// a hit breaks an Ice Spike freeze, whoever dealt it (even one an Ice Shell soaked up)
			com.toast.aurapoints.ability.IceSpike.onDamaged(event.getEntity(), event.getSource());
			// fighting is loud (Resonance): for whoever got hit and whoever hit them
			com.toast.aurapoints.ability.Noise.add(event.getEntity(), AuraConfig.NOISE_HURT);
			if (event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker) {
				com.toast.aurapoints.ability.Noise.add(attacker, AuraConfig.NOISE_ATTACK);
			}
			// a hit on someone wrapped in Entanglement's roots loosens them
			com.toast.aurapoints.ability.Entanglement.onDamaged(event.getEntity(), event.getSource());
			// and doesn't pop an Abyssal Bubble just by knocking its holder's aim around
			if (event.getEntity() instanceof ServerPlayer hurt && event.getSource().getEntity() != null) {
				com.toast.aurapoints.ability.AbyssalBubble.onHurt(hurt);
			}

			Entity attackerEntity = event.getSource().getEntity();
			if (!(attackerEntity instanceof ServerPlayer attacker)) return;

			MinecraftServer server = attacker.getServer();
			if (server == null) return;

			AuraPlayerData data = AuraState.get(server).getOrCreate(attacker.getUUID());

			// hit confirm on the attacker's screen
			ScreenFxHooks.onDamageDealt(attacker, event.getEntity(), event.getSource(), event.getNewDamage());

			// Aura Trials stunts: only does something if this hit killed a monster.
			AuraStunts.onDamageDealt(attacker, event.getEntity(), event.getNewDamage(), event.getSource());

			if (AuraAbilities.hasAbility(data, AuraAbilities.Ability.LIFESTEAL)) {
				AuraAbilities.applyLifesteal(attacker, event.getEntity(), event.getNewDamage());
			}

			// Ice Shell rebuilds faster for every hit on another player
			if (event.getEntity() instanceof ServerPlayer && event.getEntity() != attacker
					&& AuraAbilities.hasAbility(data, AuraAbilities.Ability.ICE_SHELL)) {
				com.toast.aurapoints.ability.IceShell.hitPlayer(attacker);
			}
		}

		// -------------------------------------------------------
		// Fall damage: Phantom Step negates it under the right conditions,
		// Featherfall cuts it.
		// -------------------------------------------------------

		@SubscribeEvent
		public static void onLivingFall(LivingFallEvent event) {
			if (!(event.getEntity() instanceof ServerPlayer player)) return;

			MinecraftServer server = player.getServer();
			if (server == null) return;

			// Landing from a Shadow Dive never hurts (the dive makes its shockwave instead).
			if (DashDive.isDiving(player)) {
				event.setDamageMultiplier(0.0f);
				return;
			}

			if (com.toast.aurapoints.ability.WindLaunch.consumeLanding(player)) {
				event.setDamageMultiplier(0.0f);
				return;
			}

			AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
			if (AuraAbilities.hasAbility(data, AuraAbilities.Ability.PHANTOM_STEP)
					&& AuraAbilities.tryPhantomStep(player, event.getDistance())) {
				event.setDamageMultiplier(0.0f);
				if (AuraAbilities.hasAbility(data, AuraAbilities.Ability.VANISH_STEP)) AuraAbilities.tryVanishStep(player);
				return;
			}
			if (AuraAbilities.hasAbility(data, AuraAbilities.Ability.FEATHERFALL)) {
				event.setDamageMultiplier(AuraAbilities.applyFeatherfall(event.getDamageMultiplier()));
				// Aura Trials counter: landings from a fall that would have hurt (more than 3 blocks).
				if (event.getDistance() > 3.0f) AuraStats.add(player, AuraStats.FEATHERFALL_LANDINGS, 1);
			}
		}

		// -------------------------------------------------------
		// Deaths: bosses (#aurapoints:bosses) unlock the PvP tree (and pay Aura if
		// BOSS_AURA_PER_KILL is set); player kills feed Bloodrush.
		// -------------------------------------------------------

		@SubscribeEvent
		public static void onLivingDeath(LivingDeathEvent event) {
			Entity dead = event.getEntity();

			if (dead.getType().is(AuraTags.BOSSES) && dead.getServer() != null) {
				rewardBossKill(dead);
			}
			// the killer's kill effect (cosmetic)
			if (event.getSource().getEntity() instanceof ServerPlayer fxKiller && fxKiller != dead && dead instanceof net.minecraft.world.entity.LivingEntity fxDead) {
				com.toast.aurapoints.cosmetic.Cosmetics.onKill(fxKiller, fxDead);
			}
			// an aura-touched mob pays its killer
			if (dead instanceof net.minecraft.world.entity.LivingEntity deadMob) com.toast.aurapoints.ability.TouchedMobs.onDeath(deadMob, event.getSource());
			// Bloodlust: any kill
			if (event.getSource().getEntity() instanceof ServerPlayer bloodKiller && bloodKiller != dead && dead instanceof net.minecraft.world.entity.LivingEntity deadLiving
					&& bloodKiller.getServer() != null) {
				com.toast.aurapoints.ability.Blood.onKill(bloodKiller, AuraState.get(bloodKiller.getServer()).getOrCreate(bloodKiller.getUUID()), deadLiving);
			}

			if (!(dead instanceof ServerPlayer victim)) return;

			MinecraftServer server = victim.getServer();
			if (server == null) return;

			Entity attackerEntity = event.getSource().getEntity();
			if (attackerEntity instanceof ServerPlayer killer && killer != victim) {
				AuraPlayerData killerData = AuraState.get(server).getOrCreate(killer.getUUID());
				if (AuraAbilities.hasAbility(killerData, AuraAbilities.Ability.BLOODRUSH)) {
					AuraAbilities.applyBloodrush(killer);
				}
			}

			// Clear Berserker's modifier so it doesn't linger through respawn.
			AuraAbilities.refreshBerserkerModifier(victim, false);
		}

		private static void rewardBossKill(Entity boss) {
			MinecraftServer server = boss.getServer();
			if (server == null) return;
			AuraState state = AuraState.get(server);
			double r = AuraConfig.BOSS_REWARD_RADIUS;

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.level() != boss.level() || player.distanceToSqr(boss) > r * r) continue;

				if (AuraConfig.BOSS_AURA_PER_KILL > 0) {
					AuraCurrency.add(player, AuraConfig.BOSS_AURA_PER_KILL);
					player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
							"The boss falls. You received " + AuraConfig.BOSS_AURA_PER_KILL + " Aura!")
							.withStyle(net.minecraft.ChatFormatting.GOLD));
				}

				AuraPlayerData data = state.getOrCreate(player.getUUID());
				if (!data.pvpUnlocked) {
					data.pvpUnlocked = true;
					state.markDirty();
					PuffishBridge.unlockCategory(player, AuraConfig.PUFFISH_CATEGORY_PVP);
					player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
							"The PvP skill tree is now unlocked. Buy points for it with /aura redeem pvp.")
							.withStyle(net.minecraft.ChatFormatting.RED));
					AuraFx.title(player,
							net.minecraft.network.chat.Component.literal("PvP Tree Unlocked").withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD),
							net.minecraft.network.chat.Component.literal("/aura redeem pvp").withStyle(net.minecraft.ChatFormatting.GRAY));
					AuraFx.helix(player, AuraFx.dust(0xB0121B, 1.3f), 30, 0.9, 2.4);
					AuraFx.sound(player.serverLevel(), player, net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
				}
			}
		}

		// -------------------------------------------------------
		// Login: one-time wipe per WIPE_VERSION (also first-login setup for new
		// players, which locks the PvP tree), and clear stale key flags.
		// -------------------------------------------------------

		@SubscribeEvent
		public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
			if (!(event.getEntity() instanceof ServerPlayer player)) return;
			MinecraftServer server = player.getServer();
			if (server == null) return;

			AuraState state = AuraState.get(server);
			AuraPlayerData data = state.getOrCreate(player.getUUID());
			com.toast.aurapoints.ability.Thunderbolt.onLogin(player);
			com.toast.aurapoints.ability.Mjolnir.onLogin(player);
			com.toast.aurapoints.ability.Handhelds.onLogin(player);
			com.toast.aurapoints.arena.Arena.onLogin(player);
			com.toast.aurapoints.ability.Ultimates.onLogin(player);
			Tickets.onLogin(player);

			if (data.wipeVersion < AuraConfig.WIPE_VERSION) {
				AuraReset.wipe(player, data);
				// Trials/Feats counters and aurapoints:* advancements go too (wipe 5, 2026-11-01)
				com.toast.aurapoints.ability.AuraStats.resetProgress(player, data);
				state.markDirty();
				LOGGER.info("Wiped Aura data for {} (wipe version {})", player.getGameProfile().getName(), AuraConfig.WIPE_VERSION);
			}

			// a Wave Survival run that ended while they were away (crash, restart): the borrowed aura goes
			if (data.arenaRealAura != null) {
				data.aura = data.arenaRealAura;
				data.arenaRealAura = null;
				state.markDirty();
			}
			if (data.aura != null && data.rolled.add(data.aura)) state.markDirty();
			com.toast.aurapoints.network.NetworkHandler.clearKeyFlags(server, player);
			// Players who rolled before the Aura Trials existed get their section unlocked here.
			AuraStats.grantAuraAdvancement(player, data);
			com.toast.aurapoints.network.NetworkHandler.syncAura(player, data);
			com.toast.aurapoints.network.NetworkHandler.syncSettings(player, data);
			com.toast.aurapoints.network.NetworkHandler.syncDisabled(player);
			com.toast.aurapoints.network.NetworkHandler.syncAccessorySlots(player);
			// One-time: anyone who held an aura before the shop existed keeps its actives for free.
			if (!data.shopMigrated) {
				if (data.aura != null) {
					for (var a : data.aura.abilities) if (a.active) data.owned.add(a);
				}
				data.shopMigrated = true;
				state.markDirty();
			}
			com.toast.aurapoints.network.NetworkHandler.syncOwned(player, data);
			Changelog.onLogin(player, data);
			if (!data.bookGiven) {
				data.bookGiven = true;
				state.markDirty();
				com.toast.aurapoints.item.AuraBookItem.give(player);
				player.sendSystemMessage(net.minecraft.network.chat.Component.literal("You got an Aura Book: right-click it for your aura, the shop, skill points, settings and a tutorial.")
						.withStyle(net.minecraft.ChatFormatting.GOLD));
			}

			if (data.aura == null) {
				player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
						"You have no aura yet - your first roll is free: /aura roll")
						.withStyle(net.minecraft.ChatFormatting.GOLD));
			}
		}

		// -------------------------------------------------------
		// Commands
		// -------------------------------------------------------

		@SubscribeEvent
		public static void onRegisterCommands(RegisterCommandsEvent event) {
			AuraCommand.register(event.getDispatcher());
			com.toast.aurapoints.arena.ArenaCommand.register(event.getDispatcher());
			MasteryCommand.register(event.getDispatcher());
			Changelog.register(event.getDispatcher());
			Tickets.register(event.getDispatcher());
			DevTest.register(event.getDispatcher());
			// last: config/aurapoints-commands.json decides who may use each of them
			com.toast.aurapoints.config.CommandRules.apply(event.getDispatcher());
		}
	}
}
