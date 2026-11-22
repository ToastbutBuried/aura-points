package com.toast.aurapoints.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * The Tuning Fork (5.46.0, Relic Trials): handed out on a Resonance Hunt island. Held, it rings higher and brighter
 * the closer it is to the buried shard (rift/Trials.Hunt). Only exists inside a rift.
 */
public class TuningForkItem extends Item {

	public TuningForkItem(Properties props) {
		super(props);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.literal("Hold it: it rings higher near the buried shard.").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.literal("Right-click a rubble mound to dig it out.").withStyle(ChatFormatting.DARK_GRAY));
	}
}
