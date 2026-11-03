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
								.executes(ctx -> awaken(ctx, true))
								.then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
										.executes(ctx -> awaken(ctx, com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "on"))))))));
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
