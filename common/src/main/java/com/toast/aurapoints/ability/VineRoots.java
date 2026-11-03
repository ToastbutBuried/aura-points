package com.toast.aurapoints.ability;

import com.mojang.math.Transformation;
import com.toast.aurapoints.mixin.BlockDisplayAccessor;
import com.toast.aurapoints.mixin.DisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Vine Hook's roots as real block models. Two roots tear out of the ground beside the caster,
 * each a chain of block displays along a curve that rises and arcs over onto the target, squirming:
 * oak bark at the base, mangrove roots toward the tip, a knot at every joint so the bends never gap,
 * a green vine spiralling round it, hanging cave vines (some with glow berries) and azalea leaf tufts.
 * Caught: a spiral of roots wraps the target and carries it - straight through any block - to right in
 * front of the caster. Then the roots draw back into the ground.
 *
 * All server side: every tick each piece gets a new transformation that clients interpolate, so it
 * moves smoothly. The displays are lit like the spot the caster stands on, tagged, and never outlive
 * the animation - leftovers (a crash mid-animation) are removed the moment they load again.
 */
public final class VineRoots {

	private VineRoots() {
	}

	public static final String TAG = "aurapoints_vine";
	/** Pieces along each root. */
	private static final int MAIN = 14;
	private static final int WRAP = 10;
	private static final double[] TENDRILS = {0.32, 0.56, 0.8};
	private static final double[] TUFTS = {0.45, 0.68, 0.9};
	private static final int COIL = 10;
	/** Displays sit this far below the root so their culling box also covers targets below it. */
	private static final double DROP = 16;
	private static final Transformation HIDDEN = new Transformation(new Vector3f(), null, new Vector3f(0.001f), null);

	private static final class Arm {
		final List<Display.BlockDisplay> main = new ArrayList<>(), joints = new ArrayList<>(), wrap = new ArrayList<>(),
				tendrils = new ArrayList<>(), tufts = new ArrayList<>();
		Display.BlockDisplay mound;
	}

	private static final class Rig {
		final ServerPlayer owner;
		final ServerLevel level;
		final Vec3[] roots;
		LivingEntity target;
		Vec3 tip;
		/** where the caster aimed at the start: the aim can only be dragged a little away from it */
		final Vec3 startLook;
		/** the share of each root's length that is the straight rise out of the ground */
		double riseShare;
		final Vec3 anchor;
		final Brightness light;
		final List<Arm> arms = new ArrayList<>();
		final List<Display.BlockDisplay> coil = new ArrayList<>(), coilJoints = new ArrayList<>();
		int age;
		boolean caught;
		Vec3 pullFrom, pullTo;
		/** the timings, read once at the start so a config reload can't break a running animation */
		final int rise = Math.max(1, com.toast.aurapoints.config.AuraConfig.VINE_HOOK_RISE_TICKS);
		final double riseHeight = Math.max(0.3, com.toast.aurapoints.config.AuraConfig.VINE_HOOK_RISE_HEIGHT);
		final int reachTicks = Math.max(1, com.toast.aurapoints.config.AuraConfig.VINE_HOOK_REACH_TICKS);
		final double windup = Math.max(0.2, com.toast.aurapoints.config.AuraConfig.VINE_HOOK_WINDUP);
		final int hold = Math.max(1, com.toast.aurapoints.config.AuraConfig.VINE_HOOK_CARRY_TICKS);
		final int retract = Math.max(1, com.toast.aurapoints.config.AuraConfig.VINE_HOOK_RETRACT_TICKS);

		int grow() {
			return rise + reachTicks;
		}

		Rig(ServerPlayer owner, Vec3[] roots, LivingEntity target, Vec3 tip, Vec3 startLook) {
			this.startLook = startLook;
			this.owner = owner;
			this.level = owner.serverLevel();
			this.roots = roots;
			this.target = target;
			this.tip = tip;
			this.anchor = roots[0].lerp(roots[1], 0.5).subtract(0, DROP, 0);
			BlockPos at = owner.blockPosition().above();
			this.light = new Brightness(level.getBrightness(LightLayer.BLOCK, at), level.getBrightness(LightLayer.SKY, at));
		}
	}

