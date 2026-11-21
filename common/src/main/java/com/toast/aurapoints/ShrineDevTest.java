package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.Allies;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import com.toast.aurapoints.shrine.Shrines;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev shrinetest - territory shrines (5.42). A ruined shrine is placed far out, a repair
 * without the materials is refused, with them the shrine stands (core = lodestone), attuning gives the team the shrine with
 * its aura at full power, the team's damage bonus (more for the shrine's aura, none for a rival), a rival drains it to neutral,
 * and the shrines survive a save and load.
 */
public final class ShrineDevTest {

	private ShrineDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[shrinetest] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("shrinetest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			ServerPlayer owner = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c642"), "ShrineOwner"));
			ServerPlayer rival = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c643"), "ShrineRival"));
			AuraPlayerData od = AuraState.get(server).getOrCreate(owner.getUUID()), rd = AuraState.get(server).getOrCreate(rival.getUUID());
			od.aura = AuraType.FIRE;
			rd.aura = AuraType.WATER;
			owner.getInventory().clearContent();
			BlockPos core = new BlockPos(5000, 120, 5000);
			level.setChunkForced(core.getX() >> 4, core.getZ() >> 4, true);
			owner.moveTo(core.getX() + 0.5, core.getY(), core.getZ() + 2.5, 0, 0);
			rival.moveTo(core.getX() + 0.5, core.getY(), core.getZ() - 2.5, 0, 0);
			Shrines.TEST_PLAYERS.put(owner.getUUID(), owner);
			Shrines.TEST_PLAYERS.put(rival.getUUID(), rival);
			var s = Shrines.place(level, core, true, null);
			try {
				log("placed ruined: core " + level.getBlockState(core).getBlock() + " (must be chiseled stone bricks), ruined " + s.ruined);
				Shrines.devRepair(level, owner, s);
				log("repair without materials: ruined " + s.ruined + " (must be true)");
				owner.getInventory().add(new ItemStack(Items.STONE_BRICKS, 64));
				owner.getInventory().add(new ItemStack(Items.GOLD_INGOT, 16));
				Shrines.devRepair(level, owner, s);
				log("repair with materials: ruined " + s.ruined + " (must be false), core " + level.getBlockState(core).is(Blocks.LODESTONE) + " (must be true)");

				Shrines.devAttune(server, s, owner);
				log("attuned: owned " + s.owned() + " (must be true), aura " + s.aura + " (must be FIRE), power " + s.power + " (must be 100.0), team = owner's "
						+ Allies.teamOf(owner).id().equals(s.team) + " (must be true)");
				float own = Shrines.damage(owner, 10f);
				float riv = Shrines.damage(rival, 10f);
				log("damage near it: owner 10 -> " + own + " (must be " + (float) (10 * (1 + AuraConfig.SHRINE_TEAM_DAMAGE + AuraConfig.SHRINE_AURA_DAMAGE)) + "), rival 10 -> " + riv + " (must be 10.0)");

				int ticks = 0;
				while (s.owned() && ticks++ < 200) Shrines.devDrain(server, s, rival);
				log("rival drained it: owned " + s.owned() + " (must be false) after " + ticks + " drains, team " + s.team + " (must be null)");

				net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
				Shrines.write(tag);
				int before = Shrines.all().size();
				Shrines.read(tag);
				log("saved and loaded: shrines " + Shrines.all().size() + " (must be " + before + ")");
			} finally {
				Shrines.TEST_PLAYERS.clear();
				od.aura = null;
				rd.aura = null;
				owner.getInventory().clearContent();
				level.setChunkForced(core.getX() >> 4, core.getZ() >> 4, false);
			}
			log("shrinetest done");
			return 1;
		})));
	}
}
