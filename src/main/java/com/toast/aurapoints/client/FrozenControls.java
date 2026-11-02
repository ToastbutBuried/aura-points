package com.toast.aurapoints.client;

import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/**
 * CLIENT ONLY. Frozen solid by an Ice Spike: no walking, jumping or sneaking, no attacking, using
 * or placing, and the hotbar stays on the slot it froze on. The camera is held by mixin/MouseHandlerMixin.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class FrozenControls {

	private FrozenControls() {
	}

	@SubscribeEvent
	public static void onMovementInput(MovementInputUpdateEvent event) {
		if (!ClientAuraState.isFrozen()) return;
		var input = event.getInput();
		input.forwardImpulse = 0;
		input.leftImpulse = 0;
		input.up = input.down = input.left = input.right = false;
		input.jumping = false;
		input.shiftKeyDown = false;
	}

	@SubscribeEvent
	public static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
		if (!ClientAuraState.isFrozen()) return;
		event.setCanceled(true);
		event.setSwingHand(false);
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Pre event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		if (!ClientAuraState.isFrozen()) {
			ClientAuraState.frozenSlot = -1;
			return;
		}
		if (ClientAuraState.frozenSlot < 0) ClientAuraState.frozenSlot = mc.player.getInventory().selected;
		mc.player.getInventory().selected = ClientAuraState.frozenSlot;
		if (mc.player.isUsingItem()) mc.player.stopUsingItem();
		mc.player.setSprinting(false);
	}
}
