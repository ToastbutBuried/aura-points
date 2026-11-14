package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Fire aura's Fireball: a blaze fireball (sets the target on fire, FIREBALL_DAMAGE damage
 * however far it flew). Where it hits, a few fires
 * break out around the impact (FIREBALL_FIRES within FIREBALL_FIRE_RADIUS). Still a vanilla small fireball on the
 * client, so no renderer or registration is needed.
 */
public class AuraFireball extends SmallFireball {

	/** Blue Flame: the entity this fireball already passed through (it pierces the first target only). */
	private Entity pierced = null;
	private boolean didPierce = false;
	/** Set at launch (boost): a Blue Flame fireball keeps piercing even if its owner switches branch mid-flight. */
	private boolean canPierce = false;
	/** A cast's tuning (tune): the share of FIREBALL_DAMAGE this one deals, and the Meteor's blast radius (0 = none). */
	private float damageFactor = 1f;
	private double blastRadius = 0;

	public AuraFireball(Level level, LivingEntity owner, Vec3 movement) {
		super(level, owner, movement);
	}

	/** Spread Shot / Meteor (Casts): damage share, launch speed factor, and a blast radius that burns and hurts everything around the impact. */
	public void tune(float damageFactor, double speedFactor, double blastRadius) {
		this.damageFactor = damageFactor;
		this.blastRadius = blastRadius;
		if (speedFactor > 0 && speedFactor != 1.0) {
			this.accelerationPower *= speedFactor;
			setDeltaMovement(getDeltaMovement().scale(speedFactor));
		}
	}

	/** Blue Flame flies faster: scales the launch speed and the push that keeps it going. */
	public void boost(ServerPlayer owner) {
		if (!Branch.is(owner, Branch.BLUE_FLAME)) return;
		canPierce = true;
		if (AuraConfig.FIRE_BLUE_FLAME_FIREBALL_SPEED <= 0) return;
		double f = AuraConfig.FIRE_BLUE_FLAME_FIREBALL_SPEED;
		this.accelerationPower *= f;
		setDeltaMovement(getDeltaMovement().scale(f));
	}

	private boolean piercing() {
		return AuraConfig.FIRE_BLUE_FLAME_PIERCE && canPierce && !didPierce;
	}

	/** Vanilla discards the fireball on any hit; Blue Flame lets the first entity hit pass and flies on. */
	@Override
	protected void onHit(net.minecraft.world.phys.HitResult result) {
		if (result instanceof EntityHitResult hit && piercing() && !this.level().isClientSide) {
			didPierce = true;
			pierced = hit.getEntity();
			onHitEntity(hit);
			return;
		}
		super.onHit(result);
	}

	@Override
	protected boolean canHitEntity(Entity entity) {
		return entity != pierced && super.canHitEntity(entity);
	}

	/**
	 * Vanilla's small-fireball hit (5 s burning, fire is undone if the hit is blocked), with
	 * FIREBALL_DAMAGE damage. Plus the Aura Trials counter for the shooter.
	 */
	@Override
	protected void onHitEntity(EntityHitResult hit) {
		// Mark first: if this very hit kills, the Burn Notice stunt must already see it.
		if (getOwner() instanceof ServerPlayer owner && hit.getEntity() instanceof LivingEntity living) {
			AuraStats.add(owner, AuraStats.FIREBALL_HITS, 1);
			AuraStunts.fireHit(owner, living);
		}
		if (!(this.level() instanceof ServerLevel level)) return;
		Entity target = hit.getEntity();
		int fireBefore = target.getRemainingFireTicks();
		target.igniteForSeconds(5.0F);
		DamageSource source = damageSources().fireball(this, getOwner());
		if (!target.hurt(source, AuraAbilities.vsMob(target, AuraConfig.FIREBALL_DAMAGE * damageFactor))) {
			target.setRemainingFireTicks(fireBefore);
		} else {
			EnchantmentHelper.doPostAttackEffects(level, target, source);
		}
		ignite(level, target.position().add(0, 0.5, 0));
	}

