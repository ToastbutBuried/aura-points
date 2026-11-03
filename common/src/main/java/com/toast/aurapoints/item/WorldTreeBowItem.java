package com.toast.aurapoints.item;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Handhelds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** Verdant's handheld: a plain bow (arrows, enchantments), and sneak + right-click shoots Thorn Sentinel's seed. Unbreakable. */
public class WorldTreeBowItem extends BowItem implements software.bernie.geckolib.animatable.GeoItem {

	public WorldTreeBowItem(Properties properties) {
		super(properties.stacksTo(1).rarity(Rarity.EPIC).fireResistant()
				.component(net.minecraft.core.component.DataComponents.UNBREAKABLE, new net.minecraft.world.item.component.Unbreakable(false)));
	}

	// GeckoLib (client/GeoItems draws it)
	private final software.bernie.geckolib.animatable.instance.AnimatableInstanceCache geoCache = software.bernie.geckolib.util.GeckoLibUtil.createInstanceCache(this);

	@Override
	public void registerControllers(software.bernie.geckolib.animation.AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new software.bernie.geckolib.animation.AnimationController<>(this, "idle", 0,
				state -> state.setAndContinue(software.bernie.geckolib.animation.RawAnimation.begin().thenLoop("animation.world_tree_bow.idle"))));
	}

	@Override
	public software.bernie.geckolib.animatable.instance.AnimatableInstanceCache getAnimatableInstanceCache() {
		return geoCache;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!com.toast.aurapoints.AuraCommand.isOwner(player)) return InteractionResultHolder.fail(stack);
		if (hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown()) {
			if (level.isClientSide) return InteractionResultHolder.success(stack);
			return Handhelds.use((ServerPlayer) player, Ability.THORN_SENTINEL) ? InteractionResultHolder.consume(stack) : InteractionResultHolder.fail(stack);
		}
		return super.use(level, player, hand);
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
		HandheldItem.notWorthy(level, entity, selected);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag type) {
		tooltip.add(Component.literal("A bow of the ancient world tree, leaves and blossoms growing all over it.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		tooltip.add(Component.literal(AuraType.VERDANT.displayName + " handheld").withStyle(AuraType.VERDANT.color));
		tooltip.add(Component.literal("Sneak + right-click: " + Ability.THORN_SENTINEL.displayName).withStyle(ChatFormatting.GOLD));
		tooltip.add(Component.literal(Ability.THORN_SENTINEL.description()).withStyle(ChatFormatting.GRAY));
		super.appendHoverText(stack, context, tooltip, type);
	}
}
