package com.toast.aurapoints.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.shim.fml.loading.FMLPaths;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The worthy: players the owner allows to wield what is otherwise owner-only (Mjölnir, the aura handhelds, the World Tree
 * Bow), from config/aurapoints-worthy.json (names or UUIDs). Only the owner changes the list (/aura worthy add|remove);
 * the whole system can be switched off with the WORTHY_ENABLED balance value (then only owners are worthy).
 * The owner is always worthy; see AuraCommand.isWorthy.
 */
public final class Worthy {

	private Worthy() {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String INFO = "Aura Points - players besides the owner who may wield Mjolnir, the aura handhelds and the World Tree Bow. "
			+ "worthy: player names or UUIDs. Change it with /aura worthy add/remove (owner only). The switch is WORTHY_ENABLED in aurapoints-general.json.";

	private static List<String> worthy;

	private static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve("aurapoints-worthy.json");
	}

	private static synchronized void ensureLoaded() {
		if (worthy != null) return;
		worthy = new ArrayList<>();
		Path path = file();
		if (!Files.exists(path)) return;
		try {
			JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			if (json.has("worthy")) for (JsonElement e : json.getAsJsonArray("worthy")) worthy.add(e.getAsString());
		} catch (Exception e) {
			AuraPoints.LOGGER.error("Can't read {} - only the owner is worthy until it's fixed.", path, e);
		}
	}

	private static synchronized void save() {
		JsonObject json = new JsonObject();
		json.addProperty("_info", INFO);
		JsonArray list = new JsonArray();
		for (String s : worthy) list.add(s);
		json.add("worthy", list);
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
		worthy = null;
		ensureLoaded();
	}

	/** On the list (not looking at the switch or the owner). */
	public static synchronized boolean listed(ServerPlayer player) {
		ensureLoaded();
		String name = player.getGameProfile().getName();
		String id = player.getUUID().toString();
		for (String s : worthy) if (s.equalsIgnoreCase(name) || s.equalsIgnoreCase(id)) return true;
		return false;
	}

	public static synchronized List<String> list() {
		ensureLoaded();
		return List.copyOf(worthy);
	}

	/** False if already listed. */
	public static synchronized boolean add(String who) {
		ensureLoaded();
		for (String s : worthy) if (s.equalsIgnoreCase(who)) return false;
		worthy.add(who);
		save();
		return true;
	}

	/** False if not listed. */
	public static synchronized boolean remove(String who) {
		ensureLoaded();
		boolean removed = worthy.removeIf(s -> s.equalsIgnoreCase(who));
		if (removed) save();
		return removed;
	}
}
