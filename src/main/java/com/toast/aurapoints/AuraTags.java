package com.toast.aurapoints;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/** Tags this mod reads. Other mods and datapacks add entries to them. */
public final class AuraTags {

	private AuraTags() {
	}

	/**
	 * Custom bosses. Killing one unlocks the PvP skill tree for nearby players (and pays
	 * Aura if BOSS_AURA_PER_KILL is set). Empty by default; the boss mod (or a datapack) adds
	 * its entity IDs via data/aurapoints/tags/entity_type/bosses.json.
	 * Vanilla bosses are deliberately NOT in it.
	 */
	public static final TagKey<EntityType<?>> BOSSES =
			TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("aurapoints", "bosses"));

	/*
	 * EASY_MOBS: the common monsters (zombies, skeletons, spiders, creepers...). Every ability's own
	 * damage hits them EASY_MOB_DAMAGE_MULTIPLIER times as hard, so abilities are worth using on them.
	 */

	/**
	 * Bosses for the Aura Trials boss stunts: Wither, Ender Dragon, Warden, Elder
	 * Guardian, plus everything in #aurapoints:bosses.
	 */
	public static final TagKey<EntityType<?>> EASY_MOBS =
			TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("aurapoints", "easy_mobs"));

	public static final TagKey<EntityType<?>> STUNT_BOSSES =
			TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("aurapoints", "stunt_bosses"));
}
