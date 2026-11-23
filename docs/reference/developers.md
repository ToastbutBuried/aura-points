# For mod developers

## Layout (since 5.4.0)
```
common/     the whole mod, compiled against plain Minecraft. Loader calls go through common/.../shim/
neoforge/   thin NeoForge layer (bridges the shim's events and platform calls)
fabric/     thin Fabric layer (Fabric API callbacks, a few mixins)
```
Every change has to work on **both loaders**. A new event type needs a bridge in each loader module; the shim's event names match NeoForge's.

Packages under `common/src/main/java/com/toast/aurapoints/`:

| Package | What |
|---|---|
| `ability/` | auras, abilities, casts, chains, reactions, branches, ultimates, relics, mastery |
| `arena/` | the arena dimension, duels, matches, waves, training hall, altar ritual, trial |
| `rift/` | rifts, the Reliquary, trials, Relic Warden |
| `shrine/`, `weather/` | territory shrines, local storms |
| `config/` | `AuraConfig` (every balance value is a `public static` field), config file IO, `AuraBalance` (which field lands in which file and Tome page) |
| `data/` | saved world and player data (`AuraState`, `AuraPlayerData`) |
| `geo/`, `client/` | GeckoLib entities and client rendering. **The server never references `client/*` classes** |
| `network/` | packets and key handling (bump the network version when packets change) |
| `menu/`, `cosmetic/`, `item/`, `loot/`, `mixin/` | the Aura Book, cosmetics, items, loot conditions, mixins |

## Building
```
./gradlew build -q                 # neoforge/build/libs/ and fabric/build/libs/
./gradlew :neoforge:runClient      # dev client (also :fabric:runClient)
./gradlew :neoforge:runServer      # dev server with /auradev tests (also :fabric:runServer)
```
Java 21. Dependencies (GeckoLib, Pufferfish's Skills and Attributes, Player Animator) come from Modrinth's maven. CI builds both jars (`.github/workflows/build.yml`).

## Rules worth knowing
- **Balance values** are `public static` fields in `AuraConfig`; the name prefix decides the config file and Balance Tome page (`AuraBalance.PREFIXES`). Changing a default does not change a live config file.
- **New registry entries or entity types** mean clients must update. **Packet changes** mean a network version bump.
- Saved per-player fields go in `AuraPlayerData` read/write **and** the reset code.
- Never modify a map inside its own iteration; the abilities add and remove entities mid-loop.
- Read an aura's kit through `aura.abilities(branch)` and `Loadouts.keySlots(data)`, never the raw fields, or branches and loadouts break.
- Server-side ability damage to mobs goes through `AuraAbilities.vsMob`.

## Adding content
- **A new ability** touches: the `Ability` enum entry (text), `AuraType` lists and key slots, dispatch, cooldown, config defaults and `AuraBalance` prefix, lore, lang, icons, a stat, the Aura Trial and chains. A sneak or charged cast is a `CastMode.VARIANTS` entry plus a branch in `Casts.activate` and its description text.
- **A new chain** is a JSON entry in the chain table (`Chains.defaults()`); a new effect is a `Chains.Effect` case.
- **A new reaction** is a `Reactions.Reaction` entry; a new zone or trigger for a combo is one call in the ability (`Combos.zone` / `Combos.at`).
- **A new branch** is a `Branch` enum value whose config fields start with one of its `configPrefixes()`.

## Dev tests
The dev server has `/auradev <test>` commands: `casttest`, `chaintest`, `chainstack`, `utiltest`, `trialtest`, `altartest`, `branch`, `relictest`, `stormtest`, `shrinetest`, `rifttest`, `wardentest`, `loadouttest`, `trainertest`, `tome` and more. They log lines with "(must be X)" you can check. Dev tests are not present on a normal server.

## Updates
`latest-neoforge.json` and `latest-fabric.json` are the manifests the [installer/updater](https://github.com/ToastbutBuried/aura-points-installer) reads; `latest.json` is a legacy copy of the NeoForge one. Every release rewrites them.

## License
LGPL-3.0-or-later. You may use the mod in modpacks and servers and write add-ons under any license; changes to Aura Points itself must stay under the LGPL.

[← docs index](../README.md)
