package com.toast.aurapoints.shim.neoforge.event.entity.living;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class LivingDeathEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.world.entity.LivingEntity entity;
	private net.minecraft.world.damagesource.DamageSource source;

	public LivingDeathEvent(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
		this.entity = entity;
		this.source = source;
	}

	public net.minecraft.world.entity.LivingEntity getEntity() {
		return entity;
	}

	public net.minecraft.world.damagesource.DamageSource getSource() {
		return source;
	}
}
