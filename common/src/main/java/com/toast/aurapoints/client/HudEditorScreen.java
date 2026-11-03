package com.toast.aurapoints.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraHud;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterClientCommandsEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterKeyMappingsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * CLIENT ONLY. Drag the ability panel and the charge bar wherever you like; the mouse wheel over one
 * resizes it. Opened with /aurahud or the "Edit Aura HUD" key (unbound at first). Saved into the client
 * config as offsets from the chosen anchor, so it stays put across window sizes as well as it can.
 */
public class HudEditorScreen extends Screen {

	public static final KeyMapping OPEN = com.toast.aurapoints.shim.ClientPlatform.get().inGameKey("key.aurapoints.edit_hud",
			InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "key.categories.aurapoints");

	/** Set by the command; opened on the next tick (the chat screen closes after a command). */
	private static boolean openNextTick = false;

	private enum Part {
		NONE, PANEL, BAR
	}

	private Part dragging = Part.NONE;
	private double grabX, grabY;

	public HudEditorScreen() {
		super(Component.literal("Aura HUD"));
	}

	@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
	public static final class Events {
		private Events() {
		}

		@SubscribeEvent
		public static void onKeys(RegisterKeyMappingsEvent event) {
			event.register(OPEN);
		}

		@SubscribeEvent
		public static void onCommands(RegisterClientCommandsEvent event) {
			event.getDispatcher().register(net.minecraft.commands.Commands.literal("aurahud").executes(ctx -> {
				openNextTick = true;
				return 1;
			}));
		}

		@SubscribeEvent
		public static void onTick(ClientTickEvent.Post event) {
			Minecraft mc = Minecraft.getInstance();
			while (OPEN.consumeClick()) openNextTick = true;
			if (openNextTick && mc.player != null && (mc.screen == null || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
				openNextTick = false;
				mc.setScreen(new HudEditorScreen());
			}
		}
	}

