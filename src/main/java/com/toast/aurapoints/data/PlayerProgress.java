package com.toast.aurapoints.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.PuffishBridge;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraStats;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Mastery;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Player progress as editable files: /aura progress export writes one JSON per player into
 * {@code <server>/<PROGRESS_FOLDER>/players/}, an admin edits it, /aura progress import applies the edits.
 * The world save stays the source of truth - nothing reads these files on its own.
 *
 * Only keys present in a file are applied, so a file can be trimmed to just what should change.
 * {@code mastery} is the exception: it is the whole list, a missing aura means level 0.
 * wipeVersion is never exported (importing a lower one would wipe the player on login).
 */
public final class PlayerProgress {

	private PlayerProgress() {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private static final String INFO = "Edit, save, then /aura progress import <name> dry (shows the changes) and /aura progress import <name> (applies them). "
			+ "Names are aura ids (fire, wind...) and ability names (fireball, dash...). Keys you delete are left as they are, "
			+ "except mastery: an aura missing there goes back to level 0 (max 5). stats, masteryXp and skill points only apply while the player is online. "
			+ "dps_best, iron_skin_blocked and ice_shell_blocked are stored x10 (123 = 12.3).";

	private static final String README = """
			Aura Points - player progress files
			===================================

			1. /aura progress export [name]   writes players/<name>-<id>.json (everyone, or one player)
			2. Edit the file and save it.
			3. /aura progress import <name> dry   shows what would change, changes nothing
			4. /aura progress import <name>       applies it

			Without a name, import goes through every file in players/.
			Before a real import, the current state of every player it changes is saved to backups/<time>/.
			To undo an import, copy those files back into players/ and import again.

			Some things only apply while the player is online (stats, masteryXp, skillPointsBought, pvpUnlocked);
			for an offline player they are skipped with a warning - import again once they are on.
			Quest progress (FTB Quests) is not in these files: use /ftbquests change_progress.
			""";

	// ---------------------------------------------------------------
	// Folders
	// ---------------------------------------------------------------

	/** The progress folder; a setting that would leave the server directory falls back to "aura-progress". */
	public static Path root() {
		Path game = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
		Path dir = game.resolve(AuraConfig.PROGRESS_FOLDER).normalize();
		return dir.startsWith(game) && !dir.equals(game) ? dir : game.resolve("aura-progress");
	}

	private static Path playersDir() {
		return root().resolve("players");
	}

	// ---------------------------------------------------------------
	// Names
	// ---------------------------------------------------------------

	/** The player's name: online, else the server's name cache, else null. */
	public static String nameOf(MinecraftServer server, UUID id) {
		ServerPlayer online = server.getPlayerList().getPlayer(id);
		if (online != null) return online.getGameProfile().getName();
		var cache = server.getProfileCache();
		return cache == null ? null : cache.get(id).map(GameProfile::getName).orElse(null);
	}

	/** The id behind a name: online, else the server's name cache. Empty if the server never saw it. */
	public static Optional<UUID> idOf(MinecraftServer server, String name) {
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		if (online != null) return Optional.of(online.getUUID());
		var cache = server.getProfileCache();
		return cache == null ? Optional.empty() : cache.get(name).map(GameProfile::getId);
	}

	/** Names for tab completion: everyone with saved aura data whose name is known. */
	public static List<String> knownNames(MinecraftServer server) {
		List<String> out = new ArrayList<>();
		for (UUID id : AuraState.get(server).playerIds()) {
			String name = nameOf(server, id);
			if (name != null) out.add(name);
		}
		return out;
	}

	// ---------------------------------------------------------------
	// Export
	// ---------------------------------------------------------------

	/** Writes the file of {@code only} (or of every player with aura data when null). One report line per file. */
	public static List<String> export(MinecraftServer server, UUID only) {
		List<String> report = new ArrayList<>();
		AuraState state = AuraState.get(server);
		List<UUID> ids = only != null ? List.of(only) : new ArrayList<>(state.playerIds());
		try {
			Files.createDirectories(playersDir());
			writeReadme();
			Map<UUID, Path> existing = existingFiles(report);
			for (UUID id : ids) {
				String name = nameOf(server, id);
				Path path = existing.getOrDefault(id, playersDir().resolve(fileName(id, name)));
				write(path, snapshot(server, id, name));
				report.add("Wrote " + root().relativize(path.toAbsolutePath()).toString().replace('\\', '/'));
			}
		} catch (IOException e) {
			report.add("ERROR: can't write to " + root() + " (" + e.getMessage() + ")");
			AuraPoints.LOGGER.error("Progress export failed", e);
		}
		return report;
	}

	private static String fileName(UUID id, String name) {
		return name == null ? id + ".json" : name + "-" + id.toString().substring(0, 8) + ".json";
	}

	private static void writeReadme() throws IOException {
		Path readme = root().resolve("README.txt");
		if (!Files.exists(readme)) Files.writeString(readme, README, StandardCharsets.UTF_8);
	}

	/** Write beside it and move over, so a crash mid-write can't leave half a file. */
	private static void write(Path path, JsonObject json) throws IOException {
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
		Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
	}

	/** The player's progress as it is now. */
	static JsonObject snapshot(MinecraftServer server, UUID id, String name) {
		AuraState state = AuraState.get(server);
		// a player without aura data yet: export the defaults without saving an entry for them
		AuraPlayerData data = state.has(id) ? state.getOrCreate(id) : new AuraPlayerData();
		ServerPlayer online = server.getPlayerList().getPlayer(id);
		Map<ResourceLocation, Integer> stats = online != null ? liveStats(online) : savedStats(server, id);

		JsonObject json = new JsonObject();
		json.addProperty("_info", INFO);
		json.addProperty("uuid", id.toString());
		json.addProperty("name", name);
		json.add("aura", data.aura == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(data.aura.id()));
		json.addProperty("freeRollUsed", data.freeRollUsed);
		json.addProperty("locked", data.locked);
		JsonArray owned = new JsonArray();
		for (AuraAbilities.Ability a : data.owned) owned.add(a.name().toLowerCase(Locale.ROOT));
		json.add("owned", owned);
		JsonArray awakened = new JsonArray();
		for (AuraType t : data.awakened) awakened.add(t.id());
		json.add("awakened", awakened);
		JsonObject mastery = new JsonObject();
		for (var e : data.mastery.entrySet()) mastery.addProperty(e.getKey().id(), e.getValue());
		json.add("mastery", mastery);
		JsonObject masteryXp = new JsonObject();
		for (AuraType t : AuraType.values()) {
			ResourceLocation stat = AuraStats.mastery(t);
			if (stat != null) masteryXp.addProperty(t.id(), stats.getOrDefault(stat, 0));
		}
		json.add("masteryXp", masteryXp);
		if (name != null) json.addProperty("auraPoints", score(server, name));
		json.addProperty("skillPointsBought", data.skillPointsBought);
		json.addProperty("pvpUnlocked", data.pvpUnlocked);
		JsonObject trial = new JsonObject();
		for (ResourceLocation stat : AuraStats.all()) {
			if (!AuraStats.isMastery(stat)) trial.addProperty(stat.getPath(), stats.getOrDefault(stat, 0));
		}
		json.add("stats", trial);
		return json;
	}

	private static Map<ResourceLocation, Integer> liveStats(ServerPlayer player) {
		Map<ResourceLocation, Integer> out = new LinkedHashMap<>();
		for (ResourceLocation stat : AuraStats.all()) out.put(stat, player.getStats().getValue(Stats.CUSTOM.get(stat)));
		return out;
	}

	/** An offline player's counters, read (never written) from world/stats/<uuid>.json. */
	private static Map<ResourceLocation, Integer> savedStats(MinecraftServer server, UUID id) {
		Map<ResourceLocation, Integer> out = new LinkedHashMap<>();
		Path file = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json");
		try {
			if (!Files.exists(file)) return out;
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject custom = root.getAsJsonObject("stats") == null ? null : root.getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
			if (custom == null) return out;
			for (ResourceLocation stat : AuraStats.all()) {
				JsonElement v = custom.get(stat.toString());
				if (v != null) out.put(stat, v.getAsInt());
			}
		} catch (Exception e) {
			AuraPoints.LOGGER.warn("Can't read stats of {} from {}", id, file, e);
		}
		return out;
	}

	private static int score(MinecraftServer server, String name) {
		ReadOnlyScoreInfo info = server.getScoreboard().getPlayerScoreInfo(ScoreHolder.forNameOnly(name), AuraCurrency.objective(server));
		return info == null ? 0 : info.value();
	}

	// ---------------------------------------------------------------
	// Import
	// ---------------------------------------------------------------

	/**
	 * Reads every file in players/ (or only the one of {@code only}) and applies it; {@code dry} only reports.
	 * {@code owner}: the aura lock may only be changed by the server owner.
	 */
	public static List<String> importFiles(MinecraftServer server, UUID only, boolean dry, boolean owner) {
		List<String> report = new ArrayList<>();
		if (!Files.isDirectory(playersDir())) {
			report.add("ERROR: " + playersDir() + " doesn't exist - run /aura progress export first.");
			return report;
		}
		Map<UUID, Path> files = existingFiles(report);
		if (only != null) files.keySet().retainAll(Set.of(only));
		if (files.isEmpty()) {
			report.add("WARNING: no file to import" + (only != null ? " for that player" : "") + ".");
			return report;
		}
		AuraState state = AuraState.get(server);

		// first pass: what each file would change, nothing applied
		Map<UUID, List<String>> changes = new LinkedHashMap<>();
		Map<UUID, JsonObject> parsed = new LinkedHashMap<>();
		for (var e : files.entrySet()) {
			UUID id = e.getKey();
			String label = e.getValue().getFileName().toString();
			if (!state.has(id) && server.getPlayerList().getPlayer(id) == null) {
				report.add("WARNING: " + label + " - no such player on this server, skipped.");
				continue;
			}
			JsonObject json;
			try {
				json = read(e.getValue());
			} catch (Exception ex) {
				report.add("ERROR: " + label + " is not valid JSON" + where(ex) + " - skipped.");
				continue;
			}
			parsed.put(id, json);
			changes.put(id, apply(server, id, json, false, owner));
		}

		boolean any = changes.values().stream().anyMatch(lines -> lines.stream().anyMatch(PlayerProgress::isChange));
		if (!dry && any) {
			// keep what is about to be overwritten, so an import can be undone
			Path backup = root().resolve("backups").resolve(LocalDateTime.now().format(STAMP));
			try {
				Files.createDirectories(backup);
				for (var e : changes.entrySet()) {
					if (e.getValue().stream().noneMatch(PlayerProgress::isChange)) continue;
					String name = nameOf(server, e.getKey());
					write(backup.resolve(fileName(e.getKey(), name)), snapshot(server, e.getKey(), name));
				}
				report.add("Backed up the old state to " + root().relativize(backup.toAbsolutePath()).toString().replace('\\', '/'));
			} catch (IOException ex) {
				report.add("ERROR: can't write the backup (" + ex.getMessage() + ") - nothing imported.");
				AuraPoints.LOGGER.error("Progress backup failed", ex);
				return report;
			}
		}

		for (var e : changes.entrySet()) {
			UUID id = e.getKey();
			String who = Objects.requireNonNullElse(nameOf(server, id), id.toString());
			List<String> lines = dry ? e.getValue() : apply(server, id, parsed.get(id), true, owner);
			long count = lines.stream().filter(PlayerProgress::isChange).count();
			if (lines.isEmpty()) {
				report.add(who + ": no changes");
				continue;
			}
			report.add(who + ": " + count + " change(s)" + (dry ? " (dry run - nothing applied)" : " applied"));
			for (String line : lines) report.add(line.startsWith("WARNING") || line.startsWith("ERROR") ? line : "  " + line);
		}
		if (!dry && any) state.markDirty();
		return report;
	}

	/** " (line 12 column 3)" from a parse error, so the report says where the typo is. */
	private static String where(Exception e) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("line (\\d+) column (\\d+)").matcher(String.valueOf(e.getMessage()));
		return m.find() ? " (line " + m.group(1) + " column " + m.group(2) + ")" : "";
	}

