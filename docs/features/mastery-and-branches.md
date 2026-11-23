# Mastery and branches

## Mastery I-V
Using an aura earns **Mastery XP** for that aura. The default thresholds are 100 / 300 / 750 / 1500 / 3000 XP for levels I to V (`MASTERY_XP_LEVEL_1..5`). You claim each level in the quest book ("Aura Mastery"). `/aura mastery` shows where you stand.

| Level | Unlocks |
|---|---|
| II | sneak casts ([casts](casts.md)) |
| III | charged (hold) casts |
| V | a **branch**, and with 800 Aura the **Ultimate** ([awakening](awakening-altar.md)) |

## Branches
At Mastery V you pick one of two branches of the aura you hold (`/aura branch`, or the Aura Book). A branch bends the kit: cooldowns, a passive, how an actives behaves. Every branch has a strength and a **price**. The first pick is free, switching costs Aura (`BRANCH_SWITCH_COST`). The pick is kept per aura.

| Aura | Branch A | Branch B |
|---|---|---|
| Shadow | Phantom (evasion) | **Dark** (a full kit swap, the former Void aura) |
| Water | Tidecaller (support) | Leviathan (tank) |
| Fire | Blue Flame (precision) | Magma (zone control) |
| Wind | Skyrunner (mobility) | Cyclone (crowd control) |
| Frost | Glacial (lockdown) | Frostbite (burst around the combo) |
| Verdant | Venomweaver (damage over time) | Grove Warden (support, trapper) |
| Thunder | Surge (tempo) | Conductor (area) |
| Earth | Bulwark (tank) | Tectonic (offense and terrain) |
| Light | Radiant (support) | Prismatic (damage) |
| Blood | Sanguine (aggressive sustain) | Sacrificial (high risk, high reward) |

Details are on each [aura page](../auras/overview.md).

[← docs index](../README.md)
