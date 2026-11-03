package com.toast.aurapoints.arena;

import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Arena matches for more than two: the ring, with modes.
 * <ul>
 * <li>TEAMS - two teams (2v2 up to 4v4, players dealt out in turn), best of MATCH_ROUNDS: a round ends when one team is
 * all knocked out. Teammates can't hurt each other.</li>
 * <li>FFA - everyone for themselves (3-8), best of MATCH_ROUNDS, last one standing takes the round.</li>
 * <li>KOTH - King of the Hill (2-8): a hill in the middle of the ring; standing on it alone scores a point a second,
 * first to MATCH_KOTH_TARGET (or the most when MATCH_KOTH_SECONDS run out) wins. Knocked out = back in
 * MATCH_KOTH_RESPAWN_SECONDS.</li>
 * <li>WAVES - Wave Survival (1-8, together): endless waves of mobs, see Waves. Knocked out = back in the next break;
 * late joiners step in during a break; over when everyone is down at once.</li>
 * </ul>
 * A host opens one (/aura arena match create &lt;mode&gt;), others join from the broadcast, the host starts it. Shares the
 * ring with Duel: only one of them at a time. Duel's own checks (isIn, canBeHurt, knockout ...) ask this class too,
 * so the arena's damage rules work for matches unchanged. Nothing is saved.
 */
public final class Match {

	private Match() {
	}

	public enum Mode {
		TEAMS("Team Battle", 4), FFA("Free-for-All", 3), KOTH("King of the Hill", 2), WAVES("Wave Survival", 1);

		final String displayName;
		final int minPlayers;

		Mode(String displayName, int minPlayers) {
			this.displayName = displayName;
			this.minPlayers = minPlayers;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Mode byId(String id) {
			for (Mode m : values()) if (m.id().equalsIgnoreCase(id)) return m;
			return null;
		}
	}

	/** BREAK: Wave Survival between two waves. */
	private enum Phase { LOBBY, COUNTDOWN, FIGHT, KNOCKOUT, BREAK }

	private static final class Game {
		final Mode mode;
		final UUID host;
		/** Everyone in it, in join order, with their name. */
		final Map<UUID, String> players = new LinkedHashMap<>();
		/** Player -> side (team 0/1, or their own number in FFA and KOTH). */
		final Map<UUID, Integer> side = new HashMap<>();
		final Map<UUID, Vec3> spawn = new HashMap<>();
		final Set<UUID> out = new HashSet<>();
		/** KOTH: knocked-out player -> tick they come back. */
		final Map<UUID, Long> respawnAt = new HashMap<>();
		int[] wins = new int[0];
		/** KOTH: hill points per side, and ticks held toward the next point. */
		int[] score = new int[0];
		int holdTicks, holder = -1;
		int rounds, round;
		Phase phase = Phase.LOBBY;
		int ticks;
		long lobbyEnds;
		/** WAVES: the mob side of the run. */
		Waves.Run run;
		/** WAVES: the break after a boss wave, when players may switch aura (WaveAuras). */
		boolean auraSwitch;

		Game(Mode mode, ServerPlayer host, long now) {
			this.mode = mode;
			this.host = host.getUUID();
			this.lobbyEnds = now + Math.max(20, AuraConfig.MATCH_LOBBY_SECONDS) * 20L;
			players.put(host.getUUID(), host.getGameProfile().getName());
		}

		int sides() {
			return mode == Mode.TEAMS ? 2 : mode == Mode.WAVES ? 1 : players.size();
		}

		boolean alive(UUID id) {
			return players.containsKey(id) && !out.contains(id) && !respawnAt.containsKey(id);
		}

		String sideName(int s) {
			if (mode == Mode.WAVES) return "The party";
			if (mode == Mode.TEAMS) return s == 0 ? "Red Team" : "Blue Team";
			for (var e : side.entrySet()) if (e.getValue() == s) return players.get(e.getKey());
			return "?";
		}

		List<UUID> members(int s) {
			List<UUID> out = new ArrayList<>();
			for (UUID id : players.keySet()) if (side.getOrDefault(id, -1) == s) out.add(id);
			return out;
		}
	}

	private static Game current;

	// ---------------------------------------------------------------
	// Lobby
	// ---------------------------------------------------------------

	public static int create(ServerPlayer host, Mode mode) {
		if (!AuraConfig.ARENA_ENABLED || !AuraConfig.MATCH_ENABLED) return fail(host, "Arena matches are switched off right now.");
		if (mode == Mode.WAVES && !AuraConfig.WAVES_ENABLED) return fail(host, "Wave Survival is switched off right now.");
		if (current != null) return fail(host, "A " + current.mode.displayName + " is already " + (current.phase == Phase.LOBBY ? "open - " : "on - ") + "one at a time.");
		if (Duel.running()) return fail(host, "The ring is busy with a duel. Try again after.");
		if (Duel.isIn(host)) return fail(host, "You're in a duel.");
		current = new Game(mode, host, host.getServer().getTickCount());
		host.getServer().getPlayerList().broadcastSystemMessage(Component.literal("⚔ " + host.getGameProfile().getName() + " opened a "
				+ mode.displayName + " in the Aura Arena (" + mode.minPlayers + "-" + maxPlayers() + " players)! ").withStyle(ChatFormatting.GOLD)
				.append(button("[Join]", ChatFormatting.GREEN, "/aura arena match join", "Join the " + mode.displayName)), false);
		host.sendSystemMessage(Component.literal("Start it once enough have joined: ").withStyle(ChatFormatting.GRAY)
				.append(button("[Start]", ChatFormatting.GREEN, "/aura arena match start", "Start the match"))
				.append(Component.literal(" "))
				.append(button("[Cancel]", ChatFormatting.RED, "/aura arena match leave", "Close the match")));
		return 1;
	}

