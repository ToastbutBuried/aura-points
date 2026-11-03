package com.toast.aurapoints.item;

import com.toast.aurapoints.AuraCommand;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Mjölnir - test phase, only the server owner may wield it (/aura mjolnir gives it). A heavy hammer:
 * hold Ability 3 to whirl it (ability/Mjolnir), a full spin draws lightning into it, and while it is
 * charged the 3rd charged hit in a row calls a thunder strike down on the target. Anyone else holding it
 * is not worthy: slowed and weakened to a crawl.
 */
public class MjolnirItem extends Item implements software.bernie.geckolib.animatable.GeoItem {

	public MjolnirItem(Properties properties) {
		super(properties.stacksTo(1).rarity(Rarity.EPIC).fireResistant().attributes(ItemAttributeModifiers.builder()
				.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID, 8.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
				.add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID, -3.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
				.build()));
	}


	// GeckoLib (client/GeoItems draws it)
	private final software.bernie.geckolib.animatable.instance.AnimatableInstanceCache geoCache = software.bernie.geckolib.util.GeckoLibUtil.createInstanceCache(this);

	@Override
	public void registerControllers(software.bernie.geckolib.animation.AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new software.bernie.geckolib.animation.AnimationController<>(this, "idle", 0,
				state -> state.setAndContinue(software.bernie.geckolib.animation.RawAnimation.begin().thenLoop("animation.mjolnir.crackle"))));
	}

	@Override
	public software.bernie.geckolib.animatable.instance.AnimatableInstanceCache getAnimatableInstanceCache() {
		return geoCache;
	}

	/** Right-click: throw it - it comes back (ability/Mjolnir). */
	@Override
	public net.minecraft.world.InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (hand != net.minecraft.world.InteractionHand.MAIN_HAND || !AuraCommand.isOwner(player)) return net.minecraft.world.InteractionResultHolder.pass(stack);
		if (level.isClientSide) return net.minecraft.world.InteractionResultHolder.success(stack);
		return com.toast.aurapoints.ability.Mjolnir.throwHammer((ServerPlayer) player)
				? net.minecraft.world.InteractionResultHolder.consume(stack) : net.minecraft.world.InteractionResultHolder.pass(stack);
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
		if (level.isClientSide || !selected || !(entity instanceof ServerPlayer player) || AuraCommand.isOwner(player)) return;
		// not worthy: it barely moves
		player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 3, false, false, true));
		player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 2, false, false, true));
		if (player.tickCount % 40 == 0) {
			player.displayClientMessage(Component.literal("You are not worthy.").withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC), true);
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag type) {
		tooltip.add(Component.literal("Test phase - only the worthy can wield it.").withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
		tooltip.add(Component.literal("Hold Ability 3 to spin it until lightning strikes it.").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.literal("Right-click to throw it - it comes back. While spinning: a faster throw.").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.literal("Spinning, left-click: fly with it.").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.literal("Charged: " + AuraConfig.MJOLNIR_STREAK_HITS + " full hits in a row - the last one calls down thunder.").withStyle(ChatFormatting.GRAY));
		super.appendHoverText(stack, context, tooltip, type);
	}
}
