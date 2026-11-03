package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraType;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * CLIENT ONLY. Tiny 2D particles drawn on the HUD: the aura's effects along the panel frame (embers,
 * bubbles, frost glints, sparks, leaves, smoke, wind streaks, sound rings) and the charge bar's sparks.
 * Everything moves by real time, so it looks the same at any frame rate. Each emitter keeps its own
 * list, positions are in the coordinates it draws in.
 */
public final class HudParticles {

	private static final int MAX = 120;
	private static final Random RANDOM = new Random();

	private final List<P> particles = new ArrayList<>();
	private long last = 0;
	/** fractional particles owed by the spawn rate */
	private float owed = 0;

	private static final class P {
		float x, y, vx, vy, life, age, size;
		int rgb;
		/** 0 square, 1 streak (drawn along its velocity), 2 ring, 3 twinkle */
		int kind;
		float phase;
	}

	/** Seconds since the last frame (capped, so a pause doesn't make everything jump). */
	private float step(long now) {
		float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1000f);
		last = now;
		return dt;
	}

	public void clear() {
		particles.clear();
		owed = 0;
	}

	/** Moves and draws everything (ages them; dead ones go). */
	public void draw(GuiGraphics g, long now, float alpha) {
		float dt = step(now);
		for (Iterator<P> it = particles.iterator(); it.hasNext(); ) {
			P p = it.next();
			p.age += dt;
			if (p.age >= p.life) {
				it.remove();
				continue;
			}
			p.x += p.vx * dt;
			p.y += p.vy * dt;
			float t = p.age / p.life;
			float a = alpha * (t < 0.15f ? t / 0.15f : 1f - (t - 0.15f) / 0.85f);
			switch (p.kind) {
				case 1 -> {
					// a streak along its motion
					float len = Math.max(2f, Math.abs(p.vx) * 0.06f);
					int x0 = Math.round(p.x - Math.signum(p.vx) * len), x1 = Math.round(p.x);
					g.fill(Math.min(x0, x1), Math.round(p.y), Math.max(x0, x1) + 1, Math.round(p.y) + 1, HudColors.argb(a * 0.8f, p.rgb));
				}
				case 2 -> {
					// a ring that grows as it fades
					float r = p.size * (0.3f + 0.7f * t);
					int points = Math.max(8, (int) (r * 3));
					int c = HudColors.argb(a * 0.9f, p.rgb);
					for (int i = 0; i < points; i++) {
						double ang = Math.PI * 2 * i / points;
						int px = Math.round(p.x + (float) Math.cos(ang) * r), py = Math.round(p.y + (float) Math.sin(ang) * r);
						g.fill(px, py, px + 1, py + 1, c);
					}
				}
				case 3 -> {
					// a glint: a little cross that twinkles
					float tw = 0.5f + 0.5f * (float) Math.sin(p.age * 18 + p.phase);
					int c = HudColors.argb(a * tw, p.rgb);
					int x = Math.round(p.x), y = Math.round(p.y);
					g.fill(x, y, x + 1, y + 1, HudColors.argb(a, 0xFFFFFF));
					if (tw > 0.6f) {
						g.fill(x - 1, y, x, y + 1, c);
						g.fill(x + 1, y, x + 2, y + 1, c);
						g.fill(x, y - 1, x + 1, y, c);
						g.fill(x, y + 1, x + 1, y + 2, c);
					}
				}
				default -> {
					int s = Math.max(1, Math.round(p.size * (1f - 0.5f * t)));
					int x = Math.round(p.x), y = Math.round(p.y);
					g.fill(x, y, x + s, y + s, HudColors.argb(a, p.rgb));
				}
			}
		}
	}

	private P add(float x, float y, float vx, float vy, float life, float size, int rgb, int kind) {
		if (particles.size() >= MAX) return null;
		P p = new P();
		p.x = x;
		p.y = y;
		p.vx = vx;
		p.vy = vy;
		p.life = life;
		p.size = size;
		p.rgb = rgb;
		p.kind = kind;
		p.phase = RANDOM.nextFloat() * 6.28f;
		particles.add(p);
		return p;
	}

	/** A ring bursting out of a point (a slot coming off cooldown). */
	public void ring(float x, float y, float radius, int rgb) {
		add(x, y, 0, 0, 0.45f, radius, rgb, 2);
		add(x, y, 0, 0, 0.3f, radius * 0.6f, 0xFFFFFF, 2);
	}

	/** Sparks spraying from a point (the charge bar's fill line). */
	public void sparks(float x, float y, int count, int rgb) {
		for (int i = 0; i < count; i++) {
			float a = (float) (RANDOM.nextFloat() * Math.PI * 2);
			float v = 15 + RANDOM.nextFloat() * 35;
			add(x, y, (float) Math.cos(a) * v, (float) Math.sin(a) * v - 10, 0.25f + RANDOM.nextFloat() * 0.3f, 1, i % 3 == 0 ? 0xFFFFFF : rgb, 0);
		}
	}

	/**
	 * Keeps the aura's effects going along a frame (left, top, right, bottom). {@code rate} = particles
	 * per second; call every frame (needs {@link #draw} afterwards in the same frame for the timing).
	 */
	public void frame(AuraType aura, int left, int top, int right, int bottom, int rgb, float rate, long now) {
		float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1000f);
		owed += rate * dt;
		while (owed >= 1f) {
			owed -= 1f;
			spawnFrame(aura, left, top, right, bottom, rgb);
		}
	}

	private void spawnFrame(AuraType aura, int left, int top, int right, int bottom, int rgb) {
		float w = right - left, h = bottom - top;
		float ex = left + RANDOM.nextFloat() * w;
		switch (aura) {
			// embers rising off the top edge, flickering from yellow to deep orange
			case FIRE -> add(ex, top, (RANDOM.nextFloat() - 0.5f) * 8, -10 - RANDOM.nextFloat() * 14, 0.6f + RANDOM.nextFloat() * 0.6f,
					RANDOM.nextInt(3) == 0 ? 2 : 1, RANDOM.nextBoolean() ? 0xFFD040 : 0xFF6A1F, 0);
			// bubbles drifting up from the bottom edge, and the odd ripple ring on the frame
			case WATER -> {
				if (RANDOM.nextInt(4) == 0) add(ex, top + RANDOM.nextFloat() * h, 0, 0, 0.7f, 4, rgb, 2);
				else add(ex, bottom - 2, (RANDOM.nextFloat() - 0.5f) * 3, -6 - RANDOM.nextFloat() * 6, 0.9f + RANDOM.nextFloat() * 0.5f, 1, 0xBFE8FF, 0);
			}
			// frost glints twinkling somewhere on the frame
			case FROST -> {
				float[] pt = onFrame(left, top, right, bottom);
				add(pt[0], pt[1], 0, 0, 0.5f + RANDOM.nextFloat() * 0.5f, 1, 0xDFF6FF, 3);
			}
			// sparks jumping off the frame
			case THUNDER -> {
				float[] pt = onFrame(left, top, right, bottom);
				for (int i = 0; i < 2; i++) {
					float a = (float) (RANDOM.nextFloat() * Math.PI * 2);
					add(pt[0], pt[1], (float) Math.cos(a) * 30, (float) Math.sin(a) * 30, 0.12f + RANDOM.nextFloat() * 0.12f, 1, i == 0 ? 0xFFFFFF : rgb, 0);
				}
			}
			// leaves drifting down off the top edge
			case VERDANT -> add(ex, top, 6 + RANDOM.nextFloat() * 6, 5 + RANDOM.nextFloat() * 5, 1.0f + RANDOM.nextFloat() * 0.6f, 2,
					RANDOM.nextBoolean() ? 0x6FD860 : 0x3E9A3A, 0);
			// smoke wisps curling up and growing
			case SHADOW -> add(ex, top + 1, (RANDOM.nextFloat() - 0.5f) * 5, -5 - RANDOM.nextFloat() * 5, 1.0f + RANDOM.nextFloat() * 0.8f,
					2 + RANDOM.nextInt(2), RANDOM.nextBoolean() ? 0x3A1656 : rgb, 0);
			// wind streaks skimming along the top edge
			case WIND -> add(left - 2, top + RANDOM.nextFloat() * Math.min(6, h), 60 + RANDOM.nextFloat() * 40, 0, w / 80f + 0.2f, 1, 0xF4FBFF, 1);
			// sound rings pulsing out of the corners
			case RESONANCE -> add(RANDOM.nextBoolean() ? left : right, RANDOM.nextBoolean() ? top : bottom, 0, 0, 0.8f, 6, rgb, 2);
			// pebbles crumbling off the bottom edge
			case EARTH -> add(ex, bottom, (RANDOM.nextFloat() - 0.5f) * 4, 14 + RANDOM.nextFloat() * 10, 0.9f + RANDOM.nextFloat() * 0.5f, 1 + RANDOM.nextInt(2),
					RANDOM.nextBoolean() ? 0x8A8378 : rgb, 0);
			// motes of light rising off the bottom edge
			case LIGHT -> add(ex, bottom, (RANDOM.nextFloat() - 0.5f) * 3, -10 - RANDOM.nextFloat() * 8, 0.8f + RANDOM.nextFloat() * 0.5f, 1,
					RANDOM.nextBoolean() ? 0xFFFFFF : rgb, 0);
			// drops of blood running down off the bottom edge
			case BLOOD -> add(ex, bottom, 0, 8 + RANDOM.nextFloat() * 8, 0.9f + RANDOM.nextFloat() * 0.5f, 1 + RANDOM.nextInt(2),
					RANDOM.nextBoolean() ? 0x6A0410 : rgb, 0);
			// specks drawn in from the edges toward the middle
			case VOID -> {
				float[] pt = onFrame(left, top, right, bottom);
				float cx = (left + right) / 2f, cy = (top + bottom) / 2f;
				add(pt[0], pt[1], (cx - pt[0]) * 0.8f, (cy - pt[1]) * 0.8f, 0.8f + RANDOM.nextFloat() * 0.4f, 1, RANDOM.nextBoolean() ? 0x1A0630 : rgb, 0);
			}
		}
	}

	/** A random point on the frame's outline. */
	private static float[] onFrame(int left, int top, int right, int bottom) {
		float w = right - left, h = bottom - top;
		float d = RANDOM.nextFloat() * (2 * w + 2 * h);
		if (d < w) return new float[]{left + d, top};
		d -= w;
		if (d < h) return new float[]{right, top + d};
		d -= h;
		if (d < w) return new float[]{right - d, bottom};
		d -= w;
		return new float[]{left, bottom - d};
	}
}
