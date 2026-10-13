# ipa_rapier natives

The Rust half of our own copy of Sable's Rapier physics backend, used for ships straddling portals
(`docs/seamless-ships.md`). The Java half is `src/main/java/ipaeronautics/rapier/`.

**Licence:** Sable's code is PolyForm Shield 1.0.0 (`LICENSE.md` here), as are our changes to it; IPSable's changes
on top of it and the Rapier engine are Apache-2.0. See `docs/licensing.md`.

## Origin

- `marten/`: Sable 2.0.6 (`github.com/ryanhcode/sable`, commit `ad8b4d3`), `sable_rapier/src/main/rust/marten`,
  unchanged.
- `rapier/`: IPSable 0.5.0 (`github.com/r2smith141/IPSable`, commit `f5e0471d`), `natives/rapier`. That is Sable
  2.0.3's crate plus IPSable's charts (one physics world for all dimensions), image colliders, clip regions, rope
  seams, portal rims and hardening. Sable's only Rust change from 2.0.3 to 2.0.6 (a log writer in `lib.rs`) is merged
  in, so `diff` against Sable 2.0.6 shows IPSable's changes plus ours.
- `engine/rapier/`: Rapier as forked by RyanHCode (`github.com/ryanhcode/rapier` at `38e92f1`, the rev Sable pins)
  plus IPSable's engine changes (about 600 lines in 14 files: collider portal prefix, frame-mapped contact
  constraints, velocity mapping). Diff against a checkout of `38e92f1` to see them.

Our changes, all marked in file headers:

- Crate and library renamed `sable_rapier` -> `ipa_rapier`, so both libraries can be loaded in one JVM.
- JNI symbols renamed: `Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_*` -> `Java_ipaeronautics_rapier_Rapier3D_*`
  (Sable's 53 functions) and IPSable's `Java_ipl_sable_natives_IplRapierNatives_*` -> `Java_ipaeronautics_rapier_RapierExt_*`
  (16). The Rust code calls back into Java only through object classes, never by class name.
- `Rapier3D_stepChartOnly`: the per-chart rope and joint upkeep of `step` without stepping the world, so every
  dimension gets it each substep (IPSable's fused step skipped it for all but the last dimension).
- `dispatcher.rs`: body pairs of different charts never collide. IPSable relied on 16 collision-group bits, which wrap
  above 16 dimensions. Ropes and boxes (no chart on their shape) still rely on the bits.

## Building

`scripts/build-natives.sh` builds all six targets (Linux, macOS and Windows, each x86_64 and aarch64) in Sable's
Docker images (`container/`, tagged `ipa-build-zigbuild` and `ipa-build-xwin` here), with the Rust nightly pinned in
`rust-toolchain.toml`, and copies them to `src/main/resources/natives/ipa_rapier/`. The Gradle build refuses to package
a jar without all six unless `-PallowMissingNatives` is given. The built libraries are gitignored.

A local `cargo build --release -p ipa_rapier` here is fine for a quick compile check (that toolchain is installed);
release builds come from the script (Linux targets glibc 2.17 there).

At runtime `Rapier3D` extracts this platform's library to `<game dir>/.ip_aeronautics/natives/` and loads it. If
that fails, or the platform has no library, `IPPhysicsPipelineProvider` gives every level Sable's own pipeline.
