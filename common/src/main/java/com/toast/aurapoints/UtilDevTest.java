package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Branch;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.ability.Combos;
import com.toast.aurapoints.ability.VoidAura;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;
import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev utiltest - the 5.25 utility additions, each with a fake player 12 blocks
 * apart: Forge (Flame Cloak smelts dropped items), Frost Path putting a fire out, Static Rush driving a minecart (its tick by hand),
 * Lifeline on a pet, the Rift Anchor (set, return, too far). Logs [utiltest] lines with "(must be ...)".
 */
public final class UtilDevTest {

	private UtilDevTest() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("utiltest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			AuraState state = AuraState.get(server);
			boolean cooldownsBefore = state.cooldownsDisabled;
			state.cooldownsDisabled = true;
			long now = server.getTickCount();

			// Fire: 24 smeltable items by a cloaked player, the cloak's budget is 16
			ServerPlayer fire = fake(state, level, 0, "UtilFire", AuraType.FIRE);
			drop(level, fire, Items.RAW_IRON, 3);
			drop(level, fire, Items.BEEF, 1);
			drop(level, fire, Items.COBBLESTONE, 20);
			log("Flame Cloak fired " + AuraAbilities.tryActivate(fire, Ability.FIRE_BENDING, now, CastMode.SNEAK) + " (must be true)");

			// Frost: a fire next to the path walker
			ServerPlayer frost = fake(state, level, 1, "UtilFrost", AuraType.FROST);
			BlockPos flame = frost.blockPosition().east();
			level.setBlockAndUpdate(flame.below(), Blocks.STONE.defaultBlockState());
			level.setBlockAndUpdate(flame, Blocks.FIRE.defaultBlockState());
			frost.igniteForSeconds(5);
			log("fire placed: " + level.getBlockState(flame).is(Blocks.FIRE) + " (must be true), Frost Path fired "
					+ AuraAbilities.tryActivate(frost, Ability.ICE_SPIKE, now, CastMode.SNEAK) + " (must be true)");

			// Thunder: in a minecart on the ground
			ServerPlayer thunder = fake(state, level, 2, "UtilThunder", AuraType.THUNDER);
			Minecart cart = new Minecart(level, thunder.getX(), thunder.getY(), thunder.getZ());
			level.addFreshEntity(cart);
			double cartX = cart.getX(), cartZ = cart.getZ();
			log("Static Rush fired " + AuraAbilities.tryActivate(thunder, Ability.THUNDERBOLT, now, CastMode.SNEAK) + " (must be true)");
			// a fake player can't ride: the rush's vehicle tick is run on the cart by hand for 20 ticks
			for (int t = 1; t <= 20; t++) AuraFx.later(server, t, () -> com.toast.aurapoints.ability.Casts.drive(thunder, cart, 0, server.getTickCount()));

			// Blood: its own wolf 3 blocks ahead (yaw 0 looks along +z)
			ServerPlayer blood = fake(state, level, 3, "UtilBlood", AuraType.BLOOD);
			Wolf wolf = EntityType.WOLF.create(level);
			wolf.moveTo(blood.getX(), blood.getY(), blood.getZ() + 3, 0, 0);
			wolf.setNoAi(true);
			wolf.setTame(true, false);
			wolf.setOwnerUUID(blood.getUUID());
			level.addFreshEntity(wolf);
			blood.moveTo(blood.getX(), blood.getY(), blood.getZ(), 0, 15);
			boolean lifeline = AuraAbilities.tryActivate(blood, Ability.BLOOD_TETHER, now, CastMode.SNEAK);
			log("Lifeline on your wolf fired " + lifeline + ", wolf regenerating " + wolf.hasEffect(MobEffects.REGENERATION) + " (must be true true)");
			boolean pets = AuraConfig.BLOOD_LIFELINE_PETS;
			AuraConfig.BLOOD_LIFELINE_PETS = false;
			log("with BLOOD_LIFELINE_PETS off: fired " + AuraAbilities.tryActivate(blood, Ability.BLOOD_TETHER, now, CastMode.SNEAK) + " (must be false)");
			AuraConfig.BLOOD_LIFELINE_PETS = pets;

			// Dark: anchor, return, too far
			ServerPlayer dark = fake(state, level, 4, "UtilDark", AuraType.SHADOW);
			AuraPlayerData darkData = state.getOrCreate(dark.getUUID());
			darkData.branches.put(AuraType.SHADOW, Branch.DARK);
			darkData.owned.addAll(Branch.DARK.abilities());
			log("Dark's Rift Step has a sneak cast: " + CastMode.unlocked(dark, Ability.RIFT_STEP, CastMode.SNEAK) + " (must be true)");
			boolean set = AuraAbilities.tryActivate(dark, Ability.RIFT_STEP, now, CastMode.SNEAK);
			log("Rift Anchor set: fired " + set + " anchor " + VoidAura.hasAnchor(dark) + " (must be true true)");
			dark.moveTo(dark.getX() + 5, dark.getY(), dark.getZ(), 0, 0);
			boolean back = AuraAbilities.tryActivate(dark, Ability.RIFT_STEP, now, CastMode.SNEAK);
			log("return from 5 blocks: fired " + back + " anchor left " + VoidAura.hasAnchor(dark) + " (must be true false)");
			AuraAbilities.tryActivate(dark, Ability.RIFT_STEP, now, CastMode.SNEAK);
			dark.moveTo(dark.getX() + AuraConfig.VOID_ANCHOR_RANGE + 20, dark.getY(), dark.getZ(), 0, 0);
			boolean far = AuraAbilities.tryActivate(dark, Ability.RIFT_STEP, now, CastMode.SNEAK);
			log("return from beyond the range: fired " + far + " anchor left " + VoidAura.hasAnchor(dark) + " (must be false true)");

			AuraFx.later(server, 30, () -> {
				int ingots = count(level, fire, Items.IRON_INGOT), beef = count(level, fire, Items.COOKED_BEEF), stone = count(level, fire, Items.STONE);
				int raw = count(level, fire, Items.RAW_IRON) + count(level, fire, Items.BEEF) + count(level, fire, Items.COBBLESTONE);
				log("Forge after 30 ticks: " + ingots + " iron ingots, " + beef + " cooked beef, " + stone + " stone = " + (ingots + beef + stone)
						+ " smelted, " + raw + " left raw (must be " + AuraConfig.FIRE_BENDING_CLOAK_SMELT_ITEMS + " smelted, " + (24 - AuraConfig.FIRE_BENDING_CLOAK_SMELT_ITEMS) + " left)");
				log("Frost Path: fire still there " + level.getBlockState(flame).is(Blocks.FIRE) + ", walker burning " + (frost.getRemainingFireTicks() > 0) + " (must be false false)");
				double rolled = Math.hypot(cart.getX() - cartX, cart.getZ() - cartZ);
				log(String.format(Locale.ROOT, "Static Rush: the cart rolled %.2f blocks, over half a block %s (must be true)", rolled, rolled > 0.5));
				// clean up
				for (ItemEntity i : level.getEntitiesOfClass(ItemEntity.class, fire.getBoundingBox().inflate(6))) i.discard();
				level.setBlockAndUpdate(flame, Blocks.AIR.defaultBlockState());
				cart.discard();
				wolf.discard();
				darkData.branches.remove(AuraType.SHADOW);
				for (ServerPlayer p : new ServerPlayer[]{fire, frost, thunder, blood, dark}) {
					com.toast.aurapoints.ability.Casts.onLogout(p);
					VoidAura.onLogout(p);
					Combos.TEST_PLAYERS.remove(p.getUUID());
				}
				state.cooldownsDisabled = cooldownsBefore;
				log("utiltest done");
			});
			return 1;
		})));
	}

	/** A fake player with this aura at Mastery V, {@code n} x 12 blocks east of the spawn, on the ground, looking along +z. */
	private static ServerPlayer fake(AuraState state, ServerLevel level, int n, String name, AuraType aura) {
		ServerPlayer fake = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c2" + (10 + n)), name));
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.offset(12 + n * 12, 0, 0));
		fake.moveTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, 0, 0);
		fake.clearFire();
		AuraPlayerData data = state.getOrCreate(fake.getUUID());
		data.aura = aura;
		data.rolled.add(aura);
		data.owned.addAll(aura.abilities);
		data.mastery.put(aura, 5);
		data.branches.remove(aura);
		// the cast ticks find their player by UUID
		Combos.TEST_PLAYERS.put(fake.getUUID(), fake);
		return fake;
	}

	private static void drop(ServerLevel level, ServerPlayer by, Item item, int n) {
		ItemEntity e = new ItemEntity(level, by.getX() + 1, by.getY() + 0.2, by.getZ(), new ItemStack(item, n));
		e.setDeltaMovement(0, 0, 0);
		level.addFreshEntity(e);
	}

	private static int count(ServerLevel level, ServerPlayer near, Item item) {
		int n = 0;
		for (ItemEntity i : level.getEntitiesOfClass(ItemEntity.class, near.getBoundingBox().inflate(6))) if (i.getItem().is(item)) n += i.getItem().getCount();
		return n;
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[utiltest] {}", what);
	}
}
