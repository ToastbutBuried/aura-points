package com.toast.aurapoints;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * DEV ONLY (system property aurapoints.aurashowcase=earth, with the showcase world): on login the dev player gets that
 * owner-only aura and plays each of its abilities in turn on training dummies - press by press, as the keys would -
 * while DevShowcaseClient saves the screenshots this queues (run/screenshots/aura_<aura>_<step>_<n>.png).
 */
@EventBusSubscriber(modid = AuraPoints.MOD_ID)
public final class DevAuraShowcase {

	private DevAuraShowcase() {
	}

	public static String aura() {
		return System.getProperty("aurapoints.aurashowcase", "");
	}

	public static boolean on() {
		return !aura().isEmpty();
	}

	/** A screenshot to take: game time, file name, camera (0 behind, 1 in front, 2 first person). */
	public record Shot(long at, String name, int camera) {
	}

	/** Read by the client thread (same JVM in a dev run). */
	public static final ConcurrentLinkedQueue<Shot> SHOTS = new ConcurrentLinkedQueue<>();

	/** One scripted moment: what to do, how far to turn first, and when to shoot it. */
	private record Step(String name, float yaw, float pitch, float turn, Runnable action, int camera, int... shots) {
	}

	private static final int EACH = 110;

	@SubscribeEvent
	public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!on() || !(event.getEntity() instanceof ServerPlayer player)) return;
		MinecraftServer server = player.getServer();
		AuraType aura = AuraType.byId(aura());
		if (server == null || aura == null) return;
		final Vec3[] home = new Vec3[1];
		AuraFx.later(server, 20, () -> {
			player.getAbilities().flying = false;
			player.onUpdateAbilities();
			home[0] = new Vec3(Math.floor(player.getX()) + 0.5, player.getY(), Math.floor(player.getZ()) + 0.5);
			player.teleportTo(player.serverLevel(), home[0].x, home[0].y, home[0].z, 0f, 5f);
			var data = AuraState.get(server).getOrCreate(player.getUUID());
			data.aura = aura;
			for (Ability a : aura.abilities) if (a.active) data.owned.add(a);
			data.awakened.add(aura);
			NetworkHandler.syncOwned(player, data);
			NetworkHandler.syncAura(player, data);
			AuraPoints.LOGGER.info("[aurashowcase] {} set", aura);
		});
		AuraFx.later(server, 50, () -> AuraPoints.LOGGER.info("[aurashowcase] armor {} toughness {} knockback resistance {} (on {})",
				player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR),
				player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS),
				player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE),
				player.level().getBlockState(player.getOnPos()).getBlock()));
		List<Step> steps = new ArrayList<>();
		if (aura == AuraType.EARTH) {
			steps.add(new Step("pillar", 0f, 8f, -40f, () -> press(player, Ability.PILLAR_LIFT), 0, 3, 6, 12, 22));
			steps.add(new Step("selfpillar", 90f, 35f, 0f, () -> press(player, Ability.PILLAR_LIFT), 1, 3, 8, 16, 30));
			steps.add(new Step("boulder", 0f, 6f, 0f, () -> {
				press(player, Ability.BOULDER_TOSS);
				AuraFx.later(server, 30, () -> {
					press(player, Ability.BOULDER_TOSS);
					AuraFx.later(server, 2, () -> turn(player, -35f));
				});
			}, 0, 4, 14, 33, 35, 38));
			steps.add(new Step("boulderfront", 0f, 0f, 0f, () -> press(player, Ability.BOULDER_TOSS), 1, 12));
			steps.add(new Step("quake", 0f, 20f, 0f, () -> press(player, Ability.QUAKE), 0, 3, 6, 9, 13));
			steps.add(new Step("rift", 0f, 40f, -30f, () -> Ultimates.press(player, server.getTickCount()), 0, 8, 14, 20, 30, 50, 66));
		}
		if (aura == AuraType.LIGHT) {
			steps.add(new Step("pulse", 0f, 15f, 0f, () -> {
				player.setHealth(8f);
				press(player, Ability.RADIANT_PULSE);
				AuraFx.later(server, 5, () -> AuraPoints.LOGGER.info("[aurashowcase] health 8 -> {}, horse burning {}", player.getHealth(),
						player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.horse.SkeletonHorse.class, new AABB(player.blockPosition()).inflate(20))
								.stream().map(h -> h.getHealth() + "/" + h.isOnFire()).toList()));
			}, 1, 2, 4, 7, 14));
			steps.add(new Step("flash", 0f, 8f, 0f, () -> press(player, Ability.SOLAR_FLASH), 0, 1, 3, 8));
			steps.add(new Step("flashfront", 0f, 8f, 0f, () -> press(player, Ability.SOLAR_FLASH), 1, 2, 5));
			steps.add(new Step("beam", 0f, 8f, -35f, () -> {
				press(player, Ability.PRISM_BEAM);
				AuraFx.later(server, 40, () -> com.toast.aurapoints.ability.Light.beamRelease(player, server.getTickCount()));
			}, 0, 6, 16, 30, 44));
			steps.add(new Step("beamfront", 0f, 8f, 0f, () -> {
				press(player, Ability.PRISM_BEAM);
				AuraFx.later(server, 20, () -> com.toast.aurapoints.ability.Light.beamRelease(player, server.getTickCount()));
			}, 1, 10));
			steps.add(new Step("sun", 0f, -15f, 0f, () -> Ultimates.press(player, server.getTickCount()), 0, 10, 24, 41, 71, 136, 141));
			steps.add(new Step("sunfront", 0f, -10f, 0f, () -> Ultimates.press(player, server.getTickCount()), 1, 12, 30, 139));
		}
		if (aura == AuraType.BLOOD) {
			steps.add(new Step("hemo", 0f, 8f, -30f, () -> press(player, Ability.HEMORRHAGE), 0, 2, 4, 9, 30));
			steps.add(new Step("tether", 0f, 8f, -40f, () -> press(player, Ability.BLOOD_TETHER), 0, 5, 21, 45, 80));
			steps.add(new Step("sacrifice", 0f, 8f, 0f, () -> {
				player.setHealth(20f);
				press(player, Ability.CRIMSON_SACRIFICE);
				AuraFx.later(server, 20, () -> {
					var cows = player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, new AABB(player.blockPosition()).inflate(20));
					if (cows.isEmpty()) return;
					var cow = cows.get(0);
					float before = cow.getHealth();
					player.attack(cow);
					AuraPoints.LOGGER.info("[aurashowcase] sacrifice: health 20 -> {}, punch took the cow {} -> {} (a bare fist is 1, doubled 2)",
							player.getHealth(), before, cow.getHealth());
				});
			}, 1, 3, 8, 14));
			steps.add(new Step("moon", 0f, -25f, 0f, () -> Ultimates.press(player, server.getTickCount()), 0, 10, 40, 100, 190, 240));
		}
		if (aura == AuraType.VOID) {
			steps.add(new Step("pull", 0f, 6f, -35f, () -> press(player, Ability.VOID_PULL), 0, 2, 5, 9, 16));
			steps.add(new Step("riftstep", 0f, 5f, 150f, () -> press(player, Ability.RIFT_STEP), 0, 4, 12, 40));
			steps.add(new Step("orb", 0f, 6f, -35f, () -> press(player, Ability.NULL_ORB), 0, 5, 10, 15, 19));
			steps.add(new Step("hole", 0f, 8f, -30f, () -> Ultimates.press(player, server.getTickCount()), 0, 6, 20, 50, 84, 91, 95));
		}
		// -Daurapoints.auraonly=rift,quake: just those steps
		String only = System.getProperty("aurapoints.auraonly", "");
		if (!only.isEmpty()) steps.removeIf(s -> !java.util.Arrays.asList(only.split(",")).contains(s.name()));
		run(server, player, home, steps, 0, 70);
	}

	private static void turn(ServerPlayer player, float by) {
		player.teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(), player.getYRot() + by, player.getXRot());
	}

	private static void press(ServerPlayer player, Ability ability) {
		AuraAbilities.clearCooldowns(player);
		boolean fired = AuraAbilities.tryActivate(player, ability, player.getServer().getTickCount());
		AuraPoints.LOGGER.info("[aurashowcase] {} fired={}", ability, fired);
	}

	private static void run(MinecraftServer server, ServerPlayer player, Vec3[] home, List<Step> steps, int i, int delay) {
		if (i >= steps.size()) {
			AuraFx.later(server, delay, () -> AuraPoints.LOGGER.info("[aurashowcase] finished"));
			return;
		}
		Step step = steps.get(i);
		AuraFx.later(server, delay, () -> {
			if (!AuraFx.alive(player)) return;
			// back on the spot, fresh dummies: one straight ahead, two either side, one behind
			var level = player.serverLevel();
			for (TrainingDummy d : level.getEntitiesOfClass(TrainingDummy.class, new AABB(player.blockPosition()).inflate(60))) d.discard();
			player.teleportTo(level, home[0].x, home[0].y, home[0].z, step.yaw(), step.pitch());
			player.setDeltaMovement(Vec3.ZERO);
			// a cow straight ahead (dummies don't budge, a cow flies), dummies either side and behind
			for (var cow : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, new AABB(player.blockPosition()).inflate(60))) cow.discard();
			var cow = net.minecraft.world.entity.EntityType.COW.create(level);
			if (cow != null) {
				cow.moveTo(home[0].x, home[0].y, home[0].z + 7, 180f, 0f);
				level.addFreshEntity(cow);
			}
			// and an undead that peaceful lets live (for the Light)
			for (var h : level.getEntitiesOfClass(net.minecraft.world.entity.animal.horse.SkeletonHorse.class, new AABB(player.blockPosition()).inflate(60))) h.discard();
			var horse = net.minecraft.world.entity.EntityType.SKELETON_HORSE.create(level);
			if (horse != null) {
				horse.moveTo(home[0].x + 3, home[0].y, home[0].z + 5, 200f, 0f);
				level.addFreshEntity(horse);
			}
			double[][] spots = {{4.5, 3.5}, {-4.5, 3.5}, {0, -5}};
			for (double[] s : spots) TrainingDummy.spawn(level, AuraPoints.TRAINING_DUMMY.get(), home[0].add(s[0], 0, s[1]), 180f);
		});
		AuraFx.later(server, delay + 15, () -> {
			if (!AuraFx.alive(player)) return;
			long now = player.serverLevel().getGameTime();
			for (int k = 0; k < step.shots().length; k++) {
				SHOTS.add(new Shot(now + step.shots()[k], "aura_" + aura() + "_" + step.name() + "_" + k, step.camera()));
			}
			AuraPoints.LOGGER.info("[aurashowcase] {}", step.name());
			step.action().run();
			if (step.turn() != 0) AuraFx.later(server, 2, () -> turn(player, step.turn()));
			run(server, player, home, steps, i + 1, EACH);
		});
	}
}
