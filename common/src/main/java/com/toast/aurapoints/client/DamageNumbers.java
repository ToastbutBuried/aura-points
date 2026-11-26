package com.toast.aurapoints.client;

import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.RenderLevelStageEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * CLIENT ONLY. Floating damage numbers (5.61, ticket #18) over what you hit, from FxPacket.DAMAGE: gold for
 * monsters and animals (PvE), red for players (PvP), bigger with a "!" on a kill. They rise and fade in a second.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class DamageNumbers {

	private DamageNumbers() {
	}

	private record Num(Vec3 pos, String text, int rgb, float scale, long born, float drift) {
	}

	private static final List<Num> NUMBERS = new ArrayList<>();
	private static final long LIFE_MILLIS = 1000;
	private static final int PVE_RGB = 0xFFC93C, PVP_RGB = 0xFF5555, KILL_RGB = 0xFFFFFF;

	/** From the Fx packet: value = tenths of a health point, text = "entity id;flags". */
	public static void add(Minecraft mc, int tenths, String text) {
		if (mc.level == null || !AuraClientConfig.DAMAGE_NUMBERS.get()) return;
		String[] parts = text.split(";");
		if (parts.length < 2) return;
		Entity e;
		int flags;
		try {
			e = mc.level.getEntity(Integer.parseInt(parts[0]));
			flags = Integer.parseInt(parts[1]);
		} catch (NumberFormatException ex) {
			return;
		}
		if (e == null || tenths <= 0) return;
		boolean player = (flags & 1) != 0, kill = (flags & 2) != 0, ability = (flags & 4) != 0;
		float dmg = tenths / 10f;
		String s = (dmg >= 10 ? Integer.toString(Math.round(dmg)) : String.format(java.util.Locale.ROOT, "%.1f", dmg))
				+ (kill ? "!" : ability ? " ✦" : "");
		float spread = (e.getRandom().nextFloat() - 0.5f) * e.getBbWidth();
		Vec3 pos = new Vec3(e.getX() + spread, e.getY() + e.getBbHeight() + 0.25, e.getZ() + spread);
		int rgb = kill ? KILL_RGB : player ? PVP_RGB : PVE_RGB;
		float scale = kill ? 0.04f : ability ? 0.034f : 0.027f;
		NUMBERS.add(new Num(pos, s, rgb, scale, System.currentTimeMillis(), 0.9f + e.getRandom().nextFloat() * 0.4f));
		while (NUMBERS.size() > 40) NUMBERS.remove(0);
	}

	@SubscribeEvent
	public static void onRenderLevel(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || NUMBERS.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			NUMBERS.clear();
			return;
		}
		long now = System.currentTimeMillis();
		NUMBERS.removeIf(n -> now - n.born > LIFE_MILLIS);
		Vec3 cam = event.getCamera().getPosition();
		var pose = event.getPoseStack();
		var buffers = mc.renderBuffers().bufferSource();
		Font font = mc.font;
		for (Num n : NUMBERS) {
			float t = (now - n.born) / (float) LIFE_MILLIS;
			float rise = (1f - (1f - t) * (1f - t)) * n.drift;
			float alpha = t < 0.6f ? 1f : (1f - t) / 0.4f;
			int argb = (Math.max(4, (int) (alpha * 255)) << 24) | n.rgb;
			pose.pushPose();
			pose.translate(n.pos.x - cam.x, n.pos.y + rise - cam.y, n.pos.z - cam.z);
			pose.mulPose(event.getCamera().rotation());
			float s = n.scale * (t < 0.12f ? 0.7f + t / 0.12f * 0.3f : 1f);
			pose.scale(-s, -s, s);
			float x = -font.width(n.text) / 2f;
			font.drawInBatch(n.text, x, 0, argb, true, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
			pose.popPose();
		}
		buffers.endBatch();
	}
}
