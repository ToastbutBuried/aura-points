package com.toast.aurapoints.client;

import com.toast.aurapoints.network.RiftInputPacket;
import com.toast.aurapoints.rift.SkillChecks;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.InputEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterGuiLayersEvent;
import com.toast.aurapoints.shim.neoforge.network.PacketDistributor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * CLIENT ONLY. The hold-and-release bar (5.52, FxPacket.SKILL_CHECK, rift/SkillChecks): hold Use while it fills, let go
 * in the bright zone. A HUD layer, never a screen: you keep looking and walking around.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class SkillCheckHud {

	private SkillCheckHud() {
	}

	private static final int W = 150, H = 9;
	private static boolean active, waitUp;
	private static int id, ticks, t, rgb, flash, flashKind;
	private static float lo, hi;
	private static String label = "";

	/** value = ticks to fill (0 = stop), text = "id US rgb US lo US hi US label". */
	public static void onPacket(int value, String text) {
		active = false;
		if (value <= 0) {
			waitUp = true;
			return;
		}
		String[] f = text.split("\u001F", -1);
		if (f.length < 5) return;
		try {
			id = Integer.parseInt(f[0]);
			rgb = Integer.parseInt(f[1], 16);
			lo = Float.parseFloat(f[2]);
			hi = Float.parseFloat(f[3]);
		} catch (NumberFormatException e) {
			return;
		}
		label = f[4];
		ticks = value;
		t = 0;
		flash = 0;
		active = true;
	}

	private static double fill(float partial) {
		double u = (t + partial) / ticks;
		return SkillChecks.fill(u);
	}

	private static void answer(int value) {
		active = false;
		waitUp = true;
		flash = 10;
		flashKind = value;
		PacketDistributor.sendToServer(new RiftInputPacket(RiftInputPacket.SKILL, id, value));
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			active = waitUp = false;
			return;
		}
		if (mc.isPaused()) return;
		boolean down = mc.options.keyUse.isDown();
		if (flash > 0) flash--;
		if (waitUp && !down) waitUp = false;
		if (!active) return;
		t++;
		if (mc.screen != null) {
			answer(SkillChecks.CANCEL);
			return;
		}
		double v = fill(0);
		if (!down) {
			answer(v < lo ? SkillChecks.EARLY : v <= hi ? SkillChecks.GOOD : SkillChecks.LATE);
		} else if (t >= ticks) answer(SkillChecks.LATE);
	}

	/** Holding Use must not click over and over (nor start the next check before you let go). */
	@SubscribeEvent
	public static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
		if (!(active || waitUp) || event.getButton() != 1) return;
		event.setCanceled(true);
		event.setSwingHand(false);
	}

	@SubscribeEvent
	public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("aurapoints", "skill_check"), SkillCheckHud::render);
	}

	private static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || (!active && flash <= 0)) return;
		float partial = delta.getGameTimeDeltaPartialTick(false);
		int x = (g.guiWidth() - W) / 2, y = g.guiHeight() / 2 + 34;
		int edge = 0xFF000000 | (active ? 0xE8E8F0 : flashKind == SkillChecks.GOOD ? 0x6CFF8A : 0xFF5A5A);
		g.fill(x - 2, y - 2, x + W + 2, y + H + 2, edge);
		g.fill(x - 1, y - 1, x + W + 1, y + H + 1, 0xF0101018);
		int z0 = x + Math.round(lo * W), z1 = x + Math.round(hi * W);
		g.fill(z0, y, z1, y + H, 0xFF3C8F52);
		g.fill(z0, y, z0 + 1, y + H, 0xFFB8FFC8);
		g.fill(z1 - 1, y, z1, y + H, 0xFFB8FFC8);
		if (active) {
			double v = fill(partial);
			int fx = x + (int) Math.round(v * W);
			boolean in = v >= lo && v <= hi;
			g.fill(x, y + 2, fx, y + H - 2, 0xD0000000 | (in ? 0xB8FFC8 : rgb));
			g.fill(fx - 1, y - 3, fx + 1, y + H + 3, 0xFFFFFFFF);
			g.drawCenteredString(mc.font, Component.literal(label), g.guiWidth() / 2, y - 14, 0xFFFFFF);
			g.drawCenteredString(mc.font, Component.literal(in ? "Let go!" : "Hold ").append(in ? Component.empty() : mc.options.keyUse.getTranslatedKeyMessage()).append(in ? "" : ", let go in the green"), g.guiWidth() / 2, y + H + 5, in ? 0x9CFFB0 : 0xC0C0C8);
		} else {
			String s = flashKind == SkillChecks.GOOD ? "Good!" : flashKind == SkillChecks.EARLY ? "Too early" : flashKind == SkillChecks.LATE ? "Too late" : "";
			g.drawCenteredString(mc.font, Component.literal(s), g.guiWidth() / 2, y + H + 5, edge);
		}
	}
}
