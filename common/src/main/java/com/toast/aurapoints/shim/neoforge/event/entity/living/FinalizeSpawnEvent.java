package com.toast.aurapoints.shim.neoforge.event.entity.living;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class FinalizeSpawnEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.world.entity.Mob entity;
	private net.minecraft.world.level.ServerLevelAccessor level;
	private net.minecraft.world.entity.MobSpawnType spawnType;

	public FinalizeSpawnEvent(net.minecraft.world.entity.Mob entity, net.minecraft.world.level.ServerLevelAccessor level, net.minecraft.world.entity.MobSpawnType spawnType) {
		this.entity = entity;
		this.level = level;
		this.spawnType = spawnType;
	}

	public net.minecraft.world.entity.Mob getEntity() {
		return entity;
	}

	public net.minecraft.world.level.ServerLevelAccessor getLevel() {
		return level;
	}

	public net.minecraft.world.entity.MobSpawnType getSpawnType() {
		return spawnType;
	}
}
