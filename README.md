# Useful Planetary Shield

Makes the Planetary Shield actually shield the planet.

In vanilla 0.98a the Planetary Shield industry does **nothing** against orbital
bombardment - it is a x3 ground-defense multiplier (anti-raid/invasion) plus
meteor suppression and a pretty bubble. This mod gives it the identity its name
promises, with a tradeoff.

## What it does

While the shield is **operational** (built, not disrupted):

- **Industry disruption** from tactical or saturation bombardment is reduced to
  50% of normal duration (configurable)
- **Stability loss** from bombardment is reduced to 50% (configurable)
- **No pollution** is added by bombardment (configurable)
- The colony **cannot be reduced in size** by saturation bombardment (configurable)
- The colony **cannot be decivilized** - neither by saturation bombardment nor
  by stability collapse (configurable)
- In exchange, the vanilla **x3 ground-defense bonus is removed** by default
  (configurable) - the shield stops bombs, not marines

The balance valve is built into vanilla data: a bombardment disrupts the shield
generator itself (`disruptDanger EXTREME`, no `no_saturation_bombardment` tag),
so a shielded colony absorbs the *first* strike and is then unprotected until
the shield repairs. An already-disrupted shield absorbs nothing. Sustained
bombardment campaigns still get through; single strikes do not gut a colony.

Applies to every market with a functional shield - including NPC markets the
player bombards - unless "Player Colonies Only" is enabled.

All values configurable in-game via LunaLib (optional; falls back to
`data/config/settings.json` without it).

## How it works (implementation notes)

Vanilla has no cancellable pre-bombardment hook: the AI crisis path
(`FGRaidAction.performRaid` -> `MarketCMD.doBombardment`) fires no listeners at
all, and the player-path listeners (`ColonyPlayerHostileActListener`) are
post-hoc. So:

- **Mitigation is reactive**: a transient `EveryFrameScript` snapshots each
  shielded market every ~0.1 days (per-industry disruption days, unrest
  penalty, size, pollution, `RECENTLY_BOMBARDED` flag + expiry). A bombardment
  is detected by the flag newly appearing / its expiry re-arming (repeat
  strikes), or by 2+ industries getting disrupted at once while the flag is set
  (raids only ever disrupt one industry). If the shield was functional at the
  previous snapshot, the deltas are partially rolled back.
- **Deciv prevention is proactive**: shielded markets carry the vanilla
  `$story_critical` memory flag, which all three bombardment code paths check
  (`Misc.isStoryCritical` -> `destroy = false`, downgrading destruction to a
  size reduction the rollback then restores) and which `DecivTracker` checks
  for stability-collapse deciv. The flag is tracked with a `$ups_` marker so a
  flag set by vanilla or another mod is never clobbered, and it is removed the
  moment the shield stops being functional.
- The ground-defense change is a drop-in industry plugin
  (`ups.UPSPlanetaryShield extends PlanetaryShield`) swapped via
  `industries.csv`, stripping the `GROUND_DEFENSES_MOD` mults (base, alpha
  core, improvement) unless re-enabled.

## Caveats

- **Uninstalling**: if you remove the mod, first disable "Block
  Decivilization" (or disable the mod's protection by disrupting/removing the
  shield) and load+save once, so no `$story_critical` flag is left on your
  colonies. A leftover flag would permanently prevent that colony's
  destruction.
- A ground **raid** landing within 30 days of a bombardment on a colony with
  fewer than 2 disruptable industries can, in a corner case, be mistaken for a
  follow-up bombardment and have its disruption halved.
- With "Block Decivilization" on, a story-critical-flagged colony also gets
  vanilla's other story-critical courtesies while the shield is up (e.g. some
  events will not destroy it outright). Considered in-theme.

## Building

```powershell
.\compile.ps1
```

Requires a JDK on PATH (or `$env:JAVA_HOME`). Produces `jars/UPS.jar`.
