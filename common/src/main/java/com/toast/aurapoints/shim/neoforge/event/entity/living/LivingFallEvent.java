package com.toast.aurapoints.shim.neoforge.event.entity.living;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class LivingFallEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.world.entity.LivingEntity entity;
	private float distance;
	private float damageMultiplier;

	public LivingFallEvent(net.minecraft.world.entity.LivingEntity entity, float distance, float damageMultiplier) {
		this.entity = entity;
		this.distance = distance;
		this.damageMultiplier = damageMultiplier;
	}

	public net.minecraft.world.entity.LivingEntity getEntity() {
		return entity;
	}

	public float getDistance() {
		return distance;
	}

	public void setDistance(float value) {
		this.distance = value;
	}

	public float getDamageMultiplier() {
		return damageMultiplier;
	}

	public void setDamageMultiplier(float value) {
		this.damageMultiplier = value;
	}
}
