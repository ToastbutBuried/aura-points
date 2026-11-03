package com.toast.aurapoints.shim.neoforge.network.handling;

@FunctionalInterface
public interface IPayloadHandler<T> {
	void handle(T payload, IPayloadContext context);
}
