package com.toast.aurapoints.item;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.Handhelds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * An aura's handheld weapon (ability/Handhelds): hits like a plain weapon of its kind - no special left
 * click - and right-click (or the Item Use key) calls its one ability. Unbreakable.
 */
public class HandheldItem extends Item {

	public final Ability ability;
	private final String look;

	/** {@code damage} total attack damage, {@code speed} attacks per second, {@code look} the line under the name. */
	public HandheldItem(Properties properties, Ability ability, double damage, double speed, String look) {
		super(properties.stacksTo(1).rarity(Rarity.EPIC).fireResistant()
				.component(net.minecraft.core.component.DataComponents.UNBREAKABLE, new net.minecraft.world.item.component.Unbreakable(false))
				.attributes(ItemAttributeModifiers.builder()
						.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID, damage - 1.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
						.add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID, speed - 4.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
						.build()));
		this.ability = ability;
		this.look = look;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stack);
		if (level.isClientSide) return InteractionResultHolder.success(stack);
		return Handhelds.use((ServerPlayer) player, ability) ? InteractionResultHolder.consume(stack) : InteractionResultHolder.pass(stack);
	}

	/** Owner only for now: anyone else holding it can barely move or hit (like Mjölnir). */
	@Override
	public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
		notWorthy(level, entity, selected);
	}

	static void notWorthy(Level level, net.minecraft.world.entity.Entity entity, boolean selected) {
		if (level.isClientSide || !selected || !(entity instanceof ServerPlayer player) || com.toast.aurapoints.AuraCommand.isOwner(player)) return;
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 30, 3, false, false, true));
		player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 30, 2, false, false, true));
		if (player.tickCount % 40 == 0) player.displayClientMessage(Component.literal("You are not worthy.").withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC), true);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag type) {
		tooltip.add(Component.literal(look).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		tooltip.add(Component.literal(Handhelds.auraOf(ability).displayName + " handheld").withStyle(Handhelds.auraOf(ability).color));
		tooltip.add(Component.literal("Right-click: " + ability.displayName).withStyle(ChatFormatting.GOLD));
		tooltip.add(Component.literal(ability.description()).withStyle(ChatFormatting.GRAY));
		super.appendHoverText(stack, context, tooltip, type);
	}
}
