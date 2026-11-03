package com.toast.aurapoints.shim.neoforge.client.event;

import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;

/** HUD layers: drawn right above a vanilla layer (VanillaGuiLayers) or above everything. */
public class RegisterGuiLayersEvent extends com.toast.aurapoints.shim.bus.api.Event {
	/** above == null: above all layers. */
	public interface Sink {
		void accept(ResourceLocation above, ResourceLocation id, LayeredDraw.Layer layer);
	}

	private final Sink sink;

	public RegisterGuiLayersEvent(Sink sink) {
		this.sink = sink;
	}

	public void registerAbove(ResourceLocation other, ResourceLocation id, LayeredDraw.Layer layer) {
		sink.accept(other, id, layer);
	}

	public void registerAboveAll(ResourceLocation id, LayeredDraw.Layer layer) {
		sink.accept(null, id, layer);
	}
}