	private static final List<Rig> RIGS = new ArrayList<>();
	/** Every display this mod spawned and still runs. */
	private static final Set<UUID> LIVE = new HashSet<>();

	/** Starts the roots. {@code target} null = a miss that reaches for {@code tip}. Returns the ticks until they arrive. */
	public static int start(ServerPlayer owner, Vec3[] roots, LivingEntity target, Vec3 tip, Vec3 look) {
		// planted in the real ground: down onto the surface below each spot, then a little into it
		ServerLevel level = owner.serverLevel();
		for (int i = 0; i < roots.length; i++) roots[i] = planted(level, roots[i]);
		Rig rig = new Rig(owner, roots, target, tip, look);
		rig.riseShare = riseShare(rig);
		BlockState bark = Blocks.OAK_LOG.defaultBlockState(), root = Blocks.MANGROVE_ROOTS.defaultBlockState();
		BlockState vine = Blocks.MOSS_BLOCK.defaultBlockState();
		for (int a = 0; a < roots.length; a++) {
			Arm arm = new Arm();
			for (int i = 0; i < MAIN; i++) arm.main.add(spawn(rig, i < 4 ? bark : root));
			for (int i = 0; i < MAIN - 1; i++) arm.joints.add(spawn(rig, i < 3 ? bark : root));
			for (int i = 0; i < WRAP; i++) arm.wrap.add(spawn(rig, vine));
			for (int i = 0; i < TENDRILS.length * 2; i++) {
				boolean berries = i % 4 == 1;
				arm.tendrils.add(spawn(rig, Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(CaveVines.BERRIES, berries)));
			}
			for (int i = 0; i < TUFTS.length; i++) arm.tufts.add(spawn(rig, Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState()));
			arm.mound = spawn(rig, groundAt(rig.level, roots[a]));
			rig.arms.add(arm);
		}
		for (int i = 0; i < COIL; i++) rig.coil.add(spawn(rig, root));
		for (int i = 0; i < COIL - 1; i++) rig.coilJoints.add(spawn(rig, root));
		RIGS.add(rig);
		for (Vec3 r : roots) erupt(rig.level, r, true);
		update(rig);
		return rig.grow();
	}

