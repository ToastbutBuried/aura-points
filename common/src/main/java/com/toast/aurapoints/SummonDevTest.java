package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Summons;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev summontest - the Summon key (5.44, score only since 5.45.1). A fake Shadow
 * player presses and releases: aura_key_summon must follow, and the dev datapack "summontest" tag
 * #aurapoints:summon/shadow (adds 1 to summon_test) must NOT run any more. Logs [summontest] lines.
 */
public final class SummonDevTest {

	private SummonDevTest() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("summontest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			AuraState state = AuraState.get(server);
			ServerPlayer shadow = fake(server, 1, "SummonShadow");
			AuraPlayerData sd = state.getOrCreate(shadow.getUUID());
			sd.aura = AuraType.SHADOW;
			sd.locked = false;
			var board = server.getScoreboard();
			var testObj = board.getObjective("summon_test");
			var keyObj = board.getObjective(Summons.KEY_OBJECTIVE);
			if (testObj != null) board.getOrCreatePlayerScore(shadow, testObj).set(0);
			Summons.press(shadow, true);
			int held = board.getOrCreatePlayerScore(shadow, keyObj).get();
			Summons.press(shadow, false);
			int released = board.getOrCreatePlayerScore(shadow, keyObj).get();
			// functions called from inside a command run when it ends: read the test score a tick later
			com.toast.aurapoints.ability.AuraFx.later(server, 1, () -> {
				int ran = testObj == null ? 0 : board.getOrCreatePlayerScore(shadow, testObj).get();
				log("Shadow press: aura_key_summon held " + held + " released " + released + ", summon_test " + ran + " (must be 1, 0, 0: no function tag runs)");
				log("summontest done");
			});
			return 1;
		})));
	}

	private static ServerPlayer fake(MinecraftServer server, int n, String name) {
		return FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-0000000051" + n + "0"), name));
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[summontest] {}", what);
	}
}
