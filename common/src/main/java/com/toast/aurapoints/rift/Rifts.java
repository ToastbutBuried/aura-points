package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraSounds;
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
import net.minecraft.world.item.ItemStack;
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
 * Aura Rifts (5.41.0; Relic Trials 5.46.0). Now and then a Rift Gate opens in the overworld near a random player
 * (RiftGate, announced, open for RIFT_OPEN_SECONDS). Stepping in takes you, and up to RIFT_MAX_PLAYERS others, into
 * the aurapoints:rift dimension: a realm built for the run (RealmBuilder) - a hub island with an altar and four trial
 * islands. Each trial (Trials: a puzzle; mistakes call up the aura's touched mobs) frees a Relic Shard; the four shards
 * set on the altar (Altar) forge the aura's relic, and its guardian (Guardian) tears out of the altar and takes it.
 * A lethal hit is a knockout (back at the altar after RIFT_KNOCKOUT_SECONDS); if everyone is down at once, or the time
 * limit runs out, the run fails. Winning pays Aura, mastery XP and the relic (always on your first clear of an aura).
 * Runs are not saved: logging in inside a rift sends you back to where you came from; shards never leave the rift.
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
	public static final String SHARD_TAG = "aurapoints_rift_shard";
	static final int FLOOR = Monument.ORIGIN_Y;

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
		/** The monument being placed for the run this gate leads to (5.47), and that run's id. */
		Monument.Job job;
		int runId;
		/** Players being pulled through the curtain -> ticks left. */
		final Map<UUID, Integer> pulling = new java.util.HashMap<>();

		Tear(AuraType aura, Vec3 pos, long closes) {
			this.aura = aura;
			this.pos = pos;
			this.closes = closes;
		}
	}

	/** A party's run through one realm. */
	static final class Run {
		final int id, slot;
		final AuraType aura;
		final Set<UUID> players = new LinkedHashSet<>(), down = new HashSet<>();
		/** Knocked out players -> the game time they come back. */
		final Map<UUID, Long> backAt = new java.util.HashMap<>();
		Trials.Trial[] trials = new Trials.Trial[0];
		/** Trials whose shard is on the altar. */
		final Set<Integer> placedTrials = new HashSet<>();
		Altar altar;
		boolean built, done, won;
		/** The guardian is up. */
		boolean boss;
		final List<Mob> mobs = new ArrayList<>();
		/** Every Keystone cache (decorated pot) of the run's wings, so no two share a spot. */
		final Set<BlockPos> caches = new HashSet<>();
		Mob champion;
		ServerBossEvent bar;
		long startedAt = -1, endAt = -1;
		boolean warnedTime, warnedTwo;
		/** Seeds the trial arenas still waiting to be built (one per 10 ticks after the vote). */
		net.minecraft.util.RandomSource trialRandom;

		Run(int id, AuraType aura) {
			this.id = id;
			this.slot = id % 16;
			this.aura = aura;
		}

		Difficulty difficulty = Difficulty.MEDIUM;
		final Reliquary.State flow = new Reliquary.State();

		/** The monument's origin: the floor block under the altar at the crossing. */
		BlockPos hub() {
			return Monument.origin(slot);
		}

		/** The middle of trial wing {@code i}'s floor. */
		BlockPos island(int i) {
			return hub().offset(Monument.anchors().wings.get(i));
		}

		/** The altar socket for wing {@code i} (its pillar's foot). */
		BlockPos pedestal(int i) {
			return hub().offset(Monument.anchors().sockets.get(i));
		}

		/** Where players arrive: the landing at the end of the causeway. */
		Vec3 spawn() {
			return Monument.world(hub(), Monument.anchors().spawn);
		}

		/** Where knocked out players come back before the boss: the nave, in sight of the altar. */
		Vec3 nave() {
			return Vec3.atBottomCenterOf(hub()).add(0, 1, 18);
		}

		Reliquary.Phase phase() {
			return flow.phase;
		}

		int solved() {
			int n = 0;
			for (Trials.Trial t : trials) if (t.solved) n++;
			return n;
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
		// the monument starts forming at once, a tile a tick; the gate opens when it stands
		ServerLevel rift = level(server);
		tear.runId = nextId++;
		if (rift != null) {
			tear.job = Monument.start(rift, tear.runId % 16, aura);
			Trials.preload(rift, tear.runId % 16, aura, true);
		}
		// the curtain faces whoever is nearest (the player it opened for, or the one who ran the command)
		Vec3 ground = pos.subtract(0, 1.6, 0);
		ServerPlayer facing = near != null ? near : ow.getNearestPlayer(pos.x, pos.y, pos.z, 64, false) instanceof ServerPlayer sp ? sp : null;
		Vec3 face = facing == null ? Vec3.directionFromRotation(0, ow.random.nextFloat() * 360f) : facing.position().subtract(ground);
		tear.gate = new RiftGate(ow, aura, ground, face);
		tear.gate.show();
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
		// a gate nobody went through: its arena areas needn't stay loaded
		ServerLevel lvl = level(server);
		if (tear.run == null && lvl != null) Trials.preload(lvl, tear.runId % 16, tear.aura, false);
		if (tear.job != null) tear.job.release();
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
		// runs are never saved: their mobs, shards and trial displays are leftovers once loaded again
		return loadedFromDisk && (e.getTags().contains(MOB_TAG) || e.getTags().contains(SHARD_TAG) || e.getTags().contains(Trials.DISPLAY_TAG));
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
		SkillChecks.tick();
		if (!AuraConfig.RIFT_ENABLED) {
			// no new gates; a run under way is played to its end
			if (tear != null) closeTear(server, false);
			nextTearAt = 0;
		} else if (nextTearAt == 0) nextTearAt = now + interval(ow);
		// a new tear now and then
		if (AuraConfig.RIFT_ENABLED && tear == null && now >= nextTearAt) {
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
		if (now >= tear.closes || (tear.run != null && (tear.run.solved() > 0 || tear.run.done))) {
			closeTear(server, true);
			return;
		}
		if (tear.gate != null) tear.gate.tick();
		boolean forming = tear.job != null && !tear.job.done();
		if (forming) tear.job.step();
		if (now % 20 == 0 && tear.label != null) {
			com.toast.aurapoints.arena.Holograms.setTextPublic(tear.label, forming
					? Component.literal(tear.aura.displayName.toUpperCase(java.util.Locale.ROOT) + " RIFT").withStyle(s -> s.withColor(TextColor.fromRgb(tear.aura.rgb)).withBold(true))
					.append(Component.literal("\nthe way is forming... " + Math.round(tear.job.progress() * 100) + "%").withStyle(ChatFormatting.GRAY))
					: label(tear.aura, (tear.closes - now) / 20));
		}
		if (forming) return;
		// stepping into the curtain: pulled into its middle for a moment, then through
		Tear t = tear;
		for (ServerPlayer pl : ow.players()) {
			if (pl.isSpectator() || t.pulling.containsKey(pl.getUUID()) || runOf(pl) != null || t.gate == null || !t.gate.touching(pl)) continue;
			if (t.run != null && t.run.players.size() >= AuraConfig.RIFT_MAX_PLAYERS) {
				if (now % 20 == 0) pl.displayClientMessage(Component.literal("This rift is full.").withStyle(ChatFormatting.RED), true);
				continue;
			}
			t.pulling.put(pl.getUUID(), PULL_TICKS);
			ow.playSound(null, pl.getX(), pl.getY(), pl.getZ(), AuraSounds.PORTAL_TRIGGER.value(), net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.6f);
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
				ow.playSound(null, pl.getX(), pl.getY(), pl.getZ(), AuraSounds.ENDERMAN_TELEPORT.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1f, 0.6f);
				enter(server, pl);
				if (tear != t) return; // the run started and closed this tear
				continue;
			}
			e.setValue(left);
			t.gate.pull(pl, left);
		}
	}

	/** A player walks into the tear: into its run (the first one starts it: the vote in the entry hall). */
	static void enter(MinecraftServer server, ServerPlayer player) {
		ServerLevel rift = level(server);
		if (rift == null || tear == null) return;
		if (com.toast.aurapoints.arena.Arena.in(player) || com.toast.aurapoints.arena.Match.signedUp(player)) return;
		if (tear.job == null) tear.job = Monument.start(rift, tear.runId % 16, tear.aura);
		Trials.preload(rift, tear.runId % 16, tear.aura, true);
		if (!tear.job.done()) tear.job.finish(); // dev tests and commands don't wait for the gate
		if (tear.run == null) {
			tear.run = new Run(tear.runId, tear.aura);
			RUNS.add(tear.run);
		}
		Run run = tear.run;
		if (run.players.size() >= AuraConfig.RIFT_MAX_PLAYERS) {
			player.displayClientMessage(Component.literal("This rift is full.").withStyle(ChatFormatting.RED), true);
			return;
		}
		saveReturn(server, player);
		run.players.add(player.getUUID());
		if (!run.built) {
			run.built = true;
			run.startedAt = rift.getGameTime();
			run.altar = new Altar(run);
			Reliquary.startVote(rift, run);
		}
		Vec3 start = run.spawn();
		if (!com.toast.aurapoints.shim.Platform.get().isFakePlayer(player)) {
			player.teleportTo(rift, start.x, start.y, start.z, 180f, 0f);
			player.setGameMode(GameType.ADVENTURE);
		}
		player.setHealth(player.getMaxHealth());
		player.sendSystemMessage(Component.literal("✦ " + Relics.name(run.aura) + " lies broken in four trials. ").withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb)))
				.append(Component.literal(run.phase() == Reliquary.Phase.VOTE ? "Vote on the difficulty: stand on a plate in the entry hall." : "")
						.withStyle(ChatFormatting.GRAY)));
		if (run.phase() != Reliquary.Phase.VOTE) trialList(run, player);
		if (!com.toast.aurapoints.shim.Platform.get().isFakePlayer(player))
			com.toast.aurapoints.network.NetworkHandler.sendFx(player, com.toast.aurapoints.network.FxPacket.RIFT_SKY, 0, run.aura.id());
		Reliquary.arrivalShot(run, player);
	}

	/** The trials, their wings and colours. */
	static void trialList(Run run, ServerPlayer player) {
		for (Trials.Trial t : run.trials) {
			player.sendSystemMessage(Component.literal("  " + Reliquary.WING_NAMES[t.index] + " wing").withStyle(Reliquary.WING_FMT[t.index], ChatFormatting.BOLD)
					.append(Component.literal(": " + t.name() + (t.solved ? " ✔" : "")).withStyle(t.solved ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
		}
		player.sendSystemMessage(Component.literal("  Each shard goes in the socket of its colour at the altar.").withStyle(ChatFormatting.DARK_GRAY));
	}

	/** Dev tests: the trial kinds of the next realm (null = random). */
	static List<Trials.Kind> DEV_KINDS;

	/** The vote is over: four trials picked from the five kinds and set up in the wings, at the run's difficulty. */
	static void buildTrials(ServerLevel rift, Run run) {
		run.startedAt = rift.getGameTime();
		var r = net.minecraft.util.RandomSource.create(run.id * 31L + rift.getGameTime());
		List<Trials.Kind> kinds = DEV_KINDS != null ? new ArrayList<>(DEV_KINDS) : Trials.pick(run.aura, r);
		run.trials = new Trials.Trial[4];
		for (int i = 0; i < 4; i++) run.trials[i] = Trials.make(kinds.get(i), run, i);
		// arenas are big: one per 10 ticks (dev tests build them all at once)
		if (DEV_KINDS != null) for (Trials.Trial t : run.trials) t.buildAll(rift, r);
		run.trialRandom = r;
	}

	// ---------------------------------------------------------------
	// A run
	// ---------------------------------------------------------------

	static List<ServerPlayer> online(MinecraftServer server, Run run) {
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

	/** The hurry music plays for this last stretch of the time limit. */
	private static final int HURRY_TICKS = 2400;

	private static void music(List<ServerPlayer> to, int left, String text) {
		for (ServerPlayer p : to) com.toast.aurapoints.network.NetworkHandler.sendFx(p, com.toast.aurapoints.network.FxPacket.RIFT_MUSIC, left, text);
	}

	/** Moves the time limit so {@code seconds} are left (client playtests). */
	public static void devTimeLeft(ServerPlayer p, int seconds) {
		Run run = runOf(p);
		if (run == null) return;
		long limit = Math.round(run.difficulty.minutes * 1200L * (AuraConfig.RIFT_TIME_LIMIT_MINUTES / 25.0));
		run.startedAt = p.level().getGameTime() - limit + seconds * 20L;
	}

	/** Ends the caller's run (client playtests). */
	public static void devEnd(ServerPlayer p, boolean won) {
		Run run = runOf(p);
		if (run != null) end(p.getServer(), run, won);
	}

	private static void tickRun(MinecraftServer server, ServerLevel rift, Run run) {
		long now = rift.getGameTime();
		List<ServerPlayer> here = online(server, run);
		if (run.done) {
			if (now >= run.endAt) finish(server, run);
			return;
		}
		if (!run.built) return;
		if (here.isEmpty() && (tear == null || tear.run != run)) {
			end(server, run, false);
			return;
		}
		Reliquary.Phase phase = run.phase();
		// the time limit (from the end of the vote)
		if (phase != Reliquary.Phase.VOTE && AuraConfig.RIFT_TIME_LIMIT_MINUTES > 0) {
			long limit = Math.round(run.difficulty.minutes * 1200L * (AuraConfig.RIFT_TIME_LIMIT_MINUTES / 25.0));
			long left = run.startedAt + limit - now;
			if (left <= HURRY_TICKS && left > 1200 && !run.warnedTwo) {
				run.warnedTwo = true;
				for (ServerPlayer p : here) p.sendSystemMessage(Component.literal("Two minutes left - hurry.").withStyle(ChatFormatting.GOLD));
			}
			if (left <= HURRY_TICKS && left > 0 && now % 20 == 0) music(here, (int) left, "");
			if (left <= 1200 && !run.warnedTime) {
				run.warnedTime = true;
				for (ServerPlayer p : here) p.sendSystemMessage(Component.literal("The Reliquary begins to unravel - one minute left.").withStyle(ChatFormatting.RED));
			}
			if (left <= 0) {
				end(server, run, false);
				return;
			}
		}
		List<ServerPlayer> up = new ArrayList<>();
		for (ServerPlayer p : here) {
			if (run.down.contains(p.getUUID())) {
				Long back = run.backAt.get(p.getUUID());
				if (back != null && now >= back) revive(rift, run, p);
				continue;
			}
			up.add(p);
			// fell into the void: back to the wing you fell from, the crypt in the boss fight, else the nave
			if (p.getY() < FLOOR - 75) {
				// during the vote the doors are shut: back to the arrival landing, never past them
				Vec3 to = phase == Reliquary.Phase.BOSS || phase == Reliquary.Phase.COLLAPSE ? Reliquary.cryptSpot(run, rift.random)
						: phase == Reliquary.Phase.VOTE ? run.spawn() : run.nave();
				if (phase == Reliquary.Phase.TRIALS)
					for (Trials.Trial t : run.trials) if (t.contains(new Vec3(p.getX(), FLOOR, p.getZ()))) to = t.respawn(new Vec3(p.getX(), FLOOR, p.getZ()));
				p.teleportTo(rift, to.x, to.y, to.z, p.getYRot(), 0f);
				p.fallDistance = 0;
			}
		}
		// the last one standing left, and nobody who is down comes back
		if (!here.isEmpty() && here.stream().allMatch(p -> run.down.contains(p.getUUID()) && !run.backAt.containsKey(p.getUUID()))) {
			end(server, run, false);
			return;
		}
		if (phase == Reliquary.Phase.VOTE) {
			Reliquary.tickVote(rift, run, up, now);
			return;
		}
		for (Trials.Trial t : run.trials) {
			// once its chunks are in (preloaded since the gate opened; 30 s at most, then a blocking load)
			if (!t.built && run.trialRandom != null && now >= run.startedAt + 10L * (t.index + 1) && (t.chunksReady(rift) || now >= run.startedAt + 600))
				t.buildAll(rift, run.trialRandom);
			t.tickAll(rift, up, now);
			if (run.done) return; // a knockout in there ended it
		}
		if (phase == Reliquary.Phase.TRIALS) {
			if (now % 40 == 0) keepShards(rift, run);
			if (now % 10 == 0) Reliquary.tickGuidance(rift, run, up, now);
			if (run.altar.tick(rift)) Reliquary.startCollapse(rift, run);
		}
		if (phase == Reliquary.Phase.COLLAPSE) {
			if (Reliquary.tickCollapse(rift, run, here, now)) wakeGuardian(rift, run, Math.max(1, up.size()));
			if (now - run.flow.collapseAt >= Reliquary.ENTRANCE_END) {
				run.flow.phase = Reliquary.Phase.BOSS;
				for (ServerPlayer p : here) AuraFx.title(p, Component.literal("RELIC WARDEN").withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb)).withBold(true)),
						Component.literal("it took the " + Relics.name(run.aura) + " - take it back").withStyle(ChatFormatting.GRAY));
				if (run.champion instanceof RelicWarden w) {
					w.awake = true;
					w.devEntrance(false);
				}
			}
		}
		if (run.bar != null && run.champion != null && now % 5 == 0) {
			run.bar.setProgress(Math.max(0f, run.champion.getHealth() / run.champion.getMaxHealth()));
		}
		if (now % 10 != 0) return;
		run.mobs.removeIf(m -> {
			if (m.getY() < FLOOR - 80) m.discard();
			return !m.isAlive() || m.isRemoved();
		});
		if (phase == Reliquary.Phase.BOSS && now % 100 == 0) unstick(rift, run);
		if (run.boss && (run.champion == null || !run.champion.isAlive() || run.champion.isRemoved())) {
			if (run.bar != null) run.bar.removeAllPlayers();
			end(server, run, true);
		}
	}

	/** Adds that haven't moved in 5 s (stuck on a ledge, in a corner) are put back on the crypt floor. */
	private static final Map<UUID, Vec3> LAST_POS = new java.util.HashMap<>();

	private static void unstick(ServerLevel rift, Run run) {
		for (Mob m : run.mobs) {
			if (m == run.champion || m.isNoAi()) continue;
			Vec3 was = LAST_POS.put(m.getUUID(), m.position());
			if (was != null && was.distanceToSqr(m.position()) < 0.25 && m.getTarget() != null && m.distanceTo(m.getTarget()) > 3) {
				Vec3 to = Reliquary.cryptSpot(run, rift.random);
				m.teleportTo(to.x, to.y + 6, to.z);
				m.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 60, 0));
				LAST_POS.remove(m.getUUID());
			}
		}
	}

	// ---------------------------------------------------------------
	// Trials, shards, the altar
	// ---------------------------------------------------------------

	/** A right-click on a block inside a rift (main hand). True = a trial or the altar took it (cancel the click). */
	public static boolean onUse(ServerPlayer p, BlockPos pos) {
		if (!in(p)) return false;
		Run run = runOf(p);
		if (run == null || !run.built || run.done || run.down.contains(p.getUUID())) return false;
		ServerLevel rift = (ServerLevel) p.level();
		if (run.altar.use(rift, p, pos)) return true;
		// the portals' repair: a cache pot (any trial's) or a wing's repair pedestal
		for (Trials.Trial t : run.trials) if (t.built && t.repairUse(rift, p, pos)) return true;
		for (Trials.Trial t : run.trials) if (t.built && t.stoneUse(rift, p)) return true;
		if (PortalRepair.isStone(p.getMainHandItem())) return true; // never placed as a plain block
		for (Trials.Trial t : run.trials) if (t.built && t.inArena(Vec3.atCenterOf(pos)) && t.use(rift, p, pos)) return true;
		return false;
	}

	/** The client reports its camera shot over (RiftInputPacket.CAM_CLOSED). */
	public static void camClosed(ServerPlayer p) {
		RiftCam.closed(p);
	}

	/** A right-click in the air inside a rift (main hand): an arch stone aimed at its slot. True = taken (cancel). */
	public static boolean onUseAir(ServerPlayer p) {
		if (!in(p)) return false;
		Run run = runOf(p);
		if (run == null || !run.built || run.done || run.down.contains(p.getUUID())) return false;
		for (Trials.Trial t : run.trials) if (t.built && t.stoneUse((ServerLevel) p.level(), p)) return true;
		return PortalRepair.isStone(p.getMainHandItem());
	}

	static void onTrialSolved(ServerLevel level, Run run, Trials.Trial t, Vec3 shardAt) {
		Vec3 c = Vec3.atBottomCenterOf(t.c).add(0, 1, 0);
		level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 40, 0.3, 4, 0.3, 0.05);
		if (shardAt != null) {
			dropShard(level, run, t, shardAt);
			level.sendParticles(ParticleTypes.FLASH, shardAt.x, shardAt.y, shardAt.z, 1, 0, 0, 0, 0);
			RiftSfx.shardFree(level, shardAt);
		}
		// the trial's pedestal lights up
		BlockPos ped = run.pedestal(t.index);
		RealmBuilder.set(level, ped.getX(), FLOOR + 1, ped.getZ(), RealmBuilder.palette(run.aura).light());
		int n = run.solved();
		for (ServerPlayer p : online(level.getServer(), run)) {
			p.sendSystemMessage(Component.literal("✔ " + t.name() + " solved - a shard is free (" + n + "/4)").withStyle(ChatFormatting.GREEN));
		}
	}

	/** Puts trial {@code t}'s shard into the world at {@code at} (glowing, never despawning). */
	static void dropShard(ServerLevel level, Run run, Trials.Trial t, Vec3 at) {
		var item = new net.minecraft.world.entity.item.ItemEntity(level, at.x, at.y, at.z, com.toast.aurapoints.item.RelicShardItem.make(run.aura, run.id, t.index), 0, 0.15, 0);
		item.setUnlimitedLifetime();
		item.setGlowingTag(true);
		item.setPickUpDelay(10);
		item.addTag(SHARD_TAG);
		level.addFreshEntity(item);
		t.shardOut = true;
	}

	static boolean isShard(ItemStack s, Run run, int trial) {
		return s.getItem() instanceof com.toast.aurapoints.item.RelicShardItem && com.toast.aurapoints.item.RelicShardItem.run(s) == run.id
				&& com.toast.aurapoints.item.RelicShardItem.trial(s) == trial;
	}

	static boolean holdsShard(ServerPlayer p, Run run, int trial) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (isShard(inv.getItem(i), run, trial)) return true;
		return isShard(p.containerMenu.getCarried(), run, trial);
	}

	static void takeShard(ServerPlayer p, Run run, int trial) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (isShard(inv.getItem(i), run, trial)) inv.setItem(i, ItemStack.EMPTY);
		if (isShard(p.containerMenu.getCarried(), run, trial)) p.containerMenu.setCarried(ItemStack.EMPTY);
	}

	/** A shard that is out but nowhere any more (fell into the void, burned, left with a player) comes back to its plinth. */
	private static void keepShards(ServerLevel rift, Run run) {
		List<ServerPlayer> here = online(rift.getServer(), run);
		var box = new net.minecraft.world.phys.AABB(Vec3.atCenterOf(run.hub()), Vec3.atCenterOf(run.hub())).inflate(510, 120, 510);
		List<net.minecraft.world.entity.item.ItemEntity> items = rift.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box, e -> e.getTags().contains(SHARD_TAG));
		for (Trials.Trial t : run.trials) {
			if (!t.shardOut || run.placedTrials.contains(t.index)) continue;
			boolean found = false;
			for (var it : items) {
				if (isShard(it.getItem(), run, t.index)) {
					if (it.getY() < FLOOR - 30) it.discard();
					else found = true;
				}
			}
			for (ServerPlayer p : here) if (holdsShard(p, run, t.index)) found = true;
			if (!found) {
				dropShard(rift, run, t, t.plinth());
				for (ServerPlayer p : here) p.displayClientMessage(Component.literal("A lost shard drifts back to the " + t.name() + " island.").withStyle(ChatFormatting.GOLD), true);
			}
		}
	}

	/** A puzzle mistake's price: one of the aura's touched mobs (stronger with every trial solved). */
	static Mob trialMob(ServerLevel rift, Run run, Vec3 at) {
		Kind k = KINDS[rift.random.nextInt(Math.min(KINDS.length, 3 + run.solved() * 2))];
		Mob m = spawnMob(rift, run, k.type, at, 2);
		if (m != null) run.mobs.add(m);
		return m;
	}

	/** Everything a rift hands out, taken back on the way out. */
	static void purge(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(AuraPoints.RELIC_SHARD.get()) || s.is(AuraPoints.TUNING_FORK.get())) inv.setItem(i, ItemStack.EMPTY);
		}
		Trials.purgeLent(p);
		Trials.purgeGrid(p, s -> s.is(AuraPoints.RELIC_SHARD.get()) || s.is(AuraPoints.TUNING_FORK.get()) || Trials.isKeystone(s) || PortalRepair.isStone(s)
				|| PortalRepair.isCompass(s) || TrialBells.isBaton(s));
		SkillChecks.forget(p.getUUID());
		RiftCam.forget(p.getUUID());
		ItemStack c = p.containerMenu.getCarried();
		if (c.is(AuraPoints.RELIC_SHARD.get()) || c.is(AuraPoints.TUNING_FORK.get())) p.containerMenu.setCarried(ItemStack.EMPTY);
	}

	// ---------------------------------------------------------------
	// The guardian
	// ---------------------------------------------------------------

	record Kind(EntityType<? extends Mob> type, int cost, int fromRoom) {
	}

	static final Kind[] KINDS = {
			new Kind(EntityType.ZOMBIE, 1, 0), new Kind(EntityType.SKELETON, 2, 0), new Kind(EntityType.SPIDER, 2, 0),
			new Kind(EntityType.HUSK, 2, 1), new Kind(EntityType.STRAY, 2, 1), new Kind(EntityType.CREEPER, 3, 1),
			new Kind(EntityType.WITCH, 4, 2), new Kind(EntityType.VINDICATOR, 5, 2), new Kind(EntityType.BLAZE, 5, 3)};

	static Mob spawnMob(ServerLevel rift, Run run, EntityType<? extends Mob> type, Vec3 c, int radius) {
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
		double scale = AuraConfig.RIFT_SCALE_PER_ROOM * run.solved();
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

	/** The altar's ritual is over: the realm's guardian takes the relic. */
	private static void wakeGuardian(ServerLevel rift, Run run, int players) {
		run.boss = true;
		Vec3 c = Reliquary.cryptCenter(run).add(0, 2, 0);
		Mob boss = Guardian.spawn(rift, run, c, players);
		if (boss == null) {
			end(rift.getServer(), run, true);
			return;
		}
		run.champion = boss;
		run.mobs.add(boss);
		Component name = boss.getCustomName() != null ? boss.getCustomName() : Component.literal("Guardian");
		BossEvent.BossBarColor color = switch (run.aura) {
			case FIRE, BLOOD -> BossEvent.BossBarColor.RED;
			case WATER, FROST -> BossEvent.BossBarColor.BLUE;
			case VERDANT -> BossEvent.BossBarColor.GREEN;
			case THUNDER, EARTH, LIGHT -> BossEvent.BossBarColor.YELLOW;
			case WIND -> BossEvent.BossBarColor.WHITE;
			default -> BossEvent.BossBarColor.PURPLE;
		};
		run.bar = new ServerBossEvent(name, color, BossEvent.BossBarOverlay.NOTCHED_10);
		for (ServerPlayer p : online(rift.getServer(), run)) {
			run.bar.addPlayer(p);
			// the name card comes when the entrance ends (Rifts.tickRun)
			p.sendSystemMessage(Component.literal("✦ Its attacks flash before they land: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal("YELLOW").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
					.append(Component.literal(" - one fully charged hit just before it lands parries it (swing early and you're staggered). ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal("BLUE").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
					.append(Component.literal(" - get out of the way.").withStyle(ChatFormatting.GRAY)));
		}
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
		if (run.altar != null) run.altar.remove();
		for (Trials.Trial t : run.trials) {
			for (ServerPlayer p : online(server, run)) if (t.inside.contains(p.getUUID())) t.release(p);
			t.remove();
		}
		Reliquary.cleanup(run);
		RiftCam.stop(online(server, run));
		music(online(server, run), 0, won ? "won" : "");
		for (ServerPlayer p : online(server, run)) {
			if (won) {
				AuraFx.title(p, Component.literal("RELIC RECLAIMED").withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb)).withBold(true)),
						Component.literal("back in a moment").withStyle(ChatFormatting.GRAY));
			} else {
				AuraFx.title(p, Component.literal("The realm collapses").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
						Component.literal(run.solved() + " trial(s) solved").withStyle(ChatFormatting.GRAY));
			}
			pay(p, run, won);
		}
	}

	private static void pay(ServerPlayer p, Run run, boolean won) {
		int trials = run.solved();
		int aura = (int) Math.round((trials * AuraConfig.RIFT_ROOM_AURA + (won ? AuraConfig.RIFT_BOSS_AURA : 0)) * run.difficulty.reward);
		if (aura > 0) AuraCurrency.add(p, aura);
		Mastery.addXp(p, trials * AuraConfig.RIFT_ROOM_MASTERY_XP + (won ? AuraConfig.RIFT_BOSS_MASTERY_XP : 0));
		p.sendSystemMessage(Component.literal((won ? "✔ Relic reclaimed: " : "Rift lost: ") + "+" + aura + " Aura").withStyle(won ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		if (!won) return;
		AuraStats.add(p, AuraStats.RIFTS_CLEARED, 1);
		AuraPlayerData data = AuraState.get(p.getServer()).getOrCreate(p.getUUID());
		boolean first = data.riftAuras.add(run.aura);
		AuraState.get(p.getServer()).markDirty();
		double chance = Math.min(1.0, AuraConfig.RIFT_RELIC_CHANCE * run.difficulty.relicChance / 0.5);
		if (first || p.getRandom().nextDouble() < chance) Relics.give(p, run.aura);
		else p.sendSystemMessage(Component.literal("The relic crumbles to dust in your hands - it only remembers its first bearer.").withStyle(ChatFormatting.DARK_GRAY));
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
		if (RUNS.isEmpty()) LAST_POS.clear();
		ServerLevel rift = level(server);
		if (rift != null) {
			// the big sky is cleared piece by piece while its chunks are still held, or its islands would hang about in the slot's next run
			int at = 1;
			for (Trials.Trial t : run.trials) {
				if (t.kind != Trials.Kind.RINGS || !t.built) continue;
				int h = t.arenaHalf() + 4, piece = (2 * h + 1 + 7) / 8;
				for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
					int px = t.a.getX() - h + x * piece, pz = t.a.getZ() - h + z * piece;
					com.toast.aurapoints.ability.AuraFx.later(server, at++, () -> Monument.clearColumns(rift, px, pz, piece));
				}
			}
			com.toast.aurapoints.ability.AuraFx.later(server, at + 1, () -> {
				// unless the slot is in use again by now
				for (Run o : RUNS) if (o.slot == run.slot) return;
				Trials.preload(rift, run.slot, run.aura, false);
			});
		}
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

	/** A trial's third strike on Hardcore. */
	static void knockoutFromTrial(ServerPlayer p) {
		Run run = runOf(p);
		if (run != null && !run.down.contains(p.getUUID())) knockout(p);
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
		ServerLevel rift = level(p.getServer());
		run.down.add(p.getUUID());
		boolean oneLife = run.difficulty.oneLife();
		int secs = Math.max(3, (int) Math.round(run.difficulty.knockoutSeconds * AuraConfig.RIFT_KNOCKOUT_SECONDS / 12.0));
		if (!oneLife) run.backAt.put(p.getUUID(), rift.getGameTime() + secs * 20L);
		else purge(p); // out for good: what they carry goes back to the realm (shards return to their plinth)
		p.setGameMode(GameType.SPECTATOR);
		boolean crypt = run.phase() == Reliquary.Phase.BOSS || run.phase() == Reliquary.Phase.COLLAPSE;
		Vec3 c = crypt ? Reliquary.cryptCenter(run).add(0, 14, 16) : run.nave().add(0, 9, 0);
		p.teleportTo(rift, c.x, c.y, c.z, 180f, 40f);
		AuraFx.title(p, Component.literal(oneLife ? "Fallen" : "Knocked out").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
				Component.literal(oneLife ? "Hardcore: you're out of this run - watch the others" : "back in " + secs + " s").withStyle(ChatFormatting.GRAY));
		boolean anyUp = false;
		for (UUID id : run.players) if (!run.down.contains(id) && player(p.getServer(), id) != null) anyUp = true;
		if (!anyUp) end(p.getServer(), run, false);
	}

	private static void revive(ServerLevel rift, Run run, ServerPlayer p) {
		run.down.remove(p.getUUID());
		run.backAt.remove(p.getUUID());
		p.setGameMode(GameType.ADVENTURE);
		p.setHealth(p.getMaxHealth());
		boolean crypt = run.phase() == Reliquary.Phase.BOSS || run.phase() == Reliquary.Phase.COLLAPSE;
		Vec3 s = crypt ? Reliquary.cryptSpot(run, rift.random) : run.phase() == Reliquary.Phase.VOTE ? run.spawn() : run.nave();
		p.teleportTo(rift, s.x, s.y, s.z, 180f, 0f);
		p.fallDistance = 0;
		AuraFx.burst(rift, s.x, s.y + 1, s.z, ParticleTypes.REVERSE_PORTAL, 30, 0.5, 0.2);
	}

	/** /aura leave or /aura rift leave: out of the rift (a run left by everyone ends). */
	public static boolean leave(ServerPlayer p) {
		if (!in(p)) return false;
		Run run = runOf(p);
		if (run != null) {
			run.players.remove(p.getUUID());
			run.down.remove(p.getUUID());
			run.backAt.remove(p.getUUID());
			if (run.bar != null) run.bar.removePlayer(p);
		}
		sendBack(p);
		return true;
	}

	public static void onLogout(ServerPlayer p) {
		SkillChecks.forget(p.getUUID());
		Run run = runOf(p);
		if (run != null) {
			// the trials' per-player state (bells picked, probes, immunity, lent wings) goes now: tickAll can't find them later
			for (Trials.Trial t : run.trials) if (t.inside.remove(p.getUUID())) t.release(p);
			run.players.remove(p.getUUID());
			run.down.remove(p.getUUID());
			run.backAt.remove(p.getUUID());
		}
	}

	/**
	 * Out of the rift some other way (/tp, a home command, a portal mod): out of the run too, and the
	 * game mode from before the rift comes back. The position stays where they went.
	 */
	public static void onChangedDimension(ServerPlayer p, ResourceKey<net.minecraft.world.level.Level> from, ResourceKey<net.minecraft.world.level.Level> to) {
		if (from != KEY || to == KEY || p.getServer() == null) return;
		purge(p);
		Run run = runOf(p);
		if (run != null) {
			run.players.remove(p.getUUID());
			run.down.remove(p.getUUID());
			run.backAt.remove(p.getUUID());
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
		else if (!in(p)) purge(p);
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
		purge(p);
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
				.then(net.minecraft.commands.Commands.literal("skip")
						.requires(src -> src.getPlayer() == null ? src.hasPermission(4) : com.toast.aurapoints.config.CommandRules.isOwner(src.getPlayer()))
						.executes(ctx -> {
							String msg = skipRoom(ctx.getSource().getPlayerOrException());
							ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
							return 1;
						}))
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

	/** /aura rift skip (owners): the trial the player stands in (wing or arena) counts as beaten and its shard drops at their feet. */
	static String skipRoom(ServerPlayer p) {
		Run run = runOf(p);
		ServerLevel rift = level(p.getServer());
		if (run == null || rift == null || p.level() != rift) return "You are not in a rift run.";
		for (Trials.Trial t : run.trials) {
			if (!t.inArena(p.position()) && !t.inWing(p.position())) continue;
			if (t.solved) return t.name() + " is already beaten.";
			if (!t.started) {
				t.started = true;
				t.begin(rift, List.of(p));
			}
			t.solve(rift, p.position());
			return t.name() + " skipped - the shard is at your feet.";
		}
		return "Stand inside a trial (its wing or arena) to skip it.";
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

	/** DEV ONLY: the open gate's relic display made it into the world. */
	static boolean devCoreShown(MinecraftServer server) {
		return tear != null && tear.gate != null && tear.gate.core != null && server.overworld().getEntity(tear.gate.core.getUUID()) != null;
	}

	static boolean devLabelShown(MinecraftServer server) {
		return tear != null && tear.label != null && server.overworld().getEntity(tear.label.getUUID()) != null;
	}

	public static Vec3 devTearPos() {
		return tear == null ? null : tear.pos;
	}

	public static void devEnter(MinecraftServer server, ServerPlayer p) {
		enter(server, p);
	}

	/** The player's run as text: "trials 1/4 placed 0 ritual -1 boss false mobs 0 down 0 done false won false". */
	public static String devState(ServerPlayer p) {
		Run r = runOf(p);
		if (r == null) return "no run";
		return "trials " + r.solved() + "/4 placed " + r.placedTrials.size() + " ritual " + (r.altar == null ? -1 : r.altar.ritual) + " boss " + r.boss
				+ " mobs " + r.mobs.size() + " down " + r.down.size() + " done " + r.done + " won " + r.won;
	}

	/** The kinds of the player's run's trials, in order (N, E, S, W). */
	public static String devKinds(ServerPlayer p) {
		Run r = runOf(p);
		if (r == null) return "no run";
		StringBuilder b = new StringBuilder();
		for (Trials.Trial t : r.trials) b.append(t.kind).append(t.solved ? "+" : "-").append(' ');
		return b.toString().trim();
	}

	/** Solves trial {@code i} of the player's run the way the trial would (shard out, pedestal lit). */
	public static void devSolve(ServerPlayer p, int i) {
		Run r = runOf(p);
		if (r == null || i < 0 || i >= r.trials.length) return;
		Trials.Trial t = r.trials[i];
		if (!t.started) {
			t.started = true;
			t.begin(level(p.getServer()), List.of());
		}
		t.solve(level(p.getServer()), t.plinth().add(0, 1, 0));
	}

	/** Hands the player every shard that is out, and right-clicks each pedestal with one. */
	public static void devPlaceAll(ServerPlayer p) {
		Run r = runOf(p);
		if (r == null) return;
		ServerLevel rift = level(p.getServer());
		for (Trials.Trial t : r.trials) {
			if (!t.shardOut) continue;
			for (var it : rift.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(Vec3.atCenterOf(r.hub()), Vec3.atCenterOf(r.hub())).inflate(510, 120, 510),
					e -> isShard(e.getItem(), r, t.index))) it.discard();
			takeShard(p, r, t.index);
			p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, com.toast.aurapoints.item.RelicShardItem.make(r.aura, r.id, t.index));
			r.altar.use(rift, p, r.pedestal(t.index).above(2));
		}
	}

	/** Votes for the player (plate 0 Easy .. 4 Hardcore) and ends the vote at once. */
	public static void devVote(ServerPlayer p, int plate) {
		Run r = runOf(p);
		if (r == null || r.phase() != Reliquary.Phase.VOTE) return;
		r.flow.votes.put(p.getUUID(), plate);
		Reliquary.finishVote(level(p.getServer()), r);
	}

	public static String devDifficulty(ServerPlayer p) {
		Run r = runOf(p);
		return r == null ? "no run" : r.difficulty.name() + " " + r.phase();
	}

	/** The world block of socket i's top (where a shard is set). */
	public static BlockPos devSocket(ServerPlayer p, int i) {
		Run r = runOf(p);
		return r == null ? null : r.pedestal(i).above(1);
	}

	public static boolean devDoorSealed(ServerPlayer p) {
		Run r = runOf(p);
		return r != null && r.flow.doorSealed;
	}

	/** Runs the ritual to its end at once (the guardian wakes). */
	public static void devFinishRitual(ServerPlayer p) {
		Run r = runOf(p);
		if (r == null || r.altar.ritual < 0) return;
		ServerLevel rift = level(p.getServer());
		while (r.altar.ritual >= 0 && r.altar.ritual < Altar.RITUAL_TICKS) if (r.altar.tick(rift)) wakeGuardian(rift, r, 1);
		r.flow.phase = Reliquary.Phase.BOSS;
		if (r.champion instanceof RelicWarden w) {
			w.awake = true;
			w.devEntrance(false);
		}
	}

	public static Mob devGuardian(ServerPlayer p) {
		Run r = runOf(p);
		return r == null ? null : r.champion;
	}

	public static boolean devHoldsShard(ServerPlayer p, int trial) {
		Run r = runOf(p);
		return r != null && holdsShard(p, r, trial);
	}

	public static int devShardItemsInWorld(ServerPlayer p) {
		Run r = runOf(p);
		if (r == null) return -1;
		return level(p.getServer()).getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
				new net.minecraft.world.phys.AABB(Vec3.atCenterOf(r.hub()), Vec3.atCenterOf(r.hub())).inflate(510, 120, 510), e -> e.getTags().contains(SHARD_TAG)).size();
	}

	/** A block of the run's realm: hub-relative. */
	public static BlockPos devHub(ServerPlayer p) {
		Run r = runOf(p);
		return r == null ? null : r.hub();
	}

	public static void devTickRun(ServerPlayer p) {
		Run r = runOf(p);
		if (r != null) tickRun(p.getServer(), level(p.getServer()), r);
	}

	public static void clearAll() {
		Trials.forgetPreloads();
		SkillChecks.clear();
		RiftCam.forget();
		TrialRings.forget();
		if (tear != null && tear.gate != null) tear.gate.remove();
		for (RiftGate g : CLOSING) g.remove();
		CLOSING.clear();
		tear = null;
		RUNS.clear();
		LAST_POS.clear();
		TEST_PLAYERS.clear();
		nextTearAt = 0;
	}
}
