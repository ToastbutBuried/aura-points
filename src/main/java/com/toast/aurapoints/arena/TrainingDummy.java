package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraLabel;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A straw training dummy in the arena's training grounds. It never moves (always back on its spot, no
 * knockback) and never dies: a killing blow shows "KO!" and it's back at full health, and a few seconds
 * after the last hit it heals up anyway. Every hit pops a damage number in the attacker's aura color
 * ("✦" = an ability hit); its name line shows the damage per second and what's on it right now.
 */
public class TrainingDummy extends Mob {

	public static final String NUMBER_TAG = "aurapoints_damage_number";

	private Vec3 home;
	private float homeYaw;
	/** game time of the first and last hit of the current streak (-1 = none), and the damage dealt in it */
	private long firstHit = -1, lastHit = -1;
	private float dealt;
	/** A moving dummy: slides PATROL_RANGE blocks either way along x from home; speed 0 = still, 1 = slow, 2 = fast. */
	private boolean patrol;
	private int patrolSpeed;
	public static final double PATROL_RANGE = 20.0;

	/** The last hit, for the wobble every client draws (client/TrainingDummyRenderer): a counter, how hard (radians), which way (yaw it's pushed toward). */
	private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> HIT_SEQ =
			net.minecraft.network.syncher.SynchedEntityData.defineId(TrainingDummy.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
	private static final net.minecraft.network.syncher.EntityDataAccessor<Float> HIT_POWER =
			net.minecraft.network.syncher.SynchedEntityData.defineId(TrainingDummy.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
	private static final net.minecraft.network.syncher.EntityDataAccessor<Float> HIT_YAW =
			net.minecraft.network.syncher.SynchedEntityData.defineId(TrainingDummy.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
	/** CLIENT: tickCount of the last hit (-1 = none yet). */
	private int hitTick = -1;

	public TrainingDummy(EntityType<? extends Mob> type, Level level) {
		super(type, level);
		setNoAi(true);
		setPersistenceRequired();
		setCustomNameVisible(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 100.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.MOVEMENT_SPEED, 0.0);
	}

	/** Puts a dummy on {@code pos}, facing {@code yaw}. */
	public static void spawn(ServerLevel level, EntityType<TrainingDummy> type, Vec3 pos, float yaw) {
		spawn(level, type, pos, yaw, false);
	}

	public static void spawn(ServerLevel level, EntityType<TrainingDummy> type, Vec3 pos, float yaw, boolean patrol) {
		TrainingDummy dummy = type.create(level);
		if (dummy == null) return;
		dummy.patrol = patrol;
		dummy.moveTo(pos.x, pos.y, pos.z, yaw, 0f);
		dummy.setYHeadRot(yaw);
		dummy.setYBodyRot(yaw);
		dummy.home = pos;
		dummy.homeYaw = yaw;
		dummy.setCustomName(idleName(dummy));
		ArenaBuilder.claim(dummy);
		level.addFreshEntity(dummy);
	}

	private static Component idleName(TrainingDummy dummy) {
		MutableComponent name = Component.literal(dummy.patrol ? "Moving Dummy" : "Training Dummy").withStyle(ChatFormatting.GOLD);
		String armor = TrainingHall.armorName(dummy);
		if (armor != null) name.append(tag(armor, ChatFormatting.GRAY));
		return name;
	}

	@Override
	protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(HIT_SEQ, 0);
		builder.define(HIT_POWER, 0f);
		builder.define(HIT_YAW, 0f);
	}

	@Override
	public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (HIT_SEQ.equals(key) && level().isClientSide) hitTick = tickCount;
	}

	/**
	 * CLIENT: the wobble of the last hit right now, in radians (0 = upright): it snaps over away from the hit and
	 * springs back and forth, dying out in about two seconds. {@code lag} ticks behind, for parts that follow late.
	 */
	public float wobble(float partialTick, float lag) {
		if (hitTick < 0) return 0f;
		float t = tickCount - hitTick + partialTick - lag;
		if (t < 0 || t > 45) return 0f;
		return entityData.get(HIT_POWER) * (float) Math.exp(-t / 8.0) * net.minecraft.util.Mth.sin(0.85f * t + 0.6f);
	}

	/** CLIENT: the direction the last hit pushed it (a yaw in degrees, like the entity's). */
	public float hitYaw() {
		return entityData.get(HIT_YAW);
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide) return;
		if (home == null) {
			home = position();
			homeYaw = getYRot();
		}
		// stays put (or on its track) whatever throws, pulls or launches it
		Vec3 at = home;
		if (patrol && patrolSpeed > 0) {
			double period = patrolSpeed == 1 ? 200.0 : 90.0;
			at = home.add(Math.sin(level().getGameTime() * 2 * Math.PI / period) * PATROL_RANGE, 0, 0);
		}
		if (position().distanceToSqr(at) > 1.0e-4) setPos(at.x, at.y, at.z);
		setDeltaMovement(Vec3.ZERO);
		setYRot(homeYaw);
		setYBodyRot(homeYaw);
		setYHeadRot(homeYaw);
		long now = level().getGameTime();
		int reset = Math.max(1, com.toast.aurapoints.config.AuraConfig.TRAINING_DUMMY_RESET_SECONDS) * 20;
		if (lastHit >= 0 && now - lastHit > reset) resetNow();
		if (tickCount % 5 == 0) setCustomName(lastHit < 0 && getActiveEffects().isEmpty() && !isOnFire() ? idleName(this) : statusName(now));
	}

