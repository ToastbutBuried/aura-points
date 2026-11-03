package com.toast.aurapoints.shim.fml.event.lifecycle;

import com.toast.aurapoints.shim.bus.api.Event;

/** After registration, on the main thread work queue. */
public class FMLCommonSetupEvent extends Event {
	private final java.util.function.Consumer<Runnable> queue;

	public FMLCommonSetupEvent(java.util.function.Consumer<Runnable> queue) {
		this.queue = queue;
	}

	public void enqueueWork(Runnable work) {
		queue.accept(work);
	}
}
