package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.CastMode;
import com.toast.aurapoints.ability.Casts;
import com.toast.aurapoints.ability.Chains;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The Training Hall's combo trainer (5.37.0): a live readout over the dummy you last hit (chains,
 * reactions and casts as they go off, a combo counter), and the hub that feeds the ROTATION kiosk's
 * score and the daily challenge cards ({@link TrainerCards}). Hooks: Chains.use, Reactions.feedback,
 * AuraAbilities.tryActivate / Ultimates (a press that fired), TrainingHall.onDummyHit.
 */
public final class ComboTrainer {

	private ComboTrainer() {
	}

	/** Tag on the readout displays: one left over from a crash or restart is removed when it loads. */
	static final String TAG = "aurapoints_trainer";
	private static final int LINES = 4, IDLE_TICKS = 20 * 20;

	private static final class Session {
		final Deque<Component> lines = new ArrayDeque<>();
		int combo, bestCombo;
		long lastEvent, lastSeen;
		float lastHit;
		boolean lastHitAbility;
		Vec3 anchor;
		Display.TextDisplay display;
	}

	private static final Map<UUID, Session> SESSIONS = new HashMap<>();

	public static boolean isLeftover(Entity e) {
		if (!(e instanceof Display.TextDisplay) || !e.getTags().contains(TAG)) return false;
		for (Session s : SESSIONS.values()) if (s.display == e) return false;
		return true;
	}

	/** Counts only in the Training Hall. */
	private static boolean counts(ServerPlayer player) {
		return player != null && Arena.inTraining(player);
	}

	// ---------------------------------------------------------------
	// Events
	// ---------------------------------------------------------------

