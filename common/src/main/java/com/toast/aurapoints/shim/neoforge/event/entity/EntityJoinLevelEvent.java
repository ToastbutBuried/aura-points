package com.toast.aurapoints.shim.neoforge.event.entity;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class EntityJoinLevelEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.world.entity.Entity entity;
	private net.minecraft.world.level.Level level;
	private boolean loadedFromDisk;

	public EntityJoinLevelEvent(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level, boolean loadedFromDisk) {
		this.entity = entity;
		this.level = level;
		this.loadedFromDisk = loadedFromDisk;
	}

	public net.minecraft.world.entity.Entity getEntity() {
		return entity;
	}

	public net.minecraft.world.level.Level getLevel() {
		return level;
	}

	public boolean loadedFromDisk() {
		return loadedFromDisk;
	}
}
