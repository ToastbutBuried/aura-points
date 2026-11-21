package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.ability.Casts;
import com.toast.aurapoints.ability.CombatTag;
import com.toast.aurapoints.ability.Loadouts;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev loadouttest - loadouts (5.38). A fake Earth player: keys in the aura's order
 * at first, a swap moves the abilities (and the HUD wire), a cast swap only for an ability with a sneak cast, combat and
 * the switch cooldown block a preset switch, a new preset copies the current one, a preset that no longer fits the kit
 * falls back to the aura's order, and the presets survive a save and load.
 */
public final class LoadoutDevTest {

	private LoadoutDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[loadouttest] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("loadouttest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerPlayer fake = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c638"), "LoadoutTest"));
			AuraState state = AuraState.get(server);
			AuraPlayerData data = state.getOrCreate(fake.getUUID());
			data.aura = AuraType.EARTH;
			data.rolled.add(AuraType.EARTH);
			data.mastery.put(AuraType.EARTH, 5);
			data.branches.remove(AuraType.EARTH);
			data.loadouts.clear();
			data.activeLoadout.clear();
			CombatTag.forget(fake.getUUID());
			Loadouts.forget(fake.getUUID());
			try {
				List<Ability> base = AuraType.EARTH.keySlots();
				log("start: keys " + Loadouts.keySlots(data) + " (must be " + base + "), wire '" + Loadouts.wire(data) + "' (must be '')");

				boolean swapped = Loadouts.swapKeys(fake, 1, 2);
				log("swap 1+2: " + swapped + ", keys " + Loadouts.keySlots(data) + " (must be [" + base.get(1) + ", " + base.get(0) + ", " + base.get(2) + "]), key 1 = "
						+ Loadouts.slot(data, 0) + " (must be " + base.get(1) + "), wire '" + Loadouts.wire(data) + "'");

				int withSneak = -1, without = -1;
				List<Ability> keys = Loadouts.keySlots(data);
				for (int i = 0; i < keys.size(); i++) {
					if (Casts.variantName(keys.get(i), AuraType.EARTH, CastMode.SNEAK) != null) {
						if (withSneak < 0) withSneak = i;
					} else if (without < 0) without = i;
				}
				if (withSneak >= 0) {
					Loadouts.toggleCast(fake, withSneak + 1);
					log("cast swap on key " + (withSneak + 1) + " (" + keys.get(withSneak) + "): " + Loadouts.swapped(data, keys.get(withSneak)) + " (must be true), described '" + Loadouts.describeKeys(data) + "'");
				} else log("no Earth key with a sneak cast (must not happen)");
				if (without >= 0) log("cast swap on key " + (without + 1) + " with no sneak cast refused: " + !Loadouts.toggleCast(fake, without + 1) + " (must be true)");

				CombatTag.devMark(fake);
				log("in combat: blocker '" + Loadouts.blocker(fake, data) + "', switch refused " + !Loadouts.use(fake, 2) + " (must be true)");
				CombatTag.forget(fake.getUUID());
				boolean used = Loadouts.use(fake, 2);
				log("out of combat: switch to 2 " + used + " (must be true), active " + data.activeLoadout.get(AuraType.EARTH) + " (must be 1), presets "
						+ data.loadouts.get(AuraType.EARTH).size() + " (must be 2), copy keeps keys " + Loadouts.keySlots(data).equals(keys) + " (must be true)");
				log("switch back at once refused (cooldown): " + !Loadouts.use(fake, 1) + " (must be true)");

				// a save and load
				AuraPlayerData loaded = AuraPlayerData.readNbt(data.writeNbt());
				log("saved and loaded: presets " + loaded.loadouts.get(AuraType.EARTH).size() + " (must be 2), active " + loaded.activeLoadout.get(AuraType.EARTH)
						+ " (must be 1), same keys " + Loadouts.keySlots(loaded).equals(Loadouts.keySlots(data)) + " (must be true)");

				// a preset that no longer fits the kit (another branch, a changed kit) falls back
				var p = Loadouts.active(data);
				p.keys.clear();
				p.keys.add(Ability.DASH);
				log("misfit preset: keys " + Loadouts.keySlots(data) + " (must be " + base + "), wire '" + Loadouts.wire(data) + "' (must be '')");
			} finally {
				data.loadouts.clear();
				data.activeLoadout.clear();
				CombatTag.forget(fake.getUUID());
				Loadouts.forget(fake.getUUID());
				data.aura = null;
			}
			log("loadouttest done");
			return 1;
		})));
	}
}
