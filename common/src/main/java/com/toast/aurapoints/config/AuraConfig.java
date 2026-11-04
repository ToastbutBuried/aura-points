package com.toast.aurapoints.config;

/**
 * Every tunable number for the mod lives here. Nothing in the rest of the
 * mod should hardcode a balance number directly.
 *
 * HOT RELOAD: every non-final field below is a balance value. The value written
 * here is only the default - the live value comes from
 * config/aurapoints-general.json and one file per aura (see AuraBalance; created on first start) and
 * is re-read by /aura reload or /reload, no restart needed. Fields that must
 * never change at runtime (scoreboard names, the wipe version, Puffish
 * categories) stay final and are not in the file.
 *
 * Economy in one paragraph: Aura (a scoreboard, {@link #CURRENCY_OBJECTIVE})
 * comes from quests and bosses - datapacks add to it with plain
 * "scoreboard players add". It is spent on rerolling your aura (/aura roll)
 * and on skill points for the Puffish trees (/aura redeem). Your aura decides
 * which abilities you have.
 */
public final class AuraConfig {

	private AuraConfig() {
	}

	/** Hard cap (blocks) on aimed traces whose range comes from the config, so a typo can't search the whole world. Not a balance value. */
	public static final double MAX_TRACE_RANGE = 256.0;

	// ---------------------------------------------------------------
	// Aura currency (scoreboard)
	// ---------------------------------------------------------------

	/** Scoreboard objective holding each player's Aura. Created by the mod if missing. */
	public static final String CURRENCY_OBJECTIVE = "aura_points";

	/**
	 * Folder (in the server directory, next to mods/ and config/) for /aura progress export and import:
	 * players/ holds one editable file per player, backups/ a copy taken before every import.
	 */
	public static String PROGRESS_FOLDER = "aura-progress";

	/** Every player's first roll is free; each reroll after that costs this much Aura. */
	public static int REROLL_COST = 500;

	/** Aura price of the first skill point bought with /aura redeem. */
	public static int SKILL_POINT_BASE_COST = 20;

	/** Each skill point already bought raises the price of the next one by this much. */
	public static int SKILL_POINT_COST_STEP = 1;

	/** /aura shop (active abilities): price of the first one on your aura, and how much each active you already own on it adds. */
	public static int SHOP_BASE_PRICE = 200;
	public static int SHOP_PRICE_STEP = 50;

	// ---------------------------------------------------------------
	// Bosses
	// ---------------------------------------------------------------

	/**
	 * Aura handed to every nearby player when a boss in #aurapoints:bosses dies.
	 * 0 = the mod pays nothing and the boss datapack pays per boss instead.
	 */
	public static int BOSS_AURA_PER_KILL = 0;

	/** Players within this many blocks of a dying boss share the reward and unlock the PvP tree. */
	public static double BOSS_REWARD_RADIUS = 64.0;

	/** Scoreboard objective the Boss Sigil sets to 0 for its user (read by the boss datapack). */
	public static final String BOSS_OBJECTIVE = "aura_boss";

	/** Boss Sigil cooldown, so holding right-click doesn't use up the whole stack (1 s). */
	public static int BOSS_SIGIL_COOLDOWN_TICKS = 20;

	// ---------------------------------------------------------------
	// Rokaka Fruit / wipe
	// ---------------------------------------------------------------

	/** Ticks the player has to confirm the Rokaka Fruit by using it a second time (10 s). */
	public static int FRUIT_CONFIRM_TICKS = 20 * 10;
	/** Ticks after arming before a confirming use counts (2 s). */
	public static int FRUIT_ARM_TICKS = 20 * 2;
	/**
	 * Fruit uses closer together than this are one held press (holding right-click repeats the
	 * use every 4 ticks), so a held button can never confirm. Confirming needs a fresh click (1 s).
	 */
	public static int FRUIT_HOLD_GAP_TICKS = 20;

	/**
	 * Bump this number to reset every player once on their next login
	 * (aura, free roll, Aura balance, skill trees, awakenings, mastery, cosmetics,
	 * weeklies, Trials/Feats). 5 = full reset 2026-11-01. New players pass through
	 * the same path, which also locks the PvP tree for them.
	 */
	public static final int WIPE_VERSION = 5;

	/**
	 * Every ability's own damage (Shockwave, Heat Wave, Dash Dive, Fireball, Fire Bending, Abyssal Bubble)
	 * is multiplied by this against the easy monsters in #aurapoints:easy_mobs (zombies, skeletons,
	 * spiders, creepers...), so abilities are worth using on them. 1.0 = no bonus.
	 */
	public static float EASY_MOB_DAMAGE_MULTIPLIER = 1.5f;
	/**
	 * Every aura's look on its holder (ability/AuraHalo): a halo above the head that drips the aura's
	 * particles, and specks circling the body. Redrawn every INTERVAL_TICKS; HALO_POINTS specks in a
	 * ring of HALO_RADIUS blocks; DROP_MULTIPLIER scales how often it drips (0 = never). The holder
	 * sees SELF_SHARE of their own (0 = none, 1 = all) - everyone else sees all of it.
	 */
	public static boolean AURA_EFFECT_ENABLED = true;
	/** Show every player's aura above their name ("❄ Frost ❄"). Players can also hide them for themselves in their client config. */
	public static boolean AURA_NAMETAG_ENABLED = true;
	public static int AURA_EFFECT_INTERVAL_TICKS = 2;
	public static int AURA_EFFECT_HALO_POINTS = 10;
	public static double AURA_EFFECT_HALO_RADIUS = 0.35;
	public static double AURA_EFFECT_DROP_MULTIPLIER = 1.0;
	public static double AURA_EFFECT_SELF_SHARE = 0.0;
	/**
	 * Every ability's own damage against PLAYERS, for every aura, is multiplied by this (PvP balance;
	 * 0.8 = 20% less, 1.0 = unchanged). Melee hits and passive bonuses on them are not abilities.
	 */
	public static float ABILITY_PLAYER_DAMAGE_MULTIPLIER = 1.0f;
	/**
	 * Elemental matchups between players (Fire beats Frost, Water beats Fire): an aura that is strong
	 * against the target's aura deals EFFECTIVE_MULTIPLIER times the damage (melee, projectiles and
	 * abilities alike); one that is weak against it deals RESISTED_MULTIPLIER times (1.0 = normal).
	 */
	public static double AURA_EFFECTIVE_MULTIPLIER = 1.25;
	public static double AURA_RESISTED_MULTIPLIER = 1.0;
	/**
	 * Ability damage ignores the target's protection enchantments (Protection, Blast/Fire/Projectile
	 * Protection, ...) - armor points, Resistance and absorption hearts still count. Melee hits don't.
	 */
	public static boolean ABILITY_IGNORES_PROTECTION = true;

	/** Accessory slots left of the armor in the inventory (0 = none, at most AccessorySlots.MAX = 12). */
	public static int ACCESSORY_SLOTS = 2;
	/** true: one item per accessory slot. false: whole stacks fit. */
	public static boolean ACCESSORY_SINGLE_ITEM = true;

	// ---------------------------------------------------------------
	// Passive abilities
	// ---------------------------------------------------------------

	/** Lifesteal (every aura has it): fraction of dealt damage returned as healing. Mobs only, never players. */
	public static float LIFESTEAL_FRACTION = 0.20f;

	/** Berserker: max damage multiplier at 1 HP (interpolated from 1.0 at full HP). 1.60 = up to +60%. */
	public static double BERSERKER_MAX_DAMAGE_MULTIPLIER = 1.60;

	/** Bloodrush: duration of Speed + Haste granted after a player kill, in ticks. */
	public static int BLOODRUSH_DURATION_TICKS = 20 * 5;
	/** Bloodrush: amplifier for both effects (0 = level I). */
	public static int BLOODRUSH_AMPLIFIER = 1;

	/**
	 * Nightstalker: Night Vision all the time (no icon; each player can switch it off with /aura nightvision), and
	 * more melee damage the darker it is where you stand: DAMAGE_PERCENT (0.25 = +25%) at light 0, falling evenly to
	 * nothing above SHADOW_NIGHTSTALKER_MAX_LIGHT.
	 */
	public static int SHADOW_NIGHTSTALKER_MAX_LIGHT = 7;
	public static double SHADOW_NIGHTSTALKER_DAMAGE_PERCENT = 0.25;
	public static boolean SHADOW_NIGHTSTALKER_NIGHT_VISION = true;

	/** Iron Skin: fraction of incoming damage reduced, from every source including magic. */
	public static float IRON_SKIN_DAMAGE_REDUCTION = 0.20f;

	/** Executioner: bonus damage multiplier against targets at or below the HP threshold. */
	public static float EXECUTIONER_DAMAGE_MULTIPLIER = 1.35f;
	public static float EXECUTIONER_HP_THRESHOLD_FRACTION = 0.30f;

	/**
	 * When Berserker and Executioner both boost the same hit, the combined BONUS
	 * is scaled by this. 0.70 = the bonus is cut by 30% (2.16x becomes 1.81x at most).
	 */
	public static double FIRE_COMBO_BONUS_FACTOR = 0.70;

	/** Gale Rider: damage multiplier while gliding with an elytra. 1.30 = +30%. */
	public static float GALE_RIDER_DAMAGE_MULTIPLIER = 1.30f;

	/** Featherfall: fall damage multiplier (0.5 = half), plus slow falling while sneaking in mid-air. */
	public static float FEATHERFALL_DAMAGE_MULTIPLIER = 0.5f;

	/** Flow: in water or rain - Regeneration I, water breathing and Dolphin's Grace. */
	public static int FLOW_REGEN_AMPLIFIER = 0;
	/**
	 * Flow also makes you stronger and quicker: in the rain you deal RAIN_DAMAGE_BONUS more damage
	 * (0.15 = 15%, hits and abilities alike) and your ability cooldowns are RAIN_COOLDOWN_REDUCTION
	 * shorter; in water, the WATER_ values instead. A cooldown is shortened when the ability is used,
	 * by where you are at that moment.
	 */
	public static double FLOW_RAIN_DAMAGE_BONUS = 0.15;
	public static double FLOW_WATER_DAMAGE_BONUS = 0.25;
	public static double FLOW_RAIN_COOLDOWN_REDUCTION = 0.15;
	public static double FLOW_WATER_COOLDOWN_REDUCTION = 0.25;

	// ---------------------------------------------------------------
	// Active abilities (numbers are first guesses - tune freely)
	// ---------------------------------------------------------------

	/** Phantom Step (sneak while falling): invisibility after a negated landing, and minimum fall distance. */
	public static int PHANTOM_STEP_INVIS_TICKS = 20 * 3;
	public static float PHANTOM_STEP_MIN_FALL_DISTANCE = 3.0f;

	/**
	 * Shockwave: radius, damage, knockback strength and cooldown. DAMAGE is what a target right
	 * next to you takes; it falls off with distance down to EDGE_DAMAGE_FRACTION of it at the edge
	 * of the radius (0.25 = a quarter, 1 = no falloff). FALLOFF_CURVE shapes the drop: 1 = even,
	 * above 1 = stays strong longer and drops near the edge, below 1 = drops quickly close in.
	 */
	public static double SHOCKWAVE_RADIUS = 6.0;
	public static float SHOCKWAVE_DAMAGE = 4.0f;
	public static double SHOCKWAVE_EDGE_DAMAGE_FRACTION = 0.25;
	public static double SHOCKWAVE_FALLOFF_CURVE = 1.0;
	public static double SHOCKWAVE_KNOCKBACK = 1.6;
	public static int SHOCKWAVE_COOLDOWN_TICKS = 20 * 30;

	/** Vanish: invisibility + speed duration and cooldown. */
	public static int VANISH_DURATION_TICKS = 20 * 6;
	public static int VANISH_COOLDOWN_TICKS = 20 * 45;
	/** Speed level while vanished (2 = Speed III). */
	public static int VANISH_SPEED_AMPLIFIER = 2;
	/** Vanish Step: extra vanish time per Phantom Step landing while vanished. */
	public static int VANISH_STEP_EXTEND_TICKS = 20 * 4;

	/** Shadow cloak (any invisibility on a Shadow player hides armor and gear): black floor trail every N ticks while moving. */
	public static int SHADOW_TRAIL_INTERVAL_TICKS = 2;
	/** Floor trail particles per burst. */
	public static int SHADOW_TRAIL_PARTICLES = 6;
	/** Outline specks drawn on the cloaked player's silhouette every 2 ticks (0 = no outline). */
	public static int SHADOW_OUTLINE_PARTICLES = 5;

	/** Dash: forward velocity and cooldown. */
	public static double DASH_STRENGTH = 1.9;
	public static int DASH_COOLDOWN_TICKS = 20 * 10;
	/** Dashing while gliding with an elytra is a weak boost along your look direction (this much added speed) with its own cooldown, separate from the ground Dash. */
	public static double DASH_ELYTRA_BOOST = 0.6;
	public static int DASH_ELYTRA_COOLDOWN_TICKS = 20 * 5;

	/**
	 * Shadow Dive (Dash follow-up): within DASH_DIVE_WINDOW_TICKS after a Dash, while at least
	 * DASH_DIVE_MIN_HEIGHT blocks above the ground, Dash again to slam down at DASH_DIVE_SPEED.
	 * Landing: no fall damage, and a weak shockwave DASH_DIVE_SHOCKWAVE_FORWARD blocks in front.
	 */
	public static int DASH_DIVE_WINDOW_TICKS = 30;
	public static double DASH_DIVE_MIN_HEIGHT = 4.0;
	public static double DASH_DIVE_SPEED = 2.2;
	public static double DASH_DIVE_SHOCKWAVE_FORWARD = 1.5;
	public static double DASH_DIVE_SHOCKWAVE_RADIUS = 3.0;
	public static float DASH_DIVE_SHOCKWAVE_DAMAGE = 2.0f;
	/** The landing hits harder the higher the dive started: SHOCKWAVE_DAMAGE + PER_BLOCK for every block dived, at most DAMAGE_MAX. */
	public static float DASH_DIVE_DAMAGE_PER_BLOCK = 0.25f;
	public static float DASH_DIVE_DAMAGE_MAX = 8.0f;
	public static double DASH_DIVE_SHOCKWAVE_KNOCKBACK = 0.7;
	/** Dash cooldown after a Shadow Dive, counted from the landing (replaces the normal one). */
	public static int DASH_DIVE_COOLDOWN_TICKS = 20 * 20;

	/** Double Jump (press jump again in mid-air): upward velocity and cooldown. One per landing. */
	public static double DOUBLE_JUMP_VELOCITY = 0.75;
	public static int DOUBLE_JUMP_COOLDOWN_TICKS = 20 * 3;
	/** Minimum time in the air before a jump press counts as a double jump (3 ticks = 0.15 s). */
	public static int DOUBLE_JUMP_MIN_AIR_TICKS = 3;
	/**
	 * Jump while gliding with an elytra: a small lift straight up (no forward push). Any downward speed
	 * is cancelled first, then this much upward speed is added. Own cooldown, separate from the ground Double Jump.
	 */
	public static double DOUBLE_JUMP_ELYTRA_LIFT = 0.45;
	public static int DOUBLE_JUMP_ELYTRA_COOLDOWN_TICKS = 20 * 4;

