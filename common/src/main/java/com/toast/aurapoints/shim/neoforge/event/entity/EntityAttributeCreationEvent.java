package com.toast.aurapoints.shim.neoforge.event.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

/** Default attributes for the mod's own living entities. */
public class EntityAttributeCreationEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private final java.util.function.BiConsumer<EntityType<? extends LivingEntity>, AttributeSupplier> sink;

	public EntityAttributeCreationEvent(java.util.function.BiConsumer<EntityType<? extends LivingEntity>, AttributeSupplier> sink) {
		this.sink = sink;
	}

	public void put(EntityType<? extends LivingEntity> type, AttributeSupplier attributes) {
		sink.accept(type, attributes);
	}
}
