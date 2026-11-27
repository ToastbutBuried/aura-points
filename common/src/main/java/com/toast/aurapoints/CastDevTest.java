package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AbyssalBubble;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.ability.FireBending;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): the 5.21 features, with fake players so they run over RCON.
 * <ul>
 * <li>/auradev casttest - a fake Fire, Water and Shadow player (Mastery V, every active owned) fires each cast of its
 * key abilities (sneak and charged first, then the normal one), and the Mastery gates are checked.</li>
 * <li>/auradev switchtest - the roll pool (never an aura you had) and the /aura switch rules (had it, not the current
 * one, not mid-arena, costs SWITCH_COST).</li>
 * </ul>
 * Both log [casttest] lines with "(must be ...)".
 */
public final class CastDevTest {

	private CastDevTest() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("casttest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			AuraState state = AuraState.get(server);
			boolean cooldownsBefore = state.cooldownsDisabled;
			state.cooldownsDisabled = true;
			try {
				int i = 0;
				for (AuraType aura : new AuraType[]{AuraType.FIRE, AuraType.WATER, AuraType.SHADOW, AuraType.WIND, AuraType.FROST, AuraType.THUNDER, AuraType.VERDANT,
						AuraType.EARTH, AuraType.LIGHT, AuraType.BLOOD, AuraType.MAGE}) {
					ServerPlayer fake = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c0" + (10 + i++)), "Cast" + aura.displayName));
					var spawn = level.getSharedSpawnPos();
					// looking a little down: the root casts want ground under the crosshair
					fake.moveTo(spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, 0, 40);
					AuraPlayerData data = state.getOrCreate(fake.getUUID());
					data.aura = aura;
					data.rolled.add(aura);
					data.owned.addAll(aura.abilities);
					Ability first = aura.slot(0);
					// the Mage casts need the wand in hand
					if (aura == AuraType.MAGE) fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
							new net.minecraft.world.item.ItemStack(com.toast.aurapoints.AuraPoints.APPRENTICE_WAND.get()));
					// the Mastery gates
					data.mastery.put(aura, 1);
					log(aura + " at Mastery I: sneak unlocked " + CastMode.unlocked(fake, first, CastMode.SNEAK) + " (must be false)");
					data.mastery.put(aura, AuraConfig.CAST_SNEAK_MASTERY);
					log(aura + " at Mastery " + AuraConfig.CAST_SNEAK_MASTERY + ": sneak " + CastMode.unlocked(fake, first, CastMode.SNEAK)
							+ " charged " + CastMode.unlocked(fake, first, CastMode.CHARGED) + " (must be " + CastMode.has(first, CastMode.SNEAK) + " false)");
					data.mastery.put(aura, 5);
					log(aura + " at Mastery V: charged " + CastMode.unlocked(fake, first, CastMode.CHARGED) + " (must be " + CastMode.has(first, CastMode.CHARGED) + ")");
					// Chidori refuses while a Thunderbolt combo runs, so it goes first
					java.util.List<Ability> order = new java.util.ArrayList<>(aura.keySlots());
					if (aura == AuraType.THUNDER) java.util.Collections.reverse(order);
					for (Ability ability : order) {
						// sneak and charged first: a normal Fire Bending press leaves a flame in hand, which a later sneak press would shoot instead
						for (CastMode mode : new CastMode[]{CastMode.SNEAK, CastMode.CHARGED, CastMode.TAP}) {
							if (!CastMode.has(ability, mode)) continue;
							// a Wind player's Dash key never charges in play (WindLaunch holds it)
							if (aura == AuraType.WIND && ability == Ability.DASH && mode == CastMode.CHARGED) continue;
							// the charged press picked a boulder up; a tap now would throw it, but only 8 ticks later
							if (ability == Ability.BOULDER_TOSS && mode == CastMode.TAP && com.toast.aurapoints.ability.Earth.isHolding(fake)) {
								log(aura + " BOULDER_TOSS TAP skipped: the Great Boulder is held (a tap throws it after 8 ticks)");
								continue;
							}
							// the Transfusion and Lifeline need a bleeding enemy / an ally in sight: refused here, which is the right answer
							boolean needsOther = aura == AuraType.BLOOD && mode == CastMode.SNEAK && ability != Ability.CRIMSON_SACRIFICE
									|| ability == Ability.BLOOD_TETHER || ability == Ability.PRISM_BEAM && mode == CastMode.SNEAK;
							if (aura == AuraType.MAGE) com.toast.aurapoints.ability.Mage.devReady(fake);
							boolean fired = AuraAbilities.tryActivate(fake, ability, server.getTickCount(), mode);
							log(aura + " " + ability + " " + mode + " fired=" + fired + (needsOther ? " (must be false: nobody to target)" : " (must be true)"));
							if (ability == Ability.BOULDER_TOSS && mode == CastMode.SNEAK) {
								log("Rubble Spray: rocks in flight " + com.toast.aurapoints.ability.Earth.rubbleInFlight() + " (must be " + AuraConfig.EARTH_RUBBLE_COUNT
										+ "), a boulder held " + com.toast.aurapoints.ability.Earth.isHolding(fake) + " (must be false)");
							}
							// the keys that hold: let go again; the Blood lunge/tether refuse a new press while they run
							if (ability == Ability.ABYSSAL_BUBBLE) AbyssalBubble.release(fake, server.getTickCount());
							if (ability == Ability.ICE_SPIKE) com.toast.aurapoints.ability.IceSpike.release(fake, server.getTickCount());
							if (ability == Ability.CHIDORI) com.toast.aurapoints.ability.Chidori.release(fake, server.getTickCount());
							if (ability == Ability.PRISM_BEAM) com.toast.aurapoints.ability.Light.beamRelease(fake, server.getTickCount());
							if (aura == AuraType.BLOOD) com.toast.aurapoints.ability.Blood.onLogout(fake);
							if (ability == Ability.ARCANE_BOLT) com.toast.aurapoints.ability.Mage.boltRelease(fake, server.getTickCount());
							if (ability == Ability.ARCANE_RAY) com.toast.aurapoints.ability.Mage.rayRelease(fake, server.getTickCount());
							if (ability == Ability.ARCANE_ORB) com.toast.aurapoints.ability.Mage.orbRelease(fake, server.getTickCount());
						}
					}
					if (FireBending.isHolding(fake)) FireBending.onLogout(fake);
					if (com.toast.aurapoints.ability.VerdantViper.isSteering(fake)) com.toast.aurapoints.ability.VerdantViper.press(fake, server.getTickCount() + 10000);
					if (aura == AuraType.EARTH) com.toast.aurapoints.ability.Earth.onLogout(fake);
				}
				log("an ability without variants keeps its normal cast only: LISTENING_TREE sneak has=" + CastMode.has(Ability.LISTENING_TREE, CastMode.SNEAK)
						+ " charged has=" + CastMode.has(Ability.LISTENING_TREE, CastMode.CHARGED) + " (must be false false)");
				log("hold abilities never charge: ABYSSAL_BUBBLE charged has=" + CastMode.has(Ability.ABYSSAL_BUBBLE, CastMode.CHARGED) + " (must be false)");
			} finally {
				state.cooldownsDisabled = cooldownsBefore;
			}
			log("casttest done");
			return 1;
		})));

		dispatcher.register(Commands.literal("auradev").then(Commands.literal("switchtest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			AuraState state = AuraState.get(server);
			ServerPlayer fake = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c020"), "Switcher"));
			AuraPlayerData data = state.getOrCreate(fake.getUUID());
			data.aura = AuraType.SHADOW;
			data.rolled.clear();
			data.rolled.add(AuraType.SHADOW);
			data.rolled.add(AuraType.FIRE);
			data.freeRollUsed = true;
			data.arenaRealAura = null;
			data.mastery.put(AuraType.FIRE, 3);
			AuraCurrency.set(fake, 1000);

			// A command run from inside a running command is only queued until that one ends, so each step gets its own tick.
			AuraType[] rolledTo = new AuraType[1];
			java.util.List<Runnable> steps = java.util.List.of(
					() -> {
						run(server, fake, "aura switch fire");
						log("switch to Fire (had it): aura " + data.aura + " balance " + AuraCurrency.get(fake) + " Fire mastery " + data.mastery.get(AuraType.FIRE) + " (must be FIRE 850 3)");
					},
					() -> {
						run(server, fake, "aura switch water");
						log("switch to Water (never had): aura " + data.aura + " balance " + AuraCurrency.get(fake) + " (must be FIRE 850)");
					},
					() -> {
						run(server, fake, "aura switch fire");
						log("switch to the aura held: balance " + AuraCurrency.get(fake) + " (must be 850)");
					},
					() -> {
						data.arenaRealAura = AuraType.FIRE;
						run(server, fake, "aura switch shadow");
						log("switch during a Wave run: aura " + data.aura + " balance " + AuraCurrency.get(fake) + " (must be FIRE 850)");
						data.arenaRealAura = null;
					},
					() -> {
						for (AuraType t : AuraType.values()) if (t.rollable()) data.rolled.add(t);
						run(server, fake, "aura roll confirm");
						log("roll with every aura had: aura " + data.aura + " balance " + AuraCurrency.get(fake) + " (must be FIRE 850)");
					},
					() -> {
						data.rolled.clear();
						data.rolled.add(AuraType.FIRE);
						run(server, fake, "aura roll confirm");
						rolledTo[0] = data.aura;
						log("roll: aura " + rolledTo[0] + " (must not be FIRE) balance " + AuraCurrency.get(fake) + " (must be 350) rolled " + data.rolled + " (must hold both)");
					},
					() -> {
						AuraCurrency.set(fake, 1000);
						data.rolled.clear();
						data.rolled.add(AuraType.FIRE);
						data.rolled.add(rolledTo[0]);
						run(server, fake, "aura roll confirm");
						log("second roll: aura " + data.aura + " (must be neither FIRE nor " + rolledTo[0] + ") balance " + AuraCurrency.get(fake) + " (must be 500)");
					},
					() -> {
						run(server, fake, "aura switch fire");
						log("back to Fire after rolls: aura " + data.aura + " balance " + AuraCurrency.get(fake) + " (must be FIRE 350)");
						log("switchtest done");
					});
			for (int i = 0; i < steps.size(); i++) com.toast.aurapoints.ability.AuraFx.later(server, 2 + i * 2, steps.get(i));
			return 1;
		})));
	}

	/** Runs a command as the fake player; whatever the command answers is logged, since a fake player's chat goes nowhere. */
	private static void run(MinecraftServer server, ServerPlayer player, String command) {
		net.minecraft.commands.CommandSource sink = new net.minecraft.commands.CommandSource() {
			@Override
			public void sendSystemMessage(net.minecraft.network.chat.Component message) {
				log("  '" + command + "' says: " + message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		CommandSourceStack source = new CommandSourceStack(sink, player.position(), player.getRotationVector(), player.serverLevel(), 2,
				player.getGameProfile().getName(), player.getDisplayName(), server, player);
		server.getCommands().performPrefixedCommand(source, command);
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[casttest] {}", what);
	}
}
