package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * CLIENT ONLY. The sky of a rift realm (5.48.0, FxPacket.RIFT_SKY): the dimension is a fixed noon with skylight, and
 * each element gets its own sky colour, fog and daylight (mixin/ClientLevelMixin): Wind a bright open blue, Light a
 * warm gold, Frost a pale ice blue, Shadow near night, Blood a dark red, Fire an ember dusk... Only while in the rift.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class RiftSky {

	private RiftSky() {
	}

	private static final ResourceLocation RIFT = ResourceLocation.fromNamespaceAndPath("aurapoints", "rift");
	private static AuraType aura;

	public static void set(String id) {
		aura = id == null || id.isEmpty() ? null : AuraType.byId(id);
	}

	private static boolean here() {
		Minecraft mc = Minecraft.getInstance();
		return aura != null && mc.level != null && mc.level.dimension().location().equals(RIFT);
	}

	/** {sky rgb, fog rgb, daylight x100} per element. */
	private static int[] look(AuraType a) {
		return switch (a) {
			case WIND -> new int[]{0x7CC4FF, 0xD8EEFF, 100};
			case LIGHT -> new int[]{0xFFD98A, 0xFFF1CC, 100};
			case FROST -> new int[]{0xA9DDF5, 0xE6F6FF, 92};
			case VERDANT -> new int[]{0x7FC79A, 0xA9D9A0, 82};
			case WATER -> new int[]{0x2F78B8, 0x2A5E8C, 68};
			case EARTH -> new int[]{0xD69A5E, 0xC9925E, 74};
			case FIRE -> new int[]{0x7A2A12, 0x5C1E0E, 42};
			case THUNDER -> new int[]{0x3C4458, 0x2E3445, 38};
			case BLOOD -> new int[]{0x4E0C14, 0x3A070D, 28};
			case SHADOW -> new int[]{0x1C1030, 0x120A20, 18};
			default -> new int[]{0x6B87C0, 0x8FA6D0, 70};
		};
	}

	private static Vec3 rgb(int c) {
		return new Vec3(((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0);
	}

	/** The realm's sky colour, or null outside a realm. */
	public static Vec3 sky() {
		return here() ? rgb(look(aura)[0]) : null;
	}

	/** The realm's daylight 0..1 (the sky darkening the lightmap uses), or -1 outside a realm. */
	public static float daylight() {
		return here() ? look(aura)[2] / 100f : -1f;
	}

	@SubscribeEvent
	public static void onFogColor(ViewportEvent.ComputeFogColor event) {
		if (!here()) return;
		Vec3 f = rgb(look(aura)[1]);
		event.setRed((float) f.x);
		event.setGreen((float) f.y);
		event.setBlue((float) f.z);
	}
}
