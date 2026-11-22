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
 * DEV ONLY (registered by DevTest): /auradev summontest - the Summon key (5.44). Needs the dev datapack "summontest"
 * (run/world/datapacks + run-fabric/world/datapacks): #aurapoints:summon/shadow adds 1 to summon_test. A fake Shadow
 * player presses and releases, a Fire player presses (no tag), a locked Shadow player presses. Logs [summontest] lines.
 */
public final class SummonDevTest {

	private SummonDevTest() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("summontest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			AuraState state = AuraState.get(server);
			ServerPlayer shadow = fake(server, 1, "SummonShadow");
			ServerPlayer fire = fake(server, 2, "SummonFire");
			AuraPlayerData sd = state.getOrCreate(shadow.getUUID());
			sd.aura = AuraType.SHADOW;
			sd.locked = false;
			state.getOrCreate(fire.getUUID()).aura = AuraType.FIRE;
			var board = server.getScoreboard();
			var testObj = board.getObjective("summon_test");
			if (testObj == null) {
				log("no summon_test objective: is the dev datapack 'summontest' loaded?");
				return 0;
			}
			var keyObj = board.getObjective(Summons.KEY_OBJECTIVE);
			board.getOrCreatePlayerScore(shadow, testObj).set(0);
			// functions called from inside a command (this test) run when the command ends: read the scores a tick later
			boolean ran = Summons.press(shadow, true);
			int held = board.getOrCreatePlayerScore(shadow, keyObj).get();
			Summons.press(shadow, false);
			int released = board.getOrCreatePlayerScore(shadow, keyObj).get();
			boolean fireRan = Summons.press(fire, true);
			Summons.press(fire, false);
			com.toast.aurapoints.ability.AuraFx.later(server, 1, () -> {
				log("Shadow press: ran " + ran + ", summon_test " + board.getOrCreatePlayerScore(shadow, testObj).get()
						+ ", aura_key_summon held " + held + " released " + released + " (must be true, 1, 1, 0)");
				log("Fire press: ran " + fireRan + " (must be false: no #aurapoints:summon/fire)");
				sd.locked = true;
				boolean lockedRan = Summons.press(shadow, true);
				Summons.press(shadow, false);
				sd.locked = false;
				com.toast.aurapoints.ability.AuraFx.later(server, 1, () -> {
					log("locked Shadow press: ran " + lockedRan + ", summon_test " + board.getOrCreatePlayerScore(shadow, testObj).get() + " (must be false, 1)");
					log("summontest done");
				});
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
