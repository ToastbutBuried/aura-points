package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.toast.aurapoints.arena.Arena.FLOOR;

/**
 * The Training Hall's controls - the Control Gallery inside the entrance: six kiosks, each a pedestal
 * with a spinning item under a hanging sign; clicking the sign (or the pedestal) does its thing, and
 * the signs show what's set right now - and the 30-second DPS challenge with its record board.
 */
public final class TrainingHall {

	private TrainingHall() {
	}

	/** The kiosks, west to east: things for you, then things for the dummies. */
	enum Control {
		CHALLENGE(-15, Items.TARGET),
		COOLDOWNS(-9, Items.CLOCK),
		HEAL(-3, Items.GOLDEN_APPLE),
		RESET(3, Items.HAY_BLOCK),
		ARMOR(9, Items.IRON_CHESTPLATE),
		PATROL(15, Items.MINECART);

		final int x;
		final Item item;

		Control(int x, Item item) {
			this.x = x;
			this.item = item;
		}

		/** The hanging sign, under the gallery's beam. */
		BlockPos sign() {
			return new BlockPos(x, FLOOR + 4, KIOSK_Z);
		}

		BlockPos pedestal() {
			return new BlockPos(x, FLOOR + 1, KIOSK_Z);
		}

		/** Where the item floats and spins (ArenaFx animates it). */
		Vec3 itemPos() {
			return new Vec3(x + 0.5, FLOOR + 2.45, KIOSK_Z + 0.5);
		}
	}

	/** The Control Gallery's row of kiosks. */
	static final int KIOSK_Z = Arena.TRAIN_Z0 + 8;
	/** The DPS record board: two wall signs on the hall's west wall by the door, facing in. */
	static final BlockPos BOARD_1 = new BlockPos(-Arena.TRAIN_X + 1, FLOOR + 3, Arena.TRAIN_Z0 + 3),
			BOARD_2 = new BlockPos(-Arena.TRAIN_X + 1, FLOOR + 3, Arena.TRAIN_Z0 + 4);

	private static final int CHALLENGE_COUNTDOWN = 60, CHALLENGE_TICKS = 30 * 20;

	/** A running DPS challenge: when the 30 s start (after the countdown), and the damage so far. */
	private static final class Challenge {
		final long start;
		float damage;

		Challenge(long start) {
			this.start = start;
		}
	}

	private static final Map<UUID, Challenge> CHALLENGES = new HashMap<>();

	/** player -> game time of their last ability with free cooldowns (it may still be out there dealing damage) */
	private static final Map<UUID, Long> LAST_FREE_USE = new HashMap<>();

	/** From AuraHooks.abilityUsed: an ability went off without a cooldown. */
	public static void markFreeUse(ServerPlayer player) {
		LAST_FREE_USE.put(player.getUUID(), player.serverLevel().getGameTime());
	}

	// ---------------------------------------------------------------
	// Buttons
	// ---------------------------------------------------------------

	/** A right click on a block in the arena: true if it was one of the kiosks (and it did its thing). */
	public static boolean press(ServerPlayer player, BlockPos pos) {
		if (!Arena.inTraining(player)) return false;
		for (Control control : Control.values()) {
			if (!control.sign().equals(pos) && !control.pedestal().equals(pos)) continue;
			ArenaFx.click(player.serverLevel(), control.itemPos());
			switch (control) {
				case COOLDOWNS -> {
					var state = AuraState.get(player.getServer());
					Arena.setTrainingCooldowns(player, !state.getOrCreate(player.getUUID()).trainingCooldowns);
				}
				case HEAL -> {
					player.setHealth(player.getMaxHealth());
					player.getFoodData().setFoodLevel(20);
					player.getFoodData().setSaturation(5f);
					player.removeAllEffects();
					player.clearFire();
					player.displayClientMessage(Component.literal("Healed up").withStyle(ChatFormatting.GREEN), true);
					AuraFx.sound(player.serverLevel(), player, SoundEvents.PLAYER_LEVELUP, 0.5f, 1.6f);
				}
				case RESET -> {
					for (TrainingDummy d : dummies(player.serverLevel())) d.resetNow();
					toHall(player.serverLevel(), Component.literal(player.getGameProfile().getName() + " reset the dummies").withStyle(ChatFormatting.GRAY));
				}
				case ARMOR -> {
					List<TrainingDummy> all = dummies(player.serverLevel());
					int tier = all.isEmpty() ? 0 : (armorTier(all.get(0)) + 1) % ARMOR.length;
					for (TrainingDummy d : all) setArmor(d, tier);
					toHall(player.serverLevel(), Component.literal("Dummy armor: " + ARMOR_NAMES[tier]).withStyle(ChatFormatting.AQUA));
					for (TrainingDummy d : all) {
						AuraFx.particles(player.serverLevel(), net.minecraft.core.particles.ParticleTypes.WAX_ON, d.getX(), d.getY() + 1, d.getZ(), 12, 0.4, 0.8, 0.4, 0);
					}
					ArenaFx.sound(player.serverLevel(), control.itemPos(), SoundEvents.ARMOR_EQUIP_IRON.value(), 1f, 1f);
					writeKiosks(player.serverLevel(), tier, -1);
				}
				case PATROL -> {
					int speed = -1;
					for (TrainingDummy d : dummies(player.serverLevel())) {
						if (!d.patrols()) continue;
						if (speed < 0) speed = (d.patrolSpeed() + 1) % 3;
						d.setPatrolSpeed(speed);
					}
					if (speed >= 0) {
						toHall(player.serverLevel(), Component.literal("Moving dummy: " + SPEEDS[speed]).withStyle(ChatFormatting.AQUA));
						writeKiosks(player.serverLevel(), -1, speed);
					}
				}
				case CHALLENGE -> startChallenge(player);
			}
			return true;
		}
		return false;
	}

