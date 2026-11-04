package com.toast.aurapoints.ability;

import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Aura Abilities. PASSIVE ones are always on, ACTIVE ones the player
 * triggers. Nobody buys them one by one any more: a player has exactly the
 * abilities of their rolled {@link AuraType}. Each is a small, self-contained
 * effect, deliberately separate from the Puffish skill trees.
 */
public final class AuraAbilities {

	private AuraAbilities() {
	}

	public enum Ability {
		// ---- passive ----
		LIFESTEAL("Vein of Aura", false, () ->
				"Heal " + Math.round(AuraConfig.LIFESTEAL_FRACTION * 100) + "% of damage you deal to mobs (not players)."),
		BERSERKER("Berserker's Aura", false, () ->
				"Deal up to " + Math.round((AuraConfig.BERSERKER_MAX_DAMAGE_MULTIPLIER - 1.0) * 100)
						+ "% bonus damage the lower your health."),
		BLOODRUSH("Bloodrush", false, () ->
				"Gain Speed " + roman(AuraConfig.BLOODRUSH_AMPLIFIER + 1) + " and Haste " + roman(AuraConfig.BLOODRUSH_AMPLIFIER + 1)
						+ " for " + (AuraConfig.BLOODRUSH_DURATION_TICKS / 20)
						+ "s after every player kill."),
		IRON_SKIN("Iron Skin", false, () ->
				"Take " + Math.round(AuraConfig.IRON_SKIN_DAMAGE_REDUCTION * 100) + "% less damage from all sources, magic included."),
		EXECUTIONER("Executioner", false, () ->
				"Deal " + Math.round((AuraConfig.EXECUTIONER_DAMAGE_MULTIPLIER - 1.0) * 100)
						+ "% bonus damage to targets below "
						+ Math.round(AuraConfig.EXECUTIONER_HP_THRESHOLD_FRACTION * 100) + "% health."),
		GALE_RIDER("Gale Rider", false, () ->
				"Deal " + Math.round((AuraConfig.GALE_RIDER_DAMAGE_MULTIPLIER - 1.0f) * 100)
						+ "% more damage while gliding with an elytra."),
		// Phantom Step has no key or cooldown - it triggers on landing - so it counts as a passive.
		PHANTOM_STEP("Phantom Step", false, () ->
				"Sneak while falling to take no fall damage and vanish completely for a moment on landing."),
		// Vanish Step rides on Phantom Step: no key or cooldown either.
		VANISH_STEP("Vanish Step", false, () ->
				"Land a Phantom Step while vanished to stay vanished " + (AuraConfig.VANISH_STEP_EXTEND_TICKS / 20)
						+ "s longer. No cooldown, so every landing adds more."),
		FEATHERFALL("Featherfall", false, () ->
				"Take " + Math.round((1.0f - AuraConfig.FEATHERFALL_DAMAGE_MULTIPLIER) * 100)
						+ "% less fall damage. Sneak in mid-air to drift down slowly."),
		FIREBORN("Fireborn", false, () ->
				"Fire can't hurt you: no burning, no fire, campfire, magma block or fireball damage. Only lava still burns"
						+ (AuraConfig.FIREBORN_LAVA_SIGHT > 0 ? ", but you see " + Math.round(AuraConfig.FIREBORN_LAVA_SIGHT) + " blocks through it." : ".")),
		FLOW("Flow", false, () ->
				"In water or rain: Regeneration, water breathing and faster swimming. You also deal "
						+ Math.round(AuraConfig.FLOW_RAIN_DAMAGE_BONUS * 100) + "% more damage and your cooldowns are "
						+ Math.round(AuraConfig.FLOW_RAIN_COOLDOWN_REDUCTION * 100) + "% shorter in the rain - "
						+ Math.round(AuraConfig.FLOW_WATER_DAMAGE_BONUS * 100) + "% and " + Math.round(AuraConfig.FLOW_WATER_COOLDOWN_REDUCTION * 100)
						+ "% in water."),
		SURE_FOOTED("Sure-Footed", false, () ->
				"Ice is never slippery for you: you walk and run on it like on stone."),
		ICE_SHELL("Ice Shell", false, () ->
				"A shell of " + AuraConfig.ICE_SHELL_LAYERS + " ice layers (" + fmt(AuraConfig.ICE_SHELL_LAYER_HP / 2f) + " hearts each, after armor) "
						+ "takes " + Math.round(AuraConfig.ICE_SHELL_ABSORB_FRACTION * 100) + "% of every hit for you, layer by layer. Once you stop getting hit, cracks mend in seconds and a broken layer grows back in "
						+ (AuraConfig.ICE_SHELL_REBUILD_TICKS / 20) + "s (" + fmt(AuraConfig.ICE_SHELL_COLD_REBUILD_TICKS / 20f)
						+ "s somewhere cold). Every hit you land on a player speeds it up."),
		SHATTER("Shatter", false, () ->
				"Land " + AuraConfig.SHATTER_HITS + " charged hits in a row quickly (how quick depends on your weapon) - "
						+ "no misses, no switching items - and the last one shatters for " + fmt(AuraConfig.SHATTER_BONUS_DAMAGE) + " extra damage."),
		BLOOM("Bloom", false, () ->
				"While you sneak, crops, saplings and other plants within " + AuraConfig.BLOOM_RADIUS
						+ " blocks grow as if bone-mealed. Switch it off and on with /aura bloom."),
		STATIC_CHARGE("Static Charge", false, () ->
				"Your charged melee hits build static: every " + AuraConfig.STATIC_CHARGE_HITS + "th one discharges - "
						+ fmt(AuraConfig.STATIC_CHARGE_DAMAGE) + " damage that arcs on to up to " + AuraConfig.STATIC_CHARGE_ARC_TARGETS
						+ " enemies nearby, and leaves them Charged for your Wrath of Zeus."),
		STORMBORN("Stormborn", false, () ->
				"Lightning can't hurt you. In a thunderstorm your lightning hits " + Math.round(AuraConfig.STORMBORN_DAMAGE_BONUS * 100)
						+ "% harder, Thunderbolt recharges " + Math.round(AuraConfig.STORMBORN_COOLDOWN_REDUCTION * 100)
						+ "% faster and Wrath of Zeus calls down " + AuraConfig.STORMBORN_EXTRA_BOLTS + " more bolts."),
		ECHOLOCATION("Echolocation", false, () ->
				"Your eyes are dim: a black-violet haze swallows everything past " + Math.round(AuraConfig.ECHO_FOG_END) + " blocks. But you hear: every player and mob within "
						+ Math.round(AuraConfig.ECHO_RANGE) + " blocks that makes a sound - walking, sprinting, jumping, building, fighting, drawing a bow, charging an ability - glows for you, "
						+ "even through walls. Only sneaking or standing completely still keeps them hidden."),
		STONE_SKIN("Stone Skin", false, () ->
				"While you stand on stone, dirt, sand or gravel, your skin turns to rock: +" + fmt((float) AuraConfig.EARTH_STONE_SKIN_ARMOR) + " armor and +"
						+ fmt((float) AuraConfig.EARTH_STONE_SKIN_TOUGHNESS) + " armor toughness."),
		GROUNDED("Grounded", false, () ->
				"Hard to move: " + Math.round(AuraConfig.EARTH_GROUNDED_KNOCKBACK_RESISTANCE * 100) + "% knockback resistance, and you take "
						+ Math.round(AuraConfig.EARTH_GROUNDED_FALL_REDUCTION * 100) + "% less fall damage."),
		HALO("Halo", false, () ->
				"Out in the sunlight you heal " + fmt(AuraConfig.LIGHT_HALO_HEAL / 2f) + " heart" + (AuraConfig.LIGHT_HALO_HEAL == 2f ? "" : "s") + " every "
						+ fmt(AuraConfig.LIGHT_HALO_HEAL_TICKS / 20f) + "s."),
		LUMINOUS("Luminous", false, () ->
				"You see in the dark, and every " + fmt(AuraConfig.LIGHT_LUMINOUS_PULSE_TICKS / 20f) + "s the players and monsters within "
						+ Math.round(AuraConfig.LIGHT_LUMINOUS_RADIUS) + " blocks light up for a moment, even behind walls."),
		BLOODLUST("Bloodlust", false, () ->
				"Every kill heals you " + fmt(AuraConfig.BLOOD_LUST_HEAL / 2f) + " hearts."),
		THIN_BLOOD("Thin Blood", false, () ->
				"Below " + Math.round(AuraConfig.BLOOD_THIN_THRESHOLD * 100) + "% health you run faster (Speed " + roman(AuraConfig.BLOOD_THIN_SPEED_AMPLIFIER + 1) + ")."),
		EVENT_HORIZON("Event Horizon", false, () ->
				"Arrows, tridents and other projectiles that come within " + Math.round(AuraConfig.VOID_HORIZON_RADIUS) + " blocks of you slow to a crawl."),
		ENTROPY("Entropy", false, () ->
				"Everything you hit loses " + fmt((float) AuraConfig.VOID_ENTROPY_ARMOR) + " armor for " + fmt(AuraConfig.VOID_ENTROPY_TICKS / 20f) + "s."),
		NIGHTSTALKER("Nightstalker", false, () ->
				(AuraConfig.SHADOW_NIGHTSTALKER_NIGHT_VISION ? "You always see in the dark (/aura nightvision switches it). " : "")
						+ "The darker it is, the harder you hit: up to +" + Math.round(AuraConfig.SHADOW_NIGHTSTALKER_DAMAGE_PERCENT * 100)
						+ "% melee damage in full darkness, less with every light level, none above " + AuraConfig.SHADOW_NIGHTSTALKER_MAX_LIGHT + "."),
		// ---- active ----
		SONIC_BLOSSOM("Sonic Blossom", true, () ->
				"Grab the ground: a moment later a hollow blossom grows right below you (up to " + AuraConfig.BLOSSOM_MAX + " at once, " + (AuraConfig.BLOSSOM_TICKS / 20) + "s each). It listens: "
						+ "anyone sprinting or louder within " + Math.round(AuraConfig.BLOSSOM_RANGE) + " blocks gets a dart every " + fmt(AuraConfig.BLOSSOM_SHOT_TICKS / 20f) + "s ("
						+ fmt(AuraConfig.BLOSSOM_DART_DAMAGE) + " damage), and whoever it hits glows for you for " + fmt(AuraConfig.ECHO_MARK_TICKS / 20f)
						+ "s, even sneaking. " + (AuraConfig.BLOSSOM_COOLDOWN_TICKS / 20) + "s cooldown."),
		LISTENING_TREE("Listening Tree", true, () ->
				"Plant a tree with a curled tip where you look (up to " + AuraConfig.LISTENING_TREE_MAX + " at once, " + (AuraConfig.LISTENING_TREE_TICKS / 20) + "s each). "
						+ "It grabs the loudest one near it (within " + Math.round(AuraConfig.LISTENING_TREE_RANGE) + " blocks) and drags them in: for "
						+ fmt(AuraConfig.LISTENING_TREE_HOLD_TICKS / 20f) + "s they can't get more than " + fmt((float) AuraConfig.LISTENING_TREE_LEASH)
						+ " blocks away from it - and every step away is harder. They can still fight. " + AuraConfig.LISTENING_TREE_HITS + " hits from someone else break it. " + (AuraConfig.LISTENING_TREE_COOLDOWN_TICKS / 20) + "s cooldown."),
		ABYSSAL_GRASP("Abyssal Grasp", true, () ->
				"Hold your key to wind your four tentacles back (you slow down and your view narrows): the longer you hold, the further they reach - "
						+ Math.round(AuraConfig.GRASP_MIN_RANGE) + " blocks at once, up to " + Math.round(AuraConfig.GRASP_RANGE) + " after " + fmt(AuraConfig.GRASP_CHARGE_TICKS / 20f)
						+ "s. Let go and they lash out where you point, seize the first mob or player ("
						+ fmt(AuraConfig.GRASP_DAMAGE) + " damage) and whip them back to you. You hold them in front of you - they can't move, but they can still fight - "
						+ "until you press again (after " + fmt(AuraConfig.GRASP_THROW_DELAY_TICKS / 20f) + "s) and throw them where you look (" + fmt(AuraConfig.GRASP_HOLD_TICKS / 20f) + "s at most, then they're thrown anyway). "
						+ "Thrown into a wall: " + fmt(AuraConfig.GRASP_WALL_DAMAGE) + " more. " + (AuraConfig.GRASP_COOLDOWN_TICKS / 20) + "s cooldown ("
						+ (AuraConfig.GRASP_MISS_COOLDOWN_TICKS / 20) + "s after a miss)."),
		PILLAR_LIFT("Pillar Lift", true, () ->
				"A stone pillar bursts out of the ground under whoever you aim at (up to " + Math.round(AuraConfig.EARTH_PILLAR_RANGE) + " blocks) and throws them high into the air ("
						+ fmt(AuraConfig.EARTH_PILLAR_DAMAGE) + " damage). Aim at nobody and it shoots up under you instead and launches you - no fall damage. "
						+ (AuraConfig.EARTH_PILLAR_COOLDOWN_TICKS / 20) + "s cooldown."),
		BOULDER_TOSS("Boulder Toss", true, () ->
				"Tear a boulder out of the ground: it hangs over your head (you walk slower) until you press again - or after "
						+ fmt(AuraConfig.EARTH_BOULDER_HOLD_TICKS / 20f) + "s - and hurl it where you look: " + fmt(AuraConfig.EARTH_BOULDER_DAMAGE)
						+ " damage and a heavy knockback to the first mob or player it hits, " + fmt(AuraConfig.EARTH_BOULDER_SPLASH_DAMAGE)
						+ " to anyone close by. " + (AuraConfig.EARTH_BOULDER_COOLDOWN_TICKS / 20) + "s cooldown."),
		QUAKE("Quake", true, () ->
				"Slam the ground: a ripple of heaving earth rolls out " + Math.round(AuraConfig.EARTH_QUAKE_RADIUS) + " blocks - "
						+ fmt(AuraConfig.EARTH_QUAKE_DAMAGE) + " damage, enemies thrown up and slowed (Slowness " + roman(AuraConfig.EARTH_QUAKE_SLOW_AMPLIFIER + 1)
						+ ", " + fmt(AuraConfig.EARTH_QUAKE_SLOW_TICKS / 20f) + "s). " + (AuraConfig.EARTH_QUAKE_COOLDOWN_TICKS / 20) + "s cooldown."),
		RADIANT_PULSE("Radiant Pulse", true, () ->
				"A wave of warm light rolls out " + Math.round(AuraConfig.LIGHT_PULSE_RADIUS) + " blocks: you and the players in it heal (in the Aura Arena only you) "
						+ fmt(AuraConfig.LIGHT_PULSE_HEAL / 2f) + " hearts and regenerate for " + fmt(AuraConfig.LIGHT_PULSE_REGEN_TICKS / 20f)
						+ "s, and the undead in it burn (" + fmt(AuraConfig.LIGHT_PULSE_UNDEAD_DAMAGE) + " damage). "
						+ (AuraConfig.LIGHT_PULSE_COOLDOWN_TICKS / 20) + "s cooldown."),
		SOLAR_FLASH("Solar Flash", true, () ->
				"A blinding flash from your palm: everyone in front of you (up to " + Math.round(AuraConfig.LIGHT_FLASH_RANGE) + " blocks) who can see it is blinded for "
						+ fmt(AuraConfig.LIGHT_FLASH_BLIND_TICKS / 20f) + "s and glows for " + fmt(AuraConfig.LIGHT_FLASH_GLOW_TICKS / 20f) + "s ("
						+ fmt(AuraConfig.LIGHT_FLASH_DAMAGE) + " damage), and monsters lose sight of you. " + (AuraConfig.LIGHT_FLASH_COOLDOWN_TICKS / 20) + "s cooldown."),
		PRISM_BEAM("Prism Beam", true, () ->
				"Hold your key: a beam of pure light streams from your hand to your crosshair (up to " + Math.round(AuraConfig.LIGHT_BEAM_RANGE) + " blocks), burning the first "
						+ "mob or player in it - " + fmt(AuraConfig.LIGHT_BEAM_DAMAGE) + " damage every " + fmt(AuraConfig.LIGHT_BEAM_HIT_TICKS / 20f)
						+ "s, more on the undead. You walk slower while you hold it, " + fmt(AuraConfig.LIGHT_BEAM_MAX_TICKS / 20f) + "s at most. "
						+ (AuraConfig.LIGHT_BEAM_COOLDOWN_TICKS / 20) + "s cooldown after."),
		HEMORRHAGE("Hemorrhage", true, () ->
				"Lunge where you look and cut the first mob or player you reach: " + fmt(AuraConfig.BLOOD_HEMO_DAMAGE) + " damage, and they bleed for "
						+ fmt(AuraConfig.BLOOD_BLEED_TICKS / 20f) + "s (" + fmt(AuraConfig.BLOOD_BLEED_DAMAGE) + " every " + fmt(AuraConfig.BLOOD_BLEED_EVERY_TICKS / 20f) + "s). "
						+ (AuraConfig.BLOOD_HEMO_COOLDOWN_TICKS / 20) + "s cooldown."),
		BLOOD_TETHER("Blood Tether", true, () ->
				"Bind the mob or player in your sights (up to " + Math.round(AuraConfig.BLOOD_TETHER_RANGE) + " blocks) with a thread of blood for "
						+ fmt(AuraConfig.BLOOD_TETHER_TICKS / 20f) + "s: every " + fmt(AuraConfig.BLOOD_TETHER_EVERY_TICKS / 20f) + "s it drains "
						+ fmt(AuraConfig.BLOOD_TETHER_DRAIN) + " health from them into you. It snaps if they get too far or out of sight. "
						+ (AuraConfig.BLOOD_TETHER_COOLDOWN_TICKS / 20) + "s cooldown."),
		CRIMSON_SACRIFICE("Crimson Sacrifice", true, () ->
				"Give " + fmt(AuraConfig.BLOOD_SACRIFICE_COST / 2f) + " hearts of your own blood: your next " + AuraConfig.BLOOD_SACRIFICE_HITS + " melee hits (within "
						+ fmt(AuraConfig.BLOOD_SACRIFICE_TICKS / 20f) + "s) deal " + fmt((float) AuraConfig.BLOOD_SACRIFICE_MULTIPLIER) + "x damage. Up to "
						+ AuraConfig.BLOOD_BAR_SACRIFICE_SPEND + " blood from your blood bar pays for it: with that much it costs only "
						+ fmt((float) (AuraConfig.BLOOD_SACRIFICE_COST * (1 - AuraConfig.BLOOD_SACRIFICE_MAX_REDUCTION)) / 2f) + " hearts. "
						+ (AuraConfig.BLOOD_SACRIFICE_COOLDOWN_TICKS / 20) + "s cooldown."),
		VOID_PULL("Void Pull", true, () ->
				"The void opens in your palm: every mob and player in front of you (up to " + Math.round(AuraConfig.VOID_PULL_RANGE) + " blocks) is dragged right up to you ("
						+ fmt(AuraConfig.VOID_PULL_DAMAGE) + " damage). " + (AuraConfig.VOID_PULL_COOLDOWN_TICKS / 20) + "s cooldown."),
		RIFT_STEP("Rift Step", true, () ->
				"Step through a tear in space up to " + Math.round(AuraConfig.VOID_RIFT_RANGE) + " blocks where you look. The rift stays open behind you for "
						+ fmt(AuraConfig.VOID_RIFT_TICKS / 20f) + "s and hurts whoever touches it (" + fmt(AuraConfig.VOID_RIFT_DAMAGE) + ", slowed). "
						+ (AuraConfig.VOID_RIFT_COOLDOWN_TICKS / 20) + "s cooldown."),
		NULL_ORB("Null Orb", true, () ->
				"A slow orb of nothing drifts where you look: " + fmt(AuraConfig.VOID_ORB_DAMAGE) + " damage to what it hits, and a player it hits can't use their aura for "
						+ fmt(AuraConfig.VOID_ORB_NULL_TICKS / 20f) + "s (mobs are weakened). " + (AuraConfig.VOID_ORB_COOLDOWN_TICKS / 20) + "s cooldown."),
		CHIDORI("Chidori", true, () ->
				"Hold your key: lightning gathers in your hand, chirping like a thousand birds (you walk slower; full in "
						+ fmt(AuraConfig.CHIDORI_CHARGE_TICKS / 20f) + "s). Let go and you lunge where you look - no turning once you go - up to "
						+ fmt((float) AuraConfig.CHIDORI_MAX_DISTANCE) + " blocks, and run the first mob or player through: up to "
						+ fmt(AuraConfig.CHIDORI_MAX_DAMAGE) + " damage, paralyzed, and Charged twice for your Wrath of Zeus. Let go too early and it fizzles. "
						+ (AuraConfig.CHIDORI_COOLDOWN_TICKS / 20) + "s cooldown."),
		THUNDERBOLT("Thunderbolt of Zeus", true, () ->
				"Three throws per use. Press: you leap into the air and hang there, hurling a bolt of lightning at your crosshair (up to "
						+ Math.round(AuraConfig.THUNDERBOLT_RANGE) + " blocks): " + fmt(AuraConfig.THUNDERBOLT_DAMAGE) + " damage, arcing on to "
						+ AuraConfig.THUNDERBOLT_CHAIN_TARGETS + " more enemies nearby. Press again: a quick bolt (" + fmt(AuraConfig.THUNDERBOLT_QUICK_DAMAGE)
						+ "). Press a third time: Wrath of Zeus - a storm gathers where you aim and you call it down: real lightning, up to "
						+ fmt(AuraConfig.THUNDERBOLT_WRATH_DAMAGE) + " damage within " + fmt((float) AuraConfig.THUNDERBOLT_WRATH_RADIUS)
						+ " blocks, enemies thrown and paralyzed, and more bolts raining around it. Every bolt hit leaves its target Charged: Wrath deals "
						+ fmt(AuraConfig.THUNDERBOLT_OVERLOAD_DAMAGE) + " more per charge and paralyzes longer. You can't move while you hang in the air. Wait over "
						+ fmt(AuraConfig.THUNDERBOLT_COMBO_TICKS / 20f) + "s between throws and it ends. " + (AuraConfig.THUNDERBOLT_COOLDOWN_TICKS / 20) + "s cooldown."),
		VINE_HOOK("Vine Hook", true, () ->
				"Press your key and two roots burst from the ground and reach for your crosshair, up to " + Math.round(AuraConfig.VINE_HOOK_RANGE) + " blocks: they catch the first mob or player, "
						+ (AuraConfig.VINE_HOOK_DAMAGE > 0 ? "deal " + fmt(AuraConfig.VINE_HOOK_DAMAGE) + " damage and " : "")
						+ "drag them right to you - straight through any wall. "
						+ (AuraConfig.VINE_HOOK_ENTANGLE_LOCK_TICKS > 0 ? "After a catch, Entanglement needs " + fmt(AuraConfig.VINE_HOOK_ENTANGLE_LOCK_TICKS / 20f) + "s before it can be used. " : "")
						+ (AuraConfig.VINE_HOOK_COOLDOWN_TICKS / 20) + "s cooldown."),
		ENTANGLEMENT("Entanglement", true, () ->
				"A root trap. Press to tear root arms out of the ground where you look, up to " + Math.round(AuraConfig.ENTANGLE_RANGE)
						+ " blocks away (aimed at someone, under their feet). While they rise (" + fmt(AuraConfig.ENTANGLE_TRAP_ARM_TICKS / 20f)
						+ "s) it's only a warning; then they thrash about, waiting. Whoever stays in reach for " + fmt(AuraConfig.ENTANGLE_TRAP_STAY_TICKS / 20f)
						+ "s is seized by every arm, yanked into the middle and wound up in them - they can't move until they break free, by mashing "
						+ "W A S D or cutting the 3 green arms, whichever the roots pick. Every hit they take loosens the roots, and from hit number "
						+ AuraConfig.ENTANGLE_HIT_CHANCE_FROM + " on, each one has a " + Math.round(AuraConfig.ENTANGLE_HIT_FREE_CHANCE * 100) + "% chance to tear them free. If nobody is caught within " + (AuraConfig.ENTANGLE_TRAP_TICKS / 20)
						+ "s, the arms pump out a cloud of Poison " + roman(AuraConfig.ENTANGLE_POISON_AMPLIFIER + 1) + " before they sink. One trap at a time. "
						+ (AuraConfig.ENTANGLE_COOLDOWN_TICKS / 20) + "s cooldown."),
		VERDANT_VIPER("Verdant Viper", true, () ->
				"Press to send out a viper of roots that slithers toward your crosshair (up to " + Math.round(AuraConfig.VIPER_RANGE) + " blocks) and bites the first "
						+ "mob or player it reaches: " + fmt(AuraConfig.VIPER_DAMAGE) + " damage and Poison " + roman(AuraConfig.VIPER_POISON_AMPLIFIER + 1)
						+ " for " + (AuraConfig.VIPER_POISON_TICKS / 20) + "s. While it's out you can't move, eat or heal - but nothing can hurt you. "
						+ "Other players can kill it: then you're stunned (longer the sooner it died) and take " + fmt(AuraConfig.VIPER_KILL_DAMAGE / 2f)
						+ " hearts. Press again after " + (AuraConfig.VIPER_CANCEL_AFTER_TICKS / 20) + "s to call it back. "
						+ (AuraConfig.VIPER_COOLDOWN_TICKS / 20) + "s cooldown."),
		ICE_SPIKE("Ice Spike", true, () ->
				"Hold your Ice Spike key to form a spike of ice, let go to throw it: up to " + fmt(AuraConfig.ICE_SPIKE_DAMAGE_MAX)
						+ " damage, and it slows what it hits. Fully charged (" + fmt(AuraConfig.ICE_SPIKE_FULL_CHARGE_TICKS / 20f) + "s), it freezes them solid for "
						+ fmt(AuraConfig.ICE_SPIKE_FREEZE_TICKS / 20f) + "s"
						+ (AuraConfig.ICE_SPIKE_FREEZE_BREAK_ON_HIT ? ", or until they get hit - which breaks them free with a short burst of speed" : "")
						+ ". A hit gives you Speed " + roman(AuraConfig.ICE_SPIKE_HOLDER_SPEED_AMPLIFIER + 1)
						+ " to catch up. " + (AuraConfig.ICE_SPIKE_COOLDOWN_TICKS / 20) + "s cooldown."),
		SHOCKWAVE("Shockwave", true, () ->
				"Press your Shockwave key to blast enemies within " + (int) AuraConfig.SHOCKWAVE_RADIUS
						+ " blocks away. The closer they are, the harder it hits. " + (AuraConfig.SHOCKWAVE_COOLDOWN_TICKS / 20) + "s cooldown."),
		VANISH("Vanish", true, () ->
				"Press your Vanish key to turn completely invisible (armor too) with Speed " + roman(AuraConfig.VANISH_SPEED_AMPLIFIER + 1)
						+ " for " + (AuraConfig.VANISH_DURATION_TICKS / 20)
						+ "s. You leave a black trail on the floor. " + (AuraConfig.VANISH_COOLDOWN_TICKS / 20) + "s cooldown."),
		DASH("Dash", true, () ->
				"Press your Dash key to burst forward. " + (AuraConfig.DASH_COOLDOWN_TICKS / 20) + "s cooldown. "
						+ "Wind aura only: dash up and press it again while " + (int) AuraConfig.DASH_DIVE_MIN_HEIGHT + "+ blocks high to dive down: "
						+ "no fall damage, and a shockwave in front of you on landing: " + fmt(AuraConfig.DASH_DIVE_SHOCKWAVE_DAMAGE)
						+ " damage, more the higher you dove from (up to " + fmt(AuraConfig.DASH_DIVE_DAMAGE_MAX) + "). Dash then needs "
						+ (AuraConfig.DASH_DIVE_COOLDOWN_TICKS / 20) + "s to recharge. "
						+ (AuraConfig.WIND_LAUNCH_ENABLED ? "Wind aura only: hold the key to blast yourself high into the air and shove everything within "
								+ (int) AuraConfig.WIND_LAUNCH_RADIUS + " blocks away, on its own " + (AuraConfig.WIND_LAUNCH_COOLDOWN_TICKS / 20) + "s cooldown"
								+ (AuraConfig.WIND_LAUNCH_ALLOWS_DIVE ? " (tap Dash up there to dive back down)" : "") + ". " : "")
						+ "While gliding with an elytra it is a weak boost forward instead"
						+ (AuraConfig.WIND_LAUNCH_ENABLED ? " (Wind: hold it for a big one, " + (AuraConfig.WIND_LAUNCH_ELYTRA_COOLDOWN_TICKS / 20) + "s)" : "")
						+ ", on its own " + (AuraConfig.DASH_ELYTRA_COOLDOWN_TICKS / 20) + "s cooldown (separate from the ground Dash)."),
		DOUBLE_JUMP("Double Jump", true, () ->
				"Press jump again in mid-air to jump a second time. Once per landing, "
						+ (AuraConfig.DOUBLE_JUMP_COOLDOWN_TICKS / 20) + "s cooldown. "
						+ "While gliding with an elytra, pressing jump lifts you up a little instead, on its own "
						+ (AuraConfig.DOUBLE_JUMP_ELYTRA_COOLDOWN_TICKS / 20) + "s cooldown (separate from the ground jump)."),
		SUPER_WIND_CHARGE("Super Wind Charge", true, () ->
				"Press your Super Wind Charge key to fire a wind charge with "
						+ Math.round(AuraConfig.SUPER_WIND_CHARGE_POWER) + "x the power. No wind charges needed. "
						+ (AuraConfig.SUPER_WIND_CHARGE_COOLDOWN_TICKS / 20) + "s cooldown."
						+ (AuraConfig.AIR_BENDING_ENABLED ? " Air Bending: hold the key instead to gather a ball of wind in front of you. "
								+ "Swing it around to charge it (" + Math.round(AuraConfig.AIR_BENDING_READY_DEGREES) + "° to be ready, "
								+ Math.round(AuraConfig.AIR_BENDING_FULL_DEGREES) + "° for full power), then press again to fire it: where it hits, "
								+ "everything within " + (int) AuraConfig.AIR_BENDING_BLAST_RADIUS + " blocks is hurled far away and high up. "
								+ (AuraConfig.AIR_BENDING_COOLDOWN_TICKS / 20) + "s cooldown." : "")),
		HEALING_TIDE("Healing Tide", true, () ->
				"Press your Healing Tide key to give you and every friendly player (in the arena: your teammates) within " + (int) AuraConfig.HEALING_TIDE_RADIUS
						+ " blocks Regeneration " + roman(AuraConfig.HEALING_TIDE_AMPLIFIER + 1) + " for " + (AuraConfig.HEALING_TIDE_DURATION_TICKS / 20) + "s"
						+ (AuraConfig.HEALING_TIDE_CLEANSE ? " and wash away every negative effect" : "") + ". "
						+ (AuraConfig.HEALING_TIDE_RESISTANCE_DURATION_TICKS > 0 && AuraConfig.HEALING_TIDE_RESISTANCE_AMPLIFIER >= 0
								? "You also get Resistance " + roman(AuraConfig.HEALING_TIDE_RESISTANCE_AMPLIFIER + 1) + " for "
								+ (AuraConfig.HEALING_TIDE_RESISTANCE_DURATION_TICKS / 20) + "s. " : "")
						+ (AuraConfig.HEALING_TIDE_COOLDOWN_TICKS / 20) + "s cooldown."),
		ABYSSAL_BUBBLE("Abyssal Bubble", true, () ->
				"Hold your Abyssal Bubble key: a " + (int) AuraConfig.BUBBLE_RADIUS + "-block bubble follows you (you walk slower) and "
						+ "players and mobs inside run out of air and drown, and get slowly dragged toward you"
						+ (AuraConfig.BUBBLE_RESISTANCE_AMPLIFIER >= 0 ? ", while you have Resistance " + roman(AuraConfig.BUBBLE_RESISTANCE_AMPLIFIER + 1) : "")
						+ ". Your camera shakes harder the longer you hold it - "
						+ "keep your crosshair within " + Math.round(AuraConfig.BUBBLE_BREAK_DEGREES) + "° of where you started, or the bubble breaks. "
						+ "Up to " + (AuraConfig.BUBBLE_MAX_HOLD_TICKS / 20) + "s, " + (AuraConfig.BUBBLE_COOLDOWN_TICKS / 20) + "s cooldown after."),
		FIREBALL("Fireball", true, () ->
				"Press your Fireball key to shoot a fireball that deals " + fmt(AuraConfig.FIREBALL_DAMAGE)
						+ " damage, sets its target ablaze and starts a few fires where it hits. "
						+ (AuraConfig.FIREBALL_COOLDOWN_TICKS / 20) + "s cooldown."),
		HEAT_WAVE("Heat Wave", true, () ->
				"Press your Heat Wave key to set everything within " + (int) AuraConfig.HEAT_WAVE_RADIUS
						+ " blocks on fire and blast it back. Fires break out where the wave ends, and under everyone it hit. "
							+ "Use it while holding a fully charged Fire Bending flame for the Inferno Slam: a far bigger blast, "
							+ Math.round(AuraConfig.INFERNO_SLAM_BURN_SECONDS) + "s of burning. " + (AuraConfig.HEAT_WAVE_COOLDOWN_TICKS / 20) + "s cooldown."),
		FIRE_BENDING("Fire Bending", true, () ->
				"Press your Fire Bending key to hold a ball of fire " + AuraAbilities.fmt((float) AuraConfig.FIRE_BENDING_HOLD_DISTANCE) + " blocks in front of you"
						+ (AuraConfig.FIRE_BENDING_CONTACT_CONSUMES ? " - it bursts on the first thing it touches. " : " - it burns whatever it touches. ")
						+ "Swing it around to charge it (" + Math.round(AuraConfig.FIRE_BENDING_READY_DEGREES) + "° to be ready, "
						+ Math.round(AuraConfig.FIRE_BENDING_FULL_DEGREES) + "° for full power)"
						+ (AuraConfig.FIRE_BENDING_FULL_SIDE ? " - fully charged, you hold it at your side so you can aim" : "")
						+ ", then press again to fire it at your crosshair, up to "
						+ Math.round(AuraConfig.FIRE_BENDING_RANGE) + " blocks: up to " + fmt(AuraConfig.FIRE_BENDING_DAMAGE_CLOSE)
						+ " damage up close, less the farther it flies. Fully charged, it shows you every mob and player in front of you, "
						+ "even through up to " + AuraConfig.FIRE_BENDING_HIGHLIGHT_MAX_WALL_BLOCKS + " blocks of wall. "
						+ (AuraConfig.FIRE_BENDING_COOLDOWN_TICKS / 20) + "s cooldown."),
		// ---- handheld weapons' abilities (ability/Handhelds): right-click with the weapon ----
		MAELSTROM("Maelstrom", true, () ->
				"Slam the Leviathan's Anchor down: kraken tentacles burst out of the ground within " + Math.round(AuraConfig.MAELSTROM_RADIUS)
						+ " blocks and drag every enemy into a whirlpool around you, slowed, for " + fmt(AuraConfig.MAELSTROM_PULL_TICKS / 20f)
						+ "s. Then a giant tentacle slams down on the middle: " + fmt(AuraConfig.MAELSTROM_DAMAGE) + " damage. "
						+ (AuraConfig.MAELSTROM_COOLDOWN_TICKS / 20) + "s cooldown."),
		SUNFALL("Sunfall", true, () ->
				"Point the Phoenix Fangs at a spot up to " + Math.round(AuraConfig.SUNFALL_RANGE) + " blocks away: the sky above it burns red, and "
						+ fmt(AuraConfig.SUNFALL_DELAY_TICKS / 20f) + "s later a meteor crashes down - up to " + fmt(AuraConfig.SUNFALL_DAMAGE)
						+ " damage within " + Math.round(AuraConfig.SUNFALL_RADIUS) + " blocks, everyone thrown and set ablaze, and a crater of lava and fire that burns for "
						+ fmt(AuraConfig.SUNFALL_CRATER_TICKS / 20f) + "s. " + (AuraConfig.SUNFALL_COOLDOWN_TICKS / 20) + "s cooldown."),
		EYE_OF_THE_STORM("Eye of the Storm", true, () ->
				"Hurl the Stormcaller Glaive: it becomes a tornado that sweeps ahead of you for " + fmt(AuraConfig.STORM_EYE_TICKS / 20f)
						+ "s, sucking in every enemy within " + Math.round(AuraConfig.STORM_EYE_PULL_RADIUS) + " blocks. Then it bursts - "
						+ fmt(AuraConfig.STORM_EYE_BLAST_DAMAGE) + " damage, everyone hurled far away - and the glaive flies back to you. "
						+ (AuraConfig.STORM_EYE_COOLDOWN_TICKS / 20) + "s cooldown."),
		UMBRAL_AMBUSH("Umbral Ambush", true, () ->
				"Become a shadow: you appear behind the nearest enemy within " + Math.round(AuraConfig.UMBRAL_RANGE) + " blocks, invisible for "
						+ fmt(AuraConfig.UMBRAL_INVIS_TICKS / 20f) + "s. A shadow clone stays where you stood and attacks enemies near it ("
						+ fmt(AuraConfig.UMBRAL_CLONE_DAMAGE) + " a hit, " + fmt(AuraConfig.UMBRAL_CLONE_TICKS / 20f) + "s), and your next hit deals "
						+ fmt(AuraConfig.UMBRAL_BONUS_DAMAGE) + " bonus damage. " + (AuraConfig.UMBRAL_COOLDOWN_TICKS / 20) + "s cooldown."),
		THORN_SENTINEL("Thorn Sentinel", true, () ->
				"Sneak and right-click with the World Tree Bow to shoot a seed: where it lands a thorn tree grows and for "
						+ fmt(AuraConfig.THORN_SENTINEL_TICKS / 20f) + "s shoots thorns at enemies within " + Math.round(AuraConfig.THORN_SENTINEL_REACH)
						+ " blocks (" + fmt(AuraConfig.THORN_SENTINEL_SHOT_DAMAGE) + " damage"
						+ (AuraConfig.THORN_SENTINEL_POISON_AMPLIFIER >= 0 ? " and a little poison" : "") + " each). "
						+ (AuraConfig.THORN_SENTINEL_COOLDOWN_TICKS / 20) + "s cooldown."),
		WINTERS_GATE("Winter's Gate", true, () ->
				"Raise a wall of spiked ice in front of you (" + AuraConfig.WINTERS_GATE_WIDTH + " wide, " + AuraConfig.WINTERS_GATE_HEIGHT
						+ " high): it stops projectiles, and whoever touches it freezes solid. After " + fmt(AuraConfig.WINTERS_GATE_TICKS / 20f)
						+ "s it shatters and the shards fly everywhere: " + fmt(AuraConfig.WINTERS_GATE_SHARD_DAMAGE) + " damage within "
						+ Math.round(AuraConfig.WINTERS_GATE_SHARD_RADIUS) + " blocks. " + (AuraConfig.WINTERS_GATE_COOLDOWN_TICKS / 20) + "s cooldown."),
		CHAIN_STRIKE("Chain Strike", true, () ->
				"Become pure lightning: you flash from enemy to enemy, up to " + AuraConfig.CHAIN_STRIKE_TARGETS + " of them ("
						+ fmt(AuraConfig.CHAIN_STRIKE_DAMAGE) + " damage each), starting with the one you aim at. On the last one a giant bolt strikes: "
						+ fmt(AuraConfig.CHAIN_STRIKE_FINAL_DAMAGE) + " more, and everyone near it paralyzed. "
						+ (AuraConfig.CHAIN_STRIKE_COOLDOWN_TICKS / 20) + "s cooldown."),
		// ---- Ultimates (ability/Ultimates): one per aura, unlocked by its Awakening, on the Ultimate key ----
		ECLIPSE("Eclipse", true, () ->
				"Darkness falls on every enemy player within " + Math.round(AuraConfig.ULT_ECLIPSE_RANGE) + " blocks. You vanish and blink behind up to "
						+ AuraConfig.ULT_ECLIPSE_TARGETS + " enemies in a row, striking each for " + fmt(AuraConfig.ULT_ECLIPSE_DAMAGE) + ". "
						+ AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown."),
		TIDAL_ASCENSION("Tidal Ascension", true, () ->
				"A column of water carries you high up - then you crash down and a ring wave rolls out " + Math.round(AuraConfig.ULT_TIDAL_RADIUS)
						+ " blocks: " + fmt(AuraConfig.ULT_TIDAL_DAMAGE) + " damage, everyone swept away, and you heal " + fmt(AuraConfig.ULT_TIDAL_HEAL / 2f)
						+ " hearts. " + AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown."),
		PHOENIX("Phoenix", true, () ->
				"Wreathed in flame you fly where you look for " + fmt(AuraConfig.ULT_PHOENIX_TICKS / 20f) + "s, burning whoever you pass ("
						+ fmt(AuraConfig.ULT_PHOENIX_TOUCH_DAMAGE) + "), then dive down in a blast: up to " + fmt(AuraConfig.ULT_PHOENIX_BLAST_DAMAGE)
						+ " damage within " + Math.round(AuraConfig.ULT_PHOENIX_BLAST_RADIUS) + " blocks. Sneak to dive early. "
						+ AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown."),
		TEMPEST("Tempest", true, () ->
				"You rise into the eye of a tornado for " + fmt(AuraConfig.ULT_TEMPEST_TICKS / 20f) + "s: it follows you, sucks in every enemy within "
						+ Math.round(AuraConfig.ULT_TEMPEST_RADIUS) + " blocks and whirls them round, then flings them all away ("
						+ fmt(AuraConfig.ULT_TEMPEST_FLING_DAMAGE) + "). " + AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown."),
		PERMAFROST("Permafrost", true, () ->
				"A freezing field spreads " + Math.round(AuraConfig.ULT_PERMAFROST_RADIUS) + " blocks around you: ice spikes erupt and every enemy freezes solid for "
						+ fmt(AuraConfig.ULT_PERMAFROST_FREEZE_TICKS / 20f) + "s - then it all shatters: " + fmt(AuraConfig.ULT_PERMAFROST_SHATTER_DAMAGE)
						+ " damage. " + AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown."),
		OVERGROWTH("Overgrowth", true, () ->
				"A great tree bursts up behind you for " + fmt(AuraConfig.ULT_OVERGROWTH_TICKS / 20f) + "s: its roots hold every enemy within "
						+ Math.round(AuraConfig.ULT_OVERGROWTH_RADIUS) + " blocks for " + fmt(AuraConfig.ULT_OVERGROWTH_ROOT_TICKS / 20f)
						+ "s (thorns hurt them), and its petals heal you near it. " + AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown."),
		JUDGEMENT("Judgement", true, () ->
				"You rise into the air and call down " + AuraConfig.ULT_JUDGEMENT_BOLTS + " lightning bolts on enemies within "
						+ Math.round(AuraConfig.ULT_JUDGEMENT_RANGE) + " blocks (" + fmt(AuraConfig.ULT_JUDGEMENT_BOLT_DAMAGE) + " each), then one giant bolt where you aim: "
						+ fmt(AuraConfig.ULT_JUDGEMENT_FINAL_DAMAGE) + " damage within " + Math.round(AuraConfig.ULT_JUDGEMENT_FINAL_RADIUS) + " blocks. "
						+ AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown."),
		TECTONIC_RIFT("Tectonic Rift", true, () ->
				"You stamp and the ground splits open ahead of you, " + Math.round(AuraConfig.EARTH_RIFT_LENGTH) + " blocks along where you look: jagged pillars erupt "
						+ "all along the crack and launch everyone on it (" + fmt(AuraConfig.EARTH_RIFT_DAMAGE) + " damage), then crumble away. "
						+ AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown."),
		SUNBURST("Sunburst", true, () ->
				"A small sun rises over you and burns for " + fmt(AuraConfig.LIGHT_SUN_TICKS / 20f) + "s: it spears lances of light at the enemies within "
						+ Math.round(AuraConfig.LIGHT_SUN_RANGE) + " blocks (" + fmt(AuraConfig.LIGHT_SUN_LANCE_DAMAGE) + " each) and warms every player near it back to health - "
						+ "then it bursts: " + fmt(AuraConfig.LIGHT_SUN_BURST_DAMAGE) + " damage and blindness all around. " + AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown."),
		BLOOD_MOON("Blood Moon", true, () ->
				"Needs " + AuraConfig.BLOOD_BAR_MOON_COST + " blood in your blood bar (of " + AuraConfig.BLOOD_BAR_MAX + "): every second your bleeding cuts fill it - "
						+ AuraConfig.BLOOD_BAR_PLAYER + " on players, " + AuraConfig.BLOOD_BAR_MONSTER + " on monsters, " + AuraConfig.BLOOD_BAR_MOB
						+ " on animals; dying empties it. The sky turns red for everyone near you for " + fmt(AuraConfig.BLOOD_MOON_TICKS / 20f) + "s: every hit you land heals you "
						+ Math.round(AuraConfig.BLOOD_MOON_LIFESTEAL * 100) + "% of its damage, and every enemy within " + Math.round(AuraConfig.BLOOD_MOON_RADIUS)
						+ " blocks bleeds. " + AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown."),
		SINGULARITY("Singularity", true, () ->
				"A black hole opens where you aim: for " + fmt(AuraConfig.VOID_HOLE_TICKS / 20f) + "s it drags in every enemy within " + Math.round(AuraConfig.VOID_HOLE_RADIUS)
						+ " blocks and grinds them near its heart - then it collapses: " + fmt(AuraConfig.VOID_HOLE_BLAST_DAMAGE) + " damage and everyone flung away. "
						+ AuraConfig.ULTIMATE_COOLDOWN_SECONDS + "s cooldown.");

		public final String displayName;
		public final boolean active;
		/** Built on every call, so it always shows the current (hot-reloaded) numbers. */
		private final java.util.function.Supplier<String> description;

		Ability(String displayName, boolean active, java.util.function.Supplier<String> description) {
			this.displayName = displayName;
			this.active = active;
			this.description = description;
		}

		public String description() {
			return description.get();
		}

		/** Cooldown in ticks (0 for passives and abilities without one). */
		public int cooldownTicks() {
			return switch (this) {
				case SHOCKWAVE -> AuraConfig.SHOCKWAVE_COOLDOWN_TICKS;
				case VANISH -> AuraConfig.VANISH_COOLDOWN_TICKS;
				case DASH -> AuraConfig.DASH_COOLDOWN_TICKS;
				case DOUBLE_JUMP -> AuraConfig.DOUBLE_JUMP_COOLDOWN_TICKS;
				case SUPER_WIND_CHARGE -> AuraConfig.SUPER_WIND_CHARGE_COOLDOWN_TICKS;
				case HEALING_TIDE -> AuraConfig.HEALING_TIDE_COOLDOWN_TICKS;
				case FIREBALL -> AuraConfig.FIREBALL_COOLDOWN_TICKS;
				case HEAT_WAVE -> AuraConfig.HEAT_WAVE_COOLDOWN_TICKS;
				case FIRE_BENDING -> AuraConfig.FIRE_BENDING_COOLDOWN_TICKS;
				case ABYSSAL_BUBBLE -> AuraConfig.BUBBLE_COOLDOWN_TICKS;
				case ICE_SPIKE -> AuraConfig.ICE_SPIKE_COOLDOWN_TICKS;
				case VINE_HOOK -> AuraConfig.VINE_HOOK_COOLDOWN_TICKS;
				case ENTANGLEMENT -> AuraConfig.ENTANGLE_COOLDOWN_TICKS;
				case VERDANT_VIPER -> AuraConfig.VIPER_COOLDOWN_TICKS;
				case THUNDERBOLT -> AuraConfig.THUNDERBOLT_COOLDOWN_TICKS;
				case CHIDORI -> AuraConfig.CHIDORI_COOLDOWN_TICKS;
				case SONIC_BLOSSOM -> AuraConfig.BLOSSOM_COOLDOWN_TICKS;
				case LISTENING_TREE -> AuraConfig.LISTENING_TREE_COOLDOWN_TICKS;
				case ABYSSAL_GRASP -> AuraConfig.GRASP_COOLDOWN_TICKS;
				case MAELSTROM -> AuraConfig.MAELSTROM_COOLDOWN_TICKS;
				case SUNFALL -> AuraConfig.SUNFALL_COOLDOWN_TICKS;
				case EYE_OF_THE_STORM -> AuraConfig.STORM_EYE_COOLDOWN_TICKS;
				case UMBRAL_AMBUSH -> AuraConfig.UMBRAL_COOLDOWN_TICKS;
				case THORN_SENTINEL -> AuraConfig.THORN_SENTINEL_COOLDOWN_TICKS;
				case WINTERS_GATE -> AuraConfig.WINTERS_GATE_COOLDOWN_TICKS;
				case CHAIN_STRIKE -> AuraConfig.CHAIN_STRIKE_COOLDOWN_TICKS;
				case PILLAR_LIFT -> AuraConfig.EARTH_PILLAR_COOLDOWN_TICKS;
				case BOULDER_TOSS -> AuraConfig.EARTH_BOULDER_COOLDOWN_TICKS;
				case QUAKE -> AuraConfig.EARTH_QUAKE_COOLDOWN_TICKS;
				case RADIANT_PULSE -> AuraConfig.LIGHT_PULSE_COOLDOWN_TICKS;
				case SOLAR_FLASH -> AuraConfig.LIGHT_FLASH_COOLDOWN_TICKS;
				case PRISM_BEAM -> AuraConfig.LIGHT_BEAM_COOLDOWN_TICKS;
				case HEMORRHAGE -> AuraConfig.BLOOD_HEMO_COOLDOWN_TICKS;
				case BLOOD_TETHER -> AuraConfig.BLOOD_TETHER_COOLDOWN_TICKS;
				case CRIMSON_SACRIFICE -> AuraConfig.BLOOD_SACRIFICE_COOLDOWN_TICKS;
				case VOID_PULL -> AuraConfig.VOID_PULL_COOLDOWN_TICKS;
				case RIFT_STEP -> AuraConfig.VOID_RIFT_COOLDOWN_TICKS;
				case NULL_ORB -> AuraConfig.VOID_ORB_COOLDOWN_TICKS;
				case ECLIPSE, TIDAL_ASCENSION, PHOENIX, TEMPEST, PERMAFROST, OVERGROWTH, JUDGEMENT, TECTONIC_RIFT, SUNBURST, BLOOD_MOON, SINGULARITY ->
						AuraConfig.ULTIMATE_COOLDOWN_SECONDS * 20;
				default -> 0;
			};
		}

		/** True if this ability has its own rebindable key (Phantom Step uses sneak, Double Jump uses jump). */
		public boolean hasKey() {
			return KEY_ORDER.contains(this);
		}
	}

	/** 1 -> "I", 2 -> "II" ... for effect levels in descriptions. */
	static String roman(int n) {
		String[] r = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
	}

	/** 5.0 -> "5", 2.5 -> "2.5" for damage numbers in descriptions. */
	public static String fmt(float f) {
		return f == Math.round(f) ? String.valueOf(Math.round(f)) : String.valueOf(f);
	}

	/** The actives that are triggered by an "Ability N" key (the rest use jump or sneak). */
	public static final List<Ability> KEY_ORDER =
			List.of(Ability.SHOCKWAVE, Ability.VANISH, Ability.DASH, Ability.SUPER_WIND_CHARGE,
					Ability.HEALING_TIDE, Ability.FIREBALL, Ability.HEAT_WAVE, Ability.FIRE_BENDING, Ability.ABYSSAL_BUBBLE,
					Ability.ICE_SPIKE, Ability.VINE_HOOK, Ability.ENTANGLEMENT, Ability.VERDANT_VIPER,
					Ability.THUNDERBOLT, Ability.CHIDORI, Ability.SONIC_BLOSSOM, Ability.LISTENING_TREE, Ability.ABYSSAL_GRASP,
					Ability.PILLAR_LIFT, Ability.BOULDER_TOSS, Ability.QUAKE, Ability.RADIANT_PULSE, Ability.SOLAR_FLASH, Ability.PRISM_BEAM,
					Ability.HEMORRHAGE, Ability.BLOOD_TETHER, Ability.CRIMSON_SACRIFICE, Ability.VOID_PULL, Ability.RIFT_STEP, Ability.NULL_ORB);

	/** /aura lock all is on (mirror of AuraState.allLocked, set on server start and by the command). */
	public static boolean allLocked = false;

	/** Abilities switched off in the server config (ABILITIES_ENABLED in the aurapoints-*.json files). */
	public static final java.util.Set<Ability> DISABLED = java.util.EnumSet.noneOf(Ability.class);

	/**
	 * The aura has it, it isn't switched off, and - for ACTIVE abilities - the player bought
	 * it in /aura shop. Passives come free with the aura. Every ability check goes through here.
	 */
	public static boolean hasAbility(AuraPlayerData data, Ability ability) {
		// an Ultimate: the aura's own, and awakened
		if (Ultimates.auraOf(ability) != null) {
			return data.aura != null && !data.locked && !allLocked && Ultimates.of(data.aura) == ability && data.awakened.contains(data.aura)
					&& !DISABLED.contains(ability);
		}
		return data.aura != null && !data.locked && !allLocked && data.aura.has(ability) && !DISABLED.contains(ability)
				&& (!ability.active || data.owned.contains(ability));
	}

	// ---------------------------------------------------------------
	// Cooldowns. Kept in memory on purpose: server tick counters restart
	// from zero on every boot, so persisted timestamps would leave an
	// ability stuck on cooldown after a restart.
	// ---------------------------------------------------------------

	private static final Map<UUID, EnumMap<Ability, Long>> LAST_USE = new HashMap<>();
	/** A longer (or shorter) cooldown for the current use only, e.g. Dash after a Shadow Dive. */
	private static final Map<UUID, EnumMap<Ability, Integer>> LENGTH_OVERRIDE = new HashMap<>();

	private static long cooldownLeft(ServerPlayer player, Ability ability, int cooldownTicks, long now) {
		EnumMap<Ability, Long> map = LAST_USE.get(player.getUUID());
		if (map == null || !map.containsKey(ability)) return 0;
		long since = now - map.get(ability);
		if (since < 0) return 0; // tick counter went backwards (server restart) - treat as ready
		EnumMap<Ability, Integer> overrides = LENGTH_OVERRIDE.get(player.getUUID());
		if (overrides != null && overrides.containsKey(ability)) cooldownTicks = overrides.get(ability);
		return Math.max(0, cooldownTicks - since);
	}

	public static void markUsed(ServerPlayer player, Ability ability, long now) {
		com.toast.aurapoints.AuraHooks.abilityUsed(player, ability);
		LAST_USE.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(Ability.class)).put(ability, now);
		EnumMap<Ability, Integer> overrides = LENGTH_OVERRIDE.get(player.getUUID());
		if (overrides != null) overrides.remove(ability);
		int ticks = ability.cooldownTicks();
		int flowed = com.toast.aurapoints.arena.Waves.cooldown(player, flowCooldown(player, Mastery.cooldown(player, ticks)));
		if (flowed != ticks) LENGTH_OVERRIDE.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(Ability.class)).put(ability, flowed);
		if (!cooldownsDisabled(player)) NetworkHandler.sendCooldown(player, ability, flowed);
	}

	/** 0 out of the wet, else Flow's share for rain or water (0..1 for reductions). */
	private static double flowShare(ServerPlayer player, double rain, double water) {
		if (!player.isInWaterOrRain()) return 0;
		if (player.getServer() == null || !hasAbility(com.toast.aurapoints.data.AuraState.get(player.getServer()).getOrCreate(player.getUUID()), Ability.FLOW)) return 0;
		return Math.max(0.0, player.isInWater() ? water : rain);
	}

	/** A cooldown of {@code ticks}, shortened by Flow while in rain or water. */
	public static int flowCooldown(ServerPlayer player, int ticks) {
		double share = Math.min(1.0, flowShare(player, AuraConfig.FLOW_RAIN_COOLDOWN_REDUCTION, AuraConfig.FLOW_WATER_COOLDOWN_REDUCTION));
		return share <= 0 ? ticks : (int) Math.round(ticks * (1.0 - share));
	}

	/** Damage this Flow player deals, raised while they stand in rain or water. */
	public static float flowDamage(ServerPlayer attacker, float damage) {
		double bonus = flowShare(attacker, AuraConfig.FLOW_RAIN_DAMAGE_BONUS, AuraConfig.FLOW_WATER_DAMAGE_BONUS);
		return bonus <= 0 ? damage : (float) (damage * (1.0 + bonus));
	}

	/** Restarts an ability's cooldown now with a custom length (and tells the HUD). */
	public static void startCooldown(ServerPlayer player, Ability ability, int ticks, long now) {
		com.toast.aurapoints.AuraHooks.abilityUsed(player, ability);
		if (cooldownsDisabled(player)) return;
		ticks = com.toast.aurapoints.arena.Waves.cooldown(player, flowCooldown(player, Mastery.cooldown(player, ticks)));
		LAST_USE.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(Ability.class)).put(ability, now);
		LENGTH_OVERRIDE.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(Ability.class)).put(ability, ticks);
		NetworkHandler.sendCooldown(player, ability, ticks);
	}

	/**
	 * Makes sure {@code ability} is on cooldown for at least {@code ticks} more (a longer running one is kept).
	 * Not a use: no datapack hook, no Flow shortening.
	 */
	public static void atLeastCooldown(ServerPlayer player, Ability ability, int ticks, long now) {
		if (cooldownsDisabled(player) || ticks <= 0) return;
		if (cooldownLeft(player, ability, ability.cooldownTicks(), now) >= ticks) return;
		LAST_USE.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(Ability.class)).put(ability, now);
		LENGTH_OVERRIDE.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(Ability.class)).put(ability, ticks);
		NetworkHandler.sendCooldown(player, ability, ticks);
	}

	/** Ticks left on the ability's main cooldown (0 when ready or while cooldowns are off). */
	public static int cooldownTicksLeft(ServerPlayer player, Ability ability, long now) {
		if (cooldownsDisabled(player)) return 0;
		return (int) Math.min(Integer.MAX_VALUE, cooldownLeft(player, ability, ability.cooldownTicks(), now));
	}

	/**
	 * Puts the ability's main cooldown at exactly {@code ticks} from now (0 or less = ready) and tells the HUD.
	 * Not a use: no datapack hook, no Flow shortening. Used by the aura_cd_* scoreboards.
	 */
	public static void setCooldown(ServerPlayer player, Ability ability, int ticks, long now) {
		if (cooldownsDisabled(player)) return;
		if (ticks <= 0) {
			EnumMap<Ability, Long> uses = LAST_USE.get(player.getUUID());
			if (uses != null) uses.remove(ability);
			EnumMap<Ability, Integer> overrides = LENGTH_OVERRIDE.get(player.getUUID());
			if (overrides != null) overrides.remove(ability);
			NetworkHandler.sendCooldown(player, ability, 0);
			return;
		}
		LAST_USE.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(Ability.class)).put(ability, now);
		LENGTH_OVERRIDE.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(Ability.class)).put(ability, ticks);
		NetworkHandler.sendCooldown(player, ability, ticks);
	}

	/** True while /aura cooldowns off is in effect, or for this player in the arena's training grounds with their cooldowns off. */
	public static boolean cooldownsDisabled(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) return false;
		return com.toast.aurapoints.data.AuraState.get(server).cooldownsDisabled || com.toast.aurapoints.arena.Arena.freeCooldowns(player);
	}

