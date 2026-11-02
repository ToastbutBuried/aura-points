package com.toast.aurapoints.cosmetic;

import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraStats;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Reactions;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Weekly challenges: three a week, the same for everyone, new every Monday (server time). Picked from
 * the week's date (plus WEEKLY_SEED), one per slot: an arena goal (DPS test or duels), a hunt and a
 * stat goal. Counted goals are fed by hooks (TrainingHall, Duel, TouchedMobs); stat goals count what a
 * player's stats grew by since they were first seen that week. Each one pays once; all three pay a bonus.
 */
public final class Weekly {

	private Weekly() {
	}

	enum Type {
		DPS("Reach %d DPS in the Training Hall's 30 s challenge", new int[]{8, 12, 16}),
		DUEL_WINS("Win %d duel%s in the Aura Arena", new int[]{1, 2, 3}),
		DUEL_ROUNDS("Win %d duel rounds", new int[]{3, 5, 8}),
		TOUCHED("Slay %d aura-touched mobs", new int[]{3, 5, 8}),
		TOUCHED_AURA("Slay %d %s-touched mob%s", new int[]{1, 2, 3}),
		MONSTERS("Defeat %d monsters", new int[]{60, 120, 200}),
		REACTIONS("Set off %d elemental reactions", new int[]{15, 30, 50}),
		REACTION_ONE("Set off %d %s reactions", new int[]{3, 6, 10}),
		MASTERY("Earn %d Aura Mastery XP", new int[]{200, 400, 700}),
		WAVES("Clear wave %d in Wave Survival", new int[]{10, 15, 20}),
		COMBOS("Pull off %d team combos with an ally", new int[]{3, 6, 10});

		final String text;
		final int[] targets;

		Type(String text, int[] targets) {
			this.text = text;
			this.targets = targets;
		}

		/** Counted by stat growth (not by hooks). */
		boolean byStats() {
			return this == MONSTERS || this == REACTIONS || this == REACTION_ONE || this == MASTERY || this == COMBOS;
		}
	}

	/** One of the week's three: what, how much, and the aura or reaction it names (or null). */
	record Challenge(Type type, int tier, AuraType aura, Reactions.Reaction reaction) {

		int target() {
			return type.targets[tier];
		}

		int reward() {
			return Math.max(0, AuraConfig.WEEKLY_REWARD_BASE + tier * AuraConfig.WEEKLY_REWARD_PER_TIER);
		}

		String text() {
			int n = target();
			return switch (type) {
				case DUEL_WINS -> String.format(type.text, n, n == 1 ? "" : "s");
				case TOUCHED_AURA -> String.format(type.text, n, aura.displayName, n == 1 ? "" : "s");
				case REACTION_ONE -> String.format(type.text, n, reaction.displayName);
				default -> String.format(type.text, n);
			};
		}
	}

	private static long cachedWeek = Long.MIN_VALUE;
	private static int cachedSeed;
	private static List<Challenge> cached = List.of();

	/** This week's key: the epoch day of its Monday. */
	public static long week() {
		return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
	}

	/** Monday 2026-11-02: the first week that can roll a Wave Survival challenge (4.94.0). */
	private static final long WAVES_WEEK = LocalDate.of(2026, 11, 2).toEpochDay();

	/** Monday 2026-11-02: the first week that can roll a team-combo challenge (4.95.0). */
	private static final long COMBOS_WEEK = LocalDate.of(2026, 11, 9).toEpochDay();

