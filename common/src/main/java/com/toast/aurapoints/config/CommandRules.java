package com.toast.aurapoints.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.toast.aurapoints.AuraCommand;
import com.toast.aurapoints.AuraPoints;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.toast.aurapoints.shim.fml.loading.FMLPaths;
import com.toast.aurapoints.shim.neoforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Who may use the mod's commands, from config/aurapoints-commands.json: per command
 * (by its words, "aura give", "aura arena rebuild"...) whether it is enabled, the op level it
 * needs, whether only the server owner may use it, and a blacklist of players. A
 * "blacklist" at the top of the file locks those players out of every Aura command.
 *
 * Each command node's own requirement is replaced by the rule (CommandNode has no setter,
 * hence the reflection), so the file decides completely; the defaults below are the levels
 * the commands had before. Rules are read when checked, so /aura reload or /reload applies
 * a change straight away (the command list players see is re-sent too).
 */
public final class CommandRules {

	private CommandRules() {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String FILE = "aurapoints-commands.json";

	/** The mod's top-level commands (the dev-only /auradev is left alone). */
	private static final List<String> ROOTS = List.of("aura", "shop", "changelog");

	/** One command's rule. ownerOnly: only the server owner (or the console). */
	record Rule(boolean enabled, int opLevel, boolean ownerOnly, Set<String> blacklist) {
	}

	/** The levels the commands had before this file existed; anything not listed is open to everyone (0). */
	private static final Map<String, Rule> DEFAULTS = new LinkedHashMap<>();

	static {
		for (String key : List.of("aura give", "aura reload", "aura progress", "aura cooldowns", "aura resetprogress",
				"aura setaura", "aura mastery set", "aura awaken")) {
			DEFAULTS.put(key, new Rule(true, 2, false, Set.of()));
		}
		for (String key : List.of("aura mjolnir", "aura handheld", "aura lock", "aura unlock", "aura balance whitelist", "aura arena rebuild",
				"aura arena duel stop", "aura arena match stop", "aura arena layout")) {
			DEFAULTS.put(key, new Rule(true, 0, true, Set.of()));
		}
	}

	/** The rules in force (key -> rule) and the global blacklist; swapped whole on load. */
	private static volatile Map<String, Rule> rules = new LinkedHashMap<>();
	private static volatile Set<String> blacklistAll = Set.of();
	/** The server owners (names lower case, or UUIDs); empty = everyone with permission level 4. */
	private static volatile Set<String> owners = Set.of();

	/** A server owner: listed in "owners", or (list empty) a level 4 operator. */
	public static boolean isOwner(ServerPlayer player) {
		Set<String> o = owners;
		return o.isEmpty() ? player.hasPermissions(4) : listed(o, player);
	}

	/** Every command key the last registration found, in tree order (what the file lists). */
	private static final Set<String> KNOWN = new LinkedHashSet<>();

	public static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve(FILE);
	}

	// ---------------------------------------------------------------
	// Checking
	// ---------------------------------------------------------------

	static Rule rule(String key) {
		Rule r = rules.get(key);
		if (r != null) return r;
		return DEFAULTS.getOrDefault(key, new Rule(true, 0, false, Set.of()));
	}

	/** May this source use the command {@code key}? */
	public static boolean allows(String key, CommandSourceStack src) {
		Rule r = rule(key);
		if (!r.enabled()) return false;
		ServerPlayer player = src.getPlayer();
		if (player != null && (listed(blacklistAll, player) || listed(r.blacklist(), player))) return false;
		// the owner in game, or the server console / rcon
		boolean owner = player == null ? src.getEntity() == null && src.hasPermission(4) : AuraCommand.isOwner(player);
		if (r.ownerOnly()) return owner;
		return owner || src.hasPermission(r.opLevel());
	}

	private static boolean listed(Set<String> list, ServerPlayer player) {
		return !list.isEmpty() && (list.contains(player.getGameProfile().getName().toLowerCase(Locale.ROOT))
				|| list.contains(player.getUUID().toString()));
	}

	// ---------------------------------------------------------------
	// Hooking the command tree
	// ---------------------------------------------------------------

	private static Field requirementField;

