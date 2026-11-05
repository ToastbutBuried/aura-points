package com.toast.aurapoints.item;

import com.toast.aurapoints.ability.BlueBell;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** The Blue Bell (ability/BlueBell): right-click and it flies out, striking harder every time, then a blizzard. */
public class BlueBellItem extends Item {

	public BlueBellItem(Properties properties) {
		super(properties.stacksTo(1).rarity(Rarity.EPIC));
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide) return InteractionResultHolder.success(stack);
		if (!(player instanceof ServerPlayer sp) || !BlueBell.start(sp)) return InteractionResultHolder.fail(stack);
		player.getCooldowns().addCooldown(this, AuraConfig.BLUE_BELL_COOLDOWN_TICKS);
		return InteractionResultHolder.consume(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag type) {
		tooltip.add(Component.literal("Right-click: the bell flies out and strikes your enemies.").withStyle(ChatFormatting.AQUA));
		tooltip.add(Component.literal("Every strike is harder. The last one calls a blizzard.").withStyle(ChatFormatting.GRAY));
		super.appendHoverText(stack, context, tooltip, type);
	}
}
