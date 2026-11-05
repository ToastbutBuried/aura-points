package com.toast.aurapoints.arena;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest, system property aurapoints.devtest=true): /auradev duel - two fake
 * players fight a scripted 3-round duel in the arena and every step is logged ("[arenatest]"). Fake players
 * are invulnerable and ignore teleports, so the hits go straight to the arena's damage checks
 * (Arena.blocksDamage / catchLethal - what the damage hooks call).
 * Round 1: a hit during the countdown must do nothing, then Red knocks out Blue (Fire line);
 * round 2: Blue knocks out Red with a punch; round 3: Red wins - 2-1 to Red, prize paid.
 */
public final class ArenaDevTest {

	private ArenaDevTest() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("duel").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel arena = Arena.level(server);
			if (arena == null) return 0;
			net.minecraft.server.level.ServerPlayer red = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000a001"), "Red"));
			net.minecraft.server.level.ServerPlayer blue = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b002"), "Blue"));
			AuraState state = AuraState.get(server);
			state.getOrCreate(red.getUUID()).aura = AuraType.FIRE;
			state.getOrCreate(blue.getUUID()).aura = AuraType.FROST;
			for (net.minecraft.server.level.ServerPlayer p : new net.minecraft.server.level.ServerPlayer[]{red, blue}) {
				Duel.TEST_PLAYERS.put(p.getUUID(), p);
				p.moveTo(Arena.LOBBY.x, Arena.LOBBY.y, Arena.LOBBY.z);
				p.setHealth(p.getMaxHealth());
			}
			log("challenge: " + Duel.challenge(red, blue, 3, 0));
			log("accept: " + Duel.accept(blue) + " -> " + Duel.debugState() + " red at " + red.position() + " blue at " + blue.position());
			// both pick a map at once, so the vote ends on the next tick
			log("vote: red " + Duel.vote(red, "random") + ", blue " + Duel.vote(blue, "random") + " (must be 1, 1)");
			int countdown = Math.max(1, com.toast.aurapoints.config.AuraConfig.DUEL_COUNTDOWN_SECONDS) * 20;
			int intro = Duel.INTRO_TICKS;
			int knockout = Math.max(1, com.toast.aurapoints.config.AuraConfig.DUEL_KNOCKOUT_SECONDS) * 20;
			// round 1: a hit before FIGHT is blocked, then Red's (ability) hit knocks Blue out
			AuraFx.later(server, intro + 10, () -> {
				log("hit during countdown blocked: " + Arena.blocksDamage(blue, arena.damageSources().playerAttack(red)) + " (must be true), abilities held: " + Duel.holdsStill(red));
			});
			int t = intro + countdown + 5;
			AuraFx.later(server, t, () -> {
				log("fight: " + Duel.debugState() + ", abilities held: " + Duel.holdsStill(red));
				var hit = arena.damageSources().playerAttack(red);
				log("hit mid-round blocked: " + Arena.blocksDamage(blue, hit) + " (must be false); a 4 dmg hit lethal: " + Arena.catchLethal(blue, hit, 4f) + " (must be false)");
				Arena.noteHit(blue, true); // as if an ability dealt it: the Fire pool
				log("lethal: " + Arena.catchLethal(blue, hit, 500f) + " -> " + Duel.debugState() + ", blue health " + blue.getHealth() + " mode " + blue.gameMode.getGameModeForPlayer());
				log("hit during the knockout blocked: " + Arena.blocksDamage(red, arena.damageSources().playerAttack(blue)) + " (must be true)");
			});
			// round 2: Blue punches Red out
			t += knockout + countdown + 5;
			AuraFx.later(server, t, () -> {
				log("round 2: " + Duel.debugState() + ", blue mode " + blue.gameMode.getGameModeForPlayer() + " at " + blue.position());
				log("lethal punch on red: " + Arena.catchLethal(red, arena.damageSources().playerAttack(blue), 500f) + " -> " + Duel.debugState());
			});
			// round 3: Red again - 2-1, the duel is over
			t += knockout + countdown + 5;
			int aura = com.toast.aurapoints.AuraCurrency.get(red);
			AuraFx.later(server, t, () -> {
				log("round 3: " + Duel.debugState());
				log("lethal on blue: " + Arena.catchLethal(blue, arena.damageSources().playerAttack(red), 500f) + " -> " + Duel.debugState());
			});
			AuraFx.later(server, t + knockout + 5, () -> {
				log("end: " + Duel.debugState() + ", red aura " + aura + " -> " + com.toast.aurapoints.AuraCurrency.get(red)
						+ ", red at " + red.position() + " mode " + red.gameMode.getGameModeForPlayer() + ", blue mode " + blue.gameMode.getGameModeForPlayer());
				Duel.TEST_PLAYERS.clear();
			});
			return 1;
		})).then(Commands.literal("match").then(Commands.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
			// /auradev match ffa|teams|koth: fake players play a scripted match through the arena's damage hooks
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel arena = Arena.level(server);
			Match.Mode mode = Match.Mode.byId(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "mode"));
			if (arena == null || mode == null) return 0;
			int n = mode == Match.Mode.TEAMS ? 4 : mode == Match.Mode.FFA ? 3 : 2;
			AuraType[] auras = {AuraType.FIRE, AuraType.FROST, AuraType.WIND, AuraType.SHADOW};
			net.minecraft.server.level.ServerPlayer[] ps = new net.minecraft.server.level.ServerPlayer[n];
			AuraState state = AuraState.get(server);
			for (int i = 0; i < n; i++) {
				ps[i] = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000d0" + (10 + i)), "P" + (i + 1)));
				state.getOrCreate(ps[i].getUUID()).aura = auras[i];
				Duel.TEST_PLAYERS.put(ps[i].getUUID(), ps[i]);
				ps[i].moveTo(Arena.LOBBY.x + i, Arena.LOBBY.y, Arena.LOBBY.z);
			}
			log("create: " + Match.create(ps[0], mode) + " too few start: " + Match.start(ps[0]) + " (must be 0)");
			for (int i = 1; i < n; i++) log("join P" + (i + 1) + ": " + Match.join(ps[i]));
			log("non-host start: " + Match.start(ps[1]) + " (must be 0); duel challenge while open: " + Duel.challenge(ps[0], ps[1], 1, 0) + " (must be 0)");
			int aura0 = com.toast.aurapoints.AuraCurrency.get(ps[0]);
			log("start: " + Match.start(ps[0]) + " -> " + Match.debugState());
			int countdown = Math.max(1, com.toast.aurapoints.config.AuraConfig.DUEL_COUNTDOWN_SECONDS) * 20;
			int knockout = Math.max(1, com.toast.aurapoints.config.AuraConfig.DUEL_KNOCKOUT_SECONDS) * 20;
			AuraFx.later(server, 10, () -> log("countdown: hit blocked " + Arena.blocksDamage(ps[1], arena.damageSources().playerAttack(ps[0])) + " (must be true), held " + Duel.holdsStill(ps[0])));
			java.util.function.BiFunction<Integer, Integer, Boolean> ko = (a, b) -> Arena.catchLethal(ps[b], arena.damageSources().playerAttack(ps[a]), 500f);
			int t = countdown + 5;
			if (mode == Match.Mode.KOTH) {
				int was = com.toast.aurapoints.config.AuraConfig.MATCH_KOTH_TARGET;
				com.toast.aurapoints.config.AuraConfig.MATCH_KOTH_TARGET = 3;
				AuraFx.later(server, t, () -> {
					log("fight: " + Match.debugState() + ", held " + Duel.holdsStill(ps[0]));
					log("P1 knocks out P2: " + ko.apply(0, 1) + " -> " + Match.debugState() + ", P2 held " + Duel.holdsStill(ps[1]) + " mode " + ps[1].gameMode.getGameModeForPlayer());
					ps[0].moveTo(0.5, Arena.RING_FLOOR + 1, Arena.RING_Z + 0.5);
					ps[1].moveTo(10.5, Arena.RING_FLOOR + 1, Arena.RING_Z + 0.5);
				});
				AuraFx.later(server, t + 30, () -> log("1.5 s on the hill: " + Match.debugState()));
				AuraFx.later(server, t + 100, () -> {
					log("end: " + Match.debugState() + ", P1 aura " + aura0 + " -> " + com.toast.aurapoints.AuraCurrency.get(ps[0]) + ", running " + Match.running());
					com.toast.aurapoints.config.AuraConfig.MATCH_KOTH_TARGET = was;
					Duel.TEST_PLAYERS.clear();
				});
				return 1;
			}
			for (int round = 1; round <= 2; round++) {
				final int r = round;
				AuraFx.later(server, t, () -> {
					log("round " + r + ": " + Match.debugState());
					if (mode == Match.Mode.TEAMS) {
						// P1 + P3 red, P2 + P4 blue
						log("teammate hit blocked: " + Arena.blocksDamage(ps[2], arena.damageSources().playerAttack(ps[0])) + " (must be true)");
						log("P1 KO P2: " + ko.apply(0, 1) + "; knocked-out P2 hits P1 blocked: " + Arena.blocksDamage(ps[0], arena.damageSources().playerAttack(ps[1])) + " (must be true)");
						log("P3 KO P4: " + ko.apply(2, 3) + " -> " + Match.debugState());
					} else {
						log("P1 KO P2: " + ko.apply(0, 1) + " -> " + Match.debugState());
						log("P1 KO P3: " + ko.apply(0, 2) + " -> " + Match.debugState());
					}
				});
				t += knockout + countdown + 5;
			}
			AuraFx.later(server, t, () -> {
				log("end: " + Match.debugState() + ", P1 aura " + aura0 + " -> " + com.toast.aurapoints.AuraCurrency.get(ps[0]) + ", running " + Match.running()
						+ ", P2 mode " + ps[1].gameMode.getGameModeForPlayer());
				Duel.TEST_PLAYERS.clear();
			});
			return 1;
		}))).then(Commands.literal("waves").executes(ctx -> {
			// /auradev waves: the wave plan, then a scripted run - wave 1 cleared and paid, a late join in the break (and
			// one refused mid-wave), the wave-5 boss, a knockout back in the break, everyone down = the end, nothing left over
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel arena = Arena.level(server);
			if (arena == null) return 0;
			for (String line : Waves.describePlan(1, 30)) log("solo " + line);
			for (String line : Waves.describePlan(4, 10)) log("4p " + line);
			AuraState state = AuraState.get(server);
			net.minecraft.server.level.ServerPlayer[] ps = new net.minecraft.server.level.ServerPlayer[3];
			AuraType[] auras = {AuraType.FIRE, AuraType.FROST, AuraType.WIND};
			for (int i = 0; i < 3; i++) {
				ps[i] = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000e0" + (10 + i)), "W" + (i + 1)));
				state.getOrCreate(ps[i].getUUID()).aura = auras[i];
				state.getOrCreate(ps[i].getUUID()).arenaRealAura = null;
				state.getOrCreate(ps[i].getUUID()).rolled.clear();
				Duel.TEST_PLAYERS.put(ps[i].getUUID(), ps[i]);
				ps[i].moveTo(Arena.LOBBY.x + i, Arena.LOBBY.y, Arena.LOBBY.z);
			}
			int aura0 = com.toast.aurapoints.AuraCurrency.get(ps[0]);
			state.getOrCreate(ps[0].getUUID()).rolled.add(AuraType.EARTH); // W1 had Earth before
			log("create: " + Match.create(ps[0], Match.Mode.WAVES) + ", solo start: " + Match.start(ps[0]) + " -> " + Match.debugState());
			int countdown = Math.max(1, com.toast.aurapoints.config.AuraConfig.DUEL_COUNTDOWN_SECONDS) * 20;
			int brk = Math.max(1, com.toast.aurapoints.config.AuraConfig.WAVES_BREAK_SECONDS) * 20;
			int t = countdown + 30;
			AuraFx.later(server, t, () -> {
				Waves.Run run = Match.devRun();
				log("wave 1 under way: " + Match.debugState() + ", spawned " + run.alive.size() + ", in ring " + Waves.devCountInRing(arena)
						+ ", cooldown 100 -> " + Waves.cooldown(ps[0], 100) + " (must be 50), held " + Duel.holdsStill(ps[0]));
				log("late join mid-wave: " + Match.join(ps[1]) + " (must be 0)");
				Waves.devClear(run);
			});
			t += 5;
			AuraFx.later(server, t, () -> {
				log("cleared: " + Match.debugState() + ", W1 aura " + aura0 + " -> " + com.toast.aurapoints.AuraCurrency.get(ps[0]) + " (must be +" + com.toast.aurapoints.config.AuraConfig.WAVES_AURA_PER_WAVE + ")");
				log("late join in the break: " + Match.join(ps[1]) + " (must be 1) -> " + Match.debugState());
				log("aura switch after a normal wave: " + Match.switchAura(ps[0], "earth") + " (must be 0)");
				Match.devRun().wave = 4; // next one is the boss wave
			});
			t += brk + 30;
			AuraFx.later(server, t, () -> {
				Waves.Run run = Match.devRun();
				log("wave 5: " + Match.debugState() + ", boss " + (run.boss == null ? "none" : run.boss.getName().getString() + " hp " + run.boss.getMaxHealth())
						+ ", bar " + (run.bar != null));
				// teammates: W1 can't hurt W2; wave mobs never take each other as their target
				var hit = arena.damageSources().playerAttack(ps[0]);
				log("teammates: W1 hurts W2 blocked=" + Arena.blocksDamage(ps[1], hit) + " (must be true), teammates=" + Match.teammates(ps[0], ps[1]) + " (must be true)");
				java.util.List<net.minecraft.world.entity.Mob> mobs = new java.util.ArrayList<>(run.alive);
				if (run.boss != null) mobs.add(run.boss);
				if (mobs.size() >= 2) {
					net.minecraft.world.entity.Mob a = mobs.get(0), b2 = mobs.get(1);
					a.setTarget(b2);
					log("infighting: " + a.getType().toShortString() + " told to target " + b2.getType().toShortString() + " -> target is a wave mob=" + (a.getTarget() != null && Waves.isWaveMob(a.getTarget())) + " (must be false)");
				} else log("infighting: fewer than 2 mobs, skipped");
				// a mob knocks W1 out; W2 still stands
				net.minecraft.world.entity.Mob by = run.boss != null ? run.boss : run.alive.isEmpty() ? null : run.alive.get(0);
				var src = by == null ? arena.damageSources().generic() : arena.damageSources().mobAttack(by);
				log("W1 knocked out: " + Arena.catchLethal(ps[0], src, 500f) + " -> " + Match.debugState() + ", W1 mode " + ps[0].gameMode.getGameModeForPlayer());
				Waves.devClear(run);
			});
			t += 5;
			AuraFx.later(server, t, () -> {
				log("wave 5 cleared: " + Match.debugState() + ", W1 back: " + ps[0].gameMode.getGameModeForPlayer() + " (must be ADVENTURE)"
						+ ", W2 aura earned " + Match.devRun().earned.get(ps[1].getUUID()) + " (must be 2 + boss bonus)");
				var d1 = state.getOrCreate(ps[0].getUUID());
				log("W1 unlocked " + WaveAuras.unlocked(ps[0], d1) + " (must hold FIRE + EARTH, maybe more from old xp)");
				log("W1 -> earth: " + Match.switchAura(ps[0], "earth") + " (must be 1), aura " + d1.aura + " real " + d1.arenaRealAura + " (must be EARTH / FIRE)");
				log("W1 -> void (never held): " + Match.switchAura(ps[0], "void") + " (must be 0 unless old xp), W2 -> earth: " + Match.switchAura(ps[1], "earth") + " (must be 0 unless old xp)");
				log("W1 back -> fire: " + Match.switchAura(ps[0], "fire") + " (must be 1), real " + d1.arenaRealAura + " (must be null); -> earth again: " + Match.switchAura(ps[0], "earth"));
			});
			t += Math.max(1, com.toast.aurapoints.config.AuraConfig.WAVES_BOSS_BREAK_SECONDS) * 20 + 30;
			AuraFx.later(server, t, () -> {
				var src = arena.damageSources().generic();
				log("aura switch mid-wave 6: " + Match.switchAura(ps[0], "fire") + " (must be 0)");
				log("wave 6: " + Match.debugState() + ", in ring " + Waves.devCountInRing(arena) + ", untracked " + Waves.devUntracked(arena, Match.devRun()));
				log("W1 down: " + Arena.catchLethal(ps[0], src, 500f) + ", W2 down: " + Arena.catchLethal(ps[1], src, 500f) + " -> " + Match.debugState());
			});
			t += 5;
			AuraFx.later(server, t, () -> {
				var rec = state.waveRecords.get(ps[0].getUUID());
				log("end: running " + Match.running() + ", wave mobs left " + Waves.devCountInRing(arena) + " (must be 0), W1 record "
						+ (rec == null ? "none" : rec.best + " with " + rec.party) + " (must be 5.0), W1 aura " + aura0 + " -> " + com.toast.aurapoints.AuraCurrency.get(ps[0]));
				var d1 = state.getOrCreate(ps[0].getUUID());
				log("W1 after the run: aura " + d1.aura + " real " + d1.arenaRealAura + " (must be FIRE / null)");
				Duel.TEST_PLAYERS.clear();
			});
			return 1;
		})).then(Commands.literal("durability").executes(ctx -> {
			// nothing wears out in the arena (mixin/ItemStackMixin), outside it does
			MinecraftServer server = ctx.getSource().getServer();
			var inArena = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
			var outside = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE);
			inArena.hurtAndBreak(5, Arena.level(server), (net.minecraft.server.level.ServerPlayer) null, i -> {
			});
			outside.hurtAndBreak(5, server.overworld(), (net.minecraft.server.level.ServerPlayer) null, i -> {
			});
			log("durability: arena damage " + inArena.getDamageValue() + " (must be 0), overworld damage " + outside.getDamageValue() + " (must be 5)");
			return 1;
		})).then(Commands.literal("spar").executes(ctx -> {
			// PvP zones: in the hall's Spar Pit players can hit each other (and a knockout sends you to the door), elsewhere in the hall and in the lobby they can't
			ServerLevel arena = Arena.level(ctx.getSource().getServer());
			net.minecraft.server.level.ServerPlayer red = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000a001"), "Red"));
			net.minecraft.server.level.ServerPlayer blue = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b002"), "Blue"));
			var hit = arena.damageSources().playerAttack(red);
			red.moveTo(Arena.LOBBY.x, Arena.LOBBY.y, Arena.LOBBY.z);
			blue.moveTo(Arena.LOBBY.x + 1, Arena.LOBBY.y, Arena.LOBBY.z);
			log("lobby: hit blocked " + Arena.blocksDamage(blue, hit) + " (must be true)");
			red.moveTo(Arena.HALL_ARRIVAL.x, Arena.HALL_ARRIVAL.y, Arena.HALL_ARRIVAL.z + 10);
			blue.moveTo(Arena.HALL_ARRIVAL.x + 1, Arena.HALL_ARRIVAL.y, Arena.HALL_ARRIVAL.z + 10);
			log("hall outside the pit: hit blocked " + Arena.blocksDamage(blue, hit) + " (must be true)");
			red.moveTo(0.5, Arena.FLOOR, Arena.SPAR_Z - 1.5);
			blue.moveTo(1.5, Arena.FLOOR, Arena.SPAR_Z + 1.5);
			log("spar pit: hit blocked " + Arena.blocksDamage(blue, hit) + " (must be false), lethal caught " + Arena.catchLethal(blue, hit, 500f) + " (must be true)");
			red.moveTo(0.5, Arena.FLOOR, Arena.SPAR_Z - 1.5);
			blue.moveTo(Arena.HALL_ARRIVAL.x, Arena.HALL_ARRIVAL.y, Arena.HALL_ARRIVAL.z + 10);
			log("pit attacker, victim outside: hit blocked " + Arena.blocksDamage(blue, hit) + " (must be true)");
			blue.moveTo(1.5, Arena.FLOOR, Arena.SPAR_Z + 1.5);
			red.moveTo(Arena.LOBBY.x, Arena.LOBBY.y, Arena.LOBBY.z);
			log("hall victim, attacker in lobby: hit blocked " + Arena.blocksDamage(blue, hit) + " (must be true)");
			return 1;
		})).then(Commands.literal("duelvote").executes(ctx -> {
			// /auradev duelvote: both agree = that map; different picks = one of the two; no votes = the time runs out; a forced layout skips the vote
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel arena = Arena.level(server);
			if (arena == null) return 0;
			net.minecraft.server.level.ServerPlayer red = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000a001"), "Red"));
			net.minecraft.server.level.ServerPlayer blue = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b002"), "Blue"));
			AuraState state = AuraState.get(server);
			state.getOrCreate(red.getUUID()).aura = AuraType.FIRE;
			state.getOrCreate(blue.getUUID()).aura = AuraType.FROST;
			int oldVote = com.toast.aurapoints.config.AuraConfig.DUEL_VOTE_SECONDS;
			com.toast.aurapoints.config.AuraConfig.DUEL_VOTE_SECONDS = 3;
			Runnable[] steps = new Runnable[5];
			java.util.function.Consumer<Integer> next = i -> AuraFx.later(server, 5, steps[i]);
			Runnable open = () -> {
				Duel.stop(server);
				for (net.minecraft.server.level.ServerPlayer p : new net.minecraft.server.level.ServerPlayer[]{red, blue}) {
					Duel.TEST_PLAYERS.put(p.getUUID(), p);
					p.moveTo(Arena.LOBBY.x, Arena.LOBBY.y, Arena.LOBBY.z);
				}
				Duel.challenge(red, blue, 1, 0);
				Duel.accept(blue);
			};
			steps[0] = () -> {
				open.run();
				log("A (both terraces): phase " + Duel.debugState() + " (must be VOTE); bad map " + Duel.vote(red, "castle") + " (must be 0)");
				Duel.vote(red, "terraces");
				Duel.vote(blue, "terraces");
				next.accept(1);
			};
			steps[1] = () -> {
				log("A result: " + Duel.debugState() + ", layout " + RingLayout.current + " (must be INTRO, TERRACES)");
				open.run();
				Duel.vote(red, "pillars");
				Duel.vote(blue, "shattered");
				next.accept(2);
			};
			steps[2] = () -> {
				log("B (pillars vs shattered): " + Duel.debugState() + ", layout " + RingLayout.current + " (must be INTRO, PILLARS or SHATTERED)");
				open.run();
				Duel.vote(red, "terraces");
				next.accept(3);
			};
			steps[3] = () -> {
				log("C (one vote, the other silent): " + Duel.debugState() + " (must still be VOTE)");
				AuraFx.later(server, 3 * 20 + 10, () -> {
					log("C after the time: " + Duel.debugState() + ", layout " + RingLayout.current + " (must be INTRO, TERRACES)");
					RingLayout.forced = RingLayout.SHATTERED;
					open.run();
					log("D (forced shattered): " + Duel.debugState() + ", layout " + RingLayout.current + " (must be INTRO, SHATTERED, no vote)");
					RingLayout.forced = null;
					Duel.stop(server);
					Duel.TEST_PLAYERS.clear();
					com.toast.aurapoints.config.AuraConfig.DUEL_VOTE_SECONDS = oldVote;
				});
			};
			steps[0].run();
			return 1;
		})).then(Commands.literal("wavemod").executes(ctx -> {
			// /auradev wavemod: a Brutal run - the hit mods, the Aura bonus, no record; then the solo / party record boards
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel arena = Arena.level(server);
			if (arena == null) return 0;
			AuraState state = AuraState.get(server);
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000e010"), "W1"));
			state.getOrCreate(p.getUUID()).aura = AuraType.FIRE;
			Duel.TEST_PLAYERS.put(p.getUUID(), p);
			p.moveTo(Arena.LOBBY.x, Arena.LOBBY.y, Arena.LOBBY.z);
			Match.stop(server);
			log("create brutal: " + Match.create(p, Match.Mode.WAVES, Waves.Modifier.BRUTAL) + ", " + Match.describe() + ", modifier " + Match.waveModifier() + " (must be BRUTAL)");
			log("start: " + Match.start(p));
			AuraFx.later(server, Math.max(1, com.toast.aurapoints.config.AuraConfig.DUEL_COUNTDOWN_SECONDS) * 20 + 60, () -> {
				Waves.Run run = Match.devRun();
				for (var mob : run.alive) {
					var dmg = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
					if (dmg == null) continue;
					log("mob " + mob.getType().getDescriptionId() + " attack " + dmg.getValue() + " base " + dmg.getBaseValue() + " (must be base x "
							+ (1.0 + com.toast.aurapoints.config.AuraConfig.WAVES_MOD_DAMAGE) + ")");
					break;
				}
				Waves.devClear(run);
			});
			AuraFx.later(server, Math.max(1, com.toast.aurapoints.config.AuraConfig.DUEL_COUNTDOWN_SECONDS) * 20 + 100, () -> {
				int per = com.toast.aurapoints.config.AuraConfig.WAVES_AURA_PER_WAVE;
				log("wave 1 earned " + Match.devRun().earned.getOrDefault(p.getUUID(), 0) + " (must be " + Math.round(per * (1.0 + com.toast.aurapoints.config.AuraConfig.WAVES_MOD_AURA_BONUS)) + ")");
				Match.stop(server);
				state.waveRecords.remove(p.getUUID());
				state.partyWaveRecords.remove(p.getUUID());
				var two = new java.util.LinkedHashMap<UUID, String>();
				two.put(p.getUUID(), "W1");
				two.put(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000e011"), "W2");
				var one = new java.util.LinkedHashMap<UUID, String>();
				one.put(p.getUUID(), "W1");
				log("record with a modifier: " + Waves.record(server, 7, one, Waves.Modifier.BRUTAL) + " (must be false)");
				log("party record: " + Waves.record(server, 8, two, Waves.Modifier.NONE) + " (must be true), party board " + state.partyWaveRecords.get(p.getUUID()).best
						+ " (must be 8), solo board " + (state.waveRecords.get(p.getUUID()) == null ? "none" : "set") + " (must be none)");
				log("solo record: " + Waves.record(server, 5, one, Waves.Modifier.NONE) + " (must be true), solo board " + state.waveRecords.get(p.getUUID()).best + " (must be 5)");
				state.waveRecords.remove(p.getUUID());
				state.partyWaveRecords.remove(p.getUUID());
				Duel.TEST_PLAYERS.clear();
			});
			return 1;
		})).then(Commands.literal("twists").executes(ctx -> {
			// /auradev twists: the wave twist vote without a run - due, offers, hotbar stash + restore, stacking, once-only, pay, record
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel arena = Arena.level(server);
			if (arena == null) return 0;
			String oldMode = com.toast.aurapoints.config.AuraConfig.WAVES_TWIST_VOTE_MODE;
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000e020"), "T1"));
			AuraState state = AuraState.get(server);
			state.getOrCreate(p.getUUID()).aura = AuraType.FIRE;
			var inv = p.getInventory();
			for (int i = 0; i < 9; i++) inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
			inv.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
			inv.setItem(3, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 7));
			Waves.Run run = new Waves.Run(7);
			var ps = java.util.List.of(p);
			run.wave = 3;
			log("due at wave 3: " + WaveTwists.due(run) + " (must be false); at 4: " + (run.wave = 4) + " " + WaveTwists.due(run) + " (must be true)");
			com.toast.aurapoints.config.AuraConfig.WAVES_TWIST_VOTE_MODE = "hotbar";
			log("open: " + WaveTwists.open(server, run, ps, 18) + " (must be true), offered " + run.twists.offered);
			boolean items = true;
			for (int slot : new int[]{1, 3, 5}) items &= !inv.getItem(slot).isEmpty() && inv.getItem(slot).get(net.minecraft.core.component.DataComponents.CUSTOM_DATA) != null;
			log("vote items in slots 2/4/6: " + items + " (must be true), slot 1 empty " + inv.getItem(0).isEmpty() + " (must be true), saved stash " + (state.getOrCreate(p.getUUID()).twistHotbar != null) + " (must be true)");
			log("use item in slot 4: " + WaveTwists.onUse(p, inv.getItem(3)) + " (must be true); use a sword: " + WaveTwists.onUse(p, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD)) + " (must be false)");
			WaveTwists.vote(p, run.twists, 2);
			log("votes " + run.twists.votes + " (must be {" + p.getUUID() + "=1}) all voted " + WaveTwists.allVoted(run.twists, ps) + " (must be true)");
			var picked = run.twists.offered.get(1);
			WaveTwists.resolve(server, run, ps);
			log("winner " + picked + " stacks " + run.twists.stacks(picked) + " (must be 1), open " + run.twists.open + " (must be false)");
			boolean back = inv.getItem(0).is(net.minecraft.world.item.Items.DIAMOND_SWORD) && inv.getItem(3).is(net.minecraft.world.item.Items.BREAD) && inv.getItem(3).getCount() == 7
					&& inv.getItem(1).isEmpty() && inv.getItem(5).isEmpty() && state.getOrCreate(p.getUUID()).twistHotbar == null;
			log("hotbar back: " + back + " (must be true)");
			// leaving mid-vote also gives it back
			log("open 2: " + WaveTwists.open(server, run, ps, 18));
			WaveTwists.dropped(run, p);
			log("after drop: sword back " + inv.getItem(0).is(net.minecraft.world.item.Items.DIAMOND_SWORD) + " (must be true), vote items gone " + inv.getItem(1).isEmpty() + " (must be true)");
			run.twists.open = false;
			// stacking + once-only: take every twist 3 times over by force
			com.toast.aurapoints.config.AuraConfig.WAVES_TWIST_VOTE_MODE = "chat";
			boolean onceOk = true;
			for (int i = 0; i < 12; i++) {
				run.wave += 4;
				if (!WaveTwists.open(server, run, ps, 18)) {
					log("round " + i + ": nothing to offer");
					break;
				}
				for (var t : run.twists.offered) if (!t.repeatable && run.twists.stacks(t) > 0) onceOk = false;
				WaveTwists.vote(p, run.twists, 1);
				WaveTwists.resolve(server, run, ps);
			}
			log("stacks " + WaveTwists.summary(run) + "; one-offs never offered twice: " + onceOk + " (must be true); hard taken " + WaveTwists.hardTaken(run));
			for (var t : WaveTwists.Twist.values()) if (!t.repeatable && run.twists.stacks(t) > 1) log("ONE-OFF STACKED: " + t);
			// pay
			Waves.Run pay = new Waves.Run(1);
			pay.twists.stacks.put(WaveTwists.Twist.HALF_PAY, 1);
			pay.twists.stacks.put(WaveTwists.Twist.BONUS_XP, 2);
			pay.twists.stacks.put(WaveTwists.Twist.STRONGER, 1);
			pay.wave = 3;
			int[] odd = WaveTwists.pay(pay, 2, 15);
			pay.wave = 4;
			int[] even = WaveTwists.pay(pay, 2, 15);
			log("half pay odd " + java.util.Arrays.toString(odd) + " (must be [0, 0]); even " + java.util.Arrays.toString(even) + " (must be [6, 34]: (2+1)x2, (15+2)x2)");
			log("record with a hard twist: " + Waves.record(server, 6, java.util.Map.of(p.getUUID(), "T1"), Waves.Modifier.NONE, true) + " (must be false)");
			com.toast.aurapoints.config.AuraConfig.WAVES_TWIST_VOTE_MODE = oldMode;
			return 1;
		})).then(Commands.literal("twistrun").executes(ctx -> {
			// /auradev twistrun: a real solo run - clear waves until the vote opens (wave 4), vote 1, check wave 5's mobs
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel arena = Arena.level(server);
			if (arena == null) return 0;
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000e030"), "T2"));
			AuraState.get(server).getOrCreate(p.getUUID()).aura = AuraType.FIRE;
			Duel.TEST_PLAYERS.put(p.getUUID(), p);
			p.moveTo(Arena.LOBBY.x, Arena.LOBBY.y, Arena.LOBBY.z);
			Match.stop(server);
			log("create: " + Match.create(p, Match.Mode.WAVES, Waves.Modifier.NONE) + ", start: " + Match.start(p));
			final boolean[] voted = {false};
			final int[] guard = {0};
			final Runnable[] loop = new Runnable[1];
			loop[0] = () -> {
				Waves.Run run = Match.waveRun();
				if (run == null || ++guard[0] > 90) {
					log("twistrun ended (guard " + guard[0] + ")");
					Match.stop(server);
					Duel.TEST_PLAYERS.clear();
					return;
				}
				if (run.twists.open && !voted[0]) {
					log("vote open at wave " + run.wave + ": offered " + run.twists.offered + " (must be 3 of them)");
					log("vote cmd: " + Match.voteTwist(p, 1));
					voted[0] = true;
				} else if (voted[0] && run.wave == 5 && !run.alive.isEmpty()) {
					var mob = run.alive.get(0);
					var hp = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
					log("wave 5 twists: " + WaveTwists.summary(run) + "; first mob " + mob.getType().getDescriptionId() + " max health " + hp.getValue() + " base " + hp.getBaseValue() + "; queue " + (run.alive.size() + run.queue.size()));
					Match.stop(server);
					Duel.TEST_PLAYERS.clear();
					log("after stop: stash " + (AuraState.get(server).getOrCreate(p.getUUID()).twistHotbar == null) + " (must be true)");
					return;
				} else if (run.wave > 0) Waves.devClear(run);
				AuraFx.later(server, 20, loop[0]);
			};
			AuraFx.later(server, Math.max(1, com.toast.aurapoints.config.AuraConfig.DUEL_COUNTDOWN_SECONDS) * 20 + 60, loop[0]);
			return 1;
		})).then(Commands.literal("npc").executes(ctx -> {
			// every NPC's click, by a fake player (teleports don't move it; this is for errors and the menus' logic)
			ServerLevel arena = Arena.level(ctx.getSource().getServer());
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c003"), "Clicker"));
			var npcs = arena.getEntitiesOfClass(ArenaNpc.class, ArenaBuilder.bounds());
			log("npcs loaded: " + npcs.size());
			for (ArenaNpc npc : npcs) {
				if (npc.kind() == NpcKind.LEAVE) continue; // a fake player can't change dimension
				try {
					npc.kind().use(p, npc);
					log(npc.kind() + " ok at " + npc.blockPosition() + ", live line: " + npc.kind().liveLine(arena).getString());
				} catch (Exception e) {
					log(npc.kind() + " FAILED: " + e);
				}
			}
			return 1;
		})));
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[arenatest] {}", what);
	}
}
