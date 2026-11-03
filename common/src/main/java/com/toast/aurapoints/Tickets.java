package com.toast.aurapoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.toast.aurapoints.shim.fml.loading.FMLPaths;
import com.toast.aurapoints.shim.neoforge.event.ServerChatEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Player tickets: change requests that end up in readable files.
 *
 * /aura ticket <title> <message>  - send a ticket right away (title: one word or "a quoted title")
 * /aura ticket <title>            - ticket mode: your next chat message is the ticket (nobody else sees it)
 * /aura ticket cancel             - leave ticket mode
 * /aura ticket status             - your tickets and where they are
 * /aura tickets ...               - reviewers (config list) and the console: list, view, vote, wip/done/reopen
 *
 * A ticket starts OPEN. The reviewers in config/aurapoints-tickets.json vote on it: a strict majority of
 * all reviewers approves (ACTIVE) or declines (DECLINED) it; a draw leaves it OPEN. ACTIVE -> WIP -> DONE
 * are set by hand. Nothing is deleted. Everything lives in config/aurapoints-tickets/tickets.json, and
 * one readable file per state (open.txt, active.txt, ...) is rewritten on every change.
 */
public final class Tickets {

	private Tickets() {
	}

	public enum State {
		OPEN(ChatFormatting.YELLOW), ACTIVE(ChatFormatting.GREEN), WIP(ChatFormatting.AQUA),
		DONE(ChatFormatting.DARK_GREEN), DECLINED(ChatFormatting.RED);

		final ChatFormatting color;

		State(ChatFormatting color) {
			this.color = color;
		}

		String file() {
			return name().toLowerCase(Locale.ROOT) + ".txt";
		}
	}

	static final class Change {
		State state;
		String by;
		long at;
		String note;
	}

	static final class Vote {
		boolean approve;
		String name;
		long at;
		String note;
	}

	static final class Ticket {
		int id;
		String author;
		String authorUuid;
		String title;
		String body;
		State state = State.OPEN;
		long created;
		String pos;
		/** Reviewer key (lowercase name or uuid, as listed in the config) -> vote; only while OPEN. */
		Map<String, Vote> votes = new LinkedHashMap<>();
		List<Change> history = new ArrayList<>();
		/** The state changed while the author was offline: tell them on join. */
		boolean unseenChange;
	}

	static final class Store {
		int nextId = 1;
		List<Ticket> tickets = new ArrayList<>();
	}

	/** config/aurapoints-tickets.json */
	static final class Config {
		boolean enabled = true;
		int maxTicketsPerWindow = 3;
		int windowMinutes = 60;
		int maxLength = 1500;
		int maxTitleLength = 60;
		List<String> reviewers = new ArrayList<>();
		List<String> blacklist = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String CONFIG_FILE = "aurapoints-tickets.json";
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private static Store store;
	private static Config config;
	/** Players in ticket mode -> the title they gave. No timeout. */
	private static final Map<UUID, String> pendingTitle = new HashMap<>();

	private static Path dir() {
		return FMLPaths.CONFIGDIR.get().resolve("aurapoints-tickets");
	}

	// ---------------------------------------------------------------
	// Config
	// ---------------------------------------------------------------

	private static Config config() {
		if (config == null) reloadConfig();
		return config;
	}

	/** (Re)reads the config, writing it with the defaults if it's missing. Returns problems for /aura reload. */
	public static List<String> reloadConfig() {
		Path path = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
		List<String> report = new ArrayList<>();
		Config loaded = null;
		if (Files.exists(path)) {
			try {
				loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Config.class);
			} catch (IOException | JsonParseException e) {
				AuraPoints.LOGGER.error("Can't read {}", path, e);
				report.add("ERROR " + CONFIG_FILE + ": " + e.getMessage() + " (kept the previous ticket settings)");
				if (config == null) config = new Config();
				return report;
			}
		}
		if (loaded == null) {
			loaded = new Config();
			try {
				Files.writeString(path, GSON.toJson(loaded) + "\n", StandardCharsets.UTF_8);
			} catch (IOException e) {
				AuraPoints.LOGGER.error("Can't write {}", path, e);
			}
		}
		if (loaded.reviewers == null) loaded.reviewers = new ArrayList<>();
		if (loaded.blacklist == null) loaded.blacklist = new ArrayList<>();
		if (loaded.reviewers.isEmpty()) report.add("WARNING " + CONFIG_FILE + ": no reviewers - tickets stay OPEN until the console decides");
		config = loaded;
		return report;
	}

