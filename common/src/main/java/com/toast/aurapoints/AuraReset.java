package com.toast.aurapoints;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraStats;
import com.toast.aurapoints.ability.AuraType;
import net.minecraft.stats.Stats;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import net.minecraft.server.level.ServerPlayer;

/**
 * The two ways a player gets reset:
 *  - {@link #rokakaReset}: the Rokaka Fruit. The aura is cleared (the next
 *    roll is free again), bought abilities are removed and their Aura is
 *    refunded, both skill trees are reset and every spent skill point is refunded.
 *  - {@link #wipe}: the one-time login wipe (and first-login setup for new
 *    players). Nothing is refunded: aura, free roll, Aura balance, awakenings,
 *    mastery, cosmetics and weeklies go too (Trials/Feats: AuraStats.resetProgress).
 */
public final class AuraReset {

	private AuraReset() {
	}

	/** Aura + skill tree reset with refunds. The caller marks the AuraState dirty. */
	public static void rokakaReset(ServerPlayer player, AuraPlayerData data) {
		// bought abilities go too, and the Aura paid for them comes back
		if (data.shopSpent > 0) AuraCurrency.add(player, data.shopSpent);
		data.owned.clear();
		data.shopSpent = 0;
		data.aura = null;
		data.arenaRealAura = null;
		data.freeRollUsed = false;
		data.pvpUnlocked = false;
		AuraAbilities.refreshBerserkerModifier(player, false);

		// "skills reset" locks every skill again and hands the spent points back.
		PuffishBridge.resetSkills(player, AuraConfig.PUFFISH_CATEGORY_MAIN);
		PuffishBridge.resetSkills(player, AuraConfig.PUFFISH_CATEGORY_PVP);
		PuffishBridge.lockCategory(player, AuraConfig.PUFFISH_CATEGORY_PVP);
	}

	/** What /aura resetprogress can reset, one part at a time ({@code nuke} = all of them, see {@link #nuke}). */
	public enum Part {
		QUESTS("quests", "Trials/Feats counters, Aura advancements and the FTB quest book (the whole FTB team's)"),
		TRIALS("trials", "Trials/Feats counters and Aura advancements only (quest book kept)"),
		AURA("aura", "aura, free roll, held-auras record and blood bar; bought abilities refunded"),
		SKILLS("skills", "both skill trees (points refunded, PvP tree locked again)"),
		BALANCE("balance", "Aura balance set to 0"),
		MASTERY("mastery", "Mastery levels and XP, and branch picks"),
		AWAKENINGS("awakenings", "awakened auras (Ultimates)"),
		COSMETICS("cosmetics", "owned and equipped cosmetics"),
		WEEKLIES("weeklies", "weekly challenge progress and streaks"),
		TUTORIAL("tutorial", "Aura Book tutorial: offered again, free book given again");

		public final String id;
		public final String what;

		Part(String id, String what) {
			this.id = id;
			this.what = what;
		}
	}

	/** Resets one part. Returns a short note for the admin. The caller marks the AuraState dirty and syncs. */
	public static String reset(ServerPlayer player, AuraPlayerData data, Part part) {
		switch (part) {
			case QUESTS -> {
				int revoked = AuraStats.resetProgress(player, data, false);
				return "counters zeroed, " + revoked + " advancements revoked, quest book " + (resetQuestBook(player) ? "reset" : "NOT reset (FTB Quests missing or no team data, see log)");
			}
			case TRIALS -> {
				return "counters zeroed, " + AuraStats.resetProgress(player, data, false) + " advancements revoked";
			}
			case AURA -> {
				if (data.shopSpent > 0) AuraCurrency.add(player, data.shopSpent);
				int refund = data.shopSpent;
				data.owned.clear();
				data.shopSpent = 0;
				data.aura = null;
				data.arenaRealAura = null;
				data.freeRollUsed = false;
				data.rolled.clear();
				data.bloodBar = 0;
				AuraAbilities.refreshBerserkerModifier(player, false);
				return "aura cleared, " + refund + " Aura refunded";
			}
			case SKILLS -> {
				PuffishBridge.resetSkills(player, AuraConfig.PUFFISH_CATEGORY_MAIN);
				PuffishBridge.resetSkills(player, AuraConfig.PUFFISH_CATEGORY_PVP);
				PuffishBridge.lockCategory(player, AuraConfig.PUFFISH_CATEGORY_PVP);
				data.pvpUnlocked = false;
				return "skill trees reset";
			}
			case BALANCE -> {
				AuraCurrency.set(player, 0);
				return "balance 0";
			}
			case MASTERY -> {
				data.mastery.clear();
				data.branches.clear();
				for (AuraType t : AuraType.values()) {
					var stat = AuraStats.mastery(t);
					if (stat != null) player.getStats().setValue(player, Stats.CUSTOM.get(stat), 0);
				}
				return "mastery cleared";
			}
			case AWAKENINGS -> {
				int n = data.awakened.size();
				data.awakened.clear();
				return n + " awakenings removed";
			}
			case COSMETICS -> {
				data.cosmetics.clear();
				data.equipped.clear();
				return "cosmetics cleared";
			}
			case WEEKLIES -> {
				data.weeklyWeek = Long.MIN_VALUE;
				data.weeklySeed = 0;
				data.weeklyDone = 0;
				java.util.Arrays.fill(data.weeklyProgress, 0);
				java.util.Arrays.fill(data.weeklyBase, 0);
				data.weeklyCompleted = 0;
				data.weeklyFullWeeks = 0;
				return "weeklies cleared";
			}
			case TUTORIAL -> {
				data.tutorialState = com.toast.aurapoints.menu.Tutorial.OFFER;
				data.tutorialStep = 0;
				data.bookGiven = false;
				return "tutorial offered again (book on next login)";
			}
		}
		return "";
	}

