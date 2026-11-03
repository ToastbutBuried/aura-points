package com.toast.aurapoints.shim.fml.common;

import com.toast.aurapoints.shim.api.distmarker.Dist;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A class whose static {@link com.toast.aurapoints.shim.bus.api.SubscribeEvent} methods join the shim bus at startup.
 * The build lists every such class in aurapoints-subscribers.txt (root build.gradle), so nothing else has to.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventBusSubscriber {
	String modid() default "aurapoints";

	/** Physical sides this class loads on (both by default). */
	Dist[] value() default {Dist.CLIENT, Dist.DEDICATED_SERVER};
}
