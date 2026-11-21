package com.toast.aurapoints.item;

import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Relics;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * An elemental relic (5.40.0): worn in an accessory slot it gives a small echo of its aura's passives
 * to someone of ANOTHER aura (ability/Relics). Found in chests and rifts, bought with Aura, tradeable.
 */
public class RelicItem extends Item {

	public final AuraType aura;

	public RelicItem(AuraType aura, Properties props) {
		super(props);
		this.aura = aura;
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return true;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.literal("Relic of " + aura.displayName).withStyle(aura.color));
		tooltip.add(Component.literal(Relics.describe(aura)).withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.literal("Wear it in an accessory slot (up to " + Relics.maxEquipped() + " count).").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.add(Component.literal("Does nothing for " + aura.displayName + " holders.").withStyle(ChatFormatting.DARK_GRAY));
	}
}
