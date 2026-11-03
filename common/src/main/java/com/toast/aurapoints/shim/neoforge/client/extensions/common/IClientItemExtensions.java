package com.toast.aurapoints.shim.neoforge.client.extensions.common;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;

/** Client-side item hooks: only the builtin/entity item renderer is used. */
public interface IClientItemExtensions {
	default BlockEntityWithoutLevelRenderer getCustomRenderer() {
		return null;
	}
}
