package com.toast.aurapoints.shim.neoforge.client.event;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class ViewportEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static class ComputeCameraAngles extends ViewportEvent {
		private net.minecraft.client.Camera camera;
		private double partialTick;
		private float yaw;
		private float pitch;
		private float roll;

		public ComputeCameraAngles(net.minecraft.client.Camera camera, double partialTick, float yaw, float pitch, float roll) {
			this.camera = camera;
			this.partialTick = partialTick;
			this.yaw = yaw;
			this.pitch = pitch;
			this.roll = roll;
		}

		public net.minecraft.client.Camera getCamera() {
			return camera;
		}

		public double getPartialTick() {
			return partialTick;
		}

		public float getYaw() {
			return yaw;
		}

		public void setYaw(float value) {
			this.yaw = value;
		}

		public float getPitch() {
			return pitch;
		}

		public void setPitch(float value) {
			this.pitch = value;
		}

		public float getRoll() {
			return roll;
		}

		public void setRoll(float value) {
			this.roll = value;
		}
	}

	public static class ComputeFogColor extends ViewportEvent {
		private net.minecraft.client.Camera camera;
		private double partialTick;
		private float red;
		private float green;
		private float blue;

		public ComputeFogColor(net.minecraft.client.Camera camera, double partialTick, float red, float green, float blue) {
			this.camera = camera;
			this.partialTick = partialTick;
			this.red = red;
			this.green = green;
			this.blue = blue;
		}

		public net.minecraft.client.Camera getCamera() {
			return camera;
		}

		public double getPartialTick() {
			return partialTick;
		}

		public float getRed() {
			return red;
		}

		public void setRed(float value) {
			this.red = value;
		}

		public float getGreen() {
			return green;
		}

		public void setGreen(float value) {
			this.green = value;
		}

		public float getBlue() {
			return blue;
		}

		public void setBlue(float value) {
			this.blue = value;
		}
	}

	public static class ComputeFov extends ViewportEvent {
		private net.minecraft.client.Camera camera;
		private double partialTick;
		private double fov;
		private boolean usedConfiguredFov;

		public ComputeFov(net.minecraft.client.Camera camera, double partialTick, double fov, boolean usedConfiguredFov) {
			this.camera = camera;
			this.partialTick = partialTick;
			this.fov = fov;
			this.usedConfiguredFov = usedConfiguredFov;
		}

		public net.minecraft.client.Camera getCamera() {
			return camera;
		}

		public double getPartialTick() {
			return partialTick;
		}

		public double getFOV() {
			return fov;
		}

		public void setFOV(double value) {
			this.fov = value;
		}

		public boolean usedConfiguredFov() {
			return usedConfiguredFov;
		}
	}

	public static class RenderFog extends ViewportEvent implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
		private net.minecraft.client.Camera camera;
		private net.minecraft.client.renderer.FogRenderer.FogMode mode;
		private net.minecraft.world.level.material.FogType type;
		private float farPlaneDistance;
		private float nearPlaneDistance;
		private com.mojang.blaze3d.shaders.FogShape fogShape;

		public RenderFog(net.minecraft.client.Camera camera, net.minecraft.client.renderer.FogRenderer.FogMode mode, net.minecraft.world.level.material.FogType type, float farPlaneDistance, float nearPlaneDistance, com.mojang.blaze3d.shaders.FogShape fogShape) {
			this.camera = camera;
			this.mode = mode;
			this.type = type;
			this.farPlaneDistance = farPlaneDistance;
			this.nearPlaneDistance = nearPlaneDistance;
			this.fogShape = fogShape;
		}

		public net.minecraft.client.Camera getCamera() {
			return camera;
		}

		public net.minecraft.client.renderer.FogRenderer.FogMode getMode() {
			return mode;
		}

		public net.minecraft.world.level.material.FogType getType() {
			return type;
		}

		public float getFarPlaneDistance() {
			return farPlaneDistance;
		}

		public void setFarPlaneDistance(float value) {
			this.farPlaneDistance = value;
		}

		public float getNearPlaneDistance() {
			return nearPlaneDistance;
		}

		public void setNearPlaneDistance(float value) {
			this.nearPlaneDistance = value;
		}

		public com.mojang.blaze3d.shaders.FogShape getFogShape() {
			return fogShape;
		}

		public void setFogShape(com.mojang.blaze3d.shaders.FogShape value) {
			this.fogShape = value;
		}
	}
}
