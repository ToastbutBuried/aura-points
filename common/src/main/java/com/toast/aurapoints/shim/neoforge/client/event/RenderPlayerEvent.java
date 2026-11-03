package com.toast.aurapoints.shim.neoforge.client.event;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.player.Player;

/** Around drawing one player (Pre can be canceled: not drawn). */
public abstract class RenderPlayerEvent extends com.toast.aurapoints.shim.bus.api.Event {
	private final Player entity;
	private final PlayerRenderer renderer;
	private final float partialTick;
	private final PoseStack poseStack;
	private final MultiBufferSource multiBufferSource;
	private final int packedLight;

	protected RenderPlayerEvent(Player entity, PlayerRenderer renderer, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
		this.entity = entity;
		this.renderer = renderer;
		this.partialTick = partialTick;
		this.poseStack = poseStack;
		this.multiBufferSource = buffers;
		this.packedLight = light;
	}

	public Player getEntity() {
		return entity;
	}

	public PlayerRenderer getRenderer() {
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

	public static class Pre extends RenderPlayerEvent implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
		public Pre(Player entity, PlayerRenderer renderer, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
			super(entity, renderer, partialTick, poseStack, buffers, light);
		}
	}

	public static class Post extends RenderPlayerEvent {
		public Post(Player entity, PlayerRenderer renderer, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
			super(entity, renderer, partialTick, poseStack, buffers, light);
		}
	}
}
