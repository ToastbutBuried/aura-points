package com.toast.aurapoints.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.toast.aurapoints.client.AuraTitles;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** CLIENT ONLY. Titles, subtitles and the action bar never run off the screen; the mod's own titles are sized to it (AuraTitles). */
@Mixin(Gui.class)
public abstract class GuiTitleMixin {

	@Shadow
	@Nullable
	protected Component title;
	@Shadow
	@Nullable
	protected Component subtitle;
	@Shadow
	@Nullable
	protected Component overlayMessageString;

	@Shadow
	public abstract Font getFont();

	@WrapOperation(method = "renderTitle", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
	private void aurapoints$fitTitle(PoseStack pose, float x, float y, float z, Operation<Void> original, @Local(argsOnly = true) GuiGraphics g) {
		float s = AuraTitles.scale(getFont(), x > 3f ? title : subtitle, title, x, g.guiWidth(), g.guiHeight());
		// the subtitle keeps its place below the title when only it shrinks
		if (x < 3f && s < x) pose.translate(0f, 5f * (x - s), 0f);
		original.call(pose, s, s, s);
	}

	@WrapOperation(method = "renderOverlayMessage", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
	private void aurapoints$fitActionBar(PoseStack pose, float x, float y, float z, Operation<Void> original, @Local(argsOnly = true) GuiGraphics g) {
		original.call(pose, x, y, z);
		if (overlayMessageString == null) return;
		float s = AuraTitles.fit(getFont(), overlayMessageString, 1f, g.guiWidth());
		if (s < 1f) pose.scale(s, s, 1f);
	}
}
