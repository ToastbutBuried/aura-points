package com.toast.aurapoints.rift;

import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.Props;
import com.toast.aurapoints.ability.Relics;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.item.RelicShardItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The run's flow through the Shattered Reliquary (5.47.0), on top of Rifts: the arrival shot, the difficulty vote in
 * the entry hall and the nave doors, the shard guidance (trail to the matching socket, ghost shards, beams), and the
 * entrance of the Relic Warden: lights out, the crossing floor cracks ring by ring and gives way, everyone falls into
 * the crypt arena, the Warden rises on the plinth.
 */
final class Reliquary {

	private Reliquary() {
	}

	static final String[] WING_NAMES = {"West", "North-West", "North-East", "East"};
	/** The wing colours (the build's runner concretes), for particles. */
	static final int[] WING_RGB = {0x3B6FD8, 0x7BD33A, 0xF2C43A, 0xD83B3B};
	static final ChatFormatting[] WING_FMT = {ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.YELLOW, ChatFormatting.RED};

	enum Phase {VOTE, TRIALS, FORGING, COLLAPSE, BOSS}

	/** Per run state of the flow. */
	static final class State {
		Phase phase = Phase.VOTE;
		long voteEnds, allVotedSince = -1, collapseAt;
		final Map<UUID, Integer> votes = new HashMap<>();
		final List<Display.TextDisplay> plateLabels = new ArrayList<>();
		final List<Display.ItemDisplay> ghosts = new ArrayList<>();
		final List<Debris> debris = new ArrayList<>();
		boolean doorSealed;
	}

	private record Debris(Display.BlockDisplay d, BlockState state, Vec3[] pos, Vec3[] vel, float[] spin) {
	}

	static Monument.Anchors A() {
		return Monument.anchors();
	}

	static Vec3 at(Rifts.Run run, BlockPos rel) {
		return Monument.world(run.hub(), rel);
	}

	static Vec3 at(Rifts.Run run, double x, double y, double z) {
		return Vec3.atBottomCenterOf(run.hub()).add(x, y, z);
	}

	// ---------------------------------------------------------------
	// Arrival
	// ---------------------------------------------------------------

	/** The first shot: from behind the gatehouse up the causeway to the broken spire, then onto the doors. */
	static void arrivalShot(Rifts.Run run, ServerPlayer p) {
		Vec3 spire = at(run, 0, 95, 0), door = at(run, 0, 6, 71);
		RiftCam.shot(run.aura.rgb).title("THE SHATTERED RELIQUARY", run.aura.displayName + " Rift - vote on the plates inside").skippable(true)
				.key(0, at(run, 0, 4, 154), spire)
				.key(50, at(run, 6, 22, 138), spire)
				.key(95, at(run, 0, 38, 122), spire)
				.cut(110, at(run, 0, 4, 108), at(run, 0, 3, 98))
				.key(150, at(run, 0, 7, 104), door)
				.send(List.of(p));
	}

	// ---------------------------------------------------------------
	// The vote
	// ---------------------------------------------------------------

	static void startVote(ServerLevel rift, Rifts.Run run) {
		State s = run.flow;
		s.phase = Phase.VOTE;
		s.voteEnds = rift.getGameTime() + Math.max(5, AuraConfig.RIFT_VOTE_SECONDS) * 20L;
		sealDoor(rift, run, true);
		Difficulty[] d = Difficulty.values();
		for (int i = 0; i < d.length && i < A().plates.size(); i++) {
			Vec3 at = at(run, A().plates.get(i)).add(0, 2.2, 0);
			Display.TextDisplay label = (Display.TextDisplay) com.toast.aurapoints.arena.Holograms.makePublic(rift, at, plateText(d[i], 0), 1.1f, 0x40000000);
			if (label != null) {
				label.addTag(Trials.DISPLAY_TAG);
				rift.addFreshEntity(label);
				s.plateLabels.add(label);
			}
		}
	}

	private static Component plateText(Difficulty d, int votes) {
		String what = switch (d) {
			case EASY -> "gentle trials, soft strikes";
			case MEDIUM -> "the trials as meant";
			case HARD -> "bigger puzzles, harsher strikes";
			case EXTREME -> "no help, twisted rules";
			case HARDCORE -> "one life each";
		};
		return Component.literal(d.label.toUpperCase(java.util.Locale.ROOT)).withStyle(d.color, ChatFormatting.BOLD)
				.append(Component.literal("\n" + what + "\n").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(votes + (votes == 1 ? " vote" : " votes")).withStyle(ChatFormatting.WHITE));
	}

