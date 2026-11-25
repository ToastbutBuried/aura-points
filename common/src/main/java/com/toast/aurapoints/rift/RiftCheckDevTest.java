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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/** DEV ONLY (registered by DevTest): /auradev riftcheck - what the code checks found in the rift: lent wings, fallen players' shards, knocked out players' Keystones, a shard broken by a charge. */
public final class RiftCheckDevTest {

	private RiftCheckDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[riftcheck] {}", s);
	}

	private static ServerPlayer fake(MinecraftServer server, ServerLevel rift, int n) {
		ServerPlayer p = FakePlayerFactory.get(rift, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c65" + n), "RiftCheck" + n));
		AuraState.get(server).getOrCreate(p.getUUID()).aura = AuraType.WATER;
		p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		p.getInventory().clearContent();
		Rifts.TEST_PLAYERS.put(p.getUUID(), p);
		return p;
	}

	private static Rifts.Run realm(MinecraftServer server, AuraType aura, int plate, List<Trials.Kind> kinds, ServerPlayer... who) {
		Rifts.DEV_KINDS = kinds;
		Rifts.open(server, aura, null, new Vec3(0.5, 120, 0.5));
		for (ServerPlayer p : who) {
			Rifts.TEST_PLAYERS.put(p.getUUID(), p);
			Rifts.devEnter(server, p);
		}
		Rifts.devVote(who[0], plate);
		Rifts.DEV_KINDS = null;
		return Rifts.runOf(who[0]);
	}

	private static int chests(ServerPlayer p) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(Items.DIAMOND_CHESTPLATE)) n++;
		return n;
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("riftcheck").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel rift = Rifts.level(server);
			ServerPlayer a = fake(server, rift, 1), b = fake(server, rift, 2);
			List<Trials.Kind> kinds = List.of(Trials.Kind.RINGS, Trials.Kind.DIG, Trials.Kind.GLYPH, Trials.Kind.BELLS);
			Rifts.Run run = realm(server, AuraType.WIND, 4, kinds, a, b);
			log("gate: relic in the curtain is in the world (label " + Rifts.devLabelShown(server) + ") " + Rifts.devCoreShown(server) + " (must be true)");
			// lent wings hidden in the crafting grid while the flight ends
			TrialRings r = (TrialRings) run.trials[0];
			a.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
			r.devLend(a);
			ItemStack wings = a.getItemBySlot(EquipmentSlot.CHEST).copy();
			a.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
			TrialRings.purgeLent(a);
			a.getInventory().add(wings);
			TrialRings.purgeLent(a);
			log("wings kept through the landing, then taken: chestplates " + chests(a) + " (must be 1)");
			a.getInventory().clearContent();
			bells(rift, (TrialBells) run.trials[3], a, b);
			// the crafting grid
			r.devLend(a);
			a.inventoryMenu.getCraftSlots().setItem(0, a.getItemBySlot(EquipmentSlot.CHEST).copy());
			a.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
			TrialRings.purgeLent(a);
			log("wings in the crafting grid: left there " + !a.inventoryMenu.getCraftSlots().isEmpty() + " (must be false)");
			a.getInventory().clearContent();
			// Hardcore: the fallen one holds a shard
			Rifts.devSolve(a, 1);
			for (var it : rift.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(Vec3.atCenterOf(run.hub()), Vec3.atCenterOf(run.hub())).inflate(500), e -> e.getTags().contains(Rifts.SHARD_TAG))) it.discard();
			a.getInventory().add(com.toast.aurapoints.item.RelicShardItem.make(run.aura, run.id, 1));
			Vec3 sp = run.spawn();
			b.moveTo(sp.x, sp.y, sp.z);
			Rifts.catchLethal(a, 9999f);
			log("Hardcore: fell with a shard, " + Rifts.devState(b) + " (must be down 1, done false)");
			AuraFx.later(server, 90, () -> {
				log("Hardcore: the fallen one's shard is back in the realm " + (Rifts.devShardItemsInWorld(b) == 1 && !Rifts.holdsShard(a, run, 1)) + " (must be true)");
				Rifts.leave(b);
				AuraFx.later(server, 10, () -> {
					log("Hardcore: the last one standing left, run over " + run.done + " (must be true)");
					Rifts.leave(a);
					Rifts.clearAll();
					keystones(server, rift);
				});
			});
			return 1;
		})));
	}

	/** Someone comes into the hall in the middle of the song. */
	private static void bells(ServerLevel rift, TrialBells t, ServerPlayer a, ServerPlayer b) {
		t.dev = true;
		t.begin(rift, List.of(a));
		long now = rift.getGameTime() + 5;
		for (int k = 0; k < 4; k++) t.tick(rift, List.of(a), now++);
		boolean alone = t.hears.getOrDefault(a.getUUID(), 0) == 15;
		for (int k = 0; k < 6; k++) t.tick(rift, List.of(a, b), now++);
		log("bells: alone hears all " + alone + " (must be true), then both, all four between them "
				+ ((t.hears.getOrDefault(a.getUUID(), 0) | t.hears.getOrDefault(b.getUUID(), 0)) == 15 && t.hears.getOrDefault(b.getUUID(), 0) != 0) + " (must be true)");
	}

	/** Two players: the sight passes in stage 3, then the hall is solved; nobody stays up on a perch. */
	private static void echoes(ServerLevel rift, TrialEchoes t, ServerPlayer a, ServerPlayer b) {
		List<ServerPlayer> both = List.of(a, b);
		Vec3 cp = t.checkpoint(0);
		a.moveTo(cp.x, cp.y, cp.z);
		b.moveTo(cp.x, cp.y, cp.z);
		t.begin(rift, both);
		long now = rift.getGameTime();
		t.stageCleared(rift, both, now);
		ServerPlayer old = a.getUUID().equals(t.seer) ? a : b, walker = old == a ? b : a;
		boolean up = t.onPerch(old);
		t.stageCleared(rift, both, now);
		log("echoes: the Seer stands on the stage 2 perch " + up + " (must be true), sight passed " + walker.getUUID().equals(t.seer) + " (must be true), new one on the perch "
				+ t.onPerch(walker) + " (must be true), old one down " + (old.getY() < Rifts.FLOOR + 5) + " (must be true)");
		t.stageCleared(rift, both, now);
		log("echoes: solved " + t.solved + " (must be true), the Seer is down by the gate " + (walker.getY() < Rifts.FLOOR + 5 && t.af(walker.position()) > 35) + " (must be true)");
	}

	private static void keystones(MinecraftServer server, ServerLevel rift) {
		ServerPlayer a = fake(server, rift, 1), b = fake(server, rift, 2);
		Rifts.Run run = realm(server, AuraType.FROST, 1, List.of(Trials.Kind.DIG, Trials.Kind.ECHOES, Trials.Kind.GLYPH, Trials.Kind.BELLS), a, b);
		echoes(rift, (TrialEchoes) run.trials[1], a, b);
		PortalRepair rep = run.trials[0].repair;
		Vec3 sp = run.spawn();
		a.moveTo(sp.x, sp.y, sp.z);
		b.moveTo(sp.x, sp.y, sp.z);
		for (var pos : List.copyOf(rep.caches)) rep.use(rift, a, pos);
		Rifts.catchLethal(a, 9999f);
		int[] most = {0};
		for (int t = 1; t <= 215; t++) AuraFx.later(server, t, () -> most[0] = Math.max(most[0], rep.caches.size()));
		AuraFx.later(server, 216, () -> {
			log("knocked out with all Keystones: pots refilled " + most[0] + " (must be 0)");
			// the wings' map must not outlive the server
			a.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
			a.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
			TrialRings r = new TrialRings(run, 0);
			r.devLend(a);
			Rifts.leave(b);
			Rifts.clearAll();
			a.getInventory().clearContent(); // the same player in another world
			TrialRings.purgeLent(a);
			log("another world after a stop in mid-flight: chestplates " + chests(a) + " (must be 0)");
			a.getInventory().clearContent();
			warden(server, rift);
		});
	}

	private static void warden(MinecraftServer server, ServerLevel rift) {
		ServerPlayer a = fake(server, rift, 1);
		realm(server, AuraType.SHADOW, 1, null, a);
		for (int i = 0; i < 4; i++) Rifts.devSolve(a, i);
		Rifts.devPlaceAll(a);
		Rifts.devFinishRitual(a);
		RelicWarden w = (RelicWarden) Rifts.devGuardian(a);
		w.aspects.off = true;
		w.devPhase(RelicWarden.SHIELDED, 0.6f);
		w.shards.broken = 3;
		w.shards.devKind(rift, WardenShards.GREEN);
		w.shards.hits = 1;
		Vec3 at = w.shards.spot();
		Vec3 dir = new Vec3(1, 0, 0);
		w.moveTo(at.x, w.getY(), at.z);
		// blocked on the very tick it runs into the shard
		var wall = net.minecraft.core.BlockPos.containing(w.position().add(1.4, 1, 0));
		var was = rift.getBlockState(wall);
		rift.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
		w.devCharge(dir, 5);
		w.devAiStep();
		rift.setBlock(wall, was, 3);
		for (int k = 0; k < 60; k++) w.devAiStep();
		log("last shard broken by a blocked charge: phase " + w.phase() + " (must be 3), Overdrive damage " + (w.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) == 16) + " (must be true)");
		// a shard broken by a player while it charges: no charge pose left behind
		w.devPhase(RelicWarden.SHIELDED, 0.6f);
		w.devCharge(dir, 10);
		w.shardsDown(rift, false);
		log("shard broken in a charge: pose " + w.pose() + " (must be 0)");
		Rifts.leave(a);
		Rifts.clearAll();
		Rifts.TEST_PLAYERS.clear();
		log("riftcheck done");
	}
}