	/** Nothing counts toward the Aura Trials, stunts or ability hooks: cooldowns off for everyone, or anywhere in the arena. */
	public static boolean progressPaused(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) return true;
		return com.toast.aurapoints.data.AuraState.get(server).cooldownsDisabled || com.toast.aurapoints.arena.Arena.in(player);
	}

	/** Forgets this player's running cooldowns (the caller re-syncs their HUD). */
	public static void clearCooldowns(ServerPlayer player) {
		LAST_USE.remove(player.getUUID());
		LENGTH_OVERRIDE.remove(player.getUUID());
		EXTRA.remove(player.getUUID());
	}

	/** Forgets every running cooldown (used when cooldowns are switched off or back on). */
	public static void clearAllCooldowns() {
		LAST_USE.clear();
		LENGTH_OVERRIDE.clear();
		EXTRA.clear();
	}

	/**
	 * Extra cooldowns an ability can have next to its main one, fully apart from it:
	 * FLIGHT - the flight set: Dash (tap and hold) and Double Jump while gliding with an elytra. Using them never touches the ground cooldowns, so a ground launch
	 *          can be followed by an elytra boost right away, and swapping the elytra resets nothing.
	 * LAUNCH - Wind's launch (hold Dash), so it doesn't lock the normal Dash.
	 */
	public enum Slot { FLIGHT, LAUNCH }

