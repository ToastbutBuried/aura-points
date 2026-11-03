package com.toast.aurapoints.editor;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraBalance;
import com.toast.aurapoints.config.AuraBalance.Entry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.event.server.ServerStoppedEvent;
import com.toast.aurapoints.shim.neoforge.event.tick.ServerTickEvent;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Balance Tome's editor: chest menus over every value in the aurapoints-*.json files
 * (sections -> ability groups -> values), plus an anvil for typing a value. Everything runs on
 * the server; the icons are plain items in a vanilla chest screen, so no client code is involved.
 *
 * Saving modes, per editor: IMMEDIATE writes and reloads on every click; ON_CLOSE collects the
 * changes and, when the tome is closed, shows them all for a final apply / discard. Each tome
 * session backs the files up once before its first write (config/aurapoints-backups).
 */
@EventBusSubscriber(modid = AuraPoints.MOD_ID)
public final class BalanceEditor {

	private BalanceEditor() {
	}

	public enum Mode {
		IMMEDIATE, ON_CLOSE
	}

	private static final int PER_PAGE = 45;
	private static final int HISTORY_KEPT = 200;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	/** One editor's open work: pending changes (ON_CLOSE) and the edits that made them, newest first. */
	private static final class Session {
		final LinkedHashMap<String, Object> pending = new LinkedHashMap<>();
		final Deque<Edit> edits = new ArrayDeque<>();
		boolean backedUp;
		/** Set while we swap menus ourselves, so the old menu's close doesn't count as closing the tome. */
		boolean navigating;
		boolean discardArmed;
		/** The editor picked "Immediately reload" with changes pending: switch once they're applied or discarded. */
		boolean immediateAfterReview;
	}

	/** An ON_CLOSE edit: the key and its pending value before it (null = none). */
	private record Edit(String key, Object before) {
	}

	/** An applied change, for Recent changes and undo. */
	private static final class Change {
		final String key;
		final Object from;
		final Object to;
		final String who;
		final UUID by;
		final LocalTime time = LocalTime.now();
		final boolean isUndo;
		boolean undone;

		Change(String key, Object from, Object to, ServerPlayer player, boolean isUndo) {
			this.key = key;
			this.from = from;
			this.to = to;
			this.who = player.getGameProfile().getName();
			this.by = player.getUUID();
			this.isUndo = isUndo;
		}
	}

	private static final Map<UUID, Session> SESSIONS = new HashMap<>();
	/** Applied changes, oldest first. */
	private static final List<Change> HISTORY = new ArrayList<>();
	/** Work for the next tick: a menu can't be opened from inside another menu's close. */
	private static final Map<UUID, Consumer<ServerPlayer>> LATER = new LinkedHashMap<>();

	private static Session session(ServerPlayer player) {
		return SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
	}

	private static Mode mode(ServerPlayer player) {
		return BalanceEditors.mode(player.getUUID());
	}

	// ---------------------------------------------------------------
	// Opening and closing
	// ---------------------------------------------------------------

	/** The tome (or /aura balance open): a fresh session on the main menu. */
	public static void openMain(ServerPlayer player) {
		if (!BalanceEditors.allowed(player)) {
			player.displayClientMessage(Component.literal("Only the owner and whitelisted players can use the Balance Tome.").withStyle(ChatFormatting.RED), true);
			return;
		}
		session(player).backedUp = false;
		open(player, new MainPage());
	}

	private static void open(ServerPlayer player, Page page) {
		Session s = session(player);
		s.navigating = true;
		try {
			player.openMenu(provider(page.title(player, s), (id, inv) -> new EditorMenu(id, inv, page, player, s)));
		} finally {
			s.navigating = false;
		}
	}

	private static void openInput(ServerPlayer player, Entry entry, Page back) {
		Session s = session(player);
		String start = format(s.pending.getOrDefault(entry.key(), entry.current()));
		s.navigating = true;
		try {
			player.openMenu(provider(Component.literal("Type a value: " + label(entry)), (id, inv) -> new InputMenu(id, inv, entry, back, start)));
		} finally {
			s.navigating = false;
		}
	}

	private interface Ctor {
		AbstractContainerMenu make(int id, Inventory inv);
	}

