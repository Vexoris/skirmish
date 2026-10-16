# Skirmish

**An escape tool and a combat lock, for servers where loot can be taken.**

Free and open source. Paper 1.21+. No dependencies, no database, no economy hook.

[**Download the latest jar →**](../../releases/latest)

---

Two mechanics that only make sense together:

- **The Blink Feather** — right-click to step up to 20 blocks along your line of sight, on a cooldown.
- **The combat lock** — while you are fighting you cannot pearl, chorus or `/home` away.

A combat lock on its own is a punishment. An escape tool on its own is a way to never be caught. Together they say something specific: **you may not leave a fight, but you may always try to survive one.**

## The Blink Feather

Right-click and you are twenty blocks away — behind the pillar, on the roof, across the ravine.

- **It cannot cross a wall.** The ray stops at the first block a body cannot occupy, so it can never put someone inside a base, a vault or a locked room — regardless of what protection plugin you run, or whether you run one at all. This is geometry, not a permission check.
- **It refuses instead of killing you.** Aim across a ravine and there is no floor within reach, so the blink is declined rather than dropping you twenty blocks. A movement tool that can kill you is one nobody presses at four hearts, which is the only moment it is for.
- **A refusal costs nothing.** No landing means no cooldown spent. A tool that eats its cooldown on a failed activation fails at exactly the moment somebody needed it.
- **It lands you as far as it can reach**, not one step in. Look at a rooftop eighteen blocks off and you arrive on the rooftop.
- **Both ends draw a puff and play a note.** Deliberate: a pursuer has to be able to follow. An escape nobody can track is a disappearance, and the chase is the point.
- **Vanilla cooldown sweep** across the item face, so players can see when it is ready.

## The combat lock

Any hit between two players puts both in combat for a configurable window.

- Ender pearls, chorus fruit and teleport commands are refused while tagged.
- An action bar shows the seconds remaining — a player who cannot see the timer experiences a refused teleport as a broken command rather than as a rule.
- **The feather is exempt**, and that is not a hole: it is bounded by its range, it cannot cross a wall, it leaves a visible trail at both ends, and it costs a cooldown. None of that is true of a pearl chain.
- Mobs do **not** tag you by default. On a server full of hostile mobs that would be a near-permanent lock — a worse bug than the one it fixes. Turn it on deliberately for boss or raid events.

## Install

1. Drop `Skirmish-1.0.0.jar` into `plugins/`.
2. Restart.
3. `/skirmish give` to put a feather in your hand.

## Commands & permissions

| | |
|---|---|
| `/skirmish give [player]` | Mint a Blink Feather |
| `/skirmish reload` | Re-read the config |
| `skirmish.admin` | Both of the above (default: op) |
| `skirmish.bypass` | Teleport freely while in combat (default: false) |

## Configuration

```yaml
blink:
  range: 20            # clamped 3..64
  cooldown-ticks: 100  # floored at 20 — a zero cooldown is sustained flight
combat:
  seconds: 15          # clamped 3..120
  block-teleports: true
  mobs-tag-you: false  # see above before turning this on
```

**Pick `range` against your arenas, not against a feeling.** The blink should cross most of a fighting space and never the whole of it. At 20 blocks in a 26-wide room, an escape moves you out of reach while leaving your pursuer in the same fight. Set it wider than your arenas and the tool stops being a decision.

## Compatibility

Written to run on **Paper 1.21 through current**. The one API that differs across that range — the teleport cause for chorus fruit, which is `CHORUS_FRUIT` on 1.21.x and `CONSUMABLE_EFFECT` from 26.2 — is matched by name rather than by enum constant, so a single jar covers both. Item markers go on through `editMeta`, available since 1.14, for the same reason.

Built and tested against Paper 26.2. If you run it on an older 1.21.x build and something misbehaves, open an issue with your exact server version.

## Building

```bash
gradle build      # jar lands in build/libs/
```

There is also `build-offline.sh` for machines with no gradle and no network — see the header in that file.

## Notes for anyone extending it

The decision about *where* a blink lands lives in `BlinkPlan`, which touches no Bukkit type at all and is covered by its own test suite. The listener only supplies what the world says and performs the consequences. That split is why the safety properties — cannot pass a wall, refuses over a void, never lands you inside a block — are proven offline rather than asserted in a comment.

The tests are written so that a no-op implementation fails them. If you change the geometry, run them.

## Licence

GPL-3.0-or-later. Use it, change it, run it on anything. If you distribute a modified version, it stays open too.
