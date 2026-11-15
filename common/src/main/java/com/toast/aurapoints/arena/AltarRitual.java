package com.toast.aurapoints.arena;

import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.ability.AuraLabel;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Branch;
import com.toast.aurapoints.ability.Mastery;
import com.toast.aurapoints.ability.Ultimates;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.FxPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;

import com.mojang.math.Transformation;
import com.toast.aurapoints.mixin.DisplayAccessor;
import com.toast.aurapoints.mixin.ItemDisplayAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The awakening altar (5.26): an island east of the arena lobby (ArenaBuilder.buildAltar) with a pillar per aura. An
 * aura's Awakening quest unlocks its ritual ({@link #unlock}); the ritual ({@link #begin}) asks for ULT_ALTAR_COST
 * Aura and ULT_ALTAR_MASTERY of the aura held, awakens it at once (saved before the show, so a disconnect loses
 * nothing) and plays about 20 s: the player is held on the dais, the pillars pour their light in, the aura shows
 * itself, the player rises into a beam, then the reveal. The ritual's player watches it through a cinematic camera
 * (FxPacket.RITUAL -> client/RitualCutscene); everyone near sees the world side. One ritual at a time.
 *
 * Phases (ticks): APPROACH 0, GATHER 60, VISION 160, ASCEND 260, REVEAL 340, END 400 - client/RitualCutscene keeps
 * the same numbers for its camera.
 */
public final class AltarRitual {

	private AltarRitual() {
	}

	public static final int GATHER = 60, VISION = 160, ASCEND = 260, REVEAL = 340, END = 400;

	/** The auras with a pillar (every aura that has an Ultimate), in pillar order. */
	public static List<AuraType> auras() {
		List<AuraType> out = new ArrayList<>();
		for (AuraType a : AuraType.values()) if (Ultimates.of(a) != null) out.add(a);
		return out;
	}

	/** Where pillar {@code i} of {@code n} stands (block centre, floor level), in a ring round the dais. */
	static Vec3 pillar(int i, int n) {
		// half a step off the axes: the way in from the bridge (west) runs between two pillars
		double a = 2 * Math.PI * (i + 0.5) / n;
		return new Vec3(Arena.ALTAR_X + 0.5 + Math.round(Math.cos(a) * (Arena.ALTAR_R - 1.5)), Arena.FLOOR + 1, 0.5 + Math.round(Math.sin(a) * (Arena.ALTAR_R - 1.5)));
	}

	private static final class Run {
		final UUID id;
		final AuraType aura;
		final boolean replay;
		int t;
		Display.ItemDisplay sigil, core;
		Display.ItemDisplay[] caps;
		double sigilYaw, coreYaw;

		Run(UUID id, AuraType aura, boolean replay) {
			this.id = id;
			this.aura = aura;
			this.replay = replay;
		}
	}

	private static Run run;

	public static boolean running() {
		return run != null;
	}

	public static boolean inRitual(ServerPlayer player) {
		return run != null && run.id.equals(player.getUUID());
	}

	// ---------------------------------------------------------------
	// Unlocking, the menu
	// ---------------------------------------------------------------

