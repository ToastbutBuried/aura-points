package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraHooks;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Summon key (5.44.0): one key per player for an aura's summon (a companion, a mount, a beast). Since 5.45.1 it
 * only sets the scoreboard {@code aura_key_summon} (1 while held, 0 otherwise) and runs nothing itself: the datapack
 * behind a summon checks who may summon (its owner, the aura, ...) and reads the score. Running function tags for
 * every holder of an aura let anyone of that aura summon another player's beast.
 */
public final class Summons {

	private Summons() {
	}

	public static final String KEY_OBJECTIVE = "aura_key_summon";

	/** A press or release of the Summon key: only the score. */
	public static void press(ServerPlayer player, boolean down) {
		if (player.getServer() == null) return;
		AuraHooks.setScore(player, KEY_OBJECTIVE, down ? 1 : 0);
	}
}
