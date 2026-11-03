package com.toast.aurapoints.shim.neoforge.event.entity.living;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class LivingKnockBackEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.world.entity.LivingEntity entity;
	private float strength;
	private double ratioX;
	private double ratioZ;

	public LivingKnockBackEvent(net.minecraft.world.entity.LivingEntity entity, float strength, double ratioX, double ratioZ) {
		this.entity = entity;
		this.strength = strength;
		this.ratioX = ratioX;
		this.ratioZ = ratioZ;
	}

	public net.minecraft.world.entity.LivingEntity getEntity() {
		return entity;
	}

	public float getStrength() {
		return strength;
	}

	public void setStrength(float value) {
		this.strength = value;
	}

	public double getRatioX() {
		return ratioX;
	}

	public void setRatioX(double value) {
		this.ratioX = value;
	}

	public double getRatioZ() {
		return ratioZ;
	}

	public void setRatioZ(double value) {
		this.ratioZ = value;
	}
}
