package com.toast.aurapoints.geo;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * Umbral Ambush's shadow clone (ability/UmbralAmbush): a hooded figure of shadow with glowing purple eyes. It rises out
 * of the smoke where its owner stood, hovers and breathes, glides at enemies with a walk, cuts them down with an
 * overhead strike, and melts into the ground when its time is up.
 */
public class ShadowClone extends AuraGeoEntity {

	public static final int IDLE = 0, WALK = 1, STRIKE = 2, GONE = 3;

	private static final RawAnimation HOVER = RawAnimation.begin().thenLoop("animation.shadow_clone.idle");
	private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("animation.shadow_clone.walk");
	private static final RawAnimation STRIKE_ANIM = RawAnimation.begin().thenPlay("animation.shadow_clone.strike");
	private static final RawAnimation EMERGE = RawAnimation.begin().thenPlay("animation.shadow_clone.emerge");
	private static final RawAnimation VANISH = RawAnimation.begin().thenPlayAndHold("animation.shadow_clone.vanish");

	public ShadowClone(EntityType<? extends ShadowClone> type, Level level) {
		super(type, level);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "idle", 4, state -> state.setAndContinue(
				state.getAnimatable().state() == WALK ? WALK_ANIM : HOVER)));
		// registered after idle, so a strike or the end overrides the limbs while it plays
		controllers.add(new AnimationController<>(this, "action", 1, state -> switch (state.getAnimatable().actionState(state)) {
			case STRIKE -> state.setAndContinue(STRIKE_ANIM);
			case GONE -> state.setAndContinue(VANISH);
			default -> state.getAnimatable().tickCount < 10 ? state.setAndContinue(EMERGE) : PlayState.STOP;
		}));
	}
}
