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
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

/**
 * Entanglement, Verdant's second active - a root trap. Look at a spot (or at someone: it grows under their
 * feet) and press: root arms tear out of the ground there. While they rise (ENTANGLE_TRAP_ARM_TICKS) it is
 * only a warning; then they thrash about wildly, waiting. Whoever stays inside it ENTANGLE_TRAP_STAY_TICKS
 * in a row is seized by every arm at once (GRAB), yanked into the middle (WHIP) and wound up in the arms
 * (BOUND). They can't move (the camera stays free) until they break free, by one of two ways chosen at
 * random and shown on screen: MASH W A S D (all four, ENTANGLE_MASH_PRESSES in all) or CUT the 3 green arms
 * (any attack). Mobs break free after ENTANGLE_MOB_TICKS. If nobody is caught before ENTANGLE_TRAP_TICKS
 * run out, the arms pump a cloud of poison into the air and sink away. One trap per player.
 */
public final class Entanglement {

	private Entanglement() {
	}

	private static final int GREEN_PULSE = 0xE8F07A;
	private static final net.minecraft.resources.ResourceLocation GRAB_SLOW = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("aurapoints", "entangle_grab");
	private static final int ARM_SEGS = 7;

	private enum Phase { GRAB, WHIP, BOUND, FREE }

	private enum Mode { MASH, CUT }

	/** A set of root arms round a centre: their shapes and block pieces. Made by the trap, handed on to the snare. */
	private static final class Roots {
		final RootCanvas canvas;
		final Vec3 center;
		final int n;
		final double[] angle, base, length, height, turns;
		final int[] spin;
		final boolean[] gone;
		final List<List<Display.BlockDisplay>> arms = new ArrayList<>();
		double bodyRadius = 0.42, bodyHeight = 1.8;

		Roots(RootCanvas canvas, Vec3 center, double radius) {
			this.canvas = canvas;
			this.center = center;
			this.n = Math.max(3, AuraConfig.ENTANGLE_ARMS);
			angle = new double[n];
			base = new double[n];
			length = new double[n];
			height = new double[n];
			turns = new double[n];
			spin = new int[n];
			gone = new boolean[n];
			var random = canvas.level.random;
			double offset = random.nextDouble() * Math.PI * 2;
			BlockState bark = Blocks.OAK_LOG.defaultBlockState(), root = Blocks.MANGROVE_ROOTS.defaultBlockState();
			for (int k = 0; k < n; k++) {
				angle[k] = offset + k * Math.PI * 2 / n + (random.nextDouble() - 0.5) * 0.4;
				base[k] = radius * (0.7 + random.nextDouble() * 0.3);
				length[k] = (2.0 + random.nextDouble() * 0.9) * Math.max(0.2, AuraConfig.ENTANGLE_ARM_SCALE);
				turns[k] = 0.55 + random.nextDouble() * 0.45;
				spin[k] = k % 2 == 0 ? 1 : -1; // alternate ways round, so they weave
				List<Display.BlockDisplay> segs = new ArrayList<>();
				for (int i = 0; i < ARM_SEGS; i++) segs.add(canvas.piece(i < 2 ? bark : root, center.subtract(0, 3, 0)));
				arms.add(segs);
			}
			fitTo(1.8, 0.6);
		}

		/** Sizes the coil for a body {@code h} high and {@code w} wide: most arms wind round it, every third over the head. */
		void fitTo(double h, double w) {
			bodyHeight = h;
			bodyRadius = Math.max(0.35, w * 0.55 + 0.12);
			var random = canvas.level.random;
			for (int k = 0; k < n; k++) height[k] = k % 3 == 0 ? h + 0.25 : h * (0.55 + random.nextDouble() * 0.4);
		}

		Vec3 armBase(int k) {
			return center.add(Math.cos(angle[k]) * base[k], -0.4, Math.sin(angle[k]) * base[k]);
		}

		/** Thrashing: up out of the ground, leaning out, every joint whipping about - wilder toward the tip. */
		Vec3[] thrash(int k, double grow, int age) {
			double t = age * AuraConfig.ENTANGLE_THRASH_SPEED;
			Vec3[] pts = new Vec3[ARM_SEGS + 1];
			pts[0] = armBase(k);
			double a = angle[k];
			Vec3 out = new Vec3(Math.cos(a), 0, Math.sin(a)), side = new Vec3(-Math.sin(a), 0, Math.cos(a));
			double seg = length[k] / ARM_SEGS * grow;
			for (int i = 1; i <= ARM_SEGS; i++) {
				double f = i / (double) ARM_SEGS, amp = 0.35 + 1.1 * f;
				double w1 = Math.sin(t * 0.85 + i * 0.9 + k * 2.3) * amp, w2 = Math.cos(t * 1.13 + i * 0.6 + k * 1.7) * amp;
				Vec3 d = new Vec3(0, 1, 0).add(out.scale(0.3 + 0.12 * i + w2 * 0.5)).add(side.scale(w1)).normalize();
				pts[i] = pts[i - 1].add(d.scale(seg));
			}
			return pts;
		}

