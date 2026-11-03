package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.phys.Vec3;

/**
 * The Air Bending ball: a real wind charge. While held it hangs in front of the bender
 * (AirBending moves it every tick and it does nothing by itself); once fired it flies like a
 * wind charge, and where it hits - or at AIR_BENDING_RANGE - it bursts with AirBending's
 * blast instead of the vanilla one. The circling wind specks are drawn from the server, at
 * the size it was charged to.
 *
 * Like SuperWindCharge, it is a vanilla wind charge on the client (no renderer or
 * registration needed). It is never saved, so it can't come back as a stray charge.
 */
public class AirBendingCharge extends WindCharge {

	private final ServerPlayer bender;
	boolean held = true;
	/** Radius of the circling specks, and the blast strength (READY_FACTOR..1), fixed when fired. */
	double size;
	double strength;
	private Vec3 firedFrom;
	private int flyTicks;
	private boolean burst;

	public AirBendingCharge(ServerPlayer bender, Vec3 pos) {
		super(bender, bender.level(), pos.x, pos.y, pos.z);
		this.bender = bender;
		this.setDeltaMovement(Vec3.ZERO);
	}

	/** Held: follows the bender's look. hasImpulse makes the server send the new position every tick. */
	void hold(Vec3 pos) {
		this.setPos(pos.x, pos.y, pos.z);
		this.setDeltaMovement(Vec3.ZERO);
		this.hasImpulse = true;
	}

	void fire(Vec3 dir, double speed, double strength) {
		this.held = false;
		this.strength = strength;
		this.firedFrom = this.position();
		this.setDeltaMovement(dir.scale(speed));
		this.hasImpulse = true;
	}

	@Override
	public void tick() {
		if (held) return; // AirBending moves it; no flying, no hits
		super.tick();
		if (!(this.level() instanceof ServerLevel level) || this.isRemoved()) return;
		AirBending.orbit(level, this.getBoundingBox().getCenter(), size, this.tickCount, true);
		if (++flyTicks > 200 || this.position().distanceTo(firedFrom) >= AuraConfig.AIR_BENDING_RANGE) {
			explode(this.position());
			this.discard();
		}
	}

	@Override
	protected void explode(Vec3 pos) {
		if (burst || held || !(this.level() instanceof ServerLevel level)) return;
		burst = true;
		Vec3 dir = this.getDeltaMovement().lengthSqr() > 1.0e-6 ? this.getDeltaMovement().normalize() : Vec3.ZERO;
		AirBending.blast(bender, level, pos, strength, dir);
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}
}