	/** The plate a player stands on (0..4), or -1. */
	private static int plateOf(Rifts.Run run, ServerPlayer p) {
		for (int i = 0; i < A().plates.size(); i++) {
			Vec3 c = at(run, A().plates.get(i));
			if (Math.abs(p.getX() - c.x) <= 1.6 && Math.abs(p.getZ() - c.z) <= 1.6 && Math.abs(p.getY() - c.y) < 2.5) return i;
		}
		return -1;
	}

	/** Every tick while voting. */
	static void tickVote(ServerLevel rift, Rifts.Run run, List<ServerPlayer> up, long now) {
		State s = run.flow;
		boolean all = !up.isEmpty();
		for (ServerPlayer p : up) {
			int k = plateOf(run, p);
			if (k >= 0) s.votes.put(p.getUUID(), k);
			else all = false;
		}
		if (now % 10 == 0) {
			int[] count = counts(run);
			Difficulty[] d = Difficulty.values();
			for (int i = 0; i < s.plateLabels.size(); i++) com.toast.aurapoints.arena.Holograms.setTextPublic(s.plateLabels.get(i), plateText(d[i], count[i]));
			long left = Math.max(0, (s.voteEnds - now) / 20);
			for (ServerPlayer p : up) {
				p.displayClientMessage(Component.literal("Stand on a plate to vote - " + left + " s").withStyle(ChatFormatting.GOLD), true);
			}
			for (int i = 0; i < A().plates.size(); i++) {
				if (count[i] == 0) continue;
				Vec3 c = at(run, A().plates.get(i));
				rift.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 1, c.z, 3 * count[i], 0.4, 2.5, 0.4, 0.01);
			}
		}
		// everyone on a plate for three seconds: no need to wait out the clock
		if (all) {
			if (s.allVotedSince < 0) s.allVotedSince = now;
		} else s.allVotedSince = -1;
		if (now >= s.voteEnds || (s.allVotedSince >= 0 && now - s.allVotedSince >= 60)) finishVote(rift, run);
	}

	private static int[] counts(Rifts.Run run) {
		int[] c = new int[Difficulty.values().length];
		for (UUID id : run.players) {
			Integer v = run.flow.votes.get(id);
			if (v != null) c[v]++;
		}
		return c;
	}

	static void finishVote(ServerLevel rift, Rifts.Run run) {
		State s = run.flow;
		int[] c = counts(run);
		int best = Difficulty.MEDIUM.ordinal(), most = 0;
		// most votes wins, a tie goes to the harder one; nobody voted = Medium
		for (int i = 0; i < c.length; i++) if (c[i] > 0 && c[i] >= most) {
			most = c[i];
			best = i;
		}
		run.difficulty = Difficulty.values()[best];
		for (Display.TextDisplay l : s.plateLabels) l.discard();
		s.plateLabels.clear();
		s.phase = Phase.TRIALS;
		Rifts.buildTrials(rift, run);
		ghosts(rift, run);
		openDoor(rift, run);
		List<ServerPlayer> here = Rifts.online(rift.getServer(), run);
		Difficulty d = run.difficulty;
		for (ServerPlayer p : here) {
			p.sendSystemMessage(Component.literal("✦ Difficulty: ").withStyle(ChatFormatting.GRAY).append(Component.literal(d.label).withStyle(d.color, ChatFormatting.BOLD))
					.append(Component.literal(d.oneLife() ? " - one life each. " : " - ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(d.minutes + " minutes.").withStyle(ChatFormatting.GRAY)));
			Rifts.trialList(run, p);
		}
		// the doors grind open: the nave, the altar under the broken spire
		Vec3 door = at(run, 0, 5, 71), altar = at(run, 0, 3, 0);
		RiftCam.shot(run.aura.rgb).title(d.label.toUpperCase(java.util.Locale.ROOT), "bring the four shards to the altar").skippable(true)
				.key(0, at(run, 0, 4, 90), door)
				.key(40, at(run, 0, 5, 80), door)
				.key(90, at(run, 0, 9, 50), altar)
				.key(140, at(run, 0, 16, 14), altar)
				.key(175, at(run, 6, 40, 4), altar.add(0, -2, 0))
				.send(here);
	}

	/** The nave doors: a wall of blocks while the vote runs; they grind open row by row. */
	static void sealDoor(ServerLevel rift, Rifts.Run run, boolean sealed) {
		BlockPos o = run.hub();
		BlockState st = sealed ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
		for (int x = A().doorMin.getX(); x <= A().doorMax.getX(); x++) {
			for (int y = A().doorMin.getY(); y <= A().doorMax.getY(); y++) {
				BlockPos p = o.offset(x, y, A().doorMin.getZ());
				if (sealed && !rift.getBlockState(p).isAir()) continue;
				if (sealed) rift.setBlock(p, (x + y) % 3 == 0 ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState() : st, 2 | 16);
			}
		}
		run.flow.doorSealed = sealed;
	}

	private static void openDoor(ServerLevel rift, Rifts.Run run) {
		BlockPos o = run.hub();
		int y0 = A().doorMin.getY(), y1 = A().doorMax.getY();
		for (int row = 0; row <= y1 - y0; row++) {
			int y = y0 + row;
			com.toast.aurapoints.ability.AuraFx.later(rift.getServer(), 20 + row * 4, () -> {
				for (int x = A().doorMin.getX(); x <= A().doorMax.getX(); x++) {
					BlockPos p = o.offset(x, y, A().doorMin.getZ());
					BlockState was = rift.getBlockState(p);
					if (was.is(Blocks.POLISHED_BLACKSTONE_BRICKS) || was.is(Blocks.CHISELED_POLISHED_BLACKSTONE)) {
						rift.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
						rift.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, was), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.05);
					}
				}
				Vec3 c = Vec3.atCenterOf(o.offset(0, y, A().doorMin.getZ()));
				rift.playSound(null, c.x, c.y, c.z, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.4f, 0.5f);
				rift.playSound(null, c.x, c.y, c.z, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 1.2f, 0.6f);
			});
		}
		run.flow.doorSealed = false;
	}

	// ---------------------------------------------------------------
	// Shards and sockets
	// ---------------------------------------------------------------

	/** A dim ghost shard turning over each empty socket. */
	static void ghosts(ServerLevel rift, Rifts.Run run) {
		for (int i = 0; i < 4; i++) {
			Display.ItemDisplay g = com.toast.aurapoints.rift.Altar.itemDisplay(rift, Vec3.atBottomCenterOf(run.pedestal(i)).add(0, 3.0, 0),
					RelicShardItem.make(run.aura, run.id, i), 0.55f, 4);
			if (g != null) run.flow.ghosts.add(g);
		}
	}

	static void removeGhost(Rifts.Run run, int i) {
		if (i < run.flow.ghosts.size() && run.flow.ghosts.get(i) != null) {
			run.flow.ghosts.get(i).discard();
			run.flow.ghosts.set(i, null);
		}
	}

	/** Every 10 ticks: ghosts pulse, filled sockets beam, a shard you carry points the way to its socket. */
	static void tickGuidance(ServerLevel rift, Rifts.Run run, List<ServerPlayer> up, long now) {
		Altar altar = run.altar;
		for (int i = 0; i < 4; i++) {
			Vec3 top = Vec3.atBottomCenterOf(run.pedestal(i)).add(0, 3.0, 0);
			int rgb = WING_RGB[i];
			if (altar != null && altar.shards[i] != null) {
				for (int k = 0; k < 12; k++) rift.sendParticles(AuraFx.dust(rgb, 1.6f), top.x, top.y + k * 3, top.z, 1, 0.05, 1, 0.05, 0);
			} else if (now % 20 == 0) {
				rift.sendParticles(AuraFx.dust(rgb, 1.0f), top.x, top.y, top.z, 6, 0.25, 0.25, 0.25, 0);
			}
			Display.ItemDisplay g = i < run.flow.ghosts.size() ? run.flow.ghosts.get(i) : null;
			if (g != null) g.moveTo(top.x, top.y + Math.sin(now * 0.1 + i) * 0.1, top.z, (now * 6 + i * 90) % 360, 0);
		}
		for (ServerPlayer p : up) {
			for (int i = 0; i < 4; i++) {
				if (!Rifts.holdsShard(p, run, i) || run.placedTrials.contains(i)) continue;
				Vec3 to = Vec3.atBottomCenterOf(run.pedestal(i)).add(0, 2.5, 0), from = p.position().add(0, 1.0, 0);
				Vec3 dir = to.subtract(from);
				double len = dir.length();
				dir = dir.normalize();
				for (double d = 1.2; d < Math.min(len, 9); d += 0.7) {
					Vec3 q = from.add(dir.scale(d));
					rift.sendParticles(p, AuraFx.dust(WING_RGB[i], 0.9f), true, q.x, q.y, q.z, 1, 0, 0, 0, 0);
				}
				if (now % 40 == 0) {
					p.displayClientMessage(Component.literal("Set the shard in the ").withStyle(ChatFormatting.GRAY)
							.append(Component.literal(WING_NAMES[i]).withStyle(WING_FMT[i], ChatFormatting.BOLD))
							.append(Component.literal(" socket at the altar - follow the " + colourName(i) + " trail").withStyle(ChatFormatting.GRAY)), true);
				}
			}
		}
	}

	static String colourName(int i) {
		return switch (i) {
			case 0 -> "blue";
			case 1 -> "green";
			case 2 -> "yellow";
			default -> "red";
		};
	}

	// ---------------------------------------------------------------
	// The Warden's entrance: the floor gives way
	// ---------------------------------------------------------------

	static final int CRACK_FROM = 10, CRACK_TICKS = 60, FALL_AT = 74, RISE_AT = 130, ENTRANCE_END = 175;

	static void startCollapse(ServerLevel rift, Rifts.Run run) {
		State s = run.flow;
		s.phase = Phase.COLLAPSE;
		s.collapseAt = rift.getGameTime();
		List<ServerPlayer> here = Rifts.online(rift.getServer(), run);
		Vec3 altar = at(run, 0, 3, 0), plinth = cryptCenter(run).add(0, 2, 0);
		for (ServerPlayer p : here) p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0, false, false, false));
		rift.playSound(null, altar.x, altar.y, altar.z, SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 3f, 0.5f);
		rift.playSound(null, altar.x, altar.y, altar.z, SoundEvents.AMBIENT_CAVE.value(), SoundSource.HOSTILE, 3f, 0.6f);
		RiftCam.shot(run.aura.rgb)
				.key(0, at(run, 0, 46, 6), altar)
				.key(FALL_AT - 6, at(run, 0, 30, 3), altar)
				.cut(FALL_AT, plinth.add(14, 1, 14), at(run, 0, -4, 0))
				.key(RISE_AT - 10, plinth.add(10, 2, 10), at(run, 0, -10, 0))
				.cut(RISE_AT, plinth.add(0, 2, 15), plinth.add(0, 2.5, 0))
				.key(ENTRANCE_END - 10, plinth.add(0, 1, 10), plinth.add(0, 3.5, 0))
				.send(here);
	}

	static Vec3 cryptCenter(Rifts.Run run) {
		return at(run, 0, A().cryptFloor + 1, 0);
	}

	/** Every tick of the entrance; true on the tick the Warden must rise. */
	static boolean tickCollapse(ServerLevel rift, Rifts.Run run, List<ServerPlayer> here, long now) {
		State s = run.flow;
		int t = (int) (now - s.collapseAt);
		BlockPos o = run.hub();
		int R = A().collapseRadius;
		// nobody takes fall damage during the entrance
		for (ServerPlayer p : here) p.fallDistance = 0;
		if (t >= CRACK_FROM && t < CRACK_FROM + CRACK_TICKS && (t - CRACK_FROM) % 3 == 0) {
			// the cracks run out from the altar, ring by ring
			double r = (t - CRACK_FROM) / (double) CRACK_TICKS * R;
			for (int dx = -R; dx <= R; dx++) {
				for (int dz = -R; dz <= R; dz++) {
					double d = Math.sqrt(dx * dx + dz * dz);
					if (d > r || d <= r - 1.6) continue;
					BlockPos p = o.offset(dx, 0, dz);
					if (!rift.getBlockState(p).isAir() && rift.random.nextInt(3) > 0) {
						rift.setBlock(p, Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState(), 2 | 16);
						if (rift.random.nextInt(4) == 0)
							rift.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX() + 0.5, p.getY() + 1.1, p.getZ() + 0.5, 1, 0.2, 0.05, 0.2, 0.01);
					}
				}
			}
			Vec3 c = at(run, 0, 1, 0);
			rift.playSound(null, c.x, c.y, c.z, SoundEvents.DEEPSLATE_BREAK, SoundSource.BLOCKS, 2f, 0.5f);
			if (t % 9 == 0) rift.playSound(null, c.x, c.y, c.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 3f, 0.6f);
		}
		if (t == FALL_AT) {
			// the floor gives way: everything in the circle down to the crypt's ceiling goes, as tumbling stones
			for (int dx = -R; dx <= R; dx++) {
				for (int dz = -R; dz <= R; dz++) {
					if (dx * dx + dz * dz > R * R) continue;
					for (int dy = -4; dy <= 4; dy++) {
						BlockPos p = o.offset(dx, dy, dz);
						BlockState was = rift.getBlockState(p);
						if (was.isAir()) continue;
						rift.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
						if (rift.random.nextInt(14) == 0 && s.debris.size() < 70) debris(rift, s, Vec3.atCenterOf(p), was);
					}
				}
			}
			Vec3 c = at(run, 0, 0, 0);
			rift.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4f, 0.5f);
			rift.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 3, R * 0.4, 0.5, R * 0.4, 0);
			// whoever isn't over the hole is pulled over it: everyone falls together
			for (ServerPlayer p : here) {
				Vec3 rel = p.position().subtract(c);
				if (rel.horizontalDistance() > R - 2 || rel.y < -6 || rel.y > 8) {
					double a = rift.random.nextDouble() * Math.PI * 2, d = 3 + rift.random.nextDouble() * (R - 6);
					p.teleportTo(rift, c.x + Math.cos(a) * d, c.y + 2, c.z + Math.sin(a) * d, p.getYRot(), 30f);
				}
				p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 30, 0, false, false, false));
			}
		}
		tickDebris(rift, s);
		return t == RISE_AT;
	}

	private static void debris(ServerLevel rift, State s, Vec3 at, BlockState state) {
		float size = 0.6f + rift.random.nextFloat() * 0.5f;
		Display.BlockDisplay d = Props.spawn(rift, at, state, new Transformation(new Vector3f(-size / 2), null, new Vector3f(size), null), 120, 1);
		s.debris.add(new Debris(d, state, new Vec3[]{at}, new Vec3[]{new Vec3((rift.random.nextDouble() - 0.5) * 0.2, 0.15, (rift.random.nextDouble() - 0.5) * 0.2)},
				new float[]{(rift.random.nextFloat() - 0.5f) * 0.4f, 0}));
	}

	private static void tickDebris(ServerLevel rift, State s) {
		for (var it = s.debris.iterator(); it.hasNext(); ) {
			Debris d = it.next();
			if (d.d().isRemoved()) {
				it.remove();
				continue;
			}
			d.vel()[0] = d.vel()[0].add(0, -0.06, 0);
			Vec3 next = d.pos()[0].add(d.vel()[0]);
			if (!rift.getBlockState(net.minecraft.core.BlockPos.containing(next)).isAir()) {
				rift.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, d.state()), next.x, next.y + 0.5, next.z, 8, 0.3, 0.2, 0.3, 0.1);
				Props.remove(d.d());
				it.remove();
				continue;
			}
			d.pos()[0] = next;
			d.spin()[1] += d.spin()[0];
			Props.move(d.d(), next);
			float sz = 0.8f;
			Props.set(d.d(), new Transformation(new Vector3f(-sz / 2), new Quaternionf().rotationXYZ(d.spin()[1], d.spin()[1] * 0.7f, 0), new Vector3f(sz), null), 1);
		}
	}

	static void clearDebris(State s) {
		for (Debris d : s.debris) Props.remove(d.d());
		s.debris.clear();
	}

	static void cleanup(Rifts.Run run) {
		for (Display.TextDisplay l : run.flow.plateLabels) l.discard();
		run.flow.plateLabels.clear();
		for (Display.ItemDisplay g : run.flow.ghosts) if (g != null) g.discard();
		run.flow.ghosts.clear();
		clearDebris(run.flow);
	}

	/** A random spot on the crypt floor (adds, knocked out players coming back). */
	static Vec3 cryptSpot(Rifts.Run run, net.minecraft.util.RandomSource r) {
		List<BlockPos> spots = A().cryptSpots;
		return at(run, spots.get(r.nextInt(spots.size())));
	}

	static ItemStack none() {
		return ItemStack.EMPTY;
	}
}
