package com.toast.aurapoints.ability;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.fml.loading.FMLPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ability chains (5.24): use ability A, then B within CHAINS_WINDOW_TICKS, and B is chained. For CHAINS_EMPOWER_TICKS
 * the player's ability hits (at most CHAINS_MAX_HITS) do the link's bonus more (capped at CHAINS_MAX_BONUS, never
 * stacking) and carry its effect; cast effects (refund, speed, heal) fire at once. A chain can go on (A, B, C): the
 * streak shows on the action bar. The links live per aura in config/aurapoints-chains.json ("shadow:dark" for a kit
 * branch), written with the defaults below when missing, read again on /aura reload.
 *
 * A "use" is the key press that fired ({@link #onPressed}, from AuraAbilities.tryActivate), not the cooldown start:
 * the viper, the Thunderbolt combo, the Grasp and others only start their cooldown when they END. Abilities that never
 * go through tryActivate (Wind's launch) count by {@link #onUsed} (AuraHooks.abilityUsed), which also pays a refund
 * that had to wait for the cooldown. {@link #onHit} from AuraPoints.applyAttackerBonuses (ability hits only).
 * A link can have its own window and its own length ("window" / "lasts", seconds).
 */
public final class Chains {

	private Chains() {
	}

	public enum Effect {
		NONE, IGNITE, CHILL, WET, CHARGED, POISON, SLOW, WEAKEN, KNOCKUP, LIFESTEAL, REFUND, SPEED, HEAL;

		/** Fires when the chain is made, not on a hit. */
		boolean onCast() {
			return this == REFUND || this == SPEED || this == HEAL;
		}

		String text(double power) {
			return switch (this) {
				case NONE -> "";
				case IGNITE -> "sets targets alight for " + secs(power);
				case CHILL -> "chills targets";
				case WET -> "soaks targets";
				case CHARGED -> "charges targets";
				case POISON -> "poisons targets for " + secs(power);
				case SLOW -> "slows targets for " + secs(power);
				case WEAKEN -> "weakens targets for " + secs(power);
				case KNOCKUP -> "throws targets up";
				case LIFESTEAL -> "heals you for " + pct(power) + "% of the damage";
				case REFUND -> "gives back " + pct(power) + "% of its cooldown";
				case SPEED -> "speed for " + secs(power);
				case HEAL -> "heals you " + fmt(power) + " HP";
			};
		}
	}

	/** One link: {@code from} (in {@code fromMode}, null = any cast) then {@code to} (in {@code toMode}). */
	public record Link(Ability from, CastMode fromMode, Ability to, CastMode toMode, String name, double bonus, Effect effect, double power,
			double window, double lasts) {

		int windowTicks() {
			return window > 0 ? ticks(window) : AuraConfig.CHAINS_WINDOW_TICKS;
		}

		int lastsTicks() {
			return lasts > 0 ? ticks(lasts) : AuraConfig.CHAINS_EMPOWER_TICKS;
		}

		boolean first(Ability a, CastMode m) {
			return from == a && (fromMode == null || fromMode == m);
		}

		boolean second(Ability a, CastMode m) {
			return to == a && (toMode == null || toMode == m);
		}

		/** What the chain does in a sentence, for the Aura Trial: "for 5 s your ability hits (up to 6) do +30% damage, slows targets for 3 s". */
		public String explain() {
			String hit = (bonus > 0 ? "+" + pct(Math.min(bonus, AuraConfig.CHAINS_MAX_BONUS)) + "% damage" : "");
			String onHit = effect.onCast() ? "" : effect.text(power);
			if (!onHit.isEmpty()) hit = hit.isEmpty() ? onHit : hit + ", " + onHit;
			String now = effect.onCast() ? effect.text(power) : "";
			if (hit.isEmpty()) return now.isEmpty() ? "no bonus" : "the second ability " + now;
			String out = "for " + secs(lastsTicks() / 20.0) + " your ability hits (up to " + AuraConfig.CHAINS_MAX_HITS + ") do " + hit;
			return now.isEmpty() ? out : out + "; and it " + now;
		}

		/** "+30% damage, slows targets for 3 s" */
		String perk() {
			String b = bonus > 0 ? "+" + pct(Math.min(bonus, AuraConfig.CHAINS_MAX_BONUS)) + "% damage" : "";
			String e = effect.text(power);
			return b.isEmpty() ? e : e.isEmpty() ? b : b + ", " + e;
		}
	}

	private static final class State {
		Ability last;
		CastMode lastMode;
		long lastTick = Long.MIN_VALUE / 2;
		Link active;
		long activeUntil;
		int hitsLeft;
		int streak;
		/** a refund waiting for this ability's cooldown to start */
		Ability refund;
		double refundShare;
		long refundUntil;
		final java.util.EnumMap<Ability, Long> pressed = new java.util.EnumMap<>(Ability.class);
	}

	/** A cooldown report this long after the ability's key press is still that press (the viper lives a while). */
	private static final int LATE_REPORT_TICKS = 20 * 60;

	private static final Map<UUID, State> STATES = new HashMap<>();

	/** The cast mode of the key press running right now (AuraAbilities.tryActivate sets and clears it); null = no press is running. */
	public static CastMode castMode = null;

	// ---------------------------------------------------------------
	// Use and hit
	// ---------------------------------------------------------------

	/** An ability's key press fired (AuraAbilities.tryActivate): remembers it, and chains it when the last one links to it. */
	public static void onPressed(ServerPlayer player, Ability ability, CastMode mode) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		State st = STATES.computeIfAbsent(player.getUUID(), k -> new State());
		st.pressed.put(ability, (long) server.getTickCount());
		use(player, st, ability, mode != null ? mode : CastMode.TAP, server.getTickCount());
	}

	/**
	 * An ability started its cooldown (AuraHooks.abilityUsed). Pays a waiting refund; and for an ability no key press
	 * reported (Wind's launch) this is the use.
	 */
	public static void onUsed(ServerPlayer player, Ability ability) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		long now = server.getTickCount();
		State st = STATES.get(player.getUUID());
		if (st != null && st.refund == ability) {
			double share = st.refundShare;
			st.refund = null;
			// the report comes just before the cooldown starts
			if (now <= st.refundUntil) AuraFx.later(server, 1, () -> refund(player, ability, share));
		}
		// inside a key press: onPressed follows; a press not long ago: this is that use ending
		if (castMode != null) return;
		if (st != null) {
			Long pressed = st.pressed.get(ability);
			if (pressed != null && now >= pressed && now - pressed <= LATE_REPORT_TICKS) return;
			if (st.last == ability && st.lastTick == now) return;
		}
		use(player, st != null ? st : STATES.computeIfAbsent(player.getUUID(), k -> new State()), ability, CastMode.TAP, now);
	}

	private static void use(ServerPlayer player, State st, Ability ability, CastMode mode, long now) {
		MinecraftServer server = player.getServer();
		if (server == null || !AuraConfig.CHAINS_ENABLED) return;
		AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
		if (data.aura == null || (Mastery.level(player) < AuraConfig.CHAINS_MASTERY && !data.trialKit)) return;
		Link link = null;
		if (st.last != null && now >= st.lastTick) {
			for (Link l : links(data)) {
				if (now - st.lastTick <= l.windowTicks() && l.first(st.last, st.lastMode) && l.second(ability, mode)) {
					link = l;
					break;
				}
			}
		}
		st.last = ability;
		st.lastMode = mode;
		st.lastTick = now;
		if (link == null) return;
		st.streak = st.active != null && now <= st.activeUntil + AuraConfig.CHAINS_WINDOW_TICKS ? st.streak + 1 : 2;
		st.active = link;
		st.activeUntil = now + link.lastsTicks();
		st.hitsLeft = AuraConfig.CHAINS_MAX_HITS;
		AuraStats.add(player, AuraStats.CHAINS, 1);
		com.toast.aurapoints.arena.AuraTrial.onChain(player, link);
		com.toast.aurapoints.arena.ComboTrainer.onChain(player, link, st.streak);
		castEffect(player, st, link, ability, now);
		cue(player, data.aura, link, st.streak);
	}

	private static void castEffect(ServerPlayer player, State st, Link link, Ability ability, long now) {
		switch (link.effect) {
			case SPEED -> player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, ticks(link.power), 1, false, true));
			case HEAL -> player.heal((float) link.power);
			case REFUND -> {
				// the cooldown runs already (most abilities), or starts when the ability ends: then the refund waits for it
				if (AuraAbilities.cooldownTicksLeft(player, ability, now) > 0) refund(player, ability, link.power);
				else {
					st.refund = ability;
					st.refundShare = link.power;
					st.refundUntil = now + LATE_REPORT_TICKS;
				}
			}
			default -> {
			}
		}
	}

	private static void refund(ServerPlayer player, Ability ability, double share) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		long t = server.getTickCount();
		int left = AuraAbilities.cooldownTicksLeft(player, ability, t);
		if (left > 0) AuraAbilities.setCooldown(player, ability, Math.max(1, (int) Math.round(left * (1.0 - Math.max(0.0, Math.min(1.0, share))))), t);
	}

	private static void cue(ServerPlayer player, com.toast.aurapoints.ability.AuraType aura, Link link, int streak) {
		String head = streak > 2 ? "Chain x" + (streak - 1) + "! " : "Chain! ";
		Component line = Component.literal(head).withStyle(ChatFormatting.BOLD)
				.withStyle(s -> s.withColor(TextColor.fromRgb(aura.rgb)))
				.append(Component.literal(link.name).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(link.perk().isEmpty() ? "" : "  " + link.perk()).withStyle(ChatFormatting.GRAY));
		player.displayClientMessage(line, true);
		ServerLevel level = player.serverLevel();
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.AMETHYST_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.9f, 1.2f + 0.15f * Math.min(4, streak - 2));
		AuraFx.particles(level, ParticleTypes.ENCHANTED_HIT, player.getX(), player.getY() + 1.0, player.getZ(), 10, 0.4, 0.5, 0.4, 0.15);
	}

	/** An ability hit by {@code attacker}: the running chain's bonus and effect. Returns the new damage. */
	public static float onHit(ServerPlayer attacker, LivingEntity victim, float damage) {
		State st = STATES.get(attacker.getUUID());
		MinecraftServer server = attacker.getServer();
		if (st == null || st.active == null || server == null || st.hitsLeft <= 0) return damage;
		if (server.getTickCount() > st.activeUntil) {
			st.active = null;
			return damage;
		}
		Link link = st.active;
		st.hitsLeft--;
		float out = (float) (damage * (1.0 + Math.max(0.0, Math.min(link.bonus, AuraConfig.CHAINS_MAX_BONUS))));
		int t = ticks(link.power);
		switch (link.effect) {
			case IGNITE -> victim.igniteForTicks(Math.max(victim.getRemainingFireTicks(), t));
			case CHILL -> Reactions.applyMark(victim, Reactions.Mark.CHILLED);
			case WET -> Reactions.applyMark(victim, Reactions.Mark.WET);
			case CHARGED -> Reactions.applyMark(victim, Reactions.Mark.CHARGED);
			case POISON -> victim.addEffect(new MobEffectInstance(MobEffects.POISON, t, 0), attacker);
			case SLOW -> victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, t, 1), attacker);
			case WEAKEN -> victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, t, 0), attacker);
			case KNOCKUP -> {
				victim.setDeltaMovement(victim.getDeltaMovement().x, Math.max(victim.getDeltaMovement().y, 0.55), victim.getDeltaMovement().z);
				victim.hurtMarked = true;
			}
			case LIFESTEAL -> attacker.heal((float) (out * Math.max(0.0, link.power)));
			default -> {
			}
		}
		if (AuraFx.ready(attacker, "chainhit", 4)) {
			AuraFx.particles((ServerLevel) victim.level(), ParticleTypes.ENCHANTED_HIT, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(),
					6, 0.3, 0.3, 0.3, 0.2);
		}
		return out;
	}

	/** Every link by table key (dev tests). */
	public static Map<String, List<Link>> all() {
		return table();
	}

	/** How many abilities the running chain is long (0 = none; dev tests). */
	public static int streak(ServerPlayer player) {
		return active(player) == null ? 0 : STATES.get(player.getUUID()).streak;
	}

	/** The running chain's name, or null (dev tests). */
	public static String active(ServerPlayer player) {
		State st = STATES.get(player.getUUID());
		MinecraftServer server = player.getServer();
		return st == null || st.active == null || server == null || server.getTickCount() > st.activeUntil ? null : st.active.name;
	}

	public static void forget(UUID player) {
		STATES.remove(player);
	}

	public static void clear() {
		STATES.clear();
		castMode = null;
	}

	// ---------------------------------------------------------------
	// The table
	// ---------------------------------------------------------------

	private static Map<String, List<Link>> table;

	/** The links that work for this player's aura and branch now. */
	public static List<Link> links(AuraPlayerData data) {
		Map<String, List<Link>> t = table();
		Branch branch = Branch.current(data);
		if (branch != null && branch.aura == data.aura) {
			List<Link> own = t.get(data.aura.id() + ":" + branch.id());
			if (own != null) return own;
		}
		return t.getOrDefault(data.aura.id(), List.of());
	}

	/** Lore lines for {@code ability}: every link of this aura (and its branches) that ends on it. */
	public static List<String> describe(Ability ability, com.toast.aurapoints.ability.AuraType aura) {
		List<String> out = new ArrayList<>(1);
		if (!AuraConfig.CHAINS_ENABLED) return out;
		for (var e : table().entrySet()) {
			if (!e.getKey().equals(aura.id()) && !e.getKey().startsWith(aura.id() + ":")) continue;
			for (Link l : e.getValue()) {
				if (l.to != ability) continue;
				out.add("Chain " + l.name + ": " + castName(l.from, l.fromMode) + " then " + castName(l.to, l.toMode)
						+ " within " + secs(l.windowTicks() / 20.0) + (l.perk().isEmpty() ? "." : ": " + l.perk() + "."));
			}
		}
		return out;
	}

	private static String castName(Ability a, CastMode m) {
		return m == null || m == CastMode.TAP && !CastMode.has(a, CastMode.SNEAK) && !CastMode.has(a, CastMode.CHARGED) ? a.displayName
				: m == CastMode.TAP ? a.displayName + " (normal)" : m == CastMode.SNEAK ? "sneak + " + a.displayName : "charged " + a.displayName;
	}

	private static synchronized Map<String, List<Link>> table() {
		if (table == null) load();
		return table;
	}

	private static Path file() {
		return FMLPaths.CONFIGDIR.get().resolve("aurapoints-chains.json");
	}

	/** Reads the table again (/aura reload). */
	public static synchronized void reload() {
		table = null;
	}

	private static void load() {
		Path path = file();
		JsonObject json;
		try {
			if (!Files.exists(path)) {
				json = defaults();
				Files.createDirectories(path.getParent());
				Files.writeString(path, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json), StandardCharsets.UTF_8);
			} else {
				json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (Exception e) {
			AuraPoints.LOGGER.error("Can't read {} - using the built-in chains until it's fixed.", path, e);
			json = defaults();
		}
		Map<String, List<Link>> out = new LinkedHashMap<>();
		for (var entry : json.entrySet()) {
			if (entry.getKey().startsWith("_") || !entry.getValue().isJsonArray()) continue;
			List<Link> list = new ArrayList<>();
			for (JsonElement el : entry.getValue().getAsJsonArray()) {
				try {
					list.add(parse(el.getAsJsonObject()));
				} catch (Exception e) {
					AuraPoints.LOGGER.warn("aurapoints-chains.json: skipped a {} link ({}): {}", entry.getKey(), e.getMessage(), el);
				}
			}
			out.put(entry.getKey().toLowerCase(Locale.ROOT), List.copyOf(list));
		}
		table = out;
	}

	private static Link parse(JsonObject o) {
		String[] from = o.get("from").getAsString().split(":"), to = o.get("to").getAsString().split(":");
		return new Link(ability(from[0]), from.length > 1 ? mode(from[1]) : null, ability(to[0]), to.length > 1 ? mode(to[1]) : null,
				o.has("name") ? o.get("name").getAsString() : "Chain",
				o.has("bonus") ? o.get("bonus").getAsDouble() : 0.0,
				o.has("effect") ? Effect.valueOf(o.get("effect").getAsString().toUpperCase(Locale.ROOT)) : Effect.NONE,
				o.has("power") ? o.get("power").getAsDouble() : 0.0,
				o.has("window") ? o.get("window").getAsDouble() : 0.0,
				o.has("lasts") ? o.get("lasts").getAsDouble() : 0.0);
	}

	private static Ability ability(String s) {
		return Ability.valueOf(s.trim().toUpperCase(Locale.ROOT));
	}

	private static CastMode mode(String s) {
		return CastMode.valueOf(s.trim().toUpperCase(Locale.ROOT));
	}

	private static JsonObject defaults() {
		JsonObject json = new JsonObject();
		json.addProperty("_info", "Aura Points ability chains: use 'from', then 'to' within CHAINS_WINDOW_TICKS (aurapoints-general.json). "
				+ "A link may name a cast: ability:sneak / :charged / :tap. bonus = extra damage of your ability hits for CHAINS_EMPOWER_TICKS "
				+ "(capped by CHAINS_MAX_BONUS). effect: none, ignite, chill, wet, charged, poison, slow, weaken, knockup (on hit), "
				+ "lifesteal (power = share of the damage), refund (power = share of the cooldown given back), speed, heal (power = HP). "
				+ "power is seconds for the timed effects. Optional per link: window (seconds you have for the second ability) and lasts (seconds the bonus stays). "
				+ "A use is the key press.  Keys are aura ids, 'shadow:dark' for the Dark branch's kit. Edit, then /aura reload.");
		add(json, "shadow", "vanish", "dash", "Shade Step", 0.30, "speed", 3);
		add(json, "shadow", "dash", "vanish", "Slip Away", 0.0, "refund", 0.25);
		add(json, "shadow:dark", "void_pull", "null_orb", "Collapse", 0.35, "slow", 3);
		add(json, "shadow:dark", "rift_step:tap", "void_pull", "Rift Snare", 0.25, "weaken", 3);
		add(json, "water", "shockwave", "abyssal_bubble", "Undertow", 0.30, "wet", 0);
		add(json, "water", "healing_tide", "shockwave", "Riptide", 0.25, "heal", 2);
		add(json, "fire", "fire_bending", "fireball", "Stoked", 0.30, "ignite", 4);
		add(json, "fire", "fireball", "heat_wave", "Backdraft", 0.35, "none", 0);
		add(json, "wind", "dash", "super_wind_charge", "Slipstream", 0.30, "knockup", 0);
		add(json, "wind", "super_wind_charge", "dash", "Tailspin", 0.0, "refund", 0.40);
		// Frost Path puts the spike on half its cooldown: the spike right after, while the path still lasts
		add(json, "frost", "ice_spike:sneak", "ice_spike:tap", "Black Ice", 0.35, "chill", 0).addProperty("window", 10);
		// the way back: after a spike, Frost Path comes sooner. Both casts share the spike's cooldown (15 s), so the
		// window must outlast it: Frost Path can only fire once that cooldown is over
		add(json, "frost", "ice_spike:tap", "ice_spike:sneak", "Cold Snap", 0.0, "refund", 0.30).addProperty("window", 18);
		add(json, "verdant", "vine_hook", "entanglement", "Snare", 0.25, "slow", 3);
		add(json, "verdant", "entanglement", "verdant_viper", "Ambush Bite", 0.35, "poison", 4).addProperty("lasts", 8);
		// Chidori waits for the Thunderbolt combo to end
		add(json, "thunder", "thunderbolt", "chidori", "Overcharge", 0.35, "charged", 0).addProperty("window", 5);
		add(json, "thunder", "chidori", "thunderbolt", "Arc Return", 0.25, "refund", 0.25);
		add(json, "resonance", "sonic_blossom", "abyssal_grasp", "Echo Crush", 0.30, "weaken", 3);
		add(json, "resonance", "listening_tree", "sonic_blossom", "Attuned", 0.0, "refund", 0.30);
		add(json, "earth", "pillar_lift", "boulder_toss", "Skyfall", 0.35, "slow", 3);
		add(json, "earth", "boulder_toss", "quake", "Aftershock", 0.30, "knockup", 0);
		add(json, "light", "solar_flash", "prism_beam", "Focused Light", 0.35, "weaken", 3);
		add(json, "light", "radiant_pulse", "solar_flash", "Afterglow", 0.25, "heal", 2);
		add(json, "blood", "crimson_sacrifice", "hemorrhage", "Blood Price", 0.40, "lifesteal", 0.20);
		add(json, "blood", "hemorrhage", "blood_tether", "Open Vein", 0.25, "weaken", 3);
		add(json, "mage", "arcane_bolt", "arcane_orb", "Overload", 0.30, "slow", 2);
		add(json, "mage", "arcane_barrier", "arcane_bolt", "Retort", 0.25, "weaken", 3);
		return json;
	}

	private static JsonObject add(JsonObject json, String aura, String from, String to, String name, double bonus, String effect, double power) {
		JsonObject o = new JsonObject();
		o.addProperty("from", from);
		o.addProperty("to", to);
		o.addProperty("name", name);
		o.addProperty("bonus", bonus);
		o.addProperty("effect", effect);
		o.addProperty("power", power);
		JsonArray list = json.has(aura) ? json.getAsJsonArray(aura) : new JsonArray();
		list.add(o);
		json.add(aura, list);
		return o;
	}

	// ---------------------------------------------------------------

	private static int ticks(double seconds) {
		return Math.max(1, (int) Math.round(seconds * 20));
	}

	private static String secs(double s) {
		return fmt(s) + " s";
	}

	private static String pct(double f) {
		return String.valueOf(Math.round(f * 100));
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
	}
}
