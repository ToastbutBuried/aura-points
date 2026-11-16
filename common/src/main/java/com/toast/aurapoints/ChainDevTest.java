package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Branch;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.ability.Chains;
import com.toast.aurapoints.ability.Mastery;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;

import java.util.Locale;
import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev chaintest - ability chains (5.24). A fake player per aura runs every link
 * of aurapoints-chains.json through the real ability-used hook, then the rules (order, window, Mastery gate, hit cap,
 * streak), a real hit on a golem (bonus + effect through the damage event) and a real Dash then Vanish with cooldowns
 * on (the refund). Logs [chaintest] lines with "(must be ...)".
 */
public final class ChainDevTest {

	private ChainDevTest() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("chaintest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			AuraState state = AuraState.get(server);
			boolean cooldownsBefore = state.cooldownsDisabled;
			state.cooldownsDisabled = true;

			// 1. every link of the table
			int n = 0, links = 0;
			for (var entry : Chains.all().entrySet()) {
				String[] key = entry.getKey().split(":");
				AuraType aura = AuraType.byId(key[0]);
				if (aura == null) {
					log("table key " + entry.getKey() + ": no such aura (must not happen)");
					continue;
				}
				ServerPlayer fake = fake(level, 40 + n++, "Chain" + entry.getKey().replace(':', '_'));
				AuraPlayerData data = setUp(state, fake, aura);
				if (key.length > 1) data.branches.put(aura, Branch.valueOf(key[1].toUpperCase(Locale.ROOT)));
				for (Chains.Link link : entry.getValue()) {
					links++;
					Chains.forget(fake.getUUID());
					use(fake, link.from(), link.fromMode());
					use(fake, link.to(), link.toMode());
					log(entry.getKey() + " " + link.from() + " -> " + link.to() + ": chain '" + Chains.active(fake) + "' (must be '" + link.name() + "')");
				}
				data.branches.remove(aura);
			}
			log("links tested: " + links + " (must be 24 with the default table)");

			// 2. the rules, on a Fire player
			ServerPlayer fire = fake(level, 70, "ChainRules");
			AuraPlayerData fireData = setUp(state, fire, AuraType.FIRE);
			use(fire, Ability.FIREBALL, null);
			use(fire, Ability.FIRE_BENDING, null);
			log("wrong order (Fireball, then Fire Bending): chain " + Chains.active(fire) + " (must be null)");
			Chains.forget(fire.getUUID());
			use(fire, Ability.FIRE_BENDING, null);
			use(fire, Ability.FIREBALL, null);
			use(fire, Ability.HEAT_WAVE, null);
			log("three in a row: chain '" + Chains.active(fire) + "' streak " + Chains.streak(fire) + " (must be 'Backdraft' 3)");
			Chains.forget(fire.getUUID());
			int gate = AuraConfig.CHAINS_MASTERY;
			AuraConfig.CHAINS_MASTERY = 3;
			fireData.mastery.put(AuraType.FIRE, 1);
			use(fire, Ability.FIRE_BENDING, null);
			use(fire, Ability.FIREBALL, null);
			log("Mastery I with the gate at III: chain " + Chains.active(fire) + " (must be null)");
			AuraConfig.CHAINS_MASTERY = gate;
			fireData.mastery.put(AuraType.FIRE, 5);
			Chains.forget(fire.getUUID());
			boolean on = AuraConfig.CHAINS_ENABLED;
			AuraConfig.CHAINS_ENABLED = false;
			use(fire, Ability.FIRE_BENDING, null);
			use(fire, Ability.FIREBALL, null);
			log("switched off: chain " + Chains.active(fire) + " lore lines " + Chains.describe(Ability.FIREBALL, AuraType.FIRE).size() + " (must be null 0)");
			AuraConfig.CHAINS_ENABLED = on;
			log("lore of Fireball: " + Chains.describe(Ability.FIREBALL, AuraType.FIRE) + " (must name Stoked)");
			log("lore of Ice Spike: " + Chains.describe(Ability.ICE_SPIKE, AuraType.FROST) + " (must name the sneak cast first)");
			log("lore of Null Orb for Shadow: " + Chains.describe(Ability.NULL_ORB, AuraType.SHADOW).size() + " line(s) (must be 1)");

			// an ability no key press reports (Wind's launch): its cooldown start is the use; one a press did report: that use ending
			Chains.forget(fire.getUUID());
			AuraHooks.abilityUsed(fire, Ability.FIRE_BENDING);
			AuraHooks.abilityUsed(fire, Ability.FIREBALL);
			log("two cooldown starts without presses: chain '" + Chains.active(fire) + "' (must be 'Stoked')");
			Chains.forget(fire.getUUID());
			use(fire, Ability.FIRE_BENDING, null);
			use(fire, Ability.HEAT_WAVE, null);
			AuraHooks.abilityUsed(fire, Ability.FIRE_BENDING);
			use(fire, Ability.FIREBALL, null);
			log("Fire Bending, Heat Wave, Fire Bending's late cooldown start, Fireball: chain " + Chains.active(fire) + " (must be null)");
			Chains.forget(fire.getUUID());

			// 3. a real hit: Earth's Skyfall on a golem, through the damage event
			ServerPlayer earth = fake(level, 71, "ChainHit");
			setUp(state, earth, AuraType.EARTH);
			var spawn = level.getSharedSpawnPos();
			IronGolem golem = EntityType.IRON_GOLEM.create(level);
			golem.moveTo(spawn.getX() + 3.5, spawn.getY() + 1, spawn.getZ() + 0.5, 0, 0);
			golem.setNoAi(true);
			level.addFreshEntity(golem);
			float plain = hit(earth, golem, 10f);
			use(earth, Ability.PILLAR_LIFT, null);
			use(earth, Ability.BOULDER_TOSS, null);
			float chained = hit(earth, golem, 10f);
			log(String.format(Locale.ROOT, "Skyfall hit: %.2f against %.2f plain = x%.2f (must be x1.35), slowed %s (must be true)",
					chained, plain, chained / plain, golem.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)));
			for (int i = 1; i < AuraConfig.CHAINS_MAX_HITS; i++) hit(earth, golem, 1f);
			float after = hit(earth, golem, 10f);
			log(String.format(Locale.ROOT, "hit %d of the chain: %.2f (must be %.2f, the cap is %d hits)", AuraConfig.CHAINS_MAX_HITS + 1, after, plain, AuraConfig.CHAINS_MAX_HITS));
			golem.discard();
			// a link that asks for more than the cap
			log("cap: CHAINS_MAX_BONUS " + AuraConfig.CHAINS_MAX_BONUS + " (links above it are cut to it)");

