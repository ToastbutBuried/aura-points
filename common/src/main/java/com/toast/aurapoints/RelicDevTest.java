package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Relics;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev relictest - elemental relics (5.40). A fake Water player: worn relics are read
 * from the accessory slots (own aura and past the cap don't count), the combat echoes change damage as they say, a real
 * vanilla dungeon chest fill can hold a relic (chance forced to 1), buying takes Aura and gives a relic, and a loadout
 * relic set puts the right relics on.
 */
public final class RelicDevTest {

	private RelicDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[relictest] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("relictest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			ServerPlayer fake = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c640"), "RelicTest"));
			AuraPlayerData data = AuraState.get(server).getOrCreate(fake.getUUID());
			data.aura = AuraType.WATER;
			int slotsWere = AuraConfig.ACCESSORY_SLOTS;
			double chanceWas = AuraConfig.RELIC_CHEST_CHANCE;
			AuraConfig.ACCESSORY_SLOTS = Math.max(4, slotsWere);
			var acc = AccessorySlots.items(fake);
			for (int i = 0; i < acc.getSlots(); i++) acc.setStackInSlot(i, net.minecraft.world.item.ItemStack.EMPTY);
			fake.getInventory().clearContent();
			Vindicator zombie = null;
			try {
				acc.setStackInSlot(0, Relics.stack(AuraType.WATER));
				acc.setStackInSlot(1, Relics.stack(AuraType.FIRE));
				acc.setStackInSlot(2, Relics.stack(AuraType.VERDANT));
				acc.setStackInSlot(3, Relics.stack(AuraType.BLOOD));
				log("worn " + Relics.scan(fake) + " (must be [FIRE, VERDANT]: own aura skipped, cap " + Relics.maxEquipped() + ")");

				zombie = EntityType.VINDICATOR.create(level);
				zombie.moveTo(fake.getX(), fake.getY(), fake.getZ());
				Relics.devWear(fake, EnumSet.of(AuraType.FIRE, AuraType.VERDANT));
				zombie.setHealth(zombie.getMaxHealth());
				float healthy = Relics.attack(fake, zombie, 10f, true);
				zombie.setHealth(zombie.getMaxHealth() * 0.1f);
				float low = Relics.attack(fake, zombie, 10f, true);
				zombie.addEffect(new MobEffectInstance(MobEffects.POISON, 100));
				float poisonedLow = Relics.attack(fake, zombie, 10f, true);
				log("Ember+Thorn: healthy 10 -> " + healthy + " (must be 10.0), low " + low + " (must be " + (float) (10 * (1 + AuraConfig.RELIC_EMBER_BONUS)) + "), low+poisoned "
						+ poisonedLow + " (must be " + (float) (10 * (1 + AuraConfig.RELIC_EMBER_BONUS + AuraConfig.RELIC_THORN_BONUS)) + ")");
				Relics.devWear(fake, EnumSet.of(AuraType.THUNDER, AuraType.WIND));
				float bolt = Relics.defend(fake, level.damageSources().lightningBolt(), 8f);
				float fall = Relics.defend(fake, level.damageSources().fall(), 10f);
				log("Storm+Zephyr: lightning 8 -> " + bolt + " (must be 0.0), fall 10 -> " + fall + " (must be " + (float) (10 * (1 - AuraConfig.RELIC_ZEPHYR_FALL)) + ")");

				// a real chest fill from the vanilla dungeon table
				AuraConfig.RELIC_CHEST_CHANCE = 1.0;
				var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/simple_dungeon"));
				var table = server.reloadableRegistries().getLootTable(key);
				SimpleContainer chest = new SimpleContainer(27);
				table.fill(chest, new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, fake.position()).create(LootContextParamSets.CHEST), 42L);
				int relics = 0;
				for (int i = 0; i < chest.getContainerSize(); i++) if (Relics.auraOf(chest.getItem(i)) != null) relics++;
				log("dungeon chest filled: relics " + relics + " (must be 1)");

				int before = AuraCurrency.get(fake);
				AuraCurrency.add(fake, AuraConfig.RELIC_SHOP_COST);
				boolean bought = Relics.buy(fake);
				int found = 0;
				for (int k = 0; k < fake.getInventory().getContainerSize(); k++) if (Relics.auraOf(fake.getInventory().getItem(k)) != null) found++;
				log("buy: " + bought + " (must be true), Aura back to " + AuraCurrency.get(fake) + " (must be " + before + "), relics in the inventory " + found + " (must be 1)");

				// a set: the inventory has a Thunder relic; the set wants Thunder only
				fake.getInventory().add(Relics.stack(AuraType.THUNDER));
				int worn = Relics.applySet(fake, List.of(AuraType.THUNDER));
				log("set [THUNDER]: worn now " + Relics.worn(fake) + " (must be [THUNDER]), count " + worn + " (must be 1)");
			} finally {
				AuraConfig.ACCESSORY_SLOTS = slotsWere;
				AuraConfig.RELIC_CHEST_CHANCE = chanceWas;
				for (int i = 0; i < acc.getSlots(); i++) acc.setStackInSlot(i, net.minecraft.world.item.ItemStack.EMPTY);
				fake.getInventory().clearContent();
				Relics.forget(fake.getUUID());
				data.aura = null;
				if (zombie != null) zombie.discard();
			}
			log("relictest done");
			return 1;
		})));
	}
}