	private static boolean isChange(String line) {
		return !line.startsWith("WARNING") && !line.startsWith("ERROR");
	}

	private static JsonObject read(Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	/** Every file in players/ by the uuid inside it (a file without a readable uuid is reported and skipped). */
	private static Map<UUID, Path> existingFiles(List<String> report) {
		Map<UUID, Path> out = new LinkedHashMap<>();
		if (!Files.isDirectory(playersDir())) return out;
		try (Stream<Path> list = Files.list(playersDir())) {
			for (Path path : list.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				String label = path.getFileName().toString();
				JsonObject json;
				try {
					json = read(path);
				} catch (Exception e) {
					report.add("ERROR: " + label + " is not valid JSON" + where(e) + " - skipped.");
					continue;
				}
				try {
					UUID id = UUID.fromString(json.get("uuid").getAsString());
					Path dup = out.put(id, path);
					if (dup != null) report.add("WARNING: " + label + " and " + dup.getFileName() + " are the same player - using " + label + ".");
				} catch (Exception e) {
					report.add("ERROR: " + label + " has no valid \"uuid\" - skipped.");
				}
			}
		} catch (IOException e) {
			report.add("ERROR: can't list " + playersDir() + " (" + e.getMessage() + ")");
		}
		return out;
	}

	/**
	 * Compares the file with the player's state; with {@code write} also applies it.
	 * Returns one line per change ("mastery.fire: 2 -> 5") plus WARNING lines.
	 */
	private static List<String> apply(MinecraftServer server, UUID id, JsonObject json, boolean write, boolean owner) {
		List<String> out = new ArrayList<>();
		AuraState state = AuraState.get(server);
		AuraPlayerData data = state.getOrCreate(id);
		ServerPlayer online = server.getPlayerList().getPlayer(id);
		String name = nameOf(server, id);
		boolean auraChanged = false;

		// aura
		if (json.has("aura")) {
			JsonElement v = json.get("aura");
			String raw = v.isJsonNull() ? "" : asString(v);
			AuraType to = raw == null || raw.isBlank() ? null : AuraType.byId(raw.trim().toLowerCase(Locale.ROOT));
			if (raw == null || (!raw.isBlank() && to == null)) {
				out.add("WARNING: aura \"" + v + "\" is not an aura - skipped.");
			} else if (to != data.aura) {
				out.add("aura: " + id(data.aura) + " -> " + id(to));
				if (write) data.aura = to;
				auraChanged = true;
			}
		}

		// plain flags
		Boolean freeRoll = bool(json, "freeRollUsed", out);
		if (freeRoll != null && freeRoll != data.freeRollUsed) {
			out.add("freeRollUsed: " + data.freeRollUsed + " -> " + freeRoll);
			if (write) data.freeRollUsed = freeRoll;
		}
		Boolean locked = bool(json, "locked", out);
		if (locked != null && locked != data.locked) {
			if (!owner) {
				out.add("WARNING: only the server owner can lock or unlock an aura - locked skipped.");
			} else {
				out.add("locked: " + data.locked + " -> " + locked);
				if (write) data.locked = locked;
			}
		}

		// owned abilities
		if (json.has("owned")) {
			Set<AuraAbilities.Ability> to = EnumSet.noneOf(AuraAbilities.Ability.class);
			if (enumList(json.get("owned"), "owned", out, s -> ability(s), to)) {
				Set<AuraAbilities.Ability> added = EnumSet.copyOf(to);
				added.removeAll(data.owned);
				Set<AuraAbilities.Ability> removed = data.owned.isEmpty() ? EnumSet.noneOf(AuraAbilities.Ability.class) : EnumSet.copyOf(data.owned);
				removed.removeAll(to);
				for (AuraAbilities.Ability a : added) out.add("owned: + " + a.name().toLowerCase(Locale.ROOT));
				for (AuraAbilities.Ability a : removed) out.add("owned: - " + a.name().toLowerCase(Locale.ROOT));
				if (write && (!added.isEmpty() || !removed.isEmpty())) {
					data.owned.clear();
					data.owned.addAll(to);
				}
			}
		}

		// awakened auras
		if (json.has("awakened")) {
			Set<AuraType> to = EnumSet.noneOf(AuraType.class);
			if (enumList(json.get("awakened"), "awakened", out, s -> AuraType.byId(s), to)) {
				for (AuraType t : AuraType.values()) {
					boolean was = data.awakened.contains(t), now = to.contains(t);
					if (was == now) continue;
					out.add("awakened: " + (now ? "+ " : "- ") + t.id());
					if (write) {
						if (now) data.awakened.add(t);
						else data.awakened.remove(t);
					}
				}
			}
		}

		// mastery levels: the whole list
		if (json.has("mastery")) {
			Map<AuraType, Integer> to = new EnumMap<>(AuraType.class);
			if (!json.get("mastery").isJsonObject()) {
				out.add("WARNING: mastery must be {\"fire\": 3, ...} - skipped.");
			} else {
				for (var e : json.getAsJsonObject("mastery").entrySet()) {
					AuraType t = AuraType.byId(e.getKey().toLowerCase(Locale.ROOT));
					Integer level = integer(e.getValue());
					if (t == null) out.add("WARNING: mastery." + e.getKey() + " - not an aura, skipped.");
					else if (level == null) out.add("WARNING: mastery." + e.getKey() + " = " + e.getValue() + " is not a whole number - skipped.");
					else {
						int clamped = Math.max(0, Math.min(Mastery.MAX, level));
						if (clamped != level) out.add("WARNING: mastery." + t.id() + " = " + level + " - clamped to " + clamped + ".");
						if (clamped > 0) to.put(t, clamped);
					}
				}
				for (AuraType t : AuraType.values()) {
					int was = data.mastery.getOrDefault(t, 0), now = to.getOrDefault(t, 0);
					if (was == now) continue;
					out.add("mastery." + t.id() + ": " + was + " -> " + now);
					if (write) {
						if (now == 0) data.mastery.remove(t);
						else data.mastery.put(t, now);
					}
				}
			}
		}

		// Aura balance (scoreboard, works offline by name)
		Integer points = count(json, "auraPoints", out);
		if (points != null) {
			if (name == null) {
				out.add("WARNING: auraPoints - the player's name is unknown, skipped.");
			} else {
				int was = score(server, name);
				if (was != points) {
					out.add("auraPoints: " + was + " -> " + points);
					if (write) server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), AuraCurrency.objective(server)).set(points);
				}
			}
		}

