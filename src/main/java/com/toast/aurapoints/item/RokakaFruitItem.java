package com.toast.aurapoints.item;

import com.toast.aurapoints.AuraReset;
import com.toast.aurapoints.ability.AuraFx;
import net.minecraft.core.particles.ParticleTypes;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crafted from an end crystal ringed by eight diamonds. Clears the aura
 * (next roll free), resets both skill trees (PvP tree locked again) and
 * refunds every skill point. Needs a second use within a short window to confirm, so nobody
 * wipes their build by accident.
 */
public class RokakaFruitItem extends Item {

	/** Per player: {game time of the arming use, game time of the latest use call}. In memory only. */
	private static final Map<UUID, long[]> ARMED = new HashMap<>();

	public RokakaFruitItem(Properties properties) {
		super(properties.stacksTo(16).rarity(Rarity.EPIC));
	}

	/** Logout: an armed confirmation doesn't wait for the next login. */
	public static void forget(ServerPlayer player) {
		ARMED.remove(player.getUUID());
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide) {
			return InteractionResultHolder.pass(stack);
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResultHolder.pass(stack);
		}
		MinecraftServer server = serverPlayer.getServer();
		if (server == null) {
			return InteractionResultHolder.pass(stack);
		}

		long now = level.getGameTime();
		long[] armed = ARMED.get(serverPlayer.getUUID());

		if (armed == null || now - armed[0] > AuraConfig.FRUIT_CONFIRM_TICKS) {
			ARMED.put(serverPlayer.getUUID(), new long[]{now, now});
			serverPlayer.sendSystemMessage(Component.literal(
					"This removes your aura and resets both skill trees (every skill point is refunded, your next roll is free). "
							+ "Use the fruit again within " + (AuraConfig.FRUIT_CONFIRM_TICKS / 20) + " seconds to confirm.")
					.withStyle(ChatFormatting.RED));
			return InteractionResultHolder.fail(stack);
		}

		// Holding right-click repeats the use every few ticks. Uses that close together are the
		// same press, so confirming needs a fresh click after the arm delay, never a held button.
		boolean held = now - armed[1] <= AuraConfig.FRUIT_HOLD_GAP_TICKS;
		armed[1] = now;
		if (held) {
			return InteractionResultHolder.fail(stack);
		}
		if (now - armed[0] < AuraConfig.FRUIT_ARM_TICKS) {
			serverPlayer.displayClientMessage(Component.literal("Wait a moment, then use the fruit again to confirm.")
					.withStyle(ChatFormatting.RED), true);
			return InteractionResultHolder.fail(stack);
		}

		ARMED.remove(serverPlayer.getUUID());
		AuraState state = AuraState.get(server);
		AuraPlayerData data = state.getOrCreate(serverPlayer.getUUID());
		AuraReset.rokakaReset(serverPlayer, data);
		com.toast.aurapoints.network.NetworkHandler.syncAura(serverPlayer, data);
		com.toast.aurapoints.network.NetworkHandler.syncOwned(serverPlayer, data);
		state.markDirty();

		stack.consume(1, serverPlayer); // not in creative
		level.playSound(null, serverPlayer.blockPosition(), com.toast.aurapoints.ability.AuraSounds.PLAYER_BURP.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
		// The old aura gets sucked out: particles collapse inward, then a dull thud.
		var serverLevel = serverPlayer.serverLevel();
		AuraFx.burst(serverLevel, serverPlayer.getX(), serverPlayer.getY() + 1.0, serverPlayer.getZ(), ParticleTypes.REVERSE_PORTAL, 80, 0.8, 0.05);
		AuraFx.ring(serverLevel, serverPlayer.getX(), serverPlayer.getY() + 0.1, serverPlayer.getZ(), 1.5, ParticleTypes.LARGE_SMOKE, 24);
		serverLevel.playSound(null, serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
				com.toast.aurapoints.ability.AuraSounds.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
		AuraFx.title(serverPlayer, Component.literal("Aura Cleared").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD),
				Component.literal("your next /aura roll is free").withStyle(ChatFormatting.GRAY));
		serverPlayer.sendSystemMessage(Component.literal(
				"Your aura and skills dissolve. Every skill point has been returned, and your next /aura roll is free.")
				.withStyle(ChatFormatting.GOLD));
		return InteractionResultHolder.success(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag type) {
		tooltip.add(Component.literal("Removes your aura, resets both skill trees and refunds all skill points.").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.literal("Use twice to confirm.").withStyle(ChatFormatting.DARK_GRAY));
		super.appendHoverText(stack, context, tooltip, type);
	}
}
