package com.toast.aurapoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * CLIENT ONLY. A second line over a player's name showing their aura - "❄ Frost ❄", the name in a
 * gradient of the aura's colors. Called from mixin/PlayerRendererMixin exactly when the name itself is
 * drawn, so it follows every rule the name does (range, F1, invisibility, teams); drawn like the name
 * (dimmed through walls, solid in view; not through walls while sneaking), slightly smaller.
 */
public final class AuraNametag {

	private AuraNametag() {
	}

	/** One name line, as the name tag code spaces them. */
	private static final float LINE = 9.0f * 1.15f * 0.025f;
	private static final float SCALE = 0.85f;

	/** "❄ Frost ❄" (shared with /aura info). */
	static Component label(AuraType aura) {
		return com.toast.aurapoints.ability.AuraLabel.of(aura);
	}

	/** Draws the aura line above where the name (and the below-name score, if any) goes. */
	public static void render(AbstractClientPlayer player, PoseStack pose, MultiBufferSource buffers, int light, float partialTick) {
		if (!AuraClientConfig.AURA_NAMETAGS.get()) return;
		AuraType aura = ClientAuraState.playerAura(player.getId());
		if (aura == null) return;
		Minecraft mc = Minecraft.getInstance();
		double distSq = mc.getEntityRenderDispatcher().distanceToSqr(player);
		if (!net.neoforged.neoforge.client.ClientHooks.isNameplateInRenderDistance(player, distSq)) return;
		Vec3 at = player.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, player.getViewYRot(partialTick));
		if (at == null) return;
		// the name sits at +0.5; one line up from it (two if the below-name score pushes it up, as vanilla does within 10 blocks)
		boolean score = distSq < 100.0 && player.getScoreboard().getDisplayObjective(net.minecraft.world.scores.DisplaySlot.BELOW_NAME) != null;
		float up = LINE * (score ? 2 : 1) - LINE * (1 - SCALE) * 0.5f;

		// Aura Mastery: gold stars after the label
		int mastery = ClientAuraState.playerMastery(player.getId());
		Component text = mastery <= 0 ? label(aura) : Component.empty().append(label(aura))
				.append(Component.literal(" " + "✦".repeat(Math.min(5, mastery))).withStyle(net.minecraft.ChatFormatting.GOLD));
		boolean seeThrough = !player.isDiscrete();
		pose.pushPose();
		pose.translate(at.x, at.y + 0.5 + up, at.z);
		pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
		pose.scale(0.025f * SCALE, -0.025f * SCALE, 0.025f * SCALE);
		Matrix4f matrix = pose.last().pose();
		int background = (int) (mc.options.getBackgroundOpacity(0.25f) * 255.0f) << 24;
		Font font = mc.font;
		float x = -font.width(text) / 2f;
		font.drawInBatch(text, x, 0, 0x20FFFFFF, false, matrix, buffers, seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, background, light);
		if (seeThrough) font.drawInBatch(text, x, 0, -1, false, matrix, buffers, Font.DisplayMode.NORMAL, 0, light);
		pose.popPose();
	}
}