	@Override
	protected void init() {
		int y = height - 28;
		addRenderableWidget(Button.builder(Component.literal("Anchor: " + pretty(AuraClientConfig.HUD_ANCHOR.get().name())), b -> {
			AuraClientConfig.Anchor[] all = AuraClientConfig.Anchor.values();
			AuraClientConfig.HUD_ANCHOR.set(all[(AuraClientConfig.HUD_ANCHOR.get().ordinal() + 1) % all.length]);
			AuraClientConfig.HUD_OFFSET_X.set(0);
			AuraClientConfig.HUD_OFFSET_Y.set(0);
			rebuildWidgets();
		}).bounds(width / 2 - 206, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Bar: " + pretty(AuraClientConfig.CHARGE_BAR.get().name())), b -> {
			AuraClientConfig.BarPlace[] all = AuraClientConfig.BarPlace.values();
			AuraClientConfig.CHARGE_BAR.set(all[(AuraClientConfig.CHARGE_BAR.get().ordinal() + 1) % all.length]);
			AuraClientConfig.CHARGE_OFFSET_X.set(0);
			AuraClientConfig.CHARGE_OFFSET_Y.set(0);
			rebuildWidgets();
		}).bounds(width / 2 - 102, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
			AuraClientConfig.HUD_ANCHOR.set(AuraClientConfig.Anchor.HOTBAR);
			AuraClientConfig.HUD_OFFSET_X.set(0);
			AuraClientConfig.HUD_OFFSET_Y.set(0);
			AuraClientConfig.HUD_SCALE.set(1.0);
			AuraClientConfig.CHARGE_BAR.set(AuraClientConfig.BarPlace.LEFT);
			AuraClientConfig.CHARGE_OFFSET_X.set(0);
			AuraClientConfig.CHARGE_OFFSET_Y.set(0);
			AuraClientConfig.CHARGE_SCALE.set(1.0);
			rebuildWidgets();
		}).bounds(width / 2 + 2, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 106, y, 100, 20).build());
	}

	private static String pretty(String name) {
		String s = name.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	@Override
	public void onClose() {
		AuraClientConfig.SPEC.save();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** What the panel would show (yours, or a sample if you own nothing yet). */
	private List<Ability> previewAbilities() {
		AuraType aura = aura();
		List<Ability> shown = new ArrayList<>(ClientAuraHud.shownAbilities(aura));
		if (shown.isEmpty()) shown.addAll(aura.keySlots());
		return shown;
	}

	private static AuraType aura() {
		return ClientAuraState.aura != null ? ClientAuraState.aura : AuraType.FIRE;
	}

	private String label() {
		return "✦ " + aura().displayName + " ✦";
	}

	/** {x, y, w, h} of the panel body on screen. */
	private int[] panelRect() {
		int w = ClientAuraHud.width(minecraft, label(), previewAbilities().size()), h = ClientAuraHud.height();
		int[] at = HudLayout.panel(minecraft, width, height, w, h);
		float s = HudLayout.scale();
		return new int[]{at[0], at[1], Math.round(w * s), Math.round(h * s)};
	}

	/** {x, y, w, h} of the charge bar with its icon and text. */
	private int[] barRect() {
		int[] at = HudLayout.bar(width, height);
		float s = HudLayout.chargeScale();
		return new int[]{at[0] - Math.round(4 * s), at[1] - Math.round(24 * s), Math.round((HudLayout.BAR_W + 8) * s), Math.round((HudLayout.BAR_H + 36) * s)};
	}

	private static boolean inside(int[] r, double x, double y) {
		return x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3];
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		int rgb = HudColors.of(aura());
		g.drawCenteredString(font, "Drag the panel and the charge bar. Mouse wheel over one: bigger / smaller.", width / 2, 12, 0xFFFFFFFF);
		g.drawCenteredString(font, "Size " + Math.round(HudLayout.scale() * 100) + "%  ·  Charge bar " + Math.round(AuraClientConfig.CHARGE_SCALE.get() * 100) + "%",
				width / 2, 24, 0xFFAAAAAA);

		// the panel, as it will look (static)
		int[] p = panelRect();
		List<Ability> abilities = previewAbilities();
		float s = HudLayout.scale();
		int w = ClientAuraHud.width(minecraft, label(), abilities.size()), h = ClientAuraHud.height();
		g.pose().pushPose();
		g.pose().translate(p[0], p[1], 0);
		g.pose().scale(s, s, 1);
		ClientAuraHud.drawFrame(g, 0, 0, w, h, rgb);
		if (AuraClientConfig.HUD_LABEL.get()) {
			g.pose().pushPose();
			g.pose().translate(w / 2f, -8, 0);
			g.pose().scale(0.75f, 0.75f, 1);
			g.drawString(font, label(), -font.width(label()) / 2, 0, 0xFF000000 | rgb, true);
			g.pose().popPose();
		}
		int slotsWidth = abilities.size() * 22 + (abilities.size() - 1) * 3;
		for (int i = 0; i < abilities.size(); i++) {
			int sx = (w - slotsWidth) / 2 + i * 25, sy = 4;
			g.fill(sx, sy, sx + 22, sy + 22, 0xFF2C2C38);
			g.fill(sx + 1, sy + 1, sx + 21, sy + 21, 0xFF101016);
			g.renderItem(ClientAuraHud.icon(abilities.get(i)), sx + 3, sy + 3);
			g.renderOutline(sx, sy, 22, 22, 0xFF000000 | rgb);
		}
		g.pose().popPose();
		outline(g, p, inside(p, mouseX, mouseY) || dragging == Part.PANEL);

		// the charge bar, half full
		int[] b = barRect();
		int[] at = HudLayout.bar(width, height);
		float bs = HudLayout.chargeScale();
		g.pose().pushPose();
		g.pose().translate(at[0], at[1], 0);
		g.pose().scale(bs, bs, 1);
		g.fillGradient(-3, -3, HudLayout.BAR_W + 3, HudLayout.BAR_H + 3, HudColors.panelTop(), HudColors.panelBottom());
		g.renderOutline(-3, -3, HudLayout.BAR_W + 6, HudLayout.BAR_H + 6, 0xB0000000 | rgb);
		g.fill(0, 0, HudLayout.BAR_W, HudLayout.BAR_H, 0xFF101016);
		g.fillGradient(0, HudLayout.BAR_H / 2, HudLayout.BAR_W, HudLayout.BAR_H, 0xFF000000 | HudColors.mix(rgb, 0xFFFFFF, 0.4f), 0xFF000000 | rgb);
		g.pose().popPose();
		outline(g, b, inside(b, mouseX, mouseY) || dragging == Part.BAR);
	}

	private static void outline(GuiGraphics g, int[] r, boolean hot) {
		g.renderOutline(r[0] - 3, r[1] - 3, r[2] + 6, r[3] + 6, hot ? 0xFFFFFFFF : 0x80FFFFFF);
	}

	@Override
	public boolean mouseClicked(double x, double y, int button) {
		if (super.mouseClicked(x, y, button)) return true;
		if (button != 0) return false;
		int[] p = panelRect(), b = barRect();
		if (inside(b, x, y)) {
			dragging = Part.BAR;
			grabX = x - b[0];
			grabY = y - b[1];
			return true;
		}
		if (inside(p, x, y)) {
			dragging = Part.PANEL;
			grabX = x - p[0];
			grabY = y - p[1];
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		if (dragging == Part.PANEL) {
			int w = ClientAuraHud.width(minecraft, label(), previewAbilities().size()), h = ClientAuraHud.height();
			int[] base = HudLayout.panelBase(minecraft, width, height, w, h);
			AuraClientConfig.HUD_OFFSET_X.set((int) Math.round(x - grabX) - base[0]);
			AuraClientConfig.HUD_OFFSET_Y.set((int) Math.round(y - grabY) - base[1]);
			return true;
		}
		if (dragging == Part.BAR) {
			int[] base = HudLayout.barBase(width, height);
			float s = HudLayout.chargeScale();
			AuraClientConfig.CHARGE_OFFSET_X.set((int) Math.round(x - grabX + 4 * s) - base[0]);
			AuraClientConfig.CHARGE_OFFSET_Y.set((int) Math.round(y - grabY + 24 * s) - base[1]);
			return true;
		}
		return super.mouseDragged(x, y, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		if (dragging != Part.NONE) {
			dragging = Part.NONE;
			// whatever the drag ended on: the stored offset is where it really sits after keeping it on screen
			snapStored();
			return true;
		}
		return super.mouseReleased(x, y, button);
	}

	/** Stores the clamped position, so an offset dragged past the edge doesn't keep counting. */
	private void snapStored() {
		int w = ClientAuraHud.width(minecraft, label(), previewAbilities().size()), h = ClientAuraHud.height();
		int[] at = HudLayout.panel(minecraft, width, height, w, h), base = HudLayout.panelBase(minecraft, width, height, w, h);
		AuraClientConfig.HUD_OFFSET_X.set(at[0] - base[0]);
		AuraClientConfig.HUD_OFFSET_Y.set(at[1] - base[1]);
		int[] bar = HudLayout.bar(width, height), barBase = HudLayout.barBase(width, height);
		AuraClientConfig.CHARGE_OFFSET_X.set(bar[0] - barBase[0]);
		AuraClientConfig.CHARGE_OFFSET_Y.set(bar[1] - barBase[1]);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		double step = scrollY > 0 ? 0.05 : -0.05;
		if (inside(barRect(), x, y)) {
			AuraClientConfig.CHARGE_SCALE.set(clamp(AuraClientConfig.CHARGE_SCALE.get() + step));
			return true;
		}
		if (inside(panelRect(), x, y)) {
			AuraClientConfig.HUD_SCALE.set(clamp(AuraClientConfig.HUD_SCALE.get() + step));
			snapStored();
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	private static double clamp(double v) {
		return Math.round(Math.max(0.5, Math.min(2.0, v)) * 100) / 100.0;
	}
}