	/** Reviewer keys from the config, lowercase, without duplicates. */
	private static Set<String> reviewers() {
		Set<String> out = new LinkedHashSet<>();
		for (String r : config().reviewers) if (r != null && !r.isBlank()) out.add(r.strip().toLowerCase(Locale.ROOT));
		return out;
	}

	/** The config entry that makes this player a reviewer (lowercase), or null. */
	private static String reviewerKey(ServerPlayer player) {
		String name = player.getGameProfile().getName().toLowerCase(Locale.ROOT);
		String uuid = player.getUUID().toString();
		for (String r : reviewers()) if (r.equals(name) || r.equals(uuid)) return r;
		return null;
	}

	private static boolean blacklisted(ServerPlayer player) {
		String name = player.getGameProfile().getName();
		String uuid = player.getUUID().toString();
		for (String b : config().blacklist) if (b != null && (b.strip().equalsIgnoreCase(name) || b.strip().equalsIgnoreCase(uuid))) return true;
		return false;
	}

	/** Reviewers in game, the server console / rcon. */
	private static boolean canReview(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		return player == null ? src.getEntity() == null && src.hasPermission(4) : reviewerKey(player) != null;
	}

	// ---------------------------------------------------------------
	// Store
	// ---------------------------------------------------------------

	private static Store store() {
		if (store != null) return store;
		Path path = dir().resolve("tickets.json");
		Store loaded = null;
		if (Files.exists(path)) {
			try {
				loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Store.class);
			} catch (IOException | JsonParseException e) {
				// keep the broken file, start a fresh one next to it
				Path broken = path.resolveSibling("tickets.json.broken-" + System.currentTimeMillis());
				AuraPoints.LOGGER.error("Can't read {}, moved it to {}", path, broken, e);
				try {
					Files.move(path, broken);
				} catch (IOException ignored) {
				}
			}
		}
		if (loaded == null) loaded = new Store();
		if (loaded.tickets == null) loaded.tickets = new ArrayList<>();
		for (Ticket t : loaded.tickets) {
			if (t.votes == null) t.votes = new LinkedHashMap<>();
			if (t.history == null) t.history = new ArrayList<>();
			if (t.state == null) t.state = State.OPEN;
			loaded.nextId = Math.max(loaded.nextId, t.id + 1);
		}
		store = loaded;
		return store;
	}

	/** Called on server start: reads the tickets, so a broken file shows up in the log right away. */
	public static void load() {
		store = null;
		store();
		reloadConfig();
	}

	private static void save() {
		Path d = dir();
		try {
			Files.createDirectories(d);
			Path path = d.resolve("tickets.json");
			Path tmp = d.resolve("tickets.json.tmp");
			Files.writeString(tmp, GSON.toJson(store()) + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			for (State s : State.values()) Files.writeString(d.resolve(s.file()), export(s), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AuraPoints.LOGGER.error("Can't write the tickets to {}", d, e);
		}
	}

	/** One readable file: every ticket in that state, oldest first. */
	private static String export(State state) {
		StringBuilder sb = new StringBuilder();
		sb.append("# ").append(state).append(" tickets - written by Aura Points, edits here are overwritten (tickets.json is the real data)\n\n");
		int n = 0;
		for (Ticket t : store().tickets) {
			if (t.state != state) continue;
			n++;
			sb.append('#').append(t.id).append("  [").append(t.state).append("]  ").append(t.title).append('\n');
			sb.append("    by ").append(t.author).append(", ").append(TIME.format(Instant.ofEpochMilli(t.created)));
			if (t.pos != null) sb.append(" (").append(t.pos).append(')');
			sb.append('\n');
			for (Change c : t.history) {
				sb.append("    ").append(verb(c.state)).append(" by ").append(c.by).append(' ').append(TIME.format(Instant.ofEpochMilli(c.at)));
				if (c.note != null && !c.note.isBlank()) sb.append(": \"").append(c.note).append('"');
				sb.append('\n');
			}
			if (t.state == State.OPEN && !t.votes.isEmpty()) {
				for (Vote v : t.votes.values()) {
					sb.append("    vote: ").append(v.name).append(v.approve ? " approves" : " declines");
					if (v.note != null && !v.note.isBlank()) sb.append(": \"").append(v.note).append('"');
					sb.append('\n');
				}
			}
			sb.append("    ---\n");
			for (String line : t.body.split("\n")) sb.append("    ").append(line).append('\n');
			sb.append('\n');
		}
		if (n == 0) sb.append("(none)\n");
		return sb.toString();
	}

	private static Ticket find(int id) {
		for (Ticket t : store().tickets) if (t.id == id) return t;
		return null;
	}

	private static String verb(State s) {
		return switch (s) {
			case OPEN -> "reopened";
			case ACTIVE -> "approved";
			case WIP -> "work started";
			case DONE -> "done";
			case DECLINED -> "declined";
		};
	}

	// ---------------------------------------------------------------
	// Commands
	// ---------------------------------------------------------------

	private static final SuggestionProvider<CommandSourceStack> STATES = (ctx, builder) -> {
		List<String> names = new ArrayList<>();
		for (State s : State.values()) names.add(s.name().toLowerCase(Locale.ROOT));
		names.add("all");
		return SharedSuggestionProvider.suggest(names, builder);
	};

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("aura")
				.then(Commands.literal("ticket")
						.executes(Tickets::usage)
						.then(Commands.literal("cancel").executes(Tickets::cancel))
						.then(Commands.literal("status").executes(Tickets::status))
						.then(Commands.argument("title", StringArgumentType.string())
								.executes(ctx -> startTicketMode(ctx, StringArgumentType.getString(ctx, "title")))
								.then(Commands.argument("message", StringArgumentType.greedyString())
										.executes(ctx -> {
											ServerPlayer player = ctx.getSource().getPlayerOrException();
											return submit(player, StringArgumentType.getString(ctx, "title"),
													StringArgumentType.getString(ctx, "message")) ? 1 : 0;
										}))))
				.then(Commands.literal("tickets")
						.executes(ctx -> list(ctx, State.OPEN))
						.then(Commands.literal("list")
								.then(Commands.argument("state", StringArgumentType.word())
										.suggests(STATES)
										.executes(ctx -> {
											String s = StringArgumentType.getString(ctx, "state").toUpperCase(Locale.ROOT);
											if (s.equals("ALL")) return list(ctx, null);
											try {
												return list(ctx, State.valueOf(s));
											} catch (IllegalArgumentException e) {
												ctx.getSource().sendFailure(Component.literal("Unknown state. Use open, active, wip, done, declined or all."));
												return 0;
											}
										})))
						.then(Commands.literal("view")
								.then(Commands.argument("id", IntegerArgumentType.integer(1))
										.executes(Tickets::view)))
						.then(decision("approve", true))
						.then(decision("decline", false))
						.then(change("wip", State.WIP))
						.then(change("done", State.DONE))
						.then(change("reopen", State.OPEN))));
	}

	/** approve/decline <id> [note]: a reviewer's vote, or the console's final word. */
	private static LiteralArgumentBuilder<CommandSourceStack> decision(String word, boolean approve) {
		return Commands.literal(word)
				.then(Commands.argument("id", IntegerArgumentType.integer(1))
						.executes(ctx -> vote(ctx, approve, null))
						.then(Commands.argument("note", StringArgumentType.greedyString())
								.executes(ctx -> vote(ctx, approve, StringArgumentType.getString(ctx, "note")))));
	}

	/** wip/done/reopen <id> [note]: set by hand, no vote. */
	private static LiteralArgumentBuilder<CommandSourceStack> change(String word, State to) {
		return Commands.literal(word)
				.then(Commands.argument("id", IntegerArgumentType.integer(1))
						.executes(ctx -> setState(ctx, to, null))
						.then(Commands.argument("note", StringArgumentType.greedyString())
								.executes(ctx -> setState(ctx, to, StringArgumentType.getString(ctx, "note")))));
	}

	private static int usage(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		src.sendSystemMessage(Component.literal("Tickets - ask for changes to the mod:").withStyle(ChatFormatting.GOLD));
		src.sendSystemMessage(Component.literal("  /aura ticket <title> <message>").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" - send it right away").withStyle(ChatFormatting.GRAY)));
		src.sendSystemMessage(Component.literal("  /aura ticket <title>").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" - then type the ticket as your next chat message").withStyle(ChatFormatting.GRAY)));
		src.sendSystemMessage(Component.literal("  /aura ticket status").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" - see what happened to your tickets").withStyle(ChatFormatting.GRAY)));
		src.sendSystemMessage(Component.literal("  Titles with spaces need quotes: /aura ticket \"Ice shard\" make it spikier").withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	/** Why this player can't send a ticket now, or null if they can. */
	private static String refusal(ServerPlayer player) {
		Config c = config();
		if (!c.enabled) return "Tickets are switched off right now.";
		if (blacklisted(player)) return "You can't send tickets.";
		long window = Math.max(1, c.windowMinutes) * 60_000L;
		long now = System.currentTimeMillis();
		String uuid = player.getUUID().toString();
		long oldest = Long.MAX_VALUE;
		int recent = 0;
		for (Ticket t : store().tickets) {
			if (uuid.equals(t.authorUuid) && now - t.created < window) {
				recent++;
				oldest = Math.min(oldest, t.created);
			}
		}
		if (recent >= c.maxTicketsPerWindow) {
			long minutes = Math.max(1, (oldest + window - now + 59_999) / 60_000);
			return "You sent " + recent + " tickets in the last " + c.windowMinutes + " minutes. Try again in " + minutes + " min.";
		}
		return null;
	}

	private static String titleProblem(String title) {
		if (title.isBlank()) return "The title is empty.";
		if (title.length() > config().maxTitleLength) return "The title is too long (" + title.length() + "/" + config().maxTitleLength + " characters).";
		return null;
	}

	private static int startTicketMode(CommandContext<CommandSourceStack> ctx, String title) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String problem = refusal(player);
		if (problem == null) problem = titleProblem(title.strip());
		if (problem != null) {
			ctx.getSource().sendFailure(Component.literal(problem));
			return 0;
		}
		pendingTitle.put(player.getUUID(), title.strip());
		player.sendSystemMessage(Component.literal("Ticket \"" + title.strip() + "\": type it in chat now. Nobody else will see it. ")
				.withStyle(ChatFormatting.GOLD)
				.append(Component.literal("[Cancel]").withStyle(s -> s.withColor(ChatFormatting.RED)
						.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura ticket cancel"))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Leave ticket mode"))))));
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (pendingTitle.remove(player.getUUID()) != null) {
			player.sendSystemMessage(Component.literal("Ticket cancelled, chat is normal again.").withStyle(ChatFormatting.GRAY));
			return 1;
		}
		ctx.getSource().sendFailure(Component.literal("You're not writing a ticket."));
		return 0;
	}

	/** Saves a new ticket. False (and a message) if it was refused. */
	private static boolean submit(ServerPlayer player, String title, String body) {
		title = title.strip();
		body = body.strip();
		String problem = refusal(player);
		if (problem == null) problem = titleProblem(title);
		if (problem == null && body.isEmpty()) problem = "The ticket is empty.";
		if (problem == null && body.length() > config().maxLength)
			problem = "Too long: " + body.length() + "/" + config().maxLength + " characters. Shorten it a bit.";
		if (problem != null) {
			player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED));
			return false;
		}
		Store s = store();
		Ticket t = new Ticket();
		t.id = s.nextId++;
		t.author = player.getGameProfile().getName();
		t.authorUuid = player.getUUID().toString();
		t.title = title;
		t.body = body;
		t.created = System.currentTimeMillis();
		t.pos = player.level().dimension().location().getPath() + " " + player.getBlockX() + " " + player.getBlockY() + " " + player.getBlockZ();
		s.tickets.add(t);
		save();
		AuraPoints.LOGGER.info("Ticket #{} from {}: {}", t.id, t.author, t.title);
		player.sendSystemMessage(Component.literal("Ticket #" + t.id + " \"" + t.title + "\" sent, thanks! ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal("Check on it with /aura ticket status.").withStyle(ChatFormatting.GRAY)));
		MinecraftServer server = player.getServer();
		if (server != null) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (reviewerKey(p) == null) continue;
				p.sendSystemMessage(Component.literal("New ticket from " + t.author + ": ").withStyle(ChatFormatting.GOLD).append(line(t, true)));
			}
		}
		return true;
	}

	/** Ticket mode: the next chat message is the ticket and never reaches chat. */
	public static void onChat(ServerChatEvent event) {
		ServerPlayer player = event.getPlayer();
		String title = pendingTitle.get(player.getUUID());
		if (title == null) return;
		event.setCanceled(true);
		String body = event.getRawText();
		if (body.strip().length() > config().maxLength) {
			// stay in ticket mode so they can send a shorter one
			player.sendSystemMessage(Component.literal("Too long: " + body.strip().length() + "/" + config().maxLength
					+ " characters. Send a shorter one, or /aura ticket cancel.").withStyle(ChatFormatting.RED));
			return;
		}
		pendingTitle.remove(player.getUUID());
		submit(player, title, body);
	}

	private static int status(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String uuid = player.getUUID().toString();
		List<Ticket> mine = new ArrayList<>();
		for (Ticket t : store().tickets) if (uuid.equals(t.authorUuid)) mine.add(t);
		if (mine.isEmpty()) {
			player.sendSystemMessage(Component.literal("You haven't sent any tickets. /aura ticket shows how.").withStyle(ChatFormatting.GRAY));
			return 0;
		}
		player.sendSystemMessage(Component.literal("Your tickets:").withStyle(ChatFormatting.GOLD));
		int shown = 0;
		boolean seen = false;
		for (int i = mine.size() - 1; i >= 0 && shown < 15; i--, shown++) {
			Ticket t = mine.get(i);
			MutableComponent line = Component.literal("  #" + t.id + " ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal("[" + t.state + "] ").withStyle(t.state.color))
					.append(Component.literal(t.title).withStyle(ChatFormatting.WHITE));
			Change last = t.history.isEmpty() ? null : t.history.get(t.history.size() - 1);
			String when = last == null ? "sent " + ago(t.created) : verb(last.state) + " " + ago(last.at);
			line.append(Component.literal(" - " + when).withStyle(ChatFormatting.DARK_GRAY));
			line.withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, details(t, false))));
			player.sendSystemMessage(line);
			if (t.unseenChange) {
				t.unseenChange = false;
				seen = true;
			}
		}
		if (mine.size() > shown) player.sendSystemMessage(Component.literal("  ... and " + (mine.size() - shown) + " older").withStyle(ChatFormatting.DARK_GRAY));
		if (seen) save();
		return mine.size();
	}

	private static int list(CommandContext<CommandSourceStack> ctx, State state) {
		CommandSourceStack src = ctx.getSource();
		if (!canReview(src)) {
			src.sendFailure(Component.literal("Only ticket reviewers can do that."));
			return 0;
		}
		List<Ticket> shown = new ArrayList<>();
		for (Ticket t : store().tickets) if (state == null || t.state == state) shown.add(t);
		// open tickets you still have to vote on come first
		ServerPlayer player = src.getPlayer();
		String me = player == null ? null : reviewerKey(player);
		if (me != null) shown.sort((a, b) -> Boolean.compare(a.votes.containsKey(me), b.votes.containsKey(me)));
		String label = state == null ? "All" : state.name().charAt(0) + state.name().substring(1).toLowerCase(Locale.ROOT);
		if (shown.isEmpty()) {
			src.sendSystemMessage(Component.literal(label + " tickets: none.").withStyle(ChatFormatting.GRAY));
			return 0;
		}
		src.sendSystemMessage(Component.literal(label + " tickets (" + shown.size() + "):").withStyle(ChatFormatting.GOLD));
		for (Ticket t : shown) src.sendSystemMessage(Component.literal("  ").append(line(t, true)));
		return shown.size();
	}

	private static int view(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		if (!canReview(src)) {
			src.sendFailure(Component.literal("Only ticket reviewers can do that."));
			return 0;
		}
		Ticket t = find(IntegerArgumentType.getInteger(ctx, "id"));
		if (t == null) {
			src.sendFailure(Component.literal("No such ticket."));
			return 0;
		}
		src.sendSystemMessage(details(t, true));
		if (t.state == State.OPEN) src.sendSystemMessage(buttons(t));
		return 1;
	}

	/** A reviewer votes; the console decides outright. */
	private static int vote(CommandContext<CommandSourceStack> ctx, boolean approve, String note) {
		CommandSourceStack src = ctx.getSource();
		if (!canReview(src)) {
			src.sendFailure(Component.literal("Only ticket reviewers can do that."));
			return 0;
		}
		Ticket t = find(IntegerArgumentType.getInteger(ctx, "id"));
		if (t == null) {
			src.sendFailure(Component.literal("No such ticket."));
			return 0;
		}
		if (t.state != State.OPEN) {
			src.sendFailure(Component.literal("Ticket #" + t.id + " is already " + t.state + ". Use /aura tickets reopen " + t.id + " first."));
			return 0;
		}
		MinecraftServer server = src.getServer();
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			decide(server, t, approve ? State.ACTIVE : State.DECLINED, "console", note);
			src.sendSystemMessage(Component.literal("Ticket #" + t.id + " is now " + t.state + ".").withStyle(t.state.color));
			return 1;
		}
		Vote v = new Vote();
		v.approve = approve;
		v.name = player.getGameProfile().getName();
		v.at = System.currentTimeMillis();
		v.note = note;
		t.votes.put(reviewerKey(player), v);

		// a strict majority of all reviewers decides; votes of people no longer listed don't count
		Set<String> reviewers = reviewers();
		int yes = 0, no = 0;
		List<String> yesNames = new ArrayList<>(), noNames = new ArrayList<>();
		for (Map.Entry<String, Vote> e : t.votes.entrySet()) {
			if (!reviewers.contains(e.getKey())) continue;
			if (e.getValue().approve) {
				yes++;
				yesNames.add(e.getValue().name);
			} else {
				no++;
				noNames.add(e.getValue().name);
			}
		}
		int n = reviewers.size();
		if (yes * 2 > n) {
			decide(server, t, State.ACTIVE, String.join(", ", yesNames), note);
		} else if (no * 2 > n) {
			decide(server, t, State.DECLINED, String.join(", ", noNames), note);
		} else {
			save();
			if (yes + no >= n) {
				reviewersMessage(server, Component.literal("Ticket #" + t.id + " \"" + t.title + "\" is tied " + yes + "-" + no
						+ ", it stays open. Change your vote to settle it.").withStyle(ChatFormatting.YELLOW));
			} else {
				player.sendSystemMessage(Component.literal("Vote saved: " + (approve ? "approve" : "decline") + " #" + t.id
						+ " (" + yes + " approve, " + no + " decline, " + (n - yes - no) + " still to vote).").withStyle(ChatFormatting.GRAY));
			}
		}
		return 1;
	}

	private static int setState(CommandContext<CommandSourceStack> ctx, State to, String note) {
		CommandSourceStack src = ctx.getSource();
		if (!canReview(src)) {
			src.sendFailure(Component.literal("Only ticket reviewers can do that."));
			return 0;
		}
		Ticket t = find(IntegerArgumentType.getInteger(ctx, "id"));
		if (t == null) {
			src.sendFailure(Component.literal("No such ticket."));
			return 0;
		}
		if (t.state == to) {
			src.sendFailure(Component.literal("Ticket #" + t.id + " is already " + to + "."));
			return 0;
		}
		ServerPlayer player = src.getPlayer();
		decide(src.getServer(), t, to, player == null ? "console" : player.getGameProfile().getName(), note);
		src.sendSystemMessage(Component.literal("Ticket #" + t.id + " \"" + t.title + "\" is now " + to + ".").withStyle(to.color));
		return 1;
	}

	/** Moves a ticket to a new state, records it, tells the author (now or on their next join) and the reviewers. */
	private static void decide(MinecraftServer server, Ticket t, State to, String by, String note) {
		t.state = to;
		t.votes.clear();
		Change c = new Change();
		c.state = to;
		c.by = by;
		c.at = System.currentTimeMillis();
		c.note = note;
		t.history.add(c);
		AuraPoints.LOGGER.info("Ticket #{} \"{}\" -> {} ({})", t.id, t.title, to, by);
		ServerPlayer author = authorOnline(server, t);
		if (author != null) {
			author.sendSystemMessage(changeMessage(t));
			t.unseenChange = false;
		} else {
			t.unseenChange = true;
		}
		if (to == State.ACTIVE || to == State.DECLINED) {
			reviewersMessage(server, Component.literal("Ticket #" + t.id + " \"" + t.title + "\" " + verb(to) + " (" + by + ").").withStyle(to.color));
		}
		save();
	}

	private static ServerPlayer authorOnline(MinecraftServer server, Ticket t) {
		if (server == null) return null;
		try {
			return server.getPlayerList().getPlayer(UUID.fromString(t.authorUuid));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static void reviewersMessage(MinecraftServer server, Component message) {
		if (server == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (reviewerKey(p) != null) p.sendSystemMessage(message);
	}

	private static Component changeMessage(Ticket t) {
		String what = switch (t.state) {
			case ACTIVE -> "was approved! It's on the list.";
			case DECLINED -> "was declined.";
			case WIP -> "is being worked on now.";
			case DONE -> "is done!";
			case OPEN -> "was reopened for review.";
		};
		MutableComponent msg = Component.literal("Your ticket #" + t.id + " \"" + t.title + "\" " + what).withStyle(t.state.color);
		Change last = t.history.isEmpty() ? null : t.history.get(t.history.size() - 1);
		if (last != null && last.note != null && !last.note.isBlank())
			msg.append(Component.literal(" \"" + last.note + "\"").withStyle(ChatFormatting.GRAY));
		return msg;
	}

	// ---------------------------------------------------------------
	// Chat lines
	// ---------------------------------------------------------------

	/** "#7 [OPEN] Ice shard - Player1" with the body on hover, plus buttons for open tickets. */
	private static MutableComponent line(Ticket t, boolean withButtons) {
		MutableComponent line = Component.literal("#" + t.id + " ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal("[" + t.state + "] ").withStyle(t.state.color))
				.append(Component.literal(t.title).withStyle(s -> s.withColor(ChatFormatting.WHITE)
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, details(t, false)))
						.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura tickets view " + t.id))))
				.append(Component.literal(" - " + t.author).withStyle(ChatFormatting.DARK_GRAY));
		if (t.state == State.OPEN && !t.votes.isEmpty()) line.append(Component.literal(" " + tally(t)).withStyle(ChatFormatting.DARK_GRAY));
		if (withButtons && t.state == State.OPEN) line.append(Component.literal(" ")).append(buttons(t));
		return line;
	}

	private static MutableComponent buttons(Ticket t) {
		return button("[View]", ChatFormatting.AQUA, "/aura tickets view " + t.id, "Read the whole ticket")
				.append(Component.literal(" "))
				.append(button("[Approve]", ChatFormatting.GREEN, "/aura tickets approve " + t.id, "Vote to approve"))
				.append(Component.literal(" "))
				.append(button("[Decline]", ChatFormatting.RED, "/aura tickets decline " + t.id, "Vote to decline"));
	}

	private static MutableComponent button(String text, ChatFormatting color, String command, String hover) {
		return Component.literal(text).withStyle(s -> s.withColor(color)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
	}

	/** "(1 approve, 1 decline, waiting: alex)" for an open ticket. */
	private static String tally(Ticket t) {
		Set<String> reviewers = reviewers();
		int yes = 0, no = 0;
		List<String> waiting = new ArrayList<>();
		for (String r : reviewers) {
			Vote v = t.votes.get(r);
			if (v == null) waiting.add(r);
			else if (v.approve) yes++;
			else no++;
		}
		return "(" + yes + " approve, " + no + " decline" + (waiting.isEmpty() ? "" : ", waiting: " + String.join(", ", waiting)) + ")";
	}

	/** The whole ticket: title, author, time, body, history (and votes for reviewers). */
	private static MutableComponent details(Ticket t, boolean forReviewer) {
		MutableComponent c = Component.literal("#" + t.id + " ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal("[" + t.state + "] ").withStyle(t.state.color))
				.append(Component.literal(t.title).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
				.append(Component.literal("\nby " + t.author + ", " + TIME.format(Instant.ofEpochMilli(t.created))
						+ (forReviewer && t.pos != null ? " (" + t.pos + ")" : "")).withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal("\n" + t.body).withStyle(ChatFormatting.WHITE));
		for (Change ch : t.history) {
			c.append(Component.literal("\n" + verb(ch.state) + " by " + ch.by + ", " + TIME.format(Instant.ofEpochMilli(ch.at))
					+ (ch.note != null && !ch.note.isBlank() ? ": \"" + ch.note + "\"" : "")).withStyle(ch.state.color));
		}
		if (forReviewer && t.state == State.OPEN) {
			c.append(Component.literal("\nVotes " + tally(t)).withStyle(ChatFormatting.YELLOW));
			for (Vote v : t.votes.values()) {
				if (v.note != null && !v.note.isBlank())
					c.append(Component.literal("\n  " + v.name + ": \"" + v.note + "\"").withStyle(ChatFormatting.GRAY));
			}
		}
		return c;
	}

	private static String ago(long at) {
		long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
		if (s < 60) return "just now";
		if (s < 3600) return (s / 60) + " min ago";
		if (s < 86400) return (s / 3600) + "h ago";
		return (s / 86400) + "d ago";
	}

	// ---------------------------------------------------------------
	// Join / leave
	// ---------------------------------------------------------------

	/** Authors hear about changes they missed; reviewers get the open tickets still waiting for their vote. */
	public static void onLogin(ServerPlayer player) {
		String uuid = player.getUUID().toString();
		boolean changed = false;
		for (Ticket t : store().tickets) {
			if (t.unseenChange && uuid.equals(t.authorUuid)) {
				player.sendSystemMessage(changeMessage(t));
				t.unseenChange = false;
				changed = true;
			}
		}
		if (changed) save();

		String me = reviewerKey(player);
		if (me == null) return;
		List<Ticket> waiting = new ArrayList<>();
		for (Ticket t : store().tickets) if (t.state == State.OPEN && !t.votes.containsKey(me)) waiting.add(t);
		if (waiting.isEmpty()) return;
		player.sendSystemMessage(Component.literal(waiting.size() == 1 ? "1 ticket needs your vote:" : waiting.size() + " tickets need your vote:")
				.withStyle(ChatFormatting.GOLD));
		for (Ticket t : waiting) player.sendSystemMessage(Component.literal("  ").append(line(t, true)));
	}

	public static void onLogout(ServerPlayer player) {
		pendingTitle.remove(player.getUUID());
	}
}
