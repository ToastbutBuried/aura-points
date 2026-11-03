package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Earth: Pillar Lift, Boulder Toss and Quake on the Ability keys, Stone Skin and Grounded always on
 * (its Ultimate, Tectonic Rift, is in Ultimates). Every rock is a block display (Props) that goes away again:
 * nothing is placed or broken.
 */
public final class Earth {

	private Earth() {
	}

	// ---------------------------------------------------------------
	// The ground
	// ---------------------------------------------------------------

	/** Stone, dirt, sand, gravel and the like: what Stone Skin works on and what the rocks look like as they are. */
	static boolean natural(BlockState s) {
		return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)
				|| s.is(BlockTags.TERRACOTTA) || s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE)
				|| s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.CLAY) || s.is(Blocks.COBBLED_DEEPSLATE);
	}

	/** The rock to raise from this block: natural ground as it is, anything else (planks, the arena's floor) as stone. */
	static BlockState rock(BlockState s) {
		return natural(s) ? s : Blocks.STONE.defaultBlockState();
	}

	/** What lies under a grass block or sand: its insides, for the lower part of a pillar. */
	static BlockState under(BlockState top) {
		if (top.is(Blocks.GRASS_BLOCK) || top.is(Blocks.PODZOL) || top.is(Blocks.MYCELIUM) || top.is(Blocks.DIRT_PATH)) return Blocks.DIRT.defaultBlockState();
		if (top.is(Blocks.SAND)) return Blocks.SANDSTONE.defaultBlockState();
		if (top.is(Blocks.RED_SAND)) return Blocks.RED_SANDSTONE.defaultBlockState();
		return top;
	}

	/** The top solid block of the column under {@code at}, searching {@code depth} blocks down (null if none). */
	static BlockPos ground(ServerLevel level, Vec3 at, int depth) {
		BlockPos start = BlockPos.containing(at.x, at.y + 0.5, at.z);
		for (int i = 0; i <= depth + 1; i++) {
			BlockPos pos = start.below(i);
			BlockState s = level.getBlockState(pos);
			if (!s.getCollisionShape(level, pos).isEmpty() && s.isCollisionShapeFullBlock(level, pos)) return pos;
		}
		return null;
	}

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
	}

	private static Vec3 flat(Vec3 v) {
		Vec3 f = new Vec3(v.x, 0, v.z);
		return f.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : f.normalize();
	}

	/** Thrown up off the ground: dust and crumbs of {@code block} at {@code at}. */
	static void dust(ServerLevel level, Vec3 at, BlockState block, int count, double spread) {
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.DUST_PILLAR, block), at.x, at.y, at.z, count, spread, 0.1, spread, 0.1);
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, block), at.x, at.y + 0.2, at.z, count, spread, 0.2, spread, 0.15);
	}

	/** The mob or player under the crosshair, up to {@code range} blocks (walls block it), or null. */
	private static LivingEntity aimed(ServerPlayer p, double range) {
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getLookAngle().scale(range));
		HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		Vec3 to = block.getLocation();
		var hit = ProjectileUtil.getEntityHitResult(p.level(), p, eye, to, new AABB(eye, to).inflate(1.0), e -> Handhelds.enemy(p, e), 0.6f);
		return hit != null && hit.getEntity() instanceof LivingEntity l ? l : null;
	}

	// ---------------------------------------------------------------
	// Pillar Lift
	// ---------------------------------------------------------------

	/** Players thrown up by their own pillar: no fall damage until they land. */
	private static final Map<UUID, Integer> SAFE_FALL = new HashMap<>();

	static boolean pillar(ServerPlayer p, long now) {
		if (!AuraAbilities.checkCooldown(p, Ability.PILLAR_LIFT, AuraConfig.EARTH_PILLAR_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = p.serverLevel();
		LivingEntity target = aimed(p, Math.max(2, AuraConfig.EARTH_PILLAR_RANGE));
		Entity on = target != null ? target : p;
		BlockPos g = ground(level, on.position(), target != null ? 8 : 3);
		if (g == null) {
			p.displayClientMessage(Component.literal("No ground to raise a pillar from.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		raisePillar(level, g, 2.6f, 40);
		// everyone standing on it goes up with it
		Vec3 top = Vec3.atBottomCenterOf(g.above());
		List<LivingEntity> riders = new ArrayList<>();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(top, top).inflate(0.9, 1.6, 0.9))) {
			if (e == p || Handhelds.enemy(p, e)) riders.add(e);
		}
		if (target != null && !riders.contains(target)) riders.add(target);
		for (LivingEntity e : riders) {
			if (e == p) continue;
			Handhelds.hurt(p, e, AuraConfig.EARTH_PILLAR_DAMAGE);
			Vec3 dm = e.getDeltaMovement();
			e.setDeltaMovement(dm.x * 0.3, AuraConfig.EARTH_PILLAR_LAUNCH, dm.z * 0.3);
			e.hurtMarked = true;
		}
		if (target == null || riders.contains(p)) {
			Vec3 dm = p.getDeltaMovement();
			p.setDeltaMovement(dm.x, AuraConfig.EARTH_PILLAR_SELF_LAUNCH, dm.z);
			p.hurtMarked = true;
			p.fallDistance = 0;
			SAFE_FALL.put(p.getUUID(), 0);
		}
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_EARTH, 0);
		AuraAbilities.markUsed(p, Ability.PILLAR_LIFT, now);
		return true;
	}

	/**
	 * A pillar the width of one block that bursts up {@code height} blocks out of the ground block {@code g} - its top
	 * the ground itself (grass stays grass), stone or dirt below - stands {@code hold} ticks, then sinks back.
	 */
	static void raisePillar(ServerLevel level, BlockPos g, float height, int hold) {
		BlockState top = rock(level.getBlockState(g));
		BlockState mid = rock(level.getBlockState(g.below()));
		if (mid.is(top.getBlock()) && top != under(top)) mid = under(top);
		BlockState low = rock(level.getBlockState(g.below(2)));
		Vec3 at = new Vec3(g.getX() + 0.5, g.getY() + 1, g.getZ() + 0.5);
		BlockState[] blocks = {low, mid, top};
		int life = 3 + hold + 14;
		for (int i = 0; i < 3; i++) {
			// hidden: all of it down in the ground; shown: the top block's top at height
			float y = height - 3 + i;
			var hidden = Props.box(0, -3.0 + i - 0.02, 0, 0.98f, 1f, 0.98f);
			var d = Props.spawn(level, at, blocks[i], hidden, life, 0);
			Props.setLater(d, 1, Props.box(0, y, 0, 1.0f, 1f, 1.0f), 3);
			Props.setLater(d, 3 + hold, hidden, 12);
		}
		dust(level, at, top, 24, 0.45);
		AuraFx.ring(level, at.x, at.y + 0.1, at.z, 1.1, new BlockParticleOption(ParticleTypes.DUST_PILLAR, top), 14);
		sound(level, at, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), 1.1f, 0.8f);
		sound(level, at, SoundEvents.STONE_BREAK, 1.2f, 0.6f);
		sound(level, at, SoundEvents.ROOTED_DIRT_BREAK, 1.0f, 0.5f);
		var server = level.getServer();
		AuraFx.later(server, 3 + hold, () -> {
			sound(level, at, SoundEvents.GRAVEL_BREAK, 0.9f, 0.5f);
			AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, top), at.x, at.y + height * 0.5, at.z, 12, 0.4, height * 0.3, 0.4, 0.05);
		});
	}

	// ---------------------------------------------------------------
	// Boulder Toss
	// ---------------------------------------------------------------

	private static final ResourceLocation BOULDER_SLOW = ResourceLocation.fromNamespaceAndPath("aurapoints", "earth_boulder");

	private static final class Boulder {
		final UUID owner;
		final ServerLevel level;
		final BlockState block;
		final List<Display.BlockDisplay> parts = new ArrayList<>();
		final List<Vector3f> offsets = new ArrayList<>();
		final List<Float> sizes = new ArrayList<>();
		final Vec3 from;
		final Quaternionf turn = new Quaternionf();
		final Vector3f axis;
		Vec3 pos, vel;
		int t;
		boolean flying;
		double flown;

		Boulder(UUID owner, ServerLevel level, BlockState block, Vec3 from, Vector3f axis) {
			this.owner = owner;
			this.level = level;
			this.block = block;
			this.from = from;
			this.pos = from;
			this.axis = axis;
		}
	}

	private static final Map<UUID, Boulder> BOULDERS = new HashMap<>();
	private static final double BOULDER_GRAVITY = 0.035;

	public static boolean isHolding(ServerPlayer p) {
		Boulder b = BOULDERS.get(p.getUUID());
		return b != null && !b.flying;
	}

	static boolean boulder(ServerPlayer p, long now) {
		Boulder held = BOULDERS.get(p.getUUID());
		if (held != null) {
			if (!held.flying && held.t >= 8) {
				hurl(p, held, now);
				return true;
			}
			return false;
		}
		if (!AuraAbilities.checkCooldown(p, Ability.BOULDER_TOSS, AuraConfig.EARTH_BOULDER_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = p.serverLevel();
		Vec3 front = p.position().add(flat(p.getLookAngle()).scale(1.6));
		BlockPos g = ground(level, front, 3);
		if (g == null) g = ground(level, p.position(), 3);
		if (g == null) {
			p.displayClientMessage(Component.literal("No ground to tear a boulder out of.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		BlockState top = rock(level.getBlockState(g));
		Vec3 start = new Vec3(g.getX() + 0.5, g.getY() + 0.4, g.getZ() + 0.5);
		var random = p.getRandom();
		Boulder b = new Boulder(p.getUUID(), level, top, start, new Vector3f(random.nextFloat() - 0.5f, 0.6f, random.nextFloat() - 0.5f).normalize());
		b.turn.rotateXYZ(random.nextFloat(), random.nextFloat(), random.nextFloat());
		// a lumpy rock: one big block of the ground and two smaller ones of what is under it
		BlockState[] blocks = {top, under(top).is(top.getBlock()) ? Blocks.COBBLESTONE.defaultBlockState() : under(top), Blocks.COBBLESTONE.defaultBlockState()};
		float[][] shape = {{0, 0, 0, 1.2f}, {0.42f, 0.22f, 0.18f, 0.78f}, {-0.36f, -0.18f, -0.3f, 0.7f}};
		for (int i = 0; i < 3; i++) {
			Vector3f o = new Vector3f(shape[i][0], shape[i][1], shape[i][2]);
			b.offsets.add(o);
			b.sizes.add(shape[i][3]);
			b.parts.add(Props.spawn(level, start, blocks[i], Props.cube(new Quaternionf(b.turn).transform(new Vector3f(o)), shape[i][3], b.turn), -1, 2));
		}
		BOULDERS.put(p.getUUID(), b);
		dust(level, start.add(0, 0.6, 0), top, 30, 0.5);
		sound(level, start, SoundEvents.ROOTED_DIRT_BREAK, 1.3f, 0.5f);
		sound(level, start, SoundEvents.STONE_BREAK, 1.2f, 0.5f);
		sound(level, start, AuraSounds.MACE_SMASH_GROUND.value(), 0.8f, 0.6f);
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(BOULDER_SLOW);
			speed.addTransientModifier(new AttributeModifier(BOULDER_SLOW, -0.35, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		NetworkHandler.sendBendAnim(p, BendingAnimPacket.BOULDER_HOLD);
		return true;
	}

	/** Over the head, where it hangs while held. */
	private static Vec3 overhead(ServerPlayer p, int t) {
		return p.position().add(0, p.getBbHeight() + 0.72 + 0.04 * Math.sin(t * 0.3), 0).add(flat(p.getLookAngle()).scale(0.1));
	}

	private static void hurl(ServerPlayer p, Boulder b, long now) {
		b.flying = true;
		b.t = 0;
		b.pos = overhead(p, 0);
		// at the crosshair, thrown a little high so it drops onto the spot (it starts over your head, not at your eyes)
		double speed = Math.max(0.3, AuraConfig.EARTH_BOULDER_SPEED);
		Vec3 to = Handhelds.aim(p, Math.max(4, AuraConfig.EARTH_BOULDER_RANGE)).subtract(b.pos);
		double ticks = Math.max(1, to.length() / speed);
		b.vel = (to.lengthSqr() < 1.0e-4 ? p.getLookAngle() : to.normalize()).scale(speed).add(0, 0.5 * BOULDER_GRAVITY * (ticks - 1), 0);
		release(p);
		NetworkHandler.sendBendAnim(p, BendingAnimPacket.NONE);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_EARTH, 1);
		sound(b.level, b.pos, AuraSounds.PLAYER_ATTACK_SWEEP.value(), 1.0f, 0.5f);
		sound(b.level, b.pos, SoundEvents.RAVAGER_ATTACK, 0.6f, 0.7f);
		AuraAbilities.markUsed(p, Ability.BOULDER_TOSS, now);
	}

	private static void release(ServerPlayer p) {
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) speed.removeModifier(BOULDER_SLOW);
	}

	private static void place(Boulder b) {
		for (int i = 0; i < b.parts.size(); i++) {
			Props.move(b.parts.get(i), b.pos);
			Props.set(b.parts.get(i), Props.cube(new Quaternionf(b.turn).transform(new Vector3f(b.offsets.get(i))), b.sizes.get(i), b.turn), 1);
		}
	}

	/** Returns false when it is gone. */
	private static boolean tickBoulder(MinecraftServer server, Boulder b) {
		ServerPlayer p = server.getPlayerList().getPlayer(b.owner);
		ServerLevel level = b.level;
		b.t++;
		if (!b.flying) {
			if (p == null || !p.isAlive() || p.isSpectator() || p.level() != level || !AuraAbilities.hasAbility(
					com.toast.aurapoints.data.AuraState.get(server).getOrCreate(p.getUUID()), Ability.BOULDER_TOSS)) {
				if (p != null) {
					release(p);
					NetworkHandler.sendBendAnim(p, BendingAnimPacket.NONE);
				}
				crumble(b, b.pos);
				return false;
			}
			// torn out and lifted overhead, then carried there
			Vec3 up = overhead(p, b.t);
			if (b.t <= 8) {
				double e = b.t / 8.0;
				e = e * e * (3 - 2 * e);
				b.pos = b.from.lerp(up, e).add(0, Math.sin(e * Math.PI) * 0.6, 0);
				if (b.t % 2 == 0) AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, b.block), b.pos.x, b.pos.y - 0.4, b.pos.z, 4, 0.3, 0.1, 0.3, 0);
			} else {
				b.pos = up;
				if (b.t % 6 == 0) AuraFx.particles(level, new BlockParticleOption(ParticleTypes.FALLING_DUST, b.block), b.pos.x, b.pos.y - 0.5, b.pos.z, 2, 0.35, 0.05, 0.35, 0);
			}
			b.turn.rotateAxis(0.04f, b.axis);
			place(b);
			if (b.t >= Math.max(10, AuraConfig.EARTH_BOULDER_HOLD_TICKS)) hurl(p, b, server.getTickCount());
			return true;
		}
		// in flight: a little gravity, tumbling, crumbs falling off
		Vec3 next = b.pos.add(b.vel);
		HitResult wall = level.clip(new ClipContext(b.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p != null ? p : b.parts.get(0)));
		Vec3 end = wall.getType() == HitResult.Type.MISS ? next : wall.getLocation();
		LivingEntity hit = null;
		if (p != null) {
			var entityHit = ProjectileUtil.getEntityHitResult(level, p, b.pos, end, new AABB(b.pos, end).inflate(1.0), e -> Handhelds.enemy(p, e), 0.75f);
			if (entityHit != null && entityHit.getEntity() instanceof LivingEntity l) hit = l;
		}
		if (hit != null || wall.getType() != HitResult.Type.MISS) {
			impact(p, b, hit != null ? AuraFx.chest(hit) : end, hit);
			return false;
		}
		b.pos = next;
		b.flown += b.vel.length();
		b.vel = b.vel.add(0, -BOULDER_GRAVITY, 0);
		b.turn.rotateAxis(0.35f, b.axis);
		place(b);
		if (b.t % 2 == 0) AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, b.block), b.pos.x, b.pos.y, b.pos.z, 2, 0.3, 0.3, 0.3, 0);
		if (b.t % 5 == 0) sound(level, b.pos, SoundEvents.ROOTED_DIRT_STEP, 0.5f, 0.5f);
		if (b.flown > Math.max(4, AuraConfig.EARTH_BOULDER_RANGE) || b.t > 200) {
			crumble(b, b.pos);
			return false;
		}
		return true;
	}

	private static void impact(ServerPlayer p, Boulder b, Vec3 at, LivingEntity hit) {
		ServerLevel level = b.level;
		Vec3 push = flat(b.vel);
		if (p != null) {
			if (hit != null) {
				Handhelds.hurt(p, hit, AuraConfig.EARTH_BOULDER_DAMAGE);
				double k = AuraConfig.EARTH_BOULDER_KNOCKBACK;
				hit.setDeltaMovement(hit.getDeltaMovement().scale(0.2).add(push.x * k, 0.45, push.z * k));
				hit.hurtMarked = true;
			}
			for (LivingEntity e : Handhelds.enemies(p, level, at, Math.max(0.5, AuraConfig.EARTH_BOULDER_SPLASH_RADIUS))) {
				if (e == hit) continue;
				Handhelds.hurt(p, e, AuraConfig.EARTH_BOULDER_SPLASH_DAMAGE);
				Vec3 out = flat(e.position().subtract(at));
				e.setDeltaMovement(e.getDeltaMovement().add(out.x * 0.5, 0.3, out.z * 0.5));
				e.hurtMarked = true;
			}
		}
		crumble(b, at);
		AuraFx.particles(level, ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		sound(level, at, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), 1.5f, 0.7f);
		sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), 0.6f, 1.3f);
	}

	/** Bursts into crumbs and is gone. */
	private static void crumble(Boulder b, Vec3 at) {
		for (Display.BlockDisplay d : b.parts) Props.remove(d);
		AuraFx.particles(b.level, new BlockParticleOption(ParticleTypes.BLOCK, b.block), at.x, at.y, at.z, 50, 0.6, 0.5, 0.6, 0.2);
		AuraFx.particles(b.level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState()), at.x, at.y, at.z, 25, 0.5, 0.4, 0.5, 0.2);
		AuraFx.particles(b.level, new BlockParticleOption(ParticleTypes.DUST_PILLAR, b.block), at.x, at.y, at.z, 20, 0.6, 0.3, 0.6, 0.1);
		sound(b.level, at, SoundEvents.STONE_BREAK, 1.4f, 0.6f);
	}

	// ---------------------------------------------------------------
	// Quake
	// ---------------------------------------------------------------

	private static final class Ripple {
		final UUID owner;
		final ServerLevel level;
		final Vec3 c;
		final Set<LivingEntity> hit = new HashSet<>();
		final Set<Long> raised = new HashSet<>();
		int t;

		Ripple(UUID owner, ServerLevel level, Vec3 c) {
			this.owner = owner;
			this.level = level;
			this.c = c;
		}
	}

	private static final List<Ripple> RIPPLES = new ArrayList<>();
	/** How fast the ripple rolls out (blocks per tick). */
	private static final double RIPPLE_SPEED = 0.55;

	static boolean quake(ServerPlayer p, long now) {
		if (!AuraAbilities.checkCooldown(p, Ability.QUAKE, AuraConfig.EARTH_QUAKE_COOLDOWN_TICKS, now)) return false;
		ServerLevel level = p.serverLevel();
		BlockPos g = ground(level, p.position(), 4);
		if (g == null) {
			p.displayClientMessage(Component.literal("No ground to shake.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		Vec3 c = new Vec3(p.getX(), g.getY() + 1, p.getZ());
		RIPPLES.add(new Ripple(p.getUUID(), level, c));
		BlockState block = rock(level.getBlockState(g));
		dust(level, c, block, 40, 0.8);
		AuraFx.particles(level, ParticleTypes.EXPLOSION, c.x, c.y + 0.2, c.z, 1, 0, 0, 0, 0);
		sound(level, c, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), 1.6f, 0.6f);
		sound(level, c, SoundEvents.GENERIC_EXPLODE.value(), 0.7f, 0.5f);
		sound(level, c, SoundEvents.WARDEN_DIG, 1.2f, 0.8f);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_EARTH, 2);
		AuraAbilities.markUsed(p, Ability.QUAKE, now);
		return true;
	}

	/** Returns false when the ripple has rolled out. */
	private static boolean tickRipple(MinecraftServer server, Ripple r) {
		r.t++;
		double radius = Math.max(2, AuraConfig.EARTH_QUAKE_RADIUS);
		double at = r.t * RIPPLE_SPEED;
		if (at > radius + 0.5) return false;
		ServerLevel level = r.level;
		ServerPlayer quaker = server.getPlayerList().getPlayer(r.owner);
		if (quaker != null) Combos.at(quaker, r.c.add(0, 0.5, 0), at);
		int points = Math.max(8, (int) (Math.PI * 2 * at / 0.8));
		for (int i = 0; i < points; i++) {
			double a = Math.PI * 2 * i / points;
			double x = r.c.x + Math.cos(a) * at, z = r.c.z + Math.sin(a) * at;
			BlockPos col = BlockPos.containing(x, r.c.y, z);
			if (!r.raised.add(col.asLong())) continue;
			BlockPos g = ground(level, new Vec3(x, r.c.y + 1.5, z), 4);
			if (g == null || !level.getBlockState(g.above()).getCollisionShape(level, g.above()).isEmpty()) continue;
			heave(level, g);
		}
		if (r.t % 2 == 0) {
			double a = level.getRandom().nextDouble() * Math.PI * 2;
			sound(level, r.c.add(Math.cos(a) * at, 0, Math.sin(a) * at), SoundEvents.GRAVEL_BREAK, 1.0f, 0.5f + level.getRandom().nextFloat() * 0.2f);
		}
		ServerPlayer p = server.getPlayerList().getPlayer(r.owner);
		if (p == null) return true;
		for (LivingEntity e : Handhelds.enemies(p, level, r.c, at + 1.5)) {
			if (r.hit.contains(e)) continue;
			double d = Math.hypot(e.getX() - r.c.x, e.getZ() - r.c.z);
			if (d < at - 0.9 || d > at + 0.9 || Math.abs(e.getY() - r.c.y) > 3) continue;
			r.hit.add(e);
			Handhelds.hurt(p, e, AuraConfig.EARTH_QUAKE_DAMAGE);
			Vec3 out = flat(e.position().subtract(r.c));
			e.setDeltaMovement(e.getDeltaMovement().scale(0.4).add(out.x * 0.25, AuraConfig.EARTH_QUAKE_LIFT, out.z * 0.25));
			e.hurtMarked = true;
			if (AuraConfig.EARTH_QUAKE_SLOW_TICKS > 0) {
				e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, AuraConfig.EARTH_QUAKE_SLOW_TICKS, Math.max(0, AuraConfig.EARTH_QUAKE_SLOW_AMPLIFIER)), p);
			}
		}
		return true;
	}

	/** One block of ground bucks up and drops back. */
	private static void heave(ServerLevel level, BlockPos g) {
		BlockState block = rock(level.getBlockState(g));
		Vec3 at = new Vec3(g.getX() + 0.5, g.getY(), g.getZ() + 0.5);
		var hidden = Props.box(0, -0.45, 0, 0.98f, 1f, 0.98f);
		var d = Props.spawn(level, at, block, hidden, 9, 0);
		float lift = 0.35f + level.getRandom().nextFloat() * 0.3f;
		Props.setLater(d, 1, Props.box(0, lift, 0, 0.98f, 1f, 0.98f), 2);
		Props.setLater(d, 3, hidden, 5);
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, block), at.x, at.y + 1.1, at.z, 3, 0.3, 0.1, 0.3, 0.1);
		if (level.getRandom().nextInt(3) == 0) AuraFx.particles(level, new BlockParticleOption(ParticleTypes.DUST_PILLAR, block), at.x, at.y + 1.0, at.z, 2, 0.3, 0, 0.3, 0.05);
	}

	// ---------------------------------------------------------------
	// Passives
	// ---------------------------------------------------------------

	private static final ResourceLocation SKIN_ARMOR = ResourceLocation.fromNamespaceAndPath("aurapoints", "earth_stone_skin_armor");
	private static final ResourceLocation SKIN_TOUGHNESS = ResourceLocation.fromNamespaceAndPath("aurapoints", "earth_stone_skin_toughness");
	private static final ResourceLocation GROUNDED = ResourceLocation.fromNamespaceAndPath("aurapoints", "earth_grounded");
	/** player -> the tick they last stood on natural ground (Stone Skin stays on through a jump) */
	private static final Map<UUID, Long> ON_STONE = new HashMap<>();

	/** Every server tick for every player (so losing the aura takes the modifiers off again). */
	public static void tickPassives(ServerPlayer p, AuraPlayerData data, long now) {
		if (now % 5 != 0) return;
		boolean skinAbility = AuraAbilities.hasAbility(data, Ability.STONE_SKIN) && p.isAlive() && !p.isSpectator();
		if (skinAbility && p.onGround() && natural(p.level().getBlockState(p.getOnPos()))) ON_STONE.put(p.getUUID(), now);
		Long last = ON_STONE.get(p.getUUID());
		boolean skin = skinAbility && last != null && now - last <= 20;
		boolean was = has(p, Attributes.ARMOR, SKIN_ARMOR);
		modifier(p, Attributes.ARMOR, SKIN_ARMOR, skin ? AuraConfig.EARTH_STONE_SKIN_ARMOR : 0);
		modifier(p, Attributes.ARMOR_TOUGHNESS, SKIN_TOUGHNESS, skin ? AuraConfig.EARTH_STONE_SKIN_TOUGHNESS : 0);
		if (skin && !was && !p.isInvisible()) {
			// the skin hardening: a little grit off the arms, a dull scrape
			BlockState under = rock(p.level().getBlockState(p.getOnPos()));
			AuraFx.particles(p.serverLevel(), new BlockParticleOption(ParticleTypes.BLOCK, under), p.getX(), p.getY() + 1.0, p.getZ(), 10, 0.3, 0.5, 0.3, 0);
			sound(p.serverLevel(), p.position(), SoundEvents.STONE_PLACE, 0.35f, 0.6f);
		}
		if (!skinAbility) ON_STONE.remove(p.getUUID());
		boolean grounded = AuraAbilities.hasAbility(data, Ability.GROUNDED);
		modifier(p, Attributes.KNOCKBACK_RESISTANCE, GROUNDED, grounded ? Math.max(0, Math.min(1, AuraConfig.EARTH_GROUNDED_KNOCKBACK_RESISTANCE)) : 0);
	}

	/** Grounded: fall damage taken (the incoming-damage event). */
	public static float fallDamage(AuraPlayerData data, float amount) {
		if (!AuraAbilities.hasAbility(data, Ability.GROUNDED)) return amount;
		return amount * (float) (1 - Math.max(0, Math.min(1, AuraConfig.EARTH_GROUNDED_FALL_REDUCTION)));
	}

	private static boolean has(ServerPlayer p, Holder<Attribute> attribute, ResourceLocation id) {
		AttributeInstance inst = p.getAttribute(attribute);
		return inst != null && inst.getModifier(id) != null;
	}

	private static void modifier(ServerPlayer p, Holder<Attribute> attribute, ResourceLocation id, double amount) {
		AttributeInstance inst = p.getAttribute(attribute);
		if (inst == null) return;
		AttributeModifier current = inst.getModifier(id);
		if (amount == 0) {
			if (current != null) inst.removeModifier(id);
			return;
		}
		if (current != null && current.amount() == amount) return;
		inst.addOrUpdateTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
	}

	// ---------------------------------------------------------------
	// Lifecycle
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		Props.tick();
		if (!SAFE_FALL.isEmpty()) {
			for (Iterator<Map.Entry<UUID, Integer>> it = SAFE_FALL.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
				e.setValue(e.getValue() + 1);
				if (p == null || e.getValue() > 200) {
					it.remove();
					continue;
				}
				p.fallDistance = 0;
				if (e.getValue() > 3 && (p.onGround() || p.isInWater())) it.remove();
			}
		}
		if (!BOULDERS.isEmpty()) {
			for (Boulder b : new ArrayList<>(BOULDERS.values())) {
				boolean alive;
				try {
					alive = tickBoulder(server, b);
				} catch (Exception ex) {
					com.toast.aurapoints.AuraPoints.LOGGER.error("Boulder Toss failed", ex);
					for (Display.BlockDisplay d : b.parts) Props.remove(d);
					alive = false;
				}
				if (!alive) BOULDERS.remove(b.owner);
			}
		}
		if (!RIPPLES.isEmpty()) RIPPLES.removeIf(r -> !tickRipple(server, r));
	}

	public static boolean isLeftover(Entity e) {
		return Props.isLeftover(e);
	}

	public static void onLogout(ServerPlayer p) {
		Boulder b = BOULDERS.remove(p.getUUID());
		if (b != null) for (Display.BlockDisplay d : b.parts) Props.remove(d);
		release(p);
		SAFE_FALL.remove(p.getUUID());
		ON_STONE.remove(p.getUUID());
	}

	public static void clearAll() {
		BOULDERS.clear();
		RIPPLES.clear();
		SAFE_FALL.clear();
		ON_STONE.clear();
		Props.clearAll();
	}
}
