package com.toast.aurapoints.client;

import com.toast.aurapoints.network.ClientStorms;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterGuiLayersEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;
import com.toast.aurapoints.shim.neoforge.client.gui.VanillaGuiLayers;
import com.toast.aurapoints.weather.StormType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * CLIENT ONLY. Local storms (5.39.0, network/ClientStorms): inside one the fog and sky take its colour and
 * its weather falls around you (rain, snow, ash, spores, dust...); from outside you see a wall of it at
 * the edge. Strength follows the distance to the storm's middle; the client Effect level trims it.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class StormFx {

	private StormFx() {
	}

	/** The storm the camera is in and its strength there (smoothed). */
	private static StormType current = null;
	private static float shown = 0f;

	private static float level() {
		return switch (com.toast.aurapoints.config.AuraClientConfig.EFFECT_LEVEL.get()) {
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

	private static void update(Minecraft mc) {
		float target = 0f;
		StormType type = null;
		if (mc.level != null && mc.player != null && mc.level.dimension() == Level.OVERWORLD) {
			var s = ClientStorms.strongest(mc.player.getX(), mc.player.getZ());
			if (s != null) {
				target = s.strengthAt(mc.player.getX(), mc.player.getZ());
				type = s.type();
			}
		}
		if (type != null) current = type;
		shown += (target - shown) * 0.05f;
		if (shown < 0.005f && target == 0f) {
			shown = 0f;
			current = null;
		}
	}

	/** ClientLevelMixin: the sky leans to the storm's colour. */
	public static Vec3 tintSky(Vec3 sky) {
		float s = shown;
		if (s <= 0 || current == null) return sky;
		float k = 0.55f * s;
		int rgb = current.rgb;
		return new Vec3(Mth.lerp(k, sky.x, ((rgb >> 16) & 255) / 255.0), Mth.lerp(k, sky.y, ((rgb >> 8) & 255) / 255.0), Mth.lerp(k, sky.z, (rgb & 255) / 255.0));
	}

	@SubscribeEvent
	public static void onFogColor(ViewportEvent.ComputeFogColor event) {
		float s = shown;
		if (s <= 0 || current == null) return;
		float k = 0.6f * s;
		int rgb = current.rgb;
		event.setRed(Mth.lerp(k, event.getRed(), ((rgb >> 16) & 255) / 255f));
		event.setGreen(Mth.lerp(k, event.getGreen(), ((rgb >> 8) & 255) / 255f));
		event.setBlue(Mth.lerp(k, event.getBlue(), (rgb & 255) / 255f));
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		update(mc);
		if (mc.level == null || mc.player == null || mc.isPaused() || mc.level.dimension() != Level.OVERWORLD) return;
		RandomSource r = mc.level.random;
		float lvl = level();
		// inside: the weather round the camera
		if (shown > 0.02f && current != null) {
			int n = Math.round(12 * shown * lvl);
			for (int i = 0; i < n; i++) {
				double x = mc.player.getX() + (r.nextDouble() - 0.5) * 24, y = mc.player.getY() + r.nextDouble() * 10 - 2, z = mc.player.getZ() + (r.nextDouble() - 0.5) * 24;
				inside(mc, current, r, x, y, z);
			}
		}
		// outside but near: a wall of it at the edge
		if (mc.level.getGameTime() % 2 == 0) {
			for (var s : ClientStorms.all()) {
				double dx = mc.player.getX() - s.nowX(), dz = mc.player.getZ() - s.nowZ(), d = Math.sqrt(dx * dx + dz * dz);
				if (d < s.radius() * 0.8 || d > s.radius() + 96 || s.strength() <= 0.05f) continue;
				int n = Math.round(28 * s.strength() * lvl);
				for (int i = 0; i < n; i++) {
					// a point on the edge within ~70 blocks of the player; always visible (plain particles stop at 32 blocks)
					double base = Math.atan2(dz, dx) + (r.nextDouble() - 0.5) * (140.0 / s.radius());
					double x = s.nowX() + Math.cos(base) * s.radius(), z = s.nowZ() + Math.sin(base) * s.radius();
					double y = mc.player.getY() + r.nextDouble() * 28 - 4;
					mc.level.addAlwaysVisibleParticle(edge(s.type()), x, y, z, 0, -0.05, 0);
				}
			}
		}
	}

	private static void inside(Minecraft mc, StormType type, RandomSource r, double x, double y, double z) {
		var level = mc.level;
		switch (type) {
			case THUNDERHEAD, DOWNPOUR -> level.addParticle(ParticleTypes.FALLING_WATER, x, y + 4, z, 0, -1, 0);
			case BLIZZARD -> level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, 0.45 + r.nextDouble() * 0.2, -0.1, 0.15);
			case HEAT_HAZE -> level.addParticle(r.nextInt(4) == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.WHITE_ASH, x, y, z, 0, 0.02, 0);
			case GALE -> level.addParticle(ParticleTypes.CLOUD, x, y, z, 0.6, 0, 0.2);
			case SPORE_BLOOM -> level.addParticle(r.nextBoolean() ? ParticleTypes.SPORE_BLOSSOM_AIR : ParticleTypes.HAPPY_VILLAGER, x, y, z, 0, 0, 0);
			case TREMOR -> level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.GRAVEL.defaultBlockState()), x, y + 4, z, 0, 0, 0);
			case AURORA -> level.addParticle(new DustParticleOptions(new Vector3f(0.5f, 1f, 0.8f), 1.2f), x, y + 6, z, 0, 0, 0);
			case UMBRA -> level.addParticle(ParticleTypes.SQUID_INK, x, y, z, 0, 0.01, 0);
			case CRIMSON_MOON -> level.addParticle(new DustParticleOptions(new Vector3f(0.6f, 0.05f, 0.1f), 1f), x, y, z, 0, -0.02, 0);
		}
	}

	/** The wall at a storm's edge: big dust in the storm's colour (lifted so it reads at night), clouds for the rain storms. */
	private static ParticleOptions edge(StormType type) {
		return switch (type) {
			case THUNDERHEAD, DOWNPOUR, GALE -> ParticleTypes.CLOUD;
			default -> {
				int rgb = type.rgb;
				float lift = type == StormType.UMBRA ? 0.35f : type == StormType.CRIMSON_MOON ? 0.25f : 0f;
				yield new DustParticleOptions(new Vector3f(Math.min(1f, ((rgb >> 16) & 255) / 255f + lift), Math.min(1f, ((rgb >> 8) & 255) / 255f + lift * 0.3f),
						Math.min(1f, (rgb & 255) / 255f + lift * 0.4f)), 3.0f);
			}
		};
	}

	@SubscribeEvent
	public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, ResourceLocation.fromNamespaceAndPath("aurapoints", "storm"), StormFx::render);
	}

	/** A light veil in the storm's colour (darker for Umbra and the Crimson Moon). */
	private static void render(GuiGraphics g, DeltaTracker delta) {
		float s = shown;
		if (s <= 0 || current == null || Minecraft.getInstance().options.hideGui) return;
		float a = switch (current) {
			case UMBRA -> 0.35f;
			case CRIMSON_MOON, BLIZZARD -> 0.18f;
			default -> 0.08f;
		} * s * level();
		g.fill(StatusOverlays.FX, 0, 0, g.guiWidth(), g.guiHeight(), HudColors.argb(a, current.rgb));
	}
}
