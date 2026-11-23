package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraSounds;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
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
			play(level, at, AuraSounds.GRINDSTONE_USE.value(), 0.35f + t * 0.3f, 0.9f + t);
			if (t > 0.5f) play(level, at, AuraSounds.FLINTANDSTEEL_USE.value(), 0.3f, 0.8f + t);
		} else play(level, at, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), 1f, 0.6f + t * 1.2f);
	}

	static void good(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.PLAYER_ATTACK_CRIT.value(), 0.9f, 1.3f);
		play(level, at, AuraSounds.NOTE_BLOCK_BELL.value(), 1.2f, 1.6f);
		play(level, at, AuraSounds.AMETHYST_CLUSTER_BREAK.value(), 0.9f, 1.4f);
	}

	static void bad(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.FIRE_EXTINGUISH.value(), 0.5f, 1.5f);
		play(level, at, AuraSounds.NOTE_BLOCK_BASS.value(), 1f, 0.6f);
	}

	// --- the portals

	static void compass(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.LODESTONE_COMPASS_LOCK.value(), 1.2f, 1.1f);
		play(level, at, AuraSounds.ITEM_PICKUP.value(), 0.6f, 0.9f);
	}

	static void potBreak(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.DECORATED_POT_SHATTER.value(), 1.2f, 1f);
		play(level, at, AuraSounds.AMETHYST_BLOCK_CHIME.value(), 1.6f, 1.1f);
		play(level, at, AuraSounds.EXPERIENCE_ORB_PICKUP.value(), 0.8f, 0.8f);
	}

	static void pickStone(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.DEEPSLATE_BREAK.value(), 1f, 1.1f);
		play(level, at, AuraSounds.ITEM_PICKUP.value(), 0.7f, 0.7f);
	}

	static void setStone(ServerLevel level, Vec3 at, int kind, int n) {
		play(level, at, AuraSounds.STONE_PLACE.value(), 1.4f, 0.6f + kind * 0.1f);
		play(level, at, AuraSounds.ANVIL_LAND.value(), 0.16f, 1.5f);
		play(level, at, AuraSounds.NOTE_BLOCK_CHIME.value(), 1.2f, note(n));
	}

	static void wrongStone(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.GRAVEL_BREAK.value(), 1.2f, 0.6f);
		play(level, at, AuraSounds.NOTE_BLOCK_BASS.value(), 1f, 0.7f);
	}

	static void archWhole(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.ANVIL_LAND.value(), 0.5f, 0.5f);
		play(level, at, AuraSounds.BEACON_POWER_SELECT.value(), 1.4f, 0.7f);
	}

	static void ignite(ServerLevel level, Vec3 at, int n) {
		play(level, at, AuraSounds.FIRECHARGE_USE.value(), 0.5f, 0.7f + n * 0.15f);
		play(level, at, AuraSounds.RESPAWN_ANCHOR_CHARGE.value(), 1.4f, 0.8f + n * 0.2f);
		play(level, at, AuraSounds.NOTE_BLOCK_CHIME.value(), 1f, note(n + 2));
	}

	static void passage(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), 1.4f, 0.7f);
		play(level, at, AuraSounds.ENDERMAN_TELEPORT.value(), 0.5f, 1.6f);
		play(level, at, AuraSounds.PORTAL_TRAVEL.value(), 0.12f, 1.8f);
	}

	// --- Prism

	static void mirror(ServerLevel level, Vec3 at, int state) {
		play(level, at, AuraSounds.PISTON_EXTEND.value(), 0.6f, 1.6f);
		play(level, at, AuraSounds.STONE_PLACE.value(), 0.7f, 1.3f);
		play(level, at, AuraSounds.NOTE_BLOCK_CHIME.value(), 0.8f, 0.8f + state * 0.15f);
	}

	static void lit(ServerLevel level, Vec3 at, int n) {
		play(level, at, AuraSounds.BEACON_POWER_SELECT.value(), 1.5f, 1.2f);
		for (int k = 0; k < 3; k++) play(level, at, AuraSounds.NOTE_BLOCK_CHIME.value(), 1.2f, note(n + k * 2));
	}

	// --- Bells

	static void rightBell(ServerLevel level, Vec3 at, int n) {
		play(level, at, AuraSounds.AMETHYST_BLOCK_CHIME.value(), 1.3f, note(n));
		play(level, at, AuraSounds.NOTE_BLOCK_CHIME.value(), 0.9f, note(n));
	}

	static void wrongBell(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.NOTE_BLOCK_DIDGERIDOO.value(), 2f, 0.5f);
		play(level, at, AuraSounds.ANVIL_LAND.value(), 0.4f, 0.5f);
	}

	static void roundDone(ServerLevel level, Vec3 at, int round) {
		play(level, at, AuraSounds.PLAYER_LEVELUP.value(), 1.2f, 1.2f + round * 0.1f);
		play(level, at, AuraSounds.BELL_RESONATE.value(), 1.2f, 1.4f);
	}

	// --- Echoes

	static void veilUp(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.AMETHYST_CLUSTER_PLACE.value(), 1.4f, 0.6f);
	}

	static void veilFall(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.GLASS_BREAK.value(), 1.4f, 1.3f);
		play(level, at, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), 1.8f, 1.2f);
		play(level, at, AuraSounds.NOTE_BLOCK_CHIME.value(), 1.4f, 1.2f);
	}

	static void step(ServerLevel level, Vec3 at, int n) {
		play(level, at, AuraSounds.NOTE_BLOCK_CHIME.value(), 0.8f, note(n));
		play(level, at, AuraSounds.AMETHYST_BLOCK_STEP.value(), 1f, 1.1f);
	}

	static void checkpoint(ServerLevel level, Vec3 at, int n) {
		play(level, at, AuraSounds.PLAYER_LEVELUP.value(), 1.2f, 0.8f + n * 0.2f);
		play(level, at, AuraSounds.BEACON_POWER_SELECT.value(), 1f, 1f + n * 0.15f);
	}

	// --- Statues, the shard

	static void scrape(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.GRINDSTONE_USE.value(), 1f, 0.5f);
		play(level, at, AuraSounds.DEEPSLATE_BREAK.value(), 1f, 0.5f);
	}

	static void shardFree(ServerLevel level, Vec3 at) {
		play(level, at, AuraSounds.AMETHYST_CLUSTER_BREAK.value(), 1.5f, 0.6f);
		play(level, at, AuraSounds.TOTEM_USE.value(), 0.5f, 1.4f);
		play(level, at, AuraSounds.BEACON_ACTIVATE.value(), 1.2f, 1.2f);
	}
}
