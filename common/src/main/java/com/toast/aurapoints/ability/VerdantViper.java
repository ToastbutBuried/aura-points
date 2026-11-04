package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verdant Viper, Verdant's third active. A snake head bursts out of the ground next to you and slithers
 * over the ground toward your crosshair (VIPER_SPEED, up to VIPER_RANGE away), dragging a root behind
 * it half out of the ground. It bites the first mob or player it reaches (damage + poison) and is done.
 * While it is out you are rooted to the spot - only the camera works, to steer it - and you can't be
 * hurt, knocked back, eat or heal. Only other players can hit it; if they kill it you take
 * VIPER_KILL_DAMAGE and are stunned, the longer the sooner it died. It also ends at a wall, out of range,
 * or when called back (the key again, once it has been out VIPER_CANCEL_AFTER_TICKS). Its trail stays
 * VIPER_TRAIL_STAY_TICKS, then withers black and sinks back into the ground.
 */
public final class VerdantViper {

	private VerdantViper() {
	}

	private static final double TRAIL_STEP = 0.9, TRAIL_LIFT = 0.12, HEAD_LIFT = 0.05;
	/** How far along the crosshair it looks for someone to home in on. */
	private static final double ENTITY_AIM = 96;

	private enum End { BITE, KILLED, WALL, RANGE, RECALL, GONE }

	private static final class Viper {
		final ServerPlayer caster;
		final RootCanvas canvas;
		Vec3 pos, dir;
		int age, hits;
		com.toast.aurapoints.geo.ViperHead head;
		Interaction box;
		Vec3 lastTrail;
		int trailCount;
		LivingEntity prey;
		int preyTicks;
		/** Climbing a wall: going straight up its face at (x, z) to the top at y (NaN = not climbing). */
		double climbX, climbZ, climbTo = Double.NaN;

		Viper(ServerPlayer caster, Vec3 pos, Vec3 dir) {
			this.caster = caster;
			this.pos = pos;
			this.dir = dir;
			this.canvas = new RootCanvas(caster.serverLevel(), BlockPos.containing(pos).above());
		}
	}

	/** A finished trail: waits, withers black, sinks, gone. */
	private static final class Trail {
		RootCanvas canvas; // the viper's: its head pieces wither with the trail
		final ArrayDeque<Seg> segs = new ArrayDeque<>();
		int age = -1; // -1 while its viper still runs
	}

	private record Seg(Display.BlockDisplay d, Vec3 a, Vec3 b) {
	}

	private static final Map<UUID, Viper> VIPERS = new HashMap<>();
	private static final Map<Viper, Trail> TRAILS = new HashMap<>();
	private static final List<Trail> WITHERING = new ArrayList<>();

	/** True while this player is steering a viper (immune, can't move, eat or heal). */
	public static boolean isSteering(LivingEntity e) {
		return e instanceof ServerPlayer p && VIPERS.containsKey(p.getUUID());
	}

	// ---------------------------------------------------------------
	// The press
	// ---------------------------------------------------------------