		// skill points: Puffish only takes commands for an online player
		Integer bought = count(json, "skillPointsBought", out);
		if (bought != null && bought != data.skillPointsBought) {
			if (online == null) {
				out.add("WARNING: skillPointsBought - " + who(name, id) + " is offline, skipped (import again while they are on).");
			} else if (bought < data.skillPointsBought) {
				out.add("WARNING: skillPointsBought can only go up here (Puffish points can't be taken back) - use /aura resetprogress, skipped.");
			} else {
				out.add("skillPointsBought: " + data.skillPointsBought + " -> " + bought + " (+" + (bought - data.skillPointsBought) + " skill points)");
				if (write) {
					PuffishBridge.addPoints(online, AuraConfig.PUFFISH_CATEGORY_MAIN, bought - data.skillPointsBought);
					data.skillPointsBought = bought;
				}
			}
		}
		Boolean pvp = bool(json, "pvpUnlocked", out);
		if (pvp != null && pvp != data.pvpUnlocked) {
			if (online == null) {
				out.add("WARNING: pvpUnlocked - " + who(name, id) + " is offline, skipped (import again while they are on).");
			} else {
				out.add("pvpUnlocked: " + data.pvpUnlocked + " -> " + pvp);
				if (write) {
					data.pvpUnlocked = pvp;
					if (pvp) PuffishBridge.unlockCategory(online, AuraConfig.PUFFISH_CATEGORY_PVP);
					else {
						// like the Rokaka Fruit: spent PvP points come back, then the tree locks
						PuffishBridge.resetSkills(online, AuraConfig.PUFFISH_CATEGORY_PVP);
						PuffishBridge.lockCategory(online, AuraConfig.PUFFISH_CATEGORY_PVP);
					}
				}
			}
		}

