package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shatter, a Frost passive: SHATTER_HITS charged melee hits in a row inside the weapon's time
 * window (counted from the first hit) - the last one deals SHATTER_BONUS_DAMAGE extra and bursts
 * into ice. The combo starts over on a swing at nothing (the client reports it, ShatterMissPacket),
 * hitting a block, switching or changing the held item, a weak (uncharged) hit, another target
 * (SHATTER_SAME_TARGET), or running out of time. In memory only.
 */
public final class Shatter {

	private Shatter() {
	}

	private static final int ICE_BLUE = 0xA8E4FF;

	private static final class Combo {
		int hits;
		long firstTick;
		int window;
		UUID target;
		int slot;
		Item item;
	}

	private static final Map<UUID, Combo> COMBOS = new HashMap<>();
	/** attacker -> the target whose hit about to land is the finisher (set on the attack, used by the damage event that follows) */
	private static final Map<UUID, UUID> FINISHER = new HashMap<>();

	private static int window(Item item) {
		int ticks = item instanceof SwordItem ? AuraConfig.SHATTER_WINDOW_SWORD_TICKS
				: item instanceof AxeItem ? AuraConfig.SHATTER_WINDOW_AXE_TICKS
				: item instanceof MaceItem ? AuraConfig.SHATTER_WINDOW_MACE_TICKS
				: item instanceof TridentItem ? AuraConfig.SHATTER_WINDOW_TRIDENT_TICKS
				: AuraConfig.SHATTER_WINDOW_OTHER_TICKS;
		return Math.max(1, ticks);
	}

	private static boolean has(ServerPlayer player) {
		return player.getServer() != null
				&& AuraAbilities.hasAbility(AuraState.get(player.getServer()).getOrCreate(player.getUUID()), Ability.SHATTER);
	}

	/** Starts over (a miss, a block hit, ...). */
	public static void reset(ServerPlayer player) {
		Combo c = COMBOS.remove(player.getUUID());
		FINISHER.remove(player.getUUID());
		if (c != null && c.hits > 0 && has(player)) progress(player, 0);
	}

	/** A melee attack is about to land (AttackEntityEvent, attack charge still readable). */
	public static void onAttack(ServerPlayer player, Entity target, long now) {
		FINISHER.remove(player.getUUID());
		if (!has(player) || !(target instanceof LivingEntity living) || !living.isAlive()) return;
		if (player.getAttackStrengthScale(0.5f) < AuraConfig.SHATTER_MIN_ATTACK_STRENGTH) {
			reset(player);
			return;
		}
		int need = Math.max(2, AuraConfig.SHATTER_HITS);
		Item item = player.getMainHandItem().getItem();
		int slot = player.getInventory().selected;
		Combo c = COMBOS.get(player.getUUID());
		boolean fresh = c == null || now < c.firstTick || now - c.firstTick > c.window
				|| c.slot != slot || c.item != item
				|| (AuraConfig.SHATTER_SAME_TARGET && !target.getUUID().equals(c.target));
		if (fresh) {
			c = new Combo();
			c.firstTick = now;
			c.window = window(item);
			c.target = target.getUUID();
			c.slot = slot;
			c.item = item;
			COMBOS.put(player.getUUID(), c);
		}
		c.hits++;
		if (c.hits >= need) {
			COMBOS.remove(player.getUUID());
			FINISHER.put(player.getUUID(), target.getUUID());
		} else {
			progress(player, c.hits);
			Vec3 p = AuraFx.chest(living);
			AuraFx.burst(player.serverLevel(), p.x, p.y, p.z, ParticleTypes.SNOWFLAKE, 2 + 2 * c.hits, 0.25, 0.02);
			player.serverLevel().playSound(null, p.x, p.y, p.z, AuraSounds.AMETHYST_BLOCK_HIT.value(), SoundSource.PLAYERS, 0.5f, 1.2f + 0.2f * c.hits);
		}
	}

	/** In the incoming-damage event: the finishing hit's bonus damage (0 if this hit isn't one). */
	public static float bonus(ServerPlayer attacker, LivingEntity victim, boolean melee) {
		UUID target = FINISHER.get(attacker.getUUID());
		if (target == null || !melee || !target.equals(victim.getUUID())) return 0f;
		FINISHER.remove(attacker.getUUID());
		AuraStats.add(attacker, AuraStats.SHATTER_COMBOS, 1);
		shatterFx(attacker, victim);
		progress(attacker, -1);
		return AuraAbilities.scaled(victim, AuraConfig.SHATTER_BONUS_DAMAGE);
	}

	/** Every server tick per player: switching the held item breaks the combo, and it times out. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		Combo c = COMBOS.get(player.getUUID());
		if (c == null) return;
		if (!AuraAbilities.hasAbility(data, Ability.SHATTER) || !player.isAlive()) {
			COMBOS.remove(player.getUUID());
			return;
		}
		if (player.getInventory().selected != c.slot || player.getMainHandItem().getItem() != c.item
				|| now < c.firstTick || now - c.firstTick > c.window) reset(player);
	}

	/** A small marker above the hotbar: one snowflake per hit landed. {@code hits} -1 = shattered, 0 = broken. */
	private static void progress(ServerPlayer player, int hits) {
		int need = Math.max(2, AuraConfig.SHATTER_HITS);
		if (hits < 0) {
			player.displayClientMessage(Component.literal("❄ SHATTER ❄").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), true);
			return;
		}
		if (hits == 0) {
			player.displayClientMessage(Component.empty(), true);
			return;
		}
		StringBuilder marks = new StringBuilder();
		for (int i = 0; i < need; i++) marks.append(i < hits ? "❄ " : "· ");
		player.displayClientMessage(Component.literal(marks.toString().trim()).withStyle(ChatFormatting.AQUA), true);
	}

	private static void shatterFx(ServerPlayer attacker, LivingEntity victim) {
		ServerLevel level = attacker.serverLevel();
		Vec3 p = AuraFx.chest(victim);
		var ice = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
		AuraFx.burst(level, p.x, p.y, p.z, ice, 35, 0.4, 0.2);
		AuraFx.burst(level, p.x, p.y, p.z, ParticleTypes.SNOWFLAKE, 18, 0.4, 0.08);
		AuraFx.burst(level, p.x, p.y, p.z, AuraFx.dust(ICE_BLUE, 1.5f), 14, 0.45, 0.0);
		AuraFx.particles(level, ParticleTypes.CRIT, p.x, p.y, p.z, 12, 0.3, 0.3, 0.3, 0.3);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.GLASS_BREAK.value(), SoundSource.PLAYERS, 1.0f, 0.9f);
		level.playSound(null, p.x, p.y, p.z, AuraSounds.AMETHYST_CLUSTER_BREAK.value(), SoundSource.PLAYERS, 1.0f, 0.7f);
	}

	public static void onLogout(ServerPlayer player) {
		COMBOS.remove(player.getUUID());
		FINISHER.remove(player.getUUID());
	}
}
