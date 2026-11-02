package com.toast.aurapoints.item;

import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.List;

/**
 * A hook for datapack bosses (e.g. as a summoning item). Using it sets the user's score in the
 * {@link AuraConfig#BOSS_OBJECTIVE} objective to 0. The objective is created
 * once if missing and reused after that - never recreated.
 */
public class BossSigilItem extends Item {

	public BossSigilItem(Properties properties) {
		super(properties.stacksTo(16).rarity(Rarity.EPIC));
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide) {
			return InteractionResultHolder.success(stack);
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResultHolder.pass(stack);
		}
		MinecraftServer server = serverPlayer.getServer();
		if (server == null) {
			return InteractionResultHolder.pass(stack);
		}

		ServerScoreboard scoreboard = server.getScoreboard();
		Objective objective = scoreboard.getObjective(AuraConfig.BOSS_OBJECTIVE);
		if (objective == null) {
			objective = scoreboard.addObjective(AuraConfig.BOSS_OBJECTIVE, ObjectiveCriteria.DUMMY,
					Component.literal(AuraConfig.BOSS_OBJECTIVE), ObjectiveCriteria.RenderType.INTEGER, true, null);
		}
		scoreboard.getOrCreatePlayerScore(serverPlayer, objective).set(0);

		stack.consume(1, serverPlayer);
		serverPlayer.getCooldowns().addCooldown(this, AuraConfig.BOSS_SIGIL_COOLDOWN_TICKS);
		level.playSound(null, serverPlayer.blockPosition(), com.toast.aurapoints.ability.AuraSounds.ENDER_EYE_DEATH.value(), SoundSource.PLAYERS, 1.0f, 0.7f);
		serverPlayer.sendSystemMessage(Component.literal("The sigil crumbles. Something stirs...")
				.withStyle(ChatFormatting.DARK_PURPLE));
		return InteractionResultHolder.consume(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag type) {
		tooltip.add(Component.literal("Used by datapack bosses.").withStyle(ChatFormatting.GRAY));
		super.appendHoverText(stack, context, tooltip, type);
	}
}
