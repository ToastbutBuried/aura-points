package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraStats;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Branch;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.ability.Casts;
import com.toast.aurapoints.ability.Chains;
import com.toast.aurapoints.ability.Mastery;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Training Hall's daily challenge cards (5.37.0): three per day for the aura you hold, rolled from
 * your own kit - land a chain a few times, fire a cast variant, reach a combo, or deal damage fast.
 * Counts only in the hall with your cooldowns on and no Aura Trial running. Each card pays once a day,
 * all three pay a bonus. Done cards are saved (trainerDay/trainerAura/trainerDone); progress is not.
 */
public final class TrainerCards {

	private TrainerCards() {
	}

	public enum Kind {CHAIN, CAST, COMBO, DAMAGE}

	/** One card: what to do, how many (or how much damage), within {@link #windowTicks()}. */
	public record Card(Kind kind, Chains.Link link, Ability ability, CastMode mode, int count, String text) {
		public int windowTicks() {
			return (kind == Kind.DAMAGE ? AuraConfig.TRAINING_CARD_DAMAGE_SECONDS : AuraConfig.TRAINING_CARD_WINDOW_SECONDS) * 20;
		}
	}

	/** Not saved: the events inside each card's window (tick, amount), and the best combo today. */
	private static final class Progress {
		final List<Deque<float[]>> events = new ArrayList<>();
		int bestCombo;
		String key;
	}

	private static final Map<UUID, Progress> PROGRESS = new HashMap<>();

	private static long today() {
		return LocalDate.now().toEpochDay();
	}

