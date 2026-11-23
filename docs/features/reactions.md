# Elemental reactions

An ability hit leaves its aura's element on the target as a **mark**:

| Aura | Mark |
|---|---|
| Water | Wet |
| Fire | Burning |
| Frost | Chilled |
| Verdant | Poisoned |
| Thunder | Charged |
| Wind | none |

Real water and rain, fire, poison and powder snow count as marks too, and so does standing in a matching [local storm](storms.md). A later ability hit of the right element sets the reaction off and uses the mark up. Enemies only; one reaction per target at a time.

| Reaction | Needs the mark | Set off by |
|---|---|---|
| Freeze | Wet | Frost |
| Conduct | Wet | Thunder |
| Firestorm | Burning | Wind |
| Toxic Cloud | Poisoned | Fire |
| Steam | Burning | Water |
| Melt | Chilled | Fire |
| Overgrowth | Wet | Verdant |
| Storm | Charged | Wind |

Each reaction can be switched off in `aurapoints-reactions.json`. `/aura reactions` lists them in game, and the Training Hall's [combo trainer](training-hall.md) shows them as they go off. Reactions feed weekly challenges and advancements.

[← docs index](../README.md)
