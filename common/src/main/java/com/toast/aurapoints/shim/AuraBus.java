package com.toast.aurapoints.shim;

import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.Event;
import com.toast.aurapoints.shim.bus.api.EventPriority;
import com.toast.aurapoints.shim.bus.api.IEventBus;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The mod's own event bus. Every @EventBusSubscriber class (listed by the build in aurapoints-subscribers.txt) joins
 * at start-up; the loader layer turns loader events into these and posts them (once per priority it is told about,
 * so other mods' listeners keep their place between ours).
 */
public final class AuraBus implements IEventBus {
	public static final AuraBus BUS = new AuraBus();

	private record Listener(Class<?> type, EventPriority priority, boolean receiveCanceled, Consumer<Event> call, String name) {
	}

	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	/** Per event class: its listeners, supertypes' included, in priority order. */
	private final Map<Class<?>, List<Listener>> cache = new ConcurrentHashMap<>();

	private AuraBus() {
	}

	/** Registers every listed subscriber class for this side. */
	public static void registerSubscribers(Dist side) {
		try (InputStream in = AuraBus.class.getClassLoader().getResourceAsStream("aurapoints-subscribers.txt")) {
			if (in == null) throw new IllegalStateException("aurapoints-subscribers.txt missing from the jar");
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = r.readLine()) != null) {
				line = line.trim();
				if (!line.isEmpty()) BUS.registerClass(line, side);
			}
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private void registerClass(String className, Dist side) {
		Class<?> c;
		try {
			c = Class.forName(className, false, AuraBus.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("Subscriber class not found: " + className, e);
		}
		EventBusSubscriber sub = c.getAnnotation(EventBusSubscriber.class);
		if (sub == null) return;
		if (!EnumSet.of(sub.value()[0], sub.value()).contains(side)) return;
		try {
			Class.forName(className, true, AuraBus.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException(e);
		}
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		for (Method m : c.getDeclaredMethods()) {
			SubscribeEvent ann = m.getAnnotation(SubscribeEvent.class);
			if (ann == null) continue;
			if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1 || !Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
				throw new IllegalStateException("Bad @SubscribeEvent method " + c.getName() + "." + m.getName());
			}
			try {
				m.setAccessible(true);
				MethodHandle h = lookup.unreflect(m);
				add(new Listener(m.getParameterTypes()[0], ann.priority(), ann.receiveCanceled(), e -> {
					try {
						h.invoke(e);
					} catch (RuntimeException | Error ex) {
						throw ex;
					} catch (Throwable t) {
						throw new RuntimeException(t);
					}
				}, c.getName() + "." + m.getName()));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private void add(Listener l) {
		listeners.add(l);
		cache.clear();
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends Event> void addListener(Class<T> type, Consumer<T> listener) {
		add(new Listener(type, EventPriority.NORMAL, false, e -> listener.accept((T) e), type.getSimpleName() + " lambda"));
	}

	private List<Listener> listenersFor(Class<?> type) {
		return cache.computeIfAbsent(type, t -> {
			List<Listener> out = new ArrayList<>();
			for (Listener l : listeners) if (l.type.isAssignableFrom(t)) out.add(l);
			out.sort(Comparator.comparing(Listener::priority)); // stable: registration order within a priority
			return List.copyOf(out);
		});
	}

	/** Priorities that have listeners for this event class (the loader layer hooks in at exactly these). */
	public Set<EventPriority> priorities(Class<? extends Event> type) {
		Set<EventPriority> out = EnumSet.noneOf(EventPriority.class);
		for (Listener l : listenersFor(type)) out.add(l.priority);
		return out;
	}

	public boolean hasListeners(Class<? extends Event> type) {
		return !listenersFor(type).isEmpty();
	}

	/** Every listener, all priorities. */
	public <T extends Event> T post(T event) {
		for (Listener l : listenersFor(event.getClass())) call(l, event);
		return event;
	}

	/** Only the listeners of one priority. */
	public <T extends Event> T post(EventPriority priority, T event) {
		for (Listener l : listenersFor(event.getClass())) if (l.priority == priority) call(l, event);
		return event;
	}

	private static void call(Listener l, Event event) {
		if (event.isCanceled() && !l.receiveCanceled) return;
		l.call.accept(event);
	}
}
