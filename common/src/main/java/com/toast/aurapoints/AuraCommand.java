package com.toast.aurapoints;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraStats;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

/**
 * /aura                          - your aura, abilities and Aura balance
 * /aura info [aura]              - everything about your aura (or any aura): what each ability does
 * /aura roll                     - the roll menu (first roll free, then REROLL_COST)
 * /aura roll confirm             - roll (the menu's button runs this)
 * /aura shop (or /shop)          - buy the active abilities of your aura (passives are free)
 * /aura shop buy <ability>       - buy one (the shop's buttons run this)
 * /aura redeem [n]               - buy n skill points for the Aura tree
 * /aura redeem pvp [n]           - buy n skill points for the PvP tree (once unlocked)
 * /aura give <players> <n>       - admin: add Aura (testing / events)
 * /aura setaura <players> <aura> - admin: set an aura without a roll; the secret ones only the owner
 * /aura selfhide                 - toggle: hide yourself in third person while Shadow-cloaked
 * /aura cooldowns [on|off]       - admin: switch every ability cooldown off/on for everyone
 * /aura reload                   - admin: re-read config/aurapoints-*.json (all balance numbers)
 * /aura resetprogress <part> [players] - admin: reset one part (quests, aura, skills, mastery...); nuke = everything (confirm)
 * /aura ticket [title] [message]  - send a change request; /aura tickets for reviewers (see Tickets)
 */
public final class AuraCommand {

	private AuraCommand() {
	}

	private static final Random RANDOM = new Random();