	private static Display.BlockDisplay spawn(Rig rig, BlockState block) {
		Display.BlockDisplay d = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, rig.level);
		d.setPos(rig.anchor.x, rig.anchor.y, rig.anchor.z);
		((BlockDisplayAccessor) d).aurapoints$setBlockState(block);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setWidth(64f);
		acc.aurapoints$setHeight(48f);
		acc.aurapoints$setViewRange(2.0f);
		// lit like where the caster stands, not like the ground deep below where the display sits
		acc.aurapoints$setBrightnessOverride(rig.light);
		acc.aurapoints$setTransformation(HIDDEN);
		d.addTag(TAG);
		LIVE.add(d.getUUID()); // before it joins the world, or the leftover check would remove it
		rig.level.addFreshEntity(d);
		return d;
	}

	/** Nothing to see: a speck at {@code at}, so when the piece appears it grows out right there. */
	private static Transformation hiddenAt(Rig rig, Vec3 at) {
		return new Transformation(new Vector3f((float) (at.x - rig.anchor.x), (float) (at.y - rig.anchor.y), (float) (at.z - rig.anchor.z)),
				null, new Vector3f(0.001f), null);
	}

	/** A block stretched from {@code a} to {@code b}, {@code w} thick, its up axis pointing at b. */
	private static Transformation stretch(Rig rig, Vec3 a, Vec3 b, float w, float overlap) {
		Vec3 d = b.subtract(a);
		float len = (float) d.length();
		if (len < 1.0e-3f || w < 1.0e-3f) return hiddenAt(rig, a);
		Quaternionf rot = new Quaternionf().rotationTo(0f, 1f, 0f, (float) d.x, (float) d.y, (float) d.z);
		// the block's bottom centre at a, pushed back by half the overlap so both ends reach past their joint
		Vec3 start = a.subtract(d.scale((overlap - 1) / 2));
		Vector3f offset = rot.transform(new Vector3f(w / 2f, 0f, w / 2f));
		Vector3f translation = new Vector3f((float) (start.x - rig.anchor.x), (float) (start.y - rig.anchor.y), (float) (start.z - rig.anchor.z)).sub(offset);
		return new Transformation(translation, rot, new Vector3f(w, len * overlap, w), null);
	}

	/** A cube of size {@code s} centred on {@code c}, turned along {@code dir} - the knot that fills a bend. */
	private static Transformation knot(Rig rig, Vec3 c, Vec3 dir, float s) {
		Vec3 n = dir.lengthSqr() < 1.0e-6 ? new Vec3(0, 1, 0) : dir.normalize();
		return stretch(rig, c.subtract(n.scale(s / 2)), c.add(n.scale(s / 2)), s, 1f);
	}

	private static void set(Display.BlockDisplay d, Transformation t, int interpolate) {
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setTransformation(t);
		acc.aurapoints$setInterpolationDuration(interpolate);
		d.getEntityData().set(DisplayAccessor.aurapoints$startDelta(), 0, true);
	}

	/** The straight rise's share of a root's length (rise height against the rest of the way to the tip). */
	private static double riseShare(Rig rig) {
		Vec3 top = rig.roots[0].add(0, rig.riseHeight, 0);
		double rest = top.distanceTo(rig.tip) * 1.15;
		return rig.riseHeight / (rig.riseHeight + Math.max(0.5, rest));
	}

	/**
	 * How far out the roots reach right now (0..1): ripped straight up out of the ground (fast at first),
	 * then a lash at the target that speeds up (WINDUP), later the retreat.
	 */
	private static double reach(Rig rig) {
		if (rig.age <= rig.rise) {
			double p = rig.age / (double) rig.rise;
			return rig.riseShare * (1 - (1 - p) * (1 - p));
		}
		if (rig.age <= rig.grow()) {
			double p = (rig.age - rig.rise) / (double) rig.reachTicks;
			return rig.riseShare + (1 - rig.riseShare) * Math.pow(p, rig.windup);
		}
		int back = rig.age - rig.grow() - (rig.caught ? rig.hold : 1);
		return back <= 0 ? 1.0 : Math.max(0.0, 1.0 - back / (double) rig.retract);
	}

	/** Root thickness at {@code f} along it. */
	private static float width(double f) {
		return (float) (0.52 - 0.36 * f);
	}

	/** Set by update() for along(): the rig being drawn (its rise height and share). */
	private static Rig drawing;

	/**
	 * The point a fraction {@code f} along a root: straight up out of the ground for the rise, then an
	 * arc over and onto the tip - only the arc squirms, the rise stays straight.
	 */
	private static Vec3 along(Vec3 root, Vec3 tip, double f, int age, int which) {
		f = Math.max(0, Math.min(1, f));
		double share = drawing == null ? 0.3 : drawing.riseShare, height = drawing == null ? 2.0 : drawing.riseHeight;
		Vec3 top = root.add(0, height, 0);
		if (f <= share) return root.add(0, height * f / Math.max(1.0e-4, share), 0);
		double g = (f - share) / (1 - share);
		double dist = top.distanceTo(tip);
		// a little higher over the top, then down onto the target
		Vec3 control = top.add(0, 0.4 + dist * 0.12, 0).lerp(tip, 0.25);
		double u = 1 - g;
		Vec3 p = top.scale(u * u).add(control.scale(2 * u * g)).add(tip.scale(g * g));
		Vec3 side = tip.subtract(top).cross(new Vec3(0, 1, 0));
		side = side.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : side.normalize();
		double phase = age * 0.8 + which * Math.PI;
		double calm = Math.sin(g * Math.PI);
		double sway = Math.sin(g * Math.PI * 3 - phase) * 0.22 * calm;
		return p.add(side.scale(sway)).add(0, Math.cos(g * Math.PI * 3 - phase) * 0.08 * calm, 0);
	}

	/**
	 * Until the roots arrive the aim follows the crosshair - dragged at most VINE_HOOK_DRAG_DEGREES off
	 * the first aim - so a near miss can still be pulled onto a target. Once something is caught in the
	 * sights it stays the target, and the roots follow it if it moves.
	 */
	private static void drag(Rig rig) {
		if (rig.target != null) {
			if (rig.target.isAlive() && rig.target.level() == rig.level) rig.tip = AuraFx.chest(rig.target);
			return;
		}
		Vec3 look = rig.owner.getLookAngle().normalize();
		double max = Math.toRadians(Math.max(0, com.toast.aurapoints.config.AuraConfig.VINE_HOOK_DRAG_DEGREES));
		double angle = Math.acos(Math.max(-1, Math.min(1, look.dot(rig.startLook))));
		if (angle > max) look = slerp(rig.startLook, look, max / angle);
		VineHook.Trace aim = VineHook.trace(rig.owner, look);
		rig.target = aim.target();
		rig.tip = aim.target() != null ? AuraFx.chest(aim.target()) : aim.tip();
	}

	/** From unit vector a toward b, {@code t} of the way (by angle). */
	private static Vec3 slerp(Vec3 a, Vec3 b, double t) {
		double cos = Math.max(-1, Math.min(1, a.dot(b)));
		double angle = Math.acos(cos);
		if (angle < 1.0e-4) return a;
		double s = Math.sin(angle);
		return a.scale(Math.sin((1 - t) * angle) / s).add(b.scale(Math.sin(t * angle) / s)).normalize();
	}

	/** A unit vector sideways off the root at {@code f}, turned {@code angle} around it. */
	private static Vec3 around(Vec3 root, Vec3 tip, double f, int age, int which, double angle) {
		Vec3 t = along(root, tip, f + 0.02, age, which).subtract(along(root, tip, f - 0.02, age, which));
		t = t.lengthSqr() < 1.0e-6 ? new Vec3(0, 1, 0) : t.normalize();
		Vec3 u = t.cross(new Vec3(0, 1, 0));
		u = u.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : u.normalize();
		Vec3 v = t.cross(u).normalize();
		return u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));
	}

	private static void update(Rig rig) {
		drawing = rig;
		if (!rig.caught && rig.age < rig.grow() && AuraFx.alive(rig.owner)) drag(rig);
		if (rig.caught && rig.target.isAlive() && rig.target.level() == rig.level) {
			carry(rig);
			rig.tip = AuraFx.chest(rig.target);
		}
		double reach = reach(rig);
		int interp = rig.age == 0 ? 0 : 2;
		for (int a = 0; a < rig.arms.size(); a++) {
			Arm arm = rig.arms.get(a);
			Vec3 root = rig.roots[a];
			Vec3[] pts = new Vec3[MAIN + 1];
			for (int i = 0; i <= MAIN; i++) pts[i] = along(root, rig.tip, i / (double) MAIN, rig.age, a);
			// the root itself, grown out as far as reach
			for (int i = 0; i < MAIN; i++) {
				double f0 = i / (double) MAIN, f1 = (i + 1) / (double) MAIN;
				double shown = Math.max(0.0, Math.min(1.0, (reach - f0) / (f1 - f0)));
				set(arm.main.get(i), shown <= 0 ? hiddenAt(rig, pts[i]) : stretch(rig, pts[i], pts[i].lerp(pts[i + 1], shown), width(f0), 1.25f), interp);
			}
			// knots on the joints, so the outside of a bend never opens up
			for (int i = 0; i < MAIN - 1; i++) {
				double f = (i + 1) / (double) MAIN;
				set(arm.joints.get(i), reach < f ? hiddenAt(rig, pts[i + 1]) : knot(rig, pts[i + 1], pts[i + 2].subtract(pts[i]), width(f) * 1.12f), interp);
			}
			// a green vine spiralling round the wood
			Vec3 prev = null;
			for (int k = 0; k <= WRAP; k++) {
				double f = 0.06 + 0.86 * k / WRAP;
				double angle = k * 1.9 + rig.age * 0.25 + a * Math.PI;
				Vec3 p = along(root, rig.tip, f, rig.age, a).add(around(root, rig.tip, f, rig.age, a, angle).scale(width(f) / 2 + 0.03));
				if (prev != null) set(arm.wrap.get(k - 1), reach < f ? hiddenAt(rig, prev) : stretch(rig, prev, p, 0.08f, 1.15f), interp);
				prev = p;
			}
			// cave vines hanging off it (some with glow berries) and leaf tufts
			for (int t = 0; t < TENDRILS.length; t++) {
				double f = TENDRILS[t];
				Vec3 out = around(root, rig.tip, f, rig.age, a, t * 2.2 + a);
				Vec3 base = along(root, rig.tip, f, rig.age, a).add(out.scale(width(f) / 2));
				Vec3 p1 = base.add(out.scale(0.35)).add(0, -0.15, 0);
				Vec3 p2 = p1.add(out.scale(0.15)).add(0, -0.45 - 0.1 * Math.sin(rig.age * 0.5 + t), 0);
				boolean on = reach >= f + 0.04;
				set(arm.tendrils.get(t * 2), on ? stretch(rig, base, p1, 0.4f, 1.1f) : hiddenAt(rig, base), interp);
				set(arm.tendrils.get(t * 2 + 1), on ? stretch(rig, p1, p2, 0.4f, 1.1f) : hiddenAt(rig, base), interp);
			}
			for (int t = 0; t < TUFTS.length; t++) {
				double f = TUFTS[t];
				Vec3 c = along(root, rig.tip, f, rig.age, a).add(around(root, rig.tip, f, rig.age, a, t * 2.6 + 1 + a).scale(width(f) / 2));
				set(arm.tufts.get(t), reach < f ? hiddenAt(rig, c) : knot(rig, c, new Vec3(0, 1, 0), 0.3f), interp);
			}
			// the earth bulging up round the root's base, out of the block it breaks through
			{
				double m = Math.min(1.0, reach * 6);
				Vec3 ground = root.add(0, ROOT_SINK, 0);
				set(arm.mound, m <= 0 ? hiddenAt(rig, ground) : mound(rig, ground, (float) (1.3 * m), (float) (0.5 * m)), interp);
			}
		}
		// the spiral wrapped round the target while it is carried in
		boolean wrap = rig.caught && rig.target.isAlive() && rig.age > rig.grow() && rig.age <= rig.grow() + rig.hold;
		double h = wrap ? rig.target.getBbHeight() : 0, r = wrap ? rig.target.getBbWidth() * 0.6 + 0.22 : 0;
		Vec3 base = wrap ? rig.target.position() : rig.tip;
		for (int i = 0; i < COIL; i++) {
			set(rig.coil.get(i), wrap ? stretch(rig, coilPoint(base, h, r, i, rig.age), coilPoint(base, h, r, i + 1, rig.age), 0.2f, 1.2f) : hiddenAt(rig, rig.tip), interp);
		}
		for (int i = 0; i < COIL - 1; i++) {
			Vec3 c = coilPoint(base, h, r, i + 1, rig.age);
			set(rig.coilJoints.get(i), wrap ? knot(rig, c, coilPoint(base, h, r, i + 2, rig.age).subtract(coilPoint(base, h, r, i, rig.age)), 0.22f) : hiddenAt(rig, rig.tip), interp);
		}
	}

	private static Vec3 coilPoint(Vec3 base, double h, double r, int i, int age) {
		double f = i / (double) COIL;
		double a = f * Math.PI * 3.2 + age * 0.35;
		return base.add(Math.cos(a) * r, 0.15 + f * h * 0.85, Math.sin(a) * r);
	}

	/**
	 * The catch: the target is moved along a little arc to right in front of the caster over VINE_HOOK_CARRY_TICKS,
	 * set in place every tick (so walls, floors and its own feet can't stop it), and lands safely.
	 */
	private static void carry(Rig rig) {
		int step = rig.age - rig.grow();
		if (step < 1 || step > rig.hold) return;
		LivingEntity t = rig.target;
		if (t.isPassenger()) t.stopRiding(); // sitting: torn off the seat, or everyone else would still see them on it
		double p = step / (double) rig.hold;
		double eased = 1 - (1 - p) * (1 - p);
		Vec3 at = rig.pullFrom.lerp(rig.pullTo, eased).add(0, Math.sin(Math.PI * p) * 1.2, 0);
		t.setDeltaMovement(Vec3.ZERO);
		t.fallDistance = 0;
		if (t instanceof ServerPlayer player) {
			// rotation: the player's own, as relative - teleport() sends (given - current) as the turn, so this turns
			// them by exactly 0 (passing 0 here would snap their view to due south)
			player.connection.teleport(at.x, at.y, at.z, player.getYRot(), player.getXRot(), EnumSet.of(RelativeMovement.X_ROT, RelativeMovement.Y_ROT));
		} else {
			t.noPhysics = step < rig.hold; // nothing pushes it back out of a wall on the way
			t.setPos(at.x, at.y, at.z);
		}
	}

	/** Right in front of the caster; if the target wouldn't fit there, onto the caster's own spot. */
	private static Vec3 landing(ServerPlayer owner, LivingEntity target) {
		Vec3 look = owner.getLookAngle().multiply(1, 0, 1);
		look = look.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : look.normalize();
		Vec3 front = owner.position().add(look.scale(0.9 + target.getBbWidth()));
		if (owner.serverLevel().noCollision(target, target.getBoundingBox().move(front.subtract(target.position())))) return front;
		return owner.position();
	}

	/** The closest mob or player within VINE_HOOK_TIP_RADIUS of where the roots ended, or null. */
	private static LivingEntity nearTip(Rig rig) {
		double r = Math.max(0, com.toast.aurapoints.config.AuraConfig.VINE_HOOK_TIP_RADIUS);
		if (r <= 0) return null;
		LivingEntity best = null;
		double bestDist = r * r;
		for (LivingEntity e : rig.level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(rig.tip, rig.tip).inflate(r + 1),
				e -> VineHook.canHook(rig.owner, e))) {
			double d = e.getBoundingBox().distanceToSqr(rig.tip);
			if (d <= bestDist) {
				bestDist = d;
				best = e;
			}
		}
		return best;
	}

	/** The roots reached their target: they coil round it and carry it to the caster. */
	private static void catchTarget(Rig rig) {
		LivingEntity target = rig.target;
		rig.caught = true;
		rig.pullFrom = target.position();
		rig.pullTo = landing(rig.owner, target);
		// a carried player's client smooths the ride (and rests their movement keys)
		if (target instanceof ServerPlayer carried) {
			com.toast.aurapoints.shim.neoforge.network.PacketDistributor.sendToPlayer(carried, new com.toast.aurapoints.network.CarriedPacket(rig.hold + 1));
		}
		Vec3 c = AuraFx.chest(target);
		var leaves = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.AZALEA_LEAVES.defaultBlockState());
		AuraFx.burst(rig.level, c.x, c.y, c.z, leaves, 25, 0.35, 0.15);
		rig.level.playSound(null, c.x, c.y, c.z, AuraSounds.AZALEA_LEAVES_BREAK.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
		rig.level.playSound(null, c.x, c.y, c.z, AuraSounds.MANGROVE_ROOTS_BREAK.value(), SoundSource.PLAYERS, 1.2f, 0.5f);
		rig.level.playSound(null, c.x, c.y, c.z, AuraSounds.PLAYER_ATTACK_KNOCKBACK.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
	}

	/** Every server tick: grows, carries and draws back each set of roots, then removes them. */
	public static void tickAll(MinecraftServer server) {
		if (RIGS.isEmpty()) return;
		for (Iterator<Rig> it = RIGS.iterator(); it.hasNext(); ) {
			Rig rig = it.next();
			rig.age++;
			boolean done = rig.age > rig.grow() + (rig.caught ? rig.hold : 1) + rig.retract || !AuraFx.alive(rig.owner) || rig.owner.serverLevel() != rig.level;
			if (done) {
				for (Vec3 root : rig.roots) erupt(rig.level, root, false);
				remove(rig);
				it.remove();
				continue;
			}
			// the roots arrive: catch what they reached, or whip at nothing
			if (rig.age == rig.grow()) {
				// what the roots visibly reached counts: nothing locked on, but something right at the tip? that one
				if (rig.target == null) rig.target = nearTip(rig);
				LivingEntity t = rig.target;
				if (t != null && t.isAlive() && t.level() == rig.level && t.distanceTo(rig.owner) <= com.toast.aurapoints.config.AuraConfig.VINE_HOOK_RANGE + 4) {
					catchTarget(rig);
					VineHook.yank(rig.owner, t);
				} else {
					rig.target = null;
					VineHook.miss(rig.level, rig.tip);
				}
			}
			update(rig);
			if (rig.age <= rig.grow() && rig.age % 2 == 1) {
				rig.level.playSound(null, rig.roots[0].x, rig.roots[0].y, rig.roots[0].z, AuraSounds.MANGROVE_ROOTS_PLACE.value(), SoundSource.PLAYERS, 1.0f, 0.6f + rig.age * 0.08f);
			}
			if (reach(rig) > 0.05) for (Vec3 root : rig.roots) {
				AuraFx.particles(rig.level, new BlockParticleOption(ParticleTypes.BLOCK, groundAt(rig.level, root)), root.x, root.y + 0.1, root.z, 2, 0.2, 0.05, 0.2, 0.05);
			}
		}
	}

	private static void remove(Rig rig) {
		for (Arm arm : rig.arms) {
			for (List<Display.BlockDisplay> list : List.of(arm.main, arm.joints, arm.wrap, arm.tendrils, arm.tufts)) {
				for (Display.BlockDisplay d : list) kill(d);
			}
			if (arm.mound != null) kill(arm.mound);
		}
		for (Display.BlockDisplay d : rig.coil) kill(d);
		for (Display.BlockDisplay d : rig.coilJoints) kill(d);
		// whatever happens, a carried mob is solid again
		if (rig.target != null) rig.target.noPhysics = false;
	}

	private static void kill(Entity d) {
		LIVE.remove(d.getUUID());
		d.discard();
	}

	/** A root display that loads in but isn't running (left over from a crash): gone at once. */
	public static boolean isLeftover(Entity e) {
		return e instanceof Display.BlockDisplay && e.getTags().contains(TAG) && !LIVE.contains(e.getUUID());
	}

	public static void clearAll() {
		for (Rig rig : RIGS) remove(rig);
		RIGS.clear();
	}

	/** How far below the ground's surface a root starts, so it grows out of the earth instead of standing on it. */
	private static final double ROOT_SINK = 0.6;

	/** {@code at} moved onto the top of the ground below it (up to 3 blocks down, or up out of a block it's in), then ROOT_SINK into it. */
	private static Vec3 planted(ServerLevel level, Vec3 at) {
		BlockPos pos = BlockPos.containing(at.x, at.y + 0.5, at.z);
		for (int i = 0; i < 5; i++) {
			BlockPos below = pos.below();
			var shape = level.getBlockState(below).getCollisionShape(level, below);
			if (!shape.isEmpty()) return new Vec3(at.x, below.getY() + shape.max(net.minecraft.core.Direction.Axis.Y) - ROOT_SINK, at.z);
			pos = below;
		}
		return at.subtract(0, ROOT_SINK, 0);
	}

	/** The flattened lump of earth round a root's base: {@code w} wide, {@code h} high, its bottom at the ground's surface. */
	private static Transformation mound(Rig rig, Vec3 surface, float w, float h) {
		Vector3f t = new Vector3f((float) (surface.x - rig.anchor.x) - w / 2, (float) (surface.y - rig.anchor.y) - h * 0.4f, (float) (surface.z - rig.anchor.z) - w / 2);
		return new Transformation(t, null, new Vector3f(w, h, w), null);
	}

	private static BlockState groundAt(ServerLevel level, Vec3 at) {
		BlockState ground = level.getBlockState(BlockPos.containing(at.x, at.y - 0.5, at.z));
		return ground.isAir() ? Blocks.ROOTED_DIRT.defaultBlockState() : ground;
	}

	/** The ground bursts open where a root comes out (or closes again where it went back in). */
	private static void erupt(ServerLevel level, Vec3 at, boolean out) {
		BlockState ground = groundAt(level, at);
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.1, at.z, out ? 30 : 15, 0.3, 0.1, 0.3, 0.2);
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ROOTED_DIRT.defaultBlockState()), at.x, at.y + 0.1, at.z, 10, 0.25, 0.1, 0.25, 0.15);
		AuraFx.particles(level, ParticleTypes.COMPOSTER, at.x, at.y + 0.3, at.z, 6, 0.25, 0.2, 0.25, 0.0);
		if (out) {
			level.playSound(null, at.x, at.y, at.z, AuraSounds.EVOKER_FANGS_ATTACK.value(), SoundSource.PLAYERS, 0.9f, 0.7f);
			level.playSound(null, at.x, at.y, at.z, AuraSounds.ROOTED_DIRT_BREAK.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
		} else {
			level.playSound(null, at.x, at.y, at.z, AuraSounds.ROOTED_DIRT_PLACE.value(), SoundSource.PLAYERS, 1.0f, 0.7f);
		}
	}
}
