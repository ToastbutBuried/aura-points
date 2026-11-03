package com.toast.aurapoints.shim.neoforge.event.tick;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class ServerTickEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static class Pre extends ServerTickEvent {
		private net.minecraft.server.MinecraftServer server;

		public Pre(net.minecraft.server.MinecraftServer server) {
			this.server = server;
		}

		public net.minecraft.server.MinecraftServer getServer() {
			return server;
		}
	}

	public static class Post extends ServerTickEvent {
		private net.minecraft.server.MinecraftServer server;

		public Post(net.minecraft.server.MinecraftServer server) {
			this.server = server;
		}

		public net.minecraft.server.MinecraftServer getServer() {
			return server;
		}
	}
}
