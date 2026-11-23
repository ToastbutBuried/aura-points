package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Branch;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.ability.Casts;
import com.toast.aurapoints.ability.Chains;
import com.toast.aurapoints.ability.Ultimates;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Aura Trial (5.27.0): a checklist of everything the aura you hold can do - every active ability, every sneak and
 * charged cast, every chain and (once awakened) the Ultimate - tried out in the Training Hall. Offered when you roll or
 * switch to an aura, started from the Aura Book or /aura trial, and open to retake any time.
 *
 * While it runs and you are in the Training Hall, the whole kit works whatever you own or your Mastery is
 * ({@link AuraPlayerData#trialKit}: AuraAbilities.hasAbility, CastMode.unlocked, Chains), and the hall's free cooldowns
 * apply as usual. Leaving the hall pauses it (the kit goes back to normal); it ends on logout, on an aura change, or with
 * /aura trial stop. Progress comes from {@link #onPressed} (AuraAbilities.tryActivate, the press that fired) and
 * {@link #onChain} (Chains, a link going off), in any order. Finishing marks {@link AuraPlayerData#trialDone}.
 */
public final class AuraTrial {

	private AuraTrial() {
	}

	/** One thing to try: an ability's cast (chain == null) or a chain link. */
	record Item(Ability ability, CastMode mode, Chains.Link chain, String label, String how, String what) {
		boolean matches(Ability a, CastMode m) {
			return chain == null && ability == a && mode == m;
		}
	}

	static final class Run {
		final AuraType aura;
		final Branch branch;
		final List<Item> items;
		final boolean[] done;
		int left;
		boolean paused;

		Run(AuraType aura, Branch branch, List<Item> items) {
			this.aura = aura;
			this.branch = branch;
			this.items = items;
			this.done = new boolean[items.size()];
			this.left = items.size();
		}
	}

	private static final Map<UUID, Run> RUNS = new HashMap<>();

	// ---------------------------------------------------------------
	// The checklist
	// ---------------------------------------------------------------

	/** Everything the trial asks for this aura (and branch): taps, casts, the Ultimate if awakened, chains. */
	static List<Item> checklist(AuraPlayerData data) {
		List<Item> out = new ArrayList<>();
		AuraType aura = data.aura;
		Branch branch = Branch.current(data);
		List<Ability> keys = com.toast.aurapoints.ability.Loadouts.keySlots(data);
		for (int i = 0; i < keys.size(); i++) {
			Ability a = keys.get(i);
			String key = "Ability " + (i + 1) + " key";
			out.add(new Item(a, CastMode.TAP, null, a.displayName, "press the " + key + target(a, CastMode.TAP), null));
			if (!AuraConfig.CAST_VARIANTS_ENABLED) continue;
			for (CastMode m : new CastMode[]{CastMode.SNEAK, CastMode.CHARGED}) {
				String name = Casts.variantName(a, aura, m);
				if (name == null) continue;
				out.add(new Item(a, m, null, name + " (" + a.displayName + ")", (m == CastMode.SNEAK ? "sneak + the " + key : "hold the " + key) + target(a, m), null));
			}
		}
		Ability ult = Ultimates.of(aura, branch);
		if (ult != null && data.awakened.contains(aura)) out.add(new Item(ult, CastMode.TAP, null, ult.displayName + " (Ultimate)", "press the Ultimate key", null));
		if (AuraConfig.CHAINS_ENABLED) {
			for (Chains.Link l : Chains.links(data)) {
				out.add(new Item(null, CastMode.TAP, l, "Chain: " + l.name(), cast(l.from(), l.fromMode(), aura) + ", then " + cast(l.to(), l.toMode(), aura)
						+ " within " + AuraAbilities.fmt(windowSeconds(l)) + " s", l.explain()));
			}
		}
		return out;
	}

	/** For the casts that need someone else: what to aim at in the Training Hall (a dummy stands in for an ally during the trial). */
	private static String target(Ability a, CastMode m) {
		if (a == Ability.HEMORRHAGE && m == CastMode.SNEAK) return " near something bleeding (Hemorrhage a dummy first)";
		if (a == Ability.BLOOD_TETHER && m == CastMode.SNEAK || a == Ability.PRISM_BEAM && m == CastMode.SNEAK) return " aimed at a dummy (it stands in for an ally)";
		if (a == Ability.BLOOD_TETHER) return " aimed at a dummy";
		return "";
	}

	private static float windowSeconds(Chains.Link l) {
		return (l.window() > 0 ? (float) l.window() : AuraConfig.CHAINS_WINDOW_TICKS / 20f);
	}

	private static String cast(Ability a, CastMode mode, AuraType aura) {
		if (mode == null || mode == CastMode.TAP) return a.displayName;
		String name = Casts.variantName(a, aura, mode);
		return (mode == CastMode.SNEAK ? "sneak + " : "hold ") + a.displayName + (name == null ? "" : " (" + name + ")");
	}

	// ---------------------------------------------------------------
	// Start, stop, show
	// ---------------------------------------------------------------

	public static boolean running(ServerPlayer player) {
		return RUNS.containsKey(player.getUUID());
	}

	/** True while the trial gives the whole kit (running, in the Training Hall). */
	public static boolean fullKit(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		return server != null && AuraState.get(server).getOrCreate(player.getUUID()).trialKit;
	}

	/** /aura trial and the Aura Book button: starts (or restarts) the trial for the aura held, in the Training Hall. */
	public static void start(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
		if (data.aura == null) {
			player.sendSystemMessage(Component.literal("Roll an aura first (open your Aura Book), then take its trial.").withStyle(ChatFormatting.RED));
			return;
		}
		if (Duel.isIn(player) || Match.isIn(player) || AltarRitual.inRitual(player)) {
			player.sendSystemMessage(Component.literal("Finish your match first, then take the trial.").withStyle(ChatFormatting.RED));
			return;
		}
		List<Item> items = checklist(data);
		Run run = new Run(data.aura, Branch.current(data), items);
		RUNS.put(player.getUUID(), run);
		if (!Arena.inTraining(player)) {
			if (!Arena.in(player)) Arena.rememberReturn(player);
			Arena.toHall(player);
		}
		data.trialKit = true;
		AuraFx.sound(player.serverLevel(), player, AuraSounds.BEACON_ACTIVATE.value(), 0.7f, 1.3f);
		ArenaFx.title(player, Component.literal(data.aura.displayName + " Trial").withStyle(data.aura.color, ChatFormatting.BOLD),
				Component.literal(items.size() + " things to try - in any order").withStyle(ChatFormatting.GRAY));
		player.sendSystemMessage(Component.literal("━━━ ✦ " + data.aura.displayName.toUpperCase(java.util.Locale.ROOT) + " TRIAL ✦ ━━━").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		player.sendSystemMessage(Component.literal("Your whole kit works here for the trial: every ability, cast and chain, whatever you own or your Mastery. "
				+ "Leave the Training Hall to pause it.").withStyle(ChatFormatting.GRAY));
		show(player);
	}

	/** The checklist in chat, with what is done ticked. */
	public static void show(ServerPlayer player) {
		Run run = RUNS.get(player.getUUID());
		if (run == null) {
			player.sendSystemMessage(Component.literal("No Aura Trial running. ").withStyle(ChatFormatting.GRAY).append(startButton()));
			return;
		}
		String section = "";
		for (int i = 0; i < run.items.size(); i++) {
			Item it = run.items.get(i);
			String now = it.chain != null ? "Chains" : it.mode == CastMode.TAP ? "Abilities" : "Casts";
			if (it.chain == null && it.label.endsWith("(Ultimate)")) now = "Abilities";
			if (!now.equals(section)) {
				section = now;
				player.sendSystemMessage(Component.literal(section + ":").withStyle(ChatFormatting.YELLOW));
			}
			player.sendSystemMessage(line(run, i));
			if (it.what != null) player.sendSystemMessage(Component.literal("     ↳ " + it.what).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}
		player.sendSystemMessage(Component.literal((run.items.size() - run.left) + " / " + run.items.size() + " done.  ").withStyle(ChatFormatting.AQUA)
				.append(button("[Stop]", "/aura trial stop", "End the trial", ChatFormatting.RED)));
	}

	private static Component line(Run run, int i) {
		Item it = run.items.get(i);
		boolean done = run.done[i];
		return Component.literal(done ? " ✔ " : " ☐ ").withStyle(done ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
				.append(Component.literal(it.label).withStyle(done ? ChatFormatting.GRAY : ChatFormatting.WHITE))
				.append(Component.literal("  " + it.how).withStyle(ChatFormatting.DARK_GRAY));
	}

	public static void stop(ServerPlayer player, String why) {
		Run run = RUNS.remove(player.getUUID());
		MinecraftServer server = player.getServer();
		if (server != null) AuraState.get(server).getOrCreate(player.getUUID()).trialKit = false;
		if (run != null && why != null) player.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.GRAY).append(Component.literal(" ")).append(startButton()));
	}

	/** After a roll or a switch: the chat offer. */
	public static void offer(ServerPlayer player, AuraType aura) {
		if (running(player)) stop(player, null);
		player.sendSystemMessage(Component.literal("✦ Try everything ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(aura.displayName).withStyle(aura.color, ChatFormatting.BOLD))
				.append(Component.literal(" can do - every ability, cast and chain, before you buy any - in the Aura Trial: ").withStyle(ChatFormatting.GOLD))
				.append(startButton()));
	}

	static MutableComponent startButton() {
		return button("[Take the Aura Trial]", "/aura trial", "To the Training Hall: try your aura's whole kit. Retake it any time.", ChatFormatting.GREEN);
	}

	private static MutableComponent button(String text, String command, String hover, ChatFormatting color) {
		return Component.literal(text).withStyle(s -> s.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
	}

	// ---------------------------------------------------------------
	// Progress
	// ---------------------------------------------------------------

	/** AuraAbilities.tryActivate: a key press that fired, with the cast it used. */
	public static void onPressed(ServerPlayer player, Ability ability, CastMode mode) {
		Run run = RUNS.get(player.getUUID());
		if (run == null || run.paused) return;
		CastMode m = mode == null ? CastMode.TAP : mode;
		for (int i = 0; i < run.items.size(); i++) if (!run.done[i] && run.items.get(i).matches(ability, m)) tick(player, run, i);
	}

	/** Chains: a link went off. */
	public static void onChain(ServerPlayer player, Chains.Link link) {
		Run run = RUNS.get(player.getUUID());
		if (run == null || run.paused) return;
		for (int i = 0; i < run.items.size(); i++) if (!run.done[i] && same(run.items.get(i).chain, link)) tick(player, run, i);
	}

	/** The same link, also after /aura reload made new Link objects. */
	private static boolean same(Chains.Link a, Chains.Link b) {
		return a != null && b != null && a.from() == b.from() && a.to() == b.to() && a.name().equals(b.name());
	}

	private static void tick(ServerPlayer player, Run run, int i) {
		run.done[i] = true;
		run.left--;
		Item it = run.items.get(i);
		player.sendSystemMessage(Component.literal("✔ " + it.label).withStyle(ChatFormatting.GREEN)
				.append(Component.literal("  (" + (run.items.size() - run.left) + "/" + run.items.size() + ")").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(it.what == null ? "" : "  " + it.what).withStyle(ChatFormatting.GRAY)));
		AuraFx.sound(player.serverLevel(), player, AuraSounds.EXPERIENCE_ORB_PICKUP.value(), 0.6f, 1.0f + 0.5f * (run.items.size() - run.left) / run.items.size());
		if (run.left <= 0) finish(player, run);
	}

	private static void finish(ServerPlayer player, Run run) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
		boolean first = data.trialDone.add(run.aura);
		stop(player, null);
		ArenaFx.title(player, Component.literal("Trial complete").withStyle(run.aura.color, ChatFormatting.BOLD),
				Component.literal(run.aura.displayName + ": every ability, cast and chain").withStyle(ChatFormatting.GRAY));
		AuraFx.sound(player.serverLevel(), player, AuraSounds.UI_TOAST_CHALLENGE_COMPLETE.value(), 0.8f, 1.1f);
		player.sendSystemMessage(Component.literal("✦ " + run.aura.displayName + " Trial complete" + (first ? "!" : " again!")).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		player.sendSystemMessage(Component.literal("Back to your own kit now: buy actives in the Aura Book; casts open at Mastery "
				+ com.toast.aurapoints.ability.Mastery.roman(AuraConfig.CAST_SNEAK_MASTERY) + " (sneak) and "
				+ com.toast.aurapoints.ability.Mastery.roman(AuraConfig.CAST_CHARGED_MASTERY) + " (hold). Retake the trial any time: ").withStyle(ChatFormatting.GRAY)
				.append(startButton()));
	}

	// ---------------------------------------------------------------
	// Upkeep
	// ---------------------------------------------------------------

	/** Every server tick: the kit follows the hall, the aura must stay the same, the action bar shows what is next. */
	public static void tickAll(MinecraftServer server) {
		if (RUNS.isEmpty()) return;
		AuraState state = AuraState.get(server);
		for (Iterator<Map.Entry<UUID, Run>> it = RUNS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Run> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null && testPlayer != null && testPlayer.getUUID().equals(e.getKey())) p = testPlayer;
			AuraPlayerData data = state.getOrCreate(e.getKey());
			Run run = e.getValue();
			if (p == null) {
				data.trialKit = false;
				it.remove();
				continue;
			}
			if (data.aura != run.aura || Branch.current(data) != run.branch) {
				data.trialKit = false;
				it.remove();
				p.sendSystemMessage(Component.literal("Aura Trial ended: your aura changed.").withStyle(ChatFormatting.GRAY));
				continue;
			}
			boolean in = Arena.inTraining(p);
			if (in == run.paused) {
				run.paused = !in;
				p.sendSystemMessage(Component.literal(in ? "Aura Trial goes on." : "Aura Trial paused: back to the Training Hall to go on (/aura trial stop ends it).")
						.withStyle(ChatFormatting.GRAY));
			}
			// never during a DPS challenge (TrainingHall refuses to start one in a trial; this is the safety net)
			data.trialKit = in && !TrainingHall.inChallenge(p);
			if (in && server.getTickCount() % 20 == 0) {
				Item next = null;
				for (int i = 0; i < run.items.size(); i++) if (!run.done[i]) {
					next = run.items.get(i);
					break;
				}
				if (next != null) {
					p.displayClientMessage(Component.literal("Trial " + (run.items.size() - run.left) + "/" + run.items.size() + "  ·  next: ").withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb)))
							.append(Component.literal(next.label).withStyle(ChatFormatting.WHITE))
							.append(Component.literal(" - " + next.how).withStyle(ChatFormatting.GRAY)), true);
				}
			}
		}
	}

	/** The dev test's fake player (not in the player list). */
	public static ServerPlayer testPlayer;

	public static void onLogout(ServerPlayer player) {
		stop(player, null);
	}

	public static void clear() {
		RUNS.clear();
	}

	/** Dev test: the running checklist's labels and what is done. */
	public static List<String> state(ServerPlayer player) {
		Run run = RUNS.get(player.getUUID());
		List<String> out = new ArrayList<>();
		if (run == null) return out;
		for (int i = 0; i < run.items.size(); i++) out.add((run.done[i] ? "✔ " : "☐ ") + run.items.get(i).label);
		return out;
	}

	/** Dev test: every item with its hint and (chains) what it does. */
	public static List<String> describe(ServerPlayer player) {
		Run run = RUNS.get(player.getUUID());
		List<String> out = new ArrayList<>();
		if (run != null) for (Item it : run.items) out.add(it.label + " - " + it.how + (it.what == null ? "" : " => " + it.what));
		return out;
	}
}
