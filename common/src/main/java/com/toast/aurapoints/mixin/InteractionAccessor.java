package com.toast.aurapoints.mixin;

import net.minecraft.world.entity.Interaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Interaction entities' private size setters: invisible hitboxes players can hit (Entanglement's green roots, the Verdant Viper). */
@Mixin(Interaction.class)
public interface InteractionAccessor {

	@Invoker("setWidth")
	void aurapoints$setWidth(float width);

	@Invoker("setHeight")
	void aurapoints$setHeight(float height);

	@Invoker("setResponse")
	void aurapoints$setResponse(boolean response);
}
