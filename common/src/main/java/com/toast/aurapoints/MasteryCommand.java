package com.toast.aurapoints;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Mastery;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;

/**
 * /aura mastery                                  - your Aura Mastery, aura by aura
 * /aura mastery set <players> <aura> <level>     - permission 2: what the FTB "Aura Mastery" quests run as a reward
 * /aura awaken <players> <aura> [true|false]     - permission 2: the Awakening quests' reward (unlocks the Ultimate)
 */
final class MasteryCommand {

	private MasteryCommand() {
	}

	private static final SuggestionProvider<CommandSourceStack> AURAS = (context, builder) -> {
		for (AuraType type : AuraType.values()) builder.suggest(type.id());
		return builder.buildFuture();
	};

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("aura").then(Commands.literal("mastery")
				.executes(ctx -> {
					Mastery.show(ctx.getSource().getPlayerOrException());
					return 1;
				})
				.then(Commands.literal("set").requires(src -> src.hasPermission(2))
						.then(Commands.argument("targets", EntityArgument.players())
								.then(Commands.argument("aura", StringArgumentType.word()).suggests(AURAS)
										.then(Commands.argument("level", IntegerArgumentType.integer(0, Mastery.MAX))
												.executes(MasteryCommand::set)))))));
		dispatcher.register(Commands.literal("aura").then(Commands.literal("awaken").requires(src -> src.hasPermission(2))
				.then(Commands.argument("targets", EntityArgument.players())
						.then(Commands.argument("aura", StringArgumentType.word()).suggests(AURAS)
								// the Awakening quests' reward: with the altar on it opens the ritual, else it awakens (an explicit true/false always sets the awakening itself)
								.executes(ctx -> com.toast.aurapoints.config.AuraConfig.ULT_ALTAR_ENABLED ? altarUnlock(ctx, true) : awaken(ctx, true))
								.then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
										.executes(ctx -> awaken(ctx, com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "on"))))))));
		// the Aura Trial: try the whole kit of the aura you hold in the Training Hall (retake any time)
		dispatcher.register(Commands.literal("aura").then(Commands.literal("trial")
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					if (com.toast.aurapoints.arena.AuraTrial.running(p)) com.toast.aurapoints.arena.AuraTrial.show(p);
					else com.toast.aurapoints.arena.AuraTrial.start(p);
					return 1;
				})
				.then(Commands.literal("start").executes(ctx -> {
					com.toast.aurapoints.arena.AuraTrial.start(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("stop").executes(ctx -> {
					com.toast.aurapoints.arena.AuraTrial.stop(ctx.getSource().getPlayerOrException(), "Aura Trial stopped.");
					return 1;
				}))));
		dispatcher.register(Commands.literal("aura").then(Commands.literal("altar")
				.executes(ctx -> {
					com.toast.aurapoints.arena.AltarRitual.menu(ctx.getSource().getPlayerOrException());
					return 1;
				})
				.then(Commands.literal("ritual").executes(ctx -> ritual(ctx, false)))
				.then(Commands.literal("replay").executes(ctx -> ritual(ctx, true)))
				.then(Commands.literal("skip").executes(ctx -> com.toast.aurapoints.arena.AltarRitual.skip(ctx.getSource().getPlayerOrException()) ? 1 : 0))
				.then(Commands.literal("watch").executes(ctx -> {
					com.toast.aurapoints.arena.AltarRitual.watch(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("lore")
						.executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							com.toast.aurapoints.arena.AltarRitual.lore(p, com.toast.aurapoints.data.AuraState.get(p.getServer()).getOrCreate(p.getUUID()).aura);
							return 1;
						})
						.then(Commands.argument("aura", StringArgumentType.word()).suggests(AURAS).executes(ctx -> {
							com.toast.aurapoints.arena.AltarRitual.lore(ctx.getSource().getPlayerOrException(),
									com.toast.aurapoints.ability.AuraType.byId(StringArgumentType.getString(ctx, "aura")));
							return 1;
						})))
				.then(Commands.literal("unlock").requires(src -> src.hasPermission(2))
						.then(Commands.argument("targets", EntityArgument.players())
								.then(Commands.argument("aura", StringArgumentType.word()).suggests(AURAS)
										.executes(ctx -> altarUnlock(ctx, true))
										.then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
												.executes(ctx -> altarUnlock(ctx, com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "on")))))))));
	}

	private static int ritual(CommandContext<CommandSourceStack> ctx, boolean replay) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String why = com.toast.aurapoints.arena.AltarRitual.begin(player, replay);
		if (why == null) return 1;
		ctx.getSource().sendFailure(Component.literal(why));
		return 0;
	}

	private static int altarUnlock(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
		AuraType aura = AuraType.byId(StringArgumentType.getString(ctx, "aura"));
		if (aura == null || com.toast.aurapoints.ability.Ultimates.of(aura) == null) {
			ctx.getSource().sendFailure(Component.literal(aura == null ? "Unknown aura." : aura.displayName + " has no Ultimate (yet)."));
			return 0;
		}
		for (ServerPlayer target : targets) com.toast.aurapoints.arena.AltarRitual.unlock(target, aura, on);
		ctx.getSource().sendSuccess(() -> Component.literal("The altar is " + (on ? "open" : "closed") + " for " + aura.displayName + " to " + targets.size() + " player(s)."), true);
		return targets.size();
	}


	private static int awaken(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
		AuraType aura = AuraType.byId(StringArgumentType.getString(ctx, "aura"));
		if (aura == null || com.toast.aurapoints.ability.Ultimates.of(aura) == null) {
			ctx.getSource().sendFailure(Component.literal(aura == null ? "Unknown aura." : aura.displayName + " has no Ultimate (yet)."));
			return 0;
		}
		for (ServerPlayer target : targets) com.toast.aurapoints.ability.Ultimates.awaken(target, aura, on);
		ctx.getSource().sendSuccess(() -> Component.literal(aura.displayName + (on ? " awakened" : " no longer awakened") + " for " + targets.size() + " player(s)."), true);
		return targets.size();
	}

	private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
		AuraType aura = AuraType.byId(StringArgumentType.getString(ctx, "aura"));
		int level = IntegerArgumentType.getInteger(ctx, "level");
		if (aura == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown aura."));
			return 0;
		}
		for (ServerPlayer target : targets) Mastery.set(target, aura, level);
		ctx.getSource().sendSuccess(() -> Component.literal(aura.displayName + " Mastery " + (level == 0 ? "cleared" : Mastery.roman(level))
				+ " for " + targets.size() + " player(s)."), true);
		return targets.size();
	}
}
