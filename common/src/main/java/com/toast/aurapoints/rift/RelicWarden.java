package com.toast.aurapoints.rift;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Relics;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The Relic Warden (5.46.0): the guardian a rift realm's altar wakes once the relic is forged. A stone colossus in
 * the aura's colours (textures/entity/relic_warden_&lt;aura&gt;.png), the stolen relic glowing in its open chest.
 * Five phases, each announced with a title:
 * <ol>
 * <li>GUARDIAN (100-70%): melee combos (slam, backhand), a Ground Slam and the aura's signature attack.</li>
 * <li>UNBOUND (70-40%): also hurls its arm fragments as homing barrages, charges across the arena along a warned
 * line and calls waves of touched mobs out of the pedestals.</li>
 * <li>SHATTERED (40%): the four shards break loose as Shard Pylons; it can't be hurt until they're broken. It staggers.</li>
 * <li>OVERDRIVE: tinted red, faster and harder, three-hit combos, the Core Beam (a low beam sweeping round the hub:
 * jump it, or stand on the raised rim), signature attacks twice as often.</li>
 * <li>LAST STAND (10%): it drops to one knee, rises with its core torn out in front (takes 50% more damage) and
 * throws everything it has. Kill it within 15 s - or it rallies to 30% once and fights on in Overdrive.</li>
 * </ol>
 * Every attack is telegraphed (5.47: colours as ULTRAKILL has them). A YELLOW flash = parryable: it comes PARRY_LEAD
 * ticks before the hit, and the window is the last PARRY_WINDOW ticks before it lands. A parry needs a full swing
 * (no hit on the Warden in the PARRY_LOCKOUT ticks before) from in front within 4.5 blocks; the first try decides.
 * Swinging early at a yellow tell is a mistimed parry: no damage, a clank, you're staggered. A parry cancels the
 * attack, staggers the Warden and leaves it Exposed (x2.5 damage for 3 s). BLUE = can't be parried, get out of the way.
 * Never saved (runs aren't). Death plays its own 2.5 s animation.
 */
public class RelicWarden extends PathfinderMob implements GeoEntity {

	public static final int GUARDIAN = 0, UNBOUND = 1, SHIELDED = 2, OVERDRIVE = 3, LAST_STAND = 4;
	/** What the main animation shows besides walking: 0 nothing, 1 charging, 2 beaming, 3 kneeling. */
	public static final int POSE_NONE = 0, POSE_CHARGE = 1, POSE_BEAM = 2, POSE_KNEEL = 3;
	private static final int PARRY_LEAD = 10, PARRY_WINDOW = 4, PARRY_LOCKOUT = 10;
	/** Per player: the tick of their last hit on the Warden, and when they may try to parry again. */
	private final java.util.Map<java.util.UUID, Integer> lastHit = new java.util.HashMap<>(), parryLock = new java.util.HashMap<>();
	/** Whoever already tried to parry the current tell (the first try decides). */
	private final java.util.Set<java.util.UUID> tried = new java.util.HashSet<>();
	private int windupStart, exposedUntil;
	/** False through its entrance (it rises, nothing else); set by Rifts when the fight begins. */
	public boolean awake;

	private static final EntityDataAccessor<Integer> AURA = SynchedEntityData.defineId(RelicWarden.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(RelicWarden.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> POSE = SynchedEntityData.defineId(RelicWarden.class, EntityDataSerializers.INT);

	private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.relic_warden.idle");
	private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.relic_warden.walk");
	private static final RawAnimation CHARGE = RawAnimation.begin().thenLoop("animation.relic_warden.charge");
	private static final RawAnimation BEAM = RawAnimation.begin().thenLoop("animation.relic_warden.beam");
	private static final RawAnimation KNEEL = RawAnimation.begin().thenPlayAndHold("animation.relic_warden.kneel");
	private static final RawAnimation EXPOSED = RawAnimation.begin().thenLoop("animation.relic_warden.exposed");
	private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("animation.relic_warden.death");

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
	/** The run it guards (not saved; null for a stray one, which then just fights). */
	Rifts.Run run;
	private int slamCd = 100, signatureCd = 70, summonCd = 40, barrageCd = 60, chargeCd = 120, beamCd = 80, wave;
	/** A telegraphed attack winding up: ticks until it lands, whether it can be parried, what it does. */
	private int windup = -1;
	private boolean parryable;
	private Runnable landing;
	private final List<Shulker> pylons = new ArrayList<>();
	private int staggerTicks;
	private Runnable afterStagger;
	/** Charging: direction, ticks left, who was hit already. */
	private Vec3 chargeDir = Vec3.ZERO;
	private int chargeTicks;
	private final java.util.Set<java.util.UUID> chargeHit = new java.util.HashSet<>();
	/** The Core Beam: ticks left, its angle now, per player when it may hit them again. */
	private int beamTicks;
	private double beamAngle;
	private final java.util.Map<java.util.UUID, Integer> beamHitAt = new java.util.HashMap<>();
	/** The last stand: kneel/rise ticks left, then the desperation timer; used once. */
	private int kneelTicks, desperation;
	private boolean lastStandUsed;
	private final List<Bolt> bolts = new ArrayList<>();

	/** Set once constructed: LivingEntity's constructor already calls setHealth, before the synched data exists. */
	private boolean ready;

	public RelicWarden(EntityType<? extends RelicWarden> type, Level level) {
		super(type, level);
		this.xpReward = 50;
		this.ready = true;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 400)
				.add(Attributes.ATTACK_DAMAGE, 12)
				.add(Attributes.MOVEMENT_SPEED, 0.24)
				.add(Attributes.FOLLOW_RANGE, 48.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.ARMOR, 8.0)
				.add(Attributes.STEP_HEIGHT, 1.5);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(AURA, 0);
		builder.define(PHASE, GUARDIAN);
		builder.define(POSE, POSE_NONE);
	}

	public AuraType aura() {
		List<AuraType> a = Relics.AURAS;
		return a.get(Math.floorMod(entityData.get(AURA), a.size()));
	}

	/** The skin index: the aura's place in Relics.AURAS. */
	public int skin() {
		return Math.floorMod(entityData.get(AURA), Relics.AURAS.size());
	}

	void setAura(AuraType aura) {
		entityData.set(AURA, Math.max(0, Relics.AURAS.indexOf(aura)));
		setCustomName(Component.literal("Warden of the " + aura.displayName + " Relic").withStyle(aura.color, ChatFormatting.BOLD));
	}

	public int phase() {
		return entityData.get(PHASE);
	}

	public int pose() {
		return entityData.get(POSE);
	}

	private void setPose(int pose) {
		entityData.set(POSE, pose);
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	private boolean busy() {
		return windup >= 0 || staggerTicks > 0 || chargeTicks > 0 || beamTicks > 0 || kneelTicks > 0;
	}

	// ------------------------------------------------------------ phases

	private void announce(ServerLevel level, String title, String sub) {
		if (run == null) return;
		for (ServerPlayer p : Rifts.online(level.getServer(), run)) {
			AuraFx.title(p, Component.literal(title).withStyle(s -> s.withColor(aura().rgb).withBold(true)), Component.literal(sub).withStyle(ChatFormatting.GRAY));
		}
		if (run.bar != null) run.bar.setName(Component.literal(getCustomName() == null ? "Warden" : getCustomName().getString()).append(Component.literal(" - " + title.toLowerCase(java.util.Locale.ROOT)))
				.withStyle(aura().color, ChatFormatting.BOLD));
	}

	private void setPhase(ServerLevel level, int phase) {
		entityData.set(PHASE, phase);
		switch (phase) {
			case UNBOUND -> {
				announce(level, "UNBOUND", "its arms come loose - keep moving");
				playSound(SoundEvents.WITHER_SPAWN, 2f, 0.7f);
				summonCd = 20;
				barrageCd = 30;
				chargeCd = 80;
			}
			case OVERDRIVE -> {
				announce(level, "OVERDRIVE", "jump the beam - or take the high ground");
				var speed = getAttribute(Attributes.MOVEMENT_SPEED);
				if (speed != null) speed.setBaseValue(0.3);
				var dmg = getAttribute(Attributes.ATTACK_DAMAGE);
				if (dmg != null) dmg.setBaseValue(16);
				beamCd = 60;
				signatureCd = 40;
			}
			default -> {
			}
		}
	}

	/** The lowest its health may fall in the current phase: each phase has to be gone through. */
	private float floor() {
		return switch (phase()) {
			case GUARDIAN, UNBOUND -> getMaxHealth() * 0.40f;
			case SHIELDED -> getHealth();
			case OVERDRIVE -> lastStandUsed ? 0f : getMaxHealth() * 0.10f;
			default -> kneelTicks > 0 ? getHealth() : 0f;
		};
	}

	// ------------------------------------------------------------ fighting

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, true) {
			@Override
			public boolean canUse() {
				return !busy() && phase() != SHIELDED && super.canUse();
			}

			@Override
			public boolean canContinueToUse() {
				return !busy() && phase() != SHIELDED && super.canContinueToUse();
			}
		});
		goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 24f));
		targetSelector.addGoal(1, new HurtByTargetGoal(this));
		targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, false));
	}

	/** In reach of its target: a combo instead of a plain hit. */
	@Override
	public boolean doHurtTarget(Entity target) {
		if (!(level() instanceof ServerLevel level) || busy()) return false;
		combo(level, 0);
		return true;
	}

	@Override
	public boolean isInvulnerableTo(DamageSource source) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.isInvulnerableTo(source);
		return phase() == SHIELDED || kneelTicks > 0 || super.isInvulnerableTo(source);
	}

	/** /kill and other invulnerability-bypassing damage skip the phase floor. */
	private boolean bypassFloor;

	@Override
	public void kill() {
		bypassFloor = true;
		try {
			super.kill();
		} finally {
			bypassFloor = false;
		}
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			bypassFloor = true;
			try {
				return super.hurt(source, amount);
			} finally {
				bypassFloor = false;
			}
		}
		if (!(level() instanceof ServerLevel level)) return super.hurt(source, amount);
		if (phase() == SHIELDED) {
			if (source.getEntity() instanceof ServerPlayer p) p.displayClientMessage(Component.literal("The pylons shield it - break them!").withStyle(ChatFormatting.GOLD), true);
			return false;
		}
		if (source.getDirectEntity() instanceof ServerPlayer p && windup >= 0 && parryable) {
			int since = tickCount - lastHit.getOrDefault(p.getUUID(), -1000);
			lastHit.put(p.getUUID(), tickCount);
			if (tickCount < parryLock.getOrDefault(p.getUUID(), 0)) return false;
			boolean inWindow = windup < PARRY_WINDOW;
			if (!inWindow || !tried.add(p.getUUID())) {
				// too early (or a second try): a mistimed parry
				mistimed(level, p);
				return false;
			}
			Vec3 to = p.position().subtract(position()).multiply(1, 0, 1).normalize();
			boolean front = Vec3.directionFromRotation(0, getYRot()).dot(to) > 0.3 && p.distanceTo(this) < 4.5 + getBbWidth() / 2;
			if (since >= PARRY_LOCKOUT && front) {
				parried(level, p);
				return super.hurt(source, amount + getMaxHealth() * 0.04f);
			}
			mistimed(level, p);
			return false;
		}
		if (source.getDirectEntity() instanceof ServerPlayer p) lastHit.put(p.getUUID(), tickCount);
		return super.hurt(source, amount);
	}

	@Override
	protected void actuallyHurt(DamageSource source, float amount) {
		if (phase() == LAST_STAND && kneelTicks <= 0 && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) amount *= 1.5f;
		if (tickCount < exposedUntil) amount *= 2.5f;
		super.actuallyHurt(source, amount);
	}

	/**
	 * Every way down goes through here (NeoForge's damage pipeline ignores an amount changed in actuallyHurt): health
	 * never falls below the current phase's floor, so no single hit skips the shield or the last stand.
	 */
	@Override
	public void setHealth(float health) {
		if (ready && !bypassFloor && !level().isClientSide() && health < getHealth() && !isDeadOrDying()) health = Math.max(health, Math.min(getHealth(), floor()));
		super.setHealth(health);
	}

	private void parried(ServerLevel level, ServerPlayer by) {
		windup = -1;
		landing = null;
		parryable = false;
		level.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.5f, 1.8f);
		level.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2f, 1.4f);
		level.sendParticles(ParticleTypes.FLASH, getX(), getY() + 2.2, getZ(), 1, 0, 0, 0, 0);
		level.sendParticles(AuraFx.dust(0xFFD23F, 2.5f), getX(), getY() + 2.2, getZ(), 40, 0.8, 0.8, 0.8, 0);
		AuraFx.title(by, Component.literal("PARRY").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), Component.literal("it's exposed!").withStyle(ChatFormatting.YELLOW));
		by.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
		exposedUntil = tickCount + 60;
		stagger(30, null);
	}

	/** Swung at a yellow tell at the wrong moment: a dull clank, no damage, and you reel for a moment. */
	private void mistimed(ServerLevel level, ServerPlayer p) {
		level.playSound(null, p.getX(), p.getY() + 1, p.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.2f, 0.5f);
		p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1));
		parryLock.put(p.getUUID(), tickCount + 30);
		p.displayClientMessage(Component.literal("Mistimed - wait for the yellow flash").withStyle(ChatFormatting.GRAY), true);
	}

	private void stagger(int ticks, Runnable then) {
		triggerAnim("action", "stagger");
		staggerTicks = ticks;
		afterStagger = then;
		getNavigation().stop();
	}

	@Override
	protected void customServerAiStep() {
		super.customServerAiStep();
		if (!(level() instanceof ServerLevel level) || isNoAi()) return;
		if (!awake) {
			getNavigation().stop();
			return;
		}
		tickBolts(level);
		float hp = getHealth() / getMaxHealth();
		if (staggerTicks > 0) {
			getNavigation().stop();
			if (--staggerTicks == 0 && afterStagger != null) {
				Runnable r = afterStagger;
				afterStagger = null;
				r.run();
			}
			return;
		}
		// phase changes
		if (phase() == GUARDIAN && hp <= 0.70f) setPhase(level, UNBOUND);
		if (phase() == UNBOUND && hp <= 0.40f + 0.001f) shield(level);
		if (phase() == SHIELDED) {
			tickShield(level);
			return;
		}
		if (phase() == OVERDRIVE && !lastStandUsed && getHealth() <= getMaxHealth() * 0.10f + 0.01f) lastStand(level);
		if (phase() == LAST_STAND && tickLastStand(level)) return;
		if (chargeTicks > 0) {
			tickCharge(level);
			return;
		}
		if (beamTicks > 0) {
			tickBeam(level);
			return;
		}
		// a telegraphed attack winding up
		if (windup >= 0) {
			getNavigation().stop();
			if (getTarget() != null) getLookControl().setLookAt(getTarget(), 30f, 30f);
			if (windup == Math.min(PARRY_LEAD, windupStart)) flash(level, parryable);
			if (windup-- == 0) {
				parryable = false;
				Runnable r = landing;
				landing = null;
				if (r != null) r.run();
			}
			return;
		}
		LivingEntity t = getTarget();
		if (t == null || !t.isAlive()) return;
		int ph = phase();
		boolean fast = ph == OVERDRIVE || ph == LAST_STAND;
		slamCd--;
		signatureCd--;
		summonCd--;
		barrageCd--;
		chargeCd--;
		beamCd--;
		if (ph == OVERDRIVE && beamCd <= 0) {
			beamCd = 260;
			startBeam(level);
		} else if ((ph == UNBOUND || fast) && chargeCd <= 0 && distanceTo(t) > 6) {
			chargeCd = fast ? 120 : 180;
			startCharge(level, t);
		} else if ((ph == UNBOUND || fast) && barrageCd <= 0) {
			barrageCd = ph == LAST_STAND ? 30 : fast ? 90 : 130;
			barrage(level);
		} else if (slamCd <= 0 && distanceTo(t) < 7) {
			slamCd = fast ? 90 : 140;
			slam(level);
		} else if (signatureCd <= 0) {
			signatureCd = fast ? 110 : 190;
			signature(level, aura());
		} else if (ph == UNBOUND && summonCd <= 0) {
			summonCd = 400;
			summon(level);
		}
	}

	/** The tell before an attack lands: YELLOW = parryable, BLUE = not. */
	private void flash(ServerLevel level, boolean yellow) {
		int col = yellow ? 0xFFD23F : 0x3FA9FF;
		level.sendParticles(AuraFx.dust(col, 2.8f), getX(), getY() + 2.6, getZ(), 30, 0.6, 0.9, 0.6, 0);
		level.sendParticles(ParticleTypes.FLASH, getX(), getY() + 2.6, getZ(), 1, 0, 0, 0, 0);
		if (!yellow) level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY() + 2.6, getZ(), 20, 0.6, 0.9, 0.6, 0.02);
		level.playSound(null, getX(), getY() + 2, getZ(), yellow ? SoundEvents.BELL_RESONATE : SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 2f, yellow ? 1.6f : 1.2f);
	}

	/** A ring of the aura's colour that tightens on a spot while an attack winds up there. */
	private void warn(ServerLevel level, Vec3 at, double radius, int ticks) {
		for (int k = 0; k < ticks; k += 4) {
			int left = ticks - k;
			AuraFx.later(level.getServer(), k, () -> {
				if (isAlive()) AuraFx.ring(level, at.x, at.y + 0.15, at.z, radius * (0.4 + 0.6 * left / ticks), AuraFx.dust(aura().rgb, 1.5f), 18);
			});
		}
	}

	private void windUp(int ticks, boolean canParry, Runnable then) {
		windup = ticks;
		windupStart = ticks;
		tried.clear();
		parryable = canParry;
		landing = then;
	}

	private List<ServerPlayer> playersNear(ServerLevel level, Vec3 at, double r) {
		return level.getEntitiesOfClass(ServerPlayer.class, new AABB(at, at).inflate(r, 3, r), p -> !p.isSpectator() && p.distanceToSqr(at) <= r * r + 9);
	}

	private void hit(ServerLevel level, ServerPlayer p, float dmg) {
		p.hurt(level.damageSources().mobAttack(this), dmg * (run == null ? 1f : (float) run.difficulty.bossDamage));
	}

	private Vec3 front(double d) {
		Vec3 look = Vec3.directionFromRotation(0, getYRot());
		return position().add(look.scale(d));
	}

	// ------------------------------------------------------------ combos and the slam

	/** Step 0: an overhead smash (blue), 1: a backhand (yellow), 2 (Overdrive): a second smash (blue). */
	private void combo(ServerLevel level, int step) {
		boolean three = phase() == OVERDRIVE || phase() == LAST_STAND;
		if (step == 0) {
			triggerAnim("action", "slam");
			windUp(13, true, () -> {
				Vec3 at = front(2.2);
				smash(level, at, 2.6, 10f);
				combo(level, 1);
			});
		} else if (step == 1) {
			triggerAnim("action", "sweep");
			windUp(8, false, () -> {
				Vec3 c = front(2.0);
				level.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y + 1.2, c.z, 3, 1.2, 0.3, 1.2, 0);
				level.playSound(null, c.x, c.y, c.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.6f, 0.5f);
				Vec3 side = Vec3.directionFromRotation(0, getYRot() + 90);
				for (ServerPlayer p : playersNear(level, c, 3.6)) {
					hit(level, p, 8f);
					p.setDeltaMovement(side.x * 1.2, 0.35, side.z * 1.2);
					p.hurtMarked = true;
				}
				if (three) combo(level, 2);
			});
		} else {
			triggerAnim("action", "slam");
			windUp(11, true, () -> smash(level, front(2.4), 3.2, 12f));
		}
	}

	private void smash(ServerLevel level, Vec3 at, double r, float dmg) {
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 2, 0.5, 0.1, 0.5, 0);
		level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, RealmBuilder.palette(aura()).floor().defaultBlockState()),
				at.x, at.y + 0.2, at.z, 30, r * 0.6, 0.2, r * 0.6, 0.3);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_ATTACK_IMPACT, SoundSource.HOSTILE, 2f, 0.6f);
		for (ServerPlayer p : playersNear(level, at, r)) {
			hit(level, p, dmg);
			p.setDeltaMovement(p.getDeltaMovement().x, 0.55, p.getDeltaMovement().z);
			p.hurtMarked = true;
		}
	}

	private void slam(ServerLevel level) {
		triggerAnim("action", "slam");
		Vec3 at = position();
		warn(level, at, 4.5, 16);
		playSound(SoundEvents.WARDEN_ATTACK_IMPACT, 2f, 0.6f);
		windUp(16, random.nextBoolean(), () -> {
			level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 6, 2, 0.1, 2, 0);
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, RealmBuilder.palette(aura()).floor().defaultBlockState()),
					at.x, at.y + 0.2, at.z, 60, 2.5, 0.2, 2.5, 0.3);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.6f, 0.6f);
			for (ServerPlayer p : playersNear(level, at, 4.5)) {
				hit(level, p, 10f);
				Vec3 push = p.position().subtract(at).normalize().scale(0.9);
				p.setDeltaMovement(push.x, 0.7, push.z);
				p.hurtMarked = true;
			}
		});
	}

	// ------------------------------------------------------------ the charge

	private void startCharge(ServerLevel level, LivingEntity t) {
		chargeDir = t.position().subtract(position()).multiply(1, 0, 1).normalize();
		float yaw = (float) Math.toDegrees(Math.atan2(-chargeDir.x, chargeDir.z));
		setYRot(yaw);
		yBodyRot = yaw;
		yHeadRot = yaw;
		// the warning: a yellow lane on the ground
		for (int k = 0; k < 18; k += 3) {
			AuraFx.later(level.getServer(), k, () -> {
				if (!isAlive()) return;
				for (double d = 1; d < 20; d += 0.8) {
					Vec3 p = position().add(chargeDir.scale(d));
					level.sendParticles(AuraFx.dust(0x3FA9FF, 1.2f), p.x, p.y + 0.15, p.z, 1, 0.3, 0, 0.3, 0);
				}
			});
		}
		playSound(SoundEvents.RAVAGER_ROAR, 2f, 0.6f);
		windUp(18, false, () -> {
			chargeTicks = 14;
			chargeHit.clear();
			setPose(POSE_CHARGE);
		});
	}

	private void tickCharge(ServerLevel level) {
		chargeTicks--;
		Vec3 next = position().add(chargeDir.scale(1.4));
		boolean blocked = !level.getBlockState(net.minecraft.core.BlockPos.containing(next.add(0, 1, 0))).isAir()
				|| (run != null && next.multiply(1, 0, 1).distanceTo(Reliquary.cryptCenter(run).multiply(1, 0, 1)) > Monument.anchors().cryptRadius - 3);
		if (!blocked) {
			moveTo(next.x, getY(), next.z, getYRot(), 0);
			level.sendParticles(AuraFx.dust(aura().rgb, 2f), getX(), getY() + 1, getZ(), 6, 0.6, 0.8, 0.6, 0);
		}
		for (ServerPlayer p : playersNear(level, position(), 2.3)) {
			if (!chargeHit.add(p.getUUID())) continue;
			hit(level, p, 12f);
			Vec3 side = new Vec3(-chargeDir.z, 0, chargeDir.x).scale(p.position().subtract(position()).dot(new Vec3(-chargeDir.z, 0, chargeDir.x)) >= 0 ? 1 : -1);
			p.setDeltaMovement(side.x * 1.3 + chargeDir.x * 0.6, 0.6, side.z * 1.3 + chargeDir.z * 0.6);
			p.hurtMarked = true;
		}
		if (blocked || chargeTicks <= 0) {
			chargeTicks = 0;
			setPose(POSE_NONE);
			smash(level, position(), 3, 6f);
			// a wall or the edge: it stuns itself for a moment (an opening)
			if (blocked) stagger(20, null);
		}
	}

	// ------------------------------------------------------------ the fragment barrage

	/** A fragment hurled at a player: a block display that homes in a little. */
	private static final class Bolt {
		final Display.BlockDisplay d;
		Vec3 pos, vel;
		final ServerPlayer target;
		int life = 70;

		Bolt(Display.BlockDisplay d, Vec3 pos, Vec3 vel, ServerPlayer target) {
			this.d = d;
			this.pos = pos;
			this.vel = vel;
			this.target = target;
		}
	}

	private void barrage(ServerLevel level) {
		triggerAnim("action", "cast");
		List<ServerPlayer> targets = playersNear(level, position(), 30);
		if (targets.isEmpty()) return;
		playSound(SoundEvents.BREEZE_SHOOT, 2f, 0.5f);
		int per = phase() == LAST_STAND ? 3 : 2;
		for (int k = 0; k < targets.size() * per; k++) {
			ServerPlayer t = targets.get(k % targets.size());
			int delay = k * 4;
			AuraFx.later(level.getServer(), 8 + delay, () -> {
				if (!isAlive() || !t.isAlive()) return;
				Vec3 from = position().add(0, 2.6, 0).add(Vec3.directionFromRotation(0, getYRot() + (bolts.size() % 2 == 0 ? 90 : -90)).scale(1.4));
				Vec3 vel = t.position().add(0, 1, 0).subtract(from).normalize().scale(0.55).add(0, 0.25, 0);
				var block = RealmBuilder.palette(aura()).accent().defaultBlockState();
				Display.BlockDisplay d = com.toast.aurapoints.ability.Props.spawn(level, from, block,
						new com.mojang.math.Transformation(new org.joml.Vector3f(-0.3f, -0.3f, -0.3f), null, new org.joml.Vector3f(0.6f, 0.6f, 0.6f), null), 100, 2);
				bolts.add(new Bolt(d, from, vel, t));
				level.playSound(null, from.x, from.y, from.z, SoundEvents.BREEZE_SHOOT, SoundSource.HOSTILE, 1f, 1.2f);
			});
		}
	}

	private void tickBolts(ServerLevel level) {
		if (bolts.isEmpty()) return;
		for (var it = bolts.iterator(); it.hasNext(); ) {
			Bolt b = it.next();
			b.life--;
			if (b.target.isAlive() && b.life > 20) {
				Vec3 want = b.target.position().add(0, 1, 0).subtract(b.pos).normalize().scale(0.7);
				b.vel = b.vel.scale(0.88).add(want.scale(0.12));
			}
			b.pos = b.pos.add(b.vel);
			com.toast.aurapoints.ability.Props.move(b.d, b.pos);
			level.sendParticles(AuraFx.dust(aura().rgb, 1.3f), b.pos.x, b.pos.y, b.pos.z, 2, 0.1, 0.1, 0.1, 0);
			boolean done = b.life <= 0 || !level.getBlockState(net.minecraft.core.BlockPos.containing(b.pos)).isAir();
			for (ServerPlayer p : playersNear(level, b.pos, 1.3)) {
				if (p.getBoundingBox().inflate(0.4).contains(b.pos)) {
					hit(level, p, 6f);
					done = true;
				}
			}
			if (done) {
				level.sendParticles(ParticleTypes.EXPLOSION, b.pos.x, b.pos.y, b.pos.z, 1, 0, 0, 0, 0);
				com.toast.aurapoints.ability.Props.remove(b.d);
				it.remove();
			}
		}
	}

	// ------------------------------------------------------------ the core beam

	private void startBeam(ServerLevel level) {
		triggerAnim("action", "cast");
		playSound(SoundEvents.BEACON_ACTIVATE, 3f, 0.5f);
		warn(level, position(), 14, 24);
		if (run != null) for (ServerPlayer p : Rifts.online(level.getServer(), run)) p.displayClientMessage(Component.literal("⚠ Core Beam - jump it!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), true);
		windUp(24, false, () -> {
			beamTicks = 70;
			beamAngle = Math.toRadians(getYRot() + 90);
			beamHitAt.clear();
			setPose(POSE_BEAM);
		});
	}

	private void tickBeam(ServerLevel level) {
		beamTicks--;
		getNavigation().stop();
		beamAngle += Math.PI * 2 / 60.0;
		Vec3 dir = new Vec3(Math.cos(beamAngle), 0, Math.sin(beamAngle));
		double y = getY() + 0.45;
		if (tickCount % 2 == 0) {
			for (double d = 1; d < 15; d += 0.6) {
				level.sendParticles(AuraFx.dust(aura().rgb, 1.6f), getX() + dir.x * d, y, getZ() + dir.z * d, 1, 0.02, 0.05, 0.02, 0);
			}
			level.sendParticles(ParticleTypes.END_ROD, getX() + dir.x * 14, y, getZ() + dir.z * 14, 2, 0.1, 0.1, 0.1, 0.02);
		}
		if (beamTicks % 10 == 0) level.playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_AMBIENT, SoundSource.HOSTILE, 2.5f, 1.6f);
		for (ServerPlayer p : playersNear(level, position(), 15)) {
			Vec3 rel = p.position().subtract(position());
			double along = rel.x * dir.x + rel.z * dir.z, across = Math.abs(rel.x * dir.z - rel.z * dir.x);
			// over it: feet more than ~0.9 above the Warden's floor (a jump, the raised rim)
			if (along < 0.8 || along > 15 || across > 0.8 || p.getY() - getY() > 0.9) continue;
			int next = beamHitAt.getOrDefault(p.getUUID(), 0);
			if (tickCount < next) continue;
			beamHitAt.put(p.getUUID(), tickCount + 10);
			hit(level, p, 6f);
			p.igniteForSeconds(2);
		}
		if (beamTicks <= 0) setPose(POSE_NONE);
	}

	// ------------------------------------------------------------ the last stand

	private void lastStand(ServerLevel level) {
		lastStandUsed = true;
		entityData.set(PHASE, LAST_STAND);
		windup = -1;
		landing = null;
		chargeTicks = 0;
		beamTicks = 0;
		kneelTicks = 60;
		setPose(POSE_KNEEL);
		getNavigation().stop();
		playSound(SoundEvents.WARDEN_HEARTBEAT, 3f, 0.6f);
		announce(level, "LAST STAND", "its core is exposed - finish it");
	}

	/** True while it kneels (nothing else this tick). */
	private boolean tickLastStand(ServerLevel level) {
		if (kneelTicks > 0) {
			kneelTicks--;
			getNavigation().stop();
			if (kneelTicks % 10 == 0) playSound(SoundEvents.WARDEN_HEARTBEAT, 3f, 0.6f + (60 - kneelTicks) / 60f);
			if (kneelTicks == 20) {
				setPose(POSE_NONE);
				triggerAnim("action", "rise");
			}
			if (kneelTicks == 0) {
				desperation = 300;
				playSound(SoundEvents.WARDEN_ROAR, 3f, 1.0f);
				level.sendParticles(AuraFx.dust(aura().rgb, 3f), getX(), getY() + 2.5, getZ(), 120, 2, 2, 2, 0);
				barrageCd = 0;
			}
			return true;
		}
		if (desperation > 0 && --desperation == 0) {
			// it held out: back to Overdrive with 30%, and now it can die
			setHealth(getMaxHealth() * 0.30f);
			entityData.set(PHASE, OVERDRIVE);
			announce(level, "IT ENDURES", "the Warden rallies - one last push");
			playSound(SoundEvents.TOTEM_USE, 2f, 0.6f);
		}
		return false;
	}

	private void summon(ServerLevel level) {
		triggerAnim("action", "summon");
		playSound(SoundEvents.EVOKER_PREPARE_SUMMON, 2.5f, 0.6f);
		windUp(24, false, () -> {
			wave++;
			if (run == null) return;
			int players = Math.max(1, Rifts.online(level.getServer(), run).size());
			int budget = (int) Math.round((AuraConfig.RIFT_BUDGET_BASE / 2.0 + AuraConfig.RIFT_BUDGET_PER_ROOM / 2.0 * wave) * (1 + 0.5 * (players - 1)));
			int k = 0;
			while (budget > 0 && k < 12) {
				Rifts.Kind kind = Rifts.KINDS[level.random.nextInt(Rifts.KINDS.length)];
				if (kind.cost() > budget) kind = Rifts.KINDS[0];
				budget -= kind.cost();
				// out of cracks in the crypt's ceiling, onto open floor (never into a pillar or a ledge)
				if (run.mobs.size() >= 3 * players + 1) break;
				Vec3 at = Reliquary.cryptSpot(run, level.random).add(0, 7, 0);
				Mob m = Rifts.spawnMob(level, run, kind.type(), at, 0);
				if (m != null) {
					m.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0));
					level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.DEEPSLATE.defaultBlockState()),
							at.x, at.y + 1, at.z, 20, 0.6, 0.3, 0.6, 0.1);
					run.mobs.add(m);
				}
				k++;
			}
			for (ServerPlayer p : Rifts.online(level.getServer(), run)) {
				p.displayClientMessage(Component.literal("The Warden calls the realm to its aid!").withStyle(ChatFormatting.RED), true);
			}
		});
	}

	// ------------------------------------------------------------ the shield

	private void shield(ServerLevel level) {
		entityData.set(PHASE, SHIELDED);
		announce(level, "SHATTERED", "the shards shield it - break the four pylons");
		windup = -1;
		landing = null;
		chargeTicks = 0;
		beamTicks = 0;
		setPose(POSE_NONE);
		getNavigation().stop();
		triggerAnim("action", "cast");
		playSound(SoundEvents.BEACON_ACTIVATE, 3f, 0.5f);
		Vec3 home = run != null ? Reliquary.cryptCenter(run) : position();
		for (int i = 0; i < 4; i++) {
			double a = i * Math.PI / 2 + Math.PI / 4;
			Vec3 at = home.add(Math.cos(a) * 9, 1, Math.sin(a) * 9);
			Shulker s = EntityType.SHULKER.create(level);
			if (s == null) continue;
			s.moveTo(at.x, at.y, at.z, 0, 0);
			s.setNoAi(true);
			s.setPersistenceRequired();
			s.addTag(Rifts.MOB_TAG);
			s.setCustomName(Component.literal("Shard Pylon").withStyle(aura().color));
			s.setGlowingTag(true);
			var hp = s.getAttribute(Attributes.MAX_HEALTH);
			if (hp != null) hp.setBaseValue(24 + 12 * (run == null ? 0 : Math.max(0, Rifts.online(level.getServer(), run).size() - 1)));
			s.setHealth(s.getMaxHealth());
			DyeColor dye = dye(aura());
			s.setVariant(java.util.Optional.of(dye));
			level.addFreshEntity(s);
			pylons.add(s);
			if (run != null) run.mobs.add(s);
			level.sendParticles(ParticleTypes.FLASH, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
		}
	}

	private void tickShield(ServerLevel level) {
		getNavigation().stop();
		pylons.removeIf(s -> !s.isAlive() || s.isRemoved());
		if (tickCount % 4 == 0) {
			// beams from each pylon to the stolen relic in its chest
			Vec3 chest = position().add(0, 2.3, 0);
			for (Shulker s : pylons) {
				Vec3 from = s.position().add(0, 0.6, 0);
				for (int k = 1; k < 12; k++) {
					Vec3 p = from.lerp(chest, k / 12.0);
					level.sendParticles(AuraFx.dust(aura().rgb, 1.0f), p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0);
				}
			}
		}
		if (pylons.isEmpty()) {
			playSound(SoundEvents.IRON_GOLEM_DAMAGE, 3f, 0.4f);
			entityData.set(PHASE, OVERDRIVE);
			stagger(40, () -> {
				triggerAnim("action", "roar");
				playSound(SoundEvents.WARDEN_ROAR, 3f, 0.8f);
				level.sendParticles(AuraFx.dust(aura().rgb, 3f), getX(), getY() + 2.5, getZ(), 80, 1.5, 1.5, 1.5, 0);
				setPhase(level, OVERDRIVE);
			});
		}
	}


	private static DyeColor dye(AuraType a) {
		return switch (a) {
			case FIRE -> DyeColor.ORANGE;
			case WATER -> DyeColor.BLUE;
			case WIND -> DyeColor.WHITE;
			case FROST -> DyeColor.LIGHT_BLUE;
			case VERDANT -> DyeColor.LIME;
			case THUNDER -> DyeColor.YELLOW;
			case EARTH -> DyeColor.BROWN;
			case LIGHT -> DyeColor.YELLOW;
			case SHADOW -> DyeColor.PURPLE;
			case BLOOD -> DyeColor.RED;
			default -> DyeColor.GRAY;
		};
	}

	// ------------------------------------------------------------ the signature attacks

	private void signature(ServerLevel level, AuraType aura) {
		triggerAnim("action", "cast");
		playSound(SoundEvents.EVOKER_CAST_SPELL, 2.5f, 0.6f);
		List<ServerPlayer> targets = playersNear(level, position(), 28);
		if (targets.isEmpty()) return;
		ServerPlayer main = getTarget() instanceof ServerPlayer sp ? sp : targets.get(0);
		Vec3 me = position();
		switch (aura) {
			case FIRE -> {
				// meteors: three spots around each player
				List<Vec3> spots = new ArrayList<>();
				for (ServerPlayer p : targets) for (int k = 0; k < 3; k++) spots.add(p.position().add(random.nextGaussian() * 2.5, 0, random.nextGaussian() * 2.5));
				for (Vec3 s : spots) warn(level, s, 2.2, 26);
				windUp(26, false, () -> {
					for (Vec3 s : spots) {
						level.sendParticles(ParticleTypes.LAVA, s.x, s.y + 0.5, s.z, 12, 0.6, 0.3, 0.6, 0);
						level.sendParticles(ParticleTypes.FLAME, s.x, s.y + 0.5, s.z, 30, 0.8, 0.5, 0.8, 0.05);
						level.playSound(null, s.x, s.y, s.z, SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1f, 0.6f);
						for (ServerPlayer p : playersNear(level, s, 2.2)) {
							hit(level, p, 7f);
							p.igniteForSeconds(4);
						}
					}
				});
			}
			case WATER -> {
				warn(level, me, 10, 20);
				windUp(20, false, () -> {
					AuraFx.ring(level, me.x, me.y + 0.4, me.z, 6, ParticleTypes.SPLASH, 60);
					level.playSound(null, me.x, me.y, me.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 2f, 0.5f);
					for (ServerPlayer p : playersNear(level, me, 10)) {
						hit(level, p, 6f);
						Vec3 push = p.position().subtract(me).normalize().scale(1.4);
						p.setDeltaMovement(push.x, 0.4, push.z);
						p.hurtMarked = true;
						p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
					}
				});
			}
			case WIND -> {
				warn(level, me, 14, 20);
				windUp(20, false, () -> {
					level.playSound(null, me.x, me.y, me.z, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.HOSTILE, 2f, 0.6f);
					for (ServerPlayer p : playersNear(level, me, 14)) {
						Vec3 pull = me.subtract(p.position()).normalize().scale(1.1);
						p.setDeltaMovement(pull.x, 0.9, pull.z);
						p.hurtMarked = true;
						hit(level, p, 4f);
						level.sendParticles(ParticleTypes.GUST, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
					}
				});
			}
			case FROST, EARTH -> {
				// a line of spikes / a fissure toward the target
				Vec3 dir = main.position().subtract(me).multiply(1, 0, 1).normalize();
				List<Vec3> line = new ArrayList<>();
				for (int k = 2; k <= 16; k += 2) line.add(me.add(dir.scale(k)));
				for (Vec3 s : line) warn(level, s, 1.6, 22);
				windUp(22, false, () -> {
					var block = aura == AuraType.FROST ? net.minecraft.world.level.block.Blocks.PACKED_ICE : net.minecraft.world.level.block.Blocks.DRIPSTONE_BLOCK;
					for (Vec3 s : line) {
						level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, block.defaultBlockState()), s.x, s.y + 0.6, s.z, 20, 0.4, 0.6, 0.4, 0.2);
						for (ServerPlayer p : playersNear(level, s, 1.8)) {
							hit(level, p, 8f);
							if (aura == AuraType.FROST) p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2));
							else {
								p.setDeltaMovement(p.getDeltaMovement().x, 1.0, p.getDeltaMovement().z);
								p.hurtMarked = true;
							}
						}
					}
					level.playSound(null, me.x, me.y, me.z, aura == AuraType.FROST ? SoundEvents.GLASS_BREAK : SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 2f, 0.6f);
				});
			}
			case VERDANT -> {
				for (ServerPlayer p : targets) warn(level, p.position(), 1.8, 24);
				List<Vec3> spots = targets.stream().map(Entity::position).toList();
				windUp(24, false, () -> {
					for (Vec3 s : spots) {
						level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, s.x, s.y + 0.5, s.z, 30, 0.6, 0.5, 0.6, 0);
						for (ServerPlayer p : playersNear(level, s, 1.9)) {
							hit(level, p, 4f);
							p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 6));
							p.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 1));
						}
					}
					level.playSound(null, me.x, me.y, me.z, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 2f, 0.5f);
				});
			}
			case THUNDER, LIGHT -> {
				List<Vec3> spots = new ArrayList<>();
				for (ServerPlayer p : targets) spots.add(p.position());
				if (aura == AuraType.THUNDER) for (int k = 0; k < 3; k++) spots.add(me.add(random.nextGaussian() * 6, 0, random.nextGaussian() * 6));
				for (Vec3 s : spots) warn(level, s, 2, 26);
				windUp(26, false, () -> {
					for (Vec3 s : spots) {
						if (aura == AuraType.THUNDER) {
							var bolt = EntityType.LIGHTNING_BOLT.create(level);
							if (bolt != null) {
								bolt.moveTo(s);
								bolt.setVisualOnly(true);
								level.addFreshEntity(bolt);
							}
						} else {
							level.sendParticles(ParticleTypes.END_ROD, s.x, s.y + 4, s.z, 50, 0.3, 4, 0.3, 0.02);
							level.playSound(null, s.x, s.y, s.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.5f, 1.6f);
						}
						for (ServerPlayer p : playersNear(level, s, 2)) {
							hit(level, p, 8f);
							if (aura == AuraType.LIGHT) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0));
						}
					}
				});
			}
			case SHADOW -> {
				// blinks behind its target and strikes
				Vec3 behind = main.position().subtract(main.getLookAngle().multiply(1, 0, 1).normalize().scale(2));
				level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 2, getZ(), 40, 0.8, 1.5, 0.8, 0.02);
				teleportTo(behind.x, main.getY(), behind.z);
				playSound(SoundEvents.ENDERMAN_TELEPORT, 2f, 0.5f);
				main.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0));
				windUp(10, true, () -> {
					triggerAnim("action", "slam");
					for (ServerPlayer p : playersNear(level, position(), 3.5)) hit(level, p, 9f);
				});
			}
			case BLOOD -> {
				// drains its target for three seconds, healing itself
				for (int k = 0; k < 60; k += 10) {
					AuraFx.later(level.getServer(), k, () -> {
						if (!isAlive() || !main.isAlive() || main.distanceTo(this) > 20) return;
						hit(level, main, 2f);
						heal(4f);
						Vec3 a = main.position().add(0, 1, 0), b = position().add(0, 2.3, 0);
						for (int i = 1; i < 10; i++) {
							Vec3 p = a.lerp(b, i / 10.0);
							level.sendParticles(AuraFx.dust(0xB0121B, 1.2f), p.x, p.y, p.z, 1, 0, 0, 0, 0);
						}
					});
				}
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------ dying

	@Override
	protected void tickDeath() {
		// the death animation runs 2.5 s
		++deathTime;
		if (deathTime == 1) {
			playSound(SoundEvents.WARDEN_DEATH, 3f, 0.6f);
			for (Shulker s : pylons) s.discard();
			for (Bolt b : bolts) com.toast.aurapoints.ability.Props.remove(b.d);
			bolts.clear();
		}
		if (level() instanceof ServerLevel level && deathTime % 5 == 0) {
			level.sendParticles(AuraFx.dust(aura().rgb, 2f), getX(), getY() + 2, getZ(), 12, 0.8, 1.2, 0.8, 0);
		}
		if (deathTime >= 50 && !level().isClientSide() && !isRemoved()) {
			level().broadcastEntityEvent(this, (byte) 60);
			remove(RemovalReason.KILLED);
		}
	}

	@Override
	public void remove(RemovalReason reason) {
		for (Bolt b : bolts) com.toast.aurapoints.ability.Props.remove(b.d);
		bolts.clear();
		super.remove(reason);
	}

	// ------------------------------------------------------------ animation

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "main", 5, state -> {
			RelicWarden w = state.getAnimatable();
			if (w.isDeadOrDying()) return state.setAndContinue(DEATH);
			return switch (w.pose()) {
				case POSE_CHARGE -> state.setAndContinue(CHARGE);
				case POSE_BEAM -> state.setAndContinue(BEAM);
				case POSE_KNEEL -> state.setAndContinue(KNEEL);
				default -> state.setAndContinue(state.isMoving() ? WALK : IDLE);
			};
		}));
		// the last stand: the core hangs out of the chest
		controllers.add(new AnimationController<>(this, "exposed", 5, state ->
				state.getAnimatable().phase() == LAST_STAND && state.getAnimatable().pose() != POSE_KNEEL ? state.setAndContinue(EXPOSED) : PlayState.STOP));
		AnimationController<RelicWarden> action = new AnimationController<>(this, "action", 2, state -> PlayState.STOP);
		for (String a : new String[]{"slam", "sweep", "cast", "summon", "stagger", "roar", "rise"}) {
			action.triggerableAnim(a, RawAnimation.begin().thenPlay("animation.relic_warden." + a));
		}
		controllers.add(action);
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}

	static RelicWarden create(ServerLevel level, Rifts.Run run, Vec3 at) {
		RelicWarden w = AuraPoints.RELIC_WARDEN.get().create(level);
		if (w == null) return null;
		w.run = run;
		w.setAura(run.aura);
		w.moveTo(at.x, at.y, at.z, 180f, 0f);
		w.setPersistenceRequired();
		w.addTag(Rifts.MOB_TAG);
		return w;
	}

	// ------------------------------------------------------------ dev tests

	/** Puts it straight into a phase at a share of its health (dev tests). */
	public void devPhase(int phase, float hpShare) {
		if (!(level() instanceof ServerLevel level)) return;
		setHealth(getMaxHealth() * hpShare);
		if (phase == SHIELDED) shield(level);
		else if (phase == LAST_STAND) {
			entityData.set(PHASE, OVERDRIVE);
			lastStand(level);
		} else setPhase(level, phase);
	}

	/** Sets health ignoring the phase floor (dev tests). */
	public void devSetHealth(float share) {
		ready = false;
		setHealth(getMaxHealth() * share);
		ready = true;
	}

	public void devAiStep() {
		awake = true;
		customServerAiStep();
	}

	public int devPylons() {
		return pylons.size();
	}

	/** Starts a parryable slam (yellow) whatever the dice say; true if the windup is on. */
	public boolean devStartParryable() {
		if (!(level() instanceof ServerLevel level)) return false;
		windup = -1;
		staggerTicks = 0;
		awake = true;
		triggerAnim("action", "slam");
		windUp(16, true, () -> {
		});
		return windup >= 0 && parryable;
	}

	/** Starts a parryable slam; true if the windup is on. */
	public boolean devStartSlam() {
		if (!(level() instanceof ServerLevel level)) return false;
		windup = -1;
		staggerTicks = 0;
		slam(level);
		return windup >= 0 && parryable;
	}

	public void devSetWindup(int ticks) {
		windup = ticks;
	}

	public boolean devStaggered() {
		return staggerTicks > 0;
	}

	public int devKneel() {
		return kneelTicks;
	}

	public void devSkipKneel() {
		kneelTicks = 1;
	}

	public int devDesperation() {
		return desperation;
	}
}
