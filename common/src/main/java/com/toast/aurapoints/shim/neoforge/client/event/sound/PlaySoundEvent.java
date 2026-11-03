package com.toast.aurapoints.shim.neoforge.client.event.sound;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public class PlaySoundEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private net.minecraft.client.sounds.SoundEngine engine;
	private java.lang.String name;
	private net.minecraft.client.resources.sounds.SoundInstance originalSound;
	private net.minecraft.client.resources.sounds.SoundInstance sound;

	public PlaySoundEvent(net.minecraft.client.sounds.SoundEngine engine, java.lang.String name, net.minecraft.client.resources.sounds.SoundInstance originalSound, net.minecraft.client.resources.sounds.SoundInstance sound) {
		this.engine = engine;
		this.name = name;
		this.originalSound = originalSound;
		this.sound = sound;
	}

	public net.minecraft.client.sounds.SoundEngine getEngine() {
		return engine;
	}

	public java.lang.String getName() {
		return name;
	}

	public net.minecraft.client.resources.sounds.SoundInstance getOriginalSound() {
		return originalSound;
	}

	public net.minecraft.client.resources.sounds.SoundInstance getSound() {
		return sound;
	}

	public void setSound(net.minecraft.client.resources.sounds.SoundInstance value) {
		this.sound = value;
	}
}
