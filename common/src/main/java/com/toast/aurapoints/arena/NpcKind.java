package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The arena's NPCs: what each is called, wears and holds, the beam color of its pedestal, the
 * live line under its name, and what clicking it does.
 */
public enum NpcKind {
	TRAINING("Training Hall", 0x55FF55, Blocks.LIME_STAINED_GLASS, Items.BOW, 0x80C71F),
	SPECTATE("Spectate", 0x55FFFF, Blocks.LIGHT_BLUE_STAINED_GLASS, Items.SPYGLASS, 0x3AB3DA),
	DUELS("Duels", 0xFF5555, Blocks.RED_STAINED_GLASS, Items.IRON_SWORD, 0xB02E26),
	STATS("Your Stats", 0xFFFF55, Blocks.YELLOW_STAINED_GLASS, Items.GOLDEN_HELMET, 0xFED83D),
	LEAVE("Leave Arena", 0xFFFFFF, Blocks.WHITE_STAINED_GLASS, Items.ENDER_PEARL, 0xF9FFFE),
	RING_LOBBY("Back to Lobby", 0xFFAA00, null, Items.COMPASS, 0xF9801D),
	HALL_LOBBY("Back to Lobby", 0xFFAA00, null, Items.COMPASS, 0xF9801D);

	public final String label;
	public final int color;
	/** The beacon beam's glass on the lobby pedestal (null = no pedestal). */
	public final Block glass;
	public final Item held;
	/** The leather armor's dye. */
	public final int dye;

	NpcKind(String label, int color, Block glass, Item held, int dye) {
		this.label = label;
		this.color = color;
		this.glass = glass;
		this.held = held;
		this.dye = dye;
	}

	/** The lobby's NPCs, west to east along the arc (the ones with a pedestal). */
	static final NpcKind[] LOBBY = {TRAINING, SPECTATE, DUELS, STATS, LEAVE};

	public static NpcKind byId(int id) {
		NpcKind[] all = values();
		return id >= 0 && id < all.length ? all[id] : DUELS;
	}