	/** Swapping menus without the client closing its screen first keeps the mouse where it is. */
	private static MenuProvider provider(Component title, Ctor ctor) {
		return new MenuProvider() {
			@Override
			public Component getDisplayName() {
				return title;
			}

			@Override
			public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
				return ctor.make(id, inv);
			}

			// NeoForge's IMenuProviderExtension (the Fabric layer honours it too): switching pages keeps the cursor where it is
			public boolean shouldTriggerClientSideContainerClosingOnOpen() {
				return false;
			}
		};
	}

	private static void later(ServerPlayer player, Consumer<ServerPlayer> work) {
		LATER.put(player.getUUID(), work);
	}

	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		if (LATER.isEmpty()) return;
		var work = new LinkedHashMap<>(LATER);
		LATER.clear();
		for (var e : work.entrySet()) {
			ServerPlayer player = event.getServer().getPlayerList().getPlayer(e.getKey());
			if (player != null && !player.hasDisconnected() && BalanceEditors.allowed(player)) e.getValue().accept(player);
		}
	}

	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent event) {
		SESSIONS.clear();
		HISTORY.clear();
		LATER.clear();
	}

	// ---------------------------------------------------------------
	// Changing values
	// ---------------------------------------------------------------

	/** The value the editor sees: its pending one, else the one in force. */
	private static Object shown(Session s, Entry e) {
		return s.pending.getOrDefault(e.key(), e.current());
	}

	private static void set(ServerPlayer player, Session s, Entry e, Object value) {
		if (mode(player) == Mode.IMMEDIATE) {
			if (value.equals(e.current())) return;
			applyNow(player, s, Map.of(e.key(), value), false, false);
			return;
		}
		Object before = s.pending.get(e.key());
		if (value.equals(before == null ? e.current() : before)) return;
		s.edits.push(new Edit(e.key(), before));
		if (value.equals(e.current())) s.pending.remove(e.key());
		else s.pending.put(e.key(), value);
		player.displayClientMessage(Component.literal(label(e) + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(format(value)).withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(s.pending.containsKey(e.key()) ? "  (pending)" : "  (back to the live value)").withStyle(ChatFormatting.DARK_GRAY)), true);
	}

	/** Backs up once per session, writes + reloads, records what changed. summary: report in chat (else action bar). */
	private static void applyNow(ServerPlayer player, Session s, Map<String, Object> changes, boolean isUndo, boolean summary) {
		if (!s.backedUp) {
			AuraBalance.backup();
			s.backedUp = true;
		}
		Map<String, Object> before = new LinkedHashMap<>();
		for (String key : changes.keySet()) {
			Entry e = AuraBalance.entry(key);
			if (e != null) before.put(key, e.current());
		}
		List<String> report = AuraBalance.apply(changes);
		List<Change> done = new ArrayList<>();
		for (var b : before.entrySet()) {
			Object now = AuraBalance.entry(b.getKey()).current();
			if (now.equals(b.getValue())) continue;
			Change c = new Change(b.getKey(), b.getValue(), now, player, isUndo);
			done.add(c);
			HISTORY.add(c);
			AuraPoints.LOGGER.info("[balance editor] {} set {}: {} -> {}", c.who, c.key, format(c.from), format(c.to));
		}
		while (HISTORY.size() > HISTORY_KEPT) HISTORY.remove(0);

		if (summary || done.size() != 1) {
			player.sendSystemMessage(Component.literal(done.isEmpty() ? "No values changed." : "Applied " + done.size() + (done.size() == 1 ? " change:" : " changes:"))
					.withStyle(done.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.GOLD));
			for (Change c : done) player.sendSystemMessage(changeLine(c));
		} else {
			Change c = done.get(0);
			player.displayClientMessage(Component.literal(label(AuraBalance.entry(c.key)) + ": ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(format(c.from) + " -> " + format(c.to)).withStyle(ChatFormatting.AQUA))
					.append(Component.literal("  applied").withStyle(ChatFormatting.GREEN)), true);
		}
		for (String line : report) {
			if (line.startsWith("ERROR") || (summary && line.startsWith("WARNING"))) {
				player.sendSystemMessage(Component.literal("  " + line).withStyle(line.startsWith("ERROR") ? ChatFormatting.RED : ChatFormatting.YELLOW));
			}
		}
	}

	private static Component changeLine(Change c) {
		return Component.literal("  " + c.key + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(format(c.from) + " -> " + format(c.to)).withStyle(ChatFormatting.AQUA));
	}

	private static void applyPending(ServerPlayer player, Session s) {
		Map<String, Object> changes = new LinkedHashMap<>(s.pending);
		s.pending.clear();
		s.edits.clear();
		if (!changes.isEmpty()) applyNow(player, s, changes, false, true);
	}

	/** The last pending edit, else the editor's last applied change (applied straight away). */
	private static void undo(ServerPlayer player, Session s) {
		if (!s.edits.isEmpty()) {
			Edit e = s.edits.pop();
			if (e.before() == null) s.pending.remove(e.key());
			else s.pending.put(e.key(), e.before());
			Entry entry = AuraBalance.entry(e.key());
			player.displayClientMessage(Component.literal("Undone: " + label(entry) + " back to " + format(shown(s, entry))).withStyle(ChatFormatting.YELLOW), true);
			return;
		}
		for (int i = HISTORY.size() - 1; i >= 0; i--) {
			Change c = HISTORY.get(i);
			if (!c.by.equals(player.getUUID()) || c.undone || c.isUndo) continue;
			c.undone = true;
			applyNow(player, s, Map.of(c.key, c.from), true, false);
			return;
		}
		player.displayClientMessage(Component.literal("Nothing to undo.").withStyle(ChatFormatting.GRAY), true);
	}

	// ---------------------------------------------------------------
	// Values: formatting, parsing, stepping
	// ---------------------------------------------------------------

	static String format(Object v) {
		if (v instanceof Double d) return plain(BigDecimal.valueOf(d));
		if (v instanceof Float f) return plain(new BigDecimal(Float.toString(f)));
		return String.valueOf(v);
	}

	private static String plain(BigDecimal d) {
		String s = d.stripTrailingZeros().toPlainString();
		return s.contains(".") ? s : s + ".0";
	}

	/** The typed text as a value of the entry's type; throws if it isn't one. */
	private static Object parse(Entry e, String text) {
		String t = text.trim();
		Class<?> type = e.type();
		if (type == String.class) return text;
		if (type == boolean.class) {
			return switch (t.toLowerCase(Locale.ROOT)) {
				case "true", "on", "yes", "1" -> true;
				case "false", "off", "no", "0" -> false;
				default -> throw new IllegalArgumentException("type true or false");
			};
		}
		t = t.replace(',', '.');
		if (type == int.class) return Integer.parseInt(t);
		if (type == long.class) return Long.parseLong(t);
		double d = Double.parseDouble(t);
		if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("not a number");
		return type == float.class ? (Object) (float) d : (Object) d;
	}

	/** dir +1 / -1; big = shift-click. */
	private static Object step(Entry e, Object value, int dir, boolean big) {
		Class<?> type = e.type();
		if (type == int.class || type == long.class) {
			boolean ticks = e.key().contains("TICKS");
			long step = big ? (ticks ? 20 : 10) : (ticks ? 5 : 1);
			long next = ((Number) value).longValue() + dir * step;
			return type == int.class ? (Object) (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, next)) : (Object) next;
		}
		double base = Math.abs(((Number) e.defaultValue()).doubleValue());
		if (base == 0) base = Math.abs(((Number) value).doubleValue());
		int exp = base == 0 ? -1 : Math.max(-4, (int) Math.floor(Math.log10(base)) - 1);
		BigDecimal step = BigDecimal.ONE.scaleByPowerOfTen(big ? exp + 1 : exp);
		BigDecimal next = new BigDecimal(format(value)).add(dir > 0 ? step : step.negate());
		return type == float.class ? (Object) next.floatValue() : (Object) next.doubleValue();
	}

	private static String stepText(Entry e, boolean big) {
		Object one = step(e, e.type() == int.class ? (Object) 0 : e.type() == long.class ? (Object) 0L
				: e.type() == float.class ? (Object) 0f : (Object) 0d, 1, big);
		return format(one);
	}

	// ---------------------------------------------------------------
	// Names and icons
	// ---------------------------------------------------------------

	private static String pretty(String words) {
		StringBuilder out = new StringBuilder();
		for (String w : words.split("_")) {
			if (w.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(w.charAt(0)).append(w.substring(1).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}

	private static String groupName(String group) {
		return AuraBalance.groupTitle(group);
	}

	static String label(Entry e) {
		if (e.isSwitch()) return pretty(e.ability().name()) + " enabled";
		String rest = e.key().startsWith(e.group()) ? e.key().substring(e.group().length()) : e.key();
		return pretty(rest.isEmpty() ? e.key() : rest);
	}

	private static AuraType aura(String section) {
		for (AuraType t : AuraType.values()) if (t.id().equals(section)) return t;
		return null;
	}

	private static String sectionName(String section) {
		AuraType t = aura(section);
		if (t != null) return t.displayName;
		return switch (section) {
			case "general" -> "General";
			case "handhelds" -> "Handheld Weapons";
			case "reactions" -> "Elemental Reactions";
			case "combos" -> "Team Combos";
			case "touched" -> "Aura-touched Mobs";
			case "weekly" -> "Weekly & Cosmetics";
			default -> pretty(section);
		};
	}

	private static Style sectionStyle(String section) {
		AuraType t = aura(section);
		return Style.EMPTY.withItalic(false).withColor(t != null ? TextColor.fromRgb(t.rgb) : TextColor.fromLegacyFormat(ChatFormatting.GOLD));
	}

	private static Item sectionIcon(String section) {
		return switch (section) {
			case "general" -> Items.NETHER_STAR;
			case "shadow" -> Items.ENDER_EYE;
			case "water" -> Items.HEART_OF_THE_SEA;
			case "fire" -> Items.BLAZE_POWDER;
			case "wind" -> Items.WIND_CHARGE;
			case "frost" -> Items.PACKED_ICE;
			case "verdant" -> Items.VINE;
			case "thunder" -> Items.LIGHTNING_ROD;
			case "resonance" -> Items.AMETHYST_SHARD;
			case "earth" -> Items.COBBLESTONE;
			case "light" -> Items.GLOWSTONE_DUST;
			case "blood" -> Items.REDSTONE;
			case "void" -> Items.CRYING_OBSIDIAN;
			case "handhelds" -> Items.TRIDENT;
			case "arena" -> Items.TARGET;
			case "ultimates" -> Items.DRAGON_BREATH;
			case "reactions" -> Items.BREWING_STAND;
			case "combos" -> Items.FIRE_CHARGE;
			case "touched" -> Items.ZOMBIE_HEAD;
			case "weekly" -> Items.CLOCK;
			default -> Items.BOOK;
		};
	}

	private static Item valueIcon(Entry e, Object value) {
		if (value instanceof Boolean b) return b ? Items.LIME_DYE : Items.GRAY_DYE;
		if (e.type() == String.class) return Items.NAME_TAG;
		String k = e.key();
		if (k.contains("COOLDOWN") || k.contains("TICKS") || k.contains("SECONDS")) return Items.CLOCK;
		if (k.contains("DAMAGE")) return Items.IRON_SWORD;
		if (k.contains("KNOCKBACK") || k.contains("LIFT") || k.contains("PULL")) return Items.PISTON;
		if (k.contains("RADIUS") || k.contains("RANGE") || k.contains("DISTANCE") || k.contains("SIZE")) return Items.SPYGLASS;
		if (k.contains("AMPLIFIER")) return Items.GLOWSTONE_DUST;
		if (k.contains("SPEED") || k.contains("VELOCITY")) return Items.SUGAR;
		if (k.contains("HEAL") || k.contains("HEALTH") || k.contains("_HP")) return Items.GLISTERING_MELON_SLICE;
		if (k.contains("COST") || k.contains("POINTS") || k.contains("PRICE") || k.contains("REWARD")) return Items.EMERALD;
		if (k.contains("MULTIPLIER") || k.contains("FRACTION") || k.contains("CHANCE") || k.contains("REDUCTION") || k.contains("BONUS")) return Items.COMPARATOR;
		return Items.PAPER;
	}

	private static ItemStack icon(Item item, Component name, List<Component> lore, boolean glint) {
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

	private static Component text(String s, ChatFormatting... style) {
		return Component.literal(s).withStyle(style);
	}

	private static Component pair(String name, String value, ChatFormatting color) {
		return Component.literal(name).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(color));
	}

	// ---------------------------------------------------------------
	// Menus
	// ---------------------------------------------------------------

	/** A chest menu whose every click goes to its page; nothing can be taken out. */
	private static final class EditorMenu extends ChestMenu {
		private final Page page;
		private final SimpleContainer box;
		private final ServerPlayer viewer;
		private final Session session;

		EditorMenu(int id, Inventory inv, Page page, ServerPlayer viewer, Session session) {
			this(id, inv, page, viewer, session, new SimpleContainer(page.rows() * 9));
		}

		private EditorMenu(int id, Inventory inv, Page page, ServerPlayer viewer, Session session, SimpleContainer box) {
			super(page.rows() == 3 ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6, id, inv, box, page.rows());
			this.page = page;
			this.box = box;
			this.viewer = viewer;
			this.session = session;
			fill();
		}

		private void fill() {
			ItemStack[] slots = new ItemStack[page.rows() * 9];
			java.util.Arrays.fill(slots, ItemStack.EMPTY);
			page.fill(viewer, session, slots);
			for (int i = 0; i < slots.length; i++) box.setItem(i, slots[i]);
		}

		@Override
		public void clicked(int slot, int button, ClickType type, Player player) {
			if (player == viewer && slot >= 0 && slot < page.rows() * 9 && type != ClickType.PICKUP_ALL && type != ClickType.QUICK_CRAFT) {
				if (!BalanceEditors.allowed(viewer)) {
					viewer.closeContainer();
					return;
				}
				page.click(viewer, session, slot, button, type);
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
			if (player == viewer && !session.navigating) page.closed(viewer, session);
		}
	}

	/** The anvil text box: the paper's name is the value; take the result to set it. */
	private static final class InputMenu extends AnvilMenu {
		private final Entry entry;
		private final Page back;
		private String typed;

		InputMenu(int id, Inventory inv, Entry entry, Page back, String start) {
			super(id, inv, ContainerLevelAccess.NULL);
			this.entry = entry;
			this.back = back;
			this.typed = start;
			inputSlots.setItem(0, icon(Items.PAPER, Component.literal(start), List.of(), false));
		}

		@Override
		public void createResult() {
			if (entry == null) return;
			ItemStack result;
			try {
				Object value = parse(entry, typed);
				result = icon(Items.PAPER, text(format(value), ChatFormatting.GREEN),
						List.of(text("Click to set " + entry.key(), ChatFormatting.GRAY)), true);
			} catch (Exception ex) {
				result = icon(Items.BARRIER, text("Not a valid " + typeName(entry), ChatFormatting.RED),
						List.of(text(entry.key() + " needs " + typeHint(entry), ChatFormatting.GRAY)), false);
			}
			resultSlots.setItem(0, result);
		}

		@Override
		public boolean setItemName(String name) {
			typed = name == null ? "" : name.length() > 50 ? name.substring(0, 50) : name;
			createResult();
			if (player instanceof ServerPlayer sp) {
				// the client works out its own anvil result (and a cost of 1) as you type; put ours back over it
				sp.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), 2, getSlot(2).getItem().copy()));
				sp.connection.send(new ClientboundContainerSetDataPacket(containerId, 0, 0));
			}
			return true;
		}

		@Override
		protected boolean mayPickup(Player player, boolean hasStack) {
			return false;
		}

		@Override
		public void clicked(int slot, int button, ClickType type, Player player) {
			if (!(player instanceof ServerPlayer sp)) return;
			if (slot == 2) {
				Object value;
				try {
					value = parse(entry, typed);
				} catch (Exception ex) {
					sp.displayClientMessage(text("\"" + typed + "\" is not a valid " + typeName(entry) + ".", ChatFormatting.RED), true);
					return;
				}
				Session s = session(sp);
				set(sp, s, entry, value);
				open(sp, back);
				return;
			}
			setCarried(ItemStack.EMPTY);
			sendAllDataToRemote();
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			return ItemStack.EMPTY;
		}

		@Override
		public void removed(Player player) {
			inputSlots.clearContent();
			resultSlots.clearContent();
			setCarried(ItemStack.EMPTY);
			super.removed(player);
			// Escape in the anvil = cancel, back to the values
			if (player instanceof ServerPlayer sp && !session(sp).navigating) later(sp, p -> open(p, back));
		}
	}

	private static String typeName(Entry e) {
		Class<?> t = e.type();
		return t == int.class || t == long.class ? "whole number" : t == boolean.class ? "true / false" : t == String.class ? "text" : "number";
	}

	private static String typeHint(Entry e) {
		Class<?> t = e.type();
		return t == int.class || t == long.class ? "a whole number like 40" : t == boolean.class ? "true or false" : t == String.class ? "any text" : "a number like 2.5";
	}

	// ---------------------------------------------------------------
	// Pages
	// ---------------------------------------------------------------

	private abstract static class Page {
		int page;

		abstract Component title(ServerPlayer player, Session s);

		int rows() {
			return 6;
		}

		abstract void fill(ServerPlayer player, Session s, ItemStack[] slots);

		abstract void click(ServerPlayer player, Session s, int slot, int button, ClickType type);

		/** Closing the tome: with changes waiting in ON_CLOSE mode, show them for confirmation. */
		void closed(ServerPlayer player, Session s) {
			if (mode(player) == Mode.ON_CLOSE && !s.pending.isEmpty()) later(player, p -> open(p, new ReviewPage(true, new MainPage())));
		}

		Page back() {
			return new MainPage();
		}

		// the bottom row: 45 back, 47 undo, 48/50 pages, 49 page number, 51 saving, 53 review
		void bar(ServerPlayer player, Session s, ItemStack[] slots, int total) {
			ItemStack pane = icon(Items.BLACK_STAINED_GLASS_PANE, Component.literal(" "), List.of(), false);
			for (int i = 45; i < 54; i++) slots[i] = pane;
			slots[45] = icon(Items.OAK_DOOR, text("Back", ChatFormatting.WHITE), List.of(), false);
			slots[47] = undoIcon(s);
			int pages = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
			if (page > 0) slots[48] = icon(Items.ARROW, text("Previous page", ChatFormatting.WHITE), List.of(), false);
			if (pages > 1) slots[49] = icon(Items.PAPER, text("Page " + (page + 1) + " / " + pages, ChatFormatting.WHITE), List.of(), false);
			if (page < pages - 1) slots[50] = icon(Items.SPECTRAL_ARROW, text("Next page", ChatFormatting.WHITE), List.of(), false);
			slots[51] = savingIcon(player);
			slots[53] = reviewIcon(player, s);
		}

		/** True if the click was on the bar. */
		boolean barClick(ServerPlayer player, Session s, int slot, int total) {
			if (slot < 45) return false;
			int pages = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
			switch (slot) {
				case 45 -> open(player, back());
				case 47 -> undo(player, s);
				case 48 -> page = Math.max(0, page - 1);
				case 50 -> page = Math.min(pages - 1, page + 1);
				case 51 -> open(player, new SavingPage(this));
				case 53 -> {
					if (!s.pending.isEmpty()) open(player, new ReviewPage(false, this));
				}
				default -> {
				}
			}
			return true;
		}
	}

	private static ItemStack undoIcon(Session s) {
		return icon(Items.RECOVERY_COMPASS, text("Undo", ChatFormatting.YELLOW), List.of(
				text(s.edits.isEmpty() ? "Reverts your last applied change" : "Takes back your last pending change", ChatFormatting.GRAY),
				text(s.edits.isEmpty() ? "(in force at once)." : "(" + s.pending.size() + " pending).", ChatFormatting.GRAY)), false);
	}

	private static ItemStack savingIcon(ServerPlayer player) {
		boolean now = mode(player) == Mode.IMMEDIATE;
		return icon(Items.COMPARATOR, text("Saving", ChatFormatting.GOLD), List.of(
				pair("Mode: ", now ? "Immediately reload" : "Reload after closing", ChatFormatting.AQUA),
				text("Click to change.", ChatFormatting.DARK_GRAY)), false);
	}

	private static ItemStack reviewIcon(ServerPlayer player, Session s) {
		if (s.pending.isEmpty()) {
			return icon(Items.GRAY_CONCRETE, text("Nothing pending", ChatFormatting.GRAY), List.of(
					text(mode(player) == Mode.IMMEDIATE ? "Changes apply as you click." : "Changes wait here until you close the tome.", ChatFormatting.DARK_GRAY)), false);
		}
		return icon(Items.LIME_CONCRETE, text("Review & apply (" + s.pending.size() + ")", ChatFormatting.GREEN), List.of(
				text("See every pending change, then", ChatFormatting.GRAY), text("apply or discard them.", ChatFormatting.GRAY)), true);
	}

	private static <T> List<T> pageOf(List<T> all, int page) {
		int from = Math.min(all.size(), page * PER_PAGE);
		return all.subList(from, Math.min(all.size(), from + PER_PAGE));
	}

	/** Everything shown on a section's page (its file's values, plus the Ultimates other files keep, on the Ultimates page). */
	private static List<Entry> inSection(String section) {
		return AuraBalance.entries().stream().filter(e -> e.page().equals(section)).toList();
	}

	/** The aura's "rollable" switch, shown as a dye on its first page (null for a section that isn't an aura). */
	private static Entry rollable(String section) {
		return inSection(section).stream().filter(e -> e.group().equals(AuraBalance.ROLLABLE_GROUP)).findFirst().orElse(null);
	}

	private static final int ROLLABLE_SLOT = 46;

	private static List<Component> counts(Session s, List<Entry> entries) {
		int changed = 0;
		int pending = 0;
		for (Entry e : entries) {
			if (!shown(s, e).equals(e.defaultValue())) changed++;
			if (s.pending.containsKey(e.key())) pending++;
		}
		List<Component> lore = new ArrayList<>();
		lore.add(text(entries.size() + (entries.size() == 1 ? " value" : " values"), ChatFormatting.GRAY));
		if (changed > 0) lore.add(text(changed + " changed from default", ChatFormatting.GOLD));
		if (pending > 0) lore.add(text(pending + " pending", ChatFormatting.YELLOW));
		return lore;
	}

	/** Sections, recent changes, saving, review. */
	private static final class MainPage extends Page {
		private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

		private List<String> sections() {
			List<String> out = new ArrayList<>();
			for (String section : AuraBalance.sections()) if (!inSection(section).isEmpty()) out.add(section);
			return out;
		}

		@Override
		Component title(ServerPlayer player, Session s) {
			return Component.literal("Balance Editor");
		}

		@Override
		void fill(ServerPlayer player, Session s, ItemStack[] slots) {
			slots[4] = icon(Items.KNOWLEDGE_BOOK, text("Balance Editor", ChatFormatting.GOLD), List.of(
					text("Every value of the aurapoints-*.json files.", ChatFormatting.GRAY),
					text("Pick a section, then an ability.", ChatFormatting.GRAY),
					pair("Saving: ", mode(player) == Mode.IMMEDIATE ? "Immediately reload" : "Reload after closing", ChatFormatting.AQUA)), false);
			List<String> sections = sections();
			for (int i = 0; i < sections.size() && i < SLOTS.length; i++) {
				String section = sections.get(i);
				List<Component> lore = new ArrayList<>(counts(s, inSection(section)));
				lore.add(text("aurapoints-" + section + ".json", ChatFormatting.DARK_GRAY));
				slots[SLOTS[i]] = icon(sectionIcon(section), Component.literal(sectionName(section)).withStyle(sectionStyle(section)), lore, false);
			}
			bar(player, s, slots, 0);
			slots[45] = icon(Items.BARRIER, text("Close", ChatFormatting.RED), List.of(), false);
			slots[49] = icon(Items.WRITABLE_BOOK, text("Recent changes", ChatFormatting.AQUA), List.of(
					text(HISTORY.size() + " applied since the server started.", ChatFormatting.GRAY)), false);
		}

		@Override
		void click(ServerPlayer player, Session s, int slot, int button, ClickType type) {
			if (slot == 45) {
				player.closeContainer();
				return;
			}
			if (slot == 49) {
				open(player, new HistoryPage());
				return;
			}
			if (barClick(player, s, slot, 0)) return;
			List<String> sections = sections();
			for (int i = 0; i < sections.size() && i < SLOTS.length; i++) {
				if (SLOTS[i] == slot) {
					open(player, new SectionPage(sections.get(i)));
					return;
				}
			}
		}
	}

	/** One section's ability groups. */
	private static final class SectionPage extends Page {
		private final String section;

		SectionPage(String section) {
			this.section = section;
		}

		/** The ability submenus (each ability's switch and values), in ability order; the rollable switch is a dye instead. */
		private List<String> groups() {
			LinkedHashSet<String> out = new LinkedHashSet<>();
			for (Entry e : inSection(section)) if (!e.group().equals(AuraBalance.ROLLABLE_GROUP)) out.add(e.group());
			return new ArrayList<>(out);
		}

		@Override
		Component title(ServerPlayer player, Session s) {
			return Component.literal("Balance: " + sectionName(section));
		}

		@Override
		void fill(ServerPlayer player, Session s, ItemStack[] slots) {
			List<String> groups = groups();
			List<String> shown = pageOf(groups, page);
			for (int i = 0; i < shown.size(); i++) {
				String group = shown.get(i);
				List<Entry> entries = inSection(section).stream().filter(e -> e.group().equals(group)).toList();
				Entry sw = entries.stream().filter(e -> e.isSwitch() && e.group().equals(e.ability().name() + "_")).findFirst().orElse(null);
				boolean off = sw != null && !(Boolean) shown(s, sw);
				MutableComponent name = Component.literal(groupName(group)).withStyle(sectionStyle(section));
				if (off) name.append(text("  (off)", ChatFormatting.RED));
				slots[i] = icon(off ? Items.GRAY_DYE : sectionIcon(section), name, counts(s, entries),
						entries.stream().anyMatch(e -> s.pending.containsKey(e.key())));
			}
			bar(player, s, slots, groups.size());
			Entry roll = rollable(section);
			if (roll != null) {
				boolean on = (Boolean) shown(s, roll);
				slots[ROLLABLE_SLOT] = icon(on ? Items.LIME_DYE : Items.GRAY_DYE,
						text(on ? "Rollable" : "Not rollable", on ? ChatFormatting.GREEN : ChatFormatting.GRAY), List.of(
								text(on ? "/aura roll can land on " + sectionName(section) + "." : "/aura roll never gives " + sectionName(section) + ".", ChatFormatting.GRAY),
								text("(/aura setaura works either way)", ChatFormatting.DARK_GRAY),
								text("Click to switch.", ChatFormatting.DARK_GRAY)), s.pending.containsKey(roll.key()));
			}
		}

		@Override
		void click(ServerPlayer player, Session s, int slot, int button, ClickType type) {
			List<String> groups = groups();
			Entry roll = rollable(section);
			if (slot == ROLLABLE_SLOT && roll != null) {
				set(player, s, roll, !(Boolean) shown(s, roll));
				return;
			}
			if (barClick(player, s, slot, groups.size())) return;
			List<String> shown = pageOf(groups, page);
			if (slot < shown.size()) open(player, new GroupPage(section, shown.get(slot), this));
		}
	}

	/** One ability's values: click to change. */
	private static final class GroupPage extends Page {
		private final String section;
		private final String group;
		private final SectionPage parent;

		GroupPage(String section, String group, SectionPage parent) {
			this.section = section;
			this.group = group;
			this.parent = parent;
		}

		private List<Entry> entries() {
			return inSection(section).stream().filter(e -> e.group().equals(group)).toList();
		}

		@Override
		Component title(ServerPlayer player, Session s) {
			return Component.literal(sectionName(section) + " > " + groupName(group));
		}

		@Override
		Page back() {
			return parent;
		}

		@Override
		void fill(ServerPlayer player, Session s, ItemStack[] slots) {
			List<Entry> entries = entries();
			List<Entry> shown = pageOf(entries, page);
			for (int i = 0; i < shown.size(); i++) slots[i] = valueItem(s, shown.get(i));
			bar(player, s, slots, entries.size());
		}

		private ItemStack valueItem(Session s, Entry e) {
			Object live = e.current();
			Object value = shown(s, e);
			boolean pending = s.pending.containsKey(e.key());
			List<Component> lore = new ArrayList<>();
			lore.add(text(e.key(), ChatFormatting.DARK_GRAY));
			if (pending) {
				lore.add(pair("Value: ", format(value), ChatFormatting.YELLOW).copy().append(text("  pending", ChatFormatting.YELLOW)));
				lore.add(pair("Live now: ", format(live), ChatFormatting.WHITE));
			} else {
				lore.add(pair("Value: ", format(value), ChatFormatting.AQUA));
			}
			if ((e.type() == int.class || e.type() == long.class) && e.key().contains("TICKS")) {
				lore.add(text("= " + format(((Number) value).longValue() / 20.0) + " s", ChatFormatting.DARK_AQUA));
			}
			lore.add(pair("Default: ", format(e.defaultValue()), ChatFormatting.WHITE));
			if (!value.equals(e.defaultValue())) lore.add(text("Changed from default", ChatFormatting.GOLD));
			lore.add(Component.empty());
			if (e.type() == boolean.class) {
				lore.add(text("Click: switch", ChatFormatting.GRAY));
			} else if (e.type() == String.class) {
				lore.add(text("Click or Q: type a value", ChatFormatting.GRAY));
			} else {
				lore.add(text("Left / right click: +" + stepText(e, false) + " / -" + stepText(e, false), ChatFormatting.GRAY));
				lore.add(text("Shift + click: +" + stepText(e, true) + " / -" + stepText(e, true), ChatFormatting.GRAY));
				lore.add(text("Q: type a value", ChatFormatting.GRAY));
			}
			lore.add(text("Middle click: reset to default", ChatFormatting.GRAY));
			MutableComponent name = Component.literal(label(e)).withStyle(pending ? Style.EMPTY.withColor(ChatFormatting.YELLOW) : sectionStyle(section));
			return icon(valueIcon(e, value), name, lore, !value.equals(e.defaultValue()));
		}

		@Override
		void click(ServerPlayer player, Session s, int slot, int button, ClickType type) {
			List<Entry> entries = entries();
			if (barClick(player, s, slot, entries.size())) return;
			List<Entry> shown = pageOf(entries, page);
			if (slot >= shown.size()) return;
			Entry e = shown.get(slot);
			Object value = shown(s, e);
			player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 0.4f, 1.2f);
			if (type == ClickType.CLONE) {
				set(player, s, e, e.defaultValue());
				return;
			}
			if (e.type() == boolean.class) {
				if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) set(player, s, e, !(Boolean) value);
				return;
			}
			if (type == ClickType.THROW || e.type() == String.class) {
				if (type == ClickType.THROW || type == ClickType.PICKUP) openInput(player, e, this);
				return;
			}
			if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) {
				set(player, s, e, step(e, value, button == 1 ? -1 : 1, type == ClickType.QUICK_MOVE));
			}
		}
	}

	/** The two saving modes. */
	private static final class SavingPage extends Page {
		private final Page from;

		SavingPage(Page from) {
			this.from = from;
		}

		@Override
		int rows() {
			return 3;
		}

		@Override
		Page back() {
			return from;
		}

		@Override
		Component title(ServerPlayer player, Session s) {
			return Component.literal("Saving");
		}

		@Override
		void fill(ServerPlayer player, Session s, ItemStack[] slots) {
			boolean now = mode(player) == Mode.IMMEDIATE;
			slots[4] = icon(Items.COMPARATOR, text("Saving", ChatFormatting.GOLD), List.of(
					text("Before the first change of each tome session", ChatFormatting.GRAY),
					text("the files are backed up to config/aurapoints-backups", ChatFormatting.GRAY),
					text("(the newest 30 are kept).", ChatFormatting.GRAY)), false);
			slots[11] = icon(Items.REDSTONE_TORCH, text((now ? "> " : "") + "Immediately reload", now ? ChatFormatting.GREEN : ChatFormatting.WHITE), List.of(
					text("Every click is saved and in force at once.", ChatFormatting.GRAY),
					now ? text("Selected", ChatFormatting.GREEN) : text("Click to select.", ChatFormatting.DARK_GRAY)), now);
			slots[15] = icon(Items.CLOCK, text((now ? "" : "> ") + "Reload after closing", now ? ChatFormatting.WHITE : ChatFormatting.GREEN), List.of(
					text("Changes wait until you close the tome. Then", ChatFormatting.GRAY),
					text("you see all of them and confirm before they apply.", ChatFormatting.GRAY),
					now ? text("Click to select.", ChatFormatting.DARK_GRAY) : text("Selected", ChatFormatting.GREEN)), !now);
			slots[13] = reviewIcon(player, s);
			slots[22] = icon(Items.OAK_DOOR, text("Back", ChatFormatting.WHITE), List.of(), false);
		}

		@Override
		void click(ServerPlayer player, Session s, int slot, int button, ClickType type) {
			switch (slot) {
				case 11 -> {
					if (mode(player) == Mode.IMMEDIATE) return;
					if (s.pending.isEmpty()) {
						BalanceEditors.setMode(player.getUUID(), Mode.IMMEDIATE);
					} else {
						// the waiting changes get settled first
						s.immediateAfterReview = true;
						open(player, new ReviewPage(false, this));
					}
				}
				case 15 -> BalanceEditors.setMode(player.getUUID(), Mode.ON_CLOSE);
				case 13 -> {
					if (!s.pending.isEmpty()) open(player, new ReviewPage(false, this));
				}
				case 22 -> open(player, from);
				default -> {
				}
			}
		}
	}

	/** Every pending change, then apply or discard. Shown on closing the tome in ON_CLOSE mode. */
	private static final class ReviewPage extends Page {
		private final boolean onClose;
		private final Page from;

		ReviewPage(boolean onClose, Page from) {
			this.onClose = onClose;
			this.from = from;
		}

		@Override
		Component title(ServerPlayer player, Session s) {
			int n = s.pending.size();
			return Component.literal("Apply " + n + (n == 1 ? " change?" : " changes?"));
		}

		@Override
		Page back() {
			return from;
		}

		@Override
		void fill(ServerPlayer player, Session s, ItemStack[] slots) {
			List<String> keys = new ArrayList<>(s.pending.keySet());
			List<String> shown = pageOf(keys, page);
			for (int i = 0; i < shown.size(); i++) {
				Entry e = AuraBalance.entry(shown.get(i));
				Object to = s.pending.get(e.key());
				slots[i] = icon(valueIcon(e, to), Component.literal(sectionName(e.section()) + " > " + label(e)).withStyle(sectionStyle(e.section())), List.of(
						text(e.key(), ChatFormatting.DARK_GRAY),
						pair("Now: ", format(e.current()), ChatFormatting.WHITE),
						pair("New: ", format(to), ChatFormatting.YELLOW),
						Component.empty(),
						text("Click: drop this change", ChatFormatting.GRAY)), false);
			}
			bar(player, s, slots, keys.size());
			slots[45] = icon(Items.OAK_DOOR, text(onClose ? "Keep editing" : "Back", ChatFormatting.WHITE), List.of(
					text("The changes stay pending.", ChatFormatting.GRAY)), false);
			slots[47] = icon(Items.BLACK_STAINED_GLASS_PANE, Component.literal(" "), List.of(), false);
			slots[51] = icon(Items.BLACK_STAINED_GLASS_PANE, Component.literal(" "), List.of(), false);
			String then = s.immediateAfterReview ? "Then saving switches to Immediately reload." : "";
			slots[52] = icon(s.discardArmed ? Items.TNT : Items.RED_CONCRETE, text(s.discardArmed ? "Click again to discard " + keys.size() : "Discard all", ChatFormatting.RED),
					then.isEmpty() ? List.of(text("Throws every pending change away.", ChatFormatting.GRAY))
							: List.of(text("Throws every pending change away.", ChatFormatting.GRAY), text(then, ChatFormatting.DARK_AQUA)), false);
			slots[53] = keys.isEmpty()
					? icon(Items.GRAY_CONCRETE, text("Nothing to apply", ChatFormatting.GRAY), List.of(), false)
					: icon(Items.LIME_CONCRETE, text("Apply " + keys.size() + (keys.size() == 1 ? " change" : " changes"), ChatFormatting.GREEN),
					then.isEmpty() ? List.of(text("Saves the files and reloads them now.", ChatFormatting.GRAY))
							: List.of(text("Saves the files and reloads them now.", ChatFormatting.GRAY), text(then, ChatFormatting.DARK_AQUA)), true);
		}

		@Override
		void click(ServerPlayer player, Session s, int slot, int button, ClickType type) {
			List<String> keys = new ArrayList<>(s.pending.keySet());
			if (slot == 52) {
				if (!s.discardArmed) {
					s.discardArmed = true;
					return;
				}
				int n = s.pending.size();
				s.pending.clear();
				s.edits.clear();
				player.displayClientMessage(text("Discarded " + n + (n == 1 ? " change." : " changes."), ChatFormatting.YELLOW), true);
				finish(player, s);
				return;
			}
			s.discardArmed = false;
			if (slot == 53) {
				if (keys.isEmpty()) return;
				applyPending(player, s);
				player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.5f, 1.4f);
				finish(player, s);
				return;
			}
			if (slot == 45) {
				s.immediateAfterReview = false;
				open(player, onClose ? new MainPage() : from);
				return;
			}
			// no undo or saving buttons here (panes over them)
			if (slot == 47 || slot == 51 || barClick(player, s, slot, keys.size())) return;
			List<String> shown = pageOf(keys, page);
			if (slot < shown.size()) {
				s.pending.remove(shown.get(slot));
				s.edits.removeIf(e -> e.key().equals(shown.get(slot)));
				page = Math.min(page, Math.max(0, (s.pending.size() - 1) / PER_PAGE));
			}
		}

		private void finish(ServerPlayer player, Session s) {
			s.discardArmed = false;
			if (s.immediateAfterReview) {
				s.immediateAfterReview = false;
				BalanceEditors.setMode(player.getUUID(), Mode.IMMEDIATE);
			}
			if (onClose) player.closeContainer();
			else open(player, from);
		}

		@Override
		void closed(ServerPlayer player, Session s) {
			s.discardArmed = false;
			s.immediateAfterReview = false;
			int n = s.pending.size();
			if (n > 0) {
				player.sendSystemMessage(text(n + (n == 1 ? " change is" : " changes are") + " still pending - open the Balance Tome again to apply or discard them.", ChatFormatting.YELLOW));
			}
		}
	}

	/** Applied changes, newest first; click one to set it back. */
	private static final class HistoryPage extends Page {
		@Override
		Component title(ServerPlayer player, Session s) {
			return Component.literal("Recent changes");
		}

		private List<Change> changes() {
			List<Change> out = new ArrayList<>(HISTORY);
			java.util.Collections.reverse(out);
			return out;
		}

		@Override
		void fill(ServerPlayer player, Session s, ItemStack[] slots) {
			List<Change> changes = changes();
			List<Change> shown = pageOf(changes, page);
			for (int i = 0; i < shown.size(); i++) {
				Change c = shown.get(i);
				Entry e = AuraBalance.entry(c.key);
				List<Component> lore = new ArrayList<>();
				lore.add(text(c.key, ChatFormatting.DARK_GRAY));
				lore.add(pair("", format(c.from) + " -> " + format(c.to), ChatFormatting.AQUA));
				lore.add(text("by " + c.who + " at " + c.time.format(TIME) + (c.isUndo ? " (undo)" : ""), ChatFormatting.GRAY));
				if (c.undone) lore.add(text("undone", ChatFormatting.DARK_GRAY));
				lore.add(Component.empty());
				lore.add(text("Click: set back to " + format(c.from), ChatFormatting.GRAY));
				slots[i] = icon(valueIcon(e, c.to), Component.literal(sectionName(e.section()) + " > " + label(e)).withStyle(sectionStyle(e.section())), lore, false);
			}
			bar(player, s, slots, changes.size());
		}

		@Override
		void click(ServerPlayer player, Session s, int slot, int button, ClickType type) {
			List<Change> changes = changes();
			if (barClick(player, s, slot, changes.size())) return;
			List<Change> shown = pageOf(changes, page);
			if (slot < shown.size()) {
				Change c = shown.get(slot);
				set(player, s, AuraBalance.entry(c.key), c.from);
			}
		}
	}
}
