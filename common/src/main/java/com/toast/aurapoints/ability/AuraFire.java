package com.toast.aurapoints.ability;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Real fire blocks set by Fire aura abilities (Fireball impact, the end of a Heat Wave). Only where
 * flint and steel could light one (air with something burnable or solid under/next to it), never in
 * spawn protection, and never for a player who can't build here (adventure mode).
 *
 * Each fire is put out again after AURA_FIRE_SECONDS: with the doFireTick gamerule off, fire never
 * burns out by itself. Remembered in memory only; a fire still burning at a restart stays.
 */
public final class AuraFire {

	private AuraFire() {
	}

	private record Lit(ServerLevel level, BlockPos pos, long until) {
	}

	private static final java.util.List<Lit> LIT = new java.util.ArrayList<>();

	/** Every server tick: puts out the fires whose time is up (if they are still fire). */
	public static void tick(net.minecraft.server.MinecraftServer server) {
		if (LIT.isEmpty()) return;
		long now = server.getTickCount();
		LIT.removeIf(l -> {
			if (l.level().getServer() != server) return true; // a world from an earlier session (single player)
			if (now < l.until() && l.until() - now <= 20L * 3600) return false;
			if (l.level().isLoaded(l.pos()) && l.level().getBlockState(l.pos()).getBlock() instanceof BaseFireBlock) {
				l.level().removeBlock(l.pos(), false);
				AuraFx.particles(l.level(), ParticleTypes.SMOKE, l.pos().getX() + 0.5, l.pos().getY() + 0.2, l.pos().getZ() + 0.5, 4, 0.2, 0.1, 0.2, 0.01);
			}
			return true;
		});
	}

	/** Lights a fire at the first fitting spot from one block above {@code near} down to two below. True if it did. */
	public static boolean light(ServerLevel level, ServerPlayer by, BlockPos near) {
		if (by != null && !by.mayBuild()) return false;
		for (int dy = 1; dy >= -2; dy--) {
			BlockPos pos = near.offset(0, dy, 0);
			if (!level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()) continue;
			if (!BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) continue;
			if (by != null && !level.mayInteract(by, pos)) return false;
			level.setBlock(pos, BaseFireBlock.getState(level, pos), 11);
			if (com.toast.aurapoints.config.AuraConfig.AURA_FIRE_SECONDS > 0) {
				long now = level.getServer().getTickCount();
				LIT.add(new Lit(level, pos.immutable(), now + Math.round(com.toast.aurapoints.config.AuraConfig.AURA_FIRE_SECONDS * 20)));
			}
			AuraFx.particles(level, ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 6, 0.25, 0.2, 0.25, 0.02);
			return true;
		}
		return false;
	}

	/** Up to {@code count} fires at random spots within {@code radius} blocks of {@code center}. Returns how many were lit. */
	public static int scatter(ServerLevel level, ServerPlayer by, Vec3 center, double radius, int count) {
		if (count <= 0) return 0;
		var random = level.getRandom();
		Set<BlockPos> tried = new HashSet<>();
		int lit = 0;
		for (int attempt = 0; attempt < count * 8 && lit < count; attempt++) {
			double a = random.nextDouble() * Math.PI * 2, d = Math.sqrt(random.nextDouble()) * radius;
			BlockPos pos = BlockPos.containing(center.x + Math.cos(a) * d, center.y, center.z + Math.sin(a) * d);
			if (!tried.add(pos)) continue;
			if (light(level, by, pos)) lit++;
		}
		return lit;
	}
}
