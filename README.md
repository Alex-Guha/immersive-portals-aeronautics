# Immersive Portals: Aeronautics

A fork of [Immersive Portals for NeoForge](https://github.com/iPortalTeam/ImmersivePortalsModForNeo) (Minecraft
1.21.1) that makes portals work with [Sable](https://github.com/ryanhcode/sable) ships and Create Aeronautics: ships
seen through portals, ships crossing portals, and ships resting half through a portal as one physical body drawn on
both sides.

Lineage: Immersive Portals for NeoForge v6.0.7 (Apache-2.0) -> Immersive Aeronautics 1.1.4 by ItsSoBread (Apache-2.0,
binary only; rebuilt here as source) -> this project. Seamless crossings build on IPSable 0.5.0 by r2smith141
(Apache-2.0) and a copy of Sable's Rapier physics backend (PolyForm Shield 1.0.0). This project's own code is PolyForm
Shield 1.0.0 too (`LICENSE`, a noncompete licence); the code it took from the Apache-2.0 works stays Apache-2.0
(`LICENSE-APACHE`). See `NOTICE`, `docs/licensing.md` and `docs/provenance.md`.

Mod ids (`immersive_portals_core`, `immersive_portals`, `q_misc_util`) are unchanged on purpose so other mods'
compatibility code keeps working. The jar is `ip_aeronautics-<version>.jar`. Player-facing description:
`DESCRIPTION.md`.

## How it works

**The two mods it joins.** Sable keeps each ship as a sub-level of its dimension. The ship's blocks live in a *plot*,
a block of far-off chunks (chunk coordinates beyond ±100000), and a pose (position and rotation) maps plot space into
the world. Ship physics runs in Sable's native Rapier backend on the server. Clients get ship chunks and poses through
Sable's tracking packets, but only for ships in their own dimension. Immersive Portals (IP) draws a portal by rendering
the destination world from a camera moved through the portal and clipped to the opening. It keeps a client copy of
every world visible through a portal, and it redirects packets for those worlds to the right copy.

**Ships seen through portals** (`compat.sable_integration`, `compat.mixin_sable`). `IPShipThroughPortalSync` samples
the chunks each player sees through portals and syncs the ships in them to that player. Every Sable packet that may
reach a player in another dimension goes through IP's redirection, tagged with the ship's dimension. This is needed
because plot coordinates are per dimension: an untagged packet would land on whichever ship occupies the same plot slot
in the player's current world. When the player walks through the portal, tracking passes between the portal sync and
Sable's own sync without removing and re-adding the ship. For drawing, Sable's ship renderer and Sodium, Iris,
Flywheel, Distant Horizons and Veil are patched to follow IP's portal pass: its clip plane, its swapped render buffers
and its swap of `Minecraft.level`.

**Ships crossing portals.** Two paths:

- *Straddling* (the default, `compat.sable_straddle` + `compat.sable_physics`). A ship can sit half through a portal
  as one body. It stays in its own dimension the whole time; the half past the portal is a projection into the
  destination:
  - **Physics.** We ship a copy of Sable's Rapier backend (`ipaeronautics.rapier`, Rust in `natives/`). It plugs in
    through Sable's pipeline-provider interface and runs every dimension as a chart of one native world, stepped
    together (`IPFusedPhysicsStep`). While a ship touches a portal (`IPStraddleController`, `IPStraddleSessions`), its
    blocks also exist as an *image collider* in the destination's chart. Contacts on both sides are cut at the portal
    plane.
  - **Hand-off.** Once every block of the ship is past the plane, the ship moves into the destination dimension at the
    exact mapped pose and velocity (`IPSableBridge.moveThroughPortal`).
  - **Rendering.** On the client, the ship is cut at the plane in its own dimension, and the far half is drawn again in
    the destination at the mapped pose. At the hand-off, the old copy keeps drawing until the new one has compiled
    (`IPStraddleHandoffLatch`).
  - **Everything else on a straddling ship.** Entity collision, block interaction, raycasts, sounds, particles and
    seats work on both halves (`IPStraddleCut` and friends). Each is routed to the ship's own dimension and mapped
    through the portal.
- *One-step crossing* (the fallback, `IPShipPortalTraversalImpl`): the fork's original behaviour. When a ship touches
  a portal, the whole ship moves to the destination at once, if there is room for its blocks there. This path is used
  when straddling is turned off, or when our natives or the Sable version are unavailable. In that case every level
  runs Sable's stock physics unchanged. Clients never need the natives, because physics runs on the server.

**Other mods' machinery** (`compat.create_integration`, `compat.mixin_create` and the Aeronautics parts of the Sable
packages). This covers Create Aeronautics ropes, balloons, burners and the physics staff; Create trains on portal track
and their seated riders; and Shield Generators' fields. Each gets the same treatment: anything keyed by plot position
or "current level" is routed to the ship's own dimension, and anything drawn with its own shader is clipped at the
portal plane.

