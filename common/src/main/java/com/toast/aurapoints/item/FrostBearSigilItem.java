package com.toast.aurapoints.item;

import com.toast.aurapoints.geo.FrostBear;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Right-click a block: the Frost Bear (geo/FrostBear) rises there, a random colour. The sigil is used up. */
public class FrostBearSigilItem extends Item {

	public FrostBearSigilItem(Properties properties) {
		super(properties.stacksTo(4).rarity(Rarity.EPIC));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
		var pos = context.getClickedPos().relative(context.getClickedFace());
		FrostBear bear = FrostBear.spawn(level, new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), -1);
		if (bear == null) return InteractionResult.FAIL;
		if (context.getPlayer() != null) {
			bear.setTarget(context.getPlayer());
			if (!context.getPlayer().getAbilities().instabuild) context.getItemInHand().shrink(1);
		}
		level.playSound(null, pos, com.toast.aurapoints.ability.AuraSounds.GLASS_BREAK.value(), SoundSource.HOSTILE, 2.0f, 0.5f);
		return InteractionResult.CONSUME;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag type) {
		tooltip.add(Component.literal("Right-click the ground to wake the Frost Bear.").withStyle(ChatFormatting.AQUA));
		tooltip.add(Component.literal("It never dies - it flees. Those who hurt it earn a Blue Bell.").withStyle(ChatFormatting.GRAY));
		super.appendHoverText(stack, context, tooltip, type);
	}
}
