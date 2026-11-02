package com.toast.aurapoints.arena;

import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraStats;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Mastery;
import com.toast.aurapoints.ability.TouchedMobs;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Wave Survival, Match's WAVES mode: 1-8 players hold the duel ring against endless waves of mobs that come in
 * through four gates on its rim. A wave's size is a budget (WAVES_BUDGET_BASE + WAVES_BUDGET_PER_WAVE x wave, more
 * per extra player) spent on mobs by cost; every WAVES_BOSS_EVERY-th wave brings an aura-touched champion with a boss
 * bar. From wave 3 a growing share of the mobs is aura-touched (TouchedMobs), from WAVES_SCALE_FROM they get tougher
 * every wave. Clearing a wave pays everyone who was in it, then a WAVES_BREAK_SECONDS break: the knocked out come
 * back, everyone is healed and late joiners step in. The run ends when everyone is down at once; the score is the last
 * wave cleared (WAVE RECORDS board, weekly challenge, "Wave Breaker" title).
 *
 * Match owns the players (lobby, join, knockouts, the end); this class owns the mobs. Wave mobs carry the entity
 * tag TAG, never drop loot or XP, can't grief, wear a cap so the noon sun doesn't burn them, and are thrown away
 * when one loads from disk (a run is never saved).
 */
public final class Waves {

	private Waves() {
	}

