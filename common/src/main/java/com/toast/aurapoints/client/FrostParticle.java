package com.toast.aurapoints.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/** Frost's own particles: one class, three looks (see Kind). Speed arguments become the starting velocity. */
public final class FrostParticle extends TextureSheetParticle {

	public enum Kind {
		/** Ice splinter: thrown outward, falls, spins, shrinks away. */
		SHARD(0.55f, 18, 26, 0.16f),
		/** Flash: stands still, swells fast and fades; the heart of an explosion. */
		BURST(0f, 6, 8, 0.9f),
		/** Snowy gust: big soft flake, drifts sideways with a little wobble. */
		GUST(0.02f, 34, 50, 0.3f);

		final float gravity;
		final int minLife, maxLife;
		final float size;

		Kind(float gravity, int minLife, int maxLife, float size) {
			this.gravity = gravity;
			this.minLife = minLife;
			this.maxLife = maxLife;
			this.size = size;
		}
	}

	private final Kind kind;
	private final float baseSize;
	private final float spin;

	private FrostParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, Kind kind, SpriteSet sprites) {
		super(level, x, y, z);
		this.kind = kind;
		this.xd = vx;
		this.yd = vy;
		this.zd = vz;
		this.gravity = kind.gravity;
		this.friction = kind == Kind.SHARD ? 0.92f : 0.96f;
		this.hasPhysics = kind == Kind.SHARD;
		this.lifetime = kind.minLife + random.nextInt(kind.maxLife - kind.minLife + 1);
		this.baseSize = kind.size * (0.7f + random.nextFloat() * 0.6f);
		this.quadSize = baseSize;
		this.spin = kind == Kind.SHARD ? (random.nextFloat() - 0.5f) * 0.6f : (random.nextFloat() - 0.5f) * 0.1f;
		this.oRoll = this.roll = random.nextFloat() * (float) Math.PI * 2;
		this.pickSprite(sprites);
		this.setColor(0.8f + random.nextFloat() * 0.2f, 0.93f, 1f);
		this.setAlpha(kind == Kind.GUST ? 0.55f : 1f);
	}

	@Override
	public void tick() {
		super.tick();
		this.oRoll = this.roll;
		this.roll += spin;
		float t = (float) age / lifetime;
		switch (kind) {
			case SHARD -> this.quadSize = baseSize * (1f - t * t);
			case BURST -> {
				this.quadSize = baseSize * (0.4f + 1.6f * t);
				this.setAlpha(1f - t);
			}
			case GUST -> {
				this.xd += Math.sin((age + hashCode() % 20) * 0.25) * 0.002;
				this.setAlpha(0.55f * (1f - t));
			}
		}
	}

	@Override
	protected int getLightColor(float partialTick) {
		return 0xF000F0; // glows: ice that lights up the dark
	}

	@Override
	public ParticleRenderType getRenderType() {
		return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
	}

	/** Provider for one kind. */
	public record Provider(SpriteSet sprites, Kind kind) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
			return new FrostParticle(level, x, y, z, vx, vy, vz, kind, sprites);
		}
	}
}