	/** player -> slot -> ability -> {start tick, length} */
	private static final Map<UUID, EnumMap<Slot, EnumMap<Ability, long[]>>> EXTRA = new HashMap<>();

	static boolean checkCooldown(ServerPlayer player, Ability ability, Slot slot, long now) {
		if (cooldownsDisabled(player)) return true;
		var slots = EXTRA.get(player.getUUID());
		long[] cd = slots == null || !slots.containsKey(slot) ? null : slots.get(slot).get(ability);
		if (cd == null || now < cd[0]) return true; // tick counter went backwards - treat as ready
		long left = cd[1] - (now - cd[0]);
		if (left > 0) {
			String what = slot == Slot.FLIGHT ? ability.displayName + " (flight)" : "Launch";
			player.displayClientMessage(
					Component.literal(String.format("%s on cooldown: %.1fs left", what, left / 20.0))
							.withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		return true;
	}

	static void startCooldown(ServerPlayer player, Ability ability, Slot slot, int ticks, long now) {
		com.toast.aurapoints.AuraHooks.abilityUsed(player, ability);
		if (cooldownsDisabled(player)) return;
		ticks = com.toast.aurapoints.arena.Waves.cooldown(player, ticks);
		EXTRA.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(Slot.class))
				.computeIfAbsent(slot, k -> new EnumMap<>(Ability.class)).put(ability, new long[]{now, ticks});
		NetworkHandler.sendCooldown(player, ability, slot, ticks);
	}

	public static boolean checkCooldown(ServerPlayer player, Ability ability, int cooldownTicks, long now) {
		if (cooldownsDisabled(player)) return true;
		long left = cooldownLeft(player, ability, cooldownTicks, now);
		if (left > 0) {
			player.displayClientMessage(
					Component.literal(String.format("%s on cooldown: %.1fs left", ability.displayName, left / 20.0))
							.withStyle(ChatFormatting.GRAY), true);
			return false;
		}
		return true;
	}

	/** Dispatches a key press to the matching active ability. Returns true if it fired. */
	public static boolean tryActivate(ServerPlayer player, Ability ability, long now) {
		// on the duel pads before "FIGHT!", or knocked out
		if (com.toast.aurapoints.arena.Duel.holdsStill(player)) return false;
		// steering a viper: the only key that works is the viper's own (to call it back); entangled: none
		if (VerdantViper.isSteering(player) && ability != Ability.VERDANT_VIPER) return false;
		if (Entanglement.isEntangled(player)) return false;
		// hit by a Null Orb: the aura is silent
		if (VoidAura.isNulled(player)) {
			player.displayClientMessage(Component.literal("Nulled - your aura is silent for a moment").withStyle(ChatFormatting.DARK_PURPLE), true);
			return false;
		}
		// a mastered aura's abilities go off with more particles
		float boost = AuraFx.boost;
		AuraFx.boost = Mastery.particleBoost(player);
		try {
			return activate(player, ability, now);
		} finally {
			AuraFx.boost = boost;
		}
	}

	private static boolean activate(ServerPlayer player, Ability ability, long now) {
		return switch (ability) {
			case SHOCKWAVE -> tryShockwave(player, now);
			case VANISH -> tryVanish(player, now);
			// a Dash press right after a Dash, high in the air, becomes the Shadow Dive instead
			case DASH -> DashDive.tryDive(player, now) || tryDash(player, now);
			case SUPER_WIND_CHARGE -> trySuperWindCharge(player, now);
			case HEALING_TIDE -> tryHealingTide(player, now);
			case FIREBALL -> tryFireball(player, now);
			case HEAT_WAVE -> tryHeatWave(player, now);
			case FIRE_BENDING -> FireBending.press(player, now);
			case ABYSSAL_BUBBLE -> AbyssalBubble.press(player, now);
			case ICE_SPIKE -> IceSpike.press(player, now);
			case VINE_HOOK -> VineHook.press(player, now);
			case ENTANGLEMENT -> Entanglement.press(player, now);
			case VERDANT_VIPER -> VerdantViper.press(player, now);
			case THUNDERBOLT -> Thunderbolt.press(player, now);
			case CHIDORI -> Chidori.press(player, now);
			case SONIC_BLOSSOM -> Resonance.plantBlossom(player, now);
			case LISTENING_TREE -> Resonance.plantTree(player, now);
			case ABYSSAL_GRASP -> Resonance.grasp(player, now);
			case PILLAR_LIFT -> Earth.pillar(player, now);
			case BOULDER_TOSS -> Earth.boulder(player, now);
			case QUAKE -> Earth.quake(player, now);
			case RADIANT_PULSE -> Light.pulse(player, now);
			case SOLAR_FLASH -> Light.flash(player, now);
			case PRISM_BEAM -> Light.beam(player, now);
			case HEMORRHAGE -> Blood.hemorrhage(player, now);
			case BLOOD_TETHER -> Blood.tether(player, now);
			case CRIMSON_SACRIFICE -> Blood.sacrifice(player, now);
			case VOID_PULL -> VoidAura.pull(player, now);
			case RIFT_STEP -> VoidAura.riftStep(player, now);
			case NULL_ORB -> VoidAura.nullOrb(player, now);
			default -> false;
		};
	}

	// ---------------------------------------------------------------
	// Passives
	// ---------------------------------------------------------------

	private static final int BLOOD_RED = 0xB0121B;
	/** Hot yellow embers for Heat Wave. */
	private static final int EMBER_YELLOW = 0xFFC23A;
	/** Shockwave's spray: hard and white-blue. */
	private static final int SPRAY_WHITE = 0xDDF4FF;
	/** Healing Tide: soft teal-green. */
	private static final int TIDE_TEAL = 0x3EE6B0;
	private static final int IRON_GREY = 0xC8CDD2;

	/** Mobs only: hitting another player never heals. */
	public static void applyLifesteal(ServerPlayer attacker, LivingEntity victim, float damageDealt) {
		if (victim instanceof net.minecraft.world.entity.player.Player) return;
		float healAmount = damageDealt * AuraConfig.LIFESTEAL_FRACTION;
		if (healAmount <= 0) return;
		attacker.heal(healAmount);
		if (healAmount >= 0.5f && AuraFx.ready(attacker, "lifesteal_fx", 10)) {
			NetworkHandler.sendFx(attacker, com.toast.aurapoints.network.FxPacket.LIFESTEAL, Math.round(healAmount * 10), "");
		}

		// A thin red stream from the victim into the attacker.
		if (healAmount >= 0.5f && AuraFx.ready(attacker, "lifesteal", 5)) {
			AuraFx.line(attacker.serverLevel(), AuraFx.chest(victim), AuraFx.chest(attacker),
					AuraFx.dust(BLOOD_RED, 0.8f), 8);
		}
	}

	public static final ResourceLocation BERSERKER_MODIFIER_ID =
			ResourceLocation.fromNamespaceAndPath("aurapoints", "berserker");

	/** Berserker's current damage multiplier from the player's health (1.0 at full health). */
	public static double berserkerMultiplier(ServerPlayer player) {
		float healthFraction = player.getMaxHealth() > 0 ? player.getHealth() / player.getMaxHealth() : 1.0f;
		double missingFraction = Math.max(0.0, Math.min(1.0, 1.0 - healthFraction));
		return 1.0 + missingFraction * (AuraConfig.BERSERKER_MAX_DAMAGE_MULTIPLIER - 1.0);
	}

	/** Re-applies the Berserker attack-damage modifier from current health. Pass false to clear it. */
	public static void refreshBerserkerModifier(ServerPlayer player, boolean hasAbility) {
		var attributeInstance = player.getAttribute(Attributes.ATTACK_DAMAGE);
		if (attributeInstance == null) return;

		attributeInstance.removeModifier(BERSERKER_MODIFIER_ID);
		if (!hasAbility) return;

		double bonusFraction = berserkerMultiplier(player) - 1.0;
		if (bonusFraction <= 0.0001) return;

		attributeInstance.addTransientModifier(new AttributeModifier(
				BERSERKER_MODIFIER_ID, bonusFraction, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));

		// Below half health the rage shows: embers and red sparks around the player.
		if (bonusFraction >= (AuraConfig.BERSERKER_MAX_DAMAGE_MULTIPLIER - 1.0) * 0.5) {
			ServerLevel level = player.serverLevel();
			AuraFx.burst(level, player.getX(), player.getY() + 1.0, player.getZ(),
					AuraFx.dust(BLOOD_RED, 1.0f), 4, 0.35, 0.0);
			AuraFx.burst(level, player.getX(), player.getY() + 0.8, player.getZ(), ParticleTypes.SMALL_FLAME, 2, 0.3, 0.01);
		}
	}

	public static void applyBloodrush(ServerPlayer killer) {
		killer.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,
				AuraConfig.BLOODRUSH_DURATION_TICKS, AuraConfig.BLOODRUSH_AMPLIFIER, false, true, true));
		killer.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED,
				AuraConfig.BLOODRUSH_DURATION_TICKS, AuraConfig.BLOODRUSH_AMPLIFIER, false, true, true));

		ServerLevel level = killer.serverLevel();
		AuraFx.helix(killer, AuraFx.dust(BLOOD_RED, 1.2f), 20, 0.8, 2.2);
		AuraFx.burst(level, killer.getX(), killer.getY() + 1.0, killer.getZ(), ParticleTypes.DAMAGE_INDICATOR, 10, 0.4, 0.1);
		AuraFx.sound(level, killer, AuraSounds.RAVAGER_ROAR.value(), 0.5f, 1.6f);
		AuraFx.sound(level, killer, AuraSounds.WARDEN_HEARTBEAT.value(), 1.0f, 1.3f);
		AuraFx.trail(killer, AuraFx.dust(BLOOD_RED, 0.9f), 2, AuraConfig.BLOODRUSH_DURATION_TICKS);
		AuraStats.add(killer, AuraStats.BLOODRUSH_TRIGGERS, 1);
	}

	/** Fireborn: every fire damage type except lava (fire, campfire, burning, magma blocks, fireballs). */
	public static boolean fireborn(net.minecraft.world.damagesource.DamageSource source) {
		return source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) && !source.is(net.minecraft.world.damagesource.DamageTypes.LAVA);
	}

	public static float applyIronSkinReduction(float incomingDamage) {
		return incomingDamage * (1.0f - AuraConfig.IRON_SKIN_DAMAGE_REDUCTION);
	}

	/** Iron flakes and a clank when Iron Skin soaks up a hit. */
	public static void ironSkinFx(ServerPlayer victim) {
		if (!AuraFx.ready(victim, "iron_skin", 10)) return;
		ServerLevel level = victim.serverLevel();
		Vec3 c = AuraFx.chest(victim);
		AuraFx.burst(level, c.x, c.y, c.z, new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.IRON_INGOT)), 8, 0.3, 0.08);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(IRON_GREY, 1.0f), 6, 0.4, 0.0);
		level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
				AuraSounds.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.7f, 1.5f);
	}

	/** Soul burst on the victim when Executioner lands; flames on top when it stacks with Berserker. */
	public static void executionerFx(ServerPlayer attacker, LivingEntity victim, boolean combo) {
		AuraStats.add(attacker, AuraStats.EXECUTIONER_HITS, 1);
		AuraStunts.executionerHit(attacker, victim);
		if (!AuraFx.ready(attacker, "executioner", 4)) return;
		ServerLevel level = attacker.serverLevel();
		Vec3 c = AuraFx.chest(victim);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.SCULK_SOUL, 4, 0.3, 0.04);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.DAMAGE_INDICATOR, 5, 0.3, 0.1);
		AuraFx.sound(level, victim, AuraSounds.PLAYER_ATTACK_CRIT.value(), 0.9f, 0.6f);
		if (combo) {
			AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.FLAME, 14, 0.35, 0.06);
			AuraFx.sound(level, victim, AuraSounds.BLAZE_SHOOT.value(), 0.5f, 1.2f);
		}
	}

	/** An effective (elemental advantage) hit: the attacker's aura bursts on the victim, and the attacker is told. */
	public static void effectiveFx(ServerPlayer attacker, ServerPlayer victim, AuraType aura) {
		if (!AuraFx.ready(attacker, "effective", 10)) return;
		Vec3 c = AuraFx.chest(victim);
		AuraFx.burst(attacker.serverLevel(), c.x, c.y, c.z, AuraFx.dust(AuraLabel.accent(aura), 1.3f), 10, 0.35, 0.0);
		AuraFx.burst(attacker.serverLevel(), c.x, c.y, c.z, ParticleTypes.CRIT, 6, 0.3, 0.2);
		attacker.displayClientMessage(Component.literal("✦ Effective! ✦").withStyle(style -> style.withColor(AuraLabel.accent(aura)).withBold(true)), true);
	}

	/** A gust bursting off the target when a gliding Gale Rider hits it. */
	public static void galeRiderHitFx(ServerPlayer attacker, LivingEntity victim) {
		AuraStats.add(attacker, AuraStats.GALE_RIDER_HITS, 1);
		if (!AuraFx.ready(attacker, "gale_rider", 4)) return;
		ServerLevel level = attacker.serverLevel();
		Vec3 c = AuraFx.chest(victim);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.GUST, 1, 0.0, 0.0);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.CLOUD, 10, 0.4, 0.05);
		level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
				AuraSounds.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 0.5f, 1.3f);
	}

	/** Called every server tick: a Gale Rider gliding with an elytra leaves a wind trail. */
	public static void galeRiderGlideFx(ServerPlayer player, long now) {
		if (!player.isFallFlying() || now % 2 != 0) return;
		ServerLevel level = player.serverLevel();
		AuraFx.burst(level, player.getX(), player.getY() + 0.4, player.getZ(), AuraFx.dust(AuraType.WIND.rgb, 1.3f), 2, 0.2, 0.0);
		AuraFx.burst(level, player.getX(), player.getY() + 0.4, player.getZ(), ParticleTypes.CLOUD, 1, 0.1, 0.0);
	}

	public static boolean executionerApplies(float targetHealth, float targetMaxHealth) {
		return targetMaxHealth > 0 && targetHealth / targetMaxHealth <= AuraConfig.EXECUTIONER_HP_THRESHOLD_FRACTION;
	}

	/**
	 * Executioner on a hit whose damage already contains Berserker's multiplier
	 * {@code berserker} (1.0 if it doesn't). When both boost the hit, their
	 * combined bonus is scaled by {@link AuraConfig#FIRE_COMBO_BONUS_FACTOR}.
	 */
	public static float applyExecutionerBonus(float incomingDamage, double berserker) {
		if (berserker <= 1.0001) {
			return incomingDamage * AuraConfig.EXECUTIONER_DAMAGE_MULTIPLIER;
		}
		double base = incomingDamage / berserker;
		double fullBonus = berserker * AuraConfig.EXECUTIONER_DAMAGE_MULTIPLIER - 1.0;
		return (float) (base * (1.0 + fullBonus * AuraConfig.FIRE_COMBO_BONUS_FACTOR));
	}

	// ---------------------------------------------------------------
	// Actives
	// ---------------------------------------------------------------

	/** Phantom Step: checked from LivingFallEvent; sneaking while landing negates the fall. */
	public static boolean tryPhantomStep(ServerPlayer player, float fallDistance) {
		if (fallDistance < AuraConfig.PHANTOM_STEP_MIN_FALL_DISTANCE) return false;
		if (!player.isCrouching()) return false;

		// no swirl particles: the Shadow cloak's floor trail is the only tell
		player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY,
				AuraConfig.PHANTOM_STEP_INVIS_TICKS, 0, false, false, true));

		ServerLevel level = player.serverLevel();
		AuraFx.particles(level, ParticleTypes.POOF, player.getX(), player.getY(), player.getZ(),
				20, 0.3, 0.1, 0.3, 0.02);
		AuraFx.ring(level, player.getX(), player.getY() + 0.1, player.getZ(), 1.2, ParticleTypes.LARGE_SMOKE, 16);
		AuraFx.burst(level, player.getX(), player.getY() + 0.5, player.getZ(), ParticleTypes.SOUL, 8, 0.4, 0.03);
		AuraFx.burst(level, player.getX(), player.getY() + 0.5, player.getZ(), AuraFx.dust(AuraType.SHADOW.rgb, 1.2f), 12, 0.5, 0.0);
		AuraFx.sound(level, player, AuraSounds.PHANTOM_FLAP.value(), 0.8f, 0.7f);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_PHANTOM_STEP, 0);
		AuraFx.sound(player.serverLevel(), player, AuraSounds.SWEET_WHOOSH.value(), 0.7f, 0.9f);
		AuraStats.add(player, AuraStats.PHANTOM_STEP_LANDINGS, 1);
		AuraStunts.phantomLanded(player);
		return true; // caller negates fall damage
	}

	/**
	 * An ability's damage against {@code target}: EASY_MOB_DAMAGE_MULTIPLIER times as much on the easy
	 * monsters, ABILITY_PLAYER_DAMAGE_MULTIPLIER times as much on players. Every ability's damage goes through here.
	 */
	public static float vsMob(net.minecraft.world.entity.Entity target, float damage) {
		// the hurt() this damage is about to go into is an ability's: see isAbilityHit
		abilityHitTarget = target;
		abilityHitTick = target.level().getGameTime();
		return scaled(target, damage);
	}

	/** vsMob's multipliers without marking the hit (for a bonus added onto a melee hit already under way). */
	public static float scaled(net.minecraft.world.entity.Entity target, float damage) {
		if (target instanceof net.minecraft.world.entity.player.Player) return damage * Math.max(0f, AuraConfig.ABILITY_PLAYER_DAMAGE_MULTIPLIER);
		return target.getType().is(com.toast.aurapoints.AuraTags.EASY_MOBS) ? damage * AuraConfig.EASY_MOB_DAMAGE_MULTIPLIER : damage;
	}

	/** The entity an ability's hurt() is aimed at right now (set by vsMob, just before the hurt call), and when. */
	private static net.minecraft.world.entity.Entity abilityHitTarget = null;
	private static long abilityHitTick = -1;

	/**
	 * In the incoming-damage event: true if this damage is the ability hit vsMob just marked (same
	 * entity, same tick). Clears the mark, so it counts for that one hurt() only.
	 */
	public static boolean takeAbilityHit(net.minecraft.world.entity.Entity victim) {
		if (abilityHitTarget != victim || victim.level().getGameTime() != abilityHitTick) return false;
		abilityHitTarget = null;
		return true;
	}

	/** Living things within {@code r} blocks that an area ability should hit. */
	private static List<LivingEntity> areaTargets(ServerPlayer player, double r) {
		return player.serverLevel().getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(r),
				// not creative players (can't be hurt, so not knocked or set alight either), the mount you ride (and
				// anyone on it), your own animals, armor stands or someone steering a viper - the shared rule
				e -> Handhelds.enemy(player, e) && e.distanceToSqr(player) <= r * r);
	}

	/** 0 = on the ground, 1 = in the air, 2 = in water: picks the animation variant. */
	private static int where(ServerPlayer player) {
		if (player.isInWater() || player.isSwimming()) return 2;
		return !player.onGround() && !player.isPassenger() ? 1 : 0;
	}

	/** Full SHOCKWAVE_DAMAGE up close, falling to EDGE_DAMAGE_FRACTION of it at the edge of the radius. */
	private static float shockwaveDamage(ServerPlayer player, LivingEntity target, double r) {
		double t = Math.min(1.0, Math.sqrt(target.distanceToSqr(player)) / Math.max(0.1, r));
		t = Math.pow(t, Math.max(0.01, AuraConfig.SHOCKWAVE_FALLOFF_CURVE));
		double edge = Math.max(0.0, Math.min(1.0, AuraConfig.SHOCKWAVE_EDGE_DAMAGE_FRACTION));
		return (float) (AuraConfig.SHOCKWAVE_DAMAGE * (1.0 - (1.0 - edge) * t));
	}

	public static boolean tryShockwave(ServerPlayer player, long now) {
		if (!checkCooldown(player, Ability.SHOCKWAVE, AuraConfig.SHOCKWAVE_COOLDOWN_TICKS, now)) return false;
		int at = where(player);
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_SHOCKWAVE, at == 2 ? 3 : at == 1 ? 2 : player.getRandom().nextInt(2));
		AuraFx.sound(player.serverLevel(), player, AuraSounds.SWEET_IMPACT.value(), 1.0f, 1.0f);

		ServerLevel world = player.serverLevel();
		double r = AuraConfig.SHOCKWAVE_RADIUS;
		List<LivingEntity> targets = areaTargets(player, r);

		AuraStunts.ACTIVE_ABILITY = Ability.SHOCKWAVE;
		try {
			for (LivingEntity target : targets) {
				target.hurt(world.damageSources().playerAttack(player), vsMob(target, shockwaveDamage(player, target, r)));
				double dx = player.getX() - target.getX();
				double dz = player.getZ() - target.getZ();
				target.knockback(AuraConfig.SHOCKWAVE_KNOCKBACK, dx, dz);
				AuraStunts.shockwaveHit(player, target);
				Vec3 c = AuraFx.chest(target);
				AuraFx.burst(world, c.x, c.y, c.z, AuraFx.dust(SPRAY_WHITE, 1.2f), 10, 0.3, 0.0);
				AuraFx.burst(world, c.x, c.y, c.z, ParticleTypes.CRIT, 12, 0.3, 0.3);
				AuraFx.burst(world, c.x, c.y, c.z, ParticleTypes.SPLASH, 12, 0.3, 0.2);
			}
		} finally {
			AuraStunts.ACTIVE_ABILITY = null;
		}
		AuraStats.add(player, AuraStats.SHOCKWAVE_HITS, targets.size());

		// A hard slam: an impact flash at the feet, white spray blasting up, and a ring that
		// cracks the ground outward in just 3 ticks. Deep boom, no chimes.
		double cx = player.getX(), cy = player.getY() + 0.1, cz = player.getZ();
		AuraFx.particles(world, ParticleTypes.EXPLOSION, cx, cy + 0.3, cz, 1, 0.0, 0.0, 0.0, 0.0);
		AuraFx.particles(world, AuraFx.dust(SPRAY_WHITE, 1.8f), cx, cy + 0.5, cz, 40, 0.6, 0.8, 0.6, 0.0);
		AuraFx.particles(world, ParticleTypes.SPLASH, cx, cy + 0.3, cz, 80, 0.8, 0.3, 0.8, 0.6);
		AuraFx.particles(world, ParticleTypes.CLOUD, cx, cy + 0.2, cz, 20, 0.5, 0.1, 0.5, 0.25);
		world.playSound(null, cx, cy, cz, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), SoundSource.PLAYERS, 1.0f, 0.7f);
		world.playSound(null, cx, cy, cz, AuraSounds.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.6f, 0.6f);
		world.playSound(null, cx, cy, cz, AuraSounds.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.0f, 1.1f);

		net.minecraft.core.BlockPos below = player.blockPosition().below();
		var ground = world.getBlockState(below);
		var crack = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.DUST_PILLAR,
				ground.isAir() ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState() : ground);
		MinecraftServer server = player.getServer();
		for (int step = 1; step <= 3; step++) {
			final double radius = r * step / 3.0;
			AuraFx.later(server, step, () -> {
				AuraFx.ring(world, cx, cy, cz, radius, crack, (int) (radius * 8));
				AuraFx.ring(world, cx, cy + 0.2, cz, radius, AuraFx.dust(SPRAY_WHITE, 1.5f), (int) (radius * 6));
				AuraFx.ring(world, cx, cy + 0.1, cz, radius, ParticleTypes.SPLASH, (int) (radius * 8));
			});
		}

		markUsed(player, Ability.SHOCKWAVE, now);
		return true;
	}

	public static boolean tryVanish(ServerPlayer player, long now) {
		if (!checkCooldown(player, Ability.VANISH, AuraConfig.VANISH_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_VANISH, player.getRandom().nextInt(2));

		player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY,
				AuraConfig.VANISH_DURATION_TICKS, 0, false, false, true));
		player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,
				AuraConfig.VANISH_DURATION_TICKS, AuraConfig.VANISH_SPEED_AMPLIFIER, false, false, true));

		ServerLevel world = player.serverLevel();
		AuraFx.particles(world, ParticleTypes.POOF, player.getX(), player.getY() + 1.0, player.getZ(),
				30, 0.3, 0.5, 0.3, 0.02);
		AuraFx.burst(world, player.getX(), player.getY() + 1.0, player.getZ(), ParticleTypes.LARGE_SMOKE, 25, 0.4, 0.03);
		AuraFx.burst(world, player.getX(), player.getY() + 1.0, player.getZ(), ParticleTypes.SQUID_INK, 15, 0.4, 0.05);
		AuraFx.burst(world, player.getX(), player.getY() + 1.0, player.getZ(), AuraFx.dust(AuraType.SHADOW.rgb, 1.5f), 20, 0.5, 0.0);
		world.playSound(null, player.blockPosition(), AuraSounds.ENDERMAN_TELEPORT.value(), SoundSource.PLAYERS, 0.6f, 1.6f);
		AuraFx.sound(world, player, AuraSounds.ILLUSIONER_MIRROR_MOVE.value(), 1.0f, 0.8f);

		long until = now + AuraConfig.VANISH_DURATION_TICKS;
		VANISH_UNTIL.put(player.getUUID(), until);
		scheduleReappear(player, until, now);

		AuraStats.add(player, AuraStats.VANISH_USES, 1);
		markUsed(player, Ability.VANISH, now);
		return true;
	}

	/** Server tick each player's current Vanish ends on; Vanish Step pushes it back. */
	private static final Map<UUID, Long> VANISH_UNTIL = new HashMap<>();

	/** A softer puff where the player reappears - skipped if Vanish Step extended the vanish since. */
	private static void scheduleReappear(ServerPlayer player, long until, long now) {
		AuraFx.later(player.getServer(), (int) (until - now), () -> {
			if (!VANISH_UNTIL.remove(player.getUUID(), until)) return;
			if (!AuraFx.alive(player)) return;
			ServerLevel level = player.serverLevel();
			AuraFx.burst(level, player.getX(), player.getY() + 1.0, player.getZ(), AuraFx.dust(AuraType.SHADOW.rgb, 1.2f), 15, 0.4, 0.0);
			AuraFx.burst(level, player.getX(), player.getY() + 1.0, player.getZ(), ParticleTypes.SMOKE, 15, 0.3, 0.02);
			AuraFx.sound(level, player, AuraSounds.ILLUSIONER_MIRROR_MOVE.value(), 0.6f, 1.3f);
		});
	}

	/** Vanish Step: a Phantom Step landing while vanished adds VANISH_STEP_EXTEND_TICKS to the vanish. */
	public static boolean tryVanishStep(ServerPlayer player) {
		long now = player.getServer().getTickCount();
		Long until = VANISH_UNTIL.get(player.getUUID());
		// the speed check catches a vanish cut short (death, milk) before its end tick
		if (until == null || now >= until || !player.hasEffect(MobEffects.MOVEMENT_SPEED)) return false;

		long newUntil = until + AuraConfig.VANISH_STEP_EXTEND_TICKS;
		int ticks = (int) (newUntil - now);
		player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, ticks, 0, false, false, true));
		player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, ticks, AuraConfig.VANISH_SPEED_AMPLIFIER, false, false, true));
		VANISH_UNTIL.put(player.getUUID(), newUntil);
		scheduleReappear(player, newUntil, now);

		ServerLevel level = player.serverLevel();
		AuraFx.burst(level, player.getX(), player.getY() + 0.5, player.getZ(), AuraFx.dust(AuraType.SHADOW.rgb, 1.4f), 18, 0.6, 0.0);
		AuraFx.burst(level, player.getX(), player.getY() + 0.5, player.getZ(), ParticleTypes.SQUID_INK, 8, 0.4, 0.04);
		AuraFx.sound(level, player, AuraSounds.ILLUSIONER_MIRROR_MOVE.value(), 0.8f, 1.6f);
		AuraStats.add(player, AuraStats.VANISH_STEPS, 1);
		return true;
	}

	public static void forgetVanish(ServerPlayer player) {
		VANISH_UNTIL.remove(player.getUUID());
	}

	public static boolean tryDash(ServerPlayer player, long now) {
		AuraPlayerData data = com.toast.aurapoints.data.AuraState.get(player.getServer()).getOrCreate(player.getUUID());
		if (player.isFallFlying()) return tryElytraDash(player, now);
		if (!checkCooldown(player, Ability.DASH, AuraConfig.DASH_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_DASH, where(player) == 1 ? 1 : 0);
		AuraFx.sound(player.serverLevel(), player, AuraSounds.SWEET_WHOOSH.value(), 0.9f, 1.0f);

		Vec3 look = player.getLookAngle();
		double s = AuraConfig.DASH_STRENGTH;
		player.setDeltaMovement(look.x * s, look.y * s * 0.6 + 0.15, look.z * s);
		player.hurtMarked = true; // makes the server send the new velocity to the client
		player.fallDistance = 0.0f;

		ServerLevel world = player.serverLevel();
		AuraFx.particles(world, ParticleTypes.CLOUD, player.getX(), player.getY() + 0.5, player.getZ(),
				20, 0.3, 0.2, 0.3, 0.05);
		AuraFx.ring(world, player.getX(), player.getY() + 0.1, player.getZ(), 0.9, ParticleTypes.SMOKE, 14);
		world.playSound(null, player.blockPosition(), AuraSounds.ENDER_DRAGON_FLAP.value(), SoundSource.PLAYERS, 0.8f, 1.4f);
		AuraFx.sound(world, player, AuraSounds.PHANTOM_FLAP.value(), 0.6f, 1.6f);
		// a shadowy streak behind the dash
		AuraFx.trail(player, AuraFx.dust(data.aura != null ? data.aura.rgb : AuraType.SHADOW.rgb, 1.6f), 4, 8);

		AuraStats.add(player, AuraStats.DASH_USES, 1);
		AuraStunts.dashed(player);
		DashDive.dashed(player, data, now);
		markUsed(player, Ability.DASH, now);
		return true;
	}

	/** Dash while gliding: a small push along the look direction on top of the current speed, on its own cooldown. */
	private static boolean tryElytraDash(ServerPlayer player, long now) {
		if (!checkCooldown(player, Ability.DASH, Slot.FLIGHT, now)) return false;
		Vec3 look = player.getLookAngle();
		player.setDeltaMovement(player.getDeltaMovement().add(look.scale(AuraConfig.DASH_ELYTRA_BOOST)));
		player.hurtMarked = true;

		ServerLevel world = player.serverLevel();
		AuraFx.particles(world, ParticleTypes.CLOUD, player.getX(), player.getY() + 0.5, player.getZ(), 10, 0.3, 0.2, 0.3, 0.03);
		world.playSound(null, player.blockPosition(), AuraSounds.ENDER_DRAGON_FLAP.value(), SoundSource.PLAYERS, 0.6f, 1.6f);
		AuraFx.trail(player, AuraFx.dust(AuraType.WIND.rgb, 1.2f), 3, 6);

		AuraStats.add(player, AuraStats.DASH_USES, 1);
		startCooldown(player, Ability.DASH, Slot.FLIGHT, AuraConfig.DASH_ELYTRA_COOLDOWN_TICKS, now);
		return true;
	}

	/** Players who used their double jump and haven't touched the ground since. In memory only. */
	private static final Set<UUID> AIR_JUMP_USED = new HashSet<>();

	/** Ticks each player has been airborne. In memory only. */
	private static final Map<UUID, Integer> AIR_TICKS = new HashMap<>();

	/** Logout: the airborne count and the used double jump go with the player. */
	public static void forgetAir(ServerPlayer player) {
		AIR_JUMP_USED.remove(player.getUUID());
		AIR_TICKS.remove(player.getUUID());
	}

	/** True between a double jump and the next landing. */
	public static boolean isMidDoubleJump(ServerPlayer player) {
		return AIR_JUMP_USED.contains(player.getUUID()) && !player.onGround();
	}

	/** Called every server tick per player: landing gives the double jump back. */
	public static void tickDoubleJump(ServerPlayer player) {
		if (player.onGround() || player.isInWater() || player.onClimbable()) {
			AIR_JUMP_USED.remove(player.getUUID());
			AIR_TICKS.remove(player.getUUID());
		} else {
			AIR_TICKS.merge(player.getUUID(), 1, Integer::sum);
		}
	}

	/** Double Jump: the client asks when jump is pressed in mid-air; the server checks everything again. */
	public static boolean tryDoubleJump(ServerPlayer player, long now) {
		if (player.isFallFlying()) return tryElytraLift(player, now);
		if (player.onGround() || player.isPassenger() || player.getAbilities().flying
				|| player.isInWater() || player.onClimbable()) return false;
		// The normal jump's own press can never count: the player must already be in the air.
		if (AIR_TICKS.getOrDefault(player.getUUID(), 0) < AuraConfig.DOUBLE_JUMP_MIN_AIR_TICKS) return false;
		if (AIR_JUMP_USED.contains(player.getUUID())) return false;
		if (!checkCooldown(player, Ability.DOUBLE_JUMP, AuraConfig.DOUBLE_JUMP_COOLDOWN_TICKS, now)) return false;

		Vec3 motion = player.getDeltaMovement();
		player.setDeltaMovement(motion.x, AuraConfig.DOUBLE_JUMP_VELOCITY, motion.z);
		player.hurtMarked = true;
		player.fallDistance = 0.0f; // the fall only counts from the second jump on

		ServerLevel world = player.serverLevel();
		AuraFx.particles(world, ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(),
				16, 0.35, 0.05, 0.35, 0.03);
		AuraFx.particles(world, ParticleTypes.SMALL_GUST, player.getX(), player.getY() + 0.1, player.getZ(),
				6, 0.3, 0.05, 0.3, 0.0);
		// a cloud ring kicked off under the feet, like stepping off the air
		AuraFx.ring(world, player.getX(), player.getY(), player.getZ(), 0.9, ParticleTypes.CLOUD, 18);
		AuraFx.ring(world, player.getX(), player.getY(), player.getZ(), 0.6, AuraFx.dust(AuraType.WIND.rgb, 1.2f), 12);
		world.playSound(null, player.blockPosition(), AuraSounds.BREEZE_JUMP.value(), SoundSource.PLAYERS, 0.8f, 1.2f);
		AuraFx.trail(player, AuraFx.dust(AuraType.WIND.rgb, 1.0f), 2, 6);

		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_DOUBLE_JUMP, player.getRandom().nextInt(2));
		AIR_JUMP_USED.add(player.getUUID());
		AuraStats.add(player, AuraStats.DOUBLE_JUMPS, 1);
		markUsed(player, Ability.DOUBLE_JUMP, now);
		return true;
	}

	/** Jump while gliding: a small lift straight up, keeping the forward speed. Flight set, own cooldown, not once per landing. */
	private static boolean tryElytraLift(ServerPlayer player, long now) {
		if (player.isPassenger() || player.isInWater()) return false;
		if (!checkCooldown(player, Ability.DOUBLE_JUMP, Slot.FLIGHT, now)) return false;
		Vec3 motion = player.getDeltaMovement();
		player.setDeltaMovement(motion.x, Math.max(motion.y, 0.0) + AuraConfig.DOUBLE_JUMP_ELYTRA_LIFT, motion.z);
		player.hurtMarked = true;

		ServerLevel world = player.serverLevel();
		AuraFx.particles(world, ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(), 10, 0.4, 0.05, 0.4, 0.02);
		AuraFx.particles(world, ParticleTypes.SMALL_GUST, player.getX(), player.getY() - 0.2, player.getZ(), 4, 0.3, 0.05, 0.3, 0.0);
		AuraFx.ring(world, player.getX(), player.getY() - 0.1, player.getZ(), 0.8, AuraFx.dust(AuraType.WIND.rgb, 1.2f), 12);
		world.playSound(null, player.blockPosition(), AuraSounds.BREEZE_JUMP.value(), SoundSource.PLAYERS, 0.7f, 1.4f);

		AuraStats.add(player, AuraStats.DOUBLE_JUMPS, 1);
		startCooldown(player, Ability.DOUBLE_JUMP, Slot.FLIGHT, AuraConfig.DOUBLE_JUMP_ELYTRA_COOLDOWN_TICKS, now);
		return true;
	}

	public static boolean trySuperWindCharge(ServerPlayer player, long now) {
		if (!checkCooldown(player, Ability.SUPER_WIND_CHARGE, AuraConfig.SUPER_WIND_CHARGE_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_WIND_CHARGE, where(player) == 1 ? 2 : player.getRandom().nextInt(2));
		// the charge leaves the hand at the release, aimed where the player looks by then
		int delay = Math.max(0, AuraConfig.SUPER_WIND_CHARGE_THROW_DELAY_TICKS);
		if (delay == 0) launchWindCharge(player);
		else AuraFx.later(player.getServer(), delay, () -> {
			if (AuraFx.alive(player) && player.isAlive()) launchWindCharge(player);
		});
		AuraStats.add(player, AuraStats.SUPER_WIND_CHARGE_USES, 1);
		AuraStunts.superWindCharge(player);
		markUsed(player, Ability.SUPER_WIND_CHARGE, now);
		return true;
	}

	private static void launchWindCharge(ServerPlayer player) {
		ServerLevel world = player.serverLevel();
		SuperWindCharge charge = new SuperWindCharge(player, world,
				player.position().x(), player.getEyePosition().y(), player.position().z());
		// same launch as throwing a wind charge item
		charge.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.5F, 1.0F);
		world.addFreshEntity(charge);

		world.playSound(null, player.getX(), player.getY(), player.getZ(),
				AuraSounds.WIND_CHARGE_THROW.value(), SoundSource.PLAYERS, 0.8f, 0.6f);
		AuraFx.sound(world, player, AuraSounds.BREEZE_SHOOT.value(), 0.8f, 0.8f);
		Vec3 hand = player.getEyePosition().add(player.getLookAngle().scale(0.8));
		AuraFx.burst(world, hand.x, hand.y, hand.z, ParticleTypes.SMALL_GUST, 6, 0.2, 0.0);
		AuraFx.burst(world, hand.x, hand.y, hand.z, AuraFx.dust(AuraType.WIND.rgb, 1.4f), 10, 0.25, 0.0);
	}

	public static boolean tryHealingTide(ServerPlayer player, long now) {
		if (!checkCooldown(player, Ability.HEALING_TIDE, AuraConfig.HEALING_TIDE_COOLDOWN_TICKS, now)) return false;
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_HEALING_TIDE, where(player) == 2 ? 2 : player.getRandom().nextInt(2));

		ServerLevel world = player.serverLevel();
		double r = AuraConfig.HEALING_TIDE_RADIUS;
		List<ServerPlayer> healed = world.getEntitiesOfClass(ServerPlayer.class, player.getBoundingBox().inflate(r),
				// friends only (Light.friend, like Radiant Pulse): everyone outside the arena, only teammates inside it
				p -> p.isAlive() && !p.isSpectator() && p.distanceToSqr(player) <= r * r && Light.friend(player, p));
		for (ServerPlayer p : healed) {
			if (AuraConfig.HEALING_TIDE_CLEANSE) {
				List<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> bad = p.getActiveEffects().stream()
						.map(MobEffectInstance::getEffect)
						.filter(e -> e.value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL)
						.toList();
				bad.forEach(p::removeEffect);
			}
			p.addEffect(new MobEffectInstance(MobEffects.REGENERATION,
					AuraConfig.HEALING_TIDE_DURATION_TICKS, AuraConfig.HEALING_TIDE_AMPLIFIER, false, true, true));
			Vec3 c = AuraFx.chest(p);
			AuraFx.burst(world, c.x, c.y + 0.5, c.z, ParticleTypes.HEART, 3, 0.4, 0.0);
			AuraFx.burst(world, c.x, c.y, c.z, ParticleTypes.HAPPY_VILLAGER, 8, 0.4, 0.0);
			if (p != player) AuraFx.helix(p, AuraFx.dust(TIDE_TEAL, 1.0f), 20, 0.6, 2.0);
		}

		// the caster alone: a little Resistance
		if (AuraConfig.HEALING_TIDE_RESISTANCE_DURATION_TICKS > 0 && AuraConfig.HEALING_TIDE_RESISTANCE_AMPLIFIER >= 0) {
			player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE,
					AuraConfig.HEALING_TIDE_RESISTANCE_DURATION_TICKS, AuraConfig.HEALING_TIDE_RESISTANCE_AMPLIFIER, false, true, true));
		}

		AuraStats.add(player, AuraStats.HEALING_TIDE_HEALS, healed.size());
		AuraStunts.healingTide(player);
		// Slow and gentle: a soft teal wave flows IN from the edge over a second, while light
		// motes rise around the caster. Quiet resonance and a chime, nothing that booms.
		AuraFx.helix(player, AuraFx.dust(TIDE_TEAL, 1.3f), 30, 0.9, 2.6);
		AuraFx.burst(world, player.getX(), player.getY() + 1.0, player.getZ(), ParticleTypes.HAPPY_VILLAGER, 12, 0.6, 0.0);
		double cx = player.getX(), cy = player.getY() + 0.15, cz = player.getZ();
		MinecraftServer server = player.getServer();
		for (int step = 0; step < 6; step++) {
			final double radius = r * (6 - step) / 6.0;
			AuraFx.later(server, step * 4, () -> {
				AuraFx.ring(world, cx, cy, cz, radius, AuraFx.dust(TIDE_TEAL, 1.3f), (int) (radius * 7));
				AuraFx.ring(world, cx, cy + 0.15, cz, radius * 0.97, AuraFx.dust(0xB8FFF0, 0.8f), (int) (radius * 4));
				AuraFx.ring(world, cx, cy + 0.05, cz, radius, ParticleTypes.BUBBLE_POP, (int) (radius * 2));
			});
		}
		for (int k = 0; k < 4; k++) {
			AuraFx.later(server, k * 5, () -> {
				if (!AuraFx.alive(player)) return;
				AuraFx.particles(world, ParticleTypes.END_ROD, player.getX(), player.getY() + 0.3, player.getZ(), 4, 0.8, 0.1, 0.8, 0.02);
			});
		}
		AuraFx.sound(world, player, AuraSounds.AMETHYST_BLOCK_RESONATE.value(), 1.0f, 1.3f);
		AuraFx.sound(world, player, AuraSounds.BUBBLE_COLUMN_UPWARDS_AMBIENT.value(), 0.8f, 1.2f);
		AuraFx.sound(world, player, AuraSounds.AMETHYST_BLOCK_CHIME.value(), 1.0f, 1.4f);

		markUsed(player, Ability.HEALING_TIDE, now);
		return true;
	}

	public static boolean tryFireball(ServerPlayer player, long now) {
		if (!checkCooldown(player, Ability.FIREBALL, AuraConfig.FIREBALL_COOLDOWN_TICKS, now)) return false;

		// The throw: in water a push, in the air a hurl, on the ground one of three throws at random.
		int variant = player.isInWater() || player.isSwimming() ? BendingAnimPacket.FIREBALL_WATER
				: !player.onGround() && !player.isPassenger() ? BendingAnimPacket.FIREBALL_AIR
				: player.getRandom().nextInt(3); // overhand, sidearm or push
		NetworkHandler.sendBendAnim(player, BendingAnimPacket.GESTURE, BendingAnimPacket.GESTURE_FIREBALL, variant);
		// a flicker of flame in the hand while winding up
		ServerLevel level = player.serverLevel();
		Vec3 hand = player.getEyePosition().add(player.getLookAngle().scale(0.4)).add(0, -0.3, 0);
		AuraFx.burst(level, hand.x, hand.y, hand.z, ParticleTypes.FLAME, 6, 0.1, 0.02);
		AuraFx.sound(level, player, AuraSounds.FIRECHARGE_USE.value(), 0.5f, 1.4f);
		// the fireball leaves the hand at the release, aimed where the player looks by then
		int delay = Math.max(0, AuraConfig.FIREBALL_THROW_DELAY_TICKS);
		if (delay == 0) launchFireball(player);
		else AuraFx.later(player.getServer(), delay, () -> {
			if (AuraFx.alive(player) && player.isAlive()) launchFireball(player);
		});

		markUsed(player, Ability.FIREBALL, now);
		return true;
	}

	private static void launchFireball(ServerPlayer player) {
		ServerLevel world = player.serverLevel();
		Vec3 look = player.getLookAngle();
		Vec3 start = player.getEyePosition().add(look.scale(0.8));
		AuraFireball fireball = new AuraFireball(world, player, look.normalize());
		fireball.setPos(start.x, start.y - 0.1, start.z);
		world.addFreshEntity(fireball);

		AuraFx.burst(world, start.x, start.y, start.z, ParticleTypes.FLAME, 12, 0.15, 0.05);
		AuraFx.burst(world, start.x, start.y, start.z, AuraFx.dust(AuraType.FIRE.rgb, 1.4f), 8, 0.2, 0.0);
		AuraFx.sound(world, player, AuraSounds.BLAZE_SHOOT.value(), 1.0f, 1.0f);
	}

	public static boolean tryHeatWave(ServerPlayer player, long now) {
		if (!checkCooldown(player, Ability.HEAT_WAVE, AuraConfig.HEAT_WAVE_COOLDOWN_TICKS, now)) return false;
		// Holding a fully charged Fire Bending flame turns it into the Inferno Slam (the flame is used up).
		if (AuraConfig.INFERNO_SLAM_ENABLED && FireBending.startInfernoSlam(player, now)) {
			markUsed(player, Ability.HEAT_WAVE, now);
			return true;
		}
		NetworkHandler.sendGesture(player, BendingAnimPacket.GESTURE_HEAT_WAVE, player.getRandom().nextInt(2));
		AuraFx.sound(player.serverLevel(), player, AuraSounds.SWEET_IMPACT.value(), 0.8f, 1.15f);
		heatWave(player, false);
		markUsed(player, Ability.HEAT_WAVE, now);
		return true;
	}

	/**
	 * The Heat Wave blast around the player. {@code inferno}: the Inferno Slam version - wider,
	 * harder, much longer burning, lifts targets off their feet, and far more fire.
	 */
	static void heatWave(ServerPlayer player, boolean inferno) {
		ServerLevel world = player.serverLevel();
		double r = inferno ? AuraConfig.INFERNO_SLAM_RADIUS : AuraConfig.HEAT_WAVE_RADIUS;
		float damage = inferno ? AuraConfig.INFERNO_SLAM_DAMAGE : AuraConfig.HEAT_WAVE_DAMAGE;
		float burn = inferno ? AuraConfig.INFERNO_SLAM_BURN_SECONDS : AuraConfig.HEAT_WAVE_BURN_SECONDS;
		double knockback = inferno ? AuraConfig.INFERNO_SLAM_KNOCKBACK : AuraConfig.HEAT_WAVE_KNOCKBACK;
		List<LivingEntity> burned = areaTargets(player, r);
		AuraStats.add(player, AuraStats.HEAT_WAVE_HITS, burned.size());
		// where everyone stood when the wave caught them: fire is guaranteed there at the end
		List<net.minecraft.core.BlockPos> spots = new java.util.ArrayList<>();
		for (LivingEntity target : burned) spots.add(target.blockPosition());
		AuraStunts.ACTIVE_ABILITY = Ability.HEAT_WAVE;
		try {
			for (LivingEntity target : burned) {
				target.igniteForSeconds(burn);
				AuraStunts.fireHit(player, target);
				target.hurt(world.damageSources().playerAttack(player), vsMob(target, damage));
				target.knockback(knockback, player.getX() - target.getX(), player.getZ() - target.getZ());
				if (inferno) {
					Vec3 v = target.getDeltaMovement();
					target.setDeltaMovement(v.x, Math.max(v.y, 0) + AuraConfig.INFERNO_SLAM_LIFT, v.z);
					target.hurtMarked = true;
				}
				Vec3 c = AuraFx.chest(target);
				AuraFx.burst(world, c.x, c.y, c.z, ParticleTypes.FLAME, inferno ? 50 : 25, 0.35, 0.08);
				AuraFx.burst(world, c.x, c.y, c.z, ParticleTypes.LAVA, inferno ? 8 : 3, 0.3, 0.0);
			}
		} finally {
			AuraStunts.ACTIVE_ABILITY = null;
		}

		// team combos: the wave sets off an ally's zone it reaches, and leaves embers of its own
		Combos.at(player, player.position().add(0, player.getBbHeight() * 0.5, 0), r);
		Combos.zone(player, Combos.Kind.FIRE, player.position(), r, AuraConfig.COMBO_FIRE_ZONE_TICKS, null, true);

		// The eruption: a fiery blast at the caster - flash, a fountain of flames, lava and heavy smoke.
		double cx = player.getX(), cy = player.getY() + 0.1, cz = player.getZ();
		int k = inferno ? 3 : 1;
		AuraFx.particles(world, ParticleTypes.EXPLOSION, cx, cy + 0.8, cz, 3 * k, 0.4 * k, 0.3, 0.4 * k, 0.0);
		AuraFx.particles(world, ParticleTypes.FLAME, cx, cy + 0.5, cz, 90 * k, 0.4 * k, 0.2, 0.4 * k, 0.25 * (inferno ? 1.6 : 1.0));
		AuraFx.particles(world, ParticleTypes.LAVA, cx, cy + 0.6, cz, 25 * k, 0.6 * k, 0.3, 0.6 * k, 0.0);
		AuraFx.particles(world, ParticleTypes.LARGE_SMOKE, cx, cy + 1.0, cz, 30 * k, 0.6 * k, 0.5 * k, 0.6 * k, 0.06);
		AuraFx.particles(world, AuraFx.dust(EMBER_YELLOW, 2.5f), cx, cy + 1.0, cz, 30 * k, 0.8 * k, 0.8, 0.8 * k, 0.0);
		AuraFx.sound(world, player, AuraSounds.GENERIC_EXPLODE.value(), inferno ? 1.6f : 0.9f, inferno ? 0.5f : 0.7f);
		AuraFx.sound(world, player, AuraSounds.BLAZE_SHOOT.value(), 1.0f, 0.5f);
		AuraFx.sound(world, player, AuraSounds.FIRECHARGE_USE.value(), 1.0f, 0.6f);
		AuraFx.sound(world, player, AuraSounds.LAVA_POP.value(), 1.0f, 0.6f);
		if (inferno) {
			// the slam itself: a huge blast, the ground cracking into magma, and a pillar of fire
			AuraFx.particles(world, ParticleTypes.EXPLOSION_EMITTER, cx, cy + 0.5, cz, 1, 0.0, 0.0, 0.0, 0.0);
			AuraFx.particles(world, ParticleTypes.FLAME, cx, cy + 2.0, cz, 120, 0.3, 1.8, 0.3, 0.05);
			AuraFx.particles(world, ParticleTypes.SOUL_FIRE_FLAME, cx, cy + 0.3, cz, 40, 1.0, 0.1, 1.0, 0.15);
			var magma = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.DUST_PILLAR,
					net.minecraft.world.level.block.Blocks.MAGMA_BLOCK.defaultBlockState());
			for (double ring = 1.5; ring <= r; ring += 1.5) AuraFx.ring(world, cx, cy, cz, ring, magma, (int) (ring * 7));
			AuraFx.sound(world, player, AuraSounds.MACE_SMASH_GROUND_HEAVY.value(), 1.5f, 0.6f);
			AuraFx.sound(world, player, AuraSounds.DRAGON_FIREBALL_EXPLODE.value(), 1.2f, 0.7f);
			AuraFx.sound(world, player, AuraSounds.RAVAGER_ROAR.value(), 0.7f, 0.6f);
		}

		// The wave: a wall of fire rolling outward - flame columns on the front, glowing embers,
		// and a trail of smoke and scorched ground behind it.
		MinecraftServer server = player.getServer();
		final int steps = inferno ? 9 : 6;
		final double wall = inferno ? 2.8 : 1.6;
		for (int step = 1; step <= steps; step++) {
			final double radius = r * step / steps;
			final int s = step;
			AuraFx.later(server, step, () -> {
				int points = Math.max(8, (int) (radius * (inferno ? 12 : 9)));
				double height = wall * (1.0 - 0.5 * s / steps); // the wall sinks as it spreads
				for (int i = 0; i < points; i++) {
					double a = Math.PI * 2 * i / points + s * 0.2;
					double x = cx + Math.cos(a) * radius, z = cz + Math.sin(a) * radius;
					for (double y = 0; y < height; y += 0.4) {
						AuraFx.particles(world, ParticleTypes.FLAME, x, cy + y, z, 1, 0.05, 0.05, 0.05, 0.01);
					}
					if (i % 2 == 0) AuraFx.particles(world, AuraFx.dust(i % 4 == 0 ? EMBER_YELLOW : AuraType.FIRE.rgb, 1.8f), x, cy + 0.4, z, 1, 0.1, 0.2, 0.1, 0.0);
					if (inferno && i % 3 == 0) AuraFx.particles(world, ParticleTypes.LAVA, x, cy + 0.2, z, 1, 0.1, 0.1, 0.1, 0.0);
				}
				AuraFx.ring(world, cx, cy + 0.6, cz, radius * 0.85, ParticleTypes.LARGE_SMOKE, (int) (radius * 3));
				AuraFx.ring(world, cx, cy + 0.05, cz, radius * 0.7, ParticleTypes.LAVA, Math.max(2, (int) radius));
				if (s % 2 == 0) world.playSound(null, cx, cy, cz, AuraSounds.FIRE_AMBIENT.value(), SoundSource.PLAYERS, 1.5f, 0.6f);
			});
		}
		if (inferno) {
			// the scorched ground keeps smouldering for a while
			for (int t = steps + 2; t < steps + 60; t += 3) {
				AuraFx.later(server, t, () -> {
					var random = world.getRandom();
					for (int i = 0; i < 10; i++) {
						double a = random.nextDouble() * Math.PI * 2, d = Math.sqrt(random.nextDouble()) * r;
						double x = cx + Math.cos(a) * d, z = cz + Math.sin(a) * d;
						AuraFx.particles(world, i % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME, x, cy + 0.1, z, 1, 0.1, 0.05, 0.1, 0.02);
					}
				});
			}
		}
		// At the edge: fires at random spots, and always where someone was caught.
		AuraFx.later(server, steps + 1, () -> {
			if (!AuraFx.alive(player) || player.serverLevel() != world) return;
			AuraFire.scatter(world, player, new Vec3(cx, cy + 0.5, cz), r,
					inferno ? AuraConfig.INFERNO_SLAM_RANDOM_FIRES : AuraConfig.HEAT_WAVE_RANDOM_FIRES);
			if (AuraConfig.HEAT_WAVE_FIRE_UNDER_TARGETS) {
				for (net.minecraft.core.BlockPos pos : spots) AuraFire.light(world, player, pos);
			}
			world.playSound(null, cx, cy, cz, AuraSounds.FIRECHARGE_USE.value(), SoundSource.PLAYERS, 0.8f, 0.5f);
		});
	}

	// ---------------------------------------------------------------
	// Passives driven by the server tick
	// ---------------------------------------------------------------

	/** Called every server tick per player for the passives that work over time. */
	public static void tickPassives(ServerPlayer player, AuraPlayerData data, long now) {
		ShadowCloak.tick(player, data, now);
		FireBending.tick(player, data, now);
		AirBending.tick(player, data, now);
		AbyssalBubble.tick(player, data, now);
		IceSpike.tick(player, data, now);
		IceShell.tick(player, data, now);
		AuraHalo.tick(player, data, now);
		Bloom.tick(player, data, now);
		Nightstalker.tick(player, data, now);
		Shatter.tick(player, data, now);
		DashDive.tick(player, now); // not only with Dash: a dive cut short by losing Dash must still end
		Poses.tickGlide(player, data); // every player, so the glide pose always ends
		if (hasAbility(data, Ability.GALE_RIDER)) galeRiderGlideFx(player, now);
		if (hasAbility(data, Ability.FEATHERFALL)) tickFeatherfall(player, now);
		if (hasAbility(data, Ability.FLOW) && now % 10 == 0) tickFlow(player, now);
		// Fireborn: never stays on fire (in lava it burns as normal)
		if (hasAbility(data, Ability.FIREBORN) && player.isOnFire() && !player.isInLava()) player.clearFire();
	}

	/** Featherfall: sneaking while falling (not gliding) keeps slow falling on. */
	private static void tickFeatherfall(ServerPlayer player, long now) {
		if (!player.isShiftKeyDown() || player.onGround() || player.isFallFlying() || player.isInWater()
				|| player.getKnownMovement().y >= 0) return;
		player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 10, 0, true, false, false));
		if (now % 3 == 0) {
			AuraFx.burst(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
					AuraFx.dust(AuraType.WIND.rgb, 1.0f), 2, 0.25, 0.0);
		}
	}

	public static float applyFeatherfall(float damageMultiplier) {
		return damageMultiplier * AuraConfig.FEATHERFALL_DAMAGE_MULTIPLIER;
	}

	/**
	 * Flow, every 10 ticks while in water or rain. The healing is done by hand
	 * (1 HP every 50 ticks, same pace as Regeneration I): re-applying the real
	 * effect this often would keep resetting its heal timer.
	 */
	private static void tickFlow(ServerPlayer player, long now) {
		if (!player.isInWaterOrRain()) return;
		if (player.isInWater()) {
			player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 40, 0, true, false, true));
			player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 40, 0, true, false, true));
		}
		int healEvery = Math.max(1, 50 >> Math.max(0, Math.min(5, AuraConfig.FLOW_REGEN_AMPLIFIER)));
		if (now % healEvery < 10 && player.getHealth() < player.getMaxHealth()) {
			player.heal(1.0f);
			AuraStats.add(player, AuraStats.FLOW_HEALED, 1);
			Vec3 c = AuraFx.chest(player);
			AuraFx.burst(player.serverLevel(), c.x, c.y, c.z, AuraFx.dust(AuraType.WATER.rgb, 1.0f), 5, 0.3, 0.0);
		}
	}
}
