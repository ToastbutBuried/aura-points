package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Nightstalker (Shadow passive): you always see in the dark (Night Vision, hidden icon, kept above the 10 s flicker),
 * unless you switched it off with /aura nightvision; and the darker it is where you stand (sky and blocks), the more
 * melee damage you deal - SHADOW_NIGHTSTALKER_DAMAGE_PERCENT at light 0, falling evenly to nothing above
 * SHADOW_NIGHTSTALKER_MAX_LIGHT (a transient multiplying attack-damage modifier). Checked twice a second; only the
 * Night Vision this gave is ever removed.
 */
public final class Nightstalker {

	private Nightstalker() {
	}

	private static final ResourceLocation MODIFIER_ID = ResourceLocation.fromNamespaceAndPath("aurapoints", "nightstalker");
	private static final int VISION_TICKS = 20 * 60;
	/** players wearing our Night Vision right now */
	private static final Set<UUID> SEEING = new HashSet<>();

	static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		if (now % 10 != 0) return;
		boolean has = AuraAbilities.hasAbility(data, AuraAbilities.Ability.NIGHTSTALKER) && !player.isSpectator();
		double bonus = has ? bonus(player.serverLevel().getMaxLocalRawBrightness(player.blockPosition())) : 0;
		var damage = player.getAttribute(Attributes.ATTACK_DAMAGE);
		if (damage != null) {
			damage.removeModifier(MODIFIER_ID);
			if (bonus > 0) damage.addTransientModifier(new AttributeModifier(MODIFIER_ID, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		if (wantsVision(player, data)) {
			MobEffectInstance had = player.getEffect(MobEffects.NIGHT_VISION);
			// a plain 1 min Night Vision without particles, topped up long before the end-of-effect flicker;
			// a stronger or longer one (a potion) is left alone
			if (had == null || had.getDuration() < VISION_TICKS - 200) {
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, VISION_TICKS, 0, true, false, false));
			}
			SEEING.add(player.getUUID());
		} else if (SEEING.remove(player.getUUID())) {
			clearVision(player, data);
		}
	}

	/** Night Vision on right now: Nightstalker held, the config allows it and the player didn't switch it off. */
	public static boolean wantsVision(ServerPlayer player, AuraPlayerData data) {
		return AuraConfig.SHADOW_NIGHTSTALKER_NIGHT_VISION && data.nightVisionOn && !player.isSpectator()
				&& AuraAbilities.hasAbility(data, AuraAbilities.Ability.NIGHTSTALKER);
	}

	/** Takes our Night Vision off at once (ambient + no particles = ours; Light's Luminous keeps its own). */
	public static void clearVision(ServerPlayer player, AuraPlayerData data) {
		SEEING.remove(player.getUUID());
		if (AuraAbilities.hasAbility(data, AuraAbilities.Ability.LUMINOUS)) return;
		MobEffectInstance had = player.getEffect(MobEffects.NIGHT_VISION);
		if (had != null && had.isAmbient() && !had.isVisible()) player.removeEffect(MobEffects.NIGHT_VISION);
	}

	/** The damage bonus (0.25 = +25%) at this light level: full at 0, a step less per level, none above MAX_LIGHT. */
	public static double bonus(int light) {
		int max = AuraConfig.SHADOW_NIGHTSTALKER_MAX_LIGHT;
		if (light > max || max < 0 || AuraConfig.SHADOW_NIGHTSTALKER_DAMAGE_PERCENT <= 0) return 0;
		return AuraConfig.SHADOW_NIGHTSTALKER_DAMAGE_PERCENT * (max + 1 - Math.max(0, light)) / (max + 1.0);
	}

	/** Logged out or the passive gone some other way: nothing to keep track of. */
	public static void forget(ServerPlayer player) {
		SEEING.remove(player.getUUID());
	}
}