	/** Full health, nothing on it, the damage count from zero. */
	public void resetNow() {
		if (level() instanceof ServerLevel level && (lastHit >= 0 || getHealth() < getMaxHealth())) {
			// a puff of straw as it's patched up
			com.toast.aurapoints.ability.AuraFx.particles(level, net.minecraft.core.particles.ParticleTypes.POOF, getX(), getY() + 1, getZ(), 12, 0.3, 0.6, 0.3, 0.02);
		}
		lastHit = firstHit = -1;
		dealt = 0;
		setHealth(getMaxHealth());
		removeAllEffects();
		clearFire();
		com.toast.aurapoints.ability.Rooted.unlock(this);
		setCustomName(idleName(this));
	}

	boolean patrols() {
		return patrol;
	}

	int patrolSpeed() {
		return patrolSpeed;
	}

	void setPatrolSpeed(int speed) {
		patrolSpeed = speed;
	}

	/** "DPS 12.4 · 37 dmg · Rooted · Slowness · Burning" */
	private MutableComponent statusName(long now) {
		MutableComponent name = Component.empty();
		if (lastHit >= 0) {
			float seconds = Math.max(1f, (now - firstHit) / 20f);
			name.append(Component.literal(String.format(java.util.Locale.ROOT, "DPS %.1f", dealt / seconds)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
					.append(Component.literal(String.format(java.util.Locale.ROOT, " · %.0f dmg", dealt)).withStyle(ChatFormatting.YELLOW));
		} else {
			name.append(idleName(this));
		}
		String armor = TrainingHall.armorName(this);
		if (armor != null && lastHit >= 0) name.append(tag(armor, ChatFormatting.GRAY));
		if (com.toast.aurapoints.ability.Rooted.isRooted(this)) name.append(tag("Rooted", ChatFormatting.GREEN));
		if (com.toast.aurapoints.ability.IceSpike.isFrozen(this)) name.append(tag("Frozen", ChatFormatting.AQUA));
		if (com.toast.aurapoints.ability.Entanglement.isEntangled(this)) name.append(tag("Entangled", ChatFormatting.DARK_GREEN));
		if (isOnFire()) name.append(tag("Burning", ChatFormatting.RED));
		for (MobEffectInstance effect : getActiveEffects()) {
			name.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.translatable(effect.getEffect().value().getDescriptionId()).withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		return name;
	}

	private static Component tag(String text, ChatFormatting color) {
		return Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY).append(Component.literal(text).withStyle(color));
	}

	/** Never dies: a knockout, then straight back to full health. */
	@Override
	public void die(DamageSource source) {
		setHealth(getMaxHealth());
		if (level() instanceof ServerLevel level) {
			number(level, Component.literal("KO!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			com.toast.aurapoints.ability.AuraFx.particles(level, net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING, getX(), getY() + 1.2, getZ(), 40, 0.4, 0.7, 0.4, 0.4);
			level.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.TOTEM_USE, net.minecraft.sounds.SoundSource.NEUTRAL, 0.5f, 1.4f);
		}
	}

	@Override
	public void knockback(double strength, double x, double z) {
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(Entity entity) {
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean isPersistenceRequired() {
		return true;
	}

	/** From the after-damage hook: the number pops up and the streak counts it. */
	public void onDamaged(DamageSource source, float amount, boolean abilityHit) {
		if (!(level() instanceof ServerLevel level) || amount <= 0) return;
		long now = level.getGameTime();
		if (lastHit < 0) {
			firstHit = now;
			dealt = 0;
		}
		lastHit = now;
		dealt += amount;
		// the wobble: harder hits swing further, away from where the hit came from
		Vec3 from = source.getSourcePosition();
		if (from == null && source.getEntity() != null) from = source.getEntity().position();
		Vec3 push = from == null ? Vec3.directionFromRotation(0, getYRot()).scale(-1) : position().subtract(from);
		entityData.set(HIT_POWER, net.minecraft.util.Mth.clamp(0.12f + amount * 0.025f, 0.12f, 0.5f));
		entityData.set(HIT_YAW, (float) Math.toDegrees(Math.atan2(-push.x, push.z)));
		entityData.set(HIT_SEQ, entityData.get(HIT_SEQ) + 1);
		TrainingHall.onDummyHit(source, amount, now);
		int color = 0xDDDDDD;
		if (source.getEntity() instanceof ServerPlayer attacker && attacker.getServer() != null) {
			AuraType aura = AuraState.get(attacker.getServer()).getOrCreate(attacker.getUUID()).aura;
			if (aura != null) color = AuraLabel.accent(aura);
		}
		String text = String.format(java.util.Locale.ROOT, amount >= 10 ? "%.0f" : "%.1f", amount) + (abilityHit ? " ✦" : "");
		int rgb = color;
		number(level, Component.literal(text).withStyle(s -> s.withColor(rgb).withBold(abilityHit)));
		// sparks off the straw, a heavier thud for an ability
		com.toast.aurapoints.ability.AuraFx.particles(level, abilityHit ? net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT : net.minecraft.core.particles.ParticleTypes.CRIT,
				getX(), getY() + 1.3, getZ(), abilityHit ? 14 : 8, 0.3, 0.4, 0.3, 0.25);
		level.playSound(null, getX(), getY(), getZ(), abilityHit ? net.minecraft.sounds.SoundEvents.ANVIL_LAND : net.minecraft.sounds.SoundEvents.WOOL_HIT,
				net.minecraft.sounds.SoundSource.NEUTRAL, abilityHit ? 0.25f : 0.8f, abilityHit ? 1.8f : 0.9f);
	}

	// ---------------------------------------------------------------
	// Damage numbers: text displays that float up for a second
	// ---------------------------------------------------------------

	private record Number(Display.TextDisplay display, int[] age) {
	}

	private static final List<Number> NUMBERS = new ArrayList<>();
	private static final int NUMBER_TICKS = 20;

	private void number(ServerLevel level, Component text) {
		Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
		if (display == null) return;
		CompoundTag tag = new CompoundTag();
		tag.putString("text", Component.Serializer.toJson(text, level.registryAccess()));
		tag.putString("billboard", "center");
		tag.putString("alignment", "center"); // read even when missing - left out, every number logs an error
		tag.putInt("background", 0);
		tag.putBoolean("shadow", true);
		tag.putInt("teleport_duration", 2);
		display.load(tag);
		// beside the head, left or right of it, so the numbers don't cover the name line above
		double side = (getRandom().nextBoolean() ? 1 : -1) * (0.55 + getRandom().nextDouble() * 0.35);
		double yaw = Math.toRadians(homeYaw);
		display.setPos(getX() + Math.cos(yaw) * side, getY() + getBbHeight() * 0.7 + getRandom().nextDouble() * 0.2, getZ() + Math.sin(yaw) * side);
		display.addTag(NUMBER_TAG);
		level.addFreshEntity(display);
		NUMBERS.add(new Number(display, new int[]{0}));
	}

	static void tickNumbers() {
		for (Iterator<Number> it = NUMBERS.iterator(); it.hasNext(); ) {
			Number n = it.next();
			if (n.display.isRemoved() || ++n.age[0] >= NUMBER_TICKS) {
				n.display.discard();
				it.remove();
				continue;
			}
			n.display.setPos(n.display.getX(), n.display.getY() + 0.045, n.display.getZ());
		}
	}

	static void clearNumbers() {
		for (Number n : NUMBERS) n.display.discard();
		NUMBERS.clear();
	}

	/** After-damage hook entry point. */
	public static void onDamaged(LivingEntity victim, DamageSource source, float amount) {
		if (victim instanceof TrainingDummy dummy) dummy.onDamaged(source, amount, Arena.wasAbilityHit(victim));
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		if (home != null) {
			tag.putDouble("HomeX", home.x);
			tag.putDouble("HomeY", home.y);
			tag.putDouble("HomeZ", home.z);
			tag.putFloat("HomeYaw", homeYaw);
		}
		tag.putBoolean("Patrol", patrol);
		tag.putInt("PatrolSpeed", patrolSpeed);
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		if (tag.contains("HomeX")) {
			home = new Vec3(tag.getDouble("HomeX"), tag.getDouble("HomeY"), tag.getDouble("HomeZ"));
			homeYaw = tag.getFloat("HomeYaw");
		}
		patrol = tag.getBoolean("Patrol");
		patrolSpeed = tag.getInt("PatrolSpeed");
	}
}
