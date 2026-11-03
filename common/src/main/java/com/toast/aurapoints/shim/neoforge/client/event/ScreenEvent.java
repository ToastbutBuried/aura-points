package com.toast.aurapoints.shim.neoforge.client.event;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class ScreenEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static abstract class Init extends ScreenEvent {

		public static class Post extends Init {
			private net.minecraft.client.gui.screens.Screen screen;

			public Post(net.minecraft.client.gui.screens.Screen screen) {
				this.screen = screen;
			}

			public net.minecraft.client.gui.screens.Screen getScreen() {
				return screen;
			}
		}
	}
}
