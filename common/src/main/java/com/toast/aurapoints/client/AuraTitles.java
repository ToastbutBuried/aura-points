package com.toast.aurapoints.client;

import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraState;
import com.toast.aurapoints.network.TitlePacket;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * CLIENT ONLY. How big titles are. The mod's own keep the same share of the screen whatever the GUI scale (vanilla's 4x
 * is small on a small GUI scale) and follow the titleSize option; any title that would run off the screen shrinks to fit.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class AuraTitles {

	private AuraTitles() {
	}

	/** Below this GUI height a title is vanilla's 4x; above it grows with the screen. */
	private static final float BASE_HEIGHT = 270f;

	private static Component ours, oursSub;

	@SubscribeEvent
	public static void onClientSetup(FMLClientSetupEvent event) {
		ClientAuraState.title = AuraTitles::show;
	}

	private static void show(TitlePacket p) {
		show(p.title(), p.subtitle(), p.fadeIn(), p.stay(), p.fadeOut());
	}

	/** Shows one of the mod's titles through vanilla's title display. */
	public static void show(Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
		Minecraft mc = Minecraft.getInstance();
		ours = title;
		oursSub = subtitle;
		mc.gui.setTimes(fadeIn, stay, fadeOut);
		mc.gui.setSubtitle(subtitle);
		mc.gui.setTitle(title);
	}

	/** How much bigger than normal the mod's big texts are on this screen. */
	public static float size(int guiHeight) {
		return Math.max(1f, guiHeight / BASE_HEIGHT) * (float) (double) AuraClientConfig.TITLE_SIZE.get();
	}

	/** {@code wanted}, or as much of it as fits into 92% of the screen's width. */
	public static float fit(Font font, Component text, float wanted, int guiWidth) {
		return Math.min(wanted, guiWidth * 0.92f / Math.max(1, font.width(text)));
	}

	/** The scale for a title (4) or subtitle (2) on screen: ours by the screen, others only shrink. */
	public static float scale(Font font, Component text, Component title, float vanilla, int guiWidth, int guiHeight) {
		boolean mine = title != null && title == ours || text != null && text == oursSub;
		return text == null ? vanilla : fit(font, text, mine ? vanilla * size(guiHeight) : vanilla, guiWidth);
	}
}
