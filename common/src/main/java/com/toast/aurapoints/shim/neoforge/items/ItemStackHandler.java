package com.toast.aurapoints.shim.neoforge.items;

import net.minecraft.world.item.ItemStack;

/** A fixed row of item slots (the accessory slots); the loader keeps it saved on the player. */
public interface ItemStackHandler {
	int getSlots();

	ItemStack getStackInSlot(int slot);

	void setStackInSlot(int slot, ItemStack stack);
}
