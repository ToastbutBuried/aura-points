# The auras

Ten auras can be rolled. Each has a Keeper's story told at the [Awakening Altar](../features/awakening-altar.md) (`/aura altar lore <aura>`), three keys, passives, [casts](../features/casts.md), [chains](../features/chains.md), an Ultimate and two [branches](../features/mastery-and-branches.md).

| Aura | Role | Keys | Ultimate | Branches |
|---|---|---|---|---|
| [Shadow](shadow.md) | stealth skirmisher | Dash, Vanish | Eclipse | Phantom / Dark |
| [Water](water.md) | sustain and control | Shockwave, Healing Tide, Abyssal Bubble | Tidal Ascension | Tidecaller / Leviathan |
| [Fire](fire.md) | attack | Fireball, Heat Wave, Fire Bending | Phoenix | Blue Flame / Magma |
| [Wind](wind.md) | air mobility | Dash, Super Wind Charge | Tempest | Skyrunner / Cyclone |
| [Frost](frost.md) | control | Ice Spike | Permafrost | Glacial / Frostbite |
| [Verdant](verdant.md) | trapper, poisoner | Vine Hook, Entanglement, Verdant Viper | Overgrowth | Venomweaver / Grove Warden |
| [Thunder](thunder.md) | burst | Thunderbolt, Chidori | Judgement | Surge / Conductor |
| [Earth](earth.md) | tank, terrain | Pillar Lift, Boulder Toss, Quake | Tectonic Rift | Bulwark / Tectonic |
| [Light](light.md) | healer, blinder | Radiant Pulse, Solar Flash, Prism Beam | Sunburst | Radiant / Prismatic |
| [Blood](blood.md) | duelist | Hemorrhage, Blood Tether, Crimson Sacrifice | Blood Moon | Sanguine / Sacrificial |
| [Mage](mage.md) *(not rollable yet)* | spellcaster, mana instead of cooldowns | Arcane Ray, Arcane Barrier, Arcane Orb | Last Prism | one per update |
| [Resonance](resonance.md) *(retired)* | sound hunter | Abyssal Grasp, Listening Tree, Sonic Blossom | none | Dirge / Chorus |

Every aura also has **Vein of Aura**: you heal a share of the damage you deal to mobs (not players).

## Matchups
In player-versus-player fights, an attacker whose aura is *strong against* the victim's deals extra damage (x1.25 by default) and sees an "Effective!" message. Mobs are not affected.

| Aura | Strong against | Weak against |
|---|---|---|
| Fire | Frost, Verdant | Water |
| Water | Fire | Verdant, Thunder |
| Verdant | Water, Thunder | Fire, Blood |
| Thunder | Water | Verdant, Earth |
| Earth | Thunder | none |
| Light | Shadow | none |
| Blood | Verdant | none |
| Shadow, Wind, Frost | none | Shadow: Light, Frost: Fire |
| Shadow **Dark** branch | Light, Wind | none (it is a full kit swap, so Light's edge does not reach it) |

The multiplier is `AURA_EFFECTIVE_MULTIPLIER` in `aurapoints-general.json`.

## Elements and reactions
Water, Fire, Frost, Verdant and Thunder leave a *mark* on what they hit (Wet, Burning, Chilled, Poisoned, Charged); the next hit of the right element sets off a [reaction](../features/reactions.md). Wind leaves no mark but sets off reactions.

## Aura IDs
For commands and scoreboards: `shadow water fire wind frost verdant thunder resonance earth light blood mage`. The scoreboard `aura_type` is 1 + the place in that list, except Mage = 13 (12 was Void's). See [datapack reference](../reference/datapacks.md).

[← back to the docs index](../README.md)
