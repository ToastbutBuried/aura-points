package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.Props;
import com.toast.aurapoints.item.RelicShardItem;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SHATTERED (5.55): the Warden's four shards tear out of it, one after the other in a random order. It fights on
 * meanwhile, hard to hurt; every shard broken lowers how far it can fall. Each shard breaks only by its own rule:
 * <ul>
 * <li>BLUE: rings close in on it - hit it as one meets it, three times; a wrong beat throws you back and costs one.</li>
 * <li>GREEN: caged in roots - lure the Warden's own blows or its charge into it, twice.</li>
 * <li>YELLOW: a pillar's beam follows a player - fire it through the shard, twice.</li>
 * <li>RED: embers fall near it - hit one so it flies into the shard, twice.</li>
 * </ul>
 * After STUCK ticks on one shard it cracks and plain hits finish it, so nobody is ever stuck.
 */
final class WardenShards {

	static final int BLUE = 0, GREEN = 1, YELLOW = 2, RED = 3, STUCK = 1500, CYCLE = 36, BEAM_EVERY = 100;
	private static final int[] NEED = {3, 2, 2, 2};
	private static final String[] HINT = {"The blue shard: hit it just as the ring closes on it", "The green shard: lure the Warden's blows into it",
			"The yellow shard: keep it between you and the glowing pillar", "The red shard: hit an ember into it"};

	private final RelicWarden w;
	private final List<Integer> order = new ArrayList<>(List.of(BLUE, GREEN, YELLOW, RED));
	int broken, hits, crackHits;
	private boolean on;
	long since, nextAct;
	private Vec3 at = Vec3.ZERO;
	private Display.ItemDisplay shard;
	private Vec3 pillar;
	private ServerPlayer aimed;
	private long beamAt = -1;
	private Vec3 lock;
	private Display.BlockDisplay ember;
	private Vec3 emberPos, emberVel;
	private long emberFall = -1, emberUntil;
	private final Map<UUID, Integer> swing = new HashMap<>();
	private final Map<UUID, Long> rest = new HashMap<>();

	WardenShards(RelicWarden w) {
		this.w = w;
	}

	boolean active() {
		return on;
	}

	int left() {
		return on ? 4 - broken : 0;
	}

	int kind() {
		return order.get(Math.min(3, broken));
	}

	Vec3 spot() {
		return at;
	}

	void start(ServerLevel level) {
		on = true;
		broken = 0;
		Collections.shuffle(order, new java.util.Random(level.random.nextLong()));
		next(level);
	}