	private static int maxPlayers() {
		return Math.max(2, Math.min(8, AuraConfig.MATCH_MAX_PLAYERS));
	}

	public static int join(ServerPlayer player) {
		Game g = current;
		if (g != null && g.mode == Mode.WAVES && g.phase != Phase.LOBBY) return joinRun(player, g);
		if (g == null || g.phase != Phase.LOBBY) return fail(player, "There's no open match to join.");
		if (g.players.containsKey(player.getUUID())) return fail(player, "You're already in it.");
		if (Duel.isIn(player)) return fail(player, "You're in a duel.");
		if (g.players.size() >= maxPlayers()) return fail(player, "It's full (" + maxPlayers() + ").");
		g.players.put(player.getUUID(), player.getGameProfile().getName());
		toPlayers(player.getServer(), g, Component.literal("+ " + player.getGameProfile().getName() + " joined the " + g.mode.displayName
				+ " (" + g.players.size() + "/" + maxPlayers() + ")").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	/** Leaves an open match (the host closes it); mid-match it's a forfeit. */
	public static int leave(ServerPlayer player) {
		Game g = current;
		if (g == null || !g.players.containsKey(player.getUUID())) return fail(player, "You're not in a match.");
		if (g.phase == Phase.LOBBY) {
			if (g.host.equals(player.getUUID())) {
				toPlayers(player.getServer(), g, Component.literal("The " + g.mode.displayName + " was closed.").withStyle(ChatFormatting.GRAY));
				current = null;
			} else {
				g.players.remove(player.getUUID());
				toPlayers(player.getServer(), g, Component.literal("- " + player.getGameProfile().getName() + " left the " + g.mode.displayName).withStyle(ChatFormatting.GRAY));
				player.sendSystemMessage(Component.literal("You left the " + g.mode.displayName + ".").withStyle(ChatFormatting.GRAY));
			}
			return 1;
		}
		forfeit(player, "left");
		return 1;
	}

	/** Wave Survival under way: in during a break (until it's full), else wait for one. */
	private static int joinRun(ServerPlayer player, Game g) {
		MinecraftServer server = player.getServer();
		if (g.players.containsKey(player.getUUID())) return fail(player, "You're already in it.");
		if (Duel.isIn(player)) return fail(player, "You're in a duel.");
		if (g.players.size() >= maxPlayers()) return fail(player, "It's full (" + maxPlayers() + ").");
		if (g.phase != Phase.BREAK) {
			return fail(player, "Wave " + g.run.wave + " is being fought - join in the next break (when the ring is clear).");
		}
		if (!Arena.in(player) && !Arena.enter(player)) return 0;
		ServerLevel arena = Arena.level(server);
		if (arena == null) return 0;
		g.players.put(player.getUUID(), player.getGameProfile().getName());
		g.side.put(player.getUUID(), 0);
		g.run.joinedAt.put(player.getUUID(), g.run.wave + 1);
		placeSpawns(g);
		ready(player, arena, g.spawn.get(player.getUUID()));
		ArenaFx.title(player, Component.literal("Wave Survival").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
				Component.literal("You're in from wave " + (g.run.wave + 1)).withStyle(ChatFormatting.YELLOW));
		if (g.auraSwitch) offerAuras(player, Math.max(1, g.ticks / 20));
		toPlayers(server, g, Component.literal("+ " + player.getGameProfile().getName() + " joined the Wave Survival (" + g.players.size() + "/" + maxPlayers() + ")").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	public static int start(ServerPlayer player) {
		Game g = current;
		MinecraftServer server = player.getServer();
		if (g == null || g.phase != Phase.LOBBY) return fail(player, "There's no open match.");
		if (!g.host.equals(player.getUUID())) return fail(player, "Only " + g.players.get(g.host) + " (the host) can start it.");
		g.players.keySet().removeIf(id -> player(server, id) == null);
		if (g.players.size() < g.mode.minPlayers) return fail(player, "A " + g.mode.displayName + " needs at least " + g.mode.minPlayers + " players (" + g.players.size() + " now).");
		if (Duel.running()) return fail(player, "The ring is busy with a duel.");
		for (UUID id : g.players.keySet()) {
			ServerPlayer p = player(server, id);
			if (!Arena.in(p) && !Arena.enter(p)) return fail(player, g.players.get(id) + " can't come to the arena right now.");
		}
		begin(server, g);
		return 1;
	}

	/** Sides, spawn points, and the first round. */
	private static void begin(MinecraftServer server, Game g) {
		int i = 0;
		for (UUID id : g.players.keySet()) g.side.put(id, g.mode == Mode.TEAMS ? i++ % 2 : g.mode == Mode.WAVES ? 0 : i++);
		if (g.mode == Mode.WAVES) {
			g.run = Waves.newRun(server);
			for (UUID id : g.players.keySet()) g.run.joinedAt.put(id, 1);
		}
		g.wins = new int[g.sides()];
		g.score = new int[g.sides()];
		g.rounds = g.mode == Mode.KOTH || g.mode == Mode.WAVES ? 1 : Math.max(1, AuraConfig.MATCH_ROUNDS);
		RingLayout layout = RingLayout.roll(server.overworld().getRandom(), g.mode == Mode.WAVES);
		placeSpawns(g);
		Component title = Component.literal(g.mode.displayName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		MutableComponent lineup = Component.empty();
		if (g.mode == Mode.TEAMS) {
			lineup.append(Component.literal(String.join(", ", names(g, 0))).withStyle(ChatFormatting.RED))
					.append(Component.literal("  vs  ").withStyle(ChatFormatting.GOLD))
					.append(Component.literal(String.join(", ", names(g, 1))).withStyle(ChatFormatting.BLUE));
		} else {
			lineup.append(Component.literal(String.join(" · ", g.players.values())).withStyle(ChatFormatting.YELLOW));
		}
		server.getPlayerList().broadcastSystemMessage(Component.literal("⚔ ").withStyle(ChatFormatting.GOLD).append(title)
				.append(Component.literal(" in the Aura Arena (" + layout.displayName + "): ").withStyle(ChatFormatting.GOLD)).append(lineup).append(Component.literal(" "))
				.append(button("[Watch]", ChatFormatting.AQUA, "/aura arena spectate", "Spectate the match")), false);
		Component leaveTip = Component.literal("Leave any time: ").withStyle(ChatFormatting.GRAY).append(button("[/aura leave]", ChatFormatting.RED, "/aura leave", "Back to where you were before the arena"));
		toPlayers(server, g, leaveTip);
		com.toast.aurapoints.AuraPoints.LOGGER.info("[arena] {} with {}", g.mode, g.players.values());
		startRound(server, g);
	}

	private static List<String> names(Game g, int side) {
		List<String> out = new ArrayList<>();
		for (UUID id : g.members(side)) out.add(g.players.get(id));
		return out;
	}

	/** On a circle round the ring's middle: teams on opposite sides, everyone else spread evenly, facing the middle. */
	private static void placeSpawns(Game g) {
		double r = g.mode == Mode.WAVES ? 4 : Math.max(4, Math.min(Arena.RING_R - 3, AuraConfig.MATCH_SPAWN_RADIUS));
		int n = g.players.size(), k = 0;
		Map<Integer, Integer> perSide = new HashMap<>();
		for (UUID id : g.players.keySet()) {
			int s = g.side.get(id);
			double a;
			if (g.mode == Mode.TEAMS) {
				int idx = perSide.merge(s, 1, Integer::sum) - 1, size = g.members(s).size();
				a = (s == 0 ? -Math.PI / 2 : Math.PI / 2) + (idx - (size - 1) / 2.0) * 0.35;
			} else {
				// Wave Survival: back to back in the middle, between the gates
				a = (g.mode == Mode.WAVES ? -Math.PI / 4 : -Math.PI / 2) + Math.PI * 2 * k / n;
			}
			k++;
			double x = Math.cos(a) * r, dz = Math.sin(a) * r;
			g.spawn.put(id, new Vec3(0.5 + x, RingLayout.current.standY(x, dz), Arena.RING_Z + 0.5 + dz));
		}
	}

	private static float faceMiddle(Vec3 from) {
		return (float) Math.toDegrees(Math.atan2(-(0.5 - from.x), (Arena.RING_Z + 0.5) - from.z));
	}

	// ---------------------------------------------------------------
	// Rounds
	// ---------------------------------------------------------------

	private static void startRound(MinecraftServer server, Game g) {
		ServerLevel arena = Arena.level(server);
		if (arena == null) return;
		g.round++;
		g.phase = Phase.COUNTDOWN;
		g.ticks = Math.max(1, AuraConfig.DUEL_COUNTDOWN_SECONDS) * 20;
		g.out.clear();
		g.respawnAt.clear();
		ArenaBuilder.resetRing(arena);
		RingPillars.reset(arena.getGameTime());
		RingPillars.stop();
		String which = g.mode == Mode.WAVES ? "Wave Survival" : g.mode == Mode.KOTH ? "First to " + target() + " on the hill"
				: g.round > g.rounds ? "Sudden death!" : "Round " + g.round + " of " + g.rounds;
		for (UUID id : g.players.keySet()) {
			ServerPlayer p = player(server, id);
			if (p == null) continue;
			ready(p, arena, g.spawn.get(id));
			ArenaFx.title(p, Component.literal(which).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), Component.literal(sideLine(g, id)).withStyle(sideColor(g, id)));
		}
	}

	private static String sideLine(Game g, UUID id) {
		if (g.mode == Mode.WAVES) return "Hold the ring as long as you can";
		if (g.mode != Mode.TEAMS) return "Everyone for themselves";
		List<String> mates = names(g, g.side.get(id));
		mates.remove(g.players.get(id));
		return g.sideName(g.side.get(id)) + (mates.isEmpty() ? "" : " - with " + String.join(", ", mates));
	}

	private static ChatFormatting sideColor(Game g, UUID id) {
		if (g.mode != Mode.TEAMS) return ChatFormatting.YELLOW;
		return g.side.get(id) == 0 ? ChatFormatting.RED : ChatFormatting.BLUE;
	}

	/** Fresh on the spot, facing the middle: full health and food, no effects or fire, every cooldown ready. */
	private static void ready(ServerPlayer p, ServerLevel arena, Vec3 at) {
		p.setGameMode(GameType.ADVENTURE);
		p.teleportTo(arena, at.x, at.y, at.z, faceMiddle(at), 0f);
		p.setDeltaMovement(Vec3.ZERO);
		p.fallDistance = 0;
		p.removeAllEffects();
		p.clearFire();
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5f);
		com.toast.aurapoints.ability.Stun.onLogout(p);
		com.toast.aurapoints.ability.Rooted.unlock(p);
		Arena.resetCooldowns(p);
	}

	private static int target() {
		return Math.max(1, AuraConfig.MATCH_KOTH_TARGET);
	}

	static void tick(MinecraftServer server) {
		Game g = current;
		if (g == null) return;
		if (g.phase == Phase.LOBBY) {
			if (server.getTickCount() > g.lobbyEnds) {
				toPlayers(server, g, Component.literal("The " + g.mode.displayName + " didn't start in time and was closed.").withStyle(ChatFormatting.GRAY));
				current = null;
			}
			return;
		}
		ServerLevel arena = Arena.level(server);
		if (arena == null) return;
		g.ticks--;
		switch (g.phase) {
			case COUNTDOWN -> {
				for (UUID id : g.players.keySet()) {
					ServerPlayer p = player(server, id);
					if (p == null) continue;
					Vec3 at = g.spawn.get(id);
					if (p.position().distanceToSqr(at) > 0.25) p.teleportTo(arena, at.x, at.y, at.z, p.getYRot(), p.getXRot());
					ArenaFx.padColumn(arena, at, ArenaFx.rgbOf(AuraState.get(server).getOrCreate(id).aura));
					if (g.ticks > 0 && g.ticks % 20 == 0) {
						ArenaFx.countdown(p, Component.literal(String.valueOf(g.ticks / 20)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
						AuraFx.sound(arena, p, SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 1f);
					}
				}
				if (g.ticks <= 0) {
					g.phase = Phase.FIGHT;
					g.ticks = g.mode == Mode.KOTH ? Math.max(10, AuraConfig.MATCH_KOTH_SECONDS) * 20 : Integer.MAX_VALUE;
					RingPillars.reset(arena.getGameTime());
					for (UUID id : g.players.keySet()) {
						ServerPlayer p = player(server, id);
						if (p == null) continue;
						if (g.mode == Mode.WAVES) continue;
						ArenaFx.countdown(p, Component.literal("FIGHT!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
						AuraFx.sound(arena, p, SoundEvents.NOTE_BLOCK_BELL.value(), 1f, 1.4f);
					}
					ArenaFx.shockwave(arena, 0xFFFFFF);
					ArenaFx.flashRing(net.minecraft.world.level.block.Blocks.WHITE_STAINED_GLASS, 12, arena.getGameTime());
					if (g.mode == Mode.WAVES) Waves.startWave(server, arena, g.run, online(server, g, false));
				}
			}
			case FIGHT -> {
				RingPillars.tick(arena, arena.getGameTime());
				if (g.mode == Mode.WAVES) tickWaves(server, arena, g);
				else if (g.mode == Mode.KOTH) tickHill(server, arena, g);
				else if (server.getTickCount() % 10 == 0) {
					for (UUID id : g.players.keySet()) {
						ServerPlayer p = player(server, id);
						if (p != null) p.displayClientMessage(Component.literal("⚔ " + standing(g)).withStyle(ChatFormatting.GOLD), true);
					}
				}
			}
			case BREAK -> {
				if (g.ticks % 20 == 0 && g.ticks > 0) {
					Component bar = Component.literal("☠ Wave " + (g.run.wave + 1) + " in " + g.ticks / 20 + "s").withStyle(ChatFormatting.YELLOW);
					for (ServerPlayer p : online(server, g, false)) p.displayClientMessage(bar, true);
				}
				if (g.ticks > 0) return;
				g.phase = Phase.FIGHT;
				g.auraSwitch = false;
				g.ticks = Integer.MAX_VALUE;
				Waves.startWave(server, arena, g.run, online(server, g, false));
			}
			case KNOCKOUT -> {
				if (g.ticks > 0) return;
				int best = -1, top = -1;
				boolean tie = false;
				for (int s = 0; s < g.wins.length; s++) {
					if (g.wins[s] > top) {
						top = g.wins[s];
						best = s;
						tie = false;
					} else if (g.wins[s] == top) tie = true;
				}
				// first to a majority, or the last round played
				if (!tie && (top > g.rounds / 2 || g.round >= g.rounds)) {
					finish(server, g, best);
				} else {
					if (g.round >= g.rounds) {
						g.rounds = g.round + 1;
						toPlayers(server, g, Component.literal("⚔ It's a tie - sudden death!").withStyle(ChatFormatting.RED));
					}
					startRound(server, g);
				}
			}
			default -> {
			}
		}
	}

	/** "3 left · Red 1 – 0 Blue" style line for the action bar. */
	private static String standing(Game g) {
		int alive = 0;
		for (UUID id : g.players.keySet()) if (g.alive(id)) alive++;
		if (g.mode == Mode.WAVES) return g.run == null ? alive + " in" : "wave " + g.run.wave + " · " + g.run.left() + " left · " + alive + " standing";
		if (g.mode == Mode.TEAMS) return "Red " + g.wins[0] + " – " + g.wins[1] + " Blue · " + alive + " standing";
		return "Round " + Math.min(g.round, g.rounds) + "/" + g.rounds + " · " + alive + " standing";
	}

	// ---------------------------------------------------------------
	// Wave Survival (the mobs are Waves')
	// ---------------------------------------------------------------

	/** The players in this match who are online (standing only, or all). */
	private static List<ServerPlayer> online(MinecraftServer server, Game g, boolean standingOnly) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : g.players.keySet()) {
			ServerPlayer p = player(server, id);
			if (p != null && (!standingOnly || g.alive(id))) out.add(p);
		}
		return out;
	}

	private static void tickWaves(MinecraftServer server, ServerLevel arena, Game g) {
		if (!Waves.tickWave(server, arena, g.run, online(server, g, true), online(server, g, false))) return;
		// cleared: pay, then a break - the knocked out come back, everyone fresh, late joiners welcome
		Waves.payWave(g.run, online(server, g, false));
		ArenaBuilder.setGates(arena, true);
		g.phase = Phase.BREAK;
		int breakSecs = Waves.breakSeconds(g.run);
		g.ticks = breakSecs * 20;
		g.auraSwitch = AuraConfig.WAVES_AURA_SWITCH && g.run.bossWave();
		for (UUID id : g.players.keySet()) {
			ServerPlayer p = player(server, id);
			if (p == null) continue;
			if (g.out.contains(id)) {
				ready(p, arena, g.spawn.get(id));
				ArenaFx.title(p, Component.literal("Back in!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), Component.literal("Wave " + g.run.wave + " cleared").withStyle(ChatFormatting.GRAY));
			} else {
				p.setHealth(p.getMaxHealth());
				p.getFoodData().setFoodLevel(20);
				p.clearFire();
				Arena.resetCooldowns(p);
			}
		}
		g.out.clear();
		if (g.auraSwitch) for (ServerPlayer p : online(server, g, false)) offerAuras(p, breakSecs);
		ArenaFx.flashRing(net.minecraft.world.level.block.Blocks.LIME_STAINED_GLASS, 30, arena.getGameTime());
		if (g.players.size() < maxPlayers()) {
			Component line = Component.literal("⚔ Wave Survival: wave " + g.run.wave + " cleared - join in the next " + breakSecs + "s! ")
					.withStyle(ChatFormatting.GOLD).append(button("[Join]", ChatFormatting.GREEN, "/aura arena match join", "Join the Wave Survival"));
			for (ServerPlayer p : arena.players()) if (!g.players.containsKey(p.getUUID())) p.sendSystemMessage(line);
		}
	}

	/** Everyone down at once (or gone): the run is over. Score = the last wave cleared. */
	private static void finishWaves(MinecraftServer server, Game g) {
		current = null;
		RingPillars.stop();
		Spectate.duelOver(server);
		ServerLevel arena = Arena.level(server);
		int cleared = g.run == null ? 0 : g.phase == Phase.BREAK ? g.run.wave : g.run.wave - 1;
		Waves.cleanup(arena, g.run);
		boolean record = Waves.record(server, cleared, g.players);
		for (UUID id : g.players.keySet()) {
			ServerPlayer p = player(server, id);
			if (p == null) continue;
			if (p.isSpectator() && Arena.in(p)) p.setGameMode(GameType.ADVENTURE);
			WaveAuras.restore(p);
			int earned = g.run == null ? 0 : g.run.earned.getOrDefault(id, 0);
			ArenaFx.title(p, Component.literal(cleared > 0 ? "WAVE " + cleared + " CLEARED" : "OVERRUN").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal((earned > 0 ? "+" + earned + " Aura this run" : "No wave cleared") + (record ? " · new record!" : "")).withStyle(ChatFormatting.YELLOW));
			com.toast.aurapoints.cosmetic.Cosmetics.checkEarned(p);
			AuraFx.later(server, 100, () -> {
				if (AuraFx.alive(p) && Arena.in(p) && !isIn(p) && !Duel.isIn(p)) Arena.toLobby(p);
			});
		}
		if (arena != null && cleared > 0) ArenaFx.victory(arena, 0xFFC83D);
		if (arena != null) Holograms.updateBoards(arena);
		server.getPlayerList().broadcastSystemMessage(Component.literal("⚔ " + String.join(", ", g.players.values()) + (g.players.size() == 1 ? " was" : " were")
				+ " overrun in Wave Survival after " + cleared + (cleared == 1 ? " wave" : " waves") + (record ? " - a new record!" : ".")).withStyle(ChatFormatting.GOLD), false);
	}

	/** The aura menu in chat, if this player has more than one aura to pick from. */
	private static void offerAuras(ServerPlayer p, int seconds) {
		var data = AuraState.get(p.getServer()).getOrCreate(p.getUUID());
		if (data.aura != null && WaveAuras.unlocked(p, data).size() > 1) p.sendSystemMessage(WaveAuras.menu(p, seconds));
	}

	/** /aura arena match aura <id>: switch aura for the rest of the run, only in the break after a boss wave. */
	public static int switchAura(ServerPlayer player, String id) {
		Game g = current;
		if (g == null || g.mode != Mode.WAVES || !g.players.containsKey(player.getUUID())) return fail(player, "You're not in a Wave Survival run.");
		if (g.phase != Phase.BREAK || !g.auraSwitch) return fail(player, "You can switch aura in the break after a boss wave (every " + Math.max(1, AuraConfig.WAVES_BOSS_EVERY) + " waves).");
		var aura = com.toast.aurapoints.ability.AuraType.byId(id);
		var data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (aura != null && aura == data.aura) return fail(player, "That's your aura already.");
		if (!WaveAuras.switchTo(player, aura)) return fail(player, "You haven't had that aura yet.");
		ArenaFx.title(player, Component.literal(aura.displayName).withStyle(aura.color, ChatFormatting.BOLD), Component.literal("Your aura for this run").withStyle(ChatFormatting.GRAY));
		AuraFx.burst(player.serverLevel(), player.getX(), player.getY() + 1.0, player.getZ(), AuraFx.dust(aura.rgb, 1.6f), 30, 0.6, 0.0);
		toPlayers(player.getServer(), g, Component.literal("✦ " + player.getGameProfile().getName() + " switched to " + aura.displayName).withStyle(aura.color));
		return 1;
	}

	/** Fighting the waves right now (a running Wave Survival, standing or not). */
	public static boolean inWaves(ServerPlayer player) {
		Game g = current;
		return g != null && g.mode == Mode.WAVES && g.phase != Phase.LOBBY && g.players.containsKey(player.getUUID());
	}

	/** Open lobby, or a Wave Survival run that takes late joiners. */
	public static boolean joinable() {
		Game g = current;
		return g != null && (g.phase == Phase.LOBBY || g.mode == Mode.WAVES && g.players.size() < maxPlayers());
	}

	// ---------------------------------------------------------------
	// King of the Hill
	// ---------------------------------------------------------------

	private static void tickHill(MinecraftServer server, ServerLevel arena, Game g) {
		long now = server.getTickCount();
		// the knocked out come back
		for (var it = g.respawnAt.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (e.getValue() > now) continue;
			it.remove();
			ServerPlayer p = player(server, e.getKey());
			if (p != null) ready(p, arena, g.spawn.get(e.getKey()));
		}
		double r = Math.max(1, AuraConfig.MATCH_KOTH_RADIUS);
		Vec3 hill = new Vec3(0.5, RingLayout.current.standY(0, 0), Arena.RING_Z + 0.5);
		Set<Integer> on = new HashSet<>();
		for (UUID id : g.players.keySet()) {
			ServerPlayer p = player(server, id);
			if (p == null || !g.alive(id)) continue;
			double dx = p.getX() - hill.x, dz = p.getZ() - hill.z;
			if (dx * dx + dz * dz <= r * r && Math.abs(p.getY() - hill.y) < 3) on.add(g.side.get(id));
		}
		int holder = on.size() == 1 ? on.iterator().next() : -1;
		if (holder != g.holder) {
			g.holder = holder;
			g.holdTicks = 0;
		}
		int rgb = 0xFFC83D;
		if (holder >= 0) {
			UUID h = g.members(holder).get(0);
			rgb = ArenaFx.rgbOf(AuraState.get(server).getOrCreate(h).aura);
			if (++g.holdTicks >= 20) {
				g.holdTicks = 0;
				if (++g.score[holder] >= target()) {
					finish(server, g, holder);
					return;
				}
			}
		}
		if (now % 4 == 0) AuraFx.ring(arena, hill.x, hill.y + 0.1, hill.z, r, AuraFx.dust(on.size() > 1 ? 0xFF4040 : rgb, 1.4f), 32);
		if (now % 10 == 0) {
			String status = holder >= 0 ? "♛ " + g.sideName(holder) + " holds the hill" : on.size() > 1 ? "♛ The hill is contested!" : "♛ The hill is free";
			Component bar = Component.literal(status + "  ·  " + hillScores(g) + "  ·  " + g.ticks / 20 + "s").withStyle(ChatFormatting.GOLD);
			for (UUID id : g.players.keySet()) {
				ServerPlayer p = player(server, id);
				if (p != null) p.displayClientMessage(bar, true);
			}
		}
		if (g.ticks <= 0) {
			int best = -1, top = -1;
			boolean tie = false;
			for (int s = 0; s < g.score.length; s++) {
				if (g.score[s] > top) {
					top = g.score[s];
					best = s;
					tie = false;
				} else if (g.score[s] == top) tie = true;
			}
			finish(server, g, tie ? -1 : best);
		}
	}

	private static String hillScores(Game g) {
		StringBuilder sb = new StringBuilder();
		for (int s = 0; s < g.score.length; s++) {
			if (!sb.isEmpty()) sb.append("  ");
			sb.append(g.sideName(s)).append(' ').append(g.score[s]).append('/').append(target());
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------
	// Knockouts
	// ---------------------------------------------------------------

	/** A killing blow on someone in a running match: they're out (KOTH: for a few seconds). False if not in a fight. */
	static boolean knockout(ServerPlayer loser, DamageSource source) {
		Game g = current;
		MinecraftServer server = loser.getServer();
		if (g == null || server == null || g.phase != Phase.FIGHT || !g.alive(loser.getUUID())) return false;
		Entity by = source.getEntity();
		ServerPlayer killer = by instanceof ServerPlayer k && k != loser && g.players.containsKey(k.getUUID()) ? k : null;
		ServerLevel arena = loser.serverLevel();
		AuraType killerAura = killer == null ? null : AuraState.get(server).getOrCreate(killer.getUUID()).aura;
		ArenaFx.lightning(arena, loser.position());
		AuraFx.particles(arena, AuraFx.dust(ArenaFx.rgbOf(killerAura), 2.0f), loser.getX(), loser.getY() + 1, loser.getZ(), 60, 1.0, 1.0, 1.0, 0);
		loser.setHealth(loser.getMaxHealth());
		loser.clearFire();
		loser.removeAllEffects();
		loser.setGameMode(GameType.SPECTATOR);
		AuraFx.sound(arena, loser, SoundEvents.PLAYER_DEATH, 1f, 1f);
		if (killer != null) com.toast.aurapoints.ability.Mastery.addXp(killer, AuraConfig.MASTERY_DUEL_ROUND_XP);
		String line = killer != null ? killer.getGameProfile().getName() + " knocked out " + loser.getGameProfile().getName()
				: by != null && g.mode == Mode.WAVES ? loser.getGameProfile().getName() + " fell to " + by.getDisplayName().getString()
				: loser.getGameProfile().getName() + " is out";
		toPlayers(server, g, Component.literal("☠ " + line).withStyle(ChatFormatting.YELLOW));
		if (g.mode == Mode.WAVES) {
			g.out.add(loser.getUUID());
			boolean anyone = false;
			for (UUID id : g.players.keySet()) if (g.alive(id) && player(server, id) != null) anyone = true;
			if (!anyone) {
				finishWaves(server, g);
				return true;
			}
			ArenaFx.title(loser, Component.literal("Knocked out").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal("Back in when this wave is cleared").withStyle(ChatFormatting.GRAY));
			return true;
		}
		if (g.mode == Mode.KOTH) {
			int secs = Math.max(1, AuraConfig.MATCH_KOTH_RESPAWN_SECONDS);
			g.respawnAt.put(loser.getUUID(), server.getTickCount() + secs * 20L);
			ArenaFx.title(loser, Component.literal("Knocked out").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal("Back in " + secs + "s").withStyle(ChatFormatting.GRAY));
			return true;
		}
		g.out.add(loser.getUUID());
		ArenaFx.title(loser, Component.literal("Knocked out").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal("Watch until the round ends").withStyle(ChatFormatting.GRAY));
		checkRound(server, g);
		return true;
	}

	/** One side (or none) left standing: the round is decided. */
	private static void checkRound(MinecraftServer server, Game g) {
		if (g.phase != Phase.FIGHT || g.mode == Mode.KOTH || g.mode == Mode.WAVES) return;
		Set<Integer> standing = new HashSet<>();
		for (UUID id : g.players.keySet()) if (g.alive(id)) standing.add(g.side.get(id));
		if (standing.size() > 1) return;
		RingPillars.stop();
		g.phase = Phase.KNOCKOUT;
		g.ticks = Math.max(1, AuraConfig.DUEL_KNOCKOUT_SECONDS) * 20;
		if (standing.isEmpty()) {
			toPlayers(server, g, Component.literal("⚔ Nobody left standing - the round is a draw.").withStyle(ChatFormatting.GRAY));
			return;
		}
		int s = standing.iterator().next();
		g.wins[s]++;
		for (UUID id : g.members(s)) {
			ServerPlayer p = player(server, id);
			if (p == null) continue;
			com.toast.aurapoints.cosmetic.Weekly.onRoundWon(p);
			ArenaFx.title(p, Component.literal("Round won").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), Component.literal(standing(g)).withStyle(ChatFormatting.GRAY));
		}
		ServerLevel arena = Arena.level(server);
		if (arena != null) ArenaFx.flashRing(ArenaFx.glassOf(AuraState.get(server).getOrCreate(g.members(s).get(0)).aura), 50, arena.getGameTime());
		toPlayers(server, g, Component.literal("⚔ " + g.sideName(s) + " takes round " + g.round + "  (" + standing(g) + ")").withStyle(ChatFormatting.GOLD));
	}

	// ---------------------------------------------------------------
	// The end
	// ---------------------------------------------------------------

	/** Over: MATCH_PRIZE_AURA to each winner (side {@code winner}, -1 = nobody), everyone back to the lobby. */
	private static void finish(MinecraftServer server, Game g, int winner) {
		current = null;
		RingPillars.stop();
		Spectate.duelOver(server);
		int prize = Math.max(0, AuraConfig.MATCH_PRIZE_AURA);
		List<UUID> winners = winner >= 0 ? g.members(winner) : List.of();
		for (UUID id : g.players.keySet()) {
			ServerPlayer p = player(server, id);
			if (p == null) continue;
			boolean won = winners.contains(id);
			if (p.isSpectator() && Arena.in(p)) p.setGameMode(GameType.ADVENTURE);
			if (won) {
				if (prize > 0) AuraCurrency.add(p, prize);
				p.awardStat(com.toast.aurapoints.ability.AuraStats.DUEL_WINS);
				com.toast.aurapoints.cosmetic.Weekly.onDuelWon(p);
				ArenaFx.title(p, Component.literal("VICTORY").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
						Component.literal(g.mode.displayName + (prize > 0 ? "  ·  +" + prize + " Aura" : "")).withStyle(ChatFormatting.YELLOW));
				AuraFx.helix(p, AuraFx.dust(0xFFC83D, 1.3f), 30, 0.9, 2.4);
				AuraFx.sound(p.serverLevel(), p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
				AuraFx.later(server, 15, () -> {
					if (AuraFx.alive(p)) com.toast.aurapoints.cosmetic.Cosmetics.victory(p);
				});
			} else {
				ArenaFx.title(p, Component.literal(winner < 0 ? "DRAW" : "DEFEAT").withStyle(winner < 0 ? ChatFormatting.GRAY : ChatFormatting.RED, ChatFormatting.BOLD),
						Component.literal(winner < 0 ? "Nobody wins this one" : g.sideName(winner) + " wins").withStyle(ChatFormatting.GRAY));
			}
			AuraFx.later(server, 100, () -> {
				if (AuraFx.alive(p) && Arena.in(p) && !isIn(p) && !Duel.isIn(p)) Arena.toLobby(p);
			});
		}
		ServerLevel arena = Arena.level(server);
		if (arena != null && winner >= 0) ArenaFx.victory(arena, ArenaFx.rgbOf(AuraState.get(server).getOrCreate(winners.get(0)).aura));
		String what = g.mode == Mode.KOTH ? " (" + hillScores(g) + ")" : g.mode == Mode.TEAMS ? " (" + g.wins[0] + "–" + g.wins[1] + ")" : "";
		server.getPlayerList().broadcastSystemMessage(Component.literal("⚔ " + (winner < 0 ? "The " + g.mode.displayName + " ended in a draw" + what + "."
				: g.sideName(winner) + " won the " + g.mode.displayName + what + (prize > 0 ? " and takes " + prize + " Aura" + (winners.size() > 1 ? " each" : "") : "") + "!"))
				.withStyle(ChatFormatting.GOLD), false);
	}

	/** Logged out, left the arena or /aura arena match leave mid-match: out for good; the rest play on (or it ends). */
	static void forfeit(ServerPlayer quitter, String why) {
		Game g = current;
		MinecraftServer server = quitter.getServer();
		if (g == null || server == null || !g.players.containsKey(quitter.getUUID())) return;
		if (g.phase == Phase.LOBBY) {
			if (g.host.equals(quitter.getUUID())) {
				toPlayers(server, g, Component.literal("The " + g.mode.displayName + " was closed (the host " + why + ").").withStyle(ChatFormatting.GRAY));
				current = null;
			} else {
				g.players.remove(quitter.getUUID());
			}
			return;
		}
		int side = g.side.getOrDefault(quitter.getUUID(), -1);
		g.out.add(quitter.getUUID());
		g.respawnAt.remove(quitter.getUUID());
		g.players.remove(quitter.getUUID());
		if (quitter.isSpectator() && Arena.in(quitter)) quitter.setGameMode(GameType.ADVENTURE);
		toPlayers(server, g, Component.literal("☠ " + quitter.getGameProfile().getName() + " " + why + " and is out of the match.").withStyle(ChatFormatting.GRAY));
		if (g.mode == Mode.WAVES) {
			WaveAuras.restore(quitter);
			if (g.run != null && g.run.bar != null) g.run.bar.removePlayer(quitter);
			boolean anyone = false;
			for (UUID id : g.players.keySet()) if (g.alive(id) && player(server, id) != null) anyone = true;
			if (!anyone && (g.phase == Phase.FIGHT || g.players.isEmpty())) finishWaves(server, g);
			return;
		}
		// whole sides that are gone for good
		Set<Integer> left = new HashSet<>();
		for (UUID id : g.players.keySet()) left.add(g.side.get(id));
		if (left.size() <= 1) {
			finish(server, g, left.isEmpty() ? -1 : left.iterator().next());
			return;
		}
		if (side >= 0) checkRound(server, g);
	}

	/** An owner stopped it: nobody wins, everyone back to the lobby. */
	public static int stop(MinecraftServer server) {
		Game g = current;
		if (g == null) return 0;
		current = null;
		RingPillars.stop();
		if (g.run != null) Waves.cleanup(Arena.level(server), g.run);
		Spectate.duelOver(server);
		for (UUID id : g.players.keySet()) {
			ServerPlayer p = player(server, id);
			if (p == null) continue;
			if (p.isSpectator() && Arena.in(p)) p.setGameMode(GameType.ADVENTURE);
			if (g.phase != Phase.LOBBY && Arena.in(p)) Arena.toLobby(p);
			WaveAuras.restore(p);
			p.sendSystemMessage(Component.literal("The " + g.mode.displayName + " was called off.").withStyle(ChatFormatting.GRAY));
		}
		return 1;
	}

	// ---------------------------------------------------------------
	// Asked by Duel (and so by Arena and the abilities)
	// ---------------------------------------------------------------

	/** A match is being fought (an open lobby doesn't count). */
	public static boolean running() {
		return current != null && current.phase != Phase.LOBBY;
	}

	public static boolean open() {
		return current != null && current.phase == Phase.LOBBY;
	}

	/** "Free-for-All · round 1/3 · 4 standing" (or ""). */
	public static String describe() {
		Game g = current;
		if (g == null) return "";
		if (g.phase == Phase.LOBBY) return g.mode.displayName + " (open, " + g.players.size() + " joined)";
		return g.mode.displayName + " · " + (g.mode == Mode.KOTH ? hillScores(g) : standing(g));
	}

	/** In a match that has started. */
	/** In this match in any phase, the lobby too. */
	public static boolean signedUp(ServerPlayer player) {
		Game g = current;
		return g != null && g.players.containsKey(player.getUUID());
	}

	public static boolean isIn(ServerPlayer player) {
		Game g = current;
		return g != null && g.phase != Phase.LOBBY && g.players.containsKey(player.getUUID());
	}

	/** Same side of a started match: Team Battle teammates, or two players in the same Wave Survival run. */
	public static boolean teammates(ServerPlayer a, ServerPlayer b) {
		Game g = current;
		if (g == null || a == b || !isIn(a) || !isIn(b)) return false;
		if (g.mode == Mode.WAVES) return true;
		return g.mode == Mode.TEAMS && java.util.Objects.equals(g.side.get(a.getUUID()), g.side.get(b.getUUID()));
	}

	/** Waiting for "FIGHT!", knocked out, or between rounds: no abilities. */
	public static boolean holdsStill(ServerPlayer player) {
		Game g = current;
		return isIn(player) && (g.phase != Phase.FIGHT || !g.alive(player.getUUID()));
	}

	/** May {@code attacker} (null = no one) hurt this player? Mid-fight only, never a teammate or someone who's out. */
	static boolean canBeHurt(ServerPlayer victim, Entity attacker) {
		Game g = current;
		if (g == null || g.phase != Phase.FIGHT || !g.alive(victim.getUUID())) return false;
		if (!(attacker instanceof ServerPlayer a)) return true;
		if (!g.alive(a.getUUID())) return false;
		return g.mode != Mode.TEAMS || !g.side.get(a.getUUID()).equals(g.side.get(victim.getUUID()));
	}

	/** Where this player starts (for falling off the map mid-match), or null. */
	static Vec3 spawnOf(ServerPlayer player) {
		Game g = current;
		return isIn(player) ? g.spawn.get(player.getUUID()) : null;
	}

	/** Dev tests: "FFA FIGHT round 1/3 wins=[0, 1, 0] out=1" (or "none"). */
	static String debugState() {
		Game g = current;
		if (g == null) return "none";
		return g.mode + " " + g.phase + " round " + g.round + "/" + g.rounds + " wins=" + java.util.Arrays.toString(g.wins)
				+ " score=" + java.util.Arrays.toString(g.score) + " out=" + g.out.size() + " respawning=" + g.respawnAt.size()
				+ (g.run == null ? "" : " wave=" + g.run.wave + " left=" + g.run.left() + " boss=" + (g.run.boss != null) + " players=" + g.players.size());
	}

	/** Dev tests: the running Wave Survival's mob side (or null). */
	static Waves.Run devRun() {
		return current == null ? null : current.run;
	}

	private static ServerPlayer player(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null || Duel.TEST_PLAYERS.isEmpty() ? p : Duel.TEST_PLAYERS.get(id);
	}

	private static void toPlayers(MinecraftServer server, Game g, Component message) {
		com.toast.aurapoints.AuraPoints.LOGGER.info("[arena] {}", message.getString());
		for (UUID id : g.players.keySet()) {
			ServerPlayer p = player(server, id);
			if (p != null) p.sendSystemMessage(message);
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!g.players.containsKey(p.getUUID()) && Spectate.watching(p)) p.sendSystemMessage(message);
		}
	}

	private static int fail(ServerPlayer player, String why) {
		player.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.RED));
		return 0;
	}

	private static MutableComponent button(String text, ChatFormatting color, String command, String hover) {
		return Component.literal(text).withStyle(s -> s.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
	}

	static void clearAll() {
		current = null;
		Waves.clearAll();
	}
}