	/** This week's three challenges. */
	static List<Challenge> challenges() {
		long week = week();
		if (week == cachedWeek && cachedSeed == AuraConfig.WEEKLY_SEED) return cached;
		Random r = new Random(week * 7919L + AuraConfig.WEEKLY_SEED);
		List<Challenge> out = new ArrayList<>();
		// arena: the DPS test, duels or (from the week Wave Survival came out, so the week before keeps its rolls) waves
		double a = r.nextDouble();
		Type arena = week < WAVES_WEEK ? (a < 0.5 ? Type.DPS : a < 0.75 ? Type.DUEL_WINS : Type.DUEL_ROUNDS)
				: a < 0.4 ? Type.DPS : a < 0.6 ? Type.DUEL_WINS : a < 0.8 ? Type.DUEL_ROUNDS : Type.WAVES;
		out.add(new Challenge(arena, r.nextInt(3), null, null));
		Type[] hunts = {Type.TOUCHED, Type.TOUCHED_AURA, Type.MONSTERS};
		out.add(new Challenge(hunts[r.nextInt(hunts.length)], r.nextInt(3), AuraType.values()[r.nextInt(AuraType.values().length)], null));
		Type[] stats = week < COMBOS_WEEK ? new Type[]{Type.REACTIONS, Type.REACTION_ONE, Type.MASTERY}
				: new Type[]{Type.REACTIONS, Type.REACTION_ONE, Type.MASTERY, Type.COMBOS};
		out.add(new Challenge(stats[r.nextInt(stats.length)], r.nextInt(3), null,
				Reactions.Reaction.values()[r.nextInt(Reactions.Reaction.values().length)]));
		cachedWeek = week;
		cachedSeed = AuraConfig.WEEKLY_SEED;
		cached = List.copyOf(out);
		return cached;
	}

	// ---------------------------------------------------------------
	// Progress
	// ---------------------------------------------------------------

	private static AuraPlayerData data(ServerPlayer p) {
		return AuraState.get(p.getServer()).getOrCreate(p.getUUID());
	}

	/** Brings the player's weekly data to this week (a new week starts from zero, with stat baselines taken now). */
	private static AuraPlayerData current(ServerPlayer player) {
		AuraPlayerData data = data(player);
		long week = week();
		if (data.weeklyWeek != week || data.weeklySeed != AuraConfig.WEEKLY_SEED) {
			data.weeklyWeek = week;
			data.weeklySeed = AuraConfig.WEEKLY_SEED;
			data.weeklyDone = 0;
			java.util.Arrays.fill(data.weeklyProgress, 0);
			List<Challenge> list = challenges();
			for (int i = 0; i < list.size(); i++) data.weeklyBase[i] = list.get(i).type.byStats() ? statValue(player, list.get(i)) : 0;
			AuraState.get(player.getServer()).markDirty();
		}
		return data;
	}

	private static int stat(ServerPlayer p, ResourceLocation id) {
		return id == null ? 0 : p.getStats().getValue(Stats.CUSTOM.get(id));
	}

	/** The stat total a stat challenge counts the growth of. */
	private static int statValue(ServerPlayer p, Challenge c) {
		return switch (c.type) {
			case MONSTERS -> p.getStats().getValue(Stats.CUSTOM.get(Stats.MOB_KILLS));
			case REACTIONS -> {
				int n = 0;
				for (ResourceLocation id : AuraStats.reactionStats()) n += stat(p, id);
				yield n;
			}
			case REACTION_ONE -> stat(p, AuraStats.reaction(c.reaction.id()));
			case COMBOS -> {
				int n = 0;
				for (ResourceLocation id : AuraStats.comboStats()) n += stat(p, id);
				yield n;
			}
			case MASTERY -> {
				int n = 0;
				for (AuraType t : AuraType.values()) n += stat(p, AuraStats.mastery(t));
				yield n;
			}
			default -> 0;
		};
	}

	private static int progress(ServerPlayer p, AuraPlayerData data, int i, Challenge c) {
		return c.type.byStats() ? Math.max(0, statValue(p, c) - data.weeklyBase[i]) : data.weeklyProgress[i];
	}

