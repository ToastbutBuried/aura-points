package com.toast.aurapoints.geo;

import com.toast.aurapoints.AuraPoints;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * The Shadow reaper (geo/entity/reaper.geo.json, source reaper/gen.py): a hooded figure with a scythe that a datapack
 * puppets. Damage and sounds are the datapack's; the faint ambient aura (violet motes, ground mist, sparks drawn to the
 * crown) and the mist where it rises out of the ground are this entity's own, client side. Its NBT:
 * <ul>
 * <li>{@code Animation:"slash"} (rises out of nothing and sweeps the scythe) or {@code "slam"} (brings it down in front):
 * a one-shot order, at summon or by /data merge. It is used up at once (always saved as ""), so a /data merge of
 * something else never repeats it and the same order twice plays it twice. A {@code "slam"} ordered in the tick the
 * reaper is summoned first rises out of the ground (14 ticks), then slams: it still reports {@code Playing:"slam"},
 * but PlayingTicks run 14 higher (impact at 31, 44 ticks in all). A {@code "slam"} ordered later is the plain 30-tick one.</li>
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
		STANCE("", 0), SLASH("slash", 36), SLAM("slam", 30), SLAM_EMERGE("slam", 44), VANISH("vanish", 12);

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
	private static final RawAnimation SLAM_EMERGE = RawAnimation.begin().thenPlayAndHold("animation.reaper.slam_emerge");
	private static final RawAnimation VANISH = RawAnimation.begin().thenPlayAndHold("animation.reaper.vanish");
	/** CLIENT: the violet motes of the aura. */
	private static final DustParticleOptions MOTE = new DustParticleOptions(new Vector3f(.45f, .36f, .62f), 1.0f);
	private static final RawAnimation CLOTH = RawAnimation.begin().thenLoop("animation.reaper.cloth");

	/** How far the scythe reaches past the hitbox at scale 1, in blocks. */
	private static final double REACH = 3.5;

	private int playingTicks;
	private boolean vanish;
	/** SERVER: true until its first tick, so an order read at /summon (or by a /data merge in the same tick) can tell. */
	private boolean fresh = true;
	/** CLIENT: the PLAY value the controller last started. */
	private int lastPlay = -1;
	/** CLIENT: the PLAY value the particles last saw, and the tick it started. */
	private int particlePlay = -1;
	private int clipStartTick;

	public Reaper(EntityType<? extends Reaper> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(PLAY, 0);
	}

	private Clip clip() {
		return CLIPS[entityData.get(PLAY) & 7];
	}

	private void play(Clip clip) {
		entityData.set(PLAY, (entityData.get(PLAY) / 8 + 1) % 100000 * 8 + clip.ordinal());
		playingTicks = 0;
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide) {
			auraParticles();
			return;
		}
		if (isRemoved()) return;
		fresh = false;
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

	/**
	 * CLIENT: a faint otherworldly aura. Violet motes rise around it, mist creeps along the ground, sparks are drawn in toward
	 * the crown, and while it rises out of the ground (slash, emerging slam) a burst of mist and sparks pours out. addParticle
	 * obeys the player's particle setting; sized by generic.scale.
	 */
	private void auraParticles() {
		int play = entityData.get(PLAY);
		if (play != particlePlay) {
			particlePlay = play;
			clipStartTick = tickCount;
		}
		Clip clip = clip();
		if (clip == Clip.VANISH) return;
		double s = getScale(), x = getX(), y = getY(), z = getZ();
		if (tickCount % 2 == 0)
			level().addParticle(MOTE, x + (random.nextDouble() - .5) * .9 * s, y + random.nextDouble() * 2.6 * s, z + (random.nextDouble() - .5) * .9 * s, 0, .02 * s, 0);
		if (tickCount % 3 == 0) {
			double a = random.nextDouble() * Math.PI * 2, r = (.35 + random.nextDouble() * .5) * s;
			level().addParticle(ParticleTypes.SMOKE, x + Math.cos(a) * r, y + .05 * s, z + Math.sin(a) * r, Math.cos(a) * .01, .004, Math.sin(a) * .01);
		}
		if (tickCount % 4 == 0)
			level().addParticle(ParticleTypes.REVERSE_PORTAL, x + (random.nextDouble() - .5) * 2.4 * s, y + (2.1 + random.nextDouble() * .9) * s, z + (random.nextDouble() - .5) * 2.4 * s, 0, 0, 0);
		if ((clip == Clip.SLASH || clip == Clip.SLAM_EMERGE) && tickCount - clipStartTick < 14) {
			for (int i = 0; i < 3; i++) {
				double a = random.nextDouble() * Math.PI * 2, r = (.2 + random.nextDouble() * .5) * s;
				level().addParticle(ParticleTypes.SMOKE, x + Math.cos(a) * r, y + .1 * s, z + Math.sin(a) * r, Math.cos(a) * .06 * s, .03 * s, Math.sin(a) * .06 * s);
				level().addParticle(ParticleTypes.REVERSE_PORTAL, x + Math.cos(a) * r, y + .2 * s, z + Math.sin(a) * r, Math.cos(a) * .1 * s, .08 * s, Math.sin(a) * .1 * s);
			}
		}
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
				case SLAM_EMERGE -> SLAM_EMERGE;
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
		if (ordered == Clip.SLAM && fresh) ordered = Clip.SLAM_EMERGE; // just summoned (no server tick yet): rises out of the ground first
		if (ordered == Clip.SLASH || ordered == Clip.SLAM || ordered == Clip.SLAM_EMERGE) {
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
