package com.toast.aurapoints.arena;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The duel intro's camera: an invisible, untouchable point the duellers watch through (spectator
 * camera) while the server flies it round the ring. Never saved - it's gone with the intro.
 */
public class ArenaCamera extends Entity {

	public ArenaCamera(EntityType<? extends ArenaCamera> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
		setInvisible(true);
	}

	/** Moves to {@code at}, looking at {@code focus}. */
	void aim(Vec3 at, Vec3 focus) {
		double dx = focus.x - at.x, dy = focus.y - at.y, dz = focus.z - at.z;
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		moveTo(at.x, at.y, at.z, yaw, pitch);
		setYHeadRot(yaw);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}
}
