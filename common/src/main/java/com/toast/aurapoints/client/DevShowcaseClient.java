package com.toast.aurapoints.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterGuiLayersEvent;
import com.toast.aurapoints.shim.neoforge.client.event.ScreenEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * DEV ONLY (system property aurapoints.showcase=true): goes straight into a flat test world, and draws
 * the player model from the side, the front and behind on the right of the screen, so poses can be
 * judged in screenshots. Does nothing at all without the property.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class DevShowcaseClient {

	private DevShowcaseClient() {
	}

	private static boolean on() {
		return Boolean.getBoolean("aurapoints.showcase");
	}

	private static boolean started = false;

	@SubscribeEvent
	public static void onTitle(ScreenEvent.Init.Post event) {
		if (!on() && !com.toast.aurapoints.arena.ArenaTour.on() && !com.toast.aurapoints.DevIdleGallery.on() && !com.toast.aurapoints.DevIdleGallery.test()
				&& !com.toast.aurapoints.DevUltShowcase.on() && !com.toast.aurapoints.DevAuraShowcase.on() && System.getProperty("aurapoints.clientcmd", "").isEmpty() || started || !(event.getScreen() instanceof TitleScreen)) return;
		started = true;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			String id = "aura_showcase";
			if (mc.getLevelSource().levelExists(id)) {
				mc.createWorldOpenFlows().openWorld(id, () -> mc.setScreen(new TitleScreen()));
				return;
			}
			GameRules rules = new GameRules();
			rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
			rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
			rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
			LevelSettings settings = new LevelSettings(id, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
			mc.createWorldOpenFlows().createFreshLevel(id, settings, new WorldOptions(1L, false, false),
					reg -> reg.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
					new TitleScreen());
		});
	}

	/** Arena tour: a world with the arena's custom dimension asks to confirm on opening - say yes (and log what asked). */
	@SubscribeEvent
	public static void onConfirm(ScreenEvent.Init.Post event) {
		if (!com.toast.aurapoints.arena.ArenaTour.on() || event.getScreen() instanceof TitleScreen) return;
		com.toast.aurapoints.AuraPoints.LOGGER.info("[arenatour] screen {} '{}'", event.getScreen().getClass().getSimpleName(), event.getScreen().getTitle().getString());
		for (var child : event.getScreen().children()) {
			if (child instanceof net.minecraft.client.gui.components.Button button) {
				String text = button.getMessage().getString().toLowerCase(java.util.Locale.ROOT);
				if (text.contains("proceed") || text.equals("yes") || text.contains("continue") || text.contains("know what")) {
					Minecraft.getInstance().execute(button::onPress);
					return;
				}
			}
		}
	}

	private static int ticksIn = 0;
	/** The idle gallery: the idle seen last tick and how far it was. */
	private static int galleryIdle = -1, galleryTick = -1;

	/** Saves a screenshot of the game every 15 ticks through the showcase (run/screenshots). */
	@SubscribeEvent
	public static void onTick(com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (!on() && !com.toast.aurapoints.arena.ArenaTour.on() && !com.toast.aurapoints.DevIdleGallery.on() && !com.toast.aurapoints.DevIdleGallery.test()
				&& !com.toast.aurapoints.DevUltShowcase.on() && !com.toast.aurapoints.DevAuraShowcase.on() && System.getProperty("aurapoints.clientcmd", "").isEmpty() || mc.player == null) {
			ticksIn = 0;
			return;
		}
		ticksIn++;
		if (com.toast.aurapoints.DevIdleGallery.test()) {
			idleTest(mc);
			return;
		}
		if (com.toast.aurapoints.DevAuraShowcase.on()) {
			// the owner-aura showcase: the shots its script queued, each from its camera
			var shot = com.toast.aurapoints.DevAuraShowcase.SHOTS.peek();
			if (shot != null && mc.level != null) {
				long now = mc.level.getGameTime();
				var camera = switch (shot.camera()) {
					case 1 -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
					case 2 -> net.minecraft.client.CameraType.FIRST_PERSON;
					default -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
				};
				if (now >= shot.at() - 12) mc.options.setCameraType(camera);
				if (now >= shot.at()) {
					com.toast.aurapoints.DevAuraShowcase.SHOTS.poll();
					com.toast.aurapoints.AuraPoints.LOGGER.info("[aurashowcase] shot {}", shot.name());
					net.minecraft.client.Screenshot.grab(mc.gameDirectory, shot.name() + ".png", mc.getMainRenderTarget(), msg -> {
					});
				}
			}
			return;
		}
		if (com.toast.aurapoints.DevUltShowcase.on()) {
			// the Ultimate gallery: from the front, a little way off, five shots of each
			if (ticksIn == 5) {
				mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
				mc.options.fov().set(70);
			}
			BendingAnimation anim = BendingAnimation.of(mc.player);
			int[] g = anim == null ? null : anim.gesture();
			if (g != null && g[0] == com.toast.aurapoints.network.BendingAnimPacket.GESTURE_ULTIMATE) {
				int[] at = java.util.Arrays.stream(System.getProperty("aurapoints.ultframes", "6,18,34,60,100").split(","))
						.mapToInt(Integer::parseInt).toArray();
				for (int k = 0; k < at.length; k++) {
					if (g[2] == at[k]) {
						com.toast.aurapoints.AuraPoints.LOGGER.info("[ultshowcase] shot {} of {} at {}", k, g[1], g[2]);
						net.minecraft.client.Screenshot.grab(mc.gameDirectory, String.format("ult_%d_%d.png", g[1], k), mc.getMainRenderTarget(), msg -> {
						});
					}
				}
			}
			return;
		}
		// -Daurapoints.clientcmd=<command>[;<command>...] (+ for spaces): run as the dev player once the world is up, 5 ticks apart
		String[] cmds = System.getProperty("aurapoints.clientcmd", "").replace('+', ' ').split(";");
		for (int i = 0; i < cmds.length; i++) {
			if (!cmds[i].isEmpty() && ticksIn == 60 + 5 * i) {
				com.toast.aurapoints.AuraPoints.LOGGER.info("[clientcmd] /{}", cmds[i]);
				mc.player.connection.sendCommand(cmds[i]);
			}
		}
		// -Daurapoints.clientshots=<ticks,...>: shots from the front at those ticks (after a clientcmd, say)
		for (String at : System.getProperty("aurapoints.clientshots", "").split(",")) {
			if (at.isEmpty()) continue;
			if (ticksIn == 61) mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
			if (ticksIn == Integer.parseInt(at)) {
				net.minecraft.client.Screenshot.grab(mc.gameDirectory, "clientshot_" + at + ".png", mc.getMainRenderTarget(), msg -> {
				});
			}
		}
		// -Daurapoints.presskey=<AbilityKeyPacket index>: that key pressed like a player would, then a shot of the HUD
		int key = Integer.getInteger("aurapoints.presskey", -1);
		if (key >= 0 && key < com.toast.aurapoints.network.ClientKeyHandler.KEYS.length) {
			if (ticksIn == 200) {
				com.toast.aurapoints.AuraPoints.LOGGER.info("[clientcmd] pressing {}", com.toast.aurapoints.network.ClientKeyHandler.KEYS[key].getName());
				com.toast.aurapoints.network.ClientKeyHandler.KEYS[key].setDown(true);
			}
			if (ticksIn == 203) com.toast.aurapoints.network.ClientKeyHandler.KEYS[key].setDown(false);
			if (ticksIn == 190 || ticksIn == 230) {
				mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				net.minecraft.client.Screenshot.grab(mc.gameDirectory, "keytest_" + ticksIn + ".png", mc.getMainRenderTarget(), msg -> {
				});
			}
		}
		if (!cmds[0].isEmpty()) return;
		if (com.toast.aurapoints.DevIdleGallery.on()) {
			// the idle gallery: watched from the front, four shots of each idle
			if (ticksIn == 5) {
				mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
				mc.options.fov().set(55);
			}
			BendingAnimation anim = BendingAnimation.of(mc.player);
			int idle = anim == null ? -1 : anim.idleVariant(), t = anim == null ? -1 : anim.idleTick();
			var which = com.toast.aurapoints.network.BendingAnimPacket.Idle.byId(idle);
			// the ones on the ground: the player looks up, so the front camera sits above and in front, looking down
			// at the ground between itself and the player (the idle's head keys keep the model's head as animated)
			boolean ground = which == com.toast.aurapoints.network.BendingAnimPacket.Idle.SHADOW_PUPPET || which == com.toast.aurapoints.network.BendingAnimPacket.Idle.VERDANT_SPROUT;
			// the shadow puppet's wolf lies further out: from steeply above and behind, past the head
			boolean puppet = which == com.toast.aurapoints.network.BendingAnimPacket.Idle.SHADOW_PUPPET;
			if (which != null) {
				mc.options.setCameraType(puppet ? net.minecraft.client.CameraType.THIRD_PERSON_BACK : net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
				mc.player.setXRot(puppet ? 70f : ground ? -40f : 0f);
			}
			// an idle that stops before its end was cut short: say so
			if (galleryIdle >= 0 && idle != galleryIdle && galleryTick < com.toast.aurapoints.network.BendingAnimPacket.Idle.byId(galleryIdle).length - 2) {
				com.toast.aurapoints.AuraPoints.LOGGER.warn("[idlegallery] {} stopped early at tick {}", com.toast.aurapoints.network.BendingAnimPacket.Idle.byId(galleryIdle), galleryTick);
			}
			galleryIdle = idle;
			galleryTick = t;
			if (which != null) {
				for (int k = 1; k <= 4; k++) {
					if (t == Math.round(which.length * (0.05f + 0.2f * k))) {
						com.toast.aurapoints.AuraPoints.LOGGER.info("[idlegallery] shot {} at t={}", k, t);
						net.minecraft.client.Screenshot.grab(mc.gameDirectory, String.format("idle_%02d_%s_%d.png", idle, which.name().toLowerCase(java.util.Locale.ROOT), k),
								mc.getMainRenderTarget(), msg -> {
								});
					}
				}
			}
			return;
		}
		if (com.toast.aurapoints.arena.ArenaTour.on()) {
			// the arena tour's screenshots, at the ticks it lists
			int[] shots = com.toast.aurapoints.arena.ArenaTour.SHOTS;
			for (int i = 0; i < shots.length; i++) {
				if (ticksIn == shots[i]) {
					net.minecraft.client.Screenshot.grab(mc.gameDirectory, String.format("arena_%d.png", i), mc.getMainRenderTarget(), msg -> {
					});
				}
			}
			return;
		}
		if ((ticksIn >= 48 && ticksIn <= 80 && ticksIn % 3 == 0) || (ticksIn >= 470 && ticksIn <= 1180 && ticksIn % 40 == 0)) {
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, String.format("show_%04d.png", ticksIn), mc.getMainRenderTarget(), msg -> {
			});
		}
	}

	/**
	 * The natural-idle test (aurapoints.idletest): stand still and log each idle as it starts and stops;
	 * a step forward 30 ticks into the second idle (must cut it short), a sprint at 1500 with a shot of the trail.
	 */
	private static void idleTest(Minecraft mc) {
		if (ticksIn == 5) {
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
			mc.options.fov().set(55);
		}
		BendingAnimation anim = BendingAnimation.of(mc.player);
		int idle = anim == null ? -1 : anim.idleVariant(), t = anim == null ? -1 : anim.idleTick();
		if (idle != galleryIdle) {
			if (galleryIdle >= 0) {
				com.toast.aurapoints.AuraPoints.LOGGER.info("[idletest] {} stopped at its tick {} of {} (client tick {})",
						com.toast.aurapoints.network.BendingAnimPacket.Idle.byId(galleryIdle), galleryTick, com.toast.aurapoints.network.BendingAnimPacket.Idle.byId(galleryIdle).length, ticksIn);
			}
			if (idle >= 0) com.toast.aurapoints.AuraPoints.LOGGER.info("[idletest] {} started (client tick {})", com.toast.aurapoints.network.BendingAnimPacket.Idle.byId(idle), ticksIn);
		}
		if (idle >= 0 && t == 40 && !idleShot) {
			idleShot = true;
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, "idletest_idle.png", mc.getMainRenderTarget(), msg -> {
			});
		}
		// a step forward 30 ticks into the second idle: it must stop there, and the next one wait the full 8 s again
		if (idle >= 0 && galleryIdle != idle) idlesSeen++;
		if (idlesSeen == 2 && idle >= 0 && t == 30 && stepAt < 0) {
			stepAt = ticksIn;
			com.toast.aurapoints.AuraPoints.LOGGER.info("[idletest] stepping forward 30 ticks into {} (client tick {})",
					com.toast.aurapoints.network.BendingAnimPacket.Idle.byId(idle), ticksIn);
			mc.options.keyUp.setDown(true);
		}
		if (stepAt > 0 && ticksIn == stepAt + 6) mc.options.keyUp.setDown(false);
		galleryIdle = idle;
		galleryTick = t;
		// then a sprint, watched from behind
		if (ticksIn == 1500) {
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			mc.player.setXRot(20f);
			mc.options.keyUp.setDown(true);
			mc.player.setSprinting(true);
		}
		if (ticksIn > 1500 && ticksIn < 1550) mc.player.setSprinting(true);
		if (ticksIn == 1535) {
			com.toast.aurapoints.AuraPoints.LOGGER.info("[idletest] sprinting: {}", mc.player.isSprinting());
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, "idletest_trail.png", mc.getMainRenderTarget(), msg -> {
			});
		}
		if (ticksIn == 1550) {
			mc.options.keyUp.setDown(false);
			com.toast.aurapoints.AuraPoints.LOGGER.info("[idletest] done");
		}
	}

	private static boolean idleShot = false;
	private static int idlesSeen = 0, stepAt = -1;

	@SubscribeEvent
	public static void onLayers(RegisterGuiLayersEvent event) {
		if (!on() && !com.toast.aurapoints.DevIdleGallery.on()) return;
		event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("aurapoints", "dev_showcase"), DevShowcaseClient::render);
	}

	private static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		int x = g.guiWidth() - 40;
		int size = 48;
		if (com.toast.aurapoints.DevIdleGallery.on()) {
			// the idle gallery: side and three-quarter views next to the front view of the world camera
			int s = g.guiHeight() / 7;
			view(g, mc, g.guiWidth() - s - 4, (int) (s * 2.3f), s, 90f);
			view(g, mc, g.guiWidth() - s - 4, (int) (s * 4.6f), s, 135f);
			return;
		}
		// side (facing left) and front
		view(g, mc, x - 10, 110, size, 90f);
		view(g, mc, x - 10, 230, size, 0f);
	}

	/** The player model turned {@code yaw} degrees (0 = facing the screen). */
	private static void view(GuiGraphics g, Minecraft mc, int x, int y, int size, float yaw) {
		var p = mc.player;
		float bodyRot = p.yBodyRot, bodyRotO = p.yBodyRotO, yRot = p.getYRot(), yRotO = p.yRotO, xRot = p.getXRot(), xRotO = p.xRotO,
				headRot = p.yHeadRot, headRotO = p.yHeadRotO;
		p.yBodyRot = p.yBodyRotO = 180f + yaw;
		p.setYRot(180f + yaw);
		p.yRotO = 180f + yaw;
		p.setXRot(0);
		p.xRotO = 0;
		p.yHeadRot = p.yHeadRotO = 180f + yaw;
		float walk = p.walkAnimation.speed(), walkOld = walkOld(p, Float.NaN);
		p.walkAnimation.setSpeed(0);
		walkOld(p, 0f);
		g.fill(x - size * 5 / 6, y - size * 25 / 12, x + size * 5 / 6, y + size / 6, 0x60000000);
		InventoryScreen.renderEntityInInventory(g, x, y, size, new Vector3f(0, 0, 0), new Quaternionf().rotateZ((float) Math.PI), null, p);
		p.yBodyRot = bodyRot;
		p.yBodyRotO = bodyRotO;
		p.setYRot(yRot);
		p.yRotO = yRotO;
		p.setXRot(xRot);
		p.xRotO = xRotO;
		p.yHeadRot = headRot;
		p.yHeadRotO = headRotO;
		p.walkAnimation.setSpeed(walk);
		walkOld(p, walkOld);
	}

	/** Reads (set NaN) or writes the walk animation's last-tick speed, so the preview stands still. */
	private static float walkOld(net.minecraft.world.entity.LivingEntity p, float set) {
		try {
			java.lang.reflect.Field f = net.minecraft.world.entity.WalkAnimationState.class.getDeclaredField("speedOld");
			f.setAccessible(true);
			if (Float.isNaN(set)) return f.getFloat(p.walkAnimation);
			f.setFloat(p.walkAnimation, set);
		} catch (ReflectiveOperationException ignored) {
		}
		return set;
	}
}
