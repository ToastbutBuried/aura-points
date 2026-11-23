# Configuration

Everything is in the server's `config/` folder. Apply changes with `/aura reload`, or edit them in game with the Balance Tome (`/aura balance`).

| File | Holds |
|---|---|
| `aurapoints-general.json` | rolling and switching costs, casts, chains, mastery XP, matchup multiplier, worthy, storms, relics, rifts, shrines ... |
| `aurapoints-<aura>.json` | that aura's balance numbers, branches included (`shadow`, `water`, `fire`, `wind`, `frost`, `verdant`, `thunder`, `earth`, `light`, `blood`, `resonance`; the Dark branch's numbers are in `aurapoints-void.json`) |
| `aurapoints-ultimates.json` | the Ultimates and awakening |
| `aurapoints-chains.json` | the chain table ([below](#chains)) |
| `aurapoints-reactions.json` | each reaction on or off, numbers |
| `aurapoints-combos.json` | team combos |
| `aurapoints-touched.json` | aura-touched mobs |
| `aurapoints-arena.json` | arena, waves, duels |
| `aurapoints-weekly.json` | weekly challenges |
| `aurapoints-weather.json` | local storms |
| `aurapoints-handhelds.json` | aura weapons |
| `aurapoints-commands.json` | who may use which command; `owners` (names or UUIDs; empty = everyone with permission level 4) |
| `aurapoints-tickets.json` | reviewers, rate limit, blacklist |
| `aurapoints-editors.json` | who may use the Balance Tome |
| `aurapoints-worthy.json` | who besides the owners may wield the weapons |
| `aurapoints-client.toml` | client: animations, HUD, titleSize, riftMusic, stormWalls, shader-safe wind charges ... (Mods > Aura Points > Config) |

**New keys are added to your file automatically. A changed default never overwrites a value already in your file**: when a release changes a number you want, edit your file.

## Chains
`aurapoints-chains.json` is a map from an aura id (or `shadow:dark` for the Dark branch's kit) to a list of links:

```json
{
  "fire": [
    { "from": "fire_bending", "to": "fireball", "name": "Stoked",
      "bonus": 0.30, "effect": "ignite", "power": 4 }
  ],
  "frost": [
    { "from": "ice_spike:sneak", "to": "ice_spike:tap", "name": "Black Ice",
      "bonus": 0.35, "effect": "chill", "power": 0, "window": 10 }
  ]
}
```

- `from` / `to`: an ability id, optionally with a cast: `ability:sneak`, `:charged`, `:tap`.
- `bonus`: extra damage of your ability hits for `CHAINS_EMPOWER_TICKS`, capped by `CHAINS_MAX_BONUS`.
- `effect`: `none ignite chill wet charged poison slow weaken knockup lifesteal refund speed heal`. `power` is seconds for timed effects, a share for `lifesteal` / `refund`, HP for `heal`.
- optional `window` (seconds you have for the second ability) and `lasts` (seconds the bonus stays).
- The window between the two abilities is `CHAINS_WINDOW_TICKS` in the general file.

## Roll pool
Each aura has a `<AURA>_ROLLABLE` switch in its config (Resonance is off); turn one off and `/aura roll` never lands on it.

[← docs index](../README.md)