	static final String TAG = "aurapoints_wave";
	private static final ResourceLocation SCALE_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "wave_scale");
	private static final ResourceLocation BOSS_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "wave_boss");
	private static final ResourceLocation BOSS_SIZE_ID = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "wave_boss_size");

	/** A mob a wave can buy: its cost and the first wave it shows up in. */
	record Kind(EntityType<? extends Mob> type, int cost, int fromWave) {
	}

	static final List<Kind> KINDS = List.of(
			new Kind(EntityType.ZOMBIE, 1, 1),
			new Kind(EntityType.SKELETON, 2, 1),
			new Kind(EntityType.SPIDER, 2, 1),
			new Kind(EntityType.HUSK, 2, 2),
			new Kind(EntityType.STRAY, 2, 2),
			new Kind(EntityType.CREEPER, 3, 3),
			new Kind(EntityType.WITCH, 4, 6),
			new Kind(EntityType.VINDICATOR, 5, 6),
			new Kind(EntityType.BLAZE, 5, 8));

	/** The champions, in turn. */
	static final List<EntityType<? extends Mob>> BOSSES = List.of(EntityType.RAVAGER, EntityType.EVOKER, EntityType.WITHER_SKELETON);

	/** One run's mob side. */
	static final class Run {
		final long seed;
		int wave;
		/** Mobs still to come this wave (spawned as room frees up). */
		final List<EntityType<? extends Mob>> queue = new ArrayList<>();
		/** The touched ones among the queue: index in the queue at roll time -> aura (consumed as they spawn). */
		final List<AuraType> queueAura = new ArrayList<>();
		/** This wave's mobs that count toward clearing it. */
		final List<Mob> alive = new ArrayList<>();
		/** Mobs that came with them (vexes, jockey riders): cleared with the wave, not counted. */
		final Set<Mob> extras = new HashSet<>();
		Mob boss;
		ServerBossEvent bar;
		long waveStarted;
		/** Player -> the wave they joined in (paid from that one on). */
		final Map<UUID, Integer> joinedAt = new HashMap<>();
		/** Player -> Aura earned this run. */
		final Map<UUID, Integer> earned = new HashMap<>();

		Run(long seed) {
			this.seed = seed;
		}

		int left() {
			return alive.size() + queue.size();
		}

		boolean bossWave() {
			return isBossWave(wave);
		}
	}

	static boolean isBossWave(int wave) {
		int every = AuraConfig.WAVES_BOSS_EVERY;
		return every > 0 && wave % every == 0;
	}

	/** Set while this class adds mobs: anything joining the level meanwhile (a jockey's rider) is one of ours. */
	private static boolean spawning;
	/** The run being fought, for the entity hooks (null when none). */
	private static Run active;

	// ---------------------------------------------------------------
	// The plan of a wave
	// ---------------------------------------------------------------

	static int budget(int wave, int players) {
		double b = AuraConfig.WAVES_BUDGET_BASE + AuraConfig.WAVES_BUDGET_PER_WAVE * (double) wave;
		b *= 1.0 + Math.max(0, players - 1) * Math.max(0.0, AuraConfig.WAVES_EXTRA_PLAYER_BONUS);
		if (isBossWave(wave)) b *= 0.5; // the champion is most of a boss wave
		return Math.max(1, (int) Math.round(b));
	}

	/** Share of a wave's mobs that are aura-touched: none before WAVES_TOUCHED_FROM, then rising to the max over 17 waves. */
	static double touchedShare(int wave) {
		int from = Math.max(1, AuraConfig.WAVES_TOUCHED_FROM);
		if (wave < from) return 0;
		double t = Math.min(1.0, (wave - from) / 17.0);
		return AuraConfig.WAVES_TOUCHED_START + (AuraConfig.WAVES_TOUCHED_MAX - AuraConfig.WAVES_TOUCHED_START) * t;
	}

	/** Extra health and damage (0.25 = +25%) for this wave's mobs. */
	static double scaling(int wave) {
		int from = Math.max(1, AuraConfig.WAVES_SCALE_FROM);
		return wave < from ? 0 : (wave - from + 1) * Math.max(0.0, AuraConfig.WAVES_SCALE_PER_WAVE);
	}

	/** Rolls a wave: the mobs (spent from the budget, dearest affordable first-ish) and which are touched. */
	static void roll(Run run, int players) {
		Random r = new Random(run.seed * 31L + run.wave);
		run.queue.clear();
		run.queueAura.clear();
		int left = budget(run.wave, players);
		List<Kind> open = new ArrayList<>();
		for (Kind k : KINDS) if (run.wave >= k.fromWave) open.add(k);
		while (left > 0) {
			List<Kind> fits = new ArrayList<>();
			for (Kind k : open) if (k.cost <= left) fits.add(k);
			if (fits.isEmpty()) break;
			Kind k = fits.get(r.nextInt(fits.size()));
			run.queue.add(k.type);
			left -= k.cost;
		}
		java.util.Collections.shuffle(run.queue, r);
		double share = touchedShare(run.wave);
		AuraType[] auras = AuraType.values();
		for (int i = 0; i < run.queue.size(); i++) run.queueAura.add(r.nextDouble() < share ? auras[r.nextInt(auras.length)] : null);
	}

	/** Dev test: "wave 7: budget 27, 14 mobs {ZOMBIE=5, ...}, touched 4, +0%" for waves 1..n. */
	static List<String> describePlan(int players, int waves) {
		List<String> out = new ArrayList<>();
		Run run = new Run(42);
		for (int w = 1; w <= waves; w++) {
			run.wave = w;
			roll(run, players);
			Map<String, Integer> count = new java.util.TreeMap<>();
			for (EntityType<?> t : run.queue) count.merge(EntityType.getKey(t).getPath(), 1, Integer::sum);
			int touched = 0;
			for (AuraType a : run.queueAura) if (a != null) touched++;
			out.add("wave " + w + ": budget " + budget(w, players) + ", " + run.queue.size() + " mobs " + count + ", touched " + touched
					+ ", +" + Math.round(scaling(w) * 100) + "%" + (isBossWave(w) ? ", BOSS " + EntityType.getKey(bossType(w)).getPath() + " " + Math.round(bossHealth(w, players)) + " hp" : ""));
		}
		return out;
	}

	static EntityType<? extends Mob> bossType(int wave) {
		int every = Math.max(1, AuraConfig.WAVES_BOSS_EVERY);
		return BOSSES.get((wave / every - 1) % BOSSES.size());
	}

	// ---------------------------------------------------------------
	// A run
	// ---------------------------------------------------------------

	static Run newRun(MinecraftServer server) {
		Run run = new Run(server.overworld().getRandom().nextLong());
		active = run;
		return run;
	}

	/** The next wave: rolled, announced; its mobs start coming in through the gates. */
	static void startWave(MinecraftServer server, ServerLevel arena, Run run, List<ServerPlayer> players) {
		run.wave++;
		run.waveStarted = server.getTickCount();
		roll(run, players.size());
		ArenaBuilder.setGates(arena, false);
		ArenaFx.sound(arena, new Vec3(0.5, Arena.RING_FLOOR + 2, Arena.RING_Z + 0.5), SoundEvents.IRON_DOOR_OPEN, 1.5f, 0.6f);
		Component title = Component.literal("Wave " + run.wave).withStyle(run.bossWave() ? ChatFormatting.DARK_RED : ChatFormatting.GOLD, ChatFormatting.BOLD);
		Component sub = Component.literal(run.bossWave() ? "A champion comes!" : run.left() + " mobs incoming").withStyle(ChatFormatting.GRAY);
		for (ServerPlayer p : players) {
			ArenaFx.title(p, title, sub);
			AuraFx.sound(arena, p, run.bossWave() ? SoundEvents.RAID_HORN.value() : SoundEvents.NOTE_BLOCK_BELL.value(), run.bossWave() ? 0.6f : 1f, 0.8f);
		}
		if (run.bossWave()) spawnBoss(arena, run, players.size());
	}

	/** Every tick of a wave. True once it's cleared. */
	static boolean tickWave(MinecraftServer server, ServerLevel arena, Run run, List<ServerPlayer> fighters, List<ServerPlayer> everyone) {
		long now = server.getTickCount();
		run.alive.removeIf(m -> m.isRemoved() || !m.isAlive());
		run.extras.removeIf(m -> m.isRemoved() || !m.isAlive());
		if (run.boss != null && (run.boss.isRemoved() || !run.boss.isAlive())) {
			run.boss = null;
			if (run.bar != null) run.bar.removeAllPlayers();
			run.bar = null;
		}
		// let them in, a few at a time
		if (now % 10 == 0) {
			int room = Math.max(1, AuraConfig.WAVES_MAX_ALIVE) - run.alive.size();
			for (int i = 0; i < Math.min(4, room) && !run.queue.isEmpty(); i++) {
				EntityType<? extends Mob> type = run.queue.remove(0);
				AuraType aura = run.queueAura.isEmpty() ? null : run.queueAura.remove(0);
				Mob m = spawn(arena, run, type, aura, gate(arena.getRandom().nextInt(4)));
				if (m != null) run.alive.add(m);
			}
		}
		if (now % 20 == 0) {
			Vec3 mid = new Vec3(0.5, Arena.RING_FLOOR + 1, Arena.RING_Z + 0.5);
			boolean glow = now - run.waveStarted > Math.max(5, AuraConfig.WAVES_GLOW_AFTER_SECONDS) * 20L && run.left() <= 3;
			List<Mob> all = new ArrayList<>(run.alive);
			all.addAll(run.extras);
			if (run.boss != null) all.add(run.boss);
			for (Mob m : all) {
				// off the ring floor (over the wall, knocked up high): back through a gate
				double dx = m.getX() - mid.x, dz = m.getZ() - mid.z;
				if (dx * dx + dz * dz > (Arena.RING_R - 0.5) * (Arena.RING_R - 0.5) || m.getY() < Arena.RING_FLOOR - 1 || m.getY() > Arena.FLOOR + 3) {
					Vec3 g = gate(arena.getRandom().nextInt(4));
					m.teleportTo(g.x, g.y, g.z);
					m.setDeltaMovement(Vec3.ZERO);
				}
				// after the closest standing player
				if (!(m.getTarget() instanceof ServerPlayer t) || !fighters.contains(t)) {
					ServerPlayer best = null;
					double bestD = Double.MAX_VALUE;
					for (ServerPlayer p : fighters) {
						double d = p.distanceToSqr(m);
						if (d < bestD) {
							bestD = d;
							best = p;
						}
					}
					m.setTarget(best);
				}
				if (glow && !m.hasGlowingTag()) m.setGlowingTag(true);
			}
		}
		if (run.bar != null && run.boss != null && now % 5 == 0) {
			run.bar.setProgress(Math.max(0f, Math.min(1f, run.boss.getHealth() / run.boss.getMaxHealth())));
			for (ServerPlayer p : arena.players()) if (!run.bar.getPlayers().contains(p)) run.bar.addPlayer(p);
		}
		if (now % 10 == 0) {
			Component bar = Component.literal("☠ Wave " + run.wave + " · " + run.left() + " left · " + fighters.size() + " standing").withStyle(ChatFormatting.GOLD);
			for (ServerPlayer p : everyone) p.displayClientMessage(bar, true);
		}
		if (now % 4 == 0 && !run.queue.isEmpty()) {
			for (int i = 0; i < 4; i++) {
				Vec3 g = gate(i);
				AuraFx.ring(arena, g.x, g.y + 0.1, g.z, 1.4, AuraFx.dust(0xFFC83D, 1.2f), 12);
			}
		}
		return run.left() == 0 && run.boss == null;
	}

	/** How long the break after the wave just cleared lasts (longer after a boss wave: aura switching). */
	static int breakSeconds(Run run) {
		return Math.max(1, run.bossWave() ? AuraConfig.WAVES_BOSS_BREAK_SECONDS : AuraConfig.WAVES_BREAK_SECONDS);
	}

	/** Cleared: everyone who was in it gets paid. */
	static void payWave(Run run, List<ServerPlayer> players) {
		int aura = Math.max(0, AuraConfig.WAVES_AURA_PER_WAVE) + (run.bossWave() ? Math.max(0, AuraConfig.WAVES_BOSS_BONUS) : 0);
		for (ServerPlayer p : players) {
			if (run.joinedAt.getOrDefault(p.getUUID(), Integer.MAX_VALUE) > run.wave) continue;
			if (aura > 0) AuraCurrency.add(p, aura);
			run.earned.merge(p.getUUID(), aura, Integer::sum);
			Mastery.addXp(p, AuraConfig.WAVES_MASTERY_XP);
			p.awardStat(AuraStats.WAVES_CLEARED);
			var best = Stats.CUSTOM.get(AuraStats.BEST_WAVE);
			if (p.getStats().getValue(best) < run.wave) p.getStats().setValue(p, best, run.wave);
			com.toast.aurapoints.cosmetic.Weekly.onWaveCleared(p, run.wave);
			ArenaFx.title(p, Component.literal("Wave " + run.wave + " cleared").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
					Component.literal((aura > 0 ? "+" + aura + " Aura · " : "") + "next wave in " + breakSeconds(run) + "s").withStyle(ChatFormatting.GRAY));
			AuraFx.sound(p.serverLevel(), p, SoundEvents.PLAYER_LEVELUP, 0.7f, 1.2f);
		}
		// what came along (vexes, riders) goes with the wave
		for (Mob m : run.extras) m.discard();
		run.extras.clear();
	}

	/** Everything this run put in the arena goes; the boss bar too. */
	static void cleanup(ServerLevel arena, Run run) {
		if (run != null) {
			for (Mob m : run.alive) m.discard();
			for (Mob m : run.extras) m.discard();
			if (run.boss != null) run.boss.discard();
			if (run.bar != null) run.bar.removeAllPlayers();
			run.alive.clear();
			run.extras.clear();
			run.queue.clear();
			run.boss = null;
			run.bar = null;
		}
		if (active == run) active = null;
		if (arena != null) {
			ArenaBuilder.setGates(arena, true);
			AABB ring = new AABB(-Arena.RING_R - 4, Arena.RING_FLOOR - 4, Arena.RING_Z - Arena.RING_R - 4,
					Arena.RING_R + 4, Arena.FLOOR + 30, Arena.RING_Z + Arena.RING_R + 4);
			for (Mob m : arena.getEntitiesOfClass(Mob.class, ring, Waves::isWaveMob)) m.discard();
		}
	}

	// ---------------------------------------------------------------
	// Mobs
	// ---------------------------------------------------------------

	/** The angle of gate 0-3 round the ring's middle: the diagonals (NW, NE, SE, SW), clear of the pads. */
	static double gateAngle(int i) {
		return -3 * Math.PI / 4 + i * Math.PI / 2;
	}

	/** Gate 0-3 on the ring floor just inside its barred tunnel in the pit wall (ArenaBuilder.waveGate). */
	static Vec3 gate(int i) {
		double r = Arena.RING_R - 2, a = gateAngle(i);
		return new Vec3(0.5 + Math.cos(a) * r, Arena.RING_FLOOR + 1, Arena.RING_Z + 0.5 + Math.sin(a) * r);
	}

	private static Mob spawn(ServerLevel arena, Run run, EntityType<? extends Mob> type, AuraType aura, Vec3 at) {
		Mob m = type.create(arena);
		if (m == null) return null;
		double ox = (arena.getRandom().nextDouble() - 0.5) * 3, oz = (arena.getRandom().nextDouble() - 0.5) * 3;
		m.moveTo(at.x + ox, at.y, at.z + oz, arena.getRandom().nextFloat() * 360f, 0f);
		if (!arena.noCollision(m)) m.moveTo(at.x, at.y, at.z, m.getYRot(), 0f);
		m.addTag(TAG);
		spawning = true;
		try {
			net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(m, arena, arena.getCurrentDifficultyAt(BlockPos.containing(at)), MobSpawnType.EVENT, null);
			if (m instanceof Zombie z) {
				z.setBaby(false);
				var reinforce = z.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
				if (reinforce != null) reinforce.setBaseValue(0);
			}
			m.setPersistenceRequired();
			double scale = scaling(run.wave);
			if (scale > 0) {
				var hp = m.getAttribute(Attributes.MAX_HEALTH);
				if (hp != null) hp.addPermanentModifier(new AttributeModifier(SCALE_ID, scale, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
				var dmg = m.getAttribute(Attributes.ATTACK_DAMAGE);
				if (dmg != null) dmg.addPermanentModifier(new AttributeModifier(SCALE_ID, scale, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
				m.setHealth(m.getMaxHealth());
			}
			if (aura != null) TouchedMobs.touch(m, aura);
			cap(m, aura == null ? 0xFFC83D : aura.rgb);
			arena.addFreshEntityWithPassengers(m);
		} finally {
			spawning = false;
		}
		AuraFx.burst(arena, m.getX(), m.getY() + 1, m.getZ(), ParticleTypes.PORTAL, 20, 0.4, 0.3);
		return m;
	}

	/** The champion's health: WAVES_BOSS_HEALTH_BASE + PER_WAVE x wave, more per extra player. */
	static double bossHealth(int wave, int players) {
		double hp = Math.max(1, AuraConfig.WAVES_BOSS_HEALTH_BASE + AuraConfig.WAVES_BOSS_HEALTH_PER_WAVE * (double) wave);
		return hp * (1.0 + Math.max(0, players - 1) * Math.max(0.0, AuraConfig.WAVES_EXTRA_PLAYER_BONUS));
	}

	/** The champion: big, aura-touched, a set amount of health, a boss bar for the arena. */
	private static void spawnBoss(ServerLevel arena, Run run, int players) {
		AuraType[] auras = AuraType.values();
		AuraType aura = auras[new Random(run.seed + run.wave * 7L).nextInt(auras.length)];
		Mob boss = spawn(arena, run, bossType(run.wave), aura, gate(0));
		if (boss == null) return;
		var hp = boss.getAttribute(Attributes.MAX_HEALTH);
		if (hp != null) {
			// whatever the mob, the touch and the wave scaling made it: brought to the target exactly
			double target = bossHealth(run.wave, players), now = hp.getValue();
			if (now > 0) hp.addPermanentModifier(new AttributeModifier(BOSS_ID, target / now - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			boss.setHealth(boss.getMaxHealth());
		}
		var size = boss.getAttribute(Attributes.SCALE);
		if (size != null) size.addPermanentModifier(new AttributeModifier(BOSS_SIZE_ID, 0.4, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
		Component name = Component.literal("★ " + aura.displayName + "-touched ").append(boss.getType().getDescription()).append(" Champion ★").withStyle(aura.color, ChatFormatting.BOLD);
		boss.setCustomName(name);
		run.boss = boss;
		run.bar = new ServerBossEvent(name, BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
		for (ServerPlayer p : arena.players()) run.bar.addPlayer(p);
		ArenaFx.lightning(arena, boss.position());
	}

	/** A dyed leather cap that never drops or breaks: undead don't burn under the arena's noon sun. */
	private static void cap(Mob m, int rgb) {
		if (!(m instanceof Zombie || m instanceof AbstractSkeleton)) return;
		ItemStack cap = new ItemStack(Items.LEATHER_HELMET);
		cap.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb, false));
		cap.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
		m.setItemSlot(EquipmentSlot.HEAD, cap);
		m.setDropChance(EquipmentSlot.HEAD, 0f);
	}

	// ---------------------------------------------------------------
	// Entity hooks (from AuraPoints' events)
	// ---------------------------------------------------------------

	public static boolean isWaveMob(Entity e) {
		return e.getTags().contains(TAG);
	}

	/**
	 * EntityJoinLevelEvent in the arena. True = cancel it: a wave mob loaded from disk (runs are never saved).
	 * Riders that come with a spawn, and vexes an evoker calls in mid-run, become extras of the wave.
	 */
	public static boolean onJoin(Entity e, boolean loadedFromDisk) {
		if (!(e instanceof Mob m)) return false;
		if (loadedFromDisk) return isWaveMob(m);
		Run run = active;
		if (run == null || isWaveMob(m) && !spawning) return false;
		if (spawning || m instanceof Vex) {
			if (!isWaveMob(m)) {
				m.addTag(TAG);
				m.setPersistenceRequired();
				run.extras.add(m);
			}
		}
		return false;
	}

	/** Shorter cooldowns for players fighting the waves (WAVES_COOLDOWN_MULTIPLIER). */
	public static int cooldown(ServerPlayer player, int ticks) {
		if (ticks <= 0 || !Match.inWaves(player)) return ticks;
		double mult = Math.max(0.0, AuraConfig.WAVES_COOLDOWN_MULTIPLIER);
		return mult == 1.0 ? ticks : (int) Math.round(ticks * mult);
	}

	/** Records the run's score for each player: best wave (and the party it was with). Returns true if any record broke. */
	static boolean record(MinecraftServer server, int cleared, Map<UUID, String> players) {
		if (cleared <= 0) return false;
		AuraState state = AuraState.get(server);
		String party = String.join(", ", players.values());
		boolean any = false;
		for (var e : players.entrySet()) {
			AuraState.ArenaRecord r = state.waveRecords.computeIfAbsent(e.getKey(), id -> new AuraState.ArenaRecord(e.getValue()));
			r.name = e.getValue();
			r.wins++; // runs played
			if (cleared > r.best) {
				r.best = cleared;
				r.party = party;
				any = true;
			}
		}
		state.markDirty();
		return any;
	}

	/** Dev tests: the rest of this wave dies at once (queue emptied, the boss too). */
	static void devClear(Run run) {
		run.queue.clear();
		run.queueAura.clear();
		for (Mob m : new ArrayList<>(run.alive)) m.kill();
		if (run.boss != null) run.boss.kill();
	}

	/** Dev tests: wave mobs left anywhere in the ring. */
	static int devCountInRing(ServerLevel arena) {
		AABB ring = new AABB(-Arena.RING_R - 4, Arena.RING_FLOOR - 4, Arena.RING_Z - Arena.RING_R - 4,
				Arena.RING_R + 4, Arena.FLOOR + 30, Arena.RING_Z + Arena.RING_R + 4);
		return arena.getEntitiesOfClass(Mob.class, ring, Waves::isWaveMob).size();
	}

	/** Dev tests: tagged mobs in the ring this run doesn't know about, by type ("{zombie=2}"). */
	static String devUntracked(ServerLevel arena, Run run) {
		AABB ring = new AABB(-Arena.RING_R - 4, Arena.RING_FLOOR - 4, Arena.RING_Z - Arena.RING_R - 4,
				Arena.RING_R + 4, Arena.FLOOR + 30, Arena.RING_Z + Arena.RING_R + 4);
		Map<String, Integer> out = new java.util.TreeMap<>();
		for (Mob m : arena.getEntitiesOfClass(Mob.class, ring, Waves::isWaveMob)) {
			if (run.alive.contains(m) || run.extras.contains(m) || m == run.boss) continue;
			out.merge(EntityType.getKey(m.getType()).getPath() + (m.isAlive() ? "" : "(dead)") + (m.isRemoved() ? "(removed)" : ""), 1, Integer::sum);
		}
		return out.toString();
	}

	static void clearAll() {
		active = null;
		spawning = false;
	}
}
