package com.toast.aurapoints.shim.neoforge.event.entity.living;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class LivingExperienceDropEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private net.minecraft.world.entity.LivingEntity entity;
	private net.minecraft.world.entity.player.Player attackingPlayer;
	private int droppedExperience;

	public LivingExperienceDropEvent(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.entity.player.Player attackingPlayer, int droppedExperience) {
		this.entity = entity;
		this.attackingPlayer = attackingPlayer;
		this.droppedExperience = droppedExperience;
	}

	public net.minecraft.world.entity.LivingEntity getEntity() {
		return entity;
	}

	public net.minecraft.world.entity.player.Player getAttackingPlayer() {
		return attackingPlayer;
	}

	public int getDroppedExperience() {
		return droppedExperience;
	}

	public void setDroppedExperience(int value) {
		this.droppedExperience = value;
	}
}