	/** Super Wind Charge: power compared to a normal wind charge (radius and knockback), and cooldown. */
	public static float SUPER_WIND_CHARGE_POWER = 2.0f;
	public static int SUPER_WIND_CHARGE_COOLDOWN_TICKS = 20 * 20;
	/** Ticks from the key press until the charge leaves the hand, matching the animation (0 = at once). */
	public static int SUPER_WIND_CHARGE_THROW_DELAY_TICKS = 2;

	/**
	 * Wind launch: holding the Dash key for HOLD_TICKS (Wind aura only) blasts you straight up at VELOCITY and shoves
	 * everything within RADIUS away (KNOCKBACK sideways + LIFT up, no damage). A tap still dashes, on release.
	 * Own COOLDOWN, separate from Dash. ALLOWS_DIVE: a Dash press high up right after the launch dives (like after a Dash).
	 * false (ENABLED): Dash always dashes on press.
	 */
	public static boolean WIND_LAUNCH_ENABLED = true;
	public static int WIND_LAUNCH_HOLD_TICKS = 10;
	public static double WIND_LAUNCH_VELOCITY = 1.5;
	public static double WIND_LAUNCH_RADIUS = 4.0;
	public static double WIND_LAUNCH_KNOCKBACK = 0.9;
	public static double WIND_LAUNCH_LIFT = 0.3;
	public static int WIND_LAUNCH_COOLDOWN_TICKS = 20 * 20;
	public static boolean WIND_LAUNCH_NO_FALL_DAMAGE = true;
	public static boolean WIND_LAUNCH_ALLOWS_DIVE = true;
	/** Holding Dash while gliding with an elytra boosts you along your look direction instead: this much added speed (flight set cooldown). */
	public static double WIND_LAUNCH_ELYTRA_BOOST = 2.2;
	public static int WIND_LAUNCH_ELYTRA_COOLDOWN_TICKS = 20 * 12;

	/**
	 * Air Bending: holding the Super Wind Charge key for HOLD_TICKS (a tap still throws the charge) gathers a ball of wind
	 * HOLD_DISTANCE blocks in front of you. Swing it around to charge it like Fire Bending (READY_DEGREES to fire,
	 * FULL_DEGREES for full power, at most MAX_DEGREES_PER_TICK counted per tick); press again to fire it, at SPEED
	 * blocks per tick up to RANGE. Where it hits, everything within BLAST_RADIUS is hurled away (KNOCKBACK, sideways
	 * speed) and up (LIFT) and takes DAMAGE; READY_FACTOR is the strength when just ready (1.0 at full).
	 * SELF_FACTOR: how hard your own blast throws you if you are inside it (0 = never), with no fall damage after.
	 * Not fired within MAX_HOLD_TICKS, it fades. COOLDOWN starts at the shot or fade (it uses Super Wind Charge's cooldown).
	 */
	public static boolean AIR_BENDING_ENABLED = true;
	public static int AIR_BENDING_HOLD_TICKS = 10;
	public static double AIR_BENDING_HOLD_DISTANCE = 2.5;
	/** The held wind charge has wind specks circling it: START_SIZE blocks out at first, growing to FULL_SIZE at full charge. */
	public static double AIR_BENDING_START_SIZE = 0.25;
	public static double AIR_BENDING_FULL_SIZE = 0.6;
	/**
	 * FULL_RAISED: once fully charged, you raise your right arm out to the side and the wind charge sits on your
	 * hand (the shot still goes where your crosshair points). The arm's angles, in degrees: RAISED_OUT_DEGREES up
	 * and out to the side from hanging (90 = level with the shoulder, 180 = straight up), RAISED_FORWARD_DEGREES
	 * swung forward. RAISED_DROP_PIXELS moves the whole arm down your side (1 pixel = 1/16 of the skin; the
	 * shoulder is at pixel 2, the waist at about 10, max 20). The charge is placed on the hand with the same
	 * values, so arm and charge always match.
	 */
	public static boolean AIR_BENDING_FULL_RAISED = true;
	public static double AIR_BENDING_RAISED_OUT_DEGREES = 150.0;
	public static double AIR_BENDING_RAISED_FORWARD_DEGREES = 40.0;
	public static double AIR_BENDING_RAISED_DROP_PIXELS = 5.0;
	/** The whole raised arm moved this many pixels to the front of the body (0..10). */
	public static double AIR_BENDING_RAISED_FORWARD_PIXELS = 2.0;
	/** ... and this many pixels out to the right (0..10). Both go in half-pixel steps. */
	public static double AIR_BENDING_RAISED_RIGHT_PIXELS = 1.5;
	/** The raised arm turned about its own length (degrees): 180 = palm the other way, so a held item points up instead of down. */
	public static double AIR_BENDING_RAISED_TWIST_DEGREES = 180.0;
	/** Fully charged, you only see this share of the wind circling your own charge (0 = none, 1 = all); everyone else sees it all. */
	public static double AIR_BENDING_HOLDER_PARTICLES = 0.35;
	public static double AIR_BENDING_READY_DEGREES = 500.0;
	public static double AIR_BENDING_FULL_DEGREES = 1000.0;
	public static double AIR_BENDING_MAX_DEGREES_PER_TICK = 45.0;
	public static int AIR_BENDING_MAX_HOLD_TICKS = 20 * 20;
	public static int AIR_BENDING_COOLDOWN_TICKS = 20 * 30;
	public static double AIR_BENDING_RANGE = 30.0;
	public static double AIR_BENDING_SPEED = 3.0;
	public static double AIR_BENDING_BLAST_RADIUS = 5.0;
	public static double AIR_BENDING_KNOCKBACK = 3.0;
	public static double AIR_BENDING_LIFT = 1.6;
	public static float AIR_BENDING_DAMAGE = 4.0f;
	public static double AIR_BENDING_READY_FACTOR = 0.5;
	public static double AIR_BENDING_SELF_FACTOR = 0.7;

	/** Healing Tide: Regeneration for you and every friendly player within the radius (outside the arena everyone, in it only teammates). */
	public static double HEALING_TIDE_RADIUS = 8.0;
	public static int HEALING_TIDE_DURATION_TICKS = 20 * 5;
	public static int HEALING_TIDE_AMPLIFIER = 1; // Regeneration II
	public static int HEALING_TIDE_COOLDOWN_TICKS = 20 * 40;
	/** true: also clears every negative effect (poison, wither, slowness...) from every friend it reaches. */
	public static boolean HEALING_TIDE_CLEANSE = true;
	/** The caster alone also gets Resistance (AMPLIFIER 0 = Resistance I) for this long (0 = none). */
	public static int HEALING_TIDE_RESISTANCE_DURATION_TICKS = 20 * 6;
	public static int HEALING_TIDE_RESISTANCE_AMPLIFIER = 0;

	/** Fireball: a blaze fireball (5 s burning, never sets blocks on fire) and cooldown. */
	public static int FIREBALL_COOLDOWN_TICKS = 20 * 15;

	/** Aura branches (5.9.0, ability/Branch): switch them all off here. A branch needs this Mastery level on its aura. */
	public static boolean BRANCHES_ENABLED = true;
	public static int BRANCH_MASTERY_REQUIRED = 5;
	/** Aura price of switching to another branch (the first pick on an aura is free). */
	public static int BRANCH_SWITCH_COST = 300;

	/** Fire - Blue Flame: Fireball speed x this, passes through the first target; cooldowns. */
	public static double FIRE_BLUE_FLAME_FIREBALL_SPEED = 1.5;
	public static boolean FIRE_BLUE_FLAME_PIERCE = true;
	public static int FIRE_BLUE_FLAME_FIREBALL_COOLDOWN_TICKS = 20 * 11;
	public static int FIRE_BLUE_FLAME_HEAT_WAVE_COOLDOWN_TICKS = 20 * 31;
	/** Fire Bending shot damage for Blue Flame (see FIRE_BENDING_DAMAGE_CLOSE / FAR for the base). */
	public static float FIRE_BLUE_FLAME_BENDING_DAMAGE_CLOSE = 14.0f;
	public static float FIRE_BLUE_FLAME_BENDING_DAMAGE_FAR = 6.0f;
	/** Scorch (Blue Flame passive): burning targets take this much more damage from you (0.20 = +20%). */
	public static double SCORCH_BONUS = 0.20;

	/** Fire - Magma: pools (radius in blocks, life and damage tick in ticks) and Resistance for the owner standing in one. */
	public static int FIRE_MAGMA_FIREBALL_COOLDOWN_TICKS = 20 * 18;
	public static double FIRE_MAGMA_POOL_RADIUS = 2.0;
	public static int FIRE_MAGMA_POOL_TICKS = 20 * 6;
	public static int FIRE_MAGMA_POOL_INTERVAL_TICKS = 10;
	public static float FIRE_MAGMA_POOL_DAMAGE = 1.0f;
	public static float FIRE_MAGMA_POOL_BURN_SECONDS = 3.0f;
	public static int FIRE_MAGMA_RESISTANCE_AMPLIFIER = 0;
	/** Heat Wave leaves this many pools at random spots inside the wave; the Inferno Slam one pool of INFERNO_POOL_RADIUS. */
	public static int FIRE_MAGMA_HEAT_WAVE_POOLS = 3;
	public static double FIRE_MAGMA_HEAT_WAVE_POOL_RADIUS = 1.6;
	public static double FIRE_MAGMA_INFERNO_POOL_RADIUS = 4.0;

	/** Shadow - Phantom (ability/Branch, PhantomDecoy): Vanish and Dash cooldown x this, decoy lifetime, Phantom Step invisibility x this, Vanish Step extension added. */
	public static double PHANTOM_COOLDOWN_FACTOR = 0.75;
	public static int PHANTOM_DECOY_TICKS = 20 * 3;
	public static double PHANTOM_STEP_INVIS_FACTOR = 1.5;
	public static int PHANTOM_VANISH_STEP_BONUS_TICKS = 20;

	/** Frost - Glacial (ability/Branch): longer Ice Spike freeze and slow, more Ice Shell layers that rebuild slower, Shatter needs more hits. */
	public static int FROST_GLACIAL_FREEZE_TICKS = 100;
	public static double FROST_GLACIAL_SLOW_FACTOR = 1.5;
	public static int FROST_GLACIAL_SHELL_LAYERS = 5;
	public static int FROST_GLACIAL_SHELL_REBUILD_TICKS = 20 * 45;
	public static int FROST_GLACIAL_SHELL_COLD_REBUILD_TICKS = 450;
	public static int FROST_GLACIAL_SHATTER_HITS = 4;
	/** Frost - Frostbite: Shatter needs fewer hits, Brittle (Chilled targets take BRITTLE_BONUS more from you), a thinner shell and shorter freeze. */
	public static int FROST_BITE_SHATTER_HITS = 2;
	public static double BRITTLE_BONUS = 0.15;
	public static int FROST_BITE_SHELL_LAYERS = 2;
	public static int FROST_BITE_FREEZE_TICKS = 40;

	/** Verdant - Venomweaver: stronger Virulence, poison that stacks up to this amplifier, a slower Viper. */
	public static double VERDANT_VENOM_VIRULENCE_BONUS = 0.40;
	public static int VERDANT_VENOM_POISON_MAX_AMPLIFIER = 3;
	public static int VERDANT_VENOM_VIPER_COOLDOWN_TICKS = 20 * 35;
	/** Verdant - Grove Warden: Bloom heals friends within the radius (HP per pulse, a pulse is BLOOM_INTERVAL_TICKS), tougher and longer Entanglement, a weaker Viper (no Virulence). */
	public static float VERDANT_GROVE_HEAL = 1.0f;
	public static int VERDANT_GROVE_HEAL_RADIUS = 6;
	public static int VERDANT_GROVE_TRAP_TICKS = 20 * 25;
	public static int VERDANT_GROVE_MASH_PRESSES = 34;
	public static int VERDANT_GROVE_VIPER_POISON_AMPLIFIER = 0;

	/** Earth - Bulwark: tougher Stone Skin and Grounded, Quake gives absorption (per enemy hit, capped), Pillar Lift and Boulder Toss deal ATTACK_FACTOR of their damage. */
	public static double EARTH_BULWARK_SKIN_ARMOR = 6.0;
	public static double EARTH_BULWARK_SKIN_TOUGHNESS = 3.0;
	public static double EARTH_BULWARK_KNOCKBACK_RESISTANCE = 0.8;
	public static double EARTH_BULWARK_FALL_REDUCTION = 0.5;
	public static float EARTH_BULWARK_QUAKE_ABSORB = 2.0f;
	public static float EARTH_BULWARK_QUAKE_ABSORB_MAX = 8.0f;
	public static int EARTH_BULWARK_QUAKE_ABSORB_TICKS = 20 * 8;
	public static double EARTH_BULWARK_ATTACK_FACTOR = 0.75;
	/** Earth - Tectonic: harder Pillar Lift, Boulder Toss and Quake; weaker Stone Skin and Grounded. */
	public static float EARTH_TECTONIC_PILLAR_DAMAGE = 6.0f;
	public static double EARTH_TECTONIC_PILLAR_LAUNCH = 1.35;
	public static float EARTH_TECTONIC_BOULDER_DAMAGE = 11.0f;
	public static float EARTH_TECTONIC_BOULDER_SPLASH = 5.5f;
	public static double EARTH_TECTONIC_QUAKE_RADIUS = 9.0;
	public static float EARTH_TECTONIC_QUAKE_DAMAGE = 6.0f;
	public static double EARTH_TECTONIC_SKIN_ARMOR = 2.0;
	public static double EARTH_TECTONIC_KNOCKBACK_RESISTANCE = 0.25;

	/** Light - Radiant: bigger heals, Halo shines on friends (Resistance I for BUFF_TICKS within RADIUS), a weaker Prism Beam (x BEAM_FACTOR) and a slower Solar Flash. */
	public static float LIGHT_RADIANT_PULSE_HEAL = 6.0f;
	public static float LIGHT_RADIANT_SUN_HEAL = 3.0f;
	public static int LIGHT_RADIANT_HALO_RADIUS = 6;
	public static int LIGHT_RADIANT_HALO_BUFF_TICKS = 100;
	public static double LIGHT_RADIANT_BEAM_FACTOR = 0.7;
	public static int LIGHT_RADIANT_FLASH_COOLDOWN_TICKS = 18 * 20;
	/** Light - Prismatic: a stronger Prism Beam that chains from a target Solar Flash marked (MARK_TICKS) to CHAIN_TARGETS others within CHAIN_RADIUS for CHAIN_FACTOR of its damage; weaker Pulse and Halo heals. */
	public static float LIGHT_PRISM_BEAM_DAMAGE = 2.25f;
	public static int LIGHT_PRISM_MARK_TICKS = 120;
	public static int LIGHT_PRISM_CHAIN_TARGETS = 2;
	public static double LIGHT_PRISM_CHAIN_RADIUS = 5.0;
	public static double LIGHT_PRISM_CHAIN_FACTOR = 0.6;
	public static float LIGHT_PRISM_PULSE_HEAL = 3.0f;
	public static float LIGHT_PRISM_HALO_HEAL = 0.5f;

