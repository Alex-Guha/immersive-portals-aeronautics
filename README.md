<div align="center">

# Immersive Portals: Aeronautics

[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1735987?style=flat-square&logo=curseforge&label=CurseForge&color=%23EB622B)](https://www.curseforge.com/minecraft/mc-mods/immersive-portals-aeronautics) [![Modrinth Downloads](https://img.shields.io/modrinth/dt/jkNDQMu6?style=flat-square&logo=modrinth&label=Modrinth&color=%2300AF5C)](https://modrinth.com/mod/immersive-portals-aeronautics) ![Static Badge](https://img.shields.io/badge/License-PolyForm%20Shield%201.0.0-blue?style=flat-square)

</div>

A fork of [Immersive Portals for NeoForge](https://github.com/iPortalTeam/ImmersivePortalsModForNeo) (Minecraft 1.21.1) that makes portals work with [Sable](https://github.com/ryanhcode/sable) ships and Create Aeronautics: ships seen through portals, ships crossing portals, and ships resting half through a portal as one physical body drawn on both sides.

Lineage: Immersive Portals for NeoForge v6.0.7 (Apache-2.0) -> Immersive Aeronautics 1.1.4 by ItsSoBread (Apache-2.0, binary only; rebuilt here as source) -> this project. Seamless crossings build on IPSable 0.5.0 by r2smith141 (Apache-2.0) and our own build of Sable's Rapier physics library (PolyForm Shield 1.0.0). This project's own code is PolyForm Shield 1.0.0 too (`LICENSE`, a noncompete licence); the code it took from the Apache-2.0 works stays Apache-2.0 (`LICENSE-APACHE`). See `NOTICE` and `LICENSING.md`.

Mod ids (`immersive_portals_core`, `immersive_portals`, `q_misc_util`) are unchanged on purpose so other mods' compatibility code keeps working. The jar is `ip_aeronautics-<version>.jar`.

## How it works

**The two mods it joins.** Sable keeps each ship as a sub-level of its dimension. The ship's blocks live in a *plot*, a block of far-off chunks (chunk coordinates beyond ±100000), and a pose (position and rotation) maps plot space into the world. Ship physics runs in Sable's native Rapier backend on the server. Clients get ship chunks and poses through Sable's tracking packets, but only for ships in their own dimension. Immersive Portals (IP) draws a portal by rendering the destination world from a camera moved through the portal and clipped to the opening. It keeps a client copy of every world visible through a portal, and it redirects packets for those worlds to the right copy.

**Ships seen through portals** (`compat.sable_integration`, `compat.mixin_sable`). `IPShipThroughPortalSync` samples the chunks each player sees through portals and syncs the ships in them to that player. Every Sable packet that may reach a player in another dimension goes through IP's redirection, tagged with the ship's dimension. This is needed because plot coordinates are per dimension: an untagged packet would land on whichever ship occupies the same plot slot in the player's current world. When the player walks through the portal, tracking passes between the portal sync and Sable's own sync without removing and re-adding the ship. For drawing, Sable's ship renderer and Sodium, Iris, Flywheel, Distant Horizons and Veil are patched to follow IP's portal pass: its clip plane, its swapped render buffers and its swap of `Minecraft.level`.

**Ships crossing portals.** Two paths:

- *Straddling* (the default, `compat.sable_straddle` + `compat.sable_physics`). A ship can sit half through a portal as one body. It stays in its own dimension the whole time; the half past the portal is a projection into the destination:
  - **Physics.** We ship our own build of Sable's Rapier library (Rust in `natives/`). It is loaded in place of Sable's, driven by Sable's own Java classes (`IPPhysicsBackend`, mixins on Sable's pipeline), and runs every dimension as a chart of one native world, stepped together (`IPFusedPhysicsStep`). While a ship touches a portal (`IPStraddleController`, `IPStraddleSessions`), its blocks also exist as an *image collider* in the destination's chart. Contacts on both sides are cut at the portal plane.
  - **Hand-off.** Once every block of the ship is past the plane, the ship moves into the destination dimension at the exact mapped pose and velocity (`IPSableBridge.moveThroughPortal`).
  - **Rendering.** On the client, the ship is cut at the plane in its own dimension, and the far half is drawn again in the destination at the mapped pose. At the hand-off, the old copy keeps drawing until the new one has compiled (`IPStraddleHandoffLatch`).
  - **Everything else on a straddling ship.** Entity collision, block interaction, raycasts, sounds, particles and seats work on both halves (`IPStraddleCut` and friends). Each is routed to the ship's own dimension and mapped through the portal.
- *One-step crossing* (the fallback, `IPShipPortalTraversalImpl`): the fork's original behaviour. When a ship touches a portal, the whole ship moves to the destination at once, if there is room for its blocks there. This path is used when straddling is turned off, or when our natives or the Sable version are unavailable. In that case every level runs Sable's stock physics unchanged. Clients never need the natives, because physics runs on the server.

**Other mods' machinery** (`compat.create_integration`, `compat.mixin_create` and the Aeronautics parts of the Sable packages). This covers Create Aeronautics ropes, balloons, burners and the physics staff; Create trains on portal track and their seated riders; and Shield Generators' fields. Each gets the same treatment: anything keyed by plot position or "current level" is routed to the ship's own dimension, and anything drawn with its own shader is clipped at the portal plane.

**Smaller pieces from the fork:** Distant Horizons near clipping, Iris depth fixes and a shader-distance portal cull (`compat.mixin_dh`, `mixin_physicsmod`, `mixin_render_tweaks`, `iris_integration`). Mixins on optional mods are skipped when the mod is absent (`compat.IPOptionalModMixinPlugin`).

## Development

### Requirements

- **JDK 21.** Newer JDKs break NeoGradle and Mixin; point `JAVA_HOME` at a 21 (on Debian and Ubuntu: `/usr/lib/jvm/java-21-openjdk-amd64`).
- **Docker**, to build the `ipa_rapier` native libraries for all six platforms. Not needed for a build without them (below).
- **Python 3** for the helper scripts (no packages needed).
- A Rust toolchain only for quick local compile checks of `natives/` (`rust-toolchain.toml` pins the nightly).

### First build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew build -x test --no-daemon --console=plain -PallowMissingNatives -PwithoutVoxy   # resolves dependencies
scripts/extract-nested-deps.sh      # fills deps/ with jar-in-jar libraries (Sodium 0.8, Veil, Flywheel, Aeronautics modules...)
```

Then either build the natives and the full jar:

```bash
scripts/build-natives.sh            # ipa_rapier for all six targets, ~4 min (Docker)
./gradlew build -x test --no-daemon --console=plain -PwithoutVoxy
```

or keep building with `-PallowMissingNatives`, which is enough for work on the Java side that doesn't touch straddling physics.

- Output: `build/libs/ip_aeronautics-<ver>.jar` (`-all` is the jarJar variant). A warm build takes ~1 min.
- The native libraries are not in the repository (`src/main/resources/natives/ipa_rapier/`, gitignored). The build refuses to package a jar without all six unless `-PallowMissingNatives` is given. Such a jar runs, but without straddling: every level runs Sable's own physics and ships cross portals in one step.
- Gradle only sees the wrapper jars of Sodium, Sable, Aeronautics and friends. If a class from a compat mod is "missing", rerun `scripts/extract-nested-deps.sh` before debugging anything else; also after changing compile dependencies.
- `-PwithoutVoxy` leaves the Voxy compat out (`compat/mixin_voxy`, `compat/voxy_integration` and their mixin config). It compiles against a Voxy jar patched by Roxy, which is not published here, so any clone without that jar in `deps/` needs the flag. Such a jar runs everywhere but has no Voxy support; releases always include the Voxy compat.
- Never run two Gradle invocations at once in this repository.
- `scripts/build-natives.sh linux-x86_64` builds one target; `cargo check --release -p ipa_rapier` in `natives/` is a quick compile check. Details: `natives/README.md`.
- After changing the natives' Rust dependencies (`natives/Cargo.lock`), rerun `python3 scripts/native-licenses.py`: it rewrites the licence list of the linked crates shipped in the jar (`--check` tells whether it is current).

### Dependencies

Pinned in `build.gradle` and `gradle.properties`: NeoForge 21.1.152, Sodium 0.8.13, Iris 1.8.14-beta.1, Pehkui, Create 6.0.10, and compile-only Sable 2.0.6, Create Aeronautics 1.3.2, Distant Horizons 3.3.3, Colorwheel 1.3.2, SodiumDynamicLights 1.0.10 and Sable Player Ragdoll 0.7.5. Other mods we work with (Voxy, Create: Pocket Sized, Create Teleporters, ...) are reached through string mixin targets or reflection. Modrinth maven coordinates are version ids (e.g. `maven.modrinth:sable:fg9dTRz9`); look them up with `curl https://api.modrinth.com/v2/project/<slug>/version`. After bumping Sodium, Iris, NeoForge, Sable, Create, Create Aeronautics or Flywheel, run `scripts/mixincheck.py <repo> <classpath> <config.json...>`: it checks that every class, method and field the mixins name exists in the given jars (absolute paths; put the named Minecraft jar from `~/.gradle/caches/neoformruntime/intermediate_results/compiledWithNeoForge_*_output.jar` on the classpath; `JAVAP` picks the javap). Our physics library stands in for Sable 2.0.6's and refuses any other Sable version.

### Testing

Test in game, with the built jar in a normal game install, not with `runClient`/`runServer`. Ships need Sable and Create Aeronautics; straddling needs a jar with the natives. Static checks: `scripts/mixincheck.py` after a dependency bump (above) and `python3 scripts/stamp-headers.py --check` (below).

### Versions

`<x.y.z>` is a release: a patch version for fixes, a minor version for features, the changes in `changelog/` (one file per minor version, index in its `README.md`). A four-part version (`2.9.0.3-<name>`) is a test build of work in progress and never a release. The version line continues from the fork (1.1.4 -> 1.2.0).

### New source files

Every Java file starts with an origin and licence comment (Apache-2.0 section 4). After adding files run `python3 scripts/stamp-headers.py`, then add a line by hand for anything ported from or modelled on IPSable, and check with `python3 scripts/stamp-headers.py --check`. Changes to upstream files are marked `Immersive Aeronautics:` in the code. Rules: `LICENSING.md`.

### Mixin conventions

- Compat mixin configs: `imm_ptl_{sable,create,dh,voxy,physicsmod}_compat.mixins.json`, `imm_ptl_render_tweaks.mixins.json`, all `required: false`, registered in `build.gradle` (`mixin {}`) and `META-INF/neoforge.mods.toml`. Mixins on other mods use string `targets` and `remap = false`; `IPOptionalModMixinPlugin` skips them when their mod is absent, and `IPCompatMixinPlugin` does the same for the Iris and Sodium mixins of the upstream `imm_ptl_compat.mixins.json`.
- Upstream configs run with `defaultRequire: 1`; compat mixins carry `require = 0`. Confirm such injections with `-Dmixin.debug.export=true`.
- Server-class mixins go in a config's `mixins` section, never `client`.
- Every config sets `"overwrites": {"conformVisibility": true}`: another mod's access transformer can make a method public that we `@Overwrite` as private (Petrolpark's Library widens `ChunkMap.onChunkReadyToSend`), and Mixin then refuses to load the class unless the overwrite may take the target's visibility (2.3.4).

