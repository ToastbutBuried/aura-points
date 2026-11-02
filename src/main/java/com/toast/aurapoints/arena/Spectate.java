package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraFx;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import static com.toast.aurapoints.arena.Arena.RING_FLOOR;
import static com.toast.aurapoints.arena.Arena.RING_R;
import static com.toast.aurapoints.arena.Arena.RING_Z;

/**
 * Watching a duel: free spectator mode over the ring (click a dueller to see through their eyes,
 * the number keys jump to players), kept inside the ring's space. When the duel ends everyone
 * watching goes back to the lobby.
 */
public final class Spectate {

	private Spectate() {
	}

	private static final Set<UUID> WATCHING = new HashSet<>();
	private static final Vec3 OVER_RING = new Vec3(0.5, RING_FLOOR + 12, RING_Z + 0.5);

	/** /aura arena spectate, the Spectate NPC, a [Watch] link: into the arena if need be, then over the ring. */
	public static int start(ServerPlayer player) {
		if (!Duel.running()) {
			player.displayClientMessage(Component.literal("No duel right now - challenge someone at the Duels NPC!").withStyle(ChatFormatting.RED), true);
			return 0;
		}
		if (Duel.isIn(player)) return 0;
		if (!Arena.in(player) && !Arena.enter(player)) return 0;
		ServerLevel arena = Arena.level(player.getServer());
		player.setGameMode(GameType.SPECTATOR);
		player.teleportTo(arena, OVER_RING.x, OVER_RING.y, OVER_RING.z, 180f, 45f);
		WATCHING.add(player.getUUID());
		ArenaFx.title(player, Component.literal("Spectating").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
				Component.literal(Duel.describe() + " · click a player to watch through their eyes").withStyle(ChatFormatting.GRAY));
		player.sendSystemMessage(Component.literal("You're watching " + Duel.describe() + ". /aura arena lobby to stop.").withStyle(ChatFormatting.AQUA));
		return 1;
	}

	public static boolean watching(ServerPlayer player) {
		return WATCHING.contains(player.getUUID());
	}

	public static int count() {
		return WATCHING.size();
	}

	/** No longer watching (teleported elsewhere in the arena, or out): their own view back. Their game mode is the caller's business. */
	static void stop(ServerPlayer player) {
		if (!WATCHING.remove(player.getUUID())) return;
		if (player.getCamera() != player && !(player instanceof net.neoforged.neoforge.common.util.FakePlayer)) player.setCamera(player);
	}

	/** Logged out or left the dimension. */
	static void forget(ServerPlayer player) {
		WATCHING.remove(player.getUUID());
	}

	/** The duel is over: everyone watching back to the lobby (a moment later, after the winner's fireworks). */
	static void duelOver(MinecraftServer server) {
		for (UUID id : Set.copyOf(WATCHING)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) {
				WATCHING.remove(id);
				continue;
			}
			AuraFx.later(server, 100, () -> {
				if (AuraFx.alive(p) && WATCHING.contains(p.getUUID())) Arena.toLobby(p);
			});
		}
	}

	/** Every tick: watchers who wander out of the ring's space are put back over it. */
	static void tick(MinecraftServer server) {
		for (Iterator<UUID> it = WATCHING.iterator(); it.hasNext(); ) {
			ServerPlayer p = server.getPlayerList().getPlayer(it.next());
			if (p == null || !Arena.in(p) || !p.isSpectator()) {
				it.remove();
				continue;
			}
			if (p.getCamera() != p) continue; // watching through someone: they're in the ring anyway
			double dx = p.getX() - OVER_RING.x, dz = p.getZ() - OVER_RING.z;
			boolean out = dx * dx + dz * dz > (RING_R + 10) * (RING_R + 10) || p.getY() < RING_FLOOR - 1 || p.getY() > RING_FLOOR + 30;
			if (out) {
				p.teleportTo(p.serverLevel(), OVER_RING.x, OVER_RING.y, OVER_RING.z, p.getYRot(), p.getXRot());
				p.displayClientMessage(Component.literal("Stay over the ring while you watch (/aura arena lobby to stop)").withStyle(ChatFormatting.GRAY), true);
			}
		}
	}

	static void clearAll() {
		WATCHING.clear();
	}
}
