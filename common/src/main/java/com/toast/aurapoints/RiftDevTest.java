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
 * DEV ONLY (registered by DevTest): /auradev rifttest - Aura Rifts (5.41; Relic Trials 5.46). Opens a Fire gate, a fake
 * player (made in the rift dimension: fakes don't travel on NeoForge) enters, the realm is built with four different
 * trials, each trial is solved (shards out), the shards go on the altar, the ritual runs, the guardian wakes and goes
 * down, the run pays Aura and the first-clear relic, shards are taken back on the way out. Then a knockout and a /tp out.
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
			log("gate open " + opened + " " + Rifts.devTearOpen() + " (must be true true)");
			Rifts.devEnter(server, fake);
			String kinds = Rifts.devKinds(fake);
			log("entered: " + Rifts.devState(fake) + " (must be trials 0/4), kinds " + kinds + " (must be 4 different)");
			var hub = Rifts.devHub(fake);
			log("realm built: hub floor " + !rift.getBlockState(hub).isAir() + ", focus " + !rift.getBlockState(hub.above()).isAir()
					+ ", north island " + !rift.getBlockState(hub.offset(0, 0, -48)).isAir() + " (must be true true true)");
			// an empty-handed pedestal click is taken (and does nothing)
			log("empty pedestal click taken " + Rifts.onUse(fake, hub.offset(0, 2, -6)) + " (must be true): " + Rifts.devState(fake) + " (must be placed 0)");
			for (int i = 0; i < 4; i++) Rifts.devSolve(fake, i);
			log("all solved: " + Rifts.devState(fake) + " (must be trials 4/4), shards in the world " + Rifts.devShardItemsInWorld(fake) + " (must be 4), kinds " + Rifts.devKinds(fake));
			Rifts.devPlaceAll(fake);
			log("shards set: " + Rifts.devState(fake) + " (must be placed 4, ritual 0), shards in the world " + Rifts.devShardItemsInWorld(fake) + " (must be 0)");
			Rifts.devFinishRitual(fake);
			var g = Rifts.devGuardian(fake);
			log("ritual done: " + Rifts.devState(fake) + " (must be boss true), guardian " + (g != null && g.isAlive()) + " (must be true) hp " + (g == null ? 0 : g.getMaxHealth()));
			// a shard and the fork in the pocket: both must be gone after the way out
			fake.getInventory().add(new net.minecraft.world.item.ItemStack(AuraPoints.TUNING_FORK.get()));
			if (g != null) g.kill();
			AuraFx.later(server, 25, () -> {
				log("guardian down: " + Rifts.devState(fake) + " (must be done true won true)");
				int paid = AuraCurrency.get(fake) - aura0;
				int want = 4 * AuraConfig.RIFT_ROOM_AURA + AuraConfig.RIFT_BOSS_AURA;
				int relics = 0;
				for (int k = 0; k < fake.getInventory().getContainerSize(); k++) if (Relics.auraOf(fake.getInventory().getItem(k)) == AuraType.FIRE) relics++;
				log("won: paid " + paid + " (must be " + want + "), Fire relics " + relics + " (must be 1), first clear saved " + data.riftAuras.contains(AuraType.FIRE) + " (must be true)");
				AuraFx.later(server, 180, () -> {
					boolean fork = fake.getInventory().contains(new net.minecraft.world.item.ItemStack(AuraPoints.TUNING_FORK.get()));
					log("after the end: " + Rifts.devState(fake) + " (must be no run), gate open " + Rifts.devTearOpen() + " (must be false), fork kept " + fork + " (must be false)");
					// a knockout: a new run, a lethal hit
					Rifts.open(server, AuraType.FROST, null, new Vec3(0.5, 120, 0.5));
					Rifts.devEnter(server, fake);
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
			return 1;
		})));
	}
}