**Smaller pieces from the fork:** Distant Horizons near clipping, Iris depth fixes and a
shader-distance portal cull (`compat.mixin_dh`, `mixin_physicsmod`, `mixin_render_tweaks`, `iris_integration`).
Mixins on optional mods are skipped when the mod is absent (`compat.IPOptionalModMixinPlugin`).

The full story of each subject, including the rules learned along the way, is in `docs/lessons/`.

## Development

### Requirements

- **JDK 21.** Newer JDKs break NeoGradle and Mixin; point `JAVA_HOME` at a 21 (here:
  `/usr/lib/jvm/java-21-openjdk-amd64`).
- **Docker**, to build the `ipa_rapier` native libraries for all six platforms. Optional for Java-only work.
- **Python 3** for the helper scripts (no packages needed).
- A Rust toolchain only for quick local compile checks of `natives/` (`rust-toolchain.toml` pins the nightly).

### First build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew build -x test --no-daemon --console=plain -PallowMissingNatives   # resolves dependencies
scripts/extract-nested-deps.sh      # fills deps/ with jar-in-jar libraries (Sodium 0.8, Veil, Flywheel, Aeronautics modules...)
scripts/build-natives.sh            # ipa_rapier for all six targets, ~4 min (Docker)
./gradlew build -x test --no-daemon --console=plain
```

- Output: `build/libs/ip_aeronautics-<ver>.jar` (`-all` is the jarJar variant). A warm build takes ~1 min.
- The build refuses to package a jar without all six native libraries (gitignored) unless `-PallowMissingNatives`.
  Such a jar runs, but every level falls back to Sable's own physics and ships cross in one step.
- Gradle only sees the wrapper jars of Sodium, Sable, Aeronautics and friends. If a class from a compat mod is
  "missing", rerun `scripts/extract-nested-deps.sh` before debugging anything else; also after changing compile
  dependencies.
- Never run two Gradle invocations at once in this repository.
- `scripts/build-natives.sh linux-x86_64` builds one target; `cargo check --release -p ipa_rapier` in `natives/` is a
  quick compile check. Details: `natives/README.md`.
- After changing the natives' Rust dependencies (`natives/Cargo.lock`), rerun `python3 scripts/native-licenses.py`: it
  rewrites the licence list of the linked crates shipped in the jar (`--check` tells whether it is current).

### Dependencies

Pinned in `build.gradle` and `gradle.properties`: NeoForge 21.1.152, Sodium 0.8.13, Iris 1.8.14-beta.1,
Pehkui, Create 6.0.10, and compile-only Sable 2.0.6, Create Aeronautics 1.3.2, Distant Horizons 3.3.3. Modrinth maven coordinates are version ids (e.g. `maven.modrinth:sable:fg9dTRz9`); look them up with
`curl https://api.modrinth.com/v2/project/<slug>/version`. After bumping Sodium, Iris, NeoForge or Sable, run
`scripts/mixincheck.py` (`docs/testing.md`). Our physics backend is a copy of Sable 2.0.6's and refuses any other
Sable version.

### Testing

Headless server tests for physics and server logic, the game for everything client-side, static checks for mixin
targets and file headers: `docs/testing.md`. `runClient`/`runServer` do not work under WSL (no mouse capture).

### Releases

Every jar handed out for testing gets its own version: bump `mod_version` in `gradle.properties` (patch for fixes,
minor for features), move the "Unreleased" notes in `changelog.md` under a `[x.y.z] - date` heading, rebuild, commit,
and tag `v<ver>`. The version line continues from the fork (1.1.4 -> 1.2.0).
Before a public release: the checks in `docs/licensing.md`, and `scripts/native-licenses.py` if `natives/Cargo.lock`
changed since the last one.