	/** Everything: the login wipe, every counter and advancement, and the FTB quest book. Nothing is refunded. */
	public static String nuke(ServerPlayer player, AuraPlayerData data) {
		wipe(player, data);
		int revoked = AuraStats.resetProgress(player, data, true);
		return "wiped, " + revoked + " advancements revoked, quest book " + (resetQuestBook(player) ? "reset" : "NOT reset (FTB Quests missing or no team data, see log)");
	}

	/**
	 * Resets the player's FTB Quests progress (= /ftbquests change_progress reset-all, which only an op player in
	 * game may run). FTB keeps progress per team, so a party's progress goes for all of them. Reflection: no
	 * compile dependency on FTB Quests. False if it isn't installed or the player has no team data.
	 */
	public static boolean resetQuestBook(ServerPlayer player) {
		try {
			Class<?> fileClass = Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile");
			Object file = fileClass.getField("INSTANCE").get(null);
			if (file == null) return false;
			java.util.Optional<?> team = (java.util.Optional<?>) fileClass.getMethod("getTeamData", net.minecraft.world.entity.player.Player.class).invoke(file, player);
			if (team.isEmpty()) return false;
			Class<?> base = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObjectBase");
			Class<?> change = Class.forName("dev.ftb.mods.ftbquests.util.ProgressChange");
			Object c = change.getConstructor(base, java.util.UUID.class).newInstance(file, player.getUUID());
			change.getMethod("setReset", boolean.class).invoke(c, true);
			base.getMethod("forceProgress", Class.forName("dev.ftb.mods.ftbquests.quest.TeamData"), change).invoke(file, team.get(), c);
			return true;
		} catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
			AuraPoints.LOGGER.warn("Could not reset the FTB quest book of {}: {}", player.getGameProfile().getName(), e.toString());
			return false;
		}
	}

	/** One-time wipe on login; also runs for brand-new players so the PvP tree starts locked. */
	public static void wipe(ServerPlayer player, AuraPlayerData data) {
		data.owned.clear();
		data.shopSpent = 0;
		data.shopMigrated = true;
		data.aura = null;
		data.arenaRealAura = null;
		data.freeRollUsed = false;
		data.skillPointsBought = 0;
		data.pvpUnlocked = false;
		data.wipeVersion = AuraConfig.WIPE_VERSION;
		// newer systems (wipe 5): awakenings, mastery, held-auras record, cosmetics, weeklies
		data.awakened.clear();
		data.rolled.clear();
		data.mastery.clear();
		data.branches.clear();
		reset(player, data, Part.COSMETICS);
		reset(player, data, Part.WEEKLIES);
		AuraAbilities.refreshBerserkerModifier(player, false);
		AuraCurrency.set(player, 0);

		PuffishBridge.eraseCategory(player, AuraConfig.PUFFISH_CATEGORY_MAIN);
		PuffishBridge.eraseCategory(player, AuraConfig.PUFFISH_CATEGORY_PVP);
		PuffishBridge.lockCategory(player, AuraConfig.PUFFISH_CATEGORY_PVP);
	}
}
