package com.toast.aurapoints.client;

import com.toast.aurapoints.network.SwingMissPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

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