	public static boolean press(ServerPlayer player, long now) {
		Viper live = VIPERS.get(player.getUUID());
		if (live != null) {
			int wait = AuraConfig.VIPER_CANCEL_AFTER_TICKS - live.age;
			if (wait > 0) {
				player.displayClientMessage(Component.literal(String.format("You can call the viper back in %.1fs", wait / 20.0)).withStyle(ChatFormatting.GRAY), true);
				return false;
			}
			end(live, End.RECALL, now);
			return true;
		}
		if (!AuraAbilities.checkCooldown(player, Ability.VERDANT_VIPER, AuraConfig.VIPER_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = player.serverLevel();
		double yaw = Math.toRadians(player.getYRot());
		Vec3 front = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 side = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		Vec3 spot = player.position().add(front.scale(0.8)).add(side.scale(-0.9));
		double y = RootCanvas.surfaceY(level, spot, 1, 3);
		if (Double.isNaN(y)) {
			player.displayClientMessage(Component.literal("The viper needs ground to come out of.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		Viper v = new Viper(player, new Vec3(spot.x, y, spot.z), front);
		buildHead(v);
		v.lastTrail = v.pos;
		TRAILS.put(v, new Trail());
		VIPERS.put(player.getUUID(), v);
		Rooted.lock(player, player.position(), false);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_VIPER, 0);
		// after the summon sweep: the steering pose until the viper is gone
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.VIPER_STEER);
		var dirt = new BlockParticleOption(ParticleTypes.BLOCK, RootCanvas.groundBlock(level, v.pos));
		AuraFx.particles(level, dirt, v.pos.x, v.pos.y + 0.1, v.pos.z, 25, 0.3, 0.1, 0.3, 0.15);
		level.playSound(null, v.pos.x, v.pos.y, v.pos.z, AuraSounds.ROOTED_DIRT_BREAK.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
		level.playSound(null, v.pos.x, v.pos.y, v.pos.z, AuraSounds.CAT_HISS.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
		player.displayClientMessage(Component.literal("✦ Steer the viper with your view ✦").withStyle(ChatFormatting.DARK_GREEN), true);
		return true;
	}

	// ---------------------------------------------------------------
	// The head
	// ---------------------------------------------------------------

	/** A Venus flytrap on a green stem (geo/ViperHead, drawn by GeckoLib), and the hitbox players strike it on. */
	private static void buildHead(Viper v) {
		v.head = com.toast.aurapoints.AuraPoints.VIPER_HEAD.get().create(v.canvas.level);
		if (v.head != null) {
			v.head.setScale((float) Math.max(0.1, AuraConfig.VIPER_SCALE));
			v.head.moveTo(v.pos.x, v.pos.y, v.pos.z, yaw(v), 0);
			v.canvas.level.addFreshEntity(v.head);
		}
		// a hitbox no smaller than this, however small the trap is drawn, so it can still be hit
		float hw = (float) Math.max(0.6, 0.75 * AuraConfig.VIPER_SCALE), hh = (float) Math.max(0.6, 1.0 * AuraConfig.VIPER_SCALE);
		v.box = v.canvas.hitbox(v.pos, hw, hh);
		drawHead(v);
	}

	private static float yaw(Viper v) {
		return (float) Math.toDegrees(Math.atan2(-v.dir.x, v.dir.z));
	}

	/** Moves the head (it gapes and sways by itself) and the hitbox to where the viper is now. */
	private static void drawHead(Viper v) {
		v.box.setPos(v.pos.x, v.pos.y, v.pos.z);
		if (v.head == null) return;
		v.head.keepAlive();
		v.head.setPos(v.pos.x, v.pos.y + HEAD_LIFT, v.pos.z);
		v.head.setYRot(yaw(v));
		v.head.setYHeadRot(yaw(v));
	}

	// ---------------------------------------------------------------
	// Every tick
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		long now = server.getTickCount();
		for (Viper v : new ArrayList<>(VIPERS.values())) {
			if (!v.caster.isAlive() || !AuraFx.alive(v.caster) || v.caster.serverLevel() != v.canvas.level) {
				end(v, End.GONE, now);
				continue;
			}
			step(v, now);
		}
		for (Iterator<Trail> it = WITHERING.iterator(); it.hasNext(); ) {
			if (wither(it.next())) it.remove();
		}
	}

	private static void step(Viper v, long now) {
		v.age++;
		ServerLevel level = v.canvas.level;
		Vec3 want = aim(v);
		Vec3 flat = new Vec3(want.x - v.pos.x, 0, want.z - v.pos.z);
		double speed = Math.max(0.05, AuraConfig.VIPER_SPEED);
		if (!Double.isNaN(v.climbTo)) {
			// up the wall face, a little slower; on top it carries on as normal
			double up = Math.min(Math.max(0.05, AuraConfig.VIPER_CLIMB_SPEED), v.climbTo - v.pos.y);
			v.pos = new Vec3(v.pos.x, v.pos.y + up, v.pos.z);
			if (v.climbTo - v.pos.y < 1.0e-3) {
				v.pos = new Vec3(v.climbX, v.climbTo, v.climbZ);
				v.climbTo = Double.NaN;
			}
			if (v.pos.distanceTo(v.lastTrail) >= TRAIL_STEP) lay(v);
		} else if (flat.lengthSqr() > 0.04) {
			Vec3 to = flat.normalize();
			v.dir = turn(v.dir, to, Math.toRadians(AuraConfig.VIPER_TURN_DEGREES));
			// still facing away after the turn: it slows down and swings round on the spot instead of
			// circling wide round the target
			double off = Math.acos(Math.max(-1, Math.min(1, v.dir.dot(to))));
			if (off > Math.toRadians(25)) speed *= Math.max(0.15, Math.cos(off));
			double go = Math.min(speed, flat.length());
			// in steps of at most half a block, so it never skips over a wall, a dip or someone to bite
			int steps = (int) Math.ceil(go / 0.5);
			for (int i = 0; i < steps; i++) {
				Vec3 next = v.pos.add(v.dir.scale(go / steps));
				BlockPos feet = BlockPos.containing(next.x, next.y + 0.5, next.z);
				if (!level.isLoaded(feet)) {
					end(v, End.RANGE, now);
					return;
				}
				int climb = Math.max(1, (int) Math.ceil(AuraConfig.VIPER_CLIMB_HEIGHT));
				double y = RootCanvas.surfaceY(level, next, climb, 4);
				if (Double.isNaN(y) || y - v.pos.y > AuraConfig.VIPER_CLIMB_HEIGHT + 0.01 || blocked(level, new Vec3(next.x, y, next.z))) {
					end(v, End.WALL, now);
					return;
				}
				if (y - v.pos.y > 0.6) {
					// a wall it can get over - unless there's no room to go up its face (a low tunnel's ceiling)
					if (!openAbove(level, v.pos, y)) {
						end(v, End.WALL, now);
						return;
					}
					// climb its face first (from the next tick on), then go on
					v.climbX = next.x;
					v.climbZ = next.z;
					v.climbTo = y;
					break;
				}
				v.pos = new Vec3(next.x, y, next.z);
				if (v.pos.distanceTo(v.lastTrail) >= TRAIL_STEP) lay(v);
				if (tryBite(v, now)) return;
			}
			if (v.pos.distanceTo(v.caster.position()) > AuraConfig.VIPER_RANGE) {
				end(v, End.RANGE, now);
				return;
			}
		}
		if (tryBite(v, now)) return;
		if (v.age % 10 == 0) v.canvas.light = RootCanvas.lightAt(level, BlockPos.containing(v.pos).above());
		drawHead(v);
		if (v.age % 3 == 0) {
			var dirt = new BlockParticleOption(ParticleTypes.BLOCK, RootCanvas.groundBlock(level, v.pos));
			AuraFx.particles(level, dirt, v.pos.x, v.pos.y + 0.05, v.pos.z, 2, 0.15, 0.02, 0.15, 0.05);
		}
		if (v.age % 14 == 0) level.playSound(null, v.pos.x, v.pos.y, v.pos.z, AuraSounds.MANGROVE_ROOTS_STEP.value(), SoundSource.PLAYERS, 0.8f, 1.2f);
		if (v.age % 50 == 0) level.playSound(null, v.pos.x, v.pos.y, v.pos.z, AuraSounds.CAT_HISS.value(), SoundSource.PLAYERS, 0.5f, 0.5f);
	}

	/**
	 * Bites whoever is within VIPER_BITE_RADIUS of the head: measured to their hitbox, from anywhere
	 * between the ground and a little over the head (so someone jumping over it still gets bitten).
	 */
	private static boolean tryBite(Viper v, long now) {
		double r = Math.max(0.3, AuraConfig.VIPER_BITE_RADIUS);
		Vec3 low = v.pos.add(v.dir.scale(0.3)), high = low.add(0, 1.2, 0);
		AABB reach = new AABB(low, high).inflate(r);
		LivingEntity prey = null;
		double best = Double.MAX_VALUE;
		for (LivingEntity e : v.canvas.level.getEntitiesOfClass(LivingEntity.class, reach, e -> VineHook.canHook(v.caster, e))) {
			double d = e.getBoundingBox().distanceToSqr(low);
			if (d < best) {
				best = d;
				prey = e;
			}
		}
		if (prey == null) return false;
		bite(v, prey, now);
		return true;
	}

	/**
	 * Where to go: the mob or player under (or close to, VIPER_AIM_ASSIST) the caster's crosshair - it
	 * homes in on them - else the block the crosshair rests on, else straight on the way they look.
	 */
	private static Vec3 aim(Viper v) {
		ServerPlayer p = v.caster;
		ServerLevel level = v.canvas.level;
		Vec3 eye = p.getEyePosition(), look = p.getLookAngle().normalize();
		double range = Math.max(4, AuraConfig.VIPER_RANGE);
		// clipped in short pieces, never into unloaded chunks (a clip there would load them)
		Vec3 from = eye, end = null;
		boolean hitBlock = false;
		for (double done = 0; done < range; done += 16) {
			Vec3 to = eye.add(look.scale(Math.min(range, done + 16)));
			if (!level.isLoaded(BlockPos.containing(to))) break;
			HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			end = hit.getLocation();
			if (hit.getType() != HitResult.Type.MISS) {
				hitBlock = true;
				break;
			}
			from = to;
		}
		// someone along the line of sight (up to ENTITY_AIM blocks out): the viper goes for them
		Vec3 pickEnd = end == null ? eye.add(look.scale(4)) : end;
		if (pickEnd.distanceTo(eye) > ENTITY_AIM) pickEnd = eye.add(look.scale(ENTITY_AIM));
		double assist = Math.max(0, AuraConfig.VIPER_AIM_ASSIST);
		var picked = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, p, eye, pickEnd,
				new AABB(eye, pickEnd).inflate(assist + 1), e -> VineHook.canHook(p, e), (float) assist);
		if (picked != null) {
			v.prey = (LivingEntity) picked.getEntity();
			v.preyTicks = 10;
		} else if (v.preyTicks > 0 && v.prey != null && v.prey.isAlive()) {
			v.preyTicks--; // a moment of slack when the crosshair slips off them
		} else {
			v.prey = null;
		}
		if (v.prey != null) return v.prey.position();
		if (hitBlock) return end;
		Vec3 flat = new Vec3(look.x, 0, look.z);
		if (flat.lengthSqr() < 1.0e-4) return v.pos; // looking straight up or down: hold still
		return v.pos.add(flat.normalize().scale(4));
	}

	private static Vec3 turn(Vec3 from, Vec3 to, double max) {
		double a = Math.atan2(from.z, from.x), b = Math.atan2(to.z, to.x);
		double d = Math.atan2(Math.sin(b - a), Math.cos(b - a));
		double c = a + Math.max(-max, Math.min(max, d));
		return new Vec3(Math.cos(c), 0, Math.sin(c));
	}

	/** Nothing solid straight above {@code from} up to a bit over {@code top}: room to climb a wall's face. */
	private static boolean openAbove(ServerLevel level, Vec3 from, double top) {
		for (double y = from.y + 0.5; y <= top + 0.5; y += 1.0) {
			BlockPos at = BlockPos.containing(from.x, y, from.z);
			if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return false;
		}
		return true;
	}

	/** Something solid right where the head would be on top of {@code ground} (walls up to VIPER_CLIMB_HEIGHT it climbs). */
	private static boolean blocked(ServerLevel level, Vec3 ground) {
		BlockPos at = BlockPos.containing(ground.x, ground.y + 0.3, ground.z);
		return !level.getBlockState(at).getCollisionShape(level, at).isEmpty();
	}

	/** One more length of root on the trail, from the last point to here, half out of the ground. */
	private static void lay(Viper v) {
		Trail t = TRAILS.get(v);
		double k = Math.max(0.1, AuraConfig.VIPER_SCALE);
		Vec3 a = v.lastTrail.add(0, TRAIL_LIFT * k, 0), b = v.pos.add(0, TRAIL_LIFT * k, 0);
		// a gentle side-to-side, like the track a snake leaves
		Vec3 f = b.subtract(a);
		Vec3 s = new Vec3(-f.z, 0, f.x).normalize().scale(Math.sin(v.trailCount * 1.3) * 0.12 * k);
		b = b.add(s);
		v.canvas.light = RootCanvas.lightAt(v.canvas.level, BlockPos.containing(v.pos).above());
		// a green stem: bamboo-green, mossy here and there
		BlockState wood = v.trailCount % 4 == 3 ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.BAMBOO_BLOCK.defaultBlockState();
		Display.BlockDisplay d = v.canvas.piece(wood, a);
		RootCanvas.hide(d, a, 0);
		RootCanvas.stretch(d, a, b, (float) (AuraConfig.VIPER_TRAIL_WIDTH * (1 + 0.12 * Math.sin(v.trailCount * 0.7))), 1.25f, 2);
		t.segs.addLast(new Seg(d, a, b));
		v.trailCount++;
		v.lastTrail = v.pos.add(s);
		// too long: the oldest end already withers away
		while (t.segs.size() > Math.max(8, AuraConfig.VIPER_TRAIL_MAX)) {
			Seg old = t.segs.pollFirst();
			v.canvas.remove(old.d());
		}
	}

	private static void bite(Viper v, LivingEntity prey, long now) {
		ServerLevel level = v.canvas.level;
		ServerPlayer caster = v.caster;
		Vec3 c = AuraFx.chest(prey);
		if (AuraConfig.VIPER_DAMAGE > 0) {
			prey.hurt(level.damageSources().playerAttack(caster), AuraAbilities.vsMob(prey, AuraAbilities.flowDamage(caster, AuraConfig.VIPER_DAMAGE)));
		}
		Entanglement.poison(prey, caster, Branch.is(caster, Branch.GROVE_WARDEN) ? AuraConfig.VERDANT_GROVE_VIPER_POISON_AMPLIFIER : AuraConfig.VIPER_POISON_AMPLIFIER, AuraConfig.VIPER_POISON_TICKS);
		AuraFx.particles(level, ParticleTypes.ITEM_SLIME, c.x, c.y, c.z, 12, 0.25, 0.3, 0.25, 0.05);
		AuraFx.particles(level, AuraFx.dust(0x5FA83A, 1.2f), c.x, c.y, c.z, 10, 0.3, 0.3, 0.3, 0.0);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.PHANTOM_BITE.value(), SoundSource.PLAYERS, 1.2f, 0.7f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.SPIDER_HURT.value(), SoundSource.PLAYERS, 0.6f, 1.4f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.EVOKER_FANGS_ATTACK.value(), SoundSource.PLAYERS, 1.0f, 1.3f);
		AuraStats.add(caster, AuraStats.VIPER_BITES, 1);
		// the trap snaps shut on them
		if (v.head != null) v.head.setState(com.toast.aurapoints.geo.ViperHead.BITE);
		end(v, End.BITE, now);
	}

	/** A hit on one of our hitboxes; true if it was a viper's (the caster can't hit their own). */
	public static boolean onHitbox(ServerPlayer attacker, Entity box) {
		for (Viper v : VIPERS.values()) {
			if (v.box != box) continue;
			if (attacker == v.caster) return true;
			ServerLevel level = v.canvas.level;
			AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()), v.pos.x, v.pos.y + 0.3, v.pos.z, 12, 0.2, 0.2, 0.2, 0.1);
			level.playSound(null, v.pos.x, v.pos.y, v.pos.z, AuraSounds.MOSS_BREAK.value(), SoundSource.PLAYERS, 1.0f, 1.2f);
			if (++v.hits >= Math.max(1, AuraConfig.VIPER_HITS)) killed(v, attacker, level.getServer().getTickCount());
			return true;
		}
		return false;
	}

