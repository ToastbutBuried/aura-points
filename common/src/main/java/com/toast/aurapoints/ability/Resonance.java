package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraCommand;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.HighlightPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PinkPetalsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resonance: a hunter by ear. Everything here reads the {@link Noise} meter.
 * <ul>
 * <li>Echolocation (passive): a violet fog dims your sight (client side, see client/EchoFog), but every noisy player
 * and mob within ECHO_RANGE glows for you through walls (the fire-sight outline, in magenta).</li>
 * <li>Sonic Blossom: you grab the ground and a hollow blossom grows below you - purple spore-blossom petals curled shut
 * over a glowing heart. Whoever makes noise near it, it bursts open and shoots a dart at; a hit keeps the target glowing
 * for you a while, noise or not.</li>
 * <li>Listening Tree: a tree with a curled tip that grabs the loudest one near it with a tendril and holds them on a
 * leash - easy to move right next to it, harder and harder further out, never past LEASH.</li>
 * <li>Abyssal Grasp: the tentacles on your back (drawn by each client, client/Tentacles) lash out, seize the first one
 * in line, whip them to you and hold them; press again to throw them.</li>
 * </ul>
 * Plants are GeckoLib models (geo/SonicBlossom, geo/ListeningTree; the tree's tendril and hitbox are RootCanvas
 * pieces), gone when their owner logs out.
 */
public final class Resonance {

	private Resonance() {
	}

	static final int VIOLET = 0x4A0A6E, MAGENTA = 0xD02CF0, PINK = 0x8E1AB8;

	/** Somewhere noise is being listened to: a Resonance player or one of their plants. */
	record Listener(ServerLevel level, Vec3 at) {
	}

	// ---------------------------------------------------------------
	// State
	// ---------------------------------------------------------------

	private static final class Dart {
		Vec3 pos;
		final LivingEntity target;
		int age;

		Dart(Vec3 pos, LivingEntity target) {
			this.pos = pos;
			this.target = target;
		}
	}

	/** A plant: its GeckoLib model, plus a RootCanvas for the display/hitbox pieces it still needs (the tree's tendril). */
	private abstract static class Plant {
		final RootCanvas canvas;
		com.toast.aurapoints.geo.AuraGeoEntity model;

		Plant(RootCanvas canvas) {
			this.canvas = canvas;
		}

		abstract int goneState();

		/** Its end: the model plays its wither, the pieces sink, all gone a moment later. */
		void wither(MinecraftServer server) {
			if (model != null && !model.isRemoved()) {
				model.setState(goneState());
				var m = model;
				AuraFx.later(server != null ? server : canvas.level.getServer(), 10, m::discard);
			}
			Resonance.wither(canvas, server);
		}

		void discardAll() {
			if (model != null) model.discard();
			canvas.discardAll();
		}
	}

	private static final class Blossom extends Plant {
		final UUID owner;
		final ServerLevel level;
		final Vec3 base, head;
		final float spin;
		final List<Dart> darts = new ArrayList<>();
		LivingEntity aim;
		int age, reload, open = -1;

		Blossom(UUID owner, ServerLevel level, Vec3 base, float spin) {
			super(new RootCanvas(level, BlockPos.containing(base).above()));
			this.owner = owner;
			this.level = level;
			this.base = base;
			this.head = base.add(0, 0.35, 0);
			this.spin = spin;
		}

		@Override
		int goneState() {
			return com.toast.aurapoints.geo.SonicBlossom.GONE;
		}
	}

	private static final class Tree extends Plant {
		final UUID owner;
		final ServerLevel level;
		final Vec3 base;
		final List<Display.BlockDisplay> tendril = new ArrayList<>();
		Vec3 tip;
		LivingEntity held;
		net.minecraft.world.entity.Interaction box;
		int age, holdAge, rest, hits, hitAt = -100;
		boolean broken;

		Tree(UUID owner, ServerLevel level, Vec3 base) {
			super(new RootCanvas(level, BlockPos.containing(base).above(2)));
			this.owner = owner;
			this.level = level;
			this.base = base;
		}

		@Override
		int goneState() {
			return com.toast.aurapoints.geo.ListeningTree.GONE;
		}
	}

	private static final Map<UUID, Deque<Blossom>> BLOSSOMS = new HashMap<>();
	private static final Map<UUID, Deque<Tree>> TREES = new HashMap<>();
	/** players grabbing the ground to plant a blossom -> the tick it grows */
	private static final Map<UUID, Long> PLANTING = new HashMap<>();
	/** Resonance player -> entities a dart marked -> tick the mark ends */
	private static final Map<UUID, Map<LivingEntity, Long>> MARKS = new HashMap<>();
	/** What each Resonance player's client was last told to outline. */
	private static final Map<UUID, Set<Integer>> SENT = new HashMap<>();

	private static final int TENDRIL_SEGS = 7, GROW_TICKS = 20, YANK_TICKS = 6;

	/** Holding Resonance. */
	private static boolean isResonant(ServerPlayer player, AuraPlayerData data) {
		return data.aura == AuraType.RESONANCE;
	}

