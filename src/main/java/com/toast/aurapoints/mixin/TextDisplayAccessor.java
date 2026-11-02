package com.toast.aurapoints.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Text displays' private text setter, for the Aura Arena's live holograms (arena/Holograms). */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {

	@Invoker("setText")
	void aurapoints$setText(Component text);
}
