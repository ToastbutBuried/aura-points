package com.toast.aurapoints.shim.neoforge.event.entity.living;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class LivingEntityUseItemEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static class Start extends LivingEntityUseItemEvent implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
		private net.minecraft.world.entity.LivingEntity entity;
		private net.minecraft.world.item.ItemStack item;
		private int duration;

		public Start(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.item.ItemStack item, int duration) {
			this.entity = entity;
			this.item = item;
			this.duration = duration;
		}

		public net.minecraft.world.entity.LivingEntity getEntity() {
			return entity;
		}

		public net.minecraft.world.item.ItemStack getItem() {
			return item;
		}

		public int getDuration() {
			return duration;
		}

		public void setDuration(int value) {
			this.duration = value;
		}
	}
}