	/** Brings the saved state to today and this aura (a new day or aura starts with no cards done). */
	private static AuraPlayerData current(ServerPlayer player) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		String aura = data.aura == null ? "" : data.aura.id();
		if (data.trainerDay != today() || !aura.equals(data.trainerAura)) {
			data.trainerDay = today();
			data.trainerAura = aura;
			data.trainerDone = 0;
			AuraState.get(player.getServer()).markDirty();
		}
		return data;
	}

	/** Today's three cards for the player's aura (empty without one). Same roll all day. */
	public static List<Card> cards(ServerPlayer player) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (data.aura == null) return List.of();
		AuraType aura = data.aura;
		Branch branch = Branch.current(data);
		Random r = new Random(today() * 1_000_003L ^ player.getUUID().getLeastSignificantBits() ^ (aura.ordinal() * 7919L) ^ AuraConfig.WEEKLY_SEED);
		List<Card> pool = new ArrayList<>();
		// a chain of your aura (Mastery for chains)
		if (AuraConfig.CHAINS_ENABLED && Mastery.level(player) >= AuraConfig.CHAINS_MASTERY) {
			List<Chains.Link> links = Chains.links(data);
			if (!links.isEmpty()) {
				Chains.Link l = links.get(r.nextInt(links.size()));
				int n = 2 + r.nextInt(2);
				pool.add(new Card(Kind.CHAIN, l, null, null, n, "Land the chain " + l.name() + " " + n + "x within " + AuraConfig.TRAINING_CARD_WINDOW_SECONDS + " s"));
			}
		}
		// a sneak or charged cast you have unlocked
		List<Card> casts = new ArrayList<>();
		for (Ability a : aura.keySlots(branch)) {
			for (CastMode m : new CastMode[]{CastMode.SNEAK, CastMode.CHARGED}) {
				String name = Casts.variantName(a, aura, m);
				if (name == null || !CastMode.unlocked(player, a, m)) continue;
				int n = 3 + r.nextInt(2);
				casts.add(new Card(Kind.CAST, null, a, m, n, "Fire " + name + " (" + (m == CastMode.SNEAK ? "sneak" : "hold") + " " + a.displayName + ") "
						+ n + "x within " + AuraConfig.TRAINING_CARD_WINDOW_SECONDS + " s"));
			}
		}
		if (!casts.isEmpty()) pool.add(casts.get(r.nextInt(casts.size())));
		// a combo needs combo steps: chains, reactions or cast variants
		if (!pool.isEmpty()) {
			int n = 3 + r.nextInt(2);
			pool.add(new Card(Kind.COMBO, null, null, null, n, "Reach a x" + n + " combo (chains, reactions and casts, under "
					+ com.toast.aurapoints.ability.AuraAbilities.fmt(AuraConfig.TRAINING_COMBO_GAP_TICKS / 20f) + " s apart)"));
		}
		Collections.shuffle(pool, r);
		List<Card> out = new ArrayList<>(pool.subList(0, Math.min(2, pool.size())));
		// damage cards fill the rest: everyone can do these
		int t = r.nextInt(3);
		while (out.size() < 3) {
			int dmg = Math.max(1, AuraConfig.TRAINING_CARD_DAMAGE_MIN + (t++ % 3) * AuraConfig.TRAINING_CARD_DAMAGE_STEP);
			out.add(new Card(Kind.DAMAGE, null, null, null, dmg, "Deal " + dmg + " damage to the dummies within " + AuraConfig.TRAINING_CARD_DAMAGE_SECONDS + " s"));
		}
		return out;
	}

	/** Cards count only in the hall, with real cooldowns, outside a trial. */
	private static boolean counts(ServerPlayer player) {
		if (!AuraConfig.TRAINING_CARDS_ENABLED || player.getServer() == null || !Arena.inTraining(player)) return false;
		if (AuraState.get(player.getServer()).cooldownsDisabled || Arena.freeCooldowns(player) || AuraTrial.running(player)) return false;
		return AuraState.get(player.getServer()).getOrCreate(player.getUUID()).aura != null;
	}

	private static Progress progress(ServerPlayer player, AuraPlayerData data) {
		String key = data.trainerDay + ":" + data.trainerAura;
		Progress p = PROGRESS.get(player.getUUID());
		if (p == null || !key.equals(p.key)) {
			p = new Progress();
			p.key = key;
			for (int i = 0; i < 3; i++) p.events.add(new ArrayDeque<>());
			PROGRESS.put(player.getUUID(), p);
		}
		return p;
	}

	// ---------------------------------------------------------------
	// Events (from ComboTrainer)
	// ---------------------------------------------------------------

	static void onChain(ServerPlayer player, Chains.Link link) {
		push(player, c -> c.kind == Kind.CHAIN && c.link.from() == link.from() && c.link.to() == link.to() && c.link.name().equals(link.name()), 1);
	}

	static void onCast(ServerPlayer player, Ability ability, CastMode mode) {
		push(player, c -> c.kind == Kind.CAST && c.ability == ability && c.mode == mode, 1);
	}

	public static void onDamage(ServerPlayer player, float amount) {
		push(player, c -> c.kind == Kind.DAMAGE, amount);
	}

	static void onCombo(ServerPlayer player, int combo) {
		if (!counts(player)) return;
		AuraPlayerData data = current(player);
		Progress p = progress(player, data);
		p.bestCombo = Math.max(p.bestCombo, combo);
		List<Card> cards = cards(player);
		for (int i = 0; i < cards.size(); i++) {
			if (cards.get(i).kind == Kind.COMBO && (data.trainerDone & 1 << i) == 0 && combo >= cards.get(i).count) complete(player, data, cards, i);
		}
	}

	private static void push(ServerPlayer player, java.util.function.Predicate<Card> match, float amount) {
		if (!counts(player)) return;
		AuraPlayerData data = current(player);
		Progress p = progress(player, data);
		long now = player.serverLevel().getGameTime();
		List<Card> cards = cards(player);
		for (int i = 0; i < cards.size(); i++) {
			Card c = cards.get(i);
			if ((data.trainerDone & 1 << i) != 0 || !match.test(c)) continue;
			Deque<float[]> q = p.events.get(i);
			q.addLast(new float[]{now, amount});
			while (!q.isEmpty() && now - (long) q.peekFirst()[0] > c.windowTicks()) q.removeFirst();
			if (sum(q) >= c.count) complete(player, data, cards, i);
		}
	}

	private static float sum(Deque<float[]> q) {
		float n = 0;
		for (float[] e : q) n += e[1];
		return n;
	}

	private static void complete(ServerPlayer player, AuraPlayerData data, List<Card> cards, int i) {
		data.trainerDone |= 1 << i;
		AuraState.get(player.getServer()).markDirty();
		// the arena pauses AuraStats/rewards, so pay and count directly
		AuraCurrency.add(player, AuraConfig.TRAINING_CARD_AURA);
		Mastery.addXp(player, AuraConfig.TRAINING_CARD_MASTERY_XP);
		player.awardStat(AuraStats.TRAINER_CARDS, 1);
		com.toast.aurapoints.cosmetic.Weekly.onTrainerCard(player);
		player.sendSystemMessage(Component.literal("✔ Card done: ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(cards.get(i).text).withStyle(ChatFormatting.WHITE))
				.append(Component.literal("  +" + AuraConfig.TRAINING_CARD_AURA + " Aura").withStyle(ChatFormatting.LIGHT_PURPLE)));
		com.toast.aurapoints.ability.AuraFx.sound(player.serverLevel(), player, AuraSounds.PLAYER_LEVELUP.value(), 0.6f, 1.5f);
		if (Integer.bitCount(data.trainerDone & 7) >= Math.min(3, cards.size()) && AuraConfig.TRAINING_CARDS_BONUS > 0) {
			AuraCurrency.add(player, AuraConfig.TRAINING_CARDS_BONUS);
			ArenaFx.title(player, Component.literal("All cards done!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal("+" + AuraConfig.TRAINING_CARDS_BONUS + " Aura bonus · new cards tomorrow").withStyle(ChatFormatting.YELLOW));
		}
	}

	// ---------------------------------------------------------------
	// Showing them
	// ---------------------------------------------------------------

	public static boolean done(ServerPlayer player, int i) {
		return (current(player).trainerDone & 1 << i) != 0;
	}

	/** "2/3", "84/150" or "best x2/4" for card {@code i}. */
	public static String progressText(ServerPlayer player, int i) {
		List<Card> cards = cards(player);
		if (i >= cards.size()) return "";
		Card c = cards.get(i);
		if (done(player, i)) return "done";
		Progress p = progress(player, current(player));
		if (c.kind == Kind.COMBO) return "best x" + p.bestCombo + " / x" + c.count;
		Deque<float[]> q = p.events.get(i);
		long now = player.serverLevel().getGameTime();
		while (!q.isEmpty() && now - (long) q.peekFirst()[0] > c.windowTicks()) q.removeFirst();
		return Math.round(sum(q)) + " / " + c.count + " right now";
	}

	/** /aura trainer: the cards in chat. */
	public static void show(ServerPlayer player) {
		List<Card> cards = cards(player);
		if (cards.isEmpty()) {
			player.sendSystemMessage(Component.literal("Roll an aura first, then the Training Hall has challenge cards for you.").withStyle(ChatFormatting.RED));
			return;
		}
		player.sendSystemMessage(Component.literal("Training cards today").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal("  (Training Hall, cooldowns on · +" + AuraConfig.TRAINING_CARD_AURA + " Aura each, +"
						+ AuraConfig.TRAINING_CARDS_BONUS + " for all three)").withStyle(ChatFormatting.GRAY)));
		for (int i = 0; i < cards.size(); i++) {
			boolean d = done(player, i);
			player.sendSystemMessage(Component.literal(d ? " ✔ " : " ☐ ").withStyle(d ? ChatFormatting.GREEN : ChatFormatting.GRAY)
					.append(Component.literal(cards.get(i).text).withStyle(d ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE))
					.append(Component.literal(d ? "" : "  " + progressText(player, i)).withStyle(ChatFormatting.DARK_AQUA)));
		}
		if (!counts(player) && Arena.inTraining(player)) {
			player.sendSystemMessage(Component.literal("Cards only count with your cooldowns on (COOLDOWNS kiosk) and no Aura Trial running.").withStyle(ChatFormatting.YELLOW));
		}
	}

	/** Dev test: completes nothing, just forgets the in-memory progress. */
	static void clearAll() {
		PROGRESS.clear();
	}
}
