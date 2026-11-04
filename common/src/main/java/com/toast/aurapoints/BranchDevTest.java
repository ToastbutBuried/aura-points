package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraFireball;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Branch;
import com.toast.aurapoints.ability.FireBending;
import com.toast.aurapoints.ability.MagmaPools;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * DEV ONLY (aurapoints.devtest): /auradev branch - the 5.9.0 aura branches on a fake player: unlock, pick and
 * switch cost, cooldowns, Scorch, Fire Bending damage, Fireball pierce + speed, Magma pools. Logs [branchtest] lines
 * with "(must be X)"; the results that need game ticks (pierce, pools) come about 3 s later.
 */
final class BranchDevTest {

	private BranchDevTest() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("branch").executes(ctx -> {
			run(ctx.getSource().getServer());
			return 1;
		})));
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[branchtest] {}", what);
	}

	private static Pig pig(ServerLevel level, Vec3 at) {
		Pig pig = new Pig(EntityType.PIG, level);
		pig.moveTo(at.x, at.y, at.z);
		pig.setNoAi(true);
		pig.setNoGravity(true);
		pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
		pig.setHealth(200);
		level.addFreshEntity(pig);
		return pig;
	}

	private static void run(net.minecraft.server.MinecraftServer server) {
		ServerLevel level = server.overworld();
		var spawn = level.getSharedSpawnPos();
		Vec3 base = new Vec3(spawn.getX() + 0.5, 120, spawn.getZ() + 0.5);
		net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b4a1"), "Brancher"));
		var state = com.toast.aurapoints.data.AuraState.get(server);
		var data = state.getOrCreate(p.getUUID());
		var wasAura = data.aura;
		var wasMastery = data.mastery.get(AuraType.FIRE);
		AuraCurrency.set(p, 1000);
		MagmaPools.clear();

		// 1. base kit, no branch, then a branch picked below Mastery V (must not count)
		data.aura = AuraType.FIRE;
		data.mastery.put(AuraType.FIRE, 5);
		data.branches.clear();
		log("no pick: branch " + Branch.current(p) + " (must be null), Scorch " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.SCORCH)
				+ " (must be false), Fireball cd " + Branch.cooldown(p, AuraAbilities.Ability.FIREBALL, AuraConfig.FIREBALL_COOLDOWN_TICKS) + " (must be " + AuraConfig.FIREBALL_COOLDOWN_TICKS + ")");
		data.branches.put(AuraType.FIRE, Branch.BLUE_FLAME);
		data.mastery.put(AuraType.FIRE, 4);
		log("picked at Mastery IV: branch " + Branch.current(p) + " (must be null), blocker '" + Branch.blocker(data) + "'");
		data.mastery.put(AuraType.FIRE, 5);
		data.branches.clear();

		// 2. pick: first one free, a switch costs, too poor fails, wrong aura fails, other aura without branches fails
		var m1 = Branch.pick(p, Branch.BLUE_FLAME).getString();
		log("first pick: '" + m1 + "', Aura " + AuraCurrency.get(p) + " (must be 1000), branch " + Branch.current(p) + " (must be BLUE_FLAME), scoreboard number " + Branch.current(p).number() + " (must be 1)");
		var m2 = Branch.pick(p, Branch.BLUE_FLAME).getString();
		log("same again: '" + m2 + "' (must refuse), Aura " + AuraCurrency.get(p) + " (must be 1000)");
		var m3 = Branch.pick(p, Branch.MAGMA).getString();
		log("switch: '" + m3 + "', Aura " + AuraCurrency.get(p) + " (must be " + (1000 - AuraConfig.BRANCH_SWITCH_COST) + "), branch " + Branch.current(p) + " (must be MAGMA)");
		AuraCurrency.set(p, 10);
		var m4 = Branch.pick(p, Branch.BLUE_FLAME).getString();
		log("too poor: '" + m4 + "' (must refuse), branch " + Branch.current(p) + " (must be MAGMA), Aura " + AuraCurrency.get(p) + " (must be 10)");
		data.aura = AuraType.WATER;
		data.mastery.put(AuraType.WATER, 5);
		log("Water: blocker '" + Branch.blocker(data) + "' (must say no branches), held Fire pick ignored: " + Branch.current(p) + " (must be null)");
		data.aura = AuraType.FIRE;

		// 3. cooldowns and passives per branch
		data.branches.put(AuraType.FIRE, Branch.BLUE_FLAME);
		log("Blue Flame: Scorch " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.SCORCH) + " (must be true), Fireball cd "
				+ Branch.cooldown(p, AuraAbilities.Ability.FIREBALL, AuraConfig.FIREBALL_COOLDOWN_TICKS) + " (must be " + AuraConfig.FIRE_BLUE_FLAME_FIREBALL_COOLDOWN_TICKS
				+ "), Heat Wave cd " + Branch.cooldown(p, AuraAbilities.Ability.HEAT_WAVE, AuraConfig.HEAT_WAVE_COOLDOWN_TICKS) + " (must be " + AuraConfig.FIRE_BLUE_FLAME_HEAT_WAVE_COOLDOWN_TICKS
				+ "), Fire Bending cd " + Branch.cooldown(p, AuraAbilities.Ability.FIRE_BENDING, AuraConfig.FIRE_BENDING_COOLDOWN_TICKS) + " (must be " + AuraConfig.FIRE_BENDING_COOLDOWN_TICKS + ")");
		log("Blue Flame shot: close " + FireBending.shotDamage(p, 0, 1f) + " (must be " + AuraConfig.FIRE_BLUE_FLAME_BENDING_DAMAGE_CLOSE + "), far " + FireBending.shotDamage(p, 1000, 1f)
				+ " (must be " + AuraConfig.FIRE_BLUE_FLAME_BENDING_DAMAGE_FAR + ")");
		data.branches.put(AuraType.FIRE, Branch.MAGMA);
		log("Magma: Scorch " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.SCORCH) + " (must be false), Fireball cd "
				+ Branch.cooldown(p, AuraAbilities.Ability.FIREBALL, AuraConfig.FIREBALL_COOLDOWN_TICKS) + " (must be " + AuraConfig.FIRE_MAGMA_FIREBALL_COOLDOWN_TICKS
				+ "), shot close " + FireBending.shotDamage(p, 0, 1f) + " (must be " + AuraConfig.FIRE_BENDING_DAMAGE_CLOSE + ")");

		// 4. Scorch: a hit on a burning pig
		double[] hit = new double[3];
		for (int i = 0; i < 3; i++) {
			data.branches.put(AuraType.FIRE, i == 2 ? Branch.MAGMA : Branch.BLUE_FLAME);
			Pig target = pig(level, base.add(40, 0, 0));
			if (i > 0) target.igniteForSeconds(5);
			target.invulnerableTime = 0;
			target.hurt(level.damageSources().playerAttack(p), 10f);
			hit[i] = 200 - target.getHealth();
			target.discard();
		}
		log("Scorch: Blue Flame plain " + hit[0] + ", burning " + hit[1] + " (must be x" + (1.0 + AuraConfig.SCORCH_BONUS) + "), Magma burning " + hit[2] + " (must equal plain)");

		// 5. Fireball pierce + speed (resolved after the fireballs have flown), Magma pool (after a few pulses)
		data.branches.put(AuraType.FIRE, Branch.BLUE_FLAME);
		Pig bfNear = pig(level, base.add(3, 0, 0)), bfFar = pig(level, base.add(7, 0, 0));
		AuraFireball bf = new AuraFireball(level, p, new Vec3(1, 0, 0));
		bf.setPos(base.x, base.y, base.z);
		bf.boost(p);
		double bfSpeed = bf.getDeltaMovement().length();
		level.addFreshEntity(bf);
		data.branches.put(AuraType.FIRE, Branch.MAGMA);
		Pig plainNear = pig(level, base.add(3, 0, 20)), plainFar = pig(level, base.add(7, 0, 20));
		AuraFireball plain = new AuraFireball(level, p, new Vec3(1, 0, 0));
		plain.setPos(base.x, base.y, base.z + 20);
		plain.boost(p);
		double plainSpeed = plain.getDeltaMovement().length();
		level.addFreshEntity(plain);
		log("fireball speed: Blue Flame " + bfSpeed + " vs Magma " + plainSpeed + " (ratio must be " + AuraConfig.FIRE_BLUE_FLAME_FIREBALL_SPEED + ")");

		// Magma pool: a pig stands in one, a pig stands outside; a Blue Flame owner gets no pool at all
		Pig inPool = pig(level, base.add(0, 0, -30)), outPool = pig(level, base.add(8, 0, -30));
		MagmaPools.add(p, base.add(0, -0.5, -30), 2.0);
		int poolsMagma = MagmaPools.count();
		data.branches.put(AuraType.FIRE, Branch.BLUE_FLAME);
		MagmaPools.add(p, base.add(50, 0, 0), 2.0);
		log("pools: Magma owner " + poolsMagma + " (must be 1), then Blue Flame owner adds " + (MagmaPools.count() - poolsMagma) + " (must be 0)");
		data.branches.put(AuraType.FIRE, Branch.MAGMA);

		AuraFx.later(server, 100, () -> {
			log("pierce: Blue Flame near " + (200 - bfNear.getHealth()) + " far " + (200 - bfFar.getHealth()) + " (both must be > 0), plain near "
					+ (200 - plainNear.getHealth()) + " (must be > 0) far " + (200 - plainFar.getHealth()) + " (must be 0: no pierce for Magma)");
			log("magma pool: pig in it lost " + (200 - inPool.getHealth()) + " and burns " + inPool.isOnFire() + " (must be > 0 / true), pig outside lost "
					+ (200 - outPool.getHealth()) + " (must be 0), pools left " + MagmaPools.count());
			for (Pig q : new Pig[]{bfNear, bfFar, plainNear, plainFar, inPool, outPool}) q.discard();
			MagmaPools.clear();
			data.aura = wasAura;
			data.branches.clear();
			if (wasMastery == null) data.mastery.remove(AuraType.FIRE);
			else data.mastery.put(AuraType.FIRE, wasMastery);
			data.mastery.remove(AuraType.WATER);
			log("done (state restored)");
		});
	}
}
