package com.toast.aurapoints.shim.neoforge.event.entity.living;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class LivingDamageEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static class Pre extends LivingDamageEvent {
		private net.minecraft.world.entity.LivingEntity entity;
		private net.minecraft.world.damagesource.DamageSource source;
		private float originalDamage;
		private float newDamage;

		public Pre(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source, float originalDamage, float newDamage) {
			this.entity = entity;
			this.source = source;
			this.originalDamage = originalDamage;
			this.newDamage = newDamage;
		}

		public net.minecraft.world.entity.LivingEntity getEntity() {
			return entity;
		}

		public net.minecraft.world.damagesource.DamageSource getSource() {
			return source;
		}

		public float getOriginalDamage() {
			return originalDamage;
		}

		public float getNewDamage() {
			return newDamage;
		}

		public void setNewDamage(float value) {
			this.newDamage = value;
		}
	}

	public static class Post extends LivingDamageEvent {
		private net.minecraft.world.entity.LivingEntity entity;
		private net.minecraft.world.damagesource.DamageSource source;
		private float originalDamage;
		private float newDamage;

		public Post(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source, float originalDamage, float newDamage) {
			this.entity = entity;
			this.source = source;
			this.originalDamage = originalDamage;
			this.newDamage = newDamage;
		}

		public net.minecraft.world.entity.LivingEntity getEntity() {
			return entity;
		}

		public net.minecraft.world.damagesource.DamageSource getSource() {
			return source;
		}

		public float getOriginalDamage() {
			return originalDamage;
		}

		public float getNewDamage() {
			return newDamage;
		}
	}
}
