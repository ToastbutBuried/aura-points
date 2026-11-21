package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.ability.Chains;
import com.toast.aurapoints.arena.Arena;
import com.toast.aurapoints.arena.ComboTrainer;
import com.toast.aurapoints.arena.TrainerCards;
import com.toast.aurapoints.arena.TrainingHall;
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
 * DEV ONLY (registered by DevTest): /auradev trainertest - the combo trainer (5.37). A fake Fire player at Mastery V in the
 * Training Hall with cooldowns on: the readout names a cast, a reaction and a chain and counts the combo; today's three
 * cards are the same twice; feeding each card's events finishes it and pays; cards don't count with cooldowns off;
 * the rotation score maths.
 */
public final class TrainerDevTest {

	private TrainerDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[trainertest] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("trainertest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel arena = Arena.level(server);
			if (arena == null) {
				log("no arena dimension (must not happen)");
				return 0;
			}
			AuraState state = AuraState.get(server);
			boolean cooldownsWere = state.cooldownsDisabled;
			ServerPlayer fake = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c637"), "TrainerTest"));
			AuraPlayerData data = state.getOrCreate(fake.getUUID());
			data.aura = AuraType.FIRE;
			data.rolled.add(AuraType.FIRE);
			data.mastery.put(AuraType.FIRE, 5);
			data.branches.remove(AuraType.FIRE);
			data.trainingCooldowns = true;
			data.trainerDay = Long.MIN_VALUE;
			Chains.forget(fake.getUUID());
			state.cooldownsDisabled = false;
			try {
				fake.moveTo(Arena.HALL_ARRIVAL.x, Arena.HALL_ARRIVAL.y, Arena.HALL_ARRIVAL.z, 0, 0);
				log("in the hall " + Arena.inTraining(fake) + " (must be true), free cooldowns " + Arena.freeCooldowns(fake) + " (must be false)");

				// the readout
				ComboTrainer.onCast(fake, Ability.FIREBALL, CastMode.SNEAK);
				ComboTrainer.onReaction(fake, "Melt", "✹", 0xFF9A5A);
				List<Chains.Link> links = Chains.links(data);
				if (!links.isEmpty()) ComboTrainer.onChain(fake, links.get(0), 2);
				String text = ComboTrainer.devText(fake);
				log("readout: " + (text == null ? "null" : text.replace("\n", " | ")));
				log("readout names the cast " + (text != null && text.contains("Spread Shot")) + ", the reaction " + (text != null && text.contains("Melt"))
						+ ", the chain " + (text != null && (links.isEmpty() || text.contains(links.get(0).name()))) + " (must be true true true)");
				log("combo " + ComboTrainer.combo(fake) + " (must be " + (links.isEmpty() ? 2 : 3) + ")");

				// the cards
				List<TrainerCards.Card> cards = TrainerCards.cards(fake);
				log("cards " + cards.size() + " (must be 3), same roll twice " + cards.equals(TrainerCards.cards(fake)) + " (must be true)");
				for (TrainerCards.Card c : cards) log("card " + c.kind() + ": " + c.text());
				data.trainerDone = 0;
				// cooldowns off for everyone: nothing counts
				state.cooldownsDisabled = true;
				feed(fake, cards);
				log("with cooldowns off: done " + Integer.bitCount(data.trainerDone) + " (must be 0)");
				state.cooldownsDisabled = false;
				int before = AuraCurrency.get(fake);
				feed(fake, cards);
				int paid = AuraCurrency.get(fake) - before;
				log("fed every card: done " + Integer.bitCount(data.trainerDone) + " (must be 3), paid " + paid + " (must be "
						+ (3 * AuraConfig.TRAINING_CARD_AURA + AuraConfig.TRAINING_CARDS_BONUS) + ")");
				for (int i = 0; i < cards.size(); i++) log("card " + (i + 1) + " shows " + TrainerCards.progressText(fake, i) + " (must be done)");

				double score = TrainingHall.devRotationScore(500f, 2, 1, 3);
				double want = 500 + 2 * AuraConfig.TRAINING_ROTATION_CHAIN_POINTS + AuraConfig.TRAINING_ROTATION_REACTION_POINTS + 3 * AuraConfig.TRAINING_ROTATION_VARIETY_POINTS;
				log("rotation score " + score + " (must be " + want + ")");
			} finally {
				Chains.forget(fake.getUUID());
				AuraAbilities.clearCooldowns(fake);
				state.cooldownsDisabled = cooldownsWere;
				data.aura = null;
			}
			log("trainertest done");
			return 1;
		})));
	}

	/** Every card's events, as many as it asks for. */
	private static void feed(ServerPlayer fake, List<TrainerCards.Card> cards) {
		for (TrainerCards.Card c : cards) {
			switch (c.kind()) {
				case CHAIN -> { for (int k = 0; k < c.count(); k++) ComboTrainer.onChain(fake, c.link(), 2); }
				case CAST -> { for (int k = 0; k < c.count(); k++) ComboTrainer.onCast(fake, c.ability(), c.mode()); }
				case COMBO -> {
					// steps under the gap apart: fed in one tick, each counts one more
					for (int k = 0; k < c.count() + 1; k++) ComboTrainer.onReaction(fake, "Melt", "✹", 0xFF9A5A);
				}
				case DAMAGE -> TrainerCards.onDamage(fake, c.count());
			}
		}
	}
}
