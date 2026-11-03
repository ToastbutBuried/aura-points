package com.toast.aurapoints.shim.neoforge.client.event;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class MovementInputUpdateEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private net.minecraft.world.entity.player.Player entity;
	private net.minecraft.client.player.Input input;

	public MovementInputUpdateEvent(net.minecraft.world.entity.player.Player entity, net.minecraft.client.player.Input input) {
		this.entity = entity;
		this.input = input;
	}

	public net.minecraft.world.entity.player.Player getEntity() {
		return entity;
	}

	public net.minecraft.client.player.Input getInput() {
		return input;
	}
}
