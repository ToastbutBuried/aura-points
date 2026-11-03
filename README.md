# Aura Points

A Minecraft 1.21.1 mod (NeoForge and Fabric) about elemental auras. Roll one of 12 auras, buy its abilities, level them up, and test
yourself in a built-in arena.

**Auras:** Shadow, Water, Fire, Wind, Frost, Verdant, Thunder, Resonance, Earth, Light, Blood, Void. Each has
active abilities on rebindable keys, passives, an awakenable Ultimate and a Mastery level.

## Features
- **Aura Book**: every player gets one. Right-click it for your aura and abilities (buy them there), rolling,
  skill points, cosmetics, weekly challenges and settings. It also offers a short tutorial. Every action also
  works as a command.
- **Aura**: a currency on the scoreboard `aura_points`. Weekly challenges, aura-touched mobs, team combos and
  arena wins pay it; so can quests, datapacks and commands. It buys abilities, rerolls, cosmetics and points for
  the built-in skill trees. **[How to earn Aura, with the full quest list →](QUESTS.md)**
- **Skill trees** (Pufferfish's Skills): an Aura tree, an Ability tree, and a PvP tree that unlocks once you
  defeat a boss.
- **Team Combos and Reactions** when auras meet, and aura-touched mobs.
- **Aura weapons**: handheld weapons per aura, e.g. Mjölnir and the World Tree Bow. Server owners only by
  default.
- **Arena dimension**: duels, team matches, Wave Survival and a Training Hall with dummies. Open it with
  `/aura arena`.
- **Weekly challenges**, a `/changelog` in game, and player **tickets** (change requests that reviewers vote on).
- **Everything is configurable**: every balance value lives in a JSON file. Server owners can also change them in
  game with the Balance Tome (`/aura balance`).

## Requirements
Each release has two jars: `aura-points-neoforge-<version>.jar` for NeoForge and `aura-points-fabric-<version>.jar` for Fabric.
Use the dependencies built for your loader.

| Mod | Where |
|---|---|
| [NeoForge](https://neoforged.net) 21.1.x **or** [Fabric Loader](https://fabricmc.net) 0.16+ with [Fabric API](https://modrinth.com/mod/fabric-api), for Minecraft 1.21.1 | server + client |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.9.3+ | server + client |
| [Pufferfish's Skills](https://modrinth.com/mod/skills) 0.19+ | server + client |
| [Pufferfish's Attributes](https://modrinth.com/mod/puffish-attributes) | server + client |
| [Player Animator](https://modrinth.com/mod/playeranimator) 2.0.4+ | client |

Optional: FTB Teams (team members count as allies) and FTB Quests (`/aura resetprogress quests`). On Fabric,
[Mod Menu](https://modrinth.com/mod/modmenu) adds a config button for the client settings.

## Getting started
1. Install the mod and its requirements on the server and every client.
2. In game: right-click the Aura Book, or use `/aura roll` to get your first aura (the first roll is free).
3. Buy abilities in the book or with `/shop`, and bind the ability keys under Options > Controls > Aura Points.

Useful commands:

| Command | What it does |
|---|---|
| `/aura book`, `/aura menu` | get or open the book |
| `/aura roll` | roll an aura |
| `/shop` | buy abilities |
| `/aura redeem` | buy skill points |
| `/aura info` | show your aura's details |
| `/aura mastery` | show Mastery |
| `/aura cosmetics` | cosmetics |
| `/aura weekly` | weekly challenges |
| `/aura arena` | arena: `join`, `leave`, `duel`, `match`, `spectate` |
| `/aura ticket` | write a ticket (`/aura tickets` for reviewers) |
| `/changelog` | what's new |
| `/aura reload` | admin: reload all config files |
| `/aura give` | admin |
| `/aura setaura` | admin |
| `/aura resetprogress` | admin |
| `/aura balance` | admin: the Balance Tome |

## Configuration
Everything is in the server's `config/` folder. Apply changes with `/aura reload`.

- `aurapoints-<aura>.json`, `-general`, `-arena`, `-ultimates`, `-combos`, `-reactions`, `-touched`, `-weekly`,
  `-handhelds`: all balance values. New keys are added automatically. A changed default does not overwrite
  values already in your file.
- `aurapoints-commands.json`: who may use which command (op level, owner only, blacklists).
  - `owners`: player names or UUIDs of the server owners. Empty means everyone with permission level 4.
- `aurapoints-tickets.json`: ticket reviewers, rate limit and blacklist.
- `aurapoints-editors.json`: who may use the Balance Tome.
- Client: `aurapoints-client.toml` (Mods > Aura Points > Config) for animations, HUD and shader-safe wind
  charges.

## Datapack hooks
The mod creates these scoreboards and tags. Datapacks can build on them without any Java.

**Scoreboards**

| Objective | Value |
|---|---|
| `aura_points` | the currency |
| `aura_type` | the player's aura: 0 none, then 1-12 in the order listed at the top |
| `aura_mastery` | Mastery level of the current aura |
| `aura_mastery_<aura>` | Mastery level per aura |
| `aura_cd_<ability>` | cooldown ticks left; writable |
| `itemuse` / `itemsecondary` / `itemtertiary` | 1 while that mod key is held, for datapack items |

**Function tags** (they run as and at the player)

| Tag | Runs when |
|---|---|
| `#aurapoints:ability/<ability>` | the player uses that ability |
| `#aurapoints:aura_changed` | the player's aura changes |
| `#aurapoints:left_click` | the player left-clicks |

**Entity tags**

| Tag | Effect |
|---|---|
| `#aurapoints:bosses` | killing one unlocks the PvP tree; default: Wither, Ender Dragon, Warden, Elder Guardian |
| `#aurapoints:stunt_bosses` | boss versions of the stunt advancements |

**Loot conditions** in `aurapoints:` check a player's aura, for example in loot tables.

## Building
```
./gradlew build                    # neoforge/build/libs/ and fabric/build/libs/
./gradlew :neoforge:runClient      # dev client (also :fabric:runClient)
./gradlew :neoforge:runServer      # dev server; /auradev test commands are on there (also :fabric:runServer)
```
Needs Java 21. The build downloads every dependency. The mod itself lives in `common/` and is compiled against
plain Minecraft; `neoforge/` and `fabric/` are the thin loader layers (see `common/.../shim/`).

## About latest.json
`latest.json` in this repo is the update manifest that the
[installer/updater](https://github.com/ToastbutBuried/aura-points-installer) reads. Every release rewrites it.

## License
Code and assets: [GNU LGPL v3.0 or later](COPYING.LESSER) (it builds on the [GPL v3.0](LICENSE)). All
textures and models were made for this mod. You may use the mod in modpacks and on servers, and write add-ons
for it under any license. If you change Aura Points itself and share it, your changes must stay under the LGPL.
