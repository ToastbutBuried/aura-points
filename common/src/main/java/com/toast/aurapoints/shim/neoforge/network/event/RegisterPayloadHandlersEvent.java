package com.toast.aurapoints.shim.neoforge.network.event;

import com.toast.aurapoints.shim.neoforge.network.registration.PayloadRegistrar;

public class RegisterPayloadHandlersEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private final java.util.function.Function<String, PayloadRegistrar> registrars;

	public RegisterPayloadHandlersEvent(java.util.function.Function<String, PayloadRegistrar> registrars) {
		this.registrars = registrars;
	}

	/** Packets that need the same network version on both ends. */
	public PayloadRegistrar registrar(String version) {
		return registrars.apply(version);
	}
}
