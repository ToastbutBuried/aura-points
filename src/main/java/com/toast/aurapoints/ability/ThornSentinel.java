package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Thorn Sentinel (Verdant, World Tree Bow). Sneak + right-click shoots a seed (an item display, falling
 * in an arc); where it lands a thorn tree grows out of the ground - a crooked trunk, thorny branches, a
 * leafy crown and a pod on a branch (geo/SentinelTree, drawn by GeckoLib) - and for THORN_SENTINEL_TICKS it turns
 * to the nearest enemy it can see and shoots thorns at it from the pod. Then it withers back into the ground.
 */
final class ThornSentinel {

	private ThornSentinel() {
	}

	private static final int GREEN = 0x4FB848, DARK = 0x2D5A1E, GROW_TICKS = 16, WITHER_TICKS = 14, SHOOT_TICKS = 7;
	/** The pod on the model: this high over the root and this far in front of the trunk (blocks). */
	private static final double POD_HEIGHT = 2.5, POD_FORWARD = 0.95;

	private static final class Seed {
		final UUID owner;
		final ServerLevel level;
		final RootCanvas canvas;
		final Display.ItemDisplay display;
		Vec3 pos, vel;
		double travelled;

		Seed(UUID owner, ServerLevel level, Vec3 pos, Vec3 vel) {
			this.owner = owner;
			this.level = level;
			this.pos = pos;
			this.vel = vel;
			this.canvas = new RootCanvas(level, BlockPos.containing(pos));
			this.display = canvas.item(new ItemStack(com.toast.aurapoints.AuraPoints.THORN_SEED.get()), pos);
		}
	}

	private static final class Thorn {
		Vec3 pos;
		final Vec3 vel;
		int age;

		Thorn(Vec3 pos, Vec3 vel) {
			this.pos = pos;
			this.vel = vel;
		}
	}

	private static final class Tree {
		final UUID owner;
		final ServerLevel level;
		final Vec3 base, crown;
		final com.toast.aurapoints.geo.SentinelTree model;
		final List<Thorn> thorns = new ArrayList<>();
		int age, shotAt = -100;

		Tree(UUID owner, ServerLevel level, Vec3 base) {
			this.owner = owner;
			this.level = level;
			this.base = base;
			this.crown = base.add(0, 3.0, 0);
			this.model = com.toast.aurapoints.AuraPoints.SENTINEL_TREE.get().create(level);
		}

		void discard() {
			if (model != null) model.discard();
		}
	}

	private static final List<Seed> SEEDS = new ArrayList<>();
	private static final List<Tree> TREES = new ArrayList<>();

