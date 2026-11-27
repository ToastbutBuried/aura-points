package com.toast.aurapoints.item;

import com.toast.aurapoints.ability.Mage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The Mage's Apprentice Staff (5.62.0; its own right-click since 5.63.0): right-click = Arcane Bolt (tap = a quick shock,
 * hold = the charged bolt, fired when you let go). The Mage's keys need it in hand too.
 */
public class ApprenticeStaffItem extends Item {

	public ApprenticeStaffItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		// either hand; with a staff in both, the main hand's casts
		if (hand == InteractionHand.OFF_HAND && player.getMainHandItem().getItem() instanceof ApprenticeStaffItem) return InteractionResultHolder.pass(stack);
		// both sides hold the use, so the client reports the let-go (releaseUsing on the server fires the bolt)
		player.startUsingItem(hand);
		if (player instanceof ServerPlayer sp && !Mage.boltPress(sp, sp.server.getTickCount())) {
			player.stopUsingItem();
			return InteractionResultHolder.fail(stack);
		}
		return InteractionResultHolder.consume(stack);
	}

	@Override
	public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
		if (entity instanceof ServerPlayer sp) Mage.boltRelease(sp, sp.server.getTickCount());
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return 72000;
	}

	@Override
	public UseAnim getUseAnimation(ItemStack stack) {
		return UseAnim.NONE;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag type) {
		tooltip.add(Component.literal("Right-click: Arcane Bolt. Hold to charge it.").withStyle(ChatFormatting.BLUE));
		tooltip.add(Component.literal("Mage spells are cast with it in hand.").withStyle(ChatFormatting.GRAY));
		super.appendHoverText(stack, context, tooltip, type);
	}
}
