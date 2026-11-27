# Mage

> *"Magic was never a gift. It is a muscle, and the mage trains it every day."*
> *Its pillar was carved by apprentices, one rune per lesson, until it glowed on its own.*
> *The awakened hold a prism that splits light like a scholar splits hairs.*
>
> The keeper of the Awakening Altar (`/aura altar lore mage`)

**Not rollable yet** (`MAGE_ROLLABLE` is off while its branches are being built, one per update). Admins can hand it out with `/aura setaura <player> mage`.

**Role:** spellcaster. No cooldowns: every spell spends **mana**. The default kit is the Apprentice's: weak on purpose. The power comes from training your mana and, later, from the branch you pick.

| | |
|---|---|
| Keys | Ability 1: **Arcane Bolt**, 2: **Arcane Barrier**, 3: **Arcane Orb** |
| Passives | Focus |
| Ultimate | **Last Prism** |
| Branches (Mastery V) | coming one per update (heals, protection, summoning and more) |
| Handheld | the **Apprentice Wand** (needed for the three keys) |
| Strong against | nobody |
| Weak against | nobody |
| Reaction mark | none |

## Mana
- A pool of mana instead of cooldowns, shown as a blue bar under your ability slots with your **mana level**. A slot you can't pay for fills blue up to its cost.
- Mana flows back a few points a second, starting a moment after your last spell.
- **Focus** *(passive)*: sneak to focus. Mana flows back twice as fast and starts sooner after a spell (1 s instead of 2).
- **Mana levels**: hits and kills with your spells, the mana you spend and focusing all give mana XP. Every level makes the pool bigger and the regen faster.
- **Mana wisps**: a mob killed near a Mage may leave a little spirit flame behind. Walk close and it flies into you: mana and a bit of XP.
- No sneak or hold-for-power casts: Mage keys are tap or hold only.

## Abilities
- **Arcane Bolt**: point your wand. Tap: a quick shock that locks onto what you look at. Hold to charge it (mana drains while you hold): a lone target takes a big hit; with others close by it chains to up to three of them and the damage is shared evenly.
- **Arcane Barrier**: runes close around you: a few hearts of shield for some seconds or until it breaks.
- **Arcane Orb**: hold the key: you raise your wand and an orb forms over the crystal, pulling motes of light into itself and growing (mana drains while it grows). Let go to fire it: bigger orb, bigger burst.

Lost your wand? `/aura wand` gives a new one.

## Chains ([how chains work](../features/chains.md))
- **Overload**: Arcane Bolt, then Arcane Orb: the orb hits harder and slows.
- **Retort**: Arcane Barrier, then Arcane Bolt: the bolt hits harder and weakens.

## Ultimate: Last Prism
Hold the Ultimate key: you put the wand away and raise the Last Prism (a nod to Terraria). Six rainbow beams fan out and spiral inward until they merge into one thick beam that hits three times as hard. Your aim turns slowly while you channel and the mana it eats rises as the beams close in; it ends when you let go or run dry. No cooldown.

---

[← back to all auras](overview.md)
