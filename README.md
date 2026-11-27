# Aura Points

**Elemental auras for Minecraft 1.21.1, on NeoForge and Fabric.**
Roll an aura, buy its abilities, master it, awaken its Ultimate and test yourself in a built-in arena.

[Docs](docs/README.md) · [All auras](docs/auras/overview.md) · [How to earn Aura (quests)](QUESTS.md) · [Changelog](CHANGELOG.md) · [For datapack makers](docs/reference/datapacks.md)

---

## The auras
Ten auras can be rolled (Resonance is retired). Every one has its own keys, passives, extra casts, chains, an Ultimate and two branches, and its own story told by the Keeper of the Awakening Altar.

| | Aura | Role | Keys | Ultimate |
|---|---|---|---|---|
| | [Shadow](docs/auras/shadow.md) | stealth skirmisher | Dash, Vanish | Eclipse |
| | [Water](docs/auras/water.md) | sustain and control | Shockwave, Healing Tide, Abyssal Bubble | Tidal Ascension |
| | [Fire](docs/auras/fire.md) | attack | Fireball, Heat Wave, Fire Bending | Phoenix |
| | [Wind](docs/auras/wind.md) | air mobility | Dash, Super Wind Charge | Tempest |
| | [Frost](docs/auras/frost.md) | control | Ice Spike | Permafrost |
| | [Verdant](docs/auras/verdant.md) | trapper, poisoner | Vine Hook, Entanglement, Verdant Viper | Overgrowth |
| | [Thunder](docs/auras/thunder.md) | burst | Thunderbolt, Chidori | Judgement |
| | [Earth](docs/auras/earth.md) | tank, terrain | Pillar Lift, Boulder Toss, Quake | Tectonic Rift |
| | [Light](docs/auras/light.md) | healer, blinder | Radiant Pulse, Solar Flash, Prism Beam | Sunburst |
| | [Blood](docs/auras/blood.md) | duelist | Hemorrhage, Blood Tether, Crimson Sacrifice | Blood Moon |
| | [Mage](docs/auras/mage.md) *(new, not rollable yet)* | spellcaster: mana instead of cooldowns | Arcane Ray, Arcane Barrier, Arcane Orb | Last Prism |

At Mastery V an aura picks one of two [branches](docs/features/mastery-and-branches.md) that bend its kit (Fire: Blue Flame or Magma, Shadow: Phantom or Dark, and so on).
> *"Frost is patience made into a weapon."* Each aura page opens with the Keeper's story of its pillar.

## How it plays
1. **Roll** an aura (the first is free) and open the **Aura Book** to buy its abilities.
2. Bind the ability keys (Options > Controls > Aura Points) and try everything in the **[Aura Trial](docs/features/aura-trial.md)** in the Training Hall.
3. Earn **Aura** from quests, weekly challenges, aura-touched mobs, arena wins and team combos.
4. Level **Mastery** to unlock **sneak** and **hold** [casts](docs/features/casts.md), then pick a branch at Mastery V.
5. At the **[Awakening Altar](docs/features/awakening-altar.md)**, 800 Aura and Mastery V awaken your **Ultimate**.
6. Test it all against players and waves in the **[Aura Arena](docs/features/arena.md)**, or take a squad into an **[Aura Rift](docs/features/rift-trials.md)**.

