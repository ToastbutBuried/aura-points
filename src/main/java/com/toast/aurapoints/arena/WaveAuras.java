package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Mastery;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Wave Survival: after every boss wave the players may switch to any aura they have held before, for the rest of
 * the run. The real aura is kept in {@link AuraPlayerData#arenaRealAura} (saved, so a crash mid-run can't lose it)
 * and comes back when the run ends, they leave it, or on their next login. Match decides when switching is allowed.
 */
public final class WaveAuras {

	private WaveAuras() {
	}

	/**
	 * Auras this player has held: the ones recorded since 4.98 ({@code rolled}), plus older evidence - mastery XP
	 * or a level, an Awakening, or a bought active of that aura (rerolls keep those) - and the real aura itself.
	 */
	public static List<AuraType> unlocked(ServerPlayer player, AuraPlayerData data) {
		AuraType real = data.arenaRealAura != null ? data.arenaRealAura : data.aura;
		List<AuraType> out = new ArrayList<>();
		for (AuraType t : AuraType.values()) {
			boolean had = t == real || data.rolled.contains(t) || data.awakened.contains(t)
					|| data.mastery.getOrDefault(t, 0) > 0 || Mastery.xp(player, t) > 0;
			if (!had) for (AuraAbilities.Ability a : t.abilities) if (a.active && data.owned.contains(a)) had = true;
			if (had) out.add(t);
		}
		return out;
	}

	/** Switches for the run (back to the real aura = the borrowed one ends). Returns false if not allowed. */
	static boolean switchTo(ServerPlayer player, AuraType aura) {
		AuraState state = AuraState.get(player.getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		if (aura == null || data.aura == null || aura == data.aura || !unlocked(player, data).contains(aura)) return false;
		if (data.arenaRealAura == null) data.arenaRealAura = data.aura;
		data.aura = aura;
		if (data.arenaRealAura == aura) data.arenaRealAura = null;
		state.markDirty();
		refresh(player, data);
		Arena.resetCooldowns(player);
		return true;
	}

	/** The run is over for this player (finished, left, logged in after a crash): the real aura comes back. */
	public static void restore(ServerPlayer player) {
		if (player.getServer() == null) return;
		AuraState state = AuraState.get(player.getServer());
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		if (data.arenaRealAura == null) return;
		data.aura = data.arenaRealAura;
		data.arenaRealAura = null;
		state.markDirty();
		refresh(player, data);
		player.sendSystemMessage(Component.literal("You have your " + data.aura.displayName + " aura back.").withStyle(data.aura.color));
	}

	private static void refresh(ServerPlayer player, AuraPlayerData data) {
		AuraAbilities.refreshBerserkerModifier(player, AuraAbilities.hasAbility(data, AuraAbilities.Ability.BERSERKER));
		NetworkHandler.syncAura(player, data);
		// the HUD's Ultimate slot follows the aura (an awakened aura's Ultimate rides in the owned list)
		NetworkHandler.syncOwned(player, data);
	}

	/** The chat menu: one clickable button per held aura (the current one marked). */
	static Component menu(ServerPlayer player, int seconds) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		MutableComponent line = Component.literal("✦ Boss down! Switch aura for the run (" + seconds + "s): ").withStyle(ChatFormatting.GOLD);
		for (AuraType t : unlocked(player, data)) {
			boolean now = t == data.aura;
			line.append(Component.literal(now ? "[" + t.displayName + " ✔] " : "[" + t.displayName + "] ").withStyle(s -> s.withColor(t.color).withBold(now)
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura arena match aura " + t.id()))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(now ? "Your aura right now" : "Fight on as " + t.displayName)))));
		}
		return line;
	}
}