	private static final String[] SPEEDS = {"off", "slow", "fast"};

	/** The kiosks' signs, with what's set now ({@code armor} / {@code speed} -1 = look it up). */
	static void writeKiosks(ServerLevel level, int armor, int speed) {
		List<TrainingDummy> all = dummies(level);
		if (armor < 0) armor = all.stream().filter(d -> !d.patrols()).findFirst().map(TrainingHall::armorTier).orElse(0);
		if (speed < 0) speed = all.stream().filter(TrainingDummy::patrols).findFirst().map(TrainingDummy::patrolSpeed).orElse(0);
		List<AuraState.ArenaRecord> top = top(AuraState.get(level.getServer()).dpsRecords, r -> r.best);
		String record = top.isEmpty() ? "Record: none yet" : String.format(java.util.Locale.ROOT, "Record: %.1f", top.get(0).best);
		String holder = top.isEmpty() ? "" : "by " + top.get(0).name;
		for (Control c : Control.values()) {
			String[] lines = switch (c) {
				case CHALLENGE -> new String[]{"DPS CHALLENGE", "30 s, cooldowns on", record, holder};
				case COOLDOWNS -> new String[]{"COOLDOWNS", "on / off", "just for you", "(off by default)"};
				case HEAL -> new String[]{"HEAL ME", "full health", "and food,", "no effects"};
				case RESET -> new String[]{"RESET DUMMIES", "heal them up,", "clear effects", ""};
				case ARMOR -> new String[]{"DUMMY ARMOR", "click to cycle", "now: " + ARMOR_NAMES[armor], ""};
				case PATROL -> new String[]{"MOVING DUMMY", "off/slow/fast", "now: " + SPEEDS[speed], ""};
			};
			ArenaBuilder.writeSign(level, c.sign(), lines);
		}
	}

	private static List<TrainingDummy> dummies(ServerLevel level) {
		return level.getEntitiesOfClass(TrainingDummy.class, hall());
	}

	static AABB hall() {
		return new AABB(-Arena.TRAIN_X, FLOOR - 2, Arena.TRAIN_Z0, Arena.TRAIN_X + 1, FLOOR + 15, Arena.TRAIN_Z1 + 1);
	}

	private static void toHall(ServerLevel level, Component message) {
		for (ServerPlayer p : level.players()) if (Arena.inTraining(p)) p.displayClientMessage(message, true);
	}

	// ---------------------------------------------------------------
	// Dummy armor
	// ---------------------------------------------------------------

