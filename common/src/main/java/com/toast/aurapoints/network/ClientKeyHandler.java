package com.toast.aurapoints.network;

import com.mojang.blaze3d.platform.InputConstants;
import com.toast.aurapoints.ability.AuraAbilities;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;
import com.toast.aurapoints.shim.neoforge.client.event.RegisterKeyMappingsEvent;
import com.toast.aurapoints.shim.neoforge.network.PacketDistributor;

/**
 * CLIENT ONLY. One rebindable key per keyed active ability (Shockwave,
 * Vanish, Dash, Super Wind Charge). They start unbound so they never collide
 * with anything; players bind them in Options > Controls > Aura Points. Each
 * press and release is sent to the server as an {@link AbilityKeyPacket}.
 *
 * Double Jump has no key of its own: a fresh jump press while in mid-air
 * sends a {@link DoubleJumpPacket}. The client doesn't know the player's aura,
 * so it sends it regardless and the server ignores it for everyone but Wind.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class ClientKeyHandler {

	private ClientKeyHandler() {
	}

	/** Ability 1, 2, 3 (whatever the aura puts there), then Item Use and Item Secondary, then the Ultimate, then Item Tertiary. Index = AbilityKeyPacket key. */
	public static final KeyMapping[] KEYS = new KeyMapping[com.toast.aurapoints.ability.AuraType.KEY_SLOTS + 4];
	private static final boolean[] WAS_DOWN = new boolean[KEYS.length];
	private static final String[] NAMES = {"ability_1", "ability_2", "ability_3", "itemuse", "itemsecondary", "ultimate", "itemtertiary"};
	private static boolean jumpWasDown = false;

	static {
		for (int i = 0; i < KEYS.length; i++) {
			KEYS[i] = com.toast.aurapoints.shim.ClientPlatform.get().inGameKey(
					"key.aurapoints." + NAMES[i],
					InputConstants.Type.KEYSYM,
					InputConstants.UNKNOWN.getValue(), // no defaults: players pick their own keys (the chat hint asks)
					"key.categories.aurapoints");
		}
	}

	@SubscribeEvent
	public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
		for (KeyMapping key : KEYS) {
			event.register(key);
		}
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft client = Minecraft.getInstance();
		boolean inGame = client.player != null && client.screen == null;

		for (int i = 0; i < KEYS.length; i++) {
			boolean down = inGame && com.toast.aurapoints.shim.ClientPlatform.get().isKeyDown(KEYS[i]);
			if (down == WAS_DOWN[i]) continue;
			WAS_DOWN[i] = down;
			if (client.player == null) continue;
			PacketDistributor.sendToServer(new AbilityKeyPacket(i, down));
			if (down && i < com.toast.aurapoints.ability.AuraType.KEY_SLOTS) com.toast.aurapoints.client.ClientCooldowns.onKeyPressed(i);
		}
	}

	/**
	 * Double Jump is checked BEFORE the player ticks. After the tick, the normal
	 * jump from that same press has already lifted the player off the ground, so
	 * the first press would look like a mid-air press. Before the tick, a press
	 * only counts if the player was already airborne when it happened.
	 */
	@SubscribeEvent
	public static void onClientTickPre(ClientTickEvent.Pre event) {
		Minecraft client = Minecraft.getInstance();
		boolean inGame = client.player != null && client.screen == null;

		// Only a fresh press counts: holding jump through a normal jump never double jumps.
		boolean jumpDown = inGame && client.options.keyJump.isDown();
		if (jumpDown && !jumpWasDown && canDoubleJump(client.player)) {
			PacketDistributor.sendToServer(new DoubleJumpPacket());
		}
		jumpWasDown = jumpDown;
	}

	/** Mid-air (a double jump) or gliding with an elytra (the flight lift); the server tells them apart. */
	private static boolean canDoubleJump(LocalPlayer player) {
		return player != null && !player.onGround() && !player.isPassenger() && !player.getAbilities().flying
				&& !player.isInWater() && !player.onClimbable();
	}
}
