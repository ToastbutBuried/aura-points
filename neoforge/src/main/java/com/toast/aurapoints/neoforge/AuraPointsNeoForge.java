package com.toast.aurapoints.neoforge;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.ClientPlatform;
import com.toast.aurapoints.shim.Platform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge entry point: hands common/ its platform, then turns NeoForge's events into the mod's own (shim/AuraBus). */
@Mod(AuraPoints.MOD_ID)
public class AuraPointsNeoForge {

	public AuraPointsNeoForge(IEventBus modBus, ModContainer container, Dist dist) {
		Platform.set(new NeoPlatform(modBus, container));
		if (dist.isClient()) ClientPlatform.set(new NeoClientPlatform(container));
		AuraBus.registerSubscribers(dist.isClient() ? com.toast.aurapoints.shim.api.distmarker.Dist.CLIENT
				: com.toast.aurapoints.shim.api.distmarker.Dist.DEDICATED_SERVER);
		NeoPlatform.ATTACHMENTS.register(modBus);
		AuraPoints.init(AuraBus.BUS);
		// last: the bridges hook in at exactly the priorities the listeners above use
		NeoBridge.register(modBus, NeoForge.EVENT_BUS);
		if (dist.isClient()) NeoClientBridge.register(modBus, NeoForge.EVENT_BUS);
	}
}
