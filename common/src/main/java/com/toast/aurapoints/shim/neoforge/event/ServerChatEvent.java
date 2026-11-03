package com.toast.aurapoints.shim.neoforge.event;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class ServerChatEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.server.level.ServerPlayer player;
	private java.lang.String rawText;
	private net.minecraft.network.chat.Component message;

	public ServerChatEvent(net.minecraft.server.level.ServerPlayer player, java.lang.String rawText, net.minecraft.network.chat.Component message) {
		this.player = player;
		this.rawText = rawText;
		this.message = message;
	}

	public net.minecraft.server.level.ServerPlayer getPlayer() {
		return player;
	}

	public java.lang.String getRawText() {
		return rawText;
	}

	public net.minecraft.network.chat.Component getMessage() {
		return message;
	}

	public void setMessage(net.minecraft.network.chat.Component value) {
		this.message = value;
	}
}
