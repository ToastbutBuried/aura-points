package com.toast.aurapoints.item;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.menu.AuraBook;
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

/** Opens the Aura Book menu (menu/AuraBook) for whoever uses it: the book itself holds no data. */
public class AuraBookItem extends Item {

	public AuraBookItem(Properties properties) {
		super(properties.stacksTo(1).rarity(Rarity.UNCOMMON));
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) AuraBook.open(serverPlayer);
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return true;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.literal("Right-click: your aura, shop, skill points,").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.literal("settings and the tutorial.").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.literal("Lost it? /aura book or book + amethyst shard").withStyle(ChatFormatting.DARK_GRAY));
	}

	/** Into the inventory, or dropped at the player's feet if it's full. */
	public static void give(ServerPlayer player) {
		ItemStack book = new ItemStack(AuraPoints.AURA_BOOK.get());
		if (!player.getInventory().add(book)) player.drop(book, false);
	}
}
