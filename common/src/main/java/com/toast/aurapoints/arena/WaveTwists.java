package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wave Survival twists (5.15.0): after every WAVES_TWIST_EVERY-th cleared wave the party votes for one of three
 * twists (chat buttons, or three items in the hotbar), and the winner stays for the rest of the run. Repeatable ones
 * stack (additive), the one-offs can be taken once. Match owns the break and calls open / resolve; Waves asks for the
 * numbers (mob bonuses, pay, cooldowns). Hotbar mode keeps the real hotbar in {@code AuraPlayerData.twistHotbar}
 * (saved) so a crash mid-vote loses nothing: {@link #restore} puts it back when the vote ends, they leave, or on login.
 */
public final class WaveTwists {

	private WaveTwists() {
	}

	public enum Twist {
		STRONGER("stronger", "Stronger monsters", true, true, Items.NETHERITE_SCRAP, ChatFormatting.RED),
		SWARM("swarm", "Swarm", true, true, Items.RABBIT_FOOT, ChatFormatting.DARK_RED),
		SWIFT("swift", "Swift monsters", true, true, Items.FEATHER, ChatFormatting.AQUA),
		BONUS_XP("bonus_xp", "Bonus XP", true, false, Items.EXPERIENCE_BOTTLE, ChatFormatting.GREEN),
		NO_COOLDOWNS("no_cooldowns", "No cooldowns", false, true, Items.CLOCK, ChatFormatting.LIGHT_PURPLE),
		HALF_PAY("half_pay", "Half time pay", false, false, Items.GOLD_NUGGET, ChatFormatting.GOLD);

		public final String id;
		public final String displayName;
		/** Can be taken again and stacks. */
		public final boolean repeatable;
		/** Makes the run harder: no records, and it pays extra (repeatable ones) or has a price (the others). */
		public final boolean hard;
		final Item icon;
		final ChatFormatting color;

		Twist(String id, String displayName, boolean repeatable, boolean hard, Item icon, ChatFormatting color) {
			this.id = id;
			this.displayName = displayName;
			this.repeatable = repeatable;
			this.hard = hard;
			this.icon = icon;
			this.color = color;
		}

		/** One line for the vote, from the live config. */
		String blurb(int stacks) {
			return switch (this) {
				case STRONGER -> "Mobs +" + pct(AuraConfig.WAVES_TWIST_STRONGER) + " health and damage. +" + AuraConfig.WAVES_TWIST_HARD_AURA + " Aura a wave.";
				case SWARM -> "+" + pct(AuraConfig.WAVES_TWIST_SWARM) + " more mobs per wave. +" + AuraConfig.WAVES_TWIST_HARD_AURA + " Aura a wave.";
				case SWIFT -> "Mobs +" + pct(AuraConfig.WAVES_TWIST_SWIFT) + " faster. +" + AuraConfig.WAVES_TWIST_HARD_AURA + " Aura a wave.";
				case BONUS_XP -> "+" + AuraConfig.WAVES_TWIST_XP_BONUS + " Mastery XP for every cleared wave.";
				case NO_COOLDOWNS -> "Your abilities have no cooldown. Mobs get +" + pct(AuraConfig.WAVES_TWIST_NO_COOLDOWN_HEALTH) + " health.";
				case HALF_PAY -> "Aura and XP are paid every 2nd wave only, doubled.";
			};
		}

		private static String pct(double v) {
			return Math.round(v * 100) + "%";
		}
	}

	private static final ResourceLocation TWIST_ID = ResourceLocation.fromNamespaceAndPath(com.toast.aurapoints.AuraPoints.MOD_ID, "wave_twist");
	private static final String ITEM_KEY = "aurapoints_twist";
	/** Hotbar slots (0-based) of the three vote items: the 2nd, 4th and 6th. */
	private static final int[] SLOTS = {1, 3, 5};

	/** A run's twists: what was taken, and the vote in progress. */
	public static final class State {
		final Map<Twist, Integer> stacks = new EnumMap<>(Twist.class);
		final List<Twist> offered = new ArrayList<>();
		final Map<UUID, Integer> votes = new HashMap<>();
		boolean open;
		boolean hotbar;

		int stacks(Twist t) {
			return stacks.getOrDefault(t, 0);
		}
	}

	// ---------------------------------------------------------------
	// The numbers Waves / Match ask for
	// ---------------------------------------------------------------

	public static boolean due(Waves.Run run) {
		int every = AuraConfig.WAVES_TWIST_EVERY;
		return AuraConfig.WAVES_TWIST_ENABLED && every > 0 && run.wave > 0 && run.wave % every == 0;
	}

	/** Any hard twist taken: the run sets no records. */
	public static boolean hardTaken(Waves.Run run) {
		if (run == null) return false;
		for (var e : run.twists.stacks.entrySet()) if (e.getKey().hard && e.getValue() > 0) return true;
		return false;
	}

	public static boolean noCooldowns(Waves.Run run) {
		return run != null && run.twists.stacks(Twist.NO_COOLDOWNS) > 0;
	}

	/** Mob count factor for a wave (1.25 = a quarter more). */
	static double swarm(Waves.Run run) {
		return 1.0 + run.twists.stacks(Twist.SWARM) * Math.max(0.0, AuraConfig.WAVES_TWIST_SWARM);
	}

	/** Pay for a cleared wave: {aura, xp}, from the base amounts. */
	static int[] pay(Waves.Run run, int aura, int xp) {
		State s = run.twists;
		int hard = s.stacks(Twist.STRONGER) + s.stacks(Twist.SWARM) + s.stacks(Twist.SWIFT);
		aura += hard * Math.max(0, AuraConfig.WAVES_TWIST_HARD_AURA);
		xp += s.stacks(Twist.BONUS_XP) * Math.max(0, AuraConfig.WAVES_TWIST_XP_BONUS);
		if (s.stacks(Twist.HALF_PAY) > 0) {
			boolean paid = run.wave % 2 == 0;
			aura = paid ? aura * 2 : 0;
			xp = paid ? xp * 2 : 0;
		}
		return new int[]{aura, xp};
	}

	/** Health, damage and speed for a freshly spawned wave mob. */
	static void applyToMob(Mob m, Waves.Run run) {
		State s = run.twists;
		if (s.stacks.isEmpty()) return;
		double hp = s.stacks(Twist.STRONGER) * Math.max(0.0, AuraConfig.WAVES_TWIST_STRONGER);
		double dmg = hp;
		if (s.stacks(Twist.NO_COOLDOWNS) > 0) hp += Math.max(0.0, AuraConfig.WAVES_TWIST_NO_COOLDOWN_HEALTH);
		double speed = s.stacks(Twist.SWIFT) * Math.max(0.0, AuraConfig.WAVES_TWIST_SWIFT);
		add(m, Attributes.MAX_HEALTH, hp);
		add(m, Attributes.ATTACK_DAMAGE, dmg);
		add(m, Attributes.MOVEMENT_SPEED, speed);
		if (hp > 0) m.setHealth(m.getMaxHealth());
	}

	private static void add(Mob m, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double amount) {
		if (amount <= 0) return;
		var a = m.getAttribute(attribute);
		if (a != null) a.addPermanentModifier(new AttributeModifier(TWIST_ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
	}

	/** "Stronger monsters x2, No cooldowns" for the chat. */
	public static String summary(Waves.Run run) {
		List<String> parts = new ArrayList<>();
		for (Twist t : Twist.values()) {
			int n = run.twists.stacks(t);
			if (n > 0) parts.add(t.displayName + (n > 1 ? " x" + n : ""));
		}
		return parts.isEmpty() ? "none" : String.join(", ", parts);
	}

	// ---------------------------------------------------------------
	// The vote
	// ---------------------------------------------------------------

	private static boolean available(State s, Twist t) {
		int n = s.stacks(t);
		if (!t.repeatable) return n == 0;
		int max = AuraConfig.WAVES_TWIST_MAX_STACKS;
		return max <= 0 || n < max;
	}

	/** Offers three twists to the players. False when there is nothing to vote on. */
	static boolean open(MinecraftServer server, Waves.Run run, List<ServerPlayer> players, int breakSeconds) {
		State s = run.twists;
		List<Twist> pool = new ArrayList<>();
		for (Twist t : Twist.values()) if (available(s, t)) pool.add(t);
		if (pool.size() < 2 || players.isEmpty()) return false;
		Collections.shuffle(pool, new java.util.Random(run.seed * 17L + run.wave));
		s.offered.clear();
		s.offered.addAll(pool.subList(0, Math.min(3, pool.size())));
		s.votes.clear();
		s.open = true;
		s.hotbar = "hotbar".equalsIgnoreCase(AuraConfig.WAVES_TWIST_VOTE_MODE);
		for (ServerPlayer p : players) {
			p.sendSystemMessage(Component.literal("✦ Twist vote - " + Math.max(1, AuraConfig.WAVES_TWIST_VOTE_SECONDS) + "s. Active: " + summary(run)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			for (int i = 0; i < s.offered.size(); i++) {
				Twist t = s.offered.get(i);
				int have = s.stacks(t);
				String where = s.hotbar ? "slot " + (SLOTS[i] + 1) : "";
				MutableComponent line = s.hotbar
						? Component.literal("  [" + (i + 1) + "] ").withStyle(ChatFormatting.YELLOW)
						: button("[" + (i + 1) + "] ", ChatFormatting.YELLOW, "/aura arena match twist " + (i + 1), "Vote for " + t.displayName);
				line.append(Component.literal(t.displayName + (have > 0 ? " (x" + (have + 1) + ")" : "")).withStyle(t.color, ChatFormatting.BOLD))
						.append(Component.literal(" - " + t.blurb(have) + (t.repeatable ? "" : " (once)") + (where.isEmpty() ? "" : "  <" + where + ">")).withStyle(ChatFormatting.GRAY));
				p.sendSystemMessage(line);
			}
			if (s.hotbar) {
				stash(p);
				give(p, s);
				p.sendSystemMessage(Component.literal("  Right-click the item of your pick (or /aura arena match twist N). Your hotbar comes back after.").withStyle(ChatFormatting.DARK_GRAY));
			}
			AuraFx.sound(p.serverLevel(), p, SoundEvents.NOTE_BLOCK_CHIME.value(), 1f, 1.2f);
		}
		return true;
	}

	/** /aura arena match twist N (and the hotbar items). */
	static int vote(ServerPlayer voter, State s, int n) {
		if (!s.open || n < 1 || n > s.offered.size()) {
			voter.sendSystemMessage(Component.literal("There's no twist " + n + " to vote for.").withStyle(ChatFormatting.RED));
			return 0;
		}
		s.votes.put(voter.getUUID(), n - 1);
		Twist t = s.offered.get(n - 1);
		voter.sendSystemMessage(Component.literal("You voted: " + t.displayName).withStyle(t.color));
		AuraFx.sound(voter.serverLevel(), voter, SoundEvents.UI_BUTTON_CLICK.value(), 0.8f, 1.4f);
		return 1;
	}

	static boolean allVoted(State s, List<ServerPlayer> players) {
		if (players.isEmpty()) return false;
		for (ServerPlayer p : players) if (!s.votes.containsKey(p.getUUID())) return false;
		return true;
	}

	static void bar(State s, List<ServerPlayer> players, int ticks) {
		int voted = 0;
		for (ServerPlayer p : players) if (s.votes.containsKey(p.getUUID())) voted++;
		Component bar = Component.literal("✦ Twist vote: " + Math.max(0, (ticks - 60 + 19) / 20) + "s  (" + voted + "/" + players.size() + " voted)").withStyle(ChatFormatting.GOLD);
		for (ServerPlayer p : players) p.displayClientMessage(bar, true);
	}

	/** Counts the votes (a tie or no votes = a random pick among the best), applies the winner, closes the vote. */
	static void resolve(MinecraftServer server, Waves.Run run, List<ServerPlayer> players) {
		State s = run.twists;
		if (!s.open) return;
		s.open = false;
		int[] count = new int[s.offered.size()];
		for (int v : s.votes.values()) if (v >= 0 && v < count.length) count[v]++;
		int top = 0;
		for (int c : count) top = Math.max(top, c);
		List<Integer> best = new ArrayList<>();
		for (int i = 0; i < count.length; i++) if (count[i] == top) best.add(i);
		Twist won = s.offered.get(best.get(new java.util.Random().nextInt(best.size())));
		s.stacks.merge(won, 1, Integer::sum);
		int n = s.stacks(won);
		for (ServerPlayer p : players) {
			restore(p);
			ArenaFx.title(p, Component.literal(won.displayName + (n > 1 ? " x" + n : "")).withStyle(won.color, ChatFormatting.BOLD),
					Component.literal(won.blurb(n)).withStyle(ChatFormatting.GRAY));
			p.sendSystemMessage(Component.literal("✦ Twist: " + won.displayName + (n > 1 ? " x" + n : "") + "  (votes " + java.util.Arrays.toString(count) + "). Active: " + summary(run)).withStyle(won.color));
			AuraFx.sound(p.serverLevel(), p, SoundEvents.ENDER_DRAGON_GROWL, 0.35f, 1.4f);
		}
		s.votes.clear();
		s.offered.clear();
	}

	/** A player left the vote (their vote goes, their hotbar comes back). */
	static void dropped(Waves.Run run, ServerPlayer p) {
		if (run != null) run.twists.votes.remove(p.getUUID());
		restore(p);
	}

	// ---------------------------------------------------------------
	// Hotbar mode
	// ---------------------------------------------------------------

	/** Right-click with a vote item: votes, true when it was one. */
	public static boolean onUse(ServerPlayer player, ItemStack stack) {
		int idx = itemIndex(stack);
		if (idx < 0) return false;
		Waves.Run run = Match.waveRun();
		if (run != null && run.twists.open && Match.inWaves(player)) vote(player, run.twists, idx + 1);
		return true;
	}

	private static int itemIndex(ItemStack stack) {
		if (stack.isEmpty()) return -1;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return -1;
		CompoundTag tag = data.copyTag();
		return tag.contains(ITEM_KEY) ? tag.getInt(ITEM_KEY) : -1;
	}

	/** Puts the real hotbar away (once; the saved copy survives a crash). */
	private static void stash(ServerPlayer p) {
		AuraState state = AuraState.get(p.getServer());
		var data = state.getOrCreate(p.getUUID());
		if (data.twistHotbar != null) return;
		Inventory inv = p.getInventory();
		ListTag list = new ListTag();
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			if (s.save(p.registryAccess()) instanceof CompoundTag t) {
				t.putByte("Slot", (byte) i);
				list.add(t);
			}
			inv.setItem(i, ItemStack.EMPTY);
		}
		CompoundTag wrap = new CompoundTag();
		wrap.put("Items", list);
		data.twistHotbar = wrap;
		state.markDirty();
	}

	private static void give(ServerPlayer p, State s) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < s.offered.size() && i < SLOTS.length; i++) {
			Twist t = s.offered.get(i);
			int have = s.stacks(t);
			ItemStack item = new ItemStack(t.icon);
			CompoundTag tag = new CompoundTag();
			tag.putInt(ITEM_KEY, i);
			item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
			item.set(DataComponents.CUSTOM_NAME, Component.literal((i + 1) + ". " + t.displayName + (have > 0 ? " (x" + (have + 1) + ")" : "")).withStyle(t.color, ChatFormatting.BOLD)
					.withStyle(st -> st.withItalic(false)));
			item.set(DataComponents.LORE, new ItemLore(List.of(
					Component.literal(t.blurb(have)).withStyle(st -> st.withColor(ChatFormatting.GRAY).withItalic(false)),
					Component.literal("Right-click to vote").withStyle(st -> st.withColor(ChatFormatting.YELLOW).withItalic(false)))));
			inv.setItem(SLOTS[i], item);
		}
		inv.selected = SLOTS[0];
	}

	/** The vote items go, the real hotbar comes back. Safe to call any time (nothing stashed = nothing happens). */
	public static void restore(ServerPlayer p) {
		if (p.getServer() == null) return;
		AuraState state = AuraState.get(p.getServer());
		var data = state.getOrCreate(p.getUUID());
		if (data.twistHotbar == null) return;
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (itemIndex(inv.getItem(i)) >= 0) inv.setItem(i, ItemStack.EMPTY);
		if (itemIndex(p.containerMenu.getCarried()) >= 0) p.containerMenu.setCarried(ItemStack.EMPTY);
		ListTag list = data.twistHotbar.getList("Items", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag t = list.getCompound(i);
			ItemStack s = ItemStack.parse(p.registryAccess(), t).orElse(ItemStack.EMPTY);
			if (s.isEmpty()) continue;
			int slot = t.getByte("Slot");
			// something else landed in that slot meanwhile (picked up): the old item goes to the inventory or the floor
			if (slot >= 0 && slot < 9 && inv.getItem(slot).isEmpty()) inv.setItem(slot, s);
			else if (!inv.add(s)) p.drop(s, false);
		}
		data.twistHotbar = null;
		state.markDirty();
		p.containerMenu.broadcastChanges();
	}

	private static MutableComponent button(String text, ChatFormatting color, String command, String hover) {
		return Component.literal(text).withStyle(st -> st.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
	}
}
