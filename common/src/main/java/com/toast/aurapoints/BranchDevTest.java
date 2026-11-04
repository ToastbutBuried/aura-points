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
import net.minecraft.world.entity.monster.Zombie;
import com.toast.aurapoints.ability.PhantomDecoy;
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
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("branch2").executes(ctx -> {
			frostVerdantEarth(ctx.getSource().getServer());
			return 1;
		})));
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("dark").executes(ctx -> {
			dark(ctx.getSource().getServer());
			return 1;
		})));
	}

	/** /auradev dark: Shadow's Phantom and Dark (5.10.0): pick/switch, kit, keys, Ultimate, matchups, scoreboards, decoy, old Void saves. */
	private static void dark(net.minecraft.server.MinecraftServer server) {
		ServerLevel level = server.overworld();
		var spawn = level.getSharedSpawnPos();
		Vec3 base = new Vec3(spawn.getX() + 0.5, 120, spawn.getZ() + 0.5);
		net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b4a2"), "Darkling"));
		var state = com.toast.aurapoints.data.AuraState.get(server);
		var data = state.getOrCreate(p.getUUID());
		var wasAura = data.aura;
		var wasMastery = data.mastery.get(AuraType.SHADOW);
		AuraCurrency.set(p, 1000);
		data.aura = AuraType.SHADOW;
		data.mastery.put(AuraType.SHADOW, 5);
		data.branches.clear();
		data.owned.clear();
		data.owned.add(AuraAbilities.Ability.DASH);
		data.owned.add(AuraAbilities.Ability.VANISH);
		data.awakened.add(AuraType.SHADOW);

		// base Shadow
		log("base Shadow: Dash " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.DASH) + " (true), Void Pull " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.VOID_PULL) + " (false), Ultimate "
				+ Ultimates(data) + " (must be ECLIPSE), keys " + AuraType.SHADOW.keySlots(Branch.current(data)) + " (must be [DASH, VANISH])");
		log("branches of Shadow: " + Branch.of(AuraType.SHADOW) + " numbers " + Branch.PHANTOM.number() + "/" + Branch.DARK.number() + " (must be 1/2)");

		// Dark
		log("pick Dark: '" + Branch.pick(p, Branch.DARK).getString() + "'");
		log("Dark kit: Void Pull " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.VOID_PULL) + " Rift Step " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.RIFT_STEP)
				+ " Null Orb " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.NULL_ORB) + " Horizon " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.EVENT_HORIZON)
				+ " Entropy " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.ENTROPY) + " Bloodrush " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.BLOODRUSH)
				+ " Nightstalker " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.NIGHTSTALKER) + " (all must be true), Dash " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.DASH)
				+ " Vanish " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.VANISH) + " Phantom Step " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.PHANTOM_STEP) + " (must be false)");
		log("Dark free actives: owned " + data.owned.containsAll(java.util.List.of(AuraAbilities.Ability.VOID_PULL, AuraAbilities.Ability.RIFT_STEP, AuraAbilities.Ability.NULL_ORB))
				+ " (must be true), Aura " + AuraCurrency.get(p) + " (must be 1000: the first pick is free)");
		log("Dark keys " + AuraType.SHADOW.keySlots(Branch.current(data)) + " (must be [RIFT_STEP, VOID_PULL, NULL_ORB]), slot 0 " + AuraType.SHADOW.slot(0, Branch.current(data)) + ", Ultimate "
				+ Ultimates(data) + " (must be SINGULARITY; awakened Shadow carries over), Singularity usable " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.SINGULARITY)
				+ " (true), Eclipse " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.ECLIPSE) + " (false)");
		log("Dark wire '" + Branch.wire(data.aura, Branch.current(data)) + "' (must be shadow:dark), balance page of Singularity/Void Pull: " + "see sectionOf");
		// matchups
		var dark = Branch.DARK;
		log("matchups: Dark beats Light " + AuraType.beats(AuraType.SHADOW, dark, AuraType.LIGHT, null) + " Wind " + AuraType.beats(AuraType.SHADOW, dark, AuraType.WIND, null)
				+ " (true/true), Dark beats Fire " + AuraType.beats(AuraType.SHADOW, dark, AuraType.FIRE, null) + " (false), Light beats plain Shadow "
				+ AuraType.beats(AuraType.LIGHT, null, AuraType.SHADOW, null) + " (true), Light beats Dark " + AuraType.beats(AuraType.LIGHT, null, AuraType.SHADOW, dark)
				+ " (false), Dark weak against " + AuraType.SHADOW.weakAgainst(dark) + " (must be []), plain Shadow weak against " + AuraType.SHADOW.weakAgainst(null) + " (must be [LIGHT])");
		// scoreboard
		var board = server.getScoreboard();
		var obj = board.getObjective(AuraHooks.BRANCH_OBJECTIVE);
		log("scoreboard aura_branch " + (obj == null ? "missing" : board.getOrCreatePlayerScore(p, obj).get()) + " (must be 2), aura_type "
				+ board.getOrCreatePlayerScore(p, board.getObjective(AuraHooks.AURA_OBJECTIVE)).get() + " (must be Shadow's number 1)");
		// switch to Phantom costs, Phantom kit
		log("switch to Phantom: '" + Branch.pick(p, Branch.PHANTOM).getString() + "', Aura " + AuraCurrency.get(p) + " (must be " + (1000 - AuraConfig.BRANCH_SWITCH_COST) + "), Dash "
				+ AuraAbilities.hasAbility(data, AuraAbilities.Ability.DASH) + " (true), Void Pull " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.VOID_PULL) + " (false), Ultimate " + Ultimates(data) + " (ECLIPSE)");
		log("Phantom cooldowns: Vanish " + Branch.cooldown(p, AuraAbilities.Ability.VANISH, 900) + " Dash " + Branch.cooldown(p, AuraAbilities.Ability.DASH, 100) + " Void Pull "
				+ Branch.cooldown(p, AuraAbilities.Ability.VOID_PULL, 100) + " (must be " + Math.round(900 * AuraConfig.PHANTOM_COOLDOWN_FACTOR) + " / " + Math.round(100 * AuraConfig.PHANTOM_COOLDOWN_FACTOR) + " / 100)");
		// below Mastery V: no branch at all
		data.mastery.put(AuraType.SHADOW, 4);
		log("Mastery IV: branch " + Branch.current(data) + " (null), Void Pull " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.VOID_PULL) + " (false)");
		data.mastery.put(AuraType.SHADOW, 5);

		// Phantom decoy: a mob hunting the player loses them
		net.minecraft.world.entity.monster.Zombie zombie = new Zombie(EntityType.ZOMBIE, level);
		zombie.moveTo(base.x + 4, base.y, base.z + 60, 0, 0);
		zombie.setNoGravity(true);
		level.addFreshEntity(zombie);
		zombie.setTarget(p);
		boolean before = zombie.getTarget() == p;
		PhantomDecoy.spawn(p, base.add(0, 0, 60));
		log("decoy: zombie hunted " + before + " -> " + (zombie.getTarget() == p) + " (must be true -> false), decoys " + PhantomDecoy.count() + " (1)");

		// old saves: a held Void aura and Void entries in the lists
		var tag = new net.minecraft.nbt.CompoundTag();
		tag.putString("aura", "VOID");
		var rolled = new net.minecraft.nbt.ListTag();
		rolled.add(net.minecraft.nbt.StringTag.valueOf("VOID"));
		rolled.add(net.minecraft.nbt.StringTag.valueOf("FIRE"));
		tag.put("rolled", rolled);
		var aw = new net.minecraft.nbt.ListTag();
		aw.add(net.minecraft.nbt.StringTag.valueOf("VOID"));
		tag.put("awakened", aw);
		var mast = new net.minecraft.nbt.CompoundTag();
		mast.putInt("VOID", 3);
		mast.putInt("SHADOW", 2);
		tag.put("mastery", mast);
		var owned = new net.minecraft.nbt.ListTag();
		owned.add(net.minecraft.nbt.StringTag.valueOf("VOID_PULL"));
		tag.put("owned", owned);
		var old = com.toast.aurapoints.data.AuraPlayerData.readNbt(tag);
		log("old Void save: aura " + old.aura + " branch " + old.branches.get(AuraType.SHADOW) + " (must be SHADOW / DARK), rolled " + old.rolled + " (SHADOW, FIRE), awakened " + old.awakened
				+ " (SHADOW), mastery SHADOW " + old.mastery.get(AuraType.SHADOW) + " (5), owned " + old.owned + " (VOID_PULL), Void Pull usable " + AuraAbilities.hasAbility(old, AuraAbilities.Ability.VOID_PULL) + " (true)");
		// and a round trip of the migrated data
		var again = com.toast.aurapoints.data.AuraPlayerData.readNbt(old.writeNbt());
		log("round trip: aura " + again.aura + " branch " + again.branches.get(AuraType.SHADOW) + " (SHADOW / DARK)");

		AuraFx.later(server, 40, () -> {
			zombie.discard();
			PhantomDecoy.clear();
			data.aura = wasAura;
			data.branches.clear();
			data.awakened.remove(AuraType.SHADOW);
			if (wasMastery == null) data.mastery.remove(AuraType.SHADOW);
			else data.mastery.put(AuraType.SHADOW, wasMastery);
			log("dark done (state restored)");
		});
	}

	/** /auradev branch2: the 5.11.0 branches (Frost, Verdant, Earth): numbers each one changes, prices, Brittle and Virulence hits, poison stacking, Bloom heal, Bulwark absorption. */
	private static void frostVerdantEarth(net.minecraft.server.MinecraftServer server) {
		ServerLevel level = server.overworld();
		var spawn = level.getSharedSpawnPos();
		Vec3 base = new Vec3(spawn.getX() + 0.5, 120, spawn.getZ() + 0.5);
		net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b4a3"), "Weaver"));
		net.minecraft.server.level.ServerPlayer friend = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b4a4"), "Buddy"));
		var state = com.toast.aurapoints.data.AuraState.get(server);
		var data = state.getOrCreate(p.getUUID());
		var wasAura = data.aura;
		var wasMastery = new java.util.EnumMap<AuraType, Integer>(AuraType.class);
		wasMastery.putAll(data.mastery);
		for (AuraType a : new AuraType[]{AuraType.FROST, AuraType.VERDANT, AuraType.EARTH}) data.mastery.put(a, 5);
		data.branches.clear();
		AuraAbilities.Ability V = AuraAbilities.Ability.VIRULENCE;

		log("branches of: Frost " + Branch.of(AuraType.FROST) + " Verdant " + Branch.of(AuraType.VERDANT) + " Earth " + Branch.of(AuraType.EARTH) + " (2 each)");
		StringBuilder icons = new StringBuilder();
		java.util.Set<net.minecraft.world.item.Item> seen = new java.util.HashSet<>();
		for (Branch b : Branch.values()) {
			icons.append(b.id()).append('=').append(b.icon).append(' ');
			seen.add(b.icon);
		}
		log("icons: " + icons + "(distinct " + seen.size() + " of " + Branch.values().length + ", must be equal)");

		// ---- Frost
		data.aura = AuraType.FROST;
		data.branches.put(AuraType.FROST, null);
		data.branches.remove(AuraType.FROST);
		log("Frost base: Shatter " + com.toast.aurapoints.ability.Shatter.hitsNeeded(p) + " (3) layers " + com.toast.aurapoints.ability.IceShell.maxLayers(p) + " (3) rebuild "
				+ com.toast.aurapoints.ability.IceShell.rebuildTicks(p, false) + " (600) freeze " + com.toast.aurapoints.ability.IceSpike.freezeTicks(p) + " (60) Brittle "
				+ AuraAbilities.hasAbility(data, AuraAbilities.Ability.BRITTLE) + " (false)");
		data.branches.put(AuraType.FROST, Branch.GLACIAL);
		log("Glacial: Shatter " + com.toast.aurapoints.ability.Shatter.hitsNeeded(p) + " (4) layers " + com.toast.aurapoints.ability.IceShell.maxLayers(p) + " (5) rebuild "
				+ com.toast.aurapoints.ability.IceShell.rebuildTicks(p, false) + " (900) freeze " + com.toast.aurapoints.ability.IceSpike.freezeTicks(p) + " (100) Brittle "
				+ AuraAbilities.hasAbility(data, AuraAbilities.Ability.BRITTLE) + " (false)");
		data.branches.put(AuraType.FROST, Branch.FROSTBITE);
		log("Frostbite: Shatter " + com.toast.aurapoints.ability.Shatter.hitsNeeded(p) + " (2) layers " + com.toast.aurapoints.ability.IceShell.maxLayers(p) + " (2) freeze "
				+ com.toast.aurapoints.ability.IceSpike.freezeTicks(p) + " (40) Brittle " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.BRITTLE) + " (true), passives listed "
				+ AuraType.FROST.abilities(Branch.FROSTBITE).contains(AuraAbilities.Ability.BRITTLE) + " (true)");
		double[] hit = new double[3];
		for (int i = 0; i < 3; i++) {
			data.branches.put(AuraType.FROST, i == 2 ? Branch.GLACIAL : Branch.FROSTBITE);
			net.minecraft.world.entity.animal.Pig t = pig(level, base.add(80, 0, 0));
			if (i > 0) t.setTicksFrozen(120);
			t.invulnerableTime = 0;
			t.hurt(level.damageSources().playerAttack(p), 10f);
			hit[i] = 200 - t.getHealth();
			t.discard();
		}
		log("Brittle: Frostbite plain " + hit[0] + ", chilled " + hit[1] + " (must be x" + (1 + AuraConfig.BRITTLE_BONUS) + "), Glacial chilled " + hit[2] + " (must equal plain)");

		// ---- Verdant
		data.aura = AuraType.VERDANT;
		data.branches.put(AuraType.VERDANT, Branch.VENOMWEAVER);
		log("Venomweaver: Virulence " + AuraAbilities.hasAbility(data, V) + " (true), Viper cd " + Branch.cooldown(p, AuraAbilities.Ability.VERDANT_VIPER, AuraConfig.VIPER_COOLDOWN_TICKS)
				+ " (" + AuraConfig.VERDANT_VENOM_VIPER_COOLDOWN_TICKS + ")");
		data.branches.put(AuraType.VERDANT, Branch.GROVE_WARDEN);
		log("Grove Warden: Virulence " + AuraAbilities.hasAbility(data, V) + " (false), still in Verdant's base list " + AuraType.VERDANT.abilities.contains(V) + " (true), in the branch list "
				+ AuraType.VERDANT.abilities(Branch.GROVE_WARDEN).contains(V) + " (false), Bloom " + AuraAbilities.hasAbility(data, AuraAbilities.Ability.BLOOM) + " (true)");
		double[] vir = new double[3];
		for (int i = 0; i < 3; i++) {
			data.branches.remove(AuraType.VERDANT);
			if (i == 1) data.branches.put(AuraType.VERDANT, Branch.VENOMWEAVER);
			if (i == 2) data.branches.put(AuraType.VERDANT, Branch.GROVE_WARDEN);
			net.minecraft.world.entity.animal.Pig t = pig(level, base.add(80, 0, 10));
			t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 200, 0));
			t.invulnerableTime = 0;
			t.hurt(level.damageSources().playerAttack(p), 10f);
			vir[i] = 200 - t.getHealth();
			t.discard();
		}
		log("Virulence on a poisoned pig: base " + vir[0] + " (x" + (1 + AuraConfig.VIRULENCE_BONUS) + "), Venomweaver " + vir[1] + " (x" + (1 + AuraConfig.VERDANT_VENOM_VIRULENCE_BONUS) + "), Grove Warden " + vir[2] + " (10, no passive)");
		int[] amp = new int[4];
		for (int i = 0; i < 4; i++) {
			data.branches.put(AuraType.VERDANT, i == 3 ? Branch.GROVE_WARDEN : Branch.VENOMWEAVER);
			net.minecraft.world.entity.animal.Pig t = pig(level, base.add(80, 0, 20));
			com.toast.aurapoints.ability.Entanglement.poison(t, p, 1, 200);
			if (i >= 1) com.toast.aurapoints.ability.Entanglement.poison(t, p, 1, 200);
			if (i >= 2) com.toast.aurapoints.ability.Entanglement.poison(t, p, 1, 200);
			if (i == 2) com.toast.aurapoints.ability.Entanglement.poison(t, p, 1, 200);
			amp[i] = t.getEffect(net.minecraft.world.effect.MobEffects.POISON).getAmplifier();
			t.discard();
		}
		log("poison stacking (Venomweaver, 1 / 2 / 4 bites, Grove Warden with 3): amplifiers " + amp[0] + "/" + amp[1] + "/" + amp[2] + "/" + amp[3] + " (must be 1/2/3/1)");
		data.branches.put(AuraType.VERDANT, Branch.GROVE_WARDEN);
		data.bloomOn = true;
		p.setHealth(10f);
		friend.setHealth(10f);
		friend.moveTo(p.getX(), p.getY(), p.getZ()); // Fabric fake players spawn apart, NeoForge's ignore this
		int healed = com.toast.aurapoints.ability.Bloom.healFriends(p, java.util.List.of(p, friend));
		log("Bloom heal (the filter, Grove Warden's code): healed " + healed + " (must be 1), friend " + friend.getHealth() + " (must be 10 + " + AuraConfig.VERDANT_GROVE_HEAL + "), you " + p.getHealth() + " (must stay 10; the area query itself is not testable with fake players)");
		p.setShiftKeyDown(false);
		friend.setHealth(friend.getMaxHealth());
		p.setHealth(p.getMaxHealth());

		// ---- Earth
		data.aura = AuraType.EARTH;
		var kb = net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE;
		StringBuilder earth = new StringBuilder();
		for (Branch b : new Branch[]{null, Branch.BULWARK, Branch.TECTONIC}) {
			if (b == null) data.branches.remove(AuraType.EARTH);
			else data.branches.put(AuraType.EARTH, b);
			com.toast.aurapoints.ability.Earth.tickPassives(p, data, 5);
			earth.append(b == null ? "base" : b.id()).append(": kb ").append(p.getAttributeValue(kb)).append(" fall x").append(com.toast.aurapoints.ability.Earth.fallDamage(data, 10f) / 10f)
					.append(" pillar ").append(com.toast.aurapoints.ability.Earth.pillarDamage(p)).append("/").append(com.toast.aurapoints.ability.Earth.pillarLaunch(p))
					.append(" boulder ").append(com.toast.aurapoints.ability.Earth.boulderDamage(p)).append("/").append(com.toast.aurapoints.ability.Earth.boulderSplash(p)).append(" | ");
		}
		log("Earth " + earth);
		log("(must be: base kb 0.5 fall x0.7 pillar 4.0/1.15 boulder 8.0/4.0 | bulwark kb 0.8 fall x0.5 pillar 3.0/1.15 boulder 6.0/3.0 | tectonic kb 0.25 fall x0.7 pillar 6.0/1.35 boulder 11.0/5.5)");
		data.branches.put(AuraType.EARTH, Branch.BULWARK);
		p.setAbsorptionAmount(0);
		for (int i = 0; i < 5; i++) com.toast.aurapoints.ability.Earth.bulwarkAbsorb(p);
		float peak = p.getAbsorptionAmount();
		AuraFx.later(server, AuraConfig.EARTH_BULWARK_QUAKE_ABSORB_TICKS + 20, () -> {
			log("Bulwark absorption: peak " + peak + " (must be " + AuraConfig.EARTH_BULWARK_QUAKE_ABSORB_MAX + ", capped), effect "
					+ p.getEffect(net.minecraft.world.effect.MobEffects.ABSORPTION).getAmplifier() + " (must be 1) lasting " + (AuraConfig.EARTH_BULWARK_QUAKE_ABSORB_TICKS / 20)
					+ "s (a fake player does not tick, so the expiry is not testable here)");
			data.aura = wasAura;
			data.branches.clear();
			data.mastery.clear();
			data.mastery.putAll(wasMastery);
			p.removeAllEffects();
			com.toast.aurapoints.ability.Earth.tickPassives(p, data, 5);
			log("branch2 done (state restored)");
		});
	}

	private static AuraAbilities.Ability Ultimates(com.toast.aurapoints.data.AuraPlayerData data) {
		return com.toast.aurapoints.ability.Ultimates.of(data.aura, Branch.current(data));
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