	private void next(ServerLevel level) {
		drop();
		hits = crackHits = 0;
		since = level.getGameTime();
		nextAct = since + 40;
		double a = (broken + level.random.nextDouble() * 0.5) * Math.PI / 2 + Math.PI / 4;
		at = Reliquary.cryptCenter(w.run).add(Math.cos(a) * 11, 1.5, Math.sin(a) * 11);
		shard = Altar.itemDisplay(level, at, RelicShardItem.make(w.run.aura, w.run.id, kind()), 2.2f, 15);
		pillar = null;
		if (kind() == YELLOW) {
			for (Vec3 e : w.aspects.emitters()) if (pillar == null || e.distanceToSqr(at) < pillar.distanceToSqr(at)) pillar = e;
		}
		int rgb = Reliquary.WING_RGB[kind()];
		AuraFx.line(level, w.position().add(0, 2.9, 0), at, AuraFx.dust(rgb, 1.8f), 24);
		level.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.AMETHYST_CLUSTER_BREAK.value(), SoundSource.HOSTILE, 2.5f, 0.6f);
		w.say(level, HINT[kind()], rgb);
	}

	void tick(ServerLevel level, List<ServerPlayer> players) {
		if (!on) return;
		long now = level.getGameTime();
		int rgb = Reliquary.WING_RGB[kind()];
		if (shard != null) shard.moveTo(at.x, at.y + Math.sin(now * 0.08) * 0.2, at.z, (now * 5) % 360, 0);
		boolean cracked = now - since > STUCK;
		if (now % 6 == 0) {
			AuraFx.ring(level, at.x, at.y - 1.3, at.z, 2.2, AuraFx.dust(rgb, 1.3f), 20);
			if (cracked) level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, 0.4, 0.5, 0.4, 0.1);
		}
		if (now % 60 == 0) for (ServerPlayer p : players) p.displayClientMessage(Component.literal(cracked ? "The shard is cracking - hit it!" : HINT[kind()] + "  " + hits + "/" + NEED[kind()]).withStyle(s -> s.withColor(rgb)), true);
		for (ServerPlayer p : players) {
			if (!swung(p) || now < rest.getOrDefault(p.getUUID(), 0L)) continue;
			if (aims(p, at, 5)) struck(level, p, now);
			else if (ember != null && emberVel == null && emberFall < 0 && aims(p, emberPos, 5)) launch(level, p.getLookAngle());
		}
		if (!on) return;
		switch (kind()) {
			case BLUE -> {
				if (now % 2 == 0) AuraFx.ring(level, at.x, at.y - 0.6, at.z, ringRadius(now), AuraFx.dust(inBeat(now) ? 0xFFFFFF : rgb, 1.6f), 26);
				if (beat(now) == CYCLE - 8) level.playSound(null, at.x, at.y, at.z, AuraSounds.NOTE_BLOCK_BELL.value(), SoundSource.HOSTILE, 1.6f, 1.2f);
			}
			case GREEN -> {
				if (now % 10 == 0) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()), at.x, at.y - 0.6, at.z, 10, 0.7, 0.9, 0.7, 0.02);
			}
			case YELLOW -> beam(level, players, now);
			default -> ember(level, players, now);
		}
	}

	/** A new swing this tick (a block placed, an ability: any arm swing counts as a try). */
	private boolean swung(ServerPlayer p) {
		int t = p.swinging ? p.swingTime : 99, was = swing.getOrDefault(p.getUUID(), 99);
		swing.put(p.getUUID(), t);
		return t < was;
	}

	private static boolean aims(ServerPlayer p, Vec3 q, double reach) {
		Vec3 to = q.subtract(p.getEyePosition());
		double along = to.dot(p.getLookAngle());
		return along > 0 && to.length() < reach && to.subtract(p.getLookAngle().scale(along)).length() < 1.3;
	}

	int beat(long now) {
		return (int) Math.floorMod(now - since, (long) CYCLE);
	}

	private double ringRadius(long now) {
		return 0.4 + 4 * (1 - beat(now) / (double) (CYCLE - 1));
	}

	boolean inBeat(long now) {
		return beat(now) >= CYCLE - 9;
	}

	/** A player's swing at the shard. */
	void struck(ServerLevel level, ServerPlayer p, long now) {
		rest.put(p.getUUID(), now + 12);
		if (now - since > STUCK) {
			level.playSound(null, at.x, at.y, at.z, AuraSounds.AMETHYST_CLUSTER_BREAK.value(), SoundSource.HOSTILE, 1.5f, 0.9f);
			if (++crackHits >= 8) breakIt(level);
			return;
		}
		if (kind() == BLUE) {
			if (inBeat(now)) good(level);
			else {
				hits = Math.max(0, hits - 1);
				Vec3 out = p.position().subtract(at).multiply(1, 0, 1).normalize();
				p.setDeltaMovement(out.x * 0.9, 0.35, out.z * 0.9);
				p.hurtMarked = true;
				rest.put(p.getUUID(), now + 25);
				level.playSound(null, at.x, at.y, at.z, AuraSounds.BELL_BLOCK.value(), SoundSource.HOSTILE, 2f, 0.5f);
				p.displayClientMessage(Component.literal("Off the beat - wait for the ring").withStyle(s -> s.withColor(0xAAAAAA)), true);
			}
		} else level.playSound(null, at.x, at.y, at.z, AuraSounds.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 1f, 1.4f);
	}

	private void good(ServerLevel level) {
		hits++;
		level.sendParticles(AuraFx.dust(Reliquary.WING_RGB[kind()], 2.2f), at.x, at.y, at.z, 30, 0.6, 0.6, 0.6, 0);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.NOTE_BLOCK_CHIME.value(), SoundSource.HOSTILE, 2f, RiftSfx.note(2 + hits * 2));
		if (hits >= NEED[kind()]) breakIt(level);
	}

	/** The Warden's own blow landed at {@code where}. */
	void blow(ServerLevel level, Vec3 where, double radius) {
		if (!on || kind() != GREEN || level.getGameTime() < nextAct) return;
		if (where.subtract(at).horizontalDistance() > radius + 1.5) return;
		nextAct = level.getGameTime() + 20;
		good(level);
	}

	private void beam(ServerLevel level, List<ServerPlayer> players, long now) {
		if (pillar == null || players.isEmpty()) return;
		if (now % 4 == 0) level.sendParticles(ParticleTypes.END_ROD, pillar.x, pillar.y, pillar.z, 4, 0.4, 0.6, 0.4, 0.02);
		if (beamAt < 0) {
			if (now < nextAct) return;
			aimed = players.get(level.random.nextInt(players.size()));
			beamAt = now;
			level.playSound(null, pillar.x, pillar.y, pillar.z, AuraSounds.BEACON_POWER_SELECT.value(), SoundSource.HOSTILE, 2f, 1.8f);
		}
		long n = now - beamAt;
		if (n <= 32 || lock == null) lock = aimed.position().add(0, 1, 0);
		if (n % 2 == 0) AuraFx.line(level, pillar, lock, n > 32 ? ParticleTypes.END_ROD : AuraFx.dust(Reliquary.WING_RGB[YELLOW], 1.1f), 30);
		if (n < 44) return;
		w.aspects.fire(level, pillar, lock);
		boolean through = crosses(pillar, lock, at);
		beamAt = -1;
		nextAct = now + BEAM_EVERY;
		Vec3 gone = lock;
		lock = null;
		if (through && gone != null) good(level);
	}

	/** Seen from above, the line from {@code a} to {@code b} runs through {@code q}. */
	static boolean crosses(Vec3 a, Vec3 b, Vec3 q) {
		Vec3 d = b.subtract(a).multiply(1, 0, 1), r = q.subtract(a).multiply(1, 0, 1);
		double len = d.lengthSqr();
		if (len < 1.0e-4) return false;
		double u = r.dot(d) / len;
		return u > 0 && u < 1 && r.subtract(d.scale(u)).length() < 1.7;
	}

	private void ember(ServerLevel level, List<ServerPlayer> players, long now) {
		if (ember == null) {
			if (now < nextAct) return;
			double a = level.random.nextDouble() * Math.PI * 2;
			emberPos = new Vec3(at.x + Math.cos(a) * 6, at.y - 0.6, at.z + Math.sin(a) * 6);
			Vec3 c = Reliquary.cryptCenter(w.run);
			// never out under the gallery: on the far side it lands towards the middle
			if (emberPos.subtract(c).horizontalDistance() > 16) emberPos = new Vec3(2 * at.x - emberPos.x, emberPos.y, 2 * at.z - emberPos.z);
			ember = Props.spawn(level, emberPos.add(0, 20, 0), Blocks.MAGMA_BLOCK.defaultBlockState(), RuinKit.centred(new Vector3f(), 1f, 1f, 1f, new Quaternionf().rotationXYZ(0.5f, 0.4f, 0)), -1, 2);
			Props.glow(ember);
			emberFall = now;
			emberVel = null;
			return;
		}
		if (emberFall >= 0) {
			double u = (now - emberFall) / 30.0;
			if (now % 3 == 0) AuraFx.ring(level, emberPos.x, emberPos.y - 0.4, emberPos.z, 3.2 * (1 - u * 0.6), AuraFx.dust(Reliquary.WING_RGB[RED], 1.5f), 20);
			Vec3 q = emberPos.add(0, 20 * (1 - Math.min(1, u)), 0);
			Props.move(ember, q);
			level.sendParticles(ParticleTypes.FLAME, q.x, q.y, q.z, 4, 0.2, 0.2, 0.2, 0.02);
			if (u < 1) return;
			emberFall = -1;
			emberUntil = now + 160;
			w.aspects.impact(level, emberPos);
			return;
		}
		if (now % 3 == 0) level.sendParticles(ParticleTypes.FLAME, emberPos.x, emberPos.y, emberPos.z, 3, 0.3, 0.3, 0.3, 0.01);
		if (emberVel != null) {
			emberPos = emberPos.add(emberVel);
			Props.move(ember, emberPos);
			level.sendParticles(ParticleTypes.LAVA, emberPos.x, emberPos.y, emberPos.z, 1, 0, 0, 0, 0);
			boolean hit = emberPos.subtract(at).horizontalDistance() < 1.9;
			if (hit || now > emberUntil) {
				endEmber(level, now);
				if (hit) good(level);
			}
		} else if (now > emberUntil) endEmber(level, now);
	}

	/** A player hits the ember: it flies along the floor the way they look. */
	void launch(ServerLevel level, Vec3 look) {
		Vec3 flat = look.multiply(1, 0, 1);
		if (flat.lengthSqr() < 1.0e-4) return;
		emberVel = flat.normalize().scale(0.8);
		emberUntil = level.getGameTime() + 24;
		level.playSound(null, emberPos.x, emberPos.y, emberPos.z, AuraSounds.BLAZE_SHOOT.value(), SoundSource.HOSTILE, 1.5f, 1.2f);
	}

	private void endEmber(ServerLevel level, long now) {
		level.sendParticles(ParticleTypes.EXPLOSION, emberPos.x, emberPos.y, emberPos.z, 1, 0, 0, 0, 0);
		Props.remove(ember);
		ember = null;
		emberVel = null;
		nextAct = now + 40;
	}

	private void breakIt(ServerLevel level) {
		int rgb = Reliquary.WING_RGB[kind()];
		level.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 2, 0.2, 0.2, 0.2, 0);
		level.sendParticles(AuraFx.dust(rgb, 3f), at.x, at.y, at.z, 80, 1, 1, 1, 0);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.AMETHYST_BLOCK.defaultBlockState()), at.x, at.y, at.z, 50, 0.6, 0.6, 0.6, 0.3);
		level.playSound(null, at.x, at.y, at.z, AuraSounds.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.4f, 1.3f);
		AuraFx.line(level, at, w.position().add(0, 2.9, 0), ParticleTypes.END_ROD, 24);
		broken++;
		if (broken >= 4) {
			clear();
			w.shardsDown(level, true);
			return;
		}
		w.shardsDown(level, false);
		next(level);
	}

	private void drop() {
		if (shard != null) shard.discard();
		shard = null;
		if (ember != null) Props.remove(ember);
		ember = null;
		emberVel = null;
		emberFall = beamAt = -1;
		lock = null;
	}

	void clear() {
		on = false;
		drop();
	}

	// ------------------------------------------------------------ dev

	/** DEV ONLY: the shard of this kind now, fresh. */
	void devKind(ServerLevel level, int kind) {
		order.remove((Integer) kind);
		order.add(broken, kind);
		next(level);
		nextAct = 0;
	}

	void devBeam(ServerLevel level, Vec3 target) {
		beamAt = level.getGameTime() - 44;
		lock = target;
		aimed = null;
		long now = level.getGameTime();
		w.aspects.fire(level, pillar, lock);
		beamAt = -1;
		if (crosses(pillar, target, at)) good(level);
	}

	void devEmber(ServerLevel level, boolean toward) {
		emberPos = at.add(6, -0.6, 0);
		ember = Props.spawn(level, emberPos, Blocks.MAGMA_BLOCK.defaultBlockState(), RuinKit.centred(new Vector3f(), 1f, 1f, 1f, new Quaternionf()), -1, 2);
		emberFall = -1;
		launch(level, toward ? new Vec3(-1, 0, 0) : new Vec3(1, 0, 0));
		for (int k = 0; k < 24 && ember != null; k++) ember(level, List.of(), level.getGameTime() + k);
		if (ember != null) endEmber(level, 0);
	}

	Vec3 devPillar() {
		return pillar;
	}
}