	static List<Listener> listeners(MinecraftServer server) {
		List<Listener> out = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (isResonant(p, AuraState.get(server).getOrCreate(p.getUUID()))) out.add(new Listener(p.serverLevel(), p.position()));
		}
		if (Handhelds.devPlayer != null && !Handhelds.devPlayer.isRemoved()) out.add(new Listener(Handhelds.devPlayer.serverLevel(), Handhelds.devPlayer.position()));
		for (Deque<Blossom> q : BLOSSOMS.values()) for (Blossom b : q) out.add(new Listener(b.level, b.base));
		for (Deque<Tree> q : TREES.values()) for (Tree t : q) out.add(new Listener(t.level, t.base));
		return out;
	}

	private static void mark(UUID owner, LivingEntity target, long now) {
		MARKS.computeIfAbsent(owner, k -> new HashMap<>()).put(target, now + AuraConfig.ECHO_MARK_TICKS);
	}

	/** Where a plant goes: the ground at the crosshair (up to {@code range}), else the ground in front of the player. */
	private static Vec3 plantSpot(ServerPlayer player, double range) {
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition();
		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(player.getLookAngle().scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 at = hit.getType() == HitResult.Type.MISS ? hit.getLocation()
				: hit.getDirection() == Direction.UP ? hit.getLocation() : hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(0.3));
		double y = RootCanvas.surfaceY(level, at, 1, 8);
		if (Double.isNaN(y)) {
			at = player.position();
			y = RootCanvas.surfaceY(level, at, 1, 3);
			if (Double.isNaN(y)) return null;
		}
		return new Vec3(at.x, y, at.z);
	}

	// ---------------------------------------------------------------
	// Sonic Blossom
	// ---------------------------------------------------------------

	/** Press: you grab the ground (a short windup, slowed); the blossom grows below you when it ends. Floor only. */
	static boolean plantBlossom(ServerPlayer player, long now) {
		if (PLANTING.containsKey(player.getUUID())) return false;
		if (!player.onGround() || player.isInWater()) {
			player.displayClientMessage(Component.literal("You need solid ground under you.").withStyle(ChatFormatting.DARK_PURPLE), true);
			return false;
		}
		if (!AuraAbilities.checkCooldown(player, Ability.SONIC_BLOSSOM, AuraConfig.BLOSSOM_COOLDOWN_TICKS, now)) return false;
		PLANTING.put(player.getUUID(), now + Math.max(1, AuraConfig.BLOSSOM_WINDUP_TICKS));
		Stun.windup(player, true, AuraConfig.BLOSSOM_WINDUP_SLOWNESS);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_PLANT, 0);
		ServerLevel level = player.serverLevel();
		AuraFx.particles(level, ParticleTypes.SQUID_INK, player.getX(), player.getY() + 0.1, player.getZ(), 10, 0.4, 0.05, 0.4, 0.01);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.SCULK_BLOCK_SPREAD.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
		return true;
	}

	/** Every tick: windups that end (planting) or break off (stunned, frozen, lifted off the ground). */
	private static void tickPlanting(MinecraftServer server, long now) {
		if (PLANTING.isEmpty()) return;
		for (Iterator<Map.Entry<UUID, Long>> it = PLANTING.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer p = Handhelds.player(server, e.getKey());
			if (p == null) {
				it.remove();
				continue;
			}
			boolean broken = !p.isAlive() || Stun.isStunned(p) || IceSpike.isFrozen(p) || Entanglement.isEntangled(p);
			if (!broken && now < e.getValue()) {
				if (now % 3 == 0) AuraFx.particles(p.serverLevel(), AuraFx.dust(VIOLET, 1.0f), p.getX(), p.getY() + 0.1, p.getZ(), 4, 0.35, 0.02, 0.35, 0);
				continue;
			}
			it.remove();
			Stun.windup(p, false, 0);
			if (broken || !p.onGround() || p.isInWater()) continue;
			grow(p, now);
		}
	}

	private static void grow(ServerPlayer player, long now) {
		ServerLevel level = player.serverLevel();
		double y = RootCanvas.surfaceY(level, player.position(), 0, 2);
		if (Double.isNaN(y)) return;
		Vec3 at = new Vec3(player.getX(), y, player.getZ());
		Blossom b = new Blossom(player.getUUID(), level, at, level.random.nextFloat() * 360f);
		var model = com.toast.aurapoints.AuraPoints.SONIC_BLOSSOM.get().create(level);
		if (model != null) {
			model.moveTo(at.x, at.y, at.z, b.spin, 0);
			model.setYHeadRot(b.spin);
			level.addFreshEntity(model);
			b.model = model;
		}
		Deque<Blossom> mine = BLOSSOMS.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
		mine.addLast(b);
		while (mine.size() > Math.max(1, AuraConfig.BLOSSOM_MAX)) mine.removeFirst().wither(null);

		AuraFx.particles(level, ParticleTypes.SQUID_INK, at.x, at.y + 0.3, at.z, 12, 0.3, 0.2, 0.3, 0.02);
		AuraFx.particles(level, AuraFx.dust(MAGENTA, 0.9f), at.x, at.y + 0.4, at.z, 10, 0.3, 0.3, 0.3, 0);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.SCULK_BLOCK_PLACE.value(), SoundSource.PLAYERS, 1.2f, 0.5f);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.WARDEN_HEARTBEAT.value(), SoundSource.PLAYERS, 1.0f, 1.2f);
		AuraAbilities.markUsed(player, Ability.SONIC_BLOSSOM, now);
	}

	private static boolean tickBlossom(ServerPlayer owner, Blossom b, long now) {
		int age = b.age++;
		if (age >= AuraConfig.BLOSSOM_TICKS) return true;
		ServerLevel level = b.level;
		if (b.model != null) b.model.keepAlive();
		if (b.reload > 0) b.reload--;
		// open: the dart flies 2 ticks into the burst, the petals curl shut again (the model's open animation)
		if (b.open >= 0) {
			int o = b.open++;
			if (o == 2 && b.aim != null && b.aim.isAlive()) b.darts.add(new Dart(b.head, b.aim));
			if (o == com.toast.aurapoints.geo.SonicBlossom.OPEN_TICKS - 1) {
				if (b.model != null) b.model.setState(com.toast.aurapoints.geo.SonicBlossom.IDLE);
				b.open = -1;
			}
		}
		// the loudest enemy it can hear and see
		if (age >= 10 && b.reload == 0 && b.open < 0) {
			LivingEntity target = loudest(owner, level, b.head, AuraConfig.BLOSSOM_RANGE, AuraConfig.BLOSSOM_MIN_NOISE, true);
			if (target != null) {
				b.reload = Math.max(1, AuraConfig.BLOSSOM_SHOT_TICKS);
				b.aim = target;
				b.open = 0;
				if (b.model != null) b.model.setState(com.toast.aurapoints.geo.SonicBlossom.OPEN);
				AuraFx.particles(level, AuraFx.dust(MAGENTA, 1.0f), b.head.x, b.head.y, b.head.z, 6, 0.2, 0.1, 0.2, 0);
				level.playSound(null, b.head.x, b.head.y, b.head.z, AuraSounds.SCULK_CLICKING.value(), SoundSource.PLAYERS, 1.2f, 1.3f);
				level.playSound(null, b.head.x, b.head.y, b.head.z, AuraSounds.ARROW_SHOOT.value(), SoundSource.PLAYERS, 0.5f, 0.6f);
			}
		}
		if (age % 25 == 0) AuraFx.particles(level, ParticleTypes.SCULK_SOUL, b.head.x, b.head.y + 0.1, b.head.z, 1, 0.1, 0.05, 0.1, 0.01);

		for (Iterator<Dart> it = b.darts.iterator(); it.hasNext(); ) {
			Dart d = it.next();
			if (flyDart(owner, b, d, now)) it.remove();
		}
		return false;
	}

	/** A dart: flies at its target (homing a little, so it can still be dodged); true when it's done. */
	private static boolean flyDart(ServerPlayer owner, Blossom b, Dart d, long now) {
		ServerLevel level = b.level;
		if (++d.age > 30 || !d.target.isAlive() || d.target.level() != level) return true;
		Vec3 want = AuraFx.chest(d.target).subtract(d.pos);
		Vec3 step = want.normalize().scale(Math.min(want.length(), AuraConfig.BLOSSOM_DART_SPEED));
		Vec3 next = d.pos.add(step);
		BlockHitResult wall = level.clip(new ClipContext(d.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
		Vec3 end = wall.getType() == HitResult.Type.MISS ? next : wall.getLocation();
		var hit = ProjectileUtil.getEntityHitResult(level, owner, d.pos, end, new AABB(d.pos, end).inflate(0.4),
				e -> Handhelds.enemy(owner, e), 0.15f);
		AuraFx.line(level, d.pos, end, AuraFx.dust(MAGENTA, 0.6f), 3);
		AuraFx.particles(level, AuraFx.dust(VIOLET, 0.9f), end.x, end.y, end.z, 1, 0, 0, 0, 0);
		if (hit != null && hit.getEntity() instanceof LivingEntity victim) {
			victim.invulnerableTime = 0;
			Handhelds.hurt(owner, victim, AuraConfig.BLOSSOM_DART_DAMAGE);
			mark(owner.getUUID(), victim, now);
			Vec3 c = AuraFx.chest(victim);
			AuraFx.particles(level, AuraFx.dust(PINK, 1.0f), c.x, c.y, c.z, 6, 0.2, 0.2, 0.2, 0);
			level.playSound(null, c.x, c.y, c.z, AuraSounds.SCULK_BLOCK_HIT.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
			return true;
		}
		if (wall.getType() != HitResult.Type.MISS) {
			AuraFx.particles(level, ParticleTypes.SQUID_INK, end.x, end.y, end.z, 3, 0.05, 0.05, 0.05, 0.01);
			return true;
		}
		d.pos = next;
		return false;
	}

	/** Held by a Listening Tree or an Abyssal Grasp right now: the plants leave them alone. */
	private static boolean isGrabbed(LivingEntity e) {
		for (Grasp g : GRASPS.values()) if (g.target == e && g.phase != Phase.REACH) return true;
		for (Deque<Tree> q : TREES.values()) for (Tree t : q) if (t.held == e) return true;
		return false;
	}

	/** The loudest enemy at least {@code min} loud within {@code r} of {@code from} (closer wins a tie), optionally only ones in sight. */
	private static LivingEntity loudest(ServerPlayer owner, ServerLevel level, Vec3 from, double r, float min, boolean sight) {
		LivingEntity best = null;
		double score = Double.NEGATIVE_INFINITY;
		for (LivingEntity e : Handhelds.enemies(owner, level, from, r)) {
			float noise = Noise.get(e);
			if (noise < Math.max(min, AuraConfig.NOISE_LOUD) || isGrabbed(e)) continue;
			double s = noise - AuraFx.chest(e).distanceTo(from) * 2.0;
			if (s <= score) continue;
			if (sight && level.clip(new ClipContext(from, AuraFx.chest(e), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner)).getType() != HitResult.Type.MISS) continue;
			score = s;
			best = e;
		}
		return best;
	}

	// ---------------------------------------------------------------
	// Listening Tree
	// ---------------------------------------------------------------

	static boolean plantTree(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.LISTENING_TREE, AuraConfig.LISTENING_TREE_COOLDOWN_TICKS, now)) return false;
		Vec3 at = plantSpot(player, AuraConfig.LISTENING_TREE_PLANT_RANGE);
		if (at == null) return false;
		ServerLevel level = player.serverLevel();
		Tree t = new Tree(player.getUUID(), level, at);
		buildTree(t);
		Deque<Tree> mine = TREES.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
		mine.addLast(t);
		while (mine.size() > Math.max(1, AuraConfig.LISTENING_TREE_MAX)) {
			Tree old = mine.removeFirst();
			release(old);
			old.wither(null);
		}

		var dirt = new BlockParticleOption(ParticleTypes.BLOCK, RootCanvas.groundBlock(level, at));
		AuraFx.particles(level, dirt, at.x, at.y + 0.2, at.z, 30, 0.5, 0.2, 0.5, 0.15);
		AuraFx.particles(level, ParticleTypes.SQUID_INK, at.x, at.y + 2, at.z, 30, 0.6, 1.2, 0.6, 0.02);
		AuraFx.particles(level, ParticleTypes.SCULK_SOUL, at.x, at.y + 3.5, at.z, 6, 0.5, 0.4, 0.5, 0.02);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.SCULK_BLOCK_SPREAD.value(), SoundSource.PLAYERS, 1.5f, 0.5f);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.WARDEN_HEARTBEAT.value(), SoundSource.PLAYERS, 1.5f, 0.8f);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_PLANT, 1);
		AuraAbilities.markUsed(player, Ability.LISTENING_TREE, now);
		return true;
	}

	/**
	 * The tree: one GeckoLib model (geo/ListeningTree, about 4 blocks tall, its curl rolling over toward its front),
	 * turned a random way. Its hitbox and the tendril it grabs with stay display pieces on the canvas.
	 */
	private static void buildTree(Tree t) {
		float yaw = t.level.random.nextFloat() * 360f;
		double r = Math.toRadians(yaw);
		t.tip = t.base.add(-Math.sin(r) * com.toast.aurapoints.geo.ListeningTree.TIP_FORWARD, com.toast.aurapoints.geo.ListeningTree.TIP_UP,
				Math.cos(r) * com.toast.aurapoints.geo.ListeningTree.TIP_FORWARD);
		var model = com.toast.aurapoints.AuraPoints.LISTENING_TREE.get().create(t.level);
		if (model != null) {
			model.moveTo(t.base.x, t.base.y, t.base.z, yaw, 0);
			model.setYHeadRot(yaw);
			t.level.addFreshEntity(model);
			t.model = model;
		}
		// what other players hit to break it
		t.box = t.canvas.hitbox(t.base.add(0, -0.1, 0), 1.0f, 3.9f);
		// the tendril it grabs with: hidden until it does
		BlockState flesh = com.toast.aurapoints.AuraPoints.RESONANCE_FLESH.get().defaultBlockState();
		for (int i = 0; i < TENDRIL_SEGS; i++) t.tendril.add(t.canvas.piece(flesh, t.tip));
	}

	private static boolean tickTree(ServerPlayer owner, Tree t, long now) {
		int age = t.age++;
		if (t.broken || age >= AuraConfig.LISTENING_TREE_TICKS) {
			release(t);
			return true;
		}
		ServerLevel level = t.level;
		if (t.model != null) {
			t.model.keepAlive();
			int want = age - t.hitAt < com.toast.aurapoints.geo.ListeningTree.HIT_TICKS ? com.toast.aurapoints.geo.ListeningTree.HIT
					: t.held != null ? com.toast.aurapoints.geo.ListeningTree.HOLD : com.toast.aurapoints.geo.ListeningTree.IDLE;
			if (t.model.state() != want) t.model.setState(want);
		}
		if (t.held != null) {
			if (!hold(t)) {
				release(t);
				t.rest = Math.max(0, AuraConfig.LISTENING_TREE_REGRAB_TICKS);
			}
			return false;
		}
		if (t.rest > 0) {
			t.rest--;
			return false;
		}
		if (age < GROW_TICKS) return false;
		LivingEntity target = loudest(owner, level, t.base.add(0, 1, 0), AuraConfig.LISTENING_TREE_RANGE, AuraConfig.NOISE_LOUD, false);
		if (target != null) {
			t.held = target;
			t.holdAge = 0;
			if (target.isPassenger()) target.stopRiding();
			Vec3 c = AuraFx.chest(target);
			level.playSound(null, c.x, c.y, c.z, AuraSounds.SCULK_BLOCK_BREAK.value(), SoundSource.PLAYERS, 1.5f, 0.5f);
			level.playSound(null, t.tip.x, t.tip.y, t.tip.z, AuraSounds.WARDEN_TENDRIL_CLICKS.value(), SoundSource.PLAYERS, 1.5f, 1.2f);
			if (target instanceof ServerPlayer p) p.displayClientMessage(Component.literal("Something heard you...").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC), true);
		} else if (age % 30 == 0) {
			AuraFx.particles(level, AuraFx.dust(PINK, 0.8f), t.tip.x, t.tip.y, t.tip.z, 3, 0.2, 0.2, 0.2, 0);
		}
		return false;
	}

	/** One tick of holding: the tendril follows, the leash pulls. False when the hold is over. */
	private static boolean hold(Tree t) {
		LivingEntity e = t.held;
		int h = t.holdAge++;
		if (h >= AuraConfig.LISTENING_TREE_HOLD_TICKS || !e.isAlive() || e.isRemoved() || e.level() != t.level) return false;
		Vec3 flat = e.position().subtract(t.base).multiply(1, 0, 1);
		double d = flat.length();
		if (d > AuraConfig.LISTENING_TREE_LEASH + 6) return false; // teleported away
		Vec3 out = d < 1.0e-3 ? new Vec3(1, 0, 0) : flat.scale(1 / d);
		double easy = AuraConfig.LISTENING_TREE_EASY, leash = Math.max(easy + 0.1, AuraConfig.LISTENING_TREE_LEASH);

		if (d > leash + 0.05 && h >= YANK_TICKS) {
			// never past the leash
			Vec3 to = new Vec3(t.base.x + out.x * leash, e.getY(), t.base.z + out.z * leash);
			if (e instanceof ServerPlayer p) p.connection.teleport(to.x, to.y, to.z, p.getYRot(), p.getXRot());
			else e.teleportTo(to.x, to.y, to.z);
			d = leash;
		}
		double pull;
		if (h < YANK_TICKS) {
			pull = d > easy ? Math.min(1.1, (d - easy) * 0.45 + 0.2) : 0; // the first yank drags them in
		} else {
			double f = Math.max(0, Math.min(1, (d - easy) / (leash - easy)));
			pull = d > easy ? AuraConfig.LISTENING_TREE_PULL * Math.pow(f, 1.5) : 0;
		}
		if (pull > 0) {
			Vec3 v = e.getDeltaMovement();
			double up = e.getY() > t.base.y + 4 ? -0.3 : (h < YANK_TICKS ? Math.max(v.y, 0.1) : v.y);
			e.setDeltaMovement(-out.x * pull, up, -out.z * pull);
			e.hurtMarked = true;
		}
		e.fallDistance = 0;

		// the tendril: from the curled tip, sagging down to their chest
		Vec3 a = t.tip, b = AuraFx.chest(e);
		Vec3 ctrl = a.add(b).scale(0.5).add(0, -0.6 + Math.sin(h * 0.4) * 0.15, 0);
		Vec3 prev = a;
		for (int i = 0; i < TENDRIL_SEGS; i++) {
			double s = (i + 1.0) / TENDRIL_SEGS, u = 1 - s;
			Vec3 q = a.scale(u * u).add(ctrl.scale(2 * u * s)).add(b.scale(s * s));
			float w = 0.24f - 0.1f * i / TENDRIL_SEGS;
			RootCanvas.stretch(t.tendril.get(i), prev, q, w, 1.15f, 1);
			prev = q;
		}
		if (h % 8 == 0) AuraFx.particles(t.level, AuraFx.dust(MAGENTA, 0.8f), b.x, b.y, b.z, 4, 0.25, 0.3, 0.25, 0);
		return true;
	}

	/** A hit on a tree's hitbox: anyone but its planter chips at it, the last hit breaks it. True if it was a tree. */
	public static boolean onHitbox(ServerPlayer attacker, net.minecraft.world.entity.Entity box) {
		for (Deque<Tree> q : TREES.values()) {
			for (Tree t : q) {
				if (t.box != box) continue;
				if (attacker.getUUID().equals(t.owner) || t.broken) return true;
				t.hitAt = t.age;
				ServerLevel level = t.level;
				Vec3 c = t.base.add(0, 1.5, 0);
				var bark = new BlockParticleOption(ParticleTypes.BLOCK, com.toast.aurapoints.AuraPoints.RESONANCE_BARK.get().defaultBlockState());
				AuraFx.particles(level, bark, c.x, c.y, c.z, 20, 0.3, 0.8, 0.3, 0.1);
				AuraFx.particles(level, ParticleTypes.SQUID_INK, c.x, c.y, c.z, 6, 0.3, 0.6, 0.3, 0.02);
				level.playSound(null, c.x, c.y, c.z, AuraSounds.SCULK_BLOCK_HIT.value(), SoundSource.PLAYERS, 1.2f, 0.7f);
				level.playSound(null, c.x, c.y, c.z, AuraSounds.WOOD_HIT.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
				if (++t.hits >= Math.max(1, AuraConfig.LISTENING_TREE_HITS)) {
					t.broken = true; // gone on its next tick
					level.playSound(null, c.x, c.y, c.z, AuraSounds.SCULK_SHRIEKER_BREAK.value(), SoundSource.PLAYERS, 1.5f, 0.6f);
					level.playSound(null, c.x, c.y, c.z, AuraSounds.WARDEN_HURT.value(), SoundSource.PLAYERS, 1.0f, 1.4f);
					AuraFx.particles(level, bark, c.x, c.y, c.z, 60, 0.4, 1.2, 0.4, 0.2);
				}
				return true;
			}
		}
		return false;
	}

	private static void release(Tree t) {
		if (t.held == null) return;
		t.held = null;
		for (var seg : t.tendril) if (!seg.isRemoved()) RootCanvas.hide(seg, t.tip, 4);
		t.level.playSound(null, t.tip.x, t.tip.y, t.tip.z, AuraSounds.SCULK_BLOCK_HIT.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
	}

	// ---------------------------------------------------------------
	// Abyssal Grasp
	// ---------------------------------------------------------------

	private enum Phase { REACH, PULL, HOLD }

	private static final class Grasp {
		final LivingEntity target;
		final Vec3 aim;
		Phase phase = Phase.REACH;
		long caughtAt;
		Vec3 from;
		int age;
		boolean thrown;

		Grasp(LivingEntity target, Vec3 aim) {
			this.target = target;
			this.aim = aim;
		}
	}

	/** Thrown by a grasp: the owner, and how long we still watch for the wall. */
	private record Thrown(UUID owner, long until) {
	}

	private static final Map<UUID, Grasp> GRASPS = new HashMap<>();
	/** players winding their tentacles back (holding the key) -> the tick they started */
	private static final Map<UUID, Long> CHARGING = new HashMap<>();
	private static final Map<LivingEntity, Thrown> THROWN = new HashMap<>();

	/** Press: start winding back (or, holding someone, throw them). */
	static boolean grasp(ServerPlayer player, long now) {
		Grasp g = GRASPS.get(player.getUUID());
		if (g != null) {
			if (g.phase == Phase.REACH) return false;
			// the tentacles wind up first: no throwing right after the catch
			if (now - g.caughtAt < AuraConfig.GRASP_THROW_DELAY_TICKS) {
				player.displayClientMessage(Component.literal("The tentacles are still winding up...").withStyle(ChatFormatting.DARK_PURPLE), true);
				return false;
			}
			GRASPS.remove(player.getUUID());
			throwHeld(player, g, now);
			return true;
		}
		if (CHARGING.containsKey(player.getUUID())) return false;
		if (!AuraAbilities.checkCooldown(player, Ability.ABYSSAL_GRASP, AuraConfig.GRASP_COOLDOWN_TICKS, now)) return false;
		CHARGING.put(player.getUUID(), now);
		Stun.windup(player, true, AuraConfig.GRASP_CHARGE_SLOWNESS);
		NetworkHandler.sendFireBending(player, true, 0f, 1f);
		NetworkHandler.sendGrasp(player, com.toast.aurapoints.network.GraspPacket.CHARGE, null, null, Math.max(1, AuraConfig.GRASP_CHARGE_TICKS));
		ServerLevel level = player.serverLevel();
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.WARDEN_TENDRIL_CLICKS.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
		return true;
	}

	private static double chargeOf(long since, long now) {
		return Math.max(0, Math.min(1, (now - since) / (double) Math.max(1, AuraConfig.GRASP_CHARGE_TICKS)));
	}

	/** Key let go while winding back: lash out, further the longer it was held. */
	public static void graspRelease(ServerPlayer player, long now) {
		Long since = CHARGING.remove(player.getUUID());
		if (since == null) return;
		Stun.windup(player, false, 0);
		NetworkHandler.sendFireBending(player, false, 0, 0);
		double range = AuraConfig.GRASP_MIN_RANGE + (AuraConfig.GRASP_RANGE - AuraConfig.GRASP_MIN_RANGE) * chargeOf(since, now);
		reach(player, Math.max(1, range));
	}

	/** Winding back: the charge bar, and a stun, freeze or entanglement drops it (no cooldown). */
	private static void tickCharging(MinecraftServer server, long now) {
		if (CHARGING.isEmpty()) return;
		for (Iterator<Map.Entry<UUID, Long>> it = CHARGING.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer p = Handhelds.player(server, e.getKey());
			if (p == null) {
				it.remove();
				continue;
			}
			// the grasp gone (locked, switched off) also drops it: its key release never gets here then
			boolean lost = p != Handhelds.devPlayer && !AuraAbilities.hasAbility(AuraState.get(server).getOrCreate(p.getUUID()), Ability.ABYSSAL_GRASP);
			if (lost || !p.isAlive() || Stun.isStunned(p) || IceSpike.isFrozen(p) || Entanglement.isEntangled(p)) {
				it.remove();
				Stun.windup(p, false, 0);
				NetworkHandler.sendFireBending(p, false, 0, 0);
				NetworkHandler.sendGrasp(p, com.toast.aurapoints.network.GraspPacket.NONE, null, null, 0);
				continue;
			}
			if (now % 2 == 0) NetworkHandler.sendFireBending(p, true, (float) chargeOf(e.getValue(), now), 1f);
		}
	}

	private static void reach(ServerPlayer player, double range) {
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getLookAngle().scale(Math.min(range, AuraConfig.MAX_TRACE_RANGE)));
		BlockHitResult wall = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 stop = wall.getType() == HitResult.Type.MISS ? end : wall.getLocation();
		double r = AuraConfig.GRASP_HITBOX;
		var hit = ProjectileUtil.getEntityHitResult(level, player, eye, stop, new AABB(eye, stop).inflate(r + 1),
				e -> Handhelds.enemy(player, e) && e.getBoundingBox().inflate(r).clip(eye, stop).isPresent(), 0f);
		LivingEntity target = hit != null && hit.getEntity() instanceof LivingEntity l ? l : null;
		Vec3 aim = target != null ? AuraFx.chest(target) : stop;
		GRASPS.put(player.getUUID(), new Grasp(target, aim));
		NetworkHandler.sendGrasp(player, com.toast.aurapoints.network.GraspPacket.REACH, null, aim, Math.max(1, AuraConfig.GRASP_REACH_TICKS));
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_GRASP, 0);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.WARDEN_TENDRIL_CLICKS.value(), SoundSource.PLAYERS, 1.5f, 0.8f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.PHANTOM_FLAP.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
	}

	/** Where the held one hangs: in front of you at chest height, pulled closer if a wall is in the way. */
	private static Vec3 holdSpot(ServerPlayer player, LivingEntity target) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		double d = AuraConfig.GRASP_HOLD_DISTANCE;
		BlockHitResult wall = player.level().clip(new ClipContext(eye, eye.add(look.scale(d + 0.6)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (wall.getType() != HitResult.Type.MISS) d = Math.max(0.8, wall.getLocation().distanceTo(eye) - 0.6);
		Vec3 mid = eye.add(look.scale(d)).add(0, -0.3, 0);
		return mid.add(0, -target.getBbHeight() * 0.5, 0);
	}

	/** Puts the held one at {@code feet}: players without turning their camera, mobs held still. */
	private static void place(LivingEntity e, Vec3 feet) {
		// sitting (a seat, a boat, a horse): torn out of it, or everyone would still see them on it
		if (e.isPassenger()) e.stopRiding();
		if (e instanceof ServerPlayer p) {
			p.connection.teleport(feet.x, feet.y, feet.z, p.getYRot(), p.getXRot(),
					java.util.EnumSet.of(net.minecraft.world.entity.RelativeMovement.X_ROT, net.minecraft.world.entity.RelativeMovement.Y_ROT));
		} else {
			e.moveTo(feet.x, feet.y, feet.z, e.getYRot(), e.getXRot());
			if (e instanceof net.minecraft.world.entity.Mob mob) mob.getNavigation().stop();
		}
		e.setDeltaMovement(Vec3.ZERO);
		e.fallDistance = 0;
	}

	private static void tickGrasps(MinecraftServer server, long now) {
		if (!GRASPS.isEmpty()) {
			for (Iterator<Map.Entry<UUID, Grasp>> it = GRASPS.entrySet().iterator(); it.hasNext(); ) {
				var entry = it.next();
				ServerPlayer owner = Handhelds.player(server, entry.getKey());
				Grasp g = entry.getValue();
				boolean done;
				try {
					done = owner == null || !owner.isAlive() || tickGrasp(owner, g, now);
				} catch (Exception e) {
					AuraPoints.LOGGER.error("Abyssal Grasp failed", e);
					done = true;
				}
				if (done) {
					it.remove();
					// a throw plays its own fling; anything else just relaxes the tentacles
					if (owner != null && !g.thrown) NetworkHandler.sendGrasp(owner, com.toast.aurapoints.network.GraspPacket.NONE, null, null, 0);
				}
			}
		}
		if (!THROWN.isEmpty()) {
			for (Iterator<Map.Entry<LivingEntity, Thrown>> it = THROWN.entrySet().iterator(); it.hasNext(); ) {
				var entry = it.next();
				LivingEntity e = entry.getKey();
				if (!e.isAlive() || now >= entry.getValue().until()) {
					it.remove();
					continue;
				}
				if (e.horizontalCollision) {
					it.remove();
					ServerPlayer owner = Handhelds.player(server, entry.getValue().owner());
					if (owner == null) continue;
					e.invulnerableTime = 0;
					Handhelds.hurt(owner, e, AuraConfig.GRASP_WALL_DAMAGE);
					Vec3 c = AuraFx.chest(e);
					var level = (ServerLevel) e.level();
					AuraFx.particles(level, ParticleTypes.SQUID_INK, c.x, c.y, c.z, 15, 0.3, 0.4, 0.3, 0.05);
					AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(BlockPos.containing(c.add(e.getDeltaMovement().normalize())))), c.x, c.y, c.z, 20, 0.3, 0.4, 0.3, 0.1);
					level.playSound(null, c.x, c.y, c.z, AuraSounds.PLAYER_ATTACK_KNOCKBACK.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
					level.playSound(null, c.x, c.y, c.z, AuraSounds.SCULK_BLOCK_BREAK.value(), SoundSource.PLAYERS, 1.0f, 0.5f);
				}
			}
		}
	}

	/** One tick of a grasp; true when it's over. */
	private static boolean tickGrasp(ServerPlayer owner, Grasp g, long now) {
		int age = g.age++;
		ServerLevel level = owner.serverLevel();
		LivingEntity t = g.target;
		switch (g.phase) {
			case REACH -> {
				if (age + 1 < Math.max(1, AuraConfig.GRASP_REACH_TICKS)) return false;
				if (t == null || !t.isAlive() || t.level() != level || t.distanceTo(owner) > AuraConfig.GRASP_RANGE + 3) {
					NetworkHandler.sendGrasp(owner, com.toast.aurapoints.network.GraspPacket.MISS, null, g.aim, 8);
					AuraAbilities.startCooldown(owner, Ability.ABYSSAL_GRASP, AuraConfig.GRASP_MISS_COOLDOWN_TICKS, now);
					level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), AuraSounds.SCULK_BLOCK_HIT.value(), SoundSource.PLAYERS, 1.0f, 0.5f);
					return true;
				}
				// caught
				t.invulnerableTime = 0;
				Handhelds.hurt(owner, t, AuraConfig.GRASP_DAMAGE);
				g.phase = Phase.PULL;
				g.age = 0;
				g.caughtAt = now;
				g.from = t.position();
				int ticks = Math.max(1, AuraConfig.GRASP_PULL_TICKS) + Math.max(1, AuraConfig.GRASP_HOLD_TICKS);
				NetworkHandler.sendGrasp(owner, com.toast.aurapoints.network.GraspPacket.HOLD, t, null, ticks);
				if (t instanceof ServerPlayer p) {
					com.toast.aurapoints.shim.neoforge.network.PacketDistributor.sendToPlayer(p, new com.toast.aurapoints.network.CarriedPacket(ticks + 2));
					p.displayClientMessage(Component.literal("Seized!").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), true);
				}
				Vec3 c = AuraFx.chest(t);
				AuraFx.particles(level, ParticleTypes.SQUID_INK, c.x, c.y, c.z, 20, 0.3, 0.4, 0.3, 0.05);
				level.playSound(null, c.x, c.y, c.z, AuraSounds.SCULK_BLOCK_BREAK.value(), SoundSource.PLAYERS, 1.5f, 0.5f);
				level.playSound(null, c.x, c.y, c.z, AuraSounds.PLAYER_ATTACK_KNOCKBACK.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
				return false;
			}
			case PULL -> {
				if (!t.isAlive() || t.level() != level) return true;
				// whipped in: slow off the mark, fast at the end
				double f = Math.min(1.0, (age + 1.0) / Math.max(1, AuraConfig.GRASP_PULL_TICKS));
				Vec3 to = holdSpot(owner, t);
				Vec3 at = g.from.add(to.subtract(g.from).scale(f * f)).add(0, Math.sin(f * Math.PI) * 0.8, 0);
				place(t, at);
				if (f >= 1.0) {
					g.phase = Phase.HOLD;
					g.age = 0;
				}
				return false;
			}
			case HOLD -> {
				if (!t.isAlive() || t.level() != level) return true;
				place(t, holdSpot(owner, t));
				if (age % 6 == 0) {
					Vec3 c = AuraFx.chest(t);
					AuraFx.particles(level, AuraFx.dust(VIOLET, 1.0f), c.x, c.y, c.z, 4, 0.3, 0.4, 0.3, 0);
				}
				if (age + 1 >= Math.max(1, AuraConfig.GRASP_HOLD_TICKS)) {
					throwHeld(owner, g, now);
					return true;
				}
				return false;
			}
		}
		return true;
	}

	/** Thrown where the owner looks; the full cooldown starts. The caller takes the grasp out of GRASPS. */
	private static void throwHeld(ServerPlayer owner, Grasp g, long now) {
		g.thrown = true;
		LivingEntity t = g.target;
		Vec3 look = owner.getLookAngle();
		if (t != null && t.isAlive()) {
			t.setDeltaMovement(look.scale(AuraConfig.GRASP_THROW_STRENGTH).add(0, AuraConfig.GRASP_THROW_LIFT, 0));
			t.hurtMarked = true;
			THROWN.put(t, new Thrown(owner.getUUID(), now + 30));
			if (t instanceof ServerPlayer p) com.toast.aurapoints.shim.neoforge.network.PacketDistributor.sendToPlayer(p, new com.toast.aurapoints.network.CarriedPacket(0));
		}
		NetworkHandler.sendGrasp(owner, com.toast.aurapoints.network.GraspPacket.THROW, t, null, 7);
		NetworkHandler.sendGesture(owner, BendingAnimPacket.GESTURE_WIND_CHARGE, 1);
		ServerLevel level = owner.serverLevel();
		level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), AuraSounds.PLAYER_ATTACK_SWEEP.value(), SoundSource.PLAYERS, 1.2f, 0.5f);
		level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), AuraSounds.WARDEN_ATTACK_IMPACT.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
		AuraAbilities.markUsed(owner, Ability.ABYSSAL_GRASP, now);
	}

	/** The held one is let go where they hang (the owner is gone). */
	private static void drop(UUID owner) {
		Grasp g = GRASPS.remove(owner);
		if (g != null && g.target instanceof ServerPlayer p) {
			com.toast.aurapoints.shim.neoforge.network.PacketDistributor.sendToPlayer(p, new com.toast.aurapoints.network.CarriedPacket(0));
		}
	}

	// ---------------------------------------------------------------
	// Echolocation + ticking
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		Noise.tick(server);
		long now = server.getTickCount();
		tickPlanting(server, now);
		tickCharging(server, now);
		tickGrasps(server, now);
		tickPlants(server, BLOSSOMS, (owner, b) -> tickBlossom(owner, b, now), b -> {
		});
		tickPlants(server, TREES, (owner, t) -> tickTree(owner, t, now), Resonance::release);
		if (now % 4 == 0) echo(server, now);
	}

	private static <T extends Plant> void tickPlants(MinecraftServer server, Map<UUID, Deque<T>> all, java.util.function.BiPredicate<ServerPlayer, T> tick,
									   java.util.function.Consumer<T> end) {
		if (all.isEmpty()) return;
		for (Iterator<Map.Entry<UUID, Deque<T>>> it = all.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			ServerPlayer owner = Handhelds.player(server, entry.getKey());
			for (Iterator<T> pi = entry.getValue().iterator(); pi.hasNext(); ) {
				T plant = pi.next();
				boolean done;
				try {
					done = owner == null || tick.test(owner, plant);
				} catch (Exception e) {
					AuraPoints.LOGGER.error("Resonance plant failed", e);
					done = true;
				}
				if (done) {
					end.accept(plant);
					plant.wither(server);
					pi.remove();
				}
			}
			if (entry.getValue().isEmpty()) it.remove();
		}
	}

	/** A plant's end: it sinks into the ground and is gone a moment later. */
	private static void wither(RootCanvas canvas, MinecraftServer server) {
		if (canvas.pieces.isEmpty()) return;
		var first = canvas.pieces.get(0);
		Vec3 at = first.position().add(0, -0.5, 0);
		for (var d : canvas.pieces) if (d instanceof Display dd && !dd.isRemoved()) RootCanvas.hide(dd, at, 8);
		AuraFx.particles(canvas.level, ParticleTypes.SQUID_INK, at.x, at.y + 1, at.z, 8, 0.4, 0.5, 0.4, 0.01);
		AuraFx.later(server != null ? server : canvas.level.getServer(), 9, canvas::discardAll);
	}

	/** Every 4 ticks: who each Resonance player hears (noisy or dart-marked) - their client outlines exactly those. */
	private static void echo(MinecraftServer server, long now) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			AuraPlayerData data = AuraState.get(server).getOrCreate(p.getUUID());
			Set<Integer> ids = new HashSet<>();
			if (isResonant(p, data) && AuraAbilities.hasAbility(data, Ability.ECHOLOCATION)) {
				Map<LivingEntity, Long> marks = MARKS.get(p.getUUID());
				if (marks != null) marks.entrySet().removeIf(m -> m.getValue() <= now || !m.getKey().isAlive());
				double r = AuraConfig.ECHO_RANGE;
				List<LivingEntity> heard = p.serverLevel().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r),
						e -> e != p && e.isAlive() && !e.isSpectator() && e.distanceToSqr(p) <= r * r
								&& (Noise.isLoud(e) || (marks != null && marks.containsKey(e))));
				heard.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
				for (LivingEntity e : heard) {
					if (ids.size() >= HighlightPacket.MAX_IDS) break; // more would not fit the packet and kick the player
					ids.add(e.getId());
				}
			}
			Set<Integer> was = SENT.getOrDefault(p.getUUID(), Set.of());
			if (ids.equals(was)) continue;
			if (ids.isEmpty()) SENT.remove(p.getUUID());
			else SENT.put(p.getUUID(), ids);
			NetworkHandler.sendHighlight(p, new ArrayList<>(ids));
		}
	}

	/** DEV ONLY: each plant's model (state, removed) and how many plant models exist in the level. */
	public static String devModels(ServerLevel level) {
		StringBuilder sb = new StringBuilder();
		for (Deque<Blossom> q : BLOSSOMS.values()) for (Blossom b : q) sb.append(" blossom=").append(b.model == null ? "none" : b.model.state() + (b.model.isRemoved() ? "x" : ""));
		for (Deque<Tree> q : TREES.values()) for (Tree t : q) sb.append(" tree=").append(t.model == null ? "none" : t.model.state() + (t.model.isRemoved() ? "x" : "")).append(t.held != null ? "(holding)" : "");
		int n = 0;
		for (var e : level.getAllEntities()) if (e instanceof com.toast.aurapoints.geo.SonicBlossom || e instanceof com.toast.aurapoints.geo.ListeningTree) n++;
		return sb.append(" models_in_world=").append(n).toString();
	}

	/** DEV ONLY: a Resonance ability for the fake player, no cooldown (1 blossom, 2 tree, 3 grasp; again = throw). */
	public static boolean devPress(ServerPlayer player, int which) {
		Handhelds.devPlayer = player;
		long now = player.getServer().getTickCount() + 100000;
		return switch (which) {
			case 1 -> {
				boolean ok = plantBlossom(player, now);
				grow(player, now); // a fake player never finishes a windup on its own
				PLANTING.remove(player.getUUID());
				yield ok;
			}
			case 2 -> plantTree(player, now);
			case 5 -> {
				// a tree, hit 3 times by someone else (the planter's hits don't count)
				plantTree(player, now);
				Tree t = TREES.get(player.getUUID()).peekLast();
				var other = com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory.get(player.serverLevel(),
						new com.mojang.authlib.GameProfile(UUID.fromString("5bd1f9a0-0000-4000-8000-000000000002"), "[AuraDev2]"));
				onHitbox(player, t.box);
				for (int i = 0; i < AuraConfig.LISTENING_TREE_HITS; i++) {
					onHitbox(other, t.box);
					AuraPoints.LOGGER.info("[auradev] tree hit {} broken={}", i + 1, t.broken);
				}
				yield t.broken;
			}
			default -> {
				// the fake player can't hold a key: a press, then let go at full charge
				if (GRASPS.containsKey(player.getUUID())) yield grasp(player, now);
				boolean ok = grasp(player, now);
				graspRelease(player, now + AuraConfig.GRASP_CHARGE_TICKS);
				yield ok;
			}
		};
	}

	public static void onLogout(ServerPlayer player) {
		if (PLANTING.remove(player.getUUID()) != null) Stun.windup(player, false, 0);
		if (CHARGING.remove(player.getUUID()) != null) Stun.windup(player, false, 0);
		drop(player.getUUID());
		// held by someone's grasp: let go
		GRASPS.values().removeIf(g -> g.target == player);
		MARKS.remove(player.getUUID());
		SENT.remove(player.getUUID());
		// their plants go with them
		Deque<Blossom> b = BLOSSOMS.remove(player.getUUID());
		if (b != null) for (Blossom x : b) x.discardAll();
		Deque<Tree> t = TREES.remove(player.getUUID());
		if (t != null) for (Tree x : t) {
			release(x);
			x.discardAll();
		}
	}

	public static void clearAll() {
		for (Deque<Blossom> q : BLOSSOMS.values()) for (Blossom b : q) b.discardAll();
		for (Deque<Tree> q : TREES.values()) for (Tree t : q) t.discardAll();
		BLOSSOMS.clear();
		TREES.clear();
		PLANTING.clear();
		CHARGING.clear();
		GRASPS.clear();
		THROWN.clear();
		MARKS.clear();
		SENT.clear();
		Noise.clearAll();
	}
}