	/** Killed by another player: the caster pays for it - the quicker it died, the longer the stun. */
	private static void killed(Viper v, ServerPlayer killer, long now) {
		ServerPlayer caster = v.caster;
		ServerLevel level = v.canvas.level;
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()), v.pos.x, v.pos.y + 0.3, v.pos.z, 30, 0.3, 0.3, 0.3, 0.15);
		level.playSound(null, v.pos.x, v.pos.y, v.pos.z, AuraSounds.CAT_HURT.value(), SoundSource.PLAYERS, 1.0f, 0.5f);
		level.playSound(null, v.pos.x, v.pos.y, v.pos.z, AuraSounds.MANGROVE_ROOTS_BREAK.value(), SoundSource.PLAYERS, 1.2f, 0.7f);
		int age = v.age;
		end(v, End.KILLED, now); // first, so the caster is no longer immune
		double share = Math.min(1.0, age / (double) Math.max(1, AuraConfig.VIPER_STUN_SCALE_TICKS));
		int stun = (int) Math.round(AuraConfig.VIPER_STUN_MAX_TICKS + (AuraConfig.VIPER_STUN_MIN_TICKS - AuraConfig.VIPER_STUN_MAX_TICKS) * share);
		Stun.stun(caster, Math.max(0, stun));
		if (AuraConfig.VIPER_KILL_DAMAGE > 0) {
			AuraAbilities.vsMob(caster, 0); // marks it an ability hit: Protection doesn't shrink it (magic already skips armor)
			caster.hurt(level.damageSources().indirectMagic(killer, killer), AuraConfig.VIPER_KILL_DAMAGE);
		}
		caster.displayClientMessage(Component.literal("✦ Your viper was killed! ✦").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
		killer.displayClientMessage(Component.literal("✦ Viper killed ✦").withStyle(ChatFormatting.GREEN), true);
	}

	private static void end(Viper v, End why, long now) {
		if (VIPERS.remove(v.caster.getUUID()) == null) return;
		Rooted.unlock(v.caster);
		if (Poses.current(v.caster) == BendingAnimPacket.NONE) NetworkHandler.sendBendAnim(v.caster, BendingAnimPacket.NONE);
		ServerLevel level = v.canvas.level;
		Trail t = TRAILS.remove(v);
		// the head droops and sinks back into the ground (after a bite: once it has held the snap shut a moment)
		var head = v.head;
		if (head != null) {
			if (why == End.GONE) head.discard();
			else {
				int hold = why == End.BITE ? 6 : 0;
				AuraFx.later(level.getServer(), hold, () -> {
					head.keepAlive();
					head.setState(com.toast.aurapoints.geo.ViperHead.GONE);
				});
				AuraFx.later(level.getServer(), hold + 14, head::discard);
			}
		}
		v.canvas.remove(v.box);
		if (why != End.BITE && why != End.KILLED) {
			var dirt = new BlockParticleOption(ParticleTypes.BLOCK, RootCanvas.groundBlock(level, v.pos));
			AuraFx.particles(level, dirt, v.pos.x, v.pos.y + 0.1, v.pos.z, 15, 0.25, 0.1, 0.25, 0.1);
			level.playSound(null, v.pos.x, v.pos.y, v.pos.z, AuraSounds.ROOTED_DIRT_PLACE.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
		}
		if (why == End.WALL) v.caster.displayClientMessage(Component.literal("The viper hit a wall.").withStyle(ChatFormatting.GRAY), true);
		if (why == End.RANGE) v.caster.displayClientMessage(Component.literal("The viper went too far.").withStyle(ChatFormatting.GRAY), true);
		if (t == null) t = new Trail();
		t.canvas = v.canvas;
		t.age = 0;
		WITHERING.add(t);
		if (why != End.GONE) AuraAbilities.markUsed(v.caster, Ability.VERDANT_VIPER, now);
	}

	/** Returns true once the trail is gone. */
	private static boolean wither(Trail t) {
		t.age++;
		int stay = Math.max(0, AuraConfig.VIPER_TRAIL_STAY_TICKS);
		RootCanvas canvas = t.canvas;
		if (t.age == stay) {
			// withers: blackened, then sinks back into the ground
			for (Seg s : t.segs) RootCanvas.setBlock(s.d(), Blocks.BLACKSTONE.defaultBlockState());
			for (Seg s : t.segs) {
				Vec3 down = new Vec3(0, -0.35, 0);
				RootCanvas.stretch(s.d(), s.a().add(down), s.b().add(down), 0.14f, 1.2f, 20);
			}
			if (!t.segs.isEmpty()) {
				Vec3 mid = t.segs.peekLast().b();
				canvas.level.playSound(null, mid.x, mid.y, mid.z, AuraSounds.ROOTED_DIRT_BREAK.value(), SoundSource.PLAYERS, 0.6f, 0.5f);
			}
			int i = 0;
			for (Seg s : t.segs) {
				if (i++ % 4 == 0) AuraFx.particles(canvas.level, ParticleTypes.SMOKE, s.b().x, s.b().y, s.b().z, 1, 0.1, 0.02, 0.1, 0.0);
			}
		}
		if (t.age >= stay + 22) {
			canvas.discardAll();
			return true;
		}
		return false;
	}

	public static void onLogout(ServerPlayer player) {
		Viper v = VIPERS.get(player.getUUID());
		if (v != null) end(v, End.GONE, 0);
	}

	public static void clearAll() {
		for (Viper v : new ArrayList<>(VIPERS.values())) end(v, End.GONE, 0);
		for (Trail t : WITHERING) t.canvas.discardAll();
		WITHERING.clear();
	}
}
