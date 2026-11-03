package com.toast.aurapoints.shim.neoforge.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/** One vanilla creative tab being filled: accept() adds to its end. */
public class BuildCreativeModeTabContentsEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private final ResourceKey<CreativeModeTab> tabKey;
	private final java.util.function.Consumer<ItemStack> sink;

	public BuildCreativeModeTabContentsEvent(ResourceKey<CreativeModeTab> tabKey, java.util.function.Consumer<ItemStack> sink) {
		this.tabKey = tabKey;
		this.sink = sink;
	}

	public ResourceKey<CreativeModeTab> getTabKey() {
		return tabKey;
	}

	public void accept(ItemLike item) {
		sink.accept(new ItemStack(item));
	}
}
