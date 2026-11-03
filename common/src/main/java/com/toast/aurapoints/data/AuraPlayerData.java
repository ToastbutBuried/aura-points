package com.toast.aurapoints.data;

import com.toast.aurapoints.ability.AuraType;
import net.minecraft.nbt.CompoundTag;

/**
 * Persistent per-player state. One instance per player, keyed by UUID in
 * {@link AuraState}. The aura is saved by NAME (not ordinal) so adding or
 * removing auras later never scrambles saved data. Old-format saves are
 * ignored; the one-time login wipe resets them anyway.
 *
 * The Aura balance is NOT here: it lives on the scoreboard so datapacks can
 * read and change it (see AuraCurrency).
 */
public class AuraPlayerData {

	/** The rolled aura, or null before the first roll. */
	public AuraType aura = null;

	/** True once the free first roll has been used. */
	public boolean freeRollUsed = false;

	/** Skill points bought with /aura redeem so far; drives the rising price. */
	public int skillPointsBought = 0;

	/** True once a boss (#aurapoints:bosses) has been defeated and the PvP tree unlocked. */
	public boolean pvpUnlocked = false;

	/** Last wipe version this player went through (see AuraConfig.WIPE_VERSION). */
	public int wipeVersion = 0;

	/** /aura selfhide: also hide yourself (third person) while Shadow-cloaked. */
	public boolean selfHide = false;

	/** Active abilities bought in /aura shop (passives need no buying). */
	public final java.util.Set<com.toast.aurapoints.ability.AuraAbilities.Ability> owned =
			java.util.EnumSet.noneOf(com.toast.aurapoints.ability.AuraAbilities.Ability.class);

	/** Aura spent in the shop, refunded by the Rokaka Fruit. */
	public int shopSpent = 0;

	/** One-time: players who had an aura before the shop existed get its abilities for free. */
	public boolean shopMigrated = false;

	/** The newest changelog version this player has seen with /changelog ("" = never looked). */
	public String changelogSeen = "";

	/** Verdant's Bloom: on unless the player switched it off with /aura bloom. */
	public boolean bloomOn = true;

	/** Shadow's Nightstalker: Night Vision on unless the player switched it off with /aura nightvision. */
	public boolean nightVisionOn = true;

	/** /aura lock: an admin switched this player's aura off - no actives, no passives - until /aura unlock. */
	public boolean locked = false;

	/**
	 * Where /aura arena took this player from (dimension id, position, look, game mode id), to put them back on
	 * /aura arena leave. "" = not in the arena (or got there some other way).
	 */
	public String arenaReturnDim = "";
	public double arenaReturnX, arenaReturnY, arenaReturnZ;
	public float arenaReturnYaw, arenaReturnPitch;
	public int arenaReturnMode = 0;

	/** Training grounds: true = this player switched their cooldowns back on there (off by default). */
	public boolean trainingCooldowns = false;

	/** Auras whose Awakening this player completed: their Ultimate is unlocked (ability/Ultimates). Saved by name. */
	public final java.util.Set<AuraType> awakened = java.util.EnumSet.noneOf(AuraType.class);

	/** Every aura this player has held (recorded from 4.98 on; arena/WaveAuras also counts older evidence). Saved by name. */
	public final java.util.Set<AuraType> rolled = java.util.EnumSet.noneOf(AuraType.class);

	/** Wave Survival aura switch: the real aura while a borrowed one is held (null = none borrowed), see arena/WaveAuras. */
	public AuraType arenaRealAura = null;

	/** Aura Mastery level per aura (ability/Mastery), kept when rerolling. Saved by aura name. */
	public final java.util.Map<AuraType, Integer> mastery = new java.util.EnumMap<>(AuraType.class);

	/** Cosmetics owned (cosmetic/Cosmetics ids) and the one worn per kind (kind id -> cosmetic id). */
	public final java.util.Set<String> cosmetics = new java.util.LinkedHashSet<>();
	public final java.util.Map<String, String> equipped = new java.util.HashMap<>();

	/** Weekly challenges (cosmetic/Weekly): the week (its Monday's epoch day) and seed these are for, done bits, counters, stat baselines. */
	public long weeklyWeek = Long.MIN_VALUE;
	public int weeklySeed = 0;
	public int weeklyDone = 0;
	public final int[] weeklyProgress = new int[3];
	public final int[] weeklyBase = new int[3];
	/** Lifetime: weekly challenges finished, and weeks with all three done. */
	public int weeklyCompleted = 0;
	public int weeklyFullWeeks = 0;

