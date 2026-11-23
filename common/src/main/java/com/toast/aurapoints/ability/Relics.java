package com.toast.aurapoints.ability;

import com.toast.aurapoints.AccessorySlots;
import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.item.RelicItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Elemental relics (5.40.0): one item per aura (Resonance has none). Worn in an accessory slot, a relic of
 * another aura gives a small echo of that aura's passives; a relic of your own aura does nothing. At most
 * RELIC_MAX_EQUIPPED different ones count. Found in vanilla loot chests (mixin/LootTableMixin), bought or
 * rerolled with Aura (/aura relic), dropped by rifts. Loadout presets can remember a relic set.
 *
 * Echoes: Fire Ember (Executioner: more damage to the badly hurt), Water Tide (Iron Skin: less damage
 * taken from attacks), Wind Zephyr (Featherfall: softer falls), Frost Rime (Shatter: more damage to the
 * frozen or slowed), Verdant Thorn (Virulence: more damage to the poisoned), Thunder Storm (Stormborn:
 * lightning does nothing), Earth Bedrock (Grounded: knockback resistance), Light Halo (Halo: a little
 * healing now and then), Shadow Dusk (Nightstalker: faster in the dark), Blood Vein (lifesteal on melee).
 */
public final class Relics {

	private Relics() {
	}

	/** The relic auras, in aura order (Resonance has none). */
	public static final List<AuraType> AURAS = List.of(AuraType.FIRE, AuraType.WATER, AuraType.WIND, AuraType.FROST, AuraType.VERDANT,
			AuraType.THUNDER, AuraType.EARTH, AuraType.LIGHT, AuraType.SHADOW, AuraType.BLOOD);

	public static String name(AuraType aura) {
		return switch (aura) {
			case FIRE -> "Ember Relic";
			case WATER -> "Tide Relic";
			case WIND -> "Zephyr Relic";
			case FROST -> "Rime Relic";
			case VERDANT -> "Thorn Relic";
			case THUNDER -> "Storm Relic";
			case EARTH -> "Bedrock Relic";
			case LIGHT -> "Halo Relic";
			case SHADOW -> "Dusk Relic";
			case BLOOD -> "Vein Relic";
			default -> aura.displayName + " Relic";
		};
	}

	public static String describe(AuraType aura) {
		return switch (aura) {
			case FIRE -> "+" + pct(AuraConfig.RELIC_EMBER_BONUS) + "% damage to targets under " + pct(AuraConfig.RELIC_EMBER_HEALTH) + "% health";
			case WATER -> "-" + pct(AuraConfig.RELIC_TIDE_REDUCTION) + "% damage taken from attacks";
			case WIND -> "-" + pct(AuraConfig.RELIC_ZEPHYR_FALL) + "% fall damage";
			case FROST -> "+" + pct(AuraConfig.RELIC_RIME_BONUS) + "% damage to frozen or slowed targets";
			case VERDANT -> "+" + pct(AuraConfig.RELIC_THORN_BONUS) + "% damage to poisoned targets";
			case THUNDER -> "Lightning does no damage to you";
			case EARTH -> "+" + pct(AuraConfig.RELIC_BEDROCK_KNOCKBACK) + "% knockback resistance";
			case LIGHT -> "Heals " + AuraAbilities.fmt((float) AuraConfig.RELIC_HALO_HEAL) + " HP every " + AuraConfig.RELIC_HALO_SECONDS + " s";
			case SHADOW -> "+" + pct(AuraConfig.RELIC_DUSK_SPEED) + "% speed in the dark";
			case BLOOD -> "Melee hits heal " + pct(AuraConfig.RELIC_VEIN_LIFESTEAL) + "% of the damage";
			default -> "";
		};
	}

	private static long pct(double v) {
		return Math.round(v * 100);
	}

	public static int maxEquipped() {
		return Math.max(0, AuraConfig.RELIC_MAX_EQUIPPED);
	}

	// ---------------------------------------------------------------
	// Items
	// ---------------------------------------------------------------

	public static ItemStack stack(AuraType aura) {
		var item = AuraPoints.RELICS.get(aura);
		return item == null ? ItemStack.EMPTY : new ItemStack(item.get());
	}

	public static AuraType auraOf(ItemStack stack) {
		return stack.getItem() instanceof RelicItem r ? r.aura : null;
	}

	// ---------------------------------------------------------------
	// What a player wears (refreshed every second)
	// ---------------------------------------------------------------

