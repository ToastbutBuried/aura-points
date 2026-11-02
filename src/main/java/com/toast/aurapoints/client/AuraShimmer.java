package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * CLIENT ONLY. A faint glow of particles around players in their aura's color, now and then one of the
 * aura's own (an ember, a snowflake, a spark...), much livelier for a moment right after they cast
 * something. Spawned only here, so it costs the server nothing. Never on anyone invisible, cloaked or
 * sneaking - it must not give a hidden player away.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class AuraShimmer {

	private AuraShimmer() {
	}

	private static final double RANGE_SQ = 32 * 32;
	private static final long BURST_MILLIS = 1500;
	/** Per player entity id: the pose packet time last seen, and when their last cast was. */
	private static final Map<Integer, long[]> SEEN = new HashMap<>();

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		AuraClientConfig.Shimmer mode = AuraClientConfig.AURA_SHIMMER.get();
		if (mc.level == null || mc.player == null || mc.isPaused()) {
			if (mc.level == null) SEEN.clear();
			return;
		}
		if (mode == AuraClientConfig.Shimmer.OFF) return;
		float density = (float) (double) AuraClientConfig.SHIMMER_DENSITY.get();
		long now = System.currentTimeMillis();
		RandomSource r = mc.level.random;
		for (AbstractClientPlayer p : mc.level.players()) {
			boolean self = p == mc.player;
			if (self && (mode != AuraClientConfig.Shimmer.ALL || mc.options.getCameraType().isFirstPerson())) continue;
			AuraType aura = ClientAuraState.playerAura(p.getId());
			if (aura == null || p.isSpectator() || p.isInvisible() || p.isDiscrete() || ClientAuraState.isCloaked(p.getId())) continue;
			if (p.distanceToSqr(mc.player) > RANGE_SQ) continue;

			long[] seen = SEEN.computeIfAbsent(p.getId(), k -> new long[]{-1, 0});
			long[] pose = ClientAuraState.bendState(p.getId());
			if (pose != null && pose[1] != seen[0]) {
				seen[0] = pose[1];
				seen[1] = now;
			}
			boolean burst = now - seen[1] < BURST_MILLIS;
			float rate = (burst ? 1.4f : 0.18f) * density;
			int count = (int) rate + (r.nextFloat() < rate - (int) rate ? 1 : 0);
			for (int i = 0; i < count; i++) {
				double a = r.nextDouble() * Math.PI * 2;
				double rad = p.getBbWidth() * (0.55 + r.nextDouble() * 0.35);
				double x = p.getX() + Math.cos(a) * rad, z = p.getZ() + Math.sin(a) * rad;
				double y = p.getY() + r.nextDouble() * p.getBbHeight();
				ParticleOptions particle = r.nextInt(burst ? 3 : 6) == 0 ? flavor(aura) : glow(aura, r);
				if (particle == null) particle = glow(aura, r);
				mc.level.addParticle(particle, x, y, z, 0, 0.015 + r.nextDouble() * 0.02, 0);
			}
		}
		if (SEEN.size() > 64) SEEN.keySet().removeIf(id -> mc.level.getEntity(id) == null);
	}

	private static ParticleOptions glow(AuraType aura, RandomSource r) {
		int rgb = aura.rgb;
		return new DustParticleOptions(new Vector3f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f),
				0.45f + r.nextFloat() * 0.3f);
	}

	/** One of the aura's own particles, now and then among the glow. */
	private static ParticleOptions flavor(AuraType aura) {
		return switch (aura) {
			case FIRE -> ParticleTypes.SMALL_FLAME;
			case WATER -> ParticleTypes.BUBBLE_POP;
			case FROST -> ParticleTypes.SNOWFLAKE;
			case WIND -> ParticleTypes.WHITE_ASH;
			case VERDANT -> ParticleTypes.HAPPY_VILLAGER;
			case THUNDER -> ParticleTypes.ELECTRIC_SPARK;
			case SHADOW -> ParticleTypes.SMOKE;
			case RESONANCE -> ParticleTypes.SCULK_CHARGE_POP;
			case EARTH -> ParticleTypes.MYCELIUM;
			case LIGHT -> ParticleTypes.END_ROD;
			case BLOOD -> ParticleTypes.DAMAGE_INDICATOR;
			case VOID -> ParticleTypes.REVERSE_PORTAL;
		};
	}
}
