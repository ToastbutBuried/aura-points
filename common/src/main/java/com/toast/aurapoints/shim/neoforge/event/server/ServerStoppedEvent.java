package com.toast.aurapoints.shim.neoforge.event.server;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class ServerStoppedEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private net.minecraft.server.MinecraftServer server;

	public ServerStoppedEvent(net.minecraft.server.MinecraftServer server) {
		this.server = server;
	}

	public net.minecraft.server.MinecraftServer getServer() {
		return server;
	}
}
