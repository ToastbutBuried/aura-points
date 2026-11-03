package com.toast.aurapoints.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.client.event.ViewportEvent;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ViewportEvent.ComputeCameraAngles, as NeoForge does it: the first rotation of Camera.setup can be changed (yaw,
 * pitch) and rolled; the mirrored third-person view rolls the other way.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private float partialTickTime;
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	@Final
	private Vector3f forwards;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;
	@Shadow
	@Final
	private static Vector3f FORWARDS;
	@Shadow
	@Final
	private static Vector3f UP;
	@Shadow
	@Final
	private static Vector3f LEFT;
	@Unique
	private float aurapoints$roll;
	@Unique
	private float aurapoints$nextRoll;

	@WrapOperation(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0))
	private void aurapoints$angles(Camera camera, float yaw, float pitch, Operation<Void> original) {
		var event = AuraBus.BUS.post(new ViewportEvent.ComputeCameraAngles(camera, partialTickTime, yaw, pitch, 0f));
		aurapoints$nextRoll = event.getRoll();
		original.call(camera, event.getYaw(), event.getPitch());
	}

	@WrapOperation(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1))
	private void aurapoints$mirrored(Camera camera, float yaw, float pitch, Operation<Void> original) {
		aurapoints$nextRoll = -aurapoints$roll;
		original.call(camera, yaw, pitch);
	}

	/** Every other rotation has no roll (sleeping, other callers), like NeoForge's two-argument setRotation. */
	@Inject(method = "setRotation", at = @At("TAIL"))
	private void aurapoints$roll(float yaw, float pitch, CallbackInfo ci) {
		float roll = aurapoints$nextRoll;
		aurapoints$nextRoll = 0f;
		aurapoints$roll = roll;
		if (roll == 0f) return;
		rotation.rotationYXZ((float) Math.PI - yaw * (float) (Math.PI / 180.0), -pitch * (float) (Math.PI / 180.0), -roll * (float) (Math.PI / 180.0));
		FORWARDS.rotate(rotation, forwards);
		UP.rotate(rotation, up);
		LEFT.rotate(rotation, left);
	}
}