	/** Aura Book tutorial (menu/Tutorial): OFFER 0, STARTED 1, DONE 2, DISMISSED 3, and the current step. */
	public int tutorialState = 0;
	public int tutorialStep = 0;
	/** Not saved: seconds since the roll step's aura arrived (lets the reveal play). */
	public int tutorialStepTicks = 0;
	/** The free Aura Book was handed out (first join after 5.1.0). */
	public boolean bookGiven = false;

	public CompoundTag writeNbt() {
		CompoundTag nbt = new CompoundTag();
		if (aura != null) nbt.putString("aura", aura.name());
		nbt.putBoolean("freeRollUsed", freeRollUsed);
		nbt.putInt("skillPointsBought", skillPointsBought);
		nbt.putBoolean("pvpUnlocked", pvpUnlocked);
		nbt.putInt("wipeVersion", wipeVersion);
		nbt.putBoolean("selfHide", selfHide);
		net.minecraft.nbt.ListTag ownedList = new net.minecraft.nbt.ListTag();
		for (var a : owned) ownedList.add(net.minecraft.nbt.StringTag.valueOf(a.name()));
		nbt.put("owned", ownedList);
		nbt.putInt("shopSpent", shopSpent);
		nbt.putBoolean("shopMigrated", shopMigrated);
		nbt.putString("changelogSeen", changelogSeen);
		nbt.putBoolean("bloomOff", !bloomOn);
		nbt.putBoolean("nightVisionOff", !nightVisionOn);
		nbt.putBoolean("locked", locked);
		nbt.putInt("tutorialState", tutorialState);
		nbt.putInt("tutorialStep", tutorialStep);
		nbt.putBoolean("bookGiven", bookGiven);
		if (!arenaReturnDim.isEmpty()) {
			CompoundTag back = new CompoundTag();
			back.putString("dim", arenaReturnDim);
			back.putDouble("x", arenaReturnX);
			back.putDouble("y", arenaReturnY);
			back.putDouble("z", arenaReturnZ);
			back.putFloat("yaw", arenaReturnYaw);
			back.putFloat("pitch", arenaReturnPitch);
			back.putInt("mode", arenaReturnMode);
			nbt.put("arenaReturn", back);
		}
		nbt.putBoolean("trainingCooldowns", trainingCooldowns);
		CompoundTag masteryTag = new CompoundTag();
		for (var e : mastery.entrySet()) masteryTag.putInt(e.getKey().name(), e.getValue());
		nbt.put("mastery", masteryTag);
		net.minecraft.nbt.ListTag awakenedList = new net.minecraft.nbt.ListTag();
		for (AuraType a : awakened) awakenedList.add(net.minecraft.nbt.StringTag.valueOf(a.name()));
		nbt.put("awakened", awakenedList);
		net.minecraft.nbt.ListTag rolledList = new net.minecraft.nbt.ListTag();
		for (AuraType a : rolled) rolledList.add(net.minecraft.nbt.StringTag.valueOf(a.name()));
		nbt.put("rolled", rolledList);
		if (arenaRealAura != null) nbt.putString("arenaRealAura", arenaRealAura.name());
		net.minecraft.nbt.ListTag cosmeticList = new net.minecraft.nbt.ListTag();
		for (String c : cosmetics) cosmeticList.add(net.minecraft.nbt.StringTag.valueOf(c));
		nbt.put("cosmetics", cosmeticList);
		CompoundTag equippedTag = new CompoundTag();
		for (var e : equipped.entrySet()) equippedTag.putString(e.getKey(), e.getValue());
		nbt.put("equipped", equippedTag);
		CompoundTag weekly = new CompoundTag();
		weekly.putLong("week", weeklyWeek);
		weekly.putInt("seed", weeklySeed);
		weekly.putInt("done", weeklyDone);
		weekly.putIntArray("progress", weeklyProgress);
		weekly.putIntArray("base", weeklyBase);
		weekly.putInt("completed", weeklyCompleted);
		weekly.putInt("fullWeeks", weeklyFullWeeks);
		nbt.put("weekly", weekly);
		return nbt;
	}

