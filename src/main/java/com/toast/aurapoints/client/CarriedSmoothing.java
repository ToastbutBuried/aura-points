package com.toast.aurapoints.client;

import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/**
 * CLIENT ONLY. Being dragged in by a Vine Hook: the server puts you on a new spot every tick, and each
 * of those resets where the frame interpolation starts, so the camera would jump 20 times a second.
 * After every tick this sets the "previous position" back to where you were the tick before, so the
 * frames in between glide along the path. Your movement keys rest until you land.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class CarriedSmoothing {

	private CarriedSmoothing() {
	}

	private static Vec3 prev = null;

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !ClientAuraState.isCarried()) {
			prev = null;
			return;
		}
		Vec3 now = mc.player.position();
		if (prev != null) {
			mc.player.xo = mc.player.xOld = prev.x;
			mc.player.yo = mc.player.yOld = prev.y;
			mc.player.zo = mc.player.zOld = prev.z;
		}
		prev = now;
	}

	@SubscribeEvent
	public static void onMovementInput(MovementInputUpdateEvent event) {
		if (!ClientAuraState.isCarried()) return;
		var input = event.getInput();
		input.forwardImpulse = 0;
		input.leftImpulse = 0;
		input.up = input.down = input.left = input.right = false;
		input.jumping = false;
	}
}
