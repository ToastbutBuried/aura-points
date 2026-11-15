package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.ability.Chains;
import com.toast.aurapoints.arena.Arena;
import com.toast.aurapoints.arena.AuraTrial;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev trialtest - the Aura Trial (5.27). A fake Fire player with nothing bought and
 * Mastery 0: the kit is closed outside the Training Hall and open inside during the trial, a real Fireball press ticks its
 * item, every other item ticks through the same hooks (casts via onPressed, chains via real Chains presses), the trial
 * finishes and marks the aura, the kit closes again; a retake, a pause outside the hall, and Resonance out of rolling.
 */
public final class TrialDevTest {

	private TrialDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[trialtest] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("trialtest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel arena = Arena.level(server);
			if (arena == null) {
				log("no arena dimension (must not happen)");
				return 0;
			}
			AuraState state = AuraState.get(server);
			boolean cooldownsWere = state.cooldownsDisabled;
			ServerPlayer fake = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c601"), "TrialTest"));
			AuraTrial.testPlayer = fake;
			AuraPlayerData data = state.getOrCreate(fake.getUUID());
			data.aura = AuraType.FIRE;
			data.rolled.add(AuraType.FIRE);
			data.owned.clear();
			data.mastery.put(AuraType.FIRE, 0);
			data.branches.remove(AuraType.FIRE);
			data.awakened.remove(AuraType.FIRE);
			data.trialDone.remove(AuraType.FIRE);
			Chains.forget(fake.getUUID());
			state.cooldownsDisabled = true;
			try {
				fake.moveTo(Arena.HALL_ARRIVAL.x, Arena.HALL_ARRIVAL.y, Arena.HALL_ARRIVAL.z, 0, 0);
				log("before: Fireball usable " + AuraAbilities.hasAbility(data, Ability.FIREBALL) + " (must be false), Spread Shot open "
						+ CastMode.unlocked(fake, Ability.FIREBALL, CastMode.SNEAK) + " (must be false)");

				AuraTrial.start(fake);
				List<String> list = AuraTrial.state(fake);
				long chains = list.stream().filter(l -> l.contains("Chain: ")).count();
				log("started: running " + AuraTrial.running(fake) + " (must be true), " + list.size() + " items, " + chains + " chains (must be "
						+ Chains.links(data).size() + "), in the hall " + Arena.inTraining(fake) + " (must be true)");
				log("checklist: " + String.join(" | ", list));
				log("during: Fireball usable " + AuraAbilities.hasAbility(data, Ability.FIREBALL) + " (must be true), Spread Shot open "
						+ CastMode.unlocked(fake, Ability.FIREBALL, CastMode.SNEAK) + ", Meteor open " + CastMode.unlocked(fake, Ability.FIREBALL, CastMode.CHARGED) + " (must be true true)");

				boolean fired = AuraAbilities.tryActivate(fake, Ability.FIREBALL, server.getTickCount(), CastMode.TAP);
				log("a real Fireball press: fired " + fired + " (must be true), ticked " + AuraTrial.state(fake).stream().anyMatch(l -> l.equals("✔ Fireball")) + " (must be true)");

				// every cast through the hook, every chain through real chain presses
				for (Ability a : AuraType.FIRE.keySlots()) {
					for (CastMode m : CastMode.values()) if (CastMode.has(a, m)) AuraTrial.onPressed(fake, a, m);
				}
				log("after every cast: " + AuraTrial.state(fake).stream().filter(l -> l.startsWith("☐")).count() + " left (must be " + chains + ", the chains)");
				for (Chains.Link l : Chains.links(data)) {
					Chains.forget(fake.getUUID());
					Chains.onPressed(fake, l.from(), l.fromMode() == null ? CastMode.TAP : l.fromMode());
					Chains.onPressed(fake, l.to(), l.toMode() == null ? CastMode.TAP : l.toMode());
				}
				log("finished: running " + AuraTrial.running(fake) + " done " + data.trialDone.contains(AuraType.FIRE) + " kit " + data.trialKit
						+ " Fireball usable " + AuraAbilities.hasAbility(data, Ability.FIREBALL) + " (must be false true false false)");

				// awakened: the Ultimate is on the list, and its own key (Ultimates.press, not tryActivate) ticks it
				data.awakened.add(AuraType.FIRE);
				AuraTrial.start(fake);
				boolean listed = AuraTrial.state(fake).stream().anyMatch(l -> l.endsWith("(Ultimate)"));
				com.toast.aurapoints.ability.Ultimates.press(fake, server.getTickCount());
				log("awakened: Ultimate listed " + listed + " (must be true), ticked by its key " + AuraTrial.state(fake).stream().anyMatch(l -> l.startsWith("✔") && l.endsWith("(Ultimate)"))
						+ " (must be true)");
				com.toast.aurapoints.ability.Ultimates.onLogout(fake);
				data.awakened.remove(AuraType.FIRE);
				AuraTrial.stop(fake, null);

				// a retake, then a pause outside the hall
				AuraTrial.start(fake);
				log("retake: running " + AuraTrial.running(fake) + ", nothing ticked yet " + AuraTrial.state(fake).stream().noneMatch(l -> l.startsWith("✔")) + " (must be true true)");
				fake.moveTo(Arena.HALL_ARRIVAL.x, Arena.HALL_ARRIVAL.y, Arena.HALL_ARRIVAL.z - 60, 0, 0);
				AuraTrial.tickAll(server);
				log("outside the hall: kit " + data.trialKit + " Fireball usable " + AuraAbilities.hasAbility(data, Ability.FIREBALL) + " running " + AuraTrial.running(fake)
						+ " (must be false false true)");
				fake.moveTo(Arena.HALL_ARRIVAL.x, Arena.HALL_ARRIVAL.y, Arena.HALL_ARRIVAL.z, 0, 0);
				AuraTrial.tickAll(server);
				log("back in: kit " + data.trialKit + " (must be true)");
				state.cooldownsDisabled = false;
				boolean started = com.toast.aurapoints.arena.TrainingHall.devTryChallenge(fake);
				state.cooldownsDisabled = true;
				AuraTrial.stop(fake, null);
				state.cooldownsDisabled = false;
				boolean control = com.toast.aurapoints.arena.TrainingHall.devTryChallenge(fake);
				state.cooldownsDisabled = true;
				log("DPS kiosk during the trial: challenge started " + started + " (must be false), without a trial " + control + " (must be true)");
				AuraTrial.start(fake);
				AuraTrial.tickAll(server);
				data.aura = AuraType.WATER;
				AuraTrial.tickAll(server);
				log("aura changed: running " + AuraTrial.running(fake) + " kit " + data.trialKit + " (must be false false)");

				log("Resonance rollable " + AuraType.RESONANCE.rollable() + " (must be false), RESONANCE_ROLLABLE " + AuraConfig.RESONANCE_ROLLABLE);
			} finally {
				AuraTrial.stop(fake, null);
				AuraTrial.testPlayer = null;
				Chains.forget(fake.getUUID());
				AuraAbilities.clearCooldowns(fake);
				state.cooldownsDisabled = cooldownsWere;
				data.aura = null;
			}
			log("trialtest done");
			// entities need their chunk loaded a while before lookups see them: force it, Blood's part runs 40 ticks later
			int cx = (int) Math.floor(Arena.HALL_ARRIVAL.x) >> 4, cz = (int) Math.floor(Arena.HALL_ARRIVAL.z + 4) >> 4;
			arena.setChunkForced(cx, cz, true);
			com.toast.aurapoints.ability.AuraFx.later(server, 40, () -> blood(server, arena, fake, data, cx, cz));
			return 1;
		})));
	}

	/** Blood's trial alone in the hall: a dummy in your sights stands in for the Lifeline's ally. Runs 40 ticks after the chunk is forced. */
	private static void blood(MinecraftServer server, ServerLevel arena, ServerPlayer fake, AuraPlayerData data, int cx, int cz) {
		AuraState state = AuraState.get(server);
		boolean cooldownsWere = state.cooldownsDisabled;
		state.cooldownsDisabled = true;
		AuraTrial.testPlayer = fake;
		data.aura = AuraType.BLOOD;
		data.rolled.add(AuraType.BLOOD);
		data.owned.clear();
		data.mastery.put(AuraType.BLOOD, 0);
		data.branches.remove(AuraType.BLOOD);
		fake.moveTo(Arena.HALL_ARRIVAL.x, Arena.HALL_ARRIVAL.y, Arena.HALL_ARRIVAL.z, 0, 0);
		var dummy = AuraPoints.TRAINING_DUMMY.get().create(arena);
		dummy.moveTo(Arena.HALL_ARRIVAL.x, Arena.HALL_ARRIVAL.y, Arena.HALL_ARRIVAL.z + 4, 180, 0);
		com.toast.aurapoints.arena.ArenaBuilder.claim(dummy);
		arena.addFreshEntity(dummy);
		fake.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, com.toast.aurapoints.ability.AuraFx.chest(dummy));
		try {
			AuraAbilities.clearCooldowns(fake);
			AuraTrial.start(fake);
			AuraTrial.tickAll(server);
			for (String line : AuraTrial.describe(fake)) log("  Blood item: " + line);
			boolean lifeline = AuraAbilities.tryActivate(fake, Ability.BLOOD_TETHER, server.getTickCount(), CastMode.SNEAK);
			log("Blood trial: Lifeline on a dummy fired " + lifeline + " (must be true), ticked "
					+ AuraTrial.state(fake).stream().anyMatch(l -> l.startsWith("✔ Lifeline")) + " (must be true)");
			AuraTrial.stop(fake, null);
			AuraAbilities.clearCooldowns(fake);
			data.owned.add(Ability.BLOOD_TETHER);
			data.mastery.put(AuraType.BLOOD, 5);
			boolean outside = AuraAbilities.tryActivate(fake, Ability.BLOOD_TETHER, server.getTickCount(), CastMode.SNEAK);
			log("no trial: Lifeline on a dummy fired " + outside + " (must be false: a dummy is no ally)");
		} finally {
			com.toast.aurapoints.ability.Blood.onLogout(fake);
			AuraTrial.stop(fake, null);
			AuraTrial.testPlayer = null;
			AuraAbilities.clearCooldowns(fake);
			data.owned.clear();
			data.aura = null;
			dummy.discard();
			arena.setChunkForced(cx, cz, false);
			state.cooldownsDisabled = cooldownsWere;
		}
		log("trialtest blood done");
	}
}
