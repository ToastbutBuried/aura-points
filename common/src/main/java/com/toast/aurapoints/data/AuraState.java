package com.toast.aurapoints.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * World-level persistent storage for every player's {@link AuraPlayerData}.
 * Stored once on the overworld's DimensionDataStorage so it survives
 * restarts and isn't tied to any single dimension.
 *
 * This is a FRESH data format (not compatible with the old Fabric mod's
 * "aurapoints_player_data" PersistentState file) - the file name below is
 * new on purpose, per the decision to start fresh rather than migrate.
 */
public class AuraState extends SavedData {

	private static final String ID = "aurapoints_player_data_v2";

	private final Map<UUID, AuraPlayerData> playerData = new HashMap<>();

	/** /aura cooldowns off: every ability cooldown is skipped, for everyone. Saved. */
	public boolean cooldownsDisabled = false;

	/** /aura lock all: every player's aura is switched off. Saved; mirrored in AuraAbilities.allLocked. */
	public boolean allLocked = false;

	/** The arena map version built into the arena dimension (0 = not built yet); see arena/ArenaBuilder. */
	public int arenaMapVersion = 0;

	/** Counts arena (re)builds; the entities of a build are tagged with it (arena/ArenaBuilder.isStale). */
	public int arenaBuildId = 0;

	/** A player's line on an arena board: their name then, and the numbers (DPS best; duel wins and losses). */
	public static final class ArenaRecord {
		public String name;
		public double best;
		public int wins, losses;
		/** Wave Survival: who the best run was with. */
		public String party = "";

		public ArenaRecord(String name) {
			this.name = name;
		}
	}

	/** Training Hall DPS challenge bests and duel results, per player (shown on the arena's sign boards). */
	public final Map<UUID, ArenaRecord> dpsRecords = new HashMap<>();
	public final Map<UUID, ArenaRecord> duelRecords = new HashMap<>();
	/** Wave Survival: best wave cleared (best), runs (wins), with whom (party). */
	public final Map<UUID, ArenaRecord> waveRecords = new HashMap<>();
	/** Wave Survival with two or more players: its own board, so solo and party records stay apart (waveRecords = solo). */
	public final Map<UUID, ArenaRecord> partyWaveRecords = new HashMap<>();

	public static AuraState create() {
		return new AuraState();
	}

	public static AuraState load(CompoundTag tag, HolderLookup.Provider registries) {
		AuraState state = new AuraState();
		state.cooldownsDisabled = tag.getBoolean("cooldownsDisabled");
		state.allLocked = tag.getBoolean("allLocked");
		state.arenaMapVersion = tag.getInt("arenaMapVersion");
		state.arenaBuildId = tag.getInt("arenaBuildId");
		readRecords(tag.getList("dpsRecords", CompoundTag.TAG_COMPOUND), state.dpsRecords);
		readRecords(tag.getList("duelRecords", CompoundTag.TAG_COMPOUND), state.duelRecords);
		readRecords(tag.getList("waveRecords", CompoundTag.TAG_COMPOUND), state.waveRecords);
		readRecords(tag.getList("partyWaveRecords", CompoundTag.TAG_COMPOUND), state.partyWaveRecords);
		// before 5.8.0 one board held both: a best that came from a party moves over
		for (var it = state.waveRecords.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (e.getValue().party.contains(", ") && !state.partyWaveRecords.containsKey(e.getKey())) {
				state.partyWaveRecords.put(e.getKey(), e.getValue());
				it.remove();
			}
		}
		ListTag list = tag.getList("players", CompoundTag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompound(i);
			UUID id = entry.getUUID("uuid");
			AuraPlayerData data = AuraPlayerData.readNbt(entry.getCompound("data"));
			state.playerData.put(id, data);
		}
		return state;
	}

	/** The state of the running server, looked up once (it's asked for many times every tick). */
	private static volatile MinecraftServer cachedServer;
	private static volatile AuraState cached;

	public static AuraState get(MinecraftServer server) {
		AuraState state = cached;
		if (state != null && cachedServer == server) return state;
		ServerLevel overworld = server.overworld();
		state = overworld.getDataStorage().computeIfAbsent(
				new SavedData.Factory<>(AuraState::create, AuraState::load, null),
				ID
		);
		cached = state;
		cachedServer = server;
		return state;
	}

	/** The server is stopping: the next one (single player) loads its own. */
	public static void forget() {
		cached = null;
		cachedServer = null;
	}

	public AuraPlayerData getOrCreate(UUID playerId) {
		return playerData.computeIfAbsent(playerId, id -> new AuraPlayerData());
	}

	/** True if this player has saved aura data (getOrCreate would not make a new entry). */
	public boolean has(UUID playerId) {
		return playerData.containsKey(playerId);
	}

	/** Every player with saved aura data. */
	public java.util.Set<UUID> playerIds() {
		return java.util.Collections.unmodifiableSet(playerData.keySet());
	}

	/** Call after any mutation to a returned {@link AuraPlayerData} to ensure it gets saved. */
	public void markDirty() {
		this.setDirty();
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		for (Map.Entry<UUID, AuraPlayerData> entry : playerData.entrySet()) {
			CompoundTag wrapper = new CompoundTag();
			wrapper.putUUID("uuid", entry.getKey());
			wrapper.put("data", entry.getValue().writeNbt());
			list.add(wrapper);
		}
		tag.put("players", list);
		tag.putBoolean("cooldownsDisabled", cooldownsDisabled);
		tag.putBoolean("allLocked", allLocked);
		tag.putInt("arenaMapVersion", arenaMapVersion);
		tag.putInt("arenaBuildId", arenaBuildId);
		tag.put("dpsRecords", writeRecords(dpsRecords));
		tag.put("duelRecords", writeRecords(duelRecords));
		tag.put("waveRecords", writeRecords(waveRecords));
		tag.put("partyWaveRecords", writeRecords(partyWaveRecords));
		return tag;
	}

	private static ListTag writeRecords(Map<UUID, ArenaRecord> records) {
		ListTag list = new ListTag();
		for (Map.Entry<UUID, ArenaRecord> e : records.entrySet()) {
			CompoundTag r = new CompoundTag();
			r.putUUID("uuid", e.getKey());
			r.putString("name", e.getValue().name);
			r.putDouble("best", e.getValue().best);
			r.putInt("wins", e.getValue().wins);
			r.putInt("losses", e.getValue().losses);
			if (!e.getValue().party.isEmpty()) r.putString("party", e.getValue().party);
			list.add(r);
		}
		return list;
	}

	private static void readRecords(ListTag list, Map<UUID, ArenaRecord> into) {
		for (int i = 0; i < list.size(); i++) {
			CompoundTag r = list.getCompound(i);
			ArenaRecord record = new ArenaRecord(r.getString("name"));
			record.best = r.getDouble("best");
			record.wins = r.getInt("wins");
			record.losses = r.getInt("losses");
			record.party = r.getString("party");
			into.put(r.getUUID("uuid"), record);
		}
	}
}
