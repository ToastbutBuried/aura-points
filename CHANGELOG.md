# Aura Points – Changelog (4.10 → 5.4.0)

Everyone needs the newest jar on their client, plus **GeckoLib**, **Pufferfish's Skills + Attributes** and **Player Animator** (`player-animation-lib-forge-2.0.4+1.21.1.jar`).

4.36 and later are copied from the in-game changelog (`src/main/resources/aurapoints-changelog.txt`, shown by `/changelog`); 4.35.6 and older are the original detailed notes.

---

## 5.4.0
- Fabric 1.21.1 support: one codebase builds a NeoForge jar (`aura-points-<version>.jar`, unchanged) and a Fabric jar
  (`aura-points-fabric-<version>.jar`). Same features and gameplay on both; worlds, configs and packets stay as they were on NeoForge.
- Fabric needs Fabric API, GeckoLib (Fabric), Pufferfish's Skills + Attributes (Fabric) and, on the client, Player Animator (Fabric).
  Mod Menu (optional) gives the client settings a config button.

## 5.3.0
- The Aura Book has an "Earn Aura" page with every way to earn Aura and a button for the quest book; the Aura Book and Balance Tome look like proper Minecraft books.

## 5.2.0
- Open source (LGPL-3.0-or-later). Everything uses the mod's own art: the new models, effects, scarecrow dummy, arena NPCs and ability icons are always on (the "New models" setting is gone); new Aura Book and Balance Tome textures.
- The skill trees ship inside the mod (Pufferfish's Skills is now a required dependency). The PvP tree unlocks when a boss in `#aurapoints:bosses` dies (Wither, Ender Dragon, Warden, Elder Guardian by default).
- Server owners are configured in `aurapoints-commands.json` (`owners`, empty = permission level 4); ticket reviewers default to none.
- Datapacks: the left-click hook is the function tag `#aurapoints:left_click` (the old advancement is gone).

## 5.1.0
- New Aura Book: everyone gets one (lost it? /aura book or craft book + amethyst shard). Right-click for your aura and abilities (buy right there), rolling, skill points, cosmetics, weeklies and settings. All commands still work; /aura menu opens it too.
- The book offers a short tutorial until you finish it or pick "Don't show again".

## 5.0.3
- New "Item Tertiary" key for datapack items (scoreboard itemtertiary).
- The keybind hint in chat now also warns about a missing or clashing Ultimate key (once awakened) and Item Use / Secondary / Tertiary keys.

## 5.0.2
- Shadow's Night Vision works again (a plain 1 min effect without particles) and goes away at once when you switch it off.
- Wave Survival: switching aura after a boss wave now shows that aura's Ultimate if you awakened it.

## 5.0.1
- Full Aura reset: everyone starts fresh (aura, Aura balance, shop, skill trees, Trials and Feats, awakenings, mastery, cosmetics, weekly challenges).

## 5.0.0
- New arena map: a dusk sky, floating islets, stands round the duel ring with a live board, barred wave gates, and three ring layouts (Pillars, Shattered, Terraces) rolled for each duel or match.
- Training Hall: higher ceiling, a Spar Pit (the only place to fight other players there), a 30-block shooting range with a raised platform and a moving target.

## 4.99.0
- New: /aura leave gets you out of any arena match, duel, Wave Survival or spectating, and back to where you were before the arena, however you got in.
- /arena is gone: every arena command is under /aura arena now (/aura duel and /aura match still work as shortcuts).

## 4.98.0
- Aura Arena: nothing wears out in any mode now. Tools, weapons and armor lose no durability in the arena.
- Wave Survival: after every boss wave you get a 20s break and can switch to any aura you've had before, for the rest of the run (click it in chat). You get your own aura back when the run ends.
- Arena announcements are shorter, and you can show them in chat instead: Mods > Aura Points > Config > Feedback > Arena messages.

## 4.97.0
- Training dummies take a hit properly now: they tip over on their pole away from the blow (further for a harder hit), spring back and forth and settle, arms flapping.

## 4.96.0
- Resonance: Sonic Blossom and Listening Tree are real animated models now. The blossom shoots up and curls shut over its glowing heart, breathes while it listens and bursts open for every dart; the tree rises straight, then rolls its tip over like an ear turning to listen, strains while it holds someone, shudders when it's hit and sinks away at the end.
- Fixed: animated ability models only played a one-off move (a shot, a burst) the first time.

## 4.95.0
- New: Team combos. Some abilities leave a zone for a moment (Heat Wave embers, a Fireball's fire, the Fire Bending flame, the Abyssal Bubble, poison clouds). When an ally's ability goes through it, you get a combo: Fire Tornado (fire + wind), Electrified Bubble (bubble + thunder), Toxic Gale (poison cloud + wind), Glacier (bubble + Ice Spike), Magma Field (fire + Quake), Boiling Bubble (bubble + fire) and Prism Burst (bubble + Prism Beam).
- Allies are your FTB Teams party and your side in an arena match. A combo never hurts either of you or your allies, pays both of you a few Aura and mastery XP (not in the arena, with a short cooldown), and weekly challenges can now ask for team combos.

## 4.94.0
- New: Wave Survival in the Aura Arena ring (1-8 players, solo works). Hold out against endless waves of mobs coming through four gates; every 5th wave brings an aura-touched champion with a boss bar, and from wave 3 more and more of them are aura-touched. Open it with /arena match create waves or the [Wave Survival] button at the Duels NPC.
- Knocked out = back in after the wave; others can join in the breaks between waves. Each cleared wave pays 2 Aura (+10 for a boss wave), ability cooldowns are halved while you fight, your best wave goes on the WAVE RECORDS board, wave 20 earns the title "Wave Breaker", and weekly challenges can now ask for a wave.

## 4.93.0
- New: group matches in the Aura Arena ring. Team Battle (2v2 up to 4v4, best of 3, no friendly fire), Free-for-All (3-8, last one standing takes the round) and King of the Hill (2-8, hold the middle of the ring alone to score). Open one with /arena match or at the Duels NPC; everyone gets a [Join] link.
- Winners get 30 Aura each, and match wins and rounds count toward the weekly arena challenges.

## 4.92.0
- Shadow (Nightstalker): you always see in the dark now (switch it with /aura nightvision), and the bonus is a percentage that grows the darker it is: up to +25% melee damage in full darkness.
- Stunts now also give you experience points (15 each, triple for a boss stunt).
- Datapacks: new scoreboards aura_mastery_<aura> (each aura's mastery level) and aura_mastery (the level of the aura you hold).

## 4.91.0
- New: weekly challenges. Three a week for everyone (new every Monday): an arena goal, a hunt and a stat goal, each paying Aura Points, plus a bonus for all three. See them with /aura weekly or on the board in the Training Hall.
- New: cosmetics (/aura cosmetics, or the link in /aura shop): halo styles, sprint trails, kill effects, titles in chat and tab, and duel victory poses. Some can only be earned - with weekly challenges, aura-touched mob hunts or elemental reactions.

## 4.90.0
- New: aura-touched mobs. A few monsters (2%) spawn with an aura: they glow in its color, wear its halo, have double health and use one small ability of their aura (Shadow steps behind you, Fire throws fire bolts, Void pulls you in...). Killing one pays 5 Aura Points and mastery XP.

## 4.89.0
- New: elemental reactions. An ability hit leaves its element on the target for 5 s (Wet, Burning, Chilled, Poisoned, Charged; water, rain, fire and poison count too). Hit it with the right element for a reaction: Freeze (Wet + Frost), Conduct (Wet + Thunder, jumps to other wet enemies), Firestorm (Burning + Wind), Toxic Cloud (Poisoned + Fire), Steam (Burning + Water), Melt (Chilled + Fire), Overgrowth (Wet + Verdant, roots) and Storm (Charged + Wind).
- Reactions only hit enemies, each target can react once every 2 s, and your first of each one is a stunt. Server owners: all of it is in aurapoints-reactions.json (its own Balance Tome section).

## 4.88.0
- New: Aura Points now needs GeckoLib (the updater installs it for you). The Verdant Viper is a real animated flytrap now: it rises from the ground, gapes while it hunts, snaps shut on a bite and sinks away.
- Balance Tome: every aura is split into one menu per ability (with its on/off switch inside), all Ultimates are in the Ultimates section, and each aura's first page has a dye that shows and switches whether it can be rolled.

## 4.87.0
- Every aura is public now: Earth, Light, Blood and Void join the roll, with mastery, duel lines, trails and arena colors of their own.
- Resonance, Earth, Light, Blood and Void abilities now count toward Aura Mastery.
- Fire (Fireborn): you can see much further in lava.
- Shadow gets a new passive, Nightstalker: in the dark you see clearly and hit harder.
- DPS challenge: no more cheating with cooldowns off (leftover blossoms and the like no longer count).

## 4.86.0
- New look (newModels on): all 43 ability icons redrawn - each one is unique now, centered in its slot, and shows what the ability does.

## 4.85.0
- Tickets: ask for changes with /aura ticket <title> <message> (or /aura ticket <title> and type it as your next chat message, only you see it).
- /aura ticket status shows what happened to your tickets: open, approved, being worked on, done or declined.

## 4.84.0
- Server owners: the Balance Tome (/aura balance) edits every balance value in a menu: click to change, Q to type a value, middle click to reset. Choose whether changes apply at once or after closing (with a final check). Owner only, plus a whitelist (/aura balance whitelist).

## 4.83.0
- Ice Spike: a new frost shard, slim and needle-sharp with splinters of ice.
- The shard now sits in your raised hand while charging instead of floating beside it.

## 4.82.0
- Sharper textures for the aura weapons, the World Tree Bow, the frost shard and the new models: crisp pixels with lit and shaded edges.

## 4.81.0
- New models, off by default: Mods > Aura Points > Config > New models. New Mjölnir, Boss Sigil, Rokaka Fruit, Zeus Gauntlets, ability effects (meteor, tentacles, viper, shadow clone, ice, roots, Ultimates), a scarecrow training dummy, arena staff and ability icons. Only you see your choice.

## 4.80.0
- Ice Spike is now a real frost shard: it grows in your hand while you charge and flies as a glowing crystal before it shatters.

## 4.79.0
- New 3D models for the aura weapons: Soul Reaper, Leviathan's Anchor, Phoenix Fangs, Stormcaller Glaive, Absolute Zero and the World Tree Bow (with a drawn arrow). Their runes and souls glow in the dark.

## 4.78.0
- Server owners: config/aurapoints-commands.json decides per Aura command if it's on, the op level it needs and who is blacklisted.

## 4.77.0
- Admins: /aura progress export and import - player progress as editable files in the aura-progress folder.

## 4.76.1
- Double Jump no longer works while frozen, entangled, rooted, nulled, steering a viper or held on the duel pad.

## 4.76.0
- New Shadow passive Vanish Step: land a Phantom Step while vanished to stay vanished 4s longer (no cooldown).

## 4.75.2
- /aura setaura is admin-only again.

## 4.75.1
- Resonance is open to everyone now, and Frost, Verdant, Thunder and Resonance can all be rolled.

## 4.75.0
- Behind-the-scenes changes.

## 4.74.0
- Behind-the-scenes changes.

## 4.73.0
- Behind-the-scenes changes.

## 4.72.0
- Behind-the-scenes changes.

## 4.71.0
- Awakening: reach Mastery V with an aura, beat 3 bosses and win 5 duels to awaken it in the quest book - a short ceremony, and that aura's Ultimate is yours on the new Aura Ultimate key (V, 5 min cooldown, a gold slot on the HUD).
- Seven Ultimates: Eclipse (Shadow), Tidal Ascension (Water), Phoenix (Fire), Tempest (Wind), Permafrost (Frost), Overgrowth (Verdant) and Judgement (Thunder), each with its own full-body move.

## 4.70.0
- Aura Mastery: using your aura earns its mastery XP; claim Mastery I to V in the quest book (new "Aura Mastery" chapter, with a trial from level II on). Each level makes your abilities look grander, cools them down a little faster and adds a star next to your aura over your name. /aura mastery shows your progress; it's kept per aura, so rerolling loses nothing.
- Frost, Verdant and Thunder now unlock their quest-book sections like the other auras.

## 4.69.0
- Idle animations: stand still for a few seconds and your character comes alive - three little idles for each aura (a Fire player doodles a heart in the air, sets their own fist on fire and panics, juggles a flame; a Shadow player makes a shadow-puppet wolf, ...) plus a few everyone has. Move and it stops at once.
- A soft trail in your aura's style behind you when you sprint. Both can be switched off in Mods > Aura Points > Config (idle animations, your own idles in first person, sprint trails).

## 4.68.0
- New: the Aura Arena (/arena), a floating hub with NPCs that take you to duels, the Training Hall and spectating. Duel anyone with a camera intro, rounds where nobody really dies, shifting pillars and a prize for the winner; watch running duels from above.
- The Training Hall: dummies with damage numbers and DPS, a moving target, a 30-second DPS challenge with records, free cooldowns and sparring (PvP only there and in duels, never in the lobby).

## 4.67.2
- Calmer stance while holding a bending orb: feet close together instead of spread wide, with a slow shift of weight from foot to foot, and your legs keep swinging when you walk.

## 4.67.1
- Smoother server: ability particles now go out bundled once per tick instead of as hundreds of separate packets (big abilities like Inferno Slam, Abyssal Bubble and lightning were the worst), and some per-tick lookups are cached.
- Bug fixes: dying while rooted or frozen can no longer leave you unable to move after respawning; Dash Dive, the Fire Bending flame, Wind's shove and the Abyssal Bubble leave spectators, creative players and armor stands alone; hit marks only show for ability hits (not Thorns or sweeps); and more small fixes.

## 4.67.0
- Big polish update: a livelier ability HUD (cooldowns sweep away, slots pop when ready, react when you press them), full-body ability animations, every aura with its own sounds and charge hums, hit marks, stunt popups, a points toast and a spinning reel for /aura roll.
- Lots of new settings in Mods > Aura Points > Config (HUD position and size, colorblind colors, volume, camera shake, reduced motion...), and /aurahud to drag the HUD where you like. Plus bug fixes.

## 4.66.1
- Wind's Dash launch, Air Bending and wind charges no longer work while you're entangled or steering a viper.
- Bug fixes: Chain Strike keeps your own invisibility and can't leave you floating after a crash; Heat Wave and Shockwave leave spectators and creative players alone.

## 4.66.0
- Behind-the-scenes changes.

## 4.65.3
- Fixed a server crash.

## 4.65.2
- Behind-the-scenes changes.

## 4.65.0
- Behind-the-scenes changes.

## 4.64.0
- Behind-the-scenes changes.

## 4.63.1
- Ability HUD tidied up: panels no longer run off the edge of the screen.

## 4.62.0
- Accessory slots: the server can allow whole stacks in them (ACCESSORY_SINGLE_ITEM in the general config, one item per slot by default).

## 4.61.0
- New accessory slots left of your armor in the inventory (2 for now, any item fits). They drop on death like the rest of your inventory.

## 4.60.0
- Datapacks: every ability cooldown is on a scoreboard now (aura_cd_<ability>, in ticks) and can be set with /scoreboard.
- Datapacks: every left click runs the advancement aurapoints:toastbutburiedm1 (reward: function pluss:generalm1).

## 4.59.2
- Verdant Viper climbs walls up to 2 blocks high now (a bit slower going up) instead of stopping at them.

## 4.59.1
- Entanglement's trap: more arms (11), bigger, and they thrash a little slower.

## 4.59.0
- Hitting someone caught in Entanglement's roots loosens them: an arm tears loose with every hit, and from the 3rd hit on each hit has a chance to free them at once.

## 4.58.0
- Entanglement's trap is now a nest of root arms thrashing about. Stay in reach too long and every arm grabs you, yanks you into the middle and wraps you up - no more poison when you're caught.
- If the trap catches nobody, its arms pump a poison cloud into the air before they sink.

## 4.57.0
- Entanglement's trap only springs if you stay in it for a moment (half a second) - step through quickly and you're fine.
- Caught: root arms burst out of the ground, thrash around you, then wind round you in a tangle.

## 4.56.0
- Entanglement is now a root trap: a nest of roots grows where you aim, rises as a warning, then cages whoever steps in (or stays in). The poison spike field is gone.

## 4.55.0
- Vine Hook: you have to aim properly now (much smaller grab area), and after a catch Entanglement needs 4s before it can cage them.

## 4.54.1
- The heavy thing stops spinning the moment you let go.

## 4.54.0
- The heavy thing flies straight now, head first, and it can take its wielder along.

## 4.53.0
- The heavy thing got a new look, and it can fly now. It always comes back.

## 4.52.0
- Something heavy has arrived. Only the worthy may lift it.

## 4.51.2
- Chidori's charge bar is now thunder blue with flickering sparks (was fire red).

## 4.51.1
- /aura lock and /aura unlock are now owner-only.

## 4.51.0
- Thunder: new ability Chidori (key 2) - hold to gather a chirping lightning blade in your hand, let go to lunge and run your target through.
- Admins can switch auras off with /aura lock <players|all> (and back on with /aura unlock).

## 4.50.0
- New aura in testing: Thunder! Thunderbolt of Zeus - leap into the sky and throw 3 bolts of chain lightning; the third is Wrath of Zeus, calling a storm down where you aim.
- Thunder passives: Static Charge (every 4th charged hit arcs lightning) and Stormborn (lightning can't hurt you, stronger in thunderstorms).

## 4.49.2
- Verdant Viper is now a carnivorous plant: a flytrap head on a green stem that snaps shut when it bites, and a bit bigger.

## 4.49.1
- Verdant Viper turns much quicker and snappier, is slimmer (thin root trail, leafy plant head), homes in on whoever your crosshair is on and bites far more reliably.

## 4.49.0
- Verdant: new ability Entanglement (key 2) - roots grab and cocoon a target with poison until they mash W A S D or cut the 3 green roots; aimed at nothing it plants poison spikes.
- Verdant: new ability Verdant Viper (key 3) - steer a root snake with your view to bite and poison someone; you can't move or be hurt meanwhile, but if a player kills it you're stunned.

## 4.48.7
- Getting grabbed by a Vine Hook no longer spins your camera around.

## 4.48.6
- Vine Hook: if the roots visibly reach a target, it gets pulled (no more near misses), and the roots sit deeper in the ground.

## 4.48.5
- Vine Hook roots grow out of the real ground (the earth bulges up round them) instead of floating, and being pulled glides smoothly instead of jerking your camera.

## 4.48.4
- Vine Hook roots rip straight up out of the ground before they lash out, and you can drag your aim a little while they reach.

## 4.48.3
- Vine Hook roots: no more gaps, warmer brown wood, wrapped in vines, cave vines and leaves, a slow rise then a fast lash - and a caught target always reaches you, straight through walls.

## 4.48.2
- Vine Hook: real roots now tear out of the ground and wrap the target, your hands reach out and yank them in.

## 4.48.1
- Verdant's Vine Hook: two vines now burst out of the ground beside you and grab the target like tentacles.
- Wind's Dash Dive hits harder the higher you dive from.

## 4.48.0
- Testing a new aura: Verdant (Vine Hook pulls targets to you, Bloom grows plants while you sneak). Not in /aura roll yet.

## 4.47.1
- New client setting "Ability animations" (Mods > Aura Points > Config): all, not in first person, or off.

## 4.47.0
- Ability keys are now just "Ability 1 / 2 / 3" (Left Alt, X, ` by default) - every aura uses the same keys. Check Controls once!
- New "Item Use" and "Item Secondary" keys for datapack items.
- Abyssal Bubble: much less shake, and getting hit no longer pops it.

## 4.46.1
- Fireball now always deals the same damage, however far it flies.

## 4.46.0
- New /aura info: what every ability of your aura does (or /aura info <aura> for any other).
- Elemental matchups: Fire is strong against Frost, Water against Fire (+25% damage between players).
- Frost's Ice Shell was nerfed hard for PvP: thinner, and it barely repairs mid-fight.

## 4.45.1
- Frozen means frozen: no more dashing or launching out of an Ice Spike freeze, and held flames, wind balls and bubbles break.

## 4.45.0
- New /changelog command: what changed since you last looked.

## 4.44.0
- Everyone's aura is now shown above their name (turn it off in Mods > Aura Points > Config).
- Removed the "Moneten" line under names.

## 4.43.0
- Every aura now has its own look: a halo over your head that drips particles, plus circling specks.

## 4.42.0
- Ability damage now ignores Protection enchantments (armor itself still counts).

## 4.40.0
- Water: in rain +15% damage and 15% shorter cooldowns, in water +25% and 25%.

## 4.39.0
- Frost's Ice Shell now has 3 layers that wear down and grow back (cracks mend fast, broken layers slowly).
- Air Bending: the fully charged arm pose was reworked.

## 4.38.0
- A fully charged Ice Spike freezes its target completely - until it thaws or gets hit.

## 4.37.0
- New aura: Frost (Ice Spike, Ice Shell, Shatter, Sure-Footed). Not in /aura roll yet.

## 4.36.0
- Abyssal Bubble slowly pulls everyone inside toward you.
- Shockwave hits harder the closer they are.

---

# Older (4.10 → 4.35.6)

## 4.35 – Raised wind
- **4.35.6:** Fully charged, you only see about a third of the wind circling your own charge (clearer view); everyone else still sees all of it. `AIR_BENDING_HOLDER_PARTICLES` (0.35; 0 = none, 1 = all).
- **4.35.5:** The held wind charge stays on the hand: each player's game checks where the raised hand is actually drawn and glides the charge there smoothly (no jumping), for your own arm and for anyone you watch. After the throw the correction fades out. Pose now 135° up, 50° forward, 5 px down (live values).
- **4.35.4:** The whole arm sits lower: it comes out of your side at the waist (still angled out like before), the wind charge on the hand. `AIR_BENDING_RAISED_DROP_PIXELS` (8; the shoulder is at pixel 2, the waist about 10). Network version 19.
- **4.35.3:** Back to the reference pose: the arm goes out **to the side** (100° from hanging, 20° forward), not forward. The wind charge now sits exactly on the hand - the server places it with the same arm math the game draws the arm with. Config is now the arm angles: `AIR_BENDING_RAISED_OUT_DEGREES` (90 = level with the shoulder, 180 = straight up) and `AIR_BENDING_RAISED_FORWARD_DEGREES` (replacing `_RIGHT` / `_FORWARD` / `_HEIGHT`).
- **4.35.2:** The arm now follows `AIR_BENDING_RAISED_RIGHT` / `_FORWARD` / `_HEIGHT` too: it points from the shoulder at that hand position, so moving it moves arm and wind charge together (also right after `/aura reload`, even mid-hold).
- **4.35.1:** The arm is lower and closer in: held at shoulder height in front of you, a little out to the right, with the wind charge just above the hand.
- **Air Bending, fully charged:** you raise your right arm up and out to the side and hold the wind charge above your hand, the other arm relaxed - instead of holding it in front of you. Firing hurls it from up there to wherever your crosshair points (overhead throw). Tunable: `AIR_BENDING_FULL_RAISED` (false = old behavior), `AIR_BENDING_RAISED_RIGHT` / `_FORWARD` / `_HEIGHT`.
- Network version 18: everyone needs the new jar.

## 4.34 – Shader-safe wind charges
- **Wind charges look black with your shaders?** New per-player client setting: **Mods → Aura Points → Config → Shader-safe wind charges** (or `shaderSafeWindCharges = true` in `config/aurapoints-client.toml` on your own PC). Wind charges are then drawn in a way shader packs can handle - they lose the scrolling wind texture but still spin. Off by default; it only changes how they look for you.

## 4.33 – Real wind charge
- **4.33.1:** Calmer circling wind: fewer and smaller specks, rarer gusts, and the rings sit right around the charge (centered on it, 0.25 → 0.6 blocks).
- **Air Bending is a real wind charge now:** while you hold it, an actual wind charge hangs in front of you, with wind specks circling it on three rings. The circling grows as you charge it (`AIR_BENDING_START_SIZE` → `AIR_BENDING_FULL_SIZE`, both in `aurapoints-wind.json`); a big one is held a bit farther out so it doesn't block your view. Fired, it flies as a wind charge with the rings around it and bursts with the big Air Bending blast.

## 4.32 – Air Bending
- **Launch moved to Dash (Wind):** hold **Dash** for 0.5 s to blast yourself high into the air (shoving everyone close away). A tap still dashes (now on release). The launch has its **own 20 s cooldown**, shown as a thin sky-blue bar on the Dash slot, so it no longer blocks anything. Tap Dash high up right after a launch to **dive** back down. Gliding: hold Dash for the big forward boost, tap for the small one (flight set). Shadow's Dash is unchanged.
- **Air Bending (Super Wind Charge):** hold the key for 0.5 s to gather a ball of wind in front of you. Swing it around to charge it like Fire Bending (charge bar in wind colors), then press again to fire it: where it hits, a huge blast hurls everything within 5 blocks far away and high up (some damage too). If you are inside your own blast, it throws you as well, with no fall damage. 30 s cooldown. A tap still throws the normal Super Wind Charge.
- Config (`aurapoints-wind.json`): `SUPER_WIND_CHARGE_LAUNCH_*` is now `WIND_LAUNCH_*` (values carried over), plus `WIND_LAUNCH_ALLOWS_DIVE` and all `AIR_BENDING_*` settings.
- Network version 17: everyone needs the new jar.

## 4.31 – Flight lift
- **Double Jump joins the flight set:** pressing jump while gliding with an elytra lifts you up a little (straight up, no forward push; any downward speed is cancelled first). Own 4 s cooldown, separate from the ground Double Jump, and not limited to once per landing. Tunable: `DOUBLE_JUMP_ELYTRA_LIFT`, `DOUBLE_JUMP_ELYTRA_COOLDOWN_TICKS` in `aurapoints-wind.json`.
- The Double Jump slot also shows its flight cooldown (sky-blue frame) while gliding.

## 4.30 – Flight set
- **Gliding with an elytra is its own ability set:** the elytra boosts of **Super Wind Charge** (hold) and **Dash** now have their own cooldowns, completely separate from the ground versions. You can launch up with Super Wind Charge, open your elytra and boost straight away.
- **No exploit:** using a flight ability never touches the ground cooldown, so swapping the elytra on and off resets nothing (before, the elytra boost overwrote the ground cooldown).
- **HUD:** while gliding, the panel reads "Wind · Flight" and the Dash / Super Wind Charge slots get a sky-blue frame and show their flight cooldowns.

## 4.29 – Water buffs
- **Healing Tide** now also washes away every negative effect (poison, wither, slowness, weakness…) from everyone it reaches, and gives **you** (not the others) Resistance I for 6 s. Tunable in `aurapoints-water.json`: `HEALING_TIDE_CLEANSE`, `HEALING_TIDE_RESISTANCE_DURATION_TICKS`, `HEALING_TIDE_RESISTANCE_AMPLIFIER`.
- **Abyssal Bubble:** while you hold it, **you** have Resistance I, and it lasts 2 s after the bubble ends. Tunable: `BUBBLE_RESISTANCE_AMPLIFIER` (-1 = off), `BUBBLE_RESISTANCE_AFTER_TICKS`.

## 4.28 – Elytra boosts
- **Wind launch while gliding:** holding Super Wind Charge with an elytra boosts you forward along your look direction instead of up. 12 s cooldown (`SUPER_WIND_CHARGE_LAUNCH_ELYTRA_BOOST` / `_COOLDOWN_TICKS`).
- **Dash while gliding** is a weak boost forward instead of the full dash, with its own 5 s cooldown (`DASH_ELYTRA_BOOST` / `DASH_ELYTRA_COOLDOWN_TICKS` in `aurapoints-general.json`).

## 4.27 – Wind launch
- **Super Wind Charge, hold version:** hold the key for 0.5 s to blast yourself high into the air and shove everything within 4 blocks away (a little lift, no damage). A quick tap still throws the charge, but now at the moment you let go. Your landing takes no fall damage. 20 s cooldown. Tunable via `SUPER_WIND_CHARGE_LAUNCH_*` in `aurapoints-wind.json`, or switch it off with `SUPER_WIND_CHARGE_LAUNCH_ENABLED`.

## 4.26 – Burning arm
- **4.26.6:** Balance pass. Fireball max damage 16 → 11, Fire Bending close damage 16 → 12. Fire sight sees through 2 blocks of wall (was 5) and reaches 15 blocks (was 20). Inferno Slam radius 8 → 6, burn 12 s → 8 s. Abyssal Bubble radius 6 → 5, air drain 28 → 22, cooldown 35 s → 45 s.
- **4.26.5:** More forgiving Fire Bending: when the held flame bursts on a mob or player, the cooldown is only **5 s** instead of 20 s (`FIRE_BENDING_CONTACT_COOLDOWN_TICKS` in `aurapoints-fire.json`).
- **4.26.4:** Bug check. Fixed a possible crash in single player when switching worlds while ability fires were still burning.
- **4.26.3:** The burning-arm flames reach just past the fingertips.
- **4.26.2:** Fully charged Fire Bending: the flame particles now wrap your arm from shoulder to fingertips instead of floating as a ball. Each player's own game draws them on your arm, so they don't lag behind with ping, and you see them in first person too.
- **4.26.1:** At full charge you hold the burning arm out to your side.
- **4.26.0:** First try at a burning arm with fire textures (replaced in 4.26.2).

## 4.25 – Animations for every aura
- **New animations**, with variations depending on whether you're on the ground, in the air or in water, plus random variety:
  - **Water:** Shockwave (hammer slam / backhand / air burst / water push), Healing Tide (rising palms / opening from the chest / water circles), Abyssal Bubble (arms out and trembling while held, a jerk when it pops, calm lowering when you let go)
  - **Shadow:** Vanish (crossed arms / cloak sweep), Dash (lunge / flying pose in the air), Phantom Step (low landing, one hand to the ground)
  - **Wind:** Super Wind Charge (palm thrust / two-handed shove / air slam), Dash Dive (arms up while diving, thrown wide on landing), Double Jump, Featherfall (wings spread while gliding)
  - **Fire:** Heat Wave (fling wide / circle sweep)
- **No stuck animations:** moves always finish cleanly, and poses end correctly when you let go, land, die or lose the ability.
- **Super Wind Charge** leaves your hand at the release (2 ticks after the press).
- **Fixed:** dying mid Dash Dive set off the landing shockwave at your respawn point.
- **Fixed:** Featherfall's slow fall while sneaking in mid-air didn't trigger.
- **4.25.1:** Air Dash no longer puts your arms in front of your legs.

## 4.24 – Fireball throws
- **Fireball throw animations:** overhand throw, sidearm hook or two-handed push on the ground (random), a hurl in the air, a push in water.
- The fireball leaves your hand at the release (3 ticks after the press), aimed where you look at that moment.

## 4.23 – Inferno Slam nerf + new config files
- **Inferno Slam:** a ~0.7 s windup where you move 60% slower, then a 0.5 s stun after the slam: no moving, jumping, attacking or abilities. The arms stay down during the stun.
- **Config split:** the balance file is now `aurapoints-general.json` (economy, bosses, shared abilities) plus one file per aura (`-shadow`, `-water`, `-fire`, `-wind`), each with its own on/off switches. All old values were carried over.

## 4.22 – Inferno Slam (Fire combo)
- With a **fully charged Fire Bending flame**, press **Heat Wave**: you raise your arms and slam the ground. The result is a huge fiery blast (8 blocks) that hurls everyone back and up, burns them for **12 s**, and sets lots of fire. The flame is used up.
- **4.22.1:** Fixed the arms showing the bending pose after the slam.

## 4.21 – Fireborn (new Fire passive)
- **Fire can't hurt you:** no fire, burning, campfire, magma block or fireball damage, and you never keep burning. Only lava still burns.

## 4.20 – Fire spreads
- **Fireball** sets up to 3 fires within 2 blocks of where it hits.
- **Heat Wave** is much mightier: an eruption and a rolling wall of fire. At its edge it lights a few random fires, and always one where each hit mob or player stood.
- Ability fires go out on their own after **5 s**, since fire doesn't burn out on this server.

## 4.19 – Abilities vs. monsters
- **All ability damage does 1.5×** against easy monsters (zombies, husks, skeletons, strays, boggeds, spiders, creepers…). The server currently has it at 1.8×.
- The **Abyssal Bubble** now drowns **every monster**, undead and sea monsters included.

## 4.18 – Abyssal Bubble (new Water active)
- **Hold** the key: a 5-block bubble follows you (you walk slower) and players and mobs inside run out of air and drown.
- **Skill check:** your camera shakes harder the longer you hold. If your crosshair drifts too far from where you started, the bubble breaks. Up to 10 s.
- **4.18.1:** Aim HUD with a target ring where you started, the allowed zone around it, a guide line back, and an edge arrow when the target is off screen.
- **4.18.2:** The bubble is much more visible: a dense shell, spinning rings, glow, and a rim on the ground.
- **Shockwave** and **Healing Tide** got new effects so they're easy to tell apart (a hard white slam vs. a soft teal wave).

## 4.17 – Fire Bending stance
- At **full charge**, a sound plays and the flame moves to your right hand at your side, held close to the body. Both arms reach for the flame while charging.
- **4.17.1:** Big bug fix round:
  - fire sight could kick players near large mob farms;
  - stuck poses after someone came back into view;
  - Turkish-language PCs broke Fire/Wind;
  - possible endless fall immunity after a Dash Dive;
  - a few config values that could crash the server.

## 4.16 – Fire Bending animations
- Arm animations for Fire Bending (reach while charging, thrust when firing), visible to everyone nearby. **Needs Player Animator** on every client from now on.

## 4.15 – Flame to the side
- The charged flame moves aside so your crosshair is clear; the shot still goes where you aim.

## 4.14 – Fire sight
- At full charge, every mob and player in front of you glows fire-orange, even through up to 5 blocks of wall. Only you see it.
- **4.14.1:** The held flame bursts on the first mob or player it touches (used up, cooldown starts).

## 4.13 – Fire Bending (new Fire active)
- Summon a flame that follows your look, charge it by swinging it around, then fire a fast shot. It hits harder up close, and the charge bar is shown on the HUD.
- **Fireball buff:** damage grows with distance flown (5 up close, up to 16).
- Pressing the key for an ability you haven't bought tells you to buy it in `/aura shop`.
- **4.13.1:** Fixed endless shot particles at max range and shots skipping close targets; the flame is held 3 blocks out.

## 4.12 – Ability shop
- Your aura's **passives are free**; its **actives are bought in `/aura shop`** (200 Aura, +50 per active you already own there).
- Bought actives stay yours if you roll that aura again later; the Rokaka Fruit refunds them.
- The HUD only shows actives you own.

## 4.11 – Wind rework + on/off switches
- **Wind:** Dash (with the dive), Super Wind Charge, Double Jump | Gale Rider, Featherfall.
- The dive is Wind-only now; Shadow's Dash is a plain dash.
- **`ABILITIES_ENABLED`:** every ability can be switched off server-wide.

## 4.10 – Dash Dive (Shadow at first, Wind-only since 4.11)
- Dash upward, then press Dash again 4+ blocks up to **dive down**: no fall damage, and a small shockwave in front of you on landing.
- **4.10.1:** Dash takes 20 s to recharge after a dive (normal Dash: 10 s).
