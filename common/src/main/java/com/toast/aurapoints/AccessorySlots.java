package com.toast.aurapoints;

import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SlotRange;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import com.toast.aurapoints.shim.neoforge.items.ItemStackHandler;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Accessory slots: a column of extra slots left of the armor in the survival inventory, as many as
 * ACCESSORY_SLOTS in aurapoints-general.json (0..{@link #MAX}). Any item fits, one per slot unless ACCESSORY_SINGLE_ITEM is false. The menu always
 * has MAX of them (both sides must agree on the slot list); the ones past the configured count are switched off.
 * Datapacks read and write them like vanilla slots: {@code accessory.0} .. {@code accessory.<MAX-1>} and
 * {@code accessory.*}, e.g. {@code execute if items entity @s accessory.* minecraft:totem_of_undying}
 * or {@code item replace entity @s accessory.0 with minecraft:diamond}.
 * Items drop on death like the rest of the inventory (kept with keepInventory).
 */
public final class AccessorySlots {

	private AccessorySlots() {
	}

	public static final int MAX = 12;
	/** Slot ids for /item and execute if items (vanilla uses up to 514). */
	public static final int FIRST_SLOT_ID = 700;
	/** Slots per column; the first column sits right next to the armor. */
	public static final int COLUMN = 4;

	/** Saved per player as the "aurapoints:accessories" attachment (MAX slots, kept through death): see Platform.accessories. */

	/** The server's slot count as this client knows it (AccessorySlotsPacket). */
	public static volatile int clientCount = 0;
	/** The server's ACCESSORY_SINGLE_ITEM as this client knows it. */
	public static volatile boolean clientSingleItem = true;
	/** Client only: true while the slots must stay hidden (creative inventory, open recipe book). */
	public static BooleanSupplier clientHidden = () -> false;

	/** Items per slot: 1 with ACCESSORY_SINGLE_ITEM, else a normal stack (still capped by the item's own stack size). */
	public static int stackLimit(boolean clientSide) {
		return (clientSide ? clientSingleItem : AuraConfig.ACCESSORY_SINGLE_ITEM) ? 1 : Item.ABSOLUTE_MAX_STACK_SIZE;
	}

	public static int count(boolean clientSide) {
		return Math.max(0, Math.min(MAX, clientSide ? clientCount : AuraConfig.ACCESSORY_SLOTS));
	}

	public static ItemStackHandler items(Player player) {
		return com.toast.aurapoints.shim.Platform.get().accessories(player);
	}

	/** Slot position inside the inventory screen: columns of 4 growing to the left of the armor. */
	public static int slotX(int index) {
		return -21 - (index / COLUMN) * 18;
	}

	public static int slotY(int index) {
		return 8 + (index % COLUMN) * 18;
	}

	/** Called at the end of InventoryMenu's constructor (InventoryMenuMixin). */
	public static void addSlots(Player player, java.util.function.Consumer<Slot> addSlot) {
		Container container = new PlayerContainer(player);
		for (int i = 0; i < MAX; i++) addSlot.accept(new AccessorySlot(container, player, i));
	}

	/**
	 * Items in slots past the configured count, and anything over the per-slot limit (ACCESSORY_SINGLE_ITEM switched
	 * back on), go back into the inventory (dropped if it is full).
	 */
	public static void tidy(ServerPlayer player) {
		ItemStackHandler items = items(player);
		int count = count(false), limit = stackLimit(false);
		for (int i = 0; i < items.getSlots(); i++) {
			ItemStack stack = items.getStackInSlot(i);
			int keep = i < count ? limit : 0;
			if (stack.getCount() <= keep) continue;
			ItemStack extra = stack.copyWithCount(stack.getCount() - keep);
			items.setStackInSlot(i, keep == 0 ? ItemStack.EMPTY : stack.copyWithCount(keep));
			player.getInventory().placeItemBackInInventory(extra);
		}
	}

	/** Death without keepInventory: the accessories drop with the rest (Curse of Vanishing ones vanish). */
	public static void onDrops(ServerPlayer player, Collection<ItemEntity> drops) {
		if (player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
		ItemStackHandler items = items(player);
		for (int i = 0; i < items.getSlots(); i++) {
			ItemStack stack = items.getStackInSlot(i);
			if (stack.isEmpty()) continue;
			items.setStackInSlot(i, ItemStack.EMPTY);
			if (EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) continue;
			ItemEntity drop = new ItemEntity(player.level(), player.getX(), player.getEyeY() - 0.3, player.getZ(), stack);
			drop.setDefaultPickUpDelay();
			drops.add(drop);
		}
	}

	/** /item and execute if items access for slot id {@code id}, or null if it is not an (enabled) accessory slot. */
	public static SlotAccess slotAccess(Player player, int id) {
		int index = id - FIRST_SLOT_ID;
		if (index < 0 || index >= count(player.level().isClientSide)) return null;
		return new SlotAccess() {
			@Override
			public ItemStack get() {
				return items(player).getStackInSlot(index);
			}

			@Override
			public boolean set(ItemStack stack) {
				// no more than the inventory screen allows
				int limit = stackLimit(player.level().isClientSide);
				items(player).setStackInSlot(index, stack.getCount() > limit ? stack.copyWithCount(limit) : stack);
				return true;
			}
		};
	}

	/** Adds accessory.0 .. accessory.N and accessory.* to the vanilla slot names (SlotRangesMixin). */
	public static void addSlotNames(List<SlotRange> slots) {
		IntList all = new IntArrayList();
		for (int i = 0; i < MAX; i++) {
			slots.add(SlotRange.of("accessory." + i, IntList.of(FIRST_SLOT_ID + i)));
			all.add(FIRST_SLOT_ID + i);
		}
		slots.add(SlotRange.of("accessory.*", all));
	}

	/** A view of the player's accessory items as a vanilla Container (looked up each time: loading a save replaces the attachment). */
	private record PlayerContainer(Player player) implements Container {

		private ItemStackHandler items() {
			return AccessorySlots.items(player);
		}

		@Override
		public int getContainerSize() {
			return MAX;
		}

		@Override
		public boolean isEmpty() {
			for (int i = 0; i < MAX; i++) if (!getItem(i).isEmpty()) return false;
			return true;
		}

		@Override
		public ItemStack getItem(int slot) {
			return items().getStackInSlot(slot);
		}

		@Override
		public ItemStack removeItem(int slot, int amount) {
			ItemStack stack = getItem(slot);
			if (stack.isEmpty() || amount <= 0) return ItemStack.EMPTY;
			ItemStack taken = stack.split(amount);
			setItem(slot, stack);
			return taken;
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			ItemStack stack = getItem(slot);
			setItem(slot, ItemStack.EMPTY);
			return stack;
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			items().setStackInSlot(slot, stack);
		}

		@Override
		public int getMaxStackSize() {
			return stackLimit(player.level().isClientSide);
		}

		@Override
		public void setChanged() {
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}

		@Override
		public void clearContent() {
			for (int i = 0; i < MAX; i++) setItem(i, ItemStack.EMPTY);
		}
	}

	private static final class AccessorySlot extends Slot {

		private final Player player;

		AccessorySlot(Container container, Player player, int index) {
			super(container, index, slotX(index), slotY(index));
			this.player = player;
		}

		private boolean enabled() {
			return getContainerSlot() < count(player.level().isClientSide);
		}

		@Override
		public boolean isActive() {
			return enabled() && !(player.level().isClientSide && clientHidden.getAsBoolean());
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return enabled();
		}

		@Override
		public boolean mayPickup(Player player) {
			return enabled() && super.mayPickup(player);
		}

		@Override
		public int getMaxStackSize() {
			return stackLimit(player.level().isClientSide);
		}
	}
}
