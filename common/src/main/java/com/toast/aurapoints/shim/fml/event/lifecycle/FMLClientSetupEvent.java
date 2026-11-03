package com.toast.aurapoints.shim.fml.event.lifecycle;

import com.toast.aurapoints.shim.bus.api.Event;

/** Client start-up, after registration. */
public class FMLClientSetupEvent extends Event {
	private final java.util.function.Consumer<Runnable> queue;

	public FMLClientSetupEvent(java.util.function.Consumer<Runnable> queue) {
		this.queue = queue;
	}

	public void enqueueWork(Runnable work) {
		queue.accept(work);
	}
}
