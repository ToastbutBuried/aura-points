package com.toast.aurapoints.geo;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * Verdant Viper's head (ability/VerdantViper): a Venus flytrap on a stem. It comes up out of the ground, gapes and
 * sways while it hunts, snaps shut on a bite, and droops and sinks into the ground when it's done.
 */
public class ViperHead extends AuraGeoEntity {

	public static final int HUNT = 0, BITE = 1, GONE = 2;

	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.verdant_viper.idle");
	private static final RawAnimation EMERGE = RawAnimation.begin().thenPlay("animation.verdant_viper.emerge");
	private static final RawAnimation BITE_ANIM = RawAnimation.begin().thenPlayAndHold("animation.verdant_viper.bite");
	private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("animation.verdant_viper.death");

	public ViperHead(EntityType<? extends ViperHead> type, Level level) {
		super(type, level);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "idle", 0, state -> state.setAndContinue(IDLE)));
		// registered after idle, so a bite or the end overrides the jaws while it plays
		controllers.add(new AnimationController<>(this, "action", 2, state -> switch (state.getAnimatable().actionState(state)) {
			case BITE -> state.setAndContinue(BITE_ANIM);
			case GONE -> state.setAndContinue(DEATH);
			default -> state.getAnimatable().tickCount < 8 ? state.setAndContinue(EMERGE) : PlayState.STOP;
		}));
	}
}
