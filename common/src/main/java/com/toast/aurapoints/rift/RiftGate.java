package com.toast.aurapoints.rift;

import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Props;
import com.toast.aurapoints.ability.Relics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The Rift Gate (5.46.0): what a tear looks like in the overworld. Display entities only - no block in the world is
 * changed, so nothing is left behind if the server stops while one is open (Props drops leftover displays as they load).
 * <ul>
 * <li>a tall oval of floating stones in the aura's palette, orbiting slowly and tumbling, a few of them lit;</li>
 * <li>the curtain: stacked strips of swirling portal, tinted by the aura's stained glass on both faces, breathing;</li>
 * <li>the aura's relic turning in the middle; glowing cracks in the ground and debris drifting up around the foot;</li>
 * <li>a thin beam of light into the sky that can be seen from far away, a low hum, particles pulled into the curtain.</li>
 * </ul>
 * Opening: the stones rise out of the ground one after another, the cracks spread, then the curtain unfolds.
 * Closing: the curtain folds shut, the stones fall in on the middle and are gone.
 */
final class RiftGate {

	/** Half width and half height of the curtain's oval. */
	static final float A = 1.45f, B = 2.35f;
	/** The middle of the curtain above the ground. */
	static final float HEIGHT = 2.75f;
	private static final int STONES = 18, STRIPS = 11, CRACKS = 10, DEBRIS = 8, OPEN_TICKS = 60, CLOSE_TICKS = 24;

	final AuraType aura;
	final ServerLevel level;
	/** On the ground below the gate, and the middle of the curtain. */
	final Vec3 ground, center;
	/** Unit vectors: out of the curtain's face (toward whoever it opened for) and along it. */
	final Vec3 normal, right;
	private final Quaternionf yaw;
	private final RealmBuilder.Palette palette;
	private final List<Display.BlockDisplay> stones = new ArrayList<>(), strips = new ArrayList<>(), cracks = new ArrayList<>(), debris = new ArrayList<>();
	private final float[] stoneSize = new float[STONES], stoneSpin = new float[STONES], crackAngle = new float[CRACKS], crackLen = new float[CRACKS],
			crackY = new float[CRACKS], debrisAngle = new float[DEBRIS], debrisRad = new float[DEBRIS], debrisPhase = new float[DEBRIS];
	Display.ItemDisplay core;
	private int age;
	private int closingAge = -1;

	RiftGate(ServerLevel level, AuraType aura, Vec3 ground, Vec3 facing) {
		this.level = level;
		this.aura = aura;
		this.ground = ground;
		this.center = ground.add(0, HEIGHT, 0);
		Vec3 n = new Vec3(facing.x, 0, facing.z);
		if (n.lengthSqr() < 1.0e-6) n = new Vec3(0, 0, 1);
		this.normal = n.normalize();
		float a = (float) Math.atan2(normal.x, normal.z);
		this.yaw = new Quaternionf().rotationY(a);
		this.right = new Vec3(Math.cos(a), 0, -Math.sin(a));
		this.palette = RealmBuilder.palette(aura);
		build();
	}

	// ---------------------------------------------------------------
	// Building
	// ---------------------------------------------------------------

