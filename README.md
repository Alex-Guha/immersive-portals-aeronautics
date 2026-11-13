<div align="center">

# Immersive Portals: Aeronautics

[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1735987?style=flat-square&logo=curseforge&label=CurseForge&color=%23EB622B)](https://www.curseforge.com/minecraft/mc-mods/immersive-portals-aeronautics) [![Modrinth Downloads](https://img.shields.io/modrinth/dt/jkNDQMu6?style=flat-square&logo=modrinth&label=Modrinth&color=%2300AF5C)](https://modrinth.com/mod/immersive-portals-aeronautics) ![Static Badge](https://img.shields.io/badge/License-PolyForm%20Shield%201.0.0-blue?style=flat-square) [![Build](https://img.shields.io/github/actions/workflow/status/Alex-Guha/immersive-portals-aeronautics/build.yml?branch=main&style=flat-square&logo=github&label=Build)](https://github.com/Alex-Guha/immersive-portals-aeronautics/actions/workflows/build.yml) [![Discord](https://img.shields.io/discord/1558244213487829002?style=flat-square&logo=discord)](https://discord.gg/R6jjbqnThA)

</div>

Picking up where [Immersive Portals](https://github.com/iPortalTeam/ImmersivePortalsModForNeo) left off, this mod **replaces** it, allowing portals to work alongside Create as well as Create Aeronautics (or more generally, Sable).

![Aeronautics ship viewed through a portal from the Nether](https://media.forgecdn.net/attachments/2049/160/2026-10-13_05-34-50-png.png)

## Highlights:

*   Butter smooth transitions for Sable objects through portals
*   Create's trains, pipes, conveyor belts, and shafts work seamlessly with portals!
*   DH & Voxy LODs render through portals
*   No visual glitches or rendering issues (unless there's a mod incompatibility)
*   Shader support!
*   Vast inter-mod compatibility support. If you find an issue, I will almost certainly fix it.
*   So, so many fixes on the base Immersive Portals
*   …and more!

### Good to know

- **Portals on ships.** A portal on a ship links to a fixed portal in the other dimension, and breaks if it is carried through another portal. Moving one end of a portal is an easy way to travel far, so the setting *Portals on ships* chooses Off, Limited or Unlimited. Limited, the default, breaks the portal once its two ends are farther apart than *Ship Portal Range* (in chunks).
- **Air pressure.** Air pressure can differ between a portal's two ends, so a ship may sink or rise after passing through unless the portals are placed with care.
- **Frame rate.** *View Distance Through Portals* limits how far you see past a portal before the view fades into fog. Drawing less behind portals helps the frame rate when several are in view. A full performance update will come soon.

### Compatibility

Works with:
- Sodium and Iris, with shaders: BSL, Complementary Unbound, Complementary Reimagined and Sundial Lite are tested. If the view through a portal flickers, turn off the shader's TAA (often under "Post-processing").
- Distant Horizons. Turn off its anti-aliasing if LODs jitter through portals.
- Voxy with Roxy, and Neo Voxy.
- Separate Sable Render Distance (SSRD), Create Sable Dynamic Lights, Create: Teleporters, [Create: Pocket Sized](https://www.curseforge.com/minecraft/mc-mods/pocket) (scaled portals resize ships passing through), [Shield Generators](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics-shield-generator-force-field), and many others not listed. If you run into an issue, make a github issue or post it in the discord.

Partly compatible: Vista (no crash, but its far cameras shouldn't work through portals).

Not compatible: Create Transferables and other mods with their own pipes and shafts through portals, Stackable planar dimensions, Dimensional Threading (doesn't actually parallelize anyways).

## How it works

Immersive Portals draws a portal by rendering the world on the other side, seen from a camera moved through the portal and cut to the opening. To do that, a client keeps a copy of every world it can see through a portal. Sable keeps each ship as a separate block space with its own position and rotation, simulated by its own physics engine, and normally only tells a player about ships in the player's own dimension.

This mod joins the two:

- **Seeing ships.** Players are sent the ships they can see through portals, from any dimension, and those ships are drawn through the portal like the rest of the world. That includes Sodium, Iris shaders, Flywheel, Distant Horizons and Voxy.
- **Crossing portals.** A ship touching a portal stays in its own dimension while its front half becomes a copy in the destination, with the same blocks at the mapped position and speed. The copy collides with the world on that side. Both halves are cut at the portal, so each side sees only its part. Players, mobs, seats, ropes and block interactions work on either half. Once the whole ship has passed the portal, it moves to the destination at exactly the position and speed it had, so there is no jolt. For this, the mod ships its own build of Sable's physics library, which runs every dimension in one simulation. Without it, ships cross in one step: the whole ship moves once it touches the portal, if there is room for it.
- **Create and Aeronautics parts.** Trains, belts, shafts, pipes, ropes, balloons and the physics staff are taught to work across a portal and on both halves of a ship.
- **Portals on ships.** A portal can be part of a ship and move with it, carrying players, items, trains and other ships through it.

## Building from source

You need JDK 21 (newer ones break NeoGradle and Mixin) and Python 3. Docker is needed only for the physics libraries.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew downloadModJars --no-daemon --console=plain   # downloads the mods we compile against
scripts/extract-nested-deps.sh      # unpacks the libraries nested inside their jars into deps/
scripts/build-natives.sh            # optional, needs Docker: the physics libraries for all six platforms (~4 min)
./gradlew build --no-daemon --console=plain -PwithoutVoxy   # add -PallowMissingNatives if you skipped the line above
scripts/check.sh                    # the checks CI runs on every push
```

The jar is `build/libs/ip_aeronautics-<version>.jar`. The build also runs the unit tests (`-x test` skips them).
- `-PallowMissingNatives` builds a jar without the physics libraries. It runs, but ships cross portals in one step.
- `-PwithoutVoxy` leaves out Voxy support, which compiles against a patched Voxy jar that isn't published.
- The build refuses physics libraries built from another version of `natives/` than the one checked out: run `scripts/build-natives.sh` again, or build with `-PallowStaleNatives` to package them anyway.
- If the build reports a class from another mod as missing, run `scripts/extract-nested-deps.sh` again.

## Contributing and reporting bugs

Report bugs in the [Discord](https://discord.gg/R6jjbqnThA) or through [GitHub issues](../../issues/new/choose), and here rather than to Immersive Portals, Immersive Aeronautics or Sable. Pull requests are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Changes in each release are in [changelog/](changelog/).

## Credits and licence

This mod began as a fork of [Immersive Aeronautics](https://www.curseforge.com/minecraft/mc-mods/immersive-aeronautics) by ItsSoBread, rebuilt here as source, and of [IPSable](https://github.com/r2smith141/IPSable) by r2smith141 and GaMiR9195 (who has since resumed working, see https://github.com/GaMiR9195/immersiveportals-x-sable). Both are forks of [Immersive Portals for NeoForge](https://github.com/iPortalTeam/ImmersivePortalsModForNeo) by qouteall, Nick1st and contributors ([website](https://qouteall.fun/immptl/), [wiki](https://qouteall.fun/immptl/wiki/), [API for other mods](https://qouteall.fun/immptl/wiki/API-for-Other-Mods.html)). Ship crossings use a modified copy of [Sable](https://github.com/ryanhcode/sable)'s physics library by RyanHCode, with the [Rapier](https://github.com/ryanhcode/rapier) physics engine. This fork is maintained independently.

This mod's own code is under the [PolyForm Shield License 1.0.0](LICENSE). You may include it in modpacks, patch it and build add-ons for it, but not republish it or a fork of it as a competing mod. If this mod stops being maintained, that restriction no longer applies. The code taken from Immersive Portals, Immersive Aeronautics, IPSable and Rapier stays under the [Apache License 2.0](LICENSE-APACHE), and the Sable code under PolyForm Shield 1.0.0. Every project is credited in [NOTICE](NOTICE), and [LICENSING.md](LICENSING.md) says which part is under which licence.
