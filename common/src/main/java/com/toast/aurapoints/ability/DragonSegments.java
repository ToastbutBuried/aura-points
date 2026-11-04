package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.mixin.DisplayAccessor;
import com.toast.aurapoints.mixin.ItemDisplayAccessor;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * The shadow dragon's body is a chain of item displays (items dragon_segment_a / b, spawned by a datapack). A display is
 * only drawn within its view_range x 64 blocks (1.0 by default), so a long dragon seen from far away loses its far half.
 * Any item display holding a segment gets its view range raised to SHADOW_DRAGON_SEGMENT_VIEW_RANGE (2.5 = 160 blocks,
 * the most the server sends an item display at all). A larger value a datapack set itself is left alone. The change is
 * saved with the entity, so it only has to happen once.
 */
public final class DragonSegments {

	private DragonSegments() {
	}

	public static boolean isSegment(Entity e) {
		if (!(e instanceof Display.ItemDisplay display)) return false;
		ItemStack stack = ((ItemDisplayAccessor) display).aurapoints$getItemStack();
		return stack.is(AuraPoints.DRAGON_SEGMENT_A.get()) || stack.is(AuraPoints.DRAGON_SEGMENT_B.get());
	}

	/** Raises the view range of a dragon segment; returns true if it changed anything. */
	public static boolean raise(Entity e) {
		float want = AuraConfig.SHADOW_DRAGON_SEGMENT_VIEW_RANGE;
		if (want <= 0 || !isSegment(e)) return false;
		DisplayAccessor display = (DisplayAccessor) e;
		if (display.aurapoints$getViewRange() >= want) return false;
		display.aurapoints$setViewRange(want);
		return true;
	}

	/**
	 * A display joined the world: check it now, and again a moment later, because a datapack often spawns the display
	 * first and sets its item with a data command afterwards.
	 */
	public static void onJoin(Entity e) {
		if (!(e instanceof Display.ItemDisplay) || e.getServer() == null) return;
		raise(e);
		AuraFx.later(e.getServer(), 1, () -> raise(e));
		AuraFx.later(e.getServer(), 20, () -> raise(e));
	}
}
