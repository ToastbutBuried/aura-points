package com.toast.aurapoints.client;

import com.toast.aurapoints.network.SwingMissPacket;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerInteractEvent;
import com.toast.aurapoints.shim.neoforge.network.PacketDistributor;

/** CLIENT ONLY. A swing at the air: tell the server (Frost: it breaks Shatter; Mjölnir: it starts the flight). */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class SwingMiss {

	private SwingMiss() {
	}

	@SubscribeEvent
	public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
		// Every miss: the M1 datapack hook. Frost: a miss breaks Shatter. Mjölnir: a click while spinning it takes off.
		PacketDistributor.sendToServer(new SwingMissPacket());
	}
}
