# Aura Points

Releases of the Aura Points mod (NeoForge 1.21.1) for our server, and a small updater that keeps
everyone's copy current. `latest.json` always names the newest release.

## Auto-update setup (once per player, Prism Launcher)

1. Download [`aura-updater.jar`](https://github.com/ToastbutBuried/aura-points/raw/main/aura-updater.jar).
2. In Prism, right-click the instance → **Folder**, and put `aura-updater.jar` in that folder
   (the instance folder itself, next to `instance.cfg` - not in `minecraft/` or `mods/`).
3. Right-click the instance → **Edit** → **Settings** → **Custom commands**, tick the box, and paste
   this into **Pre-launch command**:

   ```
   "$INST_JAVA" -jar "$INST_DIR/aura-updater.jar" "$INST_MC_DIR/mods"
   ```

That's it. On every launch it checks this repo; if there is a newer Aura Points it downloads it,
verifies it, and replaces the old jar before the game starts. Its messages show up at the top of
Prism's log as `[aura-updater]`.

It can't get in the way of playing: without internet, if GitHub is down, or if anything takes longer
than 45 seconds, it just starts the game with the jar you already have. Old copies of the mod are
found by their mod id, so a renamed one (like `Aura Points (1).jar`) is cleaned up too.

**If you ever delete `aura-updater.jar`, also clear the Pre-launch command** - otherwise Prism can't
run it and won't start the game.

## Keys

Options → Controls → **Aura Points**: **Ability 1 / 2 / 3** (default Left Alt, X, \`) work for every aura -
each aura puts its abilities on them (`/aura info` shows which is where). **Item Use** and **Item Secondary**
are for datapack items (see below). Double Jump uses jump.

## For datapack makers

Everything is plain vanilla - no mod knowledge needed:

| What | How |
|---|---|
| A player's aura | scoreboard `aura_type`: 0 none, 1 Shadow, 2 Water, 3 Fire, 4 Wind, 5 Frost |
| Item keys | scoreboards `itemuse` and `itemsecondary`: 1 while the player holds the key, 0 otherwise |
| Aura currency | scoreboard `aura_points` (add to it to pay players) |
| An ability was used | function tag `#aurapoints:ability/<ability>` runs as and at the player, e.g. `data/aurapoints/tags/function/ability/fireball.json` |
| Aura changed | function tag `#aurapoints:aura_changed` runs as and at the player |

Ability names for the tags: `shockwave`, `healing_tide`, `abyssal_bubble`, `vanish`, `dash`, `super_wind_charge`,
`double_jump`, `fireball`, `heat_wave`, `fire_bending`, `ice_spike`.

Example `data/aurapoints/tags/function/ability/fireball.json`:
```json
{ "values": ["mypack:on_fireball"] }
```

## What Aura Points itself needs

To run the mod at all (any world or server):

| | Version |
|---|---|
| Minecraft | **1.21.1** |
| NeoForge | **21.1.251** or newer 21.1.x |
| Java | **21** (Prism picks it automatically) |
| [Player Animator](https://modrinth.com/mod/playeranimator) | **2.0.4+1.21.1** or newer - client only, for the ability animations |

For `/aura redeem` and the skill trees it also needs
[Pufferfish's Skills](https://modrinth.com/mod/skills) and [Pufferfish's Attributes](https://modrinth.com/mod/attributes)
on the server (and so on every player too - see below). Everything else (abilities, shop, HUD, quests' Aura rewards) works without them.

## What you need to join our server

NeoForge **21.1.251** for Minecraft **1.21.1**, and these mods. Use **exactly** these versions where it
says so - a different version of a mod that adds blocks, items or ships can stop you from joining or crash
the game.

**Required (exact versions):**

| Mod | Version |
|---|---|
| Aura Points | the newest release here (the updater keeps it current) |
| Player Animator | 2.0.4+1.21.1 |
| Create | 6.0.10 |
| Create Aeronautics (bundled) | 1.3.2 |
| Sable | 2.0.6 |
| Create Big Cannons | 5.11.7 |
| Ritchie's Projectile Library | 2.1.2 |
| **Forgified Fabric API** | **0.116.15+2.3.5** - *not* 2.3.6, that one crashes on NeoForge 21.1.251 |
| FTB Library | 2101.1.37 |
| FTB Teams | 2101.1.11 |
| FTB Quests | 2101.1.36 |
| Pufferfish's Skills | 0.19.1 |
| Pufferfish's Attributes | 0.8.3 |
| Sophisticated Core | 1.5.7.2381 |
| Sophisticated Backpacks | 3.26.9.2195 |
| Sophisticated Backpacks Create Integration | 0.2.2.188 |
| Sophisticated Storage | 1.6.2.2159 |
| Sophisticated Storage Create Integration | 0.1.22.238 |
| Gravestone | 1.0.40 |
| Horseshoes | 1.3.3 |
| Vanilla Backport | 1.1.7.10 |
| Call Your Horse | 1.3.11 |
| Two Players One Horse | 1.0.2 |
| Veinminer | 2.11.2 |
| FallingTree | 1.21.1.11 |
| Right Click Harvest | 4.6.1 |
| Libraries the above need: Architectury 13.0.11, Balm 21.0.66, Cloth Config 15.0.140, Konkrete 1.9.9, Platform 1.3.3, JamLib 1.3.6, YetAnotherConfigLib 3.8.2, KotlinLangForge 2.15.0, Kotlin for Forge 5.12.0 | |

**Recommended (any version for 1.21.1 works):**

| Mod | Why |
|---|---|
| Do a Barrel Roll 3.7.3 | elytra flying like everyone else on the server (needs the Forgified Fabric API above) |
| Xaero's Minimap + Xaero's World Map 1.47.0 | the map (the server has the world map part) |
| Just Enough Items (JEI) | recipes |
| Distant Horizons 3.3.3 | far view distance, the server sends the far terrain |
| Sodium, Iris, ... | performance and shaders - entirely up to you |

**Not needed on your side** (server only): NeoEssentials, Root Companion, InvHistory, ServerCore, spark,
squaremap, SkinRestorer, Biome Replacer, Terralith, Lithostitched, Lithium, FerriteCore, ModernFix, Clumps.

**Easiest:** ask someone who can already join to send you their whole instance (Prism: right-click → Export).
