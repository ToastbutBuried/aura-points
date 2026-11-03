package com.toast.aurapoints.shim.neoforge.event.entity.player;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class AttackEntityEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.world.entity.player.Player entity;
	private net.minecraft.world.entity.Entity target;

	public AttackEntityEvent(net.minecraft.world.entity.player.Player entity, net.minecraft.world.entity.Entity target) {
		this.entity = entity;
		this.target = target;
	}

	public net.minecraft.world.entity.player.Player getEntity() {
		return entity;
	}

	public net.minecraft.world.entity.Entity getTarget() {
		return target;
	}
}
