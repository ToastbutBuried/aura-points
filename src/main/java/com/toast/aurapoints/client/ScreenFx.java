package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraState;
import com.toast.aurapoints.network.ClientKeyHandler;
import com.toast.aurapoints.network.FxPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * CLIENT ONLY. The moments the server points out (FxPacket), drawn on this player's screen:
 *  - hit confirm: little ticks on the crosshair when one of your abilities lands, a bigger cross on a kill;
 *  - stunt popups: the stunt's name punched in above the crosshair;
 *  - the /aura roll reel: aura names spinning, slowing and landing on yours as the reveal comes;
 *  - the points toast: "+20 Aura" sliding in at the corner (gains in a row add up);
 *  - stuns and Lifesteal go to StatusOverlays.
 * Also the keybind hint when an ability key of your aura is unbound or shares its key.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class ScreenFx {

	private ScreenFx() {
	}

	@SubscribeEvent
	public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAbove(VanillaGuiLayers.CROSSHAIR, ResourceLocation.fromNamespaceAndPath("aurapoints", "hit_confirm"), ScreenFx::renderHit);
		event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("aurapoints", "popups"), ScreenFx::renderPopups);
	}

	// ---------------------------------------------------------------
	// Incoming
	// ---------------------------------------------------------------

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			// drain what came in while there was no player, and start fresh next time
			while (ClientAuraState.pollFx() != null) ;
			stunts.clear();
			reel = null;
			warnedFor = null;
			itemKeysChecked = false;
			return;
		}
		FxPacket p;
		while ((p = ClientAuraState.pollFx()) != null) handle(mc, p);
		checkKeybinds(mc);
	}

	private static void handle(Minecraft mc, FxPacket p) {
		long now = System.currentTimeMillis();
		switch (p.kind()) {
			case FxPacket.HIT -> {
				if (!AuraClientConfig.HIT_CONFIRM.get()) return;
				boolean kill = (p.value() & 1) != 0;
				hitAt = now;
				if (kill) killAt = now;
				if (AuraClientConfig.HIT_SOUND.get()) {
					if (kill) ClientSounds.ui(AuraSounds.UI_KILL.value(), 1.0f, 1.0f);
					else if (now - lastHitSound > 70) ClientSounds.ui(AuraSounds.UI_HIT.value(), 1.0f, 1.0f);
					lastHitSound = now;
				}
			}
			case FxPacket.STUNT -> {
				if (!AuraClientConfig.STUNT_POPUPS.get()) return;
				stunts.add(new Stunt(title(p.text()), p.value() == 1, now));
				while (stunts.size() > 3) stunts.remove(0);
				ClientSounds.ui(AuraSounds.UI_STUNT.value(), 1.0f, 1.0f);
			}
			case FxPacket.POINTS -> {
				if (!AuraClientConfig.POINTS_TOAST.get()) return;
				ToastComponent toasts = mc.getToasts();
				PointsToast open = toasts.getToast(PointsToast.class, PointsToast.TOKEN);
				if (open != null) open.add(p.value(), p.text());
				else toasts.addToast(new PointsToast(p.value(), p.text()));
				ClientSounds.ui(AuraSounds.UI_POINTS.value(), 1.0f, 1.0f);
			}
			case FxPacket.ROLL -> {
				if (!AuraClientConfig.ROLL_REEL.get()) return;
				String[] ids = p.text().split(",");
				if (ids.length < 2) return;
				AuraType rolled = AuraType.byId(ids[0]);
				List<AuraType> all = new ArrayList<>();
				for (int i = 1; i < ids.length; i++) {
					AuraType t = AuraType.byId(ids[i]);
					if (t != null) all.add(t);
				}
				if (rolled == null || all.isEmpty()) return;
				if (!all.contains(rolled)) all.add(rolled);
				reel = new Reel(rolled, all, now, Math.max(400, p.value() * 50L));
			}
			case FxPacket.STUNNED -> StatusOverlays.stunned(p.value());
			case FxPacket.LIFESTEAL -> StatusOverlays.lifesteal(p.value() / 10f);
			case FxPacket.BLOOD_MOON -> BloodMoonSky.start(p.value());
			default -> {
			}
		}
	}

	/** "flame_lance" -> "Flame Lance". */
	private static String title(String id) {
		StringBuilder out = new StringBuilder();
		for (String w : id.split("_")) {
			if (w.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}

	// ---------------------------------------------------------------
	// Hit confirm
	// ---------------------------------------------------------------

	private static long hitAt = 0, killAt = 0, lastHitSound = 0;

	private static void renderHit(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || !mc.options.getCameraType().isFirstPerson()) return;
		long now = System.currentTimeMillis();
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		long sinceKill = now - killAt, sinceHit = now - hitAt;
		if (sinceKill < 450) {
			float t = sinceKill / 450f;
			float r0 = 4 + 3 * easeOut(Math.min(1f, t * 3)), len = 5;
			ticks(g, cx, cy, r0, len, HudColors.argb(1 - t, t < 0.15f ? 0xFFFFFF : HudColors.denied()), 2);
		} else if (sinceHit < 240) {
			float t = sinceHit / 240f;
			int rgb = ClientAuraState.aura == null ? 0xFFFFFF : HudColors.mix(HudColors.of(ClientAuraState.aura), 0xFFFFFF, 0.4f);
			ticks(g, cx, cy, 3 + 2 * easeOut(Math.min(1f, t * 2.5f)), 3, HudColors.argb(1 - t, rgb), 1);
		}
	}

	/** Four diagonal ticks around the crosshair, from radius {@code r0} out by {@code len}. */
	private static void ticks(GuiGraphics g, int cx, int cy, float r0, float len, int argb, int thick) {
		for (int q = 0; q < 4; q++) {
			int sx = (q & 1) == 0 ? 1 : -1, sy = q < 2 ? 1 : -1;
			for (float r = r0; r < r0 + len; r += 0.7f) {
				int px = Math.round(cx + sx * r * 0.7071f), py = Math.round(cy + sy * r * 0.7071f);
				g.fill(px, py, px + thick, py + thick, argb);
			}
		}
	}

	private static float easeOut(float t) {
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	// ---------------------------------------------------------------
	// Stunt popups and the roll reel
	// ---------------------------------------------------------------

	private record Stunt(String name, boolean boss, long at) {
	}

	private static final List<Stunt> stunts = new ArrayList<>();
	private static final long STUNT_MILLIS = 2000;

	private static void renderPopups(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui) return;
		long now = System.currentTimeMillis();
		Font font = mc.font;
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;

		int row = 0;
		for (Iterator<Stunt> it = stunts.iterator(); it.hasNext(); ) {
			Stunt s = it.next();
			long age = now - s.at;
			if (age > STUNT_MILLIS) {
				it.remove();
				continue;
			}
			// punched in big, settles with a little bounce, then floats up and fades
			float scale;
			if (AuraClientConfig.calm()) scale = 1.2f;
			else if (age < 120) scale = Mth.lerp(age / 120f, 2.4f, 1.05f);
			else if (age < 220) scale = Mth.lerp((age - 120) / 100f, 1.05f, 1.25f);
			else scale = 1.2f;
			float fade = age > STUNT_MILLIS - 400 ? (STUNT_MILLIS - age) / 400f : 1f;
			float rise = age > STUNT_MILLIS - 400 ? (1 - fade) * 10 : 0;
			int y = cy - 52 - row * 22;
			String main = "✦ " + s.name.toUpperCase(Locale.ROOT) + " ✦";
			int gold = s.boss ? 0xFF7A1A : 0xFFD24A;
			g.pose().pushPose();
			g.pose().translate(cx, y - rise, 0);
			g.pose().scale(scale, scale, 1);
			int tx = -font.width(main) / 2;
			// a dark outline, then the text with a shine passing through
			int outline = HudColors.argb(fade * 0.9f, 0x2A1600);
			for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) g.drawString(font, main, tx + o[0], o[1], outline, false);
			float shine = AuraClientConfig.calm() ? 0 : Math.max(0f, 1f - Math.abs((age % 900) / 900f * 2.4f - 1.2f));
			g.drawString(font, main, tx, 0, HudColors.argb(fade, HudColors.mix(gold, 0xFFFFFF, shine * 0.6f)), false);
			g.pose().popPose();
			String sub = s.boss ? "BOSS STUNT" : "STUNT";
			g.pose().pushPose();
			g.pose().translate(cx, y - rise + 11, 0);
			g.pose().scale(0.6f, 0.6f, 1);
			g.drawString(font, sub, -font.width(sub) / 2, 0, HudColors.argb(fade * 0.85f, 0xFFF2C8), true);
			g.pose().popPose();
			row++;
		}

		if (reel != null && !reel.draw(g, font, cx, cy + 46, now)) reel = null;
	}

	private static Reel reel = null;

	/** The roll reel: a window of aura names spinning down, slowing, and landing on the rolled one. */
	private static final class Reel {
		private static final long HOLD = 1500, FADE = 400;
		private final AuraType rolled;
		private final List<AuraType> all;
		private final long start, spin;
		private final int last;
		private int lastShown = -1;
		private boolean landed;
		private final HudParticles fx = new HudParticles();

		Reel(AuraType rolled, List<AuraType> all, long start, long spin) {
			this.rolled = rolled;
			this.all = all;
			this.start = start;
			this.spin = spin;
			// spin through the list a few times and stop exactly on the rolled aura
			this.last = 3 * all.size() + all.indexOf(rolled);
		}

		/** False once it's over. */
		boolean draw(GuiGraphics g, Font font, int cx, int cy, long now) {
			long age = now - start;
			if (age > spin + HOLD + FADE) return false;
			float t = Mth.clamp(age / (float) spin, 0f, 1f);
			// fast at first, easing into the stop (quartic out)
			float pos = last * (1 - (1 - t) * (1 - t) * (1 - t) * (1 - t));
			int shown = (int) Math.floor(pos + 0.5f);
			if (shown != lastShown && t < 1f) {
				lastShown = shown;
				ClientSounds.ui(AuraSounds.UI_ROLL_TICK.value(), 1.0f, 1.0f + 0.4f * t);
			}
			if (t >= 1f && !landed) {
				landed = true;
				ClientSounds.ui(AuraSounds.UI_ROLL_LAND.value(), 1.0f, 1.0f);
				if (!AuraClientConfig.calm()) {
					fx.ring(cx, cy, 34, HudColors.palette(rolled));
					fx.ring(cx, cy, 22, 0xFFFFFF);
					fx.sparks(cx, cy, 14, HudColors.palette(rolled));
				}
			}
			float alpha = age > spin + HOLD ? 1f - (age - spin - HOLD) / (float) FADE : Math.min(1f, age / 150f);
			int w = 132, h = 40;
			int current = HudColors.palette(all.get(Math.floorMod(shown, all.size())));
			int frame = landed ? HudColors.palette(rolled) : current;
			float pop = landed && !AuraClientConfig.calm() ? Math.max(0f, 1f - (age - spin) / 300f) : 0f;

			g.pose().pushPose();
			g.pose().translate(cx, cy, 0);
			g.pose().scale(1 + 0.12f * pop, 1 + 0.12f * pop, 1);
			g.fillGradient(-w / 2, -h / 2, w / 2, h / 2, HudColors.argb(0.82f * alpha, 0x141420), HudColors.argb(0.82f * alpha, 0x050508));
			g.renderOutline(-w / 2, -h / 2, w, h, HudColors.argb(alpha, frame));
			g.fill(-w / 2 + 2, -h / 2 + 1, w / 2 - 2, -h / 2 + 2, HudColors.argb(alpha, frame));
			// the names: the one in the middle big and bright, its neighbours small and dim above and below
			float frac = pos - (float) Math.floor(pos);
			int base = (int) Math.floor(pos);
			g.enableScissor(cx - w / 2 + 1, cy - h / 2 + 1, cx + w / 2 - 1, cy + h / 2 - 1);
			for (int k = -2; k <= 2; k++) {
				int idx = base + k;
				AuraType type = all.get(Math.floorMod(idx, all.size()));
				float off = (k - frac) * 14f;
				float near = 1f - Math.min(1f, Math.abs(off) / 22f);
				float sc = 0.7f + 0.5f * near;
				String name = type.displayName.toUpperCase(Locale.ROOT);
				g.pose().pushPose();
				g.pose().translate(0, off, 0);
				g.pose().scale(sc, sc, 1);
				g.drawString(font, name, -font.width(name) / 2, -4, HudColors.argb(alpha * (0.35f + 0.65f * near), HudColors.palette(type)), true);
				g.pose().popPose();
			}
			g.disableScissor();
			// the pointer marks on both sides
			g.fill(-w / 2 + 3, -1, -w / 2 + 8, 1, HudColors.argb(alpha, 0xFFFFFF));
			g.fill(w / 2 - 8, -1, w / 2 - 3, 1, HudColors.argb(alpha, 0xFFFFFF));
			if (pop > 0) g.fill(-w / 2 + 1, -h / 2 + 1, w / 2 - 1, h / 2 - 1, HudColors.argb(0.5f * pop, 0xFFFFFF));
			g.pose().popPose();
			fx.draw(g, now, alpha);
			String caption = landed ? "Your new aura" : "Rolling...";
			g.pose().pushPose();
			g.pose().translate(cx, cy + h / 2f + 4, 0);
			g.pose().scale(0.6f, 0.6f, 1);
			g.drawString(font, caption, -font.width(caption) / 2, 0, HudColors.argb(alpha * 0.9f, 0xDDDDDD), true);
			g.pose().popPose();
			return true;
		}
	}

	// ---------------------------------------------------------------
	// Points toast
	// ---------------------------------------------------------------

	/** "+20 Aura" in the corner; more gains while it shows add up and keep it open. */
	private static final class PointsToast implements Toast {
		static final Object TOKEN = new Object();
		private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/advancement");
		private static final ItemStack ICON = new ItemStack(Items.AMETHYST_SHARD);
		private int amount;
		private String total;
		private long changedAt = -1;
		private boolean changed = true;

		PointsToast(int amount, String total) {
			this.amount = amount;
			this.total = total;
		}

		void add(int more, String total) {
			amount += more;
			this.total = total;
			changed = true;
		}

		@Override
		public Object getToken() {
			return TOKEN;
		}

		@Override
		public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
			if (changed) {
				changedAt = time;
				changed = false;
			}
			g.blitSprite(BACKGROUND, 0, 0, width(), height());
			float bump = AuraClientConfig.calm() ? 0f : Math.max(0f, 1f - (time - changedAt) / 250f);
			g.pose().pushPose();
			g.pose().translate(16, 16, 0);
			g.pose().scale(1 + 0.25f * bump, 1 + 0.25f * bump, 1);
			g.renderFakeItem(ICON, -8, -8);
			g.pose().popPose();
			Font font = toasts.getMinecraft().font;
			g.drawString(font, Component.literal("+" + amount + " Aura").withStyle(ChatFormatting.BOLD), 30, 7,
					0xFF000000 | HudColors.mix(0xFFD24A, 0xFFFFFF, bump), false);
			g.drawString(font, "Total: " + total, 30, 18, 0xFFC8C8D0, false);
			return time - changedAt >= 3500 ? Visibility.HIDE : Visibility.SHOW;
		}
	}

	// ---------------------------------------------------------------
	// Keybind hint
	// ---------------------------------------------------------------

	/** The aura the hint was last checked for (once per aura per session), and since when it's been held. */
	private static AuraType warnedFor = null;
	private static AuraType pending = null;
	private static int pendingTicks = 0;
	/** The Item Use / Secondary / Tertiary keys are checked once per session (they don't depend on the aura). */
	private static boolean itemKeysChecked = false;
	private static final String[] ITEM_KEY_NAMES = {"Item Use", "Item Secondary", "Item Tertiary"};
	private static final int[] ITEM_KEY_INDEXES = {AuraType.KEY_SLOTS, AuraType.KEY_SLOTS + 1, com.toast.aurapoints.network.AbilityKeyPacket.ITEM_TERTIARY};

	private static void checkKeybinds(Minecraft mc) {
		AuraType aura = ClientAuraState.aura;
		if (aura != pending) {
			pending = aura;
			pendingTicks = 0;
		}
		if (!AuraClientConfig.KEYBIND_WARNING.get() || ((aura == null || aura == warnedFor) && itemKeysChecked)) return;
		// wait a moment after joining or rolling, and for no screen to be open
		if (++pendingTicks < 60 || mc.screen != null || mc.player == null) return;

		List<String> problems = new ArrayList<>();
		if (aura != null && aura != warnedFor) {
			warnedFor = aura;
			List<Ability> slots = aura.keySlots();
			for (int i = 0; i < slots.size(); i++) {
				Ability a = slots.get(i);
				if (ClientAuraState.isOwned(a) && !ClientAuraState.isDisabled(a))
					keyProblem(mc, ClientKeyHandler.KEYS[i], "Ability " + (i + 1) + " (" + a.displayName + ")", problems);
			}
			// an awakened aura's Ultimate (the server lists it with the owned abilities)
			Ability ult = com.toast.aurapoints.ability.Ultimates.of(aura);
			if (ult != null && ClientAuraState.isOwned(ult) && !ClientAuraState.isDisabled(ult))
				keyProblem(mc, ClientKeyHandler.KEYS[com.toast.aurapoints.network.AbilityKeyPacket.ULTIMATE], "Ultimate (" + ult.displayName + ")", problems);
		}
		List<String> unboundItems = new ArrayList<>();
		if (!itemKeysChecked) {
			itemKeysChecked = true;
			for (int i = 0; i < ITEM_KEY_INDEXES.length; i++) {
				KeyMapping key = ClientKeyHandler.KEYS[ITEM_KEY_INDEXES[i]];
				if (key.isUnbound()) unboundItems.add(ITEM_KEY_NAMES[i]);
				else keyProblem(mc, key, ITEM_KEY_NAMES[i], problems);
			}
		}
		if (problems.isEmpty() && unboundItems.isEmpty()) return;
		Component tag = Component.literal("[Aura Points] ").withStyle(ChatFormatting.LIGHT_PURPLE);
		if (!problems.isEmpty())
			mc.player.displayClientMessage(tag.copy().append(Component.literal(String.join("; ", problems) + ".").withStyle(ChatFormatting.YELLOW)), false);
		if (!unboundItems.isEmpty())
			mc.player.displayClientMessage(tag.copy().append(Component.literal(String.join(", ", unboundItems)
					+ (unboundItems.size() == 1 ? " has" : " have") + " no key (used by datapack items).").withStyle(ChatFormatting.YELLOW)), false);
		mc.player.displayClientMessage(Component.literal("  Fix it in Options > Controls > Key Binds > Aura Points (turn this hint off: Mods > Aura Points > Config > Feedback).").withStyle(ChatFormatting.GRAY), false);
	}

	/** Adds "<name> has no key" or "<name> shares <key> with ..." to {@code problems} if {@code key} is unbound or collides with another binding. */
	private static void keyProblem(Minecraft mc, KeyMapping key, String name, List<String> problems) {
		if (key.isUnbound()) {
			problems.add(name + " has no key");
			return;
		}
		for (KeyMapping other : mc.options.keyMappings) {
			// the creative-only hotbar keys never get in an ability's way, and keys for other contexts (GUIs) don't either
			if (other == mc.options.keyLoadHotbarActivator || other == mc.options.keySaveHotbarActivator) continue;
			if (!key.getKeyConflictContext().conflicts(other.getKeyConflictContext())) continue;
			if (other != key && other.same(key) && !other.isUnbound()) {
				problems.add(name + " shares " + key.getTranslatedKeyMessage().getString() + " with \""
						+ Component.translatable(other.getName()).getString() + "\"");
				return;
			}
		}
	}
}
