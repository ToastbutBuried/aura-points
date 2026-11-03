package com.toast.aurapoints.shim.bus.api;

import java.util.function.Consumer;

/** The one shim event bus (see {@link com.toast.aurapoints.shim.AuraBus}). */
public interface IEventBus {
	<T extends Event> void addListener(Class<T> type, Consumer<T> listener);
}
