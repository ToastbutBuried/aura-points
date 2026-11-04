package com.toast.aurapoints.menu;

import com.toast.aurapoints.AuraCommand;
import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Aura Book: a chest menu over the player commands (like the Balance Tome, everything runs on the
 * server, so no client code). The book holds no data: it always shows whoever opens it. Buttons run the
 * same /aura commands a player could type, so permissions (CommandRules) and messages stay identical.
 */
public final class AuraBook {

	private AuraBook() {
	}

	/** The book or /aura menu: the tutorial prompt first if it is still on offer, else the main menu. */
	public static void open(ServerPlayer player) {
		AuraPlayerData data = data(player);
		if (Tutorial.wantsPrompt(player, data)) open(player, new PromptPage());
		else open(player, new MainPage());
	}

	static void openMain(ServerPlayer player) {
		open(player, new MainPage());
	}

	static void open(ServerPlayer player, Page page) {
		page.opened(player, data(player));
		if (com.toast.aurapoints.shim.Platform.get().isFakePlayer(player)) {
			// dev tests: a fake player's openMenu does nothing, so hand it the menu directly
			player.containerMenu = new BookMenu(0, player.getInventory(), page, player);
			return;
		}
		player.openMenu(new MenuProvider() {
			@Override
			public Component getDisplayName() {
				return page.title(player);
			}

			@Override
			public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
				return new BookMenu(id, inv, page, player);
			}
		});
	}

	static AuraPlayerData data(ServerPlayer player) {
		return AuraState.get(player.getServer()).getOrCreate(player.getUUID());
	}

	/** Runs an /aura command as the player (no leading slash), exactly as if they typed it. */
	static void run(ServerPlayer player, String command) {
		player.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
	}

	static void pageSound(ServerPlayer player) {
		player.playNotifySound(SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.7f, 1.0f);
	}

	// ---------------------------------------------------------------
	// Menu + icons
	// ---------------------------------------------------------------

	private static final class BookMenu extends ChestMenu {
		private final Page page;
		private final SimpleContainer box;
		private final ServerPlayer viewer;

		BookMenu(int id, Inventory inv, Page page, ServerPlayer viewer) {
			this(id, inv, page, viewer, new SimpleContainer(page.rows() * 9));
		}

		private BookMenu(int id, Inventory inv, Page page, ServerPlayer viewer, SimpleContainer box) {
			super(page.rows() == 3 ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6, id, inv, box, page.rows());
			this.page = page;
			this.box = box;
			this.viewer = viewer;
			fill();
		}

		private void fill() {
			ItemStack[] slots = new ItemStack[page.rows() * 9];
			java.util.Arrays.fill(slots, ItemStack.EMPTY);
			page.fill(viewer, data(viewer), slots);
			for (int i = 0; i < slots.length; i++) box.setItem(i, slots[i]);
		}

		@Override
		public void clicked(int slot, int button, ClickType type, Player player) {
			if (player == viewer && slot >= 0 && slot < page.rows() * 9 && type != ClickType.PICKUP_ALL && type != ClickType.QUICK_CRAFT
					&& !box.getItem(slot).isEmpty()) {
				page.click(viewer, data(viewer), slot);
			}
			if (viewer.containerMenu == this) {
				fill();
				setCarried(ItemStack.EMPTY);
				sendAllDataToRemote();
			}
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			return ItemStack.EMPTY;
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}

		@Override
		public void removed(Player player) {
			setCarried(ItemStack.EMPTY);
			super.removed(player);
		}
	}

	static ItemStack icon(Item item, Component name, List<Component> lore, boolean glint) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(st -> st.withItalic(false)));
		List<Component> lines = new ArrayList<>();
		for (Component c : lore) lines.add(c.copy().withStyle(st -> st.withItalic(false)));
		stack.set(DataComponents.LORE, new ItemLore(lines));
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY.withTooltip(false));
		stack.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
		if (glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return stack;
	}

	static Component text(String s, ChatFormatting... style) {
		return Component.literal(s).withStyle(style);
	}

	/** Splits a long description into tooltip lines of about {@code width} characters. */
	static List<Component> wrap(String s, int width, ChatFormatting color) {
		List<Component> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			if (line.length() > 0 && line.length() + word.length() + 1 > width) {
				out.add(text(line.toString(), color));
				line.setLength(0);
			}
			if (line.length() > 0) line.append(' ');
			line.append(word);
		}
		if (line.length() > 0) out.add(text(line.toString(), color));
		return out;
	}

	static Item auraIcon(AuraType aura) {
		if (aura == null) return Items.ENDER_EYE;
		return switch (aura) {
			case SHADOW -> Items.ECHO_SHARD;
			case WATER -> Items.HEART_OF_THE_SEA;
			case FIRE -> Items.BLAZE_POWDER;
			case WIND -> Items.FEATHER;
			case FROST -> Items.BLUE_ICE;
			case VERDANT -> Items.OAK_SAPLING;
			case THUNDER -> Items.LIGHTNING_ROD;
			case RESONANCE -> Items.SCULK_SENSOR;
			case EARTH -> Items.MOSSY_COBBLESTONE;
			case LIGHT -> Items.GLOWSTONE_DUST;
			case BLOOD -> Items.REDSTONE;
		};
	}

	static Component auraName(AuraType aura) {
		return Component.literal(aura.displayName + " Aura").withStyle(aura.color, ChatFormatting.BOLD);
	}

	static final int BACK = 45, CLOSE = 49;

	/** Bottom row of a 6-row page: glass, Back (unless main), Close. */
	static void bar(ItemStack[] slots, boolean back) {
		ItemStack pane = icon(Items.BLACK_STAINED_GLASS_PANE, Component.literal(" "), List.of(), false);
		for (int i = 45; i < 54; i++) slots[i] = pane;
		if (back) slots[BACK] = icon(Items.OAK_DOOR, text("Back", ChatFormatting.WHITE), List.of(), false);
		slots[CLOSE] = icon(Items.BARRIER, text("Close", ChatFormatting.RED), List.of(), false);
	}

	/** Handles Back/Close; true if the click was one of them. */
	static boolean barClick(ServerPlayer player, int slot) {
		if (slot == BACK) {
			pageSound(player);
			openMain(player);
			return true;
		}
		if (slot == CLOSE) {
			player.closeContainer();
			return true;
		}
		return slot > 44;
	}

	// ---------------------------------------------------------------
	// Pages
	// ---------------------------------------------------------------

	abstract static class Page {
		abstract Component title(ServerPlayer player);

		int rows() {
			return 6;
		}

		/** Called once when the page opens (the tutorial counts page visits). */
		void opened(ServerPlayer player, AuraPlayerData data) {
		}

		abstract void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots);

		abstract void click(ServerPlayer player, AuraPlayerData data, int slot);
	}

	/** "Tutorial?" with Yes / No / Don't show again, nothing else. */
	static final class PromptPage extends Page {
		static final int YES = 11, NO = 13, NEVER = 15;

		@Override
		Component title(ServerPlayer player) {
			return Component.literal(Tutorial.started(data(player)) ? "Continue the tutorial?" : "Tutorial?");
		}

		@Override
		int rows() {
			return 3;
		}

		@Override
		void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots) {
			boolean resume = Tutorial.started(data);
			slots[YES] = icon(Items.LIME_DYE, text(resume ? "Yes, continue" : "Yes", ChatFormatting.GREEN, ChatFormatting.BOLD), List.of(
					text(resume ? "Pick up where you left off." : "A short walk through your aura,", ChatFormatting.GRAY),
					text(resume ? "" : "the Aura Book and how to get stronger.", ChatFormatting.GRAY)), true);
			slots[NO] = icon(Items.GRAY_DYE, text("No", ChatFormatting.YELLOW), List.of(
					text("Not now. The book asks again", ChatFormatting.GRAY),
					text("next time you open it.", ChatFormatting.GRAY)), false);
			slots[NEVER] = icon(Items.BARRIER, text("Don't show again", ChatFormatting.RED), List.of(
					text("The book stops asking.", ChatFormatting.GRAY),
					text("You can still start it from the menu.", ChatFormatting.DARK_GRAY)), false);
		}

		@Override
		void click(ServerPlayer player, AuraPlayerData data, int slot) {
			switch (slot) {
				case YES -> {
					pageSound(player);
					Tutorial.start(player, data);
					openMain(player);
				}
				case NO -> {
					pageSound(player);
					openMain(player);
				}
				case NEVER -> {
					pageSound(player);
					Tutorial.dismiss(player, data);
					openMain(player);
				}
				default -> {
				}
			}
		}
	}

	static final class MainPage extends Page {
		static final int HEADER = 4, AURA = 20, ROLL = 21, SKILLS = 22, COSMETICS = 23, WEEKLY = 24, SETTINGS = 30, EARN = 31, TUTORIAL = 32, ARENA = 29, REACTIONS = 33;

		@Override
		Component title(ServerPlayer player) {
			return Component.literal("Aura Book");
		}

		@Override
		void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots) {
			int balance = AuraCurrency.get(player);
			List<Component> head = new ArrayList<>();
			head.add(text("Aura: " + balance, ChatFormatting.LIGHT_PURPLE));
			head.add(text(EarnPage.questsLoaded() ? "Earn more: quests, mobs, weeklies, arena." : "Earn more: mobs, weeklies, arena.", ChatFormatting.GRAY));
			head.add(text("Click: ways to earn Aura", ChatFormatting.YELLOW));
			head.add(text("Every command still works: /aura", ChatFormatting.DARK_GRAY));
			slots[HEADER] = icon(Items.WRITTEN_BOOK, text(player.getGameProfile().getName() + "'s Aura", ChatFormatting.GOLD), head, false);

			if (data.aura == null) {
				slots[AURA] = icon(Items.ENDER_EYE, text("No aura yet", ChatFormatting.GRAY), List.of(
						text("Roll one first (next to this).", ChatFormatting.DARK_GRAY)), false);
			} else {
				List<Component> lore = new ArrayList<>(wrap(AuraCommand.role(data.aura), 36, ChatFormatting.GRAY));
				lore.add(text("Mastery " + data.mastery.getOrDefault(data.aura, 0) + " / 5"
						+ (data.awakened.contains(data.aura) ? "  ·  Awakened" : ""), ChatFormatting.AQUA));
				lore.add(text("Click: abilities, buy actives", ChatFormatting.YELLOW));
				slots[AURA] = icon(auraIcon(data.aura), auraName(data.aura), lore, data.awakened.contains(data.aura));
			}
			slots[ROLL] = icon(Items.AMETHYST_SHARD, text(data.freeRollUsed ? "Reroll" : "Roll your aura", ChatFormatting.GREEN), List.of(
					text(data.freeRollUsed ? "Costs " + AuraConfig.REROLL_COST + " Aura (never the one you have)." : "Your first roll is free.", ChatFormatting.GRAY),
					text("Click: see every aura", ChatFormatting.YELLOW)), !data.freeRollUsed);
			slots[SKILLS] = icon(Items.EXPERIENCE_BOTTLE, text("Skill points", ChatFormatting.LIGHT_PURPLE), List.of(
					text("Next point: " + AuraCurrency.nextSkillPointCost(data) + " Aura", ChatFormatting.GRAY),
					text("Click: buy points for the skill trees", ChatFormatting.YELLOW)), false);
			if (AuraConfig.COSMETICS_ENABLED) {
				slots[COSMETICS] = icon(Items.FIREWORK_STAR, text("Cosmetics", ChatFormatting.LIGHT_PURPLE), List.of(
						text("Halos, trails, kill effects, titles, poses", ChatFormatting.GRAY),
						text("Click: open the list in chat", ChatFormatting.YELLOW)), false);
			}
			slots[WEEKLY] = icon(Items.CLOCK, text("Weekly challenges", ChatFormatting.AQUA), List.of(
					text(data.weeklyDone + " done this week", ChatFormatting.GRAY),
					text("Click: show them in chat", ChatFormatting.YELLOW)), false);
			slots[SETTINGS] = icon(Items.COMPARATOR, text("Settings", ChatFormatting.WHITE), List.of(
					text("Self-hide, Bloom, Night Vision", ChatFormatting.GRAY),
					text("Keys: Options > Controls > Aura Points", ChatFormatting.DARK_GRAY)), false);
			slots[EARN] = icon(Items.EMERALD, text("Earn Aura", ChatFormatting.GOLD, ChatFormatting.BOLD), List.of(
					text(EarnPage.questsLoaded() ? "Quest book, aura-touched mobs," : "Aura-touched mobs,", ChatFormatting.GRAY),
					text("weekly challenges, arena and more", ChatFormatting.GRAY),
					text("Click: see every way to earn", ChatFormatting.YELLOW)), EarnPage.questsLoaded());
			slots[ARENA] = icon(Items.IRON_SWORD, text("Arena", ChatFormatting.RED), List.of(
					text("Duels, team matches, Wave Survival", ChatFormatting.GRAY),
					text("and the Training Hall.", ChatFormatting.GRAY),
					text("Click: join the Arena lobby", ChatFormatting.YELLOW)), false);
			slots[REACTIONS] = icon(Items.BLAZE_POWDER, text("Reactions", ChatFormatting.GOLD), List.of(
					text("What happens when two elements meet.", ChatFormatting.GRAY),
					text("Click: all reactions", ChatFormatting.YELLOW)), false);
			slots[TUTORIAL] = Tutorial.menuIcon(data);
			bar(slots, false);
			Tutorial.highlight(data, slots);
		}

		@Override
		void click(ServerPlayer player, AuraPlayerData data, int slot) {
			if (barClick(player, slot)) return;
			switch (slot) {
				case HEADER, EARN -> {
					pageSound(player);
					open(player, new EarnPage());
				}
				case AURA -> {
					if (data.aura != null) {
						pageSound(player);
						open(player, new AuraPage());
					}
				}
				case ROLL -> {
					pageSound(player);
					open(player, new RollPage());
				}
				case SKILLS -> {
					pageSound(player);
					open(player, new SkillsPage());
				}
				case COSMETICS -> {
					player.closeContainer();
					run(player, "aura cosmetics");
				}
				case WEEKLY -> {
					player.closeContainer();
					run(player, "aura weekly");
				}
				case SETTINGS -> {
					pageSound(player);
					open(player, new SettingsPage());
				}
				case ARENA -> {
					pageSound(player);
					open(player, new ArenaPage());
				}
				case REACTIONS -> {
					pageSound(player);
					open(player, new ReactionsPage());
				}
				case TUTORIAL -> Tutorial.menuClick(player, data);
				default -> {
				}
			}
		}
	}

	/** Your aura: role, matchups, actives (buy here) and passives. */
	static final class AuraPage extends Page {
		static final int[] ACTIVE_SLOTS = {19, 20, 21, 22, 23, 24, 25};
		static final int[] PASSIVE_SLOTS = {37, 38, 39, 40, 41, 42, 43};
		static final int INFO = 47;

		@Override
		Component title(ServerPlayer player) {
			AuraType aura = data(player).aura;
			return Component.literal(aura == null ? "Your aura" : aura.displayName + " Aura");
		}

		@Override
		void opened(ServerPlayer player, AuraPlayerData data) {
			Tutorial.visited(player, data, Tutorial.Step.AURA);
		}

		private static List<AuraAbilities.Ability> actives(AuraType aura, com.toast.aurapoints.ability.Branch branch) {
			return aura.abilities(branch).stream().filter(a -> a.active).toList();
		}

		private static List<AuraAbilities.Ability> passives(AuraType aura, com.toast.aurapoints.ability.Branch branch) {
			List<AuraAbilities.Ability> out = new ArrayList<>(aura.abilities(branch).stream().filter(a -> !a.active).toList());
			out.addAll(AuraType.GLOBAL);
			return out;
		}

		@Override
		void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots) {
			AuraType aura = data.aura;
			bar(slots, true);
			if (aura == null) return;
			com.toast.aurapoints.ability.Branch branch = com.toast.aurapoints.ability.Branch.current(data);
			List<Component> head = new ArrayList<>(wrap(AuraCommand.role(aura), 36, ChatFormatting.GRAY));
			if (!aura.strongAgainst(branch).isEmpty()) {
				head.add(text("Strong against: " + String.join(", ", aura.strongAgainst(branch).stream().map(t -> t.displayName).toList()), ChatFormatting.GREEN));
			}
			if (!aura.weakAgainst(branch).isEmpty()) {
				head.add(text("Weak against: " + String.join(", ", aura.weakAgainst(branch).stream().map(t -> t.displayName).toList()), ChatFormatting.RED));
			}
			head.add(text("Aura: " + AuraCurrency.get(player), ChatFormatting.LIGHT_PURPLE));
			slots[4] = icon(auraIcon(aura), auraName(aura), head, data.awakened.contains(aura));
			if (!com.toast.aurapoints.ability.Branch.of(aura).isEmpty() && AuraConfig.BRANCHES_ENABLED) {
				var picked = com.toast.aurapoints.ability.Branch.current(data);
				boolean open = com.toast.aurapoints.ability.Branch.unlocked(data);
				slots[6] = icon(open ? Items.RECOVERY_COMPASS : Items.COMPASS, text("Branches", open ? ChatFormatting.GOLD : ChatFormatting.GRAY), List.of(
						text(picked != null ? picked.label() : open ? "Choose your branch" : "Unlocks at Mastery " + com.toast.aurapoints.ability.Mastery.roman(AuraConfig.BRANCH_MASTERY_REQUIRED),
								picked != null ? picked.color : ChatFormatting.GRAY),
						text("Click: see both branches", ChatFormatting.YELLOW)), picked != null);
			}

			slots[10] = icon(Items.BLAZE_ROD, text("Actives", ChatFormatting.GOLD), List.of(
					text("Bought once, used with your keys.", ChatFormatting.GRAY)), false);
			List<AuraAbilities.Ability> actives = actives(aura, branch);
			int price = AuraCommand.nextShopPrice(data);
			for (int i = 0; i < actives.size() && i < ACTIVE_SLOTS.length; i++) {
				AuraAbilities.Ability a = actives.get(i);
				boolean owned = data.owned.contains(a), off = AuraAbilities.DISABLED.contains(a);
				List<Component> lore = new ArrayList<>(wrap(a.description(), 38, ChatFormatting.GRAY));
				int key = aura.keySlots(branch).indexOf(a);
				lore.add(text(key >= 0 ? "Key: Ability " + (key + 1) : "Key: jump", ChatFormatting.DARK_AQUA));
				if (a.cooldownTicks() > 0) lore.add(text("Cooldown: " + AuraAbilities.fmt(a.cooldownTicks() / 20f) + "s", ChatFormatting.DARK_GRAY));
				if (off) lore.add(text("Switched off on this server", ChatFormatting.RED));
				else if (owned) lore.add(text("✔ Owned", ChatFormatting.GREEN));
				else lore.add(text("Click: buy for " + price + " Aura", ChatFormatting.YELLOW));
				Item item = owned ? Items.ENCHANTED_BOOK : off ? Items.GRAY_DYE : Items.BOOK;
				slots[ACTIVE_SLOTS[i]] = icon(item, text(a.displayName, owned ? ChatFormatting.GREEN : ChatFormatting.WHITE, ChatFormatting.BOLD), lore, false);
			}
			slots[28] = icon(Items.GLOWSTONE_DUST, text("Passives", ChatFormatting.AQUA), List.of(
					text("Always on, free.", ChatFormatting.GRAY)), false);
			List<AuraAbilities.Ability> passives = passives(aura, branch);
			for (int i = 0; i < passives.size() && i < PASSIVE_SLOTS.length; i++) {
				AuraAbilities.Ability a = passives.get(i);
				List<Component> lore = new ArrayList<>(wrap(a.description(), 38, ChatFormatting.GRAY));
				if (AuraAbilities.DISABLED.contains(a)) lore.add(text("Switched off on this server", ChatFormatting.RED));
				slots[PASSIVE_SLOTS[i]] = icon(Items.PAPER, text(a.displayName, ChatFormatting.AQUA), lore, false);
			}
			slots[INFO] = icon(Items.WRITABLE_BOOK, text("Full info in chat", ChatFormatting.WHITE), List.of(
					text("Same as /aura info", ChatFormatting.DARK_GRAY)), false);
		}

		@Override
		void click(ServerPlayer player, AuraPlayerData data, int slot) {
			if (slot == INFO) {
				player.closeContainer();
				run(player, "aura info");
				return;
			}
			if (barClick(player, slot) || data.aura == null) return;
			if (slot == 6 && !com.toast.aurapoints.ability.Branch.of(data.aura).isEmpty() && AuraConfig.BRANCHES_ENABLED) {
				open(player, new BranchPage());
				return;
			}
			List<AuraAbilities.Ability> actives = actives(data.aura, com.toast.aurapoints.ability.Branch.current(data));
			for (int i = 0; i < actives.size() && i < ACTIVE_SLOTS.length; i++) {
				AuraAbilities.Ability a = actives.get(i);
				if (ACTIVE_SLOTS[i] == slot && !data.owned.contains(a) && !AuraAbilities.DISABLED.contains(a)) {
					run(player, "aura shop buy " + a.name().toLowerCase(Locale.ROOT));
					return;
				}
			}
		}
	}

	/** The two branches of the held aura: what each changes, the pick (free the first time, then BRANCH_SWITCH_COST). */
	static final class BranchPage extends Page {
		static final int[] SPOTS = {21, 23};

		@Override
		Component title(ServerPlayer player) {
			AuraType aura = data(player).aura;
			return Component.literal(aura == null ? "Branches" : aura.displayName + " branches");
		}

		@Override
		void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots) {
			bar(slots, true);
			if (data.aura == null) return;
			String blocker = com.toast.aurapoints.ability.Branch.blocker(data);
			List<com.toast.aurapoints.ability.Branch> branches = com.toast.aurapoints.ability.Branch.of(data.aura);
			var mine = data.branches.get(data.aura);
			slots[4] = icon(Items.RECOVERY_COMPASS, text("Branches", ChatFormatting.GOLD), blocker != null
					? List.of(text(blocker, ChatFormatting.RED))
					: List.of(text(mine == null ? "Pick one (free the first time)." : "Switching costs " + AuraConfig.BRANCH_SWITCH_COST + " Aura.", ChatFormatting.GRAY),
							text("Aura: " + AuraCurrency.get(player), ChatFormatting.LIGHT_PURPLE)), false);
			for (int i = 0; i < branches.size() && i < SPOTS.length; i++) {
				var b = branches.get(i);
				List<Component> lore = new ArrayList<>();
				lore.add(text(b.role, ChatFormatting.DARK_AQUA));
				for (String line : b.description()) lore.addAll(wrap(line, 38, ChatFormatting.GRAY));
				if (b == mine) lore.add(text("✔ Your branch", ChatFormatting.GREEN));
				else if (blocker == null) lore.add(text(mine == null ? "Click: choose (free)" : "Click: switch for " + AuraConfig.BRANCH_SWITCH_COST + " Aura", ChatFormatting.YELLOW));
				slots[SPOTS[i]] = icon(b == com.toast.aurapoints.ability.Branch.BLUE_FLAME ? Items.SOUL_CAMPFIRE : Items.MAGMA_BLOCK,
						text(b.displayName, b.color, ChatFormatting.BOLD), lore, b == mine);
			}
		}

		@Override
		void click(ServerPlayer player, AuraPlayerData data, int slot) {
			if (barClick(player, slot) || data.aura == null) return;
			List<com.toast.aurapoints.ability.Branch> branches = com.toast.aurapoints.ability.Branch.of(data.aura);
			for (int i = 0; i < branches.size() && i < SPOTS.length; i++) {
				if (SPOTS[i] == slot) {
					run(player, "aura branch " + branches.get(i).id());
					return;
				}
			}
		}
	}

	/** Every rollable aura, plus the roll button (a reroll needs a second click). */
	static final class RollPage extends Page {
		static final int ROLL = 49;
		private boolean armed;

		@Override
		Component title(ServerPlayer player) {
			return Component.literal("Roll an aura");
		}

		@Override
		void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots) {
			int i = 10;
			for (AuraType aura : AuraType.values()) {
				if (!aura.rollable()) continue;
				if (i % 9 == 8) i += 2;
				if (i > 43) break;
				List<Component> lore = new ArrayList<>(wrap(AuraCommand.role(aura), 36, ChatFormatting.GRAY));
				if (aura == data.aura) lore.add(text("Yours (a reroll never gives it again)", ChatFormatting.DARK_GRAY));
				slots[i++] = icon(auraIcon(aura), auraName(aura), lore, aura == data.aura);
			}
			bar(slots, true);
			boolean free = !data.freeRollUsed;
			String label = free ? "Roll - free" : armed ? "Click again to reroll" : "Reroll - " + AuraConfig.REROLL_COST + " Aura";
			slots[ROLL] = icon(armed ? Items.LIME_CONCRETE : Items.AMETHYST_CLUSTER, text(label, ChatFormatting.GREEN, ChatFormatting.BOLD), List.of(
					text("You have " + AuraCurrency.get(player) + " Aura.", ChatFormatting.GRAY),
					text(free ? "Your aura is picked at random." : "You lose your current aura (bought actives come back if you roll it again).", ChatFormatting.DARK_GRAY)), true);
			slots[53] = icon(Items.BARRIER, text("Close", ChatFormatting.RED), List.of(), false);
		}

		@Override
		void click(ServerPlayer player, AuraPlayerData data, int slot) {
			if (slot == ROLL) {
				if (data.freeRollUsed && !armed) {
					armed = true;
					pageSound(player);
					return;
				}
				player.closeContainer();
				run(player, "aura roll confirm");
				return;
			}
			if (slot == 53) {
				player.closeContainer();
				return;
			}
			if (slot == BACK) barClick(player, slot);
		}
	}

	static final class SkillsPage extends Page {
		static final int MAIN = 20, MAIN5 = 21, PVP = 24;

		@Override
		Component title(ServerPlayer player) {
			return Component.literal("Skill points");
		}

		@Override
		void opened(ServerPlayer player, AuraPlayerData data) {
			Tutorial.visited(player, data, Tutorial.Step.SKILLS);
		}

		@Override
		void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots) {
			int next = AuraCurrency.nextSkillPointCost(data);
			slots[4] = icon(Items.EXPERIENCE_BOTTLE, text("Skill points", ChatFormatting.LIGHT_PURPLE), List.of(
					text("Buy points with Aura, then spend them", ChatFormatting.GRAY),
					text("in the skill tree screen (Puffish's Skills).", ChatFormatting.GRAY),
					text("Every point costs " + AuraConfig.SKILL_POINT_COST_STEP + " Aura more than the last.", ChatFormatting.DARK_GRAY),
					text("Aura: " + AuraCurrency.get(player), ChatFormatting.LIGHT_PURPLE)), false);
			slots[MAIN] = icon(Items.LAPIS_LAZULI, text("Buy 1 point (Aura tree)", ChatFormatting.LIGHT_PURPLE), List.of(
					text("Costs " + next + " Aura", ChatFormatting.GRAY)), false);
			int five = 0;
			for (int i = 0; i < 5; i++) five += AuraConfig.SKILL_POINT_BASE_COST + (data.skillPointsBought + i) * AuraConfig.SKILL_POINT_COST_STEP;
			slots[MAIN5] = icon(Items.LAPIS_BLOCK, text("Buy 5 points (Aura tree)", ChatFormatting.LIGHT_PURPLE), List.of(
					text("Costs " + five + " Aura", ChatFormatting.GRAY)), false);
			slots[PVP] = data.pvpUnlocked
					? icon(Items.REDSTONE, text("Buy 1 point (PvP tree)", ChatFormatting.RED), List.of(text("Costs " + next + " Aura", ChatFormatting.GRAY)), false)
					: icon(Items.GRAY_DYE, text("PvP tree locked", ChatFormatting.DARK_GRAY), List.of(text("Unlocks when you defeat a boss.", ChatFormatting.GRAY)), false);
			bar(slots, true);
		}

		@Override
		void click(ServerPlayer player, AuraPlayerData data, int slot) {
			if (barClick(player, slot)) return;
			switch (slot) {
				case MAIN -> run(player, "aura redeem");
				case MAIN5 -> run(player, "aura redeem 5");
				case PVP -> {
					if (data.pvpUnlocked) run(player, "aura redeem pvp");
				}
				default -> {
				}
			}
		}
	}

	/** Every way to earn Aura: the FTB quest book (when installed) first, then the built-in sources with this server's values. */
	public static final class EarnPage extends Page {
		static final int QUESTS = 20, MOBS = 22, WEEKLY = 24, ARENA = 29, WAVES = 31, COMBOS = 33, BOSSES = 40;

		public static boolean questsLoaded() {
			return com.toast.aurapoints.shim.fml.ModList.get() != null && com.toast.aurapoints.shim.fml.ModList.get().isLoaded("ftbquests");
		}

		/** FTB's own quest book item as the icon, so it looks like the thing it opens. */
		private static Item questBookItem() {
			return net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ftbquests", "book"))
					.orElse(Items.WRITABLE_BOOK);
		}

		@Override
		Component title(ServerPlayer player) {
			return Component.literal("Earn Aura");
		}

		@Override
		void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots) {
			slots[4] = icon(Items.EMERALD, text("Earn Aura", ChatFormatting.GOLD, ChatFormatting.BOLD), List.of(
					text("You have " + AuraCurrency.get(player) + " Aura.", ChatFormatting.LIGHT_PURPLE),
					text("Spend it on actives, rerolls,", ChatFormatting.GRAY),
					text("skill points and cosmetics.", ChatFormatting.GRAY)), false);
			if (questsLoaded()) {
				slots[QUESTS] = icon(questBookItem(), text("Quest book", ChatFormatting.GOLD, ChatFormatting.BOLD), List.of(
						text("The main way to earn Aura.", ChatFormatting.GRAY),
						text("Quests pay Aura when you claim them;", ChatFormatting.GRAY),
						text("harder quests pay more.", ChatFormatting.GRAY),
						text("Click: open the quest book", ChatFormatting.YELLOW)), true);
			}
			if (AuraConfig.TOUCHED_ENABLED && AuraConfig.TOUCHED_KILL_POINTS > 0) {
				slots[MOBS] = icon(Items.GLOW_INK_SAC, text("Aura-touched mobs", ChatFormatting.LIGHT_PURPLE), List.of(
						text("Glowing, named monsters with an aura.", ChatFormatting.GRAY),
						text("They hit harder and use abilities.", ChatFormatting.GRAY),
						text(AuraConfig.TOUCHED_KILL_POINTS + " Aura per kill", ChatFormatting.GREEN)), false);
			}
			if (AuraConfig.WEEKLY_ENABLED) {
				int lo = AuraConfig.WEEKLY_REWARD_BASE, hi = lo + 2 * AuraConfig.WEEKLY_REWARD_PER_TIER;
				slots[WEEKLY] = icon(Items.CLOCK, text("Weekly challenges", ChatFormatting.AQUA), List.of(
						text("3 new challenges every Monday.", ChatFormatting.GRAY),
						text(lo + "-" + hi + " Aura each, +" + AuraConfig.WEEKLY_ALL_BONUS + " for all three", ChatFormatting.GREEN),
						text(data.weeklyDone + " done this week", ChatFormatting.DARK_GRAY),
						text("Click: show them in chat", ChatFormatting.YELLOW)), false);
			}
			slots[ARENA] = icon(Items.IRON_SWORD, text("Arena", ChatFormatting.RED), List.of(
					text("Win a duel: " + AuraConfig.DUEL_PRIZE_AURA + " Aura + the wager pot", ChatFormatting.GREEN),
					text("Win a match: " + AuraConfig.MATCH_PRIZE_AURA + " Aura", ChatFormatting.GREEN),
					text("Click: open the arena menu", ChatFormatting.YELLOW)), false);
			slots[WAVES] = icon(Items.ZOMBIE_HEAD, text("Wave Survival", ChatFormatting.DARK_GREEN), List.of(
					text("Arena mode: fight waves of monsters.", ChatFormatting.GRAY),
					text(AuraConfig.WAVES_AURA_PER_WAVE + " Aura per wave, +" + AuraConfig.WAVES_BOSS_BONUS + " per boss wave", ChatFormatting.GREEN)), false);
			if (AuraConfig.COMBO_AURA_REWARD > 0) {
				slots[COMBOS] = icon(Items.BLAZE_POWDER, text("Team combos", ChatFormatting.GOLD), List.of(
						text("Mix your ability with a teammate's.", ChatFormatting.GRAY),
						text(AuraConfig.COMBO_AURA_REWARD + " Aura each when it hits", ChatFormatting.GREEN),
						text("(outside the arena, with a cooldown)", ChatFormatting.DARK_GRAY)), false);
			}
			List<Component> boss = new ArrayList<>();
			if (AuraConfig.BOSS_AURA_PER_KILL > 0) boss.add(text(AuraConfig.BOSS_AURA_PER_KILL + " Aura per boss", ChatFormatting.GREEN));
			boss.add(text("Your first boss unlocks the PvP skill tree.", ChatFormatting.GRAY));
			slots[BOSSES] = icon(Items.WITHER_SKELETON_SKULL, text("Bosses", ChatFormatting.DARK_PURPLE), boss, false);
			bar(slots, true);
		}

		@Override
		void click(ServerPlayer player, AuraPlayerData data, int slot) {
			if (barClick(player, slot)) return;
			switch (slot) {
				case QUESTS -> {
					if (questsLoaded()) {
						player.closeContainer();
						run(player, "ftbquests open_book");
					}
				}
				case WEEKLY -> {
					player.closeContainer();
					run(player, "aura weekly");
				}
				case ARENA -> {
					pageSound(player);
					open(player, new ArenaPage());
				}
				default -> {
				}
			}
		}
	}

	/** Dev tests: where the reaction icons sit. */
	public static int[] reactionSlots() {
		return ReactionsPage.SPOTS;
	}

	/** The Aura Arena: one click to its lobby, then the NPCs there explain the rest. */
	static final class ArenaPage extends Page {
		static final int LOBBY = 20, HALL = 22, RING = 24, MATCH = 29, SPECTATE = 31, LEAVE = 33;

		@Override
		Component title(ServerPlayer player) {
			return Component.literal("Arena");
		}

		@Override
		void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots) {
			boolean in = com.toast.aurapoints.arena.Arena.in(player);
			slots[4] = icon(Items.IRON_SWORD, text("The Aura Arena", ChatFormatting.RED, ChatFormatting.BOLD), List.of(
					text("Its own dimension. Your items and spot", ChatFormatting.GRAY),
					text("outside are safe; /aura leave brings you back.", ChatFormatting.GRAY),
					text(in ? "You are in the arena right now." : "You are not in the arena.", in ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)), false);
			slots[LOBBY] = icon(Items.ENDER_PEARL, text("Join the lobby", ChatFormatting.GREEN, ChatFormatting.BOLD), List.of(
					text("Start here. The NPCs in the lobby set up", ChatFormatting.GRAY),
					text("duels, team matches and Wave Survival.", ChatFormatting.GRAY),
					text("Click: go to the lobby", ChatFormatting.YELLOW)), true);
			slots[HALL] = icon(Items.TARGET, text("Training Hall", ChatFormatting.AQUA), List.of(
					text("Dummies, a 30 s damage test and the", ChatFormatting.GRAY),
					text("Spar Pit for free PvP.", ChatFormatting.GRAY),
					text("Click: go to the hall", ChatFormatting.YELLOW)), false);
			slots[RING] = icon(Items.SHIELD, text("The ring", ChatFormatting.GOLD), List.of(
					text("Where duels and matches are fought.", ChatFormatting.GRAY),
					text("Click: go to the ring's rim", ChatFormatting.YELLOW)), false);
			slots[MATCH] = icon(Items.WRITABLE_BOOK, text("Join an open match", ChatFormatting.LIGHT_PURPLE), List.of(
					text("If someone opened a match lobby,", ChatFormatting.GRAY),
					text("sign up from anywhere.", ChatFormatting.GRAY),
					text("Click: join", ChatFormatting.YELLOW)), false);
			slots[SPECTATE] = icon(Items.ENDER_EYE, text("Spectate", ChatFormatting.WHITE), List.of(
					text("Watch the running fight.", ChatFormatting.GRAY),
					text("Click: spectate", ChatFormatting.YELLOW)), false);
			slots[LEAVE] = icon(Items.OAK_DOOR, text("Leave", ChatFormatting.RED), List.of(
					text("Out of the arena or a match", ChatFormatting.GRAY),
					text("and back to where you were.", ChatFormatting.GRAY),
					text("Click: /aura leave", ChatFormatting.YELLOW)), false);
			bar(slots, true);
		}

		@Override
		void click(ServerPlayer player, AuraPlayerData data, int slot) {
			if (barClick(player, slot)) return;
			String command = switch (slot) {
				case LOBBY -> "aura arena lobby";
				case HALL -> "aura arena hall";
				case RING -> "aura arena ring";
				case MATCH -> "aura arena match join";
				case SPECTATE -> "aura arena spectate";
				case LEAVE -> "aura leave";
				default -> null;
			};
			if (command == null) return;
			player.closeContainer();
			run(player, command);
		}
	}

	/** Every elemental reaction: what sets it off and what it does. Your own aura's part is marked. */
	static final class ReactionsPage extends Page {
		static final int[] SPOTS = {19, 21, 23, 25, 28, 30, 32, 34};

		@Override
		Component title(ServerPlayer player) {
			return Component.literal("Reactions");
		}

		private static Item iconFor(com.toast.aurapoints.ability.Reactions.Reaction r) {
			return switch (r) {
				case FREEZE -> Items.PACKED_ICE;
				case CONDUCT -> Items.LIGHTNING_ROD;
				case FIRESTORM -> Items.FIRE_CHARGE;
				case TOXIC_CLOUD -> Items.FERMENTED_SPIDER_EYE;
				case STEAM -> Items.WATER_BUCKET;
				case MELT -> Items.MAGMA_CREAM;
				case OVERGROWTH -> Items.VINE;
				case STORM -> Items.WIND_CHARGE;
			};
		}

		@Override
		void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots) {
			var reactions = com.toast.aurapoints.ability.Reactions.Reaction.values();
			slots[4] = icon(Items.BLAZE_POWDER, text("Elemental reactions", ChatFormatting.GOLD, ChatFormatting.BOLD), List.of(
					text("Water, Fire, Frost, Verdant and Thunder hits", ChatFormatting.GRAY),
					text("leave a mark on the target for " + (AuraConfig.REACTION_MARK_TICKS / 20) + "s.", ChatFormatting.GRAY),
					text("Hit a marked enemy with the right element", ChatFormatting.GRAY),
					text("and the reaction goes off, using the mark up.", ChatFormatting.GRAY),
					text("Wind sets reactions off but leaves no mark.", ChatFormatting.GRAY),
					text("Enemies only; a reaction has a short cooldown", ChatFormatting.DARK_GRAY),
					text("per target.", ChatFormatting.DARK_GRAY)), false);
			AuraType mine = data.aura;
			for (int i = 0; i < reactions.length && i < SPOTS.length; i++) {
				var r = reactions[i];
				AuraType trigger = r.triggerAura();
				boolean yours = mine != null && mine == trigger;
				List<Component> lore = new ArrayList<>();
				lore.add(Component.literal("Needs: ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(r.markName()).withStyle(s -> s.withColor(r.markRgb()))));
				lore.add(Component.literal("Then hit with: ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(trigger.displayName).withStyle(trigger.color)));
				lore.add(text(r.effect(), ChatFormatting.WHITE));
				if (yours) lore.add(text("Your aura sets this off.", ChatFormatting.GREEN));
				if (!r.enabled() || !AuraConfig.REACTION_ENABLED) lore.add(text("Switched off on this server.", ChatFormatting.RED));
				slots[SPOTS[i]] = icon(iconFor(r), Component.literal(r.symbol() + " " + r.displayName).withStyle(s -> s.withColor(r.rgb()).withBold(true)), lore, yours);
			}
			if (mine != null) {
				String leaves = com.toast.aurapoints.ability.Reactions.markLeftBy(mine);
				slots[40] = icon(auraIcon(mine), auraName(mine), List.of(
						text(leaves != null ? "Your hits leave: " + leaves : "Your hits leave no mark,", leaves != null ? ChatFormatting.AQUA : ChatFormatting.GRAY),
						text(leaves != null ? "so allies' elements can react to them." : "but they set off reactions.", ChatFormatting.GRAY)), false);
			}
			bar(slots, true);
		}

		@Override
		void click(ServerPlayer player, AuraPlayerData data, int slot) {
			barClick(player, slot);
		}
	}

	static final class SettingsPage extends Page {
		static final int SELFHIDE = 20, BLOOM = 22, NIGHT = 24;

		@Override
		Component title(ServerPlayer player) {
			return Component.literal("Settings");
		}

		@Override
		void opened(ServerPlayer player, AuraPlayerData data) {
			Tutorial.visited(player, data, Tutorial.Step.SETTINGS);
		}

		private static ItemStack toggle(boolean on, String name, String what) {
			return icon(on ? Items.LIME_DYE : Items.GRAY_DYE, text(name + ": " + (on ? "ON" : "OFF"), on ? ChatFormatting.GREEN : ChatFormatting.GRAY),
					List.of(text(what, ChatFormatting.GRAY), text("Click to switch", ChatFormatting.YELLOW)), false);
		}

		@Override
		void fill(ServerPlayer player, AuraPlayerData data, ItemStack[] slots) {
			slots[4] = icon(Items.COMPARATOR, text("Settings", ChatFormatting.WHITE), List.of(
					text("Ability keys: Options > Controls > Aura Points", ChatFormatting.GRAY),
					text("HUD layout and effects: the mod's client config", ChatFormatting.DARK_GRAY)), false);
			slots[SELFHIDE] = toggle(data.selfHide, "Self-hide", "Shadow: hide your own body while cloaked");
			slots[BLOOM] = toggle(data.bloomOn, "Bloom", "Verdant: sneak to make plants grow");
			slots[NIGHT] = toggle(data.nightVisionOn, "Night Vision", "Shadow's Nightstalker effect");
			bar(slots, true);
		}

		@Override
		void click(ServerPlayer player, AuraPlayerData data, int slot) {
			if (barClick(player, slot)) return;
			switch (slot) {
				case SELFHIDE -> run(player, "aura selfhide");
				case BLOOM -> run(player, "aura bloom");
				case NIGHT -> run(player, "aura nightvision");
				default -> {
				}
			}
		}
	}
}
