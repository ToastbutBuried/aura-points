package com.toast.aurapoints.weather;

import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Reactions;

/**
 * The ten storms (5.39.0), one per aura (Resonance has none): the aura they favour, their colour for the
 * client's fog/sky, the reaction mark they put on everything inside, and whether they only form at night.
 */
public enum StormType {
	THUNDERHEAD("Thunderhead", AuraType.THUNDER, 0x5A5F7A, Reactions.Mark.CHARGED, false),
	DOWNPOUR("Downpour", AuraType.WATER, 0x3B6E9E, Reactions.Mark.WET, false),
	BLIZZARD("Blizzard", AuraType.FROST, 0xDDEEFF, Reactions.Mark.CHILLED, false),
	HEAT_HAZE("Heat Haze", AuraType.FIRE, 0xE8A05A, null, false),
	GALE("Gale", AuraType.WIND, 0xBFD8CF, null, false),
	SPORE_BLOOM("Spore Bloom", AuraType.VERDANT, 0x8FBF5A, null, false),
	TREMOR("Tremor", AuraType.EARTH, 0x9C8466, null, false),
	AURORA("Aurora", AuraType.LIGHT, 0x7FFFD0, null, true),
	UMBRA("Umbra", AuraType.SHADOW, 0x2A2238, null, false),
	CRIMSON_MOON("Crimson Moon", AuraType.BLOOD, 0x8A1020, null, true);

	public final String displayName;
	public final AuraType aura;
	public final int rgb;
	/** Put on every living thing inside (as an environment mark: never used up), or null. */
	public final Reactions.Mark mark;
	public final boolean nightOnly;

	StormType(String displayName, AuraType aura, int rgb, Reactions.Mark mark, boolean nightOnly) {
		this.displayName = displayName;
		this.aura = aura;
		this.rgb = rgb;
		this.mark = mark;
		this.nightOnly = nightOnly;
	}

	public String id() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	public static StormType byId(String id) {
		for (StormType t : values()) if (t.id().equals(id) || t.name().equalsIgnoreCase(id)) return t;
		return null;
	}
}