		// counters: mastery XP and the trial stats (vanilla stats, need the player online)
		Map<ResourceLocation, Integer> wanted = new LinkedHashMap<>();
		if (json.has("masteryXp")) {
			if (!json.get("masteryXp").isJsonObject()) out.add("WARNING: masteryXp must be {\"fire\": 1200, ...} - skipped.");
			else for (var e : json.getAsJsonObject("masteryXp").entrySet()) {
				AuraType t = AuraType.byId(e.getKey().toLowerCase(Locale.ROOT));
				ResourceLocation stat = t == null ? null : AuraStats.mastery(t);
				Integer v = integer(e.getValue());
				if (stat == null) out.add("WARNING: masteryXp." + e.getKey() + " - not an aura with mastery, skipped.");
				else if (v == null || v < 0) out.add("WARNING: masteryXp." + e.getKey() + " = " + e.getValue() + " must be 0 or more - skipped.");
				else wanted.put(stat, v);
			}
		}
		if (json.has("stats")) {
			if (!json.get("stats").isJsonObject()) out.add("WARNING: stats must be {\"fireball_hits\": 52, ...} - skipped.");
			else for (var e : json.getAsJsonObject("stats").entrySet()) {
				ResourceLocation stat = trialStat(e.getKey());
				Integer v = integer(e.getValue());
				if (stat == null) out.add("WARNING: stats." + e.getKey() + " - not an Aura stat, skipped.");
				else if (v == null || v < 0) out.add("WARNING: stats." + e.getKey() + " = " + e.getValue() + " must be 0 or more - skipped.");
				else wanted.put(stat, v);
			}
		}
		if (!wanted.isEmpty()) {
			Map<ResourceLocation, Integer> now = online != null ? liveStats(online) : savedStats(server, id);
			List<ResourceLocation> differ = wanted.keySet().stream().filter(s -> !wanted.get(s).equals(now.getOrDefault(s, 0))).toList();
			if (!differ.isEmpty() && online == null) {
				out.add("WARNING: " + differ.size() + " stat/masteryXp change(s) - " + who(name, id) + " is offline, skipped (import again while they are on).");
			} else {
				for (ResourceLocation stat : differ) {
					String key = AuraStats.isMastery(stat) ? "masteryXp." + stat.getPath().substring("mastery_".length()) : "stats." + stat.getPath();
					out.add(key + ": " + now.getOrDefault(stat, 0) + " -> " + wanted.get(stat));
					if (write) online.getStats().setValue(online, Stats.CUSTOM.get(stat), wanted.get(stat));
				}
			}
		}

