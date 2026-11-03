package com.toast.aurapoints.shim.neoforge.client.extensions.common;

import net.minecraft.world.item.Item;

public class RegisterClientExtensionsEvent extends com.toast.aurapoints.shim.bus.api.Event {
	public interface Sink {
		void accept(IClientItemExtensions extensions, Item... items);
	}

	private final Sink sink;

	public RegisterClientExtensionsEvent(Sink sink) {
		this.sink = sink;
	}

	public void registerItem(IClientItemExtensions extensions, Item... items) {
		sink.accept(extensions, items);
	}
}
