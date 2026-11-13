# Licensing

For anyone who redistributes this mod, includes it in a pack, or contributes code to it: which works it is built from, which part is under which licence, how each file says where it comes from, and how the Apache License's conditions are met. It is not legal advice.

## Chain of works

| # | Work | Licence | What this project took |
|---|---|---|---|
| 1 | [Immersive Portals for NeoForge](https://github.com/iPortalTeam/ImmersivePortalsModForNeo) v6.0.7 (iPortalTeam: qouteall, Nick1st and contributors) | Apache-2.0 | The base: every upstream file, git tag `v6.0.7` in this repository's history |
| 2 | [Immersive Aeronautics](https://www.curseforge.com/minecraft/mc-mods/immersive-aeronautics) 1.1.4 (ItsSoBread), a binary-only fork of (1) | Apache-2.0 (`license` in the jar's `neoforge.mods.toml`) | Its added classes, rebuilt as source from the published jar, and its edits to upstream classes |
| 3 | [Sable](https://github.com/ryanhcode/sable) 2.0.6 (RyanHCode) | PolyForm Shield 1.0.0 | Its `sable_rapier` crate (Rust), copied and modified |
| 4 | [IPSable](https://github.com/r2smith141/IPSable) 0.5.0 (r2smith141), commit `f5e0471d` | Apache-2.0 (its copy of Sable's crate stays PolyForm Shield) | Its changes to Sable's crate and to Rapier, taken whole; the design, algorithms and numbers of its straddling, re-implemented in our own classes |
| 5 | [Rapier](https://github.com/ryanhcode/rapier) at `38e92f1` (Dimforge, as forked by RyanHCode) | Apache-2.0 | The physics engine, vendored with IPSable's changes |

Upstream (1) ships no NOTICE file and no per-file copyright headers; neither does (2). The LGPL-3.0 licence of the original Fabric Immersive Portals does not apply: the NeoForge repository ships its own Apache-2.0 licence. No GPL or LGPL code is involved.

This project depends on, but ships no code from, Create, Create Aeronautics, Sodium, Iris, Flywheel, Veil, Distant Horizons, Physics Mod, Pehkui, Create: Pocket Sized, Colorwheel, Voxy and the other mods it has compatibility code for.

## Which part is under which licence

| Part | Licence | Licensor |
|---|---|---|
| Code this project wrote: new files, our changes in modified and reconstructed files, our changes to the Sable copy, our resources and scripts | PolyForm Shield 1.0.0 (`LICENSE`) | Alex Guha |
| The Sable copy (`natives/marten`, Sable's code in `natives/rapier`) | PolyForm Shield 1.0.0 | RyanHCode |
| Code taken from works 1, 2, 4 and 5 | Apache-2.0, unchanged (`LICENSE-APACHE`) | their authors |

Apache-2.0 section 4 lets a derivative work put its own modifications, or the work as a whole, under other terms, provided the Apache conditions keep being met for the code it took (below). What this means:

- **Noncompete.** Anyone may use, change and redistribute this project's code for any purpose except "providing any product that competes with the software or any product the licensor ... provides using the software". Modpacks, private patches, add-ons and compatibility mods are fine; republishing this mod or a fork of it as an alternative to it is not.
- **The Apache parts stay Apache.** Apache-2.0's grant runs from each original author to every recipient, so the inherited code stays usable by anyone under Apache-2.0, from its original sources or from this repository. The restriction covers this project's own work only.
- **Discontinued products.** PolyForm Shield lets others compete with a product its licensor has stopped providing, unless the licensor ships a `Licensor Line of Business:` line. This project ships none, on purpose: if this mod is abandoned, anyone may pick it up and republish it.
- **Required notice.** `NOTICE` carries `Required Notice: Copyright 2026 Alex Guha`. PolyForm Shield's "Notices" clause makes redistributors pass it on with the licence text or its URL. Sable provides no such line.
- **Sable's noncompete.** This mod is an add-on whose Sable parts only work with Sable installed, and Sable's own physics pipeline stays the fallback; the maintainer's position is that it does not compete with Sable.
- **AI-assisted code.** Copyright, and with it any licence, covers only human authorship; how far code written mostly with AI assistance is protected is unsettled. The licences apply to whatever of it is protectable.

## How each file is marked

### Java sources (`src/main/java`)

Every Java file states its origin and licence in a comment at its top. `scripts/stamp-headers.py` writes the A, B and C headers; `python3 scripts/stamp-headers.py --check` verifies them (run it after adding files).

| Kind | Rule | Header |
|---|---|---|
| Unmodified upstream | in (1) at `v6.0.7` and unchanged | none (upstream ships no per-file notices); Apache-2.0 |
| A, modified upstream | in (1) at `v6.0.7`, changed since | "modified file", names (1), and (2) as well when the fork changed the file too; changes marked `Immersive Aeronautics:` in the code; our changes PolyForm Shield, the rest Apache-2.0 |
| B, reconstructed | not in (1); the class exists in the fork's jar (2) | "reconstructed and modified file", names (2) and (1); our changes PolyForm Shield, the rest Apache-2.0 |
| C, new | neither | "Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0)" |

`RapierExt` (`ipaeronautics/rapier`) declares the JNI entry points of our native additions after IPSable's `IplRapierNatives`; it sits in that package only because the JNI names require it.

### Code derived from IPSable

IPSable's Java assumes its hosting dimension throughout, so none of it was copied verbatim: its algorithms were ported (a "port") or its approach followed in our own code (a "follows"). Either way the file's header has one more line naming the IPSable class or classes and "by r2smith141 (Apache License 2.0)"; `stamp-headers.py --check` lists them. Paths under `qouteall/imm_ptl/core/` unless they start with `ipaeronautics/`:

| File | IPSable counterpart | Relation |
|---|---|---|
| `compat/sable_straddle/IPShipCrossingDetector` | `PortalCrossingDetector` | port |
| `compat/sable_straddle/IPPortalIsometry` | `IplStraddlePoseMap.StraddleMapping` | follows |
| `compat/sable_straddle/IPStraddleSessions` | `IplAtlasStraddleSession` | follows |
| `compat/sable_straddle/IPStraddleController` | `SableTransitController` | follows |
| `compat/sable_straddle/IPStraddleCut` | `IplStraddleBlockClipMixin`, `IplStraddleCollisionClip` | follows |
| `compat/sable_straddle/IPStraddleSync` | `IplStraddleSessionSync` | follows |
| `compat/sable_straddle/IPPortalRims` | `IplStraddlePortalRim` | follows |
| `compat/sable_straddle/IPStraddleInteraction` | `IplProjectionAwareClipMixin`, `IplStraddleInteractDistanceMixin` | follows |
| `compat/sable_straddle/client/IPStraddleClientSessions` | `IplStraddleSessionStore` | follows |
| `compat/sable_straddle/client/IPStraddleRender` | second clip plane, projection draw (approach) | follows |
| `compat/sable_straddle/client/IPStraddlePick` | `IplProjectionAwareClipMixin`, `IplStraddleOutlineMixin` | follows |
| `compat/sable_straddle/client/IPStraddleHandoffLatch` | hand-off latch (idea) | follows |
| `compat/sable_integration/IPPhysicsStaffPortals` | `IplStaffDragSessionOverwriteMixin` (re-framing on crossings) | follows |
| `compat/sable_physics/IPFusedPhysicsStep` | `IplFusedStep` | follows |
| `compat/create_integration/IPFlywheelStraddleClip` | `IplFlywheelCompilationMixin`, `IplFlywheelEmbeddedClipMixin` | follows |
| `compat/mixin_create/MixinFlywheelEnvironment_StraddleClip` | `IplFlywheelEmbeddedClipMixin` | follows |
| `compat/mixin_sable/MixinSableEntityCollision_StraddleCut` | `IplStraddleCollisionPoseMixin` | follows |
| `compat/mixin_sable/MixinSablePhysicsTickets_StraddleImages` | `IplHostedTicketManagerMixin` | follows |
| `compat/mixin_sable/MixinSableChunkedRenderData_StraddleClip` | `SableSourceClipMixin` | follows |
| `compat/mixin_sable/MixinSableBlockEntityRenderer_StraddleCut` | `SableVanillaSubLevelBERMixin` | follows |
| `compat/mixin_sable/MixinSableRenderDispatcher_StraddleProjection` | `IplHostedSubLevelRenderMixin` | follows |
| `compat/mixin_sable/MixinSableClientSubLevel_StraddlePose` | `IplClientSubLevelPoseOverrideMixin` | follows |
| `compat/mixin_sable/MixinSableCanFall_StraddleCut` | `IplStraddleCanFallMixin` | follows |
| `compat/mixin_sable/MixinClipContext_StraddleCut` | `IplProjectionAwareClipMixin` | follows |
| `compat/mixin_sable/MixinLevelRenderer_StraddleOutline` | `IplStraddleOutlineMixin` | follows |
| `compat/mixin_sable/MixinSablePhysicsSystem_FusedStep` | `IplFusedStepMixin` | follows |
| `compat/mixin_sable/MixinSableServerContainer_FusedTick` | `IplFusedStepMixin` | follows |
| `compat/mixin_sable/MixinSableStopTracking_HandoffLatch` | `IplParentDimSync` hand-off | follows |
| `ipaeronautics/rapier/RapierExt` | `IplRapierNatives` (JNI declarations) | port |
| `mixin/common/collision/MixinPlayer_Collision` (upstream file) | its `MixinPlayer_Collision` priority | follows |

A comparison against the IPSable source found no other counterparts: the rope cut, CPU plane clipping, the Flywheel far half, far-half ground and block effects, the client hand-off timeline, `LevelBoundCollisionCallback` and the routing of far-half block interaction through Immersive Portals' remote targets are this project's own. Code ported from IPSable later gets the header line in the same commit.

### Native sources (`natives/`)

Details and our changes: `natives/README.md`.

| Path | From | Licence | Marking |
|---|---|---|---|
| `marten/` | Sable 2.0.6 (`ad8b4d3`), unchanged | PolyForm Shield | `// Copied from Sable 2.0.6 ...` in every file |
| `rapier/` | IPSable's `natives/rapier` (Sable 2.0.3's crate with IPSable's changes), rebased on 2.0.6 and renamed `ipa_rapier` | PolyForm Shield (Sable's code and ours), Apache-2.0 (IPSable's changes) | `// From IPSable 0.5.0 ...` in every file, naming our changes |
| `engine/rapier/` | Rapier `38e92f1` (RyanHCode's fork) with IPSable's engine changes, trimmed to the 3D crate | Apache-2.0 (our changes, in 7 files: PolyForm Shield) | the 14 files IPSable changed carry `// Modified by IPSable 0.5.0 ...` (IPSable shipped no notice; we added it); the 7 of them we changed say so in that header; the crate's `Cargo.toml` says it was trimmed |
| `container/` | Sable's Docker build images | PolyForm Shield | none (build tooling, not shipped) |
| `LICENSE.md` | Sable's licence text | | the same text as the root `LICENSE` |

IPSable's `natives/rapier` is itself a copy of Sable's crate (labelled Apache-2.0 in its repository); what we ported from it keeps Sable's PolyForm Shield licence. IPSable's own changes to Rapier are Apache-2.0.

### Other files

- `src/main/resources`: upstream's mixin configs (`imm_ptl.mixins.json`, `imm_ptl_compat.mixins.json`), `neoforge.mods.toml` and `shaders/shader_transformation.json` (upstream's `shader_transformation.yaml`, converted to JSON) are modified; the five compat mixin configs come from the fork (2), extended by us; `imm_ptl_voxy_compat.mixins.json` is ours. Assets and lang files are upstream's, except the mod icon (below). JSON has no comments to carry notices: the changelog and `NOTICE` cover them. Our files and changes among them are PolyForm Shield.
- `scripts/`, `changelog/`, `NOTICE`, the `.md` files: ours (PolyForm Shield). `build.gradle` and `gradle.properties`: upstream's, modified. `LICENSE`: the PolyForm Shield text; `LICENSE-APACHE`: upstream's `LICENSE` (Apache-2.0), renamed.

## What Apache-2.0 section 4 requires, and how it is met

1. **Give recipients a copy of the licence.** `LICENSE-APACHE` is in the repository and in every built jar as `META-INF/LICENSE-APACHE`, with `META-INF/NOTICE`. `META-INF/LICENSE` is the PolyForm Shield text (one copy for Sable's code and ours).
2. **Modified files carry prominent notices that they were changed.** The Java headers above (classified against upstream tag `v6.0.7` and the classes of the fork's jar), and the native file headers.
3. **Retain copyright, patent, trademark and attribution notices.** Neither upstream nor the fork has per-file notices or a NOTICE file; their authors are named in `neoforge.mods.toml` (`authors`, `credits`) and in `NOTICE`.
4. **NOTICE file.** The originals ship none; this project adds one, and it travels with every redistribution of the jar.

Section 6 (trademarks): the names "Immersive Portals" and "Immersive Aeronautics" are used only to describe origin. The mod ids `immersive_portals_core`, `immersive_portals`, `imm_ptl` and `q_misc_util` are kept so other mods' compatibility code keeps working; they report the upstream version this project is built on (`6.0.7+ipa.<ours>`), and its own mod id `ip_aeronautics` reports its own version. This is an independent fork: report its bugs here, not upstream.

Redistributing the jar alone is fine under both licences, as long as `META-INF/LICENSE`, `META-INF/LICENSE-APACHE` and `META-INF/NOTICE` stay in it.

## Things that are not code

- Assets (textures, lang files) are upstream's, Apache-2.0, except the mod icon (`icon.png`, `assets/immersive_portals/icon.png`): this project's logo around Immersive Portals' portal, with the airship from Create Aeronautics' logo. Create Aeronautics' All Rights Reserved terms cover only the three `assets/` directories named in its `LICENSE.md`; everything else there is MIT, its logo included, so its MIT notice is in `NOTICE`.
- Create's assets are All Rights Reserved (only its code is MIT): none of them ships here, nor anything derived from them. The gold portal-track texture is made on the player's machine from their installed Create's texture when the block atlas is stitched (`IPCreateGoldPortalTrackSource`, named by `assets/minecraft/atlases/blocks.json`).
- No third-party Java library is bundled: the `-all` jar holds no nested jars. The `ipa_rapier` native libraries statically link Rapier and its Rust dependencies; the jar ships `natives/ipa_rapier/THIRD-PARTY-LICENSES.txt` with every crates.io crate linked into them (normal dependencies for the six targets, proc-macros excluded), their licences and licence texts, written by `scripts/native-licenses.py` (rerun it after changing `natives/Cargo.lock`; `--check` verifies it). All are MIT, Apache-2.0, Zlib or Unlicense (or a choice among them), except `colored` (MPL-2.0, file-level copyleft: unmodified, its source named by crates.io URL in that file).

## Contributions

Code from other people goes in only under terms that let it ship under this project's licence: `CONTRIBUTING.md` says how.
