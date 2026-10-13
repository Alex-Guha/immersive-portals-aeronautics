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

Pinned in `build.gradle` and `gradle.properties`: NeoForge 21.1.152, Sodium 0.8.13, Iris 1.8.14-beta.1, Cloth Config,
Pehkui, Create 6.0.10, and compile-only Sable 2.0.6, Create Aeronautics 1.3.2, Distant Horizons 3.3.3, Physics Mod
3.0.34. Modrinth maven coordinates are version ids (e.g. `maven.modrinth:sable:fg9dTRz9`); look them up with
`curl https://api.modrinth.com/v2/project/<slug>/version`. After bumping Sodium, Iris, NeoForge or Sable, run
`scripts/mixincheck.py` (`docs/testing.md`). Our physics backend is a copy of Sable 2.0.6's and refuses any other
Sable version.

### Testing

Headless server tests for physics and server logic, the game for everything client-side, static checks for mixin
targets and file headers: `docs/testing.md`. `runClient`/`runServer` do not work under WSL (no mouse capture).

### Releases

Every jar handed out for testing gets its own version: bump `mod_version` in `gradle.properties` (patch for fixes,
minor for features), move the "Unreleased" notes in `changelog.md` under a `[x.y.z] - date` heading, rebuild, commit,
tag `v<ver>`, and delete stale jars from `build/libs`. The version line continues from the fork (1.1.4 -> 1.2.0).
Before a public release: the licence list of the Rust crates in `ipa_rapier`, and the checks in `docs/licensing.md`.

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

## Repository layout

| Path | What |
|---|---|
| `src/main/java/qouteall/` | Immersive Portals (upstream), with our changes |
| `src/main/java/qouteall/imm_ptl/core/compat/` | Compat layers; `sable_integration`, `mixin_sable`, `sable_physics`, `sable_straddle` hold the Sable work |
| `src/main/java/ipaeronautics/rapier/` | Our copy of Sable's Rapier backend (PolyForm Shield) |
| `natives/` | Rust sources of `ipa_rapier` (Sable's crate with IPSable's changes, vendored Rapier engine) |
| `scripts/` | Build, test and audit helpers; `scripts/server-test/` holds headless test command files |
| `docs/` | Plans, licensing, provenance, testing; `docs/lessons/` holds what we learned, one file per subject |
| `reference/` | The fork's decompile (`decompiled/`), its diff against upstream and a per-class analysis |
| `deps/` | Extracted jar-in-jar libraries for compilation (gitignored) |
| `BUGS.md` | Open bug reports, concerns and the owed test matrix |
| `changelog.md` | Changes per release |

## Docs

- `docs/seamless-ships.md`: ships straddling portals, status and next steps.
- `docs/lessons/`: how Sable, IP and the other mods behave where it matters to us, and the history of each feature.
- `docs/testing.md`: headless and in-game testing, static checks.
- `docs/licensing.md`, `docs/provenance.md`, `NOTICE`: licences and the origin of every file.
- `natives/README.md`: the native library's origin and build.
- `reference/fork-analysis.md`: what Immersive Aeronautics 1.1.4 changed, class by class.

## Git

- `main` = upstream v6.0.7 + our work up to 1.5.0; `seamless-ships` carries the straddling work (1.5.1 onward).
- Remote `upstream` = iPortalTeam/ImmersivePortalsModForNeo; tag `v6.0.7` is the base, `parity-ia1.1.4` the state
  that matched the fork.

## Upstream

Immersive Portals by qouteall and contributors: [Website](https://qouteall.fun/immptl/),
[Wiki](https://qouteall.fun/immptl/wiki/), [API for other mods](https://qouteall.fun/immptl/wiki/API-for-Other-Mods.html),
[Implementation details](https://qouteall.fun/immptl/wiki/Implementation-Details). This fork is maintained
independently: please do not report its bugs upstream.