## Repository layout

| Path | What |
|---|---|
| `src/main/java/qouteall/` | Immersive Portals (upstream), with our changes |
| `src/main/java/qouteall/imm_ptl/core/compat/` | Compat layers; `sable_integration`, `mixin_sable`, `sable_physics`, `sable_straddle` hold the Sable work |
| `src/main/java/ipaeronautics/rapier/` | `RapierExt`: our library's functions beyond Sable's (fixed by their JNI names) |
| `natives/` | Rust sources of `ipa_rapier` (Sable's crate with IPSable's changes, vendored Rapier engine) |
| `scripts/` | Build helpers: natives, nested dependencies, the natives' licence list, file headers, mixin target check |
| `changelog/` | Changes per release, one file per minor version |
| `deps/` | Extracted jar-in-jar libraries for compilation (gitignored) |

## Contributing

Bug reports, feature requests and pull requests: `CONTRIBUTING.md`, which also covers the licence of contributions. Licences, file headers and credits: `LICENSING.md`.

## Notes

The maintainer's working notes (design notes per subject, plans, trackers, test records) and the tooling that serves only their workflow are kept in a private repository. When a code comment cites `docs/...`, it means those notes: the comment itself says what the code does and why, and the file is not in this repository. Changes per release: `changelog/`. Native library: `natives/README.md`.

## History

Upstream Immersive Portals for NeoForge's history is kept as it is up to tag `v6.0.7`, the base of this project. From there it is one commit per public release, tagged `v<x.y.z>`; the work in each release is described in `changelog/`. The repository is standalone: it does not track iPortalTeam/ImmersivePortalsModForNeo.

## Upstream

Immersive Portals by qouteall and contributors: [Website](https://qouteall.fun/immptl/), [Wiki](https://qouteall.fun/immptl/wiki/), [API for other mods](https://qouteall.fun/immptl/wiki/API-for-Other-Mods.html), [Implementation details](https://qouteall.fun/immptl/wiki/Implementation-Details). This fork is maintained independently: please do not report its bugs upstream.
