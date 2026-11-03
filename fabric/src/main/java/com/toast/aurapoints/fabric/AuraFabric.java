package com.toast.aurapoints.fabric;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.Platform;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric entry point: hands common/ its platform, registers everything, then wires Fabric's callbacks to the mod's events. */
public final class AuraFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		Platform.set(new FabricPlatform());
		boolean client = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
		// client classes build their key mappings when they load, which happens just below (registerSubscribers)
		if (client) com.toast.aurapoints.shim.ClientPlatform.set(new com.toast.aurapoints.fabric.client.FabricClientPlatform());
		AuraBus.registerSubscribers(client ? Dist.CLIENT : Dist.DEDICATED_SERVER);
		FabricAttachments.init();
		AuraPoints.init(AuraBus.BUS);
		FabricEvents.register();
	}
}
