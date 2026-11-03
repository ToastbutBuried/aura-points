package com.toast.aurapoints.shim.neoforge.server;

import com.toast.aurapoints.shim.Platform;
import net.minecraft.server.MinecraftServer;

public final class ServerLifecycleHooks {
	private ServerLifecycleHooks() {
	}

	/** The running server (integrated or dedicated), null when there is none. */
	public static MinecraftServer getCurrentServer() {
		return Platform.get().currentServer();
	}
}
