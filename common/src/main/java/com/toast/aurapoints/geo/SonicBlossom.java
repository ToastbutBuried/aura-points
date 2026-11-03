package com.toast.aurapoints.geo;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * Resonance's Sonic Blossom (ability/Resonance): six purple petals curled shut over a glowing heart on a pad of
 * leaves. It shoots up and closes, breathes while it listens, bursts open for every dart, and sinks away.
 */
public class SonicBlossom extends AuraGeoEntity {

	public static final int IDLE = 0, OPEN = 1, GONE = 2;
	/** How long OPEN plays (ticks): set it back to IDLE after this. */
	public static final int OPEN_TICKS = 9;

	private static final RawAnimation BREATHE = RawAnimation.begin().thenLoop("animation.sonic_blossom.idle");
	private static final RawAnimation GROW = RawAnimation.begin().thenPlay("animation.sonic_blossom.grow");
	private static final RawAnimation OPEN_ANIM = RawAnimation.begin().thenPlay("animation.sonic_blossom.open");
	private static final RawAnimation WITHER = RawAnimation.begin().thenPlayAndHold("animation.sonic_blossom.wither");

	public SonicBlossom(EntityType<? extends SonicBlossom> type, Level level) {
		super(type, level);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "idle", 4, state -> state.setAndContinue(BREATHE)));
		controllers.add(new AnimationController<>(this, "action", 1, state -> switch (state.getAnimatable().actionState(state)) {
			case OPEN -> state.setAndContinue(OPEN_ANIM);
			case GONE -> state.setAndContinue(WITHER);
			default -> state.getAnimatable().tickCount < 10 ? state.setAndContinue(GROW) : PlayState.STOP;
		}));
	}
}
