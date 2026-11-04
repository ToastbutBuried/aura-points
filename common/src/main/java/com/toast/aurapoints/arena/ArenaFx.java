package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraLabel;
import com.toast.aurapoints.ability.AuraType;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.toast.aurapoints.arena.Arena.FLOOR;
import static com.toast.aurapoints.arena.Arena.RING_FLOOR;
import static com.toast.aurapoints.arena.Arena.RING_R;
import static com.toast.aurapoints.arena.Arena.RING_Z;

/**
 * The arena's living parts, every tick while anyone is in the dimension:
 *  - animated displays (tagged "aa_…" by ArenaBuilder): the lobby's spinning aura core and its orbiting
 *    crystals, the crystals circling over the duel ring, the spinning items on the hall's kiosks;
 *  - the duel ring's glass lights chasing round (or flashing one color for a moment);
 *  - motes drifting under the Training Hall's skylight;
 * and the one-off effects of a duel: the "FIGHT!" shockwave, lightning, fireworks.
 */
public final class ArenaFx {

	private ArenaFx() {
	}

	/** The aura colors as glass, in the same order as ArenaBuilder's rings. */
	static final Block[] GLASS = {Blocks.PURPLE_STAINED_GLASS, Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS,
			Blocks.WHITE_STAINED_GLASS, Blocks.CYAN_STAINED_GLASS, Blocks.LIME_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS,
			Blocks.MAGENTA_STAINED_GLASS, Blocks.BROWN_STAINED_GLASS, Blocks.WHITE_STAINED_GLASS, Blocks.RED_STAINED_GLASS, Blocks.BLACK_STAINED_GLASS};
	static final int[] RGB = {0xB04FE0, 0x5FC8FF, 0xFF8A2A, 0xF4F8FF, 0x7FE9FF, 0x7CD957, 0xFFE347,
			0xD24CF0, 0xB08254, 0xFFF1C2, 0xD3202C, 0x6A34B8};

	/** Where the lobby's core floats, and the centre of the crystals over the ring. */
	static final Vec3 CORE = new Vec3(0.5, FLOOR + 7, 0.5);
	static final Vec3 RING_SKY = new Vec3(0.5, FLOOR + 13, RING_Z + 0.5);
	static final int ORBS = 7;

	// ---------------------------------------------------------------
	// Animated displays
	// ---------------------------------------------------------------

	private static final List<Display> ANIMATED = new ArrayList<>();

	/** An arena display joined the level (placed now, or loaded with its chunk): animate it if it's tagged for that. */
	public static void track(Entity e) {
		if (!(e instanceof Display display) || e.level().dimension() != Arena.KEY) return;
		for (String tag : e.getTags()) {
			if (tag.startsWith("aa_")) {
				ANIMATED.add(display);
				return;
			}
		}
	}

	private static void animate(Display d, long t) {
		String kind = null;
		int index = 0;
		for (String tag : d.getTags()) {
			if (!tag.startsWith("aa_")) continue;
			int us = tag.lastIndexOf('_');
			if (us > 2 && Character.isDigit(tag.charAt(tag.length() - 1))) {
				kind = tag.substring(3, us);
				index = Integer.parseInt(tag.substring(us + 1));
			} else {
				kind = tag.substring(3);
			}
		}
		if (kind == null) return;
		switch (kind) {
			case "core" -> move(d, CORE.x, CORE.y + Math.sin(t * 0.05) * 0.25, CORE.z, t * 2.5f);
			case "lorb" -> {
				double a = t * 0.035 + index * Math.PI * 2 / ORBS;
				move(d, CORE.x + Math.cos(a) * 3.4, CORE.y + Math.sin(t * 0.07 + index) * 0.5, CORE.z + Math.sin(a) * 3.4, -t * 4f);
			}
			case "rorb" -> {
				double a = -t * 0.012 + index * Math.PI * 2 / ORBS;
				move(d, RING_SKY.x + Math.cos(a) * 19, RING_SKY.y + Math.sin(t * 0.04 + index * 0.9) * 1.2, RING_SKY.z + Math.sin(a) * 19, t * 3f);
			}
			case "kiosk" -> {
				Vec3 at = TrainingHall.Control.values()[index].itemPos();
				move(d, at.x, at.y + Math.sin(t * 0.08 + index) * 0.08, at.z, t * 4f);
			}
			default -> {
			}
		}
	}

	private static void move(Display d, double x, double y, double z, float yaw) {
		d.moveTo(x, y, z, yaw % 360f, 0f);
	}

	// ---------------------------------------------------------------
	// The duel ring's glass lights
	// ---------------------------------------------------------------

	/** The glass ring's blocks and their angle round the centre (0..1). */
	private static List<int[]> ringGlass;
	private static double[] ringAngle;
	private static long flashUntil;
	private static Block flash;
	private static int chaseSpeed = 1;
	private static long fastUntil;

	/** Is this pit floor block (x, offset from the ring's centre) part of the glass ring? */
	static boolean isRingGlass(double d) {
		return d > RING_R - 2.6 && d <= RING_R - 1.4;
	}

