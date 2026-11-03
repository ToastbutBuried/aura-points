package com.toast.aurapoints.ability;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Visual and sound effects. Everything here runs on the server and goes out
 * as normal particle/sound packets, so clients need nothing extra to see it.
 *
 * Also holds a tiny tick scheduler (for effects that play out over several
 * ticks, like an expanding ring) and per-player trails. Both live in memory
 * only and are driven by {@link #tick} from the server tick.
 */
public final class AuraFx {

	private AuraFx() {
	}

	// ---------------------------------------------------------------
	// Scheduler and trails
	// ---------------------------------------------------------------

	private record Scheduled(long due, Runnable action) {
	}

	private record Trail(ParticleOptions particle, int count, long until) {
	}

	private static final List<Scheduled> QUEUE = new ArrayList<>();
	private static final Map<UUID, Trail> TRAILS = new HashMap<>();
	private static final Map<String, Long> THROTTLE = new HashMap<>();

	/** Runs {@code action} on the server thread {@code delayTicks} ticks from now. */
	public static void later(MinecraftServer server, int delayTicks, Runnable action) {
		QUEUE.add(new Scheduled(server.getTickCount() + delayTicks, action));
	}

	/** Leaves {@code count} particles behind the player every tick for {@code ticks} ticks. */
	public static void trail(ServerPlayer player, ParticleOptions particle, int count, int ticks) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		TRAILS.put(player.getUUID(), new Trail(particle, count, server.getTickCount() + ticks));
	}

	/**
	 * True at most once per {@code ticks} for this player and key, so effects on
	 * frequent events (every hit, every heal) can't flood the screen.
	 */
	public static boolean ready(ServerPlayer player, String key, int ticks) {
		MinecraftServer server = player.getServer();
		if (server == null) return false;
		long now = server.getTickCount();
		String id = player.getUUID() + key;
		Long last = THROTTLE.get(id);
		if (last != null && now - last < ticks && now >= last) return false;
		THROTTLE.put(id, now);
		return true;
	}

	/** Call once per server tick. */
	public static void tick(MinecraftServer server) {
		long now = server.getTickCount();

		if (!QUEUE.isEmpty()) {
			List<Scheduled> due = new ArrayList<>();
			for (Iterator<Scheduled> it = QUEUE.iterator(); it.hasNext(); ) {
				Scheduled s = it.next();
				// the second check flushes entries if the tick counter ever went backwards
				if (s.due() <= now || s.due() > now + 1200) {
					due.add(s);
					it.remove();
				}
			}
			for (Scheduled s : due) {
				// one broken effect must not take the server tick (and the server) down with it
				try {
					s.action().run();
				} catch (Exception e) {
					com.toast.aurapoints.AuraPoints.LOGGER.error("Delayed Aura effect failed", e);
				}
			}
		}

		// throttle stamps are only needed for a short while; drop old ones so the map never grows
		if (now % 1200 == 0 && !THROTTLE.isEmpty()) THROTTLE.values().removeIf(t -> now - t > 1200 || t > now);

		if (!TRAILS.isEmpty()) {
			for (Iterator<Map.Entry<UUID, Trail>> it = TRAILS.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<UUID, Trail> e = it.next();
				ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
				Trail t = e.getValue();
				if (player == null || now > t.until()) {
					it.remove();
					continue;
				}
				AuraFx.particles(player.serverLevel(), t.particle(), player.getX(), player.getY() + 0.3, player.getZ(),
						t.count(), 0.15, 0.15, 0.15, 0.0);
			}
		}
	}

	/**
	 * The server is stopping: drop every delayed effect and trail. Single player keeps these statics into the
	 * next world, where the tick counter starts over and the old effects would all fire at once.
	 */
	public static void clearAll() {
		PENDING.clear();
		QUEUE.clear();
		TRAILS.clear();
		THROTTLE.clear();
	}

	// ---------------------------------------------------------------
	// Particle batching
	// ---------------------------------------------------------------

	/**
	 * Particle packets waiting for the end of the tick, per player. Every ability particle goes through
	 * {@link #particles}: instead of one packet (and one network flush) per particle per player, each player
	 * gets one bundle per tick. Same rules as vanilla: same level, within 32 blocks (512 when forced).
	 */
	private static final Map<ServerPlayer, List<net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>>> PENDING =
			new java.util.IdentityHashMap<>();
	/** Vanilla's limit for one bundle is 4096 packets. */
	private static final int BUNDLE_MAX = 4000;

	/** Like {@code level.sendParticles(particle, x, y, z, count, dx, dy, dz, speed)}, batched. */
	/** More particles while a mastered aura's ability goes off (set round AuraAbilities.tryActivate; 1 = as asked). */
	public static float boost = 1f;

	private static int boosted(int count) {
		return count <= 0 || boost <= 1f ? count : Math.round(count * boost);
	}

	public static void particles(ServerLevel level, ParticleOptions particle, double x, double y, double z, int count,
								 double dx, double dy, double dz, double speed) {
		count = boosted(count);
		var packet = new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(particle, false, x, y, z,
				(float) dx, (float) dy, (float) dz, (float) speed, count);
		List<ServerPlayer> players = level.players();
		for (int i = 0; i < players.size(); i++) queue(level, players.get(i), false, x, y, z, packet);
	}

	/** Like {@code level.sendParticles(player, particle, force, x, y, z, count, dx, dy, dz, speed)}, batched. */
	public static void particles(ServerLevel level, ServerPlayer player, ParticleOptions particle, boolean force, double x, double y, double z,
								 int count, double dx, double dy, double dz, double speed) {
		count = boosted(count);
		queue(level, player, force, x, y, z, new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(particle, force, x, y, z,
				(float) dx, (float) dy, (float) dz, (float) speed, count));
	}

	private static void queue(ServerLevel level, ServerPlayer player, boolean force, double x, double y, double z,
							  net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket packet) {
		if (player.level() != level) return;
		double far = force ? 512.0 : 32.0;
		// vanilla measures from the middle of the block the player stands in
		double px = player.blockPosition().getX() + 0.5 - x, py = player.blockPosition().getY() + 0.5 - y, pz = player.blockPosition().getZ() + 0.5 - z;
		if (px * px + py * py + pz * pz >= far * far) return;
		PENDING.computeIfAbsent(player, k -> new ArrayList<>()).add(packet);
	}

	/** Sends everything batched this tick (call at the very end of the server tick). */
	public static void flushParticles() {
		if (PENDING.isEmpty()) return;
		for (Map.Entry<ServerPlayer, List<net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>>> e : PENDING.entrySet()) {
			ServerPlayer player = e.getKey();
			var packets = e.getValue();
			if (player.hasDisconnected() || packets.isEmpty()) continue;
			if (packets.size() == 1) {
				player.connection.send(packets.get(0));
				continue;
			}
			for (int from = 0; from < packets.size(); from += BUNDLE_MAX) {
				player.connection.send(new net.minecraft.network.protocol.game.ClientboundBundlePacket(
						new ArrayList<>(packets.subList(from, Math.min(packets.size(), from + BUNDLE_MAX)))));
			}
		}
		PENDING.clear();
	}

	/** True if the player is still around for a delayed effect. */
	public static boolean alive(ServerPlayer player) {
		return !player.isRemoved() && !player.hasDisconnected();
	}

	// ---------------------------------------------------------------
	// Shapes
	// ---------------------------------------------------------------

	/** Colored dust; {@code rgb} like 0xFF5A1F. */
	public static DustParticleOptions dust(int rgb, float scale) {
		return new DustParticleOptions(new Vector3f(
				((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f), scale);
	}

	public static void burst(ServerLevel level, double x, double y, double z, ParticleOptions particle,
							 int count, double spread, double speed) {
		AuraFx.particles(level, particle, x, y, z, count, spread, spread, spread, speed);
	}

	/** Flat horizontal ring around (x, y, z). */
	public static void ring(ServerLevel level, double x, double y, double z, double radius,
							ParticleOptions particle, int points) {
		for (int i = 0; i < points; i++) {
			double a = Math.PI * 2 * i / points;
			AuraFx.particles(level, particle, x + Math.cos(a) * radius, y, z + Math.sin(a) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** Particles evenly along a line. */
	public static void line(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle, int points) {
		for (int i = 0; i <= points; i++) {
			Vec3 p = from.lerp(to, (double) i / points);
			AuraFx.particles(level, particle, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * A double helix climbing around the entity, drawn over {@code ticks} ticks
	 * (one frame every 2 ticks), so it plays out as an animation.
	 */
	public static void helix(ServerPlayer player, ParticleOptions particle, int ticks, double radius, double height) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		int frames = Math.max(1, ticks / 2);
		for (int f = 0; f < frames; f++) {
			final double progress = (double) f / frames;
			later(server, f * 2, () -> {
				if (!alive(player)) return;
				ServerLevel level = player.serverLevel();
				double y = player.getY() + progress * height;
				double a = progress * Math.PI * 6;
				for (int strand = 0; strand < 2; strand++) {
					double sa = a + strand * Math.PI;
					AuraFx.particles(level, particle, player.getX() + Math.cos(sa) * radius, y,
							player.getZ() + Math.sin(sa) * radius, 2, 0.02, 0.02, 0.02, 0.0);
				}
			});
		}
	}

	public static Vec3 chest(Entity entity) {
		return entity.position().add(0, entity.getBbHeight() * 0.6, 0);
	}

	// ---------------------------------------------------------------
	// Sound and screen
	// ---------------------------------------------------------------

	public static void sound(ServerLevel level, Entity at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.getX(), at.getY(), at.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
	}

	/** Big title in the middle of the player's screen (null subtitle = none). */
	public static void title(ServerPlayer player, Component title, Component subtitle) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
		if (subtitle != null) player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}
}
