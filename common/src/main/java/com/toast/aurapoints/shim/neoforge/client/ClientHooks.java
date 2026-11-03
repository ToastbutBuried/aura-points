package com.toast.aurapoints.shim.neoforge.client;

import com.toast.aurapoints.shim.ClientPlatform;
import net.minecraft.world.entity.Entity;

public final class ClientHooks {
	private ClientHooks() {
	}

	public static boolean isNameplateInRenderDistance(Entity entity, double squareDistance) {
		return ClientPlatform.get().isNameplateInRenderDistance(entity, squareDistance);
	}
}
