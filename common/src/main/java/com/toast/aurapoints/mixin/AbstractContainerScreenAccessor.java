package com.toast.aurapoints.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A container screen's top-left corner (NeoForge has getGuiLeft/getGuiTop; plain Minecraft keeps them protected). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int aurapoints$leftPos();

	@Accessor("topPos")
	int aurapoints$topPos();
}
