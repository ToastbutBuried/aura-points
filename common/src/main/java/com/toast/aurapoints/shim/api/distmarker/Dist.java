package com.toast.aurapoints.shim.api.distmarker;

/** Physical side (same meaning as NeoForge's Dist). */
public enum Dist {
	CLIENT,
	DEDICATED_SERVER;

	public boolean isClient() {
		return this == CLIENT;
	}

	public boolean isDedicatedServer() {
		return this == DEDICATED_SERVER;
	}
}