	private static final String[] ARMOR_NAMES = {"none", "leather", "chainmail", "iron", "diamond", "netherite"};
	private static final Item[][] ARMOR = {
			{},
			{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
			{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
			{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
			{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS},
			{Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS},
	};
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private static int armorTier(TrainingDummy dummy) {
		Item chest = dummy.getItemBySlot(EquipmentSlot.CHEST).getItem();
		for (int t = 1; t < ARMOR.length; t++) if (ARMOR[t][1] == chest) return t;
		return 0;
	}

	private static void setArmor(TrainingDummy dummy, int tier) {
		for (int i = 0; i < SLOTS.length; i++) {
			dummy.setItemSlot(SLOTS[i], tier == 0 ? ItemStack.EMPTY : new ItemStack(ARMOR[tier][i]));
			dummy.setDropChance(SLOTS[i], 0f);
		}
	}

	/** "Iron armor" for the dummy's name line, or null without any. */
	static String armorName(TrainingDummy dummy) {
		int tier = armorTier(dummy);
		return tier == 0 ? null : Character.toUpperCase(ARMOR_NAMES[tier].charAt(0)) + ARMOR_NAMES[tier].substring(1) + " armor";
	}

	// ---------------------------------------------------------------
	// DPS challenge: 3-2-1, then 30 seconds on the dummies with cooldowns on
	// ---------------------------------------------------------------

	private static void startChallenge(ServerPlayer player) {
		if (CHALLENGES.containsKey(player.getUUID())) {
			player.displayClientMessage(Component.literal("Your DPS challenge is already running!").withStyle(ChatFormatting.RED), true);
			return;
		}
		if (AuraState.get(player.getServer()).cooldownsDisabled) {
			player.sendSystemMessage(Component.literal("No DPS challenge while cooldowns are off for everyone (/aura cooldowns).").withStyle(ChatFormatting.RED));
			return;
		}
		long now = player.serverLevel().getGameTime();
		long clean = AuraConfig.TRAINING_CHALLENGE_CLEAN_SECONDS * 20L - (now - LAST_FREE_USE.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2));
		if (clean > 0) {
			player.sendSystemMessage(Component.literal("You just used abilities with your cooldowns off - wait " + (clean + 19) / 20
					+ " s so nothing left over counts, then start the challenge.").withStyle(ChatFormatting.RED));
			return;
		}
		CHALLENGES.put(player.getUUID(), new Challenge(now + CHALLENGE_COUNTDOWN));
		// a fair start: cooldowns on (see Arena.freeCooldowns) and all ready
		Arena.resetCooldowns(player);
		player.sendSystemMessage(Component.literal("DPS challenge: hit the dummies as hard as you can for 30 seconds! Cooldowns are on for it.").withStyle(ChatFormatting.GOLD));
	}

	/** Cooldowns stay on for a player doing the challenge, whatever their switch says. */
	static boolean inChallenge(ServerPlayer player) {
		return CHALLENGES.containsKey(player.getUUID());
	}

	/** From TrainingDummy: a hit counts for its attacker's challenge (once it has started). */
	static void onDummyHit(DamageSource source, float amount, long now) {
		if (!(source.getEntity() instanceof ServerPlayer attacker)) return;
		Challenge c = CHALLENGES.get(attacker.getUUID());
		if (c != null && now >= c.start && now < c.start + CHALLENGE_TICKS) c.damage += amount;
	}

	static void tick(MinecraftServer server) {
		for (Iterator<Map.Entry<UUID, Challenge>> it = CHALLENGES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Challenge> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Challenge c = e.getValue();
			if (p == null || !Arena.inTraining(p)) {
				if (p != null) p.sendSystemMessage(Component.literal("DPS challenge cancelled (you left the hall).").withStyle(ChatFormatting.GRAY));
				it.remove();
				continue;
			}
			if (AuraState.get(server).cooldownsDisabled) {
				p.sendSystemMessage(Component.literal("DPS challenge cancelled (cooldowns were switched off for everyone).").withStyle(ChatFormatting.RED));
				it.remove();
				continue;
			}
			long now = p.serverLevel().getGameTime();
			long toStart = c.start - now;
			if (toStart > 0) {
				if (toStart % 20 == 0) {
					ArenaFx.countdown(p, Component.literal(String.valueOf(toStart / 20)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
					AuraFx.sound(p.serverLevel(), p, SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 1f);
				}
				continue;
			}
			if (toStart == 0) {
				ArenaFx.title(p, Component.literal("GO!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal("30 seconds").withStyle(ChatFormatting.GRAY));
				AuraFx.sound(p.serverLevel(), p, SoundEvents.NOTE_BLOCK_BELL.value(), 1f, 1.4f);
			}
			long left = c.start + CHALLENGE_TICKS - now;
			if (left > 0) {
				if (left % 5 == 0) {
					p.displayClientMessage(Component.literal(String.format(java.util.Locale.ROOT, "DPS challenge: %d s left · %.0f damage", (left + 19) / 20, c.damage))
							.withStyle(ChatFormatting.GOLD), true);
				}
				continue;
			}
			it.remove();
			finishChallenge(p, c.damage);
		}
	}

	private static void finishChallenge(ServerPlayer player, float damage) {
		double dps = damage / (CHALLENGE_TICKS / 20.0);
		AuraState state = AuraState.get(player.getServer());
		AuraState.ArenaRecord record = state.dpsRecords.computeIfAbsent(player.getUUID(), id -> new AuraState.ArenaRecord(player.getGameProfile().getName()));
		record.name = player.getGameProfile().getName();
		boolean best = dps > record.best;
		com.toast.aurapoints.cosmetic.Weekly.onDps(player, dps);
		// for quests (Aura Mastery IV asks for a DPS best), in tenths like the vanilla damage stats
		var stat = net.minecraft.stats.Stats.CUSTOM.get(com.toast.aurapoints.ability.AuraStats.DPS_BEST);
		player.getStats().setValue(player, stat, Math.max(player.getStats().getValue(stat), (int) Math.round(dps * 10)));
		boolean top = best && top(state.dpsRecords, r -> r.best).stream().noneMatch(r -> r.best >= dps);
		if (best) {
			record.best = dps;
			state.markDirty();
		}
		ArenaFx.title(player, Component.literal(String.format(java.util.Locale.ROOT, "%.1f DPS", dps)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
				Component.literal(String.format(java.util.Locale.ROOT, "%.0f damage in 30 s", damage)
						+ (top ? " · NEW RECORD!" : best ? " · personal best!" : "")).withStyle(ChatFormatting.YELLOW));
		AuraFx.sound(player.serverLevel(), player, best ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.NOTE_BLOCK_CHIME.value(), 0.8f, 1f);
		if (top) {
			for (ServerPlayer p : player.serverLevel().players()) {
				p.sendSystemMessage(Component.literal(String.format(java.util.Locale.ROOT, "★ %s set a new DPS record: %.1f!", player.getGameProfile().getName(), dps)).withStyle(ChatFormatting.GOLD));
			}
			// fireworks off the challenge kiosk
			Vec3 at = Control.CHALLENGE.itemPos();
			for (int k = 0; k < 3; k++) {
				final int n = k;
				AuraFx.later(player.getServer(), k * 6, () -> ArenaFx.firework(player.serverLevel(), at.add(n - 1, 0.5, 0), 0xFFC83D, 0xFF4A12));
			}
		}
		if (best) {
			writeBoards(player.serverLevel());
			writeKiosks(player.serverLevel(), -1, -1);
		}
	}

	// ---------------------------------------------------------------
	// Sign boards: DPS records here, top duelists in the lobby
	// ---------------------------------------------------------------

	static List<AuraState.ArenaRecord> top(Map<UUID, AuraState.ArenaRecord> records, java.util.function.ToDoubleFunction<AuraState.ArenaRecord> by) {
		List<AuraState.ArenaRecord> list = new ArrayList<>(records.values());
		list.removeIf(r -> by.applyAsDouble(r) <= 0);
		list.sort((a, b) -> Double.compare(by.applyAsDouble(b), by.applyAsDouble(a)));
		return list;
	}

	private static String entry(int rank, List<AuraState.ArenaRecord> list, java.util.function.Function<AuraState.ArenaRecord, String> value) {
		if (rank > list.size()) return rank + ". -";
		AuraState.ArenaRecord r = list.get(rank - 1);
		String name = r.name.length() > 9 ? r.name.substring(0, 9) : r.name;
		return rank + ". " + name + " " + value.apply(r);
	}

	/** Both boards, as the records stand now. */
	public static void writeBoards(ServerLevel level) {
		AuraState state = AuraState.get(level.getServer());
		List<AuraState.ArenaRecord> dps = top(state.dpsRecords, r -> r.best);
		java.util.function.Function<AuraState.ArenaRecord, String> dpsValue = r -> String.format(java.util.Locale.ROOT, "%.1f", r.best);
		ArenaBuilder.writeSign(level, BOARD_1, "★ DPS RECORDS ★", "30 s challenge", entry(1, dps, dpsValue), entry(2, dps, dpsValue));
		ArenaBuilder.writeSign(level, BOARD_2, entry(3, dps, dpsValue), entry(4, dps, dpsValue), entry(5, dps, dpsValue), entry(6, dps, dpsValue));

		// the lobby's leaderboards are holograms
		Holograms.updateBoards(level);
	}

	/** A duel ended: the winner's and loser's lines, and the lobby board. */
	static void recordDuel(MinecraftServer server, UUID winner, String winnerName, UUID loser, String loserName) {
		AuraState state = AuraState.get(server);
		AuraState.ArenaRecord w = state.duelRecords.computeIfAbsent(winner, id -> new AuraState.ArenaRecord(winnerName));
		AuraState.ArenaRecord l = state.duelRecords.computeIfAbsent(loser, id -> new AuraState.ArenaRecord(loserName));
		w.name = winnerName;
		l.name = loserName;
		w.wins++;
		l.losses++;
		state.markDirty();
		ServerLevel arena = Arena.level(server);
		if (arena != null) writeBoards(arena);
	}

	static void clearAll() {
		CHALLENGES.clear();
		LAST_FREE_USE.clear();
	}
}