	/** Chains.use: a link went off. */
	public static void onChain(ServerPlayer player, Chains.Link link, int streak) {
		if (!counts(player)) return;
		event(player, Component.literal("Chain" + (streak > 2 ? " x" + (streak - 1) : "") + " ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal(link.name()).withStyle(ChatFormatting.WHITE)), true);
		TrainingHall.rotationEvent(player, TrainingHall.Score.CHAIN, link.name());
		TrainerCards.onChain(player, link);
	}

	/** Reactions.feedback: a reaction went off. */
	public static void onReaction(ServerPlayer player, String name, String symbol, int rgb) {
		if (!counts(player)) return;
		event(player, Component.literal(symbol + " " + name).withStyle(s -> s.withColor(TextColor.fromRgb(rgb)).withBold(true)), true);
		TrainingHall.rotationEvent(player, TrainingHall.Score.REACTION, name);
	}

	/** A key press that fired (tap, sneak or charged cast, or the Ultimate). */
	public static void onCast(ServerPlayer player, Ability ability, CastMode mode) {
		if (!counts(player) || ability == null) return;
		CastMode m = mode == null ? CastMode.TAP : mode;
		var data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		String variant = data.aura == null ? null : Casts.variantName(ability, data.aura, m);
		String label = variant != null ? variant + (m == CastMode.SNEAK ? " (sneak)" : " (hold)") : ability.displayName;
		ChatFormatting color = variant != null ? ChatFormatting.AQUA : ChatFormatting.GRAY;
		// a plain tap alone isn't a combo step, a cast variant is
		event(player, Component.literal(label).withStyle(color), variant != null);
		TrainingHall.rotationEvent(player, TrainingHall.Score.USE, ability.name() + ":" + m.name());
		TrainerCards.onCast(player, ability, m);
	}

	/** TrainingHall.onDummyHit: the readout moves over the dummy that was hit. */
	static void onHit(ServerPlayer attacker, LivingEntity dummy, float amount, boolean abilityHit) {
		if (!counts(attacker)) return;
		TrainerCards.onDamage(attacker, amount);
		if (!AuraConfig.TRAINING_READOUT_ENABLED) return;
		Session s = SESSIONS.computeIfAbsent(attacker.getUUID(), k -> new Session());
		s.lastHit = amount;
		s.lastHitAbility = abilityHit;
		s.lastSeen = attacker.serverLevel().getGameTime();
		s.anchor = dummy.position().add(0, dummy.getBbHeight() + 1.1, 0);
		redraw(attacker, s);
	}

	private static void event(ServerPlayer player, Component line, boolean comboStep) {
		long now = player.serverLevel().getGameTime();
		Session s = SESSIONS.computeIfAbsent(player.getUUID(), k -> new Session());
		if (comboStep) {
			s.combo = now - s.lastEvent <= AuraConfig.TRAINING_COMBO_GAP_TICKS ? s.combo + 1 : 1;
			s.lastEvent = now;
			s.bestCombo = Math.max(s.bestCombo, s.combo);
			TrainerCards.onCombo(player, s.combo);
		}
		s.lastSeen = now;
		s.lines.addFirst(line);
		while (s.lines.size() > LINES) s.lines.removeLast();
		if (AuraConfig.TRAINING_READOUT_ENABLED) redraw(player, s);
	}

	/** The combo the player is on right now (0 once it ran out). */
	public static int combo(ServerPlayer player) {
		Session s = SESSIONS.get(player.getUUID());
		if (s == null) return 0;
		return player.serverLevel().getGameTime() - s.lastEvent <= AuraConfig.TRAINING_COMBO_GAP_TICKS ? s.combo : 0;
	}

	// ---------------------------------------------------------------
	// The hologram
	// ---------------------------------------------------------------

	static Component text(ServerPlayer player, Session s, long now) {
		boolean live = now - s.lastEvent <= AuraConfig.TRAINING_COMBO_GAP_TICKS && s.combo > 1;
		MutableComponent text = Component.literal(player.getGameProfile().getName()).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(live ? "  COMBO x" + s.combo : "  combo -").withStyle(live ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
		if (s.lastHit > 0) {
			text.append(Component.literal(String.format(java.util.Locale.ROOT, "\nlast hit %.1f%s", s.lastHit, s.lastHitAbility ? " ✦" : ""))
					.withStyle(ChatFormatting.GRAY));
		}
		for (Component line : s.lines) text.append(Component.literal("\n")).append(line);
		if (s.bestCombo > 1) text.append(Component.literal("\nbest combo x" + s.bestCombo).withStyle(ChatFormatting.DARK_GRAY));
		return text;
	}

	private static void redraw(ServerPlayer player, Session s) {
		ServerLevel level = player.serverLevel();
		long now = level.getGameTime();
		Vec3 at = s.anchor != null ? s.anchor
				: player.getEyePosition().add(player.getLookAngle().multiply(1, 0, 1).normalize().scale(3)).add(0, 0.4, 0);
		if (s.display == null || s.display.isRemoved()) {
			s.display = Holograms.make(level, at, text(player, s, now), 0.6f, 0x70000000);
			if (s.display == null) return;
			s.display.addTag(TAG);
			level.addFreshEntity(s.display);
		} else {
			if (s.display.position().distanceToSqr(at) > 0.01) s.display.teleportTo(at.x, at.y, at.z);
			Holograms.setText(s.display, text(player, s, now));
		}
	}

	private static void remove(Session s) {
		if (s.display != null && !s.display.isRemoved()) s.display.discard();
		s.display = null;
	}

	/** From TrainingHall.tick: the combo running out redraws, an idle or gone player's readout goes. */
	static void tick(MinecraftServer server) {
		for (Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Session> e = it.next();
			Session s = e.getValue();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || !Arena.inTraining(p) || p.serverLevel().getGameTime() - s.lastSeen > IDLE_TICKS) {
				remove(s);
				it.remove();
				continue;
			}
			long now = p.serverLevel().getGameTime();
			if (s.combo > 0 && now - s.lastEvent == AuraConfig.TRAINING_COMBO_GAP_TICKS + 1 && s.display != null) redraw(p, s);
		}
	}

	/** Dev test: the readout's text for a player, or null without a session. */
	public static String devText(ServerPlayer player) {
		Session s = SESSIONS.get(player.getUUID());
		return s == null ? null : text(player, s, player.serverLevel().getGameTime()).getString();
	}

	static void clearAll() {
		for (Session s : SESSIONS.values()) remove(s);
		SESSIONS.clear();
		TrainerCards.clearAll();
	}
}