	/** Raises (add) or lifts to at least (max) the counted progress of every challenge of {@code type} this week. */
	private static void count(ServerPlayer player, Type type, int amount, boolean max, AuraType aura) {
		if (!AuraConfig.WEEKLY_ENABLED || player.getServer() == null || amount <= 0) return;
		AuraPlayerData data = current(player);
		List<Challenge> list = challenges();
		for (int i = 0; i < list.size(); i++) {
			Challenge c = list.get(i);
			if (c.type != type || (c.aura != null && type == Type.TOUCHED_AURA && c.aura != aura)) continue;
			data.weeklyProgress[i] = max ? Math.max(data.weeklyProgress[i], amount) : data.weeklyProgress[i] + amount;
			AuraState.get(player.getServer()).markDirty();
		}
		check(player);
	}

	public static void onDps(ServerPlayer player, double dps) {
		count(player, Type.DPS, (int) Math.floor(dps), true, null);
	}

	public static void onDuelWon(ServerPlayer player) {
		count(player, Type.DUEL_WINS, 1, false, null);
	}

	public static void onRoundWon(ServerPlayer player) {
		count(player, Type.DUEL_ROUNDS, 1, false, null);
	}

	/** Wave Survival: a wave cleared (the highest one counts). */
	public static void onWaveCleared(ServerPlayer player, int wave) {
		count(player, Type.WAVES, wave, true, null);
	}

	public static void onTouchedKill(ServerPlayer player, AuraType aura) {
		count(player, Type.TOUCHED, 1, false, null);
		count(player, Type.TOUCHED_AURA, 1, false, aura);
	}

