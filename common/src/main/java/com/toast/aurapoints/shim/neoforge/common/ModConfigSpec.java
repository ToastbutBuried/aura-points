package com.toast.aurapoints.shim.neoforge.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A config file's layout (sections, keys, defaults, comments), built like NeoForge's. Values live in memory until the
 * loader binds them: NeoForge to a real ModConfigSpec (same file as before), Fabric to its own TOML file.
 */
public class ModConfigSpec {
	/** The layout in order, for the loader to replay. */
	public sealed interface Op permits Push, Pop, Value {
	}

	public record Push(String name, String[] comment, String translation) implements Op {
	}

	public record Pop() implements Op {
	}

	public record Value(ConfigValue<?> value) implements Op {
	}

	private final List<Op> ops;
	private Runnable saver = () -> {
	};

	private ModConfigSpec(List<Op> ops) {
		this.ops = Collections.unmodifiableList(ops);
	}

	/** Called once by the loader layer. */
	public void bindSaver(Runnable saver) {
		this.saver = saver;
	}

	/** Writes the file now. */
	public void save() {
		saver.run();
	}

	public List<Op> ops() {
		return ops;
	}

	public static class ConfigValue<T> implements Supplier<T> {
		private final List<String> path;
		private final T defaultValue;
		private final String[] comment;
		private final String translation;
		private final Predicate<Object> validator;
		private T memory;
		private Supplier<T> getter;
		private Consumer<T> setter;
		private Runnable saver = () -> {
		};

		ConfigValue(List<String> path, T defaultValue, String[] comment, String translation, Predicate<Object> validator) {
			this.path = path;
			this.defaultValue = defaultValue;
			this.comment = comment;
			this.translation = translation;
			this.validator = validator;
			this.memory = defaultValue;
		}

		/** Called once by the loader layer. */
		public void bind(Supplier<T> getter, Consumer<T> setter, Runnable saver) {
			this.getter = getter;
			this.setter = setter;
			this.saver = saver;
		}

		@Override
		public T get() {
			return getter != null ? getter.get() : memory;
		}

		public void set(T value) {
			if (setter != null) setter.accept(value);
			else memory = value;
		}

		public void save() {
			saver.run();
		}

		public T getDefault() {
			return defaultValue;
		}

		public List<String> getPath() {
			return path;
		}

		public String[] comment() {
			return comment;
		}

		public String translation() {
			return translation;
		}

		public boolean valid(Object value) {
			return validator == null || validator.test(value);
		}
	}

	public static class BooleanValue extends ConfigValue<Boolean> {
		BooleanValue(List<String> path, boolean def, String[] comment, String translation) {
			super(path, def, comment, translation, o -> o instanceof Boolean);
		}

		public boolean getAsBoolean() {
			return get();
		}
	}

	public static class IntValue extends ConfigValue<Integer> {
		public final int min, max;

		IntValue(List<String> path, int def, int min, int max, String[] comment, String translation) {
			super(path, def, comment, translation, o -> o instanceof Number n && n.intValue() >= min && n.intValue() <= max);
			this.min = min;
			this.max = max;
		}

		public int getAsInt() {
			return get();
		}
	}

	public static class DoubleValue extends ConfigValue<Double> {
		public final double min, max;

		DoubleValue(List<String> path, double def, double min, double max, String[] comment, String translation) {
			super(path, def, comment, translation, o -> o instanceof Number n && n.doubleValue() >= min && n.doubleValue() <= max);
			this.min = min;
			this.max = max;
		}

		public double getAsDouble() {
			return get();
		}
	}

	public static class EnumValue<E extends Enum<E>> extends ConfigValue<E> {
		public final Class<E> type;

		EnumValue(List<String> path, E def, String[] comment, String translation) {
			super(path, def, comment, translation, null);
			this.type = def.getDeclaringClass();
		}
	}

	public static class Builder {
		private final List<Op> ops = new ArrayList<>();
		private final List<String> section = new ArrayList<>();
		private String[] comment = new String[0];
		private String translation;

		public Builder comment(String... comment) {
			this.comment = comment;
			return this;
		}

		public Builder translation(String key) {
			this.translation = key;
			return this;
		}

		public Builder push(String name) {
			ops.add(new Push(name, comment, translation));
			section.add(name);
			reset();
			return this;
		}

		public Builder pop() {
			ops.add(new Pop());
			section.remove(section.size() - 1);
			return this;
		}

		private List<String> path(String key) {
			List<String> p = new ArrayList<>(section);
			p.addAll(Arrays.asList(key.split("\\.")));
			return List.copyOf(p);
		}

		private <V extends ConfigValue<?>> V add(V value) {
			ops.add(new Value(value));
			reset();
			return value;
		}

		private void reset() {
			comment = new String[0];
			translation = null;
		}

		public BooleanValue define(String key, boolean def) {
			return add(new BooleanValue(path(key), def, comment, translation));
		}

		public <T> ConfigValue<T> define(String key, T def, Predicate<Object> validator) {
			return add(new ConfigValue<>(path(key), def, comment, translation, validator));
		}

		public <E extends Enum<E>> EnumValue<E> defineEnum(String key, E def) {
			return add(new EnumValue<>(path(key), def, comment, translation));
		}

		public IntValue defineInRange(String key, int def, int min, int max) {
			return add(new IntValue(path(key), def, min, max, comment, translation));
		}

		public DoubleValue defineInRange(String key, double def, double min, double max) {
			return add(new DoubleValue(path(key), def, min, max, comment, translation));
		}

		public ModConfigSpec build() {
			return new ModConfigSpec(new ArrayList<>(ops));
		}
	}
}
