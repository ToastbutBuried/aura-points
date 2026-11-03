package com.toast.aurapoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraState;
import com.toast.aurapoints.network.GraspPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;

/**
 * CLIENT ONLY. The four tentacles on a Resonance player's back, drawn here every frame (smooth, nothing sent over the
 * network but Abyssal Grasp's phases). They grow from the upper back, arc up over the shoulders and reach forward,
 * tips curling slightly down; they turn with the body and a little with the head, and writhe slowly. Near-black at the
 * root, raw purple further out, thinning to a point. Abyssal Grasp bends them: lashing out to the aim, wrapped around
 * whoever is held, flung forward on the throw, pulled back after a miss. Your own show over your shoulders in first
 * person unless firstPersonExtras is off.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class Tentacles {

	private Tentacles() {
	}

	private static final int SEGS = 12;
	private static final Vec3 UP = new Vec3(0, 1, 0);

	@SubscribeEvent
	public static void onRenderLevel(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		var camera = event.getCamera();
		Vec3 cam = camera.getPosition();
		float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
		PoseStack pose = event.getPoseStack();
		MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		boolean drew = false;
		for (AbstractClientPlayer p : mc.level.players()) {
			if (ClientAuraState.playerAura(p.getId()) != AuraType.RESONANCE || p.isInvisible() || p.isSpectator()) continue;
			if (p == mc.player && !camera.isDetached() && !AuraClientConfig.FIRST_PERSON_EXTRAS.get()) continue;
			if (p.position().distanceToSqr(cam) > 96 * 96) continue;
			try {
				draw(mc, p, partial, cam, pose, buffers);
				drew = true;
			} catch (Exception e) {
				AuraPoints.LOGGER.error("Drawing tentacles failed", e);
			}
		}
		if (drew) buffers.endBatch();
	}

	private static void draw(Minecraft mc, AbstractClientPlayer p, float partial, Vec3 cam, PoseStack pose, MultiBufferSource buffers) {
		Vec3 feet = p.getPosition(partial);
		float bodyYaw = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot);
		float headYaw = Mth.rotLerp(partial, p.yHeadRotO, p.yHeadRot);
		float pitch = Mth.lerp(partial, p.xRotO, p.getXRot());
		// they follow the head, but only a little
		Vec3 fwd = Vec3.directionFromRotation(pitch * 0.35f, bodyYaw + 0.35f * Mth.wrapDegrees(headYaw - bodyYaw));
		Vec3 body = Vec3.directionFromRotation(0, bodyYaw);
		Vec3 right = new Vec3(-body.z, 0, body.x);
		double h = p.isCrouching() ? 1.05 : 1.32;
		double time = (mc.level.getGameTime() + partial) / 20.0;
		int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(feet.add(0, h, 0)));
		BlockState bark = AuraPoints.RESONANCE_BARK.get().defaultBlockState();
		BlockState flesh = AuraPoints.RESONANCE_FLESH.get().defaultBlockState();

		ClientAuraState.Grasp grasp = ClientAuraState.grasp(p.getId());
		double progress = grasp == null ? 0 : Math.min(1.0, (System.currentTimeMillis() - grasp.since()) / (50.0 * Math.max(1, grasp.ticks())));
		Entity held = grasp != null && grasp.phase() == GraspPacket.HOLD ? mc.level.getEntity(grasp.target()) : null;

		for (int i = 0; i < 4; i++) {
			int s = i % 2 == 0 ? -1 : 1;
			int k = i / 2; // 0 upper pair, 1 lower pair
			double phase = i * 1.7;
			Vec3 anchor = feet.add(UP.scale(h - k * 0.28)).add(body.scale(-0.2)).add(right.scale(s * 0.14));
			double wob1 = Math.sin(time * 2.2 + phase) * 0.12, wob2 = Math.cos(time * 1.7 + phase * 1.3) * 0.15;
			Vec3 p1 = anchor.add(UP.scale(0.75 - 0.2 * k)).add(body.scale(-0.45)).add(right.scale(s * (0.35 + 0.15 * k)));
			Vec3 p2 = anchor.add(UP.scale(1.05 - 0.4 * k + wob1)).add(fwd.scale(0.35)).add(right.scale(s * (0.62 + 0.2 * k)));
			Vec3 p3 = anchor.add(UP.scale(0.55 - 0.5 * k + wob2)).add(fwd.scale(1.45 - 0.1 * k)).add(right.scale(s * (0.72 + 0.22 * k)));

			// Abyssal Grasp bends them toward a target point T by b (0 = resting shape, 1 = all the way)
			Vec3 target = null;
			double b = 0;
			if (grasp != null) {
				Vec3 aim = new Vec3(grasp.x(), grasp.y(), grasp.z());
				Vec3 spread = right.scale(s * 0.25).add(UP.scale(k == 0 ? 0.2 : -0.2));
				switch (grasp.phase()) {
					case GraspPacket.CHARGE -> {
						target = anchor.add(body.scale(-0.7)).add(UP.scale(1.1)).add(right.scale(s * (0.9 + 0.2 * k)));
						b = ease(progress) * 0.85;
					}
					case GraspPacket.REACH -> {
						target = aim.add(spread);
						b = ease(progress);
					}
					case GraspPacket.MISS -> {
						target = aim.add(spread);
						b = 1 - ease(progress);
					}
					case GraspPacket.HOLD -> {
						if (held != null) {
							Vec3 c = held.getPosition(partial).add(0, held.getBbHeight() * 0.55, 0);
							double a = i * Math.PI / 2 + time * 1.5;
							double r = held.getBbWidth() * 0.75;
							target = c.add(Math.cos(a) * r, k == 0 ? 0.25 : -0.25, Math.sin(a) * r);
							b = 1;
						}
					}
					case GraspPacket.THROW -> {
						target = anchor.add(fwd.scale(3.2)).add(UP.scale(0.4)).add(right.scale(s * 0.4));
						b = progress < 0.3 ? progress / 0.3 : 1 - (progress - 0.3) / 0.7;
					}
					default -> {
					}
				}
			}
			if (target != null && b > 0) {
				Vec3 bend = anchor.add(target).scale(0.5).add(UP.scale(0.5)).add(right.scale(s * 0.35));
				p1 = p1.lerp(anchor.add(UP.scale(0.4)).add(right.scale(s * 0.25)), b);
				p2 = p2.lerp(bend, b);
				p3 = p3.lerp(target, b);
			}

			Vec3 prev = anchor;
			for (int j = 1; j <= SEGS; j++) {
				double t = (double) j / SEGS, u = 1 - t;
				Vec3 q = anchor.scale(u * u * u).add(p1.scale(3 * u * u * t)).add(p2.scale(3 * u * t * t)).add(p3.scale(t * t * t));
				double tPrev = (j - 1.0) / SEGS;
				float w = (float) (0.2 * Math.pow(1 - tPrev, 0.9) + 0.02);
				segment(mc, pose, buffers, j <= 4 ? bark : flesh, prev, q, w, cam, light);
				prev = q;
			}
		}
	}

	private static double ease(double t) {
		return 1 - (1 - t) * (1 - t);
	}

	/** One piece of a tentacle: the block stretched from a to b, w thick, a little longer so the joints close. */
	private static void segment(Minecraft mc, PoseStack pose, MultiBufferSource buffers, BlockState block, Vec3 a, Vec3 b, float w, Vec3 cam, int light) {
		Vec3 d = b.subtract(a);
		float len = (float) d.length();
		if (len < 1.0e-4) return;
		pose.pushPose();
		pose.translate(a.x - cam.x, a.y - cam.y, a.z - cam.z);
		pose.mulPose(new Quaternionf().rotationTo(0f, 1f, 0f, (float) d.x, (float) d.y, (float) d.z));
		pose.scale(w, len * 1.15f, w);
		pose.translate(-0.5, -0.07, -0.5);
		mc.getBlockRenderer().renderSingleBlock(block, pose, buffers, light, OverlayTexture.NO_OVERLAY);
		pose.popPose();
	}
}
