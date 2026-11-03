package com.toast.aurapoints.arena;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.toast.aurapoints.AuraCommand;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * /aura arena                              - to the Aura Arena (where you were is saved)
 * /aura arena leave                        - back where you came from
 * /aura arena lobby | ring | hall          - to the hub, the duel ring's rim, the Training Hall
 * /aura arena spectate                     - watch the duel that's on
 * /aura arena cooldowns on|off             - your own cooldowns in the training grounds (off by default)
 * /aura arena duel <player> [rounds] [wager], /aura arena duel accept|decline
 * /aura arena match create teams|ffa|koth, join, leave, start - matches for more than two (Match)
 * /aura arena duel stop, /aura arena match stop, /aura arena rebuild - owner only
 * /aura arena layout pillars|shattered|terraces|random - owner only: the ring layout every duel and match uses (RingLayout)
 * Shortcuts: /aura duel …, /aura match …; /aura leave gets you out of anything arena, from anywhere.
 */
public final class ArenaCommand {

	private ArenaCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> duel = Commands.literal("duel")
				.then(Commands.literal("accept").executes(ctx -> Duel.accept(ctx.getSource().getPlayerOrException())))
				.then(Commands.literal("decline").executes(ctx -> Duel.decline(ctx.getSource().getPlayerOrException())))
				.then(Commands.literal("stop").requires(ArenaCommand::owner).executes(ctx -> {
					int stopped = Duel.stop(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal(stopped > 0 ? "Duel stopped, wagers returned." : "No duel running."), false);
					return stopped;
				}))
				.then(Commands.argument("player", EntityArgument.player())
						.executes(ctx -> challenge(ctx, AuraConfig.DUEL_DEFAULT_ROUNDS, 0))
						.then(Commands.argument("rounds", IntegerArgumentType.integer(1, 99))
								.executes(ctx -> challenge(ctx, IntegerArgumentType.getInteger(ctx, "rounds"), 0))
								.then(Commands.argument("wager", IntegerArgumentType.integer(0))
										.executes(ctx -> challenge(ctx, IntegerArgumentType.getInteger(ctx, "rounds"), IntegerArgumentType.getInteger(ctx, "wager"))))));