	/** Blood - Sanguine: Hemorrhage and bleeding heal you (share of the damage), a bigger Bloodlust heal, a faster Blood bar, a weaker Crimson Sacrifice and a dearer Blood Moon. */
	public static double BLOOD_SANGUINE_HEMO_LIFESTEAL = 0.5;
	public static double BLOOD_SANGUINE_BLEED_LIFESTEAL = 0.25;
	public static float BLOOD_SANGUINE_LUST_HEAL = 6.0f;
	public static double BLOOD_SANGUINE_BAR_FACTOR = 1.5;
	public static double BLOOD_SANGUINE_SACRIFICE_MULTIPLIER = 1.5;
	public static int BLOOD_SANGUINE_MOON_COST = 130;
	/** Blood - Sacrificial: a dearer, much stronger Crimson Sacrifice; a Tether that costs you health and no longer heals; a cheap Blood Moon; a poorer Bloodlust and Thin Blood. */
	public static float BLOOD_SACRIFICIAL_SACRIFICE_COST = 10.0f;
	public static double BLOOD_SACRIFICIAL_SACRIFICE_MULTIPLIER = 3.0;
	public static int BLOOD_SACRIFICIAL_SACRIFICE_HITS = 4;
	public static float BLOOD_SACRIFICIAL_TETHER_DRAIN = 3.0f;
	public static float BLOOD_SACRIFICIAL_TETHER_COST = 1.0f;
	public static int BLOOD_SACRIFICIAL_MOON_COST = 70;
	public static float BLOOD_SACRIFICIAL_LUST_HEAL = 2.0f;
	public static double BLOOD_SACRIFICIAL_THIN_THRESHOLD = 0.3;

	/** Water - Tidecaller: a bigger Healing Tide that marks enemies Wet, a harder Shockwave; a thinner Iron Skin, a slower Abyssal Bubble. */
	public static int WATER_TIDECALLER_HEALING_AMPLIFIER = 2;
	public static double WATER_TIDECALLER_HEALING_RADIUS = 10.0;
	public static double WATER_TIDECALLER_SHOCKWAVE_KNOCKBACK = 2.4;
	public static float WATER_TIDECALLER_IRON_SKIN_REDUCTION = 0.10f;
	public static int WATER_TIDECALLER_BUBBLE_COOLDOWN_TICKS = 20 * 60;
	/** Water - Leviathan: Iron Skin gains STACK_BONUS per hit taken (up to MAX_STACKS, gone after STACK_TICKS without a hit), a harder Bubble pull; a weaker Healing Tide and Shockwave. */
	public static float WATER_LEVIATHAN_STACK_BONUS = 0.05f;
	public static int WATER_LEVIATHAN_MAX_STACKS = 5;
	public static int WATER_LEVIATHAN_STACK_TICKS = 100;
	public static double WATER_LEVIATHAN_BUBBLE_PULL = 0.05;
	public static int WATER_LEVIATHAN_HEALING_AMPLIFIER = 0;
	public static double WATER_LEVIATHAN_SHOCKWAVE_FACTOR = 0.75;

	/** Thunder - Surge: faster Thunderbolt and Chidori, Static Charge every STATIC_HITS-th hit arcing to fewer targets; a weaker Wrath and slower Chidori. */
	public static int THUNDER_SURGE_THUNDERBOLT_COOLDOWN_TICKS = 20 * 14;
	public static int THUNDER_SURGE_CHIDORI_CHARGE_TICKS = 20;
	public static int THUNDER_SURGE_STATIC_HITS = 3;
	public static int THUNDER_SURGE_STATIC_ARC_TARGETS = 1;
	public static double THUNDER_SURGE_WRATH_FACTOR = 0.75;
	public static int THUNDER_SURGE_CHIDORI_COOLDOWN_TICKS = 20 * 18;
	/** Thunder - Conductor: wider chains, Chidori arcs, Charged targets spread damage; one Chidori charge and a slower Thunderbolt. */
	public static int THUNDER_CONDUCTOR_STATIC_ARC_TARGETS = 4;
	public static double THUNDER_CONDUCTOR_CHAIN_RADIUS = 8.0;
	public static int THUNDER_CONDUCTOR_CHAIN_TARGETS = 3;
	public static int THUNDER_CONDUCTOR_CHIDORI_ARC_TARGETS = 2;
	public static float THUNDER_CONDUCTOR_CHIDORI_ARC_FRACTION = 0.4f;
	public static double THUNDER_CONDUCTOR_SPREAD_FRACTION = 0.3;
	public static int THUNDER_CONDUCTOR_SPREAD_TARGETS = 3;
	public static double THUNDER_CONDUCTOR_SPREAD_RADIUS = 4.0;
	public static int THUNDER_CONDUCTOR_CHIDORI_CHARGES = 1;
	public static int THUNDER_CONDUCTOR_THUNDERBOLT_COOLDOWN_TICKS = 20 * 24;

	/** Wind - Skyrunner: Gale Rider lingers after gliding, two-use Dash and Double Jump charges; a slower Super Wind Charge and a smaller Air Bending blast. */
	public static int WIND_SKYRUNNER_GALE_LINGER_TICKS = 80;
	public static int WIND_SKYRUNNER_WIND_CHARGE_COOLDOWN_TICKS = 20 * 28;
	public static double WIND_SKYRUNNER_BLAST_RADIUS = 4.0;
	/** Wind - Cyclone: the blast pulls enemies in, Firestorm and Storm hit harder; slower Dash and Double Jump. */
	public static double WIND_CYCLONE_STORM_FACTOR = 1.5;
	public static int WIND_CYCLONE_DASH_COOLDOWN_TICKS = 20 * 14;
	public static int WIND_CYCLONE_DOUBLE_JUMP_COOLDOWN_TICKS = 20 * 5;

	/** Resonance - Dirge: harder darts that deafen (noise pinned at NOISE_DEAF_LEVEL for DEAF_TICKS); a shorter-lived tree and slower Grasp. */
	public static float RESONANCE_DIRGE_DART_DAMAGE = 3.5f;
	public static int RESONANCE_DIRGE_DEAF_TICKS = 80;
	public static int RESONANCE_DIRGE_TREE_TICKS = 20 * 40;
	public static int RESONANCE_DIRGE_GRASP_COOLDOWN_TICKS = 20 * 30;
	/** Resonance - Chorus: a longer-lived tree, more blossoms, Echolocation shows friends; weaker darts and a slower Grasp. */
	public static int RESONANCE_CHORUS_TREE_TICKS = 20 * 90;
	public static int RESONANCE_CHORUS_BLOSSOM_MAX = 5;
	public static float RESONANCE_CHORUS_DART_DAMAGE = 1.5f;
	public static int RESONANCE_CHORUS_GRASP_COOLDOWN_TICKS = 20 * 32;
	public static float NOISE_DEAF_LEVEL = 60.0f;

	/** Shadow dragon: view range of the item displays that make up its body (x 64 blocks; 2.5 is the most the server sends, 0 = leave them alone). */
	public static float SHADOW_DRAGON_SEGMENT_VIEW_RANGE = 6.0f;
	/** How far (blocks) the server sends a dragon segment to players (0 = vanilla 160). The server's view-distance (chunks x 16, minus 16) caps it. */
	public static int SHADOW_DRAGON_SEGMENT_TRACK_BLOCKS = 384;

	/** Fireborn: how far you see while in lava, in blocks (vanilla: about 1, with Fire Resistance 5; 0 = like vanilla). */
	public static float FIREBORN_LAVA_SIGHT = 16f;
	/** Fireball damage on a hit - the same however far it flew. */
	public static float FIREBALL_DAMAGE = 5.0f;
	/** Fireball impact: up to FIREBALL_FIRES fire blocks at random spots within FIREBALL_FIRE_RADIUS blocks (0 = none). */
	public static int FIREBALL_FIRES = 3;
	/** Ticks from the key press until the fireball leaves the hand, matching the throw animation (0 = at once). */
	public static int FIREBALL_THROW_DELAY_TICKS = 3;
	/** Fires set by Fire abilities go out after this many seconds (0 = they stay; with doFireTick off they never burn out). */
	public static float AURA_FIRE_SECONDS = 5.0f;
	public static double FIREBALL_FIRE_RADIUS = 2.0;

	/**
	 * Fire Bending: press the key to hold a fire orb FIRE_BENDING_HOLD_DISTANCE blocks in front of your eyes.
	 * Looking around charges it (degrees turned, at most MAX_DEGREES_PER_TICK counted per tick).
	 * From READY_DEGREES on, press again to shoot it; charging on up to FULL_DEGREES makes the shot
	 * faster and stronger. Not shot within MAX_HOLD_TICKS, it fizzles. Cooldown starts at the shot or fizzle.
	 */
	public static double FIRE_BENDING_HOLD_DISTANCE = 3.0;
	/**
	 * FULL_SIDE: once fully charged (FULL_DEGREES), the orb comes to your side and you hold it above
	 * your right hand, so it no longer blocks your view; the shot still goes where your crosshair
	 * points. SIDE_RIGHT / SIDE_FORWARD / SIDE_HEIGHT: where it is held, in blocks from your feet
	 * (turned with your body). false = it stays in front of you.
	 */
	public static boolean FIRE_BENDING_FULL_SIDE = true;
	public static double FIRE_BENDING_SIDE_RIGHT = 0.72;
	public static double FIRE_BENDING_SIDE_FORWARD = 0.25;
	public static double FIRE_BENDING_SIDE_HEIGHT = 0.85;
	public static double FIRE_BENDING_READY_DEGREES = 500.0;
	public static double FIRE_BENDING_FULL_DEGREES = 1000.0;
	public static double FIRE_BENDING_MAX_DEGREES_PER_TICK = 45.0;
	public static int FIRE_BENDING_MAX_HOLD_TICKS = 20 * 20;
	public static int FIRE_BENDING_COOLDOWN_TICKS = 20 * 20;
	/** Held orb hitbox (half its width in blocks); touching it hurts and ignites, each target at most every CONTACT_INTERVAL ticks. */
	public static double FIRE_BENDING_HITBOX = 0.5;
	public static float FIRE_BENDING_CONTACT_DAMAGE = 2.0f;
	public static int FIRE_BENDING_CONTACT_INTERVAL_TICKS = 10;
	/** true: the held orb bursts on the first mob or player it touches (used up, cooldown starts). false: it keeps burning everything it touches. */
	public static boolean FIRE_BENDING_CONTACT_CONSUMES = true;
	/** Cooldown when the held orb bursts on a mob or player (instead of the full FIRE_BENDING_COOLDOWN_TICKS). */
	public static int FIRE_BENDING_CONTACT_COOLDOWN_TICKS = 5 * 20;
	/** Shot: range, and speed in blocks per tick at READY_DEGREES and at FULL_DEGREES. */
	public static double FIRE_BENDING_RANGE = 20.0;
	public static double FIRE_BENDING_SPEED_READY = 2.5;
	public static double FIRE_BENDING_SPEED_FULL = 5.0;
	/** Shot damage: CLOSE point-blank, falling off evenly to FAR at full range (at full charge). */
	public static float FIRE_BENDING_DAMAGE_CLOSE = 12.0f;
	public static float FIRE_BENDING_DAMAGE_FAR = 4.0f;
	/** Shot damage multiplier at READY_DEGREES (rises evenly to 1.0 at FULL_DEGREES). */
	public static float FIRE_BENDING_READY_DAMAGE_FACTOR = 0.6f;
	public static float FIRE_BENDING_BURN_SECONDS = 4.0f;
	public static double FIRE_BENDING_KNOCKBACK = 0.6;
	/**
	 * Sight while holding a charged orb (it blocks your view): from HIGHLIGHT_DEGREES of charge on,
	 * mobs and players in front of you glow - only for you. In front = within HIGHLIGHT_ANGLE degrees
	 * of where you look, at most HIGHLIGHT_RANGE blocks away, behind at most HIGHLIGHT_MAX_WALL_BLOCKS solid blocks.
	 */
	public static double FIRE_BENDING_HIGHLIGHT_DEGREES = 750.0;
	public static double FIRE_BENDING_HIGHLIGHT_ANGLE = 50.0;
	public static double FIRE_BENDING_HIGHLIGHT_RANGE = 15.0;
	public static int FIRE_BENDING_HIGHLIGHT_MAX_WALL_BLOCKS = 2;

	/**
	 * Abyssal Bubble (Water, hold the key): a sphere of BUBBLE_RADIUS blocks follows you and drowns
	 * everything inside that breathes air. Their air drops by AIR_DRAIN_PER_TICK a tick (300 = full),
	 * then they take DAMAGE every DAMAGE_INTERVAL_TICKS. You move SLOWNESS slower (0.4 = 40%).
	 * Your camera shakes: SHAKE_START degrees at first, SHAKE_PER_SECOND more every second held.
	 * If your crosshair drifts more than BREAK_DEGREES from where you started, it pops.
	 * Lasts at most MAX_HOLD_TICKS; the cooldown starts when it ends.
	 */
	public static double BUBBLE_RADIUS = 5.0;
	public static int BUBBLE_AIR_DRAIN_PER_TICK = 22;
	public static float BUBBLE_DAMAGE = 2.0f;
	public static int BUBBLE_DAMAGE_INTERVAL_TICKS = 20;
	public static double BUBBLE_SLOWNESS = 0.4;
	public static double BUBBLE_SHAKE_START = 1.5;
	public static double BUBBLE_SHAKE_PER_SECOND = 3.0;
	/** Getting hit while holding moves the spot to hold steady on to where you look then (and calms the shake), so knockback can't pop it. */
	public static boolean BUBBLE_HIT_RECENTERS = true;
	public static double BUBBLE_BREAK_DEGREES = 25.0;
	public static int BUBBLE_MAX_HOLD_TICKS = 20 * 10;
	public static int BUBBLE_COOLDOWN_TICKS = 20 * 45;
	/** The holder alone has Resistance (AMPLIFIER 0 = Resistance I, -1 = none) while holding it, and AFTER_TICKS longer once it ends. */
	public static int BUBBLE_RESISTANCE_AMPLIFIER = 0;
	public static int BUBBLE_RESISTANCE_AFTER_TICKS = 20 * 2;
	/**
	 * The bubble's undertow, on everyone inside it but you: PULL_STRENGTH is added to their speed
	 * toward you every tick (blocks/tick; 0.025 = a gentle drift you can easily walk out of, 0 = off).
	 * OUTWARD_DRAG takes that fraction of their speed away from you off every tick, so leaving is
	 * sluggish (0.35 = 35%, 0 = off, 1 = can't walk out at all). Sideways only, never up or down.
	 */
	public static double BUBBLE_PULL_STRENGTH = 0.025;
	public static double BUBBLE_OUTWARD_DRAG = 0.35;

	/** Heat Wave: radius, damage, burn time, knockback and cooldown. */
	public static double HEAT_WAVE_RADIUS = 4.0;
	public static float HEAT_WAVE_DAMAGE = 2.0f;
	public static float HEAT_WAVE_BURN_SECONDS = 4.0f;
	public static double HEAT_WAVE_KNOCKBACK = 0.8;
	public static int HEAT_WAVE_COOLDOWN_TICKS = 20 * 30;
	/**
	 * When the wave reaches its edge: HEAT_WAVE_RANDOM_FIRES fires at random spots inside it, plus
	 * (HEAT_WAVE_FIRE_UNDER_TARGETS) one where every mob or player it hit was standing.
	 */
	public static int HEAT_WAVE_RANDOM_FIRES = 4;
	public static boolean HEAT_WAVE_FIRE_UNDER_TARGETS = true;

