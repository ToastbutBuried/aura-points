package com.toast.aurapoints.fabric;

import com.mojang.serialization.Codec;
import com.toast.aurapoints.AccessorySlots;
import com.toast.aurapoints.shim.neoforge.items.ItemStackHandler;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Data saved on entities: the accessory slots (players, kept through death) and the mod's per-entity tag. */
public final class FabricAttachments {
	private FabricAttachments() {
	}

	/** MAX slots in order; saved as a list of optional stacks. */
	public static final class Slots implements ItemStackHandler {
		final NonNullList<ItemStack> stacks = NonNullList.withSize(AccessorySlots.MAX, ItemStack.EMPTY);

		static final Codec<Slots> CODEC = ItemStack.OPTIONAL_CODEC.listOf().xmap(list -> {
			Slots s = new Slots();
			for (int i = 0; i < Math.min(list.size(), s.stacks.size()); i++) s.stacks.set(i, list.get(i));
			return s;
		}, s -> new ArrayList<>(s.stacks));

		@Override
		public int getSlots() {
			return stacks.size();
		}

		@Override
		public ItemStack getStackInSlot(int slot) {
			return stacks.get(slot);
		}

		@Override
		public void setStackInSlot(int slot, ItemStack stack) {
			stacks.set(slot, stack);
		}
	}

	public static final AttachmentType<Slots> ACCESSORIES = AttachmentRegistry.<Slots>builder()
			.persistent(Slots.CODEC).copyOnDeath().initializer(Slots::new)
			.buildAndRegister(ResourceLocation.fromNamespaceAndPath("aurapoints", "accessories"));

	public static final AttachmentType<CompoundTag> DATA = AttachmentRegistry.<CompoundTag>builder()
			.persistent(CompoundTag.CODEC).initializer(CompoundTag::new)
			.buildAndRegister(ResourceLocation.fromNamespaceAndPath("aurapoints", "data"));

	static void init() {
		// class load registers both
	}

	public static ItemStackHandler accessories(Player player) {
		return player.getAttachedOrCreate(ACCESSORIES);
	}

	public static CompoundTag persistentData(Entity entity) {
		return entity.getAttachedOrCreate(DATA);
	}

	/** For the accessory slot list's codec (unused stacks stay empty). */
	static List<ItemStack> copy(Slots s) {
		return new ArrayList<>(s.stacks);
	}
}
