package com.toast.aurapoints.rift;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** The trials' sounds (5.52): one method per moment, each a layer or two of vanilla sounds, heard by everyone near. */
final class RiftSfx {

	private RiftSfx() {
	}

	static void play(ServerLevel level, Vec3 at, SoundEvent s, float vol, float pitch) {
		level.playSound(null, at.x, at.y, at.z, s, SoundSource.BLOCKS, vol, Math.max(0.5f, Math.min(2f, pitch)));
	}

	static void play(ServerLevel level, Vec3 at, Holder<SoundEvent> s, float vol, float pitch) {
		play(level, at, s.value(), vol, pitch);
	}

	/** A major pentatonic step (0 = the root) as a pitch: things in a row climb a tune. */
	static float note(int k) {
		int[] semi = {0, 2, 4, 7, 9};
		int n = Math.floorMod(k, 10);
		return (float) (0.6 * Math.pow(2, (semi[n % 5] + 12 * (n / 5)) / 12.0));
	}

	// --- skill checks

	static void hold(ServerLevel level, Vec3 at, float t, boolean rub) {
		if (rub) {
			play(level, at, SoundEvents.GRINDSTONE_USE, 0.35f + t * 0.3f, 0.9f + t);
			if (t > 0.5f) play(level, at, SoundEvents.FLINTANDSTEEL_USE, 0.3f, 0.8f + t);
		} else play(level, at, SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, 0.6f + t * 1.2f);
	}

	static void good(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.PLAYER_ATTACK_CRIT, 0.9f, 1.3f);
		play(level, at, SoundEvents.NOTE_BLOCK_BELL, 1.2f, 1.6f);
		play(level, at, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.9f, 1.4f);
	}

	static void bad(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.FIRE_EXTINGUISH, 0.5f, 1.5f);
		play(level, at, SoundEvents.NOTE_BLOCK_BASS, 1f, 0.6f);
	}

	// --- the portals

	static void compass(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.LODESTONE_COMPASS_LOCK, 1.2f, 1.1f);
		play(level, at, SoundEvents.ITEM_PICKUP, 0.6f, 0.9f);
	}

	static void potBreak(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.DECORATED_POT_SHATTER, 1.2f, 1f);
		play(level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6f, 1.1f);
		play(level, at, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 0.8f);
	}

	static void pickStone(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.DEEPSLATE_BREAK, 1f, 1.1f);
		play(level, at, SoundEvents.ITEM_PICKUP, 0.7f, 0.7f);
	}

	static void setStone(ServerLevel level, Vec3 at, int kind, int n) {
		play(level, at, SoundEvents.STONE_PLACE, 1.4f, 0.6f + kind * 0.1f);
		play(level, at, SoundEvents.ANVIL_LAND, 0.16f, 1.5f);
		play(level, at, SoundEvents.NOTE_BLOCK_CHIME, 1.2f, note(n));
	}

	static void wrongStone(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.GRAVEL_BREAK, 1.2f, 0.6f);
		play(level, at, SoundEvents.NOTE_BLOCK_BASS, 1f, 0.7f);
	}

	static void archWhole(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.ANVIL_LAND, 0.5f, 0.5f);
		play(level, at, SoundEvents.BEACON_POWER_SELECT, 1.4f, 0.7f);
	}

	static void ignite(ServerLevel level, Vec3 at, int n) {
		play(level, at, SoundEvents.FIRECHARGE_USE, 0.5f, 0.7f + n * 0.15f);
		play(level, at, SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.4f, 0.8f + n * 0.2f);
		play(level, at, SoundEvents.NOTE_BLOCK_CHIME, 1f, note(n + 2));
	}

	static void passage(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4f, 0.7f);
		play(level, at, SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.6f);
		play(level, at, SoundEvents.PORTAL_TRAVEL, 0.12f, 1.8f);
	}

	// --- Prism

	static void mirror(ServerLevel level, Vec3 at, int state) {
		play(level, at, SoundEvents.PISTON_EXTEND, 0.6f, 1.6f);
		play(level, at, SoundEvents.STONE_PLACE, 0.7f, 1.3f);
		play(level, at, SoundEvents.NOTE_BLOCK_CHIME, 0.8f, 0.8f + state * 0.15f);
	}

	static void lit(ServerLevel level, Vec3 at, int n) {
		play(level, at, SoundEvents.BEACON_POWER_SELECT, 1.5f, 1.2f);
		for (int k = 0; k < 3; k++) play(level, at, SoundEvents.NOTE_BLOCK_CHIME, 1.2f, note(n + k * 2));
	}

	// --- Bells

	static void rightBell(ServerLevel level, Vec3 at, int n) {
		play(level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 1.3f, note(n));
		play(level, at, SoundEvents.NOTE_BLOCK_CHIME, 0.9f, note(n));
	}

	static void wrongBell(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.NOTE_BLOCK_DIDGERIDOO, 2f, 0.5f);
		play(level, at, SoundEvents.ANVIL_LAND, 0.4f, 0.5f);
	}

	static void roundDone(ServerLevel level, Vec3 at, int round) {
		play(level, at, SoundEvents.PLAYER_LEVELUP, 1.2f, 1.2f + round * 0.1f);
		play(level, at, SoundEvents.BELL_RESONATE, 1.2f, 1.4f);
	}

	// --- Echoes

	static void veilUp(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.AMETHYST_CLUSTER_PLACE, 1.4f, 0.6f);
	}

	static void veilFall(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.GLASS_BREAK, 1.4f, 1.3f);
		play(level, at, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.8f, 1.2f);
		play(level, at, SoundEvents.NOTE_BLOCK_CHIME, 1.4f, 1.2f);
	}

	static void step(ServerLevel level, Vec3 at, int n) {
		play(level, at, SoundEvents.NOTE_BLOCK_CHIME, 0.8f, note(n));
		play(level, at, SoundEvents.AMETHYST_BLOCK_STEP, 1f, 1.1f);
	}

	static void checkpoint(ServerLevel level, Vec3 at, int n) {
		play(level, at, SoundEvents.PLAYER_LEVELUP, 1.2f, 0.8f + n * 0.2f);
		play(level, at, SoundEvents.BEACON_POWER_SELECT, 1f, 1f + n * 0.15f);
	}

	// --- Statues, the shard

	static void scrape(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.GRINDSTONE_USE, 1f, 0.5f);
		play(level, at, SoundEvents.DEEPSLATE_BREAK, 1f, 0.5f);
	}

	static void shardFree(ServerLevel level, Vec3 at) {
		play(level, at, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.5f, 0.6f);
		play(level, at, SoundEvents.TOTEM_USE, 0.5f, 1.4f);
		play(level, at, SoundEvents.BEACON_ACTIVATE, 1.2f, 1.2f);
	}
}
