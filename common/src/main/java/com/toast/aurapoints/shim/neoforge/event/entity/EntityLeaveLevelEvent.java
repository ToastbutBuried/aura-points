package com.toast.aurapoints.shim.neoforge.event.entity;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class EntityLeaveLevelEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private net.minecraft.world.entity.Entity entity;
	private net.minecraft.world.level.Level level;

	public EntityLeaveLevelEvent(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level) {
		this.entity = entity;
		this.level = level;
	}

	public net.minecraft.world.entity.Entity getEntity() {
		return entity;
	}

	public net.minecraft.world.level.Level getLevel() {
		return level;
	}
}