	private void build() {
		RandomSource r = RandomSource.create(Double.doubleToLongBits(ground.x * 31 + ground.z));
		Transformation none = box(new Vector3f(0, -HEIGHT, 0), new Vector3f(0, 0, 0), new Quaternionf());
		for (int i = 0; i < STONES; i++) {
			BlockState b = (i % 4 == 0 ? palette.light() : i % 2 == 0 ? palette.accent() : palette.edge()).defaultBlockState();
			Display.BlockDisplay d = Props.spawn(level, center, b, none, -1, 0);
			if (i % 4 == 0) Props.glow(d);
			stones.add(d);
			stoneSize[i] = 0.38f + r.nextFloat() * 0.38f;
			stoneSpin[i] = (r.nextFloat() - 0.5f) * 0.12f;
		}
		BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, net.minecraft.core.Direction.Axis.X);
		BlockState tint = palette.gate().defaultBlockState();
		for (int j = 0; j < STRIPS; j++) {
			for (int layer = 0; layer < 3; layer++) {
				Display.BlockDisplay d = Props.spawn(level, center, layer == 0 ? portal : tint, none, -1, 0);
				Props.glow(d);
				strips.add(d);
			}
		}
		for (int i = 0; i < CRACKS; i++) {
			crackAngle[i] = (float) (i * Math.PI * 2 / CRACKS + (r.nextFloat() - 0.5f) * 0.4f);
			crackLen[i] = 2.2f + r.nextFloat() * 2.6f;
			// the ground under the crack's middle, relative to the gate's foot
			double mx = ground.x + Math.cos(crackAngle[i]) * crackLen[i] * 0.6, mz = ground.z + Math.sin(crackAngle[i]) * crackLen[i] * 0.6;
			int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(mx), Mth.floor(mz));
			crackY[i] = (float) Mth.clamp(top - ground.y, -1.5, 1.5);
			Display.BlockDisplay d = Props.spawn(level, center, (i % 2 == 0 ? palette.light() : palette.accent()).defaultBlockState(), none, -1, 0);
			if (i % 2 == 0) Props.glow(d);
			cracks.add(d);
		}
		for (int i = 0; i < DEBRIS; i++) {
			debrisAngle[i] = r.nextFloat() * (float) Math.PI * 2;
			debrisRad[i] = 2.4f + r.nextFloat() * 1.8f;
			debrisPhase[i] = r.nextFloat();
			debris.add(Props.spawn(level, center, palette.base().defaultBlockState(), none, -1, 0));
		}
		core = relicDisplay();
		level.playSound(null, center.x, center.y, center.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 1.5f, 1.3f);
		level.playSound(null, ground.x, ground.y, ground.z, SoundEvents.WARDEN_EMERGE, SoundSource.AMBIENT, 1.2f, 0.6f);
	}

	private Display.ItemDisplay relicDisplay() {
		Display.ItemDisplay d = EntityType.ITEM_DISPLAY.create(level);
		if (d == null) return null;
		CompoundTag t = new CompoundTag();
		t.put("item", Relics.stack(aura).save(level.registryAccess()));
		t.putString("billboard", "center");
		t.putFloat("view_range", 2f);
		CompoundTag light = new CompoundTag();
		light.putInt("sky", 15);
		light.putInt("block", 15);
		t.put("brightness", light);
		CompoundTag tr = new CompoundTag();
		tr.put("left_rotation", floats(0, 0, 0, 1));
		tr.put("right_rotation", floats(0, 0, 0, 1));
		tr.put("translation", floats(0, 0, 0));
		tr.put("scale", floats(0.01f, 0.01f, 0.01f));
		t.put("transformation", tr);
		t.putInt("interpolation_duration", 20);
		d.load(t);
		d.moveTo(center.x, center.y, center.z, 0, 0);
		d.addTag(Rifts.TEAR_TAG);
		level.addFreshEntity(d);
		return d;
	}

	private static ListTag floats(float... v) {
		ListTag l = new ListTag();
		for (float f : v) l.add(FloatTag.valueOf(f));
		return l;
	}

	// ---------------------------------------------------------------
	// Shapes (local frame: x along the curtain, y up, z out of its face; origin = the curtain's middle)
	// ---------------------------------------------------------------

	/** A box of {@code size} centred on {@code c} (local), turned by {@code rot} (local) about its middle. */
	private Transformation box(Vector3f c, Vector3f size, Quaternionf rot) {
		Quaternionf total = new Quaternionf(yaw).mul(rot);
		Vector3f t = yaw.transform(new Vector3f(c)).sub(total.transform(new Vector3f(size).mul(0.5f)));
		return new Transformation(t, total, size, null);
	}

	private static float ease(float f) {
		f = Mth.clamp(f, 0f, 1f);
		return f * f * (3 - 2 * f);
	}

	// ---------------------------------------------------------------
	// Ticking
	// ---------------------------------------------------------------

	boolean closing() {
		return closingAge >= 0;
	}

	/** Starts the closing animation (the gate removes itself when it ends: {@link #tick} returns false). */
	void close() {
		if (closingAge >= 0) return;
		closingAge = 0;
		level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.AMBIENT, 1.4f, 0.6f);
		level.playSound(null, center.x, center.y, center.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.AMBIENT, 1.2f, 0.7f);
	}

	/** Every server tick; false once a closing gate is gone. */
	boolean tick() {
		age++;
		float shut = 1f;
		if (closingAge >= 0) {
			closingAge++;
			shut = 1f - ease(closingAge / (float) CLOSE_TICKS);
			if (closingAge > CLOSE_TICKS) {
				remove();
				return false;
			}
		}
		if (age % 2 == 0) moveStones(shut);
		if (age % 4 == 0) moveCurtain(shut);
		if (age % 4 == 1) moveGround(shut);
		if (core != null && age % 20 == 1) {
			float s = 1.1f * ease((age - 40) / 30f) * shut;
			float bob = (float) Math.sin(age * 0.05) * 0.12f;
			var acc = (com.toast.aurapoints.mixin.DisplayAccessor) core;
			// a little in front of the curtain, or the portal strips hide it
			Vec3 f = normal.scale(0.35);
			acc.aurapoints$setTransformation(new Transformation(new Vector3f((float) f.x, bob, (float) f.z), null, new Vector3f(s, s, s), null));
			acc.aurapoints$setInterpolationDuration(20);
			core.getEntityData().set(com.toast.aurapoints.mixin.DisplayAccessor.aurapoints$startDelta(), 0, true);
		}
		particles(shut);
		sounds();
		return true;
	}

	private void moveStones(float shut) {
		float spin = age * 0.012f;
		for (int i = 0; i < STONES; i++) {
			float rise = ease((age - i * 2f) / 26f);
			double th = i * Math.PI * 2 / STONES + spin;
			float ra = (A + 0.55f) * (0.35f + 0.65f * shut), rb = (B + 0.55f) * (0.35f + 0.65f * shut);
			float tx = (float) Math.cos(th) * ra, ty = (float) Math.sin(th) * rb;
			float wob = (float) Math.sin(age * 0.07 + i) * 0.12f;
			// out of the ground: from below the foot to its place in the ring
			float x = tx * (0.4f + 0.6f * rise), y = Mth.lerp(rise, -HEIGHT + 0.2f, ty + wob), z = (float) Math.cos(age * 0.05 + i * 1.7) * 0.15f;
			float s = stoneSize[i] * rise * (closingAge >= 0 ? shut : 1f);
			Quaternionf rot = new Quaternionf().rotationXYZ(age * stoneSpin[i], age * stoneSpin[i] * 0.7f + i, i * 0.6f);
			Props.set(stones.get(i), box(new Vector3f(x, y, z), new Vector3f(s, s, s), rot), 3);
		}
	}

	private void moveCurtain(float shut) {
		float open = ease((age - 34) / 26f) * shut;
		float h = 2 * B / STRIPS;
		for (int j = 0; j < STRIPS; j++) {
			float yc = -B + (j + 0.5f) * h;
			float half = A * (float) Math.sqrt(Math.max(0.05, 1 - (yc / B) * (yc / B)));
			float breathe = 1f + (float) Math.sin(age * 0.09 + j * 0.8) * 0.04f;
			float w = 2 * half * breathe * open + 0.001f;
			float hh = h * (0.25f + 0.75f * open) + 0.02f;
			Props.set(strips.get(j * 3), box(new Vector3f(0, yc, 0), new Vector3f(w, hh, 0.08f), new Quaternionf()), 4);
			Props.set(strips.get(j * 3 + 1), box(new Vector3f(0, yc, 0.07f), new Vector3f(w + 0.02f, hh, 0.02f), new Quaternionf()), 4);
			Props.set(strips.get(j * 3 + 2), box(new Vector3f(0, yc, -0.07f), new Vector3f(w + 0.02f, hh, 0.02f), new Quaternionf()), 4);
		}
	}

	private void moveGround(float shut) {
		for (int i = 0; i < CRACKS; i++) {
			float grow = ease((age - 6 - i * 1.5f) / 30f) * shut;
			float len = crackLen[i] * grow + 0.001f;
			// in the local frame the crack's world angle has to be undone by the gate's yaw
			double wa = crackAngle[i];
			Vec3 dir = new Vec3(Math.cos(wa), 0, Math.sin(wa));
			float lx = (float) dir.dot(right), lz = (float) dir.dot(normal);
			float mid = len / 2f + 0.4f;
			Quaternionf rot = new Quaternionf().rotationY((float) Math.atan2(-lz, lx));
			Props.set(cracks.get(i), box(new Vector3f(lx * mid, -HEIGHT + crackY[i] + 0.03f, lz * mid), new Vector3f(len, 0.06f, 0.22f + 0.08f * (i % 3)), rot), 4);
		}
		for (int i = 0; i < DEBRIS; i++) {
			float cycle = ((age / 80f) + debrisPhase[i]) % 1f;
			float a = debrisAngle[i] + age * 0.01f;
			float s = 0.3f * (1 - cycle) * ease(age / 40f) * shut;
			float x = (float) Math.cos(a) * debrisRad[i], z = (float) Math.sin(a) * debrisRad[i], y = -HEIGHT + cycle * 3.5f;
			Quaternionf rot = new Quaternionf().rotationXYZ(age * 0.05f + i, age * 0.03f, 0);
			// a fresh cycle jumps back to the ground: no easing, or it would visibly slide down
			Props.set(debris.get(i), box(new Vector3f(x, y, z), new Vector3f(s, s, s), rot), cycle < 0.05f ? 0 : 4);
		}
	}

	private void particles(float shut) {
		if (closingAge >= 0) {
			if (closingAge == CLOSE_TICKS - 2) {
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y, center.z, 80, 0.4, 0.8, 0.4, 0.6);
				level.sendParticles(ParticleTypes.FLASH, center.x, center.y, center.z, 1, 0, 0, 0, 0);
			}
			return;
		}
		int rgb = aura.rgb;
		Vector3f col = new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f);
		RandomSource r = level.random;
		if (age > 34 && age % 2 == 0) {
			// pulled in from the oval's edge (a portal particle flies from pos + delta to pos)
			for (int k = 0; k < 4; k++) {
				double th = r.nextDouble() * Math.PI * 2;
				Vec3 edge = right.scale(Math.cos(th) * (A + 0.8)).add(0, Math.sin(th) * (B + 0.8), 0);
				level.sendParticles(ParticleTypes.PORTAL, center.x, center.y, center.z, 0, edge.x, edge.y, edge.z, 1);
			}
			var dust = new DustColorTransitionOptions(col, new Vector3f(1f, 1f, 1f), 1.3f);
			double th = r.nextDouble() * Math.PI * 2;
			Vec3 at = center.add(right.scale(Math.cos(th) * (A + 0.2))).add(0, Math.sin(th) * (B + 0.2), 0);
			level.sendParticles(dust, at.x, at.y, at.z, 2, 0.08, 0.08, 0.08, 0);
		}
		if (age < OPEN_TICKS && age % 3 == 0) {
			level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, ground.x, ground.y + 0.2, ground.z, 2, 1.2, 0.1, 1.2, 0.01);
			level.sendParticles(new DustParticleOptions(col, 2f), ground.x, ground.y + 0.3, ground.z, 6, 2.0, 0.1, 2.0, 0);
		}
		if (age % 10 == 0 && age > 40) {
			// the beam: forced, so it is seen far beyond normal particle range
			var big = new DustParticleOptions(col, 3.5f);
			for (ServerPlayer p : level.players()) {
				if (p.distanceToSqr(center) > 200 * 200) continue;
				for (int k = 0; k < 14; k++) {
					double y = center.y + B + 1 + k * 4 + r.nextDouble() * 3;
					level.sendParticles(p, big, true, center.x, y, center.z, 1, 0.05, 0.4, 0.05, 0);
					if (k % 3 == 0) level.sendParticles(p, ParticleTypes.END_ROD, true, center.x, y, center.z, 1, 0.02, 0.6, 0.02, 0.01);
				}
			}
		}
	}

	private void sounds() {
		if (closingAge >= 0) return;
		if (age % 50 == 10) level.playSound(null, center.x, center.y, center.z, SoundEvents.PORTAL_AMBIENT, SoundSource.AMBIENT, 0.9f, 0.55f);
		if (age % 80 == 40) level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_AMBIENT, SoundSource.AMBIENT, 1.6f, 0.5f);
	}

	// ---------------------------------------------------------------
	// Players
	// ---------------------------------------------------------------

	/** True if the player's middle is in the curtain's oval (and the curtain is open). */
	boolean touching(ServerPlayer p) {
		if (age < OPEN_TICKS - 10 || closingAge >= 0) return false;
		Vec3 v = p.position().add(0, 0.9, 0).subtract(center);
		double d = v.dot(normal), x = v.dot(right), y = v.y;
		return Math.abs(d) < 0.9 && (x * x) / (A * A) + (y * y) / (B * B) <= 1.15;
	}

	/** One tick of pulling the player into the curtain's middle; {@code left} = ticks until they are through. */
	void pull(ServerPlayer p, int left) {
		Vec3 to = center.subtract(0, 0.9, 0).subtract(p.position());
		p.setDeltaMovement(to.scale(0.3).add(0, 0.04, 0));
		p.hurtMarked = true;
		p.fallDistance = 0;
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1, p.getZ(), 6, 0.3, 0.6, 0.3, 0.05);
		if (left == 5) p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS, 30, 0, false, false, false));
	}

	void remove() {
		for (Display.BlockDisplay d : stones) Props.remove(d);
		for (Display.BlockDisplay d : strips) Props.remove(d);
		for (Display.BlockDisplay d : cracks) Props.remove(d);
		for (Display.BlockDisplay d : debris) Props.remove(d);
		stones.clear();
		strips.clear();
		cracks.clear();
		debris.clear();
		if (core != null) core.discard();
		core = null;
	}

	BlockPos foot() {
		return BlockPos.containing(ground);
	}
}
