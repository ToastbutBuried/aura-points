package com.toast.aurapoints.geo;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * The Thorn Sentinel's tree (ability/ThornSentinel): a crooked thorny trunk with a leafy crown and a pod on a branch
 * that points where it shoots. It sprouts and unfurls, sways, kicks back on every shot, and withers into the ground.
 */
public class SentinelTree extends AuraGeoEntity {

	public static final int IDLE = 0, SHOOT = 1, GONE = 2;

	private static final RawAnimation SWAY = RawAnimation.begin().thenLoop("animation.thorn_sentinel.idle");
	private static final RawAnimation GROW = RawAnimation.begin().thenPlay("animation.thorn_sentinel.grow");
	private static final RawAnimation SHOOT_ANIM = RawAnimation.begin().thenPlay("animation.thorn_sentinel.shoot");
	private static final RawAnimation WITHER = RawAnimation.begin().thenPlayAndHold("animation.thorn_sentinel.wither");

	public SentinelTree(EntityType<? extends SentinelTree> type, Level level) {
		super(type, level);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "idle", 4, state -> state.setAndContinue(SWAY)));
		controllers.add(new AnimationController<>(this, "action", 1, state -> switch (state.getAnimatable().actionState(state)) {
			case SHOOT -> state.setAndContinue(SHOOT_ANIM);
			case GONE -> state.setAndContinue(WITHER);
			default -> state.getAnimatable().tickCount < 16 ? state.setAndContinue(GROW) : PlayState.STOP;
		}));
	}
}
