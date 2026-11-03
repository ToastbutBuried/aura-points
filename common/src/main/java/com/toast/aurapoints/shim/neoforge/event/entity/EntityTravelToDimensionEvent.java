package com.toast.aurapoints.shim.neoforge.event.entity;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class EntityTravelToDimensionEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.world.entity.Entity entity;
	private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;

	public EntityTravelToDimensionEvent(net.minecraft.world.entity.Entity entity, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
		this.entity = entity;
		this.dimension = dimension;
	}

	public net.minecraft.world.entity.Entity getEntity() {
		return entity;
	}

	public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> getDimension() {
		return dimension;
	}
}