		// an online player sees it straight away (the same refresh /aura setaura does)
		if (write && online != null && out.stream().anyMatch(PlayerProgress::isChange)) {
			NetworkHandler.syncOwned(online, data);
			AuraAbilities.refreshBerserkerModifier(online, AuraAbilities.hasAbility(data, AuraAbilities.Ability.BERSERKER));
			if (auraChanged) AuraStats.grantAuraAdvancement(online, data);
			NetworkHandler.syncAura(online, data);
			NetworkHandler.syncDisabled(online);
		}
		return out;
	}

	// ---------------------------------------------------------------
	// Parsing helpers (each reports its own problem and returns null)
	// ---------------------------------------------------------------

	private static String id(AuraType t) {
		return t == null ? "none" : t.id();
	}

	private static String who(String name, UUID id) {
		return name != null ? name : id.toString();
	}

	private static String asString(JsonElement v) {
		return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
	}

	private static Integer integer(JsonElement v) {
		if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return null;
		double d = v.getAsDouble();
		return d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (int) d : null;
	}

	private static Boolean bool(JsonObject json, String key, List<String> out) {
		if (!json.has(key)) return null;
		JsonElement v = json.get(key);
		if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) return v.getAsBoolean();
		out.add("WARNING: " + key + " = " + v + " is not true/false - skipped.");
		return null;
	}

	/** A whole number of 0 or more. */
	private static Integer count(JsonObject json, String key, List<String> out) {
		if (!json.has(key)) return null;
		Integer v = integer(json.get(key));
		if (v == null || v < 0) {
			out.add("WARNING: " + key + " = " + json.get(key) + " must be a whole number, 0 or more - skipped.");
			return null;
		}
		return v;
	}

	/** Fills {@code into} from a list of names; unknown names are reported and left out. False if it isn't a list. */
	private static <T> boolean enumList(JsonElement v, String key, List<String> out, java.util.function.Function<String, T> lookup, Set<T> into) {
		if (!v.isJsonArray()) {
			out.add("WARNING: " + key + " must be a list like [\"a\", \"b\"] - skipped.");
			return false;
		}
		for (JsonElement e : v.getAsJsonArray()) {
			String s = asString(e);
			T t = s == null ? null : lookup.apply(s.trim().toLowerCase(Locale.ROOT));
			if (t == null) out.add("WARNING: " + key + " " + e + " is not known - skipped.");
			else into.add(t);
		}
		return true;
	}

	private static AuraAbilities.Ability ability(String name) {
		try {
			return AuraAbilities.Ability.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static ResourceLocation trialStat(String key) {
		String path = key.toLowerCase(Locale.ROOT);
		if (path.startsWith(AuraPoints.MOD_ID + ":")) path = path.substring(AuraPoints.MOD_ID.length() + 1);
		for (ResourceLocation stat : AuraStats.all()) if (!AuraStats.isMastery(stat) && stat.getPath().equals(path)) return stat;
		return null;
	}
}
