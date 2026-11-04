package com.toast.aurapoints.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.shim.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hot-reloadable balance values, split over six files in config/:
 *   aurapoints-general.json  - economy, bosses, the Rokaka Fruit, and abilities several auras share (Dash, Lifesteal)
 *   aurapoints-shadow.json / -water.json / -fire.json / -wind.json / -frost.json - that aura's own abilities
 * Together they hold every non-final field of {@link AuraConfig} by name, and each has an
 * ABILITIES_ENABLED section for its abilities. Edit, then /aura reload (or /reload) - no restart.
 *
 * Missing files and values are added with their defaults; the rest is left as written. The old
 * single file aurapoints-balance.json is read once for its values, then renamed to .migrated.
 */
public final class AuraBalance {

	private AuraBalance() {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String INFO_KEY = "_info";
	/** Section with one true/false per ability; false switches it off for everyone. */
	private static final String ENABLED_KEY = "ABILITIES_ENABLED";
	private static final String UNITS = "TICKS: 20 = 1 second. AMPLIFIER: 0 = level I, 1 = II, 2 = III. MULTIPLIER: 1.3 = +30%. "
			+ "FRACTION / REDUCTION: 0.2 = 20%. ABILITIES_ENABLED: false switches an ability off for everyone.";
	private static final String LEGACY = "aurapoints-balance.json";

	/** The five files, in order: general first, then one per aura. */
	private static final List<String> SECTIONS = List.of("general", "shadow", "water", "fire", "wind", "frost", "verdant", "thunder", "resonance", "earth", "light", "blood", "void", "handhelds", "arena", "ultimates", "reactions", "combos", "touched", "weekly");

	/** Field name prefix -> section. Anything not listed is general. */
	private static final Map<String, String> PREFIXES = new LinkedHashMap<>();

	static {
		// Wind's Dash Dive before the shared DASH_ prefix
		for (String p : List.of("WIND_ROLLABLE", "DASH_DIVE_", "DOUBLE_JUMP_", "SUPER_WIND_CHARGE_", "GALE_RIDER_", "FEATHERFALL_", "WIND_LAUNCH_", "AIR_BENDING_")) PREFIXES.put(p, "wind");
		for (String p : List.of("VANISH_", "SHADOW_", "PHANTOM_STEP_", "BLOODRUSH_")) PREFIXES.put(p, "shadow");
		for (String p : List.of("WATER_ROLLABLE", "SHOCKWAVE_", "HEALING_TIDE_", "IRON_SKIN_", "FLOW_", "BUBBLE_")) PREFIXES.put(p, "water");
		for (String p : List.of("FROST_", "ICE_SHELL_", "SHATTER_", "ICE_SPIKE_")) PREFIXES.put(p, "frost");
		for (String p : List.of("VERDANT_", "VINE_HOOK_", "BLOOM_", "VIRULENCE_", "ENTANGLE_", "VIPER_")) PREFIXES.put(p, "verdant");
		for (String p : List.of("THUNDER_", "THUNDERBOLT_", "STATIC_CHARGE_", "STORMBORN_", "CHIDORI_", "MJOLNIR_")) PREFIXES.put(p, "thunder");
		for (String p : List.of("RESONANCE_", "NOISE_", "ECHO_", "BLOSSOM_", "LISTENING_TREE_", "GRASP_")) PREFIXES.put(p, "resonance");
		for (String p : List.of("HANDHELD_", "MAELSTROM_", "SUNFALL_", "STORM_EYE_", "UMBRAL_", "THORN_SENTINEL_", "WINTERS_GATE_", "CHAIN_STRIKE_")) PREFIXES.put(p, "handhelds");
		for (String p : List.of("FIRE_ROLLABLE", "FIREBALL_", "HEAT_WAVE_", "FIRE_BENDING_", "INFERNO_SLAM_", "BERSERKER_", "EXECUTIONER_",
				"FIRE_COMBO_", "AURA_FIRE_", "FIREBORN_")) PREFIXES.put(p, "fire");
		for (String p : List.of("ARENA_", "DUEL_", "TRAINING_", "MATCH_", "WAVES_")) PREFIXES.put(p, "arena");
		PREFIXES.put("EARTH_", "earth");
		PREFIXES.put("LIGHT_", "light");
		PREFIXES.put("BLOOD_", "blood");
		PREFIXES.put("VOID_", "void");
		for (String p : List.of("ULT_", "ULTIMATE_")) PREFIXES.put(p, "ultimates");
		PREFIXES.put("REACTION_", "reactions");
		PREFIXES.put("COMBO_", "combos");
		PREFIXES.put("TOUCHED_", "touched");
		for (String p : List.of("WEEKLY_", "COSMETIC")) PREFIXES.put(p, "weekly");
	}

	/** Each value as the code sets it, before any file is read: what the editor's reset goes back to. */
	private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

	static {
		for (Field f : fields()) {
			try {
				DEFAULTS.put(f.getName(), f.get(null));
			} catch (IllegalAccessException ignored) {
			}
		}
	}

	/**
	 * One value the in-game editor can change. key is the JSON key; an on/off switch is
	 * "ABILITIES_ENABLED.<ABILITY>" and has field null. section is the file it lives in; page is the
	 * Balance Tome menu it shows on (the section, except the Ultimates that are kept in their aura's
	 * file, which show under Ultimates). group is the ability submenu: the name prefix it shares with
	 * its neighbours ("VINE_HOOK_"), each ability's switch with its values; ROLLABLE_GROUP for an aura's
	 * "rollable" switch (the tome shows that one as a dye on the aura's first page).
	 */
	public record Entry(String key, String section, String page, String group, Class<?> type, Object defaultValue, Field field, AuraAbilities.Ability ability) {

		public boolean isSwitch() {
			return ability != null;
		}

		/** The value in force right now. */
		public Object current() {
			if (isSwitch()) return !AuraAbilities.DISABLED.contains(ability);
			try {
				return field.get(null);
			} catch (IllegalAccessException e) {
				return defaultValue;
			}
		}
	}

	private static List<Entry> entries;
	private static Map<String, Entry> byKey;

	/** The sections in file order. */
	public static List<String> sections() {
		return SECTIONS;
	}

	/** Every editable value: the fields first, each ability's switch first in its group. */
	public static synchronized List<Entry> entries() {
		if (entries != null) return entries;
		List<Entry> values = new ArrayList<>();
		java.util.Set<String> groups = new java.util.HashSet<>();
		for (Field f : fields()) {
			String section = sectionOf(f);
			String group = f.getName().endsWith("_ROLLABLE") ? ROLLABLE_GROUP : groupOf(f.getName());
			groups.add(section + "/" + group);
			values.add(new Entry(f.getName(), section, pageOf(section, group), group, f.getType(), DEFAULTS.get(f.getName()), f, null));
		}
		List<Entry> out = new ArrayList<>();
		for (AuraAbilities.Ability ability : AuraAbilities.Ability.values()) {
			String section = sectionOf(ability);
			// every ability gets its own submenu, its switch first, even one without values of its own
			String group = ABILITY_GROUPS.getOrDefault(ability.name(), ability.name() + "_");
			out.add(new Entry(ENABLED_KEY + "." + ability.name(), section, pageOf(section, group), group, boolean.class, true, null, ability));
		}
		out.addAll(values);
		Map<String, Entry> index = new LinkedHashMap<>();
		for (Entry e : out) index.put(e.key(), e);
		byKey = index;
		entries = List.copyOf(out);
		return entries;
	}

	public static Entry entry(String key) {
		entries();
		return byKey.get(key);
	}

	/** The group of an aura's "rollable" switch (FROST_ROLLABLE ...). */
	public static final String ROLLABLE_GROUP = "ROLLABLE";

	/**
	 * Finer groups than the file prefixes, one per ability, for the auras whose values all share the aura's own prefix
	 * (EARTH_..., ULT_...). Only the tome's submenus use them; which file a value lives in doesn't change.
	 */
	private static final List<String> SUBGROUPS = List.of(
			"SHADOW_NIGHTSTALKER_",
			"EARTH_PILLAR_", "EARTH_BOULDER_", "EARTH_QUAKE_", "EARTH_STONE_SKIN_", "EARTH_GROUNDED_", "EARTH_RIFT_",
			"LIGHT_PULSE_", "LIGHT_FLASH_", "LIGHT_BEAM_", "LIGHT_HALO_", "LIGHT_LUMINOUS_", "LIGHT_SUN_", "LIGHT_UNDEAD_",
			"BLOOD_HEMO_", "BLOOD_BLEED_", "BLOOD_TETHER_", "BLOOD_SACRIFICE_", "BLOOD_LUST_", "BLOOD_THIN_", "BLOOD_MOON_",
			"VOID_PULL_", "VOID_RIFT_", "VOID_ORB_", "VOID_HORIZON_", "VOID_ENTROPY_", "VOID_HOLE_",
			"ULT_ECLIPSE_", "ULT_TIDAL_", "ULT_PHOENIX_", "ULT_TEMPEST_", "ULT_PERMAFROST_", "ULT_OVERGROWTH_", "ULT_JUDGEMENT_",
			"REACTION_FREEZE_", "REACTION_CONDUCT_", "REACTION_FIRESTORM_", "REACTION_TOXIC_", "REACTION_STEAM_", "REACTION_MELT_",
			"REACTION_OVERGROWTH_", "REACTION_STORM_",
			"COMBO_TORNADO_", "COMBO_SHOCK_", "COMBO_GALE_", "COMBO_GLACIER_", "COMBO_MAGMA_", "COMBO_BOIL_", "COMBO_PRISM_");

	/** Abilities whose values go by another prefix than their own name: ability -> that group. */
	private static final Map<String, String> ABILITY_GROUPS = Map.ofEntries(
			Map.entry("ECHOLOCATION", "ECHO_"), Map.entry("SONIC_BLOSSOM", "BLOSSOM_"), Map.entry("ABYSSAL_GRASP", "GRASP_"),
			Map.entry("ENTANGLEMENT", "ENTANGLE_"), Map.entry("VERDANT_VIPER", "VIPER_"), Map.entry("ABYSSAL_BUBBLE", "BUBBLE_"),
			Map.entry("EYE_OF_THE_STORM", "STORM_EYE_"), Map.entry("UMBRAL_AMBUSH", "UMBRAL_"), Map.entry("NIGHTSTALKER", "SHADOW_NIGHTSTALKER_"),
			Map.entry("PILLAR_LIFT", "EARTH_PILLAR_"), Map.entry("BOULDER_TOSS", "EARTH_BOULDER_"), Map.entry("QUAKE", "EARTH_QUAKE_"),
			Map.entry("STONE_SKIN", "EARTH_STONE_SKIN_"), Map.entry("GROUNDED", "EARTH_GROUNDED_"), Map.entry("TECTONIC_RIFT", "EARTH_RIFT_"),
			Map.entry("RADIANT_PULSE", "LIGHT_PULSE_"), Map.entry("SOLAR_FLASH", "LIGHT_FLASH_"), Map.entry("PRISM_BEAM", "LIGHT_BEAM_"),
			Map.entry("HALO", "LIGHT_HALO_"), Map.entry("LUMINOUS", "LIGHT_LUMINOUS_"), Map.entry("SUNBURST", "LIGHT_SUN_"),
			Map.entry("HEMORRHAGE", "BLOOD_HEMO_"), Map.entry("CRIMSON_SACRIFICE", "BLOOD_SACRIFICE_"), Map.entry("BLOODLUST", "BLOOD_LUST_"),
			Map.entry("THIN_BLOOD", "BLOOD_THIN_"), Map.entry("BLOOD_MOON", "BLOOD_MOON_"),
			Map.entry("RIFT_STEP", "VOID_RIFT_"), Map.entry("NULL_ORB", "VOID_ORB_"), Map.entry("EVENT_HORIZON", "VOID_HORIZON_"),
			Map.entry("ENTROPY", "VOID_ENTROPY_"), Map.entry("SINGULARITY", "VOID_HOLE_"),
			Map.entry("ECLIPSE", "ULT_ECLIPSE_"), Map.entry("TIDAL_ASCENSION", "ULT_TIDAL_"), Map.entry("PHOENIX", "ULT_PHOENIX_"),
			Map.entry("TEMPEST", "ULT_TEMPEST_"), Map.entry("PERMAFROST", "ULT_PERMAFROST_"), Map.entry("OVERGROWTH", "ULT_OVERGROWTH_"),
			Map.entry("JUDGEMENT", "ULT_JUDGEMENT_"));

	/** Groups that are no single ability, with a name for the tome. */
	private static final Map<String, String> GROUP_TITLES = Map.ofEntries(
			Map.entry("BLOOD_BLEED_", "Bleeding (Hemorrhage, Blood Moon)"), Map.entry("LIGHT_UNDEAD_", "Undead Bonus"),
			Map.entry("ULTIMATE_", "All Ultimates"), Map.entry("SHADOW_", "Shadow (shared)"), Map.entry("NOISE_", "Noise Meter"),
			Map.entry("REACTION_", "Marks and Rules"), Map.entry("REACTION_FREEZE_", "Freeze (Wet + Frost)"),
			Map.entry("REACTION_CONDUCT_", "Conduct (Wet + Thunder)"), Map.entry("REACTION_FIRESTORM_", "Firestorm (Burning + Wind)"),
			Map.entry("REACTION_TOXIC_", "Toxic Cloud (Poisoned + Fire)"), Map.entry("REACTION_STEAM_", "Steam (Burning + Water)"),
			Map.entry("REACTION_MELT_", "Melt (Chilled + Fire)"), Map.entry("REACTION_OVERGROWTH_", "Overgrowth (Wet + Verdant)"),
			Map.entry("REACTION_STORM_", "Storm (Charged + Wind)"), Map.entry("TOUCHED_", "Aura-touched Mobs"),
			Map.entry("COMBO_", "Allies, Zones and Rewards"), Map.entry("COMBO_TORNADO_", "Fire Tornado (Fire + Wind)"),
			Map.entry("COMBO_SHOCK_", "Electrified Bubble (Bubble + Thunder)"), Map.entry("COMBO_GALE_", "Toxic Gale (Poison cloud + Wind)"),
			Map.entry("COMBO_GLACIER_", "Glacier (Bubble + Frost)"), Map.entry("COMBO_MAGMA_", "Magma Field (Fire + Earth)"),
			Map.entry("COMBO_BOIL_", "Boiling Bubble (Bubble + Fire)"), Map.entry("COMBO_PRISM_", "Prism Burst (Bubble + Light)"),
			Map.entry("WEEKLY_", "Weekly Challenges"), Map.entry("COSMETIC", "Cosmetics"));

	/** The Ultimates kept in their aura's file: shown on the Ultimates page anyway. */
	private static final java.util.Set<String> ULTIMATE_GROUPS = java.util.Set.of("EARTH_RIFT_", "LIGHT_SUN_", "BLOOD_MOON_", "VOID_HOLE_");

	private static String pageOf(String section, String group) {
		return ULTIMATE_GROUPS.contains(group) ? "ultimates" : section;
	}

	/** A group's name in the tome: the ability's own name where it is one ability, else a title or the prefix in words. */
	public static String groupTitle(String group) {
		if (group.equals(ENABLED_KEY)) return "Abilities On / Off";
		if (GROUP_TITLES.containsKey(group)) return GROUP_TITLES.get(group);
		for (AuraAbilities.Ability a : AuraAbilities.Ability.values()) {
			if (group.equals(ABILITY_GROUPS.getOrDefault(a.name(), a.name() + "_"))) return a.displayName;
		}
		StringBuilder out = new StringBuilder();
		for (String w : group.split("_")) {
			if (w.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(w.charAt(0)).append(w.substring(1).toLowerCase(java.util.Locale.ROOT));
		}
		return out.toString();
	}

	/** The prefix a value shares with the rest of its ability: the longest known one, else its first word. */
	private static String groupOf(String name) {
		String best = null;
		for (String p : SUBGROUPS) if (name.startsWith(p) && (best == null || p.length() > best.length())) best = p;
		for (String p : PREFIXES.keySet()) if (name.startsWith(p) && (best == null || p.length() > best.length())) best = p;
		if (best != null) return best;
		int cut = name.indexOf('_');
		return cut > 0 ? name.substring(0, cut + 1) : name;
	}

	/**
	 * Writes the given values (key -> new value, of the entry's type) into their files and
	 * reloads them. Returns the reload report, like /aura reload.
	 */
	public static synchronized List<String> apply(Map<String, Object> changes) {
		List<String> report = new ArrayList<>();
		Map<String, JsonObject> touched = new LinkedHashMap<>();
		for (var change : changes.entrySet()) {
			Entry e = entry(change.getKey());
			if (e == null) {
				report.add("ERROR: unknown value " + change.getKey() + " - skipped.");
				continue;
			}
			JsonObject json = touched.get(e.section());
			if (json == null) {
				try {
					json = read(file(e.section()));
				} catch (Exception ex) {
					report.add("ERROR: can't read aurapoints-" + e.section() + ".json (" + ex.getMessage() + ") - " + e.key() + " not changed.");
					continue;
				}
				if (json == null) json = new JsonObject();
				touched.put(e.section(), json);
			}
			if (e.isSwitch()) {
				JsonObject enabled = json.has(ENABLED_KEY) && json.get(ENABLED_KEY).isJsonObject() ? json.getAsJsonObject(ENABLED_KEY) : new JsonObject();
				enabled.addProperty(e.ability().name(), (Boolean) change.getValue());
				json.add(ENABLED_KEY, enabled);
			} else {
				json.add(e.key(), GSON.toJsonTree(change.getValue()));
			}
		}
		for (var t : touched.entrySet()) {
			try {
				write(file(t.getKey()), t.getValue());
			} catch (IOException ex) {
				report.add("ERROR: can't write aurapoints-" + t.getKey() + ".json (" + ex.getMessage() + ").");
				AuraPoints.LOGGER.error("Can't write {}", file(t.getKey()), ex);
			}
		}
		report.addAll(load());
		return report;
	}

	private static final java.time.format.DateTimeFormatter BACKUP_NAME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final int BACKUPS_KEPT = 30;

	/** Copies every balance file into config/aurapoints-backups/<time>/, keeping the newest 30. Returns the folder, or null. */
	public static synchronized Path backup() {
		Path root = FMLPaths.CONFIGDIR.get().resolve("aurapoints-backups");
		Path dir = root.resolve(java.time.LocalDateTime.now().format(BACKUP_NAME));
		try {
			Files.createDirectories(dir);
			for (String section : SECTIONS) {
				Path f = file(section);
				if (Files.exists(f)) Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
			try (var list = Files.list(root)) {
				List<Path> old = list.filter(Files::isDirectory).sorted().toList();
				for (int i = 0; i < old.size() - BACKUPS_KEPT; i++) {
					try (var files = Files.walk(old.get(i))) {
						for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
					}
				}
			}
			return dir;
		} catch (IOException e) {
			AuraPoints.LOGGER.error("Can't back up the balance files to {}", dir, e);
			return null;
		}
	}

	/** Writes beside the file and moves over, so a crash mid-write can't leave half a file. */
	private static void write(Path target, JsonObject json) throws IOException {
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	public static Path file(String section) {
		return FMLPaths.CONFIGDIR.get().resolve("aurapoints-" + section + ".json");
	}

	private static String sectionOf(Field f) {
		for (var e : PREFIXES.entrySet()) if (f.getName().startsWith(e.getKey())) return e.getValue();
		return "general";
	}

	/** An ability only one aura has goes in that aura's file; shared ones (Dash, Lifesteal) in general. */
	private static String sectionOf(AuraAbilities.Ability ability) {
		if (com.toast.aurapoints.ability.Handhelds.auraOf(ability) != null) return "handhelds";
		// the Ultimates of the auras that started out secret stay in that aura's own file (where they always were)
		AuraType ultOf = com.toast.aurapoints.ability.Ultimates.auraOf(ability);
		if (ultOf != null) return java.util.EnumSet.of(AuraType.EARTH, AuraType.LIGHT, AuraType.BLOOD, AuraType.VOID).contains(ultOf) ? ultOf.id() : "ultimates";
		AuraType only = null;
		for (AuraType type : AuraType.values()) {
			if (!type.abilities.contains(ability)) continue;
			if (only != null) return "general";
			only = type;
		}
		return only == null ? "general" : only.id();
	}

	/** The balance fields: public, static, not final, and a number or boolean. */
	private static List<Field> fields() {
		List<Field> out = new ArrayList<>();
		for (Field f : AuraConfig.class.getDeclaredFields()) {
			int m = f.getModifiers();
			if (!Modifier.isPublic(m) || !Modifier.isStatic(m) || Modifier.isFinal(m)) continue;
			Class<?> t = f.getType();
			if (t == int.class || t == long.class || t == float.class || t == double.class || t == boolean.class || t == String.class) out.add(f);
		}
		return out;
	}

	private static JsonObject read(Path path) throws IOException {
		return Files.exists(path) ? JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject() : null;
	}

	/**
	 * Reads the files and applies them. Returns one line per changed value
	 * ("VANISH_SPEED_AMPLIFIER: 2 -> 3") plus any problems; empty = nothing changed.
	 */
	public static synchronized List<String> load() {
		List<String> report = new ArrayList<>();
		Map<String, JsonObject> files = new LinkedHashMap<>();
		Map<String, Boolean> rewrite = new LinkedHashMap<>();
		for (String section : SECTIONS) {
			Path path = file(section);
			try {
				JsonObject json = read(path);
				files.put(section, json == null ? new JsonObject() : json);
				rewrite.put(section, json == null);
			} catch (Exception e) {
				report.add("ERROR: can't read " + path.getFileName() + " (" + e.getMessage() + ") - nothing changed.");
				AuraPoints.LOGGER.error("Can't read {}", path, e);
				return report;
			}
		}
		Path legacyPath = FMLPaths.CONFIGDIR.get().resolve(LEGACY);
		JsonObject legacy = null;
		try {
			legacy = read(legacyPath);
		} catch (Exception e) {
			report.add("WARNING: can't read the old " + LEGACY + " (" + e.getMessage() + ") - using defaults for anything missing.");
		}
		JsonObject legacyEnabled = legacy != null && legacy.has(ENABLED_KEY) && legacy.get(ENABLED_KEY).isJsonObject()
				? legacy.getAsJsonObject(ENABLED_KEY) : new JsonObject();

		// the values
		Map<String, String> known = new LinkedHashMap<>(); // key -> its section
		for (Field f : fields()) {
			String section = sectionOf(f);
			known.put(f.getName(), section);
			JsonObject json = files.get(section);
			JsonElement value = json.get(f.getName());
			try {
				if (value == null) {
					value = legacy != null && legacy.has(f.getName()) ? legacy.get(f.getName()) : GSON.toJsonTree(f.get(null));
					json.add(f.getName(), value);
					rewrite.put(section, true);
				}
				Object before = f.get(null);
				Object after = parse(f.getType(), value);
				if (!before.equals(after)) {
					f.set(null, after);
					report.add(f.getName() + ": " + before + " -> " + after);
				}
			} catch (Exception e) {
				report.add("ERROR: " + f.getName() + " = " + value + " is not a valid " + f.getType().getSimpleName() + " - kept the old value.");
			}
		}

		// the on/off switches, each in its aura's file
		Map<String, JsonObject> enabled = new LinkedHashMap<>();
		for (String section : SECTIONS) {
			JsonObject json = files.get(section);
			JsonObject s = json.has(ENABLED_KEY) && json.get(ENABLED_KEY).isJsonObject() ? json.getAsJsonObject(ENABLED_KEY) : new JsonObject();
			enabled.put(section, s);
		}
		Map<String, String> knownAbilities = new LinkedHashMap<>();
		for (AuraAbilities.Ability ability : AuraAbilities.Ability.values()) {
			String section = sectionOf(ability);
			knownAbilities.put(ability.name(), section);
			JsonObject s = enabled.get(section);
			JsonElement v = s.get(ability.name());
			if (v == null) {
				v = legacyEnabled.has(ability.name()) ? legacyEnabled.get(ability.name()) : new com.google.gson.JsonPrimitive(true);
				s.add(ability.name(), v);
				rewrite.put(section, true);
			}
			boolean on;
			try {
				on = v.getAsBoolean();
			} catch (Exception e) {
				report.add("ERROR: " + ENABLED_KEY + "." + ability.name() + " = " + v + " is not true/false - kept the old value.");
				continue;
			}
			boolean was = !AuraAbilities.DISABLED.contains(ability);
			if (on != was) {
				if (on) AuraAbilities.DISABLED.remove(ability);
				else AuraAbilities.DISABLED.add(ability);
				report.add(ENABLED_KEY + "." + ability.name() + ": " + was + " -> " + on);
			}
		}

		// keys that don't belong where they are
		for (String section : SECTIONS) {
			String name = "aurapoints-" + section + ".json";
			for (String key : files.get(section).keySet()) {
				if (key.equals(INFO_KEY) || key.equals(ENABLED_KEY)) continue;
				String home = known.get(key);
				if (home == null) report.add("WARNING: unknown key " + key + " in " + name + " (typo?) - ignored.");
				else if (!home.equals(section)) report.add("WARNING: " + key + " belongs in aurapoints-" + home + ".json, not " + name + " - ignored here.");
			}
			for (String key : enabled.get(section).keySet()) {
				String home = knownAbilities.get(key);
				if (home == null) report.add("WARNING: unknown ability " + ENABLED_KEY + "." + key + " in " + name + " (typo?) - ignored.");
				else if (!home.equals(section)) report.add("WARNING: " + ENABLED_KEY + "." + key + " belongs in aurapoints-" + home + ".json, not " + name + " - ignored here.");
			}
		}

		// write what changed: _info first, then the values, then the switches
		boolean written = true;
		for (String section : SECTIONS) {
			if (!rewrite.get(section)) continue;
			JsonObject json = files.get(section);
			JsonObject out = new JsonObject();
			out.addProperty(INFO_KEY, info(section));
			for (String key : json.keySet()) if (!key.equals(INFO_KEY) && !key.equals(ENABLED_KEY)) out.add(key, json.get(key));
			out.add(ENABLED_KEY, enabled.get(section));
			try {
				write(file(section), out);
			} catch (IOException e) {
				written = false;
				AuraPoints.LOGGER.error("Can't write {}", file(section), e);
			}
		}
		if (legacy != null && written) {
			try {
				Files.move(legacyPath, legacyPath.resolveSibling(LEGACY + ".migrated"), StandardCopyOption.REPLACE_EXISTING);
				report.add("Moved the values of " + LEGACY + " into aurapoints-general/shadow/water/fire/wind.json (old file kept as " + LEGACY + ".migrated).");
			} catch (IOException e) {
				AuraPoints.LOGGER.error("Can't rename {}", legacyPath, e);
			}
		}

		for (String line : report) AuraPoints.LOGGER.info("[balance] {}", line);
		// tell every online HUD which abilities are off now
		var server = com.toast.aurapoints.shim.neoforge.server.ServerLifecycleHooks.getCurrentServer();
		if (server != null) {
			for (var p : server.getPlayerList().getPlayers()) {
				com.toast.aurapoints.network.NetworkHandler.syncDisabled(p);
				com.toast.aurapoints.network.NetworkHandler.syncAccessorySlots(p);
			}
		}
		return report;
	}

	private static String info(String section) {
		String what = section.equals("handhelds")
				? "Aura Points - the handheld weapons' abilities (Maelstrom, Sunfall, Eye of the Storm, Umbral Ambush, Thorn Sentinel, Winter's Gate, Chain Strike). "
				: section.equals("weekly")
				? "Aura Points - weekly challenges and cosmetics. "
				: section.equals("touched")
				? "Aura Points - aura-touched mobs: a few natural hostile spawns carry an aura, an outline, a halo and one ability. "
				: section.equals("combos")
				? "Aura Points - team combos: an ally's ability passing through a zone you left (fire, bubble, poison cloud) sets off a combo. "
				: section.equals("reactions")
				? "Aura Points - elemental reactions: a hit leaves its element on the target, a matching element sets off a reaction. "
				: section.equals("general")
				? "Aura Points - general balance: economy, bosses, Rokaka Fruit, and abilities several auras share (Dash, Lifesteal). "
				: "Aura Points - " + section.substring(0, 1).toUpperCase(Locale.ROOT) + section.substring(1) + " aura balance. ";
		return what + "Edit, then /aura reload (or /reload). " + UNITS;
	}

	private static Object parse(Class<?> type, JsonElement v) {
		if (type == int.class) return v.getAsInt();
		if (type == long.class) return v.getAsLong();
		if (type == float.class) return v.getAsFloat();
		if (type == double.class) return v.getAsDouble();
		if (type == boolean.class) return v.getAsBoolean();
		if (type == String.class && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) return v.getAsString();
		throw new IllegalArgumentException(type.getName());
	}
}