	/** The line under the name, kept up to date by Holograms. */
	Component liveLine(ServerLevel level) {
		long inHall = level.players().stream().filter(Arena::inTraining).count();
		String text = switch (this) {
			case DUELS -> Duel.running() ? "⚔ " + Duel.describe() : "Challenge anyone!";
			case TRAINING -> inHall == 0 ? "Dummies, DPS, sparring" : inHall + " training now";
			case SPECTATE -> Duel.running() ? Spectate.count() + " watching " + Duel.describe() : "No duel right now";
			case STATS -> "Wins, losses, best DPS";
			case LEAVE -> "Back where you came from";
			case RING_LOBBY, HALL_LOBBY -> "Click to go back";
		};
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	/** The name and "click" lines over the NPC. */
	Component title() {
		return Component.literal(label).withStyle(s -> s.withColor(color).withBold(true))
				.append(Component.literal("\n▶ CLICK " + (this == DUELS || this == TRAINING ? "TO PLAY" : "") + " ◀").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
	}

	private static final Map<UUID, Long> LAST_CLICK = new HashMap<>();

	/** Clicked (either button). */
	public void use(ServerPlayer player, ArenaNpc npc) {
		long now = player.serverLevel().getGameTime();
		Long last = LAST_CLICK.get(player.getUUID());
		if (last != null && now - last < 10) return;
		LAST_CLICK.put(player.getUUID(), now);
		ServerLevel level = player.serverLevel();
		AuraFx.particles(level, ParticleTypes.HAPPY_VILLAGER, npc.getX(), npc.getY() + 1.2, npc.getZ(), 10, 0.4, 0.6, 0.4, 0);
		ArenaFx.sound(level, npc.position(), SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.6f);
		switch (this) {
			case DUELS -> duelMenu(player);
			case TRAINING -> Arena.toHall(player);
			case SPECTATE -> Spectate.start(player);
			case STATS -> stats(player);
			case LEAVE -> Arena.leave(player);
			case RING_LOBBY, HALL_LOBBY -> Arena.toLobby(player);
		}
		if (this == TRAINING || this == RING_LOBBY || this == HALL_LOBBY) {
			ArenaFx.sound(level, player.position(), SoundEvents.ENDERMAN_TELEPORT, 0.6f, 1.2f);
			AuraFx.particles(level, ParticleTypes.PORTAL, player.getX(), player.getY() + 1, player.getZ(), 40, 0.5, 1, 0.5, 0.3);
		}
	}

	/** The Duels NPC: who's around to challenge, and what's on. */
	private static void duelMenu(ServerPlayer player) {
		var server = player.getServer();
		player.sendSystemMessage(Component.literal("━━━━━━━━ ⚔ DUELS ⚔ ━━━━━━━━").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		if (Duel.running()) {
			player.sendSystemMessage(Component.literal("  Now: " + Duel.describe() + "  ").withStyle(ChatFormatting.GOLD)
					.append(button("[Watch]", ChatFormatting.AQUA, "/aura arena spectate", "Spectate this duel")));
		}
		int listed = 0;
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other == player) continue;
			String name = other.getGameProfile().getName();
			player.sendSystemMessage(Component.literal("  " + name + "  ").withStyle(ChatFormatting.YELLOW)
					.append(button("[Duel]", ChatFormatting.GREEN, "/aura arena duel " + name, "Challenge " + name + " (best of 3)"))
					.append(Component.literal(" "))
					.append(button("[Bo5]", ChatFormatting.DARK_GREEN, "/aura arena duel " + name + " 5", "Challenge " + name + " to 5 rounds")));
			listed++;
		}
		if (listed == 0) player.sendSystemMessage(Component.literal("  Nobody else is online right now.").withStyle(ChatFormatting.GRAY));
		player.sendSystemMessage(Component.literal("  ").append(button("[Visit the Ring]", ChatFormatting.LIGHT_PURPLE, "/aura arena ring", "Look around the duel ring"))
				.append(Component.literal("  Wager Aura: /aura arena duel <player> <rounds> <wager>").withStyle(ChatFormatting.DARK_GRAY)));
		matchMenu(player);
	}

	/** Matches for more than two: the one that's open (join it), or open one. */
	static void matchMenu(ServerPlayer player) {
		if (Match.open()) {
			player.sendSystemMessage(Component.literal("  Open: " + Match.describe() + "  ").withStyle(ChatFormatting.GOLD)
					.append(button("[Join]", ChatFormatting.GREEN, "/aura arena match join", "Join it"))
					.append(Component.literal(" "))
					.append(button("[Start]", ChatFormatting.DARK_GREEN, "/aura arena match start", "Start it (the host only)")));
			return;
		}
		if (Match.running() && Match.joinable()) {
			player.sendSystemMessage(Component.literal("  Under way: " + Match.describe() + "  ").withStyle(ChatFormatting.GOLD)
					.append(button("[Join]", ChatFormatting.GREEN, "/aura arena match join", "Step in during the next break between waves")));
			return;
		}
		player.sendSystemMessage(Component.literal("  Group matches: ").withStyle(ChatFormatting.YELLOW)
				.append(button("[2v2 Teams]", ChatFormatting.RED, "/aura arena match create teams", "Two teams (2v2 to 4v4), best of 3"))
				.append(Component.literal(" "))
				.append(button("[Free-for-All]", ChatFormatting.GOLD, "/aura arena match create ffa", "Everyone for themselves (3-8), best of 3"))
				.append(Component.literal(" "))
				.append(button("[King of the Hill]", ChatFormatting.AQUA, "/aura arena match create koth", "Hold the hill in the middle (2-8)"))
				.append(Component.literal(" "))
				.append(button("[Wave Survival]", ChatFormatting.DARK_RED, "/aura arena match create waves", "Together against endless waves of mobs (1-8, solo works)")));
		player.sendSystemMessage(Component.literal("  Wave Survival with a twist: ").withStyle(ChatFormatting.YELLOW)
				.append(button("[Swift]", ChatFormatting.AQUA, "/aura arena match create waves swift", hoverFor(com.toast.aurapoints.arena.Waves.Modifier.SWIFT)))
				.append(Component.literal(" "))
				.append(button("[Brutal]", ChatFormatting.RED, "/aura arena match create waves brutal", hoverFor(com.toast.aurapoints.arena.Waves.Modifier.BRUTAL)))
				.append(Component.literal(" "))
				.append(button("[Tough]", ChatFormatting.GOLD, "/aura arena match create waves tough", hoverFor(com.toast.aurapoints.arena.Waves.Modifier.TOUGH)))
				.append(Component.literal(" "))
				.append(button("[Flow]", ChatFormatting.LIGHT_PURPLE, "/aura arena match create waves flow", hoverFor(com.toast.aurapoints.arena.Waves.Modifier.FLOW))));
	}

	private static String hoverFor(com.toast.aurapoints.arena.Waves.Modifier mod) {
		return mod.blurb + (mod.hard() ? " +" + Math.round(com.toast.aurapoints.config.AuraConfig.WAVES_MOD_AURA_BONUS * 100) + "% Aura per wave." : "") + " No records.";
	}

	private static void stats(ServerPlayer player) {
		AuraState state = AuraState.get(player.getServer());
		AuraState.ArenaRecord duel = state.duelRecords.get(player.getUUID()), dps = state.dpsRecords.get(player.getUUID());
		player.sendSystemMessage(Component.literal("━━━━━━━━ ★ YOUR STATS ★ ━━━━━━━━").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
		player.sendSystemMessage(Component.literal("  Duels: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal((duel == null ? 0 : duel.wins) + " wins").withStyle(ChatFormatting.GREEN))
				.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal((duel == null ? 0 : duel.losses) + " losses").withStyle(ChatFormatting.RED)));
		player.sendSystemMessage(Component.literal("  Best DPS challenge: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(dps == null || dps.best <= 0 ? "none yet" : String.format(java.util.Locale.ROOT, "%.1f", dps.best)).withStyle(ChatFormatting.GOLD)));
	}

	private static MutableComponent button(String text, ChatFormatting color, String command, String hover) {
		return Component.literal(text).withStyle(s -> s.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
	}

	static void clearAll() {
		LAST_CLICK.clear();
	}
}