			// 4. the window, and a real Dash then Vanish with cooldowns on (ticks apart)
			ServerPlayer shadow = fake(level, 72, "ChainShadow");
			setUp(state, shadow, AuraType.SHADOW);
			use(fire, Ability.FIRE_BENDING, null);
			// a refund that waits: the Thunderbolt's cooldown only starts when its combo ends
			ServerPlayer thunder = fake(level, 73, "ChainThunder");
			setUp(state, thunder, AuraType.THUNDER);
			AuraFx.later(server, 2, () -> {
				state.cooldownsDisabled = false;
				AuraAbilities.clearCooldowns(thunder);
				use(thunder, Ability.CHIDORI, null);
				use(thunder, Ability.THUNDERBOLT, null);
				AuraFx.later(server, 20, () -> {
					AuraAbilities.startCooldown(thunder, Ability.THUNDERBOLT, 400, server.getTickCount());
					AuraFx.later(server, 3, () -> {
						int full = Mastery.cooldown(thunder, 400);
						log("Arc Return, Thunderbolt's cooldown starting 20 ticks later: " + AuraAbilities.cooldownTicksLeft(thunder, Ability.THUNDERBOLT, server.getTickCount())
								+ " of " + full + " ticks left (must be about " + Math.round(full * 0.75) + ")");
						AuraAbilities.clearCooldowns(thunder);
						state.cooldownsDisabled = true;
					});
				});
			});
			AuraFx.later(server, AuraConfig.CHAINS_WINDOW_TICKS + 5, () -> {
				use(fire, Ability.FIREBALL, null);
				log("Fireball " + (AuraConfig.CHAINS_WINDOW_TICKS + 5) + " ticks after Fire Bending: chain " + Chains.active(fire) + " (must be null)");
				state.cooldownsDisabled = false;
				AuraAbilities.clearCooldowns(shadow);
				boolean dash = AuraAbilities.tryActivate(shadow, Ability.DASH, server.getTickCount());
				AuraFx.later(server, 5, () -> {
					boolean vanish = AuraAbilities.tryActivate(shadow, Ability.VANISH, server.getTickCount());
					log("real Dash " + dash + ", then Vanish " + vanish + ": chain '" + Chains.active(shadow) + "' (must be true true 'Slip Away')");
					AuraFx.later(server, 3, () -> {
						int left = AuraAbilities.cooldownTicksLeft(shadow, Ability.VANISH, server.getTickCount());
						int full = Mastery.cooldown(shadow, Ability.VANISH.cooldownTicks());
						log("Vanish cooldown 3 ticks later: " + left + " of " + full + " ticks (must be about " + Math.round(full * 0.75) + ": 25% back)");
						AuraAbilities.clearCooldowns(shadow);
						state.cooldownsDisabled = cooldownsBefore;
						log("chaintest done");
					});
				});
			});
			return 1;
		})));
	}

	private static ServerPlayer fake(ServerLevel level, int n, String name) {
		ServerPlayer fake = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c1" + n), name));
		var spawn = level.getSharedSpawnPos();
		fake.moveTo(spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, 0, 40);
		return fake;
	}

	private static AuraPlayerData setUp(AuraState state, ServerPlayer fake, AuraType aura) {
		AuraPlayerData data = state.getOrCreate(fake.getUUID());
		data.aura = aura;
		data.rolled.add(aura);
		data.owned.addAll(aura.abilities);
		data.mastery.put(aura, 5);
		data.branches.remove(aura);
		Chains.forget(fake.getUUID());
		return data;
	}

	/** One key press of the ability that fired. */
	private static void use(ServerPlayer fake, Ability ability, CastMode mode) {
		Chains.onPressed(fake, ability, mode);
	}

	/** An ability hit of {@code damage}; returns the health it took. */
	private static float hit(ServerPlayer attacker, IronGolem golem, float damage) {
		golem.invulnerableTime = 0;
		float before = golem.getHealth();
		golem.hurt(attacker.serverLevel().damageSources().playerAttack(attacker), AuraAbilities.vsMob(golem, damage));
		return before - golem.getHealth();
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[chaintest] {}", what);
	}
}
