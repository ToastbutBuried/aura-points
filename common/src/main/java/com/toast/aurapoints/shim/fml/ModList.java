package com.toast.aurapoints.shim.fml;

import com.toast.aurapoints.shim.Platform;

/** Which other mods are installed. */
public final class ModList {
	private static final ModList INSTANCE = new ModList();

	private ModList() {
	}

	public static ModList get() {
		return INSTANCE;
	}

	public boolean isLoaded(String modId) {
		return Platform.get().isModLoaded(modId);
	}
}