	private static final Map<UUID, Set<AuraType>> WORN = new HashMap<>();
	private static final ResourceLocation BEDROCK_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "relic_bedrock"),
			DUSK_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "relic_dusk");

	/** The relics that count for this player: worn, of another aura, the first RELIC_MAX_EQUIPPED different ones. */
	public static Set<AuraType> active(ServerPlayer player) {
		return WORN.getOrDefault(player.getUUID(), Set.of());
	}

	public static boolean has(LivingEntity e, AuraType relic) {
		return e instanceof ServerPlayer p && AuraConfig.RELIC_ENABLED && active(p).contains(relic);
	}

	/** Reads the accessory slots now. */
	public static Set<AuraType> scan(ServerPlayer player) {
		Set<AuraType> out = EnumSet.noneOf(AuraType.class);
		if (!AuraConfig.RELIC_ENABLED || player.getServer() == null) return out;
		AuraType own = AuraState.get(player.getServer()).getOrCreate(player.getUUID()).aura;
		var items = AccessorySlots.items(player);
		int slots = AccessorySlots.count(false);
		for (int i = 0; i < slots && i < items.getSlots() && out.size() < maxEquipped(); i++) {
			AuraType a = auraOf(items.getStackInSlot(i));
			if (a != null && a != own) out.add(a);
		}
		return out;
	}

	/** Every second for each player (from the server tick's player loop). */
	public static void tick(ServerPlayer player, long now) {
		if (now % 20 != 0) return;
		Set<AuraType> worn = scan(player);
		WORN.put(player.getUUID(), worn);
		modifier(player.getAttribute(Attributes.KNOCKBACK_RESISTANCE), BEDROCK_ID,
				worn.contains(AuraType.EARTH) ? AuraConfig.RELIC_BEDROCK_KNOCKBACK : 0, AttributeModifier.Operation.ADD_VALUE);
		boolean dark = player.level().getMaxLocalRawBrightness(player.blockPosition()) <= 7;
		modifier(player.getAttribute(Attributes.MOVEMENT_SPEED), DUSK_ID,
				worn.contains(AuraType.SHADOW) && dark ? AuraConfig.RELIC_DUSK_SPEED : 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
		if (worn.contains(AuraType.LIGHT) && player.isAlive() && now % (Math.max(1, AuraConfig.RELIC_HALO_SECONDS) * 20L) == 0
				&& player.getHealth() < player.getMaxHealth()) {
			player.heal((float) AuraConfig.RELIC_HALO_HEAL);
		}
	}

	private static void modifier(AttributeInstance attr, ResourceLocation id, double value, AttributeModifier.Operation op) {
		if (attr == null) return;
		AttributeModifier now = attr.getModifier(id);
		if (value == 0) {
			if (now != null) attr.removeModifier(id);
			return;
		}
		if (now != null && now.amount() == value) return;
		attr.removeModifier(id);
		attr.addTransientModifier(new AttributeModifier(id, value, op));
	}

	// ---------------------------------------------------------------
	// Combat (AuraPoints' incoming-damage handler)
	// ---------------------------------------------------------------

	/** The wearer was hit: Tide, Zephyr and Storm. Returns the new damage (0 = none). */
	public static float defend(ServerPlayer victim, DamageSource source, float amount) {
		if (!AuraConfig.RELIC_ENABLED) return amount;
		Set<AuraType> worn = active(victim);
		if (worn.isEmpty()) return amount;
		if (worn.contains(AuraType.THUNDER) && source.is(DamageTypes.LIGHTNING_BOLT)) return 0f;
		if (worn.contains(AuraType.WIND) && source.is(DamageTypeTags.IS_FALL)) amount *= (float) (1 - AuraConfig.RELIC_ZEPHYR_FALL);
		if (worn.contains(AuraType.WATER) && source.getEntity() instanceof LivingEntity) amount *= (float) (1 - AuraConfig.RELIC_TIDE_REDUCTION);
		return amount;
	}

	/** The wearer hits something: Ember, Rime, Thorn, and Vein's heal. Returns the new damage. */
	public static float attack(ServerPlayer attacker, LivingEntity victim, float amount, boolean melee) {
		if (!AuraConfig.RELIC_ENABLED || amount <= 0) return amount;
		Set<AuraType> worn = active(attacker);
		if (worn.isEmpty()) return amount;
		double mult = 1;
		if (worn.contains(AuraType.FIRE) && victim.getHealth() < victim.getMaxHealth() * AuraConfig.RELIC_EMBER_HEALTH) mult += AuraConfig.RELIC_EMBER_BONUS;
		if (worn.contains(AuraType.FROST) && (victim.getTicksFrozen() > 0 || victim.hasEffect(MobEffects.MOVEMENT_SLOWDOWN))) mult += AuraConfig.RELIC_RIME_BONUS;
		if (worn.contains(AuraType.VERDANT) && victim.hasEffect(MobEffects.POISON)) mult += AuraConfig.RELIC_THORN_BONUS;
		float out = (float) (amount * mult);
		if (worn.contains(AuraType.BLOOD) && melee) attacker.heal((float) Math.min(AuraConfig.RELIC_VEIN_MAX_HEAL, out * AuraConfig.RELIC_VEIN_LIFESTEAL));
		return out;
	}

	// ---------------------------------------------------------------
	// Finding them: loot chests
	// ---------------------------------------------------------------

	/** mixin/LootTableMixin: a container was just filled from loot table {@code table}. */
	public static void onChestFilled(Container container, ResourceLocation table, ServerLevel level) {
		if (!AuraConfig.RELIC_ENABLED || level == null || AuraConfig.RELIC_CHEST_CHANCE <= 0) return;
		boolean listed = false;
		for (String t : AuraConfig.RELIC_CHEST_TABLES.split(",")) if (t.strip().equals(table.toString())) listed = true;
		if (!listed || level.random.nextDouble() >= AuraConfig.RELIC_CHEST_CHANCE) return;
		List<Integer> empty = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) if (container.getItem(i).isEmpty()) empty.add(i);
		if (empty.isEmpty()) return;
		container.setItem(empty.get(level.random.nextInt(empty.size())), stack(AURAS.get(level.random.nextInt(AURAS.size()))));
	}

	// ---------------------------------------------------------------
	// Buying and rerolling (/aura relic, the Aura Book)
	// ---------------------------------------------------------------

	/** A random relic of another aura than the player's (and than {@code not}). */
	private static AuraType random(ServerPlayer player, AuraType not) {
		AuraType own = AuraState.get(player.getServer()).getOrCreate(player.getUUID()).aura;
		List<AuraType> pool = new ArrayList<>(AURAS);
		pool.remove(own);
		pool.remove(not);
		return pool.get(player.getRandom().nextInt(pool.size()));
	}

	public static void give(ServerPlayer player, AuraType aura) {
		ItemStack stack = stack(aura);
		if (!player.getInventory().add(stack)) player.drop(stack, false);
		player.sendSystemMessage(Component.literal("✦ You got a " + name(aura) + ": ").withStyle(aura.color)
				.append(Component.literal(describe(aura)).withStyle(ChatFormatting.GRAY)));
		AuraFx.sound(player.serverLevel(), player, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), 0.8f, 1.2f);
	}

	public static boolean buy(ServerPlayer player) {
		if (!AuraConfig.RELIC_ENABLED) return false;
		if (!AuraCurrency.take(player, AuraConfig.RELIC_SHOP_COST)) {
			player.sendSystemMessage(Component.literal("A relic costs " + AuraConfig.RELIC_SHOP_COST + " Aura.").withStyle(ChatFormatting.RED));
			return false;
		}
		give(player, random(player, null));
		return true;
	}

	/** Trades the relic in the main hand plus RELIC_REROLL_COST Aura for a different one. */
	public static boolean reroll(ServerPlayer player) {
		if (!AuraConfig.RELIC_ENABLED) return false;
		ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
		AuraType from = auraOf(held);
		if (from == null) {
			player.sendSystemMessage(Component.literal("Hold the relic you want to trade in.").withStyle(ChatFormatting.RED));
			return false;
		}
		if (!AuraCurrency.take(player, AuraConfig.RELIC_REROLL_COST)) {
			player.sendSystemMessage(Component.literal("A reroll costs " + AuraConfig.RELIC_REROLL_COST + " Aura.").withStyle(ChatFormatting.RED));
			return false;
		}
		held.shrink(1);
		give(player, random(player, from));
		return true;
	}

	// ---------------------------------------------------------------
	// Loadout relic sets
	// ---------------------------------------------------------------

	/** The relic auras in the accessory slots now (in slot order). */
	public static List<AuraType> worn(ServerPlayer player) {
		List<AuraType> out = new ArrayList<>();
		var items = AccessorySlots.items(player);
		for (int i = 0; i < AccessorySlots.count(false) && i < items.getSlots(); i++) {
			AuraType a = auraOf(items.getStackInSlot(i));
			if (a != null) out.add(a);
		}
		return out;
	}

	/**
	 * Puts on the relics of a set: relics in the accessory slots that aren't in it go to the inventory,
	 * missing ones come from the inventory. Says which it couldn't find. Returns how many are worn after.
	 */
	public static int applySet(ServerPlayer player, List<AuraType> set) {
		var items = AccessorySlots.items(player);
		int slots = Math.min(AccessorySlots.count(false), items.getSlots());
		var inv = player.getInventory();
		// take off the ones not in the set
		for (int i = 0; i < slots; i++) {
			AuraType a = auraOf(items.getStackInSlot(i));
			if (a == null || set.contains(a)) continue;
			ItemStack off = items.getStackInSlot(i).copy();
			items.setStackInSlot(i, ItemStack.EMPTY);
			if (!inv.add(off)) player.drop(off, false);
		}
		List<AuraType> missing = new ArrayList<>();
		for (AuraType want : set) {
			boolean on = false;
			for (int i = 0; i < slots; i++) if (auraOf(items.getStackInSlot(i)) == want) on = true;
			if (on) continue;
			int from = -1;
			for (int k = 0; k < inv.getContainerSize(); k++) if (auraOf(inv.getItem(k)) == want) {
				from = k;
				break;
			}
			int free = -1;
			for (int i = 0; i < slots; i++) if (items.getStackInSlot(i).isEmpty()) {
				free = i;
				break;
			}
			if (from < 0 || free < 0) {
				missing.add(want);
				continue;
			}
			items.setStackInSlot(free, inv.removeItem(from, 1));
		}
		if (!missing.isEmpty()) {
			List<String> names = new ArrayList<>();
			for (AuraType a : missing) names.add(name(a));
			player.sendSystemMessage(Component.literal("Not in your inventory (or no free accessory slot): " + String.join(", ", names)).withStyle(ChatFormatting.YELLOW));
		}
		WORN.put(player.getUUID(), scan(player));
		return worn(player).size();
	}

	// ---------------------------------------------------------------
	// /aura relic
	// ---------------------------------------------------------------

	public static void show(ServerPlayer player) {
		player.sendSystemMessage(Component.literal("Elemental relics").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
				.append(Component.literal("  (accessory slots, up to " + maxEquipped() + " count, not your own aura's)").withStyle(ChatFormatting.GRAY)));
		Set<AuraType> on = scan(player);
		for (AuraType a : AURAS) {
			player.sendSystemMessage(Component.literal((on.contains(a) ? " ✔ " : " • ") + name(a)).withStyle(on.contains(a) ? ChatFormatting.GREEN : a.color)
					.append(Component.literal("  " + describe(a)).withStyle(ChatFormatting.GRAY)));
		}
		player.sendSystemMessage(Component.literal(" /aura relic buy: a random one for " + AuraConfig.RELIC_SHOP_COST + " Aura · /aura relic reroll: trade the one in your hand + "
				+ AuraConfig.RELIC_REROLL_COST + " Aura").withStyle(ChatFormatting.DARK_GRAY));
	}

	public static void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
		dispatcher.register(net.minecraft.commands.Commands.literal("aura").then(net.minecraft.commands.Commands.literal("relic")
				.executes(ctx -> {
					show(ctx.getSource().getPlayerOrException());
					return 1;
				})
				.then(net.minecraft.commands.Commands.literal("buy").executes(ctx -> buy(ctx.getSource().getPlayerOrException()) ? 1 : 0))
				.then(net.minecraft.commands.Commands.literal("reroll").executes(ctx -> reroll(ctx.getSource().getPlayerOrException()) ? 1 : 0))));
	}

	public static void forget(UUID id) {
		WORN.remove(id);
	}

	/** Dev test: as if the player wore exactly these. */
	public static void devWear(ServerPlayer player, Set<AuraType> relics) {
		WORN.put(player.getUUID(), EnumSet.copyOf(relics.isEmpty() ? EnumSet.noneOf(AuraType.class) : relics));
	}

	/** Server stopped: nothing worn carries into the next world. */
	public static void clearAll() {
		WORN.clear();
	}
}
