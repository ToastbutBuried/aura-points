package com.toast.aurapoints.client;

import com.toast.aurapoints.AccessorySlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;

/**
 * CLIENT ONLY. Draws the panel behind the accessory slots, left of the armor in the survival inventory.
 * The slots hide in the creative inventory (it lays slots out by index and the server ignores creative
 * edits past slot 45) and while the recipe book is open (it covers that side).
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class AccessoryPanel {

	private AccessoryPanel() {
	}

	@SubscribeEvent
	public static void onClientSetup(FMLClientSetupEvent event) {
		AccessorySlots.clientHidden = AccessoryPanel::hidden;
	}

	private static boolean hidden() {
		Screen screen = Minecraft.getInstance().screen;
		return screen instanceof CreativeModeInventoryScreen
				|| screen instanceof InventoryScreen inventory && inventory.getRecipeBookComponent().isVisible();
	}

	@SubscribeEvent
	public static void onBackground(ContainerScreenEvent.Render.Background event) {
		if (!(event.getContainerScreen() instanceof InventoryScreen screen) || hidden()) return;
		int count = AccessorySlots.count(true);
		if (count <= 0) return;
		GuiGraphics g = event.getGuiGraphics();
		int left = screen.getGuiLeft(), top = screen.getGuiTop();
		int rows = Math.min(count, AccessorySlots.COLUMN);
		int columns = (count + AccessorySlots.COLUMN - 1) / AccessorySlots.COLUMN;

		// vanilla-style panel: black outline, light top/left edge, dark bottom/right edge
		int x0 = left + AccessorySlots.slotX((columns - 1) * AccessorySlots.COLUMN) - 8, x1 = left;
		int y0 = top + AccessorySlots.slotY(0) - 7, y1 = top + AccessorySlots.slotY(rows - 1) + 24;
		g.fill(x0, y0, x1, y1, 0xFF000000);
		g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFFC6C6C6);
		g.fill(x0 + 1, y0 + 1, x1 - 2, y0 + 3, 0xFFFFFFFF);
		g.fill(x0 + 1, y0 + 1, x0 + 3, y1 - 2, 0xFFFFFFFF);
		g.fill(x0 + 3, y1 - 3, x1 - 1, y1 - 1, 0xFF555555);
		g.fill(x1 - 3, y0 + 3, x1 - 1, y1 - 1, 0xFF555555);

		for (int i = 0; i < count; i++) {
			int x = left + AccessorySlots.slotX(i), y = top + AccessorySlots.slotY(i);
			g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
			g.fill(x, y, x + 17, y + 17, 0xFFFFFFFF);
			g.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
		}
	}
}
