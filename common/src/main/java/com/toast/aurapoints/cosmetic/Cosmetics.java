package com.toast.aurapoints.cosmetic;

import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraStats;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Cosmetics: looks only, no gameplay. Five kinds - halo styles, sprint trails, kill effects, titles and
 * duel victory poses - bought with Aura Points in /aura cosmetics, or earned (weekly challenges, hunts,
 * reactions) when the price is 0. One of each kind can be worn. Everything is drawn by the server, so
 * no client needs an update for a new one. Saved in AuraPlayerData (cosmetics, equipped).
 */
public final class Cosmetics {

	private Cosmetics() {
	}

	public enum Kind {
		HALO("Halo Styles"), TRAIL("Sprint Trails"), KILL("Kill Effects"), TITLE("Titles"), POSE("Victory Poses");

		public final String displayName;

		Kind(String displayName) {
			this.displayName = displayName;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** price > 0: bought; price 0: earned when {@code earned} holds ({@code how} says how). */
	public enum Cosmetic {
		HALO_CROWN(Kind.HALO, "Crown", 300, null, null),
		HALO_TWIN(Kind.HALO, "Twin Rings", 400, null, null),
		HALO_ORBIT(Kind.HALO, "Orbit", 500, null, null),
		HALO_STARLIGHT(Kind.HALO, "Starlight", 0, "Finish 6 weekly challenges", p -> data(p).weeklyCompleted >= 6),

		TRAIL_HEARTS(Kind.TRAIL, "Hearts", 200, null, null),
		TRAIL_NOTES(Kind.TRAIL, "Music Notes", 200, null, null),
		TRAIL_CHERRY(Kind.TRAIL, "Cherry Petals", 300, null, null),
		TRAIL_SOUL_FIRE(Kind.TRAIL, "Soul Fire", 350, null, null),
		TRAIL_ENCHANT(Kind.TRAIL, "Runes", 300, null, null),
		TRAIL_GOLDEN(Kind.TRAIL, "Golden Path", 0, "Finish 3 weekly challenges", p -> data(p).weeklyCompleted >= 3),

		KILL_FIREWORK(Kind.KILL, "Firework", 250, null, null),
		KILL_SOUL(Kind.KILL, "Soul Release", 250, null, null),
		KILL_CONFETTI(Kind.KILL, "Confetti", 300, null, null),
		KILL_THUNDERCLAP(Kind.KILL, "Thunderclap", 0, "Slay 25 aura-touched mobs", p -> stat(p, AuraStats.TOUCHED_KILLS) >= 25),

		TITLE_ELEMENTALIST(Kind.TITLE, "the Elementalist", 500, null, null),
		TITLE_STORM_CHASER(Kind.TITLE, "Storm Chaser", 300, null, null),
		TITLE_EMBER_HEART(Kind.TITLE, "Ember Heart", 300, null, null),
		TITLE_MOB_HUNTER(Kind.TITLE, "Mob Hunter", 0, "Slay 10 aura-touched mobs", p -> stat(p, AuraStats.TOUCHED_KILLS) >= 10),
		TITLE_ALCHEMIST(Kind.TITLE, "Alchemist", 0, "Set off 50 elemental reactions", p -> reactions(p) >= 50),
		TITLE_CHAMPION(Kind.TITLE, "Weekly Champion", 0, "Finish all three weekly challenges in one week", p -> data(p).weeklyFullWeeks >= 1),
		TITLE_WAVE_BREAKER(Kind.TITLE, "Wave Breaker", 0, "Clear wave 20 in Wave Survival", p -> bestWave(p) >= 20),

		POSE_SKY_CALL(Kind.POSE, "Sky Call", 300, null, null),
		POSE_KNEEL(Kind.POSE, "Kneel", 200, null, null),
		POSE_ARMS_WIDE(Kind.POSE, "Arms Wide", 200, null, null),
		POSE_RISING_PALMS(Kind.POSE, "Rising Palms", 250, null, null),
		POSE_STRETCH(Kind.POSE, "Lazy Stretch", 150, null, null);

		public final Kind kind;
		public final String displayName;
		final int basePrice;
		final String how;
		final Predicate<ServerPlayer> earned;

		Cosmetic(Kind kind, String displayName, int basePrice, String how, Predicate<ServerPlayer> earned) {
			this.kind = kind;
			this.displayName = displayName;
			this.basePrice = basePrice;
			this.how = how;
			this.earned = earned;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public boolean earnOnly() {
			return basePrice <= 0;
		}

		public int price() {
			return (int) Math.max(1, Math.round(basePrice * Math.max(0, AuraConfig.COSMETIC_PRICE_MULTIPLIER)));
		}

		public static Cosmetic byId(String id) {
			for (Cosmetic c : values()) if (c.id().equals(id)) return c;
			return null;
		}
	}

	private static AuraPlayerData data(ServerPlayer p) {
		return AuraState.get(p.getServer()).getOrCreate(p.getUUID());
	}

	private static int bestWave(ServerPlayer p) {
		if (p.getServer() == null) return 0;
		var state = AuraState.get(p.getServer());
		var solo = state.waveRecords.get(p.getUUID());
		var party = state.partyWaveRecords.get(p.getUUID());
		return (int) Math.max(solo == null ? 0 : solo.best, party == null ? 0 : party.best);
	}

	private static int stat(ServerPlayer p, net.minecraft.resources.ResourceLocation id) {
		return p.getStats().getValue(Stats.CUSTOM.get(id));
	}

	private static int reactions(ServerPlayer p) {
		int n = 0;
		for (var id : AuraStats.reactionStats()) n += stat(p, id);
		return n;
	}

	public static boolean owns(AuraPlayerData data, Cosmetic c) {
		return data.cosmetics.contains(c.id());
	}

	/** What the player wears of this kind, or null. */
	public static Cosmetic worn(AuraPlayerData data, Kind kind) {
		String id = data.equipped.get(kind.id());
		Cosmetic c = id == null ? null : Cosmetic.byId(id);
		return c != null && c.kind == kind && owns(data, c) ? c : null;
	}

	// ---------------------------------------------------------------
	// Earning
	// ---------------------------------------------------------------

	/** Grants every earn-only cosmetic whose goal the player has reached (called after anything that could reach one). */
	public static void checkEarned(ServerPlayer player) {
		if (!AuraConfig.COSMETICS_ENABLED || player.getServer() == null) return;
		AuraPlayerData data = data(player);
		for (Cosmetic c : Cosmetic.values()) {
			if (!c.earnOnly() || owns(data, c) || !c.earned.test(player)) continue;
			data.cosmetics.add(c.id());
			AuraState.get(player.getServer()).markDirty();
			player.sendSystemMessage(Component.literal("✦ Cosmetic earned: ").withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(Component.literal(c.displayName + " (" + c.kind.displayName + ")").withStyle(ChatFormatting.GOLD))
					.append(Component.literal(" [Wear it]").withStyle(s -> s.withColor(ChatFormatting.GREEN)
							.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aura cosmetics equip " + c.id())))));
			AuraFx.sound(player.serverLevel(), player, AuraSounds.UI_STUNT.value(), 0.8f, 1.3f);
		}
	}

	// ---------------------------------------------------------------
	// The menu (/aura cosmetics)
	// ---------------------------------------------------------------

	public static void showMenu(ServerPlayer player, Kind only) {
		AuraPlayerData data = data(player);
		player.sendSystemMessage(Component.literal("=== Cosmetics ===").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
				.append(Component.literal("  (looks only - you have " + AuraCurrency.get(player) + " Aura)").withStyle(ChatFormatting.DARK_GRAY)));
		for (Kind kind : Kind.values()) {
			if (only != null && kind != only) continue;
			Cosmetic worn = worn(data, kind);
			MutableComponent head = Component.literal(kind.displayName + ": ").withStyle(ChatFormatting.GOLD)
					.append(Component.literal(worn == null ? "none" : worn.displayName).withStyle(ChatFormatting.WHITE));
			if (worn != null) head.append(button(" [Take off]", ChatFormatting.GRAY, "/aura cosmetics unequip " + kind.id(), "Wear nothing of this kind"));
			player.sendSystemMessage(head);
			for (Cosmetic c : Cosmetic.values()) {
				if (c.kind != kind) continue;
				MutableComponent line = Component.literal("  - " + c.displayName).withStyle(ChatFormatting.AQUA);
				if (c == worn) line.append(Component.literal(" [Worn]").withStyle(ChatFormatting.DARK_GREEN));
				else if (owns(data, c)) line.append(button(" [Wear]", ChatFormatting.GREEN, "/aura cosmetics equip " + c.id(), "Wear " + c.displayName));
				else if (c.earnOnly()) line.append(Component.literal(" [Earn: " + c.how + "]").withStyle(ChatFormatting.DARK_PURPLE));
				else line.append(button(" [Buy - " + c.price() + " Aura]", ChatFormatting.YELLOW, "/aura cosmetics buy " + c.id(), "Buy and wear " + c.displayName));
				player.sendSystemMessage(line);
			}
		}
	}

	private static Component button(String text, ChatFormatting color, String command, String hover) {
		return Component.literal(text).withStyle(s -> s.withColor(color)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
	}

	/** Returns a message for the player (failure when it starts with "!"). */
	public static String buy(ServerPlayer player, Cosmetic c) {
		AuraPlayerData data = data(player);
		if (owns(data, c)) return equip(player, c);
		if (c.earnOnly()) return "!" + c.displayName + " can't be bought: " + c.how + ".";
		int price = c.price();
		if (!AuraCurrency.take(player, price)) return "!" + c.displayName + " costs " + price + " Aura, you have " + AuraCurrency.get(player) + ".";
		data.cosmetics.add(c.id());
		AuraState.get(player.getServer()).markDirty();
		AuraFx.sound(player.serverLevel(), player, AuraSounds.UI_POINTS.value(), 0.8f, 1.2f);
		equip(player, c);
		return "Bought " + c.displayName + " for " + price + " Aura - you're wearing it now.";
	}

	public static String equip(ServerPlayer player, Cosmetic c) {
		AuraPlayerData data = data(player);
		if (!owns(data, c)) return "!You don't have " + c.displayName + " yet.";
		data.equipped.put(c.kind.id(), c.id());
		AuraState.get(player.getServer()).markDirty();
		if (c.kind == Kind.TITLE) refreshName(player);
		if (c.kind == Kind.POSE) victory(player); // a preview
		return "Wearing " + c.displayName + ".";
	}

	public static String unequip(ServerPlayer player, Kind kind) {
		AuraPlayerData data = data(player);
		data.equipped.remove(kind.id());
		AuraState.get(player.getServer()).markDirty();
		if (kind == Kind.TITLE) refreshName(player);
		return "No " + kind.displayName.toLowerCase(Locale.ROOT) + " worn.";
	}

	// ---------------------------------------------------------------
	// Titles: in front of the name in chat and the tab list
	// ---------------------------------------------------------------

	private static void refreshName(ServerPlayer player) {
		com.toast.aurapoints.shim.Platform.get().refreshDisplayName(player);
		com.toast.aurapoints.shim.Platform.get().refreshTabListName(player);
	}

	/** PlayerEvent.NameFormat / TabListNameFormat: the name with the worn title in front, or null to leave it. */
	public static Component titled(ServerPlayer player, Component name) {
		if (!AuraConfig.COSMETICS_ENABLED || player.getServer() == null) return null;
		Cosmetic title = worn(data(player), Kind.TITLE);
		if (title == null) return null;
		return Component.literal("[" + title.displayName + "] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(name.copy());
	}

	// ---------------------------------------------------------------
	// Trails, kill effects and victory poses
	// ---------------------------------------------------------------

	/** Every tick per player: the worn sprint trail behind the feet, for everyone around. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		if (!AuraConfig.COSMETICS_ENABLED || now % 2 != 0) return;
		if (!player.isSprinting() || !player.onGround() || player.isInvisible() || player.isSpectator() || player.isSwimming()) return;
		Cosmetic trail = worn(data, Kind.TRAIL);
		if (trail == null) return;
		Vec3 back = Vec3.directionFromRotation(0, player.getYRot()).scale(-0.4);
		double x = player.getX() + back.x, y = player.getY() + 0.15, z = player.getZ() + back.z;
		ServerLevel level = player.serverLevel();
		ParticleOptions particle = switch (trail) {
			case TRAIL_HEARTS -> ParticleTypes.HEART;
			case TRAIL_NOTES -> ParticleTypes.NOTE;
			case TRAIL_CHERRY -> ParticleTypes.CHERRY_LEAVES;
			case TRAIL_SOUL_FIRE -> ParticleTypes.SOUL_FIRE_FLAME;
			case TRAIL_ENCHANT -> ParticleTypes.ENCHANT;
			case TRAIL_GOLDEN -> AuraFx.dust(0xFFD24A, 1.1f);
			default -> null;
		};
		if (particle == null) return;
		// hearts and notes are big: fewer of them
		if ((trail == Cosmetic.TRAIL_HEARTS || trail == Cosmetic.TRAIL_NOTES) && now % 6 != 0) return;
		AuraFx.particles(level, particle, x, y, z, trail == Cosmetic.TRAIL_ENCHANT ? 4 : 1, 0.15, 0.05, 0.15, 0.0);
		if (trail == Cosmetic.TRAIL_GOLDEN && now % 4 == 0) AuraFx.particles(level, ParticleTypes.WAX_OFF, x, y + 0.2, z, 1, 0.2, 0.1, 0.2, 0.0);
	}

	/** A kill by {@code killer}: their worn kill effect where {@code dead} fell. */
	public static void onKill(ServerPlayer killer, LivingEntity dead) {
		if (!AuraConfig.COSMETICS_ENABLED || killer.getServer() == null || !(dead.level() instanceof ServerLevel level)) return;
		Cosmetic effect = worn(data(killer), Kind.KILL);
		if (effect == null) return;
		Vec3 c = AuraFx.chest(dead);
		switch (effect) {
			case KILL_FIREWORK -> {
				AuraFx.burst(level, c.x, c.y + 0.5, c.z, ParticleTypes.FIREWORK, 40, 0.2, 0.25);
				AuraFx.sound(level, dead, AuraSounds.FIREWORK_ROCKET_TWINKLE.value(), 1.0f, 1.0f);
			}
			case KILL_SOUL -> {
				AuraFx.particles(level, ParticleTypes.SOUL, c.x, c.y, c.z, 20, 0.3, 0.5, 0.3, 0.05);
				AuraFx.particles(level, ParticleTypes.SCULK_SOUL, c.x, c.y + 0.5, c.z, 6, 0.2, 0.3, 0.2, 0.03);
				AuraFx.sound(level, dead, AuraSounds.SOUL_ESCAPE.value(), 1.2f, 0.8f);
			}
			case KILL_CONFETTI -> {
				int[] colors = {0xFF4A4A, 0xFFD24A, 0x4AFF7A, 0x4AB8FF, 0xC04AFF};
				for (int rgb : colors) AuraFx.burst(level, c.x, c.y + 0.4, c.z, AuraFx.dust(rgb, 1.0f), 10, 0.6, 0.0);
				AuraFx.sound(level, dead, AuraSounds.FIREWORK_ROCKET_TWINKLE.value(), 0.8f, 1.6f);
			}
			case KILL_THUNDERCLAP -> {
				var bolt = EntityType.LIGHTNING_BOLT.create(level);
				if (bolt != null) {
					bolt.moveTo(dead.getX(), dead.getY(), dead.getZ());
					bolt.setVisualOnly(true);
					level.addFreshEntity(bolt);
				}
			}
			default -> {
			}
		}
	}

	/** Duel won (or a pose bought): the worn victory pose. */
	public static void victory(ServerPlayer player) {
		if (!AuraConfig.COSMETICS_ENABLED || player.getServer() == null) return;
		Cosmetic pose = worn(data(player), Kind.POSE);
		if (pose == null) return;
		switch (pose) {
			case POSE_SKY_CALL -> NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_THUNDERBOLT, 2);
			case POSE_KNEEL -> NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_PLANT, 0);
			case POSE_ARMS_WIDE -> NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_HEAT_WAVE, 0);
			case POSE_RISING_PALMS -> NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_HEALING_TIDE, 0);
			case POSE_STRETCH -> NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_IDLE, BendingAnimPacket.Idle.STRETCH.ordinal());
			default -> {
			}
		}
	}
}
