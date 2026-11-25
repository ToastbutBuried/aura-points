package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Gardening;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingIncomingDamageEvent;
import com.toast.aurapoints.shim.neoforge.event.level.BlockEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.EnumMap;
import java.util.UUID;

/**
 * DEV ONLY (aurapoints.devtest): /auradev garden then, a second later, /auradev garden2 - the Verdant farming passives
 * (Green Thumb, Well Fed, Thornless, 5.59.0) on a fake Verdant player. Logs [gardentest] lines with "(must be X)".
 * garden: the instant checks, and three crops (cancelled break, normal break, young crop); garden2 counts what dropped.
 */
final class GardenDevTest {

	private GardenDevTest() {
	}

	private static final UUID ID = UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c7a1");
	private static final int[] DX = {0, 4, 8};
	private static double chanceBefore;
	private static AuraType auraBefore;
	private static EnumMap<AuraType, Integer> masteryBefore;

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("garden").executes(ctx -> {
			run(ctx.getSource().getServer());
			return 1;
		})));
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("garden2").executes(ctx -> {
			finish(ctx.getSource().getServer());
			return 1;
		})));
	}

	private static BlockPos base(ServerLevel level) {
		BlockPos spawn = level.getSharedSpawnPos();
		return new BlockPos(spawn.getX() + 30, 120, spawn.getZ() + 30);
	}

	private static ServerPlayer player(ServerLevel level) {
		return FakePlayerFactory.get(level, new GameProfile(ID, "Gardener"));
	}

	private static void run(MinecraftServer server) {
		ServerLevel level = server.overworld();
		ServerPlayer p = player(level);
		var data = AuraState.get(server).getOrCreate(p.getUUID());
		auraBefore = data.aura;
		masteryBefore = new EnumMap<>(AuraType.class);
		masteryBefore.putAll(data.mastery);
		chanceBefore = AuraConfig.GREEN_THUMB_CHANCE;

		// ---- the kit
		data.aura = AuraType.VERDANT;
		data.mastery.put(AuraType.VERDANT, 4);
		log("Verdant has Green Thumb " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.GREEN_THUMB) + " (true), Well Fed "
				+ AuraAbilities.hasAbility(data, AuraAbilities.Ability.WELL_FED) + " (true), Thornless " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.THORNLESS) + " (true)");
		data.aura = AuraType.FIRE;
		log("Fire has Green Thumb " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.GREEN_THUMB) + " (false), Thornless "
				+ AuraAbilities.hasAbility(data, AuraAbilities.Ability.THORNLESS) + " (false)");
		data.aura = AuraType.VERDANT;

		// ---- Thornless
		LivingIncomingDamageEvent bush = new LivingIncomingDamageEvent(p, level.damageSources().sweetBerryBush(), 2.0f);
		boolean cancelled = Gardening.onIncomingDamage(bush);
		log("Thornless, Mastery IV, berry bush 2.0: amount " + bush.getAmount() + " (must be 1.0) cancelled " + cancelled + " (false)");
		LivingIncomingDamageEvent cactus = new LivingIncomingDamageEvent(p, level.damageSources().cactus(), 4.0f);
		Gardening.onIncomingDamage(cactus);
		log("Thornless, Mastery IV, cactus 4.0: amount " + cactus.getAmount() + " (must be 2.0)");
		LivingIncomingDamageEvent other = new LivingIncomingDamageEvent(p, level.damageSources().generic(), 4.0f);
		Gardening.onIncomingDamage(other);
		log("Thornless leaves other damage alone: " + other.getAmount() + " (must be 4.0)");
		data.mastery.put(AuraType.VERDANT, 5);
		LivingIncomingDamageEvent mastered = new LivingIncomingDamageEvent(p, level.damageSources().sweetBerryBush(), 2.0f);
		log("Thornless, Mastery V, berry bush: cancelled " + Gardening.onIncomingDamage(mastered) + " (must be true)");
		data.aura = AuraType.FIRE;
		LivingIncomingDamageEvent fire = new LivingIncomingDamageEvent(p, level.damageSources().cactus(), 4.0f);
		log("Thornless, a Fire player, cactus: cancelled " + Gardening.onIncomingDamage(fire) + " amount " + fire.getAmount() + " (must be false, 4.0)");
		data.aura = AuraType.VERDANT;
		data.mastery.put(AuraType.VERDANT, 4);

		// ---- Well Fed
		log("Well Fed, carrot at food 10 saturation 2: +" + Gardening.wellFedBonus(new ItemStack(Items.CARROT), 10, 2.0f) + " (must be 1.8)");
		log("Well Fed, apple at food 10 saturation 2: +" + Gardening.wellFedBonus(new ItemStack(Items.APPLE), 10, 2.0f) + " (must be 1.2)");
		log("Well Fed, carrot with the saturation nearly at the food level (9.5 of 10): +" + Gardening.wellFedBonus(new ItemStack(Items.CARROT), 10, 9.5f) + " (must be 0.5)");
		log("Well Fed, cooked beef: +" + Gardening.wellFedBonus(new ItemStack(Items.COOKED_BEEF), 10, 2.0f) + " (must be 0.0), cod +" + Gardening.wellFedBonus(new ItemStack(Items.COOKED_COD), 10, 2.0f)
				+ " (0.0), rotten flesh +" + Gardening.wellFedBonus(new ItemStack(Items.ROTTEN_FLESH), 10, 2.0f) + " (0.0), a stick +" + Gardening.wellFedBonus(new ItemStack(Items.STICK), 10, 2.0f) + " (0.0)");

		// the real thing: start eating, finish, and the per-tick detection (the same calls the server loop makes) adds the bonus
		log("Well Fed, really eating a carrot: saturation " + eat(level, p, data, Items.CARROT, true) + " (must be 5.4 = 3.6 + 1.8)");
		log("Well Fed, really eating cooked beef: saturation " + eat(level, p, data, Items.COOKED_BEEF, true) + " (must be 12.8: beef's own saturation, no Well Fed bonus for meat)");
		log("Well Fed, a bite stopped before the end: saturation " + eat(level, p, data, Items.CARROT, false) + " (must be 0.0)");

		// ---- Green Thumb: three crops in a row, each on farmland
		AuraConfig.GREEN_THUMB_CHANCE = 1.0;
		Gardening.testFakePlayers = true;
		BlockPos base = base(level);
		for (int i = 0; i < 3; i++) {
			BlockPos pos = base.east(DX[i]);
			level.setBlock(pos.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), 3);
			level.setBlock(pos, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, i == 2 ? 3 : 7), 3);
		}
		int before = Gardening.pending();
		for (int i = 0; i < 3; i++) {
			BlockPos pos = base.east(DX[i]);
			BlockState state = level.getBlockState(pos);
			Gardening.onBreak(new BlockEvent.BreakEvent(level, pos, state, p));
			if (i == 1) level.destroyBlock(pos, true, p); // the normal break; crop 0's break is "cancelled": the block stays; crop 2 is young
		}
		log("Green Thumb, chance 1.0: harvests waiting " + (Gardening.pending() - before) + " (must be 2: the cancelled one and the normal one; the young crop gives none)");
		log("now /auradev garden2 about a second later: wheat near crop 0 must be 0 (cancelled), crop 1 must be 2 (one normal roll + one extra), crop 2 must be 0");
	}

	/** Food level 10, saturation 0; starts using the item, runs Gardening.tick, finishes (or stops) the use, runs it again. Returns the saturation. */
	private static float eat(ServerLevel level, ServerPlayer p, com.toast.aurapoints.data.AuraPlayerData data, net.minecraft.world.item.Item item, boolean finish) {
		p.getFoodData().setFoodLevel(10);
		p.getFoodData().setSaturation(0f);
		ItemStack stack = new ItemStack(item);
		p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
		p.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
		Gardening.tick(p, data, level.getGameTime());
		if (finish) stack.finishUsingItem(level, p);
		p.stopUsingItem();
		Gardening.tick(p, data, level.getGameTime() + 1);
		float saturation = p.getFoodData().getSaturationLevel();
		p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		return saturation;
	}

	private static void finish(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos base = base(level);
		for (int i = 0; i < 3; i++) {
			BlockPos pos = base.east(DX[i]);
			int wheat = 0;
			for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0, 3.0, 2.0))) {
				if (e.getItem().is(Items.WHEAT)) wheat += e.getItem().getCount();
				e.discard();
			}
			String why = i == 0 ? "cancelled break, must be 0" : i == 1 ? "normal break + Green Thumb, must be 2" : "young crop, must be 0";
			log("crop " + i + ": wheat dropped " + wheat + " (" + why + ")");
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3);
		}
		log("harvests still waiting " + Gardening.pending() + " (must be 0)");
		AuraConfig.GREEN_THUMB_CHANCE = chanceBefore;
		Gardening.testFakePlayers = false;
		var data = AuraState.get(server).getOrCreate(player(level).getUUID());
		data.aura = auraBefore;
		data.mastery.clear();
		if (masteryBefore != null) data.mastery.putAll(masteryBefore);
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[gardentest] {}", what);
	}
}
