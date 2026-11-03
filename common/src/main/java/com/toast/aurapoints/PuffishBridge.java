package com.toast.aurapoints;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * We do not compile against Puffish's Skills at all - it is data driven and
 * exposes everything through the /puffish_skills server command. Running that
 * command from a silent op-level source is the integration route that needs
 * no hard Java dependency. Every method returns whether the command ran, so
 * callers can log or tell the player instead of failing silently.
 *
 * Commands used (Puffish Skills 0.19.x):
 *   points add / category lock / category unlock / category erase / skills reset
 * If a command's syntax differs on your build, only this file needs changing.
 */
public final class PuffishBridge {

	private PuffishBridge() {
	}

	public static boolean addPoints(ServerPlayer player, String category, int amount) {
		if (amount <= 0) return true;
		return run(player, "points add %s %s %d", player, category, amount);
	}

	/** Locks every skill in the category again and refunds the spent points. */
	public static boolean resetSkills(ServerPlayer player, String category) {
		return run(player, "skills reset %s %s", player, category);
	}

	public static boolean lockCategory(ServerPlayer player, String category) {
		return run(player, "category lock %s %s", player, category);
	}

	public static boolean unlockCategory(ServerPlayer player, String category) {
		return run(player, "category unlock %s %s", player, category);
	}

	/** Deletes all of the player's progress in the category (skills, points, experience). */
	public static boolean eraseCategory(ServerPlayer player, String category) {
		return run(player, "category erase %s %s", player, category);
	}

	private static boolean run(ServerPlayer player, String format, Object... args) {
		MinecraftServer server = player.getServer();
		if (server == null) return false;

		Object[] formatted = args.clone();
		for (int i = 0; i < formatted.length; i++) {
			if (formatted[i] instanceof ServerPlayer p) formatted[i] = p.getGameProfile().getName();
		}
		String command = "puffish_skills " + String.format(format, formatted);

		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		try {
			int result = server.getCommands().getDispatcher().execute(command, source);
			return result >= 0;
		} catch (CommandSyntaxException e) {
			AuraPoints.LOGGER.warn("Failed to run Puffish Skills command '{}': {}", command, e.getMessage());
			return false;
		} catch (Exception e) {
			AuraPoints.LOGGER.error("Unexpected error running Puffish Skills command '{}'", command, e);
			return false;
		}
	}
}
