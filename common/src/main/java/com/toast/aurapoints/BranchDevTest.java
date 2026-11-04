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
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("tome").executes(ctx -> {
			tome(ctx.getSource().getServer());
			return 1;
		})));
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("segments").executes(ctx -> {
			segments(ctx.getSource().getServer());
			return 1;
		})));
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("branch4").executes(ctx -> {
			lastEight(ctx.getSource().getServer());
			return 1;
		})));
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("branch3").executes(ctx -> {
			lightBlood(ctx.getSource().getServer());
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

	/** /auradev branch3: the 5.12.0 branches (Light, Blood): numbers per branch, Halo friends, the Prismatic mark and chain, Blood bar gain, Moon cost, a Sacrificial Sacrifice. */
	private static void lightBlood(net.minecraft.server.MinecraftServer server) {
		ServerLevel level = server.overworld();
		var spawn = level.getSharedSpawnPos();
		Vec3 base = new Vec3(spawn.getX() + 0.5, 120, spawn.getZ() + 0.5);
		net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b4a5"), "Prismer"));
		net.minecraft.server.level.ServerPlayer friend = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b4a6"), "Mate"));
		var state = com.toast.aurapoints.data.AuraState.get(server);
		var data = state.getOrCreate(p.getUUID());
		var wasAura = data.aura;
		var wasMastery = new java.util.EnumMap<AuraType, Integer>(AuraType.class);
		wasMastery.putAll(data.mastery);
		var wasBar = data.bloodBar;
		for (AuraType a : new AuraType[]{AuraType.LIGHT, AuraType.BLOOD}) data.mastery.put(a, 5);
		data.branches.clear();

		// ---- Light
		data.aura = AuraType.LIGHT;
		StringBuilder light = new StringBuilder();
		for (Branch b : new Branch[]{null, Branch.RADIANT, Branch.PRISMATIC}) {
			if (b == null) data.branches.remove(AuraType.LIGHT);
			else data.branches.put(AuraType.LIGHT, b);
			light.append(b == null ? "base" : b.id()).append(": pulse ").append(com.toast.aurapoints.ability.Light.pulseHeal(p)).append(" sun ").append(com.toast.aurapoints.ability.Light.sunHeal(p))
					.append(" beam ").append(com.toast.aurapoints.ability.Light.beamDamage(p)).append(" flash cd ").append(Branch.cooldown(p, AuraAbilities.Ability.SOLAR_FLASH, AuraConfig.LIGHT_FLASH_COOLDOWN_TICKS)).append(" | ");
		}
		log("Light " + light);
		log("(must be: base pulse 4.0 sun 2.0 beam 1.5 flash cd 280 | radiant pulse 6.0 sun 3.0 beam 1.05 flash cd 360 | prismatic pulse 3.0 sun 2.0 beam 2.25 flash cd 280)");
		data.branches.put(AuraType.LIGHT, Branch.RADIANT);
		friend.moveTo(p.getX(), p.getY(), p.getZ()); // Fabric fake players spawn apart
		int reached = com.toast.aurapoints.ability.Light.haloFriends(p, java.util.List.of(p, friend));
		var res = friend.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
		log("Radiant Halo: reached " + reached + " (must be 1, not you), friend has Resistance " + (res == null ? "none" : String.valueOf(res.getAmplifier() + 1)) + " (must be 1) for " + (res == null ? 0 : res.getDuration()) + " ticks (<= " + AuraConfig.LIGHT_RADIANT_HALO_BUFF_TICKS + ")");
		friend.removeAllEffects();
		data.branches.put(AuraType.LIGHT, Branch.PRISMATIC);
		net.minecraft.world.entity.animal.Pig target = pig(level, base.add(12, 0, 0));
		net.minecraft.world.entity.animal.Pig near1 = pig(level, base.add(14, 0, 0)), near2 = pig(level, base.add(12, 0, 3)), near3 = pig(level, base.add(9, 0, 0)), far = pig(level, base.add(12, 0, 20));
		for (var q : new net.minecraft.world.entity.animal.Pig[]{near1, near2, near3, far}) q.invulnerableTime = 0;
		log("Prismatic mark: before " + com.toast.aurapoints.ability.Light.isFlashed(target) + " (false)");
		com.toast.aurapoints.ability.Light.markFlashed(target);
		log("after Solar Flash marks it: " + com.toast.aurapoints.ability.Light.isFlashed(target) + " (true), a pig never flashed " + com.toast.aurapoints.ability.Light.isFlashed(far) + " (false)");
		int chained = com.toast.aurapoints.ability.Light.chain(p, target, 10f);
		double d1 = 200 - near1.getHealth(), d2 = 200 - near2.getHealth(), d3 = 200 - near3.getHealth(), dfar = 200 - far.getHealth();
		log("chain: reached " + chained + " (must be " + AuraConfig.LIGHT_PRISM_CHAIN_TARGETS + "), damage to the three near pigs " + d1 + "/" + d2 + "/" + d3 + " (two of them " + (10 * AuraConfig.LIGHT_PRISM_CHAIN_FACTOR)
				+ ", one 0), far pig " + dfar + " (must be 0), the marked target itself " + (200 - target.getHealth()) + " (0: the beam hits it, the chain does not)");
		for (var q : new net.minecraft.world.entity.animal.Pig[]{target, near1, near2, near3, far}) q.discard();

		// ---- Blood
		data.aura = AuraType.BLOOD;
		StringBuilder blood = new StringBuilder();
		for (Branch b : new Branch[]{null, Branch.SANGUINE, Branch.SACRIFICIAL}) {
			if (b == null) data.branches.remove(AuraType.BLOOD);
			else data.branches.put(AuraType.BLOOD, b);
			data.bloodBar = 0;
			com.toast.aurapoints.ability.Blood.addBlood(p, 4);
			blood.append(b == null ? "base" : b.id()).append(": lust ").append(com.toast.aurapoints.ability.Blood.lustHeal(data)).append(" sacrifice x")
					.append(com.toast.aurapoints.ability.Blood.sacrificeMultiplier(p)).append(" moon ").append(com.toast.aurapoints.ability.Blood.moonCost(p)).append(" bar+4 -> ")
					.append(data.bloodBar).append(" | ");
		}
		log("Blood " + blood);
		log("(must be: base lust 4.0 sacrifice x2.0 moon 100 bar+4 -> 4 | sanguine lust 6.0 sacrifice x1.5 moon 130 bar+4 -> 6 | sacrificial lust 2.0 sacrifice x3.0 moon 70 bar+4 -> 4)");
		data.bloodBar = 0;
		data.branches.put(AuraType.BLOOD, Branch.SACRIFICIAL);
		AuraCurrency.set(p, 0);
		p.setHealth(20f);
		boolean paid = com.toast.aurapoints.ability.Blood.sacrifice(p, server.getTickCount());
		float after = p.getHealth();
		net.minecraft.world.entity.animal.Pig victim = pig(level, base.add(12, 0, -12));
		float hitDamage = com.toast.aurapoints.ability.Blood.onHit(p, victim, 10f, true);
		victim.discard();
		log("Sacrificial Sacrifice: fired " + paid + " (true), health 20 -> " + after + " (must be " + (20 - AuraConfig.BLOOD_SACRIFICIAL_SACRIFICE_COST) + "), next hit 10 -> " + hitDamage + " (must be " + (10 * AuraConfig.BLOOD_SACRIFICIAL_SACRIFICE_MULTIPLIER) + ")");
		// a full blood bar: Sacrificial still pays the whole 10, base takes the bar's discount off (and spends the bar)
		p.removeAllEffects();
		StringBuilder bar = new StringBuilder();
		for (Branch b : new Branch[]{Branch.SACRIFICIAL, null}) {
			if (b == null) data.branches.remove(AuraType.BLOOD);
			else data.branches.put(AuraType.BLOOD, b);
			data.bloodBar = 100;
			p.setHealth(20f);
			boolean ok = com.toast.aurapoints.ability.Blood.sacrifice(p, server.getTickCount() + 200000 + (b == null ? 100000 : 0));
			bar.append(b == null ? "base" : b.id()).append(": fired ").append(ok).append(", health 20 -> ").append(p.getHealth()).append(", bar 100 -> ").append(data.bloodBar).append(" | ");
		}
		log("Sacrifice with a full bar " + bar + "(must be: sacrificial health 10.0 bar 100 (flat, bar kept) | base health above 12.0 and bar below 100 (the bar paid part))");
		data.branches.put(AuraType.BLOOD, Branch.SACRIFICIAL);
		data.bloodBar = 0;
		p.setHealth(2f);
		boolean tooLow = com.toast.aurapoints.ability.Blood.sacrifice(p, server.getTickCount() + 100000);
		log("Sacrifice at 1 heart: fired " + tooLow + " (must be false, never kills you), health " + p.getHealth() + " (2.0)");
		p.setHealth(p.getMaxHealth());
		p.removeAllEffects();

		data.aura = wasAura;
		data.branches.clear();
		data.bloodBar = wasBar;
		data.mastery.clear();
		data.mastery.putAll(wasMastery);
		log("branch3 done (state restored)");
	}

	/** /auradev tome: the Balance Tome's Branches item, the branch pages and every branch's values (5.14.0). */
	private static void tome(net.minecraft.server.MinecraftServer server) {
		ServerLevel level = server.overworld();
		net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b4a9"), "Tomer"));
		var entries = com.toast.aurapoints.config.AuraBalance.entries();
		int missing = 0, stray = 0, branchValues = 0;
		StringBuilder per = new StringBuilder();
		for (Branch b : Branch.values()) {
			String g = com.toast.aurapoints.config.AuraBalance.BRANCH_GROUP + b.name();
			long n = entries.stream().filter(e -> e.group().equals(g)).count();
			branchValues += n;
			if (b != Branch.DARK && n == 0) missing++;
			per.append(b.id()).append('=').append(n).append(' ');
			// every config key starting with one of the branch's prefixes must be in that group
			for (var e : entries) {
				if (e.isSwitch()) continue;
				for (String pre : b.configPrefixes()) if (e.key().startsWith(pre) && !e.group().equals(g)) stray++;
			}
		}
		log("tome branch values per branch: " + per + "(each non-Dark one > 0; Dark 0 because it shows the Void kit), branches without values " + missing + " (0), keys outside their branch group " + stray + " (0), total " + branchValues);
		long general = entries.stream().filter(e -> e.group().equals("BRANCH_")).count();
		log("general Branches group: " + general + " values (must be 3: BRANCHES_ENABLED, BRANCH_MASTERY_REQUIRED, BRANCH_SWITCH_COST) on page " + entries.stream().filter(e -> e.group().equals("BRANCH_")).map(e -> e.page()).distinct().toList() + " (general)");
		for (String section : new String[]{"fire", "shadow", "frost", "verdant", "earth", "light", "blood", "water", "thunder", "wind", "resonance", "void", "general"}) {
			log("tome " + com.toast.aurapoints.editor.BalanceEditor.devBranchPages(p, section));
		}
		var sample = entries.stream().filter(e -> e.group().equals(com.toast.aurapoints.config.AuraBalance.BRANCH_GROUP + "SACRIFICIAL")).map(e -> e.key()).toList();
		log("Sacrificial keys: " + sample);
	}

	/** /auradev segments: dragon segment item displays get a longer view range, other displays and bigger datapack values are left alone. */
	private static void segments(net.minecraft.server.MinecraftServer server) {
		ServerLevel level = server.overworld();
		var spawn = level.getSharedSpawnPos();
		Vec3 base = new Vec3(spawn.getX() + 0.5, 120, spawn.getZ() + 0.5);
		var items = new net.minecraft.world.item.ItemStack[]{new net.minecraft.world.item.ItemStack(AuraPoints.DRAGON_SEGMENT_A.get()), new net.minecraft.world.item.ItemStack(AuraPoints.DRAGON_SEGMENT_B.get()),
				new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE), new net.minecraft.world.item.ItemStack(AuraPoints.DRAGON_SEGMENT_A.get()), net.minecraft.world.item.ItemStack.EMPTY};
		net.minecraft.world.entity.Display.ItemDisplay[] d = new net.minecraft.world.entity.Display.ItemDisplay[items.length];
		for (int i = 0; i < items.length; i++) {
			d[i] = new net.minecraft.world.entity.Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
			d[i].setPos(base.x + i * 2, base.y, base.z);
			if (i < 4) ((com.toast.aurapoints.mixin.ItemDisplayAccessor) d[i]).aurapoints$setItemStack(items[i]);
			if (i == 3) ((com.toast.aurapoints.mixin.DisplayAccessor) d[i]).aurapoints$setViewRange(4.0f); // a datapack's own bigger value
			level.addFreshEntity(d[i]);
		}
		// the segment item arrives after the spawn, like a datapack's data modify
		((com.toast.aurapoints.mixin.ItemDisplayAccessor) d[4]).aurapoints$setItemStack(new net.minecraft.world.item.ItemStack(AuraPoints.DRAGON_SEGMENT_B.get()));
		AuraFx.later(server, 40, () -> {
			StringBuilder sb = new StringBuilder();
			for (var e : d) sb.append(((com.toast.aurapoints.mixin.DisplayAccessor) e).aurapoints$getViewRange()).append(' ');
			log("segment view ranges: " + sb + "(must be 2.5 2.5 1.0 4.0 2.5: segments raised, a stone display untouched, a datapack's 4.0 kept, a late item caught)");
			for (var e : d) e.discard();
		});
	}

	/** /auradev branch4: the 5.13.0 branches (Water, Thunder, Wind, Resonance). */
	private static void lastEight(net.minecraft.server.MinecraftServer server) {
		ServerLevel level = server.overworld();
		var spawn = level.getSharedSpawnPos();
		Vec3 base = new Vec3(spawn.getX() + 0.5, 120, spawn.getZ() + 0.5);
		net.minecraft.server.level.ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b4a7"), "Stormer"));
		net.minecraft.server.level.ServerPlayer friend = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000b4a8"), "Pal"));
		var state = com.toast.aurapoints.data.AuraState.get(server);
		var data = state.getOrCreate(p.getUUID());
		var wasAura = data.aura;
		var wasMastery = new java.util.EnumMap<AuraType, Integer>(AuraType.class);
		wasMastery.putAll(data.mastery);
		for (AuraType a : new AuraType[]{AuraType.WATER, AuraType.THUNDER, AuraType.WIND, AuraType.RESONANCE}) data.mastery.put(a, 5);
		data.branches.clear();
		long tick = server.getTickCount();

		// ---- Water
		data.aura = AuraType.WATER;
		StringBuilder water = new StringBuilder();
		for (Branch b : new Branch[]{null, Branch.TIDECALLER, Branch.LEVIATHAN}) {
			if (b == null) data.branches.remove(AuraType.WATER);
			else data.branches.put(AuraType.WATER, b);
			water.append(b == null ? "base" : b.id()).append(": iron ").append(AuraAbilities.ironSkinReduction(p)).append(" heal amp ").append(AuraAbilities.healingAmplifier(p))
					.append(" bubble cd ").append(Branch.cooldown(p, AuraAbilities.Ability.ABYSSAL_BUBBLE, AuraConfig.BUBBLE_COOLDOWN_TICKS)).append(" | ");
		}
		log("Water " + water);
		log("(must be: base iron 0.2 heal amp 1 bubble cd 900 | tidecaller iron 0.1 heal amp 2 bubble cd 1200 | leviathan iron 0.2 heal amp 0 bubble cd 900)");
		data.branches.put(AuraType.WATER, Branch.LEVIATHAN);
		StringBuilder hits = new StringBuilder();
		for (int i = 0; i < 7; i++) hits.append(AuraAbilities.applyIronSkinReduction(p, 10f)).append(i < 6 ? "/" : "");
		log("Leviathan stacks, ten damage seven times: " + hits + " (must be 8.0/7.5/7.0/6.5/6.0/5.5/5.5), stacks " + AuraAbilities.ironStacks(p) + " (5)");
		data.branches.put(AuraType.WATER, Branch.TIDECALLER);
		log("Tidecaller hit: " + AuraAbilities.applyIronSkinReduction(p, 10f) + " (must be 9.0)");
		net.minecraft.world.entity.animal.Pig soaked = pig(level, p.position().add(3, 0, 0)); // NeoForge fake players sit where they spawned, so measure from them
		boolean before = com.toast.aurapoints.ability.Reactions.marked(soaked, com.toast.aurapoints.ability.Reactions.Mark.WET);
		AuraAbilities.tryHealingTide(p, tick + 100000);
		log("Tidecaller Healing Tide: pig Wet " + before + " -> " + com.toast.aurapoints.ability.Reactions.marked(soaked, com.toast.aurapoints.ability.Reactions.Mark.WET) + " (must be false -> true)");
		soaked.discard();

		// ---- Thunder
		data.aura = AuraType.THUNDER;
		StringBuilder thunder = new StringBuilder();
		for (Branch b : new Branch[]{null, Branch.SURGE, Branch.CONDUCTOR}) {
			if (b == null) data.branches.remove(AuraType.THUNDER);
			else data.branches.put(AuraType.THUNDER, b);
			thunder.append(b == null ? "base" : b.id()).append(": bolt cd ").append(Branch.cooldown(p, AuraAbilities.Ability.THUNDERBOLT, AuraConfig.THUNDERBOLT_COOLDOWN_TICKS))
					.append(" chidori cd ").append(Branch.cooldown(p, AuraAbilities.Ability.CHIDORI, AuraConfig.CHIDORI_COOLDOWN_TICKS)).append(" chidori charge ").append(com.toast.aurapoints.ability.Chidori.chargeTicks(p))
					.append(" arcs ").append(com.toast.aurapoints.ability.Thunderbolt.staticArcs(p)).append(" chain r ").append(com.toast.aurapoints.ability.Thunderbolt.chainRadius(p)).append(" | ");
		}
		log("Thunder " + thunder);
		log("(must be: base bolt cd 400 chidori cd 280 chidori charge 30 arcs 2 chain r 5.0 | surge bolt cd 280 chidori cd 360 chidori charge 20 arcs 1 chain r 5.0 | conductor bolt cd 480 chidori cd 280 chidori charge 30 arcs 4 chain r 8.0)");
		data.branches.put(AuraType.THUNDER, Branch.CONDUCTOR);
		net.minecraft.world.entity.animal.Pig v = pig(level, base.add(10, 0, 12));
		net.minecraft.world.entity.animal.Pig n1 = pig(level, base.add(12, 0, 12)), n2 = pig(level, base.add(10, 0, 14)), n3 = pig(level, base.add(8, 0, 12)), nFar = pig(level, base.add(10, 0, 22));
		for (var q : new net.minecraft.world.entity.animal.Pig[]{v, n1, n2, n3, nFar}) q.invulnerableTime = 0;
		int unchargedSpread = com.toast.aurapoints.ability.Thunderbolt.spread(p, v, 10f);
		com.toast.aurapoints.ability.Thunderbolt.charge(v, 2);
		com.toast.aurapoints.ability.Thunderbolt.charge(n1, 1); // charged neighbours must not chain the spread on
		com.toast.aurapoints.ability.Thunderbolt.charge(n2, 1);
		int reached = com.toast.aurapoints.ability.Thunderbolt.spread(p, v, 10f);
		double a1 = 200 - n1.getHealth(), a2 = 200 - n2.getHealth(), a3 = 200 - n3.getHealth(), af = 200 - nFar.getHealth();
		log("Conductor spread: uncharged victim reached " + unchargedSpread + " (must be 0), charged reached " + reached + " (must be 3), damage " + a1 + "/" + a2 + "/" + a3 + " (each exactly "
				+ (10 * AuraConfig.THUNDER_CONDUCTOR_SPREAD_FRACTION) + ": no second spread from the charged neighbours), far pig " + af + " (0), the victim itself " + (200 - v.getHealth()) + " (0)");
		data.branches.put(AuraType.THUNDER, Branch.SURGE);
		log("Surge does not spread: " + com.toast.aurapoints.ability.Thunderbolt.spread(p, v, 10f) + " (must be 0)");
		for (var q : new net.minecraft.world.entity.animal.Pig[]{v, n1, n2, n3, nFar}) q.discard();

		// ---- Wind
		data.aura = AuraType.WIND;
		data.branches.put(AuraType.WIND, Branch.SKYRUNNER);
		boolean u1 = AuraAbilities.useCharge(p, AuraAbilities.Ability.DASH, 1000, 200), u2 = AuraAbilities.useCharge(p, AuraAbilities.Ability.DASH, 1010, 200),
				u3 = AuraAbilities.useCharge(p, AuraAbilities.Ability.DASH, 1020, 200), u4 = AuraAbilities.useCharge(p, AuraAbilities.Ability.DASH, 1250, 200);
		log("Skyrunner charges (use at 1000, 1010, 1020, 1250): free " + u1 + "/" + u2 + "/" + u3 + "/" + u4 + " (must be true/false/true/true: first free, second starts the cooldown, a new first, and it refunds after the cooldown)");
		boolean other = AuraAbilities.useCharge(p, AuraAbilities.Ability.DOUBLE_JUMP, 1000, 60) | AuraAbilities.firstChargeSpent(p, AuraAbilities.Ability.DOUBLE_JUMP) == false;
		log("charges are per ability: Double Jump first use free " + AuraAbilities.firstChargeSpent(p, AuraAbilities.Ability.DOUBLE_JUMP) + " (spent, true) while Dash holds " + AuraAbilities.firstChargeSpent(p, AuraAbilities.Ability.DASH) + " (true)" + (other ? "" : ""));
		data.branches.put(AuraType.WIND, Branch.CYCLONE);
		log("Cyclone: Dash charges " + AuraAbilities.useCharge(p, AuraAbilities.Ability.DASH, 5000, 200) + " (must be false, only Skyrunner has them), Dash cd "
				+ Branch.cooldown(p, AuraAbilities.Ability.DASH, AuraConfig.DASH_COOLDOWN_TICKS) + " (280) Double Jump cd " + Branch.cooldown(p, AuraAbilities.Ability.DOUBLE_JUMP, AuraConfig.DOUBLE_JUMP_COOLDOWN_TICKS)
				+ " (100) storm x" + com.toast.aurapoints.ability.Reactions.stormDamage(p, 4f) / 4f + " (1.5)");
		data.branches.put(AuraType.WIND, Branch.SKYRUNNER);
		log("Skyrunner Super Wind Charge cd " + Branch.cooldown(p, AuraAbilities.Ability.SUPER_WIND_CHARGE, AuraConfig.SUPER_WIND_CHARGE_COOLDOWN_TICKS) + " (560), storm x" + com.toast.aurapoints.ability.Reactions.stormDamage(p, 4f) / 4f + " (1)");
		AuraAbilities.markGlide(p, tick - 40);
		boolean linger = AuraAbilities.galeActive(p);
		AuraAbilities.markGlide(p, tick - 200);
		boolean gone = AuraAbilities.galeActive(p);
		data.branches.put(AuraType.WIND, Branch.CYCLONE);
		AuraAbilities.markGlide(p, tick - 40);
		log("Gale Rider linger (Skyrunner): 2 s after gliding " + linger + " (true), 10 s after " + gone + " (false), Cyclone 2 s after " + AuraAbilities.galeActive(p) + " (false)");
		Vec3 centre = new Vec3(0, 0, 0), thing = new Vec3(3, 0, 0), dir = new Vec3(1, 0, 0);
		Vec3 out = com.toast.aurapoints.ability.AirBending.blastPush(thing, centre, dir, 1.0, false), in = com.toast.aurapoints.ability.AirBending.blastPush(thing, centre, dir, 1.0, true),
				onTop = com.toast.aurapoints.ability.AirBending.blastPush(centre, centre, dir, 1.0, true);
		log("blast push at 3 blocks: outward x " + out.x + " (must be > 0), Cyclone inward x " + in.x + " (must be < 0 and never more than 2.4 so it cannot overshoot), at the centre x " + onTop.x + " (0), both lift " + out.y + "/" + in.y + " (equal, > 0)");

		// ---- Resonance
		data.aura = AuraType.RESONANCE;
		StringBuilder res = new StringBuilder();
		for (Branch b : new Branch[]{null, Branch.DIRGE, Branch.CHORUS}) {
			if (b == null) data.branches.remove(AuraType.RESONANCE);
			else data.branches.put(AuraType.RESONANCE, b);
			res.append(b == null ? "base" : b.id()).append(": dart ").append(com.toast.aurapoints.ability.Resonance.dartDamage(p)).append(" tree ").append(com.toast.aurapoints.ability.Resonance.treeTicks(p))
					.append(" blossoms ").append(com.toast.aurapoints.ability.Resonance.blossomMax(p)).append(" grasp cd ").append(Branch.cooldown(p, AuraAbilities.Ability.ABYSSAL_GRASP, AuraConfig.GRASP_COOLDOWN_TICKS)).append(" | ");
		}
		log("Resonance " + res);
		log("(must be: base dart 2.0 tree 1200 blossoms 3 grasp cd 500 | dirge dart 3.5 tree 800 blossoms 3 grasp cd 600 | chorus dart 1.5 tree 1800 blossoms 5 grasp cd 640)");
		net.minecraft.world.entity.animal.Pig quiet = pig(level, base.add(14, 0, 0));
		boolean wasLoud = com.toast.aurapoints.ability.Noise.isLoud(quiet);
		com.toast.aurapoints.ability.Noise.deafen(quiet, 80);
		log("Dirge deafen: loud " + wasLoud + " -> " + com.toast.aurapoints.ability.Noise.isLoud(quiet) + " (must be false -> true), noise " + com.toast.aurapoints.ability.Noise.get(quiet) + " (>= " + AuraConfig.NOISE_DEAF_LEVEL + "), deaf " + com.toast.aurapoints.ability.Noise.isDeaf(quiet) + " (true)");
		data.branches.put(AuraType.RESONANCE, Branch.CHORUS);
		boolean friendHeard = com.toast.aurapoints.ability.Resonance.hears(p, friend, false), pigHeard = com.toast.aurapoints.ability.Resonance.hears(p, quiet, false);
		data.branches.put(AuraType.RESONANCE, Branch.DIRGE);
		boolean dirgeFriend = com.toast.aurapoints.ability.Resonance.hears(p, friend, false);
		log("Chorus Echolocation: friend heard " + friendHeard + " (true), a mob that is loud anyway " + pigHeard + " (true here because of the deafen above); Dirge friend heard " + dirgeFriend + " (false)");
		quiet.discard();

		AuraFx.later(server, 130, () -> {
			log("Leviathan stacks after 6.5 s without a hit: " + AuraAbilities.ironStacks(p) + " (must be 0)");
			data.aura = wasAura;
			data.branches.clear();
			data.mastery.clear();
			data.mastery.putAll(wasMastery);
			log("branch4 done (state restored)");
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
		log("Water: blocker '" + Branch.blocker(data) + "' (null: Water has branches since 5.13.0), held Fire pick ignored: " + Branch.current(p) + " (must be null)");
		var noAura = data.aura;
		data.aura = null;
		log("No aura: blocker '" + Branch.blocker(data) + "' (must say you have no aura yet)");
		data.aura = noAura;
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
