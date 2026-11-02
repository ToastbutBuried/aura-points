package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SimpleExplosionDamageCalculator;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.Function;

/**
 * A wind charge whose burst has {@link AuraConfig#SUPER_WIND_CHARGE_POWER} times
 * the radius and knockback of a normal one. It is still a vanilla wind charge
 * entity on the client, so it needs no renderer or registration; only the
 * explosion (which runs on the server) differs. If it is saved mid-flight it
 * comes back as a normal wind charge, which is harmless.
 */
public class SuperWindCharge extends WindCharge {

	/** Vanilla values: radius 1.2, knockback multiplier 1.22. Built per burst so a config reload applies at once. */
	private static ExplosionDamageCalculator damageCalculator() {
		return new SimpleExplosionDamageCalculator(
				true, false, Optional.of(1.22F * AuraConfig.SUPER_WIND_CHARGE_POWER),
				BuiltInRegistries.BLOCK.getTag(BlockTags.BLOCKS_WIND_CHARGE_EXPLOSIONS).map(Function.identity()));
	}

	public SuperWindCharge(Player player, Level level, double x, double y, double z) {
		super(player, level, x, y, z);
	}

	/** Server side: a swirling white trail behind the charge in flight. */
	@Override
	public void tick() {
		super.tick();
		if (this.level() instanceof ServerLevel level && this.isAlive()) {
			AuraFx.particles(level, ParticleTypes.CLOUD, getX(), getY(), getZ(), 2, 0.05, 0.05, 0.05, 0.0);
			AuraFx.particles(level, AuraFx.dust(AuraType.WIND.rgb, 1.2f), getX(), getY(), getZ(), 2, 0.1, 0.1, 0.1, 0.0);
			if (this.tickCount % 3 == 0) {
				AuraFx.particles(level, ParticleTypes.SMALL_GUST, getX(), getY(), getZ(), 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	@Override
	protected void explode(Vec3 pos) {
		float radius = 1.2F * AuraConfig.SUPER_WIND_CHARGE_POWER;
		this.level().explode(this, null, damageCalculator(), pos.x(), pos.y(), pos.z(),
				radius, false, Level.ExplosionInteraction.TRIGGER,
				ParticleTypes.GUST_EMITTER_SMALL, ParticleTypes.GUST_EMITTER_LARGE, AuraSounds.WIND_CHARGE_BURST);

		if (getOwner() instanceof net.minecraft.server.level.ServerPlayer owner) Combos.pass(owner, pos, pos, radius, getDeltaMovement());

		// On top of the vanilla burst: a wide ring of gusts and a deep boom, so it reads as "super".
		if (this.level() instanceof ServerLevel level) {
			AuraFx.ring(level, pos.x(), pos.y(), pos.z(), radius, ParticleTypes.GUST, 10);
			AuraFx.ring(level, pos.x(), pos.y(), pos.z(), radius * 0.6, ParticleTypes.CLOUD, 20);
			AuraFx.burst(level, pos.x(), pos.y(), pos.z(), AuraFx.dust(AuraType.WIND.rgb, 1.8f), 30, radius * 0.4, 0.0);
			AuraFx.burst(level, pos.x(), pos.y(), pos.z(), ParticleTypes.EXPLOSION, 1, 0.0, 0.0);
			level.playSound(null, pos.x(), pos.y(), pos.z(), AuraSounds.BREEZE_WIND_CHARGE_BURST.value(),
					SoundSource.PLAYERS, 1.2f, 0.6f);
		}
	}
}
