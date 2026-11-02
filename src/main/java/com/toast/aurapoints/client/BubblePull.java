package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AbyssalBubble;
import com.toast.aurapoints.network.BubblePullPacket;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * CLIENT ONLY. The undertow of someone else's Abyssal Bubble on this player: applied here,
 * before the player moves each tick, from the last BubblePullPacket. Ends on its own when
 * the packets stop (left the bubble, or it closed).
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class BubblePull {

	private BubblePull() {
	}

	/** Packets come every tick; after this long without one the pull is over. */
	private static final long EXPIRE_MS = 150;

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Pre event) {
		Minecraft mc = Minecraft.getInstance();
		BubblePullPacket p = ClientAuraState.bubblePull;
		if (p == null || mc.player == null || mc.isPaused()) return;
		if (System.currentTimeMillis() - ClientAuraState.bubblePullAt > EXPIRE_MS) {
			ClientAuraState.bubblePull = null;
			return;
		}
		mc.player.setDeltaMovement(AbyssalBubble.undertow(mc.player.position(), mc.player.getDeltaMovement(),
				p.centerX(), p.centerZ(), p.pull(), p.outwardDrag()));
	}
}
