package com.toast.aurapoints.shim.neoforge.event.entity.living;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class LivingDropsEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.world.entity.LivingEntity entity;
	private net.minecraft.world.damagesource.DamageSource source;
	private java.util.Collection<net.minecraft.world.entity.item.ItemEntity> drops;

	public LivingDropsEvent(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source, java.util.Collection<net.minecraft.world.entity.item.ItemEntity> drops) {
		this.entity = entity;
		this.source = source;
		this.drops = drops;
	}

	public net.minecraft.world.entity.LivingEntity getEntity() {
		return entity;
	}

	public net.minecraft.world.damagesource.DamageSource getSource() {
		return source;
	}

	public java.util.Collection<net.minecraft.world.entity.item.ItemEntity> getDrops() {
		return drops;
	}
}
