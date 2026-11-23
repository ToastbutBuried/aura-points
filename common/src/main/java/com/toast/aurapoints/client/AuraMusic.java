package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * CLIENT ONLY. One looping mod track at a time: fades in and out, crossfades to another track, holds vanilla music back
 * while it plays, follows the Music and Aura Points sliders. {@code urgency} speeds the track up as the timer runs out
 * (the pitch rises with it: 0 = as written, 1 = 8% faster).
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class AuraMusic {

	private AuraMusic() {
	}

	private static Track current;
	private static float urgency;
	/** The rift's time limit: ticks left, and ticks since the server last said so (it says so every second). */
	private static int left, silence;

	private static final int HURRY = 2400, LAST = 900, LOST_AFTER = 200;

	/** FxPacket.RIFT_MUSIC. */
	public static void onPacket(int ticksLeft, String text) {
		left = ticksLeft;
		silence = 0;
		if (ticksLeft > 0) return;
		play(null);
		if ("won".equals(text) && AuraClientConfig.RIFT_MUSIC.get())
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forMusic(AuraSounds.MUSIC_HURRY_DONE.value()));
	}

	/** Picks the rift's track for the time left; another dimension, or ten seconds without a word from the server = you are out of it. */
	private static void tickRift() {
		if (left <= 0) return;
		if (++silence > LOST_AFTER || !AuraClientConfig.RIFT_MUSIC.get() || Minecraft.getInstance().level.dimension() != com.toast.aurapoints.rift.Rifts.KEY) {
			left = 0;
			play(null);
			return;
		}
		left = Math.max(1, left - 1);
		urgency = Math.max(0f, Math.min(1f, 1f - left / (float) HURRY));
		play(left > LAST ? AuraSounds.MUSIC_HURRY1.value() : AuraSounds.MUSIC_HURRY2.value());
	}

	/** Plays this track (null = fade out). Asking for the one that plays does nothing. */
	public static void play(SoundEvent track) {
		if (current != null && !current.isStopped() && track != null && current.getLocation().equals(track.getLocation())) return;
		if (current != null) current.fading = true;
		current = null;
		if (track == null) return;
		current = new Track(track);
		Minecraft.getInstance().getSoundManager().play(current);
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			left = 0;
			play(null);
			return;
		}
		if (mc.isPaused()) return;
		tickRift();
		if (current == null) return;
		if (current.isStopped() || ++current.age > 40 && !mc.getSoundManager().isActive(current)) { // also after a resource reload
			current = null;
			return;
		}
		mc.getMusicManager().stopPlaying(); // vanilla music waits its turn
	}

	private static final class Track extends AbstractTickableSoundInstance {
		private boolean fading;
		private float gain;
		private int age;

		Track(SoundEvent sound) {
			super(sound, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
			this.looping = true;
			this.delay = 0;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
			this.volume = 0.01f;
			this.pitch = 1f + 0.08f * urgency; // a tier takes over at the speed it finds
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
			gain += fading ? -0.05f : 0.05f; // one second
			if (gain <= 0f) {
				stop();
				return;
			}
			gain = Math.min(1f, gain);
			volume = Math.max(0.001f, gain * (float) (double) AuraClientConfig.ABILITY_VOLUME.get());
			float want = fading ? pitch : 1f + 0.08f * urgency;
			pitch += (want - pitch) * 0.02f; // slow enough not to wobble
		}
	}
}
