package com.toast.aurapoints.config;

import com.toast.aurapoints.shim.neoforge.common.ModConfigSpec;

/**
 * Per-player client settings: config/aurapoints-client.toml on each player's own machine
 * (also editable in game: Mods > Aura Points > Config). Never read on the server.
 */
public final class AuraClientConfig {

	private AuraClientConfig() {
	}

	public static final ModConfigSpec SPEC;
	public static final ModConfigSpec.BooleanValue SHADER_SAFE_WIND_CHARGES;
	public static final ModConfigSpec.BooleanValue AURA_NAMETAGS;
	public static final ModConfigSpec.BooleanValue FIRST_PERSON_EXTRAS;
	public static final ModConfigSpec.EnumValue<AnimationMode> ANIMATIONS;
	public static final ModConfigSpec.BooleanValue FULL_BODY;
	public static final ModConfigSpec.BooleanValue SHOW_IDLE_ANIMATIONS, IDLE_FIRST_PERSON, AURA_TRAILS, OWN_TRAIL;

	// HUD
	public static final ModConfigSpec.EnumValue<Anchor> HUD_ANCHOR;
	public static final ModConfigSpec.IntValue HUD_OFFSET_X, HUD_OFFSET_Y;
	public static final ModConfigSpec.DoubleValue HUD_SCALE, HUD_OPACITY;
	public static final ModConfigSpec.BooleanValue HUD_LABEL, HUD_KEYBINDS, HUD_EFFECTS;
	public static final ModConfigSpec.EnumValue<CooldownText> COOLDOWN_TEXT;
	public static final ModConfigSpec.ConfigValue<String> HUD_COLOR;
	public static final ModConfigSpec.EnumValue<Palette> PALETTE;
	public static final ModConfigSpec.BooleanValue CROSSHAIR_PIPS;
	public static final ModConfigSpec.EnumValue<BarPlace> CHARGE_BAR;
	public static final ModConfigSpec.IntValue CHARGE_OFFSET_X, CHARGE_OFFSET_Y;
	public static final ModConfigSpec.DoubleValue CHARGE_SCALE;

	// feedback
	public static final ModConfigSpec.BooleanValue HIT_CONFIRM, STUNT_POPUPS, POINTS_TOAST, ROLL_REEL, STATUS_OVERLAYS, KEYBIND_WARNING;
	public static final ModConfigSpec.DoubleValue OVERLAY_STRENGTH, CAMERA_SHAKE, DRAGON_TRANSPARENCY;
	public static final ModConfigSpec.BooleanValue DRAGON_SHADER_SUPPORT;
	public static final ModConfigSpec.BooleanValue FOV_KICK, REDUCED_MOTION;
	public static final ModConfigSpec.EnumValue<Shimmer> AURA_SHIMMER;
	public static final ModConfigSpec.EnumValue<ArenaMessages> ARENA_MESSAGES;
	public static final ModConfigSpec.DoubleValue SHIMMER_DENSITY;

	// sound
	public static final ModConfigSpec.DoubleValue ABILITY_VOLUME;
	public static final ModConfigSpec.BooleanValue READY_CHIME, NOT_READY_SOUND, CHARGE_HUMS, ALMOST_READY_TICK, HIT_SOUND;

	/** Which ability animations (arm poses and gestures) this client shows. */
	public enum AnimationMode {
		/** everything, your own arms in first person too */
		ALL,
		/** other players, and yourself in third person - in first person you keep your normal hand */
		NO_FIRST_PERSON,
		/** none at all */
		OFF
	}

	/** How arena announcements (round won, wave cleared, knocked out...) appear. */
	public enum ArenaMessages {
		/** a short title in the middle of the screen */
		TITLE,
		/** a line in chat (countdowns go to the action bar) */
		CHAT
	}

	/** Where the ability panel sits before its offset is added. */
	public enum Anchor {
		/** left of the hotbar (and right of it for a handheld's panel), as always */
		HOTBAR, TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
	}

	public enum CooldownText {
		/** 12, then 4.5 under ten seconds */
		DECIMAL,
		/** whole seconds only */
		SECONDS,
		/** no numbers, just the sweep */
		OFF
	}

