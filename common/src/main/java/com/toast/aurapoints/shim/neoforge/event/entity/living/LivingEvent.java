package com.toast.aurapoints.shim.neoforge.event.entity.living;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public abstract class LivingEvent extends com.toast.aurapoints.shim.bus.api.Event {

	/** How visible an entity is to a mob looking for it (multiplied into the vanilla value). */
	public static class LivingVisibilityEvent extends LivingEvent {
		private final LivingEntity entity;
		private final Entity lookingEntity;
		private double visibilityModifier;

		public LivingVisibilityEvent(LivingEntity entity, Entity lookingEntity, double visibilityModifier) {
			this.entity = entity;
			this.lookingEntity = lookingEntity;
			this.visibilityModifier = visibilityModifier;
		}

		public LivingEntity getEntity() {
			return entity;
		}

		public Entity getLookingEntity() {
			return lookingEntity;
		}

		public void modifyVisibility(double mod) {
			visibilityModifier *= mod;
		}

		public double getVisibilityModifier() {
			return visibilityModifier;
		}
	}
}
