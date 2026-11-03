package com.toast.aurapoints.shim.neoforge.client.event;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class RenderFrameEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static class Pre extends RenderFrameEvent {
		private net.minecraft.client.DeltaTracker partialTick;

		public Pre(net.minecraft.client.DeltaTracker partialTick) {
			this.partialTick = partialTick;
		}

		public net.minecraft.client.DeltaTracker getPartialTick() {
			return partialTick;
		}
	}

	public static class Post extends RenderFrameEvent {
		private net.minecraft.client.DeltaTracker partialTick;

		public Post(net.minecraft.client.DeltaTracker partialTick) {
			this.partialTick = partialTick;
		}

		public net.minecraft.client.DeltaTracker getPartialTick() {
			return partialTick;
		}
	}
}