	private static final SuggestionProvider<CommandSourceStack> SHOP_SUGGESTIONS = (context, builder) -> {
		ServerPlayer player = context.getSource().getPlayer();
		if (player != null) {
			AuraPlayerData data = AuraState.get(context.getSource().getServer()).getOrCreate(player.getUUID());
			if (data.aura != null) {
				for (AuraAbilities.Ability a : data.aura.abilities(com.toast.aurapoints.ability.Branch.current(data))) if (a.active && !data.owned.contains(a)) builder.suggest(a.name().toLowerCase(java.util.Locale.ROOT));
			}
		}
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> AURA_SUGGESTIONS = (context, builder) -> {
		for (AuraType type : AuraType.values()) builder.suggest(type.id());
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> COSMETIC_SUGGESTIONS = (context, builder) -> {
		for (var c : com.toast.aurapoints.cosmetic.Cosmetics.Cosmetic.values()) builder.suggest(c.id());
		return builder.buildFuture();
	};
	private static final SuggestionProvider<CommandSourceStack> KIND_SUGGESTIONS = (context, builder) -> {
		for (var k : com.toast.aurapoints.cosmetic.Cosmetics.Kind.values()) builder.suggest(k.id());
		return builder.buildFuture();
	};

	/** /aura cosmetics buy|equip <cosmetic>, unequip <kind>. */
	private static int cosmetic(CommandContext<CommandSourceStack> ctx, String action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!AuraConfig.COSMETICS_ENABLED) {
			ctx.getSource().sendFailure(Component.literal("Cosmetics are switched off on this server."));
			return 0;
		}
		String msg;
		if (action.equals("unequip")) {
			String id = StringArgumentType.getString(ctx, "kind");
			com.toast.aurapoints.cosmetic.Cosmetics.Kind kind = null;
			for (var k : com.toast.aurapoints.cosmetic.Cosmetics.Kind.values()) if (k.id().equalsIgnoreCase(id)) kind = k;
			msg = kind == null ? "!No cosmetic kind called " + id + "." : com.toast.aurapoints.cosmetic.Cosmetics.unequip(player, kind);
		} else {
			String id = StringArgumentType.getString(ctx, "cosmetic").toLowerCase(java.util.Locale.ROOT);
			var c = com.toast.aurapoints.cosmetic.Cosmetics.Cosmetic.byId(id);
			if (c == null) msg = "!No cosmetic called " + id + ".";
			else msg = action.equals("buy") ? com.toast.aurapoints.cosmetic.Cosmetics.buy(player, c) : com.toast.aurapoints.cosmetic.Cosmetics.equip(player, c);
		}
		if (msg.startsWith("!")) {
			ctx.getSource().sendFailure(Component.literal(msg.substring(1)));
			return 0;
		}
		player.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.LIGHT_PURPLE));
		return 1;
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("aura")
				.executes(AuraCommand::showStatus)
				.then(Commands.literal("info")
						.executes(ctx -> showInfo(ctx, null))
						.then(Commands.argument("aura", StringArgumentType.word())
								.suggests(AURA_SUGGESTIONS)
								.executes(ctx -> showInfo(ctx, StringArgumentType.getString(ctx, "aura")))))
				.then(Commands.literal("roll")
						.executes(AuraCommand::showRollMenu)
						.then(Commands.literal("confirm").executes(AuraCommand::roll)))
				.then(Commands.literal("redeem")
						.executes(ctx -> redeem(ctx, AuraConfig.PUFFISH_CATEGORY_MAIN, 1))
						.then(Commands.argument("amount", IntegerArgumentType.integer(1, 1000))
								.executes(ctx -> redeem(ctx, AuraConfig.PUFFISH_CATEGORY_MAIN,
										IntegerArgumentType.getInteger(ctx, "amount"))))
						.then(Commands.literal("pvp")
								.executes(ctx -> redeem(ctx, AuraConfig.PUFFISH_CATEGORY_PVP, 1))
								.then(Commands.argument("amount", IntegerArgumentType.integer(1, 1000))
										.executes(ctx -> redeem(ctx, AuraConfig.PUFFISH_CATEGORY_PVP,
												IntegerArgumentType.getInteger(ctx, "amount"))))))
				.then(Commands.literal("give")
						.requires(src -> src.hasPermission(2))
						.then(Commands.argument("targets", EntityArgument.players())
								.then(Commands.argument("amount", IntegerArgumentType.integer(1))
										.executes(AuraCommand::giveAura))))
				.then(Commands.literal("shop")
						.executes(AuraCommand::showShop)
						.then(Commands.literal("buy")
							.then(Commands.argument("ability", StringArgumentType.word())
								.suggests(SHOP_SUGGESTIONS)
								.executes(AuraCommand::buyAbility))))
				.then(Commands.literal("weekly").executes(ctx -> {
					com.toast.aurapoints.cosmetic.Weekly.show(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("cosmetics")
						.executes(ctx -> {
							com.toast.aurapoints.cosmetic.Cosmetics.showMenu(ctx.getSource().getPlayerOrException(), null);
							return 1;
						})
						.then(Commands.literal("buy").then(Commands.argument("cosmetic", StringArgumentType.word()).suggests(COSMETIC_SUGGESTIONS)
								.executes(ctx -> cosmetic(ctx, "buy"))))
						.then(Commands.literal("equip").then(Commands.argument("cosmetic", StringArgumentType.word()).suggests(COSMETIC_SUGGESTIONS)
								.executes(ctx -> cosmetic(ctx, "equip"))))
						.then(Commands.literal("unequip").then(Commands.argument("kind", StringArgumentType.word()).suggests(KIND_SUGGESTIONS)
								.executes(ctx -> cosmetic(ctx, "unequip")))))
				// the Aura Book: its menu without the item, and a new copy
				.then(Commands.literal("menu").executes(ctx -> {
					com.toast.aurapoints.menu.AuraBook.open(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("book").executes(AuraCommand::giveAuraBook))
				.then(Commands.literal("selfhide").executes(AuraCommand::toggleSelfHide))
				.then(Commands.literal("bloom").executes(AuraCommand::toggleBloom))
				.then(Commands.literal("branch")
						.executes(AuraCommand::showBranches)
						.then(Commands.argument("branch", StringArgumentType.word())
								.suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
										java.util.Arrays.stream(com.toast.aurapoints.ability.Branch.values()).map(com.toast.aurapoints.ability.Branch::id), b))
								.executes(AuraCommand::pickBranch)))
				.then(Commands.literal("nightvision").executes(AuraCommand::toggleNightVision))
				.then(Commands.literal("reload")
						.requires(src -> src.hasPermission(2))
						.executes(AuraCommand::reloadBalance))
				// player progress as editable files: export, edit, import (dry shows the changes first)
				.then(Commands.literal("progress")
						.requires(src -> src.hasPermission(2))
						.then(Commands.literal("export")
								.executes(ctx -> exportProgress(ctx, null))
								.then(Commands.argument("player", StringArgumentType.word())
										.suggests(PROGRESS_NAMES)
										.executes(ctx -> exportProgress(ctx, StringArgumentType.getString(ctx, "player")))))
						.then(Commands.literal("import")
								.executes(ctx -> importProgress(ctx, null, false))
								.then(Commands.literal("dry").executes(ctx -> importProgress(ctx, null, true)))
								.then(Commands.argument("player", StringArgumentType.word())
										.suggests(PROGRESS_NAMES)
										.executes(ctx -> importProgress(ctx, StringArgumentType.getString(ctx, "player"), false))
										.then(Commands.literal("dry")
												.executes(ctx -> importProgress(ctx, StringArgumentType.getString(ctx, "player"), true))))))
				// admin: an aura-touched zombie of that aura where you stand
				.then(Commands.literal("touched")
						.requires(src -> src.hasPermission(2))
						.then(Commands.argument("aura", StringArgumentType.word())
								.suggests(AURA_SUGGESTIONS)
								.executes(AuraCommand::spawnTouched)))
				.then(Commands.literal("cooldowns")
						.requires(src -> src.hasPermission(2))
						.executes(AuraCommand::showCooldowns)
						.then(Commands.literal("on").executes(ctx -> setCooldowns(ctx, true)))
						.then(Commands.literal("off").executes(ctx -> setCooldowns(ctx, false))))
				// the Balance Tome: owner + whitelist (checked inside); the whitelist itself is owner only
				.then(Commands.literal("balance")
						.executes(AuraCommand::giveBalanceTome)
						.then(Commands.literal("open")
								.executes(ctx -> {
									ServerPlayer player = ctx.getSource().getPlayerOrException();
									com.toast.aurapoints.editor.BalanceEditor.openMain(player);
									return 1;
								}))
						.then(Commands.literal("whitelist")
								.requires(AuraCommand::isOwner)
								.executes(AuraCommand::listBalanceWhitelist)
								.then(Commands.literal("add")
										.then(Commands.argument("player", StringArgumentType.word())
												.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
												.executes(ctx -> editBalanceWhitelist(ctx, StringArgumentType.getString(ctx, "player"), true))))
								.then(Commands.literal("remove")
										.then(Commands.argument("player", StringArgumentType.word())
												.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(com.toast.aurapoints.editor.BalanceEditors.whitelist(), b))
												.executes(ctx -> editBalanceWhitelist(ctx, StringArgumentType.getString(ctx, "player"), false))))))
				// the Frost Bear boss: /aura frostbear [0-3] spawns it where you look, /aura frostbear sigil gives the summon item, /aura bluebell its drop (owner only)
				.then(Commands.literal("frostbear")
						.requires(AuraCommand::isOwner)
						.executes(ctx -> spawnFrostBear(ctx, -1))
						.then(Commands.argument("variant", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, com.toast.aurapoints.geo.FrostBear.VARIANTS - 1))
								.executes(ctx -> spawnFrostBear(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "variant"))))
						.then(Commands.literal("sigil").executes(ctx -> giveItem(ctx, com.toast.aurapoints.AuraPoints.FROST_BEAR_SIGIL.get()))))
				.then(Commands.literal("bluebell")
						.requires(AuraCommand::isOwner)
						.executes(ctx -> giveItem(ctx, com.toast.aurapoints.AuraPoints.BLUE_BELL.get())))
				.then(Commands.literal("mjolnir")
						.requires(src -> isOwner(src.getPlayer()))
						.executes(AuraCommand::giveMjolnir)
						.then(Commands.argument("targets", EntityArgument.players())
								.executes(ctx -> giveMjolnirTo(ctx, EntityArgument.getPlayers(ctx, "targets")))))
				// who else may wield Mjölnir, the handhelds and the World Tree Bow (owner only; WORTHY_ENABLED is the switch)
				.then(Commands.literal("worthy")
						.requires(AuraCommand::isOwner)
						.executes(AuraCommand::listWorthy)
						.then(Commands.literal("add")
								.then(Commands.argument("player", StringArgumentType.word())
										.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
										.executes(ctx -> editWorthy(ctx, StringArgumentType.getString(ctx, "player"), true))))
						.then(Commands.literal("remove")
								.then(Commands.argument("player", StringArgumentType.word())
										.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(com.toast.aurapoints.config.Worthy.list(), b))
										.executes(ctx -> editWorthy(ctx, StringArgumentType.getString(ctx, "player"), false)))))
				.then(Commands.literal("handheld")
						.requires(AuraCommand::isOwner)
						.executes(ctx -> giveHandheld(ctx, null, null))
						.then(Commands.argument("aura", com.mojang.brigadier.arguments.StringArgumentType.word())
								.suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
										java.util.Arrays.stream(com.toast.aurapoints.ability.AuraType.values()).map(com.toast.aurapoints.ability.AuraType::id), b))
								.executes(ctx -> giveHandheld(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "aura"), null))
								.then(Commands.argument("targets", EntityArgument.players())
										.executes(ctx -> giveHandheld(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "aura"),
												EntityArgument.getPlayers(ctx, "targets"))))))
				.then(Commands.literal("lock")
						.requires(AuraCommand::isOwner)
						.executes(AuraCommand::showLocks)
						.then(Commands.literal("all").executes(ctx -> lockAll(ctx, true)))
						.then(Commands.argument("targets", EntityArgument.players())
								.executes(ctx -> lock(ctx, true))))
				.then(Commands.literal("unlock")
						.requires(AuraCommand::isOwner)
						.then(Commands.literal("all").executes(ctx -> lockAll(ctx, false)))
						.then(Commands.argument("targets", EntityArgument.players())
								.executes(ctx -> lock(ctx, false))))
				.then(resetProgressNode())
				// admin tool: give anyone any aura
				.then(Commands.literal("setaura")
						.requires(src -> src.hasPermission(2) || isOwner(src))
						.then(Commands.argument("targets", EntityArgument.players())
								.then(Commands.argument("aura", StringArgumentType.word())
										.suggests(AURA_SUGGESTIONS)
										.executes(AuraCommand::setAura)))));

		// The old shop is gone; point anyone with muscle memory at the new command.
		// /shop is a short alias for /aura shop
		dispatcher.register(Commands.literal("shop").executes(AuraCommand::showShop));
	}

	private static String names(AuraType aura, boolean active) {
		return names(aura, active, null);
	}

	/** With {@code data}: actives the player hasn't bought yet are marked. */
	private static String names(AuraType aura, boolean active, AuraPlayerData data) {
		StringBuilder out = new StringBuilder();
		List<AuraAbilities.Ability> all = new ArrayList<>(aura.abilities(data == null ? null : com.toast.aurapoints.ability.Branch.current(data)));
		all.addAll(AuraType.GLOBAL);
		for (AuraAbilities.Ability ability : all) {
			if (ability.active != active) continue;
			if (!out.isEmpty()) out.append(", ");
			out.append(ability.displayName);
			if (AuraAbilities.DISABLED.contains(ability)) out.append(" (off)");
			else if (data != null && ability.active && !data.owned.contains(ability)) out.append(" (buy in /aura shop)");
		}
		return out.isEmpty() ? "none" : out.toString();
	}

	private static int showStatus(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Only players can check their own aura."));
			return 0;
		}

		AuraPlayerData data = AuraState.get(source.getServer()).getOrCreate(player.getUUID());

		player.sendSystemMessage(Component.literal("=== Aura ===").withStyle(ChatFormatting.GOLD));
		if (data.aura == null) {
			player.sendSystemMessage(Component.literal("You have no aura yet. ").withStyle(ChatFormatting.GRAY)
					.append(rollButton(data)));
		} else {
			player.sendSystemMessage(Component.literal("Your aura: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(data.aura.displayName).withStyle(data.aura.color, ChatFormatting.BOLD)));
			player.sendSystemMessage(Component.literal("Passive: " + names(data.aura, false)).withStyle(ChatFormatting.AQUA));
			player.sendSystemMessage(Component.literal("Active: " + names(data.aura, true, data)).withStyle(ChatFormatting.AQUA));
		}
		player.sendSystemMessage(Component.literal(String.format("Aura: %d | next skill point: %d Aura (/aura redeem)",
				AuraCurrency.get(player), AuraCurrency.nextSkillPointCost(data))).withStyle(ChatFormatting.LIGHT_PURPLE));
		player.sendSystemMessage(Component.literal("PvP skill tree: " + (data.pvpUnlocked ? "unlocked (/aura redeem pvp)" : "locked until you defeat a boss"))
				.withStyle(ChatFormatting.GRAY));
		player.sendSystemMessage(Component.literal("Bind ability keys in Options > Controls > Aura Points.")
				.withStyle(ChatFormatting.GRAY));
		player.sendSystemMessage(Component.literal("What does everything do? ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal("[/aura info]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
						.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura info"))))
				.append(Component.literal("  "))
				.append(Component.literal("[Aura Book]").withStyle(style -> style.withColor(ChatFormatting.GOLD)
						.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura menu"))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Everything in one menu"))))));
		return 1;
	}

	/** /aura book: a new Aura Book (it shows whoever opens it, so any copy works). */
	private static int giveAuraBook(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		com.toast.aurapoints.item.AuraBookItem.give(player);
		player.sendSystemMessage(Component.literal("Here's an Aura Book. Right-click it to open the menu.").withStyle(ChatFormatting.GOLD));
		return 1;
	}

	/** /aura bloom: Verdant's Bloom on or off (it can be a nuisance on a farm). */
	private static int toggleNightVision(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return 0;
		AuraState state = AuraState.get(ctx.getSource().getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		data.nightVisionOn = !data.nightVisionOn;
		state.markDirty();
		if (!data.nightVisionOn) com.toast.aurapoints.ability.Nightstalker.clearVision(player, data);
		player.sendSystemMessage(Component.literal("Nightstalker's Night Vision is now " + (data.nightVisionOn ? "on." : "off (the damage bonus stays)."))
				.withStyle(data.nightVisionOn ? ChatFormatting.DARK_PURPLE : ChatFormatting.GRAY));
		return 1;
	}

	/** /aura branch: what your aura's branches are and which one you walk. */
	private static int showBranches(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return 0;
		AuraPlayerData data = AuraState.get(ctx.getSource().getServer()).getOrCreate(player.getUUID());
		String blocker = com.toast.aurapoints.ability.Branch.blocker(data);
		if (blocker != null) {
			player.sendSystemMessage(Component.literal(blocker).withStyle(ChatFormatting.GRAY));
			return 0;
		}
		var current = data.branches.get(data.aura);
		for (var b : com.toast.aurapoints.ability.Branch.of(data.aura)) {
			player.sendSystemMessage(Component.literal(b.label()).withStyle(b.color, ChatFormatting.BOLD)
					.append(Component.literal(" - " + b.role + (b == current ? "  (yours)" : "")).withStyle(ChatFormatting.GRAY)));
			for (String line : b.description()) player.sendSystemMessage(Component.literal("  " + line).withStyle(ChatFormatting.DARK_GRAY));
		}
		player.sendSystemMessage(Component.literal(current == null ? "Pick one with /aura branch <name> (free the first time)."
				: "Switching costs " + com.toast.aurapoints.config.AuraConfig.BRANCH_SWITCH_COST + " Aura.").withStyle(ChatFormatting.YELLOW));
		return 1;
	}

	private static int pickBranch(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return 0;
		var branch = com.toast.aurapoints.ability.Branch.byId(StringArgumentType.getString(ctx, "branch"));
		if (branch == null) {
			player.sendSystemMessage(Component.literal("No such branch. /aura branch lists yours.").withStyle(ChatFormatting.RED));
			return 0;
		}
		player.sendSystemMessage(com.toast.aurapoints.ability.Branch.pick(player, branch));
		return 1;
	}

	private static int toggleBloom(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return 0;
		AuraState state = AuraState.get(ctx.getSource().getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		data.bloomOn = !data.bloomOn;
		state.markDirty();
		player.sendSystemMessage(Component.literal("Bloom is now " + (data.bloomOn ? "on - sneak to make plants around you grow." : "off."))
				.withStyle(data.bloomOn ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		return 1;
	}

	/** One line on what each aura is about, for /aura info. */
	public static String role(AuraType aura) {
		return switch (aura) {
			case SHADOW -> "Fast and stealthy: vanish, dash past them and strike from where they can't see you.";
			case WATER -> "Sustain and control: heal your team, blast enemies back, drown them in a bubble.";
			case FIRE -> "All-out attack: fireballs, waves of flame, and a flame you charge by swinging it around.";
			case WIND -> "Master of the air: dashes, double jumps, wind charges and gliding.";
			case FROST -> "Control: slow and freeze your enemies, and take their hits on a shell of ice.";
			case VERDANT -> "Nature: drag your enemies to you with vines, and make everything around you grow.";
			case THUNDER -> "Burst: rise into the sky and hurl lightning like Zeus - three bolts, the last one calls down the storm.";
			case RESONANCE -> "Hunter by ear: you can barely see, but you hear everyone who makes a sound - and so do your plants.";
			case EARTH -> "Immovable: raise pillars, tear boulders out of the ground and make it heave under your enemies.";
			case LIGHT -> "Radiance: heal everyone around you, blind your enemies and burn them with a beam of pure light.";
			case BLOOD -> "Hunger: cut them open, drain them dry, and spend your own blood for the hits that count.";
		};
	}

	/** /aura info [aura]: the aura's role, then every ability with what it does right now. */
	private static int showInfo(CommandContext<CommandSourceStack> ctx, String auraId) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		AuraPlayerData data = player == null ? null : AuraState.get(source.getServer()).getOrCreate(player.getUUID());
		AuraType aura = auraId == null ? (data == null ? null : data.aura) : AuraType.byId(auraId.toLowerCase(java.util.Locale.ROOT));
		if (auraId != null && aura == null) {
			source.sendFailure(Component.literal("There is no aura called '" + auraId + "'."));
			return 0;
		}
		if (aura == null) {
			source.sendSystemMessage(Component.literal("You have no aura yet. Read up on one: ").withStyle(ChatFormatting.GRAY).append(auraLinks(null)));
			return 1;
		}
		boolean mine = data != null && data.aura == aura;
		com.toast.aurapoints.ability.Branch br = mine ? com.toast.aurapoints.ability.Branch.current(data) : null;
		int accent = com.toast.aurapoints.ability.AuraLabel.accent(aura);

		source.sendSystemMessage(Component.literal(""));
		source.sendSystemMessage(Component.literal("━━━━ ").withStyle(style -> style.withColor(accent))
				.append(com.toast.aurapoints.ability.AuraLabel.of(aura, aura.displayName + " Aura"))
				.append(Component.literal(" ━━━━").withStyle(style -> style.withColor(accent))));
		source.sendSystemMessage(Component.literal(role(aura)).withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
		if (!aura.rollable()) source.sendSystemMessage(Component.literal("Not in /aura roll yet.").withStyle(ChatFormatting.DARK_GRAY));
		if (!aura.strongAgainst(br).isEmpty() || !aura.weakAgainst(br).isEmpty()) {
			MutableComponent matchups = Component.empty();
			if (!aura.strongAgainst(br).isEmpty()) {
				matchups.append(Component.literal("Strong against ").withStyle(ChatFormatting.GREEN));
				for (AuraType t : aura.strongAgainst(br)) matchups.append(com.toast.aurapoints.ability.AuraLabel.of(t)).append(Component.literal(" "));
				matchups.append(Component.literal("(+" + Math.round((AuraConfig.AURA_EFFECTIVE_MULTIPLIER - 1) * 100) + "% damage)  ").withStyle(ChatFormatting.DARK_GRAY));
			}
			if (!aura.weakAgainst(br).isEmpty()) {
				matchups.append(Component.literal("Weak against ").withStyle(ChatFormatting.RED));
				for (AuraType t : aura.weakAgainst(br)) matchups.append(com.toast.aurapoints.ability.AuraLabel.of(t)).append(Component.literal(" "));
			}
			source.sendSystemMessage(matchups);
		}

		List<AuraAbilities.Ability> actives = new ArrayList<>(), passives = new ArrayList<>();
		for (AuraAbilities.Ability a : aura.abilities(br)) (a.active ? actives : passives).add(a);
		passives.addAll(AuraType.GLOBAL);

		if (!actives.isEmpty()) {
			source.sendSystemMessage(Component.literal("✦ Abilities").withStyle(style -> style.withColor(accent).withBold(true))
					.append(Component.literal("  (keys: Options > Controls > Aura Points)").withStyle(style -> style.withBold(false).withColor(ChatFormatting.DARK_GRAY))));
			for (AuraAbilities.Ability a : actives) {
				MutableComponent line = Component.literal(" • ").withStyle(style -> style.withColor(accent))
						.append(Component.literal(a.displayName).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
				int slot = aura.keySlots(br).indexOf(a);
				line.append(Component.literal(slot >= 0 ? "  [Ability " + (slot + 1) + " key]" : "  [jump key]").withStyle(ChatFormatting.DARK_AQUA));
				if (AuraAbilities.DISABLED.contains(a)) {
					line.append(Component.literal("  switched off").withStyle(ChatFormatting.RED));
				} else if (mine && data.owned.contains(a)) {
					line.append(Component.literal("  ✔ owned").withStyle(ChatFormatting.GREEN));
				} else if (mine) {
					int price = nextShopPrice(data);
					String cmd = "/aura shop buy " + a.name().toLowerCase(java.util.Locale.ROOT);
					line.append(Component.literal("  [Buy - " + price + " Aura]").withStyle(style -> style.withColor(ChatFormatting.YELLOW)
							.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
							.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to buy " + a.displayName)))));
				}
				if (a.cooldownTicks() > 0) {
					line.append(Component.literal("  ⌛ " + AuraAbilities.fmt(a.cooldownTicks() / 20f) + "s").withStyle(ChatFormatting.DARK_GRAY));
				}
				source.sendSystemMessage(line);
				source.sendSystemMessage(Component.literal("   " + a.description()).withStyle(ChatFormatting.GRAY));
			}
		}
		source.sendSystemMessage(Component.literal("✦ Passives").withStyle(style -> style.withColor(accent).withBold(true))
				.append(Component.literal("  (always on, free)").withStyle(style -> style.withBold(false).withColor(ChatFormatting.DARK_GRAY))));
		for (AuraAbilities.Ability a : passives) {
			MutableComponent line = Component.literal(" • ").withStyle(style -> style.withColor(accent))
					.append(Component.literal(a.displayName).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
			if (AuraAbilities.DISABLED.contains(a)) line.append(Component.literal("  switched off").withStyle(ChatFormatting.RED));
			source.sendSystemMessage(line);
			source.sendSystemMessage(Component.literal("   " + a.description()).withStyle(ChatFormatting.GRAY));
		}
		source.sendSystemMessage(Component.literal("Other auras: ").withStyle(ChatFormatting.DARK_GRAY).append(auraLinks(aura)));
		return 1;
	}

	/** Every aura's styled name, each one clickable to /aura info it ({@code skip} left out). */
	private static MutableComponent auraLinks(AuraType skip) {
		MutableComponent out = Component.empty();
		boolean first = true;
		for (AuraType type : AuraType.values()) {
			if (type == skip) continue;
			if (!first) out.append(Component.literal("  "));
			first = false;
			String cmd = "/aura info " + type.id();
			out.append(com.toast.aurapoints.ability.AuraLabel.of(type).withStyle(style -> style
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("What " + type.displayName + " can do")))));
		}
		return out;
	}

	private static MutableComponent rollButton(AuraPlayerData data) {
		String label = data.freeRollUsed ? "[Reroll - " + AuraConfig.REROLL_COST + " Aura]" : "[Roll - free]";
		return Component.literal(label).withStyle(style -> style
				.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura roll confirm"))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
						Component.literal(data.aura == null ? "Roll your aura" : "Roll a new aura (never the one you have)"))));
	}

	private static int showRollMenu(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Only players can roll an aura."));
			return 0;
		}
		AuraPlayerData data = AuraState.get(source.getServer()).getOrCreate(player.getUUID());

		player.sendSystemMessage(Component.literal("=== Aura Roll ===").withStyle(ChatFormatting.GOLD));
		for (AuraType type : AuraType.values()) {
			if (!type.rollable() && type != data.aura) continue;
			MutableComponent line = Component.literal("- " + type.displayName).withStyle(type.color, ChatFormatting.BOLD);
			if (type == data.aura) line.append(Component.literal(" (yours)").withStyle(ChatFormatting.DARK_GRAY));
			line.append(Component.literal(": " + names(type, true) + " | " + names(type, false))
					.withStyle(ChatFormatting.GRAY));
			player.sendSystemMessage(line);
		}
		player.sendSystemMessage(Component.literal("Every aura also has Vein of Aura (lifesteal against mobs).").withStyle(ChatFormatting.DARK_GRAY));
		player.sendSystemMessage(Component.literal("You have " + AuraCurrency.get(player) + " Aura. ").withStyle(ChatFormatting.GRAY)
				.append(rollButton(data)));
		return 1;
	}

	private static int roll(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Only players can roll an aura."));
			return 0;
		}

		AuraState state = AuraState.get(source.getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());

		List<AuraType> candidates = new ArrayList<>();
		for (AuraType type : AuraType.values()) {
			if (type != data.aura && type.rollable()) candidates.add(type);
		}

		if (candidates.isEmpty()) {
			source.sendFailure(Component.literal("There is no other aura to roll right now."));
			return 0;
		}

		boolean free = !data.freeRollUsed;
		if (!free && !AuraCurrency.take(player, AuraConfig.REROLL_COST)) {
			source.sendFailure(Component.literal(String.format("A reroll costs %d Aura - you have %d.",
					AuraConfig.REROLL_COST, AuraCurrency.get(player))));
			return 0;
		}

		AuraType rolled = candidates.get(RANDOM.nextInt(candidates.size()));
		data.aura = rolled;
		data.rolled.add(rolled);
		data.freeRollUsed = true;
		state.markDirty();
		AuraAbilities.refreshBerserkerModifier(player, AuraAbilities.hasAbility(data, AuraAbilities.Ability.BERSERKER));

		// The roll plays out: every aura's color spins around the player, then the result is revealed.
		// The aura is already saved above, so a disconnect mid-animation loses nothing.
		ServerLevel level = player.serverLevel();
		AuraFx.sound(level, player, SoundEvents.BEACON_ACTIVATE, 0.8f, 1.6f);
		AuraFx.sound(level, player, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 0.8f);
		AuraType[] all = AuraType.values();
		// the reel on the player's screen (client/RollReel), landing on the result when the reveal below comes
		StringBuilder reel = new StringBuilder(rolled.id());
		for (AuraType t : all) reel.append(',').append(t.id());
		com.toast.aurapoints.network.NetworkHandler.sendFx(player, com.toast.aurapoints.network.FxPacket.ROLL, ROLL_SPIN_FRAMES * 2, reel.toString());
		for (int i = 0; i < ROLL_SPIN_FRAMES; i++) {
			final AuraType shown = all[i % all.length];
			final int frame = i;
			AuraFx.later(source.getServer(), i * 2, () -> {
				if (!AuraFx.alive(player)) return;
				double a = frame * 0.9;
				double y = player.getY() + 0.3 + frame * 0.1;
				for (int k = 0; k < 3; k++) {
					double ka = a + k * Math.PI * 2 / 3;
					com.toast.aurapoints.ability.AuraFx.particles(player.serverLevel(), AuraFx.dust(shown.rgb, 1.5f),
							player.getX() + Math.cos(ka) * 1.0, y, player.getZ() + Math.sin(ka) * 1.0, 2, 0.03, 0.03, 0.03, 0.0);
				}
				if (frame % 3 == 0) {
					AuraFx.sound(player.serverLevel(), player, SoundEvents.AMETHYST_BLOCK_CHIME, 0.6f, 0.8f + frame * 0.06f);
				}
			});
		}
		AuraFx.later(source.getServer(), ROLL_SPIN_FRAMES * 2, () -> {
			if (AuraFx.alive(player)) {
				revealRoll(player, rolled);
				return;
			}
			// died during the spin: no show, but the HUD, aura_type score, hook and advancement still catch up
			ServerPlayer respawned = source.getServer().getPlayerList().getPlayer(player.getUUID());
			if (respawned == null) return; // logged out: the login does it
			AuraPlayerData now = state.getOrCreate(respawned.getUUID());
			AuraStats.grantAuraAdvancement(respawned, now);
			com.toast.aurapoints.network.NetworkHandler.syncAura(respawned, now);
		});
		return 1;
	}

	private static final int ROLL_SPIN_FRAMES = 16;

	private static void revealRoll(ServerPlayer player, AuraType rolled) {
		ServerLevel level = player.serverLevel();
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		AuraStats.grantAuraAdvancement(player, data);
		com.toast.aurapoints.network.NetworkHandler.syncAura(player, data);
		AuraFx.burst(level, player.getX(), player.getY() + 1.0, player.getZ(), AuraFx.dust(rolled.rgb, 2.0f), 60, 0.8, 0.0);
		AuraFx.burst(level, player.getX(), player.getY() + 1.0, player.getZ(), ParticleTypes.END_ROD, 25, 0.3, 0.15);
		AuraFx.burst(level, player.getX(), player.getY() + 1.0, player.getZ(), ParticleTypes.TOTEM_OF_UNDYING, 30, 0.4, 0.4);
		AuraFx.ring(level, player.getX(), player.getY() + 0.1, player.getZ(), 1.6, AuraFx.dust(rolled.rgb, 1.6f), 32);
		AuraFx.helix(player, AuraFx.dust(rolled.rgb, 1.3f), 24, 0.7, 2.4);
		AuraFx.sound(level, player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
		AuraFx.sound(level, player, SoundEvents.PLAYER_LEVELUP, 0.8f, 1.2f);

		StringBuilder names = new StringBuilder();
		for (AuraAbilities.Ability ability : rolled.abilities) {
			if (!names.isEmpty()) names.append("  ·  ");
			names.append(ability.displayName);
		}
		AuraFx.title(player,
				Component.literal(rolled.displayName + " Aura").withStyle(rolled.color, ChatFormatting.BOLD),
				Component.literal(names.toString()).withStyle(ChatFormatting.GRAY));

		player.sendSystemMessage(Component.literal("You rolled the ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(rolled.displayName).withStyle(rolled.color, ChatFormatting.BOLD))
				.append(Component.literal(" aura!").withStyle(ChatFormatting.GOLD)));
		List<AuraAbilities.Ability> all = new ArrayList<>(rolled.abilities);
		all.addAll(AuraType.GLOBAL);
		for (AuraAbilities.Ability ability : all) {
			player.sendSystemMessage(Component.literal("- " + ability.displayName + ": ").withStyle(ChatFormatting.AQUA)
					.append(Component.literal(ability.description()).withStyle(ChatFormatting.GRAY)));
		}
		player.sendSystemMessage(Component.literal("Its passives work right away. Buy its active abilities in ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal("[/aura shop]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura shop")))));
		if (rolled.abilities.stream().anyMatch(AuraAbilities.Ability::hasKey)) {
			player.sendSystemMessage(Component.literal("Bind its keys in Options > Controls > Aura Points.")
					.withStyle(ChatFormatting.GRAY));
		}
	}

	private static int redeem(CommandContext<CommandSourceStack> ctx, String category, int amount) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Only players can redeem Aura."));
			return 0;
		}

		AuraState state = AuraState.get(source.getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		boolean pvp = category.equals(AuraConfig.PUFFISH_CATEGORY_PVP);
		if (pvp && !data.pvpUnlocked) {
			source.sendFailure(Component.literal("The PvP tree is still locked. Defeat a boss (e.g. the Wither or the Warden) to unlock it."));
			return 0;
		}

		// The price rises with every point, so add up each one.
		int total = 0;
		for (int i = 0; i < amount; i++) {
			total += AuraConfig.SKILL_POINT_BASE_COST + (data.skillPointsBought + i) * AuraConfig.SKILL_POINT_COST_STEP;
		}
		int balance = AuraCurrency.get(player);
		if (balance < total) {
			source.sendFailure(Component.literal(String.format("%d skill point%s cost%s %d Aura - you have %d.",
					amount, amount == 1 ? "" : "s", amount == 1 ? "s" : "", total, balance)));
			return 0;
		}

		if (!PuffishBridge.addPoints(player, category, amount)) {
			source.sendFailure(Component.literal("Couldn't add skill points - is Puffish's Skills installed and the '"
					+ category + "' category configured? No Aura was taken."));
			return 0;
		}
		AuraCurrency.take(player, total);
		data.skillPointsBought += amount;
		state.markDirty();

		player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.75f, 1.4f);
		ServerLevel level = player.serverLevel();
		AuraFx.sound(level, player, SoundEvents.ENCHANTMENT_TABLE_USE, 1.0f, 1.0f);
		AuraFx.burst(level, player.getX(), player.getY() + 1.2, player.getZ(), ParticleTypes.ENCHANT, 60, 0.6, 0.5);
		AuraFx.burst(level, player.getX(), player.getY() + 1.0, player.getZ(), AuraFx.dust(pvp ? 0xB0121B : 0xC77DFF, 1.3f), 25, 0.5, 0.0);
		AuraFx.ring(level, player.getX(), player.getY() + 0.1, player.getZ(), 1.0, ParticleTypes.END_ROD, 16);
		player.displayClientMessage(Component.literal("+" + amount + " skill point" + (amount == 1 ? "" : "s"))
				.withStyle(pvp ? ChatFormatting.RED : ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), true);
		player.sendSystemMessage(Component.literal(String.format("Bought %d skill point%s for the %s tree (%d Aura). Next one costs %d.",
				amount, amount == 1 ? "" : "s", pvp ? "PvP" : "Aura", total, AuraCurrency.nextSkillPointCost(data)))
				.withStyle(ChatFormatting.LIGHT_PURPLE));
		return amount;
	}

	/** Price of the next active ability on the player's current aura. */
	public static int nextShopPrice(AuraPlayerData data) {
		int owned = 0;
		if (data.aura != null) {
			for (AuraAbilities.Ability a : data.aura.abilities(com.toast.aurapoints.ability.Branch.current(data))) if (a.active && data.owned.contains(a)) owned++;
		}
		return AuraConfig.SHOP_BASE_PRICE + owned * AuraConfig.SHOP_PRICE_STEP;
	}

	private static int showShop(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Only players can use the shop."));
			return 0;
		}
		AuraPlayerData data = AuraState.get(source.getServer()).getOrCreate(player.getUUID());
		if (data.aura == null) {
			source.sendFailure(Component.literal("Roll an aura first: /aura roll"));
			return 0;
		}

		int price = nextShopPrice(data);
		player.sendSystemMessage(Component.literal("=== Ability Shop: ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(data.aura.displayName).withStyle(data.aura.color, ChatFormatting.BOLD))
				.append(Component.literal(" ===").withStyle(ChatFormatting.GOLD)));
		for (AuraAbilities.Ability ability : data.aura.abilities(com.toast.aurapoints.ability.Branch.current(data))) {
			if (!ability.active) continue;
			MutableComponent line = Component.literal("- " + ability.displayName).withStyle(ChatFormatting.AQUA);
			if (data.owned.contains(ability)) {
				line.append(Component.literal(" [Owned]").withStyle(ChatFormatting.DARK_GRAY));
			} else if (AuraAbilities.DISABLED.contains(ability)) {
				line.append(Component.literal(" [Switched off by the server]").withStyle(ChatFormatting.DARK_GRAY));
			} else {
				String cmd = "/aura shop buy " + ability.name().toLowerCase(java.util.Locale.ROOT);
				line.append(Component.literal(" [Buy - " + price + " Aura]").withStyle(style -> style
						.withColor(ChatFormatting.GREEN)
						.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(ability.description())))));
			}
			player.sendSystemMessage(line);
		}
		player.sendSystemMessage(Component.literal("Passives (always on): " + names(data.aura, false)).withStyle(ChatFormatting.GRAY));
		player.sendSystemMessage(Component.literal(String.format("You have %d Aura. Each active you buy makes the next one %d Aura more.",
				AuraCurrency.get(player), AuraConfig.SHOP_PRICE_STEP)).withStyle(ChatFormatting.DARK_GRAY));
		if (AuraConfig.COSMETICS_ENABLED) {
			player.sendSystemMessage(Component.literal("[Cosmetics: halos, trails, kill effects, titles, poses]").withStyle(style -> style
					.withColor(ChatFormatting.LIGHT_PURPLE)
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura cosmetics"))));
		}
		return 1;
	}

	private static int buyAbility(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Only players can buy abilities."));
			return 0;
		}
		AuraState state = AuraState.get(source.getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		AuraAbilities.Ability ability;
		try {
			ability = AuraAbilities.Ability.valueOf(StringArgumentType.getString(ctx, "ability").toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			source.sendFailure(Component.literal("Unknown ability. Use /aura shop."));
			return 0;
		}
		if (data.aura == null || !data.aura.abilities(com.toast.aurapoints.ability.Branch.current(data)).contains(ability) || !ability.active) {
			source.sendFailure(Component.literal(ability.displayName + " isn't an active ability of your aura. Use /aura shop."));
			return 0;
		}
		if (data.owned.contains(ability)) {
			source.sendFailure(Component.literal("You already own " + ability.displayName + "."));
			return 0;
		}
		if (AuraAbilities.DISABLED.contains(ability)) {
			source.sendFailure(Component.literal(ability.displayName + " is switched off on this server right now."));
			return 0;
		}
		int price = nextShopPrice(data);
		if (!AuraCurrency.take(player, price)) {
			source.sendFailure(Component.literal(String.format("%s costs %d Aura - you have %d.",
					ability.displayName, price, AuraCurrency.get(player))));
			return 0;
		}
		data.owned.add(ability);
		data.shopSpent += price;
		state.markDirty();
		com.toast.aurapoints.network.NetworkHandler.syncOwned(player, data);

		ServerLevel level = player.serverLevel();
		AuraFx.burst(level, player.getX(), player.getY() + 1.0, player.getZ(), AuraFx.dust(data.aura.rgb, 1.6f), 40, 0.6, 0.0);
		AuraFx.burst(level, player.getX(), player.getY() + 1.2, player.getZ(), ParticleTypes.ENCHANT, 40, 0.6, 0.5);
		AuraFx.ring(level, player.getX(), player.getY() + 0.1, player.getZ(), 1.2, ParticleTypes.END_ROD, 20);
		AuraFx.sound(level, player, SoundEvents.PLAYER_LEVELUP, 0.8f, 1.3f);
		AuraFx.sound(level, player, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.0f);
		AuraFx.title(player, Component.literal(ability.displayName).withStyle(data.aura.color, ChatFormatting.BOLD),
				Component.literal("unlocked").withStyle(ChatFormatting.GRAY));
		player.sendSystemMessage(Component.literal("Unlocked " + ability.displayName + " for " + price + " Aura! ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(ability.description()).withStyle(ChatFormatting.GRAY)));
		if (ability.hasKey()) {
			player.sendSystemMessage(Component.literal("Bind its key in Options > Controls > Aura Points.").withStyle(ChatFormatting.GRAY));
		}
		return 1;
	}

	private static int toggleSelfHide(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFailure(Component.literal("Only players can change this."));
			return 0;
		}
		AuraState state = AuraState.get(ctx.getSource().getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		data.selfHide = !data.selfHide;
		state.markDirty();
		com.toast.aurapoints.network.NetworkHandler.syncSettings(player, data);
		player.sendSystemMessage(Component.literal(data.selfHide
				? "Self-hide ON: while cloaked (Shadow + invisible) you won't see your own body or armor in third person."
				: "Self-hide OFF: you see yourself normally while cloaked. Others still can't see you.")
				.withStyle(ChatFormatting.DARK_PURPLE));
		return 1;
	}

	private static int reloadBalance(CommandContext<CommandSourceStack> ctx) {
		List<String> report = new java.util.ArrayList<>(com.toast.aurapoints.config.AuraBalance.load());
		com.toast.aurapoints.editor.BalanceEditors.reload();
		com.toast.aurapoints.config.Worthy.reload();
		report.addAll(com.toast.aurapoints.config.CommandRules.load());
		report.addAll(Tickets.reloadConfig());
		CommandSourceStack source = ctx.getSource();
		if (report.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Balance reloaded - no values changed.").withStyle(ChatFormatting.GRAY), false);
			return 1;
		}
		source.sendSuccess(() -> Component.literal("Balance reloaded:").withStyle(ChatFormatting.GOLD), true);
		for (String line : report) {
			ChatFormatting color = line.startsWith("ERROR") ? ChatFormatting.RED : line.startsWith("WARNING") ? ChatFormatting.YELLOW : ChatFormatting.AQUA;
			source.sendSuccess(() -> Component.literal("  " + line).withStyle(color), false);
		}
		return report.size();
	}

	private static final SuggestionProvider<CommandSourceStack> PROGRESS_NAMES = (context, builder) ->
			net.minecraft.commands.SharedSuggestionProvider.suggest(
					com.toast.aurapoints.data.PlayerProgress.knownNames(context.getSource().getServer()), builder);

	/** A name typed into /aura progress: the player's id, or null (and a failure message) if the server never saw them. */
	private static java.util.UUID progressTarget(CommandSourceStack source, String name) {
		java.util.Optional<java.util.UUID> id = com.toast.aurapoints.data.PlayerProgress.idOf(source.getServer(), name);
		if (id.isEmpty()) source.sendFailure(Component.literal("Unknown player: " + name));
		return id.orElse(null);
	}

	private static int exportProgress(CommandContext<CommandSourceStack> ctx, String name) {
		CommandSourceStack source = ctx.getSource();
		java.util.UUID only = null;
		if (name != null && (only = progressTarget(source, name)) == null) return 0;
		List<String> report = com.toast.aurapoints.data.PlayerProgress.export(source.getServer(), only);
		source.sendSuccess(() -> Component.literal("Progress exported to " + com.toast.aurapoints.data.PlayerProgress.root().getFileName()
				+ "/players:").withStyle(ChatFormatting.GOLD), true);
		sendReport(source, report);
		return report.size();
	}

	private static int importProgress(CommandContext<CommandSourceStack> ctx, String name, boolean dry) {
		CommandSourceStack source = ctx.getSource();
		java.util.UUID only = null;
		if (name != null && (only = progressTarget(source, name)) == null) return 0;
		List<String> report = com.toast.aurapoints.data.PlayerProgress.importFiles(source.getServer(), only, dry, isOwner(source));
		source.sendSuccess(() -> Component.literal(dry ? "Progress import (dry run):" : "Progress import:").withStyle(ChatFormatting.GOLD), !dry);
		sendReport(source, report);
		return report.size();
	}

	/** Report lines coloured like /aura reload: ERROR red, WARNING yellow, player headers white, changes aqua. */
	private static void sendReport(CommandSourceStack source, List<String> report) {
		for (String line : report) {
			ChatFormatting color = line.startsWith("ERROR") ? ChatFormatting.RED : line.startsWith("WARNING") ? ChatFormatting.YELLOW
					: line.startsWith("  ") ? ChatFormatting.AQUA : ChatFormatting.WHITE;
			source.sendSuccess(() -> Component.literal("  " + line).withStyle(color), false);
		}
	}

	// ---------------------------------------------------------------
	// /aura resetprogress <part|nuke> [players]  (+ confirm)
	// ---------------------------------------------------------------

	/** A nuke waiting for /aura resetprogress confirm: who asked (source name), whom, until when (ms). */
	private record PendingNuke(List<java.util.UUID> targets, String names, long until) {
	}

	private static final java.util.Map<String, PendingNuke> PENDING_NUKES = new java.util.HashMap<>();
	private static final long NUKE_CONFIRM_MS = 30_000;

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> resetProgressNode() {
		var node = Commands.literal("resetprogress").requires(src -> src.hasPermission(2)).executes(AuraCommand::resetProgressHelp);
		for (AuraReset.Part part : AuraReset.Part.values()) {
			node.then(Commands.literal(part.id)
					.executes(ctx -> resetProgress(ctx, List.of(ctx.getSource().getPlayerOrException()), part))
					.then(Commands.argument("targets", EntityArgument.players())
							.executes(ctx -> resetProgress(ctx, EntityArgument.getPlayers(ctx, "targets"), part))));
		}
		node.then(Commands.literal("nuke")
				.executes(ctx -> askNuke(ctx, List.of(ctx.getSource().getPlayerOrException())))
				.then(Commands.argument("targets", EntityArgument.players())
						.executes(ctx -> askNuke(ctx, EntityArgument.getPlayers(ctx, "targets")))));
		node.then(Commands.literal("confirm").executes(AuraCommand::confirmNuke));
		return node;
	}

	private static int resetProgressHelp(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> Component.literal("/aura resetprogress <part> [players] - no players = yourself. Parts:").withStyle(ChatFormatting.GOLD), false);
		for (AuraReset.Part part : AuraReset.Part.values()) {
			src.sendSuccess(() -> Component.literal("  " + part.id).withStyle(ChatFormatting.AQUA)
					.append(Component.literal(" - " + part.what).withStyle(ChatFormatting.GRAY)), false);
		}
		src.sendSuccess(() -> Component.literal("  nuke").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
				.append(Component.literal(" - ALL of the above, nothing refunded (asks for /aura resetprogress confirm)").withStyle(ChatFormatting.GRAY)), false);
		return 1;
	}

	private static int resetProgress(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets, AuraReset.Part part) {
		AuraState state = AuraState.get(ctx.getSource().getServer());
		for (ServerPlayer target : targets) {
			AuraPlayerData data = state.getOrCreate(target.getUUID());
			String note = AuraReset.reset(target, data, part);
			afterReset(target, data);
			ctx.getSource().sendSuccess(() -> Component.literal("Reset " + part.id + " of " + target.getGameProfile().getName() + ": " + note + "."), true);
		}
		state.markDirty();
		return targets.size();
	}

	private static String sourceKey(CommandSourceStack src) {
		return src.getEntity() != null ? src.getEntity().getStringUUID() : src.getTextName();
	}

	private static int askNuke(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
		List<java.util.UUID> ids = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (ServerPlayer t : targets) {
			ids.add(t.getUUID());
			names.add(t.getGameProfile().getName());
		}
		String who = String.join(", ", names);
		PENDING_NUKES.put(sourceKey(ctx.getSource()), new PendingNuke(ids, who, System.currentTimeMillis() + NUKE_CONFIRM_MS));
		ctx.getSource().sendSuccess(() -> Component.literal("☢ NUKE " + who + ": aura, Aura balance, shop, skill trees, mastery, awakenings, cosmetics, "
						+ "weeklies, Trials/Feats and the FTB quest book - all gone, nothing refunded. ").withStyle(ChatFormatting.RED)
				.append(Component.literal("[CONFIRM]").withStyle(s -> s.withColor(ChatFormatting.DARK_RED).withBold(true)
						.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/aura resetprogress confirm"))
						.withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
								Component.literal("Wipe " + who + " for good")))))
				.append(Component.literal(" or /aura resetprogress confirm within " + NUKE_CONFIRM_MS / 1000 + " s.").withStyle(ChatFormatting.GRAY)), false);
		return 1;
	}

	private static int confirmNuke(CommandContext<CommandSourceStack> ctx) {
		PendingNuke pending = PENDING_NUKES.remove(sourceKey(ctx.getSource()));
		if (pending == null || System.currentTimeMillis() > pending.until()) {
			ctx.getSource().sendFailure(Component.literal("Nothing to confirm (a nuke waits " + NUKE_CONFIRM_MS / 1000 + " s). Start with /aura resetprogress nuke [players]."));
			return 0;
		}
		net.minecraft.server.MinecraftServer server = ctx.getSource().getServer();
		AuraState state = AuraState.get(server);
		int done = 0;
		for (java.util.UUID id : pending.targets()) {
			ServerPlayer target = server.getPlayerList().getPlayer(id);
			if (target == null) {
				ctx.getSource().sendFailure(Component.literal("A target went offline (" + id + ") - skipped."));
				continue;
			}
			AuraPlayerData data = state.getOrCreate(id);
			String note = AuraReset.nuke(target, data);
			afterReset(target, data);
			target.sendSystemMessage(Component.literal("Your Aura progress was reset by an admin - you start fresh. Your first roll is free: /aura roll")
					.withStyle(ChatFormatting.RED));
			ctx.getSource().sendSuccess(() -> Component.literal("☢ Nuked " + target.getGameProfile().getName() + ": " + note + ".").withStyle(ChatFormatting.RED), true);
			done++;
		}
		state.markDirty();
		return done;
	}

	/** After a reset: the HUD, nametag, owned abilities and Berserker modifier show the new state. */
	private static void afterReset(ServerPlayer target, AuraPlayerData data) {
		AuraAbilities.refreshBerserkerModifier(target, AuraAbilities.hasAbility(data, AuraAbilities.Ability.BERSERKER));
		AuraStats.grantAuraAdvancement(target, data);
		com.toast.aurapoints.network.NetworkHandler.syncAura(target, data);
		com.toast.aurapoints.network.NetworkHandler.syncOwned(target, data);
	}

	private static int spawnTouched(CommandContext<CommandSourceStack> ctx) {
		AuraType aura = AuraType.byId(StringArgumentType.getString(ctx, "aura").toLowerCase(java.util.Locale.ROOT));
		if (aura == null) {
			ctx.getSource().sendFailure(Component.literal("No aura called " + StringArgumentType.getString(ctx, "aura") + "."));
			return 0;
		}
		var level = ctx.getSource().getLevel();
		var pos = ctx.getSource().getPosition();
		var zombie = new net.minecraft.world.entity.monster.Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
		zombie.moveTo(pos.x, pos.y, pos.z, ctx.getSource().getRotation().y, 0f);
		zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(zombie.blockPosition()), net.minecraft.world.entity.MobSpawnType.COMMAND, null);
		com.toast.aurapoints.ability.TouchedMobs.touch(zombie, aura);
		level.addFreshEntity(zombie);
		ctx.getSource().sendSuccess(() -> Component.literal("Spawned ").append(zombie.getDisplayName()).append("."), true);
		return 1;
	}

	private static int showCooldowns(CommandContext<CommandSourceStack> ctx) {
		boolean off = AuraState.get(ctx.getSource().getServer()).cooldownsDisabled;
		ctx.getSource().sendSuccess(() -> Component.literal("Ability cooldowns are " + (off ? "OFF" : "ON")
				+ ". Use /aura cooldowns on|off."), false);
		return 1;
	}

	/**
	 * A server owner (the "owners" list in aurapoints-commands.json; empty = permission level 4) - who may lock
	 * auras or wield the handhelds. On the client: what the server last sent (ClientAuraState.owner).
	 */
	public static boolean isOwner(net.minecraft.world.entity.player.Player player) {
		if (player instanceof ServerPlayer sp) return com.toast.aurapoints.config.CommandRules.isOwner(sp);
		return player != null && player.level().isClientSide && com.toast.aurapoints.network.ClientAuraState.owner;
	}

	/**
	 * May wield the owner-only items (Mjölnir, handhelds, World Tree Bow): an owner, or someone on the worthy list while
	 * WORTHY_ENABLED is on. On the client: what the server last sent (the same flag as ClientAuraState.owner).
	 */
	public static boolean isWorthy(net.minecraft.world.entity.player.Player player) {
		if (player instanceof ServerPlayer sp) {
			return com.toast.aurapoints.config.CommandRules.isOwner(sp) || (com.toast.aurapoints.config.AuraConfig.WORTHY_ENABLED && com.toast.aurapoints.config.Worthy.listed(sp));
		}
		return player != null && player.level().isClientSide && com.toast.aurapoints.network.ClientAuraState.owner;
	}

	/** /aura lock and unlock: an owner in game, or the server console / rcon. */
	private static boolean isOwner(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		return player == null ? src.getEntity() == null && src.hasPermission(4) : com.toast.aurapoints.config.CommandRules.isOwner(player);
	}

	/** /aura balance: hands the Balance Tome to the owner or a whitelisted player. */
	private static int giveBalanceTome(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!com.toast.aurapoints.editor.BalanceEditors.allowed(player)) {
			ctx.getSource().sendFailure(Component.literal("Only the owner and whitelisted players can use the Balance Tome."));
			return 0;
		}
		net.minecraft.world.item.ItemStack tome = new net.minecraft.world.item.ItemStack(AuraPoints.BALANCE_TOME.get());
		if (!player.getInventory().add(tome)) player.drop(tome, false);
		ctx.getSource().sendSuccess(() -> Component.literal("Here's the Balance Tome. Right-click it to edit the balance values.").withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	private static int listBalanceWhitelist(CommandContext<CommandSourceStack> ctx) {
		List<String> list = com.toast.aurapoints.editor.BalanceEditors.whitelist();
		ctx.getSource().sendSuccess(() -> Component.literal(list.isEmpty() ? "Balance Tome whitelist: empty (only you)."
				: "Balance Tome whitelist: " + String.join(", ", list)).withStyle(ChatFormatting.GOLD), false);
		return list.size();
	}

	private static int editBalanceWhitelist(CommandContext<CommandSourceStack> ctx, String who, boolean add) {
		boolean changed = add ? com.toast.aurapoints.editor.BalanceEditors.add(who) : com.toast.aurapoints.editor.BalanceEditors.remove(who);
		String msg = add ? (changed ? who + " can use the Balance Tome now." : who + " is already on the whitelist.")
				: (changed ? who + " can't use the Balance Tome anymore." : who + " isn't on the whitelist.");
		ctx.getSource().sendSuccess(() -> Component.literal(msg).withStyle(changed ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
		return changed ? 1 : 0;
	}

	/** /aura worthy: who is on the list and whether the system is on. */
	private static int listWorthy(CommandContext<CommandSourceStack> ctx) {
		List<String> list = com.toast.aurapoints.config.Worthy.list();
		boolean on = com.toast.aurapoints.config.AuraConfig.WORTHY_ENABLED;
		ctx.getSource().sendSuccess(() -> Component.literal("Worthy (" + (on ? "on" : "OFF - only owners count; WORTHY_ENABLED in the Balance Tome's General page") + "): "
				+ (list.isEmpty() ? "nobody but the owners." : String.join(", ", list))).withStyle(ChatFormatting.GOLD), false);
		return list.size();
	}

	/** /aura worthy add|remove <player>: tells them, and refreshes what their client knows. */
	private static int editWorthy(CommandContext<CommandSourceStack> ctx, String who, boolean add) {
		boolean changed = add ? com.toast.aurapoints.config.Worthy.add(who) : com.toast.aurapoints.config.Worthy.remove(who);
		String msg = add ? (changed ? who + " is worthy now." : who + " is already worthy.")
				: (changed ? who + " is no longer worthy." : who + " isn't on the worthy list.");
		if (changed && add && !com.toast.aurapoints.config.AuraConfig.WORTHY_ENABLED) msg += " (The worthy system is switched off: WORTHY_ENABLED.)";
		String shown = msg;
		ctx.getSource().sendSuccess(() -> Component.literal(shown).withStyle(changed ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
		ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayerByName(who);
		if (online != null) {
			if (changed) online.displayClientMessage(Component.literal(add ? "You have been judged worthy." : "You are no longer worthy.").withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC), false);
			com.toast.aurapoints.network.NetworkHandler.syncDisabled(online);
		}
		return changed ? 1 : 0;
	}

	/** /aura mjolnir <targets>: the hammer for other players (it only works for the worthy). */
	private static int giveMjolnirTo(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
		for (ServerPlayer p : targets) {
			net.minecraft.world.item.ItemStack hammer = new net.minecraft.world.item.ItemStack(com.toast.aurapoints.AuraPoints.MJOLNIR_ITEM.get());
			if (!p.getInventory().add(hammer)) p.drop(hammer, false);
			p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 1.0f, 1.2f);
			if (!isWorthy(p)) ctx.getSource().sendSuccess(() -> Component.literal(p.getGameProfile().getName() + " isn't worthy yet: /aura worthy add " + p.getGameProfile().getName()).withStyle(ChatFormatting.GRAY), false);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Mjölnir sent to " + targets.size() + " player(s).").withStyle(ChatFormatting.AQUA), true);
		return targets.size();
	}

	/** /aura mjolnir: the test hammer, for its one worthy wielder. */
	private static int spawnFrostBear(CommandContext<CommandSourceStack> ctx, int variant) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return 0;
		net.minecraft.world.phys.HitResult hit = player.pick(40, 1f, false);
		net.minecraft.world.phys.Vec3 at = hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? player.position().add(player.getLookAngle().multiply(6, 0, 6)) : hit.getLocation();
		var bear = com.toast.aurapoints.geo.FrostBear.spawn(player.serverLevel(), at, variant);
		if (bear == null) return 0;
		ctx.getSource().sendSuccess(() -> Component.literal("The Frost Bear rises.").withStyle(ChatFormatting.AQUA), false);
		return 1;
	}

	private static int giveItem(CommandContext<CommandSourceStack> ctx, net.minecraft.world.item.Item item) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return 0;
		net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
		if (!player.getInventory().add(stack)) player.drop(stack, false);
		return 1;
	}

	private static int giveMjolnir(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return 0;
		net.minecraft.world.item.ItemStack hammer = new net.minecraft.world.item.ItemStack(com.toast.aurapoints.AuraPoints.MJOLNIR_ITEM.get());
		if (!player.getInventory().add(hammer)) player.drop(hammer, false);
		player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 1.0f, 1.2f);
		ctx.getSource().sendSuccess(() -> Component.literal("Mjölnir answers your call.").withStyle(ChatFormatting.AQUA), false);
		return 1;
	}

	/** /aura handheld [aura] [targets]: an aura's handheld weapon (without an aura: the one of your own aura). */
	private static int giveHandheld(CommandContext<CommandSourceStack> ctx, String auraId, Collection<ServerPlayer> targets) throws CommandSyntaxException {
		Collection<ServerPlayer> to = targets != null ? targets : java.util.List.of(ctx.getSource().getPlayerOrException());
		com.toast.aurapoints.ability.AuraType named = auraId == null ? null : com.toast.aurapoints.ability.AuraType.byId(auraId.toLowerCase(java.util.Locale.ROOT));
		if (auraId != null && named == null) {
			ctx.getSource().sendFailure(Component.literal("No aura called " + auraId + "."));
			return 0;
		}
		AuraState state = AuraState.get(ctx.getSource().getServer());
		int given = 0;
		for (ServerPlayer p : to) {
			com.toast.aurapoints.ability.AuraType aura = named != null ? named : state.getOrCreate(p.getUUID()).aura;
			if (aura == null) {
				ctx.getSource().sendFailure(Component.literal(p.getGameProfile().getName() + " has no aura - name one: /aura handheld <aura>"));
				continue;
			}
			var item = handheldOf(aura);
			if (item == null) {
				ctx.getSource().sendFailure(Component.literal(aura.displayName + " has no handheld weapon yet."));
				continue;
			}
			net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item.get());
			if (!p.getInventory().add(stack)) p.drop(stack, false);
			p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0f, 0.8f);
			given++;
		}
		int n = given;
		if (n > 0) ctx.getSource().sendSuccess(() -> Component.literal("Gave " + n + " handheld weapon" + (n == 1 ? "" : "s") + ".").withStyle(ChatFormatting.AQUA), true);
		return n;
	}

	private static com.toast.aurapoints.shim.neoforge.registries.DeferredItem<net.minecraft.world.item.Item> handheldOf(com.toast.aurapoints.ability.AuraType aura) {
		return (switch (aura) {
			case SHADOW -> com.toast.aurapoints.AuraPoints.SOUL_REAPER;
			case WATER -> com.toast.aurapoints.AuraPoints.LEVIATHANS_ANCHOR;
			case FIRE -> com.toast.aurapoints.AuraPoints.PHOENIX_FANGS;
			case WIND -> com.toast.aurapoints.AuraPoints.STORMCALLER_GLAIVE;
			case FROST -> com.toast.aurapoints.AuraPoints.ABSOLUTE_ZERO;
			case VERDANT -> com.toast.aurapoints.AuraPoints.WORLD_TREE_BOW;
			case THUNDER -> com.toast.aurapoints.AuraPoints.ZEUS_GAUNTLETS;
			case RESONANCE, EARTH, LIGHT, BLOOD -> null;
		});
	}

	/** /aura lock <players> / /aura unlock <players>: switch their aura off (no actives, no passives) or back on. */
	private static int lock(CommandContext<CommandSourceStack> ctx, boolean locked) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
		AuraState state = AuraState.get(ctx.getSource().getServer());
		for (ServerPlayer p : targets) {
			state.getOrCreate(p.getUUID()).locked = locked;
			com.toast.aurapoints.network.NetworkHandler.syncDisabled(p);
			p.sendSystemMessage(Component.literal(locked ? "Your aura has been switched off by an admin." : "Your aura is back on.")
					.withStyle(locked ? ChatFormatting.RED : ChatFormatting.GREEN));
		}
		state.markDirty();
		int n = targets.size();
		ctx.getSource().sendSuccess(() -> Component.literal((locked ? "Locked" : "Unlocked") + " the aura of " + n + " player" + (n == 1 ? "" : "s")
				+ (!locked && AuraAbilities.allLocked ? " (but /aura lock all is still on)" : ".")), true);
		return n;
	}

	/** /aura lock all / /aura unlock all: every player's aura off or on (per-player locks stay as they are). */
	private static int lockAll(CommandContext<CommandSourceStack> ctx, boolean locked) {
		var server = ctx.getSource().getServer();
		AuraState state = AuraState.get(server);
		state.allLocked = locked;
		AuraAbilities.allLocked = locked;
		state.markDirty();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			com.toast.aurapoints.network.NetworkHandler.syncDisabled(p);
			p.sendSystemMessage(Component.literal(locked ? "Auras have been switched off for everyone." : "Auras are back on.")
					.withStyle(locked ? ChatFormatting.RED : ChatFormatting.GREEN));
		}
		ctx.getSource().sendSuccess(() -> Component.literal(locked ? "Every aura is now locked." : "Auras unlocked for everyone"
				+ (locked ? "" : " (players locked one by one stay locked)")), true);
		return 1;
	}

	/** /aura lock: who is locked right now. */
	private static int showLocks(CommandContext<CommandSourceStack> ctx) {
		var server = ctx.getSource().getServer();
		AuraState state = AuraState.get(server);
		List<String> names = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (state.getOrCreate(p.getUUID()).locked) names.add(p.getGameProfile().getName());
		ctx.getSource().sendSuccess(() -> Component.literal("All auras locked: " + (state.allLocked ? "yes" : "no")
				+ ". Locked players online: " + (names.isEmpty() ? "none" : String.join(", ", names))
				+ ". Use /aura lock <players|all> and /aura unlock <players|all>."), false);
		return 1;
	}

	private static int setCooldowns(CommandContext<CommandSourceStack> ctx, boolean on) {
		var server = ctx.getSource().getServer();
		AuraState state = AuraState.get(server);
		state.cooldownsDisabled = !on;
		state.markDirty();
		AuraAbilities.clearAllCooldowns();
		// Re-sending the aura clears every HUD's running cooldowns.
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			com.toast.aurapoints.network.NetworkHandler.syncAura(p, state.getOrCreate(p.getUUID()));
		}
		server.getPlayerList().broadcastSystemMessage(Component.literal(on
				? "Ability cooldowns are back ON."
				: "Ability cooldowns are OFF for everyone - spam away! (Aura Trials progress is paused meanwhile.)").withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	private static int giveAura(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
		int amount = IntegerArgumentType.getInteger(ctx, "amount");
		for (ServerPlayer target : targets) {
			AuraCurrency.add(target, amount);
		}
		ctx.getSource().sendSuccess(() -> Component.literal(
				"Gave " + amount + " Aura to " + targets.size() + " player(s)."), true);
		return targets.size();
	}

	private static int setAura(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
		AuraType type = AuraType.byId(StringArgumentType.getString(ctx, "aura"));
		if (type == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown aura."));
			return 0;
		}
		AuraState state = AuraState.get(ctx.getSource().getServer());
		for (ServerPlayer target : targets) {
			AuraPlayerData data = state.getOrCreate(target.getUUID());
			data.aura = type;
			data.arenaRealAura = null;
			data.rolled.add(type);
			data.freeRollUsed = true;
			for (AuraAbilities.Ability a : type.abilities) if (a.active) data.owned.add(a); // admin tool: no shopping
			com.toast.aurapoints.network.NetworkHandler.syncOwned(target, data);
			AuraAbilities.refreshBerserkerModifier(target, AuraAbilities.hasAbility(data, AuraAbilities.Ability.BERSERKER));
			AuraStats.grantAuraAdvancement(target, data);
			com.toast.aurapoints.network.NetworkHandler.syncAura(target, data);
		}
		state.markDirty();
		ctx.getSource().sendSuccess(() -> Component.literal(
				"Set the " + type.displayName + " aura for " + targets.size() + " player(s)."), true);
		return targets.size();
	}
}
