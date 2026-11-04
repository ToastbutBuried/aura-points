package com.toast.aurapoints.arena;

import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.mixin.TextDisplayAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Floating text in the arena (text displays, always facing you): the NPCs' names and their live
 * lines ("2 dueling · 3 watching"), the hub's title and the two leaderboards. The live ones carry an
 * "ah_<key>" tag and are rewritten here; the rest are placed once by ArenaBuilder.
 */
public final class Holograms {

	private Holograms() {
	}

	private static final Map<String, Display.TextDisplay> LIVE = new HashMap<>();

	/** Places a hologram; {@code key} (or null) makes it a live one. {@code background} is ARGB (0 = none). */
	static void spawn(ServerLevel level, Vec3 at, Component text, float scale, int background, String key) {
		Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
		if (display == null) return;
		CompoundTag tag = new CompoundTag();
		tag.putString("text", Component.Serializer.toJson(text, level.registryAccess()));
		tag.putString("billboard", "center");
		tag.putString("alignment", "center");
		tag.putInt("background", background);
		tag.putBoolean("shadow", true);
		tag.putInt("line_width", 400);
		tag.putFloat("view_range", 2f);
		CompoundTag light = new CompoundTag();
		light.putInt("sky", 15);
		light.putInt("block", 15);
		tag.put("brightness", light);
		CompoundTag transformation = new CompoundTag();
		transformation.put("left_rotation", floats(0f, 0f, 0f, 1f));
		transformation.put("right_rotation", floats(0f, 0f, 0f, 1f));
		transformation.put("translation", floats(0f, 0f, 0f));
		transformation.put("scale", floats(scale, scale, scale));
		tag.put("transformation", transformation);
		display.load(tag);
		display.moveTo(at.x, at.y, at.z, 0f, 0f);
		ArenaBuilder.claim(display);
		if (key != null) display.addTag("ah_" + key);
		level.addFreshEntity(display);
	}

	private static ListTag floats(float... values) {
		ListTag list = new ListTag();
		for (float v : values) list.add(FloatTag.valueOf(v));
		return list;
	}

	/** A live hologram joined the level (placed now, or loaded with its chunk). */
	public static void track(Entity e) {
		if (!(e instanceof Display.TextDisplay display) || e.level().dimension() != Arena.KEY) return;
		for (String tag : e.getTags()) {
			if (tag.startsWith("ah_")) {
				LIVE.put(tag.substring(3), display);
				return;
			}
		}
	}

	private static void set(String key, Component text) {
		Display.TextDisplay d = LIVE.get(key);
		if (d == null || d.isRemoved()) return;
		((TextDisplayAccessor) d).aurapoints$setText(text);
	}

	/** Every second: the NPCs' live lines; every half minute, the leaderboards too. */
	static void tick(ServerLevel level) {
		long t = level.getGameTime();
		if (t % 20 != 0 || LIVE.isEmpty()) return;
		for (NpcKind kind : NpcKind.values()) set("live_" + kind.name().toLowerCase(java.util.Locale.ROOT), kind.liveLine(level));
		set("ring_live", ringBoard(level));
		if (t % 600 == 0) updateBoards(level);
	}

	/** Over the ring's south rim: the duel or match being fought, or that the ring is free. */
	private static Component ringBoard(ServerLevel level) {
		Component duel = Duel.liveBoard(level.getServer());
		if (duel != null) return duel;
		if (Match.running()) {
			return Component.literal("⚔ " + Match.describe()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
					.append(Component.literal("\n" + RingLayout.current.displayName).withStyle(ChatFormatting.GRAY));
		}
		return Component.literal("⚔ THE RING").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal("\nfree - challenge someone at the Duels NPC").withStyle(ChatFormatting.GRAY));
	}

	/** The leaderboards, from the records. */
	static void updateBoards(ServerLevel level) {
		AuraState state = AuraState.get(level.getServer());
		List<AuraState.ArenaRecord> duels = TrainingHall.top(state.duelRecords, r -> r.wins + r.losses == 0 ? 0 : r.wins * 1000.0 + 999 - Math.min(r.losses, 998));
		List<AuraState.ArenaRecord> dps = TrainingHall.top(state.dpsRecords, r -> r.best);
		set("board_duels", board("⚔ TOP DUELISTS", ChatFormatting.RED, duels, r -> r.wins + " W  ·  " + r.losses + " L"));
		set("board_weekly", com.toast.aurapoints.cosmetic.Weekly.board());
		set("board_waves", waveBoard(TrainingHall.top(state.waveRecords, r -> r.best), TrainingHall.top(state.partyWaveRecords, r -> r.best)));
		set("board_dps", board("★ DPS RECORDS", ChatFormatting.GOLD, dps, r -> String.format(java.util.Locale.ROOT, "%.1f DPS", r.best)));
	}

	/** Wave records: the best solo runs on top, the best party runs under them. */
	private static Component waveBoard(List<AuraState.ArenaRecord> solo, List<AuraState.ArenaRecord> party) {
		MutableComponent text = Component.literal("☠ WAVE RECORDS").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
		waveSection(text, "SOLO", solo, false);
		waveSection(text, "PARTY", party, true);
		return text;
	}

	private static void waveSection(MutableComponent text, String title, List<AuraState.ArenaRecord> list, boolean withParty) {
		text.append(Component.literal("\n" + title).withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD));
		for (int i = 0; i < 5; i++) {
			text.append(Component.literal("\n"));
			ChatFormatting rank = i == 0 ? ChatFormatting.GOLD : i == 1 ? ChatFormatting.WHITE : i == 2 ? ChatFormatting.RED : ChatFormatting.GRAY;
			text.append(Component.literal((i + 1) + ". ").withStyle(rank));
			if (i < list.size()) {
				var r = list.get(i);
				text.append(Component.literal(r.name).withStyle(ChatFormatting.YELLOW))
						.append(Component.literal("  wave " + (int) r.best + (withParty && !r.party.isEmpty() ? "  ·  " + shortParty(r) : "")).withStyle(ChatFormatting.GRAY));
			} else {
				text.append(Component.literal("-").withStyle(ChatFormatting.DARK_GRAY));
			}
		}
	}

	private static Component board(String title, ChatFormatting color, List<AuraState.ArenaRecord> list,
								   java.util.function.Function<AuraState.ArenaRecord, String> value) {
		MutableComponent text = Component.literal(title).withStyle(color, ChatFormatting.BOLD);
		text.append(Component.literal("\n"));
		for (int i = 0; i < 10; i++) {
			text.append(Component.literal("\n"));
			ChatFormatting rank = i == 0 ? ChatFormatting.GOLD : i == 1 ? ChatFormatting.WHITE : i == 2 ? ChatFormatting.RED : ChatFormatting.GRAY;
			text.append(Component.literal((i + 1) + ". ").withStyle(rank));
			if (i < list.size()) {
				text.append(Component.literal(list.get(i).name).withStyle(ChatFormatting.YELLOW))
						.append(Component.literal("  " + value.apply(list.get(i))).withStyle(ChatFormatting.GRAY));
			} else {
				text.append(Component.literal("-").withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		return text;
	}

	/** "with A, B" (without the record holder), cut to fit. */
	private static String shortParty(AuraState.ArenaRecord r) {
		List<String> others = new java.util.ArrayList<>(List.of(r.party.split(", ")));
		others.remove(r.name);
		String s = "with " + String.join(", ", others);
		return s.length() > 28 ? s.substring(0, 27) + "…" : s;
	}

	static void clearAll() {
		LIVE.clear();
	}
}
