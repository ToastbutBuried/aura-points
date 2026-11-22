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
 * DEV ONLY (registered by DevTest): /auradev trialtest - the Relic Trials' puzzles (5.46) played through their real
 * inputs by a fake player: Rune Sequence (a wrong rune calls mobs, then the right order), Lights (the scramble pressed
 * again), Resonance Hunt (a wrong mound, then the right one), Memory Path (a wrong tile breaks, the far pad solves it),
 * then a second realm for the Carry Trial (a fall sends the shard back, carried across it settles). Logs [trialtest].
 */
public final class TrialsDevTest {

	private TrialsDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[trialtest] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("trialtest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel rift = Rifts.level(server);
			ServerPlayer fake = FakePlayerFactory.get(rift, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c642"), "TrialTest"));
			Rifts.TEST_PLAYERS.put(fake.getUUID(), fake);
			AuraState.get(server).getOrCreate(fake.getUUID()).aura = AuraType.WATER;
			fake.getInventory().clearContent();
			Rifts.DEV_KINDS = List.of(Trials.Kind.RUNES, Trials.Kind.LIGHTS, Trials.Kind.HUNT, Trials.Kind.MEMORY);
			Rifts.open(server, AuraType.FROST, null, new Vec3(0.5, 120, 0.5));
			Rifts.devEnter(server, fake);
			Rifts.Run run = Rifts.runOf(fake);
			Rifts.DEV_KINDS = null;
			if (run == null) {
				log("no run (must not happen)");
				return 0;
			}
			// --- Rune Sequence: begin, wait for the show, a wrong rune, wait again, the right order
			Trials.Runes runes = (Trials.Runes) run.trials[0];
			runes.started = true;
			runes.begin(rift, List.of(fake));
			int show = 50 + (runes.seq.length + 1) * 16 + 5;
			AuraFx.later(server, show, () -> {
				int wrong = (runes.seq[0] + 1) % 6;
				int mobs0 = run.mobs.size();
				use(rift, fake, runes.at(runes.pillar[wrong][0], runes.pillar[wrong][1], 2), runes);
				log("runes: wrong rune -> mobs " + (run.mobs.size() - mobs0) + " (must be " + com.toast.aurapoints.config.AuraConfig.RIFT_PUZZLE_FAIL_MOBS + "), solved " + runes.solved + " (must be false)");
				AuraFx.later(server, 70 + (runes.seq.length + 1) * 16 + 5, () -> {
					for (int k : runes.seq) use(rift, fake, runes.at(runes.pillar[k][0], runes.pillar[k][1], 1), runes);
					log("runes: right order (" + runes.seq.length + ") -> solved " + runes.solved + " (must be true)");
					lightsHuntMemory(server, rift, fake, run);
				});
			});
			return 1;
		})));
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
			for (int i = 0; i < 4; i++) Rifts.devSolve(fake, i);
			Rifts.devPlaceAll(fake);
			Rifts.devFinishRitual(fake);
			RelicWarden w = (RelicWarden) Rifts.devGuardian(fake);
			if (w == null) {
				log("[warden] no warden (must not happen)");
				return 0;
			}
			var src = rift.damageSources().playerAttack(fake);
			fake.moveTo(w.getX() + 2, w.getY(), w.getZ());
			log("[warden] spawned: skin " + w.skin() + " (must be " + com.toast.aurapoints.ability.Relics.AURAS.indexOf(AuraType.SHADOW) + "), phase " + w.phase() + " (must be 0), hp " + w.getMaxHealth());
			// the parry
			boolean on = w.devStartSlam();
			w.devSetWindup(4);
			float hp0 = w.getHealth();
			w.invulnerableTime = 0;
			w.hurt(src, 5f);
			log("[warden] parry: windup on " + on + " (must be true), staggered " + w.devStaggered() + " (must be true), bonus damage " + ((hp0 - w.getHealth()) > 5f) + " (must be true)");
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

	private static void lightsHuntMemory(MinecraftServer server, ServerLevel rift, ServerPlayer fake, Rifts.Run run) {
		// --- Lights: pressing the scramble again lights everything
		Trials.Lights lights = (Trials.Lights) run.trials[1];
		lights.started = true;
		for (int[] k : lights.scramble) use(rift, fake, lights.at(lights.f(k[0]), lights.f(k[1]), 1), lights);
		log("lights: " + lights.scramble.size() + " presses -> solved " + lights.solved + " (must be true)");
		// --- Resonance Hunt: a wrong mound, then the target
		Trials.Hunt hunt = (Trials.Hunt) run.trials[2];
		hunt.started = true;
		hunt.begin(rift, List.of(fake));
		boolean fork = fake.getInventory().contains(new net.minecraft.world.item.ItemStack(AuraPoints.TUNING_FORK.get()));
		BlockPos wrong = null;
		for (BlockPos m : hunt.mounds) if (!m.equals(hunt.target)) wrong = m;
		int before = hunt.mounds.size();
		use(rift, fake, wrong, hunt);
		log("hunt: fork given " + fork + " (must be true), wrong mound -> mounds " + before + " -> " + hunt.mounds.size() + ", solved " + hunt.solved + " (must be false), mound gone " + rift.getBlockState(wrong).isAir());
		use(rift, fake, hunt.target, hunt);
		log("hunt: target mound -> solved " + hunt.solved + " (must be true)");
		// --- Memory Path: a wrong tile breaks under you, the far pad solves it
		Trials.Memory mem = (Trials.Memory) run.trials[3];
		mem.started = true;
		int badF = 0, badL = 99;
		for (int f = Trials.Memory.L0; f <= Trials.Memory.L1 && badL == 99; f++)
			for (int l = -Trials.Memory.W; l <= Trials.Memory.W; l++) if (!mem.safe.contains(Trials.Memory.key(f, l))) {
				badF = f;
				badL = l;
				break;
			}
		stand(fake, mem.at(badF, badL, 1));
		mem.tick(rift, List.of(fake), rift.getGameTime());
		log("memory: stood on a wrong tile -> broken " + rift.getBlockState(mem.at(badF, badL, 0)).isAir() + " (must be true), solved " + mem.solved + " (must be false)");
		stand(fake, mem.at(Trials.Memory.L1 + 1, 0, 1));
		mem.tick(rift, List.of(fake), rift.getGameTime());
		log("memory: on the far pad -> solved " + mem.solved + " (must be true), grid filled " + !rift.getBlockState(mem.at(badF, badL, 0)).isAir() + " (must be true)");
		log("first realm: " + Rifts.devState(fake) + " (must be trials 4/4), shards in the world " + Rifts.devShardItemsInWorld(fake) + " (must be 4)");
		Rifts.leave(fake);
		Rifts.clearAll();
		Rifts.TEST_PLAYERS.put(fake.getUUID(), fake);
		carry(server, rift, fake);
	}

	private static void stand(ServerPlayer p, BlockPos feet) {
		p.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
		p.setOnGround(true);
	}

	private static void carry(MinecraftServer server, ServerLevel rift, ServerPlayer fake) {
		Rifts.DEV_KINDS = List.of(Trials.Kind.CARRY, Trials.Kind.RUNES, Trials.Kind.LIGHTS, Trials.Kind.HUNT);
		Rifts.open(server, AuraType.FIRE, null, new Vec3(0.5, 120, 0.5));
		Rifts.devEnter(server, fake);
		Rifts.DEV_KINDS = null;
		Rifts.Run run = Rifts.runOf(fake);
		Trials.Carry carry = (Trials.Carry) run.trials[0];
		carry.started = true;
		carry.begin(rift, List.of(fake));
		log("carry: shard on its plinth " + Rifts.devShardItemsInWorld(fake) + " (must be 1)");
		// picked up (the dev way: the item entity goes, the stack goes to the pocket)
		for (var it : rift.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(carry.plinth(), carry.plinth()).inflate(3))) it.discard();
		fake.getInventory().add(com.toast.aurapoints.item.RelicShardItem.make(run.aura, run.id, 0));
		// unstable: the altar won't take it yet
		fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, fake.getInventory().getItem(fake.getInventory().findSlotMatchingItem(com.toast.aurapoints.item.RelicShardItem.make(run.aura, run.id, 0))));
		run.altar.use(rift, fake, run.pedestal(0).above(2));
		log("carry: unstable shard on the altar -> placed " + run.altar.placed + " (must be 0)");
		// a fall into the gap
		fake.moveTo(Vec3.atBottomCenterOf(carry.at(0, 0, -6)));
		carry.tick(rift, List.of(fake), rift.getGameTime());
		log("carry: fell -> holds the shard " + Rifts.holdsShard(fake, run, 0) + " (must be false), back on its plinth " + Rifts.devShardItemsInWorld(fake) + " (must be 1)");
		for (var it : rift.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(carry.plinth(), carry.plinth()).inflate(3))) it.discard();
		fake.getInventory().add(com.toast.aurapoints.item.RelicShardItem.make(run.aura, run.id, 0));
		stand(fake, carry.at(Trials.Carry.END, 1, 1));
		carry.tick(rift, List.of(fake), rift.getGameTime());
		log("carry: across -> solved " + carry.solved + " (must be true), still holds it " + Rifts.holdsShard(fake, run, 0) + " (must be true)");
		Rifts.leave(fake);
		log("carry: after leaving -> holds it " + Rifts.holdsShard(fake, run, 0) + " (must be false)");
		Rifts.clearAll();
		Rifts.TEST_PLAYERS.clear();
		log("trialtest done");
	}
}
