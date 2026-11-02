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
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * A duel in the Aura Arena: two players, a set number of rounds. Each round starts on the pads with a
 * 3-2-1 countdown; a hit that would kill is a knockout instead (the loser watches as a spectator for a
 * moment, with a funny line from DuelLines). After the last round the one with more rounds wins the
 * prize and the wager pot; a tie adds a sudden-death round. One duel at a time.
 */
public final class Duel {

	private Duel() {
	}

	private enum Phase { INTRO, COUNTDOWN, FIGHT, KNOCKOUT }

	/** The intro's camera flight round the ring, before the first round. */
	static final int INTRO_TICKS = 110;

	private record Challenge(UUID from, UUID to, int rounds, int wager, long expires) {
	}

	/** Pending challenges, by the challenged player. */
	private static final Map<UUID, Challenge> CHALLENGES = new HashMap<>();
	/** The running duel, or null. */
	private static Duel.Match current;

	private static final class Match {
		final UUID a, b;
		final String nameA, nameB;
		int rounds;
		final int wager;
		int round = 0, winsA = 0, winsB = 0;
		Phase phase = Phase.COUNTDOWN;
		int ticks;
		/** The intro's camera while it flies, else null. */
		ArenaCamera camera;

		Match(ServerPlayer a, ServerPlayer b, int rounds, int wager) {
			this.a = a.getUUID();
			this.b = b.getUUID();
			this.nameA = a.getGameProfile().getName();
			this.nameB = b.getGameProfile().getName();
			this.rounds = rounds;
			this.wager = wager;
		}

		boolean has(UUID id) {
			return a.equals(id) || b.equals(id);
		}

		UUID other(UUID id) {
			return a.equals(id) ? b : a;
		}

		String score() {
			return nameA + " " + winsA + " – " + winsB + " " + nameB;
		}
	}

	// ---------------------------------------------------------------
	// Challenges
	// ---------------------------------------------------------------

