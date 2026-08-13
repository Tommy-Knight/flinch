# Flinch

Play an animation on your own character when something happens to you in combat.

Take a 0 and dodge. Take a hit and panic. Get poisoned and shrug. It is entirely cosmetic and
entirely client-side — nobody else sees it, and nothing is sent to the server.

## Triggers

| Trigger | Fires on |
|---|---|
| Block | A 0 or a blocked hit |
| Damage | Any hit above 0 |
| Poison | Poison damage |
| Venom | Venom damage |
| Heal | Being healed |

Each trigger has its own animation, and any of them can be left on **None**.

## Choosing an animation

Every sequence in the game is available — around 14,000 of them. The list is ordered so the
useful ones come first: the player emotes, then the rest of the human rig, then NPC and scenery
animations last.

- **Type to search** any dropdown. It matches the name, the underlying gameval constant, and the
  raw id.
- **Arrow up and down** to preview. Each animation plays on your character as you pass it, so you
  can hear nothing and see everything before committing.

Animations belong to a skeleton, so the NPC ones near the bottom of the list will look wrong on a
player. They are included anyway, because some of them are funnier that way.

## Animation cancel

Off by default: your own attack swings interrupt the animation, as they normally would.

On: the animation holds its ground against your swings, resuming at the frame it would have
reached rather than restarting, so a fast weapon no longer cuts it short.

Either way, clicking to walk, attack or interact cancels the animation immediately — you are
never stuck waiting for one to finish.

## Settings

Everything lives in the sidebar panel. The config panel holds a single option to hide the
sidebar button if you want it out of the way.

## Licence

BSD-2-Clause.
