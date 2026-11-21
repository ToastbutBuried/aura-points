package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Reactions;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import com.toast.aurapoints.weather.StormType;
import com.toast.aurapoints.weather.Storms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;

import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev stormtest - local storms (5.39). A Thunderhead on a fake Thunder player:
 * the damage and cooldown boosts for the storm's aura (none for another aura, none outside), the CHARGED mark on a
 * zombie inside, a save and load of the storm, drift, and the end of the storm.
 */
public final class StormDevTest {

	private StormDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[stormtest] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("stormtest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			ServerPlayer fake = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c639"), "StormTest"));
			AuraPlayerData data = AuraState.get(server).getOrCreate(fake.getUUID());
			data.aura = AuraType.THUNDER;
			boolean enabledWas = AuraConfig.STORM_ENABLED;
			AuraConfig.STORM_ENABLED = true;
			double x = 3000, z = 3000;
			fake.moveTo(x, 200, z, 0, 0);
			Storms.clear(server);
			Zombie zombie = null;
			try {
				var storm = Storms.spawn(level, StormType.THUNDERHEAD, x, z);
				// a storm fades in: pretend it is in full swing
				long now = level.getGameTime();
				log("spawned: storms " + Storms.all().size() + " (must be 1), strength now " + storm.strength(now) + ", in "
						+ (AuraConfig.STORM_FADE_SECONDS * 20) + " ticks " + storm.strength(now + AuraConfig.STORM_FADE_SECONDS * 20L) + " (must be 1.0)");
				var full = new Storms.Storm(storm.id, storm.type, x, z, 0, 0, storm.radius, now - 100000, now + 100000);
				Storms.clear(server);
				Storms.devAdd(full);
				float dmg = Storms.damage(fake, 10f);
				int cd = Storms.cooldown(fake, 200);
				log("Thunder inside: damage 10 -> " + dmg + " (must be " + (10 * (1 + AuraConfig.STORM_DAMAGE_BONUS)) + "), cooldown 200 -> " + cd
						+ " (must be " + Math.round(200 * (1 - AuraConfig.STORM_COOLDOWN_CUT)) + ")");
				data.aura = AuraType.FIRE;
				log("Fire inside: damage 10 -> " + Storms.damage(fake, 10f) + " (must be 10.0)");
				data.aura = AuraType.THUNDER;
				fake.moveTo(x + full.radius + 50, 200, z, 0, 0);
				log("Thunder outside: damage 10 -> " + Storms.damage(fake, 10f) + " (must be 10.0)");

				level.setChunkForced(((int) x) >> 4, ((int) z) >> 4, true);
				zombie = EntityType.ZOMBIE.create(level);
				zombie.moveTo(x, 200, z, 0, 0);
				zombie.setNoAi(true);
				zombie.setNoGravity(true);
				level.addFreshEntity(zombie);
				log("zombie inside: CHARGED " + Reactions.marked(zombie, Reactions.Mark.CHARGED) + " (must be true), WET " + Reactions.marked(zombie, Reactions.Mark.WET)
						+ " (must be false)");

				var tag = Storms.write();
				Storms.clear(server);
				Storms.read(tag);
				log("saved and loaded: storms " + Storms.all().size() + " (must be 1), type " + Storms.all().get(0).type + " (must be THUNDERHEAD)");

				var moving = new Storms.Storm(99, StormType.GALE, 0, 0, 2, 0, 100, now - 100000, now + 21);
				Storms.devAdd(moving);
				log("drift + end: will run 2 storm seconds, gale x 0 -> 4, then gone");
			} finally {
				AuraConfig.STORM_ENABLED = enabledWas;
				data.aura = null;
				if (zombie != null) zombie.discard();
				level.setChunkForced(((int) x) >> 4, ((int) z) >> 4, false);
			}
			// two storm ticks later: the gale moved and ended
			com.toast.aurapoints.ability.AuraFx.later(server, 45, () -> {
				var g = Storms.all().stream().filter(s -> s.id == 99).findFirst();
				log("after 2 s: gale " + (g.isPresent() ? "x " + g.get().x : "gone") + " (must be gone), thunderhead still there " + Storms.all().stream().anyMatch(s -> s.type == StormType.THUNDERHEAD) + " (must be true)");
				Storms.clear(server);
				log("stormtest done");
			});
			return 1;
		})));
	}
}
