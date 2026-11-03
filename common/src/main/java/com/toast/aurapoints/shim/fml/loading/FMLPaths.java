package com.toast.aurapoints.shim.fml.loading;

import com.toast.aurapoints.shim.Platform;

import java.nio.file.Path;

/** The game and config folders. */
public enum FMLPaths {
	GAMEDIR,
	CONFIGDIR;

	public Path get() {
		return this == GAMEDIR ? Platform.get().gameDir() : Platform.get().configDir();
	}
}
