package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.event.entity.living.LivingIncomingDamageEvent;
import com.toast.aurapoints.shim.neoforge.event.level.BlockEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Verdant farming passives (ticket #17), next to Bloom:
 * <ul>
 * <li><b>Green Thumb</b>: breaking a fully grown crop has GREEN_THUMB_CHANCE to roll its loot a second time (Fortune counts).</li>
 * <li><b>Well Fed</b>: a plant food gives WELL_FED_SATURATION_BONUS more saturation (never above the food level).</li>
 * <li><b>Thornless</b>: sweet berry bushes and cacti hurt THORNLESS_DAMAGE_REDUCTION less, and not at all from
 * Verdant Mastery THORNLESS_IMMUNE_MASTERY on.</li>
 * </ul>
 * No key, no loader code: they use the break and incoming-damage events the mod already bridges and the per-player tick.
 */
public final class Gardening {

	private Gardening() {
	}

	/** A harvest that rolled its bonus: the break is only finished after the event, so it is looked at one tick later. */
	private record Pending(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, ItemStack tool, long at) {
	}

	/** What a player started eating: the item and the food state before, to tell a meal from a cancelled bite. */
	private record Meal(ItemStack stack, int food, float saturation) {
	}

	/** Test hook (GardenDevTest): lets a fake player harvest too. */
	public static boolean testFakePlayers;

	private static final List<Pending> PENDING = new ArrayList<>();
	private static final Map<UUID, Meal> MEALS = new HashMap<>();

	private static AuraPlayerData data(ServerPlayer player) {
		return AuraState.get(player.getServer()).getOrCreate(player.getUUID());
	}

	// ---------------------------------------------------------------
	// Green Thumb
	// ---------------------------------------------------------------

	/** A crop that gives its harvest now: a fully grown crop, nether wart or cocoa, or a melon or pumpkin block. */
	public static boolean isMatureCrop(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof CropBlock crop) return crop.isMaxAge(state);
		if (block instanceof NetherWartBlock) return state.getValue(NetherWartBlock.AGE) >= 3;
		if (block instanceof CocoaBlock) return state.getValue(CocoaBlock.AGE) >= 2;
		return state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN);
	}

	/** BlockEvent.BreakEvent (before the block is removed, and another mod may still cancel it). */
	public static void onBreak(BlockEvent.BreakEvent event) {
		// a real player only: machines (fake players) get nothing
		if (!(event.getPlayer() instanceof ServerPlayer player) || (player.getClass() != ServerPlayer.class && !testFakePlayers)) return;
		if (!(event.getLevel() instanceof ServerLevel level) || player.isCreative() || player.isSpectator()) return;
		BlockState state = event.getState();
		if (!isMatureCrop(state) || !AuraAbilities.hasAbility(data(player), Ability.GREEN_THUMB)) return;
		if (!level.mayInteract(player, event.getPos()) || player.getRandom().nextDouble() >= AuraConfig.GREEN_THUMB_CHANCE) return;
		PENDING.add(new Pending(level, event.getPos().immutable(), state, player, player.getMainHandItem().copy(), level.getGameTime()));
	}

	/** Every server tick: finishes the harvests that rolled a bonus. */
	public static void tickAll(MinecraftServer server) {
		if (PENDING.isEmpty()) return;
		for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
			Pending p = it.next();
			if (p.level().getGameTime() <= p.at()) continue; // the break itself finishes in the tick it started
			it.remove();
			BlockState now = p.level().getBlockState(p.pos());
			if (now.is(p.state().getBlock()) && isMatureCrop(now)) continue; // still standing: the break was cancelled
			for (ItemStack drop : Block.getDrops(p.state(), p.level(), p.pos(), null, p.player(), p.tool())) Block.popResource(p.level(), p.pos(), drop);
			AuraFx.particles(p.level(), ParticleTypes.HAPPY_VILLAGER, p.pos().getX() + 0.5, p.pos().getY() + 0.5, p.pos().getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.0);
			AuraStats.add(p.player(), AuraStats.GREEN_THUMB_EXTRA, 1);
		}
	}

	// ---------------------------------------------------------------
	// Well Fed
	// ---------------------------------------------------------------

	/** Food that grew: anything edible except meat and fish (rotten flesh counts as meat; spider eyes are no plant either). */
	public static boolean isPlantFood(ItemStack stack) {
		return stack.has(DataComponents.FOOD) && !stack.is(ItemTags.MEAT) && !stack.is(ItemTags.FISHES) && !stack.is(Items.SPIDER_EYE);
	}

	/** The extra saturation for a meal, 0 if it is no plant food. Never lifts the saturation above the food level. */
	public static float wellFedBonus(ItemStack stack, int foodLevel, float saturation) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null || !isPlantFood(stack)) return 0;
		float extra = (float) (food.saturation() * Math.max(0, AuraConfig.WELL_FED_SATURATION_BONUS));
		return Math.max(0, Math.min(extra, foodLevel - saturation));
	}

	/** Per player per tick (AuraAbilities.tickPassives): a meal is a use of a food item that ends with more food or saturation than it began with. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		UUID id = player.getUUID();
		if (!AuraAbilities.hasAbility(data, Ability.WELL_FED)) {
			if (!MEALS.isEmpty()) MEALS.remove(id);
			return;
		}
		FoodData food = player.getFoodData();
		if (player.isUsingItem()) {
			ItemStack using = player.getUseItem();
			if (using.has(DataComponents.FOOD) && !MEALS.containsKey(id)) MEALS.put(id, new Meal(using.copy(), food.getFoodLevel(), food.getSaturationLevel()));
			return;
		}
		Meal meal = MEALS.remove(id);
		if (meal == null || (food.getFoodLevel() <= meal.food() && food.getSaturationLevel() <= meal.saturation())) return; // nothing eaten
		float bonus = wellFedBonus(meal.stack(), food.getFoodLevel(), food.getSaturationLevel());
		if (bonus <= 0) return;
		food.setSaturation(food.getSaturationLevel() + bonus);
		AuraFx.particles(player.serverLevel(), ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 1.0, player.getZ(), 4, 0.3, 0.4, 0.3, 0.0);
		AuraStats.add(player, AuraStats.WELL_FED_MEALS, 1);
	}

	// ---------------------------------------------------------------
	// Thornless
	// ---------------------------------------------------------------

	/** Whether this player takes no damage from berry bushes and cacti (Verdant Mastery high enough). */
	public static boolean thornlessImmune(ServerPlayer player) {
		return Mastery.level(player) >= AuraConfig.THORNLESS_IMMUNE_MASTERY;
	}

	/** LivingIncomingDamageEvent: berry bush and cactus damage to a Thornless player. Gives no stat or mastery XP (standing in a bush would farm it). */
	public static boolean onIncomingDamage(LivingIncomingDamageEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return false;
		if (!event.getSource().is(DamageTypes.SWEET_BERRY_BUSH) && !event.getSource().is(DamageTypes.CACTUS)) return false;
		if (!AuraAbilities.hasAbility(data(player), Ability.THORNLESS)) return false;
		if (thornlessImmune(player)) {
			event.setCanceled(true);
			return true;
		}
		event.setAmount((float) (event.getAmount() * (1.0 - Mth.clamp(AuraConfig.THORNLESS_DAMAGE_REDUCTION, 0.0, 1.0))));
		return false;
	}

	// ---------------------------------------------------------------
	// Lifecycle
	// ---------------------------------------------------------------

	public static void onLogout(ServerPlayer player) {
		MEALS.remove(player.getUUID());
		PENDING.removeIf(p -> p.player() == player);
	}

	public static void clearAll() {
		PENDING.clear();
		MEALS.clear();
	}

	/** Test hook (dev tests): how many harvests wait for their bonus. */
	public static int pending() {
		return PENDING.size();
	}
}