		/** Seizing: up out of the ground and over, the tip clamped onto {@code tip}, still whipping a little. */
		Vec3[] reach(int k, Vec3 tip, int age) {
			double t = age * AuraConfig.ENTANGLE_THRASH_SPEED, scale = Math.max(0.2, AuraConfig.ENTANGLE_ARM_SCALE);
			Vec3[] pts = new Vec3[ARM_SEGS + 1];
			Vec3 b = armBase(k), top = b.add(0, 1.4 * scale, 0), control = top.lerp(tip, 0.35).add(0, 0.5 * scale, 0);
			for (int i = 0; i <= ARM_SEGS; i++) {
				double g = i / (double) ARM_SEGS, u = 1 - g;
				Vec3 p = b.scale(u * u).add(control.scale(2 * u * g)).add(tip.scale(g * g));
				pts[i] = p.add(0, Math.sin(g * Math.PI * 3 - t * 1.2 - k) * 0.1 * Math.sin(g * Math.PI), 0);
			}
			return pts;
		}

		/** Coiled: from the ground spiralling in and up round the body at {@code at}, squirming a little. */
		Vec3 coilPoint(int k, double f, Vec3 at, double squirm, int t) {
			double ang = angle[k] + spin[k] * turns[k] * Math.PI * 2 * f;
			double tighten = Math.min(1, f * 2.2);
			tighten = tighten * tighten * (3 - 2 * tighten);
			double r = base[k] + (bodyRadius - base[k]) * tighten;
			r += 0.05 * Math.sin(f * Math.PI * 4 + k) + squirm * Math.sin(t * 0.3 * AuraConfig.ENTANGLE_THRASH_SPEED + k * 1.3 + f * 5);
			if (height[k] > bodyHeight && f > 0.8) r *= 1 - (f - 0.8) / 0.2 * 0.85; // closes over the top of the head
			return at.add(Math.cos(ang) * r, -0.4 + f * (height[k] + 0.4), Math.sin(ang) * r);
		}

		Vec3[] coil(int k, Vec3 at, double squirm, int t) {
			Vec3[] pts = new Vec3[ARM_SEGS + 1];
			for (int i = 0; i <= ARM_SEGS; i++) pts[i] = coilPoint(k, i / (double) ARM_SEGS, at, squirm, t);
			return pts;
		}

		/** How thick the arms are: full ARM_SCALE in the air ({@code air} 1), only a little thicker once wound round a body (0). */
		double thick(double air) {
			double scale = Math.max(0.2, AuraConfig.ENTANGLE_ARM_SCALE);
			double coiled = 1 + (scale - 1) * 0.35;
			return coiled + (scale - coiled) * air;
		}

		void draw(int k, Vec3[] pts, double thickness, int interp) {
			List<Display.BlockDisplay> segs = arms.get(k);
			for (int i = 0; i < ARM_SEGS; i++) {
				float w = (float) ((0.32 - 0.21 * i / (double) (ARM_SEGS - 1)) * Math.max(0.15, thickness));
				RootCanvas.stretch(segs.get(i), pts[i], pts[i + 1], w, 1.35f, interp);
			}
		}

		static Vec3[] blend(Vec3[] a, Vec3[] b, double t) {
			Vec3[] out = new Vec3[a.length];
			for (int i = 0; i < a.length; i++) out[i] = a[i].lerp(b[i], t);
			return out;
		}
	}

	private static final class Snare {
		final ServerPlayer caster;
		final LivingEntity target;
		final RootCanvas canvas;
		final Roots roots;
		final Vec3 grabSpot;
		final boolean[] armGone;
		final List<Green> greens = new ArrayList<>();
		Phase phase = Phase.GRAB;
		Mode mode;
		int age, phaseAge;
		Vec3 whipFrom;
		final int[] presses = new int[4];
		final long[] lastPress = new long[4];
		/** Hits the caught one has taken while wrapped up: each loosens the roots. */
		int hits;

		Snare(ServerPlayer caster, LivingEntity target, Roots roots) {
			this.caster = caster;
			this.target = target;
			this.roots = roots;
			this.canvas = roots.canvas;
			this.grabSpot = roots.center;
			this.armGone = roots.gone;
			roots.fitTo(target.getBbHeight(), target.getBbWidth());
		}
	}

	/** One of the three green arms in CUT mode, with the hitbox that cuts it. */
	private static final class Green {
		final List<Display.BlockDisplay> parts = new ArrayList<>();
		Interaction box;
		boolean cut;
		int rib;
	}

	private static final List<Snare> SNARES = new ArrayList<>();
	private static final List<Trap> TRAPS = new ArrayList<>();

	/** A Verdant root piece left over from a crash (Entanglement, its trap, Viper): removed on load. */
	public static boolean isLeftover(Entity e) {
		return RootCanvas.isLeftover(e);
	}

