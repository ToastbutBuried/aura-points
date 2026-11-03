package com.toast.aurapoints.shim.neoforge.client.event;

import net.minecraft.client.resources.model.ModelResourceLocation;

public abstract class ModelEvent extends com.toast.aurapoints.shim.bus.api.Event {

	/** Extra models to load and bake that no block or item uses (get them from ModelManager.getModel). */
	public static class RegisterAdditional extends ModelEvent {
		private final java.util.function.Consumer<ModelResourceLocation> sink;

		public RegisterAdditional(java.util.function.Consumer<ModelResourceLocation> sink) {
			this.sink = sink;
		}

		public void register(ModelResourceLocation model) {
			sink.accept(model);
		}
	}
}
