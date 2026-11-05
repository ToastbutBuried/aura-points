package com.toast.aurapoints.geo;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Frost Bear: a boss with colour variants (textures/entity/frost_bear_N.png) and a stage machine.
 * <ol>
 * <li>{@link #SPIKED}: a spike man sits inside the bear (a bone the renderer shows only now). Claws and ice spikes.</li>
 * <li>{@link #EJECT}: at EJECT_HEALTH_PCT the spike man is thrown out - BOOM (knockback, damage, slow) - invulnerable.</li>
 * <li>{@link #MAD}: faster, harder, spikes in a line.</li>
 * <li>{@link #TIRED} then {@link #FLEE}: at FLEE_HEALTH_PCT it can't go on, pants, and runs away. It never dies: it
 * leaves, and every player who hurt it gets Blue Bells.</li>
 * </ol>
 * Size is the vanilla generic.scale attribute (model, hitbox and boss all follow). The ICE SPAWN ANIMATION is not here:
 * {@link #spawnTicks} / {@link #onSpawnSequence()} are the hook for it (the bear is invulnerable, still and hidden from
 * the AI while {@link #spawning()}).
 */
public class FrostBear extends PathfinderMob implements GeoEntity {

	public static final int SPIKED = 0, EJECT = 1, MAD = 2, TIRED = 3, FLEE = 4;
	public static final int VARIANTS = 4;
	private static final String[] VARIANT_NAMES = {"White", "Glacier", "Midnight", "Crimson"};

	private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(FrostBear.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(FrostBear.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> SPAWNING = SynchedEntityData.defineId(FrostBear.class, EntityDataSerializers.BOOLEAN);

	private static final ResourceLocation MAD_SPEED = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "frost_bear_mad_speed");
	private static final ResourceLocation MAD_DAMAGE = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "frost_bear_mad_damage");
	private static final ResourceLocation FLEE_SPEED = ResourceLocation.fromNamespaceAndPath(AuraPoints.MOD_ID, "frost_bear_flee_speed");

	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.frost_bear.idle");
	private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.frost_bear.walk");
	private static final RawAnimation RUN = RawAnimation.begin().thenLoop("animation.frost_bear.run");
	private static final RawAnimation TIRED_ANIM = RawAnimation.begin().thenLoop("animation.frost_bear.tired");
	private static final RawAnimation EJECT_ANIM = RawAnimation.begin().thenPlayAndHold("animation.frost_bear.eject");
	private static final RawAnimation SWIPE = RawAnimation.begin().thenPlay("animation.frost_bear.swipe");
	private static final RawAnimation SPIKES = RawAnimation.begin().thenPlay("animation.frost_bear.spikes");

	private static final int EJECT_TICKS = 60, BOOM_AT = 36;

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
	private final ServerBossEvent bar = new ServerBossEvent(Component.literal("Frost Bear"), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
	/** Players who hurt it, with the damage: who gets a Blue Bell when it leaves. */
	private final Map<UUID, Float> damagers = new HashMap<>();
	private int phaseTicks;
	/** Ticks the (external) spawn animation still needs; the bear is helpless meanwhile. 0 = no spawn animation. */
	private int spawnTicks;

	public FrostBear(EntityType<? extends FrostBear> type, Level level) {
		super(type, level);
		this.xpReward = 0;
		this.bar.setVisible(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, AuraConfig.FROST_BEAR_HEALTH)
				.add(Attributes.ATTACK_DAMAGE, AuraConfig.FROST_BEAR_DAMAGE)
				.add(Attributes.MOVEMENT_SPEED, AuraConfig.FROST_BEAR_SPEED)
				.add(Attributes.FOLLOW_RANGE, 48.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.ARMOR, 6.0)
				.add(Attributes.STEP_HEIGHT, 1.5)
				.add(Attributes.SCALE, 1.0);
	}

	// ------------------------------------------------------------ spawn (and the hook for the ice spawn animation)

	/** The sigil / a command: a bear of this variant (-1 = random) at {@code pos}, fully set up. */
	public static FrostBear spawn(ServerLevel level, Vec3 pos, int variant) {
		FrostBear bear = AuraPoints.FROST_BEAR.get().create(level);
		if (bear == null) return null;
		bear.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f);
		if (variant >= 0) bear.setVariant(variant);
		bear.finalizeSpawn(level, level.getCurrentDifficultyAt(bear.blockPosition()), MobSpawnType.COMMAND, null);
		level.addFreshEntity(bear);
		return bear;
	}

	@Override
	public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason, SpawnGroupData data) {
		if (entityData.get(VARIANT) < 0) setVariant(random.nextInt(VARIANTS));
		applyConfig();
		setHealth(getMaxHealth());
		setPersistenceRequired();
		if (level instanceof ServerLevel server) {
			spawnTicks = 0;
			onSpawnSequence();
			if (spawnTicks > 0) entityData.set(SPAWNING, true);
			AuraFx.burst(server, getX(), getY() + 1, getZ(), AuraPoints.FROST_BURST.get(), 3, 0.3, 0.0);
			AuraFx.burst(server, getX(), getY() + 1, getZ(), AuraPoints.FROST_SHARD.get(), 30, 0.8, 0.3);
		}
		return super.finalizeSpawn(level, difficulty, reason, data);
	}

	/**
	 * HOOK for the ice spawn animation (built separately): set {@link #spawnTicks} to how long it runs. While it does
	 * the bear is invulnerable, can't move or fight and its boss bar is hidden. Does nothing by default.
	 */
	protected void onSpawnSequence() {
	}

	public boolean spawning() {
		return entityData.get(SPAWNING);
	}

	/** Reads the balance config into the attributes (run on every spawn, so a changed config applies to new bears). */
	private void applyConfig() {
		set(Attributes.MAX_HEALTH, AuraConfig.FROST_BEAR_HEALTH);
		set(Attributes.ATTACK_DAMAGE, AuraConfig.FROST_BEAR_DAMAGE);
		set(Attributes.MOVEMENT_SPEED, AuraConfig.FROST_BEAR_SPEED);
		set(Attributes.SCALE, AuraConfig.FROST_BEAR_SCALE);
	}

	private void set(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
		AttributeInstance a = getAttribute(attribute);
		if (a != null) a.setBaseValue(value);
	}

	// ------------------------------------------------------------ data

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(VARIANT, -1);
		builder.define(PHASE, SPIKED);
		builder.define(SPAWNING, false);
	}

	public int variant() {
		return Math.max(0, entityData.get(VARIANT));
	}

	public void setVariant(int variant) {
		entityData.set(VARIANT, Math.floorMod(variant, VARIANTS));
		refreshName();
	}

	public int phase() {
		return entityData.get(PHASE);
	}

	private void setPhase(int phase) {
		entityData.set(PHASE, phase);
		phaseTicks = 0;
	}

	private void refreshName() {
		if (!hasCustomName()) bar.setName(Component.literal("Frost Bear - " + VARIANT_NAMES[variant()]));
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putInt("Variant", variant());
		tag.putInt("Phase", phase());
		tag.putInt("PhaseTicks", phaseTicks);
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		entityData.set(VARIANT, Math.floorMod(tag.getInt("Variant"), VARIANTS));
		entityData.set(PHASE, tag.getInt("Phase"));
		phaseTicks = tag.getInt("PhaseTicks");
		refreshName();
		if (hasCustomName()) bar.setName(getDisplayName());
	}

	@Override
	public void setCustomName(Component name) {
		super.setCustomName(name);
		bar.setName(getDisplayName());
	}

	// ------------------------------------------------------------ goals

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(1, new FleeGoal(this));
		goalSelector.addGoal(2, new SpikeGoal(this));
		goalSelector.addGoal(3, new ClawGoal(this));
		goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 16f));
		goalSelector.addGoal(7, new RandomLookAroundGoal(this));
		targetSelector.addGoal(1, new HurtByTargetGoal(this));
		targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
	}

	/** Fighting is allowed in the first and the mad stage, never while it is spawning, ejecting, tired or running. */
	public boolean canFight() {
		int p = phase();
		return !spawning() && (p == SPIKED || p == MAD);
	}

	private static final class ClawGoal extends MeleeAttackGoal {
		private final FrostBear bear;

		ClawGoal(FrostBear bear) {
			super(bear, 1.15, true);
			this.bear = bear;
		}

		@Override
		public boolean canUse() {
			return bear.canFight() && super.canUse();
		}

		@Override
		public boolean canContinueToUse() {
			return bear.canFight() && super.canContinueToUse();
		}
	}

	/** Ice spikes: a warning on the ground under the target, then they erupt. In the mad stage in a line of three. */
	private static final class SpikeGoal extends Goal {
		private final FrostBear bear;
		private int cooldown = 40;
		private int warn;
		private Vec3 at = Vec3.ZERO;
		private Vec3 dir = Vec3.ZERO;

		SpikeGoal(FrostBear bear) {
			this.bear = bear;
			setFlags(java.util.EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			if (!bear.canFight()) return false;
			if (cooldown > 0) {
				cooldown--;
				return false;
			}
			LivingEntity t = bear.getTarget();
			if (t == null || !t.isAlive()) return false;
			double d = bear.distanceTo(t);
			return d > 3.5 && d < 20 && bear.getSensing().hasLineOfSight(t);
		}

		@Override
		public boolean canContinueToUse() {
			return warn > 0 && bear.canFight() && bear.getTarget() != null;
		}

		@Override
		public void start() {
			warn = Math.max(4, AuraConfig.FROST_BEAR_SPIKE_WARN_TICKS);
			LivingEntity t = bear.getTarget();
			at = t.position();
			dir = at.subtract(bear.position()).multiply(1, 0, 1).normalize();
			bear.getNavigation().stop();
			bear.triggerAnim("attack", "spikes");
			bear.playSound(SoundEvents.POLAR_BEAR_WARNING, 2.0f, 0.8f);
		}

		@Override
		public void tick() {
			LivingEntity t = bear.getTarget();
			if (t != null) bear.getLookControl().setLookAt(t, 60f, 60f);
			if (!(bear.level() instanceof ServerLevel level)) return;
			for (Vec3 p : points()) {
				// the warning: a ring of frost on the ground, tightening
				AuraFx.ring(level, p.x, p.y + 0.1, p.z, AuraConfig.FROST_BEAR_SPIKE_RADIUS * (warn / (double) Math.max(1, AuraConfig.FROST_BEAR_SPIKE_WARN_TICKS)) + 0.3,
						AuraPoints.SNOW_GUST.get(), 6);
			}
			if (--warn <= 0) erupt(level);
		}

		private Vec3[] points() {
			if (bear.phase() != MAD) return new Vec3[]{at};
			Vec3 side = new Vec3(-dir.z, 0, dir.x).scale(AuraConfig.FROST_BEAR_SPIKE_RADIUS * 1.6);
			return new Vec3[]{at, at.add(side), at.subtract(side)};
		}

		private void erupt(ServerLevel level) {
			double r = AuraConfig.FROST_BEAR_SPIKE_RADIUS;
			float dmg = AuraConfig.FROST_BEAR_SPIKE_DAMAGE;
			if (bear.phase() == MAD) dmg *= (float) AuraConfig.FROST_BEAR_MAD_DAMAGE_MULT;
			for (Vec3 p : points()) {
				AuraFx.burst(level, p.x, p.y + 0.5, p.z, AuraPoints.FROST_SHARD.get(), 30, 0.5, 0.4);
				AuraFx.burst(level, p.x, p.y + 0.3, p.z, AuraPoints.FROST_BURST.get(), 2, 0.2, 0.0);
				AuraFx.burst(level, p.x, p.y + 0.5, p.z, ParticleTypes.SNOWFLAKE, 14, 0.5, 0.1);
				level.playSound(null, p.x, p.y, p.z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.2f, 0.8f);
				for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(p, p).inflate(r, 2.5, r), e -> bear.isEnemy(e))) {
					e.hurt(bear.damageSources().mobAttack(bear), dmg);
					bear.chill(e);
					e.push(0, 0.5, 0);
					e.hurtMarked = true;
				}
			}
			cooldown = Math.max(20, AuraConfig.FROST_BEAR_SPIKE_COOLDOWN_TICKS) + bear.random.nextInt(30);
			warn = 0;
		}

		@Override
		public void stop() {
			warn = 0;
			if (cooldown <= 0) cooldown = 20;
		}
	}

	/** Runs away from the nearest player. */
	private static final class FleeGoal extends Goal {
		private final FrostBear bear;

		FleeGoal(FrostBear bear) {
			this.bear = bear;
			setFlags(java.util.EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
		}

		@Override
		public boolean canUse() {
			return bear.phase() == FLEE;
		}

		@Override
		public boolean canContinueToUse() {
			return bear.phase() == FLEE;
		}

		@Override
		public void tick() {
			if (!bear.getNavigation().isDone()) return;
			Player p = bear.level().getNearestPlayer(bear, 64);
			Vec3 away = p == null ? bear.getLookAngle().multiply(1, 0, 1) : bear.position().subtract(p.position()).multiply(1, 0, 1);
			if (away.lengthSqr() < 1.0E-4) away = new Vec3(1, 0, 0);
			Vec3 to = bear.position().add(away.normalize().scale(14)).add((bear.random.nextDouble() - 0.5) * 8, 0, (bear.random.nextDouble() - 0.5) * 8);
			bear.getNavigation().moveTo(to.x, to.y, to.z, 1.7);
		}
	}

	// ------------------------------------------------------------ combat

	boolean isEnemy(LivingEntity e) {
		return e != this && !(e instanceof FrostBear) && e.isAlive() && !(e instanceof Player p && (p.isCreative() || p.isSpectator()));
	}

	/** The cold that every frost attack leaves on a victim. */
	void chill(LivingEntity e) {
		e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, AuraConfig.FROST_BEAR_SLOW_TICKS, 1));
		e.setTicksFrozen(Math.min(e.getTicksRequiredToFreeze() + 20, e.getTicksFrozen() + 80));
	}

	@Override
	public boolean doHurtTarget(Entity target) {
		triggerAnim("attack", "swipe");
		boolean hit = super.doHurtTarget(target);
		if (hit && target instanceof LivingEntity l) chill(l);
		return hit;
	}

	@Override
	public boolean isInvulnerableTo(DamageSource source) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.isInvulnerableTo(source);
		int p = phase();
		return spawning() || p == EJECT || p == TIRED || p == FLEE || super.isInvulnerableTo(source);
	}

	/** Takes damage only down to the flee line: the bear can't die, it gets tired instead. */
	@Override
	protected void actuallyHurt(DamageSource source, float amount) {
		if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			float floor = (float) (getMaxHealth() * AuraConfig.FROST_BEAR_FLEE_HEALTH_PCT);
			amount = Math.min(amount, getHealth() - floor);
			if (amount <= 0) return;
		}
		float before = getHealth();
		super.actuallyHurt(source, amount);
		if (source.getEntity() instanceof ServerPlayer p) damagers.merge(p.getUUID(), before - getHealth(), Float::sum);
	}

	// ------------------------------------------------------------ the stage machine

	@Override
	protected void customServerAiStep() {
		super.customServerAiStep();
		if (!(level() instanceof ServerLevel level)) return;
		bar.setProgress(Math.max(0f, getHealth() / getMaxHealth()));
		bar.setVisible(!spawning());
		if (spawnTicks > 0) {
			if (--spawnTicks == 0) entityData.set(SPAWNING, false);
			getNavigation().stop();
			return;
		}
		phaseTicks++;
		switch (phase()) {
			case SPIKED -> {
				if (getHealth() <= getMaxHealth() * AuraConfig.FROST_BEAR_EJECT_HEALTH_PCT) beginEject();
			}
			case EJECT -> {
				getNavigation().stop();
				if (phaseTicks == BOOM_AT) boom(level);
				if (phaseTicks >= EJECT_TICKS) goMad();
			}
			case MAD -> {
				if (getHealth() <= getMaxHealth() * AuraConfig.FROST_BEAR_FLEE_HEALTH_PCT + 0.01f) beginTired();
				if (tickCount % 6 == 0) AuraFx.burst(level, getX(), getY() + 1.2 * scale(), getZ(), AuraPoints.SNOW_GUST.get(), 1, 0.7 * scale(), 0.02);
			}
			case TIRED -> {
				getNavigation().stop();
				setTarget(null);
				if (tickCount % 5 == 0) AuraFx.burst(level, getX(), getEyeY(), getZ(), ParticleTypes.CLOUD, 2, 0.3, 0.02);
				if (phaseTicks >= AuraConfig.FROST_BEAR_TIRED_TICKS) beginFlee();
			}
			case FLEE -> {
				if (tickCount % 2 == 0) AuraFx.burst(level, getX(), getY() + 0.3, getZ(), AuraPoints.FROST_SHARD.get(), 1, 0.4, 0.08);
				if (phaseTicks >= AuraConfig.FROST_BEAR_FLEE_TICKS) leave(level);
			}
			default -> {
			}
		}
	}

	private float scale() {
		return getScale();
	}

	private void beginEject() {
		setPhase(EJECT);
		setTarget(null);
		playSound(SoundEvents.POLAR_BEAR_WARNING, 3.0f, 0.6f);
	}

	/** The spike man leaves the bear: a blast that hurts, throws and chills everybody near. */
	private void boom(ServerLevel level) {
		double r = AuraConfig.FROST_BEAR_BOOM_RADIUS * scale();
		Vec3 c = position().add(0, 1.4 * scale(), 0);
		AuraFx.burst(level, c.x, c.y, c.z, AuraPoints.FROST_BURST.get(), 6, 0.6 * scale(), 0.0);
		AuraFx.burst(level, c.x, c.y, c.z, AuraPoints.FROST_SHARD.get(), 90, 0.8 * scale(), 0.7);
		AuraFx.burst(level, c.x, c.y, c.z, AuraPoints.SNOW_GUST.get(), 24, 1.5 * scale(), 0.12);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.EXPLOSION_EMITTER, 1, 0, 0);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 3.0f, 0.5f);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.5f, 0.7f);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(r, 4, r), this::isEnemy)) {
			e.hurt(damageSources().mobAttack(this), AuraConfig.FROST_BEAR_BOOM_DAMAGE);
			Vec3 away = e.position().subtract(position()).multiply(1, 0, 1);
			away = away.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : away.normalize();
			e.setDeltaMovement(e.getDeltaMovement().add(away.scale(AuraConfig.FROST_BEAR_BOOM_KNOCKBACK).add(0, 0.6, 0)));
			e.hurtMarked = true;
			chill(e);
		}
	}

	private void goMad() {
		setPhase(MAD);
		modifier(Attributes.MOVEMENT_SPEED, MAD_SPEED, AuraConfig.FROST_BEAR_MAD_SPEED_MULT - 1.0);
		modifier(Attributes.ATTACK_DAMAGE, MAD_DAMAGE, AuraConfig.FROST_BEAR_MAD_DAMAGE_MULT - 1.0);
		playSound(SoundEvents.POLAR_BEAR_WARNING, 3.0f, 0.5f);
	}

	private void beginTired() {
		setPhase(TIRED);
		setTarget(null);
		playSound(SoundEvents.POLAR_BEAR_AMBIENT, 3.0f, 0.4f);
	}

	private void beginFlee() {
		setPhase(FLEE);
		modifier(Attributes.MOVEMENT_SPEED, FLEE_SPEED, 0.5);
	}

	private void modifier(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, ResourceLocation id, double amount) {
		AttributeInstance a = getAttribute(attribute);
		if (a == null) return;
		a.removeModifier(id);
		a.addPermanentModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	/** Gone in a flurry of snow; everyone who hurt it is rewarded. */
	private void leave(ServerLevel level) {
		AuraFx.burst(level, getX(), getY() + 1.2 * scale(), getZ(), AuraPoints.FROST_BURST.get(), 4, 0.4, 0.0);
		AuraFx.burst(level, getX(), getY() + 1.2 * scale(), getZ(), AuraPoints.SNOW_GUST.get(), 40, 1.2 * scale(), 0.1);
		AuraFx.burst(level, getX(), getY() + 1.2 * scale(), getZ(), AuraPoints.FROST_SHARD.get(), 40, 1.0 * scale(), 0.3);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.POWDER_SNOW_BREAK, SoundSource.HOSTILE, 2.0f, 0.6f);
		for (UUID id : damagers.keySet()) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p == null) continue;
			for (int i = 0; i < Math.max(0, AuraConfig.FROST_BEAR_BELLS_PER_PLAYER); i++) {
				ItemStack bell = new ItemStack(AuraPoints.BLUE_BELL.get());
				if (!p.getInventory().add(bell)) p.drop(bell, false);
			}
			p.displayClientMessage(Component.literal("The Frost Bear fled into the blizzard... a Blue Bell is left behind.").withStyle(net.minecraft.ChatFormatting.AQUA), false);
		}
		bar.removeAllPlayers();
		discard();
	}

	@Override
	public void startSeenByPlayer(ServerPlayer player) {
		super.startSeenByPlayer(player);
		bar.addPlayer(player);
	}

	@Override
	public void stopSeenByPlayer(ServerPlayer player) {
		super.stopSeenByPlayer(player);
		bar.removePlayer(player);
	}

	@Override
	public void remove(RemovalReason reason) {
		bar.removeAllPlayers();
		super.remove(reason);
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return phase() != EJECT && !spawning() && super.isPushable();
	}

	@Override
	public boolean canBeLeashed() {
		return false;
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.POLAR_BEAR_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.POLAR_BEAR_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.POLAR_BEAR_DEATH;
	}

	// ------------------------------------------------------------ animation

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "main", 5, state -> {
			FrostBear bear = state.getAnimatable();
			return switch (bear.phase()) {
				case EJECT -> state.setAndContinue(EJECT_ANIM);
				case TIRED -> state.setAndContinue(TIRED_ANIM);
				case FLEE -> state.setAndContinue(RUN);
				case MAD -> state.setAndContinue(state.isMoving() ? RUN : IDLE);
				default -> state.setAndContinue(state.isMoving() ? WALK : IDLE);
			};
		}));
		// one-shots, drawn over the main animation
		controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP)
				.triggerableAnim("swipe", SWIPE)
				.triggerableAnim("spikes", SPIKES));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
