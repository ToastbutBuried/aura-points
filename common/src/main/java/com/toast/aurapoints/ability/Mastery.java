package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;

/**
 * Aura Mastery I-V, one per aura. Using an aura earns its mastery XP (a custom stat, aurapoints:mastery_<aura>,
 * fed by every Aura Trials count and by duel rounds won); the levels themselves are handed out by the FTB
 * "Aura Mastery" quests, whose rewards run /aura mastery set. A level makes the aura's abilities look grander
 * (more particles as they go off, a flourish at your feet, gold at V), shortens its cooldowns a little and
 * puts stars next to the aura over your name. Levels are kept per aura, so rerolling loses nothing.
 */
public final class Mastery {

	private Mastery() {
	}

	public static final int MAX = 5;
	private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V"};

	public static String roman(int level) {
		return ROMAN[Math.max(0, Math.min(MAX, level))];
	}

	/** The mastery level of the aura this player holds now (0 without an aura). */
	public static int level(ServerPlayer player) {
		if (player.getServer() == null) return 0;
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		return data.aura == null ? 0 : data.mastery.getOrDefault(data.aura, 0);
	}

	public static int xp(ServerPlayer player, AuraType aura) {
		ResourceLocation stat = AuraStats.mastery(aura);
		return stat == null ? 0 : player.getStats().getValue(Stats.CUSTOM.get(stat));
	}

	/** The XP each level asks for (the quests check the same numbers; MASTERY_XP_LEVEL_1..5). */
	public static int xpFor(int level) {
		return switch (level) {
			case 1 -> AuraConfig.MASTERY_XP_LEVEL_1;
			case 2 -> AuraConfig.MASTERY_XP_LEVEL_2;
			case 3 -> AuraConfig.MASTERY_XP_LEVEL_3;
			case 4 -> AuraConfig.MASTERY_XP_LEVEL_4;
			default -> AuraConfig.MASTERY_XP_LEVEL_5;
		};
	}

	/** Mastery XP for the aura held now (AuraStats.add, duel rounds). */
	public static void addXp(ServerPlayer player, int amount) {
		if (!AuraConfig.MASTERY_ENABLED || amount <= 0 || player.getServer() == null) return;
		AuraType aura = AuraState.get(player.getServer()).getOrCreate(player.getUUID()).aura;
		ResourceLocation stat = aura == null ? null : AuraStats.mastery(aura);
		if (stat != null) player.awardStat(stat, amount);
	}

	// ---------------------------------------------------------------
	// What a level does
	// ---------------------------------------------------------------

	/** A cooldown, shortened by the mastery of the aura held now. */
	public static int cooldown(ServerPlayer player, int ticks) {
		if (!AuraConfig.MASTERY_ENABLED || ticks <= 0) return ticks;
		int level = level(player);
		if (level <= 0) return ticks;
		double cut = Math.max(0, Math.min(0.5, AuraConfig.MASTERY_COOLDOWN_CUT_PER_LEVEL * level));
		return Math.max(1, (int) Math.round(ticks * (1 - cut)));
	}

	/** How much more of an ability's particles there are while it goes off (1 = as is). AuraFx.particles applies it. */
	public static float particleBoost(ServerPlayer player) {
		if (!AuraConfig.MASTERY_ENABLED) return 1f;
		return 1f + (float) Math.max(0, AuraConfig.MASTERY_PARTICLE_BOOST_PER_LEVEL) * level(player);
	}

	/** An ability was used: a flourish at the feet in the aura's colors, rising with the level; gold sparks at V. */
	public static void onAbilityUsed(ServerPlayer player) {
		if (!AuraConfig.MASTERY_ENABLED || player.isSpectator() || player.isInvisible()) return;
		int level = level(player);
		if (level <= 0 || player.getServer() == null) return;
		AuraType aura = AuraState.get(player.getServer()).getOrCreate(player.getUUID()).aura;
		if (aura == null) return;
		var lvl = player.serverLevel();
		AuraFx.ring(lvl, player.getX(), player.getY() + 0.1, player.getZ(), 0.6 + 0.08 * level, AuraFx.dust(aura.rgb, 1.0f), 8 + level * 4);
		if (level >= 3) AuraFx.ring(lvl, player.getX(), player.getY() + 0.5, player.getZ(), 0.45, AuraFx.dust(aura.rgb, 0.8f), 8);
		if (level >= MAX) {
			AuraFx.particles(lvl, AuraFx.dust(0xFFC83D, 1.2f), player.getX(), player.getY() + 1.0, player.getZ(), 10, 0.45, 0.6, 0.45, 0);
			AuraFx.particles(lvl, ParticleTypes.WAX_ON, player.getX(), player.getY() + 1.0, player.getZ(), 4, 0.4, 0.6, 0.4, 0.3);
		}
	}