## Features
| | |
|---|---|
| **[Aura Book](docs/features/aura-book.md)** | one book for your aura, abilities, rolling, skill points, cosmetics, weeklies, loadouts and settings. Every action is also a command |
| **[Casts](docs/features/casts.md)** | sneak + key at Mastery II, hold the key at Mastery III: a second and a third cast for most abilities |
| **[Ability chains](docs/features/chains.md)** | use one ability, then another soon after: the second hits harder and adds an effect |
| **[Elemental reactions](docs/features/reactions.md)** | Wet, Burning, Chilled, Poisoned, Charged marks and eight reactions (Freeze, Conduct, Steam, Melt ...) |
| **[Team combos](docs/features/team-combos.md)** | an ally's ability through your zone: Fire Tornado, Electrified Bubble, Glacier and more |
| **[Mastery and branches](docs/features/mastery-and-branches.md)** | five levels per aura, two branches each, every branch with a strength and a price |
| **[Awakening Altar](docs/features/awakening-altar.md)** | an island with one pillar per aura, a Keeper who tells their stories, and the Ultimate rituals |
| **[Aura Trial](docs/features/aura-trial.md)** | a checklist of everything your aura can do, with the whole kit lent to you |
| **[Aura Arena](docs/features/arena.md)** | duels, team matches, free-for-all, King of the Hill and Wave Survival with twist votes |
| **[Training Hall](docs/features/training-hall.md)** | dummies, a DPS challenge, a spar pit, a shooting range and a live combo trainer |
| **[Aura Rifts](docs/features/rift-trials.md)** | a ruined cathedral realm with six trials and the Relic Warden at the end |
| **[Territory shrines](docs/features/shrines.md)** and **[local storms](docs/features/storms.md)** | things to fight over and fight in out in the overworld |
| **[Aura weapons](docs/features/handhelds.md)** and **[relics](docs/features/relics.md)** | a handheld per aura (and Mjölnir), and relics that echo other auras' passives |
| **[Loadouts](docs/features/loadouts.md)**, **[cosmetics](docs/features/cosmetics-and-tickets.md)**, **[skill trees](docs/features/skill-trees.md)** | presets for your keys, looks, and three Pufferfish's Skills trees |
| **[Everything configurable](docs/reference/configuration.md)** | every balance value is a JSON number, editable in game with the Balance Tome |

## Requirements
Each release has two jars: `aura-points-neoforge-<version>.jar` and `aura-points-fabric-<version>.jar`. Use the dependencies built for your loader.

| Mod | Where |
|---|---|
| [NeoForge](https://neoforged.net) 21.1.x **or** [Fabric Loader](https://fabricmc.net) 0.16+ with [Fabric API](https://modrinth.com/mod/fabric-api), for Minecraft 1.21.1 | server + client |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.9.3+ | server + client |
| [Pufferfish's Skills](https://modrinth.com/mod/skills) 0.19+ | server + client |
| [Pufferfish's Attributes](https://modrinth.com/mod/puffish-attributes) | server + client |
| [Player Animator](https://modrinth.com/mod/playeranimator) 2.0.4+ | client |

Optional: FTB Teams (team members count as allies), FTB Quests (the quest book, `/aura resetprogress quests`) and, on Fabric, [Mod Menu](https://modrinth.com/mod/modmenu) for a client config button.

## Getting started
1. Install the mod and its requirements on the server and every client.
2. In game, right-click the **Aura Book** or use `/aura roll` to get your first aura.
3. Buy abilities in the book or with `/shop`, bind the keys, and try them in `/aura trial`.

Handy commands: `/aura book`, `/aura roll`, `/aura switch <aura>`, `/aura info`, `/aura mastery`, `/aura branch`, `/aura arena`, `/aura weekly`, `/changelog`. The full list is in the [command reference](docs/reference/commands.md).

## For server owners
Everything is in `config/`; `/aura reload` applies it. A changed default never overwrites a value already in your file. Owners are set in `aurapoints-commands.json`. Aura weapons are owner-only until you `/aura worthy add <player>`. See the [configuration reference](docs/reference/configuration.md).

## For datapack makers and developers
- [**Datapack reference**](docs/reference/datapacks.md): scoreboards (`aura_points`, `aura_type`, `aura_branch`, `aura_mastery_<aura>`, writable `aura_cd_<ability>`), function tags (`#aurapoints:ability/<ability>`, `aura_changed`, `left_click`), entity and block tags, loot conditions.
- [**Developer notes**](docs/reference/developers.md): the `common/` + `neoforge/` + `fabric/` layout, building, adding abilities, chains and branches, dev tests.

```
./gradlew build                    # neoforge/build/libs/ and fabric/build/libs/
./gradlew :neoforge:runClient      # dev client (also :fabric:runClient)
./gradlew :neoforge:runServer      # dev server with /auradev tests (also :fabric:runServer)
```
Needs Java 21.

## Update manifests
`latest-neoforge.json` and `latest-fabric.json` are what the [installer/updater](https://github.com/ToastbutBuried/aura-points-installer) reads, one per loader; every release rewrites them. `latest.json` is a copy of `latest-neoforge.json` for updaters from before 5.4.0.

## License
Code and assets: [GNU LGPL v3.0 or later](COPYING.LESSER) (it builds on the [GPL v3.0](LICENSE)). All textures and models were made for this mod. You may use the mod in modpacks and on servers, and write add-ons for it under any license. If you change Aura Points itself and share it, your changes must stay under the LGPL.
