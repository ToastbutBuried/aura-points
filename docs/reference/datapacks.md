# For datapack makers

Everything below is created by the mod on server start and can be used **without any Java**. All functions run **as and at the player** unless noted. Examples assume a function file in `data/<yournamespace>/function/`.

## Scoreboards (read them, a few are writable)
| Objective | Value |
|---|---|
| `aura_points` | the currency. Read and write freely (`scoreboard players add @s aura_points 50`) |
| `aura_type` | the player's aura: 0 none, 1 Shadow, 2 Water, 3 Fire, 4 Wind, 5 Frost, 6 Verdant, 7 Thunder, 8 Resonance, 9 Earth, 10 Light, 11 Blood, 13 Mage (12 Void is legacy: Dark is now Shadow + a branch) |
| `aura_branch` | branch in force for the held aura: 0 none, else its place (1 or 2) among that aura's branches: Shadow 1 Phantom / 2 Dark, Water 1 Tidecaller / 2 Leviathan, Fire 1 Blue Flame / 2 Magma, Wind 1 Skyrunner / 2 Cyclone, Frost 1 Glacial / 2 Frostbite, Verdant 1 Venomweaver / 2 Grove Warden, Thunder 1 Surge / 2 Conductor, Earth 1 Bulwark / 2 Tectonic, Light 1 Radiant / 2 Prismatic, Blood 1 Sanguine / 2 Sacrificial |
| `aura_mastery` | Mastery level (0-5) of the aura held now. Written by the mod: change levels with `/aura mastery set` |
| `aura_mastery_<aura>` | Mastery level per aura (`aura_mastery_fire`) |
| `aura_cd_<ability>` | cooldown ticks left on that ability, 0 = ready. **Writable**: `scoreboard players set @s aura_cd_fireball 100` puts it on a 5 s cooldown, 0 makes it ready. Main cooldown only (not the elytra set or Wind's launch) |
| `itemuse`, `itemsecondary`, `itemtertiary` | 1 while the player holds the "Item Use / Secondary / Tertiary" mod key, else 0. For datapack items |
| `aura_key_summon` | 1 while the Summon key is held. The key runs no function itself: **your datapack decides who may summon** (for example by tag) |

`<ability>` is the lowercase enum name: `vanish dash phantom_step vanish_step bloodrush nightstalker shockwave healing_tide abyssal_bubble iron_skin flow fireball heat_wave fire_bending berserker executioner fireborn double_jump super_wind_charge gale_rider featherfall ice_spike sure_footed ice_shell shatter vine_hook entanglement verdant_viper bloom virulence thunderbolt chidori static_charge stormborn pillar_lift boulder_toss quake stone_skin grounded radiant_pulse solar_flash prism_beam halo luminous hemorrhage blood_tether crimson_sacrifice bloodlust thin_blood void_pull rift_step null_orb event_horizon entropy sonic_blossom listening_tree abyssal_grasp echolocation lifesteal` and the Ultimates `eclipse tidal_ascension phoenix tempest permafrost overgrowth judgement tectonic_rift sunburst blood_moon singularity`.

## Function tags
| Tag | Runs when |
|---|---|
| `#aurapoints:ability/<ability>` | the player uses that ability (`#aurapoints:ability/fireball`) |
| `#aurapoints:aura_changed` | the player's aura changes |
| `#aurapoints:left_click` | the player left-clicks (air, block or entity) |

```mcfunction
# data/aurapoints/tags/function/ability/fireball.json
{ "values": ["mypack:on_fireball"] }

# data/mypack/function/on_fireball.mcfunction
execute if score @s aura_branch matches 2 run say Magma fireball!
```

## Entity type and block tags
| Tag | Effect |
|---|---|
| `#aurapoints:bosses` | killing one unlocks the PvP skill tree (and pays Aura). Default: Wither, Ender Dragon, Warden, Elder Guardian |
| `#aurapoints:stunt_bosses` | boss versions of the stunt advancements |
| `#aurapoints:easy_mobs` | common monsters (zombies, skeletons, spiders, creepers ...): abilities deal `EASY_MOB_DAMAGE_MULTIPLIER` times their damage to them |
| `#aurapoints:boss` | skipped by the PvE ability damage buff (`ABILITY_PVE_DAMAGE_MULTIPLIER`, default 1.25). Empty by default |
| `#aurapoints:dragon_passable` (block) | blocks the Shadow dragon head flies through (default: leaves) |

## Loot conditions
Usable in predicates, loot tables and `execute if predicate`. Optional `entity`: `this` (default), `attacker`, `direct_attacker`, `attacking_player`. Use `attacking_player` in a boss's loot table. A non-player never matches.

```json
{ "condition": "aurapoints:has_aura", "aura": "shadow" }
{ "condition": "aurapoints:has_aura" }
{ "condition": "aurapoints:has_ability", "ability": "iron_skin" }
```

## Custom stats and advancements
Many things are counted as custom stats under `aurapoints:` (for example `mastery_<aura>`, `combo_<id>`, `chains`, `vanish_uses`) and can be used in advancement criteria or `execute if score`. The mod ships its own advancements in `data/aurapoints/advancement/` (`aura`, `stunt`, `feats`, `combo`, `reaction`); copy their style for yours.

## Other namespaces the mod adds
- Dimensions `aurapoints:arena` and `aurapoints:rift` (with their dimension types and a biome).
- Structure templates under `aurapoints:reliquary/` (the Relic Trials realm) and recipes `aurapoints:aura_book`, `aurapoints:rokaka_fruit`.
- Items you can give or reference: `aurapoints:aura_book`, `balance_tome`, `boss_sigil`, `rokaka_fruit` (clears an aura and the skill trees), `blue_bell`, `relic_<aura>`, `relic_shard`, `tuning_fork`, the aura weapons (`leviathans_anchor`, `phoenix_fangs`, `stormcaller_glaive`, `soul_reaper`, `world_tree_bow`, `absolute_zero`, `zeus_gauntlets`, `mjolnir`) and `dragon_segment_a` / `dragon_segment_b`.

## Shadow dragon segments
The dragon's body is a chain of **item displays** holding `dragon_segment_a/b`, spawned by your datapack. The mod raises their view range (`SHADOW_DRAGON_SEGMENT_VIEW_RANGE`) and tracks them from further away so a long dragon does not lose its far half. A larger view range you set yourself is left alone.

## Quest rewards
Aura rewards in quest books (FTB Quests) are paid by function: the mod's `aura_points` scoreboard is the only thing you need. See [QUESTS.md](../../QUESTS.md) for the shipped book.

[← docs index](../README.md)