		// only under /aura (no top-level /arena: one place for every command)
		LiteralCommandNode<CommandSourceStack> arena = Commands.literal("arena")
				.executes(ctx -> Arena.enter(ctx.getSource().getPlayerOrException()) ? 1 : 0)
				.then(Commands.literal("leave").executes(ArenaCommand::leave))
				.then(Commands.literal("lobby").executes(ctx -> go(ctx, Arena::toLobby)))
				.then(Commands.literal("ring").executes(ctx -> go(ctx, Arena::toRing)))
				.then(Commands.literal("hall").executes(ctx -> go(ctx, Arena::toHall)))
				.then(Commands.literal("spectate").executes(ctx -> Spectate.start(ctx.getSource().getPlayerOrException())))
				.then(Commands.literal("cooldowns")
						.executes(ArenaCommand::showCooldowns)
						.then(Commands.literal("on").executes(ctx -> cooldowns(ctx, true)))
						.then(Commands.literal("off").executes(ctx -> cooldowns(ctx, false))))
				.then(Commands.literal("rebuild").requires(ArenaCommand::owner).executes(ArenaCommand::rebuild))
				.then(Commands.literal("layout").requires(ArenaCommand::owner)
						.then(Commands.argument("layout", com.mojang.brigadier.arguments.StringArgumentType.word())
								.suggests((c, b) -> {
									for (RingLayout l : RingLayout.values()) b.suggest(l.id());
									b.suggest("random");
									return b.buildFuture();
								})
								.executes(ArenaCommand::layout)))
				.then(Commands.literal("match")
						.executes(ctx -> {
							NpcKind.matchMenu(ctx.getSource().getPlayerOrException());
							return 1;
						})
						.then(Commands.literal("create").then(Commands.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word())
								.suggests((c, b) -> {
									for (Match.Mode m : Match.Mode.values()) b.suggest(m.id());
									return b.buildFuture();
								})
								.executes(ctx -> {
									String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "mode");
									Match.Mode mode = Match.Mode.byId(id);
									if (mode == null) {
										ctx.getSource().sendFailure(Component.literal("Modes: teams, ffa, koth."));
										return 0;
									}
									return Match.create(ctx.getSource().getPlayerOrException(), mode);
								})))
						.then(Commands.literal("join").executes(ctx -> Match.join(ctx.getSource().getPlayerOrException())))
						.then(Commands.literal("leave").executes(ctx -> Match.leave(ctx.getSource().getPlayerOrException())))
						.then(Commands.literal("start").executes(ctx -> Match.start(ctx.getSource().getPlayerOrException())))
						.then(Commands.literal("aura").then(Commands.argument("aura", com.mojang.brigadier.arguments.StringArgumentType.word())
								.suggests((c, b) -> {
									if (c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
										var data = com.toast.aurapoints.data.AuraState.get(p.getServer()).getOrCreate(p.getUUID());
										for (var t : WaveAuras.unlocked(p, data)) b.suggest(t.id());
									}
									return b.buildFuture();
								})
								.executes(ctx -> Match.switchAura(ctx.getSource().getPlayerOrException(),
										com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "aura")))))
						.then(Commands.literal("stop").requires(ArenaCommand::owner).executes(ctx -> {
							int stopped = Match.stop(ctx.getSource().getServer());
							ctx.getSource().sendSuccess(() -> Component.literal(stopped > 0 ? "Match stopped." : "No match running."), false);
							return stopped;
						})))
				.then(duel).build();

		// brigadier merges this into the existing /aura tree; /aura duel and /aura match are shortcuts
		dispatcher.register(Commands.literal("aura")
				.then(arena)
				.then(Commands.literal("duel").redirect(arena.getChild("duel")))
				.then(Commands.literal("match").redirect(arena.getChild("match")))
				.then(Commands.literal("leave").executes(ctx -> leaveAnything(ctx.getSource().getPlayerOrException()))));
	}

	/** The owner in game, or the server console / rcon. */
	private static boolean owner(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		return player == null ? src.getEntity() == null && src.hasPermission(4) : AuraCommand.isOwner(player);
	}

	private static int challenge(CommandContext<CommandSourceStack> ctx, int rounds, int wager) throws CommandSyntaxException {
		return Duel.challenge(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"), rounds, wager);
	}

	/** /aura arena lobby, ring, hall: there, entering the arena first if need be (not mid-duel). */
	private static int go(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<ServerPlayer> where) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (Duel.isIn(player)) {
			ctx.getSource().sendFailure(Component.literal("Not in the middle of a duel!"));
			return 0;
		}
		if (!Arena.in(player) && !Arena.enter(player)) return 0;
		where.accept(player);
		return 1;
	}

	/**
	 * /aura leave: out of whatever arena thing you're in - any match or duel (a forfeit mid-game), spectating, the
	 * Training Hall - and back to your last spot outside the arena. Outside the arena it leaves a match lobby.
	 */
	static int leaveAnything(ServerPlayer player) {
		if (Arena.in(player)) {
			Arena.leave(player);
			return 1;
		}
		if (Match.signedUp(player)) return Match.leave(player);
		player.sendSystemMessage(Component.literal("You're not in the Aura Arena or any match.").withStyle(ChatFormatting.GRAY));
		return 0;
	}

	private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!Arena.in(player)) {
			ctx.getSource().sendFailure(Component.literal("You're not in the Aura Arena."));
			return 0;
		}
		Arena.leave(player);
		return 1;
	}

	private static int showCooldowns(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		boolean on = com.toast.aurapoints.data.AuraState.get(player.getServer()).getOrCreate(player.getUUID()).trainingCooldowns;
		ctx.getSource().sendSuccess(() -> Component.literal("Your cooldowns in the training grounds are " + (on ? "ON" : "OFF")
				+ ". Switch with /aura arena cooldowns on|off or the Cooldowns sign in the hall's gallery.").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int cooldowns(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
		Arena.setTrainingCooldowns(ctx.getSource().getPlayerOrException(), on);
		return 1;
	}

	/** /aura arena layout <id|random>: the next duels and matches use this ring layout (shown in the ring right away if it's free). */
	private static int layout(CommandContext<CommandSourceStack> ctx) {
		String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "layout");
		RingLayout layout = RingLayout.byId(id);
		if (layout == null && !id.equals("random")) {
			ctx.getSource().sendFailure(Component.literal("Layouts: pillars, shattered, terraces, random."));
			return 0;
		}
		RingLayout.forced = layout;
		ServerLevel level = Arena.level(ctx.getSource().getServer());
		if (layout != null && level != null && !Duel.running() && !Match.running()) {
			RingLayout.current = layout;
			ArenaBuilder.resetRing(level);
		}
		ctx.getSource().sendSuccess(() -> Component.literal(layout == null ? "Ring layouts: random again." : "Ring layout: " + layout.displayName + " for every duel and match."), true);
		return 1;
	}

	private static int rebuild(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = Arena.level(ctx.getSource().getServer());
		if (level == null) {
			ctx.getSource().sendFailure(Component.literal("The arena dimension isn't loaded."));
			return 0;
		}
		Duel.stop(ctx.getSource().getServer());
		Match.stop(ctx.getSource().getServer());
		ArenaBuilder.build(level);
		var state = com.toast.aurapoints.data.AuraState.get(ctx.getSource().getServer());
		state.arenaMapVersion = ArenaBuilder.MAP_VERSION;
		state.markDirty();
		ctx.getSource().sendSuccess(() -> Component.literal("Aura Arena rebuilt."), true);
		return 1;
	}
}
