package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.MossBlock;
import net.minecraft.world.level.block.NyliumBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bloom, a Verdant passive: while sneaking, every BLOOM_INTERVAL_TICKS up to BLOOM_PLANTS_PER_PULSE
 * random plants within BLOOM_RADIUS get a dose of bone meal - crops, saplings, berries, stems. Grass,
 * moss and nylium are left alone (they'd sprout flowers and spread). Respects spawn protection and
 * claims like placing a block would. Players switch it off and on with /aura bloom.
 */
public final class Bloom {

	private Bloom() {
	}

	/** Grove Warden: friends near a sneaking Bloom heal a little every pulse (not you). */
	private static void healFriends(ServerPlayer player, ServerLevel level) {
		double r = Math.max(1, AuraConfig.VERDANT_GROVE_HEAL_RADIUS);
		healFriends(player, level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, player.getBoundingBox().inflate(r)));
	}

	/** The heal itself, for the entities near the player: friends within the radius that are hurt. Returns how many it healed. */
	public static int healFriends(ServerPlayer player, java.util.Collection<? extends net.minecraft.world.entity.LivingEntity> near) {
		double r = Math.max(1, AuraConfig.VERDANT_GROVE_HEAL_RADIUS);
		int healed = 0;
		for (net.minecraft.world.entity.LivingEntity e : near) {
			if (e == player || e.distanceToSqr(player) > r * r || e.getHealth() >= e.getMaxHealth() || !Light.friend(player, e)) continue;
			e.heal(AuraConfig.VERDANT_GROVE_HEAL);
			healed++;
			if (e.level() instanceof ServerLevel level) AuraFx.particles(level, ParticleTypes.HAPPY_VILLAGER, e.getX(), e.getY() + e.getBbHeight() * 0.6, e.getZ(), 4, 0.3, 0.4, 0.3, 0.0);
		}
		return healed;
	}

	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		if (!data.bloomOn || !player.isShiftKeyDown() || !AuraAbilities.hasAbility(data, Ability.BLOOM)) return;
		if (now % Math.max(1, AuraConfig.BLOOM_INTERVAL_TICKS) != 0 || !player.isAlive() || player.isSpectator() || !player.mayBuild()) return;
		ServerLevel level = player.serverLevel();
		if (Branch.is(player, Branch.GROVE_WARDEN)) healFriends(player, level);
		int r = Math.max(1, AuraConfig.BLOOM_RADIUS);
		BlockPos center = player.blockPosition();
		int grown = 0;
		var random = player.getRandom();
		// a handful of random tries per pulse; most land on air, which is fine
		for (int tries = 0; tries < 40 && grown < AuraConfig.BLOOM_PLANTS_PER_PULSE; tries++) {
			BlockPos pos = center.offset(random.nextInt(2 * r + 1) - r, random.nextInt(5) - 2, random.nextInt(2 * r + 1) - r);
			if (!level.isLoaded(pos)) continue; // never load a chunk just to look for a plant
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof BonemealableBlock plant)) continue;
			if (state.getBlock() instanceof GrassBlock || state.getBlock() instanceof MossBlock || state.getBlock() instanceof NyliumBlock) continue;
			if (!plant.isValidBonemealTarget(level, pos, state) || !level.mayInteract(player, pos)) continue;
			if (plant.isBonemealSuccess(level, random, pos, state)) plant.performBonemeal(level, random, pos, state);
			AuraFx.particles(level, ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.0);
			grown++;
		}
		if (grown > 0) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.BONE_MEAL_USE.value(), SoundSource.PLAYERS, 0.5f, 1.2f);
			AuraStats.add(player, AuraStats.BLOOM_GROWN, grown);
		}
	}
}
