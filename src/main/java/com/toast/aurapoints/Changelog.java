package com.toast.aurapoints;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * /changelog: a short list of what changed since the player last looked (the version they saw is
 * remembered per player). The entries come from aurapoints-changelog.txt inside the jar. On join,
 * a player who hasn't seen the newest entry gets one clickable line pointing at it.
 */
public final class Changelog {

	private Changelog() {
	}

	/** Shown at most, so a player back after a long break isn't flooded. */
	private static final int MAX_ENTRIES = 5;

	private record Entry(String version, List<String> points) {
	}

	/** Newest first. */
	private static List<Entry> entries;

	private static synchronized List<Entry> entries() {
		if (entries != null) return entries;
		List<Entry> out = new ArrayList<>();
		try (InputStream in = Changelog.class.getResourceAsStream("/aurapoints-changelog.txt")) {
			if (in != null) {
				BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				Entry current = null;
				for (String line; (line = r.readLine()) != null; ) {
					line = line.strip();
					if (line.startsWith("== ")) {
						current = new Entry(line.substring(3).strip(), new ArrayList<>());
						out.add(current);
					} else if (line.startsWith("- ") && current != null) {
						current.points().add(line.substring(2));
					}
				}
			}
		} catch (Exception e) {
			AuraPoints.LOGGER.warn("Couldn't read the changelog", e);
		}
		entries = out;
		return out;
	}

	/** The newest version in the changelog ("" if there is none). */
	public static String newest() {
		List<Entry> all = entries();
		return all.isEmpty() ? "" : all.get(0).version();
	}

	/** -1, 0, 1 for "4.39.2" style versions (missing parts count as 0). */
	static int compare(String a, String b) {
		String[] x = a.split("\\."), y = b.split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
			if (p != q) return Integer.compare(p, q);
		}
		return 0;
	}

	private static int num(String s) {
		try {
			return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("changelog").executes(Changelog::show));
	}

	private static int show(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		List<Entry> all = entries();
		if (all.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("No changelog in this version."));
			return 0;
		}
		if (player == null) {
			for (Entry e : all.subList(0, Math.min(MAX_ENTRIES, all.size()))) ctx.getSource().sendSystemMessage(Component.literal(e.version() + ": " + String.join(" / ", e.points())));
			return 1;
		}
		AuraState state = AuraState.get(player.getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		List<Entry> fresh = new ArrayList<>();
		for (Entry e : all) if (data.changelogSeen.isEmpty() || compare(e.version(), data.changelogSeen) > 0) fresh.add(e);

		player.sendSystemMessage(Component.literal("✦ Aura Points - What's New ✦").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		if (fresh.isEmpty()) {
			player.sendSystemMessage(Component.literal("You're up to date. Latest:").withStyle(ChatFormatting.GRAY));
			print(player, all.get(0));
		} else {
			for (Entry e : fresh.subList(0, Math.min(MAX_ENTRIES, fresh.size()))) print(player, e);
			if (fresh.size() > MAX_ENTRIES) {
				player.sendSystemMessage(Component.literal("...and " + (fresh.size() - MAX_ENTRIES) + " older updates.").withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		data.changelogSeen = all.get(0).version();
		state.markDirty();
		return 1;
	}

	private static void print(ServerPlayer player, Entry e) {
		player.sendSystemMessage(Component.literal(e.version()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		for (String point : e.points()) {
			player.sendSystemMessage(Component.literal(" • ").withStyle(ChatFormatting.DARK_AQUA)
					.append(Component.literal(point).withStyle(ChatFormatting.WHITE)));
		}
	}

	/** On join: one clickable line if there is something they haven't seen. */
	public static void onLogin(ServerPlayer player, AuraPlayerData data) {
		String newest = newest();
		if (newest.isEmpty() || (!data.changelogSeen.isEmpty() && compare(newest, data.changelogSeen) <= 0)) return;
		player.sendSystemMessage(Component.literal("✦ Aura Points was updated to " + newest + " - ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("[see what's new]").withStyle(style -> style
						.withColor(ChatFormatting.AQUA).withUnderlined(true)
						.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/changelog"))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/changelog"))))));
	}
}
