package com.toast.aurapoints.ability;

import com.mojang.math.Transformation;
import com.toast.aurapoints.mixin.BlockDisplayAccessor;
import com.toast.aurapoints.mixin.DisplayAccessor;
import com.toast.aurapoints.mixin.InteractionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Building kit for Verdant's root models (Entanglement's cocoon and grab roots, the spike field, the
 * Verdant Viper and its trail): block displays shaped by transformations the clients interpolate, plus
 * invisible Interaction hitboxes players can hit. Every piece is lit like {@code lightAt} (not like the
 * spot the entity sits on), tagged, tracked, and removed with {@link #discardAll}; anything tagged that
 * loads without being tracked (a crash mid-animation) is a leftover and gets removed on load.
 */
final class RootCanvas {

	static final String TAG = "aurapoints_root";
	private static final Set<UUID> LIVE = new HashSet<>();

	final ServerLevel level;
	Brightness light;
	final List<Entity> pieces = new ArrayList<>();

	RootCanvas(ServerLevel level, BlockPos lightAt) {
		this.level = level;
		this.light = lightAt(level, lightAt);
	}

	static Brightness lightAt(ServerLevel level, BlockPos at) {
		return new Brightness(level.getBrightness(LightLayer.BLOCK, at), level.getBrightness(LightLayer.SKY, at));
	}

	/** A block model sitting at {@code at} (its transformations are relative to there), hidden until shaped. */
	Display.BlockDisplay piece(BlockState block, Vec3 at) {
		Display.BlockDisplay d = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
		d.setPos(at.x, at.y, at.z);
		((BlockDisplayAccessor) d).aurapoints$setBlockState(block);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setWidth(24f);
		acc.aurapoints$setHeight(24f);
		acc.aurapoints$setViewRange(2.0f);
		acc.aurapoints$setBrightnessOverride(light);
		acc.aurapoints$setPosRotInterpolationDuration(2);
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), null, new Vector3f(0.001f), null));
		return add(d);
	}

	/** An item model at {@code at} (handheld weapons flying about), hidden until placed. */
	Display.ItemDisplay item(net.minecraft.world.item.ItemStack stack, Vec3 at) {
		Display.ItemDisplay d = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		d.setPos(at.x, at.y, at.z);
		((com.toast.aurapoints.mixin.ItemDisplayAccessor) d).aurapoints$setItemStack(stack.copy());
		((com.toast.aurapoints.mixin.ItemDisplayAccessor) d).aurapoints$setItemTransform(net.minecraft.world.item.ItemDisplayContext.FIXED);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setViewRange(4.0f);
		acc.aurapoints$setBrightnessOverride(light);
		acc.aurapoints$setPosRotInterpolationDuration(1);
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), null, new Vector3f(0.001f), null));
		return add(d);
	}

	/** Centred on {@code c}, turned by {@code rot}, scaled {@code scale} (item displays scale round their middle). */
	static void place(Display d, Vec3 c, Quaternionf rot, float scale, int interpolate) {
		set(d, new Transformation(rel(d, c), rot, new Vector3f(scale), null), interpolate);
	}

	/** A block {@code size} big centred on {@code c}, turned by {@code rot} round its own middle. */
	static void cube(Display d, Vec3 c, Quaternionf rot, Vector3f size, int interpolate) {
		Vector3f t = rel(d, c).sub(rot.transform(new Vector3f(size).mul(0.5f)));
		set(d, new Transformation(t, rot, size, null), interpolate);
	}

	/** Moves the display entity itself (its transformation stays relative to it), smoothed over one tick. */
	static void moveTo(Display d, Vec3 at) {
		d.setPos(at.x, at.y, at.z);
	}

	/** An invisible box players can hit, {@code w} wide and {@code h} high, standing on {@code at}. */
	Interaction hitbox(Vec3 at, float w, float h) {
		Interaction box = new Interaction(EntityType.INTERACTION, level);
		box.setPos(at.x, at.y, at.z);
		InteractionAccessor acc = (InteractionAccessor) box;
		acc.aurapoints$setWidth(w);
		acc.aurapoints$setHeight(h);
		acc.aurapoints$setResponse(true);
		return add(box);
	}

	private <T extends Entity> T add(T e) {
		e.addTag(TAG);
		LIVE.add(e.getUUID()); // before it joins the world, or the leftover check would remove it
		level.addFreshEntity(e);
		pieces.add(e);
		return e;
	}

	private static Vector3f rel(Display d, Vec3 p) {
		return new Vector3f((float) (p.x - d.getX()), (float) (p.y - d.getY()), (float) (p.z - d.getZ()));
	}

	private static void set(Display d, Transformation t, int interpolate) {
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setTransformation(t);
		acc.aurapoints$setInterpolationDuration(interpolate);
		d.getEntityData().set(DisplayAccessor.aurapoints$startDelta(), 0, true);
	}

	/** Nothing to see: a speck at {@code at}, so it grows out right there when shaped. */
	static void hide(Display d, Vec3 at, int interpolate) {
		set(d, new Transformation(rel(d, at), null, new Vector3f(0.001f), null), interpolate);
	}

	/** The block stretched from {@code a} to {@code b}, {@code w} thick, overlapping both ends a little. */
	static void stretch(Display d, Vec3 a, Vec3 b, float w, float overlap, int interpolate) {
		Vec3 dir = b.subtract(a);
		float len = (float) dir.length();
		if (len < 1.0e-3f || w < 1.0e-3f) {
			hide(d, a, interpolate);
			return;
		}
		Quaternionf rot = new Quaternionf().rotationTo(0f, 1f, 0f, (float) dir.x, (float) dir.y, (float) dir.z);
		Vec3 start = a.subtract(dir.scale((overlap - 1) / 2));
		Vector3f t = rel(d, start).sub(rot.transform(new Vector3f(w / 2f, 0f, w / 2f)));
		set(d, new Transformation(t, rot, new Vector3f(w, len * overlap, w), null), interpolate);
	}

	/** A cube of size {@code s} centred on {@code c}, turned along {@code dir}: fills the outside of a bend. */
	static void knot(Display d, Vec3 c, Vec3 dir, float s, int interpolate) {
		Vec3 n = dir.lengthSqr() < 1.0e-6 ? new Vec3(0, 1, 0) : dir.normalize();
		stretch(d, c.subtract(n.scale(s / 2)), c.add(n.scale(s / 2)), s, 1f, interpolate);
	}

	/** A box {@code size} big whose bottom centre is {@code base}, turned {@code yaw} degrees around the vertical. */
	static void box(Display d, Vec3 base, Vector3f size, float yaw, float pitch, int interpolate) {
		Quaternionf rot = new Quaternionf().rotationYXZ((float) Math.toRadians(-yaw), (float) Math.toRadians(pitch), 0f);
		Vector3f t = rel(d, base).sub(rot.transform(new Vector3f(size.x / 2f, 0f, size.z / 2f)));
		set(d, new Transformation(t, rot, size, null), interpolate);
	}

	static void setBlock(Display.BlockDisplay d, BlockState block) {
		((BlockDisplayAccessor) d).aurapoints$setBlockState(block);
	}

	static void setLight(Display d, Brightness light) {
		((DisplayAccessor) d).aurapoints$setBrightnessOverride(light);
	}

	void remove(Entity e) {
		LIVE.remove(e.getUUID());
		e.discard();
		pieces.remove(e);
	}

	void discardAll() {
		for (Entity e : pieces) {
			LIVE.remove(e.getUUID());
			e.discard();
		}
		pieces.clear();
	}

	/**
	 * The top of the ground below {@code at}: searched from {@code up} blocks above it down {@code down}
	 * blocks; NaN if there is none (or it isn't loaded).
	 */
	static double surfaceY(ServerLevel level, Vec3 at, int up, int down) {
		BlockPos pos = BlockPos.containing(at.x, at.y + up, at.z);
		for (int i = 0; i <= up + down; i++) {
			if (!level.isLoaded(pos)) return Double.NaN;
			var shape = level.getBlockState(pos).getCollisionShape(level, pos);
			if (!shape.isEmpty() && pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y) <= at.y + up + 0.01) {
				// the first solid block from above whose top is at most the search start
				return pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
			}
			pos = pos.below();
		}
		return Double.NaN;
	}

	static BlockState groundBlock(ServerLevel level, Vec3 surface) {
		BlockState ground = level.getBlockState(BlockPos.containing(surface.x, surface.y - 0.5, surface.z));
		return ground.isAir() ? net.minecraft.world.level.block.Blocks.ROOTED_DIRT.defaultBlockState() : ground;
	}

	/** A tagged piece that loads in but isn't running (left over from a crash): gone at once. */
	static boolean isLeftover(Entity e) {
		return e.getTags().contains(TAG) && !LIVE.contains(e.getUUID());
	}

	static boolean isOurs(Entity e) {
		return e.getTags().contains(TAG) && LIVE.contains(e.getUUID());
	}
}
