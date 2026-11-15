package com.toast.aurapoints.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CLIENT ONLY. The awakening ritual's camera (client/RitualCutscene): while it runs, the camera goes where the cutscene
 * says after the game set it up, and counts as detached so the player's own body is drawn.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "setup", at = @At("TAIL"))
	private void aurapoints$ritualCamera(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
		double[] pose = com.toast.aurapoints.client.RitualCutscene.pose(partialTick);
		if (pose == null) return;
		setPosition(pose[0], pose[1], pose[2]);
		setRotation((float) pose[3], (float) pose[4]);
		this.detached = true;
	}
}
