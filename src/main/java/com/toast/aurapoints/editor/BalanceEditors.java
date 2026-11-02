package com.toast.aurapoints.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.toast.aurapoints.AuraCommand;
import com.toast.aurapoints.AuraPoints;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Who may use the Balance Tome, from config/aurapoints-editors.json: the owner always, plus
 * everyone on "whitelist" (names or UUIDs). Only the owner changes the list (/aura balance whitelist).
 * The file also remembers each editor's saving mode.
 */
public final class BalanceEditors {

	private BalanceEditors() {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String INFO = "Aura Points - who may use the Balance Tome besides the owner. whitelist: player names or UUIDs. "
			+ "Change it with /aura balance whitelist add/remove (owner only). modes: each editor's saving mode.";

	private static List<String> whitelist;
	private static final Map<String, BalanceEditor.Mode> MODES = new LinkedHashMap<>();

	private static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve("aurapoints-editors.json");
	}

	private static synchronized void ensureLoaded() {
		if (whitelist != null) return;
		whitelist = new ArrayList<>();
		Path path = file();
		if (!Files.exists(path)) return;
		try {
			JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			if (json.has("whitelist")) for (JsonElement e : json.getAsJsonArray("whitelist")) whitelist.add(e.getAsString());
			if (json.has("modes")) {
				for (var e : json.getAsJsonObject("modes").entrySet()) {
					try {
						MODES.put(e.getKey(), BalanceEditor.Mode.valueOf(e.getValue().getAsString()));
					} catch (IllegalArgumentException ignored) {
					}
				}
			}
		} catch (Exception e) {
			AuraPoints.LOGGER.error("Can't read {} - only the owner can use the Balance Tome until it's fixed.", path, e);
		}
	}

	private static synchronized void save() {
		JsonObject json = new JsonObject();
		json.addProperty("_info", INFO);
		JsonArray list = new JsonArray();
		for (String s : whitelist) list.add(s);
		json.add("whitelist", list);
		JsonObject modes = new JsonObject();
		for (var e : MODES.entrySet()) modes.addProperty(e.getKey(), e.getValue().name());
		json.add("modes", modes);
		Path target = file();
		try {
			Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			AuraPoints.LOGGER.error("Can't write {}", target, e);
		}
	}

	/** Re-read on /aura reload, so hand edits to the file count. */
	public static synchronized void reload() {
		whitelist = null;
		MODES.clear();
		ensureLoaded();
	}

	public static boolean allowed(Player player) {
		if (player == null) return false;
		if (AuraCommand.isOwner(player)) return true;
		return listed(player.getGameProfile().getName(), player.getUUID());
	}

	private static synchronized boolean listed(String name, UUID id) {
		ensureLoaded();
		for (String s : whitelist) if (s.equalsIgnoreCase(name) || s.equalsIgnoreCase(id.toString())) return true;
		return false;
	}

	public static synchronized List<String> whitelist() {
		ensureLoaded();
		return List.copyOf(whitelist);
	}

	/** False if already listed. */
	public static synchronized boolean add(String who) {
		ensureLoaded();
		for (String s : whitelist) if (s.equalsIgnoreCase(who)) return false;
		whitelist.add(who);
		save();
		return true;
	}

	/** False if not listed. */
	public static synchronized boolean remove(String who) {
		ensureLoaded();
		boolean removed = whitelist.removeIf(s -> s.equalsIgnoreCase(who));
		if (removed) save();
		return removed;
	}

	/** Saving mode, "reload after closing" until the editor picks another. */
	public static synchronized BalanceEditor.Mode mode(UUID id) {
		ensureLoaded();
		return MODES.getOrDefault(id.toString().toLowerCase(Locale.ROOT), BalanceEditor.Mode.ON_CLOSE);
	}

	public static synchronized void setMode(UUID id, BalanceEditor.Mode mode) {
		ensureLoaded();
		MODES.put(id.toString().toLowerCase(Locale.ROOT), mode);
		save();
	}
}
