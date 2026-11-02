package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Sure-Footed (Frost passive): ice is not slippery. Movement runs on both sides - the player's own
 * client moves them and the server checks it - so both ask here, each with what it knows
 * (mixin/LivingEntityMixin swaps the friction of the block underfoot).
 */
public final class SureFooted {

	private SureFooted() {
	}

	/** Friction of a normal block (stone, dirt, ...). */
	public static final float NORMAL_FRICTION = 0.6f;
	/** Anything above this is ice (ice 0.98, blue ice 0.989); slime blocks (0.8) stay bouncy-slidey. */
	private static final float ICY = 0.9f;

	public static float friction(LivingEntity entity, float friction) {
		if (friction <= ICY || !(entity instanceof Player player)) return friction;
		return applies(player) ? NORMAL_FRICTION : friction;
	}

	private static boolean applies(Player player) {
		if (player.level().isClientSide()) {
			// other players on this client are only moved by the server's updates
			return player.isLocalPlayer() && ClientAuraState.aura == AuraType.FROST && !ClientAuraState.isDisabled(Ability.SURE_FOOTED);
		}
		return player instanceof ServerPlayer sp && sp.getServer() != null
				&& AuraAbilities.hasAbility(AuraState.get(sp.getServer()).getOrCreate(sp.getUUID()), Ability.SURE_FOOTED);
	}
}
