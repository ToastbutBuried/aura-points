package com.toast.aurapoints.mixin;

import com.mojang.math.Transformation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Display entities' private setters, for the Vine Hook's moving roots (ability/VineRoots). */
@Mixin(Display.class)
public interface DisplayAccessor {

	@Invoker("setTransformation")
	void aurapoints$setTransformation(Transformation transformation);

	@Invoker("setTransformationInterpolationDuration")
	void aurapoints$setInterpolationDuration(int ticks);

	@Invoker("setWidth")
	void aurapoints$setWidth(float width);

	@Invoker("setHeight")
	void aurapoints$setHeight(float height);

	@Invoker("setViewRange")
	void aurapoints$setViewRange(float range);
	@Invoker("getViewRange")
	float aurapoints$getViewRange();

	@Invoker("setPosRotInterpolationDuration")
	void aurapoints$setPosRotInterpolationDuration(int ticks);

	@Invoker("setBrightnessOverride")
	void aurapoints$setBrightnessOverride(net.minecraft.util.Brightness brightness);

	/** Set (forced) every update, so each new transformation interpolates from where the last one was. */
	@Accessor("DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID")
	static EntityDataAccessor<Integer> aurapoints$startDelta() {
		throw new AssertionError();
	}
}
