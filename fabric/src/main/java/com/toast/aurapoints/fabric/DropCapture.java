package com.toast.aurapoints.fabric;

import net.minecraft.world.entity.item.ItemEntity;

import java.util.List;

/** While a living entity drops its death loot, the dropped items are collected here (for LivingDropsEvent). */
public interface DropCapture {
	/** The list being filled, null when not dying. */
	List<ItemEntity> aurapoints$capture();
}
