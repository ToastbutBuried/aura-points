package com.toast.aurapoints.shim.neoforge.client.event;

import net.minecraft.client.KeyMapping;

/** Collects the mod's key mappings; the loader layer registers them. */
public class RegisterKeyMappingsEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private final java.util.function.Consumer<KeyMapping> sink;

	public RegisterKeyMappingsEvent(java.util.function.Consumer<KeyMapping> sink) {
		this.sink = sink;
	}

	public void register(KeyMapping key) {
		sink.accept(key);
	}
}