	/** Colors the HUD uses for each aura. */
	public enum Palette {
		/** the aura's own colors */
		OFF,
		/** red-green safe (deuteranopia / protanopia): no red-against-green pairs */
		RED_GREEN,
		/** blue-yellow safe (tritanopia) */
		BLUE_YELLOW,
		/** strong, bright colors on a near-black panel */
		HIGH_CONTRAST
	}

	public enum BarPlace {
		/** middle of the left screen edge, as always */
		LEFT,
		/** middle of the right screen edge */
		RIGHT,
		/** a short bar right under the crosshair */
		CROSSHAIR
	}

	public enum Shimmer {
		OFF,
		/** other players only */
		OTHERS,
		/** other players, and yourself in third person */
		ALL
	}

	static {
		ModConfigSpec.Builder b = new ModConfigSpec.Builder();
		SHADER_SAFE_WIND_CHARGES = b
				.comment("Draw wind charges in a way shader packs can handle. Turn this on if wind charges",
						"(Air Bending, Super Wind Charge, thrown ones) look black with your shaders.",
						"They lose the scrolling wind texture but still spin.")
				.translation("aurapoints.configuration.shaderSafeWindCharges")
				.define("shaderSafeWindCharges", false);
		ANIMATIONS = b
				.comment("Ability animations (arm poses and gestures):",
						"ALL - everything, your own arms in first person too;",
						"NO_FIRST_PERSON - other players and yourself in third person, your normal hand in first person;",
						"OFF - no ability animations at all.")
				.translation("aurapoints.configuration.animations")
				.defineEnum("animations", AnimationMode.ALL);
		FULL_BODY = b
				.comment("Ability animations move the whole body too (leaning, twisting, stances), not just the arms.")
				.translation("aurapoints.configuration.fullBodyAnimations")
				.define("fullBodyAnimations", true);
		SHOW_IDLE_ANIMATIONS = b
				.comment("Players who stand still for a while do little idle animations for their aura (a Fire player doodling with flame, ...).",
						"Off: you never see anyone's idles.")
				.translation("aurapoints.configuration.idleAnimations")
				.define("idleAnimations", true);
		IDLE_FIRST_PERSON = b
				.comment("See your own idle animations' hands in first person.")
				.translation("aurapoints.configuration.idleFirstPerson")
				.define("idleFirstPerson", true);
		AURA_TRAILS = b
				.comment("A soft trail in their aura's style behind sprinting players.")
				.translation("aurapoints.configuration.auraTrails")
				.define("auraTrails", true);
		OWN_TRAIL = b
				.comment("Your own sprint trail too.")
				.translation("aurapoints.configuration.ownTrail")
				.define("ownTrail", true);
		AURA_NAMETAGS = b
				.comment("Show each player's aura (like \"❄ Frost ❄\") above their name.")
				.translation("aurapoints.configuration.auraNametags")
				.define("auraNametags", true);
		FIRST_PERSON_EXTRAS = b
				.comment("Show your own aura's body extras over your shoulders in first person.")
				.translation("aurapoints.configuration.firstPersonExtras")
				.define("firstPersonExtras", true);

		b.comment("The ability panel, charge bar and crosshair marks. /aurahud opens a screen to drag them around.")
				.translation("aurapoints.configuration.hud").push("hud");
		HUD_ANCHOR = b
				.comment("Where the ability panel sits: HOTBAR (next to the hotbar), or a screen corner / the top middle.")
				.translation("aurapoints.configuration.hudAnchor")
				.defineEnum("anchor", Anchor.HOTBAR);
		HUD_OFFSET_X = b.comment("Moves the panel right (negative: left), in GUI pixels.")
				.translation("aurapoints.configuration.hudOffsetX")
				.defineInRange("offsetX", 0, -4000, 4000);
		HUD_OFFSET_Y = b.comment("Moves the panel down (negative: up), in GUI pixels.")
				.translation("aurapoints.configuration.hudOffsetY")
				.defineInRange("offsetY", 0, -4000, 4000);
		HUD_SCALE = b.comment("Size of the panel (1.0 = normal).")
				.translation("aurapoints.configuration.hudScale")
				.defineInRange("scale", 1.0, 0.5, 2.0);
		HUD_OPACITY = b.comment("How see-through the panel is (1.0 = solid).")
				.translation("aurapoints.configuration.hudOpacity")
				.defineInRange("opacity", 1.0, 0.2, 1.0);
		HUD_LABEL = b.comment("Show the aura's name above the panel.")
				.translation("aurapoints.configuration.hudLabel")
				.define("label", true);
		HUD_KEYBINDS = b.comment("Show the key for each ability in its corner.")
				.translation("aurapoints.configuration.hudKeybinds")
				.define("keybinds", true);
		COOLDOWN_TEXT = b.comment("Numbers on cooling-down slots: DECIMAL (4.5 under ten seconds), SECONDS, or OFF.")
				.translation("aurapoints.configuration.cooldownText")
				.defineEnum("cooldownText", CooldownText.DECIMAL);
		HUD_EFFECTS = b.comment("Little aura effects on the panel's frame (embers, ripples, sparks...).")
				.translation("aurapoints.configuration.hudEffects")
				.define("frameEffects", true);
		HUD_COLOR = b.comment("Panel color as #RRGGBB, or empty for your aura's color.")
				.translation("aurapoints.configuration.hudColor")
				.define("color", "", o -> o instanceof String s && (s.isEmpty() || s.matches("#?[0-9a-fA-F]{6}")));
		PALETTE = b.comment("Colorblind-friendly colors for the HUD: OFF, RED_GREEN, BLUE_YELLOW or HIGH_CONTRAST.")
				.translation("aurapoints.configuration.palette")
				.defineEnum("palette", Palette.OFF);
		CROSSHAIR_PIPS = b.comment("Tiny cooldown dots around the crosshair, one per ability.")
				.translation("aurapoints.configuration.crosshairPips")
				.define("crosshairPips", true);
		CHARGE_BAR = b.comment("Where the charge bar (Fire Bending, Air Bending, Chidori...) goes: LEFT, RIGHT or CROSSHAIR.")
				.translation("aurapoints.configuration.chargeBar")
				.defineEnum("chargeBar", BarPlace.LEFT);
		CHARGE_OFFSET_X = b.comment("Moves the charge bar right (negative: left), in GUI pixels.")
				.translation("aurapoints.configuration.chargeOffsetX")
				.defineInRange("chargeOffsetX", 0, -4000, 4000);
		CHARGE_OFFSET_Y = b.comment("Moves the charge bar down (negative: up), in GUI pixels.")
				.translation("aurapoints.configuration.chargeOffsetY")
				.defineInRange("chargeOffsetY", 0, -4000, 4000);
		CHARGE_SCALE = b.comment("Size of the charge bar (1.0 = normal).")
				.translation("aurapoints.configuration.chargeScale")
				.defineInRange("chargeScale", 1.0, 0.5, 2.0);
		b.pop();

		b.comment("Screen feedback.").translation("aurapoints.configuration.feedback").push("feedback");
		HIT_CONFIRM = b.comment("A mark on the crosshair when one of your abilities hits (bigger on a kill).")
				.translation("aurapoints.configuration.hitConfirm")
				.define("hitConfirm", true);
		STUNT_POPUPS = b.comment("Show a stunt's name on screen when you pull one off.")
				.translation("aurapoints.configuration.stuntPopups")
				.define("stuntPopups", true);
		POINTS_TOAST = b.comment("A toast in the corner when you earn Aura points.")
				.translation("aurapoints.configuration.pointsToast")
				.define("pointsToast", true);
		ROLL_REEL = b.comment("The spinning reel on screen while /aura roll picks your aura.")
				.translation("aurapoints.configuration.rollReel")
				.define("rollReel", true);
		STATUS_OVERLAYS = b.comment("Screen edges show it when you're frozen, entangled, stunned, in a bubble or healed by Lifesteal.")
				.translation("aurapoints.configuration.statusOverlays")
				.define("statusOverlays", true);
		OVERLAY_STRENGTH = b.comment("How strong those screen edges are.")
				.translation("aurapoints.configuration.overlayStrength")
				.defineInRange("overlayStrength", 1.0, 0.1, 1.0);
		CAMERA_SHAKE = b.comment("Camera shake on your own big hits (Shockwave, Inferno Slam, landings...). 0 = none.",
						"The Abyssal Bubble's shake is part of how it works and stays.")
				.translation("aurapoints.configuration.cameraShake")
				.defineInRange("cameraShake", 1.0, 0.0, 2.0);
		FOV_KICK = b.comment("A quick zoom punch on dashes and big hits.")
				.translation("aurapoints.configuration.fovKick")
				.define("fovKick", true);
		REDUCED_MOTION = b.comment("Turns off camera shake, zoom punches and the moving and flashing HUD extras in one go.")
				.translation("aurapoints.configuration.reducedMotion")
				.define("reducedMotion", false);
		AURA_SHIMMER = b.comment("A faint glow of particles around players in their aura's color, stronger right after they cast:",
						"OFF, OTHERS (other players), or ALL (you too, in third person).")
				.translation("aurapoints.configuration.auraShimmer")
				.defineEnum("auraShimmer", Shimmer.OTHERS);
		SHIMMER_DENSITY = b.comment("How many shimmer particles (1.0 = normal).")
				.translation("aurapoints.configuration.shimmerDensity")
				.defineInRange("shimmerDensity", 1.0, 0.1, 2.0);
		KEYBIND_WARNING = b.comment("A hint in chat when one of the mod's keys you need (your aura's ability keys, the Ultimate once awakened, the Item keys for datapack items) is unbound or shares its key with something else.")
				.translation("aurapoints.configuration.keybindWarning")
				.define("keybindWarning", true);
		DRAGON_TRANSPARENCY = b.comment("How see-through the shadow dragon's head is while YOU ride it, so you can see past it (0 = solid, 0.9 = almost invisible).",
						"Only the rider sees this, everyone else sees it solid. In first person the mane and nose horn are always hidden on top of this.")
				.translation("aurapoints.configuration.dragonTransparency")
				.defineInRange("dragonTransparency", 0.0, 0.0, 0.9);
		DRAGON_SHADER_SUPPORT = b.comment("Turn this on if the dragon transparency does nothing with a shader pack (Iris/Oculus): it draws the see-through head the way vanilla draws a see-through invisible player, which shader packs handle.")
				.translation("aurapoints.configuration.dragonShaderSupport")
				.define("dragonShaderSupport", false);
		ARENA_MESSAGES = b.comment("How arena announcements (countdowns, round won, wave cleared, knocked out...) appear:",
						"TITLE (short, in the middle of the screen) or CHAT (a chat line; countdowns go to the action bar).")
				.translation("aurapoints.configuration.arenaMessages")
				.defineEnum("arenaMessages", ArenaMessages.TITLE);
		b.pop();

		b.comment("Sounds.").translation("aurapoints.configuration.sound").push("sound");
		ABILITY_VOLUME = b.comment("Volume of every Aura Points sound (abilities, HUD, charge hums), on top of the game's own sliders.")
				.translation("aurapoints.configuration.abilityVolume")
				.defineInRange("volume", 1.0, 0.0, 1.0);
		READY_CHIME = b.comment("A chime when an ability comes off cooldown (in your aura's own sound).")
				.translation("aurapoints.configuration.readyChime")
				.define("readyChime", true);
		NOT_READY_SOUND = b.comment("A low thud when you press an ability that is still cooling down.")
				.translation("aurapoints.configuration.notReadySound")
				.define("notReadySound", true);
		CHARGE_HUMS = b.comment("A rising hum while you charge an ability, and a sting when it's full.")
				.translation("aurapoints.configuration.chargeHums")
				.define("chargeHums", true);
		ALMOST_READY_TICK = b.comment("A soft tick in the last second of each cooldown, to time abilities by ear.")
				.translation("aurapoints.configuration.almostReadyTick")
				.define("almostReadyTick", false);
		HIT_SOUND = b.comment("A quiet sound with the hit mark (and a bell on a kill).")
				.translation("aurapoints.configuration.hitSound")
				.define("hitSound", true);
		b.pop();
		SPEC = b.build();
	}

	/** Reduced motion on: no shakes, punches, slides or flashing extras. */
	public static boolean calm() {
		return REDUCED_MOTION.get();
	}
}
