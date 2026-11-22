package com.toast.aurapoints;

import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.Mjolnir;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * DEV ONLY (registered by DevTest): /auradev mjsteertest - steering a thrown Mjölnir (ticket #13), run as a worthy player
 * standing in the open. Builds a stone wall 14 blocks east and two still zombies 8 blocks east, then, facing east:
 * A) a plain throw (must hit and come back, never steered);
 * B) throw + Swing key down, aim swept left to right over the zombies, then at the wall (must stop there, pressed), key up (back);
 * C) spin until charged, throw while still spinning (must be steered from the start, thunder once), key up;
 * D) throw + key held past STEER_MAX_TICKS (must let go by itself).
 * Logs [mjsteertest] lines with the hammer's state every 5 ticks.
 */
public final class MjolnirSteerDevTest {

	private MjolnirSteerDevTest() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("mjsteertest").executes(ctx -> {
			ServerPlayer p = ctx.getSource().getPlayerOrException();
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel level = p.serverLevel();
			if (!AuraCommand.isWorthy(p)) {
				log("not worthy: run as an owner");
				return 0;
			}
			double x = Math.floor(p.getX()) + 0.5, y = Math.floor(p.getY()), z = Math.floor(p.getZ()) + 0.5;
			// the wall (east) and two zombies in the sweep
			for (int dz = -4; dz <= 4; dz++) {
				for (int dy = 0; dy < 6; dy++) level.setBlockAndUpdate(BlockPos.containing(x + 14, y + dy, z + dz), Blocks.STONE_BRICKS.defaultBlockState());
			}
			for (int i = 0; i < 2; i++) {
				Zombie zb = EntityType.ZOMBIE.create(level);
				if (zb == null) continue;
				zb.moveTo(x + 8, y, z + (i == 0 ? -2.5 : 2.5), 90, 0);
				zb.setNoAi(true);
				zb.setPersistenceRequired();
				zb.setInvulnerable(true); // keeps them standing for the whole test
				level.addFreshEntity(zb);
			}
			hammer(p);
			aim(p, x, y, z, -90, 0);

			// A) plain throw at the open air between the zombies
			at(server, 20, () -> log("A throw: " + Mjolnir.throwHammer(p) + " (must be true) " + Mjolnir.debugFlight(p)));
			watch(server, p, 25, 60, "A");
			// B) steered: sweep from the left zombie to the right one, then the wall
			at(server, 100, () -> {
				hammer(p);
				aim(p, x, y, z, -90 - 14, 8);
				log("B throw: " + Mjolnir.throwHammer(p) + " steerKey down: " + Mjolnir.steerKey(p, true) + " (true true)");
			});
			for (int t = 0; t < 30; t++) {
				final float yaw = -90 - 14 + t * 28f / 29f;
				at(server, 110 + t, () -> aim(p, x, y, z, yaw, 8));
			}
			at(server, 145, () -> aim(p, x, y, z, -90, -4)); // the wall, chest high
			watch(server, p, 102, 175, "B");
			at(server, 176, () -> log("B steerKey up: " + Mjolnir.steerKey(p, false) + " -> " + Mjolnir.debugFlight(p) + " (back=true, steering=false)"));
			watch(server, p, 180, 215, "B-back");
			// C) spin, charge, throw out of the spin
			at(server, 220, () -> {
				hammer(p);
				aim(p, x, y, z, -90, 8);
				Mjolnir.press(p, server.getTickCount());
			});
			at(server, 222 + Math.max(1, AuraConfig.MJOLNIR_SPIN_TICKS) + 2, () -> {
				log("C spinning " + Mjolnir.isSpinning(p) + ", throw: " + Mjolnir.throwHammer(p) + " -> " + Mjolnir.debugFlight(p) + " (steering=true, charged=true)");
			});
			watch(server, p, 270, 300, "C");
			at(server, 301, () -> Mjolnir.steerKey(p, false));
			watch(server, p, 305, 340, "C-back");
			// D) held too long
			at(server, 345, () -> {
				hammer(p);
				aim(p, x, y, z, -90, 20);
				log("D throw: " + Mjolnir.throwHammer(p) + " steerKey down: " + Mjolnir.steerKey(p, true));
			});
			int end = 345 + AuraConfig.MJOLNIR_STEER_MAX_TICKS;
			at(server, end - 5, () -> log("D just before max: " + Mjolnir.debugFlight(p) + " (steering=true)"));
			at(server, end + 5, () -> log("D just after max: " + Mjolnir.debugFlight(p) + " (steering=false back=true)"));
			watch(server, p, end + 10, end + 60, "D-back");
			at(server, end + 70, () -> {
				log("hammer back in hand: " + p.getMainHandItem().is(AuraPoints.MJOLNIR_ITEM.get()) + ", in air: " + Mjolnir.debugFlight(p) + " (true, null)");
				log("mjsteertest done");
			});
			return 1;
		})));
	}

	/** The hammer in the main hand (taken from the inventory if it's there, else a new one). */
	private static void hammer(ServerPlayer p) {
		if (p.getMainHandItem().is(AuraPoints.MJOLNIR_ITEM.get())) return;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(AuraPoints.MJOLNIR_ITEM.get())) {
				ItemStack s = inv.removeItemNoUpdate(i);
				inv.setItem(inv.selected, s);
				return;
			}
		}
		inv.setItem(inv.selected, new ItemStack(AuraPoints.MJOLNIR_ITEM.get()));
	}

	private static void aim(ServerPlayer p, double x, double y, double z, float yaw, float pitch) {
		p.connection.teleport(x, y, z, yaw, pitch);
	}

	private static void at(MinecraftServer server, int tick, Runnable r) {
		AuraFx.later(server, tick, r);
	}

	private static void watch(MinecraftServer server, ServerPlayer p, int from, int to, String what) {
		for (int t = from; t <= to; t += 5) {
			final int tt = t;
			at(server, t, () -> log(what + " +" + tt + ": " + Mjolnir.debugFlight(p)));
		}
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[mjsteertest] {}", what);
	}
}
