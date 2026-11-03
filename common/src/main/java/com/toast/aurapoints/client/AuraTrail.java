package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * CLIENT ONLY. A soft trail behind sprinting players in their aura's style - embers, drips, puffs,
 * flakes, smoke, petals, sparks, dust, glints, blood or rifts - drawn by every client from the aura it already knows
 * (PlayerAuraPacket). Client settings: auraTrails, ownTrail.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class AuraTrail {

	private AuraTrail() {
	}

	private static DustParticleOptions dust(int rgb, float size) {
		return new DustParticleOptions(new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f), size);
	}

	private static final DustParticleOptions SHADOW = dust(0x5A2A8C, 1.0f), WATER = dust(0x5FC8FF, 0.8f), FIRE = dust(0xFF8A2A, 0.7f),
			WIND = dust(0xF4FBFF, 0.7f), FROST = dust(0xBDEBFF, 0.8f), VERDANT = dust(0x7CD957, 0.9f), THUNDER = dust(0xFFE347, 0.6f),
			RESONANCE = dust(0xB01CE0, 0.8f), EARTH = dust(0x9A7148, 1.0f), LIGHT = dust(0xFFF1C2, 0.7f), BLOOD = dust(0xB0121B, 0.8f),
			VOID = dust(0x4B1C8C, 1.0f);

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.isPaused() || !AuraClientConfig.AURA_TRAILS.get()) return;
		Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
		for (AbstractClientPlayer p : mc.level.players()) {
			if (!p.isSprinting() || !p.onGround() || p.isInvisible() || p.isSpectator() || p.isSwimming()) continue;
			if (p == mc.player && !AuraClientConfig.OWN_TRAIL.get()) continue;
			if (p.distanceToSqr(camera) > 40 * 40) continue;
			AuraType aura = ClientAuraState.playerAura(p.getId());
			if (aura == null) continue;
			trail(mc, p, aura, p.getRandom());
		}
	}

	/** One tick of trail just behind the feet. */
	private static void trail(Minecraft mc, AbstractClientPlayer p, AuraType aura, RandomSource r) {
		Vec3 back = ArmPose.forward(p, 1f).scale(-0.35);
		double x = p.getX() + back.x + (r.nextDouble() - 0.5) * 0.4, y = p.getY() + 0.1, z = p.getZ() + back.z + (r.nextDouble() - 0.5) * 0.4;
		var level = mc.level;
		switch (aura) {
			case SHADOW -> {
				level.addParticle(SHADOW, x, y + 0.1, z, 0, 0, 0);
				if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.SMOKE, x, y, z, 0, 0.01, 0);
			}
			case WATER -> {
				level.addParticle(WATER, x, y, z, 0, 0, 0);
				if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.SPLASH, x, y, z, 0, 0.05, 0);
			}
			case FIRE -> {
				level.addParticle(FIRE, x, y, z, 0, 0, 0);
				if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.SMALL_FLAME, x, y, z, 0, 0.01, 0);
			}
			case WIND -> {
				level.addParticle(WIND, x, y + 0.2, z, 0, 0, 0);
				if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.CLOUD, x, y + 0.1, z, 0, 0.01, 0);
			}
			case FROST -> {
				level.addParticle(FROST, x, y, z, 0, 0, 0);
				if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.SNOWFLAKE, x, y + 0.1, z, 0, 0, 0);
			}
			case VERDANT -> {
				level.addParticle(VERDANT, x, y, z, 0, 0, 0);
				if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.CHERRY_LEAVES, x, y + 0.3, z, 0, 0, 0);
			}
			case THUNDER -> {
				level.addParticle(THUNDER, x, y, z, 0, 0, 0);
				if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y + 0.1, z, 0, 0, 0);
			}
			case RESONANCE -> {
				level.addParticle(RESONANCE, x, y, z, 0, 0, 0);
				if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.SCULK_CHARGE_POP, x, y + 0.1, z, 0, 0.02, 0);
			}
			case EARTH -> {
				level.addParticle(EARTH, x, y, z, 0, 0, 0);
				if (r.nextInt(4) == 0) level.addParticle(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
						net.minecraft.world.level.block.Blocks.COARSE_DIRT.defaultBlockState()), x, y, z, 0, 0.05, 0);
			}
			case LIGHT -> {
				level.addParticle(LIGHT, x, y + 0.1, z, 0, 0, 0);
				if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.END_ROD, x, y + 0.1, z, 0, 0.01, 0);
			}
			case BLOOD -> {
				level.addParticle(BLOOD, x, y, z, 0, 0, 0);
				if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.DRIPPING_DRIPSTONE_LAVA, x, y + 0.3, z, 0, 0, 0);
			}
			case VOID -> {
				level.addParticle(VOID, x, y + 0.1, z, 0, 0, 0);
				if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.REVERSE_PORTAL, x, y + 0.1, z, 0, 0.01, 0);
			}
		}
	}
}