	/**
	 * Inferno Slam (Fire combo): Heat Wave while holding a FULLY charged Fire Bending flame. The flame is
	 * used up (Fire Bending's cooldown starts too). WINDUP_TICKS of arms going up, while you move
	 * WINDUP_SLOWNESS slower (0.6 = 60%), then the slam: a giant Heat Wave - RADIUS, DAMAGE, KNOCKBACK plus
	 * LIFT (upward), BURN_SECONDS of burning, RANDOM_FIRES fires. After it you are stunned for STUN_TICKS:
	 * no moving, jumping, attacking or abilities. ENABLED false = Heat Wave stays normal.
	 */
	public static boolean INFERNO_SLAM_ENABLED = true;
	public static int INFERNO_SLAM_WINDUP_TICKS = 14;
	public static double INFERNO_SLAM_WINDUP_SLOWNESS = 0.6;
	public static int INFERNO_SLAM_STUN_TICKS = 10;
	public static double INFERNO_SLAM_RADIUS = 6.0;
	public static float INFERNO_SLAM_DAMAGE = 7.0f;
	public static double INFERNO_SLAM_KNOCKBACK = 2.2;
	public static double INFERNO_SLAM_LIFT = 0.55;
	public static float INFERNO_SLAM_BURN_SECONDS = 8.0f;
	public static int INFERNO_SLAM_RANDOM_FIRES = 12;

	// ---------------------------------------------------------------
	// Frost (all in aurapoints-frost.json)
	// ---------------------------------------------------------------

	/** Whether /aura roll can give Frost (/aura setaura works either way). */
	public static boolean FROST_ROLLABLE = true;

	/** Whether /aura roll can give Shadow, Water, Fire or Wind (/aura setaura works either way). Each lives in its aura's file. */
	public static boolean SHADOW_ROLLABLE = true;
	public static boolean WATER_ROLLABLE = true;
	public static boolean FIRE_ROLLABLE = true;
	public static boolean WIND_ROLLABLE = true;

	/**
	 * Ice Shell (passive): LAYERS layers of ice, LAYER_HP health each, counted AFTER armor, Protection
	 * and Resistance (and before golden apple absorption hearts). The shell takes ABSORB_FRACTION of
	 * every counting hit (1 = all of it) from its outermost layer until that layer breaks; the rest of
	 * the hit goes on into the next layer, and past the last one into you. ONLY_ATTACKS: only hits from
	 * mobs, players and their projectiles touch it (falls, fire, drowning, ... go straight to you).
	 * Healing starts REGEN_DELAY_TICKS after the last hit on it: a cracked layer is mended over
	 * REPAIR_TICKS (for a whole layer's worth of cracks, so a scratch is quick); a broken layer grows
	 * back over REBUILD_TICKS, one at a time. COLD_ versions apply where it is cold enough to snow.
	 * Every hit you land on a PLAYER (not mobs) counts as PLAYER_HIT_TICKS of healing at once.
	 */
	public static int ICE_SHELL_LAYERS = 3;
	public static float ICE_SHELL_LAYER_HP = 0.75f;
	public static double ICE_SHELL_ABSORB_FRACTION = 0.2;
	public static boolean ICE_SHELL_ONLY_ATTACKS = true;
	public static int ICE_SHELL_REGEN_DELAY_TICKS = 120;
	public static int ICE_SHELL_REPAIR_TICKS = 200;
	public static int ICE_SHELL_COLD_REPAIR_TICKS = 100;
	public static int ICE_SHELL_REBUILD_TICKS = 20 * 30;
	public static int ICE_SHELL_COLD_REBUILD_TICKS = 300;
	public static int ICE_SHELL_PLAYER_HIT_TICKS = 10;

	/**
	 * Shatter (passive): land HITS melee hits in a row within the weapon's time window and the last
	 * one deals BONUS_DAMAGE extra and shatters. A swing that hits nothing, hitting a block, switching
	 * the held item or a hit weaker than MIN_ATTACK_STRENGTH (0..1 attack charge, 0 = any) starts it
	 * over. SAME_TARGET: all hits on the same target. Window per weapon (ticks, first hit to last).
	 */
	public static int SHATTER_HITS = 3;
	public static float SHATTER_BONUS_DAMAGE = 4.0f;
	public static double SHATTER_MIN_ATTACK_STRENGTH = 0.9;
	public static boolean SHATTER_SAME_TARGET = true;
	public static int SHATTER_WINDOW_SWORD_TICKS = 50;
	public static int SHATTER_WINDOW_AXE_TICKS = 70;
	public static int SHATTER_WINDOW_MACE_TICKS = 100;
	public static int SHATTER_WINDOW_TRIDENT_TICKS = 60;
	public static int SHATTER_WINDOW_OTHER_TICKS = 60;

	/**
	 * Ice Spike (active, hold the key): an ice spike forms in your hand while you hold, reaching full
	 * charge after FULL_CHARGE_TICKS; let go to throw it (it throws itself after MAX_HOLD_TICKS).
	 * Damage, speed and the Slowness it gives grow with the charge. Fully charged, it FREEZES what it
	 * hits instead: FREEZE_TICKS of the powder snow freeze (frost on their screen, shaking, freeze
	 * damage) plus Slowness FREEZE_SLOW_AMPLIFIER+1. Any hit gives you Speed HOLDER_SPEED_AMPLIFIER+1
	 * for HOLDER_SPEED_TICKS to catch up. Cooldown starts at the throw.
	 */
	public static int ICE_SPIKE_COOLDOWN_TICKS = 20 * 15;
	public static int ICE_SPIKE_FULL_CHARGE_TICKS = 30;
	public static int ICE_SPIKE_MAX_HOLD_TICKS = 20 * 5;
	public static int ICE_SPIKE_THROW_DELAY_TICKS = 2;
	public static float ICE_SPIKE_DAMAGE_MIN = 3.0f;
	public static float ICE_SPIKE_DAMAGE_MAX = 7.0f;
	public static double ICE_SPIKE_SPEED_MIN = 1.6;
	public static double ICE_SPIKE_SPEED_MAX = 3.0;
	public static double ICE_SPIKE_RANGE = 40.0;
	public static double ICE_SPIKE_HITBOX = 0.3;
	public static double ICE_SPIKE_KNOCKBACK = 0.3;
	public static int ICE_SPIKE_SLOW_TICKS_MIN = 40;
	public static int ICE_SPIKE_SLOW_TICKS_MAX = 80;
	public static int ICE_SPIKE_SLOW_AMPLIFIER = 1;
	public static int ICE_SPIKE_FREEZE_TICKS = 60;
	public static int ICE_SPIKE_FREEZE_SLOW_AMPLIFIER = 3;
	/**
	 * BREAK_ON_HIT: a frozen target that gets hit (by anyone - not by the freeze damage itself) breaks
	 * free at once: the freeze and its Slowness end, and it gets Speed THAW_SPEED_AMPLIFIER+1 for
	 * THAW_SPEED_TICKS (0 ticks = no Speed).
	 */
	public static boolean ICE_SPIKE_FREEZE_BREAK_ON_HIT = true;
	/** FREEZE_LOCK: frozen means frozen - no moving, jumping, turning the camera, attacking, using items or abilities. Off = only slowed. */
	public static boolean ICE_SPIKE_FREEZE_LOCK = true;
	public static int ICE_SPIKE_THAW_SPEED_TICKS = 20;
	public static int ICE_SPIKE_THAW_SPEED_AMPLIFIER = 0;
	public static int ICE_SPIKE_HOLDER_SPEED_TICKS = 60;
	public static int ICE_SPIKE_HOLDER_SPEED_AMPLIFIER = 0;

	// ---------------------------------------------------------------
	// Verdant (all in aurapoints-verdant.json) - first test version
	// ---------------------------------------------------------------

	/** Whether /aura roll can give Verdant (/aura setaura works either way). */
	public static boolean VERDANT_ROLLABLE = true;

	/**
	 * Vine Hook (active): roots reach for the crosshair (RANGE blocks, HITBOX margin around targets),
	 * catch the first mob or player, deal DAMAGE and carry it to right in front of you - through any block.
	 */
	public static int VINE_HOOK_COOLDOWN_TICKS = 20 * 12;
	public static double VINE_HOOK_RANGE = 22.0;
	public static double VINE_HOOK_HITBOX = 0.2;
	public static float VINE_HOOK_DAMAGE = 2.0f;
	/** Where the two roots burst out: SIDE blocks left and right of you, FORWARD blocks in front (negative = behind). */
	public static double VINE_HOOK_ROOT_SIDE = 1.2;
	public static double VINE_HOOK_ROOT_FORWARD = 0.0;
	/**
	 * Timing (ticks): the roots first rip straight up out of the ground (RISE_HEIGHT blocks) in RISE_TICKS,
	 * then lash out to the target in REACH_TICKS, speeding up as they go - WINDUP 1 = steady,
	 * higher = a slower start and a faster snap at the end. A catch carries the target to you over
	 * CARRY_TICKS; then the roots sink back over RETRACT_TICKS.
	 */
	public static int VINE_HOOK_RISE_TICKS = 3;
	/** How high the roots rip straight up out of the ground before they bend over toward the target. */
	public static double VINE_HOOK_RISE_HEIGHT = 2.2;
	/** Until the roots arrive you can drag the aim this many degrees off where you first aimed (0 = no dragging). */
	public static double VINE_HOOK_DRAG_DEGREES = 12.0;
	/**
	 * Forgiving aim: a mob or player within GRAB_RADIUS blocks of your line of aim counts as hit (the one
	 * closest along the line wins); when the roots arrive with nothing locked on, one within TIP_RADIUS of
	 * where they ended is caught anyway. 0 = exact aim only.
	 */
	public static double VINE_HOOK_GRAB_RADIUS = 0.5;
	public static double VINE_HOOK_TIP_RADIUS = 0.5;
	/** After a catch, Entanglement can't be used for this long (so a pulled target can't be caged right away). 0 = no lock. */
	public static int VINE_HOOK_ENTANGLE_LOCK_TICKS = 20 * 4;
	public static int VINE_HOOK_REACH_TICKS = 4;
	public static double VINE_HOOK_WINDUP = 2.0;
	public static int VINE_HOOK_CARRY_TICKS = 8;
	public static int VINE_HOOK_RETRACT_TICKS = 5;

	/**
	 * Entanglement (active): a trap set within RANGE blocks (see TRAP_* below). Whoever it catches - roots grab it where it
	 * stands for GRAB_TICKS, whip it back to that spot over WHIP_TICKS and wind it up in the arms. If nobody is
	 * caught in time, the trap leaves a cloud of Poison (AMPLIFIER 0 = I, 1 = II, for POISON_TICKS; see CLOUD_*).
	 * GRAB_SLOWNESS is unused (left over from an older grab; kept so existing config files stay valid).
	 * A player stays stuck until they break free: CUT_CHANCE of the time by cutting 3 green
	 * roots, else by mashing W A S D - MASH_PRESSES in all, at least MASH_MIN_PER_KEY of each key.
	 * Mobs break free after MOB_TICKS.
	 */
	public static int ENTANGLE_COOLDOWN_TICKS = 20 * 20;
	public static double ENTANGLE_RANGE = 20.0;
	public static int ENTANGLE_POISON_AMPLIFIER = 1;
	public static int ENTANGLE_POISON_TICKS = 20 * 10;
	/** GRAB_TICKS: every arm seizes the caught one (short = violent); WHIP_TICKS: it's yanked into the middle while they wind round it. */
	public static int ENTANGLE_GRAB_TICKS = 4;
	public static int ENTANGLE_WHIP_TICKS = 6;
	public static double ENTANGLE_GRAB_SLOWNESS = 0.6;
	public static double ENTANGLE_CUT_CHANCE = 0.5;
	public static int ENTANGLE_MASH_PRESSES = 24;
	public static int ENTANGLE_MASH_MIN_PER_KEY = 4;
	public static int ENTANGLE_MOB_TICKS = 20 * 5;
	/**
	 * The trap: a nest of roots TRAP_RADIUS blocks round that grows out of the ground over TRAP_ARM_TICKS (only a
	 * warning, it catches nobody yet), then waits TRAP_TICKS for someone to step in before it sinks back.
	 */
	public static double ENTANGLE_TRAP_RADIUS = 1.3;
	public static int ENTANGLE_TRAP_ARM_TICKS = 12;
	public static int ENTANGLE_TRAP_TICKS = 20 * 15;
	/** How long (ticks) someone has to stay inside the armed trap in a row before it springs; stepping out resets it. */
	public static int ENTANGLE_TRAP_STAY_TICKS = 10;
	/** How many root arms burst out to wrap up whoever is caught (3 of them turn green in "cut the roots" mode). */
	public static int ENTANGLE_ARMS = 11;
	/** Arm size (length and thickness; wound round someone they only grow a third as much) and how fast they thrash (1 = original). */
	public static double ENTANGLE_ARM_SCALE = 1.4;
	public static double ENTANGLE_THRASH_SPEED = 0.6;
	/**
	 * Hitting someone wrapped up (anyone's hit) loosens the roots: an arm tears loose, MASH mode gets HIT_MASH_PER_KEY
	 * presses on every key, and from the HIT_CHANCE_FROM-th hit on, each hit frees them at once with HIT_FREE_CHANCE.
	 */
	public static int ENTANGLE_HIT_MASH_PER_KEY = 1;
	public static int ENTANGLE_HIT_CHANCE_FROM = 3;
	public static double ENTANGLE_HIT_FREE_CHANCE = 0.35;
	/** Nobody caught before the trap runs out: a cloud of poison (POISON_AMPLIFIER, POISON_TICKS per breath) CLOUD_RADIUS round, for CLOUD_TICKS. 0 = none. */
	public static double ENTANGLE_CLOUD_RADIUS = 2.5;
	public static int ENTANGLE_CLOUD_TICKS = 20 * 6;

