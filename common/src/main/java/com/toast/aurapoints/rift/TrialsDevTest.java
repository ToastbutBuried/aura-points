package com.toast.aurapoints.rift;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev trialtest [kind] - the Reliquary trials (5.49): the framework on the first
 * trial (portals both ways, the strike ladder, falling in the arena), then each trial's own {@code devPlay} through its
 * real inputs by a fake player. Two realms (Frost, then Wind for the Skyward Rings). Logs [trialtest].
 */
public final class TrialsDevTest {

	private TrialsDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[trialtest] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("trialtest")
				.executes(ctx -> start(ctx.getSource().getServer(), null))
				.then(Commands.literal("lag").executes(ctx -> lag(ctx.getSource().getServer())))
				.then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
						.executes(ctx -> start(ctx.getSource().getServer(), Trials.Kind.valueOf(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "kind").toUpperCase(java.util.Locale.ROOT)))))));
	}

	/**
	 * The real flow's server cost: a Wind gate opens (arena chunks preload), 20 s later a fake walks in and votes,
	 * the arenas build on their own; logs the slowest tick from the vote until all four stand.
	 */
	private static int lag(MinecraftServer server) {
		ServerLevel rift = Rifts.level(server);
		ServerPlayer fake = FakePlayerFactory.get(rift, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c642"), "TrialTest"));
		Rifts.TEST_PLAYERS.put(fake.getUUID(), fake);
		AuraState.get(server).getOrCreate(fake.getUUID()).aura = AuraType.WATER;
		Rifts.open(server, AuraType.WIND, null, new Vec3(0.5, 120, 0.5));
		AuraFx.later(server, 400, () -> {
			Rifts.devEnter(server, fake);
			Rifts.devVote(fake, 1);
			Rifts.Run run = Rifts.runOf(fake);
			long[] worst = {0}, last = {System.nanoTime()};
			int[] ticks = {0};
			Runnable[] step = new Runnable[1];
			step[0] = () -> {
				long t = System.nanoTime();
				worst[0] = Math.max(worst[0], t - last[0]);
				last[0] = t;
				boolean all = run != null && java.util.Arrays.stream(run.trials).allMatch(x -> x.built);
				if (all || ++ticks[0] > 700) {
					log("lag: arenas built " + all + " (must be true) after " + ticks[0] + " ticks, slowest tick " + worst[0] / 1_000_000 + " ms (must be < 250), kinds " + Rifts.devKinds(fake));
					Rifts.leave(fake);
					Rifts.clearAll();
					Rifts.TEST_PLAYERS.clear();
					log("trialtest done");
				} else AuraFx.later(server, 1, step[0]);
			};
			AuraFx.later(server, 1, step[0]);
		});
		return 1;
	}

	/** Runs the framework checks, then every kind's devPlay (or only {@code only}). */
	private static int start(MinecraftServer server, Trials.Kind only) {
		ServerLevel rift = Rifts.level(server);
		ServerPlayer fake = FakePlayerFactory.get(rift, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c642"), "TrialTest"));
		Rifts.TEST_PLAYERS.put(fake.getUUID(), fake);
		AuraState.get(server).getOrCreate(fake.getUUID()).aura = AuraType.WATER;
		fake.getInventory().clearContent();
		List<List<Trials.Kind>> runs = only != null
				? List.of(List.of(only, only == Trials.Kind.DIG ? Trials.Kind.ECHOES : Trials.Kind.DIG, Trials.Kind.GLYPH == only ? Trials.Kind.PRISM : Trials.Kind.GLYPH, Trials.Kind.BELLS == only ? Trials.Kind.STATUES : Trials.Kind.BELLS))
				: List.of(List.of(Trials.Kind.DIG, Trials.Kind.ECHOES, Trials.Kind.GLYPH, Trials.Kind.PRISM), List.of(Trials.Kind.BELLS, Trials.Kind.STATUES, Trials.Kind.RINGS, Trials.Kind.DIG));
		runRealm(server, rift, fake, runs, 0, only != null ? 1 : 3, true);
		return 1;
	}

	/** Realm {@code n} of {@code runs}: the first {@code count} trials get played; realm 0 also checks the framework. */
	private static void runRealm(MinecraftServer server, ServerLevel rift, ServerPlayer fake, List<List<Trials.Kind>> runs, int n, int count, boolean framework) {
		if (n >= runs.size()) {
			Rifts.clearAll();
			Rifts.TEST_PLAYERS.clear();
			log("trialtest done");
			return;
		}
		Rifts.TEST_PLAYERS.put(fake.getUUID(), fake);
		Rifts.DEV_KINDS = runs.get(n);
		Rifts.open(server, n == 1 ? AuraType.WIND : AuraType.FROST, null, new Vec3(0.5, 120, 0.5));
		Rifts.devEnter(server, fake);
		Rifts.devVote(fake, 1);
		Rifts.Run run = Rifts.runOf(fake);
		Rifts.DEV_KINDS = null;
		if (run == null) {
			log("no run (must not happen)");
			return;
		}
		log("realm " + n + ": kinds " + Rifts.devKinds(fake) + ", all built " + java.util.Arrays.stream(run.trials).allMatch(t -> t.built) + " (must be true)");
		if (framework) framework(rift, fake, run);
		int last = n == runs.size() - 1 && runs.size() > 1 ? 3 : count;
		play(server, rift, fake, run, 0, last, () -> {
			Rifts.leave(fake);
			Rifts.clearAll();
			runRealm(server, rift, fake, runs, n + 1, count, false);
		});
	}

	private static void play(MinecraftServer server, ServerLevel rift, ServerPlayer fake, Rifts.Run run, int i, int count, Runnable after) {
		if (i >= count) {
			after.run();
			return;
		}
		Trials.Trial t = run.trials[i];
		Vec3 in = t.back.exit();
		fake.moveTo(in.x, in.y, in.z);
		fake.setOnGround(true);
		t.tickAll(rift, List.of(fake), rift.getGameTime());
		log(t.name() + ": started " + t.started + " (must be true)");
		t.devPlay(server, rift, fake, TrialsDevTest::log, () -> {
			log(t.name() + ": solved " + t.solved + " (must be true), shard out " + t.shardOut + " (must be true)");
			play(server, rift, fake, run, i + 1, count, after);
		});
	}

	/** The shared machinery on trial 0: portals both ways, the strike ladder, falling in the arena. */
	private static void framework(ServerLevel rift, ServerPlayer fake, Rifts.Run run) {
		Trials.Trial t = run.trials[0];
		long now = rift.getGameTime();
		Vec3 g = Vec3.atBottomCenterOf(t.gate.base.above());
		fake.moveTo(g.x, g.y, g.z);
		t.tickAll(rift, List.of(fake), now);
		log("portal in: in the arena " + t.inArena(fake.position()) + " (must be true)");
		Vec3 b = Vec3.atBottomCenterOf(t.back.base.above());
		fake.moveTo(b.x, b.y, b.z);
		t.tickAll(rift, List.of(fake), now + 1);
		log("portal cooldown: still in the arena " + t.inArena(fake.position()) + " (must be true)");
		t.tickAll(rift, List.of(fake), now + 60);
		log("portal back: in the wing " + t.inWing(fake.position()) + " (must be true)");
		Vec3 fell = new Vec3(t.arena(0, 0, -40).getX() + 0.5, Rifts.FLOOR, t.arena(0, 0, 0).getZ() + 0.5);
		log("fall respawn in the arena -> at the way back " + (t.respawn(fell).distanceTo(t.back.exit()) < 0.1) + " (must be true)");
		int mobs = run.mobs.size();
		t.strike(rift, fake, fake.position(), List.of(fake));
		t.strike(rift, fake, fake.position(), List.of(fake));
		log("two strikes: count " + t.strikes + " (must be 2), hazard mobs " + (run.mobs.size() > mobs) + (t.kind == Trials.Kind.RINGS ? " (Rings: gusts, no mobs)" : " (must be true)"));
		t.strike(rift, fake, fake.position(), List.of(fake));
		log("third strike: count " + t.strikes + " (must be 0), total " + t.totalStrikes + " (must be 3)");
		for (var m : run.mobs) m.discard();
		run.mobs.clear();
		t.strikes = 0;
		t.totalStrikes = 0;
	}

	/** /auradev riftstep solve|place|ritual - moves the caller's own run on (for client playtests). */
	public static void registerStep(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("riftstep").then(Commands.argument("what", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
			ServerPlayer p = ctx.getSource().getPlayerOrException();
			String what = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "what");
			switch (what) {
				case "solve" -> {
					for (int i = 0; i < 4; i++) Rifts.devSolve(p, i);
				}
				case "place" -> Rifts.devPlaceAll(p);
				case "vote" -> Rifts.devVote(p, 2);
				case "ritual" -> Rifts.devFinishRitual(p);
				case "overdrive" -> {
					if (Rifts.devGuardian(p) instanceof RelicWarden w) w.devPhase(RelicWarden.OVERDRIVE, 0.3f);
				}
				case "laststand" -> {
					if (Rifts.devGuardian(p) instanceof RelicWarden w) w.devPhase(RelicWarden.LAST_STAND, 0.1f);
				}
				default -> {
					return 0;
				}
			}
			log("riftstep " + what + ": " + Rifts.devState(p));
			return 1;
		}))));
	}

	/** /auradev wardentest - the Relic Warden's phases, the parry, the pylons and the last stand. */
	public static void registerWarden(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("wardentest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel rift = Rifts.level(server);
			ServerPlayer fake = FakePlayerFactory.get(rift, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c643"), "WardenTest"));
			Rifts.TEST_PLAYERS.put(fake.getUUID(), fake);
			AuraState.get(server).getOrCreate(fake.getUUID()).aura = AuraType.WATER;
			Rifts.open(server, AuraType.SHADOW, null, new Vec3(0.5, 120, 0.5));
			Rifts.devEnter(server, fake);
			Rifts.devVote(fake, 1);
			for (int i = 0; i < 4; i++) Rifts.devSolve(fake, i);
			Rifts.devPlaceAll(fake);
			Rifts.devFinishRitual(fake);
			RelicWarden w = (RelicWarden) Rifts.devGuardian(fake);
			if (w == null) {
				log("[warden] no warden (must not happen)");
				return 0;
			}
			var src = rift.damageSources().playerAttack(fake);
			// in front of it (the parry needs that), a full swing ago
			Vec3 front = w.position().add(Vec3.directionFromRotation(0, w.getYRot()).scale(2.5));
			fake.moveTo(front.x, w.getY(), front.z);
			log("[warden] spawned: skin " + w.skin() + " (must be " + com.toast.aurapoints.ability.Relics.AURAS.indexOf(AuraType.SHADOW) + "), phase " + w.phase() + " (must be 0), hp " + w.getMaxHealth());
			// the parry
			// spam: a hit during the wind-up (too early) is a mistimed parry - no damage, no parry
			boolean on = w.devStartParryable();
			w.devSetWindup(12);
			float hpS = w.getHealth();
			w.invulnerableTime = 0;
			w.hurt(src, 5f);
			log("[warden] early swing: damage " + (w.getHealth() < hpS) + " (must be false), staggered boss " + w.devStaggered() + " (must be false)");
			// a fresh player, a clean swing inside the window
			ServerPlayer fake2 = FakePlayerFactory.get(rift, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c644"), "WardenTest2"));
			fake2.moveTo(front.x, w.getY(), front.z);
			var src2 = rift.damageSources().playerAttack(fake2);
			w.devStartParryable();
			w.devSetWindup(2);
			float hp0 = w.getHealth();
			w.invulnerableTime = 0;
			w.hurt(src2, 5f);
			log("[warden] parry: windup on " + on + " (must be true), staggered " + w.devStaggered() + " (must be true), bonus damage " + ((hp0 - w.getHealth()) > 5f) + " (must be true)");
			// a second hit in the same window by the same player can't parry again
			w.devStartParryable();
			w.devSetWindup(2);
			w.invulnerableTime = 0;
			float hp1b = w.getHealth();
			w.hurt(src2, 5f);
			log("[warden] instant re-swing: parried again " + (w.getHealth() < hp1b - 5f) + " (must be false)");
			// guardian -> unbound
			w.devSetHealth(0.69f);
			for (int k = 0; k < 40; k++) w.devAiStep();
			log("[warden] at 69%: phase " + w.phase() + " (must be 1)");
			// a huge hit can't skip the shield: the floor is 40%
			w.invulnerableTime = 0;
			w.hurt(rift.damageSources().magic(), 9999f);
			log("[warden] huge hit in Unbound: hp share " + String.format("%.2f", w.getHealth() / w.getMaxHealth()) + " (must be 0.40), alive " + w.isAlive());
			w.devAiStep();
			log("[warden] then: phase " + w.phase() + " (must be 2), pylons " + w.devPylons() + " (must be 4)");
			w.invulnerableTime = 0;
			float hp1 = w.getHealth();
			w.hurt(src, 50f);
			log("[warden] shielded: took damage " + (w.getHealth() < hp1) + " (must be false)");
			for (var e : rift.getEntitiesOfClass(net.minecraft.world.entity.monster.Shulker.class, w.getBoundingBox().inflate(20))) e.kill();
			for (int k = 0; k < 45; k++) w.devAiStep();
			log("[warden] pylons down: phase " + w.phase() + " (must be 3)");
			w.invulnerableTime = 0;
			w.hurt(rift.damageSources().magic(), 9999f);
			w.devAiStep();
			log("[warden] huge hit in Overdrive: phase " + w.phase() + " (must be 4), hp share " + String.format("%.2f", w.getHealth() / w.getMaxHealth()) + " (must be 0.10), kneel " + w.devKneel() + " (must be 59)");
			w.invulnerableTime = 0;
			float hp2 = w.getHealth();
			w.hurt(src, 50f);
			log("[warden] kneeling: took damage " + (w.getHealth() < hp2) + " (must be false)");
			w.devSkipKneel();
			w.devAiStep();
			log("[warden] risen: desperation " + w.devDesperation() + " (must be 300)");
			for (int k = 0; k < 300; k++) w.devAiStep();
			log("[warden] desperation over: phase " + w.phase() + " (must be 3), hp share " + String.format("%.2f", w.getHealth() / w.getMaxHealth()) + " (must be 0.30)");
			w.invulnerableTime = 0;
			w.hurt(rift.damageSources().magic(), 9999f);
			log("[warden] final blow: dead " + w.isDeadOrDying() + " (must be true)");
			AuraFx.later(server, 20, () -> {
				log("[warden] run: " + Rifts.devState(fake) + " (must be done true won true)");
				Rifts.leave(fake);
				Rifts.clearAll();
				Rifts.TEST_PLAYERS.clear();
				log("[warden] wardentest done");
			});
			return 1;
		})));
	}

	private static void use(ServerLevel rift, ServerPlayer p, BlockPos pos, Trials.Trial t) {
		t.use(rift, p, pos);
	}

	private static void stand(ServerPlayer p, BlockPos feet) {
		p.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
		p.setOnGround(true);
	}
}
