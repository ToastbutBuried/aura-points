package com.toast.aurapoints.geo;

import com.toast.aurapoints.AuraPoints;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * The Shadow reaper (geo/entity/reaper.geo.json, source reaper/gen.py): a hooded figure with a scythe that a datapack
 * puppets. Damage, sounds and particles are the datapack's. Its NBT:
 * <ul>
 * <li>{@code Animation:"slash"} (rises out of nothing and sweeps the scythe) or {@code "slam"} (brings it down in front):
 * a one-shot order, at summon or by /data merge. It is used up at once (always saved as ""), so a /data merge of
 * something else never repeats it and the same order twice plays it twice.</li>
 * <li>{@code Playing}, {@code PlayingTicks}: what plays ("" = the ready stance) and for how many ticks: for timing hits.</li>
 * <li>{@code Vanish:1b}: melts away and is gone once the current animation ends (right now if none plays).
 * Otherwise it stays in the ready stance.</li>
 * </ul>
 * Every client plays the clips itself; the clip is synced entity data (not a GeckoLib trigger packet, which nobody
 * would receive in the tick it is summoned).
 */
public class Reaper extends DatapackPuppet {

	/** A clip and its length in ticks; keep in sync with gen.py. */
	private enum Clip {
		STANCE("", 0), SLASH("slash", 36), SLAM("slam", 30), VANISH("vanish", 12);

		final String id;
		final int ticks;

		Clip(String id, int ticks) {
			this.id = id;
			this.ticks = ticks;
		}

		static Clip of(String id) {
			for (Clip c : values()) if (c.id.equals(id)) return c;
			return null;
		}
	}

	private static final Clip[] CLIPS = Clip.values();
	/** serial * 8 + clip: the serial changes with every start, so the same clip twice in a row restarts. */
	private static final EntityDataAccessor<Integer> PLAY = SynchedEntityData.defineId(Reaper.class, EntityDataSerializers.INT);

	private static final RawAnimation STANCE = RawAnimation.begin().thenLoop("animation.reaper.stance");
	private static final RawAnimation SLASH = RawAnimation.begin().thenPlayAndHold("animation.reaper.slash");
	private static final RawAnimation SLAM = RawAnimation.begin().thenPlayAndHold("animation.reaper.slam");
	private static final RawAnimation VANISH = RawAnimation.begin().thenPlayAndHold("animation.reaper.vanish");
	private static final RawAnimation CLOTH = RawAnimation.begin().thenLoop("animation.reaper.cloth");

	/** How far the scythe reaches past the hitbox at scale 1, in blocks. */
	private static final double REACH = 3.5;

	private int playingTicks;
	private boolean vanish;
	/** CLIENT: the PLAY value the controller last started. */
	private int lastPlay = -1;

	public Reaper(EntityType<? extends Reaper> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(PLAY, 0);
	}

	private Clip clip() {
		return CLIPS[entityData.get(PLAY) & 3];
	}

	private void play(Clip clip) {
		entityData.set(PLAY, (entityData.get(PLAY) / 8 + 1) % 100000 * 8 + clip.ordinal());
		playingTicks = 0;
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide || isRemoved()) return;
		Clip clip = clip();
		if (clip != Clip.STANCE && ++playingTicks >= clip.ticks) {
			if (clip == Clip.VANISH) {
				discard();
				return;
			}
			play(clip = Clip.STANCE);
		}
		if (vanish && clip == Clip.STANCE) play(Clip.VANISH);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "main", 0, state -> {
			int play = entityData.get(PLAY);
			if (play != lastPlay) {
				lastPlay = play;
				state.getController().forceAnimationReset();
			}
			return state.setAndContinue(switch (clip()) {
				case SLASH -> SLASH;
				case SLAM -> SLAM;
				case VANISH -> VANISH;
				case STANCE -> STANCE;
			});
		}));
		controllers.add(new AnimationController<>(this, "cloth", 0, state -> state.setAndContinue(CLOTH)));
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putString("Animation", "");
		tag.putString("Playing", clip().id);
		tag.putInt("PlayingTicks", playingTicks);
		tag.putBoolean("Vanish", vanish);
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		vanish = tag.getBoolean("Vanish");
		String order = tag.getString("Animation");
		Clip ordered = Clip.of(order);
		if (ordered == Clip.SLASH || ordered == Clip.SLAM) {
			play(ordered);
			return;
		}
		if (!order.isEmpty()) AuraPoints.LOGGER.warn("Reaper: no animation \"{}\" (slash, slam)", order);
		// loaded from disk mid-clip: pick up there (a /data merge reads back what it already plays: no restart)
		Clip saved = tag.contains("Playing", Tag.TAG_STRING) ? Clip.of(tag.getString("Playing")) : null;
		if (saved != null && saved != clip()) play(saved);
		if (saved != null) playingTicks = tag.getInt("PlayingTicks");
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return false;
	}

	/** The scythe swings far outside the hitbox: don't cull it while only the hitbox is off screen. */
	@Override
	public AABB getBoundingBoxForCulling() {
		return super.getBoundingBoxForCulling().inflate(REACH * getScale());
	}
}