	/**
	 * Verdant Viper (active): a snake head that follows your crosshair over the ground at SPEED blocks a tick
	 * (0.95 = 19 a second), turning at most TURN_DEGREES a tick, up to RANGE blocks from you, leaving a root
	 * trail. Its bite (anything within BITE_RADIUS) deals DAMAGE with Poison (AMPLIFIER, TICKS) and ends it.
	 * While it's out you can't move, be hurt, knocked back, eat or heal. Press the key again to call it back
	 * once it has been out CANCEL_AFTER_TICKS. If another player kills it (HITS hits) you take KILL_DAMAGE and are stunned: STUN_MAX_TICKS if killed at once, down to STUN_MIN_TICKS if it lived
	 * STUN_SCALE_TICKS or longer. The trail stays TRAIL_STAY_TICKS after it ends, then withers away.
	 */
	public static int VIPER_COOLDOWN_TICKS = 20 * 25;
	public static double VIPER_RANGE = 400.0;
	public static double VIPER_SPEED = 0.95;
	public static double VIPER_TURN_DEGREES = 45.0;
	/** Walls up to CLIMB_HEIGHT blocks it climbs, straight up their face at CLIMB_SPEED blocks a tick (0.85 = 17 a second); taller ones stop it. */
	public static double VIPER_CLIMB_HEIGHT = 2.0;
	public static double VIPER_CLIMB_SPEED = 0.85;
	/** Size of the viper's head (1 = full size; its hitbox shrinks with it, down to a hittable minimum). */
	public static double VIPER_SCALE = 0.9;
	/** How thick the viper's root trail is, in blocks (a torch is 0.125). */
	public static double VIPER_TRAIL_WIDTH = 0.25;
	public static float VIPER_DAMAGE = 3.0f;
	public static int VIPER_POISON_AMPLIFIER = 1;
	public static int VIPER_POISON_TICKS = 20 * 10;
	public static double VIPER_BITE_RADIUS = 1.3;
	/** A mob or player within this many blocks of your crosshair line (up to 96 blocks out) is homed in on. */
	public static double VIPER_AIM_ASSIST = 1.5;
	public static int VIPER_HITS = 1;
	public static int VIPER_CANCEL_AFTER_TICKS = 20 * 10;
	public static float VIPER_KILL_DAMAGE = 3.0f;
	public static int VIPER_STUN_MAX_TICKS = 20 * 5;
	public static int VIPER_STUN_MIN_TICKS = 20;
	public static int VIPER_STUN_SCALE_TICKS = 20 * 10;
	public static int VIPER_TRAIL_STAY_TICKS = 40;
	public static int VIPER_TRAIL_MAX = 160;

	/** Bloom (passive): while sneaking, every INTERVAL_TICKS up to PLANTS_PER_PULSE plants within RADIUS blocks get bone meal. */
	public static int BLOOM_INTERVAL_TICKS = 20;
	public static int BLOOM_RADIUS = 4;
	public static int BLOOM_PLANTS_PER_PULSE = 2;

	/** Virulence (passive): poisoned targets take this much more damage (0.25 = +25%) from the Verdant player's hits and abilities. */
	public static double VIRULENCE_BONUS = 0.25;

	// ---------------------------------------------------------------
	// Thunder (all in aurapoints-thunder.json) - first test version
	// ---------------------------------------------------------------

	/** Whether /aura roll can give Thunder (/aura setaura works either way). */
	public static boolean THUNDER_ROLLABLE = true;

	/** Whether /aura roll can give Resonance (/aura setaura works either way). */
	public static boolean RESONANCE_ROLLABLE = true;

	/**
	 * Thunderbolt of Zeus (active), three throws per use. The first press leaps you up (LEAP_VELOCITY) and,
	 * LEAP_TICKS later, you hang in the air (HOVER; no walking, camera free) until the combo ends. Throw 1
	 * goes off FIRST_THROW_DELAY_TICKS after the press: a hitscan bolt up to RANGE blocks (HITBOX margin
	 * around targets) for DAMAGE, arcing on to CHAIN_TARGETS enemies within CHAIN_RADIUS of each other for
	 * CHAIN_DAMAGE_FRACTION of it. Throw 2: the same, quicker, for QUICK_DAMAGE. Throws need THROW_GAP_TICKS
	 * between them; wait over COMBO_TICKS and the combo ends. Throw 3, Wrath of Zeus: WRATH_WINDUP_TICKS
	 * while a cloud (WRATH_CLOUD_HEIGHT up) gathers over the aim, then WRATH_DAMAGE at the middle down to
	 * WRATH_EDGE_FRACTION of it at WRATH_RADIUS, thrown back (KNOCKBACK, LIFT) and paralyzed PARALYZE_TICKS
	 * (+ PARALYZE_PER_CHARGE_TICKS per charge; players stunned, mobs slowed); WRATH_EXTRA_BOLTS more bolts
	 * hit around it (EXTRA_DAMAGE within EXTRA_RADIUS). Every bolt hit Charges the target (up to MAX_CHARGES,
	 * fading after CHARGE_TICKS): Wrath adds OVERLOAD_DAMAGE per charge. VANILLA_LIGHTNING: the sky bolts are
	 * real (visual-only) lightning with its flash and thunder. After the combo: Slow Falling for DESCENT_TICKS.
	 */
	public static int THUNDERBOLT_COOLDOWN_TICKS = 20 * 20;
	public static double THUNDERBOLT_RANGE = 48.0;
	public static double THUNDERBOLT_HITBOX = 0.5;
	public static boolean THUNDERBOLT_HOVER = true;
	public static double THUNDERBOLT_LEAP_VELOCITY = 0.8;
	public static int THUNDERBOLT_LEAP_TICKS = 6;
	public static int THUNDERBOLT_FIRST_THROW_DELAY_TICKS = 6;
	public static int THUNDERBOLT_THROW_GAP_TICKS = 8;
	public static int THUNDERBOLT_COMBO_TICKS = 50;
	public static float THUNDERBOLT_DAMAGE = 4.0f;
	public static float THUNDERBOLT_QUICK_DAMAGE = 3.0f;
	public static int THUNDERBOLT_CHAIN_TARGETS = 2;
	public static double THUNDERBOLT_CHAIN_RADIUS = 5.0;
	public static float THUNDERBOLT_CHAIN_DAMAGE_FRACTION = 0.5f;
	public static int THUNDERBOLT_WRATH_WINDUP_TICKS = 12;
	public static double THUNDERBOLT_WRATH_CLOUD_HEIGHT = 9.0;
	public static float THUNDERBOLT_WRATH_DAMAGE = 8.0f;
	public static double THUNDERBOLT_WRATH_EDGE_FRACTION = 0.35;
	public static double THUNDERBOLT_WRATH_RADIUS = 4.5;
	public static double THUNDERBOLT_WRATH_KNOCKBACK = 1.0;
	public static double THUNDERBOLT_WRATH_LIFT = 0.5;
	public static int THUNDERBOLT_WRATH_EXTRA_BOLTS = 4;
	public static float THUNDERBOLT_WRATH_EXTRA_DAMAGE = 2.0f;
	public static double THUNDERBOLT_WRATH_EXTRA_RADIUS = 1.5;
	public static int THUNDERBOLT_PARALYZE_TICKS = 10;
	public static int THUNDERBOLT_PARALYZE_PER_CHARGE_TICKS = 8;
	public static int THUNDERBOLT_MAX_CHARGES = 3;
	public static int THUNDERBOLT_CHARGE_TICKS = 20 * 6;
	public static float THUNDERBOLT_OVERLOAD_DAMAGE = 2.0f;
	public static boolean THUNDERBOLT_VANILLA_LIGHTNING = true;
	public static int THUNDERBOLT_DESCENT_TICKS = 40;

	/**
	 * Chidori (active, hold key): charges over CHARGE_TICKS while you walk CHARGE_SLOWNESS slower; auto-release
	 * after MAX_HOLD_TICKS. Released under MIN_CHARGE it fizzles (FIZZLE_COOLDOWN_TICKS). Else a lunge along your
	 * look (pitch scaled by PITCH_FACTOR) at MIN..MAX_SPEED blocks a tick for MIN..MAX_DISTANCE blocks (by charge);
	 * the first mob or player within HITBOX of the path takes MIN..MAX_DAMAGE, gets CHARGES charges (one less
	 * if not full), is paralyzed PARALYZE_MIN..MAX_TICKS and shoved on by KNOCKBACK.
	 */
	public static int CHIDORI_COOLDOWN_TICKS = 20 * 14;
	public static int CHIDORI_FIZZLE_COOLDOWN_TICKS = 40;
	public static int CHIDORI_CHARGE_TICKS = 30;
	public static int CHIDORI_MAX_HOLD_TICKS = 100;
	public static double CHIDORI_MIN_CHARGE = 0.3;
	public static double CHIDORI_CHARGE_SLOWNESS = 0.5;
	public static double CHIDORI_PITCH_FACTOR = 0.4;
	public static double CHIDORI_MIN_SPEED = 1.3;
	public static double CHIDORI_MAX_SPEED = 2.2;
	public static double CHIDORI_MIN_DISTANCE = 6.0;
	public static double CHIDORI_MAX_DISTANCE = 14.0;
	public static double CHIDORI_HITBOX = 0.6;
	public static float CHIDORI_MIN_DAMAGE = 4.0f;
	public static float CHIDORI_MAX_DAMAGE = 10.0f;
	public static int CHIDORI_CHARGES = 2;
	public static int CHIDORI_PARALYZE_MIN_TICKS = 10;
	public static int CHIDORI_PARALYZE_MAX_TICKS = 30;
	public static double CHIDORI_KNOCKBACK = 0.8;

	/**
	 * Mjölnir (test item, owner only): hold the Mjölnir Swing key (rebindable) to spin it; after SPIN_TICKS lightning strikes it (SKY_LIGHTNING:
	 * a real visual-only bolt too) and it is charged for CHARGED_TICKS. While charged, STREAK_HITS full hits in a row
	 * (each within STREAK_WINDOW_TICKS of the last) call a thunder strike on the last target: STRIKE_DAMAGE to it,
	 * SPLASH_DAMAGE to everyone within SPLASH_RADIUS, all thrown (KNOCKBACK) and paralyzed PARALYZE_TICKS.
	 */
	public static int MJOLNIR_SPIN_TICKS = 40;
	public static int MJOLNIR_CHARGED_TICKS = 20 * 20;
	public static boolean MJOLNIR_SKY_LIGHTNING = true;
	public static int MJOLNIR_STREAK_HITS = 3;
	public static int MJOLNIR_STREAK_WINDOW_TICKS = 40;
	public static float MJOLNIR_STRIKE_DAMAGE = 8.0f;
	public static float MJOLNIR_SPLASH_DAMAGE = 4.0f;
	public static double MJOLNIR_SPLASH_RADIUS = 3.5;
	public static double MJOLNIR_KNOCKBACK = 0.9;
	public static int MJOLNIR_PARALYZE_TICKS = 20;
	/**
	 * Throwing (right-click): flies THROW_SPEED blocks a tick (x SPUN_THROW_BOOST if thrown while spinning) up to
	 * THROW_RANGE, THROW_DAMAGE to the first mob or player (a charged hammer calls the thunder strike instead),
	 * RETURN_DAMAGE to whoever it passes on the way back.
	 */
	public static double MJOLNIR_THROW_SPEED = 1.6;
	public static double MJOLNIR_SPUN_THROW_BOOST = 1.5;
	public static double MJOLNIR_THROW_RANGE = 32.0;
	public static float MJOLNIR_THROW_DAMAGE = 7.0f;
	public static float MJOLNIR_RETURN_DAMAGE = 3.0f;
	/**
	 * Flight (spin, then HOLD left-click): FLIGHT_SPEED blocks a tick toward wherever you look, as long as the button is held
	 * (at most FLIGHT_MAX_TICKS); FLIGHT_STEERING is how fast the heading follows your aim (0.05 lazy .. 1 instant);
	 * FLIGHT_DAMAGE to whoever is in the way; FLIGHT_COOLDOWN_TICKS after you let go.
	 */
	public static double MJOLNIR_FLIGHT_SPEED = 1.5;
	public static double MJOLNIR_FLIGHT_STEERING = 0.35;
	public static int MJOLNIR_FLIGHT_MAX_TICKS = 20 * 30;
	public static float MJOLNIR_FLIGHT_DAMAGE = 6.0f;
	public static int MJOLNIR_FLIGHT_COOLDOWN_TICKS = 40;

	/** Static Charge (passive): every HITS-th charged melee hit deals DAMAGE, arcs on to ARC_TARGETS more and Charges them. */
	public static int STATIC_CHARGE_HITS = 4;
	public static float STATIC_CHARGE_DAMAGE = 2.0f;
	public static int STATIC_CHARGE_ARC_TARGETS = 2;

	/** Stormborn (passive): immune to lightning; in a thunderstorm (under open sky) lightning DAMAGE_BONUS, cooldown REDUCTION, EXTRA_BOLTS for Wrath. */
	public static double STORMBORN_DAMAGE_BONUS = 0.35;
	public static double STORMBORN_COOLDOWN_REDUCTION = 0.35;
	public static int STORMBORN_EXTRA_BOLTS = 3;

	// ---------------------------------------------------------------
	// Puffish integration
	// ---------------------------------------------------------------

	/** QOL skill tree (always unlocked). /aura redeem buys points here. */
	public static final String PUFFISH_CATEGORY_MAIN = "aura";

	/** PvP skill tree (locked until a boss in #aurapoints:bosses dies). /aura redeem pvp buys points here once unlocked. */

	// ---------------------------------------------------------------
	// Resonance (aurapoints-resonance.json)
	// ---------------------------------------------------------------

	/**
	 * The noise meter (0..100) every player and mob near a Resonance player or plant has. Things you keep doing set a
	 * floor while you do them (WALK, SPRINT, USE_ITEM = eating/bow/shield, ABILITY_HOLD = an ability key held); things that
	 * happen add a burst (JUMP, LAND + LAND_PER_BLOCK fallen, BLOCK placed/broken, ATTACK, HURT, ABILITY used, DOOR =
	 * doors, trapdoors, gates, chests). It drains DECAY_PER_TICK. LOUD or more = heard. Sneaking or standing still = 0.
	 */
	public static float NOISE_LOUD = 10.0f;
	public static float NOISE_DECAY_PER_TICK = 2.0f;
	public static float NOISE_WALK = 25.0f;
	public static float NOISE_SPRINT = 55.0f;
	public static float NOISE_USE_ITEM = 40.0f;
	public static float NOISE_ABILITY_HOLD = 45.0f;
	public static float NOISE_JUMP = 35.0f;
	public static float NOISE_LAND = 20.0f;
	public static float NOISE_LAND_PER_BLOCK = 6.0f;
	public static float NOISE_BLOCK = 35.0f;
	public static float NOISE_ATTACK = 40.0f;
	public static float NOISE_HURT = 30.0f;
	public static float NOISE_ABILITY = 60.0f;
	public static float NOISE_DOOR = 30.0f;
	public static double NOISE_LISTEN_RANGE = 48.0;

	/**
	 * Echolocation (passive): a violet fog from FOG_START to FOG_END blocks (instead of Blindness: you can still sprint),
	 * and every noisy entity within RANGE glows for you through walls; a Sonic Blossom dart keeps its target glowing
	 * MARK_TICKS, noise or not.
	 */
	public static double ECHO_RANGE = 48.0;
	public static float ECHO_FOG_START = 6.0f;
	public static float ECHO_FOG_END = 18.0f;
	public static int ECHO_MARK_TICKS = 100;

	/** Sonic Blossom: planted below you, MAX at once, lives TICKS; darts every SHOT_TICKS at noisy targets within RANGE. */
	public static int BLOSSOM_COOLDOWN_TICKS = 8 * 20;
	public static int BLOSSOM_MAX = 3;
	public static int BLOSSOM_TICKS = 60 * 20;
	public static double BLOSSOM_RANGE = 8.0;
	/** How loud someone must be for a blossom to shoot (walking 25, sprinting 55 - so sprinting or louder). */
	public static float BLOSSOM_MIN_NOISE = 50.0f;
	public static int BLOSSOM_SHOT_TICKS = 10;
	public static float BLOSSOM_DART_DAMAGE = 2.0f;
	public static double BLOSSOM_DART_SPEED = 1.3;

