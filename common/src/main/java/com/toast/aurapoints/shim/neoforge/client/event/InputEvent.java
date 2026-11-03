package com.toast.aurapoints.shim.neoforge.client.event;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class InputEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static class InteractionKeyMappingTriggered extends InputEvent implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
		private int button;
		private net.minecraft.client.KeyMapping keyMapping;
		private net.minecraft.world.InteractionHand hand;
		private boolean swingHand;

		public InteractionKeyMappingTriggered(int button, net.minecraft.client.KeyMapping keyMapping, net.minecraft.world.InteractionHand hand, boolean swingHand) {
			this.button = button;
			this.keyMapping = keyMapping;
			this.hand = hand;
			this.swingHand = swingHand;
		}

		public int getButton() {
			return button;
		}

		public net.minecraft.client.KeyMapping getKeyMapping() {
			return keyMapping;
		}

		public net.minecraft.world.InteractionHand getHand() {
			return hand;
		}

		public boolean shouldSwingHand() {
			return swingHand;
		}

		public void setSwingHand(boolean value) {
			this.swingHand = value;
		}
	}
}