	static boolean start(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 look = player.getLookAngle();
		Vec3 from = player.getEyePosition().add(look.scale(0.6)).add(0, -0.1, 0);
		SEEDS.add(new Seed(player.getUUID(), level, from, look.scale(AuraConfig.THORN_SENTINEL_SEED_SPEED)));
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.ARROW_SHOOT.value(), SoundSource.PLAYERS, 1.0f, 0.7f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.AZALEA_LEAVES_BREAK.value(), SoundSource.PLAYERS, 1.0f, 1.2f);
		return true;
	}

	static void tickAll(MinecraftServer server) {
		if (!SEEDS.isEmpty()) {
			for (Iterator<Seed> it = SEEDS.iterator(); it.hasNext(); ) {
				Seed s = it.next();
				boolean done;
				try {
					done = fly(server, s);
				} catch (Exception e) {
					AuraPoints.LOGGER.error("Thorn Sentinel seed failed", e);
					done = true;
				}
				if (done) {
					s.canvas.discardAll();
					it.remove();
				}
			}
		}
		if (!TREES.isEmpty()) {
			for (Iterator<Tree> it = TREES.iterator(); it.hasNext(); ) {
				Tree t = it.next();
				boolean done;
				try {
					done = tick(server, t);
				} catch (Exception e) {
					AuraPoints.LOGGER.error("Thorn Sentinel failed", e);
					done = true;
				}
				if (done) {
					t.discard();
					it.remove();
				}
			}
		}
	}

	// ---------------------------------------------------------------
	// The seed
	// ---------------------------------------------------------------

	private static boolean fly(MinecraftServer server, Seed s) {
		ServerPlayer owner = Handhelds.player(server, s.owner);
		if (owner == null || owner.level() != s.level) return true;
		ServerLevel level = s.level;
		Vec3 next = s.pos.add(s.vel);
		BlockHitResult block = level.clip(new ClipContext(s.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, owner));
		Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
		var hit = ProjectileUtil.getEntityHitResult(level, owner, s.pos, end, new AABB(s.pos, end).inflate(0.5), e -> Handhelds.enemy(owner, e), 0.3f);
		if (hit != null) {
			plant(owner, s, hit.getEntity().position());
			return true;
		}
		if (block.getType() != HitResult.Type.MISS) {
			plant(owner, s, block.getLocation());
			return true;
		}
		s.travelled += s.vel.length();
		s.pos = next;
		s.vel = s.vel.scale(0.99).add(0, -0.05, 0);
		if (s.travelled > AuraConfig.THORN_SENTINEL_SEED_RANGE || s.pos.y < level.getMinBuildHeight()) {
			plant(owner, s, s.pos);
			return true;
		}
		RootCanvas.moveTo(s.display, s.pos);
		RootCanvas.place(s.display, s.pos, new Quaternionf().rotateZ(s.travelled > 0 ? (float) s.travelled : 0f), 0.6f, 1);
		AuraFx.particles(level, AuraFx.dust(GREEN, 0.9f), s.pos.x, s.pos.y, s.pos.z, 2, 0.05, 0.05, 0.05, 0);
		AuraFx.particles(level, ParticleTypes.SPORE_BLOSSOM_AIR, s.pos.x, s.pos.y, s.pos.z, 1, 0.1, 0.1, 0.1, 0);
		return false;
	}

	/** Where the seed came down: on the ground below it (it slides off walls), or nowhere if there is none. */
	private static void plant(ServerPlayer owner, Seed s, Vec3 at) {
		ServerLevel level = s.level;
		double y = RootCanvas.surfaceY(level, at.add(0, 0.2, 0), 1, 12);
		if (Double.isNaN(y)) {
			AuraFx.particles(level, ParticleTypes.COMPOSTER, at.x, at.y, at.z, 10, 0.2, 0.2, 0.2, 0);
			return;
		}
		Vec3 base = new Vec3(at.x, y, at.z);
		Tree t = new Tree(owner.getUUID(), level, base);
		build(t);
		TREES.add(t);
		var dirt = new BlockParticleOption(ParticleTypes.BLOCK, RootCanvas.groundBlock(level, base));
		AuraFx.particles(level, dirt, base.x, base.y + 0.2, base.z, 40, 0.6, 0.2, 0.6, 0.15);
		AuraFx.particles(level, ParticleTypes.HAPPY_VILLAGER, base.x, base.y + 1, base.z, 20, 0.8, 1.0, 0.8, 0);
		level.playSound(null, base.x, base.y, base.z, AuraSounds.ROOTED_DIRT_BREAK.value(), SoundSource.PLAYERS, 1.5f, 0.6f);
		level.playSound(null, base.x, base.y, base.z, AuraSounds.BONE_MEAL_USE.value(), SoundSource.PLAYERS, 1.5f, 0.6f);
	}

	// ---------------------------------------------------------------
	// The tree
	// ---------------------------------------------------------------

	/** The tree itself: one GeckoLib model (geo/SentinelTree) that sprouts out of the ground, turned a random way to start. */
	private static void build(Tree t) {
		if (t.model == null) return;
		float yaw = t.level.random.nextFloat() * 360f;
		t.model.moveTo(t.base.x, t.base.y, t.base.z, yaw, 0);
		t.model.setYHeadRot(yaw);
		t.level.addFreshEntity(t.model);
	}

	/** Where its thorns leave from: the pod on the front branch, for a tree facing {@code yaw}. */
	private static Vec3 muzzle(Tree t, float yaw) {
		double r = Math.toRadians(yaw);
		return t.base.add(-Math.sin(r) * POD_FORWARD, POD_HEIGHT, Math.cos(r) * POD_FORWARD);
	}

	private static boolean tick(MinecraftServer server, Tree t) {
		ServerPlayer owner = Handhelds.player(server, t.owner);
		int age = t.age++;
		int life = Math.max(GROW_TICKS, AuraConfig.THORN_SENTINEL_TICKS);
		if (owner == null || age > life + WITHER_TICKS) return true;
		ServerLevel level = t.level;
		if (t.model != null) {
			t.model.keepAlive();
			if (age < life && t.model.state() == com.toast.aurapoints.geo.SentinelTree.SHOOT && age - t.shotAt >= SHOOT_TICKS) {
				t.model.setState(com.toast.aurapoints.geo.SentinelTree.IDLE);
			}
		}

		if (age == life) {
			if (t.model != null) t.model.setState(com.toast.aurapoints.geo.SentinelTree.GONE);
			level.playSound(null, t.base.x, t.base.y, t.base.z, AuraSounds.AZALEA_LEAVES_BREAK.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
			AuraFx.particles(level, ParticleTypes.FALLING_SPORE_BLOSSOM, t.crown.x, t.crown.y, t.crown.z, 20, 1.0, 0.6, 1.0, 0);
		}
		if (age < life && age % 10 == 0) AuraFx.particles(level, ParticleTypes.FALLING_SPORE_BLOSSOM, t.crown.x, t.crown.y + 0.5, t.crown.z, 2, 1.0, 0.3, 1.0, 0);

		// a thorn at the nearest enemy it can see
		if (age >= GROW_TICKS && age < life && (age - GROW_TICKS) % Math.max(1, AuraConfig.THORN_SENTINEL_SHOT_TICKS) == 0) {
			Vec3 eye = t.crown.add(0, -0.3, 0);
			LivingEntity target = null;
			double best = Double.MAX_VALUE;
			for (LivingEntity e : Handhelds.enemies(owner, level, eye, AuraConfig.THORN_SENTINEL_REACH)) {
				double d = AuraFx.chest(e).distanceToSqr(eye);
				if (d < best && level.clip(new ClipContext(eye, AuraFx.chest(e), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner)).getType() == HitResult.Type.MISS) {
					best = d;
					target = e;
				}
			}
			if (target != null) {
				// the tree turns its pod to the target and fires from it
				Vec3 to = target.position().subtract(t.base);
				float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
				Vec3 from = muzzle(t, yaw);
				if (t.model != null) {
					t.model.setYRot(yaw);
					t.model.setYHeadRot(yaw);
					t.model.setState(com.toast.aurapoints.geo.SentinelTree.SHOOT);
				}
				t.shotAt = age;
				Vec3 aimAt = AuraFx.chest(target).add(target.getDeltaMovement().scale(3));
				t.thorns.add(new Thorn(from, aimAt.subtract(from).normalize().scale(1.4)));
				level.playSound(null, from.x, from.y, from.z, AuraSounds.SWEET_BERRY_BUSH_PICK_BERRIES.value(), SoundSource.PLAYERS, 1.0f, 1.6f);
				level.playSound(null, from.x, from.y, from.z, AuraSounds.ARROW_SHOOT.value(), SoundSource.PLAYERS, 0.6f, 1.8f);
			}
		}

		// the thorns in flight
		for (Iterator<Thorn> it = t.thorns.iterator(); it.hasNext(); ) {
			Thorn th = it.next();
			Vec3 next = th.pos.add(th.vel);
			BlockHitResult block = level.clip(new ClipContext(th.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
			Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
			var hit = ProjectileUtil.getEntityHitResult(level, owner, th.pos, end, new AABB(th.pos, end).inflate(0.4), e -> Handhelds.enemy(owner, e), 0.2f);
			AuraFx.line(level, th.pos, end, AuraFx.dust(DARK, 0.7f), 3);
			AuraFx.particles(level, ParticleTypes.CRIT, end.x, end.y, end.z, 1, 0, 0, 0, 0);
			if (hit != null && hit.getEntity() instanceof LivingEntity victim) {
				victim.invulnerableTime = 0;
				Handhelds.hurt(owner, victim, AuraConfig.THORN_SENTINEL_SHOT_DAMAGE);
				if (AuraConfig.THORN_SENTINEL_POISON_AMPLIFIER >= 0 && AuraConfig.THORN_SENTINEL_POISON_TICKS > 0) {
					victim.addEffect(new MobEffectInstance(MobEffects.POISON, AuraConfig.THORN_SENTINEL_POISON_TICKS, AuraConfig.THORN_SENTINEL_POISON_AMPLIFIER), owner);
				}
				Vec3 c = AuraFx.chest(victim);
				AuraFx.particles(level, AuraFx.dust(GREEN, 1.0f), c.x, c.y, c.z, 6, 0.2, 0.2, 0.2, 0);
				it.remove();
			} else if (block.getType() != HitResult.Type.MISS || ++th.age > 20) {
				it.remove();
			} else {
				th.pos = next;
			}
		}
		return false;
	}

	static void clearAll() {
		for (Seed s : SEEDS) s.canvas.discardAll();
		for (Tree t : TREES) t.discard();
		SEEDS.clear();
		TREES.clear();
	}
}