	public static boolean isEntangled(LivingEntity e) {
		for (Snare s : SNARES) if (s.target == e) return true;
		return false;
	}

	private static Vec3 coilPoint(Snare s, int k, double f, Vec3 at, double squirm) {
		return s.roots.coilPoint(k, f, at, squirm, s.age);
	}

	// ---------------------------------------------------------------
	// The press
	// ---------------------------------------------------------------

	public static boolean press(ServerPlayer player, long now) {
		if (!AuraAbilities.checkCooldown(player, Ability.ENTANGLEMENT, AuraConfig.ENTANGLE_COOLDOWN_TICKS, now)) return false;
		VineHook.Trace aim = VineHook.trace(player, player.getLookAngle(), AuraConfig.ENTANGLE_RANGE);
		// aimed at someone: the trap grows under their feet; else on the ground under the crosshair
		Vec3 at = aim.target() != null ? aim.target().position() : aim.tip();
		double y = RootCanvas.surfaceY(player.serverLevel(), at, 1, 8);
		if (Double.isNaN(y)) {
			player.displayClientMessage(Component.literal("No ground there for the roots.").withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_ENTANGLE, 0);
		// one trap per player: an older one sinks away
		for (Trap t : TRAPS) if (t.caster == player && t.state == Trap.State.SET) t.sink(false);
		TRAPS.add(new Trap(player, new Vec3(at.x, y, at.z)));
		AuraAbilities.markUsed(player, Ability.ENTANGLEMENT, now);
		return true;
	}

	/** The trap springs on {@code target}: every arm lets go of the air and seizes it. */
	private static void seize(Trap trap, LivingEntity target) {
		Snare s = new Snare(trap.caster, target, trap.roots);
		ServerLevel level = s.canvas.level;
		// held right where it was seized, then yanked into the middle
		Rooted.lock(target, target.position(), false);
		Vec3 c = AuraFx.chest(target);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.EVOKER_FANGS_ATTACK.value(), SoundSource.PLAYERS, 1.3f, 0.5f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.MANGROVE_ROOTS_BREAK.value(), SoundSource.PLAYERS, 1.4f, 0.6f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.PLAYER_ATTACK_SWEEP.value(), SoundSource.PLAYERS, 1.2f, 0.5f);
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MANGROVE_ROOTS.defaultBlockState()), c.x, c.y, c.z, 30, 0.4, 0.6, 0.4, 0.2);
		if (target instanceof ServerPlayer p) {
			p.displayClientMessage(Component.literal("✦ The roots have you! ✦").withStyle(ChatFormatting.DARK_GREEN, ChatFormatting.BOLD), true);
		}
		AuraStats.add(trap.caster, AuraStats.ENTANGLE_CATCHES, 1);
		SNARES.add(s);
		drawArms(s);
	}

	// ---------------------------------------------------------------
	// Every tick
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		long now = server.getTickCount();
		for (Iterator<Snare> it = SNARES.iterator(); it.hasNext(); ) {
			Snare s = it.next();
			if (!s.target.isAlive() || s.target.isRemoved() || s.target.level() != s.canvas.level
					|| (s.target instanceof ServerPlayer p && p.hasDisconnected())) {
				release(s, false);
				it.remove();
				continue;
			}
			s.age++;
			s.phaseAge++;
			switch (s.phase) {
				case GRAB -> {
					drawArms(s);
					if (s.phaseAge >= Math.max(1, AuraConfig.ENTANGLE_GRAB_TICKS)) startWhip(s);
				}
				case WHIP -> {
					whip(s);
					if (s.phaseAge >= Math.max(1, AuraConfig.ENTANGLE_WHIP_TICKS)) bind(s);
				}
				case BOUND -> bound(s, now);
				case FREE -> {
					if (s.phaseAge >= 12) {
						s.canvas.discardAll();
						it.remove();
					}
				}
			}
		}
		for (Iterator<Trap> it = TRAPS.iterator(); it.hasNext(); ) {
			if (it.next().tick()) it.remove();
		}
	}

	/** Draws every arm of a snare for its phase. */
	private static void drawArms(Snare s) {
		Roots r = s.roots;
		int grabTicks = Math.max(1, AuraConfig.ENTANGLE_GRAB_TICKS), whipTicks = Math.max(1, AuraConfig.ENTANGLE_WHIP_TICKS);
		Vec3 chest = AuraFx.chest(s.target);
		for (int k = 0; k < r.n; k++) {
			if (s.armGone[k]) continue;
			Vec3[] pts;
			int interp = 1;
			double thick;
			switch (s.phase) {
				case GRAB -> {
					// all at once, hard: from thrashing straight onto the body
					double p = Math.min(1.0, (s.phaseAge + 1) / (double) grabTicks);
					pts = Roots.blend(r.thrash(k, 1, s.age), r.reach(k, chest, s.age), 1 - (1 - p) * (1 - p) * (1 - p));
					thick = r.thick(1);
				}
				case WHIP -> {
					double p = Math.min(1.0, s.phaseAge / (double) whipTicks);
					double e = p * p * (3 - 2 * p);
					pts = Roots.blend(r.reach(k, chest, s.age), r.coil(k, s.target.position(), 0, s.age), e);
					thick = r.thick(1 - e);
				}
				default -> {
					pts = r.coil(k, s.grabSpot, 0.03, s.age);
					interp = 3;
					thick = r.thick(0);
				}
			}
			r.draw(k, pts, thick, interp);
		}
	}

	/** Yanked: the roots drag it into the middle while they wind round it. */
	private static void startWhip(Snare s) {
		s.phase = Phase.WHIP;
		s.phaseAge = 0;
		Rooted.unlock(s.target);
		s.whipFrom = s.target.position();
		ServerLevel level = s.canvas.level;
		Vec3 c = AuraFx.chest(s.target);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.FISHING_BOBBER_RETRIEVE.value(), SoundSource.PLAYERS, 1.2f, 0.5f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.ROOTED_DIRT_BREAK.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
	}

	/** The whip: along a little arc into the middle, set in place every tick (walls can't stop it). */
	private static void whip(Snare s) {
		int ticks = Math.max(1, AuraConfig.ENTANGLE_WHIP_TICKS);
		double p = Math.min(1.0, s.phaseAge / (double) ticks);
		double eased = 1 - (1 - p) * (1 - p);
		Vec3 at = s.whipFrom.lerp(s.grabSpot, eased).add(0, Math.sin(Math.PI * p) * 0.5, 0);
		place(s.target, at);
		drawArms(s);
	}

	/** Wrapped up: the arms hold it, and the way out is chosen. */
	private static void bind(Snare s) {
		s.phase = Phase.BOUND;
		s.phaseAge = 0;
		place(s.target, s.grabSpot);
		s.mode = s.target instanceof ServerPlayer && s.canvas.level.random.nextDouble() < AuraConfig.ENTANGLE_CUT_CHANCE ? Mode.CUT : Mode.MASH;
		if (s.mode == Mode.CUT) {
			// three of the arms turn green: those are the ones to cut
			java.util.Set<Integer> green = new java.util.HashSet<>();
			while (green.size() < Math.min(3, s.roots.n)) green.add(s.canvas.level.random.nextInt(s.roots.n));
			for (int k : green) {
				Green g = new Green();
				g.rib = k;
				for (Display.BlockDisplay d : s.roots.arms.get(k)) {
					RootCanvas.setBlock(d, Blocks.MOSS_BLOCK.defaultBlockState());
					g.parts.add(d);
				}
				Vec3 mid = coilPoint(s, k, 0.5, s.grabSpot, 0);
				g.box = s.canvas.hitbox(mid.subtract(0, 0.45, 0), 0.6f, 0.9f);
				s.greens.add(g);
			}
		}
		drawArms(s);
		burst(s.canvas.level, s.grabSpot.add(0, 0.2, 0), true);
		Rooted.lock(s.target, s.grabSpot, s.mode == Mode.MASH && s.target instanceof ServerPlayer);
		ServerLevel level = s.canvas.level;
		Vec3 c = AuraFx.chest(s.target);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.ROOTED_DIRT_PLACE.value(), SoundSource.PLAYERS, 1.4f, 0.5f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.MANGROVE_ROOTS_PLACE.value(), SoundSource.PLAYERS, 1.4f, 0.6f);
		if (s.target instanceof ServerPlayer p) {
			if (s.mode == Mode.MASH) title(p, "MASH  W  A  S  D !", "use all four keys to break free", ChatFormatting.GOLD);
			else title(p, "CUT THE GREEN ROOTS !", "hit the 3 glowing green roots to break free", ChatFormatting.GREEN);
		}
	}

	/** Bound: the green arms pulse, the target struggles; free once the skill check is done. */
	private static void bound(Snare s, long now) {
		ServerLevel level = s.canvas.level;
		if (s.age % 3 == 0) drawArms(s); // the arms keep squirming round it
		if (s.mode == Mode.CUT) {
			double pulse = 0.5 + 0.5 * Math.sin(s.age * 0.35);
			Brightness glow = new Brightness(Math.max(s.canvas.light.block(), 6 + (int) Math.round(9 * pulse)), s.canvas.light.sky());
			for (Green g : s.greens) {
				if (g.cut) continue;
				for (Display.BlockDisplay d : g.parts) RootCanvas.setLight(d, glow);
				if (s.age % 4 == 0) {
					Vec3 m = coilPoint(s, g.rib, 0.5, s.grabSpot, 0);
					AuraFx.particles(level, AuraFx.dust(GREEN_PULSE, 0.5f), m.x, m.y, m.z, 1, 0.12, 0.2, 0.12, 0.0);
				}
			}
			if (s.greens.stream().allMatch(g -> g.cut)) free(s);
		} else if (s.target instanceof ServerPlayer p) {
			if (s.age % 4 == 0) p.displayClientMessage(mashProgress(s), true);
		} else if (s.phaseAge >= AuraConfig.ENTANGLE_MOB_TICKS) {
			free(s); // mobs can't do the skill check
		}
		if (s.age % 20 == 0) level.playSound(null, s.grabSpot.x, s.grabSpot.y + 1, s.grabSpot.z, AuraSounds.MANGROVE_ROOTS_STEP.value(), SoundSource.PLAYERS, 0.8f, 0.6f);
	}

	private static void place(LivingEntity t, Vec3 at) {
		t.setDeltaMovement(Vec3.ZERO);
		t.fallDistance = 0;
		if (t instanceof ServerPlayer p) {
			p.connection.teleport(at.x, at.y, at.z, p.getYRot(), p.getXRot(), EnumSet.of(RelativeMovement.X_ROT, RelativeMovement.Y_ROT));
		} else {
			t.setPos(at.x, at.y, at.z);
		}
	}

	private static void title(ServerPlayer p, String title, String subtitle, ChatFormatting color) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(2, 40, 8));
		p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle).withStyle(ChatFormatting.GRAY)));
		p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title).withStyle(color, ChatFormatting.BOLD)));
	}

	private static Component mashProgress(Snare s) {
		int need = Math.max(4, AuraConfig.ENTANGLE_MASH_PRESSES), min = Math.max(1, AuraConfig.ENTANGLE_MASH_MIN_PER_KEY);
		int total = 0;
		for (int n : s.presses) total += n;
		int bars = 20, filled = Math.min(bars, total * bars / need);
		var out = Component.literal("MASH ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		String[] keys = {"W", "A", "S", "D"};
		for (int i = 0; i < 4; i++) {
			out.append(Component.literal(keys[i] + " ").withStyle(s.presses[i] >= min ? ChatFormatting.GREEN : ChatFormatting.WHITE, ChatFormatting.BOLD));
		}
		out.append(Component.literal("▮".repeat(filled)).withStyle(ChatFormatting.GREEN))
				.append(Component.literal("▮".repeat(bars - filled)).withStyle(ChatFormatting.DARK_GRAY));
		return out;
	}

	/** A mash press from the entangled player's client (0 W, 1 A, 2 S, 3 D). */
	public static void onMashKey(ServerPlayer player, int key) {
		if (key < 0 || key > 3) return;
		for (Snare s : SNARES) {
			if (s.target != player || s.phase != Phase.BOUND || s.mode != Mode.MASH) continue;
			long nowMs = System.currentTimeMillis();
			if (nowMs - s.lastPress[key] < 40) return; // no macros: 25 presses a second per key at most
			s.lastPress[key] = nowMs;
			s.presses[key]++;
			int total = 0, min = Integer.MAX_VALUE;
			for (int n : s.presses) {
				total += n;
				min = Math.min(min, n);
			}
			if (s.presses[key] % 3 == 0) {
				Vec3 c = AuraFx.chest(player);
				AuraFx.particles(s.canvas.level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MANGROVE_ROOTS.defaultBlockState()), c.x, c.y, c.z, 4, 0.4, 0.5, 0.4, 0.05);
				s.canvas.level.playSound(null, c.x, c.y, c.z, AuraSounds.MANGROVE_ROOTS_HIT.value(), SoundSource.PLAYERS, 0.7f, 0.8f + key * 0.1f);
			}
			if (total >= Math.max(4, AuraConfig.ENTANGLE_MASH_PRESSES) && min >= Math.max(1, AuraConfig.ENTANGLE_MASH_MIN_PER_KEY)) free(s);
			return;
		}
	}

	/** A hit on one of our hitboxes: a green arm round someone is cut. True if it was ours. */
	public static boolean onHitbox(ServerPlayer attacker, Entity box) {
		for (Snare s : SNARES) {
			for (Green g : s.greens) {
				if (g.box != box || g.cut) continue;
				g.cut = true;
				s.armGone[g.rib] = true;
				Vec3 m = coilPoint(s, g.rib, 0.5, s.grabSpot, 0);
				for (Display.BlockDisplay d : g.parts) s.canvas.remove(d);
				s.canvas.remove(g.box);
				ServerLevel level = s.canvas.level;
				AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()), m.x, m.y, m.z, 25, 0.2, 0.4, 0.2, 0.1);
				AuraFx.particles(level, AuraFx.dust(GREEN_PULSE, 1.0f), m.x, m.y, m.z, 8, 0.2, 0.3, 0.2, 0.0);
				level.playSound(null, m.x, m.y, m.z, AuraSounds.MOSS_BREAK.value(), SoundSource.PLAYERS, 1.2f, 0.8f);
				level.playSound(null, m.x, m.y, m.z, AuraSounds.SHEEP_SHEAR.value(), SoundSource.PLAYERS, 1.0f, 0.7f);
				return true;
			}
		}
		return false;
	}

	/** Broken free: the cocoon bursts apart and falls into the ground. */
	private static void free(Snare s) {
		s.phase = Phase.FREE;
		s.phaseAge = 0;
		Rooted.unlock(s.target);
		ServerLevel level = s.canvas.level;
		Vec3 c = AuraFx.chest(s.target);
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MANGROVE_ROOTS.defaultBlockState()), c.x, c.y, c.z, 50, 0.6, 0.8, 0.6, 0.2);
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LOG.defaultBlockState()), c.x, c.y, c.z, 25, 0.6, 0.8, 0.6, 0.2);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.MANGROVE_ROOTS_BREAK.value(), SoundSource.PLAYERS, 1.5f, 0.8f);
		level.playSound(null, c.x, c.y, c.z, AuraSounds.ZOMBIE_BREAK_WOODEN_DOOR.value(), SoundSource.PLAYERS, 0.6f, 1.2f);
		// every piece drops into the ground and is gone a moment later
		for (var e : s.canvas.pieces) {
			if (e instanceof Display.BlockDisplay d) RootCanvas.hide(d, e.position().add(0, 3, 0).subtract(0, 0.5, 0), 10);
			if (e instanceof Interaction) e.discard();
		}
		if (s.target instanceof ServerPlayer p) {
			p.displayClientMessage(Component.literal("✦ Free! ✦").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), true);
			p.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
		}
	}

	/** Gone without the skill check (death, logout, server stop): everything is just removed. */
	private static void release(Snare s, boolean quiet) {
		Rooted.unlock(s.target);
		removeGrabSlow(s.target);
		s.canvas.discardAll();
	}

	private static void removeGrabSlow(LivingEntity t) {
		var speed = t.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) speed.removeModifier(GRAB_SLOW);
	}

	private static void burst(ServerLevel level, Vec3 at, boolean loud) {
		BlockState ground = RootCanvas.groundBlock(level, at);
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.1, at.z, 25, 0.35, 0.1, 0.35, 0.2);
		AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ROOTED_DIRT.defaultBlockState()), at.x, at.y + 0.1, at.z, 10, 0.3, 0.1, 0.3, 0.15);
		if (loud) level.playSound(null, at.x, at.y, at.z, AuraSounds.ROOTED_DIRT_BREAK.value(), SoundSource.PLAYERS, 1.0f, 0.7f);
	}

	public static void onLogout(ServerPlayer player) {
		for (Iterator<Snare> it = SNARES.iterator(); it.hasNext(); ) {
			Snare s = it.next();
			if (s.target == player) {
				release(s, true);
				it.remove();
			}
		}
	}

	/**
	 * The caught one was hit (by anyone): an arm tears loose, mashing gets a head start, and from the
	 * ENTANGLE_HIT_CHANCE_FROM-th hit on, every hit has ENTANGLE_HIT_FREE_CHANCE to burst the roots at once.
	 */
	public static void onDamaged(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source) {
		Entity by = source.getEntity();
		if (by == null || by == victim) return;
		for (Snare s : SNARES) {
			if (s.target != victim || s.phase != Phase.BOUND) continue;
			s.hits++;
			ServerLevel level = s.canvas.level;
			// one brown arm (never a green one - those still have to be cut) tears loose and falls away
			List<Integer> loose = new ArrayList<>();
			java.util.Set<Integer> green = new java.util.HashSet<>();
			for (Green g : s.greens) green.add(g.rib);
			for (int k = 0; k < s.roots.n; k++) if (!s.armGone[k] && !green.contains(k)) loose.add(k);
			if (!loose.isEmpty()) {
				int k = loose.get(level.random.nextInt(loose.size()));
				s.armGone[k] = true;
				for (Display.BlockDisplay d : s.roots.arms.get(k)) RootCanvas.hide(d, s.grabSpot.subtract(0, 0.6, 0), 8);
				Vec3 m = coilPoint(s, k, 0.5, s.grabSpot, 0);
				AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MANGROVE_ROOTS.defaultBlockState()), m.x, m.y, m.z, 20, 0.2, 0.4, 0.2, 0.1);
			}
			Vec3 c = AuraFx.chest(victim);
			level.playSound(null, c.x, c.y, c.z, AuraSounds.MANGROVE_ROOTS_BREAK.value(), SoundSource.PLAYERS, 1.0f, 1.1f);
			if (s.mode == Mode.MASH) {
				for (int i = 0; i < 4; i++) s.presses[i] += Math.max(0, AuraConfig.ENTANGLE_HIT_MASH_PER_KEY);
				if (mashDone(s)) {
					free(s);
					return;
				}
			}
			if (s.hits >= Math.max(1, AuraConfig.ENTANGLE_HIT_CHANCE_FROM) && level.random.nextDouble() < AuraConfig.ENTANGLE_HIT_FREE_CHANCE) {
				if (victim instanceof ServerPlayer p) p.displayClientMessage(Component.literal("✦ The hit tore the roots apart! ✦").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), true);
				free(s);
			}
			return;
		}
	}

	private static boolean mashDone(Snare s) {
		int total = 0, min = Integer.MAX_VALUE;
		for (int n : s.presses) {
			total += n;
			min = Math.min(min, n);
		}
		return total >= Math.max(4, AuraConfig.ENTANGLE_MASH_PRESSES) && min >= Math.max(1, AuraConfig.ENTANGLE_MASH_MIN_PER_KEY);
	}

	/** Poison from Verdant (the Viper's bite, the trap's cloud uses its own). */
	static void poison(LivingEntity target, Entity source, int amplifier, int ticks) {
		if (amplifier < 0 || ticks <= 0) return;
		target.addEffect(new MobEffectInstance(MobEffects.POISON, ticks, amplifier, false, true, true), source);
	}

	public static void clearAll() {
		for (Snare s : SNARES) release(s, true);
		SNARES.clear();
		for (Trap t : TRAPS) t.canvas.discardAll();
		TRAPS.clear();
	}

	// ---------------------------------------------------------------
	// The trap: root arms thrashing over a patch of churned, rooted earth
	// ---------------------------------------------------------------

	private static final class Trap {
		enum State { SET, SINKING }

		final ServerPlayer caster;
		final RootCanvas canvas;
		final Vec3 center;
		final double radius;
		final Roots roots;
		final List<Display.BlockDisplay> bed = new ArrayList<>();
		/** Who is standing in the armed trap, and for how many ticks in a row. */
		final java.util.Map<LivingEntity, Integer> dwell = new java.util.HashMap<>();
		State state = State.SET;
		int age, stateAge;

		Trap(ServerPlayer caster, Vec3 center) {
			this.caster = caster;
			this.center = center;
			this.radius = Math.max(0.6, AuraConfig.ENTANGLE_TRAP_RADIUS);
			this.canvas = new RootCanvas(caster.serverLevel(), BlockPos.containing(center).above());
			this.roots = new Roots(canvas, center, radius);
			Vec3 below = center.subtract(0, 2, 0);
			bed.add(canvas.piece(Blocks.ROOTED_DIRT.defaultBlockState(), below));
			bed.add(canvas.piece(Blocks.MOSS_CARPET.defaultBlockState(), below));
			draw(0, null, 0);
			burst(canvas.level, center, true);
			canvas.level.playSound(null, center.x, center.y, center.z, AuraSounds.ROOTED_DIRT_BREAK.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
			canvas.level.playSound(null, center.x, center.y, center.z, AuraSounds.MANGROVE_ROOTS_BREAK.value(), SoundSource.PLAYERS, 1.0f, 0.5f);
		}

		boolean armed() {
			return stateAge >= Math.max(1, AuraConfig.ENTANGLE_TRAP_ARM_TICKS);
		}

		/** The arms thrashing ({@code grow} 0..1 out of the ground), leaning in toward {@code lure} by {@code tense} 0..1. */
		void draw(double grow, LivingEntity lure, double tense) {
			for (int k = 0; k < roots.n; k++) {
				Vec3[] pts = roots.thrash(k, grow, age);
				if (lure != null && tense > 0) pts = Roots.blend(pts, roots.reach(k, AuraFx.chest(lure), age), 0.35 * tense);
				roots.draw(k, pts, Math.max(0.15, grow) * roots.thick(1), 1);
			}
			float d = (float) (radius * 1.6 * Math.max(0.05, grow));
			RootCanvas.box(bed.get(0), center.add(0, -0.97, 0), new Vector3f(d, 1f, d), (float) Math.toDegrees(roots.angle[0]), 0, 2);
			RootCanvas.box(bed.get(1), center.add(0, 0.005, 0), new Vector3f(d * 0.75f, 1f, d * 0.75f), (float) Math.toDegrees(roots.angle[1]) + 30, 0, 2);
		}

		/** The arms sink back into the ground - first pumping poison into the air if {@code poison}. */
		void sink(boolean poison) {
			state = State.SINKING;
			stateAge = 0;
			ServerLevel level = canvas.level;
			if (poison && AuraConfig.ENTANGLE_CLOUD_TICKS > 0 && AuraConfig.ENTANGLE_POISON_AMPLIFIER >= 0) {
				net.minecraft.world.entity.AreaEffectCloud cloud = new net.minecraft.world.entity.AreaEffectCloud(level, center.x, center.y + 0.1, center.z);
				cloud.setOwner(caster);
				float r = (float) Math.max(0.5, AuraConfig.ENTANGLE_CLOUD_RADIUS);
				cloud.setRadius(r);
				cloud.setDuration(AuraConfig.ENTANGLE_CLOUD_TICKS);
				cloud.setRadiusPerTick(-r / AuraConfig.ENTANGLE_CLOUD_TICKS * 0.5f);
				cloud.setWaitTime(0);
				cloud.setPotionContents(new net.minecraft.world.item.alchemy.PotionContents(java.util.Optional.empty(), java.util.Optional.empty(),
						List.of(new MobEffectInstance(MobEffects.POISON, Math.max(20, AuraConfig.ENTANGLE_POISON_TICKS), AuraConfig.ENTANGLE_POISON_AMPLIFIER))));
				level.addFreshEntity(cloud);
				Combos.zone(caster, Combos.Kind.POISON, cloud.position().add(0, 0.6, 0), r, AuraConfig.ENTANGLE_CLOUD_TICKS, cloud::discard, false);
				// the arms spit it out: a burst of spores from every tip
				for (int k = 0; k < roots.n; k++) {
					Vec3 tip = roots.thrash(k, 1, age)[ARM_SEGS];
					AuraFx.particles(level, ParticleTypes.SPORE_BLOSSOM_AIR, tip.x, tip.y, tip.z, 6, 0.2, 0.2, 0.2, 0.02);
					AuraFx.particles(level, AuraFx.dust(0x6E9B2E, 1.3f), tip.x, tip.y, tip.z, 4, 0.25, 0.25, 0.25, 0.0);
				}
				level.playSound(null, center.x, center.y, center.z, AuraSounds.PUFFER_FISH_BLOW_UP.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
				level.playSound(null, center.x, center.y, center.z, AuraSounds.SPORE_BLOSSOM_BREAK.value(), SoundSource.PLAYERS, 1.2f, 0.6f);
			}
			for (var e : canvas.pieces) {
				if (e instanceof Display.BlockDisplay d) RootCanvas.hide(d, center.subtract(0, 0.6, 0), 12);
			}
			level.playSound(null, center.x, center.y, center.z, AuraSounds.ROOTED_DIRT_PLACE.value(), SoundSource.PLAYERS, 0.8f, 0.8f);
		}

		/** True once it's gone (or handed over to a snare). */
		boolean tick() {
			age++;
			stateAge++;
			ServerLevel level = canvas.level;
			if (state == State.SINKING) {
				if (stateAge >= 14) {
					canvas.discardAll();
					return true;
				}
				return false;
			}
			int arm = Math.max(1, AuraConfig.ENTANGLE_TRAP_ARM_TICKS);
			if (!armed()) {
				// the warning: the arms tear up out of the ground, the earth churns and creaks
				double g = stateAge / (double) arm;
				draw(1 - (1 - g) * (1 - g), null, 0);
				if (stateAge % 3 == 0) {
					AuraFx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ROOTED_DIRT.defaultBlockState()), center.x, center.y + 0.1, center.z, 6, radius * 0.5, 0.05, radius * 0.5, 0.05);
					level.playSound(null, center.x, center.y, center.z, AuraSounds.MANGROVE_ROOTS_STEP.value(), SoundSource.PLAYERS, 0.9f, 0.6f);
				}
				return false;
			}
			if (!AuraFx.alive(caster) || !caster.isAlive()) {
				sink(false);
				return false;
			}
			if (stateAge >= arm + Math.max(1, AuraConfig.ENTANGLE_TRAP_TICKS)) {
				sink(true); // nobody came: poison into the air instead
				return false;
			}
			// whoever stays in it long enough (ENTANGLE_TRAP_STAY_TICKS in a row) is seized; stepping out resets it
			List<LivingEntity> inside = level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius, 1.5, radius),
					e -> VineHook.canHook(caster, e) && !isEntangled(e) && horizontalDist(e.position(), center) <= radius
							&& e.getY() - center.y > -0.6 && e.getY() - center.y < 1.0);
			dwell.keySet().retainAll(inside);
			int stay = Math.max(1, AuraConfig.ENTANGLE_TRAP_STAY_TICKS), most = 0;
			LivingEntity lure = null;
			for (LivingEntity e : inside) {
				int t = dwell.merge(e, 1, Integer::sum);
				if (t >= stay) {
					seize(this, e);
					return true; // the snare has the arms now
				}
				if (t > most) {
					most = t;
					lure = e;
				}
			}
			// idle: thrashing; someone lingering: the arms start reaching for them
			double tense = most / (double) stay;
			draw(1, lure, tense);
			if (age % 6 == 0) level.playSound(null, center.x, center.y + 1, center.z, AuraSounds.MANGROVE_ROOTS_STEP.value(), SoundSource.PLAYERS, 0.5f, 0.7f);
			if (most > 0 && most % 4 == 1) {
				level.playSound(null, center.x, center.y, center.z, AuraSounds.MANGROVE_ROOTS_HIT.value(), SoundSource.PLAYERS, 0.8f + (float) tense * 0.6f, 0.5f + (float) tense * 0.4f);
			}
			return false;
		}
	}

	private static double horizontalDist(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
