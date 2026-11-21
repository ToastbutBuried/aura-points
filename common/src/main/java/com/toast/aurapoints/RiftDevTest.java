package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Relics;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.rift.Rifts;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev rifttest - Aura Rifts (5.41). Opens a Fire tear, a fake player (made in the rift
 * dimension: fakes don't travel on NeoForge) enters, the first room fills with mobs, every room is cleared by killing its
 * mobs, the champion goes down, the run pays Aura and the first-clear relic, and everyone goes back. Then a knockout.
 */
public final class RiftDevTest {

	private RiftDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[rifttest] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("rifttest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel rift = Rifts.level(server);
			if (rift == null) {
				log("no rift dimension (must not happen)");
				return 0;
			}
			ServerPlayer fake = FakePlayerFactory.get(rift, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c641"), "RiftTest"));
			Rifts.TEST_PLAYERS.put(fake.getUUID(), fake);
			AuraPlayerData data = AuraState.get(server).getOrCreate(fake.getUUID());
			data.aura = AuraType.WATER;
			data.riftAuras.remove(AuraType.FIRE);
			fake.getInventory().clearContent();
			int aura0 = AuraCurrency.get(fake);
			boolean opened = Rifts.open(server, AuraType.FIRE, null, new Vec3(0.5, 120, 0.5));
			log("tear open " + opened + " " + Rifts.devTearOpen() + " (must be true true)");
			Rifts.devEnter(server, fake);
			log("entered: " + Rifts.devState(fake) + " (must be room 0, not fighting)");
			log("first room started " + Rifts.devStartFirst(fake) + " (must be true): " + Rifts.devState(fake));
			step(server, fake, 0, aura0, data);
			return 1;
		})));
	}

	/** Clears room {@code n} (0..4), checks the run moved on, starts the next. */
	private static void step(MinecraftServer server, ServerPlayer fake, int n, int aura0, AuraPlayerData data) {
		Rifts.devClearRoom(fake);
		AuraFx.later(server, 25, () -> {
			log("after clearing room " + n + ": " + Rifts.devState(fake) + " (must be cleared " + (n + 1) + (n < 4 ? ", room " + (n + 1) + ", not fighting)" : ", done true won true)"));
			if (n < 4) {
				Rifts.devStartNext(fake);
				log("room " + (n + 1) + " started: " + Rifts.devState(fake));
				AuraFx.later(server, 5, () -> step(server, fake, n + 1, aura0, data));
				return;
			}
			int paid = AuraCurrency.get(fake) - aura0;
			int want = 4 * AuraConfig.RIFT_ROOM_AURA + AuraConfig.RIFT_BOSS_AURA;
			int relics = 0;
			for (int k = 0; k < fake.getInventory().getContainerSize(); k++) if (Relics.auraOf(fake.getInventory().getItem(k)) == AuraType.FIRE) relics++;
			log("won: paid " + paid + " (must be " + want + "), Fire relics " + relics + " (must be 1), first clear saved " + data.riftAuras.contains(AuraType.FIRE) + " (must be true)");
			AuraFx.later(server, 180, () -> {
				log("after the end: " + Rifts.devState(fake) + " (must be no run), tear open " + Rifts.devTearOpen() + " (must be false)");
				// a knockout: a new run, a lethal hit
				Rifts.open(server, AuraType.FROST, null, new Vec3(0.5, 120, 0.5));
				Rifts.devEnter(server, fake);
				Rifts.devStartFirst(fake);
				boolean caught = Rifts.catchLethal(fake, 9999f);
				log("lethal hit caught " + caught + " (must be true): " + Rifts.devState(fake) + " (must be down 1, done true: the only player is down)");
				AuraFx.later(server, 120, () -> {
					log("after the fail: " + Rifts.devState(fake) + " (must be no run)");
					// out by /tp or a home command: out of the run, game mode from before back
					Rifts.open(server, AuraType.FROST, null, new Vec3(0.5, 120, 0.5));
					fake.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); // was a spectator from the knockout
					Rifts.devEnter(server, fake);
					fake.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
					Rifts.onChangedDimension(fake, Rifts.KEY, net.minecraft.world.level.Level.OVERWORLD);
					log("teleported out: " + Rifts.devState(fake) + " (must be no run), mode " + fake.gameMode.getGameModeForPlayer().getName()
							+ " (must be survival), return point " + (data.riftReturn != null) + " (must be false)");
					Rifts.clearAll();
					Rifts.TEST_PLAYERS.clear();
					data.aura = null;
					log("rifttest done");
				});
			});
		});
	}
}