	private static void initRing() {
		ringGlass = new ArrayList<>();
		List<Double> angles = new ArrayList<>();
		for (int x = -RING_R; x <= RING_R; x++) {
			for (int dz = -RING_R; dz <= RING_R; dz++) {
				double d = Math.sqrt(x * x + dz * dz);
				if (!isRingGlass(d)) continue;
				ringGlass.add(new int[]{x, dz});
				angles.add((Math.atan2(dz, x) + Math.PI) / (2 * Math.PI));
			}
		}
		ringAngle = angles.stream().mapToDouble(Double::doubleValue).toArray();
	}

	/** The ring all one aura color for a moment (a knockout: the winner's). */
	static void flashRing(Block glass, int ticks, long now) {
		flash = glass;
		flashUntil = now + ticks;
	}

	/** The lights racing round for a while (a victory). */
	static void fastChase(int ticks, long now) {
		fastUntil = now + ticks;
	}

	private static void tickRing(ServerLevel level, long t) {
		if (ringGlass == null) initRing();
		double phase = t * (t < fastUntil ? 0.02 : 0.004);
		for (int i = 0; i < ringGlass.size(); i++) {
			int[] p = ringGlass.get(i);
			Block block = t < flashUntil ? flash : GLASS[(int) Math.floor((ringAngle[i] + phase) * GLASS.length) % GLASS.length];
			BlockPos pos = new BlockPos(p[0], RING_FLOOR, RING_Z + p[1]);
			if (!level.isLoaded(pos) || level.getBlockState(pos).is(block)) continue;
			level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	// ---------------------------------------------------------------
	// Every tick
	// ---------------------------------------------------------------

	static void tick(ServerLevel level) {
		if (level.players().isEmpty()) return;
		long t = level.getGameTime();
		if (t % 2 == 0) {
			for (Iterator<Display> it = ANIMATED.iterator(); it.hasNext(); ) {
				Display d = it.next();
				if (d.isRemoved()) {
					it.remove();
					continue;
				}
				animate(d, t);
			}
		}
		if (t % 3 == 0) tickRing(level, t);
		// the crystals over the ring trail a little of their color
		if (t % 4 == 0) {
			for (Display d : ANIMATED) {
				if (d.getTags().stream().noneMatch(s -> s.startsWith("aa_rorb_"))) continue;
				int i = Integer.parseInt(d.getTags().stream().filter(s -> s.startsWith("aa_rorb_")).findFirst().get().substring(8));
				AuraFx.particles(level, AuraFx.dust(RGB[i % RGB.length], 1.6f), d.getX(), d.getY() + 0.5, d.getZ(), 2, 0.2, 0.2, 0.2, 0);
			}
			AuraFx.particles(level, ParticleTypes.END_ROD, CORE.x, CORE.y + 0.6, CORE.z, 1, 0.3, 0.3, 0.3, 0.02);
		}
		// the lobby: the fountain's spray, sparkles circling the NPCs
		if (t % 3 == 0) {
			AuraFx.particles(level, ParticleTypes.SPLASH, 0.5, FLOOR + 3.9, 15.5, 6, 0.35, 0.1, 0.35, 0.3);
			AuraFx.particles(level, ParticleTypes.FALLING_WATER, 0.5, FLOOR + 3.6, 15.5, 4, 1.2, 0.2, 1.2, 0);
		}
		if (t % 5 == 0) {
			for (int i = 0; i < NpcKind.LOBBY.length; i++) {
				var p = ArenaBuilder.pedestal(i);
				double a = t * 0.15 + i;
				AuraFx.particles(level, ParticleTypes.END_ROD, p.getX() + 0.5 + Math.cos(a) * 0.9, FLOOR + 2.2 + Math.sin(t * 0.07 + i) * 0.8,
						p.getZ() + 0.5 + Math.sin(a) * 0.9, 1, 0, 0, 0, 0);
			}
		}
		// motes under the hall's skylight, while someone's in there
		if (t % 6 == 0 && level.players().stream().anyMatch(Arena::inTraining)) {
			var random = level.getRandom();
			for (int k = 0; k < 3; k++) {
				double x = random.nextDouble() * 6 - 2.5, z = Arena.TRAIN_Z0 + 6 + random.nextDouble() * (Arena.TRAIN_Z1 - Arena.TRAIN_Z0 - 12);
				AuraFx.particles(level, ParticleTypes.END_ROD, x, FLOOR + 3 + random.nextDouble() * 5, z, 1, 0, 0, 0, 0.01);
			}
		}
	}

	static void clearAll() {
		ANIMATED.clear();
		flashUntil = fastUntil = 0;
	}

	// ---------------------------------------------------------------
	// One-off effects
	// ---------------------------------------------------------------

	/** Index of an aura's color (0..11), or -1 for none. */
	static int colorOf(AuraType aura) {
		if (aura == null) return -1;
		return switch (aura) {
			case SHADOW -> 0;
			case WATER -> 1;
			case FIRE -> 2;
			case WIND -> 3;
			case FROST -> 4;
			case VERDANT -> 5;
			case THUNDER -> 6;
			case RESONANCE -> 7;
			case EARTH -> 8;
			case LIGHT -> 9;
			case BLOOD -> 10;
		};
	}

	static int rgbOf(AuraType aura) {
		int i = colorOf(aura);
		return i < 0 ? (aura == null ? 0xFFFFFF : AuraLabel.accent(aura)) : RGB[i];
	}

	static Block glassOf(AuraType aura) {
		int i = colorOf(aura);
		return i < 0 ? Blocks.WHITE_STAINED_GLASS : GLASS[i];
	}

	/** A ring of light rushing out from the pit's centre to its wall. */
	static void shockwave(ServerLevel level, int rgb) {
		var server = level.getServer();
		double y = RING_FLOOR + 1.2;
		for (int step = 0; step <= 8; step++) {
			final double r = 2 + step * (RING_R - 3) / 8.0;
			AuraFx.later(server, step, () -> {
				AuraFx.ring(level, 0.5, y, RING_Z + 0.5, r, AuraFx.dust(rgb, 2.0f), (int) (r * 5));
				AuraFx.ring(level, 0.5, y + 0.3, RING_Z + 0.5, r, ParticleTypes.CLOUD, (int) (r * 1.5));
			});
		}
	}

	/** A pillar of colored light climbing out of a pad (the duellers' corners during the intro and countdown). */
	static void padColumn(ServerLevel level, Vec3 pad, int rgb) {
		for (int k = 0; k < 6; k++) {
			double a = level.getGameTime() * 0.4 + k * Math.PI / 3;
			AuraFx.particles(level, AuraFx.dust(rgb, 1.4f), pad.x + Math.cos(a) * 1.2, pad.y + (k % 3) * 0.9, pad.z + Math.sin(a) * 1.2, 1, 0, 0.3, 0, 0);
		}
		AuraFx.particles(level, ParticleTypes.END_ROD, pad.x, pad.y + 0.2, pad.z, 1, 0.4, 0.1, 0.4, 0.06);
	}

	/** A bolt that only looks the part: no fire, no damage. */
	static void lightning(ServerLevel level, Vec3 at) {
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
		if (bolt == null) return;
		bolt.setVisualOnly(true);
		bolt.moveTo(at.x, at.y, at.z);
		level.addFreshEntity(bolt);
	}

	/** A firework from {@code at}, bursting in these colors (arena players can't be hurt by it outside a round). */
	static void firework(ServerLevel level, Vec3 at, int... colors) {
		ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
		rocket.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(new FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL,
				IntList.of(colors), IntList.of(0xFFFFFF), true, true))));
		level.addFreshEntity(new FireworkRocketEntity(level, at.x, at.y, at.z, rocket));
	}

	/** A victory: fireworks all round the rim in the winner's color and gold. */
	static void victory(ServerLevel level, int rgb) {
		var server = level.getServer();
		for (int k = 0; k < 8; k++) {
			final double a = k * Math.PI / 4;
			AuraFx.later(server, k * 4, () -> firework(level, new Vec3(0.5 + Math.cos(a) * (RING_R + 3), FLOOR + 1.5, RING_Z + 0.5 + Math.sin(a) * (RING_R + 3)), rgb, 0xFFC83D));
		}
		fastChase(120, level.getGameTime());
	}

	static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.AMBIENT, volume, pitch);
	}

	/** The click of a kiosk: sparkles off its item and a chime. */
	static void click(ServerLevel level, Vec3 at) {
		AuraFx.particles(level, ParticleTypes.ENCHANT, at.x, at.y + 0.3, at.z, 30, 0.4, 0.5, 0.4, 0.6);
		AuraFx.particles(level, ParticleTypes.HAPPY_VILLAGER, at.x, at.y, at.z, 6, 0.4, 0.4, 0.4, 0);
		sound(level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 1.3f);
		sound(level, at, SoundEvents.NOTE_BLOCK_CHIME.value(), 0.6f, 1.6f);
	}

	/** Players in the arena (for effects that only they should see). */
	static List<ServerPlayer> watchers(ServerLevel level) {
		return level.players();
	}

	/** An arena announcement: a short title or a chat line, as the player's client config (arenaMessages) says. */
	public static void title(ServerPlayer player, net.minecraft.network.chat.Component title, net.minecraft.network.chat.Component subtitle) {
		com.toast.aurapoints.shim.neoforge.network.PacketDistributor.sendToPlayer(player, new com.toast.aurapoints.network.ArenaTitlePacket(
				title, subtitle == null ? net.minecraft.network.chat.Component.empty() : subtitle, false));
	}

	/** A countdown tick (3, 2, 1, FIGHT!): a one-second title, or the action bar in chat mode. */
	public static void countdown(ServerPlayer player, net.minecraft.network.chat.Component text) {
		com.toast.aurapoints.shim.neoforge.network.PacketDistributor.sendToPlayer(player, new com.toast.aurapoints.network.ArenaTitlePacket(
				text, net.minecraft.network.chat.Component.empty(), true));
	}
}
