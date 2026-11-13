# ipa_rapier natives

Our own build of Sable's Rapier physics library, used for ships straddling portals (`docs/lessons/straddling.md`). It is loaded in place of Sable's `sable_rapier` and driven by Sable's own Java classes (`docs/lessons/physics-backend.md`, D3). Our extra functions are declared in `src/main/java/ipaeronautics/rapier/RapierExt.java`.

**Licence:** Sable's code is PolyForm Shield 1.0.0 (`LICENSE.md` here), as are our changes to it; IPSable's changes on top of it and the Rapier engine are Apache-2.0. See `docs/licensing.md`.

## Origin

- `marten/`: Sable 2.0.6 (`github.com/ryanhcode/sable`, commit `ad8b4d3`), `sable_rapier/src/main/rust/marten`, unchanged.
- `rapier/`: IPSable 0.5.0 (`github.com/r2smith141/IPSable`, commit `f5e0471d`), `natives/rapier`. That is Sable 2.0.3's crate plus IPSable's charts (one physics world for all dimensions), image colliders, clip regions, rope seams (removed, below), portal rims and hardening. Sable's only Rust change from 2.0.3 to 2.0.6 (a log writer in `lib.rs`) is merged in, so `diff` against Sable 2.0.6 shows IPSable's changes plus ours.
- `engine/rapier/`: Rapier as forked by RyanHCode (`github.com/ryanhcode/rapier` at `38e92f1`, the rev Sable pins) plus IPSable's engine changes (about 600 lines in 14 files: collider portal prefix, frame-mapped contact constraints, velocity mapping). Diff against a checkout of `38e92f1` to see them.

Our changes, all marked in file headers:

- Crate and library renamed `sable_rapier` -> `ipa_rapier`, so a native crash log names us.
- JNI symbols: Sable's 53 functions keep Sable's names (`Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_*`, member for member Sable 2.0.6's signatures; from 1.6.0 until the sable-swap work they were renamed for a Java copy). IPSable's `Java_ipl_sable_natives_IplRapierNatives_*` became `Java_ipaeronautics_rapier_RapierExt_*`: 16, 9 of them left after the removals below, plus `stepChartOnly`, `moving_portals.rs`'s 4 and the scaled images' 4 (`createScaledImageCollider`, `setImagePrefixScaled`, `setImageAnchorScaled`, `setImageAnchorBetweenScaled`), 18 in all. The Rust code calls back into Java only through objects passed in, never by class name. Check a build with `nm -D --defined-only` on the Linux library: 53 Sable names and 18 `RapierExt` names.
- `RapierExt_stepChartOnly`: the per-chart rope and joint upkeep of `step` without stepping the world, so every dimension gets it each substep (IPSable's fused step skipped it for all but the last dimension).
- `moving_portals.rs` (2.2.0): images and clip regions of portals mounted on a body (a ship) follow that body every step, and the images get the portal's frame velocity; with it, 7 engine files (`portal_frame_*` fields: collider parent, contact manifold, narrow phase, the coulomb and twist contact builders) add that velocity as a surface velocity of the image (`docs/lessons/ship-mounted-portals.md`).
- Scaled images (portals that change size, 2.9.0): `RapierExt.createScaledImageCollider` / `setImagePrefixScaled` (and the `*Scaled` anchors of `moving_portals.rs`) make the image of a body through a portal of scale k (`x -> k R x + t`). Rapier's poses stay isometries; k is in the shape (`LevelCollider::scale`: the dispatcher makes block cuboids k wide and finds blocks in plot units, `algo.rs` walks the octree in the other side's units and asks for single blocks when the two scales differ) and in the engine (`ColliderParent::portal_scale` composes the collider pose `(R R_b, k R t_b + t)`; manifolds carry `portal_scale1/2`; the coulomb and twist builders map com -> k R com, v -> k R v, inverse mass -> k^2 im, gated per batch so k = 1 stays bit-identical). Hooks and events divide plot points by k. Ropes do not touch scaled images (the generic builder maps no scale). Lessons: `docs/lessons/scaled-ships.md`.
- Portal rims (2.3.0): `ipl_ext.rs` registers the shapes of rim bars; `dispatcher.rs` gives a ship's blocks against them Sable's interior check of block pairs and the ship's full extent (`adjust_aabb_for_body_blocks`), see `docs/lessons/straddle-sessions.md`.
- IPSable natives that nothing calls are removed: dormant bodies (`setBodyDormant`), parent frames (`setParentFrame` and its dispatcher guard), the joint-component query (`connectedSableBodyIds`; jointed ships are grouped in Java, `IPShipJoints`) and rope portal seams (`setRopePortalPrefix`, `remapRope`, `ropesAttachedToSableBody`, `overstretchedRopes`). If rope seams are ever wanted, take them from IPSable 0.5.0's source (`natives/rapier/src/rope.rs` in its repository).
- `dispatcher.rs`: body pairs of different charts never collide. IPSable relied on 16 collision-group bits, which wrap above 16 dimensions. Ropes and boxes (no chart on their shape) still rely on the bits.

## Building

`scripts/build-natives.sh` builds all six targets (Linux, macOS and Windows, each x86_64 and aarch64) in Sable's Docker images (`container/`, tagged `ipa-build-zigbuild` and `ipa-build-xwin` here), with the Rust nightly pinned in `rust-toolchain.toml`, and copies them to `src/main/resources/natives/ipa_rapier/`. The Gradle build refuses to package a jar without all six unless `-PallowMissingNatives` is given. The built libraries are gitignored.

A local `cargo build --release -p ipa_rapier` here is fine for a quick compile check (that toolchain is installed); release builds come from the script (Linux targets glibc 2.17 there).

At runtime `IPPhysicsBackend`, from the head of Sable's `Rapier3D.loadLibrary`, extracts this platform's library to `<game dir>/.ip_aeronautics/natives/` and loads it in place of Sable's. If that fails, or the platform has no library, Sable loads its own and every level runs stock physics.
