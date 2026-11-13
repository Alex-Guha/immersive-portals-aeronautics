# Contributing

Thanks for wanting to help. Bug reports, compatibility reports and pull requests are all welcome.

## Reporting a bug or asking for a feature

Use the issue templates ([bug report](../../issues/new?template=bug_report.yaml), [feature request](../../issues/new?template=feature_request.yml)) or the [Discord](https://discord.gg/R6jjbqnThA). A bug report helps most when it includes:
- the version of this mod, and of Sable, Create, Create Aeronautics and any other mods involved;
- steps that make it happen again;
- `logs/latest.log` (*and* `crash-reports/crash-*.txt` if relevant, not "or");
- for anything drawn wrong, a screenshot or video.

This is an independent fork: report its bugs here, not to Immersive Portals, Immersive Aeronautics or Sable.

## Building

See "Building from source" in the [README](README.md). For work that doesn't touch ships crossing portals, a build with `-PallowMissingNatives` is enough. Other tips:
- `scripts/build-natives.sh linux-x86_64` builds the physics library for one platform only.
- `cargo check --release -p ipa_rapier` in `natives/` is a quick compile check of the Rust code (`natives/README.md`).
- Voxy support needs a patched Voxy jar that isn't published, so changes to it are for the maintainer to build and test.

## Finding your way around

| Path | What |
|---|---|
| `src/main/java/qouteall/` | Immersive Portals, with this project's changes |
| `src/main/java/qouteall/imm_ptl/core/compat/` | Compatibility with other mods. The Sable work is in `sable_integration`, `mixin_sable`, `sable_physics` and `sable_straddle`; Create's in `create_integration` and `mixin_create`; portals on ships in `ship_portals` |
| `src/main/java/ipaeronautics/rapier/` | Java bindings for the physics library's functions beyond Sable's |
| `natives/` | The physics library (Rust): Sable's, with IPSable's changes and ours, and a vendored copy of the Rapier engine |
| `scripts/` | Build helpers: physics libraries, nested dependencies, licence list, file headers, mixin target check |
| `changelog/` | Changes per release, one file per minor version |

Some code comments cite `docs/...`: those are the maintainer's notes, which are not public. Every comment should explain itself without them.

The git history is upstream Immersive Portals up to tag `v6.0.7`, then one commit per release up to 2.9.0.

## Testing a change

Test in game, with the built jar in a normal game install rather than `runClient` or `runServer`. Ships need Sable and Create Aeronautics, and ships crossing portals smoothly needs a jar with the physics libraries. In the pull request, say what you tried, with which mods, and whether in single player or on a server. The maintainer may run further tests before merging.

Checks you can run:
- `python3 scripts/stamp-headers.py --check`: every Java file has its origin header (below).
- `scripts/mixincheck.py`, after changing a dependency's version: every class, method and field the mixins name exists in the jars (its docstring says how to run it).
- `python3 scripts/native-licenses.py --check`, after changing `natives/Cargo.lock`. Without `--check`, it rewrites the licence list of the Rust crates shipped in the jar.

## Code conventions

- **File headers.** Every Java file starts with a comment saying where it comes from and its licence. After adding files, run `python3 scripts/stamp-headers.py`. For code ported from or modelled on IPSable, add the header line and table row that `LICENSING.md` describes. Changes to upstream Immersive Portals files are marked `Immersive Aeronautics:` in the code.
- **Mixins on other mods** go in one of the compat configs (`imm_ptl_*_compat.mixins.json`, `imm_ptl_render_tweaks.mixins.json`), with string `targets`, `remap = false` and `require = 0`. Add the package to `IPOptionalModMixinPlugin.MOD_BY_PACKAGE`, or players without that mod crash. Check that the injection applies with `-Dmixin.debug.export=true`.
- **Server-side mixins** go in a config's `mixins` section, never `client`.
- **A new mixin config** is registered in `build.gradle` (`mixin {}`) and `META-INF/neoforge.mods.toml`, is `required: false`, and sets `"overwrites": {"conformVisibility": true}`. Other mods can widen a method we `@Overwrite`, and without that setting Mixin then refuses to load the class.
- **Comments** say what the code does and why, in terms of the game and the other mods involved.

## Pull requests

- One change per pull request.
- Describe what players will notice. The maintainer writes the changelog entry.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/), such as `fix(trains): keep carriages coupled through a portal`. Following it isn't required: the maintainer can reword a message when merging.
- Write `Fixes #123` for an issue it fixes.
- Sign off every commit (`git commit -s`, below).

## Licence of contributions

This project's own code is under the PolyForm Shield License 1.0.0, and the code it took from Immersive Portals, Immersive Aeronautics, IPSable and Rapier stays under the Apache License 2.0 (`LICENSING.md`). For a contribution to ship with the rest, its licence has to allow that. By submitting a contribution (a pull request, a patch, or code posted in an issue) you agree that:

1. **You have the right to submit it.** You certify the [Developer Certificate of Origin 1.1](https://developercertificate.org/): you wrote it, or it is based on work whose licence lets you submit it under these terms, and you understand it and this agreement are public and kept. Sign off every commit to say so: `git commit -s` adds a `Signed-off-by: Your Name <email>` line. (Use an email you are happy to make public, such as GitHub's noreply address.)
2. **You license it to the maintainer.** You grant Alex Guha, the maintainer, a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable licence to use, reproduce, modify, prepare derivative works of, publicly display, sublicense and distribute your contribution and such derivative works, as part of this project, under the PolyForm Shield License 1.0.0 or under whatever licence the project uses at the time; and a patent licence on the same terms as section 3 of the Apache License 2.0.
3. **You keep your copyright.** This is a licence, not a transfer: you may still use your own contribution in any other way you like.
4. **Code you took from elsewhere keeps its licence.** If part of your contribution comes from another project, say so in the pull request, name the project and its licence, and keep its notices. Code under a licence that can't ship under these terms (GPL, for instance) can't go in.

If you can't agree to these terms, open an issue describing the change instead, and the maintainer may write it.