	/**
	 * Listening Tree: planted up to PLANT_RANGE away, MAX at once, lives TICKS. Grabs the loudest within RANGE (closer wins
	 * a tie) for HOLD_TICKS, then rests REGRAB_TICKS. Held: free within EASY blocks of the trunk, pulled back harder and
	 * harder (PULL = the pull at the edge) up to LEASH blocks, never further.
	 */
	public static int LISTENING_TREE_COOLDOWN_TICKS = 15 * 20;
	public static double LISTENING_TREE_PLANT_RANGE = 6.0;
	public static int LISTENING_TREE_MAX = 3;
	public static int LISTENING_TREE_TICKS = 60 * 20;
	public static double LISTENING_TREE_RANGE = 6.0;
	public static int LISTENING_TREE_HOLD_TICKS = 80;
	public static int LISTENING_TREE_REGRAB_TICKS = 120;
	public static double LISTENING_TREE_EASY = 1.0;
	public static double LISTENING_TREE_LEASH = 3.0;
	public static double LISTENING_TREE_PULL = 0.32;
	/** Hits it takes (from anyone but its planter) to break a Listening Tree. */
	public static int LISTENING_TREE_HITS = 3;

	/**
	 * Abyssal Grasp: the tentacles reach RANGE blocks (HITBOX = how forgiving the aim is) over REACH_TICKS, seize the first
	 * mob or player (DAMAGE) and pull it in over PULL_TICKS to HOLD_DISTANCE in front of you; held HOLD_TICKS at most, then
	 * thrown (THROW_STRENGTH where you look, THROW_LIFT up); hitting a wall while flying: WALL_DAMAGE. A miss: MISS_COOLDOWN_TICKS.
	 */
	public static int GRASP_COOLDOWN_TICKS = 25 * 20;
	public static int GRASP_MISS_COOLDOWN_TICKS = 5 * 20;
	/** Hold to charge: the reach grows from MIN_RANGE to RANGE over CHARGE_TICKS; you walk CHARGE_SLOWNESS slower and the view zooms to ZOOM x the FOV. */
	public static double GRASP_RANGE = 14.0;
	public static double GRASP_MIN_RANGE = 4.0;
	public static int GRASP_CHARGE_TICKS = 30;
	public static double GRASP_CHARGE_SLOWNESS = 0.5;
	public static float GRASP_ZOOM = 0.85f;
	public static double GRASP_HITBOX = 0.6;
	public static int GRASP_REACH_TICKS = 4;
	public static int GRASP_PULL_TICKS = 16;
	public static int GRASP_HOLD_TICKS = 80;
	/** How long after the catch before a press can throw (the tentacles need a moment to wind up). */
	public static int GRASP_THROW_DELAY_TICKS = 20;
	public static double GRASP_HOLD_DISTANCE = 2.0;
	public static float GRASP_DAMAGE = 3.0f;
	public static double GRASP_THROW_STRENGTH = 2.0;
	public static double GRASP_THROW_LIFT = 0.45;
	public static float GRASP_WALL_DAMAGE = 4.0f;

	/** Sonic Blossom planting: you grab the ground for WINDUP_TICKS (slowed by WINDUP_SLOWNESS), then it grows below you. */
	public static int BLOSSOM_WINDUP_TICKS = 12;
	public static double BLOSSOM_WINDUP_SLOWNESS = 0.6;

	// ---------------------------------------------------------------
	// Handhelds (aurapoints-handhelds.json): one weapon per aura, plain hits, one ability each
	// (right-click, or the Item Use key; the World Tree Bow: sneak + right-click). Only a player of
	// the weapon's aura can use the ability while HANDHELD_AURA_ONLY is on.
	// ---------------------------------------------------------------

	public static boolean HANDHELD_AURA_ONLY = true;

	/**
	 * Maelstrom (Water, Leviathan's Anchor): TENTACLES kraken tentacles break out within RADIUS blocks, every enemy
	 * in there is dragged into a whirlpool round you for PULL_TICKS (PULL_STRENGTH inward, SPIN_STRENGTH round)
	 * with Slowness SLOW_AMPLIFIER, then a giant tentacle slams down: DAMAGE within SLAM_RADIUS.
	 */
	public static int MAELSTROM_COOLDOWN_TICKS = 30 * 20;
	public static double MAELSTROM_RADIUS = 8.0;
	public static int MAELSTROM_TENTACLES = 6;
	public static int MAELSTROM_PULL_TICKS = 60;
	public static double MAELSTROM_PULL_STRENGTH = 0.12;
	public static double MAELSTROM_SPIN_STRENGTH = 0.18;
	public static int MAELSTROM_SLOW_AMPLIFIER = 2;
	public static float MAELSTROM_DAMAGE = 10.0f;
	public static double MAELSTROM_SLAM_RADIUS = 3.5;
	public static double MAELSTROM_KNOCKUP = 0.6;

	/**
	 * Sunfall (Fire, Phoenix Fangs): point at a spot up to RANGE away, the sky over it burns red, after DELAY_TICKS
	 * a meteor crashes down: DAMAGE at the centre, falling to EDGE_DAMAGE_FRACTION at RADIUS, BURN_SECONDS on fire,
	 * knocked back; the crater glows with lava for CRATER_TICKS (CRATER_DAMAGE every second to anyone in it) and
	 * FIRES fires break out (they go out after AURA_FIRE_SECONDS).
	 */
	public static int SUNFALL_COOLDOWN_TICKS = 35 * 20;
	public static double SUNFALL_RANGE = 40.0;
	public static int SUNFALL_DELAY_TICKS = 30;
	public static float SUNFALL_DAMAGE = 12.0f;
	public static float SUNFALL_EDGE_DAMAGE_FRACTION = 0.35f;
	public static double SUNFALL_RADIUS = 5.0;
	public static double SUNFALL_KNOCKBACK = 1.2;
	public static int SUNFALL_BURN_SECONDS = 5;
	public static int SUNFALL_CRATER_TICKS = 100;
	public static float SUNFALL_CRATER_DAMAGE = 2.0f;
	public static int SUNFALL_FIRES = 10;

	/**
	 * Eye of the Storm (Wind, Stormcaller Glaive): the glaive spins out as a tornado for TICKS at SPEED blocks a tick
	 * along the ground where you look, dragging everything within PULL_RADIUS into it (PULL_STRENGTH) and hitting
	 * whoever is in its core (CORE_DAMAGE every 10 ticks); at the end it bursts: BLAST_DAMAGE and everyone within
	 * BLAST_RADIUS thrown BLAST_STRENGTH away and BLAST_LIFT up. Then the glaive flies back to you.
	 */
	public static int STORM_EYE_COOLDOWN_TICKS = 35 * 20;
	public static int STORM_EYE_TICKS = 120;
	public static double STORM_EYE_SPEED = 0.22;
	public static double STORM_EYE_PULL_RADIUS = 6.0;
	public static double STORM_EYE_PULL_STRENGTH = 0.14;
	public static float STORM_EYE_CORE_DAMAGE = 1.0f;
	public static float STORM_EYE_BLAST_DAMAGE = 6.0f;
	public static double STORM_EYE_BLAST_RADIUS = 5.0;
	public static double STORM_EYE_BLAST_STRENGTH = 2.6;
	public static double STORM_EYE_BLAST_LIFT = 1.1;

	/**
	 * Umbral Ambush (Shadow, Soul Reaper): you turn to shadow and appear behind the nearest enemy within RANGE,
	 * invisible for INVIS_TICKS. A shadow clone stays where you stood for CLONE_TICKS and strikes enemies within
	 * CLONE_REACH (CLONE_DAMAGE every CLONE_ATTACK_TICKS, gliding CLONE_SPEED toward them); your next melee hit
	 * within BONUS_TICKS deals BONUS_DAMAGE more.
	 */
	public static int UMBRAL_COOLDOWN_TICKS = 25 * 20;
	public static double UMBRAL_RANGE = 16.0;
	public static int UMBRAL_INVIS_TICKS = 60;
	public static int UMBRAL_CLONE_TICKS = 100;
	public static double UMBRAL_CLONE_REACH = 6.0;
	public static double UMBRAL_CLONE_SPEED = 0.2;
	public static float UMBRAL_CLONE_DAMAGE = 3.0f;
	public static int UMBRAL_CLONE_ATTACK_TICKS = 20;
	public static int UMBRAL_BONUS_TICKS = 100;
	public static float UMBRAL_BONUS_DAMAGE = 6.0f;

	/**
	 * Thorn Sentinel (Verdant, World Tree Bow): sneak + right-click shoots a seed (SEED_SPEED, up to SEED_RANGE);
	 * where it lands a thorn tree grows and for TICKS shoots a thorn every SHOT_TICKS at the nearest enemy within
	 * REACH: SHOT_DAMAGE each, Poison POISON_AMPLIFIER for POISON_TICKS (-1 amplifier = none).
	 */
	public static int THORN_SENTINEL_COOLDOWN_TICKS = 40 * 20;
	public static double THORN_SENTINEL_SEED_SPEED = 1.6;
	public static double THORN_SENTINEL_SEED_RANGE = 40.0;
	public static int THORN_SENTINEL_TICKS = 200;
	public static int THORN_SENTINEL_SHOT_TICKS = 15;
	public static double THORN_SENTINEL_REACH = 10.0;
	public static float THORN_SENTINEL_SHOT_DAMAGE = 2.5f;
	public static int THORN_SENTINEL_POISON_AMPLIFIER = 0;
	public static int THORN_SENTINEL_POISON_TICKS = 40;

	/**
	 * Winter's Gate (Frost, Absolute Zero): an ice wall WIDTH x HEIGHT with spikes rises DISTANCE blocks in front of you
	 * for TICKS. It stops projectiles and holds back whoever walks into it - they freeze for FREEZE_TICKS (Ice Spike's
	 * freeze). Then it shatters: shards fly out, SHARD_DAMAGE to everyone within SHARD_RADIUS of the wall.
	 */
	public static int WINTERS_GATE_COOLDOWN_TICKS = 30 * 20;
	public static double WINTERS_GATE_DISTANCE = 3.0;
	public static int WINTERS_GATE_WIDTH = 7;
	public static int WINTERS_GATE_HEIGHT = 4;
	public static int WINTERS_GATE_TICKS = 160;
	public static int WINTERS_GATE_FREEZE_TICKS = 40;
	public static float WINTERS_GATE_SHARD_DAMAGE = 6.0f;
	public static double WINTERS_GATE_SHARD_RADIUS = 6.0;

	/**
	 * Chain Strike (Thunder, Zeus Gauntlets): you turn into lightning and jump from enemy to enemy, up to TARGETS:
	 * the first within RANGE of your crosshair, each next within HOP_RANGE of the last, HOP_TICKS apart, DAMAGE each;
	 * on the last a giant bolt strikes: FINAL_DAMAGE to it and FINAL_SPLASH_DAMAGE within FINAL_RADIUS, paralyzed
	 * PARALYZE_TICKS.
	 */
	public static int CHAIN_STRIKE_COOLDOWN_TICKS = 25 * 20;
	public static double CHAIN_STRIKE_RANGE = 20.0;
	public static double CHAIN_STRIKE_HOP_RANGE = 10.0;
	public static int CHAIN_STRIKE_TARGETS = 3;
	public static int CHAIN_STRIKE_HOP_TICKS = 5;
	public static float CHAIN_STRIKE_DAMAGE = 4.0f;
	public static float CHAIN_STRIKE_FINAL_DAMAGE = 6.0f;
	public static float CHAIN_STRIKE_FINAL_SPLASH_DAMAGE = 3.0f;
	public static double CHAIN_STRIKE_FINAL_RADIUS = 3.0;
	public static int CHAIN_STRIKE_PARALYZE_TICKS = 20;

	// ---------------------------------------------------------------
	// Aura Arena (the arena dimension: duels and training grounds)
	// ---------------------------------------------------------------

	/** false: /aura arena and duels are switched off (players already inside can still /aura arena leave). */
	public static boolean ARENA_ENABLED = true;

	/** Duels: rounds when none are given, and the most a challenge may ask for. A tie after the last one adds a sudden-death round. */
	public static int DUEL_DEFAULT_ROUNDS = 3;
	public static int DUEL_MAX_ROUNDS = 9;
	/** After a duel is accepted both fighters vote for the map (ring layout) for this many seconds; 0 = no vote, a random layout. */
	public static int DUEL_VOTE_SECONDS = 15;
	/** Aura the winner of a duel gets on top of the wager pot (0 = only the pot). */
	public static int DUEL_PRIZE_AURA = 50;
	/** The highest wager a challenge may name (each player puts it in; the winner takes both). */
	public static int DUEL_MAX_WAGER = 1000;
	/** How long the loser of a round watches as a spectator before the next round starts. */
	public static int DUEL_KNOCKOUT_SECONDS = 3;
	/**
	 * Arena matches (/aura arena match): Team Battle, Free-for-All, King of the Hill. LOBBY_SECONDS: an open match closes if not
	 * started by then. ROUNDS: best of, for teams and free-for-all. PRIZE_AURA: to each winner. SPAWN_RADIUS: the circle
	 * they start on round the ring's middle. KOTH: TARGET points (a second alone on the hill each) wins, or the most
	 * after SECONDS; the hill's RADIUS; knocked out = back after RESPAWN_SECONDS.
	 */
	public static boolean MATCH_ENABLED = true;
	public static int MATCH_LOBBY_SECONDS = 180;
	public static int MATCH_MAX_PLAYERS = 8;
	public static int MATCH_ROUNDS = 3;
	public static int MATCH_PRIZE_AURA = 30;
	public static double MATCH_SPAWN_RADIUS = 17.0;
	public static int MATCH_KOTH_TARGET = 45;
	public static int MATCH_KOTH_SECONDS = 180;
	public static double MATCH_KOTH_RADIUS = 4.0;
	public static int MATCH_KOTH_RESPAWN_SECONDS = 4;
	/**
	 * Wave Survival (/aura arena match create waves): endless waves in the ring for 1-8 players. A wave's mob budget is
	 * BUDGET_BASE + BUDGET_PER_WAVE x wave, times (1 + EXTRA_PLAYER_BONUS) for each player past the first (halved on a boss
	 * wave). At most MAX_ALIVE at once, the rest come in as others die. Every BOSS_EVERY-th wave a champion with
	 * BOSS_HEALTH_BASE + BOSS_HEALTH_PER_WAVE x wave health (more per extra player, like the budget). TOUCHED: share of aura-touched mobs from wave TOUCHED_FROM (START, rising to MAX).
	 * SCALE: from wave SCALE_FROM, +SCALE_PER_WAVE health and damage per wave. A cleared wave pays AURA_PER_WAVE (+ BOSS_BONUS)
	 * and MASTERY_XP to everyone in it; then BREAK_SECONDS of rest. COOLDOWN_MULTIPLIER: ability cooldowns while in a run
	 * (0.5 = half). GLOW_AFTER_SECONDS: the last three mobs of a wave glow after this long.
	 */
	public static boolean WAVES_ENABLED = true;
	public static int WAVES_BREAK_SECONDS = 8;
	/** The break after a boss wave: longer, since that's when players may switch aura (WAVES_AURA_SWITCH, arena/WaveAuras). */
	public static int WAVES_BOSS_BREAK_SECONDS = 20;
	public static boolean WAVES_AURA_SWITCH = true;
	public static int WAVES_BUDGET_BASE = 6;
	public static int WAVES_BUDGET_PER_WAVE = 3;
	public static double WAVES_EXTRA_PLAYER_BONUS = 0.5;
	public static int WAVES_MAX_ALIVE = 24;
	public static int WAVES_BOSS_EVERY = 5;
	public static int WAVES_BOSS_HEALTH_BASE = 150;
	public static int WAVES_BOSS_HEALTH_PER_WAVE = 15;
	public static int WAVES_TOUCHED_FROM = 3;
	public static double WAVES_TOUCHED_START = 0.10;
	public static double WAVES_TOUCHED_MAX = 0.50;
	public static int WAVES_SCALE_FROM = 10;
	public static double WAVES_SCALE_PER_WAVE = 0.05;
	public static int WAVES_AURA_PER_WAVE = 2;
	public static int WAVES_BOSS_BONUS = 10;
	public static int WAVES_MASTERY_XP = 15;
	public static double WAVES_COOLDOWN_MULTIPLIER = 0.5;
	/** Run modifiers (picked when the run is opened): Swift = mobs this much faster, Brutal = this much more damage, Tough = this much more health. */
	public static double WAVES_MOD_SPEED = 0.3;
	public static double WAVES_MOD_DAMAGE = 0.5;
	public static double WAVES_MOD_HEALTH = 0.5;
	/** Extra Aura per wave for the three hard modifiers (0.25 = +25%). Modified runs never set records. */
	public static double WAVES_MOD_AURA_BONUS = 0.25;
	/** Flow: the cooldown multiplier is multiplied by this on top of WAVES_COOLDOWN_MULTIPLIER. */
	public static double WAVES_MOD_FLOW_COOLDOWN = 0.5;
	public static int WAVES_GLOW_AFTER_SECONDS = 30;
	/** The 3-2-1 countdown on the pads before each round. */
	public static int DUEL_COUNTDOWN_SECONDS = 3;
	/** A challenge nobody answers runs out after this long. */
	public static int DUEL_CHALLENGE_TIMEOUT_SECONDS = 60;

