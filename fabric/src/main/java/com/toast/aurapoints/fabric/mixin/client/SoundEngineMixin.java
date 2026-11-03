package com.toast.aurapoints.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.client.event.sound.PlaySoundEvent;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;

/** PlaySoundEvent: a listener may swap the sound or drop it (null). */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

	@WrapMethod(method = "play")
	private void aurapoints$playSound(SoundInstance sound, Operation<Void> original) {
		if (sound == null) {
			original.call(sound);
			return;
		}
		SoundInstance result = AuraBus.BUS.post(new PlaySoundEvent((SoundEngine) (Object) this, sound.getLocation().getPath(), sound, sound)).getSound();
		if (result != null) original.call(result);
	}
}
