package com.toast.aurapoints.shim.neoforge.client.event;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;

/** Around drawing one living entity (Pre can be canceled: not drawn). */
public abstract class RenderLivingEvent<T extends LivingEntity, M extends EntityModel<T>> extends com.toast.aurapoints.shim.bus.api.Event {
	private final T entity;
	private final LivingEntityRenderer<T, M> renderer;
	private final float partialTick;
	private final PoseStack poseStack;
	private final MultiBufferSource multiBufferSource;
	private final int packedLight;

	protected RenderLivingEvent(T entity, LivingEntityRenderer<T, M> renderer, float partialTick, PoseStack poseStack,
			MultiBufferSource multiBufferSource, int packedLight) {
		this.entity = entity;
		this.renderer = renderer;
		this.partialTick = partialTick;
		this.poseStack = poseStack;
		this.multiBufferSource = multiBufferSource;
		this.packedLight = packedLight;
	}

	public T getEntity() {
		return entity;
	}

	public LivingEntityRenderer<T, M> getRenderer() {
		return renderer;
	}

	public float getPartialTick() {
		return partialTick;
	}

	public PoseStack getPoseStack() {
		return poseStack;
	}

	public MultiBufferSource getMultiBufferSource() {
		return multiBufferSource;
	}

	public int getPackedLight() {
		return packedLight;
	}

	public static class Pre<T extends LivingEntity, M extends EntityModel<T>> extends RenderLivingEvent<T, M> implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
		public Pre(T entity, LivingEntityRenderer<T, M> renderer, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
			super(entity, renderer, partialTick, poseStack, buffers, light);
		}
	}

	public static class Post<T extends LivingEntity, M extends EntityModel<T>> extends RenderLivingEvent<T, M> {
		public Post(T entity, LivingEntityRenderer<T, M> renderer, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
			super(entity, renderer, partialTick, poseStack, buffers, light);
		}
	}
}