	@Override
	protected void onHitBlock(BlockHitResult hit) {
		// No super call: vanilla lights only the block it hit; this scatters FIREBALL_FIRES around instead.
		if (this.level() instanceof ServerLevel level) {
			Vec3 p = hit.getLocation();
			AuraFx.particles(level, ParticleTypes.FLAME, p.x, p.y, p.z, 12, 0.2, 0.2, 0.2, 0.04);
			AuraFx.particles(level, ParticleTypes.SMOKE, p.x, p.y, p.z, 6, 0.2, 0.2, 0.2, 0.02);
			ignite(level, Vec3.atCenterOf(hit.getBlockPos().relative(hit.getDirection())));
		}
	}

	/** Up to FIREBALL_FIRES fires at random spots within FIREBALL_FIRE_RADIUS of the impact. */
	private void ignite(ServerLevel level, Vec3 at) {
		ServerPlayer owner = getOwner() instanceof ServerPlayer p ? p : null;
		if (owner != null) MagmaPools.add(owner, new Vec3(at.x, Math.floor(at.y), at.z), AuraConfig.FIRE_MAGMA_POOL_RADIUS);
		if (owner != null) Combos.zone(owner, Combos.Kind.FIRE, at, AuraConfig.COMBO_FIRE_ZONE_RADIUS, AuraConfig.COMBO_FIRE_ZONE_TICKS, null, true);
		if (AuraFire.scatter(level, owner, at, AuraConfig.FIREBALL_FIRE_RADIUS, AuraConfig.FIREBALL_FIRES) > 0) {
			level.playSound(null, at.x, at.y, at.z, AuraSounds.FIRECHARGE_USE.value(), net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 0.9f);
		}
		if (blastRadius > 0 && owner != null) blast(level, owner, at);
	}

	/** The Meteor's impact: everything within blastRadius burns and takes half the fireball's damage; more fires. */
	private void blast(ServerLevel level, ServerPlayer owner, Vec3 at) {
		float damage = AuraConfig.FIREBALL_DAMAGE * damageFactor * 0.5f;
		for (LivingEntity e : Handhelds.enemies(owner, level, at, blastRadius)) {
			e.igniteForSeconds(4.0f);
			AuraStunts.fireHit(owner, e);
			e.hurt(level.damageSources().playerAttack(owner), AuraAbilities.vsMob(e, damage));
			e.knockback(0.6, at.x - e.getX(), at.z - e.getZ());
		}
		AuraFx.particles(level, ParticleTypes.EXPLOSION, at.x, at.y, at.z, 2, 0.3, 0.3, 0.3, 0.0);
		AuraFx.particles(level, ParticleTypes.FLAME, at.x, at.y, at.z, 60, blastRadius * 0.5, 0.4, blastRadius * 0.5, 0.15);
		AuraFx.particles(level, ParticleTypes.LAVA, at.x, at.y, at.z, 12, blastRadius * 0.4, 0.3, blastRadius * 0.4, 0.0);
		AuraFx.ring(level, at.x, at.y + 0.1, at.z, blastRadius, AuraFx.dust(AuraType.FIRE.rgb, 1.6f), (int) (blastRadius * 8));
		level.playSound(null, at.x, at.y, at.z, AuraSounds.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1.1f, 0.7f);
		AuraFire.scatter(level, owner, at, blastRadius, AuraConfig.FIREBALL_FIRES);
	}

	/** Server side: a thicker ember trail than the vanilla smoke. */
	@Override
	public void tick() {
		Vec3 before = position();
		super.tick();
		if (this.level() instanceof ServerLevel && this.isAlive() && getOwner() instanceof ServerPlayer owner
				&& Combos.pass(owner, before, position(), 0.3, null)) {
			discard();
			return;
		}
		if (this.level() instanceof ServerLevel level && this.isAlive()) {
			AuraFx.particles(level, ParticleTypes.FLAME, getX(), getY(), getZ(), 2, 0.05, 0.05, 0.05, 0.01);
			AuraFx.particles(level, AuraFx.dust(AuraType.FIRE.rgb, 1.2f), getX(), getY(), getZ(), 1, 0.1, 0.1, 0.1, 0.0);
			if (blastRadius > 0) {
				// a Meteor: fatter, with embers falling off
				AuraFx.particles(level, ParticleTypes.FLAME, getX(), getY(), getZ(), 4, 0.2, 0.2, 0.2, 0.02);
				AuraFx.particles(level, ParticleTypes.LAVA, getX(), getY(), getZ(), 1, 0.1, 0.1, 0.1, 0.0);
			}
		}
	}
}