	// ---------------------------------------------------------------
	// Setting a level (/aura mastery set - the quests' reward)
	// ---------------------------------------------------------------

	/** Sets the level; going up plays the moment. Returns the old level. */
	public static int set(ServerPlayer player, AuraType aura, int level) {
		MinecraftServer server = player.getServer();
		if (server == null) return 0;
		AuraState state = AuraState.get(server);
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		int old = data.mastery.getOrDefault(aura, 0);
		level = Math.max(0, Math.min(MAX, level));
		if (level == 0) data.mastery.remove(aura);
		else data.mastery.put(aura, level);
		state.markDirty();
		if (aura == data.aura) NetworkHandler.syncAura(player, data);
		else com.toast.aurapoints.AuraHooks.syncMastery(player, data);
		if (level > old) celebrate(player, aura, level);
		return old;
	}

	private static void celebrate(ServerPlayer player, AuraType aura, int level) {
		MutableComponent title = Component.literal("✦ ").withStyle(ChatFormatting.GOLD)
				.append(AuraLabel.of(aura, aura.displayName + " Mastery " + roman(level)))
				.append(Component.literal(" ✦").withStyle(ChatFormatting.GOLD));
		AuraFx.title(player, title, Component.literal(level >= MAX ? "Mastered!" : "Your " + aura.displayName + " abilities grow stronger")
				.withStyle(level >= MAX ? ChatFormatting.GOLD : ChatFormatting.GRAY));
		AuraFx.helix(player, AuraFx.dust(aura.rgb, 1.4f), 40, 1.0, 2.6);
		if (level >= MAX) AuraFx.helix(player, AuraFx.dust(0xFFC83D, 1.2f), 40, 1.3, 2.8);
		AuraFx.sound(player.serverLevel(), player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1f);
		AuraFx.sound(player.serverLevel(), player, SoundEvents.PLAYER_LEVELUP, 0.6f, 0.8f + level * 0.1f);
		if (level >= MAX && player.getServer() != null) {
			player.getServer().getPlayerList().broadcastSystemMessage(Component.literal("✦ " + player.getGameProfile().getName() + " has mastered ")
					.withStyle(ChatFormatting.GOLD).append(AuraLabel.of(aura)).append(Component.literal("!").withStyle(ChatFormatting.GOLD)), false);
		}
	}

	// ---------------------------------------------------------------
	// /aura mastery
	// ---------------------------------------------------------------

	/** The player's mastery, aura by aura: the one held now first, then any other with a level or XP. */
	public static void show(ServerPlayer player) {
		AuraPlayerData data = AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		player.sendSystemMessage(Component.literal("━━━━━━ ✦ AURA MASTERY ✦ ━━━━━━").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		boolean any = false;
		for (AuraType aura : AuraType.values()) {
			int level = data.mastery.getOrDefault(aura, 0), xp = xp(player, aura);
			boolean held = aura == data.aura;
			if (!held && level == 0 && xp == 0) continue;
			any = true;
			MutableComponent line = Component.literal(held ? " ▶ " : "   ").withStyle(ChatFormatting.YELLOW).append(AuraLabel.of(aura))
					.append(Component.literal("  " + stars(level)).withStyle(ChatFormatting.GOLD));
			line.append(Component.literal(level >= MAX ? "  " + xp + " XP - mastered"
					: "  " + xp + " / " + xpFor(level + 1) + " XP for " + roman(level + 1)).withStyle(ChatFormatting.GRAY));
			player.sendSystemMessage(line);
		}
		if (!any) player.sendSystemMessage(Component.literal("   Roll an aura to start mastering it: /aura roll").withStyle(ChatFormatting.GRAY));
		player.sendSystemMessage(Component.literal("   Earn XP by using your aura; claim each level in the quest book (Aura Mastery).").withStyle(ChatFormatting.DARK_GRAY));
	}

	/** "✦✦✦☆☆" */
	public static String stars(int level) {
		return "✦".repeat(Math.max(0, level)) + "☆".repeat(Math.max(0, MAX - level));
	}
}
