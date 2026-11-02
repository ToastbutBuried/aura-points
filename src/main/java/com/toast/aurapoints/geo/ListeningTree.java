package com.toast.aurapoints.geo;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * Resonance's Listening Tree (ability/Resonance): a black trunk that darkens into raw flesh and rolls over at the
 * top into a fern-like curl, its end glowing magenta. It rises straight and then curls over like an ear turning to
 * listen, sways, strains while it holds someone, shudders when it's hit, and uncurls into the ground.
 * The curl's end (where the tendril leaves) is {@link #TIP_UP} up and {@link #TIP_FORWARD} forward of its feet.
 */
public class ListeningTree extends AuraGeoEntity {

	public static final int IDLE = 0, HOLD = 1, HIT = 2, GONE = 3;
	/** How long HIT plays (ticks). */
	public static final int HIT_TICKS = 6;
	/** The curl's end in the rest pose, in blocks (from the model generator: gen.py tree_tip). */
	public static final double TIP_UP = 3.097, TIP_FORWARD = 0.132;

	private static final RawAnimation SWAY = RawAnimation.begin().thenLoop("animation.listening_tree.idle");
	private static final RawAnimation GROW = RawAnimation.begin().thenPlay("animation.listening_tree.grow");
	private static final RawAnimation HOLD_ANIM = RawAnimation.begin().thenLoop("animation.listening_tree.hold");
	private static final RawAnimation HIT_ANIM = RawAnimation.begin().thenPlay("animation.listening_tree.hit");
	private static final RawAnimation WITHER = RawAnimation.begin().thenPlayAndHold("animation.listening_tree.wither");

	public ListeningTree(EntityType<? extends ListeningTree> type, Level level) {
		super(type, level);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "idle", 4, state -> state.setAndContinue(SWAY)));
		controllers.add(new AnimationController<>(this, "action", 2, state -> switch (state.getAnimatable().actionState(state)) {
			case HOLD -> state.setAndContinue(HOLD_ANIM);
			case HIT -> state.setAndContinue(HIT_ANIM);
			case GONE -> state.setAndContinue(WITHER);
			default -> state.getAnimatable().tickCount < 20 ? state.setAndContinue(GROW) : PlayState.STOP;
		}));
	}
}