	/** Call after the mod's commands are registered: every literal of its commands gets its rule as requirement. */
	public static void apply(CommandDispatcher<CommandSourceStack> dispatcher) {
		try {
			if (requirementField == null) {
				requirementField = CommandNode.class.getDeclaredField("requirement");
				requirementField.setAccessible(true);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			AuraPoints.LOGGER.error("Can't hook the command requirements - {} has no effect", FILE, e);
			return;
		}
		Predicate<CommandSourceStack> open = Commands.literal("x").build().getRequirement();
		KNOWN.clear();
		for (String root : ROOTS) {
			CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(root);
			if (node != null) hook(node, root, 1, open);
		}
		load(); // the keys may be new: list them in the file
	}

	/** Depth 1-2 always get a rule; deeper literals only where they had their own requirement (mastery set, duel stop). */
	private static void hook(CommandNode<CommandSourceStack> node, String key, int depth, Predicate<CommandSourceStack> open) {
		if (node.getRedirect() == null || node instanceof LiteralCommandNode) {
			if (depth <= 2 || node.getRequirement() != open || DEFAULTS.containsKey(key)) {
				KNOWN.add(key);
				try {
					requirementField.set(node, (Predicate<CommandSourceStack>) src -> allows(key, src));
				} catch (IllegalAccessException e) {
					AuraPoints.LOGGER.error("Can't set the requirement of /{}", key, e);
				}
			}
		}
		if (node.getRedirect() != null) return; // its target is hooked under its own name
		for (CommandNode<CommandSourceStack> child : node.getChildren()) {
			if (child instanceof LiteralCommandNode<CommandSourceStack> literal) {
				hook(literal, key + " " + literal.getLiteral(), depth + 1, open);
			} else {
				// arguments: their literals count as the command's own ("aura arena duel <player> ...")
				for (CommandNode<CommandSourceStack> grand : child.getChildren()) {
					if (grand instanceof LiteralCommandNode<CommandSourceStack> literal) hook(literal, key + " " + literal.getLiteral(), depth + 2, open);
				}
			}
		}
	}

	// ---------------------------------------------------------------
	// The file
	// ---------------------------------------------------------------

	private static final String INFO = "Who may use the Aura commands. Per command: enabled (false = the command is gone for everyone, "
			+ "console too), opLevel (0 = everyone, 2 = ops, 4 = the console), ownerOnly (only the \"owners\" or the console; "
			+ "the owner also passes any opLevel), blacklist (player names or UUIDs). A sub-command also needs its parent: "
			+ "disabling \"aura\" disables all of /aura. The top \"blacklist\" locks players out of every Aura command. "
			+ "\"owners\": player names or UUIDs of the server owners (empty = everyone with permission level 4). "
			+ "Note: quest or function rewards that run a command as a blacklisted player fail too. Apply with /aura reload or /reload.";

	/** Reads the file (writing missing commands into it) and re-sends everyone's command list. One report line per change or problem. */
	public static synchronized List<String> load() {
		List<String> report = new ArrayList<>();
		Path path = file();
		JsonObject json;
		try {
			json = Files.exists(path) ? JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			report.add("ERROR: can't read " + FILE + " (" + e.getMessage() + ") - command rules unchanged.");
			AuraPoints.LOGGER.error("Can't read {}", path, e);
			return report;
		}
		boolean rewrite = !Files.exists(path);

		Set<String> all = blacklistOf(json.get("blacklist"), "blacklist", report);
		if (!json.has("blacklist")) rewrite = true;
		Set<String> ownerList = blacklistOf(json.get("owners"), "owners", report);
		if (!json.has("owners")) rewrite = true;

		JsonObject commands = json.has("commands") && json.get("commands").isJsonObject() ? json.getAsJsonObject("commands") : new JsonObject();
		// 4.99: /arena only lives under /aura now - "arena x" rules become "aura arena x"
		for (String key : new ArrayList<>(commands.keySet())) {
			if (!key.equals("arena") && !key.startsWith("arena ")) continue;
			var entry = commands.remove(key);
			String moved = "aura " + key;
			if (!commands.has(moved)) {
				// match stop never had its owner-only default (anyone could stop a match)
				if (moved.equals("aura arena match stop") && entry.isJsonObject()) entry.getAsJsonObject().addProperty("ownerOnly", true);
				commands.add(moved, entry);
			}
			rewrite = true;
		}
		Map<String, Rule> next = new LinkedHashMap<>();
		JsonObject out = new JsonObject();
		List<String> keys = new ArrayList<>(KNOWN);
		for (String k : DEFAULTS.keySet()) if (!keys.contains(k)) keys.add(k);
		// read before the commands were registered: take the file's keys as they are
		if (KNOWN.isEmpty()) for (String k : commands.keySet()) if (!keys.contains(k)) keys.add(k);
		for (String key : keys) {
			Rule def = DEFAULTS.getOrDefault(key, new Rule(true, 0, false, Set.of()));
			JsonObject entry = commands.has(key) && commands.get(key).isJsonObject() ? commands.getAsJsonObject(key) : new JsonObject();
			if (!commands.has(key)) rewrite = true;
			boolean enabled = bool(entry, "enabled", def.enabled(), key, report);
			int level = level(entry, def.opLevel(), key, report);
			boolean owner = bool(entry, "ownerOnly", def.ownerOnly(), key, report);
			Set<String> black = entry.has("blacklist") ? blacklistOf(entry.get("blacklist"), key + ".blacklist", report) : def.blacklist();
			for (String f : List.of("enabled", "opLevel", "ownerOnly", "blacklist")) if (!entry.has(f)) rewrite = true;
			Rule r = new Rule(enabled, level, owner, black);
			next.put(key, r);
			JsonObject e = new JsonObject();
			e.addProperty("enabled", enabled);
			e.addProperty("opLevel", level);
			e.addProperty("ownerOnly", owner);
			e.add("blacklist", entry.has("blacklist") ? entry.get("blacklist") : new JsonArray());
			out.add(key, e);
			Rule was = rules.get(key);
			if (was != null && !was.equals(r)) report.add("/" + key + ": " + describe(was) + " -> " + describe(r));
		}
		for (String key : commands.keySet()) {
			if (!next.containsKey(key)) {
				report.add("WARNING: unknown command \"" + key + "\" in " + FILE + " (typo?) - kept, but it does nothing.");
				out.add(key, commands.get(key));
			}
		}
		if (!blacklistAll.equals(all) && !rules.isEmpty()) report.add("blacklist (all commands): " + blacklistAll + " -> " + all);
		if (!owners.equals(ownerList) && !rules.isEmpty()) report.add("owners: " + owners + " -> " + ownerList);
		rules = next;
		blacklistAll = all;
		owners = ownerList;

		if (rewrite && !KNOWN.isEmpty()) {
			JsonObject file = new JsonObject();
			file.addProperty("_info", INFO);
			file.add("owners", json.has("owners") ? json.get("owners") : new JsonArray());
			file.add("blacklist", json.has("blacklist") ? json.get("blacklist") : new JsonArray());
			file.add("commands", out);
			try {
				Path tmp = path.resolveSibling(FILE + ".tmp");
				Files.writeString(tmp, GSON.toJson(file) + "\n", StandardCharsets.UTF_8);
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				AuraPoints.LOGGER.error("Can't write {}", path, e);
			}
		}

		// players see the commands they may use: re-send the list
		MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		if (server != null) server.execute(() -> {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) server.getCommands().sendCommands(p);
		});
		return report;
	}

