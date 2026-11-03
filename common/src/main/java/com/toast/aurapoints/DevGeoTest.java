package com.toast.aurapoints;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.geo.AuraGeoEntity;
import com.toast.aurapoints.geo.ViperHead;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * DEV ONLY (system property aurapoints.geotest=true, set by gradle runClient -Pgeotest): on login, lines up every
 * GeckoLib ability model three blocks in front of the player, facing them - one row per model, its states side by
 * side (each held, the last one cycling through all of them) - so they can be screenshotted. Logs "[geotest] scene built".
 */
@EventBusSubscriber(modid = AuraPoints.MOD_ID)
public final class DevGeoTest {

	private DevGeoTest() {
	}

	public static boolean on() {
		return Boolean.getBoolean("aurapoints.geotest");
	}

	/** One row: the type and how many states it has. */
	private record Row(Supplier<? extends net.minecraft.world.entity.EntityType<? extends AuraGeoEntity>> type, int states, float scale) {
	}

	private static List<Row> rows() {
		return List.of(new Row(AuraPoints.VIPER_HEAD, ViperHead.GONE + 1, 1.6f),
				new Row(AuraPoints.SHADOW_CLONE, com.toast.aurapoints.geo.ShadowClone.GONE + 1, 1f),
				new Row(AuraPoints.SENTINEL_TREE, com.toast.aurapoints.geo.SentinelTree.GONE + 1, 1f),
				new Row(AuraPoints.KRAKEN_TENTACLE, com.toast.aurapoints.geo.KrakenTentacle.GONE + 1, 0.6f),
				new Row(AuraPoints.SONIC_BLOSSOM, com.toast.aurapoints.geo.SonicBlossom.GONE + 1, 1.5f),
				new Row(AuraPoints.LISTENING_TREE, com.toast.aurapoints.geo.ListeningTree.GONE + 1, 1f));
	}

