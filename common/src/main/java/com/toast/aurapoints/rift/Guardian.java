package com.toast.aurapoints.rift;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * The realm's guardian (5.46.0): what tears out of the altar when the relic is forged. Spawned with the run's health
 * scaling (RIFT_BOSS_HEALTH, +RIFT_BOSS_HEALTH_PER_PLAYER per extra player).
 */
final class Guardian {

	private Guardian() {
	}

	private static final ResourceLocation BOSS_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "rift_boss"),
			BOSS_SIZE_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "rift_boss_size");

	static Mob spawn(ServerLevel rift, Rifts.Run run, Vec3 at, int players) {
		RelicWarden w = RelicWarden.create(rift, run, at);
		if (w == null) return null;
		health(w, players);
		rift.addFreshEntity(w);
		rift.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		return w;
	}

	static void health(Mob boss, int players) {
		var hp = boss.getAttribute(Attributes.MAX_HEALTH);
		if (hp == null) return;
		double target = AuraConfig.RIFT_BOSS_HEALTH * (1 + AuraConfig.RIFT_BOSS_HEALTH_PER_PLAYER * (players - 1)), cur = hp.getValue();
		if (cur > 0) hp.addPermanentModifier(new AttributeModifier(BOSS_ID, target / cur - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		boss.setHealth(boss.getMaxHealth());
	}
}
