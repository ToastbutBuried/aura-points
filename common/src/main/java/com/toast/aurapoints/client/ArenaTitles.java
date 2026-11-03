package com.toast.aurapoints.client;

import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ArenaTitlePacket;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.fml.event.lifecycle.FMLClientSetupEvent;

/** CLIENT ONLY. Shows arena announcements (ArenaTitlePacket) as a short title or in chat (config arenaMessages). */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class ArenaTitles {

	private ArenaTitles() {
	}

	@SubscribeEvent
	public static void onClientSetup(FMLClientSetupEvent event) {
		ClientAuraState.arenaTitle = ArenaTitles::show;
	}

	private static void show(ArenaTitlePacket p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		boolean hasSub = !p.subtitle().getString().isEmpty();
		if (AuraClientConfig.ARENA_MESSAGES.get() == AuraClientConfig.ArenaMessages.CHAT) {
			if (p.countdown()) {
				mc.gui.setOverlayMessage(p.title(), false);
				return;
			}
			Component line = Component.literal("⚔ ").withStyle(ChatFormatting.GOLD).append(p.title());
			if (hasSub) line = line.copy().append(Component.literal("  ")).append(p.subtitle());
			mc.player.displayClientMessage(line, false);
			return;
		}
		// a countdown tick fits in its one second; everything else stays about 1.75 s (vanilla titles: 3.5 s)
		if (p.countdown()) mc.gui.setTimes(2, 14, 4);
		else mc.gui.setTimes(3, 25, 7);
		mc.gui.setSubtitle(p.subtitle());
		mc.gui.setTitle(p.title());
	}
}
