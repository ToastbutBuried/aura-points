package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Aura Arena: its own dimension (data/aurapoints/dimension/arena.json, void) with a map built in code
 * (ArenaBuilder): a lobby, the duel arena to the north and the training grounds to the south.
 * Players come in with /aura arena (where they were is saved) and go back with /aura arena leave. Nobody really
 * dies in here and nobody can hurt another player outside a running duel (Duel).
 */
public final class Arena {

	private Arena() {
	}

	public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("aurapoints", "arena"));

	/** Floor height of the whole map: players stand at FLOOR + 1. */
	public static final int FLOOR = 64;
	public static final Vec3 LOBBY = new Vec3(0.5, FLOOR + 1, 0.5);
	/** The duel ring: a pit (its floor RING_FLOOR) with centre z RING_Z and radius RING_R; spectators stand around it at FLOOR. */
	public static final int RING_Z = -220, RING_R = 27, RING_FLOOR = FLOOR - 3;
	/** How far the pads sit from the ring's centre (north and south). */
	public static final int PAD_OFFSET = 19;
	/** Duel pads, facing each other across the ring. */
	public static final Vec3 PAD_A = new Vec3(0.5, RING_FLOOR + 1, RING_Z - PAD_OFFSET + 0.5), PAD_B = new Vec3(0.5, RING_FLOOR + 1, RING_Z + PAD_OFFSET + 0.5);
	/** The lobby hub's radius. */
	public static final int LOBBY_R = 26;
	/** The Training Hall (outer walls, blocks, inclusive) - an island of its own, far south. */
	public static final int TRAIN_X = 27, TRAIN_Z0 = 200, TRAIN_Z1 = 292;
	/** The hall's Spar Pit (the only place players can fight each other outside a duel): a sunken circle, centre x 0. */
	public static final int SPAR_Z = TRAIN_Z0 + 45, SPAR_R = 6;
	/** Where the lobby's NPCs put you: the ring's south rim, the hall's foyer. */
	public static final Vec3 RING_ARRIVAL = new Vec3(0.5, FLOOR + 1, RING_Z + RING_R + 3.5), HALL_ARRIVAL = new Vec3(0.5, FLOOR + 1, TRAIN_Z0 + 3.5);
	/** Below this you've fallen off the map and are put back. */
	private static final int VOID_Y = FLOOR - 24;

	public static ServerLevel level(MinecraftServer server) {
		return server.getLevel(KEY);
	}

	public static boolean in(Entity e) {
		return e.level().dimension() == KEY;
	}

	public static boolean inTraining(Entity e) {
		if (!in(e)) return false;
		BlockPos p = e.blockPosition();
		return Math.abs(p.getX()) <= TRAIN_X && p.getZ() >= TRAIN_Z0 && p.getZ() <= TRAIN_Z1;
	}

	/** Training grounds with the player's own cooldowns off: AuraAbilities.cooldownsDisabled says yes. */
	/** Inside the Training Hall's Spar Pit (its fence included). */
	public static boolean inSparPit(Entity e) {
		if (!inTraining(e)) return false;
		double dx = e.getX() - 0.5, dz = e.getZ() - (SPAR_Z + 0.5);
		return dx * dx + dz * dz <= (SPAR_R + 1) * (SPAR_R + 1) && e.getY() < FLOOR + 4;
	}

	public static boolean freeCooldowns(ServerPlayer player) {
		if (!inTraining(player) || player.getServer() == null || TrainingHall.inChallenge(player)) return false;
		return !AuraState.get(player.getServer()).getOrCreate(player.getUUID()).trainingCooldowns;
	}

	// ---------------------------------------------------------------
	// In and out
	// ---------------------------------------------------------------

	/** /aura arena: to the lobby, remembering where the player was (unless already inside). */
	public static boolean enter(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		ServerLevel arena = server == null ? null : level(server);
		if (arena == null) {
			player.sendSystemMessage(Component.literal("The Aura Arena isn't loaded on this server.").withStyle(ChatFormatting.RED));
			return false;
		}
		if (!AuraConfig.ARENA_ENABLED) {
			player.sendSystemMessage(Component.literal("The Aura Arena is closed right now.").withStyle(ChatFormatting.RED));
			return false;
		}
		AuraState state = AuraState.get(server);
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		rememberReturn(player);
		toLobby(player);
		player.sendSystemMessage(Component.literal("Welcome to the Aura Arena! ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("Click an NPC to play: duels, the Training Hall, spectating. /aura arena lobby brings you back here, /aura leave takes you home (from any match too).")
						.withStyle(ChatFormatting.GRAY)));
		return true;
	}

	/**
	 * The spot outside the arena that /aura leave (and /aura arena leave) brings the player back to. Called by /aura arena and
	 * for every other way into the arena dimension (EntityTravelToDimensionEvent, before the move): /tp, portals, mods.
	 */
	public static void rememberReturn(ServerPlayer player) {
		if (in(player) || player.getServer() == null) return;
		AuraState state = AuraState.get(player.getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		data.arenaReturnDim = player.level().dimension().location().toString();
		data.arenaReturnX = player.getX();
		data.arenaReturnY = player.getY();
		data.arenaReturnZ = player.getZ();
		data.arenaReturnYaw = player.getYRot();
		data.arenaReturnPitch = player.getXRot();
		data.arenaReturnMode = player.gameMode.getGameModeForPlayer().getId();
		state.markDirty();
	}

	/** Lobby spot, in adventure mode for anyone who'd otherwise break the map. */
	public static void toLobby(ServerPlayer player) {
		to(player, LOBBY, 180f);
	}

	/** The duel ring's south rim, looking in. */
	public static void toRing(ServerPlayer player) {
		to(player, RING_ARRIVAL, 180f);
	}

	/** Inside the Training Hall's door. */
	public static void toHall(ServerPlayer player) {
		to(player, HALL_ARRIVAL, 0f);
	}

	private static void to(ServerPlayer player, Vec3 at, float yaw) {
		ServerLevel arena = level(player.getServer());
		if (arena == null) return;
		Spectate.stop(player);
		player.teleportTo(arena, at.x, at.y, at.z, yaw, 0f);
		player.fallDistance = 0;
		player.setDeltaMovement(Vec3.ZERO);
		if (player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL || player.isSpectator()) player.setGameMode(GameType.ADVENTURE);
	}

	/** /aura arena leave (also: logging in inside the arena): back where /aura arena took them from, or to world spawn. */
	public static void leave(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		Duel.forfeit(player, "left the arena");
		Spectate.stop(player);
		AuraState state = AuraState.get(server);
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		String dim = data.arenaReturnDim;
		ServerLevel to = null;
		if (!dim.isEmpty()) {
			ResourceLocation id = ResourceLocation.tryParse(dim);
			if (id != null) to = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		}
		GameType mode = dim.isEmpty() ? GameType.SURVIVAL : GameType.byId(data.arenaReturnMode);
		double x = data.arenaReturnX, y = data.arenaReturnY, z = data.arenaReturnZ;
		float yaw = data.arenaReturnYaw, pitch = data.arenaReturnPitch;
		// forget it first: the dimension change below then isn't taken for walking out some other way
		data.arenaReturnDim = "";
		state.markDirty();
		if (to == null || to == level(server)) {
			to = server.overworld();
			BlockPos spawn = to.getSharedSpawnPos();
			x = spawn.getX() + 0.5;
			y = spawn.getY();
			z = spawn.getZ() + 0.5;
		}
		player.setGameMode(mode);
		player.teleportTo(to, x, y, z, yaw, pitch);
		player.fallDistance = 0;
		player.sendSystemMessage(Component.literal("You left the Aura Arena.").withStyle(ChatFormatting.GRAY));
	}

	/** Left the arena dimension some other way (/home, /back, /tp): their game mode back, and any duel forfeited. */
	public static void onChangedDimension(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to) {
		if (from != KEY || to == KEY || player.getServer() == null) return;
		Duel.forfeit(player, "left the arena");
		Spectate.forget(player);
		AuraState state = AuraState.get(player.getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		if (!data.arenaReturnDim.isEmpty()) {
			player.setGameMode(GameType.byId(data.arenaReturnMode));
			data.arenaReturnDim = "";
			state.markDirty();
		}
		forgetTraining(player);
	}

	/** Logged in inside the arena (the server restarted, or they logged out mid-duel): sent back. */
	public static void onLogin(ServerPlayer player) {
		if (in(player)) leave(player);
	}

	public static void onLogout(ServerPlayer player) {
		Duel.forfeit(player, "logged out");
		Spectate.forget(player);
		forgetTraining(player);
	}

	// ---------------------------------------------------------------
	// Every tick: falling off the map, training cooldowns on walking in or out
	// ---------------------------------------------------------------

	/** Players whose cooldowns are currently off for the training grounds. */
	private static final Set<UUID> FREE = new HashSet<>();

	public static void tick(MinecraftServer server) {
		ServerLevel arena = level(server);
		if (arena == null) return;
		Duel.tick(server);
		TrainingHall.tick(server);
		ArenaFx.tick(arena);
		Spectate.tick(server);
		Holograms.tick(arena);
		TrainingDummy.tickNumbers();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean free = freeCooldowns(player);
			if (free != FREE.contains(player.getUUID())) {
				if (free) FREE.add(player.getUUID());
				else FREE.remove(player.getUUID());
				// whatever ran (or didn't) in there starts fresh
				resetCooldowns(player);
				player.displayClientMessage(Component.literal(free
						? "Training Hall: no cooldowns (the Cooldowns sign in the gallery, or /aura arena cooldowns on)"
						: "Cooldowns are back on").withStyle(ChatFormatting.YELLOW), true);
			}
			if (player.level() == arena && player.getY() < VOID_Y && !player.isSpectator()) {
				// back onto the island they fell off
				Vec3 pad = Duel.padOf(player);
				if (pad != null) player.teleportTo(arena, pad.x, pad.y, pad.z, player.getYRot(), player.getXRot());
				else if (player.getZ() < RING_Z / 2.0) toRing(player);
				else if (player.getZ() > TRAIN_Z0 * 0.7) toHall(player);
				else toLobby(player);
				player.setDeltaMovement(Vec3.ZERO);
				player.fallDistance = 0;
			}
		}
	}

	private static void forgetTraining(ServerPlayer player) {
		if (FREE.remove(player.getUUID())) resetCooldowns(player);
	}

	/** Every cooldown of this player ready, and their HUD told so. */
	public static void resetCooldowns(ServerPlayer player) {
		if (player.getServer() == null) return;
		AuraAbilities.clearCooldowns(player);
		NetworkHandler.syncAura(player, AuraState.get(player.getServer()).getOrCreate(player.getUUID()));
	}

	public static void setTrainingCooldowns(ServerPlayer player, boolean on) {
		if (player.getServer() == null) return;
		AuraState state = AuraState.get(player.getServer());
		state.getOrCreate(player.getUUID()).trainingCooldowns = on;
		state.markDirty();
		player.displayClientMessage(Component.literal(on
				? "Training Hall: your cooldowns are ON"
				: "Training Hall: your cooldowns are OFF - spam away!").withStyle(ChatFormatting.GOLD), true);
	}

	public static void clearAll() {
		FREE.clear();
		ABILITY_HITS.clear();
		Duel.clearAll();
		Spectate.clearAll();
		Holograms.clearAll();
		NpcKind.clearAll();
		TrainingHall.clearAll();
		ArenaFx.clearAll();
		TrainingDummy.clearNumbers();
		RingLayout.current = RingLayout.PILLARS;
		RingLayout.forced = null;
	}

	// ---------------------------------------------------------------
	// Damage: duellers in a running round, and sparring in the Training Hall - nowhere else.
	// Nobody dies in here.
	// ---------------------------------------------------------------

	/** Recent ability hits per entity id (tick), so a knockout line knows an ability did it. */
	private static final Map<Integer, Long> ABILITY_HITS = new HashMap<>();

	/** From the incoming-damage hook, where the ability-hit mark is taken. */
	public static void noteHit(LivingEntity victim, boolean abilityHit) {
		if (!in(victim)) return;
		if (abilityHit) ABILITY_HITS.put(victim.getId(), victim.level().getGameTime());
		else ABILITY_HITS.remove(victim.getId());
		if (ABILITY_HITS.size() > 256) ABILITY_HITS.clear();
	}

	static boolean wasAbilityHit(LivingEntity victim) {
		Long tick = ABILITY_HITS.get(victim.getId());
		return tick != null && victim.level().getGameTime() - tick <= 1;
	}

	/** Sparring: players in the Training Hall's Spar Pit can fight each other there (ARENA_HALL_PVP) - nowhere else in the hall. */
	private static boolean sparring(ServerPlayer victim, net.minecraft.world.entity.Entity attacker) {
		if (!AuraConfig.ARENA_HALL_PVP || !inSparPit(victim) || Duel.isIn(victim)) return false;
		return !(attacker instanceof ServerPlayer p) || inSparPit(p) && !Duel.isIn(p);
	}

	/**
	 * True = cancel this damage. Players in the arena only get hurt by their opponent mid-round, or
	 * sparring in the Training Hall's Spar Pit - never in the lobby, on the ring's rim or while watching.
	 */
	public static boolean blocksDamage(LivingEntity victim, DamageSource source) {
		if (!(victim instanceof ServerPlayer player) || !in(player)) return false;
		if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
		if (sparring(player, source.getEntity())) return false;
		return !Duel.canBeHurt(player, source.getEntity());
	}

	/** A hit that would kill a dueller (a knockout) or a sparring player (back to the hall's door). True = handled (set the damage to 0). */
	public static boolean catchLethal(LivingEntity victim, DamageSource source, float damage) {
		if (!(victim instanceof ServerPlayer player) || !in(player)) return false;
		if (damage < player.getHealth() + player.getAbsorptionAmount()) return false;
		if (Duel.knockout(player, source, wasAbilityHit(player))) return true;
		if (inTraining(player)) {
			hallKnockout(player, source);
			return true;
		}
		return false;
	}

	/** Any death in here that still got through (a dueller's or anyone's): cancelled, healed, and a knockout or back to safety. */
	public static boolean catchDeath(LivingEntity victim, DamageSource source) {
		if (!(victim instanceof ServerPlayer player) || !in(player)) return false;
		player.setHealth(player.getMaxHealth());
		if (Duel.knockout(player, source, wasAbilityHit(player))) return true;
		if (inTraining(player)) hallKnockout(player, source);
		else toLobby(player);
		return true;
	}

	/** Knocked out sparring: a line for the hall, a burst of the winner's color, back to the door, fresh. */
	private static void hallKnockout(ServerPlayer loser, DamageSource source) {
		ServerLevel level = loser.serverLevel();
		String line;
		int rgb = 0xFFFFFF;
		if (source.getEntity() instanceof ServerPlayer winner && winner != loser) {
			var aura = AuraState.get(loser.getServer()).getOrCreate(winner.getUUID()).aura;
			rgb = ArenaFx.rgbOf(aura);
			DuelLines.Kind kind = wasAbilityHit(loser) ? DuelLines.ofAura(aura)
					: source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile ? DuelLines.Kind.BOW : DuelLines.Kind.MELEE;
			line = DuelLines.pick(kind, winner.getGameProfile().getName(), loser.getGameProfile().getName(), loser.getRandom());
			winner.displayClientMessage(Component.literal("KO! ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
					.append(Component.literal(line).withStyle(ChatFormatting.YELLOW)), true);
		} else {
			line = loser.getGameProfile().getName() + " knocked themself out";
		}
		com.toast.aurapoints.ability.AuraFx.particles(level, com.toast.aurapoints.ability.AuraFx.dust(rgb, 2f), loser.getX(), loser.getY() + 1, loser.getZ(), 50, 0.8, 0.8, 0.8, 0);
		ArenaFx.sound(level, loser.position(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1f, 0.8f);
		for (ServerPlayer p : level.players()) {
			if (inTraining(p)) p.sendSystemMessage(Component.literal("☠ " + line).withStyle(ChatFormatting.GRAY));
		}
		loser.setHealth(loser.getMaxHealth());
		loser.removeAllEffects();
		loser.clearFire();
		loser.getFoodData().setFoodLevel(20);
		com.toast.aurapoints.ability.Stun.onLogout(loser);
		com.toast.aurapoints.ability.Rooted.unlock(loser);
		toHall(loser);
		ArenaFx.title(loser, Component.literal("Knocked out").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
				Component.literal(line).withStyle(ChatFormatting.GRAY));
	}
}
