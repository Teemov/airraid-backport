# AirRaid — Forge 1.20.1 port

Illagers fly [Immersive Aircraft](https://modrinth.com/mod/immersive-aircraft) planes: Pillagers, Vindicators and Witches pilot biplanes, gyrodynes, airships and quadrocopters, with strafing runs, rotary cannons, heavy crossbows and bombing.

This is a Forge 1.20.1 port of [AirRaid by Ace](https://github.com/adunis/airraid) (MIT, Fabric 1.21.1 only).

## Requirements (in your `mods` folder)

- Minecraft **1.20.1** with **Forge 47.x**
- **Immersive Aircraft** for Forge 1.20.1 (1.4.x or 1.5.x)
- this mod's jar
- optional: **Guard Villagers** for Forge 1.20.1, for the village air defence

## Features

- **Ambushes** — roaming illager squadrons can attack players exploring the world.
- **Outpost air defence** — near a Pillager Outpost, defensive squadrons take off and aircraft appear parked around the outpost.
- **Raid air support** — from wave 3 onward, raids can call in a bomber escort over the village.
- `/airraid spawn <count> [vehicle] [weapon]` and `/airraid reload` (operators only).

## Guard Villagers air defence (optional)

Install [Guard Villagers](https://www.curseforge.com/minecraft/mc-mods/guard-villagers) (Forge 1.20.1) alongside this mod and villages fight back:

- **Scrambles** — when an illager squadron appears within 128 blocks of a village that has guards, there's a 75% chance 1–2 guard aircraft take off from above the bell.
- **Raids** — from wave 3, each raid wave has a 50% chance to send up guard interceptors too.
- **Dogfights** — guards go after illager aircraft first, then raiders and monsters on the ground. They never fire through players, villagers, golems or other guards, and don't carry bombs by default. Illager pilots now hunt guard aircraft as well as players.
- **Return to base** — with nothing to fight, guard aircraft circle the village. After 3 quiet minutes with no player within 48 blocks, they're removed so they don't pile up.
- At most 4 guard aircraft per village at once.
- `/airraid defend <count> [vehicle]` scrambles guard aircraft from the nearest village (or above you if there's none).

**Parked aircraft:** give the pilot `NoAI:1b` (e.g. in a `/summon`) and the aircraft stays locked in mid-air where it was spawned, without fighting or needing fuel. Remove the tag and it flies normally again.

All of this lives under `guardConfig` in the config file. Without Guard Villagers installed, the feature simply stays off.

## Config

`config/ia_pillager_addon.json` is created on first launch (same format as the Fabric original). Key settings:

| Setting | Meaning |
|---|---|
| `checkIntervalTicks` | How often spawn checks run (200 = every 10 s) |
| `minSpawnDistance`, `spawnHeight` | Where ambush squadrons appear relative to the player |
| `structureSearchRadius` | How close (blocks) a structure must be to count as "nearby" (new in this port) |
| `factions[].ambushChance` | Chance per check of a random ambush |
| `factions[].structureSpawnChance` | Chance per check of a defensive squadron near a listed structure |
| `factions[].parkedVehicleChance`, `maxParkedVehicles` | Parked aircraft at structures |
| `factions[].structures` | Structure ids, or tags prefixed with `#` (e.g. `#minecraft:village`) |
| `factions[].vehicles` | Weighted aircraft list |
| `factions[].weaponAmmoCounts` | Ammo given to each weapon |
| `aiDamageToAircraftMultiplier` | How hard AI-fired shells/arrows hit aircraft (default 0.35; 1.0 = Immersive Aircraft default, where 6 cannon hits kill a biplane) |
| `aiRotaryCannonFireInterval` | Ticks between AI cannon shots (default 4, same as a player) |
| `cleanupAircraftArrows`, `aircraftArrowLifetimeTicks` | Remove arrows fired from AI aircraft once they land (default on, max 5 s) |
| `raidConfig.*` | Raid air support (enable, `startWave`, `spawnChancePerWave`, squad size, weapons) |
| `guardConfig.*` | Guard air defence: `scrambleChance`, `raidScrambleChance`, `raidStartWave`, `maxAircraftPerVillage`, `idleDespawnSeconds`, `requireGuardsInVillage`, vehicles, weapons |

Use `/airraid reload` after editing.

## Building

**Without installing anything (GitHub):** create a new GitHub repository, upload this folder's contents, and open the **Actions** tab. The *Build mod jar* workflow runs automatically; when it finishes, download the `airraid-forge-1.20.1` artifact — the zip contains the jar.

**Locally:** install a Java 17 JDK, then in this folder run `./gradlew build` (Windows: `gradlew.bat build`). The jar appears in `build/libs/`. The first build downloads Minecraft and Forge and takes several minutes.

## Differences from the Fabric original

- Mob pilot AI hooks Immersive Aircraft's own `tickPilot()` instead of `tick()`, so no refmap is needed.
- Raid air support listens for new raid-wave raiders joining the world instead of mixing into `Raid`.
- Structure detection now actually finds Pillager Outposts (the original looked them up as a structure *tag*, which doesn't exist for outposts) and only searches the configured radius.
- Pilots ignore creative/spectator players and drop dead targets.
- Config is tolerant of missing fields; unknown vehicle ids no longer spawn pigs.
- Only illagers (and mobs listed in the config) get the pilot AI. A random mob that climbs into someone's plane is left alone.

## Boats

Immersive Aircraft has no boats. For illagers in boats on 1.20.1, add the **Ships** mod/datapack ("illager ships" on Modrinth/Planet Minecraft), which spawns illager ship dungeons and lets illagers patrol the sea in boats. It runs alongside this mod.

## License

MIT — see `LICENSE`. Original work © Ace.