	/**
	 * Ultimates (ability/Ultimates): one per aura, unlocked by the Awakening quest (Mastery V), on the Ultimate key.
	 * All share ULTIMATE_COOLDOWN_SECONDS.
	 */
	public static int ULTIMATE_COOLDOWN_SECONDS = 300;
	/** Shadow - Eclipse: darkness on enemy players within RANGE, then you blink behind up to TARGETS enemies, one strike each. */
	public static double ULT_ECLIPSE_RANGE = 16.0;
	public static int ULT_ECLIPSE_TARGETS = 5;
	public static float ULT_ECLIPSE_DAMAGE = 7.0f;
	public static int ULT_ECLIPSE_DARKNESS_TICKS = 140;
	/** Water - Tidal Ascension: carried up on a column of water, then a ring wave crashes out to RADIUS; heals you HEAL. */
	public static double ULT_TIDAL_RADIUS = 10.0;
	public static float ULT_TIDAL_DAMAGE = 9.0f;
	public static double ULT_TIDAL_KNOCKBACK = 1.4;
	public static float ULT_TIDAL_HEAL = 8.0f;
	/** Fire - Phoenix: fly for TICKS (sneak to dive early) burning whoever you pass, then dive into a blast. No block damage. */
	public static int ULT_PHOENIX_TICKS = 100;
	public static double ULT_PHOENIX_SPEED = 0.9;
	public static float ULT_PHOENIX_TOUCH_DAMAGE = 3.0f;
	public static float ULT_PHOENIX_BLAST_DAMAGE = 12.0f;
	public static double ULT_PHOENIX_BLAST_RADIUS = 5.0;
	/** Wind - Tempest: you hover in the eye of a tornado for TICKS; it pulls in enemies within RADIUS, then flings them. */
	public static int ULT_TEMPEST_TICKS = 120;
	public static double ULT_TEMPEST_RADIUS = 8.0;
	public static float ULT_TEMPEST_DAMAGE = 2.0f;
	public static float ULT_TEMPEST_FLING_DAMAGE = 5.0f;
	/** Frost - Permafrost: a freezing field out to RADIUS freezes every enemy for FREEZE_TICKS, then it all shatters. */
	public static double ULT_PERMAFROST_RADIUS = 10.0;
	public static int ULT_PERMAFROST_FREEZE_TICKS = 60;
	public static float ULT_PERMAFROST_SHATTER_DAMAGE = 10.0f;
	/** Verdant - Overgrowth: a great tree grows behind you for TICKS; its roots hold enemies within RADIUS, its petals heal you. */
	public static int ULT_OVERGROWTH_TICKS = 160;
	public static double ULT_OVERGROWTH_RADIUS = 9.0;
	public static int ULT_OVERGROWTH_ROOT_TICKS = 80;
	public static float ULT_OVERGROWTH_THORN_DAMAGE = 1.5f;
	public static float ULT_OVERGROWTH_HEAL = 1.0f;
	/** Thunder - Judgement: you rise and call down BOLTS bolts on enemies within RANGE, then one giant bolt at your aim. */
	public static double ULT_JUDGEMENT_RANGE = 20.0;
	public static int ULT_JUDGEMENT_BOLTS = 7;
	public static float ULT_JUDGEMENT_BOLT_DAMAGE = 6.0f;
	public static float ULT_JUDGEMENT_FINAL_DAMAGE = 14.0f;
	public static double ULT_JUDGEMENT_FINAL_RADIUS = 4.0;

	/**
	 * Aura Mastery (ability/Mastery): using an aura earns its mastery XP; the FTB "Aura Mastery" quests hand out
	 * levels I-V at these XP totals (keep the quest book in step if you change them). Each level cuts the aura's
	 * cooldowns by COOLDOWN_CUT_PER_LEVEL and adds PARTICLE_BOOST_PER_LEVEL more particles to its abilities as they
	 * go off. A duel round won adds DUEL_ROUND_XP.
	 */
	public static boolean MASTERY_ENABLED = true;
	public static int MASTERY_XP_LEVEL_1 = 100;
	public static int MASTERY_XP_LEVEL_2 = 300;
	public static int MASTERY_XP_LEVEL_3 = 750;
	public static int MASTERY_XP_LEVEL_4 = 1500;
	public static int MASTERY_XP_LEVEL_5 = 3000;
	public static double MASTERY_COOLDOWN_CUT_PER_LEVEL = 0.02;
	public static double MASTERY_PARTICLE_BOOST_PER_LEVEL = 0.15;
	public static int MASTERY_DUEL_ROUND_XP = 15;

	/** Vanilla experience points for every stunt pulled off (a boss stunt gives BOSS_MULTIPLIER times as much); 0 = none. */
	public static int STUNT_XP_POINTS = 15;
	public static double STUNT_XP_BOSS_MULTIPLIER = 3.0;

	/**
	 * Idle animations: a player standing still (not moving, looking round or doing anything) for START seconds
	 * plays a little animation of their aura, then another every GAP_MIN..GAP_MAX seconds while they stay put.
	 */
	public static boolean IDLE_ENABLED = true;
	public static int IDLE_START_SECONDS = 8;
	public static int IDLE_GAP_MIN_SECONDS = 15;
	public static int IDLE_GAP_MAX_SECONDS = 25;

	/** Sparring: players in the Training Hall's Spar Pit can fight each other there (a knockout sends you back to the door). */
	public static boolean ARENA_HALL_PVP = true;

	/** A training dummy heals back to full this long after its last hit (its damage-per-second count starts over too). */
	public static int TRAINING_DUMMY_RESET_SECONDS = 4;

	/**
	 * The DPS challenge only starts once you haven't used an ability with free cooldowns (your training
	 * cooldowns off, or /aura cooldowns off) for this long, so blossoms, trees and the like spammed beforehand
	 * can't add to it. It won't start at all, and a running one is cancelled, while /aura cooldowns off is on.
	 */
	public static int TRAINING_CHALLENGE_CLEAN_SECONDS = 30;

	// ---------------------------------------------------------------
	// Earth: aurapoints-earth.json
	// ---------------------------------------------------------------

	/** Whether /aura roll can give Earth (/aura setaura works either way). */
	public static boolean EARTH_ROLLABLE = true;

	/** Pillar Lift: a pillar under the one you aim at (or under you) that throws them up. */
	public static double EARTH_PILLAR_RANGE = 16.0;
	public static float EARTH_PILLAR_DAMAGE = 4.0f;
	public static double EARTH_PILLAR_LAUNCH = 1.15;
	public static double EARTH_PILLAR_SELF_LAUNCH = 1.15;
	public static int EARTH_PILLAR_COOLDOWN_TICKS = 10 * 20;
	/** Boulder Toss: torn out, held overhead, hurled. */
	public static float EARTH_BOULDER_DAMAGE = 8.0f;
	public static float EARTH_BOULDER_SPLASH_DAMAGE = 4.0f;
	public static double EARTH_BOULDER_SPLASH_RADIUS = 2.5;
	public static double EARTH_BOULDER_KNOCKBACK = 1.6;
	public static double EARTH_BOULDER_SPEED = 1.3;
	public static double EARTH_BOULDER_RANGE = 32.0;
	public static int EARTH_BOULDER_HOLD_TICKS = 60;
	public static int EARTH_BOULDER_COOLDOWN_TICKS = 12 * 20;
	/** Quake: a ripple of heaving ground rolling out from you. */
	public static double EARTH_QUAKE_RADIUS = 7.0;
	public static float EARTH_QUAKE_DAMAGE = 5.0f;
	public static double EARTH_QUAKE_LIFT = 0.5;
	public static int EARTH_QUAKE_SLOW_TICKS = 80;
	public static int EARTH_QUAKE_SLOW_AMPLIFIER = 1;
	public static int EARTH_QUAKE_COOLDOWN_TICKS = 16 * 20;
	/** Stone Skin: armor while standing on natural ground. */
	public static double EARTH_STONE_SKIN_ARMOR = 4.0;
	public static double EARTH_STONE_SKIN_TOUGHNESS = 2.0;
	/** Grounded: knockback resistance (0..1) and less fall damage (0..1). */
	public static double EARTH_GROUNDED_KNOCKBACK_RESISTANCE = 0.5;
	public static double EARTH_GROUNDED_FALL_REDUCTION = 0.3;
	/** The Ultimate, Tectonic Rift. */
	public static double EARTH_RIFT_LENGTH = 18.0;
	public static double EARTH_RIFT_WIDTH = 2.2;
	public static float EARTH_RIFT_DAMAGE = 10.0f;
	public static double EARTH_RIFT_LAUNCH = 1.2;

	// ---------------------------------------------------------------
	// Light: aurapoints-light.json
	// ---------------------------------------------------------------

	/** Whether /aura roll can give Light (/aura setaura works either way). */
	public static boolean LIGHT_ROLLABLE = true;

	/** Radiant Pulse: heals the players around you, burns the undead. */
	public static double LIGHT_PULSE_RADIUS = 8.0;
	public static float LIGHT_PULSE_HEAL = 4.0f;
	public static int LIGHT_PULSE_REGEN_TICKS = 80;
	public static float LIGHT_PULSE_UNDEAD_DAMAGE = 6.0f;
	public static int LIGHT_PULSE_COOLDOWN_TICKS = 18 * 20;
	/** Solar Flash: a cone that blinds and marks. ANGLE: the cone's full width in degrees. */
	public static double LIGHT_FLASH_RANGE = 10.0;
	public static double LIGHT_FLASH_ANGLE = 70.0;
	public static float LIGHT_FLASH_DAMAGE = 2.0f;
	public static int LIGHT_FLASH_BLIND_TICKS = 60;
	public static int LIGHT_FLASH_GLOW_TICKS = 120;
	public static int LIGHT_FLASH_COOLDOWN_TICKS = 14 * 20;
	/** Prism Beam: held; DAMAGE every HIT_TICKS to the first thing in it. */
	public static double LIGHT_BEAM_RANGE = 24.0;
	public static float LIGHT_BEAM_DAMAGE = 1.5f;
	public static int LIGHT_BEAM_HIT_TICKS = 4;
	public static int LIGHT_BEAM_MAX_TICKS = 80;
	public static int LIGHT_BEAM_COOLDOWN_TICKS = 12 * 20;
	/** The undead take this much more from the beam. */
	public static double LIGHT_UNDEAD_MULTIPLIER = 1.5;
	/** Halo: healing in the sunlight. */
	public static float LIGHT_HALO_HEAL = 1.0f;
	public static int LIGHT_HALO_HEAL_TICKS = 80;
	/** Luminous: night vision, and enemies around you light up now and then. */
	public static int LIGHT_LUMINOUS_PULSE_TICKS = 120;
	public static double LIGHT_LUMINOUS_RADIUS = 12.0;
	/** The Ultimate, Sunburst. */
	public static int LIGHT_SUN_TICKS = 120;
	public static double LIGHT_SUN_RANGE = 16.0;
	public static float LIGHT_SUN_LANCE_DAMAGE = 4.0f;
	public static int LIGHT_SUN_LANCE_TICKS = 10;
	public static float LIGHT_SUN_HEAL = 2.0f;
	public static float LIGHT_SUN_BURST_DAMAGE = 8.0f;
	public static double LIGHT_SUN_BURST_RADIUS = 8.0;

	// ---------------------------------------------------------------
	// Blood: aurapoints-blood.json
	// ---------------------------------------------------------------

	/** Whether /aura roll can give Blood (/aura setaura works either way). */
	public static boolean BLOOD_ROLLABLE = true;

