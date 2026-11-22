package com.toast.aurapoints.rift;

import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraStats;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Mastery;
import com.toast.aurapoints.ability.Relics;
import com.toast.aurapoints.ability.TouchedMobs;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aura Rifts (5.41.0). Now and then a tear opens in the overworld near a random player (announced, open for
 * RIFT_OPEN_SECONDS). Walking into it takes you, and up to RIFT_MAX_PLAYERS others, into the aurapoints:rift
 * dimension: a chain of floating rooms in the rift's aura colours, each holding that aura's touched mobs, the gate
 * to the next opening once a room is clear, then a boss room with a champion. A lethal hit is a knockout (you
 * watch until the next room); if everyone is down the run fails. Clearing pays Aura, mastery XP and a relic
 * of the rift's aura (always on your first clear of it), counts toward two earn-only titles and a weekly type.
 * Runs are not saved: logging in inside a rift sends you back to where you came from.
 */
public final class Rifts {

	private Rifts() {
	}

	public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "rift"));
	public static final String MOB_TAG = "aurapoints_rift", TEAR_TAG = "aurapoints_rift_tear";
	private static final ResourceLocation SCALE_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "rift_scale"),
			BOSS_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "rift_boss"),
			BOSS_SIZE_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "rift_boss_size");

	private static final int PULL_TICKS = 18;
	static final int FLOOR = 64, ROOMS = 4, SPACING = 30, ROOM_R = 9, BOSS_R = 13;

	public static ServerLevel level(MinecraftServer server) {
		return server.getLevel(KEY);
	}

	public static boolean in(Entity e) {
		return e.level().dimension() == KEY;
	}

	// ---------------------------------------------------------------
	// State
	// ---------------------------------------------------------------

	/** The open tear in the overworld (at most one). */
	static final class Tear {
		final AuraType aura;
		final Vec3 pos;
		final long closes;
		RiftGate gate;
		Display.TextDisplay label;
		Run run;
		/** Players being pulled through the curtain -> ticks left. */
		final Map<UUID, Integer> pulling = new java.util.HashMap<>();

		Tear(AuraType aura, Vec3 pos, long closes) {
			this.aura = aura;
			this.pos = pos;
			this.closes = closes;
		}
	}

	/** A party's run through one instance. */
	static final class Run {
		final int id, slot;
		final AuraType aura;
		final Set<UUID> players = new LinkedHashSet<>(), down = new HashSet<>();
		/** The room being fought (0..ROOMS = the boss room), -1 before the first. */
		int room = -1;
		boolean fighting, boss, done;
		final List<Mob> mobs = new ArrayList<>();
		Mob champion;
		ServerBossEvent bar;
		long endAt = -1;
		boolean won;
		int cleared;

		Run(int id, AuraType aura) {
			this.id = id;
			this.slot = id % 16;
			this.aura = aura;
		}

		Vec3 center(int room) {
			int z = room < ROOMS ? room * SPACING : ROOMS * SPACING + (BOSS_R - ROOM_R);
			return new Vec3(slot * 1024 + 0.5, FLOOR + 1, z + 0.5);
		}

		int radius(int room) {
			return room < ROOMS ? ROOM_R : BOSS_R;
		}
	}

	/** Dev tests: fake players aren't in the player list. */
	public static final Map<UUID, ServerPlayer> TEST_PLAYERS = new java.util.HashMap<>();

	private static Tear tear;
	/** Gates still playing their closing animation. */
	private static final List<RiftGate> CLOSING = new ArrayList<>();
	private static final List<Run> RUNS = new ArrayList<>();
	private static int nextId = 1;
	private static long nextTearAt = 0;

	public static Run runOf(ServerPlayer p) {
		for (Run r : RUNS) if (r.players.contains(p.getUUID())) return r;
		return null;
	}

	// ---------------------------------------------------------------
	// The tear
	// ---------------------------------------------------------------

	/** Opens a tear near {@code near} (or at {@code at} when given). */
	public static boolean open(MinecraftServer server, AuraType aura, ServerPlayer near, Vec3 at) {
		ServerLevel ow = server.overworld();
		closeTear(server, false);
		Vec3 pos = at;
		if (pos == null) {
			if (near == null) return false;
			double a = ow.random.nextDouble() * Math.PI * 2, d = 40 + ow.random.nextDouble() * 40;
			BlockPos top = ow.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(near.getX() + Math.cos(a) * d, 0, near.getZ() + Math.sin(a) * d));
			pos = Vec3.atBottomCenterOf(top).add(0, 1.6, 0);
		}
		tear = new Tear(aura, pos, ow.getGameTime() + AuraConfig.RIFT_OPEN_SECONDS * 20L);
		// the curtain faces whoever is nearest (the player it opened for, or the one who ran the command)
		Vec3 ground = pos.subtract(0, 1.6, 0);
		ServerPlayer facing = near != null ? near : ow.getNearestPlayer(pos.x, pos.y, pos.z, 64, false) instanceof ServerPlayer sp ? sp : null;
		Vec3 face = facing == null ? Vec3.directionFromRotation(0, ow.random.nextFloat() * 360f) : facing.position().subtract(ground);
		tear.gate = new RiftGate(ow, aura, ground, face);
		tear.label = (Display.TextDisplay) com.toast.aurapoints.arena.Holograms.makePublic(ow, tear.gate.center.add(0, RiftGate.B + 2.0, 0), label(aura, AuraConfig.RIFT_OPEN_SECONDS), 0.8f, 0x40000000);
		if (tear.label != null) {
			tear.label.addTag(TEAR_TAG);
			ow.addFreshEntity(tear.label);
		}
		Vec3 p = pos;
		Component line = Component.literal("⚡ " + ("AEIOU".indexOf(aura.displayName.charAt(0)) >= 0 ? "An " : "A ") + aura.displayName + " Rift tore open").withStyle(s -> s.withColor(TextColor.fromRgb(aura.rgb)).withBold(true))
				.append(Component.literal(" around x " + Math.round(p.x / 10) * 10 + ", z " + Math.round(p.z / 10) * 10
						+ " - walk in within " + AuraConfig.RIFT_OPEN_SECONDS / 60 + " min (up to " + AuraConfig.RIFT_MAX_PLAYERS + ")").withStyle(ChatFormatting.GRAY));
		for (ServerPlayer pl : server.getPlayerList().getPlayers()) pl.sendSystemMessage(line);
		return true;
	}

	private static Component label(AuraType aura, long secondsLeft) {
		return Component.literal(aura.displayName.toUpperCase(java.util.Locale.ROOT) + " RIFT").withStyle(s -> s.withColor(TextColor.fromRgb(aura.rgb)).withBold(true))
				.append(Component.literal("\nwalk in · closes in " + secondsLeft / 60 + ":" + String.format("%02d", secondsLeft % 60)).withStyle(ChatFormatting.GRAY));
	}

	private static void closeTear(MinecraftServer server, boolean say) {
		if (tear == null) return;
		if (tear.label != null) tear.label.discard();
		if (tear.gate != null) {
			// say = in view of players: fold shut; otherwise (server stop, a new tear) gone at once
			if (say) {
				tear.gate.close();
				CLOSING.add(tear.gate);
			} else tear.gate.remove();
		}
		tear = null;
	}

	/** Display entities of an old tear, and rift mobs loaded from disk (the run they were in is gone). */
	public static boolean isLeftover(Entity e, boolean loadedFromDisk) {
		if (e.getTags().contains(TEAR_TAG)) {
			if (tear != null && (e == tear.label || (tear.gate != null && e == tear.gate.core))) return false;
			for (RiftGate g : CLOSING) if (e == g.core) return false;
			return true;
		}
		return loadedFromDisk && e.getTags().contains(MOB_TAG);
	}

	public static boolean isRiftMob(Entity e) {
		return e.getTags().contains(MOB_TAG);
	}

	// ---------------------------------------------------------------
	// Ticking
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		ServerLevel ow = server.overworld();
		long now = ow.getGameTime();
		if (!AuraConfig.RIFT_ENABLED) {
			if (tear != null) closeTear(server, false);
			return;
		}
		// a new tear now and then
		if (nextTearAt == 0) nextTearAt = now + interval(ow);
		if (tear == null && now >= nextTearAt) {
			nextTearAt = now + interval(ow);
			List<ServerPlayer> here = ow.players().stream().filter(p -> !p.isSpectator()).toList();
			if (!here.isEmpty()) {
				List<AuraType> auras = Relics.AURAS;
				open(server, auras.get(ow.random.nextInt(auras.size())), here.get(ow.random.nextInt(here.size())), null);
			}
		}
		if (tear != null) tickTear(server, ow, now);
		CLOSING.removeIf(g -> !g.tick());
		ServerLevel rift = level(server);
		if (rift != null) for (Run r : new ArrayList<>(RUNS)) tickRun(server, rift, r);
	}

	private static long interval(ServerLevel ow) {
		return Math.max(600, Math.round(AuraConfig.RIFT_INTERVAL_MINUTES * 1200.0 * (0.75 + ow.random.nextDouble() * 0.5)));
	}

	private static void tickTear(MinecraftServer server, ServerLevel ow, long now) {
		if (now >= tear.closes || (tear.run != null && (tear.run.room > 0 || tear.run.done))) {
			closeTear(server, true);
			return;
		}
		if (tear.gate != null) tear.gate.tick();
		if (now % 20 == 0 && tear.label != null) {
			com.toast.aurapoints.arena.Holograms.setTextPublic(tear.label, label(tear.aura, (tear.closes - now) / 20));
		}
		// stepping into the curtain: pulled into its middle for a moment, then through
		Tear t = tear;
		for (ServerPlayer pl : ow.players()) {
			if (pl.isSpectator() || t.pulling.containsKey(pl.getUUID()) || runOf(pl) != null || t.gate == null || !t.gate.touching(pl)) continue;
			if (t.run != null && t.run.players.size() >= AuraConfig.RIFT_MAX_PLAYERS) {
				if (now % 20 == 0) pl.displayClientMessage(Component.literal("This rift is full.").withStyle(ChatFormatting.RED), true);
				continue;
			}
			t.pulling.put(pl.getUUID(), PULL_TICKS);
			ow.playSound(null, pl.getX(), pl.getY(), pl.getZ(), SoundEvents.PORTAL_TRIGGER, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.6f);
		}
		if (t.pulling.isEmpty()) return;
		for (var it = t.pulling.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer pl = server.getPlayerList().getPlayer(e.getKey());
			int left = e.getValue() - 1;
			if (pl == null || pl.level() != ow || pl.isSpectator()) {
				it.remove();
				continue;
			}
			if (left <= 0) {
				it.remove();
				ow.playSound(null, pl.getX(), pl.getY(), pl.getZ(), SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 1f, 0.6f);
				enter(server, pl);
				if (tear != t) return; // the run started and closed this tear
				continue;
			}
			e.setValue(left);
			t.gate.pull(pl, left);
		}
	}

	/** A player walks into the tear: into its run (made on the first). */
	static void enter(MinecraftServer server, ServerPlayer player) {
		ServerLevel rift = level(server);
		if (rift == null || tear == null) return;
		if (com.toast.aurapoints.arena.Arena.in(player) || com.toast.aurapoints.arena.Match.signedUp(player)) return;
		if (tear.run == null) {
			tear.run = new Run(nextId++, tear.aura);
			RUNS.add(tear.run);
			RiftBuilder.build(rift, tear.run);
		}
		Run run = tear.run;
		if (run.players.size() >= AuraConfig.RIFT_MAX_PLAYERS) {
			player.displayClientMessage(Component.literal("This rift is full.").withStyle(ChatFormatting.RED), true);
			return;
		}
		saveReturn(server, player);
		run.players.add(player.getUUID());
		Vec3 start = run.center(0).add(0, 0, -(ROOM_R - 2));
		if (!com.toast.aurapoints.shim.Platform.get().isFakePlayer(player)) {
			player.teleportTo(rift, start.x, start.y, start.z, 0f, 0f);
			player.setGameMode(GameType.ADVENTURE);
		}
		player.setHealth(player.getMaxHealth());
		AuraFx.title(player, Component.literal(run.aura.displayName + " Rift").withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb)).withBold(true)),
				Component.literal(ROOMS + " rooms, then the champion").withStyle(ChatFormatting.GRAY));
		if (run.room < 0) {
			run.room = 0;
			// the first room wakes after a short breath, so the others can follow
			AuraFx.later(server, 100, () -> {
				if (!run.done && run.room == 0 && !run.fighting) startRoom(server, run);
			});
		}
	}

	// ---------------------------------------------------------------
	// A run
	// ---------------------------------------------------------------

	private static List<ServerPlayer> online(MinecraftServer server, Run run) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : run.players) {
			ServerPlayer p = player(server, id);
			if (p != null && in(p)) out.add(p);
		}
		return out;
	}

	private static ServerPlayer player(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null ? p : TEST_PLAYERS.get(id);
	}

	private static void startRoom(MinecraftServer server, Run run) {
		ServerLevel rift = level(server);
		if (rift == null) return;
		run.fighting = true;
		int players = Math.max(1, run.players.size());
		boolean boss = run.room >= ROOMS;
		Vec3 c = run.center(run.room);
		if (boss) {
			run.boss = true;
			spawnChampion(rift, run, c, players);
			for (int k = 0; k < 2 + players; k++) run.mobs.add(spawnMob(rift, run, k % 2 == 0 ? EntityType.SKELETON : EntityType.ZOMBIE, c, run.radius(run.room)));
		} else {
			int budget = (int) Math.round((AuraConfig.RIFT_BUDGET_BASE + AuraConfig.RIFT_BUDGET_PER_ROOM * run.room) * (1 + 0.5 * (players - 1)));
			var r = rift.random;
			while (budget > 0) {
				int pick = r.nextInt(KINDS.length);
				Kind k = KINDS[pick];
				if (k.cost > budget || k.fromRoom > run.room) {
					if (budget < 2) k = KINDS[0];
					else continue;
				}
				budget -= k.cost;
				Mob m = spawnMob(rift, run, k.type, c, run.radius(run.room));
				if (m != null) run.mobs.add(m);
			}
		}
		run.mobs.removeIf(java.util.Objects::isNull);
		for (ServerPlayer p : online(server, run)) {
			p.displayClientMessage(Component.literal(boss ? "The champion wakes!" : "Room " + (run.room + 1) + " of " + ROOMS + ": " + run.mobs.size() + " foes")
					.withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb))), true);
		}
		rift.playSound(null, c.x, c.y, c.z, boss ? SoundEvents.WITHER_SPAWN : SoundEvents.EVOKER_PREPARE_SUMMON, net.minecraft.sounds.SoundSource.HOSTILE, 1f, 1f);
	}

	private record Kind(EntityType<? extends Mob> type, int cost, int fromRoom) {
	}

	private static final Kind[] KINDS = {
			new Kind(EntityType.ZOMBIE, 1, 0), new Kind(EntityType.SKELETON, 2, 0), new Kind(EntityType.SPIDER, 2, 0),
			new Kind(EntityType.HUSK, 2, 1), new Kind(EntityType.STRAY, 2, 1), new Kind(EntityType.CREEPER, 3, 1),
			new Kind(EntityType.WITCH, 4, 2), new Kind(EntityType.VINDICATOR, 5, 2), new Kind(EntityType.BLAZE, 5, 3)};

	private static Mob spawnMob(ServerLevel rift, Run run, EntityType<? extends Mob> type, Vec3 c, int radius) {
		Mob m = type.create(rift);
		if (m == null) return null;
		double a = rift.random.nextDouble() * Math.PI * 2, d = radius * (0.45 + rift.random.nextDouble() * 0.4);
		m.moveTo(c.x + Math.cos(a) * d, c.y, c.z + Math.sin(a) * d, rift.random.nextFloat() * 360f, 0);
		m.addTag(MOB_TAG);
		com.toast.aurapoints.shim.neoforge.event.EventHooks.finalizeMobSpawn(m, rift, rift.getCurrentDifficultyAt(m.blockPosition()), MobSpawnType.EVENT, null);
		if (m instanceof net.minecraft.world.entity.monster.Zombie z) {
			z.setBaby(false);
			var reinforce = z.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
			if (reinforce != null) reinforce.setBaseValue(0);
		}
		m.setPersistenceRequired();
		double scale = AuraConfig.RIFT_SCALE_PER_ROOM * run.room;
		if (scale > 0) {
			var hp = m.getAttribute(Attributes.MAX_HEALTH);
			if (hp != null) hp.addPermanentModifier(new AttributeModifier(SCALE_ID, scale, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
			var dmg = m.getAttribute(Attributes.ATTACK_DAMAGE);
			if (dmg != null) dmg.addPermanentModifier(new AttributeModifier(SCALE_ID, scale, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
		}
		TouchedMobs.touch(m, run.aura);
		m.setHealth(m.getMaxHealth());
		rift.addFreshEntityWithPassengers(m);
		AuraFx.burst(rift, m.getX(), m.getY() + 1, m.getZ(), ParticleTypes.PORTAL, 20, 0.4, 0.3);
		return m;
	}

	private static void spawnChampion(ServerLevel rift, Run run, Vec3 c, int players) {
		EntityType<? extends Mob> type = switch (run.aura.ordinal() % 3) {
			case 0 -> EntityType.RAVAGER;
			case 1 -> EntityType.EVOKER;
			default -> EntityType.WITHER_SKELETON;
		};
		Mob boss = spawnMob(rift, run, type, c, 1);
		if (boss == null) return;
		var hp = boss.getAttribute(Attributes.MAX_HEALTH);
		if (hp != null) {
			double target = AuraConfig.RIFT_BOSS_HEALTH * (1 + AuraConfig.RIFT_BOSS_HEALTH_PER_PLAYER * (players - 1)), cur = hp.getValue();
			if (cur > 0) hp.addPermanentModifier(new AttributeModifier(BOSS_ID, target / cur - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			boss.setHealth(boss.getMaxHealth());
		}
		var size = boss.getAttribute(Attributes.SCALE);
		if (size != null) size.addPermanentModifier(new AttributeModifier(BOSS_SIZE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
		Component name = Component.literal("★ " + run.aura.displayName + " Rift Champion ★").withStyle(run.aura.color, ChatFormatting.BOLD);
		boss.setCustomName(name);
		run.champion = boss;
		run.bar = new ServerBossEvent(name, BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10);
		for (ServerPlayer p : online(rift.getServer(), run)) run.bar.addPlayer(p);
		run.mobs.add(boss);
	}

	private static void tickRun(MinecraftServer server, ServerLevel rift, Run run) {
		long now = rift.getGameTime();
		List<ServerPlayer> here = online(server, run);
		if (run.done) {
			if (now >= run.endAt) finish(server, run);
			return;
		}
		if (here.isEmpty() && run.room >= 0 && (tear == null || tear.run != run)) {
			end(server, run, false);
			return;
		}
		for (ServerPlayer p : here) {
			// off the edge: back to the room
			if (p.getY() < FLOOR - 25) {
				Vec3 c = run.center(Math.max(0, run.room));
				p.teleportTo(rift, c.x, c.y + 1, c.z, p.getYRot(), 0f);
				p.fallDistance = 0;
			}
		}
		if (run.bar != null && run.champion != null && now % 5 == 0) {
			run.bar.setProgress(Math.max(0f, run.champion.getHealth() / run.champion.getMaxHealth()));
		}
		if (now % 10 != 0) return;
		if (run.fighting) {
			run.mobs.removeIf(m -> {
				if (m.getY() < FLOOR - 30) m.discard();
				return !m.isAlive() || m.isRemoved();
			});
			if (run.mobs.isEmpty()) clearRoom(server, rift, run);
		} else if (run.room >= 0 && run.room <= ROOMS && run.room > 0) {
			// the next room wakes when someone steps in
			Vec3 c = run.center(run.room);
			double r = run.radius(run.room) - 2;
			for (ServerPlayer p : here) {
				if (run.down.contains(p.getUUID())) continue;
				if (p.position().distanceToSqr(c.x, p.getY(), c.z) <= r * r) {
					startRoom(server, run);
					break;
				}
			}
		}
	}

	private static void clearRoom(MinecraftServer server, ServerLevel rift, Run run) {
		run.fighting = false;
		run.cleared++;
		if (run.boss) {
			if (run.bar != null) run.bar.removeAllPlayers();
			end(server, run, true);
			return;
		}
		RiftBuilder.openGate(rift, run, run.room);
		// the knocked out come back at the gate
		Vec3 c = run.center(run.room);
		for (ServerPlayer p : online(server, run)) {
			if (run.down.remove(p.getUUID())) {
				p.setGameMode(GameType.ADVENTURE);
				p.setHealth(p.getMaxHealth());
				p.teleportTo(rift, c.x, c.y, c.z + ROOM_R - 2, 0f, 0f);
			}
			p.displayClientMessage(Component.literal(run.room + 1 >= ROOMS ? "Room clear - the champion waits ahead" : "Room clear - the gate is open")
					.withStyle(ChatFormatting.GREEN), true);
		}
		rift.playSound(null, c.x, c.y, c.z, SoundEvents.IRON_DOOR_OPEN, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 0.7f);
		run.room++;
	}

	/** The run is over (won or not); everyone goes back a few seconds later. */
	private static void end(MinecraftServer server, Run run, boolean won) {
		if (run.done) return;
		run.done = true;
		run.won = won;
		ServerLevel rift = level(server);
		run.endAt = (rift == null ? 0 : rift.getGameTime()) + (won ? 160 : 80);
		if (run.bar != null) run.bar.removeAllPlayers();
		for (Mob m : run.mobs) if (m.isAlive()) m.discard();
		run.mobs.clear();
		for (ServerPlayer p : online(server, run)) {
			if (won) {
				AuraFx.title(p, Component.literal("RIFT CLEARED").withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb)).withBold(true)),
						Component.literal("back in a moment").withStyle(ChatFormatting.GRAY));
				pay(p, run, true);
			} else {
				AuraFx.title(p, Component.literal("The rift collapses").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
						Component.literal(run.cleared + " room(s) cleared").withStyle(ChatFormatting.GRAY));
				pay(p, run, false);
			}
		}
	}

	private static void pay(ServerPlayer p, Run run, boolean won) {
		int rooms = Math.min(ROOMS, run.cleared);
		int aura = rooms * AuraConfig.RIFT_ROOM_AURA + (won ? AuraConfig.RIFT_BOSS_AURA : 0);
		if (aura > 0) AuraCurrency.add(p, aura);
		Mastery.addXp(p, rooms * AuraConfig.RIFT_ROOM_MASTERY_XP + (won ? AuraConfig.RIFT_BOSS_MASTERY_XP : 0));
		p.sendSystemMessage(Component.literal((won ? "✔ Rift cleared: " : "Rift lost: ") + "+" + aura + " Aura").withStyle(won ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		if (!won) return;
		AuraStats.add(p, AuraStats.RIFTS_CLEARED, 1);
		AuraPlayerData data = AuraState.get(p.getServer()).getOrCreate(p.getUUID());
		boolean first = data.riftAuras.add(run.aura);
		AuraState.get(p.getServer()).markDirty();
		if (first || p.getRandom().nextDouble() < AuraConfig.RIFT_RELIC_CHANCE) Relics.give(p, run.aura);
		com.toast.aurapoints.cosmetic.Weekly.onRiftCleared(p);
		com.toast.aurapoints.cosmetic.Cosmetics.checkEarned(p);
	}

	private static void finish(MinecraftServer server, Run run) {
		for (UUID id : new ArrayList<>(run.players)) {
			ServerPlayer p = player(server, id);
			if (p != null && in(p)) sendBack(p);
		}
		run.players.clear();
		RUNS.remove(run);
		if (tear != null && tear.run == run) closeTear(server, false);
	}

	// ---------------------------------------------------------------
	// Knockouts, leaving, logins
	// ---------------------------------------------------------------

	/** A hit that would kill someone in a rift: a knockout instead. True = handled (damage to 0). */
	public static boolean catchLethal(LivingEntity victim, float damage) {
		if (!(victim instanceof ServerPlayer p) || !in(p)) return false;
		if (damage < p.getHealth() + p.getAbsorptionAmount()) return false;
		knockout(p);
		return true;
	}

	public static boolean catchDeath(LivingEntity victim) {
		if (!(victim instanceof ServerPlayer p) || !in(p)) return false;
		knockout(p);
		return true;
	}

	private static void knockout(ServerPlayer p) {
		p.setHealth(p.getMaxHealth());
		p.clearFire();
		p.removeAllEffects();
		Run run = runOf(p);
		if (run == null) {
			sendBack(p);
			return;
		}
		run.down.add(p.getUUID());
		p.setGameMode(GameType.SPECTATOR);
		Vec3 c = run.center(Math.max(0, Math.min(run.room, ROOMS)));
		p.teleportTo(level(p.getServer()), c.x, c.y + 6, c.z, p.getYRot(), 40f);
		AuraFx.title(p, Component.literal("Knocked out").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal("back in when the room is clear").withStyle(ChatFormatting.GRAY));
		boolean anyUp = false;
		for (UUID id : run.players) if (!run.down.contains(id) && player(p.getServer(), id) != null) anyUp = true;
		if (!anyUp) end(p.getServer(), run, false);
	}

	/** /aura leave or /aura rift leave: out of the rift (a run left by everyone ends). */
	public static boolean leave(ServerPlayer p) {
		if (!in(p)) return false;
		Run run = runOf(p);
		if (run != null) {
			run.players.remove(p.getUUID());
			run.down.remove(p.getUUID());
			if (run.bar != null) run.bar.removePlayer(p);
		}
		sendBack(p);
		return true;
	}

	public static void onLogout(ServerPlayer p) {
		Run run = runOf(p);
		if (run != null) {
			run.players.remove(p.getUUID());
			run.down.remove(p.getUUID());
		}
	}

	/**
	 * Out of the rift some other way (/tp, a home command, a portal mod): out of the run too, and the
	 * game mode from before the rift comes back. The position stays where they went.
	 */
	public static void onChangedDimension(ServerPlayer p, ResourceKey<net.minecraft.world.level.Level> from, ResourceKey<net.minecraft.world.level.Level> to) {
		if (from != KEY || to == KEY || p.getServer() == null) return;
		Run run = runOf(p);
		if (run != null) {
			run.players.remove(p.getUUID());
			run.down.remove(p.getUUID());
			if (run.bar != null) run.bar.removePlayer(p);
		}
		AuraPlayerData data = AuraState.get(p.getServer()).getOrCreate(p.getUUID());
		CompoundTag t = data.riftReturn;
		if (t == null) return; // sendBack did it
		data.riftReturn = null;
		p.setGameMode(GameType.byName(t.getString("mode"), GameType.SURVIVAL));
		AuraState.get(p.getServer()).markDirty();
	}

	/** Logged in inside a rift (the run is gone): back to where they came from. */
	public static void onLogin(ServerPlayer p) {
		if (in(p) && runOf(p) == null) sendBack(p);
	}

	private static void saveReturn(MinecraftServer server, ServerPlayer p) {
		AuraPlayerData data = AuraState.get(server).getOrCreate(p.getUUID());
		CompoundTag t = new CompoundTag();
		t.putString("dim", p.level().dimension().location().toString());
		t.putDouble("x", p.getX());
		t.putDouble("y", p.getY());
		t.putDouble("z", p.getZ());
		t.putFloat("yaw", p.getYRot());
		t.putFloat("pitch", p.getXRot());
		t.putString("mode", p.gameMode.getGameModeForPlayer().getName());
		data.riftReturn = t;
		AuraState.get(server).markDirty();
	}

	private static void sendBack(ServerPlayer p) {
		MinecraftServer server = p.getServer();
		if (server == null) return;
		AuraPlayerData data = AuraState.get(server).getOrCreate(p.getUUID());
		CompoundTag t = data.riftReturn;
		ServerLevel to = null;
		if (t != null) {
			ResourceLocation id = ResourceLocation.tryParse(t.getString("dim"));
			if (id != null) to = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		}
		GameType mode = t == null ? GameType.SURVIVAL : GameType.byName(t.getString("mode"), GameType.SURVIVAL);
		if (com.toast.aurapoints.shim.Platform.get().isFakePlayer(p)) {
			// dev tests: a fake player can't travel between dimensions (NeoForge sends it packets it has no channel for)
			data.riftReturn = null;
			return;
		}
		// cleared before the teleport, so onChangedDimension sees a planned way out
		data.riftReturn = null;
		if (to == null || to.dimension() == KEY) {
			to = server.overworld();
			BlockPos spawn = to.getSharedSpawnPos();
			p.teleportTo(to, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
		} else {
			p.teleportTo(to, t.getDouble("x"), t.getDouble("y"), t.getDouble("z"), t.getFloat("yaw"), t.getFloat("pitch"));
		}
		p.setGameMode(mode);
		p.fallDistance = 0;
		AuraState.get(server).markDirty();
	}

	// ---------------------------------------------------------------
	// /aura rift
	// ---------------------------------------------------------------

	public static void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
		var auras = (com.mojang.brigadier.suggestion.SuggestionProvider<net.minecraft.commands.CommandSourceStack>) (ctx, b) -> {
			for (AuraType a : Relics.AURAS) b.suggest(a.id());
			return b.buildFuture();
		};
		dispatcher.register(net.minecraft.commands.Commands.literal("aura").then(net.minecraft.commands.Commands.literal("rift")
				.executes(ctx -> where(ctx.getSource()))
				.then(net.minecraft.commands.Commands.literal("leave").executes(ctx -> leave(ctx.getSource().getPlayerOrException()) ? 1 : 0))
				.then(net.minecraft.commands.Commands.literal("close").executes(ctx -> {
					closeTear(ctx.getSource().getServer(), true);
					ctx.getSource().sendSuccess(() -> Component.literal("Rift closed."), true);
					return 1;
				}))
				.then(net.minecraft.commands.Commands.literal("open").then(net.minecraft.commands.Commands.argument("aura", com.mojang.brigadier.arguments.StringArgumentType.word()).suggests(auras)
						.executes(ctx -> {
							AuraType a = AuraType.byId(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "aura"));
							if (a == null || !Relics.AURAS.contains(a)) {
								ctx.getSource().sendFailure(Component.literal("No rift of that aura."));
								return 0;
							}
							Vec3 at = ctx.getSource().getPosition();
							if (ctx.getSource().getLevel().dimension() != Level.OVERWORLD) {
								ctx.getSource().sendFailure(Component.literal("Rifts open in the overworld."));
								return 0;
							}
							return open(ctx.getSource().getServer(), a, null, at.add(ctx.getSource().getRotation() == null ? Vec3.ZERO : Vec3.directionFromRotation(0, ctx.getSource().getRotation().y).scale(3)).add(0, 1.6, 0)) ? 1 : 0;
						})))));
	}

	private static int where(net.minecraft.commands.CommandSourceStack src) {
		if (tear == null) {
			src.sendSuccess(() -> Component.literal("No rift is open right now.").withStyle(ChatFormatting.GRAY), false);
			return 0;
		}
		long left = (tear.closes - src.getServer().overworld().getGameTime()) / 20;
		Vec3 p = tear.pos;
		src.sendSuccess(() -> Component.literal("⚡ " + tear.aura.displayName + " Rift").withStyle(s -> s.withColor(TextColor.fromRgb(tear.aura.rgb)))
				.append(Component.literal(" at x " + Math.round(p.x) + ", z " + Math.round(p.z) + " · closes in " + left / 60 + " min").withStyle(ChatFormatting.GRAY)), false);
		return 1;
	}

	// ---------------------------------------------------------------
	// Dev test access
	// ---------------------------------------------------------------

	public static boolean devTearOpen() {
		return tear != null;
	}

	public static Vec3 devTearPos() {
		return tear == null ? null : tear.pos;
	}

	public static void devEnter(MinecraftServer server, ServerPlayer p) {
		enter(server, p);
	}

	/** The player's run as text: "room 1/4 fighting 6 mobs, cleared 0, down 0, done false". */
	public static String devState(ServerPlayer p) {
		Run r = runOf(p);
		if (r == null) return "no run";
		return "room " + r.room + " fighting " + r.fighting + " mobs " + r.mobs.size() + " cleared " + r.cleared + " down " + r.down.size() + " done " + r.done + " won " + r.won;
	}

	/** Kills every mob of the player's current room. */
	public static void devClearRoom(ServerPlayer p) {
		Run r = runOf(p);
		if (r == null) return;
		for (Mob m : new ArrayList<>(r.mobs)) m.kill();
	}

	public static void devStartNext(ServerPlayer p) {
		Run r = runOf(p);
		if (r != null && !r.fighting && !r.done) startRoom(p.getServer(), r);
	}

	public static boolean devStartFirst(ServerPlayer p) {
		Run r = runOf(p);
		if (r == null || r.fighting || r.room != 0) return false;
		startRoom(p.getServer(), r);
		return true;
	}

	public static void clearAll() {
		if (tear != null && tear.gate != null) tear.gate.remove();
		for (RiftGate g : CLOSING) g.remove();
		CLOSING.clear();
		tear = null;
		RUNS.clear();
		TEST_PLAYERS.clear();
		nextTearAt = 0;
	}
}
