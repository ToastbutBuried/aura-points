package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraHooks;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Summon key (5.44.0): one key per player for an aura's summon (a companion, a mount, a beast). The mod has no
 * summons of its own yet, so a press only hands off to datapacks: it runs {@code #aurapoints:summon/<aura>} (e.g.
 * #aurapoints:summon/shadow) as and at the player, then the aura-independent {@code #aurapoints:summon}. The scoreboard
 * {@code aura_key_summon} is 1 while the key is held. A press with no function behind it says so in the action bar.
 * A built-in summon would go here, ahead of the datapack tags.
 */
public final class Summons {

	private Summons() {
	}

	public static final String KEY_OBJECTIVE = "aura_key_summon";

	public static String tagPath(AuraType aura) {
		return "summon/" + aura.id();
	}

	/** A press or release of the Summon key. Returns true if a press reached a summon (some function ran). */
	public static boolean press(ServerPlayer player, boolean down) {
		MinecraftServer server = player.getServer();
		if (server == null) return false;
		AuraHooks.setScore(player, KEY_OBJECTIVE, down ? 1 : 0);
		if (!down) return false;
		AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
		if (data.aura == null) {
			message(player, "You need an aura to summon anything.", ChatFormatting.GRAY);
			return false;
		}
		if (data.locked || AuraAbilities.allLocked) {
			message(player, "Your aura is switched off.", ChatFormatting.RED);
			return false;
		}
		if (IceSpike.isFrozen(player) || Stun.isBusy(player)) return false;
		boolean ran = AuraHooks.runTagAny(player, tagPath(data.aura));
		ran |= AuraHooks.runTagAny(player, "summon");
		if (!ran) message(player, data.aura.displayName + " has nothing to summon yet.", ChatFormatting.GRAY);
		return ran;
	}

	private static void message(ServerPlayer player, String text, ChatFormatting color) {
		player.displayClientMessage(Component.literal(text).withStyle(color), true);
	}
}
