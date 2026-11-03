package com.toast.aurapoints.shim.neoforge.event;

import net.minecraft.server.packs.resources.PreparableReloadListener;

/** Server data reload (start-up and /reload): add a listener. */
public class AddReloadListenerEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private final java.util.function.Consumer<PreparableReloadListener> sink;

	public AddReloadListenerEvent(java.util.function.Consumer<PreparableReloadListener> sink) {
		this.sink = sink;
	}

	public void addListener(PreparableReloadListener listener) {
		sink.accept(listener);
	}
}
