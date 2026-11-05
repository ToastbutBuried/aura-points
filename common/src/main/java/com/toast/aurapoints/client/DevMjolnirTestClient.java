package com.toast.aurapoints.client;

import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.decoration.ArmorStand;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;

/**
 * DEV ONLY (system property aurapoints.mjtest=true, set by gradle runClient -Pmjtest): client-side only. Runs the
 * Mjölnir spin and flight poses on the local player (standing, really walking, aiming level/up/down, turning) from
 * a side camera, saving a shot of each to run/screenshots/mj_*.png.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class DevMjolnirTestClient {

	private DevMjolnirTestClient() {
	}

	private static boolean started;
	private static int t;
	private static ArmorStand cam;
	private static double startY;

	/** {first tick, state, pitch, walking, flying, turning deg/tick, shot at, name} */
	private static final Object[][] PHASES = {
			{0, BendingAnimPacket.MJOLNIR_SPIN, 0f, false, false, 0f, 45, "spin_idle"},
			{60, BendingAnimPacket.MJOLNIR_SPIN, 0f, true, false, 0f, 105, "spin_walk"},
			{120, BendingAnimPacket.MJOLNIR_SPIN, 0f, false, false, 0f, 155, "spin_stopped"},
			{160, BendingAnimPacket.NONE, 0f, true, false, 0f, 190, "released_walk"},
			{200, BendingAnimPacket.MJOLNIR_FLY, 0f, false, true, 0f, 250, "fly_level"},
			{260, BendingAnimPacket.MJOLNIR_FLY, -60f, false, true, 0f, 310, "fly_up"},
			{320, BendingAnimPacket.MJOLNIR_FLY, 60f, false, true, 0f, 370, "fly_down"},
			{380, BendingAnimPacket.MJOLNIR_FLY, 0f, false, true, 8f, 430, "fly_turn"},
	};

	@SubscribeEvent
	public static void onTick(ClientTickEvent.Post event) {
		if (!Boolean.getBoolean("aurapoints.mjtest")) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		if (!started) {
			started = true;
			t = -80; // let the world settle
			startY = mc.player.getY();
		}
		t++;
		if (t == -70) {
			mc.player.connection.sendCommand("gamemode creative");
			mc.player.connection.sendCommand("effect clear @s");
		}
		if (t == -60) {
			mc.player.getAbilities().mayfly = true;
			mc.player.getAbilities().flying = false; // land: the walking phases are real walking
		}
		if (t < 0) return;
		mc.options.hideGui = true;
		if (cam == null) {
			cam = new ArmorStand(mc.level, mc.player.getX() - 3, mc.player.getY() + 0.8, mc.player.getZ());
			cam.setInvisible(true);
			cam.setNoGravity(true);
			mc.level.addEntity(cam);
		}
		Object[] ph = null;
		for (Object[] p : PHASES) if (t >= (int) p[0]) ph = p;
		if (ph == null || t > 470) {
			if (t == 475) {
				ClientAuraState.setBendState(mc.player.getId(), BendingAnimPacket.NONE);
				com.toast.aurapoints.AuraPoints.LOGGER.info("[mjtest] done");
			}
			return;
		}
		if (t == (int) ph[0]) com.toast.aurapoints.AuraPoints.LOGGER.info("[mjtest] phase {} at {}", ph[7], t);
		ClientAuraState.setBendState(mc.player.getId(), (byte) ph[1]);
		boolean walk = (boolean) ph[3], fly = (boolean) ph[4];
		// walking: pushed along at walking pace (the player takes no key input while the side camera is the view)
		if (walk) mc.player.setDeltaMovement(0, mc.player.getDeltaMovement().y, 0.215);
		mc.player.getAbilities().flying = fly;
		if (fly) {
			mc.player.getAbilities().mayfly = true;
			if (mc.player.getY() < startY + 2.9) mc.player.setDeltaMovement(0, 0.25, 0);
			else mc.player.setDeltaMovement(0, 0, 0);
		}
		float turn = (float) ph[5];
		float yaw = turn != 0 ? mc.player.getYRot() + turn : 0f;
		mc.player.setYRot(yaw);
		mc.player.setYHeadRot(yaw);
		mc.player.yBodyRot = yaw;
		mc.player.setXRot((float) ph[2] == 0f && !fly ? 0f : (float) ph[2]);
		// the side camera follows the player, 3 blocks to its west, looking east
		// moveTo also resets the previous position: the stand never ticks, so nothing else would
		cam.moveTo(mc.player.getX() - 3, mc.player.getY() + 0.8, mc.player.getZ(), -90f, 0f);
		cam.setYHeadRot(-90f);
		cam.yHeadRotO = -90f;
		cam.yRotO = -90f;
		cam.xRotO = 0f;
		mc.options.setCameraType(CameraType.FIRST_PERSON);
		// the player only takes walking input while it is the camera: while walking, hand over to the side camera for the shot only
		mc.setCameraEntity(cam);
		if (t % 20 == 0) {
			com.toast.aurapoints.AuraPoints.LOGGER.info("[mjtest] mode={} invisible={} spectator={} controlled={} rooted={} frozen={}",
					mc.gameMode == null ? null : mc.gameMode.getPlayerMode(), mc.player.isInvisible(), mc.player.isSpectator(),
					mc.getCameraEntity() == mc.player, ClientAuraState.rooted, ClientAuraState.isFrozen());
			com.toast.aurapoints.AuraPoints.LOGGER.info("[mjtest] t={} player=({},{},{}) onGround={} cam=({},{},{}) camEntityIsCam={} screen={} paused={} state={} keyUp={}",
					t, mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.onGround(), cam.getX(), cam.getY(), cam.getZ(),
					mc.getCameraEntity() == cam, mc.screen, mc.isPaused(), ClientAuraState.bendState(mc.player.getId()) == null ? -1 : ClientAuraState.bendState(mc.player.getId())[0],
					mc.options.keyUp.isDown());
		}
		for (int k = 0; k < 3; k++) {
			if (t == (int) ph[6] - 4 * (2 - k)) {
				com.toast.aurapoints.AuraPoints.LOGGER.info("[mjtest] shot {} {}", ph[7], k);
				Screenshot.grab(mc.gameDirectory, "mj_" + ph[7] + "_" + k + ".png", mc.getMainRenderTarget(), msg -> {
				});
			}
		}
	}
}
