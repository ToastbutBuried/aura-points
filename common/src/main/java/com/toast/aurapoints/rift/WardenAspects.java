package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraFx;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Relic Warden's four Aspects (5.51.0), one per wing colour. The fight's own phases (combos, barrages, pylons,
 * Overdrive, the last stand) run as before; on top of them the crypt itself turns on the players as the Warden's
 * health falls through 75 / 50 / 25 %:
 * <ul>
 * <li>BLUE, the Chime (100-75%): rings of sound roll out from the Warden - jump them (or stand above the floor).</li>
 * <li>GREEN, the Garden (75-50%): warned patches of roots erupt under the players and around the Warden - keep moving.</li>
 * <li>YELLOW, the Prism (50-25%): the crypt's pillars charge beams at the players - break the line behind a pillar.</li>
 * <li>RED, the Wrath (25-0%): meteors fall on the players and round the Warden.</li>
 * </ul>
 * A change of Aspect never stops the fight (5.55): a line in chat, a motif, the Warden and the crypt's pillars take the
 * colour. Hazards are telegraphed, hurt through
 * {@link RelicWarden#hit} (so difficulty scales them) and come faster on higher difficulties. Off with
 * RIFT_WARDEN_ASPECTS.
 */
final class WardenAspects {

	static final int BLUE = 0, GREEN = 1, YELLOW = 2, RED = 3;
	static final String[] NAME = {"THE CHIME", "THE GARDEN", "THE PRISM", "THE WRATH"};
	static final String[] HINT = {"jump the rings as they roll past", "keep moving - the roots rise", "break the line: hide behind a pillar", "the sky falls - run"};
	/** Ticks between hazards at Medium, per Aspect. */
	private static final int[] EVERY = {150, 110, 150, 85};
	private static final double[] TIER_SCALE = {1.35, 1.0, 0.85, 0.7, 0.7};

	private final RelicWarden w;
	private int aspect = -1;
	private long next;
	/** Dev tests turn the layer off so the phase checks stay about the phases. */
	boolean off;

	WardenAspects(RelicWarden w) {
		this.w = w;
	}

	int current() {
		return aspect;
	}

	static int aspectFor(float hpShare) {
		return hpShare > 0.75f ? BLUE : hpShare > 0.5f ? GREEN : hpShare > 0.25f ? YELLOW : RED;
	}

	/** Every server tick of an awake Warden; {@code paused} = the pylons or the kneel hold the stage. */
	void tick(ServerLevel level, boolean paused) {
		if (off || w.run == null || !w.isAlive()) return;
		long now = level.getGameTime();
		int want = aspectFor(w.getHealth() / w.getMaxHealth());
		if (want > aspect) {
			transition(level, want, now);
			return;
		}
		if (now % 8 == 0) for (Vec3 e : emitters()) level.sendParticles(AuraFx.dust(Reliquary.WING_RGB[aspect], 1.8f), e.x, e.y - 1, e.z, 3, 0.3, 2.2, 0.3, 0);
		if (paused || now < next) return;
		int tier = Math.max(0, Math.min(4, w.run.difficulty.tier()));
		next = now + Math.round(EVERY[aspect] * TIER_SCALE[tier]);
		switch (aspect) {
			case BLUE -> chime(level);
			case GREEN -> garden(level);
			case YELLOW -> prism(level);
			default -> wrath(level);
		}
	}

	/** The run's players in the crypt (the run's own list: dev tests' fake players are in no entity list). */
	List<ServerPlayer> players(ServerLevel level) {
		List<ServerPlayer> out = new ArrayList<>();
		if (w.run == null) return out;
		Vec3 c = Reliquary.cryptCenter(w.run);
		for (ServerPlayer p : Rifts.online(level.getServer(), w.run)) {
			if (p.isAlive() && !p.isSpectator() && p.level() == level && Math.hypot(p.getX() - c.x, p.getZ() - c.z) < 48 && Math.abs(p.getY() - c.y) < 30) out.add(p);
		}
		return out;
	}

	// ---------------------------------------------------------------
	// The change of Aspect
	// ---------------------------------------------------------------

	private void transition(ServerLevel level, int to, long now) {
		aspect = to;
		next = now + 70;
		int rgb = Reliquary.WING_RGB[to];
		w.showAspect(to);
		if (w.run.bar != null) w.run.bar.setColor(new net.minecraft.world.BossEvent.BossBarColor[]{net.minecraft.world.BossEvent.BossBarColor.BLUE, net.minecraft.world.BossEvent.BossBarColor.GREEN,
				net.minecraft.world.BossEvent.BossBarColor.YELLOW, net.minecraft.world.BossEvent.BossBarColor.RED}[to]);
		w.say(level, NAME[to].charAt(0) + NAME[to].substring(1).toLowerCase(java.util.Locale.ROOT) + " - " + HINT[to], rgb);
		for (Vec3 e : emitters()) AuraFx.line(level, w.position().add(0, 2.9, 0), e, AuraFx.dust(rgb, 1.6f), 30);
		for (int k = 1; k <= 5; k++) {
			double r = k * 3.0;
			AuraFx.later(level.getServer(), k * 2, () -> AuraFx.ring(level, w.getX(), w.getY() + 0.2, w.getZ(), r, AuraFx.dust(rgb, 2.2f), 24 + (int) r * 3));
		}
		level.playSound(null, w.getX(), w.getY() + 2, w.getZ(), AuraSounds.WARDEN_ROAR.value(), SoundSource.HOSTILE, 2.5f, 0.8f);
		AuraFx.ring(level, w.getX(), w.getY() + 0.2, w.getZ(), 7, AuraFx.dust(rgb, 2.2f), 48);
		motif(to);
	}

	/** A short theme per Aspect, played at the players' ears (notes are semitones above the instrument's base note). */
	private void motif(int to) {
		var server = w.level().getServer();
		int[][] seq;
		SoundEvent inst;
		switch (to) {
			case BLUE -> {
				inst = AuraSounds.NOTE_BLOCK_BELL.value();
				seq = new int[][]{{0, 3}, {8, 6}, {16, 10}, {24, 15}, {40, 10}, {48, 15}};
			}
			case GREEN -> {
				inst = AuraSounds.NOTE_BLOCK_HARP.value();
				seq = new int[][]{{0, 8}, {6, 12}, {12, 15}, {18, 12}, {24, 8}, {36, 15}, {44, 20}};
			}
			case YELLOW -> {
				inst = AuraSounds.NOTE_BLOCK_PLING.value();
				seq = new int[][]{{0, 12}, {5, 16}, {10, 19}, {15, 24}, {30, 19}, {36, 24}, {44, 28}};
			}
			default -> {
				inst = AuraSounds.NOTE_BLOCK_BASS.value();
				seq = new int[][]{{0, 0}, {10, 0}, {20, 3}, {30, 3}, {40, 5}, {50, 7}};
			}
		}
		for (int[] n : seq) {
			AuraFx.later(server, n[0], () -> {
				if (w.run == null) return;
				var here = Rifts.online(server, w.run);
				Reliquary.note(here, inst, n[1], 1.3f);
				if (to == RED) Reliquary.note(here, AuraSounds.NOTE_BLOCK_DIDGERIDOO.value(), n[1], 0.9f);
				if (to == BLUE && n[0] == 0) Reliquary.note(here, AuraSounds.NOTE_BLOCK_BASS.value(), 3, 1.4f);
			});
		}
	}

	// ---------------------------------------------------------------
	// BLUE: the Chime
	// ---------------------------------------------------------------

	/** A ring at {@code radius} hits what stands on the floor at {@code dist}: the band is 1.5 wide, a jump (or a ledge) clears it. */
	static boolean ringHits(double dist, double radius, double heightAboveFloor) {
		return Math.abs(dist - radius) <= 1.5 && heightAboveFloor < 0.7;
	}

	private void chime(ServerLevel level) {
		Vec3 c = w.position();
		int rings = w.run.difficulty.tier() >= 2 ? 2 : 1;
		var server = level.getServer();
		level.playSound(null, c.x, c.y + 1, c.z, AuraSounds.BELL_BLOCK.value(), SoundSource.HOSTILE, 3f, 0.6f);
		// the tell: a blue ring pulls in on the Warden's feet
		for (int k = 0; k < 28; k += 4) {
			double r = 4.5 * (1 - k / 30.0);
			AuraFx.later(server, k, () -> {
				if (w.isAlive()) AuraFx.ring(level, c.x, c.y + 0.15, c.z, Math.max(1, r), AuraFx.dust(Reliquary.WING_RGB[BLUE], 1.8f), 24);
			});
		}
		for (int ring = 0; ring < rings; ring++) {
			int start = 30 + ring * 18;
			Set<UUID> hit = new HashSet<>();
			for (int step = 0; step < 25; step++) {
				double r = 2.0 + step * 1.4;
				AuraFx.later(server, start + step * 2, () -> {
					if (!w.isAlive()) return;
					AuraFx.ring(level, c.x, c.y + 0.15, c.z, r, AuraFx.dust(Reliquary.WING_RGB[BLUE], 1.9f), Math.max(24, (int) (r * 6)));
					if ((int) (r * 10) % 3 == 0) level.playSound(null, c.x, c.y + 1, c.z, AuraSounds.NOTE_BLOCK_CHIME.value(), SoundSource.HOSTILE, 1.4f, (float) (0.6 + r / 30));
					for (ServerPlayer p : players(level)) {
						if (hit.contains(p.getUUID())) continue;
						double d = Math.hypot(p.getX() - c.x, p.getZ() - c.z);
						if (!ringHits(d, r, p.getY() - c.y)) continue;
						hit.add(p.getUUID());
						w.hit(level, p, 9f);
						Vec3 out = new Vec3(p.getX() - c.x, 0, p.getZ() - c.z).normalize();
						p.setDeltaMovement(out.x * 0.9, 0.45, out.z * 0.9);
						p.hurtMarked = true;
					}
				});
			}
		}
	}

	// ---------------------------------------------------------------
	// GREEN: the Garden
	// ---------------------------------------------------------------

	private void garden(ServerLevel level) {
		List<Vec3> spots = new ArrayList<>();
		for (ServerPlayer p : players(level)) spots.add(p.position());
		Vec3 c = w.position();
		for (int k = 0; k < 3; k++) {
			double a = level.random.nextDouble() * Math.PI * 2, d = 4 + level.random.nextDouble() * 12;
			spots.add(new Vec3(c.x + Math.cos(a) * d, c.y, c.z + Math.sin(a) * d));
		}
		for (Vec3 at : spots) root(level, at);
	}

	private void root(ServerLevel level, Vec3 at) {
		var server = level.getServer();
		for (int k = 0; k < 28; k += 4) {
			double r = 2.6 * (0.4 + 0.6 * (28 - k) / 28.0);
			AuraFx.later(server, k, () -> {
				if (w.isAlive()) AuraFx.ring(level, at.x, at.y + 0.15, at.z, r, AuraFx.dust(Reliquary.WING_RGB[GREEN], 1.6f), 18);
			});
		}
		AuraFx.later(server, 30, () -> {
			if (w.isAlive()) erupt(level, at);
		});
	}

	/** The roots break out of the floor: hurts and slows what stands in the patch. */
	void erupt(ServerLevel level, Vec3 at) {
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()), at.x, at.y + 0.4, at.z, 40, 1.2, 0.5, 1.2, 0.2);
		level.sendParticles(ParticleTypes.COMPOSTER, at.x, at.y + 0.8, at.z, 20, 1.0, 0.8, 1.0, 0.05);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.GRASS_BREAK.value(), SoundSource.HOSTILE, 2f, 0.6f);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.WARDEN_DIG.value(), SoundSource.HOSTILE, 0.8f, 1.4f);
		for (ServerPlayer p : players(level)) {
			if (p.distanceToSqr(at.x, p.getY(), at.z) > 2.6 * 2.6 || Math.abs(p.getY() - at.y) > 2.5) continue;
			w.hit(level, p, 7f);
			p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
			p.setDeltaMovement(p.getDeltaMovement().x, 0.5, p.getDeltaMovement().z);
			p.hurtMarked = true;
		}
	}

	// ---------------------------------------------------------------
	// YELLOW: the Prism
	// ---------------------------------------------------------------

	/** True when something solid stands between {@code from} and {@code to} (a pillar breaks the line). */
	static boolean beamBlocked(ServerLevel level, Vec3 from, Vec3 to, net.minecraft.world.entity.Entity who) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, who)).getType() != HitResult.Type.MISS;
	}

	/** Where a crypt pillar's beam starts: its side towards the middle of the arena, a few blocks above the floor. */
	List<Vec3> emitters() {
		var an = Monument.anchors();
		BlockPos hub = w.run.hub();
		Vec3 cc = Reliquary.cryptCenter(w.run);
		List<Vec3> out = new ArrayList<>();
		for (int[] pl : an.cryptPillars) {
			if (pl[2] != 0) continue; // a broken pillar is a stub
			Vec3 pc = new Vec3(hub.getX() + pl[0] + 0.5, cc.y + 6, hub.getZ() + pl[1] + 0.5);
			Vec3 in = new Vec3(cc.x - pc.x, 0, cc.z - pc.z).normalize();
			out.add(pc.add(in.scale(3.0)));
		}
		return out;
	}

	private void prism(ServerLevel level) {
		List<ServerPlayer> here = players(level);
		List<Vec3> heads = emitters();
		if (here.isEmpty() || heads.isEmpty()) return;
		int beams = Math.min(heads.size(), Math.min(3, here.size()) + (w.run.difficulty.tier() >= 3 ? 1 : 0));
		var server = level.getServer();
		int rgb = Reliquary.WING_RGB[YELLOW];
		for (int b = 0; b < beams; b++) {
			ServerPlayer target = here.get(b % here.size());
			Vec3 from = heads.get(level.random.nextInt(heads.size()));
			Vec3[] aim = {target.position().add(0, 1.0, 0)};
			level.playSound(null, from.x, from.y, from.z, AuraSounds.BEACON_POWER_SELECT.value(), SoundSource.HOSTILE, 2f, 1.8f);
			for (int k = 0; k <= 38; k += 2) {
				int tk = k;
				AuraFx.later(server, k, () -> {
					if (!w.isAlive()) return;
					if (tk <= 26 && target.isAlive()) aim[0] = target.position().add(0, 1.0, 0); // tracking, then locked
					boolean locked = tk > 26;
					Vec3 d = aim[0].subtract(from);
					int n = Math.max(6, (int) (d.length() * 1.5));
					for (int i = 0; i <= n; i++) {
						Vec3 q = from.add(d.scale(i / (double) n));
						level.sendParticles(locked ? ParticleTypes.END_ROD : AuraFx.dust(rgb, 1.0f), q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0);
					}
					if (tk == 38) fire(level, from, aim[0]);
				});
			}
		}
	}

	/** The beam fires from {@code from} through {@code to}: whoever it reaches without a pillar in the way is hurt. */
	void fire(ServerLevel level, Vec3 from, Vec3 to) {
		Vec3 dir = to.subtract(from).normalize();
		Vec3 end = from.add(dir.scale(from.distanceTo(to) + 5));
		Vec3 stop = end;
		var hit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, w));
		if (hit.getType() != HitResult.Type.MISS) stop = hit.getLocation();
		double len = from.distanceTo(stop);
		for (int i = 0; i <= (int) (len * 2); i++) {
			Vec3 q = from.add(dir.scale(i / 2.0));
			level.sendParticles(ParticleTypes.END_ROD, q.x, q.y, q.z, 1, 0.05, 0.05, 0.05, 0.01);
		}
		level.sendParticles(ParticleTypes.FLASH, stop.x, stop.y, stop.z, 1, 0, 0, 0, 0);
		level.playSound(null, from.x, from.y, from.z, AuraSounds.LIGHTNING_BOLT_IMPACT.value(), SoundSource.HOSTILE, 2f, 1.6f);
		for (ServerPlayer p : players(level)) {
			Vec3 chest = p.position().add(0, 1.0, 0);
			double t = Math.max(0, Math.min(len, chest.subtract(from).dot(dir)));
			if (chest.distanceTo(from.add(dir.scale(t))) > 1.4) continue; // off the line
			if (beamBlocked(level, from, chest, w)) continue; // a pillar in between
			w.hit(level, p, 11f);
			p.setDeltaMovement(dir.x * 0.6, 0.3, dir.z * 0.6);
			p.hurtMarked = true;
		}
	}

	// ---------------------------------------------------------------
	// RED: the Wrath
	// ---------------------------------------------------------------

	private void wrath(ServerLevel level) {
		List<Vec3> spots = new ArrayList<>();
		for (ServerPlayer p : players(level)) spots.add(p.position());
		Vec3 c = w.position();
		for (int k = 0; k < 3; k++) {
			double a = level.random.nextDouble() * Math.PI * 2, d = 3 + level.random.nextDouble() * 15;
			spots.add(new Vec3(c.x + Math.cos(a) * d, c.y, c.z + Math.sin(a) * d));
		}
		for (Vec3 at : spots) meteor(level, at);
	}

	private void meteor(ServerLevel level, Vec3 at) {
		var server = level.getServer();
		int rgb = Reliquary.WING_RGB[RED];
		for (int k = 0; k <= 36; k += 3) {
			int tk = k;
			AuraFx.later(server, k, () -> {
				if (!w.isAlive()) return;
				AuraFx.ring(level, at.x, at.y + 0.15, at.z, 3.2 * (0.4 + 0.6 * (40 - tk) / 40.0), AuraFx.dust(rgb, 1.6f), 20);
				double y = at.y + 22 * (1 - tk / 40.0);
				level.sendParticles(ParticleTypes.FLAME, at.x, y, at.z, 6, 0.3, 0.3, 0.3, 0.02);
				level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, y + 1, at.z, 2, 0.2, 0.4, 0.2, 0.01);
			});
		}
		AuraFx.later(server, 40, () -> {
			if (w.isAlive()) impact(level, at);
		});
	}

	/** A meteor lands: a blast of radius 3. */
	void impact(ServerLevel level, Vec3 at) {
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 0.8, 0.2, 0.8, 0);
		level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.5, at.z, 40, 1.5, 0.5, 1.5, 0.1);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.5f, 0.8f);
		for (ServerPlayer p : players(level)) {
			if (p.distanceToSqr(at.x, p.getY(), at.z) > 3.2 * 3.2 || Math.abs(p.getY() - at.y) > 3) continue;
			w.hit(level, p, 10f);
			Vec3 out = new Vec3(p.getX() - at.x, 0, p.getZ() - at.z).normalize();
			p.setDeltaMovement(out.x * 0.7, 0.55, out.z * 0.7);
			p.hurtMarked = true;
		}
	}

	// ---------------------------------------------------------------
	// DEV ONLY
	// ---------------------------------------------------------------

	/** DEV ONLY (wardentest): the Aspects' rules checked one by one, logged with "(must be X)". */
	void devCheck(ServerLevel level, ServerPlayer p, java.util.function.Consumer<String> log) {
		log.accept("[aspects] thresholds: " + aspectFor(0.9f) + " " + aspectFor(0.6f) + " " + aspectFor(0.4f) + " " + aspectFor(0.1f) + " (must be 0 1 2 3)");
		log.accept("[aspects] ring: on the floor in the band " + ringHits(5, 5.2, 0) + " (must be true), jumping " + ringHits(5, 5.2, 1.0) + " (must be false), outside the band " + ringHits(5, 9, 0) + " (must be false)");
		Vec3 at = p.position();
		p.invulnerableTime = 0;
		int h = w.devHits;
		erupt(level, at);
		log.accept("[aspects] roots: hurt " + (w.devHits > h) + " (must be true), slowing roots called (checked in the hit above)");
		p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
		p.invulnerableTime = 0;
		h = w.devHits;
		erupt(level, at.add(20, 0, 0));
		log.accept("[aspects] roots far away: hurt " + (w.devHits > h) + " (must be false)");
		p.invulnerableTime = 0;
		h = w.devHits;
		impact(level, at);
		log.accept("[aspects] meteor: hurt " + (w.devHits > h) + " (must be true)");
		// the prism: a clear line hurts, a pillar in the way does not
		Vec3 from = at.add(8, 1.0, 0);
		p.invulnerableTime = 0;
		h = w.devHits;
		fire(level, from, at.add(0, 1.0, 0));
		log.accept("[aspects] beam in the open: hurt " + (w.devHits > h) + " (must be true)");
		BlockPos mid = BlockPos.containing(at.x + 4, at.y, at.z);
		var was1 = level.getBlockState(mid);
		var was2 = level.getBlockState(mid.above());
		level.setBlock(mid, Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(mid.above(), Blocks.STONE.defaultBlockState(), 3);
		p.invulnerableTime = 0;
		h = w.devHits;
		fire(level, from, at.add(0, 1.0, 0));
		boolean blocked = beamBlocked(level, from, at.add(0, 1.0, 0), w);
		log.accept("[aspects] beam behind a pillar: hurt " + (w.devHits > h) + " (must be false), line blocked " + blocked + " (must be true)");
		level.setBlock(mid, was1, 3);
		level.setBlock(mid.above(), was2, 3);
		// a change of Aspect doesn't stop the fight
		boolean keepOff = off;
		off = false;
		aspect = -1;
		boolean was = w.devStaggered();
		transition(level, GREEN, level.getGameTime());
		log.accept("[aspects] change: aspect " + aspect + " (must be 1), the Warden stopped " + (w.devStaggered() && !was) + " (must be false), shown " + (w.aspectRgb() == Reliquary.WING_RGB[GREEN]) + " (must be true)");
		off = keepOff;
	}
}