	public static AuraPlayerData readNbt(CompoundTag nbt) {
		AuraPlayerData data = new AuraPlayerData();
		data.freeRollUsed = nbt.getBoolean("freeRollUsed");
		data.skillPointsBought = nbt.getInt("skillPointsBought");
		data.pvpUnlocked = nbt.getBoolean("pvpUnlocked");
		data.wipeVersion = nbt.getInt("wipeVersion");
		data.selfHide = nbt.getBoolean("selfHide");
		data.locked = nbt.getBoolean("locked");
		data.tutorialState = nbt.getInt("tutorialState");
		data.tutorialStep = nbt.getInt("tutorialStep");
		data.bookGiven = nbt.getBoolean("bookGiven");
		data.shopSpent = nbt.getInt("shopSpent");
		data.shopMigrated = nbt.getBoolean("shopMigrated");
		data.changelogSeen = nbt.getString("changelogSeen");
		data.bloomOn = !nbt.getBoolean("bloomOff");
		data.nightVisionOn = !nbt.getBoolean("nightVisionOff");
		if (nbt.contains("arenaReturn")) {
			CompoundTag back = nbt.getCompound("arenaReturn");
			data.arenaReturnDim = back.getString("dim");
			data.arenaReturnX = back.getDouble("x");
			data.arenaReturnY = back.getDouble("y");
			data.arenaReturnZ = back.getDouble("z");
			data.arenaReturnYaw = back.getFloat("yaw");
			data.arenaReturnPitch = back.getFloat("pitch");
			data.arenaReturnMode = back.getInt("mode");
		}
		data.trainingCooldowns = nbt.getBoolean("trainingCooldowns");
		var awakenedList = nbt.getList("awakened", net.minecraft.nbt.Tag.TAG_STRING);
		for (int i = 0; i < awakenedList.size(); i++) {
			try {
				data.awakened.add(AuraType.valueOf(awakenedList.getString(i)));
			} catch (IllegalArgumentException ignored) {
				// aura no longer exists
			}
		}
		CompoundTag masteryTag = nbt.getCompound("mastery");
		for (String key : masteryTag.getAllKeys()) {
			try {
				int level = masteryTag.getInt(key);
				if (level > 0) data.mastery.put(AuraType.valueOf(key), Math.min(5, level));
			} catch (IllegalArgumentException ignored) {
				// aura no longer exists
			}
		}
		var cosmeticList = nbt.getList("cosmetics", net.minecraft.nbt.Tag.TAG_STRING);
		for (int i = 0; i < cosmeticList.size(); i++) data.cosmetics.add(cosmeticList.getString(i));
		CompoundTag equippedTag = nbt.getCompound("equipped");
		for (String key : equippedTag.getAllKeys()) data.equipped.put(key, equippedTag.getString(key));
		if (nbt.contains("weekly")) {
			CompoundTag weekly = nbt.getCompound("weekly");
			data.weeklyWeek = weekly.getLong("week");
			data.weeklySeed = weekly.getInt("seed");
			data.weeklyDone = weekly.getInt("done");
			int[] progress = weekly.getIntArray("progress"), base = weekly.getIntArray("base");
			System.arraycopy(progress, 0, data.weeklyProgress, 0, Math.min(progress.length, data.weeklyProgress.length));
			System.arraycopy(base, 0, data.weeklyBase, 0, Math.min(base.length, data.weeklyBase.length));
			data.weeklyCompleted = weekly.getInt("completed");
			data.weeklyFullWeeks = weekly.getInt("fullWeeks");
		}
		var ownedList = nbt.getList("owned", net.minecraft.nbt.Tag.TAG_STRING);
		for (int i = 0; i < ownedList.size(); i++) {
			try {
				data.owned.add(com.toast.aurapoints.ability.AuraAbilities.Ability.valueOf(ownedList.getString(i)));
			} catch (IllegalArgumentException ignored) {
				// ability no longer exists
			}
		}
		var rolledList = nbt.getList("rolled", net.minecraft.nbt.Tag.TAG_STRING);
		for (int i = 0; i < rolledList.size(); i++) {
			try {
				data.rolled.add(AuraType.valueOf(rolledList.getString(i)));
			} catch (IllegalArgumentException ignored) {
			}
		}
		if (nbt.contains("arenaRealAura")) {
			try {
				data.arenaRealAura = AuraType.valueOf(nbt.getString("arenaRealAura"));
			} catch (IllegalArgumentException ignored) {
			}
		}
		if (nbt.contains("aura")) {
			try {
				data.aura = AuraType.valueOf(nbt.getString("aura"));
			} catch (IllegalArgumentException ignored) {
				// aura no longer exists - the player rolls again
			}
		}
		return data;
	}
}