	private static String describe(Rule r) {
		if (!r.enabled()) return "disabled";
		String who = r.ownerOnly() ? "owner only" : "op " + r.opLevel();
		return r.blacklist().isEmpty() ? who : who + ", blacklist " + r.blacklist();
	}

	private static boolean bool(JsonObject entry, String field, boolean def, String key, List<String> report) {
		JsonElement v = entry.get(field);
		if (v == null) return def;
		if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) return v.getAsBoolean();
		report.add("ERROR: " + key + "." + field + " = " + v + " is not true/false - using " + def + ".");
		return def;
	}

	private static int level(JsonObject entry, int def, String key, List<String> report) {
		JsonElement v = entry.get("opLevel");
		if (v == null) return def;
		if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
			double d = v.getAsDouble();
			if (d == Math.rint(d) && d >= 0 && d <= 4) return (int) d;
		}
		report.add("ERROR: " + key + ".opLevel = " + v + " must be 0 to 4 - using " + def + ".");
		return def;
	}

	private static Set<String> blacklistOf(JsonElement v, String where, List<String> report) {
		Set<String> out = new LinkedHashSet<>();
		if (v == null) return out;
		if (!v.isJsonArray()) {
			report.add("ERROR: " + where + " must be a list like [\"Name\"] - ignored.");
			return out;
		}
		for (JsonElement e : v.getAsJsonArray()) {
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && !e.getAsString().isBlank()) out.add(e.getAsString().trim().toLowerCase(Locale.ROOT));
			else report.add("WARNING: " + where + " entry " + e + " is not a name - ignored.");
		}
		return out;
	}
}
