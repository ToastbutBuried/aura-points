package com.toast.aurapoints.ability;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.data.LoadoutPreset;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loadout presets (5.38.0): up to LOADOUT_MAX per aura, each with its own order of the abilities on the
 * Ability 1/2/3 keys and a tap/sneak swap per ability. The active preset is edited in place; switching
 * presets or editing is blocked in combat, in a duel or match and during the Aura Trial.
 * Everything that maps a key to an ability reads {@link #keySlots(AuraPlayerData)} (server) or
 * ClientAuraState.keySlots() (the order rides in the HUD packet).
 */
public final class Loadouts {

	private Loadouts() {
	}

	/** player -> server tick of their last preset switch (LOADOUT_SWAP_COOLDOWN_SECONDS). */
	private static final Map<UUID, Long> LAST_SWAP = new HashMap<>();

	// ---------------------------------------------------------------
	// Reading
	// ---------------------------------------------------------------

	public static LoadoutPreset active(AuraPlayerData data) {
		if (data.aura == null || !AuraConfig.LOADOUT_ENABLED) return null;
		List<LoadoutPreset> list = data.loadouts.get(data.aura);
		if (list == null || list.isEmpty()) return null;
		int i = data.activeLoadout.getOrDefault(data.aura, 0);
		return list.get(Math.max(0, Math.min(i, list.size() - 1)));
	}

	/** The held aura's abilities on Ability 1/2/3, in the active preset's order (the aura's own order if the preset doesn't fit the kit). */
	public static List<Ability> keySlots(AuraPlayerData data) {
		if (data.aura == null) return List.of();
		List<Ability> base = data.aura.keySlots(Branch.current(data));
		LoadoutPreset p = active(data);
		return p != null && fits(p.keys, base) ? p.keys : base;
	}

	/** The ability on key {@code slot} (0-based), or null. */
	public static Ability slot(AuraPlayerData data, int slot) {
		List<Ability> keys = keySlots(data);
		return slot >= 0 && slot < keys.size() ? keys.get(slot) : null;
	}

	/** True if the active preset swaps this ability's tap and sneak casts. */
	public static boolean swapped(AuraPlayerData data, Ability ability) {
		LoadoutPreset p = active(data);
		return p != null && p.castSwap.contains(ability) && CastMode.has(ability, CastMode.SNEAK);
	}

	/** "|BOULDER_TOSS,QUAKE,PILLAR_LIFT" for the HUD packet, or "" for the aura's own order. */
	public static String wire(AuraPlayerData data) {
		if (data.aura == null) return "";
		List<Ability> keys = keySlots(data);
		if (keys.equals(data.aura.keySlots(Branch.current(data)))) return "";
		List<String> names = new ArrayList<>();
		for (Ability a : keys) names.add(a.name());
		return "|" + String.join(",", names);
	}

	private static boolean fits(List<Ability> order, List<Ability> base) {
		return !order.isEmpty() && order.size() == base.size() && new HashSet<>(order).equals(new HashSet<>(base));
	}

	// ---------------------------------------------------------------
	// Changing
	// ---------------------------------------------------------------

	/** Why the player can't switch or edit now, or null. */
	public static String blocker(ServerPlayer player, AuraPlayerData data) {
		if (!AuraConfig.LOADOUT_ENABLED) return "Loadouts are switched off on this server.";
		if (data.aura == null) return "Roll an aura first.";
		if (CombatTag.inCombat(player, AuraConfig.LOADOUT_COMBAT_SECONDS)) {
			long left = AuraConfig.LOADOUT_COMBAT_SECONDS * 20L - CombatTag.ticksSince(player);
			return "You're in combat - wait " + (left + 19) / 20 + " s.";
		}
		if (com.toast.aurapoints.arena.Duel.isIn(player) || com.toast.aurapoints.arena.Match.isIn(player)) return "Not during a duel or match.";
		if (com.toast.aurapoints.arena.AuraTrial.running(player)) return "Not during the Aura Trial.";
		return null;
	}

	/** The presets of the held aura, made (one default preset) if there are none. */
	private static List<LoadoutPreset> presets(AuraPlayerData data) {
		List<LoadoutPreset> list = data.loadouts.computeIfAbsent(data.aura, k -> new ArrayList<>());
		if (list.isEmpty()) list.add(new LoadoutPreset("Loadout 1"));
		return list;
	}

	private static LoadoutPreset editable(AuraPlayerData data) {
		List<LoadoutPreset> list = presets(data);
		int i = Math.max(0, Math.min(data.activeLoadout.getOrDefault(data.aura, 0), list.size() - 1));
		LoadoutPreset p = list.get(i);
		List<Ability> base = data.aura.keySlots(Branch.current(data));
		if (!fits(p.keys, base)) {
			p.keys.clear();
			p.keys.addAll(base);
		}
		return p;
	}

	private static boolean check(ServerPlayer player, AuraPlayerData data) {
		String why = blocker(player, data);
		if (why == null) return true;
		player.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.RED));
		return false;
	}

	private static void changed(ServerPlayer player, AuraPlayerData data) {
		AuraState.get(player.getServer()).markDirty();
		NetworkHandler.syncAura(player, data);
	}

	/** Switches to preset {@code n} (1-based); a new one starts as a copy of the current. */
	public static boolean use(ServerPlayer player, int n) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (!check(player, data)) return false;
		if (n < 1 || n > AuraConfig.LOADOUT_MAX) {
			player.sendSystemMessage(Component.literal("Pick a loadout from 1 to " + AuraConfig.LOADOUT_MAX + ".").withStyle(ChatFormatting.RED));
			return false;
		}
		int current = data.activeLoadout.getOrDefault(data.aura, 0);
		if (current == n - 1 && data.loadouts.containsKey(data.aura)) {
			player.displayClientMessage(Component.literal("Loadout " + n + " is already on.").withStyle(ChatFormatting.GRAY), true);
			return true;
		}
		long now = player.getServer().getTickCount();
		long since = now - LAST_SWAP.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2);
		if (since < AuraConfig.LOADOUT_SWAP_COOLDOWN_SECONDS * 20L) {
			player.sendSystemMessage(Component.literal("You just switched - wait " + (AuraConfig.LOADOUT_SWAP_COOLDOWN_SECONDS * 20L - since + 19) / 20 + " s.").withStyle(ChatFormatting.RED));
			return false;
		}
		List<LoadoutPreset> list = presets(data);
		LoadoutPreset from = editable(data);
		while (list.size() < n) list.add(from.copy("Loadout " + (list.size() + 1)));
		data.activeLoadout.put(data.aura, n - 1);
		LAST_SWAP.put(player.getUUID(), now);
		// its relic set, if it has one (5.40)
		List<AuraType> set = new ArrayList<>();
		for (String id : list.get(n - 1).relics) {
			AuraType a = AuraType.byId(id);
			if (a != null) set.add(a);
		}
		if (!set.isEmpty()) Relics.applySet(player, set);
		changed(player, data);
		player.displayClientMessage(Component.literal("Loadout: " + list.get(n - 1).name + "  " + describeKeys(data)).withStyle(ChatFormatting.AQUA), true);
		AuraFx.sound(player.serverLevel(), player, net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_CHAIN.value(), 0.7f, 1.3f);
		return true;
	}

	/** Swaps the abilities on keys {@code a} and {@code b} (1-based) in the active preset. */
	public static boolean swapKeys(ServerPlayer player, int a, int b) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (!check(player, data)) return false;
		LoadoutPreset p = editable(data);
		if (a < 1 || b < 1 || a > p.keys.size() || b > p.keys.size() || a == b) {
			player.sendSystemMessage(Component.literal("Your aura has " + p.keys.size() + " ability key" + (p.keys.size() == 1 ? "" : "s") + ": pick two different ones.").withStyle(ChatFormatting.RED));
			return false;
		}
		java.util.Collections.swap(p.keys, a - 1, b - 1);
		changed(player, data);
		player.displayClientMessage(Component.literal("Keys: " + describeKeys(data)).withStyle(ChatFormatting.AQUA), true);
		return true;
	}

	/** Toggles the tap/sneak swap of the ability on key {@code key} (1-based). */
	public static boolean toggleCast(ServerPlayer player, int key) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (!check(player, data)) return false;
		LoadoutPreset p = editable(data);
		Ability a = key >= 1 && key <= p.keys.size() ? p.keys.get(key - 1) : null;
		String sneakName = a == null ? null : Casts.variantName(a, data.aura, CastMode.SNEAK);
		if (sneakName == null) {
			player.sendSystemMessage(Component.literal(a == null ? "No ability on that key." : a.displayName + " has no sneak cast to swap.").withStyle(ChatFormatting.RED));
			return false;
		}
		boolean on = !p.castSwap.remove(a);
		if (on) p.castSwap.add(a);
		changed(player, data);
		player.displayClientMessage(Component.literal(on ? a.displayName + ": tap = " + sneakName + ", sneak = the normal cast" : a.displayName + ": casts back to normal")
				.withStyle(ChatFormatting.AQUA), true);
		return true;
	}

	/** Remembers the relics worn now as the active preset's relic set (none worn = forget the set). */
	public static boolean saveRelics(ServerPlayer player) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (!check(player, data)) return false;
		LoadoutPreset p = editable(data);
		p.relics.clear();
		for (AuraType a : Relics.worn(player)) p.relics.add(a.id());
		AuraState.get(player.getServer()).markDirty();
		player.displayClientMessage(Component.literal(p.relics.isEmpty() ? "This loadout no longer changes your relics."
				: "This loadout puts on: " + String.join(", ", Relics.worn(player).stream().map(Relics::name).toList())).withStyle(ChatFormatting.AQUA), true);
		return true;
	}

	public static boolean rename(ServerPlayer player, String name) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (data.aura == null) return false;
		String clean = name.strip();
		if (clean.isEmpty() || clean.length() > 20) {
			player.sendSystemMessage(Component.literal("Names are 1 to 20 characters.").withStyle(ChatFormatting.RED));
			return false;
		}
		editable(data).name = clean;
		AuraState.get(player.getServer()).markDirty();
		player.displayClientMessage(Component.literal("Loadout renamed: " + clean).withStyle(ChatFormatting.AQUA), true);
		return true;
	}

	/** Puts the active preset back to the aura's own keys and casts. */
	public static boolean reset(ServerPlayer player) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (!check(player, data)) return false;
		LoadoutPreset p = editable(data);
		p.keys.clear();
		p.keys.addAll(data.aura.keySlots(Branch.current(data)));
		p.castSwap.clear();
		changed(player, data);
		player.displayClientMessage(Component.literal("Loadout reset: " + describeKeys(data)).withStyle(ChatFormatting.AQUA), true);
		return true;
	}

	/** "1 Boulder Toss · 2 Quake · 3 Pillar Lift" */
	public static String describeKeys(AuraPlayerData data) {
		List<Ability> keys = keySlots(data);
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < keys.size(); i++) parts.add((i + 1) + " " + keys.get(i).displayName + (swapped(data, keys.get(i)) ? "*" : ""));
		return String.join(" · ", parts);
	}

	// ---------------------------------------------------------------
	// /aura loadout
	// ---------------------------------------------------------------

	/** The presets in chat, with buttons. */
	public static void show(ServerPlayer player) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (data.aura == null) {
			player.sendSystemMessage(Component.literal("Roll an aura first.").withStyle(ChatFormatting.RED));
			return;
		}
		List<LoadoutPreset> list = data.loadouts.getOrDefault(data.aura, List.of());
		int active = list.isEmpty() ? 0 : data.activeLoadout.getOrDefault(data.aura, 0);
		player.sendSystemMessage(Component.literal("Loadouts · " + data.aura.displayName).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
				.append(Component.literal("  (* = tap and sneak casts swapped)").withStyle(ChatFormatting.DARK_GRAY)));
		for (int i = 0; i < AuraConfig.LOADOUT_MAX; i++) {
			boolean on = i == active;
			String name = i < list.size() ? list.get(i).name : "Loadout " + (i + 1) + (i == 0 ? "" : " (empty)");
			MutableComponent line = Component.literal(on ? " ▶ " : "   ").withStyle(ChatFormatting.GREEN)
					.append(Component.literal(name).withStyle(on ? ChatFormatting.WHITE : ChatFormatting.GRAY));
			if (!on) line.append(button("  [Use]", "/aura loadout use " + (i + 1), "Switch to this loadout"));
			player.sendSystemMessage(line);
		}
		player.sendSystemMessage(Component.literal("   Keys now: " + describeKeys(data)).withStyle(ChatFormatting.GRAY));
		MutableComponent edit = Component.literal("   ");
		int n = data.aura.keySlots(Branch.current(data)).size();
		if (n >= 2) edit.append(button("[Swap keys 1+2]", "/aura loadout swap 1 2", "Swap the abilities on keys 1 and 2"));
		if (n >= 3) edit.append(button(" [2+3]", "/aura loadout swap 2 3", "Swap the abilities on keys 2 and 3"));
		edit.append(button(" [Reset]", "/aura loadout reset", "Back to the aura's own keys and casts"));
		player.sendSystemMessage(edit);
		player.sendSystemMessage(Component.literal("   /aura loadout cast <key> swaps tap and sneak · /aura loadout relics remembers the relics you wear · /aura loadout name <name>").withStyle(ChatFormatting.DARK_GRAY));
	}

	private static Component button(String text, String command, String hover) {
		return Component.literal(text).withStyle(s -> s.withColor(ChatFormatting.YELLOW)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("aura").then(Commands.literal("loadout")
				.executes(ctx -> {
					show(ctx.getSource().getPlayerOrException());
					return 1;
				})
				.then(Commands.literal("use").then(Commands.argument("n", IntegerArgumentType.integer(1, 9))
						.executes(ctx -> use(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "n")) ? 1 : 0)))
				.then(Commands.literal("swap").then(Commands.argument("a", IntegerArgumentType.integer(1, 3)).then(Commands.argument("b", IntegerArgumentType.integer(1, 3))
						.executes(ctx -> swapKeys(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "a"), IntegerArgumentType.getInteger(ctx, "b")) ? 1 : 0))))
				.then(Commands.literal("cast").then(Commands.argument("key", IntegerArgumentType.integer(1, 3))
						.executes(ctx -> toggleCast(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "key")) ? 1 : 0)))
				.then(Commands.literal("name").then(Commands.argument("name", StringArgumentType.greedyString())
						.executes(ctx -> rename(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")) ? 1 : 0)))
				.then(Commands.literal("relics").executes(ctx -> saveRelics(ctx.getSource().getPlayerOrException()) ? 1 : 0))
				.then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource().getPlayerOrException()) ? 1 : 0))));
	}

	public static void forget(UUID id) {
		LAST_SWAP.remove(id);
	}
}