	@SubscribeEvent
	public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!on() || !(event.getEntity() instanceof ServerPlayer player)) return;
		MinecraftServer server = player.getServer();
		AuraFx.later(server, 40, () -> {
			ServerLevel level = player.serverLevel();
			level.setDayTime(6000);
			level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
			player.setGameMode(GameType.SPECTATOR);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
			player.teleportTo(level, 0.5, y + 1.2, 0.5, 0f, 20f);
			List<AuraGeoEntity> all = new ArrayList<>();
			// -Pgeoonly=sonic_blossom,listening_tree: only those rows
			String only = System.getProperty("aurapoints.geoonly", "");
			List<Row> rows = rows().stream().filter(r -> only.isEmpty()
					|| List.of(only.split(",")).contains(net.minecraft.world.entity.EntityType.getKey(r.type().get()).getPath())).toList();
			for (int r = 0; r < rows.size(); r++) {
				Row row = rows.get(r);
				for (int s = 0; s <= row.states(); s++) {
					AuraGeoEntity e = row.type().get().create(level);
					if (e == null) continue;
					double x = 0.5 + (s - row.states() / 2.0) * 1.6;
					// facing the player (yaw 180 looks toward -Z)
					e.moveTo(x, y, 3.5 + r * 2.5, Float.parseFloat(System.getProperty("aurapoints.geoyaw", "180")), 0f);
					e.setScale(row.scale());
					e.setState(Math.min(s, row.states() - 1));
					level.addFreshEntity(e);
					all.add(e);
					if (s == row.states()) cycle(server, e, row.states(), 0);
					else if (!only.isEmpty() && s > 0 && s < row.states() - 1) pulse(server, e, s);
				}
			}
			for (int t = 1; t < 20 * 600; t += 10) AuraFx.later(server, t, () -> all.forEach(AuraGeoEntity::keepAlive));
			// -Pgeoonly=...,items: Mjolnir and the World Tree Bow as item displays (fixed, third person, gui) at eye height,
			// and a skeleton holding the bow that draws it again every 2.5 s
			// dummies from an earlier run were saved with the world
			if (!only.isEmpty()) level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(-10, y - 5, -10, 10, y + 5, 15),
					e -> e instanceof com.toast.aurapoints.arena.TrainingDummy || e instanceof net.minecraft.world.entity.Display.ItemDisplay
							|| e instanceof net.minecraft.world.entity.monster.AbstractSkeleton).forEach(net.minecraft.world.entity.Entity::discard);
			if (List.of(only.split(",")).contains("items")) {
				var ctxs = new net.minecraft.world.item.ItemDisplayContext[]{net.minecraft.world.item.ItemDisplayContext.FIXED,
						net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, net.minecraft.world.item.ItemDisplayContext.GUI};
				var items = List.of(AuraPoints.MJOLNIR_ITEM.get(), AuraPoints.WORLD_TREE_BOW.get());
				for (int i = 0; i < items.size(); i++) {
					for (int c = 0; c < ctxs.length; c++) {
						var d = new net.minecraft.world.entity.Display.ItemDisplay(net.minecraft.world.entity.EntityType.ITEM_DISPLAY, level);
						((com.toast.aurapoints.mixin.ItemDisplayAccessor) d).aurapoints$setItemStack(new net.minecraft.world.item.ItemStack(items.get(i)));
						((com.toast.aurapoints.mixin.ItemDisplayAccessor) d).aurapoints$setItemTransform(ctxs[c]);
						((com.toast.aurapoints.mixin.DisplayAccessor) d).aurapoints$setTransformation(new com.mojang.math.Transformation(new org.joml.Vector3f(),
								new org.joml.Quaternionf().rotateY((float) Math.PI), new org.joml.Vector3f(0.9f), null));
						d.setPos(0.5 + (c - 1) * 0.75, y + 2.45 - i * 0.7, 2.2);
						level.addFreshEntity(d);
					}
				}
			}
			// -Pgeoonly=...,bowdraw: a skeleton side-on in front, drawing the World Tree Bow again every 2.5 s
			if (List.of(only.split(",")).contains("bowdraw")) {
				server.setDifficulty(net.minecraft.world.Difficulty.EASY, true); // peaceful removes the skeleton
				var sk = net.minecraft.world.entity.EntityType.SKELETON.create(level);
				if (sk != null) {
					sk.moveTo(0.9, y + 0.5, 2.4, 90f, 0f);
					sk.setNoGravity(true);
					sk.setYBodyRot(90f);
					sk.setYHeadRot(90f);
					sk.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET)); // no burning
					sk.setNoAi(true);
					sk.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(AuraPoints.WORLD_TREE_BOW.get()));
					level.addFreshEntity(sk);
					drawLoop(server, sk);
				}
			}
			// -Pgeoonly=...,training_dummy: a dummy in front, hit from its right every 2.5 s (light and heavy in turn)
			if (List.of(only.split(",")).contains("training_dummy")) {
				var dummy = AuraPoints.TRAINING_DUMMY.get().create(level);
				if (dummy != null) {
					dummy.moveTo(0.5, y, 4.5, 180f, 0f);
					dummy.setYBodyRot(180f);
					dummy.setYHeadRot(180f);
					level.addFreshEntity(dummy);
					hitLoop(server, dummy, 0);
				}
			}
			AuraPoints.LOGGER.info("[geotest] scene built: {} models", all.size());
		});
	}

	private static void drawLoop(MinecraftServer server, net.minecraft.world.entity.LivingEntity e) {
		AuraFx.later(server, 50, () -> {
			if (e.isRemoved()) return;
			e.stopUsingItem();
			AuraFx.later(server, 4, () -> e.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND));
			drawLoop(server, e);
		});
	}

	private static void hitLoop(MinecraftServer server, com.toast.aurapoints.arena.TrainingDummy dummy, int k) {
		AuraFx.later(server, 50, () -> {
			if (dummy.isRemoved()) return;
			var src = new net.minecraft.world.damagesource.DamageSource(dummy.level().damageSources().generic().typeHolder(), dummy.position().add(-3, 1, 0));
			dummy.invulnerableTime = 0;
			dummy.hurt(src, k % 2 == 0 ? 3f : 15f);
			hitLoop(server, dummy, k + 1);
		});
	}

	/** The last one of a row runs through its states, 3 s each. */
	/** -Pgeoonly: one-shot states (a burst, a hit) replay every 2 s, so a screenshot can catch them. */
	private static void pulse(MinecraftServer server, AuraGeoEntity e, int state) {
		AuraFx.later(server, 40, () -> {
			if (e.isRemoved()) return;
			e.setState(0);
			AuraFx.later(server, 3, () -> e.setState(state));
			pulse(server, e, state);
		});
	}

	private static void cycle(MinecraftServer server, AuraGeoEntity e, int states, int at) {
		e.setState(at % states);
		AuraFx.later(server, 60, () -> {
			if (!e.isRemoved()) cycle(server, e, states, at + 1);
		});
	}
}
