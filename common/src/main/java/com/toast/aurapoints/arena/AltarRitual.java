package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraSounds;
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
import java.util.Map;
import java.util.HashMap;
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
	public static Vec3 pillar(int i, int n) {
		// half a step off the axes: the way in from the bridge (west) runs between two pillars
		double a = 2 * Math.PI * (i + 0.5) / n;
		// 3x3 pillars stand on a block centre; rounded away from zero (Math.round takes -5.5 to -5 but 5.5 to 6): mirror-symmetric on both axes
		return new Vec3(Arena.ALTAR_X + 0.5 + away(Math.cos(a) * Arena.PILLAR_RING), Arena.FLOOR + 1, 0.5 + away(Math.sin(a) * Arena.PILLAR_RING));
	}

	private static long away(double v) {
		return (long) Math.signum(v) * Math.round(Math.abs(v));
	}

	private static final class Run {
		final UUID id;
		final AuraType aura;
		final boolean replay;
		int t;
		Display.ItemDisplay sigil, core;
		Display.ItemDisplay[] caps;
		/** The hall's own vein sections (not ours to remove), [pillar][section]. */
		Display.ItemDisplay[][] veins;
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
			player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.BEACON_POWER_SELECT.value(), SoundSource.PLAYERS, 1.0f, 1.2f);
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
		// the keeper's stories, one per pillar
		MutableComponent lore = Component.literal("  Hear of: ").withStyle(ChatFormatting.GRAY);
		for (AuraType a : auras()) {
			lore.append(Component.literal("[" + a.displayName + "] ").withStyle(st -> st.withColor(net.minecraft.network.chat.TextColor.fromRgb(a.rgb))
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura altar lore " + a.id()))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("The keeper's story of the " + a.displayName + " pillar")))));
		}
		player.sendSystemMessage(lore);
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
		if (!Arena.inAltarRoom(player)) return "Step through the altar portal on the island east of the arena lobby.";
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
		sound(level, Arena.ALTAR, AuraSounds.BEACON_ACTIVATE.value(), 1.6f, 0.5f);
		sound(level, Arena.ALTAR, AuraSounds.WARDEN_HEARTBEAT.value(), 2.0f, 0.6f);
		spawnProps(level, run);
		if (player.getServer().getPlayerList().getPlayer(player.getUUID()) != null) invite(player, aura);
	}

	/** Sneak during a replay: straight to the end. */
	public static boolean skip(ServerPlayer player) {
		if (!inRitual(player) || !run.replay) return false;
		end(player.getServer(), true);
		return true;
	}

	public static void onLogout(ServerPlayer player) {
		PORTAL_COOLDOWN.remove(player.getUUID());
		if (ENTERING.remove(player.getUUID()) != null) Ultimates.release(player);
		if (inRitual(player)) end(player.getServer(), true);
	}

	public static void clear() {
		PORTAL_COOLDOWN.clear();
		ENTERING.clear();
		PULSED.clear();
		for (Iris iris : IRISES.values()) {
			LIVE.remove(iris.overlay.getUUID());
			iris.overlay.discard();
		}
		IRISES.clear();
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
		if (!early) offerReturn(server, p.getUUID());
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
		tickPortals(server);
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
			if (t % 20 == 0) sound(level, c, AuraSounds.WARDEN_HEARTBEAT.value(), 2.0f, 0.6f + t * 0.004f);
			if (t == 30) sound(level, c, AuraSounds.RESPAWN_ANCHOR_CHARGE.value(), 1.4f, 0.6f);
		}

		// GATHER: the aura's pillar ignites, then every pillar pours its light into the player; rune rings rise
		if (t >= GATHER && t < ASCEND) {
			Vec3 mine = pillar(own, n).add(0, PILLAR_TOP, 0);
			if (t == GATHER) {
				sound(level, mine, AuraSounds.BEACON_POWER_SELECT.value(), 2.0f, 0.8f);
				sound(level, mine, AuraSounds.FIRECHARGE_USE.value(), 1.5f, 0.7f);
				AuraFx.burst(level, mine.x, mine.y, mine.z, dust, 60, 0.5, 0.2);
			}
			if (t % 2 == 0) for (int y = 0; y < 14; y++) AuraFx.particles(level, dust, mine.x, mine.y + y * 0.9, mine.z, 1, 0.1, 0.3, 0.1, 0);
			if (t >= GATHER + 20 && t < VISION + 40) {
				// one pillar after the other joins in
				int joined = Math.min(n, (t - GATHER - 20) / 6 + 1);
				for (int i = 0; i < joined; i++) {
					AuraType a = auras.get((own + i) % n);
					Vec3 from = pillar((own + i) % n, n).add(0, PILLAR_TOP, 0);
					if ((t + i * 3) % 4 == 0) stream(level, from, chest, AuraFx.dust(a.rgb, 1.1f), (t * 0.06 + i) % 1.0);
					if (t == GATHER + 20 + i * 6) sound(level, from, AuraSounds.AMETHYST_BLOCK_CHIME.value(), 2.0f, 0.6f + i * 0.09f);
				}
			}
			// two rune rings, turning against each other, rising with the phase
			double rise = Math.min(1.0, (t - GATHER) / 80.0);
			if (t % 2 == 0) {
				runeRing(level, c.add(0, 0.3 + rise * 1.4, 0), 2.2, t * 0.07, dust, 14);
				runeRing(level, c.add(0, 0.3 + rise * 2.6, 0), 1.5, -t * 0.09, gold, 10);
			}
			if (t % 25 == 0) sound(level, c, AuraSounds.BEACON_AMBIENT.value(), 2.0f, (float) (0.7 + rise * 0.6));
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
				sound(level, c, AuraSounds.BEACON_ACTIVATE.value(), 2.0f, 0.9f);
				sound(level, c, AuraSounds.ELYTRA_FLYING.value(), 1.2f, 0.6f);
			}
			if (t % 2 == 0) for (int y = 0; y < 30; y += 2) AuraFx.particles(level, ParticleTypes.END_ROD, c.x, c.y - 1 + y, c.z, 1, 0.2, 0.6, 0.2, 0);
			if (t % 3 == 0) runeRing(level, p.position().add(0, 0.2, 0), 1.2 + (t - ASCEND) * 0.01, t * 0.2, dust, 12);
			for (int i = 0; i < n; i++) if ((t + i * 2) % 3 == 0) stream(level, pillar(i, n).add(0, PILLAR_TOP, 0), chest, AuraFx.dust(auras.get(i).rgb, 1.0f), (t * 0.1 + i * 0.1) % 1.0);
			if (t % 8 == 0) sound(level, c, AuraSounds.NOTE_BLOCK_CHIME.value(), 2.0f, 0.5f + (t - ASCEND) * 0.018f);
		}

		// REVEAL
		if (t == REVEAL) {
			Ability ult = Ultimates.of(aura, Branch.current(p));
			String name = ult == null ? "" : ult.displayName;
			AuraFx.burst(level, chest.x, chest.y, chest.z, ParticleTypes.TOTEM_OF_UNDYING, 120, 0.8, 0.7);
			AuraFx.burst(level, chest.x, chest.y, chest.z, dust, 120, 1.5, 0.3);
			AuraFx.burst(level, chest.x, chest.y, chest.z, ParticleTypes.FLASH, 3, 0.1, 0);
			for (int i = 0; i < n; i++) {
				Vec3 top = pillar(i, n).add(0, PILLAR_TOP, 0);
				AuraFx.burst(level, top.x, top.y, top.z, AuraFx.dust(auras.get(i).rgb, 1.6f), 30, 0.4, 0.3);
			}
			strike(level, c.add(0, -1, 0));
			sound(level, c, AuraSounds.TOTEM_USE.value(), 1.4f, 1.0f);
			sound(level, c, AuraSounds.UI_TOAST_CHALLENGE_COMPLETE.value(), 0.4f, 1.0f);
			sound(level, c, AuraSounds.END_PORTAL_SPAWN.value(), 0.5f, 1.3f);
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
			case FIRE -> AuraSounds.BLAZE_SHOOT.value();
			case WATER -> AuraSounds.PLAYER_SPLASH_HIGH_SPEED.value();
			case WIND -> AuraSounds.BREEZE_WIND_CHARGE_BURST.value();
			case FROST -> AuraSounds.GLASS_BREAK.value();
			case VERDANT -> AuraSounds.AZALEA_LEAVES_BREAK.value();
			case THUNDER -> AuraSounds.TRIDENT_THUNDER.value();
			case EARTH -> AuraSounds.DEEPSLATE_BREAK.value();
			case LIGHT -> AuraSounds.AMETHYST_BLOCK_RESONATE.value();
			case BLOOD -> AuraSounds.WARDEN_HEARTBEAT.value();
			case SHADOW -> AuraSounds.ENDERMAN_STARE.value();
			default -> AuraSounds.BEACON_AMBIENT.value();
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
	private static final float CORE_SCALE = 2.2f, SIGIL_SCALE = 7.5f, CAP_SCALE = 2.6f;
	static final float PILLAR_SX = 4f, PILLAR_SY = (Arena.HALL_CEIL_H - 1 - CAP_SCALE - 0.2f) * 16f / 37.6f;
	/** Above a pillar's foot: the cradle's top (the model's 29.6 px from its foot at -8, at 1/4 block per px) and where streams leave from. */
	static final double PILLAR_CRADLE = (29.6 + 8) / 16.0 * PILLAR_SY, PILLAR_TOP = PILLAR_CRADLE + 0.5;

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

	/** The pillar body: a (non-uniform) scale, no turn, no easing. */
	private static void poseXyz(Display.ItemDisplay d, Vector3f scale) {
		if (d == null || d.isRemoved()) return;
		((DisplayAccessor) d).aurapoints$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), scale, null));
	}

	private static void spawnProps(ServerLevel level, Run r) {
		Vec3 c = Arena.ALTAR;
		r.sigil = prop(level, c.add(0, SIGIL_Y, 0), new ItemStack(com.toast.aurapoints.AuraPoints.ALTAR_SIGIL.get()));
		r.core = prop(level, c.add(0, CORE_Y, 0), new ItemStack(com.toast.aurapoints.AuraPoints.ALTAR_CORE.get()));
		List<AuraType> auras = auras();
		r.caps = new Display.ItemDisplay[auras.size()];
		r.veins = veins(level, auras.size());
		for (int i = 0; i < auras.size(); i++) {
			ItemStack cap = new ItemStack(com.toast.aurapoints.AuraPoints.ALTAR_CAP.get());
			cap.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(capModel(auras.get(i))));
			// in the pillar model's cradle (its top is 18.2 px up = 4.55 blocks at scale 4), the cap's base 0.4 px above its bottom
			r.caps[i] = prop(level, pillar(i, auras.size()).add(0, PILLAR_CRADLE + (8 - 0.4) / 16.0 * CAP_SCALE, 0), cap);
		}
	}

	// ---- the Altar Hall's pillars (5.30): an altar_pillar model (vanilla blackstone textures, not lit up: it sits in the
	// room's own light) around a barrier column, and on it six altar_vein sections in the aura's colour, bottom to top.
	// Idle only the lowest section glows (it shows the pillar's aura); in the ritual they light one after the other
	// as the pillar joins, and go out again from the top after. All are arena build entities (ArenaBuilder.claim). ----

	/** The models are 1 unit = 1/16 block at scale 1; the pillar is 1/4 block per unit across (its 12-unit shaft = the 3x3 barrier) and
	 * PILLAR_SY/16 block per unit up (5.34: the pillar with the crystal on its cradle meets the hall's ceiling at Arena.HALL_CEIL_H). */
	static final int VEIN_SECTIONS = 6;
	private static final String PILLAR_TAG = "aurapoints_altar_pillar", VEIN_TAG = "aurapoints_altar_vein", PORTAL_TAG = "aurapoints_altar_portal";

	/** ArenaBuilder.buildAltarHall: the pillar and vein displays (the barrier column inside is ArenaBuilder's). */
	static void spawnPillars(ServerLevel level) {
		List<AuraType> auras = auras();
		for (int i = 0; i < auras.size(); i++) {
			// model y -8 = the pillar's foot (floor + 1); the display sits on the model's middle (y 8 = 16 px = 4 blocks up)
			Vec3 at = pillar(i, auras.size()).add(0, PILLAR_SY, 0);
			Display.ItemDisplay body = staticDisplay(level, at, new ItemStack(com.toast.aurapoints.AuraPoints.ALTAR_PILLAR.get()), false);
			poseXyz(body, new Vector3f(PILLAR_SX, PILLAR_SY, PILLAR_SX));
			body.addTag(PILLAR_TAG);
			for (int sct = 0; sct < VEIN_SECTIONS; sct++) {
				ItemStack vein = new ItemStack(com.toast.aurapoints.AuraPoints.ALTAR_VEIN.get());
				vein.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData((capModel(auras.get(i)) - 1) * VEIN_SECTIONS + sct + 1));
				Display.ItemDisplay d = staticDisplay(level, at, vein, true);
				d.addTag(VEIN_TAG);
				d.addTag(VEIN_TAG + "_" + i + "_" + sct);
				light(d, sct == 0, 0);
			}
		}
	}

	/** The swirl in the island's arch ({@code foot}: the opening's bottom middle), facing along x. */
	static void spawnPortal(ServerLevel level, Vec3 foot) {
		Display.ItemDisplay d = staticDisplay(level, foot.add(0, Arena.PORTAL_H / 2.0, 0), new ItemStack(com.toast.aurapoints.AuraPoints.ALTAR_PORTAL.get()), true);
		d.setYRot(90f);
		d.addTag(PORTAL_TAG);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(Arena.PORTAL_W, Arena.PORTAL_H, 1f), null));
	}

	/** The same in the hall's west door. */
	static void spawnExitPortal(ServerLevel level, Vec3 foot) {
		spawnPortal(level, foot);
	}

	private static Display.ItemDisplay staticDisplay(ServerLevel level, Vec3 at, ItemStack stack, boolean glow) {
		Display.ItemDisplay d = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		d.setPos(at.x, at.y, at.z);
		((ItemDisplayAccessor) d).aurapoints$setItemStack(stack);
		((ItemDisplayAccessor) d).aurapoints$setItemTransform(ItemDisplayContext.FIXED);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setViewRange(4.0f);
		if (glow) acc.aurapoints$setBrightnessOverride(new Brightness(15, 15));
		ArenaBuilder.claim(d);
		level.addFreshEntity(d);
		return d;
	}

	/** A vein section on (full size) or off (shrunk to nothing), easing over {@code ticks}. */
	static void light(Display.ItemDisplay d, boolean on, int ticks) {
		if (d == null || d.isRemoved()) return;
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), on ? new Vector3f(PILLAR_SX, PILLAR_SY, PILLAR_SX) : new Vector3f(0.001f), null));
		acc.aurapoints$setInterpolationDuration(ticks);
		d.getEntityData().set(DisplayAccessor.aurapoints$startDelta(), 0, true);
	}

	/** The vein sections round the altar, [pillar][section] (loaded ones only). */
	private static Display.ItemDisplay[][] veins(ServerLevel level, int n) {
		Display.ItemDisplay[][] out = new Display.ItemDisplay[n][VEIN_SECTIONS];
		Vec3 c = Arena.ALTAR;
		for (Display.ItemDisplay d : level.getEntitiesOfClass(Display.ItemDisplay.class, new net.minecraft.world.phys.AABB(c, c).inflate(Arena.PILLAR_RING + 3, 10, Arena.PILLAR_RING + 3),
				d -> d.getTags().contains(VEIN_TAG))) {
			for (String tag : d.getTags()) {
				if (!tag.startsWith(VEIN_TAG + "_")) continue;
				String[] parts = tag.substring(VEIN_TAG.length() + 1).split("_");
				try {
					int i = Integer.parseInt(parts[0]), k = Integer.parseInt(parts[1]);
					if (i >= 0 && i < n && k >= 0 && k < VEIN_SECTIONS) out[i][k] = d;
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
				}
			}
		}
		return out;
	}


	// ---- the keeper's lore (5.31): a few lines per pillar ----

	private static final Map<AuraType, String[]> LORE = new java.util.EnumMap<>(AuraType.class);

	static {
		LORE.put(AuraType.SHADOW, new String[]{"Before the first dawn there was only the dark, and it was not empty.",
				"Shadow is what learned to move in it unseen. Its pillar was raised in a night that never lifted.",
				"Those who wake it learn the dark was never against them."});
		LORE.put(AuraType.WATER, new String[]{"The deep remembers every shore it ever touched.",
				"Water's pillar stood under a sea that has long since left, and the veins still run like a tide.",
				"It heals as easily as it drowns. The awakened decide which."});
		LORE.put(AuraType.FIRE, new String[]{"The first flame was stolen, they say, and it has burned angry ever since.",
				"Fire's pillar is the warmest stone in the hall; keepers once cooked on it.",
				"Waking it is easy. Keeping it from waking you is the hard part."});
		LORE.put(AuraType.WIND, new String[]{"Wind has no home, so it built one out of the space between things.",
				"Its pillar hums when no one is near, a note too high to hear for long.",
				"The awakened stop falling. They only ever land."});
		LORE.put(AuraType.FROST, new String[]{"Frost is patience made into a weapon.",
				"Its pillar was cut from a glacier that took ten thousand winters to reach the sea.",
				"What it holds, it holds still. Even time, for a breath."});
		LORE.put(AuraType.VERDANT, new String[]{"Everything that grows is a little bit stubborn.",
				"Verdant's pillar had roots before the hall had walls; the floor around it is still cracked.",
				"It feeds, it binds, it poisons - and it always comes back."});
		LORE.put(AuraType.THUNDER, new String[]{"Thunder is the sky losing its temper.",
				"Its pillar was struck a hundred times on the night it was raised and never fell.",
				"The awakened move first and explain later."});
		LORE.put(AuraType.EARTH, new String[]{"Under every island here is older stone, and it is listening.",
				"Earth's pillar is the only one the hall was built around rather than beside.",
				"It asks for nothing but weight. Give it yours."});
		LORE.put(AuraType.LIGHT, new String[]{"Light does not chase the dark away. It just refuses to leave.",
				"Its pillar was the last raised, to see the others by.",
				"Those who wake it carry a little dawn with them."});
		LORE.put(AuraType.BLOOD, new String[]{"Every aura costs something. Blood just says so out loud.",
				"Its pillar was the price of the hall; the keepers do not say whose.",
				"The awakened know exactly what they can spare - and spend it."});
	}

	/** /aura altar lore [aura]: the keeper's story of a pillar (the aura you hold without an argument). */
	public static void lore(ServerPlayer player, AuraType aura) {
		if (aura == null || !LORE.containsKey(aura)) {
			player.sendSystemMessage(Component.literal("The keeper has no story for that.").withStyle(ChatFormatting.GRAY));
			return;
		}
		player.sendSystemMessage(Component.literal("✦ The " + aura.displayName + " pillar ✦").withStyle(st -> st.withColor(net.minecraft.network.chat.TextColor.fromRgb(aura.rgb)).withBold(true)));
		for (String line : LORE.get(aura)) player.sendSystemMessage(Component.literal("  " + line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
	}

	// ---- the living hall (5.31): your aura's pillar answers you; each pillar murmurs its element ----

	private static final Map<Integer, Long> PULSED = new HashMap<>();

	private static void tickHall(MinecraftServer server, ServerLevel arena) {
		long now = server.getTickCount();
		if (now % 10 != 0 || run != null) return;
		List<AuraType> auras = auras();
		int n = auras.size();
		Display.ItemDisplay[][] veins = null;
		java.util.Set<Integer> heard = new java.util.HashSet<>();
		for (ServerPlayer p : arena.players()) {
			if (p.isSpectator() || !Arena.inAltarRoom(p)) continue;
			AuraType own = AuraState.get(server).getOrCreate(p.getUUID()).aura;
			for (int i = 0; i < n; i++) {
				Vec3 at = pillar(i, n);
				double dx = p.getX() - at.x, dz = p.getZ() - at.z, d2 = dx * dx + dz * dz;
				// next to a pillar: its name and how to hear its story
				if (d2 <= 2.5 * 2.5 && now % 20 == 0) {
					AuraType a = auras.get(i);
					p.displayClientMessage(Component.literal(a.displayName + " pillar  ·  ").withStyle(st -> st.withColor(net.minecraft.network.chat.TextColor.fromRgb(a.rgb)))
							.append(Component.literal("/aura altar lore " + a.id()).withStyle(ChatFormatting.GRAY)), true);
				}
				// your own aura's pillar: its veins rise to the top and settle again
				if (auras.get(i) == own && d2 <= 4 * 4 && now >= PULSED.getOrDefault(i, 0L)) {
					PULSED.put(i, now + 80);
					if (veins == null) veins = veins(arena, n);
					Display.ItemDisplay[] col = veins[i];
					for (int k = 1; k < VEIN_SECTIONS; k++) {
						Display.ItemDisplay d = col[k];
						int up = k * 3;
						AuraFx.later(server, up, () -> light(d, true, 3));
						AuraFx.later(server, 30 + (VEIN_SECTIONS - k) * 3, () -> {
							if (run == null) light(d, false, 8);
						});
					}
					sound(arena, at.add(0, 2, 0), AuraSounds.AMETHYST_BLOCK_RESONATE.value(), 0.7f, 0.8f);
				}
				if (d2 <= 6 * 6) heard.add(i);
			}
		}
		// the murmur: now and then, quietly, from a pillar someone stands near
		if (now % 40 == 0) {
			for (int i : heard) {
				if (arena.random.nextInt(3) != 0) continue;
				Vec3 at = pillar(i, n).add(0, 2, 0);
				level(arena, at, murmur(auras.get(i)));
			}
		}
	}

	private static void level(ServerLevel level, Vec3 at, SoundEvent sound) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.AMBIENT, 0.35f, 0.8f + level.random.nextFloat() * 0.3f);
	}

	/** Each pillar's quiet sound. */
	private static SoundEvent murmur(AuraType aura) {
		return switch (aura) {
			case FIRE -> AuraSounds.CAMPFIRE_CRACKLE.value();
			case WATER -> AuraSounds.POINTED_DRIPSTONE_DRIP_WATER.value();
			case WIND -> AuraSounds.BREEZE_IDLE_AIR.value();
			case FROST -> AuraSounds.POWDER_SNOW_STEP.value();
			case VERDANT -> AuraSounds.AZALEA_LEAVES_STEP.value();
			case THUNDER -> AuraSounds.COPPER_BULB_TURN_ON.value();
			case EARTH -> AuraSounds.DEEPSLATE_STEP.value();
			case LIGHT -> AuraSounds.AMETHYST_BLOCK_CHIME.value();
			case BLOOD -> AuraSounds.WARDEN_HEARTBEAT.value();
			case SHADOW -> AuraSounds.SCULK_CLICKING.value();
			default -> AuraSounds.AMETHYST_BLOCK_CHIME.value();
		};
	}

	// ---- watching (5.31): when a ritual starts, everyone else in the arena may come and watch ----

	private static void invite(ServerPlayer p, AuraType aura) {
		ServerLevel arena = Arena.level(p.getServer());
		if (arena == null) return;
		Component name = Component.literal(p.getGameProfile().getName()).withStyle(ChatFormatting.WHITE);
		for (ServerPlayer o : arena.players()) {
			if (o == p) continue;
			if (Arena.inAltarRoom(o)) {
				AuraFx.title(o, Component.literal(p.getGameProfile().getName() + "'s ritual").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
						Component.empty().append(AuraLabel.of(aura)).append(Component.literal(" awakens").withStyle(ChatFormatting.GRAY)));
			} else {
				o.sendSystemMessage(Component.literal("✦ ").withStyle(ChatFormatting.GOLD).append(name)
						.append(Component.literal(" begins the Awakening ritual of ").withStyle(ChatFormatting.GOLD)).append(AuraLabel.of(aura))
						.append(Component.literal("  ")).append(button("[Watch]", ChatFormatting.GREEN, "/aura altar watch", "Into the Altar Hall to watch")));
			}
		}
	}

	/** /aura altar watch: into the hall while a ritual runs. */
	public static void watch(ServerPlayer player) {
		Run r = run;
		if (r == null || r.id.equals(player.getUUID())) {
			player.sendSystemMessage(Component.literal("No ritual to watch right now.").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!Arena.in(player) || Duel.isIn(player) || Match.isIn(player)) {
			player.sendSystemMessage(Component.literal("Join the Aura Arena (and leave your match) to watch.").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!Arena.inAltarRoom(player)) travel(player, Arena.ROOM_ENTRY);
	}

	// ---- the portal's colour (5.31): it flashes the aura of whoever walks through ----


	/** Dev test: stories told, portals flashed for {@code p} walking from the island arch into the hall (count tinted). */
	public static int devLoreCount() {
		return LORE.size();
	}

	public static int devFlash(ServerPlayer p) {
		flashPortals(p, new Vec3(Arena.ISLAND_X + 0.5, Arena.FLOOR + 3.5, 0.5), Arena.ROOM_ENTRY);
		return IRISES.size();
	}

	/** A portal's colour overlay while it is open: the display, and when it starts closing. */
	private record Iris(Display.ItemDisplay overlay, long closeAt) {
	}

	/** Base portal display uuid -> its open overlay. */
	private static final Map<UUID, Iris> IRISES = new HashMap<>();
	private static final int IRIS_OPEN = 8, IRIS_HOLD = 50, IRIS_CLOSE = 14;

	/**
	 * The colour change as an iris (5.33): a second swirl in the walker's aura colour, a hair thicker than the base one
	 * (no z-fighting), opens from the middle over the purple, holds, and closes back to it - no hard swap. A new walker
	 * while it is open recolours it and keeps it open.
	 */
	private static void flashPortals(ServerPlayer p, Vec3 from, Vec3 to) {
		AuraType aura = AuraState.get(p.getServer()).getOrCreate(p.getUUID()).aura;
		if (aura == null || !LORE.containsKey(aura)) return;
		ServerLevel level = p.serverLevel();
		long now = p.getServer().getTickCount();
		for (Vec3 near : new Vec3[]{from, to}) {
			for (Display.ItemDisplay d : level.getEntitiesOfClass(Display.ItemDisplay.class, new net.minecraft.world.phys.AABB(near, near).inflate(7, 9, 7),
					d -> d.getTags().contains(PORTAL_TAG))) {
				ItemStack tinted = new ItemStack(com.toast.aurapoints.AuraPoints.ALTAR_PORTAL.get());
				tinted.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(capModel(aura)));
				Iris open = IRISES.get(d.getUUID());
				Display.ItemDisplay overlay;
				if (open != null && !open.overlay.isRemoved()) {
					overlay = open.overlay;
					((ItemDisplayAccessor) overlay).aurapoints$setItemStack(tinted);
					irisScale(overlay, 1f, 4);
				} else {
					overlay = prop(level, d.position(), tinted);
					overlay.setYRot(90f);
					irisScale(overlay, 0.02f, 0);
					// next tick, so the client has the small size to ease from
					Display.ItemDisplay o = overlay;
					AuraFx.later(p.getServer(), 1, () -> irisScale(o, 1f, IRIS_OPEN));
				}
				IRISES.put(d.getUUID(), new Iris(overlay, now + IRIS_OPEN + IRIS_HOLD));
				AuraFx.burst(level, d.getX(), d.getY(), d.getZ(), AuraFx.dust(aura.rgb, 1.6f), 50, 1.4, 0.12);
				AuraFx.ring(level, d.getX(), d.getY(), d.getZ(), 2.5, AuraFx.dust(aura.rgb, 1.2f), 30);
			}
		}
	}

	/** The overlay at {@code k} of the opening's size (thicker than the base pane so it sits in front on both sides). */
	private static void irisScale(Display.ItemDisplay d, float k, int ticks) {
		if (d == null || d.isRemoved()) return;
		DisplayAccessor acc = (DisplayAccessor) d;
		float kk = Math.max(0.001f, k);
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(Arena.PORTAL_W * kk, Arena.PORTAL_H * kk, 1.6f), null));
		acc.aurapoints$setInterpolationDuration(ticks);
		d.getEntityData().set(DisplayAccessor.aurapoints$startDelta(), 0, true);
	}

	/** Every tick: open irises past their hold close, closed ones go; the portals breathe a few particles out. */
	private static void tickIrises(MinecraftServer server, ServerLevel arena) {
		long now = server.getTickCount();
		for (var it = IRISES.entrySet().iterator(); it.hasNext(); ) {
			Iris iris = it.next().getValue();
			if (iris.overlay.isRemoved()) {
				it.remove();
				continue;
			}
			if (now == iris.closeAt) irisScale(iris.overlay, 0.02f, IRIS_CLOSE);
			if (now >= iris.closeAt + IRIS_CLOSE) {
				LIVE.remove(iris.overlay.getUUID());
				iris.overlay.discard();
				it.remove();
			}
		}
		// the swirl breathes: specks drift out of each portal someone is near
		if (now % 3 != 0) return;
		for (Vec3 c : new Vec3[]{new Vec3(Arena.ISLAND_X + 0.5, Arena.FLOOR + 1 + Arena.PORTAL_H / 2.0, 0.5),
				new Vec3(Arena.ALTAR_X - Arena.ROOM_R - 0.5, Arena.FLOOR + 1 + Arena.PORTAL_H / 2.0, 0.5)}) {
			if (arena.getNearestPlayer(c.x, c.y, c.z, 24, false) == null) continue;
			for (int k = 0; k < 2; k++) {
				double z = c.z + (arena.random.nextDouble() - 0.5) * (Arena.PORTAL_W - 1);
				double y = c.y + (arena.random.nextDouble() - 0.5) * (Arena.PORTAL_H - 1.5);
				double side = arena.random.nextBoolean() ? 1 : -1;
				arena.sendParticles(ParticleTypes.REVERSE_PORTAL, c.x + side * 0.2, y, z, 1, 0.05 * side, 0.02, 0, 0.05);
				if (arena.random.nextInt(4) == 0) arena.sendParticles(AuraFx.dust(0xB060FF, 0.8f), c.x + side * 0.3, y, z, 1, 0.1, 0.1, 0.1, 0);
			}
		}
	}

	// ---- the portals: walk into the island's arch to reach the hall, into the hall's door to get back ----

	private static final Map<UUID, Long> PORTAL_COOLDOWN = new HashMap<>();

	/** Where a portal at this spot sends you, or null: the island's arch to the hall, the hall's door to the island. */
	public static Vec3 portalTarget(Vec3 pos) {
		if (pos.y < Arena.FLOOR + 1 - 0.01 || pos.y >= Arena.FLOOR + 1 + Arena.PORTAL_H || Math.abs(pos.z - 0.5) > Arena.PORTAL_W / 2.0) return null;
		if (Math.abs(pos.x - (Arena.ISLAND_X + 0.5)) <= 0.35) return Arena.ROOM_ENTRY;
		if (Math.abs(pos.x - (Arena.ALTAR_X - Arena.ROOM_R - 0.5)) <= 0.35) return Arena.ISLAND_RETURN;
		return null;
	}

	private static void tickPortals(MinecraftServer server) {
		tickEntering(server);
		ServerLevel arena = Arena.level(server);
		if (arena == null) return;
		tickIrises(server, arena);
		if (arena.players().isEmpty()) return;
		tickHall(server, arena);
		long now = server.getTickCount();
		for (ServerPlayer p : arena.players()) {
			if (p.isSpectator() || inRitual(p) || ENTERING.containsKey(p.getUUID())) continue;
			Vec3 to = portalTarget(p.position());
			if (to == null || now < PORTAL_COOLDOWN.getOrDefault(p.getUUID(), 0L)) continue;
			PORTAL_COOLDOWN.put(p.getUUID(), now + ENTER_TICKS + 40);
			enter(p, to);
		}
	}

	// ---- going in (5.32): held at the portal for ENTER_TICKS while a spiral winds up round you and the screen goes purple ----

	static final int ENTER_TICKS = 18;
	private record Entering(Vec3 to, Vec3 at, long start) {
	}

	private static final Map<UUID, Entering> ENTERING = new HashMap<>();

	private static void enter(ServerPlayer p, Vec3 to) {
		Vec3 at = p.position();
		ENTERING.put(p.getUUID(), new Entering(to, at, p.getServer().getTickCount()));
		Ultimates.hold(p);
		Ultimates.move(p, Vec3.ZERO);
		AuraType aura = AuraState.get(p.getServer()).getOrCreate(p.getUUID()).aura;
		NetworkHandler.sendFx(p, FxPacket.PORTAL, ENTER_TICKS, Integer.toHexString(aura == null ? 0x9A3CFF : aura.rgb));
		ServerLevel level = p.serverLevel();
		level.playSound(null, at.x, at.y, at.z, AuraSounds.PORTAL_TRIGGER.value(), SoundSource.PLAYERS, 0.5f, 1.6f);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.BEACON_POWER_SELECT.value(), SoundSource.PLAYERS, 0.8f, 1.4f);
	}

	private static void tickEntering(MinecraftServer server) {
		if (ENTERING.isEmpty()) return;
		long now = server.getTickCount();
		for (var it = ENTERING.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Entering en = e.getValue();
			if (p == null || !p.isAlive() || !Arena.in(p)) {
				if (p != null) Ultimates.release(p);
				it.remove();
				continue;
			}
			int t = (int) (now - en.start);
			ServerLevel level = p.serverLevel();
			AuraType aura = AuraState.get(server).getOrCreate(p.getUUID()).aura;
			ParticleOptions dust = AuraFx.dust(aura == null ? 0x9A3CFF : aura.rgb, 1.2f);
			// a spiral winding up round you, tighter as it goes
			double k = t / (double) ENTER_TICKS;
			for (int arm = 0; arm < 3; arm++) {
				double ang = t * 0.55 + arm * 2 * Math.PI / 3;
				double rad = 1.3 - k * 0.9;
				double y = 0.1 + ((t * 0.12 + arm * 0.3) % 1.0) * 2.2;
				AuraFx.particles(level, dust, p.getX() + Math.cos(ang) * rad, p.getY() + y, p.getZ() + Math.sin(ang) * rad, 2, 0.03, 0.03, 0.03, 0);
				AuraFx.particles(level, ParticleTypes.REVERSE_PORTAL, p.getX() + Math.cos(ang) * rad, p.getY() + y, p.getZ() + Math.sin(ang) * rad, 1, 0, 0, 0, 0.02);
			}
			p.teleportTo(level, en.at.x, en.at.y, en.at.z, p.getYRot(), p.getXRot());
			Ultimates.move(p, Vec3.ZERO);
			if (t == ENTER_TICKS / 2) level.playSound(null, p.getX(), p.getY(), p.getZ(), AuraSounds.AMETHYST_BLOCK_RESONATE.value(), SoundSource.PLAYERS, 1.0f, 0.7f);
			if (t >= ENTER_TICKS) {
				it.remove();
				Ultimates.release(p);
				travel(p, en.to);
				flashPortals(p, en.at, en.to);
			}
		}
	}

	/** Through a portal: there, facing in, with a whoosh (both ends). */
	private static void travel(ServerPlayer p, Vec3 to) {
		ServerLevel level = p.serverLevel();
		boolean in = to == Arena.ROOM_ENTRY;
		AuraFx.burst(level, p.getX(), p.getY() + 1, p.getZ(), ParticleTypes.REVERSE_PORTAL, 40, 0.5, 0.1);
		p.teleportTo(level, to.x, to.y, to.z, in ? -90f : 90f, 0f);
		p.fallDistance = 0;
		level.playSound(null, to.x, to.y, to.z, AuraSounds.ENDERMAN_TELEPORT.value(), SoundSource.PLAYERS, 0.8f, 0.6f);
		AuraFx.burst(level, to.x, to.y + 1, to.z, ParticleTypes.REVERSE_PORTAL, 40, 0.5, 0.1);
		if (in) {
			AuraFx.title(p, Component.literal("The Altar Hall").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal("Talk to the keeper  ·  /aura leave to go back").withStyle(ChatFormatting.GRAY));
		}
	}

	/** /aura leave in the Altar Hall: back through the portal (a ritual running ends early). False when not in the hall. */
	public static boolean leaveRoom(ServerPlayer p) {
		if (!Arena.inAltarRoom(p)) return false;
		if (inRitual(p)) end(p.getServer(), true);
		travel(p, Arena.ISLAND_RETURN);
		PORTAL_COOLDOWN.put(p.getUUID(), (long) p.getServer().getTickCount() + 40);
		return true;
	}

	/** 5 s after the show: the way back, as a button. */
	private static void offerReturn(MinecraftServer server, UUID id) {
		AuraFx.later(server, 100, () -> {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || !Arena.inAltarRoom(p) || inRitual(p)) return;
			p.sendSystemMessage(Component.literal("✦ The ritual is done. ").withStyle(ChatFormatting.GOLD)
					.append(button("[Return to the arena]", ChatFormatting.GREEN, "/aura leave", "Back through the portal to the altar island")));
		});
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
		// the veins are the hall's: back to idle, from the top down
		if (r.veins != null) {
			for (Display.ItemDisplay[] col : r.veins) for (int k = VEIN_SECTIONS - 1; k >= 1; k--) light(col[k], false, 10 + (VEIN_SECTIONS - k) * 6);
		}
		r.veins = null;
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
			// its veins light up from the bottom in the aura's colour, a section every 5 ticks
			if (r.veins != null && t > lit && t <= lit + VEIN_SECTIONS * 5 && (t - lit) % 5 == 0) light(r.veins[i][(t - lit) / 5 - 1], true, 4);
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
				sound(level, at, AuraSounds.AMETHYST_CLUSTER_BREAK.value(), 2.0f, 0.6f);
				LIVE.remove(r.core.getUUID());
				r.core.discard();
				r.core = null;
			}
		}
	}

}