### New source files

Every Java file starts with an origin and licence comment (Apache-2.0 section 4). After adding files run
`python3 scripts/stamp-headers.py`, then add a line by hand for anything ported from or modelled on IPSable, and check
with `python3 scripts/stamp-headers.py --check`. Changes to upstream files are marked `Immersive Aeronautics:` in the
code. Rules: `docs/provenance.md`.

### Mixin conventions

- Compat mixin configs: `imm_ptl_{sable,create,dh,physicsmod}_compat.mixins.json`, `imm_ptl_render_tweaks.mixins.json`,
  all `required: false`, registered in `build.gradle` (`mixin {}`) and `META-INF/neoforge.mods.toml`. Mixins on other
  mods use string `targets` and `remap = false`; `IPCompatMixinPlugin` gates Sodium, Flywheel and DH mixins by mod
  presence.
- Upstream configs run with `defaultRequire: 1`; compat mixins carry `require = 0`. Confirm such injections with
  `-Dmixin.debug.export=true`.
- Server-class mixins go in a config's `mixins` section, never `client`.
- Every config sets `"overwrites": {"conformVisibility": true}`: another mod's access transformer can make a method
  public that we `@Overwrite` as private (Petrolpark's Library widens `ChunkMap.onChunkReadyToSend`), and Mixin then
  refuses to load the class unless the overwrite may take the target's visibility (2.3.4).

## Repository layout

| Path | What |
|---|---|
| `src/main/java/qouteall/` | Immersive Portals (upstream), with our changes |
| `src/main/java/qouteall/imm_ptl/core/compat/` | Compat layers; `sable_integration`, `mixin_sable`, `sable_physics`, `sable_straddle` hold the Sable work |
| `src/main/java/ipaeronautics/rapier/` | Our copy of Sable's Rapier backend (PolyForm Shield) |
| `natives/` | Rust sources of `ipa_rapier` (Sable's crate with IPSable's changes, vendored Rapier engine) |
| `scripts/` | Build, test and audit helpers; `scripts/server-test/` holds headless test command files |
| `docs/` | Licensing, provenance, testing, bugs, feature requests and the changelog; subfolders under "Docs" below |
| `reference/` | The fork's decompile (`decompiled/`), its diff against upstream and a per-class analysis |
| `deps/` | Extracted jar-in-jar libraries for compilation (gitignored) |

## Docs

- `docs/lessons/`: how Sable, IP and the other mods behave where it matters to us, and the history of each feature, one file
  per subject (`README.md` there is the index; `straddling.md` is the way into ships straddling portals).
- `docs/plans/`: work not built yet. `docs/completed/`: what is left of work that shipped. `docs/reports/`: surveys.
- `docs/BUGS.md`: open bug reports, concerns and the owed test matrix. `docs/FEATURE-REQUESTS.md`. `docs/changelog.md`: changes per
  release.
- `docs/glossary.md`: our internal terms (far half, portal pass, back-bleed, ...).
- `docs/testing.md`: headless and in-game testing, static checks.
- `docs/licensing.md`, `docs/provenance.md`, `NOTICE`: licences and the origin of every file.
- `natives/README.md`: the native library's origin and build.
- `reference/fork-analysis.md`: what Immersive Aeronautics 1.1.4 changed, class by class.

## Git

- `main` = upstream v6.0.7 + all our work (the straddling work of `seamless-ships`, 1.5.1 onward, merged at 2.0.0).
- Standalone repository since 2.0.0: no longer tracks iPortalTeam/ImmersivePortalsModForNeo, whose history up to
  v6.0.7 it keeps. Tag `v6.0.7` is the base, `parity-ia1.1.4` the state that matched the fork.

## Upstream

Immersive Portals by qouteall and contributors: [Website](https://qouteall.fun/immptl/),
[Wiki](https://qouteall.fun/immptl/wiki/), [API for other mods](https://qouteall.fun/immptl/wiki/API-for-Other-Mods.html),
[Implementation details](https://qouteall.fun/immptl/wiki/Implementation-Details). This fork is maintained
independently: please do not report its bugs upstream.
