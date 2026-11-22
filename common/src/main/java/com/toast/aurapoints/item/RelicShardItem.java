package com.toast.aurapoints.item;

import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Relics;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;

import java.util.List;

/**
 * A Relic Shard (5.46.0, Relic Trials): one of the four pieces a rift's trials give up, set on the altar's pedestals
 * to forge the relic. Custom data: aura, run (the rift run it belongs to), trial (which room gave it). Only exists
 * inside a rift: taken away on the way out (rift/Rifts.purge).
 */
public class RelicShardItem extends Item {

	public static final String KEY = "aurapoints_shard";

	public RelicShardItem(Properties props) {
		super(props);
	}

	public static ItemStack make(AuraType aura, int run, int trial) {
		ItemStack s = new ItemStack(com.toast.aurapoints.AuraPoints.RELIC_SHARD.get());
		CompoundTag t = new CompoundTag();
		CompoundTag in = new CompoundTag();
		in.putString("aura", aura.id());
		in.putInt("run", run);
		in.putInt("trial", trial);
		t.put(KEY, in);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(Math.max(0, Relics.AURAS.indexOf(aura)) + 1));
		s.set(DataComponents.CUSTOM_NAME, Component.literal("Shard of the " + aura.displayName + " Relic").withStyle(s2 -> s2.withColor(aura.rgb).withItalic(false)));
		return s;
	}

	private static CompoundTag data(ItemStack stack) {
		CustomData d = stack.get(DataComponents.CUSTOM_DATA);
		return d == null ? null : d.copyTag().getCompound(KEY);
	}

	public static AuraType aura(ItemStack stack) {
		CompoundTag t = data(stack);
		return t == null ? null : AuraType.byId(t.getString("aura"));
	}

	/** The run this shard belongs to, -1 if none. */
	public static int run(ItemStack stack) {
		CompoundTag t = data(stack);
		return t == null || !t.contains("run") ? -1 : t.getInt("run");
	}

	public static int trial(ItemStack stack) {
		CompoundTag t = data(stack);
		return t == null || !t.contains("trial") ? -1 : t.getInt("trial");
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return true;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.literal("Set it on one of the altar's pedestals.").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.literal("Four shards forge the relic.").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.add(Component.literal("Fades when you leave the rift.").withStyle(ChatFormatting.DARK_GRAY));
	}
}
