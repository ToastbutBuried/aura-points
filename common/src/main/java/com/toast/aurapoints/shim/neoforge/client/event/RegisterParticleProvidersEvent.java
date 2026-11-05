package com.toast.aurapoints.shim.neoforge.client.event;

import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;

/** Client particle providers (NeoForge RegisterParticleProvidersEvent / Fabric ParticleFactoryRegistry). */
public class RegisterParticleProvidersEvent extends com.toast.aurapoints.shim.bus.api.Event {

	/** Makes the provider once the particle's sprites are loaded. */
	public interface Factory<T extends ParticleOptions> {
		ParticleProvider<T> create(SpriteSet sprites);
	}

	public interface SpriteSink {
		<T extends ParticleOptions> void accept(ParticleType<T> type, Factory<T> registration);
	}

	private final SpriteSink sink;

	public RegisterParticleProvidersEvent(SpriteSink sink) {
		this.sink = sink;
	}

	public <T extends ParticleOptions> void registerSpriteSet(ParticleType<T> type, Factory<T> registration) {
		sink.accept(type, registration);
	}
}
