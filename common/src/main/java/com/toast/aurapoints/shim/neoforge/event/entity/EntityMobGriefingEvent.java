package com.toast.aurapoints.shim.neoforge.event.entity;

import net.minecraft.world.entity.Entity;

/** May this mob break or change blocks? null = the game rule decides. */
public class EntityMobGriefingEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private final Entity entity;
	private Boolean canGrief;

	public EntityMobGriefingEvent(Entity entity) {
		this.entity = entity;
	}

	public Entity getEntity() {
		return entity;
	}

	public void setCanGrief(boolean canGrief) {
		this.canGrief = canGrief;
	}

	/** For the loader layer: null when no listener decided. */
	public Boolean canGriefOrNull() {
		return canGrief;
	}
}
