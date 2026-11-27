package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Mage;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * DEV ONLY (aurapoints.devtest): /auradev mage - the Mage aura (5.62.0) on a fake player: mana spend and refusal, the cast
 * delay, the wand rule, the held bolt and orb draining mana, regen, Focus, mana levels and the save round trip.
 * Logs [magetest] lines with "(must be X)". Cooldowns are switched on for the run (off = free spells, no XP).
 * Not covered (fake players aren't in the player list): wisps, the Last Prism; see the aura-playtest skill for those.
 */
final class MageDevTest {

	private MageDevTest() {
	}

	private static final UUID ID = UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c7a2");

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("mage").executes(ctx -> {
			run(ctx.getSource().getServer());
			return 1;
		})));
	}

	private static void run(MinecraftServer server) {
		ServerLevel level = server.overworld();
		ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(ID, "Apprentice"));
		var spawn = level.getSharedSpawnPos();
		p.moveTo(spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, 0, 10);
		AuraState state = AuraState.get(server);
		AuraPlayerData data = state.getOrCreate(p.getUUID());
		boolean cooldownsBefore = state.cooldownsDisabled;
		state.cooldownsDisabled = false;
		try {
			data.aura = AuraType.MAGE;
			data.owned.addAll(AuraType.MAGE.abilities);
			data.mageLevel = 1;
			data.mageXp = 0;
			data.mageMana = -1;
			data.mageWandGiven = true;
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AuraPoints.APPRENTICE_WAND.get()));
			long now = server.getTickCount();
			int max = Mage.maxMana(data);
			log("a new Mage's pool: " + fmt(Mage.mana(p)) + " of " + max + " (must be " + AuraConfig.MAGE_MANA_BASE + " of " + AuraConfig.MAGE_MANA_BASE + ")");
			log("Mage abilities have no cooldown: bolt " + Ability.ARCANE_BOLT.cooldownTicks() + " prism " + Ability.LAST_PRISM.cooldownTicks() + " (must be 0 0)");

			// ---- Barrier: spends its cost, shields
			Mage.devReady(p);
			boolean barrier = AuraAbilities.tryActivate(p, Ability.ARCANE_BARRIER, now);
			log("barrier fired " + barrier + ", mana " + fmt(Mage.mana(p)) + ", absorption " + fmt(p.getAbsorptionAmount())
					+ " (must be true, " + fmt(max - AuraConfig.MAGE_BARRIER_COST) + ", >= " + fmt(AuraConfig.MAGE_BARRIER_ABSORB) + ")");

			// ---- the cast delay: a second press the same tick doesn't fire
			boolean again = AuraAbilities.tryActivate(p, Ability.ARCANE_BOLT, now);
			log("a bolt the same tick fired " + again + " (must be false: cast delay)");

			// ---- a tapped bolt
			Mage.devReady(p);
			double before = Mage.mana(p);
			boolean bolt = AuraAbilities.tryActivate(p, Ability.ARCANE_BOLT, now);
			Mage.boltRelease(p, now);
			log("tapped bolt fired " + bolt + ", spent " + fmt(before - Mage.mana(p)) + ", still held " + Mage.holdingBolt(p)
					+ " (must be true, " + fmt(AuraConfig.MAGE_BOLT_COST) + ", false)");

			// ---- not enough mana
			Mage.devSetMana(p, AuraConfig.MAGE_BOLT_COST - 1);
			Mage.devReady(p);
			boolean poor = AuraAbilities.tryActivate(p, Ability.ARCANE_BOLT, now);
			log("bolt with " + fmt(Mage.mana(p)) + " mana fired " + poor + " (must be false), mana untouched " + fmt(Mage.mana(p))
					+ " (must be " + fmt(AuraConfig.MAGE_BOLT_COST - 1) + ")");

			// ---- the held bolt drains while charging
			Mage.devSetMana(p, max);
			Mage.devReady(p);
			AuraAbilities.tryActivate(p, Ability.ARCANE_BOLT, now);
			int held = AuraConfig.MAGE_BOLT_HOLD_TICKS + 5;
			for (int i = 1; i <= held; i++) Mage.devTick(p, now + i);
			double drained = max - AuraConfig.MAGE_BOLT_COST - Mage.mana(p);
			double want = AuraConfig.MAGE_BOLT_DRAIN * held;
			log("held bolt after " + held + " ticks: holding " + Mage.holdingBolt(p) + ", drained " + fmt(drained)
					+ " (must be true, " + fmt(want) + ")");
			Mage.boltRelease(p, now + held);
			log("released: holding " + Mage.holdingBolt(p) + " (must be false)");

			// ---- the orb grows and drains, then flies
			Mage.devSetMana(p, max);
			Mage.devReady(p);
			boolean orb = AuraAbilities.tryActivate(p, Ability.ARCANE_ORB, now);
			for (int i = 1; i <= 20; i++) Mage.devTick(p, now + i);
			double orbSpent = max - Mage.mana(p);
			log("orb fired " + orb + ", holding " + Mage.holdingOrb(p) + ", spent " + fmt(orbSpent) + " (must be true, true, "
					+ fmt(AuraConfig.MAGE_ORB_COST + AuraConfig.MAGE_ORB_DRAIN * 20) + ")");
			Mage.orbRelease(p, now + 20);
			log("orb released: holding " + Mage.holdingOrb(p) + " (must be false)");

			// ---- the Arcane Ray: a tap, then a hold that drains
			Mage.devSetMana(p, max);
			Mage.devReady(p);
			boolean ray = AuraAbilities.tryActivate(p, Ability.ARCANE_RAY, now);
			Mage.rayRelease(p, now);
			log("tapped ray fired " + ray + ", spent " + fmt(max - Mage.mana(p)) + ", held " + Mage.holdingRay(p) + " (must be true, "
					+ fmt(AuraConfig.MAGE_RAY_COST) + ", false)");
			Mage.devSetMana(p, max);
			Mage.devReady(p);
			AuraAbilities.tryActivate(p, Ability.ARCANE_RAY, now);
			int rayHeld = AuraConfig.MAGE_RAY_HOLD_TICKS + 10;
			for (int i = 1; i <= rayHeld; i++) Mage.devTick(p, now + i);
			log("held ray after " + rayHeld + " ticks: holding " + Mage.holdingRay(p) + ", drained " + fmt(max - AuraConfig.MAGE_RAY_COST - Mage.mana(p))
					+ " (must be true, " + fmt(AuraConfig.MAGE_RAY_DRAIN * (rayHeld - AuraConfig.MAGE_RAY_HOLD_TICKS + 1)) + ")");
			Mage.rayRelease(p, now + rayHeld);
			log("ray released: holding " + Mage.holdingRay(p) + " (must be false)");
			log("Arcane Bolt is the staff's right-click: key slots " + AuraType.MAGE.keySlots() + " (must be [ARCANE_RAY, ARCANE_BARRIER, ARCANE_ORB]), bolt owned free "
					+ AuraAbilities.hasAbility(data, Ability.ARCANE_BOLT) + " (must be true)");

			// ---- no wand, no spell
			p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			Mage.devReady(p);
			log("bolt without the wand fired " + AuraAbilities.tryActivate(p, Ability.ARCANE_BOLT, now) + " (must be false)");
			p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AuraPoints.APPRENTICE_WAND.get()));

			// ---- regen: a second after the delay
			Mage.devSetMana(p, 0);
			Mage.devForgetSpend(p);
			p.setShiftKeyDown(false);
			for (int i = 0; i < 20; i++) Mage.tickPassives(p, data, now + 1000 + i);
			log("regen in 1 s: " + fmt(Mage.mana(p)) + " (must be " + fmt(Mage.regenPerSecond(data)) + ")");
			// ---- Focus: sneaking, faster and with XP (no spend just before, so its short wait is over)
			Mage.devSetMana(p, 0);
			Mage.devForgetSpend(p);
			double xpBefore = data.mageXp;
			p.setShiftKeyDown(true);
			for (int i = 0; i < 20; i++) Mage.tickPassives(p, data, now + 2000 + i);
			p.setShiftKeyDown(false);
			log("focus in 1 s: " + fmt(Mage.mana(p)) + " (must be " + fmt(Mage.regenPerSecond(data) * AuraConfig.MAGE_FOCUS_REGEN_FACTOR)
					+ "), focus XP " + fmt(data.mageXp - xpBefore) + " (must be " + fmt(AuraConfig.MAGE_FOCUS_XP_PER_SECOND) + ")");

			// ---- a level up
			data.mageXp = 0;
			Mage.devAddXp(p, Mage.xpFor(1) + 0.5);
			log("after " + fmt(Mage.xpFor(1) + 0.5) + " XP: level " + data.mageLevel + ", pool " + Mage.maxMana(data) + ", XP left " + fmt(data.mageXp)
					+ " (must be 2, " + (AuraConfig.MAGE_MANA_BASE + AuraConfig.MAGE_MANA_PER_LEVEL) + ", 0.5)");

			// ---- saved and loaded
			Mage.devSetMana(p, 12.5);
			AuraPlayerData back = AuraPlayerData.readNbt(data.writeNbt());
			log("save round trip: mana " + fmt(back.mageMana) + " level " + back.mageLevel + " xp " + fmt(back.mageXp) + " wand " + back.mageWandGiven
					+ " (must be 12.5 2 0.5 true)");
		} catch (Exception ex) {
			AuraPoints.LOGGER.error("[magetest] failed", ex);
		} finally {
			state.cooldownsDisabled = cooldownsBefore;
			Mage.onLogout(p);
			data.aura = null;
			data.owned.clear();
			log("magetest done");
		}
	}

	private static String fmt(double v) {
		return String.format(java.util.Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", "");
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[magetest] {}", what);
	}
}
