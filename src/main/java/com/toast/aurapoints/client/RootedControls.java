package com.toast.aurapoints.client;

import com.toast.aurapoints.network.ClientAuraState;
import com.toast.aurapoints.network.MashKeyPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * CLIENT ONLY. Rooted (entangled, or steering a Verdant Viper): movement keys do nothing, the camera
 * stays free. While mashing free of an Entanglement, every fresh press of W, A, S or D is sent to the
 * server, which counts them.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class RootedControls {

	private RootedControls() {
	}

	private static final boolean[] WAS = new boolean[4];

	@SubscribeEvent
	public static void onMovementInput(MovementInputUpdateEvent event) {
		if (!ClientAuraState.rooted) return;
		var input = event.getInput();
		input.forwardImpulse = 0;
		input.leftImpulse = 0;
		input.up = input.down = input.left = input.right = false;
		input.jumping = false;
		input.shiftKeyDown = false;
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !ClientAuraState.rooted || !ClientAuraState.mash || mc.screen != null) {
			for (int i = 0; i < 4; i++) WAS[i] = false;
			return;
		}
		KeyMapping[] keys = {mc.options.keyUp, mc.options.keyLeft, mc.options.keyDown, mc.options.keyRight};
		for (int i = 0; i < 4; i++) {
			boolean down = keys[i].isDown();
			if (down && !WAS[i]) PacketDistributor.sendToServer(new MashKeyPacket(i));
			WAS[i] = down;
		}
	}
}
