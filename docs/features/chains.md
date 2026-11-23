# Ability chains

Use one ability, then a second one soon after, and the second **hits harder and adds an effect**: a slow, poison, burn, a heal, a cooldown refund ... Each aura has its own chains, shown on the abilities in the Aura Book (in gold).

| Aura | Chain | Link | Effect |
|---|---|---|---|
| Shadow | Shade Step | Vanish > Dash | speed |
| Shadow | Slip Away | Dash > Vanish | cooldown refund |
| Dark | Collapse | Void Pull > Null Orb | slow |
| Dark | Rift Snare | Rift Step > Void Pull | weaken |
| Water | Undertow | Shockwave > Abyssal Bubble | wet |
| Water | Riptide | Healing Tide > Shockwave | heal |
| Fire | Stoked | Fire Bending > Fireball | ignite |
| Fire | Backdraft | Fireball > Heat Wave | extra damage |
| Wind | Slipstream | Dash > Super Wind Charge | knock up |
| Wind | Tailspin | Super Wind Charge > Dash | cooldown refund |
| Frost | Black Ice | Frost Path > Ice Spike | chill |
| Frost | Cold Snap | Ice Spike > Frost Path | cooldown refund |
| Verdant | Snare | Vine Hook > Entanglement | slow |
| Verdant | Ambush Bite | Entanglement > Verdant Viper | poison |
| Thunder | Overcharge | Thunderbolt > Chidori | charged |
| Thunder | Arc Return | Chidori > Thunderbolt | cooldown refund |
| Earth | Skyfall | Pillar Lift > Boulder Toss | slow |
| Earth | Aftershock | Boulder Toss > Quake | knock up |
| Light | Focused Light | Solar Flash > Prism Beam | weaken |
| Light | Afterglow | Radiant Pulse > Solar Flash | heal |
| Blood | Blood Price | Crimson Sacrifice > Hemorrhage | lifesteal |
| Blood | Open Vein | Hemorrhage > Blood Tether | weaken |
| Resonance | Echo Crush | Sonic Blossom > Abyssal Grasp | weaken |
| Resonance | Attuned | Listening Tree > Sonic Blossom | cooldown refund |

These are the defaults. Server owners edit them in `config/aurapoints-chains.json` (see the [configuration reference](../reference/configuration.md#chains)). Bonuses are capped so stacked chains cannot run away (`CHAINS_MAX_BONUS`, `CHAINS_STACK_CAP`).

[← docs index](../README.md)