	/** Pays every newly finished challenge (and the all-three bonus). */
	private static void check(ServerPlayer player) {
		AuraPlayerData data = current(player);
		List<Challenge> list = challenges();
		boolean any = false;
		for (int i = 0; i < list.size(); i++) {
			if ((data.weeklyDone & (1 << i)) != 0) continue;
			Challenge c = list.get(i);
			if (progress(player, data, i, c) < c.target()) continue;
			data.weeklyDone |= 1 << i;
			data.weeklyCompleted++;
			any = true;
			if (c.reward() > 0) AuraCurrency.add(player, c.reward());
			player.sendSystemMessage(Component.literal("✔ Weekly challenge done: ").withStyle(ChatFormatting.GREEN)
					.append(Component.literal(c.text()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal(c.reward() > 0 ? "  +" + c.reward() + " Aura" : "").withStyle(ChatFormatting.GOLD)));
			if (data.weeklyDone == (1 << list.size()) - 1) {
				data.weeklyFullWeeks++;
				if (AuraConfig.WEEKLY_ALL_BONUS > 0) AuraCurrency.add(player, AuraConfig.WEEKLY_ALL_BONUS);
				player.sendSystemMessage(Component.literal("★ All three weekly challenges done!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
						.append(Component.literal(AuraConfig.WEEKLY_ALL_BONUS > 0 ? "  +" + AuraConfig.WEEKLY_ALL_BONUS + " Aura bonus" : "").withStyle(ChatFormatting.YELLOW)));
			}
		}
		if (any) {
			AuraState.get(player.getServer()).markDirty();
			AuraFx.sound(player.serverLevel(), player, AuraSounds.UI_STUNT.value(), 1.0f, 1.0f);
			Cosmetics.checkEarned(player);
		}
	}

	/** Checks one player now (dev test; online players are checked every 5 s anyway). */
	public static void refresh(ServerPlayer player) {
		check(player);
		Cosmetics.checkEarned(player);
	}

	/** For the dev test: this week's challenges as text, with their type. */
	public static List<String> describe() {
		List<String> out = new ArrayList<>();
		for (Challenge c : challenges()) out.add(c.type + ": " + c.text() + " (+" + c.reward() + ")");
		return out;
	}

	/** For the dev test: feeds challenge {@code i} of this week until it should be done. */
	public static void devComplete(ServerPlayer p, int i) {
		Challenge c = challenges().get(i);
		int n = c.target();
		switch (c.type) {
			case DPS -> onDps(p, n + 0.5);
			case DUEL_WINS -> { for (int k = 0; k < n; k++) onDuelWon(p); }
			case DUEL_ROUNDS -> { for (int k = 0; k < n; k++) onRoundWon(p); }
			case TOUCHED, TOUCHED_AURA -> { for (int k = 0; k < n; k++) onTouchedKill(p, c.aura); }
			// a FakePlayer's awardStat does nothing: raise the counter itself
			case MONSTERS -> devStat(p, Stats.MOB_KILLS, n);
			case REACTIONS -> devStat(p, AuraStats.reaction("freeze"), n);
			case REACTION_ONE -> devStat(p, AuraStats.reaction(c.reaction.id()), n);
			case MASTERY -> devStat(p, AuraStats.mastery(AuraType.FIRE), n);
			case WAVES -> onWaveCleared(p, n);
			case COMBOS -> devStat(p, AuraStats.combo("glacier"), n);
		}
		check(p);
	}

	private static void devStat(ServerPlayer p, ResourceLocation id, int n) {
		var stat = Stats.CUSTOM.get(id);
		p.getStats().setValue(p, stat, p.getStats().getValue(stat) + n);
	}

	/** Every 5 s for everyone online: stat goals and earned cosmetics. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 100 != 0) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (AuraConfig.WEEKLY_ENABLED) check(p);
			Cosmetics.checkEarned(p);
		}
	}

	// ---------------------------------------------------------------
	// Showing them
	// ---------------------------------------------------------------

	/** "3d 4h" until Monday 00:00. */
	static String timeLeft() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime next = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay();
		long minutes = java.time.Duration.between(now, next).toMinutes();
		long d = minutes / (60 * 24), h = (minutes / 60) % 24, m = minutes % 60;
		return d > 0 ? d + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : m + "m";
	}

	public static void show(ServerPlayer player) {
		if (!AuraConfig.WEEKLY_ENABLED) {
			player.sendSystemMessage(Component.literal("Weekly challenges are switched off on this server.").withStyle(ChatFormatting.GRAY));
			return;
		}
		AuraPlayerData data = current(player);
		List<Challenge> list = challenges();
		player.sendSystemMessage(Component.literal("=== Weekly Challenges ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal("  new ones in " + timeLeft()).withStyle(ChatFormatting.DARK_GRAY)));
		for (int i = 0; i < list.size(); i++) {
			Challenge c = list.get(i);
			boolean done = (data.weeklyDone & (1 << i)) != 0;
			int have = Math.min(progress(player, data, i, c), c.target());
			MutableComponent line = Component.literal(done ? " ✔ " : " • ").withStyle(done ? ChatFormatting.GREEN : ChatFormatting.GRAY)
					.append(Component.literal(c.text()).withStyle(done ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE))
					.append(Component.literal("  " + have + "/" + c.target()).withStyle(done ? ChatFormatting.DARK_GREEN : ChatFormatting.AQUA));
			if (!done && c.reward() > 0) line.append(Component.literal("  +" + c.reward() + " Aura").withStyle(ChatFormatting.GOLD));
			player.sendSystemMessage(line);
		}
		if (AuraConfig.WEEKLY_ALL_BONUS > 0) {
			player.sendSystemMessage(Component.literal("All three: +" + AuraConfig.WEEKLY_ALL_BONUS + " Aura bonus. Finished so far: "
					+ data.weeklyCompleted + " (some cosmetics are earned with these - /aura cosmetics).").withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	/** The board in the Training Hall. */
	public static Component board() {
		MutableComponent text = Component.literal("✦ WEEKLY CHALLENGES ✦").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		if (!AuraConfig.WEEKLY_ENABLED) return text.append(Component.literal("\n\nswitched off").withStyle(ChatFormatting.GRAY));
		text.append(Component.literal("\nnew ones in " + timeLeft()).withStyle(ChatFormatting.GRAY));
		for (Challenge c : challenges()) {
			text.append(Component.literal("\n\n• ").withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(c.text()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal(c.reward() > 0 ? "  +" + c.reward() : "").withStyle(ChatFormatting.GOLD));
		}
		return text.append(Component.literal("\n\n/aura weekly").withStyle(ChatFormatting.AQUA));
	}
}
