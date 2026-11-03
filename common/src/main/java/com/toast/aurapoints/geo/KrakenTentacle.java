package com.toast.aurapoints.geo;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * One of Maelstrom's kraken tentacles (ability/Maelstrom), the small ones and (scaled up) the giant: eight segments
 * from a thick teal root to a thin tip, suckers down its front, which faces the whirlpool. It bursts up out of the
 * ground and writhes; the giant rears back and slams down forward; all of them sink away at the end.
 */
public class KrakenTentacle extends AuraGeoEntity {

	public static final int WRITHE = 0, RAISE = 1, SLAM = 2, GONE = 3;

	private static final RawAnimation WRITHE_ANIM = RawAnimation.begin().thenLoop("animation.maelstrom_tentacle.writhe");
	private static final RawAnimation RISE = RawAnimation.begin().thenPlay("animation.maelstrom_tentacle.rise");
	private static final RawAnimation RAISE_ANIM = RawAnimation.begin().thenPlayAndHold("animation.maelstrom_tentacle.raise");
	private static final RawAnimation SLAM_ANIM = RawAnimation.begin().thenPlayAndHold("animation.maelstrom_tentacle.slam");
	private static final RawAnimation SINK = RawAnimation.begin().thenPlayAndHold("animation.maelstrom_tentacle.sink");

	public KrakenTentacle(EntityType<? extends KrakenTentacle> type, Level level) {
		super(type, level);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "idle", 4, state -> state.setAndContinue(WRITHE_ANIM)));
		controllers.add(new AnimationController<>(this, "action", 2, state -> switch (state.getAnimatable().actionState(state)) {
			case RAISE -> state.setAndContinue(RAISE_ANIM);
			case SLAM -> state.setAndContinue(SLAM_ANIM);
			case GONE -> state.setAndContinue(SINK);
			default -> state.getAnimatable().tickCount < 10 ? state.setAndContinue(RISE) : PlayState.STOP;
		}));
	}
}
