package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientStorms;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterGuiLayersEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;
import com.toast.aurapoints.shim.neoforge.client.gui.VanillaGuiLayers;
import com.toast.aurapoints.weather.StormType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.toast.aurapoints.shim.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * CLIENT ONLY. Local storms (5.39.0, network/ClientStorms; reworked 5.43.0 after the Blue Bell's blizzard): each one
 * has its own signature so you can tell the element at a glance, and it is meant to feel big -
 * <ul>
 * <li>the fog bleeds hard to the storm's colour and closes in (Blizzard and Umbra almost to a whiteout/blackout),</li>
 * <li>its weather streams past along the storm's wind (its drift), with the mod's own particles where it has them,</li>
 * <li>a screen layer per element (frost creeping in, rain on the lens, vines, a heartbeat, darkness...),</li>
 * <li>big moments: lightning strikes with a flash, quake pulses, aurora curtains in the sky, a sting when you walk in,</li>
 * <li>Thunderhead and Downpour turn on real (client-side) rain and thunder.</li>
 * </ul>
 * From outside you see a tall wall of the element at the edge. Strength follows the distance to the storm's middle;
 * the client Effect level trims the particles and reduced motion turns off the camera parts.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class StormFx {

	private StormFx() {
	}

	/** The storm the camera is in and its strength there (smoothed). */
	private static StormType current = null;
	private static ClientStorms.Storm storm = null;
	private static float shown = 0f;
	/** Lightning flash on screen (1 = full, fades fast) and the heartbeat / quake clocks. */
	private static float flash = 0f, sting = 0f;
	private static int nextBolt = 40, nextQuake = 60, beat = 0, boltIds = 0;
	/** Rain we turned on locally, so it can be handed back to the server's weather. */
	private static boolean rainOn = false;
	private static final float[][] DROPS = new float[22][4]; // x, y, size, speed (screen fractions)

	private static float level() {
		return switch (AuraClientConfig.EFFECT_LEVEL.get()) {
			case MINIMAL -> 0.25f;
			case REDUCED -> 0.6f;
			default -> 1f;
		};
	}

	/** 0..1 how much of the storm the camera is in now. */
	public static float strength() {
		return shown;
	}

	public static StormType type() {
		return current;
	}

	// ---------------------------------------------------------------
	// Per element
	// ---------------------------------------------------------------

	/** Fog colour inside (not always the sky tint: Umbra's fog is near black, the Blizzard's near white). */
	private static int fogRgb(StormType t) {
		return switch (t) {
			case BLIZZARD -> 0xDBEAF7;
			case UMBRA -> 0x0B0812;
			case CRIMSON_MOON -> 0x4A0810;
			case THUNDERHEAD -> 0x3A3F55;
			case DOWNPOUR -> 0x3E5F80;
			case HEAT_HAZE -> 0xC8743A;
			case GALE -> 0xC9DAD2;
			case SPORE_BLOOM -> 0x6E9A45;
			case TREMOR -> 0x8A7354;
			case AURORA -> 0x0E2A2E;
		};
	}

	/** How far you can see at full strength (blocks), or 0 = keep the normal view. */
	private static float fogEnd(StormType t) {
		return switch (t) {
			case BLIZZARD -> 18;
			case UMBRA -> 14;
			case DOWNPOUR -> 34;
			case TREMOR -> 38;
			case SPORE_BLOOM -> 40;
			case THUNDERHEAD -> 48;
			case HEAT_HAZE -> 56;
			case GALE -> 64;
			case CRIMSON_MOON -> 72;
			case AURORA -> 0;
		};
	}

	/** The wind: the storm's drift, or a fixed heading for one that stands still. */
	private static Vec3 wind(ClientStorms.Storm s) {
		if (s == null) return new Vec3(1, 0, 0.3).normalize();
		double l = Math.hypot(s.vx(), s.vz());
		if (l < 1e-3) {
			double a = s.type().ordinal() * 2.4 + s.radius();
			return new Vec3(Math.cos(a), 0, Math.sin(a));
		}
		return new Vec3(s.vx() / l, 0, s.vz() / l);
	}

	// ---------------------------------------------------------------
	// State
	// ---------------------------------------------------------------

	private static void update(Minecraft mc) {
		float target = 0f;
		StormType type = null;
		ClientStorms.Storm found = null;
		if (mc.level != null && mc.player != null && mc.level.dimension() == Level.OVERWORLD) {
			found = ClientStorms.strongest(mc.player.getX(), mc.player.getZ());
			if (found != null) {
				target = found.strengthAt(mc.player.getX(), mc.player.getZ());
				type = found.type();
			}
		}
		if (type != null) {
			// walking in: a sting (sound + a pulse of the storm's colour)
			if ((current != type || shown < 0.15f) && target > 0.3f && sting <= 0.05f && mc.level != null) enter(mc, type);
			current = type;
			storm = found;
		}
		// out of the Overworld the storm is gone at once, not faded out over the Nether
		shown = mc.level == null || mc.level.dimension() != Level.OVERWORLD ? 0f : shown + (target - shown) * 0.05f;
		if (shown < 0.005f && target == 0f) {
			shown = 0f;
			current = null;
			storm = null;
		}
		flash = Math.max(0f, flash - 0.12f);
		sting = Math.max(0f, sting - 0.025f);
	}

	private static void enter(Minecraft mc, StormType type) {
		sting = 1f;
		SoundEvent s = switch (type) {
			case THUNDERHEAD -> AuraSounds.LIGHTNING_BOLT_THUNDER.value();
			case DOWNPOUR -> AuraSounds.WEATHER_RAIN_ABOVE.value();
			case BLIZZARD -> AuraSounds.POWDER_SNOW_BREAK.value();
			case HEAT_HAZE -> AuraSounds.FIRECHARGE_USE.value();
			case GALE -> AuraSounds.ENDER_DRAGON_FLAP.value();
			case SPORE_BLOOM -> AuraSounds.CHERRY_LEAVES_BREAK.value();
			case TREMOR -> AuraSounds.GENERIC_EXPLODE.value();
			case AURORA -> AuraSounds.AMETHYST_BLOCK_RESONATE.value();
			case UMBRA -> AuraSounds.AMBIENT_CAVE.value();
			case CRIMSON_MOON -> AuraSounds.WARDEN_HEARTBEAT.value();
		};
		float pitch = type == StormType.TREMOR || type == StormType.BLIZZARD ? 0.5f : type == StormType.GALE ? 0.6f : 0.9f;
		sound(mc, s, 0.7f, pitch);
		if (type == StormType.TREMOR) CameraFx.add(0, 0.45f, 0f);
	}

	private static void sound(Minecraft mc, SoundEvent s, float vol, float pitch) {
		var p = mc.player;
		mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), s, SoundSource.WEATHER, vol, pitch, false);
	}

	// ---------------------------------------------------------------
	// Fog and sky
	// ---------------------------------------------------------------

	/** ClientLevelMixin: the sky leans to the storm's colour. */
	public static Vec3 tintSky(Vec3 sky) {
		float s = shown;
		if (s <= 0 || current == null) return sky;
		float k = (current == StormType.CRIMSON_MOON ? 0.8f : current == StormType.AURORA ? 0.35f : 0.6f) * s;
		int rgb = current == StormType.CRIMSON_MOON ? 0x9A0F1E : current.rgb;
		return new Vec3(Mth.lerp(k, sky.x, ((rgb >> 16) & 255) / 255.0), Mth.lerp(k, sky.y, ((rgb >> 8) & 255) / 255.0), Mth.lerp(k, sky.z, (rgb & 255) / 255.0));
	}

	@SubscribeEvent
	public static void onFogColor(ViewportEvent.ComputeFogColor event) {
		float s = shown;
		if (s <= 0 || current == null) return;
		float k = switch (current) {
			case BLIZZARD, UMBRA -> 0.88f;
			case AURORA -> 0.3f;
			default -> 0.72f;
		} * s + flash * 0.4f;
		int rgb = flash > 0.3f ? 0xEEF2FF : fogRgb(current);
		k = Math.min(1f, k);
		event.setRed(Mth.lerp(k, event.getRed(), ((rgb >> 16) & 255) / 255f));
		event.setGreen(Mth.lerp(k, event.getGreen(), ((rgb >> 8) & 255) / 255f));
		event.setBlue(Mth.lerp(k, event.getBlue(), (rgb & 255) / 255f));
	}

	/** The view closes in: a near whiteout in the Blizzard, a blackout in Umbra, rain/dust walls for the rest. */
	@SubscribeEvent
	public static void onFog(ViewportEvent.RenderFog event) {
		float s = shown;
		if (s <= 0.02f || current == null || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) return;
		float end = fogEnd(current);
		if (end <= 0) return;
		float far = event.getFarPlaneDistance();
		float newEnd = Mth.lerp(s, far, Math.min(far, end));
		boolean whiteout = current == StormType.BLIZZARD || current == StormType.UMBRA;
		float newStart = Math.min(event.getNearPlaneDistance(), newEnd * (whiteout ? 0.02f : 0.35f));
		event.setNearPlaneDistance(newStart);
		event.setFarPlaneDistance(newEnd);
		event.setCanceled(true); // NeoForge only applies the new distances when the event is cancelled
	}

	/** Heat shimmer: the view breathes a little. */
	@SubscribeEvent
	public static void onFov(ViewportEvent.ComputeFov event) {
		if (shown <= 0.05f || current != StormType.HEAT_HAZE || AuraClientConfig.calm() || !event.usedConfiguredFov()) return;
		float t = System.currentTimeMillis() / 1000f;
		event.setFOV(event.getFOV() * (1.0 + 0.012 * shown * (Mth.sin(t * 2.3f) * 0.7f + Mth.sin(t * 5.1f) * 0.3f)));
	}

	/** Gale: the camera sways in the gusts. */
	@SubscribeEvent
	public static void onAngles(ViewportEvent.ComputeCameraAngles event) {
		if (shown <= 0.05f || current != StormType.GALE || AuraClientConfig.calm()) return;
		float t = System.currentTimeMillis() / 1000f;
		float gust = 0.5f + 0.5f * Mth.sin(t * 0.7f);
		event.setRoll(event.getRoll() + shown * gust * 2.2f * Mth.sin(t * 1.9f));
		event.setYaw(event.getYaw() + shown * gust * 0.6f * Mth.sin(t * 3.3f + 1f));
	}

	// ---------------------------------------------------------------
	// Ticking: weather round the camera, the edge walls, big moments
	// ---------------------------------------------------------------

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		update(mc);
		rain(mc);
		if (mc.level == null || mc.player == null || mc.isPaused() || mc.level.dimension() != Level.OVERWORLD) return;
		RandomSource r = mc.level.random;
		float lvl = level();
		if (shown > 0.02f && current != null) inside(mc, current, r, lvl);
		if (mc.level.getGameTime() % 2 == 0) walls(mc, r, lvl);
	}

	/** Thunderhead and Downpour: real rain (and a thunder sky) on this client while you're in them. */
	private static void rain(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) {
			rainOn = false;
			return;
		}
		boolean wet = current == StormType.THUNDERHEAD || current == StormType.DOWNPOUR;
		if (wet && shown > 0.02f) {
			rainOn = true;
			level.setRainLevel(Math.max(serverRain(level), shown));
			level.setThunderLevel(current == StormType.THUNDERHEAD ? Math.max(serverThunder(level), shown) : serverThunder(level));
		} else if (rainOn) {
			rainOn = false;
			level.setRainLevel(serverRain(level));
			level.setThunderLevel(serverThunder(level));
		}
	}

	private static float serverRain(ClientLevel level) {
		return level.getLevelData().isRaining() ? 1f : 0f;
	}

	private static float serverThunder(ClientLevel level) {
		return level.getLevelData().isThundering() ? 1f : 0f;
	}

	private static void inside(Minecraft mc, StormType type, RandomSource r, float lvl) {
		ClientLevel level = mc.level;
		var p = mc.player;
		Vec3 w = wind(storm);
		float s = shown;
		int n = Math.max(1, Math.round(30 * s * lvl));
		long time = level.getGameTime();
		switch (type) {
			case BLIZZARD -> {
				// the bell's blizzard: everything driving one way, past and through the camera
				for (int i = 0; i < n; i++) {
					double x = p.getX() + (r.nextDouble() - 0.5) * 22 - w.x * 6, y = p.getY() + r.nextDouble() * 9 - 1.5, z = p.getZ() + (r.nextDouble() - 0.5) * 22 - w.z * 6;
					double sp = 0.6 + r.nextDouble() * 0.35;
					level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, w.x * sp, -0.12, w.z * sp);
				}
				for (int i = 0; i < Math.round(3 * s * lvl); i++) {
					double x = p.getX() + (r.nextDouble() - 0.5) * 14 - w.x * 4, y = p.getY() + r.nextDouble() * 4, z = p.getZ() + (r.nextDouble() - 0.5) * 14 - w.z * 4;
					level.addParticle(AuraPoints.SNOW_GUST.get(), x, y, z, w.x * 0.5, 0, w.z * 0.5);
				}
				if (time % 2 == 0) level.addParticle(AuraPoints.FROST_SHARD.get(), p.getX() + (r.nextDouble() - 0.5) * 8, p.getY() + 1 + r.nextDouble() * 3, p.getZ() + (r.nextDouble() - 0.5) * 8, w.x * 0.3, -0.05, w.z * 0.3);
				if (time % 50 == 0) sound(mc, AuraSounds.ELYTRA_FLYING.value(), 0.35f * s, 0.55f);
				if (time % 9 == 0) sound(mc, AuraSounds.POWDER_SNOW_STEP.value(), 0.5f * s, 0.5f);
			}
			case THUNDERHEAD -> {
				for (int i = 0; i < n / 3; i++) {
					double x = p.getX() + (r.nextDouble() - 0.5) * 20, y = p.getY() + r.nextDouble() * 8, z = p.getZ() + (r.nextDouble() - 0.5) * 20;
					level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, (r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3);
				}
				if (--nextBolt <= 0) {
					nextBolt = 40 + r.nextInt(Math.max(1, Math.round(110 / Math.max(0.3f, s))));
					bolt(mc, r, 12 + r.nextDouble() * 50);
				}
			}
			case DOWNPOUR -> {
				for (int i = 0; i < n; i++) {
					double x = p.getX() + (r.nextDouble() - 0.5) * 16, z = p.getZ() + (r.nextDouble() - 0.5) * 16;
					int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
					if (i % 2 == 0) level.addParticle(ParticleTypes.SPLASH, x, top + 0.05, z, 0, 0.1, 0);
					else level.addParticle(ParticleTypes.FALLING_WATER, x, Math.max(top, p.getY()) + 3 + r.nextDouble() * 6, z, 0, -1, 0);
				}
				if (time % 4 == 0) level.addParticle(ParticleTypes.BUBBLE_POP, p.getX() + (r.nextDouble() - 0.5) * 3, p.getY() + 1.6, p.getZ() + (r.nextDouble() - 0.5) * 3, 0, 0, 0);
			}
			case HEAT_HAZE -> {
				for (int i = 0; i < n; i++) {
					double x = p.getX() + (r.nextDouble() - 0.5) * 20, y = p.getY() - 1 + r.nextDouble() * 6, z = p.getZ() + (r.nextDouble() - 0.5) * 20;
					int k = r.nextInt(10);
					if (k < 4) level.addParticle(ParticleTypes.FLAME, x, y, z, w.x * 0.03, 0.06 + r.nextDouble() * 0.08, w.z * 0.03);
					else if (k < 7) level.addParticle(ParticleTypes.WHITE_ASH, x, y + 3, z, w.x * 0.1, -0.02, w.z * 0.1);
					else if (k < 9) level.addParticle(ParticleTypes.SMALL_FLAME, x, y, z, 0, 0.1, 0);
					else level.addParticle(ParticleTypes.LAVA, x, y, z, 0, 0, 0);
				}
				if (time % 5 == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, p.getX() + (r.nextDouble() - 0.5) * 16, p.getY() + r.nextDouble() * 2, p.getZ() + (r.nextDouble() - 0.5) * 16, 0, 0.08, 0);
				if (time % 30 == 0) sound(mc, r.nextBoolean() ? AuraSounds.FIRE_AMBIENT.value() : AuraSounds.LAVA_AMBIENT.value(), 0.8f * s, 0.7f + r.nextFloat() * 0.3f);
			}
			case GALE -> {
				for (int i = 0; i < n; i++) {
					double x = p.getX() + (r.nextDouble() - 0.5) * 24 - w.x * 10, y = p.getY() - 1 + r.nextDouble() * 7, z = p.getZ() + (r.nextDouble() - 0.5) * 24 - w.z * 10;
					double sp = 0.9 + r.nextDouble() * 0.6;
					if (i % 5 == 0) level.addParticle(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(r.nextBoolean() ? Items.OAK_LEAVES : Items.SHORT_GRASS)), x, y, z, w.x * sp * 0.8, 0.05, w.z * sp * 0.8);
					else level.addParticle(i % 2 == 0 ? ParticleTypes.CLOUD : ParticleTypes.WHITE_ASH, x, y, z, w.x * sp, 0, w.z * sp);
				}
				if (time % 60 == 0) sound(mc, AuraSounds.ELYTRA_FLYING.value(), 0.5f * s, 0.8f);
			}
			case SPORE_BLOOM -> {
				for (int i = 0; i < n; i++) {
					double x = p.getX() + (r.nextDouble() - 0.5) * 20, y = p.getY() - 1 + r.nextDouble() * 8, z = p.getZ() + (r.nextDouble() - 0.5) * 20;
					int k = r.nextInt(8);
					if (k < 3) level.addParticle(ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 0, 0, 0);
					else if (k < 5) level.addParticle(ParticleTypes.CHERRY_LEAVES, x, y + 3, z, w.x * 0.05, 0, w.z * 0.05);
					else if (k < 7) level.addParticle(new DustParticleOptions(new Vector3f(0.55f, 1f, 0.35f), 1.6f), x, y, z, 0, 0.01, 0);
					else level.addParticle(ParticleTypes.FALLING_SPORE_BLOSSOM, x, y + 4, z, 0, 0, 0);
				}
				if (time % 4 == 0) level.addParticle(ParticleTypes.HAPPY_VILLAGER, p.getX() + (r.nextDouble() - 0.5) * 10, p.getY() + r.nextDouble() * 2, p.getZ() + (r.nextDouble() - 0.5) * 10, 0, 0, 0);
				if (time % 70 == 0) sound(mc, AuraSounds.AZALEA_LEAVES_BREAK.value(), 0.6f * s, 0.7f);
			}
			case TREMOR -> {
				var dust = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.GRAVEL.defaultBlockState());
				for (int i = 0; i < n / 2; i++) {
					level.addParticle(dust, p.getX() + (r.nextDouble() - 0.5) * 20, p.getY() + 4 + r.nextDouble() * 5, p.getZ() + (r.nextDouble() - 0.5) * 20, 0, 0, 0);
				}
				if (time % 10 == 0) CameraFx.add(0, 0.18f * s, 0f); // the ground never quite stops
				if (--nextQuake <= 0) {
					nextQuake = 70 + r.nextInt(90);
					quake(mc, r, s);
				}
			}
			case AURORA -> {
				// curtains of light in the sky, waving slowly, green to teal to violet
				int m = Math.round(90 * s * lvl);
				float t = time * 0.02f;
				for (int i = 0; i < m; i++) {
					int band = i % 3;
					double u = (r.nextDouble() - 0.5) * 90;
					// curtains on both sides of you, so one is always in view
					double side = (i / 3) % 2 == 0 ? 1 : -1;
					double along = u, across = side * (14 + band * 9 + Math.sin(u * 0.06 + t + band * 1.7) * 7);
					double x = p.getX() + along * -w.z + across * w.x, z = p.getZ() + along * w.x + across * w.z;
					double y = p.getY() + 16 + band * 3 + r.nextDouble() * 12;
					// self-lit: GLOW (green-teal) for the body of the curtain, END_ROD sparks along its lower hem
					level.addAlwaysVisibleParticle(i % 4 == 0 ? ParticleTypes.END_ROD : ParticleTypes.GLOW, x, y, z, 0, i % 4 == 0 ? -0.02 : 0.01, 0);
				}
				if (time % 3 == 0) level.addParticle(ParticleTypes.END_ROD, p.getX() + (r.nextDouble() - 0.5) * 16, p.getY() + 5 + r.nextDouble() * 4, p.getZ() + (r.nextDouble() - 0.5) * 16, 0, -0.02, 0);
				if (time % 40 == 0) sound(mc, AuraSounds.AMETHYST_BLOCK_CHIME.value(), 0.9f * s, 0.6f + r.nextFloat() * 0.6f);
			}
			case UMBRA -> {
				for (int i = 0; i < n; i++) {
					double a = r.nextDouble() * Math.PI * 2, d = 3 + r.nextDouble() * 9; // never right on the camera
					double x = p.getX() + Math.cos(a) * d, y = p.getY() - 1 + r.nextDouble() * 5, z = p.getZ() + Math.sin(a) * d;
					int k = r.nextInt(6);
					if (k < 3) level.addParticle(new DustParticleOptions(new Vector3f(0.12f, 0.06f, 0.2f), 2.2f), x, y, z, 0, 0.03, 0);
					else if (k < 5) level.addParticle(ParticleTypes.SMOKE, x, y, z, 0, 0.04, 0);
					else level.addParticle(ParticleTypes.SOUL, x, y, z, 0, 0.02, 0);
				}
				if (time % 90 == 0) sound(mc, AuraSounds.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS.value(), 1f * s, 0.6f);
			}
			case CRIMSON_MOON -> {
				for (int i = 0; i < n; i++) {
					double x = p.getX() + (r.nextDouble() - 0.5) * 20, y = p.getY() + r.nextDouble() * 8, z = p.getZ() + (r.nextDouble() - 0.5) * 20;
					if (i % 3 == 0) level.addParticle(ParticleTypes.CRIMSON_SPORE, x, y, z, 0, 0, 0);
					else level.addParticle(new DustParticleOptions(new Vector3f(0.65f, 0.02f, 0.06f), 1.4f), x, y + 2, z, 0, -0.35, 0);
				}
				if (++beat >= 22) { // the heartbeat
					beat = 0;
					sound(mc, AuraSounds.WARDEN_HEARTBEAT.value(), 0.9f * s, 0.9f);
				}
			}
		}
	}

	/** A real-looking (client-side, harmless) lightning strike somewhere in the storm round you, with a flash if close. */
	private static void bolt(Minecraft mc, RandomSource r, double dist) {
		ClientLevel level = mc.level;
		double a = r.nextDouble() * Math.PI * 2;
		double x = mc.player.getX() + Math.cos(a) * dist, z = mc.player.getZ() + Math.sin(a) * dist;
		if (storm != null && storm.strengthAt(x, z) <= 0) return;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
		var b = EntityType.LIGHTNING_BOLT.create(level);
		if (b == null) return;
		b.setVisualOnly(true);
		b.setId(-2_000_000 - (boltIds++ & 0xFFFF)); // never one of the server's ids
		b.moveTo(x, y, z);
		level.addEntity(b);
		if (dist < 45 && !AuraClientConfig.calm()) flash = (float) Math.min(1.0, 1.4 - dist / 45);
		if (dist < 25) CameraFx.add(0, 0.35f, 0f);
	}

	/** Tremor: a quake pulse - a heavy shake, a deep boom, the ground bursting up all round. */
	private static void quake(Minecraft mc, RandomSource r, float s) {
		ClientLevel level = mc.level;
		var p = mc.player;
		CameraFx.add(0, 0.55f * s + 0.2f, 0.015f);
		sound(mc, AuraSounds.GENERIC_EXPLODE.value(), 0.5f * s, 0.4f);
		sound(mc, AuraSounds.DEEPSLATE_BREAK.value(), 1f, 0.5f);
		for (int i = 0; i < Math.round(60 * level() * s); i++) {
			double x = p.getX() + (r.nextDouble() - 0.5) * 18, z = p.getZ() + (r.nextDouble() - 0.5) * 18;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
			var ground = level.getBlockState(new BlockPos(Mth.floor(x), y - 1, Mth.floor(z)));
			if (ground.isAir()) continue;
			level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), x, y + 0.1, z, 0, 0.3 + r.nextDouble() * 0.4, 0);
		}
	}

	/** Outside but near: a tall wall of the element at the edge (always visible; plain particles stop at 32 blocks). */
	private static void walls(Minecraft mc, RandomSource r, float lvl) {
		ClientLevel level = mc.level;
		for (var s : ClientStorms.all()) {
			double dx = mc.player.getX() - s.nowX(), dz = mc.player.getZ() - s.nowZ(), d = Math.sqrt(dx * dx + dz * dz);
			if (d < s.radius() * 0.8 || d > s.radius() + 110 || s.strength() <= 0.05f) continue;
			int n = Math.round(36 * s.strength() * lvl);
			Vec3 w = wind(s);
			for (int i = 0; i < n; i++) {
				double base = Math.atan2(dz, dx) + (r.nextDouble() - 0.5) * (160.0 / s.radius());
				double rr = s.radius() - r.nextDouble() * 4;
				double x = s.nowX() + Math.cos(base) * rr, z = s.nowZ() + Math.sin(base) * rr;
				double y = mc.player.getY() + r.nextDouble() * 40 - 6;
				double px = x - mc.player.getX(), py = y - mc.player.getEyeY(), pz = z - mc.player.getZ();
				if (px * px + py * py + pz * pz < 10 * 10) continue; // huge blotches right in your face otherwise
				boolean colour = i % 2 == 0;
				level.addAlwaysVisibleParticle(colour ? dust(s.type()) : wall(s.type()), x, y, z, w.x * wallSpeed(s.type()), wallFall(s.type()), w.z * wallSpeed(s.type()));
			}
			// a Thunderhead lights up from outside too
			if (s.type() == StormType.THUNDERHEAD && r.nextInt(70) == 0 && d < s.radius() + 90) {
				double a = Math.atan2(dz, dx) + (r.nextDouble() - 0.5) * 0.8, rr = s.radius() * (0.5 + r.nextDouble() * 0.4);
				double x = s.nowX() + Math.cos(a) * rr, z = s.nowZ() + Math.sin(a) * rr;
				var b = EntityType.LIGHTNING_BOLT.create(level);
				if (b != null) {
					b.setVisualOnly(true);
					b.setId(-2_000_000 - (boltIds++ & 0xFFFF));
					b.moveTo(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z)), z);
					level.addEntity(b);
				}
			}
		}
	}

	private static ParticleOptions dust(StormType type) {
		int rgb = type.rgb;
		float lift = type == StormType.UMBRA ? 0.35f : type == StormType.CRIMSON_MOON ? 0.25f : 0f;
		return new DustParticleOptions(new Vector3f(Math.min(1f, ((rgb >> 16) & 255) / 255f + lift), Math.min(1f, ((rgb >> 8) & 255) / 255f + lift * 0.3f),
				Math.min(1f, (rgb & 255) / 255f + lift * 0.4f)), 2.5f);
	}

	/** The element itself in the wall. */
	private static ParticleOptions wall(StormType type) {
		return switch (type) {
			case BLIZZARD -> AuraPoints.SNOW_GUST.get();
			case THUNDERHEAD, DOWNPOUR, GALE -> ParticleTypes.CLOUD;
			case HEAT_HAZE -> ParticleTypes.LARGE_SMOKE;
			case SPORE_BLOOM -> ParticleTypes.SPORE_BLOSSOM_AIR;
			case TREMOR -> new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.COARSE_DIRT.defaultBlockState());
			case AURORA -> ParticleTypes.END_ROD;
			case UMBRA -> ParticleTypes.LARGE_SMOKE;
			case CRIMSON_MOON -> ParticleTypes.CRIMSON_SPORE;
		};
	}

	private static double wallSpeed(StormType type) {
		return switch (type) {
			case BLIZZARD, GALE -> 0.5;
			case THUNDERHEAD, DOWNPOUR -> 0.15;
			default -> 0.03;
		};
	}

	private static double wallFall(StormType type) {
		return switch (type) {
			case HEAT_HAZE, UMBRA -> 0.06;
			case DOWNPOUR, THUNDERHEAD, CRIMSON_MOON -> -0.2;
			default -> -0.05;
		};
	}

	// ---------------------------------------------------------------
	// The wall: a tall curtain of the element round the storm, seen from outside
	// ---------------------------------------------------------------

	/** Curtain colour (lifted for the dark storms so they read against the sky). */
	private static int wallRgb(StormType t) {
		return switch (t) {
			case BLIZZARD -> 0xF0F8FF;
			case THUNDERHEAD -> 0x4A5070;
			case DOWNPOUR -> 0x4A7AAA;
			case HEAT_HAZE -> 0xFF8A3A;
			case GALE -> 0xD8EAE2;
			case SPORE_BLOOM -> 0x8FCF5A;
			case TREMOR -> 0xA08860;
			case AURORA -> 0x60FFC8;
			case UMBRA -> 0x1A1228;
			case CRIMSON_MOON -> 0x9A1020;
		};
	}

	/** How the streaks in the curtain move: >0 falling (rain, snow), <0 rising (heat, spores, shadow), 0 = sideways (wind). */
	private static float wallFlow(StormType t) {
		return switch (t) {
			case BLIZZARD, DOWNPOUR, THUNDERHEAD, CRIMSON_MOON -> 1.6f;
			case TREMOR -> 0.6f;
			case HEAT_HAZE, SPORE_BLOOM, UMBRA, AURORA -> -0.7f;
			case GALE -> 0f;
		};
	}

	/** An Iris shader pack is on: shader packs draw plain coloured geometry wrong (black), so no wall then. */
	private static boolean shaderPack() {
		try {
			if (!irisLooked) {
				irisLooked = true;
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				iris = api.getMethod("getInstance").invoke(null);
				irisInUse = api.getMethod("isShaderPackInUse");
			}
			return irisInUse != null && (Boolean) irisInUse.invoke(iris);
		} catch (Throwable e) {
			return false;
		}
	}

	private static boolean irisLooked;
	private static Object iris;
	private static java.lang.reflect.Method irisInUse;

	@SubscribeEvent
	public static void onRenderLevel(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.level.dimension() != Level.OVERWORLD || ClientStorms.all().isEmpty()) return;
		if (!AuraClientConfig.STORM_WALLS.get() || shaderPack()) return;
		Vec3 cam = event.getCamera().getPosition();
		float t = (mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20f;
		// only inside the normal view distance (LOD mods like Distant Horizons draw past it; the wall must never cover them)
		double reach = Math.max(48, mc.options.getEffectiveRenderDistance() * 16 - 16);
		BufferBuilder bb = null;
		for (var s : ClientStorms.all()) {
			double dx = cam.x - s.nowX(), dz = cam.z - s.nowZ(), d = Math.sqrt(dx * dx + dz * dz);
			// fades in as you step out of it, out with distance, and with the storm's own strength
			// nothing up close (it read as a glass pane right next to you); there the edge particles carry it
			float out = (float) Mth.clamp((d - s.radius() - 12) / 48, 0, 1);
			float a = out * s.strength();
			if (a <= 0.01f || d - s.radius() > reach) continue;
			if (bb == null) bb = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
					com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
			drawWall(bb, event.getPoseStack(), cam, s, a, t, reach);
		}
		if (bb == null) return;
		var mesh = bb.build();
		if (mesh == null) return;
		// blended on top, never writing depth: it can't stamp over terrain, LODs or other mods' drawing
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
		com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh);
		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	private static void drawWall(VertexConsumer vc, PoseStack pose, Vec3 cam, ClientStorms.Storm s, float alpha, float t, double reach) {
		Matrix4f m = pose.last().pose();
		int rgb = wallRgb(s.type());
		float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
		double cx = s.nowX() - cam.x, cz = s.nowZ() - cam.z;
		double y0 = Math.max(cam.y - 40, -64) - cam.y, top = 96;
		int segs = 160, rows = 10;
		float flow = wallFlow(s.type());
		boolean dark = s.type() == StormType.UMBRA || s.type() == StormType.CRIMSON_MOON;
		for (int i = 0; i < segs; i++) {
			double a0 = i * Math.PI * 2 / segs, a1 = (i + 1) * Math.PI * 2 / segs;
			// a ragged edge: the radius breathes a little along the ring and over time
			double r0 = s.radius() + Math.sin(a0 * 7 + t * 0.3) * 3, r1 = s.radius() + Math.sin(a1 * 7 + t * 0.3) * 3;
			double x0 = cx + Math.cos(a0) * r0, z0 = cz + Math.sin(a0) * r0, x1 = cx + Math.cos(a1) * r1, z1 = cz + Math.sin(a1) * r1;
			// each piece fades out before the edge of your view distance
			double dist = Math.sqrt(((x0 + x1) / 2) * ((x0 + x1) / 2) + ((z0 + z1) / 2) * ((z0 + z1) / 2));
			float near = (float) Mth.clamp((reach - dist) / 32, 0, 1);
			if (near <= 0) continue;
			// streaks: each column has its own phase; they fall, rise, or (Gale) race round the ring
			float col = flow == 0 ? Mth.sin(i * 0.9f - t * 6f) : Mth.sin(i * 2.3f);
			for (int k = 0; k < rows; k++) {
				double ya = y0 + (top - y0) * k / rows, yb = y0 + (top - y0) * (k + 1) / rows;
				float fa = fade(k / (float) rows), fb = fade((k + 1) / (float) rows);
				float sa = streak(col, ya, t, flow, i), sb = streak(col, yb, t, flow, i);
				float base = (dark ? 0.75f : 0.55f) * alpha * near;
				vc.addVertex(m, (float) x0, (float) ya, (float) z0).setColor(r, g, b, base * fa * sa);
				vc.addVertex(m, (float) x1, (float) ya, (float) z1).setColor(r, g, b, base * fa * sa);
				vc.addVertex(m, (float) x1, (float) yb, (float) z1).setColor(r, g, b, base * fb * sb);
				vc.addVertex(m, (float) x0, (float) yb, (float) z0).setColor(r, g, b, base * fb * sb);
			}
		}
	}

	/** Thick at the bottom, thinning into the sky. */
	private static float fade(float h) {
		return h < 0.08f ? h / 0.08f : 1f - (float) Math.pow((h - 0.08f) / 0.92f, 1.6);
	}

	private static float streak(float col, double y, float t, float flow, int i) {
		if (flow == 0) return 0.7f + 0.3f * col;
		return 0.65f + 0.35f * Mth.sin((float) (y * 0.12 + t * flow * 3 + i * 1.7f + col * 2));
	}

	// ---------------------------------------------------------------
	// The screen layer
	// ---------------------------------------------------------------

	@SubscribeEvent
	public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath("aurapoints", "storm"), StormFx::render);
	}

	private static final ResourceLocation FROST = ResourceLocation.withDefaultNamespace("textures/misc/powder_snow_outline.png");
	/** A ragged, cloudy frame (white, alpha only; made by tools/storm_vignette.py), tinted per storm. */
	private static final ResourceLocation VIGNETTE = ResourceLocation.fromNamespaceAndPath("aurapoints", "textures/misc/storm_vignette.png");

	/** The frame round your view inside a storm: its colour and how thick. */
	private static int vignetteRgb(StormType t) {
		return switch (t) {
			case BLIZZARD -> 0xF4FAFF;
			case THUNDERHEAD -> 0x262B42;
			case DOWNPOUR -> 0x2E5C8C;
			case HEAT_HAZE -> 0xFF6A1A;
			case GALE -> 0xE4F2EA;
			case SPORE_BLOOM -> 0x5FA83A;
			case TREMOR -> 0x6E5638;
			case AURORA -> 0x40E8B0;
			case UMBRA -> 0x06030C;
			case CRIMSON_MOON -> 0x70060F;
		};
	}

	private static float vignetteAlpha(StormType t) {
		return switch (t) {
			case UMBRA, BLIZZARD -> 0.95f;
			case AURORA -> 0.6f;
			default -> 0.85f;
		};
	}

	/** The storm's frame, breathing slowly (still with reduced motion). */
	private static void vignette(GuiGraphics g, int w, int h, StormType type, float amount, float t, boolean calm) {
		int rgb = vignetteRgb(type);
		float breath = calm ? 0 : Mth.sin(t * 0.8f);
		int k = Math.round(w * 0.015f * breath);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, Math.min(1f, amount * (0.92f + 0.08f * breath)));
		g.blit(VIGNETTE, -k, -k, -90, 0f, 0f, w + 2 * k, h + 2 * k, w + 2 * k, h + 2 * k);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	private static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		float s = shown;
		if (mc.options.hideGui) return;
		int w = g.guiWidth(), h = g.guiHeight();
		if (flash > 0.01f) g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb(0.55f * flash, 0xF2F5FF));
		if (s <= 0 || current == null) return;
		float lvl = level();
		boolean calm = AuraClientConfig.calm();
		float t = System.currentTimeMillis() / 1000f;
		int rgb = current.rgb;
		if (sting > 0.02f) StatusOverlays.edges(g, w, h, rgb, 0.6f * sting * s, Math.round(h * 0.35f));
		// every storm frames your view in its element, so you always know you're in it
		vignette(g, w, h, current, vignetteAlpha(current) * s * Math.max(0.6f, lvl), t, calm);
		switch (current) {
			case BLIZZARD -> {
				// the bell's whiteout, plus frost creeping in from the edges
				g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb(0.24f * s * lvl, 0xE6F4FF));
				StatusOverlays.edges(g, w, h, 0xCFE9FF, 0.6f * s, Math.round(h * 0.22f));
				RenderSystem.enableBlend();
				RenderSystem.setShaderColor(1f, 1f, 1f, Math.min(1f, 0.85f * s));
				g.blit(FROST, 0, 0, -90, 0f, 0f, w, h, w, h);
				RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			}
			case THUNDERHEAD -> {
				g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb(0.14f * s, 0x1E2235));
				StatusOverlays.edges(g, w, h, 0x10131F, 0.45f * s, Math.round(h * 0.25f));
			}
			case DOWNPOUR -> {
				g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb(0.10f * s, 0x3B6E9E));
				StatusOverlays.edges(g, w, h, 0x2A4F78, 0.35f * s, Math.round(h * 0.18f));
				drops(g, w, h, s * lvl, calm);
			}
			case HEAT_HAZE -> {
				float pulse = calm ? 0.5f : 0.5f + 0.5f * Mth.sin(t * 2.6f);
				g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb(0.09f * s, 0xFF9A40));
				StatusOverlays.edges(g, w, h, 0xFF5A10, (0.3f + 0.2f * pulse) * s, Math.round(h * (0.16f + 0.05f * pulse)));
			}
			case GALE -> {
				g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb(0.07f * s, 0xE8F4EE));
				StatusOverlays.edges(g, w, h, 0xDDEBE4, 0.3f * s, Math.round(h * 0.14f));
				if (!calm) streaks(g, w, h, s * lvl, t);
			}
			case SPORE_BLOOM -> {
				g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb(0.10f * s, 0x9FD860));
				StatusOverlays.edges(g, w, h, 0x4F8A2A, 0.35f * s, Math.round(h * 0.2f));
				vines(g, w, h, s, calm ? 0 : t);
			}
			case TREMOR -> {
				g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb(0.12f * s, 0x9C8466));
				StatusOverlays.edges(g, w, h, 0x4A3A28, 0.45f * s, Math.round(h * 0.2f));
			}
			case AURORA -> StatusOverlays.edges(g, w, h, 0x40FFC0, (0.15f + (calm ? 0 : 0.08f * Mth.sin(t * 0.8f))) * s, Math.round(h * 0.18f));
			case UMBRA -> {
				float pulse = calm ? 0.5f : 0.5f + 0.5f * Mth.sin(t * 0.9f);
				g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb((0.30f + 0.1f * pulse) * s, 0x0A0612));
				StatusOverlays.edges(g, w, h, 0x000000, 0.8f * s, Math.round(h * (0.3f + 0.06f * pulse)));
			}
			case CRIMSON_MOON -> {
				// two thumps per beat, in time with the heartbeat sound
				float ph = beat / 22f, thump = calm ? 0.3f : (float) (Math.exp(-ph * 30) + 0.6 * Math.exp(-Math.max(0, ph - 0.16f) * 30) * (ph > 0.16f ? 1 : 0));
				g.fill(StatusOverlays.FX, 0, 0, w, h, HudColors.argb(0.12f * s, 0x8A1020));
				StatusOverlays.edges(g, w, h, 0x6A0010, (0.35f + 0.4f * thump) * s, Math.round(h * (0.2f + 0.08f * thump)));
			}
		}
	}

	/** Downpour: rain running down the lens. */
	private static void drops(GuiGraphics g, int w, int h, float amount, boolean still) {
		RandomSource r = RandomSource.create();
		for (float[] d : DROPS) {
			if (d[2] == 0 || d[1] > 1.1f) {
				d[0] = r.nextFloat();
				d[1] = -r.nextFloat() * 0.5f;
				d[2] = 2 + r.nextFloat() * 4;
				d[3] = 0.002f + r.nextFloat() * 0.006f;
			}
			if (!still) d[1] += d[3];
			int x = Math.round(d[0] * w), y = Math.round(d[1] * h), sz = Math.round(d[2]);
			int a = Math.round(90 * amount);
			g.fill(StatusOverlays.FX, x, y, x + sz, y + sz + 1, (a << 24) | 0xB8D8F0);
			g.fill(StatusOverlays.FX, x, y - sz * 3, x + Math.max(1, sz / 2), y, ((a / 3) << 24) | 0xB8D8F0);
		}
	}

	/** Gale: thin wind streaks racing across the screen. */
	private static void streaks(GuiGraphics g, int w, int h, float amount, float t) {
		for (int i = 0; i < 14; i++) {
			float speed = 0.6f + (i * 37 % 10) / 10f;
			float x = ((t * speed + i * 0.137f) % 1.4f - 0.2f) * w;
			float y = ((i * 0.618f) % 1f) * h;
			int len = Math.round(w * (0.08f + (i % 4) * 0.03f));
			int a = Math.round(70 * amount);
			g.fill(StatusOverlays.FX, Math.round(x), Math.round(y), Math.round(x) + len, Math.round(y) + 1, (a << 24) | 0xFFFFFF);
		}
	}

	/** Spore Bloom: leafy vines curling in from the corners, swaying. */
	private static void vines(GuiGraphics g, int w, int h, float amount, float t) {
		for (int v = 0; v < 8; v++) {
			boolean left = v % 2 == 0, top = v % 4 < 2;
			float x = left ? 0 : w, y = top ? 0 : h;
			float dir = (float) Math.toRadians((left ? 0 : 180) + (top ? 1 : -1) * (left ? 35 + v * 6 : -35 - v * 6));
			int steps = Math.round(26 * amount);
			for (int k = 0; k < steps; k++) {
				dir += Mth.sin(k * 0.5f + v + t * 0.8f) * 0.12f;
				x += Mth.cos(dir) * 5;
				y += Mth.sin(dir) * 5;
				int th = Math.max(1, 4 - k / 8);
				g.fill(StatusOverlays.FX, Math.round(x), Math.round(y), Math.round(x) + th, Math.round(y) + th, 0xE0000000 | 0x2F6A1E);
				if (k % 5 == 3) g.fill(StatusOverlays.FX, Math.round(x) + 2, Math.round(y) - 3, Math.round(x) + 6, Math.round(y) + 1, 0xD0000000 | 0x6FB83A);
			}
		}
	}
}
