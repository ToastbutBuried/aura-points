package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;

/** DEV ONLY (system property aurapoints.devtest=true): /auradev vine - fires a Vine Hook from a fake player at a zombie and logs where the zombie goes. */
final class DevTest {

	private DevTest() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		if (!Boolean.getBoolean("aurapoints.devtest")) return;
		com.toast.aurapoints.arena.ArenaDevTest.register(dispatcher);
		registerResetTest(dispatcher);
		registerBookTest(dispatcher);
		registerMastery(dispatcher);
		registerBloodBar(dispatcher);
		// balance editor write path: set a value + a switch through AuraBalance.apply, check, then put both back
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("balance").executes(ctx -> {
			var e = com.toast.aurapoints.config.AuraBalance.entry("FIREBALL_DAMAGE");
			var sw = com.toast.aurapoints.config.AuraBalance.entry("ABILITIES_ENABLED.FIREBALL");
			Object was = e.current();
			var r1 = com.toast.aurapoints.config.AuraBalance.apply(java.util.Map.of(e.key(), 12.5, sw.key(), false));
			log("balance set: dmg=" + e.current() + " fireball on=" + sw.current() + " report=" + r1.stream().filter(l -> !l.startsWith("WARNING")).toList());
			com.toast.aurapoints.config.AuraBalance.apply(java.util.Map.of(e.key(), was, sw.key(), true));
			log("balance back: dmg=" + e.current() + " fireball on=" + sw.current() + " entries=" + com.toast.aurapoints.config.AuraBalance.entries().size()
					+ " backup=" + com.toast.aurapoints.config.AuraBalance.backup());
			return 1;
		})));
		// /auradev reactions: for every reaction a zombie gets its mark from one aura, then the matching hit from another; logs what happened
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("reactions").executes(ctx -> {
			var server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[AuraDev]"));
			var data = com.toast.aurapoints.data.AuraState.get(server).getOrCreate(p.getUUID());
			var was = data.aura;
			int i = 0;
			for (var r : com.toast.aurapoints.ability.Reactions.Reaction.values()) {
				double x = 0.5 + 12 * i++;
				level.setChunkForced(((int) x) >> 4, 0, true);
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) x, 10);
				p.moveTo(x, y, 0.5, 0f, 0f);
				// the target is a zombie, the two bystanders are pigs (undead can't be poisoned)
				java.util.List<net.minecraft.world.entity.Mob> zs = new java.util.ArrayList<>();
				for (int k = 0; k < 3; k++) {
					net.minecraft.world.entity.Mob z = k == 0 ? new Zombie(EntityType.ZOMBIE, level) : new net.minecraft.world.entity.animal.Pig(EntityType.PIG, level);
					z.moveTo(x + (k == 0 ? 0 : 1.5), y, 10.5 + (k == 2 ? 2 : 0), 180f, 0f);
					z.setNoAi(true);
					z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
					z.setHealth(200);
					level.addFreshEntity(z);
					zs.add(z);
				}
				var z = zs.get(0);
				// the mark: from the aura whose element leaves it (Wind has none, so Firestorm/Storm get theirs from Fire/Thunder)
				com.toast.aurapoints.ability.AuraType markAura = switch (r) {
					case FREEZE, CONDUCT, OVERGROWTH -> com.toast.aurapoints.ability.AuraType.WATER;
					case FIRESTORM, STEAM -> com.toast.aurapoints.ability.AuraType.FIRE;
					case TOXIC_CLOUD -> com.toast.aurapoints.ability.AuraType.VERDANT;
					case MELT -> com.toast.aurapoints.ability.AuraType.FROST;
					case STORM -> com.toast.aurapoints.ability.AuraType.THUNDER;
				};
				com.toast.aurapoints.ability.AuraType hitAura = switch (r) {
					case FREEZE, MELT -> r == com.toast.aurapoints.ability.Reactions.Reaction.FREEZE ? com.toast.aurapoints.ability.AuraType.FROST : com.toast.aurapoints.ability.AuraType.FIRE;
					case CONDUCT -> com.toast.aurapoints.ability.AuraType.THUNDER;
					case FIRESTORM, STORM -> com.toast.aurapoints.ability.AuraType.WIND;
					case TOXIC_CLOUD -> com.toast.aurapoints.ability.AuraType.FIRE;
					case STEAM -> com.toast.aurapoints.ability.AuraType.WATER;
					case OVERGROWTH -> com.toast.aurapoints.ability.AuraType.VERDANT;
				};
				data.aura = markAura;
				for (var m : zs) com.toast.aurapoints.ability.Reactions.devHit(p, m, 1f);
				long now = server.getTickCount();
				String marks = com.toast.aurapoints.ability.Reactions.describe(z, now);
				float before = z.getHealth();
				data.aura = hitAura;
				com.toast.aurapoints.ability.Reactions.devHit(p, z, 1f);
				AuraPoints.LOGGER.info("[auradev] reaction {}: marks before={} after={} hp {} -> {} (plain hit 1 = {})", r, marks,
						com.toast.aurapoints.ability.Reactions.describe(z, now), before, z.getHealth(), before - 1);
				// a second matching hit right away: on cooldown, so it must only mark
				float before2 = z.getHealth();
				com.toast.aurapoints.ability.Reactions.devHit(p, z, 1f);
				AuraPoints.LOGGER.info("[auradev] reaction {} again (cooldown): hp {} -> {}", r, before2, z.getHealth());
				final var rr = r;
				com.toast.aurapoints.ability.AuraFx.later(server, 3, () -> AuraPoints.LOGGER.info(
						"[auradev] reaction {} +3t: frozen={} rooted={} fire={} poison={} blind={} others hp={} {} vy={}", rr,
						com.toast.aurapoints.ability.IceSpike.isFrozen(z), com.toast.aurapoints.ability.Reactions.isRooted(z), z.isOnFire(),
						zs.get(1).hasEffect(net.minecraft.world.effect.MobEffects.POISON), zs.get(1).hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS),
						zs.get(1).getHealth(), zs.get(2).getHealth(), String.format("%.2f", zs.get(1).getDeltaMovement().y)));
				com.toast.aurapoints.ability.AuraFx.later(server, 25, () -> AuraPoints.LOGGER.info("[auradev] reaction {} +25t: poison={} frozen={} rooted={}", rr,
						zs.get(1).hasEffect(net.minecraft.world.effect.MobEffects.POISON), com.toast.aurapoints.ability.IceSpike.isFrozen(z),
						com.toast.aurapoints.ability.Reactions.isRooted(z)));
			}
			data.aura = was;
			AuraPoints.LOGGER.info("[auradev] reactions: stunt xp total={} (8 first-time stunts x STUNT_XP_POINTS when progress isn't paused)", p.totalExperience);
			return 1;
		})));
		// /auradev combos: for every team combo, fake player A leaves the zone, ally B's aura passes through it; zombies inside, A stands inside too
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("combos").executes(ctx -> {
			var server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			net.minecraft.server.level.ServerPlayer a = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CA001"), "[ComboA]"));
			net.minecraft.server.level.ServerPlayer b = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CA002"), "[ComboB]"));
			net.minecraft.server.level.ServerPlayer c = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CA003"), "[ComboC]"));
			for (net.minecraft.server.level.ServerPlayer f : java.util.List.of(a, b, c)) com.toast.aurapoints.ability.Combos.TEST_PLAYERS.put(f.getUUID(), f);
			com.toast.aurapoints.ability.Allies.TEST_PARTY.add(a.getUUID());
			com.toast.aurapoints.ability.Allies.TEST_PARTY.add(b.getUUID());
			var state = com.toast.aurapoints.data.AuraState.get(server);
			int aStart = com.toast.aurapoints.AuraCurrency.get(a), bStart = com.toast.aurapoints.AuraCurrency.get(b);
			int i = 0;
			for (var combo : com.toast.aurapoints.ability.Combos.Combo.values()) {
				double x = 0.5 + 16 * i++;
				level.setChunkForced(((int) Math.floor(x)) >> 4, 4, true);
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) x, 70);
				net.minecraft.world.phys.Vec3 center = new net.minecraft.world.phys.Vec3(x, y + 1, 70.5);
				var kind = switch (combo) {
					case FIRE_TORNADO, MAGMA_FIELD -> com.toast.aurapoints.ability.Combos.Kind.FIRE;
					case TOXIC_GALE -> com.toast.aurapoints.ability.Combos.Kind.POISON;
					default -> com.toast.aurapoints.ability.Combos.Kind.BUBBLE;
				};
				var zoneAura = switch (kind) {
					case FIRE -> com.toast.aurapoints.ability.AuraType.FIRE;
					case POISON -> com.toast.aurapoints.ability.AuraType.VERDANT;
					case BUBBLE -> com.toast.aurapoints.ability.AuraType.WATER;
				};
				var trigger = switch (combo) {
					case FIRE_TORNADO, TOXIC_GALE -> com.toast.aurapoints.ability.AuraType.WIND;
					case ELECTRIFIED_BUBBLE -> com.toast.aurapoints.ability.AuraType.THUNDER;
					case GLACIER -> com.toast.aurapoints.ability.AuraType.FROST;
					case MAGMA_FIELD -> com.toast.aurapoints.ability.AuraType.EARTH;
					case BOILING_BUBBLE -> com.toast.aurapoints.ability.AuraType.FIRE;
					case PRISM_BURST -> com.toast.aurapoints.ability.AuraType.LIGHT;
				};
				state.getOrCreate(a.getUUID()).aura = zoneAura;
				a.moveTo(x + 1, y, 70.5, 0f, 0f);
				a.setHealth(a.getMaxHealth());
				b.moveTo(x, y, 60.5, 0f, 0f);
				c.moveTo(x, y, 60.5, 0f, 0f);
				java.util.List<Zombie> zs = new java.util.ArrayList<>();
				for (int k = 0; k < 2; k++) {
					Zombie z = new Zombie(EntityType.ZOMBIE, level);
					z.moveTo(x - 1 + k * 0.5, y, 70.5 + k, 0f, 0f);
					z.setNoAi(true);
					z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
					z.setHealth(200);
					level.addFreshEntity(z);
					zs.add(z);
				}
				final int xi = i;
				com.toast.aurapoints.ability.AuraFx.later(server, 5 + 2 * xi, () -> {
				a.moveTo(x + 1, y, 70.5, 0f, 0f);
				state.getOrCreate(a.getUUID()).aura = zoneAura;
				com.toast.aurapoints.ability.Combos.zone(a, kind, center, 4.0, 200, null, kind == com.toast.aurapoints.ability.Combos.Kind.FIRE);
				// wrong aura from the ally, then the right aura from a stranger: both must do nothing
				state.getOrCreate(b.getUUID()).aura = com.toast.aurapoints.ability.AuraType.SHADOW;
				boolean wrong = com.toast.aurapoints.ability.Combos.at(b, center, 1.0);
				state.getOrCreate(c.getUUID()).aura = trigger;
				boolean stranger = com.toast.aurapoints.ability.Combos.at(c, center, 1.0);
				boolean far = com.toast.aurapoints.ability.Combos.at(b, center.add(20, 0, 0), 1.0);
				state.getOrCreate(b.getUUID()).aura = trigger;
				boolean far2 = com.toast.aurapoints.ability.Combos.at(b, center.add(20, 0, 0), 1.0);
				boolean fired = com.toast.aurapoints.ability.Combos.at(b, center.add(0, 0, -6), 3.0);
				boolean again = com.toast.aurapoints.ability.Combos.at(b, center, 1.0);
				AuraPoints.LOGGER.info("[auradev] combo {}: wrongAura={} stranger={} far={} fired={} (must be true) again={} (must be false, zone used up) running={}",
						combo, wrong, stranger, far || far2, fired, again, com.toast.aurapoints.ability.Combos.running());
				});
				for (int t : new int[]{20, 70, 130}) {
					com.toast.aurapoints.ability.AuraFx.later(server, t, () -> AuraPoints.LOGGER.info(
							"[auradev] combo {} +{}t: zombies hp={} {} frozen={} fire={} effects={} pos={} | ally A hp={} frozen={} | running={}",
							combo, t, zs.get(0).getHealth(), zs.get(1).getHealth(),
							com.toast.aurapoints.ability.IceSpike.isFrozen(zs.get(0)), zs.get(0).isOnFire(),
							zs.get(0).getActiveEffects().stream().map(e -> e.getEffect().getRegisteredName().replace("minecraft:", "")).toList(),
							String.format("%.1f,%.1f,%.1f", zs.get(0).getX(), zs.get(0).getY(), zs.get(0).getZ()),
							a.getHealth(), com.toast.aurapoints.ability.IceSpike.isFrozen(a), com.toast.aurapoints.ability.Combos.running()));
				}
			}
			com.toast.aurapoints.ability.AuraFx.later(server, 160, () -> {
				AuraPoints.LOGGER.info("[auradev] combos: cooldownsDisabled={} (true pauses rewards)", state.cooldownsDisabled);
				AuraPoints.LOGGER.info("[auradev] combos: aura A {} -> {}, B {} -> {} (must be +{} each: reward cooldown after the first), zones left={} effects left={}",
						aStart, com.toast.aurapoints.AuraCurrency.get(a), bStart, com.toast.aurapoints.AuraCurrency.get(b),
						com.toast.aurapoints.config.AuraConfig.COMBO_AURA_REWARD, com.toast.aurapoints.ability.Combos.zoneCount(),
						com.toast.aurapoints.ability.Combos.effectCount());
				com.toast.aurapoints.ability.Combos.TEST_PLAYERS.clear();
				com.toast.aurapoints.ability.Allies.TEST_PARTY.clear();
			});
			return 1;
		})));
		// /auradev touched: a forced natural roll, one touched zombie per aura against a villager, then a kill for the reward
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("touched").executes(ctx -> {
			var server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[AuraDev]"));
			double was = com.toast.aurapoints.config.AuraConfig.TOUCHED_CHANCE;
			com.toast.aurapoints.config.AuraConfig.TOUCHED_CHANCE = 1.0;
			Zombie natural = new Zombie(EntityType.ZOMBIE, level);
			natural.moveTo(0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, -20), -20.5);
			com.toast.aurapoints.shim.neoforge.event.EventHooks.finalizeMobSpawn(natural, level, level.getCurrentDifficultyAt(natural.blockPosition()), net.minecraft.world.entity.MobSpawnType.NATURAL, null);
			com.toast.aurapoints.config.AuraConfig.TOUCHED_CHANCE = was;
			level.addFreshEntity(natural);
			AuraPoints.LOGGER.info("[auradev] touched natural: aura={} name={} hp={}/{} glowing={} team={}", com.toast.aurapoints.ability.TouchedMobs.auraOf(natural),
					natural.getName().getString(), natural.getHealth(), natural.getMaxHealth(), natural.hasGlowingTag(),
					server.getScoreboard().getPlayersTeam(natural.getScoreboardName()) == null ? null : server.getScoreboard().getPlayersTeam(natural.getScoreboardName()).getName());
			int i = 0;
			for (var aura : com.toast.aurapoints.ability.AuraType.values()) {
				double x = 0.5 + 12 * i++;
				level.setChunkForced(((int) Math.floor(x)) >> 4, 2, true);
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) x, 40);
				Zombie z = new Zombie(EntityType.ZOMBIE, level);
				z.moveTo(x, y, 40.5, 0f, 0f);
				com.toast.aurapoints.ability.TouchedMobs.touch(z, aura);
				level.addFreshEntity(z);
				var v = new net.minecraft.world.entity.npc.Villager(EntityType.VILLAGER, level);
				v.moveTo(x, y, 45.5, 180f, 0f);
				v.setNoAi(true);
				v.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
				v.setHealth(100);
				level.addFreshEntity(v);
				z.setTarget(v);
				z.setNoAi(true); // stays put: only the aura ability acts
				com.toast.aurapoints.ability.AuraFx.later(server, 120, () -> AuraPoints.LOGGER.info(
						"[auradev] touched {}: name={} zhp={}/{} glow={} | villager hp={} pos={} effects={} | zombie pos={}", aura, z.getName().getString(),
						z.getHealth(), z.getMaxHealth(), z.hasGlowingTag(), v.getHealth(), String.format("%.1f,%.1f,%.1f", v.getX(), v.getY(), v.getZ()),
						v.getActiveEffects().stream().map(e -> e.getEffect().getRegisteredName()).toList(), String.format("%.1f,%.1f,%.1f", z.getX(), z.getY(), z.getZ())));
				if (aura == com.toast.aurapoints.ability.AuraType.FIRE) {
					com.toast.aurapoints.ability.AuraFx.later(server, 130, () -> {
						int before = com.toast.aurapoints.AuraCurrency.get(p);
						z.invulnerableTime = 0;
						z.hurt(level.damageSources().playerAttack(p), 1000f);
						AuraPoints.LOGGER.info("[auradev] touched kill: dead={} points {} -> {}", z.isDeadOrDying(), before, com.toast.aurapoints.AuraCurrency.get(p));
					});
					com.toast.aurapoints.ability.AuraFx.later(server, 160, () -> AuraPoints.LOGGER.info("[auradev] touched after death: removed={} team={}", z.isRemoved(),
							server.getScoreboard().getPlayersTeam(z.getScoreboardName())));
				}
			}
			return 1;
		})));
		// /auradev weekly: this week's challenges, each one finished in turn, then the cosmetics they and Aura Points unlock
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("weekly").executes(ctx -> {
			var server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[AuraDev]"));
			var data = com.toast.aurapoints.data.AuraState.get(server).getOrCreate(p.getUUID());
			data.weeklyWeek = Long.MIN_VALUE; // a fresh week for the fake player
			data.weeklyCompleted = 0;
			data.weeklyFullWeeks = 0;
			data.cosmetics.clear();
			data.equipped.clear();
			AuraPoints.LOGGER.info("[auradev] weekly: {}", com.toast.aurapoints.cosmetic.Weekly.describe());
			com.toast.aurapoints.cosmetic.Weekly.refresh(p);
			int start = com.toast.aurapoints.AuraCurrency.get(p);
			for (int i = 0; i < 3; i++) {
				com.toast.aurapoints.cosmetic.Weekly.devComplete(p, i);
				AuraPoints.LOGGER.info("[auradev] weekly after #{}: done={} completed={} full={} aura +{}", i, Integer.toBinaryString(data.weeklyDone),
						data.weeklyCompleted, data.weeklyFullWeeks, com.toast.aurapoints.AuraCurrency.get(p) - start);
			}
			com.toast.aurapoints.cosmetic.Weekly.devComplete(p, 0); // again: must not pay twice
			AuraPoints.LOGGER.info("[auradev] weekly repeat: completed={} aura +{} earned={}", data.weeklyCompleted, com.toast.aurapoints.AuraCurrency.get(p) - start, data.cosmetics);
			var crown = com.toast.aurapoints.cosmetic.Cosmetics.Cosmetic.HALO_CROWN;
			com.toast.aurapoints.AuraCurrency.set(p, 100);
			AuraPoints.LOGGER.info("[auradev] cosmetics buy poor: {}", com.toast.aurapoints.cosmetic.Cosmetics.buy(p, crown));
			com.toast.aurapoints.AuraCurrency.set(p, 1000);
			AuraPoints.LOGGER.info("[auradev] cosmetics buy: {} -> left {} worn={}", com.toast.aurapoints.cosmetic.Cosmetics.buy(p, crown),
					com.toast.aurapoints.AuraCurrency.get(p), data.equipped);
			AuraPoints.LOGGER.info("[auradev] cosmetics buy earn-only: {}", com.toast.aurapoints.cosmetic.Cosmetics.buy(p, com.toast.aurapoints.cosmetic.Cosmetics.Cosmetic.KILL_THUNDERCLAP));
			AuraPoints.LOGGER.info("[auradev] cosmetics equip title: {} -> {}", com.toast.aurapoints.cosmetic.Cosmetics.equip(p, com.toast.aurapoints.cosmetic.Cosmetics.Cosmetic.TITLE_CHAMPION),
					String.valueOf(com.toast.aurapoints.cosmetic.Cosmetics.titled(p, net.minecraft.network.chat.Component.literal("[AuraDev]"))));
			AuraPoints.LOGGER.info("[auradev] weekly board: {}", com.toast.aurapoints.cosmetic.Weekly.board().getString().replace("\n", " | "));
			return 1;
		})));
		// /auradev tickets56: Nightstalker's bonus per light level, and the aura_mastery scoreboards after /aura mastery set
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("tickets56").executes(ctx -> {
			var server = ctx.getSource().getServer();
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[AuraDev]"));
			StringBuilder curve = new StringBuilder();
			for (int light = 0; light <= 15; light++) curve.append(light).append('=').append(String.format(java.util.Locale.ROOT, "%.3f ", com.toast.aurapoints.ability.Nightstalker.bonus(light)));
			AuraPoints.LOGGER.info("[auradev] nightstalker bonus {}", curve);
			var data = com.toast.aurapoints.data.AuraState.get(server).getOrCreate(p.getUUID());
			data.aura = com.toast.aurapoints.ability.AuraType.SHADOW;
			com.toast.aurapoints.ability.Mastery.set(p, com.toast.aurapoints.ability.AuraType.FIRE, 3);
			com.toast.aurapoints.ability.Mastery.set(p, com.toast.aurapoints.ability.AuraType.SHADOW, 2);
			var board = server.getScoreboard();
			java.util.function.Function<String, String> score = name -> {
				var o = board.getObjective(name);
				var info = o == null ? null : board.getPlayerScoreInfo(p, o);
				return o == null ? "no objective" : info == null ? "unset" : String.valueOf(info.value());
			};
			int xp0 = p.totalExperience;
			com.toast.aurapoints.ability.AuraStunts.xp(p, false);
			int xp1 = p.totalExperience;
			com.toast.aurapoints.ability.AuraStunts.xp(p, true);
			AuraPoints.LOGGER.info("[auradev] stunt xp: normal +{} boss +{}", xp1 - xp0, p.totalExperience - xp1);
			AuraPoints.LOGGER.info("[auradev] mastery scores: fire={} shadow={} void={} held={}", score.apply("aura_mastery_fire"),
					score.apply("aura_mastery_shadow"), score.apply("aura_mastery_void"), score.apply("aura_mastery"));
			return 1;
		})));
		// /auradev icespike: a fake player charges a full Ice Spike at a zombie; logs the frost shard displays every tick
		// /auradev holds: every hold breaks when its holder is paralyzed (stun) and when frozen
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("holds").executes(ctx -> {
			ServerLevel level = ctx.getSource().getServer().overworld();
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
			for (boolean freeze : new boolean[]{false, true}) {
				net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[AuraDev]"));
				p.moveTo(20.5, y, 20.5, 0f, 0f);
				p.setOnGround(true);
				AuraPoints.LOGGER.info("[auradev] holds {}: {}", freeze ? "freeze" : "stun", com.toast.aurapoints.ability.Stun.devHolds(p, freeze));
			}
			return 1;
		})));
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("icespike").executes(ctx -> {
			var server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[AuraDev]"));
			p.moveTo(0.5, y, 0.5, 0f, 0f);
			Zombie z = new Zombie(EntityType.ZOMBIE, level);
			z.moveTo(0.5, y, 10.5, 180f, 0f);
			z.setNoAi(true);
			level.addFreshEntity(z);
			p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, z.getEyePosition());
			long start = server.getTickCount();
			boolean pressed = com.toast.aurapoints.ability.IceSpike.press(p, start);
			java.util.function.Supplier<String> shards = () -> level.getEntities(EntityType.ITEM_DISPLAY,
					new net.minecraft.world.phys.AABB(-20, y - 20, -20, 20, y + 20, 30), d -> d.getTags().contains("aurapoints_ice_spike"))
					.stream().map(d -> String.format("%.2f,%.2f,%.2f", d.getX(), d.getY(), d.getZ())).toList().toString();
			AuraPoints.LOGGER.info("[auradev] icespike pressed={} shards={}", pressed, shards.get());
			int full = com.toast.aurapoints.config.AuraConfig.ICE_SPIKE_FULL_CHARGE_TICKS;
			com.toast.aurapoints.ability.AuraFx.later(server, full, () -> {
				com.toast.aurapoints.ability.IceSpike.release(p, start + full);
				AuraPoints.LOGGER.info("[auradev] icespike released zombieHp={} shards={}", z.getHealth(), shards.get());
			});
			for (int t = full + 1; t <= full + 20; t++) {
				final int tick = t;
				com.toast.aurapoints.ability.AuraFx.later(server, t, () ->
						AuraPoints.LOGGER.info("[auradev] icespike t={} zombieHp={} frozen={} shards={}", tick, z.getHealth(),
								com.toast.aurapoints.ability.IceSpike.isFrozen(z), shards.get()));
			}
			return 1;
		})));
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("vine").then(Commands.argument("offset", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg()).executes(ctx -> {
			double offset = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "offset");
			ServerLevel level = ctx.getSource().getServer().overworld();
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[AuraDev]"));
			p.moveTo(0.5, y, 0.5, 0f, 0f); // facing +z
			Zombie z = new Zombie(EntityType.ZOMBIE, level);
			z.moveTo(0.5, y, 8.5, 180f, 0f);
			z.setNoAi(true);
			level.addFreshEntity(z);
			p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, z.getEyePosition().add(offset, 0, 0));
			boolean fired = com.toast.aurapoints.ability.VineHook.press(p, ctx.getSource().getServer().getTickCount());
			AuraPoints.LOGGER.info("[auradev] fired={} player={} zombie={}", fired, p.position(), z.position());
			for (int t = 1; t <= 24; t++) {
				final int tick = t;
				com.toast.aurapoints.ability.AuraFx.later(ctx.getSource().getServer(), t, () ->
						AuraPoints.LOGGER.info("[auradev] t={} zombie={} alive={} noPhysics={}", tick, z.position(), z.isAlive(), z.noPhysics));
			}
			return 1;
		}))).then(Commands.literal("verdant").then(Commands.argument("which", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
			String which = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "which");
			var server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[AuraDev]"));
			p.moveTo(0.5, y, 0.5, 0f, 0f);
			Zombie z = new Zombie(EntityType.ZOMBIE, level);
			z.moveTo(0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 12), 12.5, 180f, 0f);
			z.setNoAi(true);
			if (!which.equals("trap")) level.addFreshEntity(z);
			if (which.equals("trap")) p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, new net.minecraft.world.phys.Vec3(0.5, y, 6.5));
			else p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, z.getEyePosition());
			long now = server.getTickCount() + 100000; // clear of any cooldown
			boolean fired = switch (which) {
				case "viper" -> com.toast.aurapoints.ability.VerdantViper.press(p, now);
				case "viperturn" -> {
					// sent off facing -z, then the crosshair goes onto a zombie off to the side behind: it must turn round
					p.moveTo(0.5, y, 0.5, 180f, 0f);
					z.moveTo(7.5, y, 3.5, 0f, 0f);
					boolean ok = com.toast.aurapoints.ability.VerdantViper.press(p, now);
					p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, z.position().add(0, 1.0, 0));
					yield ok;
				}
				default -> com.toast.aurapoints.ability.Entanglement.press(p, now);
			};
			AuraPoints.LOGGER.info("[auradev] {} fired={} player={} zombie={}", which, fired, p.position(), z.position());
			for (int t = 1; t <= 130; t += (t < 30 ? 1 : 10)) {
				final int tick = t;
				com.toast.aurapoints.ability.AuraFx.later(server, t, () ->
						AuraPoints.LOGGER.info("[auradev] t={} zombie={} hp={} poison={} rooted={} entangled={} steering={} player={}", tick, z.position(), z.getHealth(),
								z.hasEffect(net.minecraft.world.effect.MobEffects.POISON), com.toast.aurapoints.ability.Rooted.isRooted(z),
								com.toast.aurapoints.ability.Entanglement.isEntangled(z), com.toast.aurapoints.ability.VerdantViper.isSteering(p), p.position()));
			}
			return 1;
		}))).then(Commands.literal("resonance").then(Commands.argument("which", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 6)).executes(ctx -> {
			// 1 blossom, 2 tree, 3 grasp + throw, 4 grasp held until it throws by itself: planted 5 blocks ahead of a fake player, three zombies walking in from further out
			int asked = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "which");
			int which = asked == 4 || asked == 6 ? 3 : asked; // 6 = grasp on a zombie sitting in a boat
			var server = ctx.getSource().getServer();
			com.toast.aurapoints.ability.Resonance.clearAll(); // no plants left over from the last test
			ServerLevel level = server.overworld();
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[AuraDev]"));
			p.moveTo(0.5, y, 0.5, 0f, 0f);
			Zombie[] zs = new Zombie[3];
			double[][] at = which == 3 ? new double[][]{{0.5, 6.5}, {2.5, 9.5}, {-2.5, 10.5}} : new double[][]{{0.5, 11.5}, {2.5, 12.5}, {-2.5, 13.5}};
			for (int i = 0; i < 3; i++) {
				Zombie z = new Zombie(EntityType.ZOMBIE, level);
				z.moveTo(at[i][0], level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(at[i][0]), (int) Math.floor(at[i][1])), at[i][1], 180f, 0f);
				z.setNoAi(which == 3 && i == 0); // the shriek: one silent (standing), two walking
				level.addFreshEntity(z);
				zs[i] = z;
			}
			if (asked == 6) {
				var boat = new net.minecraft.world.entity.vehicle.Boat(EntityType.BOAT, level);
				boat.moveTo(zs[1].getX(), zs[1].getY(), zs[1].getZ());
				level.addFreshEntity(boat);
				zs[1].startRiding(boat, true);
			}
			p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, new net.minecraft.world.phys.Vec3(0.5, y, which == 3 ? 8.5 : 5.5));
			// they walk at the fake player (past the plant), so they make noise
			if (which != 3) com.toast.aurapoints.ability.AuraFx.later(server, 2, () -> {
				for (Zombie z : zs) z.getNavigation().moveTo(0.5, y, 0.5, 1.0);
			});
			// the blossom only hears loud ones (BLOSSOM_MIN_NOISE): make them shout for a few seconds
			if (which == 1) for (int t = 10; t < 120; t += 5) com.toast.aurapoints.ability.AuraFx.later(server, t, () -> {
				for (Zombie z : zs) if (z.isAlive()) com.toast.aurapoints.ability.Noise.add(z, 30f);
			});
			// the shriek: let them walk a moment first, so they are loud
			int delay = which == 3 ? 20 : 0;
			com.toast.aurapoints.ability.AuraFx.later(server, Math.max(1, delay), () -> {
				if (which == 3) p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, zs[1].getEyePosition());
				boolean fired = com.toast.aurapoints.ability.Resonance.devPress(p, which);
				AuraPoints.LOGGER.info("[auradev] resonance {} fired={}", which, fired);
			});
			// the grasp: press again a while later to throw
			if (asked == 3) com.toast.aurapoints.ability.AuraFx.later(server, delay + 40, () ->
					AuraPoints.LOGGER.info("[auradev] throw fired={}", com.toast.aurapoints.ability.Resonance.devPress(p, 3)));
			// after the plants' 60 s life (+ their wither): any models left? (later() flushes delays over 1200 at once, so chain two)
			com.toast.aurapoints.ability.AuraFx.later(server, 1200, () -> com.toast.aurapoints.ability.AuraFx.later(server, 40, () ->
					AuraPoints.LOGGER.info("[auradev] after life |{}", com.toast.aurapoints.ability.Resonance.devModels(level))));
			for (int t = 1; t <= 1101; t += (t < 60 ? 5 : t < 200 ? 20 : 100)) {
				final int tick = t;
				com.toast.aurapoints.ability.AuraFx.later(server, t, () -> {
					StringBuilder sb = new StringBuilder();
					for (Zombie z : zs) sb.append(String.format(" [%.1f %.1f %.1f hp=%.1f noise=%.0f riding=%s]", z.getX(), z.getY(), z.getZ(), z.getHealth(), com.toast.aurapoints.ability.Noise.get(z), z.isPassenger()));
					AuraPoints.LOGGER.info("[auradev] t={}{} |{}", tick, sb, com.toast.aurapoints.ability.Resonance.devModels(level));
				});
			}
			return 1;
		}))).then(Commands.literal("handheld").then(Commands.argument("which", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
			// a fake player at 0,0 facing +z, three zombies in front of it; logs where they go and how hurt they are
			String which = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "which");
			boolean ai = which.endsWith("_ai"); // zombies that can be moved (no-AI mobs ignore pushes)
			var ability = com.toast.aurapoints.ability.AuraAbilities.Ability.valueOf(which.replace("_ai", "").toUpperCase(java.util.Locale.ROOT));
			var server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[AuraDev]"));
			p.moveTo(0.5, y, 0.5, 0f, 0f);
			p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(AuraPoints.STORMCALLER_GLAIVE.get()));
			Zombie[] zs = new Zombie[3];
			double[][] at = {{0.5, 8.5}, {3.5, 10.5}, {-3.5, 11.5}};
			for (int i = 0; i < 3; i++) {
				Zombie z = new Zombie(EntityType.ZOMBIE, level);
				z.moveTo(at[i][0], level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(at[i][0]), (int) Math.floor(at[i][1])), at[i][1], 180f, 0f);
				z.setNoAi(!ai);
				if (ai) z.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 400, 9));
				level.addFreshEntity(z);
				zs[i] = z;
			}
			p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, zs[0].getEyePosition().add(0, -1.2, 0));
			boolean fired = com.toast.aurapoints.ability.Handhelds.devFire(p, ability);
			AuraPoints.LOGGER.info("[auradev] {} fired={} player={}", ability, fired, p.position());
			for (int t = 1; t <= 260; t += (t < 40 ? 3 : 20)) {
				final int tick = t;
				com.toast.aurapoints.ability.AuraFx.later(server, t, () -> {
					StringBuilder sb = new StringBuilder();
					for (Zombie z : zs) sb.append(String.format(" [%.1f %.1f %.1f hp=%.1f]", z.getX(), z.getY(), z.getZ(), z.getHealth()));
					AuraPoints.LOGGER.info("[auradev] t={} player={}{}", tick, p.position(), sb);
				});
			}
			return 1;
		}))));
	}

	/**
	 * /auradev mastery - the player running it (a fake one from the console: no stats there, so the XP line
	 * shows 0) as Fire: Trials counts turn into mastery XP, levels cut cooldowns and boost particles.
	 */
	private static void registerMastery(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("mastery").executes(ctx -> {
			var server = ctx.getSource().getServer();
			net.minecraft.server.level.ServerPlayer p = ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer()
					: FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000d004"), "Masterer"));
			var state = com.toast.aurapoints.data.AuraState.get(server);
			var data = state.getOrCreate(p.getUUID());
			data.aura = com.toast.aurapoints.ability.AuraType.FIRE;
			int before = com.toast.aurapoints.ability.Mastery.xp(p, data.aura);
			com.toast.aurapoints.ability.AuraStats.add(p, com.toast.aurapoints.ability.AuraStats.FIREBALL_HITS, 5);
			com.toast.aurapoints.ability.AuraStats.add(p, com.toast.aurapoints.ability.AuraStats.IRON_SKIN_BLOCKED, 35);
			log("xp " + before + " -> " + com.toast.aurapoints.ability.Mastery.xp(p, data.aura) + " (must be +8: 5 hits + 35 tenths)");
			com.toast.aurapoints.ability.Mastery.set(p, data.aura, 0);
			log("level 0: cooldown 400 -> " + com.toast.aurapoints.ability.Mastery.cooldown(p, 400) + ", boost " + com.toast.aurapoints.ability.Mastery.particleBoost(p));
			com.toast.aurapoints.ability.Mastery.set(p, data.aura, 3);
			log("level 3: cooldown 400 -> " + com.toast.aurapoints.ability.Mastery.cooldown(p, 400) + " (must be 376), boost " + com.toast.aurapoints.ability.Mastery.particleBoost(p) + " (must be 1.45)");
			int old = com.toast.aurapoints.ability.Mastery.set(p, data.aura, 5);
			log("set 5 (was " + old + "); saved: " + data.writeNbt().getCompound("mastery"));
			var copy = com.toast.aurapoints.data.AuraPlayerData.readNbt(data.writeNbt());
			log("reloaded mastery: " + copy.mastery);
			// a reroll keeps it, another aura starts at 0
			data.aura = com.toast.aurapoints.ability.AuraType.WATER;
			log("as Water: level " + com.toast.aurapoints.ability.Mastery.level(p) + " (must be 0), Fire still " + data.mastery.get(com.toast.aurapoints.ability.AuraType.FIRE));
			com.toast.aurapoints.ability.Mastery.set(p, com.toast.aurapoints.ability.AuraType.FIRE, 0);
			data.aura = null;
			return 1;
		})));
	}

	/** /auradev book: the Aura Book menu and tutorial on a fake player (prompt, steps, every aura page, settings). */
	static void registerBookTest(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("book").executes(ctx -> {
			var server = ctx.getSource().getServer();
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b00c"), "Booker"));
			var data = com.toast.aurapoints.data.AuraState.get(server).getOrCreate(p.getUUID());
			com.toast.aurapoints.ability.AuraFx.later(server, 1, () -> {
			java.util.function.IntFunction<String> at = i -> p.containerMenu.getSlot(i).getItem().getHoverName().getString();
			java.util.function.IntConsumer click = i -> p.containerMenu.clicked(i, 0, net.minecraft.world.inventory.ClickType.PICKUP, p);
			data.aura = null;
			data.freeRollUsed = false;
			data.tutorialState = 0;
			data.tutorialStep = 0;
			com.toast.aurapoints.menu.Tutorial.onLogout(p);
			com.toast.aurapoints.menu.AuraBook.open(p);
			rlog("prompt: " + at.apply(11) + " / " + at.apply(13) + " / " + at.apply(15) + " (must be Yes / No / Don't show again)");
			click.accept(11);
			rlog("after yes: state " + data.tutorialState + " step " + data.tutorialStep + " header " + at.apply(4) + " (must be 1 0, Booker's Aura)");
			click.accept(21);
			rlog("roll page button: " + at.apply(49) + " (must be Roll - free)");
			click.accept(49);
			rlog("rolled: " + data.aura + " freeRollUsed " + data.freeRollUsed + " (must be an aura, true)");
			click.accept(32); // not open any more: harmless
			com.toast.aurapoints.menu.AuraBook.open(p);
			rlog("reopen while running: " + at.apply(4) + " (must be the menu, no prompt)");
			click.accept(32); // tutorial icon: re-announce, skips the done roll step
			rlog("after roll: step " + data.tutorialStep + " (must be 1)");
			click.accept(20);
			rlog("aura page: " + at.apply(4) + " first active " + at.apply(19) + " step " + data.tutorialStep + " (must be 2)");
			click.accept(45);
			click.accept(22);
			rlog("skills: " + at.apply(20) + " step " + data.tutorialStep + " (must be 3)");
			click.accept(45);
			click.accept(30);
			boolean hide = data.selfHide;
			click.accept(20);
			rlog("settings: state " + data.tutorialState + " (must be 2 = done), selfHide " + hide + " -> " + data.selfHide + " (must flip), icon " + at.apply(20));
			click.accept(45);
			click.accept(21);
			click.accept(49);
			var before = data.aura;
			rlog("reroll first click: " + at.apply(49) + ", aura still " + (data.aura == before) + " (must be Click again to reroll, true)");
			for (var aura : com.toast.aurapoints.ability.AuraType.values()) {
				data.aura = aura;
				com.toast.aurapoints.menu.AuraBook.open(p);
				click.accept(20);
				rlog("page " + aura + ": " + at.apply(4) + " | " + at.apply(19) + " | " + at.apply(37));
			}
			data.tutorialState = 0;
			com.toast.aurapoints.menu.AuraBook.open(p);
			click.accept(15);
			rlog("dont show again: state " + data.tutorialState + " header " + at.apply(4) + " (must be 3, menu)");
			com.toast.aurapoints.menu.AuraBook.open(p);
			rlog("next open: " + at.apply(4) + " (must be the menu)");
			rlog("earn icon: " + at.apply(31) + " (must be Earn Aura)");
			click.accept(31);
			rlog("earn page: " + at.apply(4) + " | quests " + at.apply(20) + " (must be Quest book only with ftbquests: "
					+ com.toast.aurapoints.menu.AuraBook.EarnPage.questsLoaded() + ") | " + at.apply(22) + " | " + at.apply(24) + " | "
					+ at.apply(29) + " | " + at.apply(31) + " | " + at.apply(33) + " | " + at.apply(40));
			click.accept(20); // quest book: closes + runs ftbquests open_book, or nothing without the mod
			click.accept(45);
			rlog("earn back: " + at.apply(4) + " (must be Booker's Aura)");
			click.accept(4);
			rlog("header click: " + at.apply(4) + " (must be Earn Aura)");
			data.tutorialState = 1;
			com.toast.aurapoints.menu.Tutorial.onLogout(p);
			com.toast.aurapoints.menu.AuraBook.open(p);
			rlog("started, new session: " + at.apply(11) + " (must be Yes, continue)");
			p.closeContainer();
			data.aura = null;
			});
			return 1;
		})));
	}

	/** /auradev resetparts: every /aura resetprogress part and the nuke on a fake player, plus Nightstalker's vision. */
	static void registerResetTest(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("resetparts").executes(ctx -> {
			var server = ctx.getSource().getServer();
			net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000d0e5"), "Resetter"));
			var state = com.toast.aurapoints.data.AuraState.get(server);
			var data = state.getOrCreate(p.getUUID());
			var FIRE = com.toast.aurapoints.ability.AuraType.FIRE;
			Runnable fill = () -> {
				data.aura = FIRE;
				data.rolled.add(FIRE);
				data.freeRollUsed = true;
				data.mastery.put(FIRE, 3);
				data.awakened.add(FIRE);
				data.weeklyDone = 2;
				data.weeklyCompleted = 5;
				data.equipped.put("title", "x");
				p.getStats().setValue(p, net.minecraft.stats.Stats.CUSTOM.get(com.toast.aurapoints.ability.AuraStats.FIREBALL_HITS), 7);
				p.getStats().setValue(p, net.minecraft.stats.Stats.CUSTOM.get(com.toast.aurapoints.ability.AuraStats.mastery(FIRE)), 50);
			};
			for (AuraReset.Part part : AuraReset.Part.values()) {
				fill.run();
				String note = AuraReset.reset(p, data, part);
				rlog(part.id + ": " + note + " | aura " + data.aura + " mastery " + data.mastery + " awakened " + data.awakened + " weeklyDone " + data.weeklyDone
						+ " equipped " + data.equipped + " hits " + p.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(com.toast.aurapoints.ability.AuraStats.FIREBALL_HITS))
						+ " masteryXp " + com.toast.aurapoints.ability.Mastery.xp(p, FIRE));
			}
			fill.run();
			rlog("nuke: " + AuraReset.nuke(p, data) + " | aura " + data.aura + " mastery " + data.mastery + " awakened " + data.awakened + " rolled " + data.rolled
					+ " masteryXp " + com.toast.aurapoints.ability.Mastery.xp(p, FIRE) + " (must be all empty/0)");
			// Nightstalker: on -> 1 min vision that Light's passive no longer strips; off -> gone at once
			data.aura = com.toast.aurapoints.ability.AuraType.SHADOW;
			data.nightVisionOn = true;
			for (var a : data.aura.abilities) if (a.active) data.owned.add(a);
			rlog("has nightstalker " + com.toast.aurapoints.ability.AuraAbilities.hasAbility(data, com.toast.aurapoints.ability.AuraAbilities.Ability.NIGHTSTALKER));
			com.toast.aurapoints.ability.AuraAbilities.tickPassives(p, data, 1000);
			com.toast.aurapoints.ability.Light.tickPassives(p, data, 1010); // used to strip it
			var nv = p.getEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);
			rlog("vision after tick: " + (nv == null ? "none" : nv.getDuration() + " ticks, particles " + nv.isVisible()) + " (must be 1200, particles false)");
			data.nightVisionOn = false;
			com.toast.aurapoints.ability.Nightstalker.clearVision(p, data);
			rlog("vision after off: " + p.getEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION) + " (must be null)");
			data.aura = null;
			data.owned.clear();
			state.markDirty();
			return 1;
		})));
	}

	private static void rlog(String what) {
		AuraPoints.LOGGER.info("[resettest] {}", what);
	}

	/** /auradev bloodbar: bleed ticks fill the bar (cow 1, zombie 2, player 4), Sacrifice spends it, Blood Moon needs it, death and NBT. */
	static void registerBloodBar(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("bloodbar").executes(ctx -> {
			var server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			var p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000d0b1"), "Bleeder"));
			var victim = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000d0b2"), "Bled"));
			var state = com.toast.aurapoints.data.AuraState.get(server);
			var data = state.getOrCreate(p.getUUID());
			data.aura = com.toast.aurapoints.ability.AuraType.BLOOD;
			data.awakened.add(com.toast.aurapoints.ability.AuraType.BLOOD);
			data.bloodBar = 0;
			var spawn = level.getSharedSpawnPos().above(2);
			p.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
			victim.setPos(spawn.getX() + 3.5, spawn.getY(), spawn.getZ() + 0.5);
			var cow = EntityType.COW.create(level);
			var zombie = EntityType.ZOMBIE.create(level);
			cow.setPos(spawn.getX() - 3.5, spawn.getY(), spawn.getZ() + 0.5);
			zombie.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 3.5);
			cow.setNoAi(true);
			zombie.setNoAi(true);
			level.addFreshEntity(cow);
			level.addFreshEntity(zombie);
			// 41 ticks = 2 bleeding ticks each
			com.toast.aurapoints.ability.Blood.bleed(p, cow, 41);
			com.toast.aurapoints.ability.Blood.bleed(p, zombie, 41);
			com.toast.aurapoints.ability.Blood.bleed(p, victim, 41);
			com.toast.aurapoints.ability.AuraFx.later(server, 50, () -> {
				bloodLog("cooldownsDisabled in this world: " + com.toast.aurapoints.data.AuraState.get(server).cooldownsDisabled);
				// a FakePlayer isn't in the player list, so its bleeds pay nobody: check what each one would pay
				try {
					var gain = com.toast.aurapoints.ability.Blood.class.getDeclaredMethod("gain", net.minecraft.world.entity.LivingEntity.class);
					gain.setAccessible(true);
					bloodLog("gain per bleed tick: cow " + gain.invoke(null, cow) + " (1), zombie " + gain.invoke(null, zombie) + " (2), player " + gain.invoke(null, victim) + " (4)");
				} catch (ReflectiveOperationException ex) {
					bloodLog("gain lookup failed: " + ex);
				}
				var st = com.toast.aurapoints.data.AuraState.get(server);
				boolean wasOff = st.cooldownsDisabled;
				st.cooldownsDisabled = true;
				long now = server.getTickCount();
				int[][] cases = {{30, 2, 5}, {10, 0, 0}, {0, 0, 0}};
				for (int[] c : cases) {
					data.bloodBar = c[0];
					p.setHealth(20f);
					boolean fired = com.toast.aurapoints.ability.AuraAbilities.tryActivate(p, com.toast.aurapoints.ability.AuraAbilities.Ability.CRIMSON_SACRIFICE, now);
					bloodLog("sacrifice with bar " + c[0] + ": fired " + fired + ", health 20 -> " + p.getHealth() + ", bar -> " + data.bloodBar);
				}
				st.cooldownsDisabled = false;
				bloodLog("(expected: 18.0/5, ~14.4/0, 12.0/0)");
				com.toast.aurapoints.ability.AuraAbilities.clearCooldowns(p);
				data.bloodBar = 63;
				com.toast.aurapoints.ability.Ultimates.press(p, now);
				bloodLog("moon with 63: moonUntil " + com.toast.aurapoints.ability.Blood.moonUntil(p) + " (must be 0), bar " + data.bloodBar + " (63)");
				data.bloodBar = 100;
				com.toast.aurapoints.ability.Ultimates.press(p, now);
				bloodLog("moon with 100: running " + (com.toast.aurapoints.ability.Blood.moonUntil(p) > now) + " (true), bar " + data.bloodBar + " (0)");
				data.bloodBar = 40;
				var copy = com.toast.aurapoints.data.AuraPlayerData.readNbt(data.writeNbt());
				bloodLog("nbt round trip: " + copy.bloodBar + " (40)");
				com.toast.aurapoints.ability.Blood.onDeath(p);
				bloodLog("after death: " + data.bloodBar + " (0)");
				st.cooldownsDisabled = wasOff;
				com.toast.aurapoints.ability.Ultimates.onLogout(p);
				cow.discard();
				zombie.discard();
				com.toast.aurapoints.ability.Blood.onLogout(p);
				state.markDirty();
			});
			return 1;
		})));
	}

	private static void bloodLog(String what) {
		AuraPoints.LOGGER.info("[bloodbartest] {}", what);
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[masterytest] {}", what);
	}
}
