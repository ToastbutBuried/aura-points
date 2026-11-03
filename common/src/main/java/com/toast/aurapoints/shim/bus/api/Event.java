package com.toast.aurapoints.shim.bus.api;

/** Base of every shim event. The loader layer builds one from the loader's own event, posts it, and copies the results back. */
public abstract class Event {
	private boolean canceled;

	public void setCanceled(boolean canceled) {
		this.canceled = canceled;
	}

	public boolean isCanceled() {
		return canceled;
	}
}
