package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Branch;
import com.toast.aurapoints.ability.Chains;
import com.toast.aurapoints.ability.Reactions;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev chainstack - how far a chained ability hit stacks with everything else (5.27).
 * For every aura and branch it takes the link with the biggest bonus and hits a fresh golem with a 4-damage ability hit,
 * through the real damage event, four ways: plain, chained, "stacked" (the target poisoned, burning, under 30% health and
 * carrying every element mark, so every passive, branch bonus, Executioner and a reaction that can apply does) and stacked +
 * chained. Then the same with the stack cap on (CHAINS_STACK_CAP_ENABLED). Not measured: rain/water (Flow), gliding (Gale
 * Rider), PvP matchups and team combos (their own hits, never multiplied by a chain).
 */
public final class ChainStackDevTest {

	private ChainStackDevTest() {
	}

	private static final float BASE = 4f;

	private static void log(String s) {
		AuraPoints.LOGGER.info("[chainstack] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("chainstack").executes(ctx -> {
			ServerLevel level = ctx.getSource().getLevel();
			AuraState state = AuraState.get(level.getServer());
			boolean capWas = AuraConfig.CHAINS_STACK_CAP_ENABLED;
			ServerPlayer fake = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c501"), "ChainStack"));
			var spawn = level.getSharedSpawnPos();
			fake.moveTo(spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, 0, 0);
			Map<String, List<Chains.Link>> table = Chains.all();
			double worst = 0, worstCapped = 0;
			String worstName = "";
			int rows = 0;
			log(String.format(Locale.ROOT, "base %.0f damage per hit; cap %s x%.2f; CHAINS_MAX_BONUS %.2f", BASE, AuraConfig.CHAINS_STACK_CAP_ENABLED ? "on" : "off",
					AuraConfig.CHAINS_STACK_CAP, AuraConfig.CHAINS_MAX_BONUS));
			try {
				for (AuraType aura : AuraType.values()) {
					if (aura == AuraType.RESONANCE) continue;
					List<Branch> setups = new ArrayList<>();
					setups.add(null);
					setups.addAll(Branch.of(aura));
					for (Branch branch : setups) {
						List<Chains.Link> links = branch != null && table.containsKey(aura.id() + ":" + branch.id())
								? table.get(aura.id() + ":" + branch.id()) : table.getOrDefault(aura.id(), List.of());
						Chains.Link best = null;
						for (Chains.Link l : links) if (best == null || l.bonus() > best.bonus()) best = l;
						if (best == null) continue;
						AuraPlayerData data = state.getOrCreate(fake.getUUID());
						data.aura = aura;
						data.rolled.add(aura);
						data.owned.addAll(aura.abilities);
						if (branch != null && branch.abilities() != null) data.owned.addAll(branch.abilities());
						data.mastery.put(aura, 5);
						data.branches.remove(aura);
						if (branch != null) data.branches.put(aura, branch);

						AuraConfig.CHAINS_STACK_CAP_ENABLED = false;
						float plain = hit(level, fake, null, false);
						float chained = hit(level, fake, best, false);
						float stacked = hit(level, fake, null, true);
						float both = hit(level, fake, best, true);
						AuraConfig.CHAINS_STACK_CAP_ENABLED = true;
						float capped = hit(level, fake, best, true);
						AuraConfig.CHAINS_STACK_CAP_ENABLED = false;
						String name = aura.id() + (branch == null ? "" : ":" + branch.id());
						log(String.format(Locale.ROOT, "%-22s link %-16s +%2.0f%% | plain %5.2f chained %5.2f (x%.2f) | stacked %5.2f (x%.2f) | stacked+chained %5.2f (x%.2f) | capped %5.2f (x%.2f)",
								name, best.name(), Math.min(best.bonus(), AuraConfig.CHAINS_MAX_BONUS) * 100, plain, chained, chained / plain, stacked, stacked / plain,
								both, both / plain, capped, capped / plain));
						rows++;
						if (both / plain > worst) {
							worst = both / plain;
							worstCapped = capped / plain;
							worstName = name;
						}
					}
				}
				log(String.format(Locale.ROOT, "%d setups; the most a chained hit stacks to: x%.2f (%s), with the cap on x%.2f (must be <= %.2f + reaction rounding)",
						rows, worst, worstName, worstCapped, Math.max(1.0, AuraConfig.CHAINS_STACK_CAP)));
				log("capped never above the cap " + (worstCapped <= Math.max(1.0, AuraConfig.CHAINS_STACK_CAP) + 0.01) + " (must be true)");
			} finally {
				AuraConfig.CHAINS_STACK_CAP_ENABLED = capWas;
				Chains.forget(fake.getUUID());
				state.getOrCreate(fake.getUUID()).branches.clear();
			}
			var tomeOn = com.toast.aurapoints.config.AuraBalance.entry("CHAINS_STACK_CAP_ENABLED");
			var tomeCap = com.toast.aurapoints.config.AuraBalance.entry("CHAINS_STACK_CAP");
			log("Balance Tome entries: " + (tomeOn != null && tomeCap != null) + " (must be true), group " + (tomeOn == null ? "-" : tomeOn.group())
					+ ", labels '" + (tomeOn == null ? "" : com.toast.aurapoints.editor.BalanceEditor.label(tomeOn)) + "' '" + (tomeCap == null ? "" : com.toast.aurapoints.editor.BalanceEditor.label(tomeCap)) + "'");
			log("chainstack done");
			return 1;
		})));
	}

	/** A fresh golem, the conditions, the chain's two presses (when given), one ability hit of BASE; the health it took. */
	private static float hit(ServerLevel level, ServerPlayer attacker, Chains.Link link, boolean stacked) {
		IronGolem golem = EntityType.IRON_GOLEM.create(level);
		if (golem == null) return 0;
		var spawn = level.getSharedSpawnPos();
		golem.moveTo(spawn.getX() + 3.5, spawn.getY() + 1, spawn.getZ() + 0.5, 0, 0);
		golem.setNoAi(true);
		level.addFreshEntity(golem);
		try {
			if (stacked) {
				golem.setHealth(golem.getMaxHealth() * (AuraConfig.EXECUTIONER_HP_THRESHOLD_FRACTION - 0.02f));
				golem.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
				golem.setRemainingFireTicks(200);
				for (Reactions.Mark m : Reactions.Mark.values()) Reactions.applyMark(golem, m);
			}
			Chains.forget(attacker.getUUID());
			if (link != null) {
				Chains.onPressed(attacker, link.from(), link.fromMode());
				Chains.onPressed(attacker, link.to(), link.toMode());
			}
			golem.invulnerableTime = 0;
			float before = golem.getHealth() + golem.getAbsorptionAmount();
			golem.hurt(level.damageSources().playerAttack(attacker), AuraAbilities.vsMob(golem, BASE));
			return before - golem.getHealth() - golem.getAbsorptionAmount();
		} finally {
			Chains.forget(attacker.getUUID());
			golem.discard();
		}
	}
}
