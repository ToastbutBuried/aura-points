package com.toast.aurapoints.shim.neoforge.network.handling;

import net.minecraft.world.entity.player.Player;

import java.util.concurrent.CompletableFuture;

/** Where a packet came from: the sending player on the server, the local player on the client. */
public interface IPayloadContext {
	Player player();

	/** Runs the work on the main thread (now, if already on it). */
	CompletableFuture<Void> enqueueWork(Runnable work);
}
