package com.toast.aurapoints.shim.neoforge.client.event;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.function.Supplier;

public abstract class EntityRenderersEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public interface LayerSink {
		void accept(ModelLayerLocation layer, Supplier<LayerDefinition> definition);
	}

	public interface RendererSink {
		<T extends Entity> void accept(EntityType<? extends T> type, EntityRendererProvider<T> provider);
	}

	public static class RegisterLayerDefinitions extends EntityRenderersEvent {
		private final LayerSink sink;

		public RegisterLayerDefinitions(LayerSink sink) {
			this.sink = sink;
		}

		public void registerLayerDefinition(ModelLayerLocation layer, Supplier<LayerDefinition> definition) {
			sink.accept(layer, definition);
		}
	}

	public static class RegisterRenderers extends EntityRenderersEvent {
		private final RendererSink sink;

		public RegisterRenderers(RendererSink sink) {
			this.sink = sink;
		}

		public <T extends Entity> void registerEntityRenderer(EntityType<? extends T> type, EntityRendererProvider<T> provider) {
			sink.accept(type, provider);
		}
	}
}