	/** Bleeding (Hemorrhage, Blood Moon): DAMAGE every EVERY_TICKS, for TICKS. */
	public static float BLOOD_BLEED_DAMAGE = 1.0f;
	public static int BLOOD_BLEED_EVERY_TICKS = 20;
	public static int BLOOD_BLEED_TICKS = 120;
	/** Hemorrhage: the lunging cut. */
	public static float BLOOD_HEMO_DAMAGE = 4.0f;
	public static double BLOOD_HEMO_SPEED = 1.5;
	public static int BLOOD_HEMO_COOLDOWN_TICKS = 10 * 20;
	/** Blood Tether: DRAIN health every EVERY_TICKS, HEAL_FRACTION of it to you. */
	public static double BLOOD_TETHER_RANGE = 16.0;
	public static int BLOOD_TETHER_TICKS = 100;
	public static int BLOOD_TETHER_EVERY_TICKS = 10;
	public static float BLOOD_TETHER_DRAIN = 1.0f;
	public static double BLOOD_TETHER_HEAL_FRACTION = 1.0;
	public static int BLOOD_TETHER_COOLDOWN_TICKS = 18 * 20;
	/** Crimson Sacrifice: COST health for HITS melee hits at MULTIPLIER (within TICKS). */
	public static float BLOOD_SACRIFICE_COST = 8.0f;
	public static int BLOOD_SACRIFICE_HITS = 3;
	public static double BLOOD_SACRIFICE_MULTIPLIER = 2.0;
	public static int BLOOD_SACRIFICE_TICKS = 300;
	public static int BLOOD_SACRIFICE_COOLDOWN_TICKS = 25 * 20;
	/** Bloodlust: health back on every kill. */
	public static float BLOOD_LUST_HEAL = 4.0f;
	/** Thin Blood: below THRESHOLD of your health, Speed. */
	public static double BLOOD_THIN_THRESHOLD = 0.4;
	public static int BLOOD_THIN_SPEED_AMPLIFIER = 0;
	/** The Ultimate, Blood Moon: how long, how far its red sky reaches, the share of your hits you heal, the bleeding radius. */
	public static int BLOOD_MOON_TICKS = 200;
	public static double BLOOD_MOON_SKY_RANGE = 48.0;
	public static double BLOOD_MOON_LIFESTEAL = 0.5;
	public static double BLOOD_MOON_RADIUS = 12.0;
	/**
	 * The blood bar (0..MAX): every bleeding tick your bleeds deal fills it - PLAYER for a player, MONSTER for a monster,
	 * MOB for anything else (animals, villagers) - except while your own Blood Moon is up. Blood Moon needs MOON_COST and
	 * spends it; Crimson Sacrifice spends up to SACRIFICE_SPEND, and a full SACRIFICE_SPEND takes MAX_REDUCTION off its cost.
	 * Kept on logout, emptied on death.
	 */
	public static int BLOOD_BAR_MAX = 100;
	public static int BLOOD_BAR_MOON_COST = 100;
	public static int BLOOD_BAR_PLAYER = 4;
	public static int BLOOD_BAR_MONSTER = 2;
	public static int BLOOD_BAR_MOB = 1;
	public static int BLOOD_BAR_SACRIFICE_SPEND = 25;
	public static double BLOOD_SACRIFICE_MAX_REDUCTION = 0.75;

	// ---------------------------------------------------------------
	// Void: aurapoints-void.json
	// ---------------------------------------------------------------

	/** Void Pull: a cone (ANGLE: full width in degrees) that drags enemies to you. */
	public static double VOID_PULL_RANGE = 12.0;
	public static double VOID_PULL_ANGLE = 60.0;
	public static double VOID_PULL_STRENGTH = 1.0;
	public static float VOID_PULL_DAMAGE = 3.0f;
	public static int VOID_PULL_COOLDOWN_TICKS = 12 * 20;
	/** Rift Step: the teleport and the rift it leaves (DAMAGE every HIT_TICKS to anyone touching it). */
	public static double VOID_RIFT_RANGE = 10.0;
	public static int VOID_RIFT_TICKS = 100;
	public static float VOID_RIFT_DAMAGE = 3.0f;
	public static int VOID_RIFT_HIT_TICKS = 10;
	public static int VOID_RIFT_COOLDOWN_TICKS = 9 * 20;
	/** Null Orb: SPEED in blocks per tick; NULL_TICKS: how long a player hit can't use abilities. */
	public static double VOID_ORB_SPEED = 0.45;
	public static double VOID_ORB_RANGE = 22.0;
	public static float VOID_ORB_DAMAGE = 6.0f;
	public static int VOID_ORB_NULL_TICKS = 60;
	public static int VOID_ORB_COOLDOWN_TICKS = 15 * 20;
	/** Event Horizon: projectiles within RADIUS keep SLOW of their speed each tick. */
	public static double VOID_HORIZON_RADIUS = 4.0;
	public static double VOID_HORIZON_SLOW = 0.7;
	/** Entropy: armor taken off what you hit, for TICKS. */
	public static double VOID_ENTROPY_ARMOR = 4.0;
	public static int VOID_ENTROPY_TICKS = 80;
	/** The Ultimate, Singularity. */
	public static double VOID_HOLE_RANGE = 16.0;
	public static int VOID_HOLE_TICKS = 80;
	public static double VOID_HOLE_RADIUS = 12.0;
	public static double VOID_HOLE_PULL = 0.35;
	public static float VOID_HOLE_GRIND_DAMAGE = 1.0f;
	public static float VOID_HOLE_BLAST_DAMAGE = 12.0f;
	public static double VOID_HOLE_BLAST_RADIUS = 5.0;

	// ---------------------------------------------------------------
	// Elemental reactions: aurapoints-reactions.json
	// ---------------------------------------------------------------

	/** ENABLED: the whole system. MARK_TICKS: how long a hit's element stays on the target. COOLDOWN_TICKS: per target, between two reactions. */
	public static boolean REACTION_ENABLED = true;
	public static int REACTION_MARK_TICKS = 100;
	public static int REACTION_COOLDOWN_TICKS = 40;
	/** Water/rain, being on fire, Poison and powder-snow cold count as marks too (never used up). */
	public static boolean REACTION_ENVIRONMENT_MARKS = true;
	/** Freeze (Wet + Frost): the Ice Spike freeze for TICKS, plus DAMAGE on the hit. */
	public static boolean REACTION_FREEZE_ENABLED = true;
	public static int REACTION_FREEZE_TICKS = 40;
	public static float REACTION_FREEZE_DAMAGE = 2.0f;
	/** Conduct (Wet + Thunder): DAMAGE on the hit, then it jumps to up to CHAIN_MAX wet enemies within CHAIN_RADIUS. */
	public static boolean REACTION_CONDUCT_ENABLED = true;
	public static float REACTION_CONDUCT_DAMAGE = 3.0f;
	public static double REACTION_CONDUCT_CHAIN_RADIUS = 6.0;
	public static int REACTION_CONDUCT_CHAIN_MAX = 4;
	public static float REACTION_CONDUCT_CHAIN_DAMAGE = 3.0f;
	/** Firestorm (Burning + Wind): enemies within RADIUS burn for FIRE_SECONDS and take DAMAGE. */
	public static boolean REACTION_FIRESTORM_ENABLED = true;
	public static double REACTION_FIRESTORM_RADIUS = 4.0;
	public static float REACTION_FIRESTORM_DAMAGE = 3.0f;
	public static int REACTION_FIRESTORM_FIRE_SECONDS = 4;
	/** Toxic cloud (Poisoned + Fire): DAMAGE on the hit, then a cloud of RADIUS for TICKS that poisons enemies inside. */
	public static boolean REACTION_TOXIC_ENABLED = true;
	public static float REACTION_TOXIC_DAMAGE = 2.0f;
	public static double REACTION_TOXIC_RADIUS = 3.0;
	public static int REACTION_TOXIC_TICKS = 100;
	public static int REACTION_TOXIC_POISON_AMPLIFIER = 1;
	public static int REACTION_TOXIC_POISON_TICKS = 40;
	/** Steam (Burning + Water): puts the fire out for DAMAGE, enemies within RADIUS are blinded for BLIND_TICKS. */
	public static boolean REACTION_STEAM_ENABLED = true;
	public static float REACTION_STEAM_DAMAGE = 4.0f;
	public static double REACTION_STEAM_RADIUS = 3.0;
	public static int REACTION_STEAM_BLIND_TICKS = 40;
	/** Melt (Chilled + Fire): the hit deals MULTIPLIER times its damage. */
	public static boolean REACTION_MELT_ENABLED = true;
	public static double REACTION_MELT_MULTIPLIER = 1.75;
	/** Overgrowth (Wet + Verdant): rooted in place (no walking or jumping) for ROOT_TICKS, plus DAMAGE. */
	public static boolean REACTION_OVERGROWTH_ENABLED = true;
	public static int REACTION_OVERGROWTH_ROOT_TICKS = 30;
	public static float REACTION_OVERGROWTH_DAMAGE = 2.0f;
	/** Storm (Charged + Wind): enemies within RADIUS take DAMAGE and are thrown up by LIFT. */
	public static boolean REACTION_STORM_ENABLED = true;
	public static double REACTION_STORM_RADIUS = 5.0;
	public static float REACTION_STORM_DAMAGE = 4.0f;
	public static double REACTION_STORM_LIFT = 0.8;

	// ---------------------------------------------------------------
	// Team combos: aurapoints-combos.json
	// ---------------------------------------------------------------

	/** ENABLED: the whole system. Allies = same FTB Teams party, or the same side of an arena match. */
	public static boolean COMBO_ENABLED = true;
	/** Abilities (and combos) never hurt players in your FTB Teams party. */
	public static boolean COMBO_PARTY_PROTECTION = false;
	/** Paid to both players when a combo reaches its first enemy (not in the arena), at most once per REWARD_COOLDOWN_TICKS each. */
	public static int COMBO_AURA_REWARD = 5;
	public static int COMBO_MASTERY_XP = 15;
	public static int COMBO_REWARD_COOLDOWN_TICKS = 600;
	/** Fire zones: Heat Wave leaves embers (its radius) and a Fireball's fire (FIRE_ZONE_RADIUS) for FIRE_ZONE_TICKS. */
	public static int COMBO_FIRE_ZONE_TICKS = 80;
	public static double COMBO_FIRE_ZONE_RADIUS = 3.0;
	/** Fire Tornado (Fire zone + Wind burst): drifts with the wind at SPEED, pulls in within RADIUS, burns for DAMAGE every HIT_TICKS, then flings everyone out. */
	public static boolean COMBO_TORNADO_ENABLED = true;
	public static int COMBO_TORNADO_TICKS = 100;
	public static double COMBO_TORNADO_RADIUS = 6.0;
	public static double COMBO_TORNADO_SPEED = 0.08;
	public static double COMBO_TORNADO_PULL = 0.12;
	public static float COMBO_TORNADO_DAMAGE = 2.0f;
	public static int COMBO_TORNADO_HIT_TICKS = 10;
	public static int COMBO_TORNADO_FIRE_SECONDS = 4;
	public static double COMBO_TORNADO_FLING = 1.4;
	public static float COMBO_TORNADO_FLING_DAMAGE = 4.0f;
	/** Electrified Bubble (Abyssal Bubble + Thunder): for TICKS, every HIT_TICKS everyone inside takes DAMAGE and is paralyzed PARALYZE_TICKS. */
	public static boolean COMBO_SHOCK_ENABLED = true;
	public static int COMBO_SHOCK_TICKS = 60;
	public static float COMBO_SHOCK_DAMAGE = 2.5f;
	public static int COMBO_SHOCK_HIT_TICKS = 10;
	public static int COMBO_SHOCK_PARALYZE_TICKS = 12;
	/** Toxic Gale (poison cloud + Wind burst): a wall RADIUS wide rolls on at SPEED for TICKS; each enemy once: DAMAGE + Poison. */
	public static boolean COMBO_GALE_ENABLED = true;
	public static int COMBO_GALE_TICKS = 50;
	public static double COMBO_GALE_SPEED = 0.45;
	public static double COMBO_GALE_RADIUS = 3.0;
	public static double COMBO_GALE_PUSH = 0.4;
	public static float COMBO_GALE_DAMAGE = 3.0f;
	public static int COMBO_GALE_POISON_AMPLIFIER = 1;
	public static int COMBO_GALE_POISON_TICKS = 100;
	/** Glacier (Abyssal Bubble + Ice Spike): everyone inside frozen FREEZE_TICKS, then SHATTER_DAMAGE (half to those nearby). */
	public static boolean COMBO_GLACIER_ENABLED = true;
	public static int COMBO_GLACIER_FREEZE_TICKS = 60;
	public static float COMBO_GLACIER_SHATTER_DAMAGE = 7.0f;
	/** Magma Field (Fire zone + Earth Quake): for TICKS, enemies on it get Slowness (SLOW_AMPLIFIER) and DAMAGE + fire every HIT_TICKS. */
	public static boolean COMBO_MAGMA_ENABLED = true;
	public static int COMBO_MAGMA_TICKS = 100;
	public static float COMBO_MAGMA_DAMAGE = 1.5f;
	public static int COMBO_MAGMA_HIT_TICKS = 10;
	public static int COMBO_MAGMA_SLOW_AMPLIFIER = 2;
	public static int COMBO_MAGMA_FIRE_SECONDS = 3;
	/** Boiling Bubble (Abyssal Bubble + Fire): for TICKS, every HIT_TICKS everyone inside takes DAMAGE and is blinded BLIND_TICKS. */
	public static boolean COMBO_BOIL_ENABLED = true;
	public static int COMBO_BOIL_TICKS = 60;
	public static float COMBO_BOIL_DAMAGE = 2.0f;
	public static int COMBO_BOIL_HIT_TICKS = 10;
	public static int COMBO_BOIL_BLIND_TICKS = 40;
	/** Prism Burst (Abyssal Bubble + Prism Beam): RAYS rays at the nearest enemies within the bubble + REACH, DAMAGE each, Glowing GLOW_TICKS. */
	public static boolean COMBO_PRISM_ENABLED = true;
	public static int COMBO_PRISM_RAYS = 6;
	public static double COMBO_PRISM_REACH = 8.0;
	public static float COMBO_PRISM_DAMAGE = 6.0f;
	public static int COMBO_PRISM_GLOW_TICKS = 100;

	// ---------------------------------------------------------------
	// Aura-touched mobs: aurapoints-touched.json
	// ---------------------------------------------------------------

	/** CHANCE: share of natural hostile spawns that get a random aura (0.02 = 2%). */
	public static boolean TOUCHED_ENABLED = true;
	public static double TOUCHED_CHANCE = 0.02;
	/** Max health times this (2.0 = double). */
	public static double TOUCHED_HEALTH_MULTIPLIER = 2.0;
	/** The colored outline everyone sees through walls (false = only the halo and the name). */
	public static boolean TOUCHED_OUTLINE = true;
	/** Their one ability: used on their target within RANGE, every COOLDOWN_TICKS. */
	public static boolean TOUCHED_ABILITIES = true;
	public static int TOUCHED_ABILITY_COOLDOWN_TICKS = 160;
	public static double TOUCHED_ABILITY_RANGE = 10.0;
	public static float TOUCHED_ABILITY_DAMAGE = 4.0f;
	/** What the killer gets. */
	public static int TOUCHED_KILL_POINTS = 5;
	public static int TOUCHED_KILL_MASTERY_XP = 25;

	// ---------------------------------------------------------------
	// Weekly challenges and cosmetics: aurapoints-weekly.json
	// ---------------------------------------------------------------

	/** SEED: change it to draw a different set of challenges this week (everyone's progress for the week starts over). */
	public static boolean WEEKLY_ENABLED = true;
	public static int WEEKLY_SEED = 0;
	/** Aura for a finished challenge: BASE, plus PER_TIER for each harder tier (0, 1, 2); ALL_BONUS for all three. */
	public static int WEEKLY_REWARD_BASE = 40;
	public static int WEEKLY_REWARD_PER_TIER = 20;
	public static int WEEKLY_ALL_BONUS = 100;
	/** Cosmetics (/aura cosmetics): PRICE_MULTIPLIER scales every price (earned ones stay free). */
	public static boolean COSMETICS_ENABLED = true;
	public static double COSMETIC_PRICE_MULTIPLIER = 1.0;

	public static final String PUFFISH_CATEGORY_PVP = "aura_pvp";

	/** Overview tree listing the abilities. */
	public static final String PUFFISH_CATEGORY_ABILITIES = "aura_abilities";
}