	/** The Awakening quest's reward: the aura's ritual is open (or closed again). Returns whether it changed. */
	public static boolean unlock(ServerPlayer player, AuraType aura, boolean on) {
		MinecraftServer server = player.getServer();
		if (server == null || Ultimates.of(aura) == null) return false;
		AuraState state = AuraState.get(server);
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		boolean changed = on ? data.altarUnlocked.add(aura) : data.altarUnlocked.remove(aura);
		if (!changed) return false;
		state.markDirty();
		if (on && !data.awakened.contains(aura)) {
			player.sendSystemMessage(Component.literal("✦ The Awakening Altar answers to your ").withStyle(ChatFormatting.GOLD).append(AuraLabel.of(aura))
					.append(Component.literal(" aura. Go to the altar in the Aura Arena (east of the lobby) for the ritual: "
							+ AuraConfig.ULT_ALTAR_COST + " Aura and Mastery " + Mastery.roman(AuraConfig.ULT_ALTAR_MASTERY) + ".").withStyle(ChatFormatting.GOLD)));
			player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0f, 1.2f);
		}
		return true;
	}

	private static Component check(boolean ok, String text) {
		return Component.literal(ok ? "  ✔ " : "  ✘ ").withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED)
				.append(Component.literal(text).withStyle(ok ? ChatFormatting.GRAY : ChatFormatting.WHITE));
	}

	private static MutableComponent button(String text, ChatFormatting color, String command, String hover) {
		return Component.literal(text).withStyle(s -> s.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
	}

	/** The altar's keeper (or /aura altar): what the ritual asks of you for the aura you hold, and the button. */
	public static void menu(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
		player.sendSystemMessage(Component.literal("━━━━━ ✦ AWAKENING ALTAR ✦ ━━━━━").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		AuraType aura = data.aura;
		if (aura == null) {
			player.sendSystemMessage(Component.literal("  You have no aura yet.").withStyle(ChatFormatting.GRAY));
			return;
		}
		Ability ult = Ultimates.of(aura, Branch.current(data));
		if (ult == null) {
			player.sendSystemMessage(Component.literal("  ").append(AuraLabel.of(aura)).append(Component.literal(" has no Ultimate to awaken (yet).").withStyle(ChatFormatting.GRAY)));
			return;
		}
		if (data.awakened.contains(aura)) {
			player.sendSystemMessage(Component.literal("  ").append(AuraLabel.of(aura)).append(Component.literal(" is awakened: " + ult.displayName + ".  ").withStyle(ChatFormatting.YELLOW))
					.append(button("[Watch the ritual again]", ChatFormatting.AQUA, "/aura altar replay", "Free; sneak skips it")));
			return;
		}
		int mastery = data.mastery.getOrDefault(aura, 0), have = AuraCurrency.get(player);
		player.sendSystemMessage(Component.literal("  The ritual awakens ").withStyle(ChatFormatting.YELLOW).append(AuraLabel.of(aura))
				.append(Component.literal(": " + ult.displayName + ".").withStyle(ChatFormatting.YELLOW)));
		player.sendSystemMessage(check(data.altarUnlocked.contains(aura), aura.displayName + " Awakening quest (Aura Mastery chapter of the quest book)"));
		player.sendSystemMessage(check(mastery >= AuraConfig.ULT_ALTAR_MASTERY, "Mastery " + Mastery.roman(AuraConfig.ULT_ALTAR_MASTERY) + " (you: " + Mastery.roman(mastery) + ")"));
		player.sendSystemMessage(check(have >= AuraConfig.ULT_ALTAR_COST, AuraConfig.ULT_ALTAR_COST + " Aura (you: " + have + ")"));
		String why = refusal(player, data, aura, false);
		if (why == null) {
			player.sendSystemMessage(Component.literal("  ").append(button("[Begin the ritual]", ChatFormatting.GOLD, "/aura altar ritual",
					"Costs " + AuraConfig.ULT_ALTAR_COST + " Aura. About 20 seconds; you can't move while it runs.")));
		}
	}

	/** Why the ritual can't start now (null = it can). */
	private static String refusal(ServerPlayer player, AuraPlayerData data, AuraType aura, boolean replay) {
		if (aura == null) return "You have no aura yet.";
		if (Ultimates.of(aura, Branch.current(data)) == null) return aura.displayName + " has no Ultimate to awaken (yet).";
		if (replay) {
			if (!data.awakened.contains(aura)) return "Your " + aura.displayName + " aura isn't awakened yet.";
		} else {
			if (data.awakened.contains(aura)) return "Your " + aura.displayName + " aura is awakened already (watch it again: /aura altar replay).";
			if (!data.altarUnlocked.contains(aura)) return "The altar doesn't answer yet: finish the " + aura.displayName + " Awakening quest first.";
			if (data.mastery.getOrDefault(aura, 0) < AuraConfig.ULT_ALTAR_MASTERY) return "The ritual asks for " + aura.displayName + " Mastery " + Mastery.roman(AuraConfig.ULT_ALTAR_MASTERY) + ".";
			if (AuraCurrency.get(player) < AuraConfig.ULT_ALTAR_COST) return "The ritual costs " + AuraConfig.ULT_ALTAR_COST + " Aura (you have " + AuraCurrency.get(player) + ").";
		}
		if (!Arena.in(player) || player.position().distanceTo(Arena.ALTAR) > Arena.ALTAR_R + 3) return "Come to the Awakening Altar in the Aura Arena (east of the lobby).";
		if (Duel.holdsStill(player) || data.arenaRealAura != null) return "Not in the middle of a match.";
		if (run != null) return "A ritual is under way - wait for it to end.";
		return null;
	}

	// ---------------------------------------------------------------
	// The ritual
	// ---------------------------------------------------------------

	/** Starts the ritual for the aura the player holds ({@code replay}: free, for an awakened aura). Returns the refusal, or null when it began. */
	public static String begin(ServerPlayer player, boolean replay) {
		MinecraftServer server = player.getServer();
		if (server == null) return "No server.";
		AuraState state = AuraState.get(server);
		AuraPlayerData data = state.getOrCreate(player.getUUID());
		AuraType aura = data.aura;
		String why = refusal(player, data, aura, replay);
		if (why != null) return why;
		if (!replay) {
			if (!AuraCurrency.take(player, AuraConfig.ULT_ALTAR_COST)) return "The ritual costs " + AuraConfig.ULT_ALTAR_COST + " Aura.";
			// saved now: whatever happens to the show, the awakening is yours
			data.awakened.add(aura);
			state.markDirty();
			NetworkHandler.syncOwned(player, data);
		}
		start(player, aura, replay);
		return null;
	}

	/** The show itself, no checks and no cost (begin and the dev command). */
	public static void start(ServerPlayer player, AuraType aura, boolean replay) {
		if (run != null) end(player.getServer(), true);
		run = new Run(player.getUUID(), aura, replay);
		ServerLevel level = player.serverLevel();
		player.stopRiding();
		// on the dais, facing the lobby (west)
		player.teleportTo(level, Arena.ALTAR.x, Arena.ALTAR.y, Arena.ALTAR.z, 90f, 0f);
		Ultimates.hold(player);
		Ultimates.move(player, Vec3.ZERO);
		Ability ult = Ultimates.of(aura, Branch.current(player));
		NetworkHandler.sendFx(player, FxPacket.RITUAL, END, String.format(Locale.ROOT, "%s;%s;%d;%.2f;%.2f;%.2f",
				aura.id(), ult == null ? "" : ult.displayName, replay ? 1 : 0, Arena.ALTAR.x, Arena.ALTAR.y, Arena.ALTAR.z));
		sound(level, Arena.ALTAR, SoundEvents.BEACON_ACTIVATE, 1.6f, 0.5f);
		sound(level, Arena.ALTAR, SoundEvents.WARDEN_HEARTBEAT, 2.0f, 0.6f);
		spawnProps(level, run);
	}

	/** Sneak during a replay: straight to the end. */
	public static boolean skip(ServerPlayer player) {
		if (!inRitual(player) || !run.replay) return false;
		end(player.getServer(), true);
		return true;
	}

	public static void onLogout(ServerPlayer player) {
		if (inRitual(player)) end(player.getServer(), true);
	}

	public static void clear() {
		removeProps(run);
		run = null;
	}

	private static void end(MinecraftServer server, boolean early) {
		Run r = run;
		run = null;
		removeProps(r);
		if (r == null || server == null) return;
		ServerPlayer p = player(server, r.id);
		if (p == null) return;
		Ultimates.release(p);
		if (early) {
			NetworkHandler.sendFx(p, FxPacket.RITUAL, 0, "");
			if (Arena.in(p)) p.teleportTo(p.serverLevel(), Arena.ALTAR.x, Arena.ALTAR.y, Arena.ALTAR.z, p.getYRot(), p.getXRot());
		}
		Ultimates.move(p, Vec3.ZERO);
	}

	/** Fake players of the dev test (not in the player list). */
	public static ServerPlayer testPlayer;

	private static ServerPlayer player(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null || testPlayer == null || !testPlayer.getUUID().equals(id) ? p : testPlayer;
	}

	public static void tickAll(MinecraftServer server) {
		Run r = run;
		if (r == null) return;
		ServerPlayer p = player(server, r.id);
		ServerLevel arena = Arena.level(server);
		if (p == null || !p.isAlive() || arena == null || p.level() != arena) {
			end(server, true);
			return;
		}
		int t = r.t++;
		if (t >= END) {
			end(server, false);
			return;
		}
		try {
			show(arena, p, r, t);
		} catch (Exception ex) {
			com.toast.aurapoints.AuraPoints.LOGGER.error("The awakening ritual failed at tick {} - ending it.", t, ex);
			end(server, true);
		}
	}

	private static void show(ServerLevel level, ServerPlayer p, Run r, int t) {
		AuraType aura = r.aura;
		List<AuraType> auras = auras();
		int n = auras.size(), own = Math.max(0, auras.indexOf(aura));
		Vec3 c = Arena.ALTAR;
		ParticleOptions dust = AuraFx.dust(aura.rgb, 1.5f), gold = AuraFx.dust(0xFFC83D, 1.2f), mote = mote(aura);
		Vec3 chest = p.position().add(0, 1.1, 0);

		animateProps(r, t, own, n);

		// held in place until the ascent
		if (t < ASCEND) {
			if (p.position().distanceToSqr(c) > 0.25) p.teleportTo(level, c.x, c.y, c.z, p.getYRot(), p.getXRot());
			Ultimates.move(p, Vec3.ZERO);
		}

		// APPROACH: the dais wakes
		if (t < GATHER) {
			if (t % 4 == 0) AuraFx.ring(level, c.x, c.y + 0.1, c.z, 1.6 + t * 0.03, gold, 28);
			if (t % 20 == 0) sound(level, c, SoundEvents.WARDEN_HEARTBEAT, 2.0f, 0.6f + t * 0.004f);
			if (t == 30) sound(level, c, SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.4f, 0.6f);
		}

		// GATHER: the aura's pillar ignites, then every pillar pours its light into the player; rune rings rise
		if (t >= GATHER && t < ASCEND) {
			Vec3 mine = pillar(own, n).add(0, 4.5, 0);
			if (t == GATHER) {
				sound(level, mine, SoundEvents.BEACON_POWER_SELECT, 2.0f, 0.8f);
				sound(level, mine, SoundEvents.FIRECHARGE_USE, 1.5f, 0.7f);
				AuraFx.burst(level, mine.x, mine.y, mine.z, dust, 60, 0.5, 0.2);
			}
			if (t % 2 == 0) for (int y = 0; y < 14; y++) AuraFx.particles(level, dust, mine.x, mine.y + y * 0.9, mine.z, 1, 0.1, 0.3, 0.1, 0);
			if (t >= GATHER + 20 && t < VISION + 40) {
				// one pillar after the other joins in
				int joined = Math.min(n, (t - GATHER - 20) / 6 + 1);
				for (int i = 0; i < joined; i++) {
					AuraType a = auras.get((own + i) % n);
					Vec3 from = pillar((own + i) % n, n).add(0, 4.5, 0);
					if ((t + i * 3) % 4 == 0) stream(level, from, chest, AuraFx.dust(a.rgb, 1.1f), (t * 0.06 + i) % 1.0);
					if (t == GATHER + 20 + i * 6) sound(level, from, SoundEvents.AMETHYST_BLOCK_CHIME, 2.0f, 0.6f + i * 0.09f);
				}
			}
			// two rune rings, turning against each other, rising with the phase
			double rise = Math.min(1.0, (t - GATHER) / 80.0);
			if (t % 2 == 0) {
				runeRing(level, c.add(0, 0.3 + rise * 1.4, 0), 2.2, t * 0.07, dust, 14);
				runeRing(level, c.add(0, 0.3 + rise * 2.6, 0), 1.5, -t * 0.09, gold, 10);
			}
			if (t % 25 == 0) sound(level, c, SoundEvents.BEACON_AMBIENT, 2.0f, (float) (0.7 + rise * 0.6));
		}

		// VISION: the aura shows itself round the altar
		if (t >= VISION && t < ASCEND) {
			double k = (t - VISION) / (double) (ASCEND - VISION);
			double rad = 2.5 + k * 3.5;
			for (int arm = 0; arm < 3; arm++) {
				double a = t * 0.16 + arm * 2 * Math.PI / 3;
				double y = 0.4 + ((t * 0.05 + arm * 0.33) % 1.0) * 6.0;
				AuraFx.particles(level, mote, c.x + Math.cos(a) * rad, c.y + y, c.z + Math.sin(a) * rad, 3, 0.15, 0.15, 0.15, 0.01);
				AuraFx.particles(level, dust, c.x + Math.cos(a) * rad, c.y + y, c.z + Math.sin(a) * rad, 2, 0.1, 0.1, 0.1, 0);
			}
			if ((t - VISION) % 20 == 0) {
				AuraFx.ring(level, c.x, c.y + 0.2, c.z, rad, mote, 40);
				sound(level, c, voice(aura), 1.8f, 0.7f + (float) k * 0.5f);
				if (aura == AuraType.THUNDER || t == VISION + 60) strike(level, c.add(Math.cos(t) * rad, 0, Math.sin(t) * rad));
			}
		}

		// ASCEND: lifted into a beam
		if (t >= ASCEND && t < REVEAL) {
			Ultimates.move(p, t < ASCEND + 40 ? new Vec3(0, 0.1, 0) : Vec3.ZERO);
			if (t == ASCEND) {
				sound(level, c, SoundEvents.BEACON_ACTIVATE, 2.0f, 0.9f);
				sound(level, c, SoundEvents.ELYTRA_FLYING, 1.2f, 0.6f);
			}
			if (t % 2 == 0) for (int y = 0; y < 30; y += 2) AuraFx.particles(level, ParticleTypes.END_ROD, c.x, c.y - 1 + y, c.z, 1, 0.2, 0.6, 0.2, 0);
			if (t % 3 == 0) runeRing(level, p.position().add(0, 0.2, 0), 1.2 + (t - ASCEND) * 0.01, t * 0.2, dust, 12);
			for (int i = 0; i < n; i++) if ((t + i * 2) % 3 == 0) stream(level, pillar(i, n).add(0, 4.5, 0), chest, AuraFx.dust(auras.get(i).rgb, 1.0f), (t * 0.1 + i * 0.1) % 1.0);
			if (t % 8 == 0) sound(level, c, SoundEvents.NOTE_BLOCK_CHIME.value(), 2.0f, 0.5f + (t - ASCEND) * 0.018f);
		}

		// REVEAL
		if (t == REVEAL) {
			Ability ult = Ultimates.of(aura, Branch.current(p));
			String name = ult == null ? "" : ult.displayName;
			AuraFx.burst(level, chest.x, chest.y, chest.z, ParticleTypes.TOTEM_OF_UNDYING, 120, 0.8, 0.7);
			AuraFx.burst(level, chest.x, chest.y, chest.z, dust, 120, 1.5, 0.3);
			AuraFx.burst(level, chest.x, chest.y, chest.z, ParticleTypes.FLASH, 3, 0.1, 0);
			for (int i = 0; i < n; i++) {
				Vec3 top = pillar(i, n).add(0, 4.5, 0);
				AuraFx.burst(level, top.x, top.y, top.z, AuraFx.dust(auras.get(i).rgb, 1.6f), 30, 0.4, 0.3);
			}
			strike(level, c.add(0, -1, 0));
			sound(level, c, SoundEvents.TOTEM_USE, 1.4f, 1.0f);
			sound(level, c, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.6f, 1.0f);
			sound(level, c, SoundEvents.END_PORTAL_SPAWN, 0.7f, 1.3f);
			// clients with the cutscene draw their own title; the vanilla one is for everyone else looking on
			Component sub = Component.empty().append(AuraLabel.of(aura)).append(Component.literal("  " + name).withStyle(ChatFormatting.YELLOW));
			for (ServerPlayer near : level.players()) {
				if (near != p && near.position().distanceTo(c) < 40) {
					AuraFx.title(near, Component.literal(p.getGameProfile().getName() + " AWAKENED").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), sub);
				}
			}
			if (!r.replay) {
				p.sendSystemMessage(Component.literal("✦ Ultimate unlocked: " + name + " - press ").withStyle(ChatFormatting.YELLOW)
						.append(Component.keybind("key.aurapoints.ultimate").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
						.append(Component.literal(" while you hold " + aura.displayName + ".").withStyle(ChatFormatting.YELLOW)));
				level.getServer().getPlayerList().broadcastSystemMessage(Component.literal("✦ " + p.getGameProfile().getName() + " has awakened ")
						.withStyle(ChatFormatting.GOLD).append(AuraLabel.of(aura)).append(Component.literal(" at the altar and unlocked " + name + "!")
								.withStyle(ChatFormatting.GOLD)), false);
			}
		}
		if (t > REVEAL) {
			// set down again
			boolean above = p.getY() > c.y + 0.15;
			Ultimates.move(p, above ? new Vec3(0, -0.1, 0) : Vec3.ZERO);
			if (t % 3 == 0) AuraFx.particles(level, gold, chest.x, chest.y, chest.z, 6, 0.6, 0.8, 0.6, 0.02);
			if (t % 2 == 0 && t < REVEAL + 40) for (int y = 0; y < 30; y += 3) AuraFx.particles(level, ParticleTypes.END_ROD, c.x, c.y - 1 + y, c.z, 1, 0.2, 0.6, 0.2, 0);
		}
	}

	/** A few specks along the way from {@code from} to {@code to}, bunched round {@code phase} (0..1) so they read as flowing. */
	private static void stream(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle, double phase) {
		for (int k = 0; k < 5; k++) {
			double f = (phase + k * 0.2) % 1.0;
			Vec3 at = from.lerp(to, f).add(0, Math.sin(f * Math.PI) * 1.2, 0);
			AuraFx.particles(level, particle, at.x, at.y, at.z, 2, 0.06, 0.06, 0.06, 0);
		}
	}

	/** A ring of {@code marks} short dashes, turned by {@code spin}. */
	private static void runeRing(ServerLevel level, Vec3 centre, double radius, double spin, ParticleOptions particle, int marks) {
		for (int i = 0; i < marks; i++) {
			double a = spin + 2 * Math.PI * i / marks;
			for (int k = 0; k < 2; k++) {
				double b = a + k * 0.07;
				AuraFx.particles(level, particle, centre.x + Math.cos(b) * radius, centre.y, centre.z + Math.sin(b) * radius, 1, 0, 0, 0, 0);
			}
		}
	}

	private static void strike(ServerLevel level, Vec3 at) {
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
		if (bolt == null) return;
		bolt.moveTo(at.x, at.y, at.z);
		bolt.setVisualOnly(true);
		level.addFreshEntity(bolt);
	}

	/** The particle each aura shows itself with. */
	private static ParticleOptions mote(AuraType aura) {
		return switch (aura) {
			case FIRE -> ParticleTypes.FLAME;
			case WATER -> ParticleTypes.BUBBLE_POP;
			case WIND -> ParticleTypes.CLOUD;
			case FROST -> ParticleTypes.SNOWFLAKE;
			case VERDANT -> ParticleTypes.CHERRY_LEAVES;
			case THUNDER -> ParticleTypes.ELECTRIC_SPARK;
			case EARTH -> ParticleTypes.MYCELIUM;
			case LIGHT -> ParticleTypes.END_ROD;
			case BLOOD -> ParticleTypes.DAMAGE_INDICATOR;
			case SHADOW -> ParticleTypes.SQUID_INK;
			default -> ParticleTypes.ENCHANT;
		};
	}

	private static SoundEvent voice(AuraType aura) {
		return switch (aura) {
			case FIRE -> SoundEvents.BLAZE_SHOOT;
			case WATER -> SoundEvents.PLAYER_SPLASH_HIGH_SPEED;
			case WIND -> SoundEvents.BREEZE_WIND_CHARGE_BURST.value();
			case FROST -> SoundEvents.GLASS_BREAK;
			case VERDANT -> SoundEvents.AZALEA_LEAVES_BREAK;
			case THUNDER -> SoundEvents.TRIDENT_THUNDER.value();
			case EARTH -> SoundEvents.DEEPSLATE_BREAK;
			case LIGHT -> SoundEvents.AMETHYST_BLOCK_RESONATE;
			case BLOOD -> SoundEvents.WARDEN_HEARTBEAT;
			case SHADOW -> SoundEvents.ENDERMAN_STARE;
			default -> SoundEvents.BEACON_AMBIENT;
		};
	}

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
	}

	// ---- the props: item displays with the altar models (altar_sigil under the player, altar_core over the head,
	// an altar_cap crystal on each pillar). Spawned with the show, gone when it ends; tagged ones from a crash are dropped on load. ----

	private static final String PROP_TAG = "aurapoints_altar_prop";
	private static final Set<UUID> LIVE = new HashSet<>();
	/** Where the core hovers over the dais, and where the sigil turns (just over the dais steps). */
	private static final double CORE_Y = 3.9, SIGIL_Y = -0.35;
	private static final float CORE_SCALE = 2.2f, SIGIL_SCALE = 7.5f, CAP_SCALE = 1.0f;

	public static boolean isLeftover(Entity e) {
		return e.getTags().contains(PROP_TAG) && !LIVE.contains(e.getUUID());
	}

	/** How many ritual props are up (the dev test). */
	public static int liveProps() {
		return LIVE.size();
	}

	private static Display.ItemDisplay prop(ServerLevel level, Vec3 at, ItemStack stack) {
		Display.ItemDisplay d = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		d.setPos(at.x, at.y, at.z);
		((ItemDisplayAccessor) d).aurapoints$setItemStack(stack);
		((ItemDisplayAccessor) d).aurapoints$setItemTransform(ItemDisplayContext.FIXED);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setPosRotInterpolationDuration(3);
		acc.aurapoints$setViewRange(3.0f);
		acc.aurapoints$setBrightnessOverride(new Brightness(15, 15));
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.001f), null));
		d.addTag(PROP_TAG);
		LIVE.add(d.getUUID());
		level.addFreshEntity(d);
		return d;
	}

	/** Scale and turn about Y, the client easing over {@code ticks}. */
	private static void pose(Display.ItemDisplay d, float scale, double yaw, int ticks) {
		if (d == null || d.isRemoved()) return;
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationY((float) yaw), new Vector3f(Math.max(0.001f, scale)), null));
		acc.aurapoints$setInterpolationDuration(ticks);
		d.getEntityData().set(DisplayAccessor.aurapoints$startDelta(), 0, true);
	}

	private static void spawnProps(ServerLevel level, Run r) {
		Vec3 c = Arena.ALTAR;
		r.sigil = prop(level, c.add(0, SIGIL_Y, 0), new ItemStack(com.toast.aurapoints.AuraPoints.ALTAR_SIGIL.get()));
		r.core = prop(level, c.add(0, CORE_Y, 0), new ItemStack(com.toast.aurapoints.AuraPoints.ALTAR_CORE.get()));
		List<AuraType> auras = auras();
		r.caps = new Display.ItemDisplay[auras.size()];
		for (int i = 0; i < auras.size(); i++) {
			ItemStack cap = new ItemStack(com.toast.aurapoints.AuraPoints.ALTAR_CAP.get());
			cap.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(capModel(auras.get(i))));
			// the model's base is 0.4 px up from its bottom: sit it on the pillar's glass (floor + 6)
			r.caps[i] = prop(level, pillar(i, auras.size()).add(0, 5 + (8 - 0.4) / 16.0 * CAP_SCALE, 0), cap);
		}
	}

	/** altar_cap's custom_model_data for an aura (1..10, the model's override order). */
	static int capModel(AuraType aura) {
		return switch (aura) {
			case SHADOW -> 1;
			case WATER -> 2;
			case FIRE -> 3;
			case WIND -> 4;
			case FROST -> 5;
			case VERDANT -> 6;
			case THUNDER -> 7;
			case EARTH -> 8;
			case LIGHT -> 9;
			case BLOOD -> 10;
			default -> 1;
		};
	}

	private static void removeProps(Run r) {
		if (r == null) return;
		List<Display.ItemDisplay> all = new ArrayList<>();
		all.add(r.sigil);
		all.add(r.core);
		if (r.caps != null) all.addAll(List.of(r.caps));
		for (Display.ItemDisplay d : all) {
			if (d == null) continue;
			LIVE.remove(d.getUUID());
			d.discard();
		}
		r.sigil = r.core = null;
		r.caps = null;
	}

	private static void animateProps(Run r, int t, int own, int n) {
		if (r.caps == null) return;
		// the sigil: grows out of the dais, turns all the way, faster as the power builds
		if (t == 2) pose(r.sigil, SIGIL_SCALE, 0, 40);
		if (t > 2 && t % 10 == 0) {
			double speed = t < GATHER ? 0.25 : t < ASCEND ? 0.45 : t < REVEAL ? 0.9 : 0.2;
			r.sigilYaw += speed;
			pose(r.sigil, t >= REVEAL + 30 ? SIGIL_SCALE * Math.max(0.05f, 1 - (t - REVEAL - 30) / 30f) : SIGIL_SCALE, r.sigilYaw, 10);
		}
		// the caps: the aura's own lights first, the rest join one by one with the streams
		for (int i = 0; i < n; i++) {
			int k = (i - own + n) % n; // order of joining
			int lit = k == 0 ? GATHER : GATHER + 20 + k * 6;
			if (t == lit) pose(r.caps[i], CAP_SCALE * 1.35f, i, 6);
			if (t == lit + 6) pose(r.caps[i], CAP_SCALE, i + 0.4, 8);
			if (t > lit + 6 && t % 20 == (i * 2) % 20) pose(r.caps[i], CAP_SCALE, i + t * 0.04, 20);
			if (t == REVEAL) pose(r.caps[i], CAP_SCALE * 1.5f, i + t * 0.04, 4);
			if (t == REVEAL + 8) pose(r.caps[i], CAP_SCALE, i + t * 0.04, 12);
		}
		// the core: wakes in the gathering, rises ahead of the ascent, breaks at the reveal
		if (r.core != null) {
			if (t == GATHER) pose(r.core, CORE_SCALE * 0.6f, 0, 30);
			if (t > GATHER && t < REVEAL && t % 10 == 0) {
				r.coreYaw += t < ASCEND ? 0.5 : 1.1;
				float grow = t < VISION ? 0.6f + 0.4f * (t - GATHER) / (VISION - GATHER) : 1f;
				pose(r.core, CORE_SCALE * grow, r.coreYaw, 10);
			}
			// keeps ahead of the player rising 0.1 a tick for 40 ticks
			if (t >= ASCEND && t < ASCEND + 40) r.core.setPos(r.core.getX(), r.core.getY() + 0.11, r.core.getZ());
			if (t == REVEAL) {
				ServerLevel level = (ServerLevel) r.core.level();
				Vec3 at = r.core.position();
				AuraFx.burst(level, at.x, at.y, at.z, ParticleTypes.END_ROD, 80, 0.4, 0.4);
				sound(level, at, SoundEvents.AMETHYST_CLUSTER_BREAK, 2.0f, 0.6f);
				LIVE.remove(r.core.getUUID());
				r.core.discard();
				r.core = null;
			}
		}
	}

}
