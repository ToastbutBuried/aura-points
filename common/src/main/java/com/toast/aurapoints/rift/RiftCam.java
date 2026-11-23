package com.toast.aurapoints.rift;

import com.toast.aurapoints.network.FxPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds and sends a rift camera shot (5.47.0) to players: keyframes in world coordinates, played by
 * client/RiftCamera. {@code RiftCam.shot(rgb).title("...").key(0, pos, look).key(60, pos, look).cut(60, pos, look).send(players)}.
 */
final class RiftCam {

	private final int rgb;
	private String title = "", subtitle = "";
	private boolean skippable;
	private final List<String> keys = new ArrayList<>();
	private int length;

	/** Who watches a shot: {sent at, over at the latest}; the client's "closed" report ends it sooner. */
	private static final java.util.Map<java.util.UUID, long[]> UNTIL = new java.util.HashMap<>();

	static boolean watching(ServerPlayer p) {
		long[] u = UNTIL.get(p.getUUID());
		return u != null && p.level().getGameTime() < u[1];
	}

	/** The client's camera closed (ended or skipped); a report right after a new shot still belongs to the one before. */
	static void closed(ServerPlayer p) {
		long[] u = UNTIL.get(p.getUUID());
		if (u != null && p.level().getGameTime() - u[0] >= 8) UNTIL.remove(p.getUUID());
	}

	static void forget(java.util.UUID id) {
		UNTIL.remove(id);
	}

	static void forget() {
		UNTIL.clear();
	}

	private RiftCam(int rgb) {
		this.rgb = rgb;
	}

	static RiftCam shot(int rgb) {
		return new RiftCam(rgb);
	}

	RiftCam title(String title, String subtitle) {
		this.title = title.replace('\u001F', ' ');
		this.subtitle = subtitle.replace('\u001F', ' ');
		return this;
	}

	RiftCam skippable(boolean on) {
		this.skippable = on;
		return this;
	}

	RiftCam key(int t, Vec3 pos, Vec3 look) {
		return key(t, pos, look, 1f, false);
	}

	/** A hard cut to a new shot at tick t. */
	RiftCam cut(int t, Vec3 pos, Vec3 look) {
		return key(t, pos, look, 1f, true);
	}

	RiftCam key(int t, Vec3 pos, Vec3 look, float fov, boolean cut) {
		keys.add(String.format(Locale.ROOT, "%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d", t, pos.x, pos.y, pos.z, look.x, look.y, look.z, fov, cut ? 1 : 0));
		length = Math.max(length, t);
		return this;
	}

	/** Total length in ticks (the last key, plus a little to ease home). */
	int length() {
		return length + 14;
	}

	void send(Iterable<ServerPlayer> to) {
		String text = String.format("%06X", rgb & 0xFFFFFF) + '\u001F' + (skippable ? "1" : "0") + '\u001F' + title + '\u001F' + subtitle + '\u001F' + String.join("|", keys);
		for (ServerPlayer p : to) {
			if (com.toast.aurapoints.shim.Platform.get().isFakePlayer(p)) continue;
			UNTIL.put(p.getUUID(), new long[]{p.level().getGameTime(), p.level().getGameTime() + length() + 10});
			NetworkHandler.sendFx(p, FxPacket.RIFT_CAM, length(), text);
		}
	}

	static void stop(Iterable<ServerPlayer> to) {
		for (ServerPlayer p : to) if (!com.toast.aurapoints.shim.Platform.get().isFakePlayer(p)) NetworkHandler.sendFx(p, FxPacket.RIFT_CAM, 0, "");
	}
}
