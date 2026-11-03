package com.toast.aurapoints.shim.neoforge.client.event;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class ClientPlayerNetworkEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static class LoggingIn extends ClientPlayerNetworkEvent {
		private net.minecraft.client.player.LocalPlayer player;

		public LoggingIn(net.minecraft.client.player.LocalPlayer player) {
			this.player = player;
		}

		public net.minecraft.client.player.LocalPlayer getPlayer() {
			return player;
		}
	}

	public static class LoggingOut extends ClientPlayerNetworkEvent {
		private net.minecraft.client.player.LocalPlayer player;

		public LoggingOut(net.minecraft.client.player.LocalPlayer player) {
			this.player = player;
		}

		public net.minecraft.client.player.LocalPlayer getPlayer() {
			return player;
		}
	}
}
