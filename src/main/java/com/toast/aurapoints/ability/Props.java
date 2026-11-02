package com.toast.aurapoints.ability;

import com.mojang.math.Transformation;
import com.toast.aurapoints.mixin.BlockDisplayAccessor;
import com.toast.aurapoints.mixin.DisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Short-lived block displays for the owner-only auras' rocks, beams and rifts: spawned with a life in ticks, reshaped
 * or moved with the client smoothing it over, and gone on their own when the life runs out. Nothing is ever placed in
 * the world. Tagged, so any left over from a crash are dropped as they load (AuraPoints' join check).
 */
final class Props {

	private Props() {
	}

	private static final String TAG = "aurapoints_prop";
	/** display -> ticks left (-1: until removed) */
	private static final Map<Display.BlockDisplay, Integer> LIFE = new HashMap<>();
	private static final Set<UUID> LIVE = new HashSet<>();

	/**
	 * A display of {@code block} at {@code at} shaped by {@code t}, removed after {@code life} ticks (-1: only by
	 * {@link #remove}). {@code smooth}: how many ticks the client takes to follow a move.
	 */
	static Display.BlockDisplay spawn(ServerLevel level, Vec3 at, BlockState block, Transformation t, int life, int smooth) {
		Display.BlockDisplay d = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
		d.setPos(at.x, at.y, at.z);
		((BlockDisplayAccessor) d).aurapoints$setBlockState(block);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setWidth(8f);
		acc.aurapoints$setHeight(8f);
		acc.aurapoints$setViewRange(2.0f);
		acc.aurapoints$setPosRotInterpolationDuration(Math.max(0, smooth));
		acc.aurapoints$setBrightnessOverride(new Brightness(Math.max(9, level.getMaxLocalRawBrightness(BlockPos.containing(at.add(0, 1, 0)))), 15));
		acc.aurapoints$setTransformation(t);
		d.addTag(TAG);
		LIVE.add(d.getUUID());
		level.addFreshEntity(d);
		LIFE.put(d, life);
		return d;
	}

	/** Glowing at full light whatever is around (beams, the sun). */
	static void glow(Display.BlockDisplay d) {
		((DisplayAccessor) d).aurapoints$setBrightnessOverride(new Brightness(15, 15));
	}

	/** Reshapes it, the client easing over {@code ticks}. */
	static void set(Display.BlockDisplay d, Transformation t, int ticks) {
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setTransformation(t);
		acc.aurapoints$setInterpolationDuration(Math.max(0, ticks));
		d.getEntityData().set(DisplayAccessor.aurapoints$startDelta(), 0, true);
	}

	/** Reshapes it after {@code delay} ticks (if it is still there). */
	static void setLater(Display.BlockDisplay d, int delay, Transformation t, int ticks) {
		var server = d.level().getServer();
		if (server == null) return;
		AuraFx.later(server, delay, () -> {
			if (!d.isRemoved()) set(d, t, ticks);
		});
	}

	static void move(Display.BlockDisplay d, Vec3 to) {
		d.setPos(to.x, to.y, to.z);
	}

	static void remove(Display.BlockDisplay d) {
		LIFE.remove(d);
		LIVE.remove(d.getUUID());
		d.discard();
	}

	/** Every server tick. */
	static void tick() {
		if (LIFE.isEmpty()) return;
		for (Iterator<Map.Entry<Display.BlockDisplay, Integer>> it = LIFE.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			Display.BlockDisplay d = e.getKey();
			int left = e.getValue();
			if (d.isRemoved()) {
				it.remove();
				LIVE.remove(d.getUUID());
				continue;
			}
			if (left < 0) continue;
			if (left == 0) {
				it.remove();
				LIVE.remove(d.getUUID());
				d.discard();
				continue;
			}
			e.setValue(left - 1);
		}
	}

	public static boolean isLeftover(Entity e) {
		return e instanceof Display.BlockDisplay && e.getTags().contains(TAG) && !LIVE.contains(e.getUUID());
	}

	static void clearAll() {
		for (Display.BlockDisplay d : LIFE.keySet()) d.discard();
		LIFE.clear();
		LIVE.clear();
	}

	// ---------------------------------------------------------------
	// Shapes
	// ---------------------------------------------------------------

	/** A box {@code w} x {@code h} x {@code d} with its bottom middle at (x, y, z) relative to the display. */
	static Transformation box(double x, double y, double z, float w, float h, float depth) {
		return new Transformation(new Vector3f((float) x - w / 2f, (float) y, (float) z - depth / 2f), null, new Vector3f(w, h, depth), null);
	}

	/** A cube of edge {@code s} centred on {@code c} (relative to the display), turned by {@code rot} about its middle. */
	static Transformation cube(Vector3f c, float s, Quaternionf rot) {
		Vector3f half = new Vector3f(-s / 2f, -s / 2f, -s / 2f);
		Vector3f t = new Quaternionf(rot).transform(half).add(c);
		return new Transformation(t, new Quaternionf(rot), new Vector3f(s, s, s), null);
	}

	/** A slab {@code w} wide, {@code h} long, {@code w} deep from {@code base} along {@code dir} (a spike, a pillar, a beam). */
	static Transformation along(Vec3 base, Vec3 dir, float w, float h) {
		Vec3 n = dir.lengthSqr() < 1.0e-8 ? new Vec3(0, 1, 0) : dir.normalize();
		Quaternionf rot = new Quaternionf().rotationTo(0f, 1f, 0f, (float) n.x, (float) n.y, (float) n.z);
		Vector3f offset = rot.transform(new Vector3f(w / 2f, 0f, w / 2f));
		return new Transformation(new Vector3f((float) base.x, (float) base.y, (float) base.z).sub(offset), rot, new Vector3f(w, h, w), null);
	}
}
