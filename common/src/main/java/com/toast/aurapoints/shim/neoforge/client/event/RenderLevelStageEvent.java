package com.toast.aurapoints.shim.neoforge.client.event;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;

/** One stage of drawing the world. The pose stack is already in camera space. */
public class RenderLevelStageEvent extends com.toast.aurapoints.shim.bus.api.Event {
	public enum Stage {
		AFTER_SKY,
		AFTER_SOLID_BLOCKS,
		AFTER_ENTITIES,
		AFTER_BLOCK_ENTITIES,
		AFTER_TRANSLUCENT_BLOCKS,
		AFTER_PARTICLES,
		AFTER_WEATHER,
		AFTER_LEVEL
	}

	private final Stage stage;
	private final LevelRenderer levelRenderer;
	private final PoseStack poseStack;
	private final Matrix4f modelViewMatrix;
	private final Matrix4f projectionMatrix;
	private final DeltaTracker partialTick;
	private final Camera camera;

	public RenderLevelStageEvent(Stage stage, LevelRenderer levelRenderer, PoseStack poseStack, Matrix4f modelViewMatrix,
			Matrix4f projectionMatrix, DeltaTracker partialTick, Camera camera) {
		this.stage = stage;
		this.levelRenderer = levelRenderer;
		this.poseStack = poseStack;
		this.modelViewMatrix = modelViewMatrix;
		this.projectionMatrix = projectionMatrix;
		this.partialTick = partialTick;
		this.camera = camera;
	}

	public Stage getStage() {
		return stage;
	}

	public LevelRenderer getLevelRenderer() {
		return levelRenderer;
	}

	public PoseStack getPoseStack() {
		return poseStack;
	}

	public Matrix4f getModelViewMatrix() {
		return modelViewMatrix;
	}

	public Matrix4f getProjectionMatrix() {
		return projectionMatrix;
	}

	public DeltaTracker getPartialTick() {
		return partialTick;
	}

	public Camera getCamera() {
		return camera;
	}
}
