package com.toast.aurapoints.fabric;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.shim.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client settings as a small TOML file, laid out like NeoForge's (same sections, keys and comments), so the file
 * reads the same on both loaders. Only what the spec uses: [sections], key = true/false, numbers, "strings".
 */
final class FabricConfig {
	private FabricConfig() {
	}

	static void load(ModConfigSpec spec, Path file) {
		Map<String, String> raw = read(file);
		Map<ModConfigSpec.ConfigValue<?>, Object> values = new LinkedHashMap<>();
		for (ModConfigSpec.Op op : spec.ops()) {
			if (op instanceof ModConfigSpec.Value v) values.put(v.value(), parse(v.value(), raw.get(String.join(".", v.value().getPath()))));
		}
		Runnable save = () -> write(spec, values, file);
		for (var e : values.entrySet()) bind(e.getKey(), values, save);
		spec.bindSaver(save);
		save.run(); // fills in missing keys and fixes bad values, like NeoForge does
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void bind(ModConfigSpec.ConfigValue v, Map<ModConfigSpec.ConfigValue<?>, Object> values, Runnable save) {
		v.bind(() -> values.get(v), x -> values.put(v, x), save);
	}

	/** The file's value for this key, or the default when missing or not valid. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object parse(ModConfigSpec.ConfigValue<?> v, String text) {
		if (text == null) return v.getDefault();
		try {
			Object value;
			if (v instanceof ModConfigSpec.BooleanValue) {
				if (!text.equals("true") && !text.equals("false")) return v.getDefault();
				value = Boolean.parseBoolean(text);
			} else if (v instanceof ModConfigSpec.IntValue) value = Integer.parseInt(text);
			else if (v instanceof ModConfigSpec.DoubleValue) value = Double.parseDouble(text);
			else if (v instanceof ModConfigSpec.EnumValue ev) value = Enum.valueOf(ev.type, unquote(text));
			else value = unquote(text);
			return v.valid(value) ? value : v.getDefault();
		} catch (RuntimeException e) {
			return v.getDefault();
		}
	}

	private static String unquote(String s) {
		if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
			return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
		}
		return s;
	}

	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static Map<String, String> read(Path file) {
		Map<String, String> out = new LinkedHashMap<>();
		if (!Files.exists(file)) return out;
		try {
			String section = "";
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String t = line.strip();
				if (t.isEmpty() || t.startsWith("#")) continue;
				if (t.startsWith("[") && t.endsWith("]")) {
					section = t.substring(1, t.length() - 1).strip();
					continue;
				}
				int eq = t.indexOf('=');
				if (eq < 0) continue;
				String key = t.substring(0, eq).strip();
				String value = t.substring(eq + 1).strip();
				if (!value.startsWith("\"")) {
					int hash = value.indexOf('#');
					if (hash >= 0) value = value.substring(0, hash).strip();
				}
				out.put(section.isEmpty() ? key : section + "." + key, value);
			}
		} catch (IOException e) {
			AuraPoints.LOGGER.warn("Could not read {}: {}", file, e.toString());
		}
		return out;
	}

	private static synchronized void write(ModConfigSpec spec, Map<ModConfigSpec.ConfigValue<?>, Object> values, Path file) {
		StringBuilder sb = new StringBuilder();
		List<String> section = new ArrayList<>();
		for (ModConfigSpec.Op op : spec.ops()) {
			switch (op) {
				case ModConfigSpec.Push push -> {
					section.add(push.name());
					sb.append('\n');
					for (String c : push.comment()) sb.append("#").append(c).append('\n');
					sb.append('[').append(String.join(".", section)).append("]\n");
				}
				case ModConfigSpec.Pop pop -> section.remove(section.size() - 1);
				case ModConfigSpec.Value v -> {
					ModConfigSpec.ConfigValue<?> cv = v.value();
					for (String c : cv.comment()) sb.append(section.isEmpty() ? "" : "\t").append("#").append(c).append('\n');
					if (cv instanceof ModConfigSpec.IntValue iv) sb.append(section.isEmpty() ? "" : "\t").append("#Range: ").append(iv.min).append(" ~ ").append(iv.max).append('\n');
					if (cv instanceof ModConfigSpec.DoubleValue dv) sb.append(section.isEmpty() ? "" : "\t").append("#Range: ").append(dv.min).append(" ~ ").append(dv.max).append('\n');
					Object value = values.get(cv);
					String text = value instanceof Boolean || value instanceof Number ? String.valueOf(value)
							: quote(value instanceof Enum<?> en ? en.name() : String.valueOf(value));
					sb.append(section.isEmpty() ? "" : "\t").append(cv.getPath().get(cv.getPath().size() - 1)).append(" = ").append(text).append('\n');
				}
			}
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, sb.toString().stripLeading(), StandardCharsets.UTF_8);
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			AuraPoints.LOGGER.warn("Could not write {}: {}", file, e.toString());
		}
	}
}
