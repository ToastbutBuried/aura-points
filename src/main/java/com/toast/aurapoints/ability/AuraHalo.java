package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Every aura shows on its holder: a slowly turning halo above the head that drips its aura's
 * particles, and (all but Frost, whose Ice Shell flakes do it) a couple of specks circling the body.
 * Seen by everyone else; the holder gets only AURA_EFFECT_SELF_SHARE of it, so their own view stays
 * clear. Nothing shows while invisible (a Shadow cloak never gives itself away).
 */
public final class AuraHalo {

	private AuraHalo() {
	}

	/** The look of one aura: halo colors, what drips from it, and the circling specks (null = none). */
	private record Style(int ringRgb, int ringRgb2, ParticleOptions accent, ParticleOptions drop, double dropChance,
						 ParticleOptions orbit, double orbitSpeed) {
	}

	private static Style style(AuraType aura) {
		return switch (aura) {
			case SHADOW -> new Style(0x7B2FBE, 0x2A0A3D, null, ParticleTypes.FALLING_OBSIDIAN_TEAR, 0.18,
					AuraFx.dust(0x4B1A70, 0.7f), 0.09);
			case WATER -> new Style(0x2E9BFF, 0x9FDCFF, null, ParticleTypes.FALLING_WATER, 0.25,
					AuraFx.dust(0x5FB8FF, 0.65f), 0.1);
			case FIRE -> new Style(0xFF5A1F, 0xFFC23A, ParticleTypes.SMALL_FLAME, ParticleTypes.FALLING_LAVA, 0.06,
					ParticleTypes.SMALL_FLAME, 0.12);
			case WIND -> new Style(0xDDF3FF, 0xFFFFFF, null, ParticleTypes.WHITE_ASH, 0.5,
					AuraFx.dust(0xF4FBFF, 0.6f), 0.22);
			case FROST -> new Style(0xA8E4FF, 0xF4FBFF, null, ParticleTypes.SNOWFLAKE, 0.3, null, 0);
			case VERDANT -> new Style(0x3FA34D, 0xB6F27A, null, ParticleTypes.FALLING_SPORE_BLOSSOM, 0.25,
					ParticleTypes.COMPOSTER, 0.1);
			case THUNDER -> new Style(0xFFE347, 0x7FC8FF, ParticleTypes.ELECTRIC_SPARK, ParticleTypes.ELECTRIC_SPARK, 0.3,
					ParticleTypes.ELECTRIC_SPARK, 0.25);
			case RESONANCE -> new Style(0x3A0858, 0xC020E0, null, ParticleTypes.SQUID_INK, 0.08,
					AuraFx.dust(0x6A0F96, 0.65f), 0.12);
			case EARTH -> new Style(0x9A7148, 0x6E6A64, null,
					new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST, net.minecraft.world.level.block.Blocks.COARSE_DIRT.defaultBlockState()), 0.25,
					AuraFx.dust(0x8A8378, 0.7f), 0.07);
			case LIGHT -> new Style(0xFFE9A8, 0xFFFFFF, ParticleTypes.END_ROD, ParticleTypes.WAX_OFF, 0.2,
					AuraFx.dust(0xFFD966, 0.65f), 0.15);
			case BLOOD -> new Style(0xB0121B, 0x4A0008, null, Blood.DROPS, 0.2,
					AuraFx.dust(0x8B0A14, 0.7f), 0.08);
			case VOID -> new Style(0x2A0A4A, 0x7A2BD6, ParticleTypes.REVERSE_PORTAL, ParticleTypes.REVERSE_PORTAL, 0.25,
					AuraFx.dust(0x1A0630, 0.75f), -0.1);
		};
	}

	/** Every server tick per player. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		if (!AuraConfig.AURA_EFFECT_ENABLED || data.aura == null) return;
		if (now % Math.max(1, AuraConfig.AURA_EFFECT_INTERVAL_TICKS) != 0) return;
		if (!player.isAlive() || player.isSpectator() || player.isInvisible() || ShadowCloak.isCloaked(player)) return;
		Style s = style(data.aura);
		Specks out = new Specks(player.serverLevel(), player, AuraConfig.AURA_EFFECT_SELF_SHARE);
		var random = player.getRandom();

		// the halo: a ring over the head, turning slowly, two colors alternating
		double cx = player.getX(), cz = player.getZ();
		double y = player.getY() + player.getBbHeight() + 0.28;
		double r = AuraConfig.AURA_EFFECT_HALO_RADIUS;
		int points = Math.max(4, AuraConfig.AURA_EFFECT_HALO_POINTS);
		double spin = now * 0.05;
		// the halo style worn (cosmetic): the classic ring, a crown, twin rings, a tilted orbit, or starlight
		var style = com.toast.aurapoints.cosmetic.Cosmetics.worn(data, com.toast.aurapoints.cosmetic.Cosmetics.Kind.HALO);
		if (!AuraConfig.COSMETICS_ENABLED) style = null;
		for (int i = 0; i < points; i++) {
			double a = spin + i * Math.PI * 2 / points;
			double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
			double py = y;
			if (style == com.toast.aurapoints.cosmetic.Cosmetics.Cosmetic.HALO_ORBIT) py += Math.sin(a - spin * 2) * 0.22;
			out.send(AuraFx.dust(i % 2 == 0 ? s.ringRgb() : s.ringRgb2(), 0.55f), x, py, z);
			if (s.accent() != null && i % 4 == 0 && random.nextFloat() < 0.35f) out.send(s.accent(), x, py + 0.02, z);
			if (style == com.toast.aurapoints.cosmetic.Cosmetics.Cosmetic.HALO_CROWN && i % 2 == 0) {
				out.send(AuraFx.dust(s.ringRgb2(), 0.45f), x, y + 0.1, z);
				out.send(AuraFx.dust(s.ringRgb(), 0.4f), x, y + 0.19, z);
			}
			if (style == com.toast.aurapoints.cosmetic.Cosmetics.Cosmetic.HALO_TWIN && i % 2 == 0) {
				double b = -spin * 1.5 + i * Math.PI * 2 / points;
				out.send(AuraFx.dust(s.ringRgb2(), 0.45f), cx + Math.cos(b) * r * 0.7, y + 0.2, cz + Math.sin(b) * r * 0.7);
			}
		}
		if (style == com.toast.aurapoints.cosmetic.Cosmetics.Cosmetic.HALO_STARLIGHT && random.nextFloat() < 0.6f) {
			double a = random.nextDouble() * Math.PI * 2;
			out.send(random.nextBoolean() ? ParticleTypes.END_ROD : ParticleTypes.WAX_ON, cx + Math.cos(a) * r, y + 0.05, cz + Math.sin(a) * r);
		}
		// what drips off it
		if (random.nextDouble() < s.dropChance() * AuraConfig.AURA_EFFECT_DROP_MULTIPLIER) {
			double a = random.nextDouble() * Math.PI * 2;
			out.send(s.drop(), cx + Math.cos(a) * r, y - 0.05, cz + Math.sin(a) * r);
		}

		// circling specks around the body (Frost has its Ice Shell flakes for this)
		if (s.orbit() != null) {
			for (int i = 0; i < 2; i++) {
				double a = now * s.orbitSpeed() * (i == 0 ? 1 : -1) + i * Math.PI;
				double oy = player.getY() + 0.45 + 0.5 * (1 + Math.sin(now * 0.05 + i * 2.1));
				out.send(s.orbit(), cx + Math.cos(a) * 0.7, oy, cz + Math.sin(a) * 0.7);
			}
		}
	}

	/** An aura-touched mob's halo: the ring over its head and what drips from it, for everyone. */
	static void mobHalo(ServerLevel level, net.minecraft.world.entity.LivingEntity mob, AuraType aura, long now) {
		Style s = style(aura);
		double cx = mob.getX(), cz = mob.getZ();
		double y = mob.getY() + mob.getBbHeight() + 0.25;
		double r = Math.max(0.3, mob.getBbWidth() * 0.55);
		int points = 10;
		double spin = now * 0.05;
		for (int i = 0; i < points; i++) {
			double a = spin + i * Math.PI * 2 / points;
			AuraFx.particles(level, AuraFx.dust(i % 2 == 0 ? s.ringRgb() : s.ringRgb2(), 0.55f), cx + Math.cos(a) * r, y, cz + Math.sin(a) * r, 1, 0, 0, 0, 0);
		}
		if (mob.getRandom().nextDouble() < s.dropChance()) {
			double a = mob.getRandom().nextDouble() * Math.PI * 2;
			AuraFx.particles(level, s.drop(), cx + Math.cos(a) * r, y - 0.05, cz + Math.sin(a) * r, 1, 0, 0, 0, 0);
		}
	}

	/** One standing particle at a time to everyone near enough; the holder gets only {@code share} of them. */
	private static final class Specks {
		final ServerLevel level;
		final ServerPlayer holder;
		final double share;
		int sent;

		Specks(ServerLevel level, ServerPlayer holder, double share) {
			this.level = level;
			this.holder = holder;
			this.share = Math.max(0.0, Math.min(1.0, share));
		}

		void send(ParticleOptions particle, double x, double y, double z) {
			boolean holderGets = Math.floor((sent + 1) * share) > Math.floor(sent * share);
			sent++;
			for (ServerPlayer p : level.players()) {
				if (p == holder && !holderGets) continue;
				AuraFx.particles(level, p, particle, false, x, y, z, 0, 0, 0, 0, 0);
			}
		}
	}
}