	public static int challenge(ServerPlayer from, ServerPlayer to, int rounds, int wager) {
		if (!AuraConfig.ARENA_ENABLED) return fail(from, "The Aura Arena is closed right now.");
		if (from == to) return fail(from, "You can't duel yourself (well, you can, but not here).");
		if (com.toast.aurapoints.arena.Match.running() || com.toast.aurapoints.arena.Match.open()) return fail(from, "The ring is taken by a " + com.toast.aurapoints.arena.Match.describe() + ". Try again after.");
		if (isIn(from) || isIn(to)) return fail(from, "One of you is already in a duel.");
		int maxRounds = Math.max(1, AuraConfig.DUEL_MAX_ROUNDS);
		if (rounds < 1 || rounds > maxRounds) return fail(from, "Rounds: 1 to " + maxRounds + ".");
		if (wager < 0 || wager > Math.max(0, AuraConfig.DUEL_MAX_WAGER)) return fail(from, "Wager: 0 to " + Math.max(0, AuraConfig.DUEL_MAX_WAGER) + " Aura.");
		if (wager > 0 && AuraCurrency.get(from) < wager) return fail(from, "You don't have " + wager + " Aura to bet.");
		long expires = from.getServer().getTickCount() + Math.max(5, AuraConfig.DUEL_CHALLENGE_TIMEOUT_SECONDS) * 20L;
		CHALLENGES.put(to.getUUID(), new Challenge(from.getUUID(), to.getUUID(), rounds, wager, expires));

		String terms = rounds + (rounds == 1 ? " round" : " rounds") + (wager > 0 ? ", " + wager + " Aura each on the line" : "");
		to.sendSystemMessage(Component.literal("⚔ " + from.getGameProfile().getName() + " challenges you to an Aura duel! ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("(" + terms + ") ").withStyle(ChatFormatting.GRAY))
				.append(button("[Accept]", ChatFormatting.GREEN, "/aura arena duel accept", "Teleports you both to the arena"))
				.append(Component.literal(" "))
				.append(button("[Decline]", ChatFormatting.RED, "/aura arena duel decline", "No thanks")));
		AuraFx.sound(to.serverLevel(), to, SoundEvents.RAID_HORN.value(), 0.4f, 1.4f);
		from.sendSystemMessage(Component.literal("Challenge sent to " + to.getGameProfile().getName() + " (" + terms + ").").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static MutableComponent button(String text, ChatFormatting color, String command, String hover) {
		return Component.literal(text).withStyle(s -> s.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
	}

	public static int accept(ServerPlayer to) {
		MinecraftServer server = to.getServer();
		Challenge c = CHALLENGES.remove(to.getUUID());
		if (server == null || c == null || c.expires < server.getTickCount()) return fail(to, "Nobody has challenged you (or it ran out).");
		ServerPlayer from = player(server, c.from);
		if (from == null) return fail(to, "Your challenger is gone.");
		if (current != null) return fail(to, "The arena is busy (" + current.score() + "). Try again after.");
		if (com.toast.aurapoints.arena.Match.running()) return fail(to, "The ring is taken by a " + com.toast.aurapoints.arena.Match.describe() + ". Try again after.");
		if (isIn(from) || isIn(to)) return fail(to, "One of you is already in a duel.");
		if (c.wager > 0) {
			if (AuraCurrency.get(from) < c.wager || AuraCurrency.get(to) < c.wager) {
				fail(from, "The duel is off: someone can't cover the " + c.wager + " Aura wager.");
				return fail(to, "The duel is off: someone can't cover the " + c.wager + " Aura wager.");
			}
			AuraCurrency.take(from, c.wager);
			AuraCurrency.take(to, c.wager);
		}
		if (!Arena.in(from) && !Arena.enter(from) || !Arena.in(to) && !Arena.enter(to)) {
			// refund: the arena didn't take them
			if (c.wager > 0) {
				AuraCurrency.add(from, c.wager);
				AuraCurrency.add(to, c.wager);
			}
			return 0;
		}
		current = new Match(from, to, c.rounds, c.wager);
		// everyone hears of it, with a link to watch
		com.toast.aurapoints.AuraPoints.LOGGER.info("[arena] duel {} vs {}", current.nameA, current.nameB);
		server.getPlayerList().broadcastSystemMessage(Component.literal("⚔ " + current.nameA + " vs " + current.nameB + " in the Aura Arena - " + c.rounds
				+ (c.rounds == 1 ? " round" : " rounds") + (c.wager > 0 ? ", " + c.wager * 2 + " Aura pot" : "") + "! ").withStyle(ChatFormatting.GOLD)
				.append(button("[Watch]", ChatFormatting.AQUA, "/aura arena spectate", "Spectate the duel")), false);
		startIntro(server);
		return 1;
	}

	public static int decline(ServerPlayer to) {
		Challenge c = CHALLENGES.remove(to.getUUID());
		if (c == null) return fail(to, "Nobody has challenged you.");
		ServerPlayer from = player(to.getServer(), c.from);
		if (from != null) from.sendSystemMessage(Component.literal(to.getGameProfile().getName() + " declined your duel.").withStyle(ChatFormatting.GRAY));
		to.sendSystemMessage(Component.literal("Duel declined.").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int fail(ServerPlayer player, String why) {
		player.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.RED));
		return 0;
	}

	// ---------------------------------------------------------------
	// Rounds
	// ---------------------------------------------------------------

	// ---------------------------------------------------------------
	// The intro: both watch a camera fly round the ring (sneak to skip), names up in their aura colors
	// ---------------------------------------------------------------

	private static void startIntro(MinecraftServer server) {
		Match m = current;
		ServerLevel arena = Arena.level(server);
		ServerPlayer a = player(server, m.a), b = player(server, m.b);
		if (arena == null || a == null || b == null) return;
		RingLayout layout = RingLayout.roll(arena.getRandom(), false);
		ArenaBuilder.resetRing(arena);
		RingPillars.reset(arena.getGameTime());
		RingPillars.stop();
		m.phase = Phase.INTRO;
		m.ticks = INTRO_TICKS;
		m.camera = com.toast.aurapoints.AuraPoints.ARENA_CAMERA.get().create(arena);
		if (m.camera != null) {
			m.camera.aim(cameraAt(0), ringCentre());
			arena.addFreshEntity(m.camera);
		}
		Component versus = Component.empty().append(name(a)).append(Component.literal("  ⚔  ").withStyle(ChatFormatting.GOLD)).append(name(b));
		Component terms = Component.literal(layout.displayName + " · " + (m.rounds == 1 ? "1 round" : m.rounds + " rounds") + (m.wager > 0 ? " · " + m.wager * 2 + " Aura pot" : "")
				+ " · sneak to skip").withStyle(ChatFormatting.GRAY);
		for (ServerPlayer p : new ServerPlayer[]{a, b}) {
			p.setGameMode(GameType.SPECTATOR);
			p.teleportTo(arena, Arena.PAD_A.x, Arena.PAD_A.y + 6, Arena.PAD_A.z, 0f, 0f);
			ArenaFx.title(p, versus, terms);
			p.sendSystemMessage(Component.literal("Leave any time: ").withStyle(ChatFormatting.GRAY).append(Component.literal("[/aura leave]")
					.withStyle(st -> st.withColor(ChatFormatting.RED).withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/aura leave"))
							.withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal("Forfeit and go back to where you were before the arena"))))));
		}
		ArenaFx.sound(arena, ringCentre(), SoundEvents.RAID_HORN.value(), 2f, 0.9f);
	}

	/** "Name" in their aura's color. */
	private static Component name(ServerPlayer p) {
		AuraType aura = AuraState.get(p.getServer()).getOrCreate(p.getUUID()).aura;
		int rgb = ArenaFx.rgbOf(aura);
		return Component.literal(p.getGameProfile().getName()).withStyle(s -> s.withColor(rgb).withBold(true));
	}

	private static Vec3 ringCentre() {
		return new Vec3(0.5, Arena.RING_FLOOR + 1.5, Arena.RING_Z + 0.5);
	}

	/** The camera's path, {@code t} 0..1: a swoop from high behind the red pad, round and down to look along the line between the pads. */
	private static Vec3 cameraAt(double t) {
		double e = t * t * (3 - 2 * t);
		double angle = Math.PI * 0.5 + e * Math.PI * 1.35;
		double radius = 30 - e * 14, height = Arena.FLOOR + 16 - e * 10;
		return new Vec3(0.5 + Math.cos(angle) * radius, height, Arena.RING_Z + 0.5 - Math.sin(angle) * radius);
	}

	/** Fake players (the dev test's) aren't in the chunk map - putting one on a camera crashes the server. */
	private static boolean realPlayer(ServerPlayer p) {
		return !(p instanceof net.neoforged.neoforge.common.util.FakePlayer);
	}

	private static void endCamera(MinecraftServer server, Match m) {
		for (UUID id : new UUID[]{m.a, m.b}) {
			ServerPlayer p = player(server, id);
			if (p != null && realPlayer(p) && p.getCamera() != p) p.setCamera(p);
		}
		if (m.camera != null) m.camera.discard();
		m.camera = null;
	}

	private static void startRound(MinecraftServer server) {
		Match m = current;
		ServerLevel arena = Arena.level(server);
		ServerPlayer a = player(server, m.a), b = player(server, m.b);
		if (arena == null || a == null || b == null) return;
		m.round++;
		m.phase = Phase.COUNTDOWN;
		m.ticks = Math.max(1, AuraConfig.DUEL_COUNTDOWN_SECONDS) * 20;
		ArenaBuilder.resetRing(arena);
		RingPillars.reset(arena.getGameTime());
		RingPillars.stop();
		ready(a, arena, Arena.PAD_A, 0f);
		ready(b, arena, Arena.PAD_B, 180f);
		String which = m.round > m.rounds ? "Sudden death!" : "Round " + m.round + " of " + m.rounds;
		for (ServerPlayer p : new ServerPlayer[]{a, b}) {
			ArenaFx.title(p, Component.literal(which).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), Component.literal(m.score()).withStyle(ChatFormatting.GRAY));
		}
	}

	/** On the pad, facing the other one, fresh: full health and food, no effects or fire, every cooldown ready. */
	private static void ready(ServerPlayer p, ServerLevel arena, Vec3 pad, float yaw) {
		p.setGameMode(GameType.ADVENTURE);
		p.teleportTo(arena, pad.x, pad.y, pad.z, yaw, 0f);
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

	static void tick(MinecraftServer server) {
		// challenges nobody answered
		long now = server.getTickCount();
		for (Iterator<Challenge> it = CHALLENGES.values().iterator(); it.hasNext(); ) {
			if (it.next().expires < now) it.remove();
		}
		com.toast.aurapoints.arena.Match.tick(server);
		Match m = current;
		if (m == null) return;
		ServerPlayer a = player(server, m.a), b = player(server, m.b);
		if (a == null || b == null) return; // the logout hook forfeits
		m.ticks--;
		ServerLevel arena = Arena.level(server);
		switch (m.phase) {
			case INTRO -> {
				if (m.camera != null) m.camera.aim(cameraAt(1 - m.ticks / (double) INTRO_TICKS), ringCentre());
				// on the camera once their game knows it (a camera packet for an entity it hasn't seen yet is dropped)
				if (m.ticks == INTRO_TICKS - 3 && m.camera != null) {
					for (ServerPlayer p : new ServerPlayer[]{a, b}) if (p.isSpectator() && realPlayer(p)) p.setCamera(m.camera);
				}
				ArenaFx.padColumn(arena, Arena.PAD_A, ArenaFx.rgbOf(AuraState.get(server).getOrCreate(m.a).aura));
				ArenaFx.padColumn(arena, Arena.PAD_B, ArenaFx.rgbOf(AuraState.get(server).getOrCreate(m.b).aura));
				if (m.ticks <= 0) {
					endCamera(server, m);
					startRound(server);
				}
			}
			case COUNTDOWN -> {
				// held on the pads, their aura rising round them
				hold(a, Arena.PAD_A);
				hold(b, Arena.PAD_B);
				ArenaFx.padColumn(arena, Arena.PAD_A, ArenaFx.rgbOf(AuraState.get(server).getOrCreate(m.a).aura));
				ArenaFx.padColumn(arena, Arena.PAD_B, ArenaFx.rgbOf(AuraState.get(server).getOrCreate(m.b).aura));
				if (m.ticks > 0 && m.ticks % 20 == 0) {
					String n = String.valueOf(m.ticks / 20);
					for (ServerPlayer p : new ServerPlayer[]{a, b}) {
						ArenaFx.countdown(p, Component.literal(n).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
						AuraFx.sound(p.serverLevel(), p, SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 1f);
					}
				}
				if (m.ticks <= 0) {
					m.phase = Phase.FIGHT;
					RingPillars.reset(arena.getGameTime());
					for (ServerPlayer p : new ServerPlayer[]{a, b}) {
						ArenaFx.countdown(p, Component.literal("FIGHT!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
						AuraFx.sound(p.serverLevel(), p, SoundEvents.NOTE_BLOCK_BELL.value(), 1f, 1.4f);
					}
					// a ring of light rushing out from the middle, the ring lit white
					ArenaFx.shockwave(arena, 0xFFFFFF);
					ArenaFx.flashRing(net.minecraft.world.level.block.Blocks.WHITE_STAINED_GLASS, 12, arena.getGameTime());
					ArenaFx.sound(arena, ringCentre(), SoundEvents.WARDEN_SONIC_BOOM, 0.7f, 1.2f);
				}
			}
			case FIGHT -> {
				RingPillars.tick(arena, arena.getGameTime());
				for (ServerPlayer p : new ServerPlayer[]{a, b}) {
					p.displayClientMessage(Component.literal("⚔ " + m.score()).withStyle(ChatFormatting.GOLD), true);
				}
			}
			case KNOCKOUT -> {
				if (m.ticks > 0) return;
				boolean decided = m.round >= m.rounds && m.winsA != m.winsB;
				if (decided) {
					boolean aWon = m.winsA > m.winsB;
					finish(server, aWon ? a : b, aWon ? b : a, null);
				} else {
					if (m.round >= m.rounds) {
						m.rounds = m.round + 1; // tie: one more
						toArena(server, Component.literal("⚔ It's a tie - sudden death!").withStyle(ChatFormatting.RED));
					}
					startRound(server);
				}
			}
		}
	}

	private static void hold(ServerPlayer p, Vec3 pad) {
		if (p.position().distanceToSqr(pad) > 0.25) p.teleportTo(p.serverLevel(), pad.x, pad.y, pad.z, p.getYRot(), p.getXRot());
	}

	/** A killing blow on a dueller: the round goes to the other one. False if this player isn't fighting a round. */
	static boolean knockout(ServerPlayer loser, DamageSource source, boolean abilityHit) {
		if (com.toast.aurapoints.arena.Match.isIn(loser)) return com.toast.aurapoints.arena.Match.knockout(loser, source);
		Match m = current;
		MinecraftServer server = loser.getServer();
		if (m == null || server == null || !m.has(loser.getUUID()) || m.phase != Phase.FIGHT) return false;
		ServerPlayer winner = player(server, m.other(loser.getUUID()));
		if (winner == null) return false;
		if (winner.getUUID().equals(m.a)) m.winsA++;
		else m.winsB++;
		com.toast.aurapoints.cosmetic.Weekly.onRoundWon(winner);
		// a round won is mastery XP for the winner's aura (duels count even though the arena pauses other progress)
		com.toast.aurapoints.ability.Mastery.addXp(winner, com.toast.aurapoints.config.AuraConfig.MASTERY_DUEL_ROUND_XP);
		m.phase = Phase.KNOCKOUT;
		m.ticks = Math.max(1, AuraConfig.DUEL_KNOCKOUT_SECONDS) * 20;
		RingPillars.stop();
		// the knockout: a (harmless) bolt where they fell, the winner's color bursting and lighting the ring
		ServerLevel arena = loser.serverLevel();
		AuraType winnerAura = AuraState.get(server).getOrCreate(winner.getUUID()).aura;
		ArenaFx.lightning(arena, loser.position());
		AuraFx.particles(arena, AuraFx.dust(ArenaFx.rgbOf(winnerAura), 2.2f), loser.getX(), loser.getY() + 1, loser.getZ(), 80, 1.2, 1.2, 1.2, 0);
		AuraFx.particles(arena, net.minecraft.core.particles.ParticleTypes.SOUL, loser.getX(), loser.getY() + 1, loser.getZ(), 20, 0.4, 0.6, 0.4, 0.05);
		ArenaFx.flashRing(ArenaFx.glassOf(winnerAura), 50, arena.getGameTime());

		String line = DuelLines.pick(kind(winner, loser, source, abilityHit), winner.getGameProfile().getName(), loser.getGameProfile().getName(), loser.getRandom());
		loser.setHealth(loser.getMaxHealth());
		loser.clearFire();
		loser.removeAllEffects();
		loser.setGameMode(GameType.SPECTATOR);
		ArenaFx.title(loser, Component.literal("You lost").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal(line).withStyle(ChatFormatting.GRAY));
		ArenaFx.title(winner, Component.literal("Round won").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), Component.literal(line).withStyle(ChatFormatting.GRAY));
		AuraFx.sound(loser.serverLevel(), loser, SoundEvents.PLAYER_DEATH, 1f, 1f);
		toArena(server, Component.literal("☠ " + line).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal("  (" + m.score() + ")").withStyle(ChatFormatting.GRAY)));
		return true;
	}

	/** Which pool of lines fits this knockout. */
	private static DuelLines.Kind kind(ServerPlayer winner, ServerPlayer loser, DamageSource source, boolean abilityHit) {
		Entity by = source.getEntity();
		if (by == loser) return DuelLines.Kind.SELF;
		if (by != winner) return DuelLines.Kind.ENVIRONMENT;
		if (source.getDirectEntity() instanceof Projectile && !abilityHit) return DuelLines.Kind.BOW;
		if (!abilityHit) return DuelLines.Kind.MELEE;
		AuraType aura = AuraState.get(winner.getServer()).getOrCreate(winner.getUUID()).aura;
		return DuelLines.ofAura(aura);
	}

	/** The duel is over: prize and pot to the winner, both back to the lobby. {@code forfeit} = why the loser gave up (null if beaten). */
	private static void finish(MinecraftServer server, ServerPlayer winner, ServerPlayer loser, String forfeit) {
		Match m = current;
		current = null;
		if (m == null) return;
		endCamera(server, m);
		RingPillars.stop();
		Spectate.duelOver(server);
		int prize = Math.max(0, AuraConfig.DUEL_PRIZE_AURA) + m.wager * 2;
		String result = forfeit == null ? m.score() : (loser != null ? loser.getGameProfile().getName() : "their opponent") + " " + forfeit;
		if (winner != null) {
			if (prize > 0) AuraCurrency.add(winner, prize);
			ArenaFx.title(winner, Component.literal("VICTORY").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal(result + (prize > 0 ? "  ·  +" + prize + " Aura" : "")).withStyle(ChatFormatting.YELLOW));
			AuraFx.helix(winner, AuraFx.dust(0xFFC83D, 1.3f), 30, 0.9, 2.4);
			AuraFx.sound(winner.serverLevel(), winner, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
			if (forfeit == null) com.toast.aurapoints.cosmetic.Weekly.onDuelWon(winner);
			if (Arena.in(winner) && forfeit == null) {
				// the winner's victory pose (cosmetic), once the title is up
				AuraFx.later(server, 15, () -> {
					if (AuraFx.alive(winner)) com.toast.aurapoints.cosmetic.Cosmetics.victory(winner);
				});
				// a moment in the ring to take it in: fireworks round the rim, a beam of light on the winner
				ServerLevel arena = winner.serverLevel();
				ArenaFx.victory(arena, ArenaFx.rgbOf(AuraState.get(server).getOrCreate(winner.getUUID()).aura));
				winner.setGameMode(GameType.ADVENTURE);
				for (int k = 0; k < 20; k++) {
					AuraFx.later(server, k * 4, () -> {
						if (AuraFx.alive(winner)) AuraFx.particles(arena, net.minecraft.core.particles.ParticleTypes.END_ROD, winner.getX(), winner.getY() + 3, winner.getZ(), 12, 0.15, 3, 0.15, 0.02);
					});
				}
				AuraFx.later(server, 100, () -> {
					if (AuraFx.alive(winner) && Arena.in(winner) && !isIn(winner)) Arena.toLobby(winner);
				});
			} else if (Arena.in(winner)) {
				Arena.toLobby(winner);
			}
		}
		if (loser != null && Arena.in(loser)) {
			ArenaFx.title(loser, Component.literal("DEFEAT").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal(result).withStyle(ChatFormatting.GRAY));
			if (forfeit == null) {
				// watches the winner's moment, then off to the lobby too
				AuraFx.later(server, 100, () -> {
					if (AuraFx.alive(loser) && Arena.in(loser) && !isIn(loser)) Arena.toLobby(loser);
				});
			} else {
				Arena.toLobby(loser);
			}
		}
		String winnerName = winner != null ? winner.getGameProfile().getName() : "?";
		String loserName = winnerName.equals(m.nameA) ? m.nameB : m.nameA;
		if (winner != null) {
			TrainingHall.recordDuel(server, winner.getUUID(), winnerName, m.other(winner.getUUID()), loserName);
			// for quests (Aura Mastery III asks for a won duel); a forfeit counts too
			winner.awardStat(com.toast.aurapoints.ability.AuraStats.DUEL_WINS);
		}
		server.getPlayerList().broadcastSystemMessage(Component.literal("⚔ " + winnerName + " beat " + loserName + " in the Aura Arena ("
				+ (forfeit == null ? m.winsA + "–" + m.winsB : "forfeit") + ")" + (prize > 0 ? " and takes " + prize + " Aura!" : "!")).withStyle(ChatFormatting.GOLD), false);
	}

	/** Logged out, left the arena or /aura arena leave mid-duel: the other one wins. */
	static void forfeit(ServerPlayer quitter, String why) {
		CHALLENGES.remove(quitter.getUUID());
		com.toast.aurapoints.arena.Match.forfeit(quitter, why);
		Match m = current;
		MinecraftServer server = quitter.getServer();
		if (m == null || server == null || !m.has(quitter.getUUID())) return;
		ServerPlayer winner = player(server, m.other(quitter.getUUID()));
		if (quitter.isSpectator()) quitter.setGameMode(GameType.ADVENTURE);
		finish(server, winner, quitter, why);
	}

	/** An owner stopped it (/aura arena duel stop): wagers back, nobody wins. */
	public static int stop(MinecraftServer server) {
		Match m = current;
		if (m == null) return 0;
		current = null;
		endCamera(server, m);
		RingPillars.stop();
		Spectate.duelOver(server);
		for (UUID id : new UUID[]{m.a, m.b}) {
			ServerPlayer p = player(server, id);
			if (p == null) continue;
			if (m.wager > 0) AuraCurrency.add(p, m.wager);
			if (Arena.in(p)) Arena.toLobby(p);
			p.sendSystemMessage(Component.literal("The duel was called off" + (m.wager > 0 ? " - your wager is back." : ".")).withStyle(ChatFormatting.GRAY));
		}
		return 1;
	}

	/** Dev tests only (/auradev duel): fake players taking part, looked up like online ones. */
	static final Map<UUID, ServerPlayer> TEST_PLAYERS = new HashMap<>();

	private static ServerPlayer player(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null || TEST_PLAYERS.isEmpty() ? p : TEST_PLAYERS.get(id);
	}

	/** Dev tests: "round 2/3, FIGHT, 1-0" (or "none"). */
	static String debugState() {
		Match m = current;
		return m == null ? "none" : "round " + m.round + "/" + m.rounds + ", " + m.phase + ", " + m.winsA + "-" + m.winsB;
	}

	private static void toArena(MinecraftServer server, Component message) {
		com.toast.aurapoints.AuraPoints.LOGGER.info("[arena] {}", message.getString());
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (Arena.in(p)) p.sendSystemMessage(message);
	}

	// ---------------------------------------------------------------
	// Asked by Arena and the ability code
	// ---------------------------------------------------------------

	/** A duel or a match is on in the ring. */
	public static boolean running() {
		return current != null || com.toast.aurapoints.arena.Match.running();
	}

	/** "Red vs Blue · round 2/3" (or "" with no duel). */
	public static String describe() {
		Match m = current;
		if (m == null) return com.toast.aurapoints.arena.Match.running() ? com.toast.aurapoints.arena.Match.describe() : "";
		return m.nameA + " vs " + m.nameB + (m.round > 0 ? " · round " + Math.min(m.round, m.rounds) + "/" + m.rounds : "");
	}

	/** In a duel or a running match. */
	public static boolean isIn(ServerPlayer player) {
		return current != null && current.has(player.getUUID()) || com.toast.aurapoints.arena.Match.isIn(player);
	}

	/** On the pads waiting for "FIGHT!", or knocked out: no abilities. */
	public static boolean holdsStill(ServerPlayer player) {
		return current != null && current.has(player.getUUID()) && current.phase != Phase.FIGHT || com.toast.aurapoints.arena.Match.holdsStill(player);
	}

	/** May {@code attacker} (null = no one: fall, fire, …) hurt this player in the arena? Only the opponent, only mid-round. */
	static boolean canBeHurt(ServerPlayer victim, Entity attacker) {
		if (com.toast.aurapoints.arena.Match.isIn(victim)) return com.toast.aurapoints.arena.Match.canBeHurt(victim, attacker);
		Match m = current;
		if (m == null || !m.has(victim.getUUID()) || m.phase != Phase.FIGHT) return false;
		return attacker == null || !(attacker instanceof ServerPlayer) || m.has(attacker.getUUID());
	}

	/** The ring's live board (Holograms): both duellers with their health, the round and score, the layout; null = no duel. */
	static Component liveBoard(MinecraftServer server) {
		Match m = current;
		if (m == null) return null;
		ServerPlayer a = player(server, m.a), b = player(server, m.b);
		return Component.literal("⚔ " + m.nameA + "  " + hearts(a) + "   vs   " + hearts(b) + "  " + m.nameB).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal("\n" + (m.phase == Phase.INTRO ? "about to start" : "round " + Math.max(1, m.round) + "/" + m.rounds)
						+ " · " + m.winsA + "–" + m.winsB + " · " + RingLayout.current.displayName).withStyle(ChatFormatting.GRAY));
	}

	private static String hearts(ServerPlayer p) {
		return p == null ? "-" : "❤" + (int) Math.ceil(p.getHealth());
	}

	/** The dueller's pad (for falling off the map mid-duel), or null. */
	static Vec3 padOf(ServerPlayer player) {
		if (com.toast.aurapoints.arena.Match.isIn(player)) return com.toast.aurapoints.arena.Match.spawnOf(player);
		Match m = current;
		if (m == null || !m.has(player.getUUID())) return null;
		return m.a.equals(player.getUUID()) ? Arena.PAD_A : Arena.PAD_B;
	}

	static void clearAll() {
		CHALLENGES.clear();
		TEST_PLAYERS.clear();
		current = null;
		com.toast.aurapoints.arena.Match.clearAll();
	}
}
