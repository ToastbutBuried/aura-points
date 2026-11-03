package com.toast.aurapoints.shim.bus.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A static listener method in an {@link com.toast.aurapoints.shim.fml.common.EventBusSubscriber} class: one parameter, the event. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SubscribeEvent {
	EventPriority priority() default EventPriority.NORMAL;

	/** Also called for an event an earlier listener canceled. */
	boolean receiveCanceled() default false;
}
