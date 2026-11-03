package com.toast.aurapoints.shim.neoforge.event.entity.living;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class LivingHealEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.world.entity.LivingEntity entity;
	private float amount;

	public LivingHealEvent(net.minecraft.world.entity.LivingEntity entity, float amount) {
		this.entity = entity;
		this.amount = amount;
	}

	public net.minecraft.world.entity.LivingEntity getEntity() {
		return entity;
	}

	public float getAmount() {
		return amount;
	}

	public void setAmount(float value) {
		this.amount = value;
	}
}
