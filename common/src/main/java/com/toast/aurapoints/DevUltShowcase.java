package com.toast.aurapoints;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Ultimates;
import com.toast.aurapoints.arena.TrainingDummy;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;

/**
 * DEV ONLY (system property aurapoints.ultshowcase=true, with the showcase world; aurapoints.ultonly=fire,wind
 * for just some): on login, every aura with an Ultimate in turn - awakened (the ceremony plays the first time),
 * training dummies set round the player as targets, the Ultimate pressed - while DevShowcaseClient saves
 * screenshots of each (run/screenshots/ult_*.png).
 */
@EventBusSubscriber(modid = AuraPoints.MOD_ID)
public final class DevUltShowcase {

	private DevUltShowcase() {
	}

	public static boolean on() {
		return Boolean.getBoolean("aurapoints.ultshowcase");
	}

	/** Ticks from one Ultimate to the next. */
	public static final int EACH = 220;

	@SubscribeEvent
	public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!on() || !(event.getEntity() instanceof ServerPlayer player)) return;
		MinecraftServer server = player.getServer();
		if (server == null) return;
		final Vec3[] home = new Vec3[1];
		AuraFx.later(server, 20, () -> {
			player.getAbilities().flying = false;
			player.onUpdateAbilities();
			home[0] = new Vec3(Math.floor(player.getX()) + 0.5, player.getY(), Math.floor(player.getZ()) + 0.5);
			player.teleportTo(player.serverLevel(), home[0].x, home[0].y, home[0].z, 0f, -8f);
		});
		String only = System.getProperty("aurapoints.ultonly", "");
		java.util.List<AuraType> auras = new java.util.ArrayList<>();
		for (AuraType aura : AuraType.values()) {
			if (Ultimates.of(aura) == null) continue;
			if (!only.isEmpty() && !java.util.Arrays.asList(only.split(",")).contains(aura.id())) continue;
			auras.add(aura);
		}
		// one after the other, each scheduling the next (AuraFx.later runs anything more than a minute ahead at once)
		play(server, player, auras, 0, home, 60);
	}

	private static void play(MinecraftServer server, ServerPlayer player, java.util.List<AuraType> auras, int i, Vec3[] home, int delay) {
		if (i >= auras.size()) return;
		AuraType aura = auras.get(i);
		AuraFx.later(server, delay, () -> {
			if (!AuraFx.alive(player)) return;
			var data = AuraState.get(server).getOrCreate(player.getUUID());
			data.aura = aura;
			NetworkHandler.syncAura(player, data);
			Ultimates.awaken(player, aura, true);
			AuraPoints.LOGGER.info("[ultshowcase] {} awakened", aura);
		});
		AuraFx.later(server, delay + 80, () -> {
			// back on the start spot, dummies round it (none left from the last one)
			var level = player.serverLevel();
			for (TrainingDummy d : level.getEntitiesOfClass(TrainingDummy.class, new AABB(player.blockPosition()).inflate(60))) d.discard();
			player.teleportTo(level, home[0].x, home[0].y, home[0].z, 0f, aura == AuraType.FIRE ? -12f : -8f);
			for (int k = 0; k < 4; k++) {
				double a = Math.toRadians(new double[]{55, -55, 125, -125}[k]);
				Vec3 pos = home[0].add(-Math.sin(a) * 5, 0, Math.cos(a) * 5);
				TrainingDummy.spawn(level, AuraPoints.TRAINING_DUMMY.get(), pos, 0f);
			}
		});
		AuraFx.later(server, delay + 100, () -> {
			AuraPoints.LOGGER.info("[ultshowcase] {} pressed", Ultimates.of(aura));
			Ultimates.press(player, server.getTickCount());
			play(server, player, auras, i + 1, home, EACH);
		});
	}
}
