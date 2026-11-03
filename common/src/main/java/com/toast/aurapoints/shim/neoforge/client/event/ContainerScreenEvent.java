package com.toast.aurapoints.shim.neoforge.client.event;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class ContainerScreenEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static abstract class Render extends ContainerScreenEvent {

		public static class Background extends Render {
			private net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen;
			private net.minecraft.client.gui.GuiGraphics guiGraphics;
			private int mouseX;
			private int mouseY;

			public Background(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen, net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY) {
				this.containerScreen = containerScreen;
				this.guiGraphics = guiGraphics;
				this.mouseX = mouseX;
				this.mouseY = mouseY;
			}

			public net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> getContainerScreen() {
				return containerScreen;
			}

			public net.minecraft.client.gui.GuiGraphics getGuiGraphics() {
				return guiGraphics;
			}

			public int getMouseX() {
				return mouseX;
			}

			public int getMouseY() {
				return mouseY;
			}
		}
	}
}
