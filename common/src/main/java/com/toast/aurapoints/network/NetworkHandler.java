package com.toast.aurapoints.network;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.network.PacketDistributor;
import com.toast.aurapoints.shim.neoforge.network.event.RegisterPayloadHandlersEvent;
import com.toast.aurapoints.shim.neoforge.network.handling.IPayloadContext;
import com.toast.aurapoints.shim.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers {@link AbilityKeyPacket} and {@link DoubleJumpPacket} (handled on
 * the server) and the HUD packets {@link AuraHudPacket} and {@link CooldownPacket}
 * (handled on the client). Runs on the mod event bus (RegisterPayloadHandlersEvent
 * is a mod-bus event).
 */
@EventBusSubscriber(modid = "aurapoints")
public final class NetworkHandler {

	private NetworkHandler() {
	}

	@SubscribeEvent
	public static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("41");
		registrar.playToServer(
				AbilityKeyPacket.TYPE,
				AbilityKeyPacket.STREAM_CODEC,
				NetworkHandler::handleKeyOnServer
		);
		registrar.playToServer(
				DoubleJumpPacket.TYPE,
				DoubleJumpPacket.STREAM_CODEC,
				NetworkHandler::handleDoubleJumpOnServer
		);
		// a swing at nothing: the M1 hook, and it breaks the Shatter combo; only the client sees misses
		registrar.playToServer(
				SwingMissPacket.TYPE,
				SwingMissPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> {
					// at most one a tick per player: a tampered client can't flood the M1 hook (it runs datapack functions)
					if (context.player() instanceof ServerPlayer player && player.getServer() != null
							&& !Long.valueOf(player.getServer().getTickCount()).equals(LAST_SWING.put(player.getUUID(), (long) player.getServer().getTickCount()))) {
						com.toast.aurapoints.AuraHooks.m1(player);
						if (!com.toast.aurapoints.ability.Mjolnir.onLeftClick(player)) com.toast.aurapoints.ability.Shatter.reset(player);
					}
				})
		);
		// Server -> client, for the cooldown HUD. The handlers only touch ClientAuraState (plain data).
		registrar.playToClient(
				AuraHudPacket.TYPE,
				AuraHudPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setAura(packet.aura()))
		);
		registrar.playToClient(
				CooldownPacket.TYPE,
				CooldownPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.startCooldown(packet.ability(), packet.ticks()))
		);
		registrar.playToClient(
				AccessorySlotsPacket.TYPE,
				AccessorySlotsPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> {
					com.toast.aurapoints.AccessorySlots.clientCount = packet.count();
					com.toast.aurapoints.AccessorySlots.clientSingleItem = packet.singleItem();
				})
		);
		registrar.playToClient(
				GraspPacket.TYPE,
				GraspPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setGrasp(packet))
		);
		registrar.playToClient(
				EchoPacket.TYPE,
				EchoPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> {
					ClientAuraState.echoFogStart = packet.fogStart();
					ClientAuraState.echoFogEnd = packet.fogEnd();
					ClientAuraState.graspZoom = packet.graspZoom();
					ClientAuraState.lavaSight = packet.lavaSight();
				})
		);
		registrar.playToClient(
				SettingsPacket.TYPE,
				SettingsPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.selfHide = packet.selfHide())
		);
		registrar.playToClient(
				DisabledAbilitiesPacket.TYPE,
				DisabledAbilitiesPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> {
					ClientAuraState.setDisabled(packet.names());
					ClientAuraState.owner = packet.owner();
				})
		);
		registrar.playToClient(
				OwnedAbilitiesPacket.TYPE,
				OwnedAbilitiesPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setOwned(packet.names()))
		);
		registrar.playToClient(
				FireBendingPacket.TYPE,
				FireBendingPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setFireBending(packet.active(), packet.charge(), packet.readyAt()))
		);
		registrar.playToClient(
				BubblePacket.TYPE,
				BubblePacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setBubble(packet))
		);
		registrar.playToClient(
				BubblePullPacket.TYPE,
				BubblePullPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setBubblePull(packet))
		);
		registrar.playToClient(
				PlayerAuraPacket.TYPE,
				PlayerAuraPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setPlayerAura(packet.entityId(), packet.aura()))
		);
		registrar.playToClient(
				CarriedPacket.TYPE,
				CarriedPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setCarried(packet.ticks()))
		);
		registrar.playToClient(
				RootedPacket.TYPE,
				RootedPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> {
					ClientAuraState.rooted = packet.on();
					ClientAuraState.mash = packet.on() && packet.mash();
				})
		);
		registrar.playToServer(
				MashKeyPacket.TYPE,
				MashKeyPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> {
					if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
						com.toast.aurapoints.ability.Entanglement.onMashKey(player, packet.key());
					}
				})
		);
		registrar.playToClient(
				IceShellPacket.TYPE,
				IceShellPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.iceShell = packet)
		);
		registrar.playToClient(
				FrozenPacket.TYPE,
				FrozenPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setFrozen(packet.ticks()))
		);
		registrar.playToClient(
				HighlightPacket.TYPE,
				HighlightPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setHighlighted(packet.ids()))
		);
		registrar.playToClient(
				BendingAnimPacket.TYPE,
				BendingAnimPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setBendState(packet.entityId(), packet.state(), packet.windup(), packet.hold()))
		);
		registrar.playToClient(
				ArenaTitlePacket.TYPE,
				ArenaTitlePacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.arenaTitle.accept(packet))
		);
		registrar.playToClient(
				FxPacket.TYPE,
				FxPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.pushFx(packet))
		);
		registrar.playToClient(
				CloakPacket.TYPE,
				CloakPacket.STREAM_CODEC,
				(packet, context) -> context.enqueueWork(() -> ClientAuraState.setCloaked(packet.entityId(), packet.cloaked()))
		);
	}

	/** The tick each player's last swing-at-nothing arrived (one per tick is plenty for real clicks). */
	private static final java.util.Map<java.util.UUID, Long> LAST_SWING = new java.util.HashMap<>();

	public static void forgetSwings(ServerPlayer player) {
		LAST_SWING.remove(player.getUUID());
	}

	/** Tells the player's HUD which aura they hold now (call whenever it changes, and on login). */
	public static void syncAura(ServerPlayer player, AuraPlayerData data) {
		PacketDistributor.sendToPlayer(player, new AuraHudPacket(data.aura == null ? "" : data.aura.id()));
		com.toast.aurapoints.AuraHooks.syncAura(player, data);
		// and the label over their name, for them and everyone who sees them
		PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new PlayerAuraPacket(player.getId(), labelOf(data)));
	}

	/** {@code target}'s aura label to one player who just started seeing them. */
	public static void sendPlayerAuraTo(ServerPlayer watcher, ServerPlayer target, AuraPlayerData data) {
		PacketDistributor.sendToPlayer(watcher, new PlayerAuraPacket(target.getId(), labelOf(data)));
	}

	/** "fire", or "fire*3" with Mastery III (ClientAuraState.setPlayerAura reads it). */
	private static String labelOf(AuraPlayerData data) {
		if (data.aura == null || !com.toast.aurapoints.config.AuraConfig.AURA_NAMETAG_ENABLED) return "";
		int mastery = com.toast.aurapoints.config.AuraConfig.MASTERY_ENABLED ? data.mastery.getOrDefault(data.aura, 0) : 0;
		return mastery > 0 ? data.aura.id() + "*" + mastery : data.aura.id();
	}

	/** Sends the active abilities the player owns (on login and whenever they change). */
	public static void syncOwned(ServerPlayer player, AuraPlayerData data) {
		StringBuilder names = new StringBuilder();
		for (AuraAbilities.Ability a : data.owned) {
			if (!names.isEmpty()) names.append(',');
			names.append(a.name());
		}
		// the Ultimate of an awakened aura shows on the HUD like an owned ability
		AuraAbilities.Ability ult = com.toast.aurapoints.ability.Ultimates.of(data.aura);
		if (ult != null && data.awakened.contains(data.aura)) {
			if (!names.isEmpty()) names.append(',');
			names.append(ult.name());
		}
		PacketDistributor.sendToPlayer(player, new OwnedAbilitiesPacket(names.toString()));
	}

	/** Sends the abilities switched off in the server config (on login and after every reload). */
	public static void syncDisabled(ServerPlayer player) {
		// Echolocation's fog distances and Fireborn's lava sight ride along (sent on login and every reload, like this)
		PacketDistributor.sendToPlayer(player, new EchoPacket(com.toast.aurapoints.config.AuraConfig.ECHO_FOG_START, com.toast.aurapoints.config.AuraConfig.ECHO_FOG_END,
				com.toast.aurapoints.config.AuraConfig.GRASP_ZOOM, com.toast.aurapoints.config.AuraConfig.FIREBORN_LAVA_SIGHT));
		StringBuilder names = new StringBuilder();
		MinecraftServer server = player.getServer();
		// locked by an admin: everything shows as off
		boolean locked = AuraAbilities.allLocked || (server != null && AuraState.get(server).getOrCreate(player.getUUID()).locked);
		for (AuraAbilities.Ability a : locked ? java.util.EnumSet.allOf(AuraAbilities.Ability.class) : AuraAbilities.DISABLED) {
			if (!names.isEmpty()) names.append(',');
			names.append(a.name());
		}
		PacketDistributor.sendToPlayer(player, new DisabledAbilitiesPacket(names.toString(), com.toast.aurapoints.config.CommandRules.isOwner(player)));
	}

	/** Sends the accessory slot count (on login and after every reload) and moves items out of slots that were switched off. */
	public static void syncAccessorySlots(ServerPlayer player) {
		PacketDistributor.sendToPlayer(player, new AccessorySlotsPacket(com.toast.aurapoints.AccessorySlots.count(false), com.toast.aurapoints.config.AuraConfig.ACCESSORY_SINGLE_ITEM));
		com.toast.aurapoints.AccessorySlots.tidy(player);
	}

	/** Sends the player's own display settings (on login and when they change). */
	public static void syncSettings(ServerPlayer player, AuraPlayerData data) {
		PacketDistributor.sendToPlayer(player, new SettingsPacket(data.selfHide));
	}

	/** Tells the player's HUD that an ability just went on cooldown. */
	public static void sendCooldown(ServerPlayer player, AuraAbilities.Ability ability, int ticks) {
		PacketDistributor.sendToPlayer(player, new CooldownPacket(ability.name(), ticks));
	}

	/** Same for one of an ability's extra cooldowns (flight set, Wind launch). */
	public static void sendCooldown(ServerPlayer player, AuraAbilities.Ability ability, AuraAbilities.Slot slot, int ticks) {
		PacketDistributor.sendToPlayer(player, new CooldownPacket(ability.name() + CooldownPacket.SLOT_SEPARATOR + slot.name(), ticks));
	}

	/** A visual moment on the player's own screen (FxPacket kinds). */
	public static void sendFx(ServerPlayer player, byte kind, int value, String text) {
		PacketDistributor.sendToPlayer(player, new FxPacket(kind, value, text == null ? "" : text));
	}

	/** Updates the Fire Bending charge bar (active=false hides it). */
	public static void sendFireBending(ServerPlayer player, boolean active, float charge, float readyAt) {
		PacketDistributor.sendToPlayer(player, new FireBendingPacket(active, charge, readyAt));
	}

	/** Abyssal Bubble on/off for the holder's own client (camera shake and the stability bar), with the shake settings. */
	public static void sendBubble(ServerPlayer player, boolean active, float startYaw, float startPitch) {
		PacketDistributor.sendToPlayer(player, new BubblePacket(active, startYaw, startPitch,
				(float) com.toast.aurapoints.config.AuraConfig.BUBBLE_SHAKE_START,
				(float) com.toast.aurapoints.config.AuraConfig.BUBBLE_SHAKE_PER_SECOND,
				(float) com.toast.aurapoints.config.AuraConfig.BUBBLE_BREAK_DEGREES,
				com.toast.aurapoints.config.AuraConfig.BUBBLE_MAX_HOLD_TICKS / 20f));
	}

	/** Someone else's Abyssal Bubble pulls this player toward its middle (sent every tick they are inside). */
	public static void sendBubblePull(ServerPlayer player, double centerX, double centerZ) {
		PacketDistributor.sendToPlayer(player, new BubblePullPacket(centerX, centerZ,
				(float) com.toast.aurapoints.config.AuraConfig.BUBBLE_PULL_STRENGTH,
				(float) com.toast.aurapoints.config.AuraConfig.BUBBLE_OUTWARD_DRAG));
	}

	/** The player's Ice Shell layers, for their HUD slot. */
	public static void sendIceShell(ServerPlayer player, int maxLayers, int layers, float top, float rebuild, int nextTicks) {
		PacketDistributor.sendToPlayer(player, new IceShellPacket(maxLayers, layers, top, rebuild, nextTicks));
	}

	/** Frozen solid by an Ice Spike for {@code ticks} (0 = thawed): the player's own client locks its controls. */
	public static void sendFrozen(ServerPlayer player, int ticks) {
		PacketDistributor.sendToPlayer(player, new FrozenPacket(ticks));
	}

	/** Fire sight: the entities this player's client should draw glowing (empty = none). */
	/** What the owner's tentacles do now, for everyone who sees them (and themselves). */
	public static void sendGrasp(ServerPlayer owner, int phase, net.minecraft.world.entity.Entity target, net.minecraft.world.phys.Vec3 at, int ticks) {
		PacketDistributor.sendToPlayersTrackingEntityAndSelf(owner, new GraspPacket(owner.getId(), phase, target == null ? -1 : target.getId(),
				at == null ? 0f : (float) at.x, at == null ? 0f : (float) at.y, at == null ? 0f : (float) at.z, ticks));
	}

	public static void sendHighlight(ServerPlayer player, java.util.List<Integer> ids) {
		PacketDistributor.sendToPlayer(player, new HighlightPacket(ids));
	}

	/** The player's Fire Bending pose, to everyone who sees them and to themselves. */
	public static void sendBendAnim(ServerPlayer player, byte state) {
		if (state == BendingAnimPacket.AIR_RAISED) sendBendAnim(player, state, airArmOut(), airArmHold());
		else sendBendAnim(player, state, 0, 0);
	}

	/** The Air Bending arm angles as sent with AIR_RAISED. */
	public static int airArmOut() {
		return BendingAnimPacket.armAngle(com.toast.aurapoints.config.AuraConfig.AIR_BENDING_RAISED_OUT_DEGREES,
				com.toast.aurapoints.config.AuraConfig.AIR_BENDING_RAISED_FORWARD_PIXELS,
				com.toast.aurapoints.config.AuraConfig.AIR_BENDING_RAISED_RIGHT_PIXELS);
	}

	public static int airArmHold() {
		return BendingAnimPacket.armHold(com.toast.aurapoints.config.AuraConfig.AIR_BENDING_RAISED_FORWARD_DEGREES,
				com.toast.aurapoints.config.AuraConfig.AIR_BENDING_RAISED_DROP_PIXELS,
				com.toast.aurapoints.config.AuraConfig.AIR_BENDING_RAISED_TWIST_DEGREES);
	}

	/** With timings (ticks), for moves that need them: the Inferno Slam's windup and how long the arms stay down. */
	public static void sendBendAnim(ServerPlayer player, byte state, int windup, int hold) {
		PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new BendingAnimPacket(player.getId(), state, windup, hold));
	}

	/** A one-shot ability gesture (client/Gestures) for the player and everyone who sees them. */
	public static void sendGesture(ServerPlayer player, int kind, int variant) {
		sendBendAnim(player, BendingAnimPacket.GESTURE, kind, variant);
	}

	/** The pose of {@code target} to one player who just started seeing them. */
	public static void sendBendAnimTo(ServerPlayer watcher, ServerPlayer target, byte state) {
		boolean air = state == BendingAnimPacket.AIR_RAISED;
		PacketDistributor.sendToPlayer(watcher, new BendingAnimPacket(target.getId(), state, air ? airArmOut() : 0, air ? airArmHold() : 0));
	}

	/** Scoreboard objective datapacks can read for a given key, e.g. aura_key_dash. */
	public static String objectiveName(AuraAbilities.Ability ability) {
		return "aura_key_" + ability.name().toLowerCase(java.util.Locale.ROOT);
	}

	/** Writes the held state into the scoreboard. Does nothing if the objective was not created. */
	public static void setKeyFlag(MinecraftServer server, ServerPlayer player, AuraAbilities.Ability ability, boolean down) {
		Scoreboard scoreboard = server.getScoreboard();
		Objective objective = scoreboard.getObjective(objectiveName(ability));
		if (objective != null) {
			scoreboard.getOrCreatePlayerScore(player, objective).set(down ? 1 : 0);
		}
	}

	public static void clearKeyFlags(MinecraftServer server, ServerPlayer player) {
		for (AuraAbilities.Ability ability : AuraAbilities.KEY_ORDER) {
			setKeyFlag(server, player, ability, false);
		}
		com.toast.aurapoints.AuraHooks.clearItemKeys(player);
		HELD_SLOTS.remove(player.getUUID());
	}

	/** True while the player holds down any Ability key that started an ability (it makes noise, for Resonance). */
	public static boolean holdsAbilityKey(ServerPlayer player) {
		AuraAbilities.Ability[] held = HELD_SLOTS.get(player.getUUID());
		if (held == null) return false;
		for (AuraAbilities.Ability a : held) if (a != null) return true;
		return false;
	}

	/** Per player, the ability each Ability key started on its press - its release goes to the same one, even if the aura changed meanwhile. */
	private static final java.util.Map<java.util.UUID, AuraAbilities.Ability[]> HELD_SLOTS = new java.util.HashMap<>();

	/** AbilityKeyPacket key -> AuraHooks.ITEM_OBJECTIVES index (Item Use, Item Secondary, Item Tertiary), or -1 for any other key. */
	private static int itemSlot(int key) {
		int slots = com.toast.aurapoints.ability.AuraType.KEY_SLOTS;
		if (key == slots || key == slots + 1) return key - slots;
		if (key == AbilityKeyPacket.ITEM_TERTIARY) return 2;
		return -1;
	}

	private static void handleKeyOnServer(AbilityKeyPacket packet, IPayloadContext context) {
		// Network thread: hop to the main thread before touching world state.
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player)) return;
			MinecraftServer server = player.getServer();
			if (server == null) return;

			int slots = com.toast.aurapoints.ability.AuraType.KEY_SLOTS;
			// the Ultimate key: a press is all it takes
			if (packet.key() == AbilityKeyPacket.ULTIMATE) {
				if (packet.down()) com.toast.aurapoints.ability.Ultimates.press(player, server.getTickCount());
				return;
			}
			// Never trust the client: ignore indexes that do not map to a key.
			int itemSlot = itemSlot(packet.key());
			if (packet.key() < 0 || (packet.key() >= slots && itemSlot < 0)) return;
			// Item Use / Item Secondary / Item Tertiary: only a scoreboard for datapacks (nothing while frozen)
			if (itemSlot >= 0) {
				boolean frozen = com.toast.aurapoints.ability.IceSpike.isFrozen(player);
				com.toast.aurapoints.AuraHooks.setItemKey(player, itemSlot, packet.down() && !frozen);
				// Item Use with a handheld weapon: its ability
				if (packet.key() == slots && packet.down() && !frozen) com.toast.aurapoints.ability.Handhelds.onItemKey(player);
				return;
			}

			// Ability 3 while holding Mjölnir: spin it (test item, owner only)
			if (packet.key() == 2 && com.toast.aurapoints.ability.Mjolnir.handlesKey(player, packet.down())) {
				if (packet.down()) com.toast.aurapoints.ability.Mjolnir.press(player, server.getTickCount());
				else com.toast.aurapoints.ability.Mjolnir.release(player);
				return;
			}

			AuraState state = AuraState.get(server);
			AuraPlayerData data = state.getOrCreate(player.getUUID());
			// Ability N: whatever the player's aura has there; a release goes to what the press started
			AuraAbilities.Ability[] held = HELD_SLOTS.computeIfAbsent(player.getUUID(), k -> new AuraAbilities.Ability[slots]);
			AuraAbilities.Ability ability;
			if (packet.down()) {
				ability = data.aura == null ? null : data.aura.slot(packet.key());
				held[packet.key()] = ability;
			} else {
				ability = held[packet.key()];
				held[packet.key()] = null;
			}
			if (ability == null) return;
			// frozen by an Ice Spike: no abilities (releases still count, so nothing stays held)
			if (packet.down() && com.toast.aurapoints.ability.IceSpike.isFrozen(player)) return;
			// busy with a big move (Inferno Slam windup / stun): keys do nothing, but releases still count
			if (packet.down() && com.toast.aurapoints.ability.Stun.isBusy(player)) {
				if (com.toast.aurapoints.ability.Stun.isStunned(player)) {
					player.displayClientMessage(net.minecraft.network.chat.Component.literal("Stunned!")
							.withStyle(net.minecraft.ChatFormatting.GRAY), true);
				}
				return;
			}
			if (!AuraAbilities.hasAbility(data, ability)) {
				// a release still lets go of the key, so nothing it started stays held
				if (!packet.down()) {
					setKeyFlag(server, player, ability, false);
					com.toast.aurapoints.ability.WindLaunch.cancelHolds(player);
					return;
				}
				if (packet.down() && (data.locked || AuraAbilities.allLocked)) {
					player.displayClientMessage(net.minecraft.network.chat.Component.literal("Your aura is switched off.")
							.withStyle(net.minecraft.ChatFormatting.RED), true);
					return;
				}
				// Your aura has it, you just haven't bought it: say so instead of doing nothing.
				if (packet.down() && data.aura != null && data.aura.has(ability) && !AuraAbilities.DISABLED.contains(ability)) {
					player.displayClientMessage(net.minecraft.network.chat.Component.literal(
							"You don't own " + ability.displayName + " yet - buy it in /aura shop")
							.withStyle(net.minecraft.ChatFormatting.YELLOW), true);
				}
				return;
			}

			setKeyFlag(server, player, ability, packet.down());

			if (com.toast.aurapoints.ability.WindLaunch.handlesKey(data, ability)) {
				// Wind's hold keys: Dash (tap = dash, hold = launch), Super Wind Charge (tap = throw, hold = air bending)
				if (packet.down()) com.toast.aurapoints.ability.WindLaunch.press(player, ability, server.getTickCount());
				else com.toast.aurapoints.ability.WindLaunch.release(player, ability, server.getTickCount());
			} else if (packet.down()) {
				AuraAbilities.tryActivate(player, ability, server.getTickCount());
			} else if (ability == AuraAbilities.Ability.ABYSSAL_BUBBLE) {
				com.toast.aurapoints.ability.AbyssalBubble.release(player, server.getTickCount());
			} else if (ability == AuraAbilities.Ability.ICE_SPIKE) {
				com.toast.aurapoints.ability.IceSpike.release(player, server.getTickCount());
			} else if (ability == AuraAbilities.Ability.CHIDORI) {
				com.toast.aurapoints.ability.Chidori.release(player, server.getTickCount());
			} else if (ability == AuraAbilities.Ability.ABYSSAL_GRASP) {
				com.toast.aurapoints.ability.Resonance.graspRelease(player, server.getTickCount());
			} else if (ability == AuraAbilities.Ability.PRISM_BEAM) {
				com.toast.aurapoints.ability.Light.beamRelease(player, server.getTickCount());
			}
		});
	}

	/** The client sends this for every mid-air jump press; only Wind players get anything out of it. */
	private static void handleDoubleJumpOnServer(DoubleJumpPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player)) return;
			MinecraftServer server = player.getServer();
			if (server == null) return;

			AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
			if (!AuraAbilities.hasAbility(data, AuraAbilities.Ability.DOUBLE_JUMP) || com.toast.aurapoints.ability.Stun.isBusy(player)) return;
			// the same holds as the Ability keys: frozen, entangled, rooted, nulled, steering a viper, held on the duel pad
			if (com.toast.aurapoints.ability.IceSpike.isFrozen(player) || com.toast.aurapoints.ability.Entanglement.isEntangled(player)
					|| com.toast.aurapoints.ability.Rooted.isRooted(player) || com.toast.aurapoints.ability.VoidAura.isNulled(player)
					|| com.toast.aurapoints.ability.VerdantViper.isSteering(player) || com.toast.aurapoints.arena.Duel.holdsStill(player)) return;

			AuraAbilities.tryDoubleJump(player, server.getTickCount());
		});
	}
}
