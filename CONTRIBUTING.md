# Contributing

Thanks for wanting to help. Bug reports, compatibility reports and pull requests are all welcome.

## Reporting a bug or asking for a feature

Use the issue templates: [bug report](../../issues/new?template=bug_report.yaml) or [feature request](../../issues/new?template=feature_request.yml). A bug report helps most with the mod's version, the versions of Sable, Create, Create Aeronautics and the other mods involved, steps that make it happen again, and `logs/latest.log`. For anything drawn wrong, a screenshot or video says more than a description. This is an independent fork: report its bugs here, not to Immersive Portals, Immersive Aeronautics or Sable.

## Building

`README.md` ("Development") has the requirements and the first build. In short: JDK 21, `scripts/extract-nested-deps.sh` once, `-PwithoutVoxy` (the Voxy compat compiles against a patched Voxy jar that isn't published), and either `scripts/build-natives.sh` (Docker) for the full jar, or `-PallowMissingNatives` for a jar without straddling physics, which is enough for work that doesn't touch it. A change to the Voxy compat itself is the maintainer's to build and test.

## Testing a change

Test in game, with the built jar in a normal game install (not `runClient`): ships need Sable and Create Aeronautics, and straddling needs a jar with the natives. Say in the pull request what you tried, with which mods, in single player or on a server.

The maintainer also runs a headless regression suite (a dedicated server fed scripted commands, our physics compared with Sable's to the last digit). It needs a test world that is not published, so it is not in this repository; the maintainer runs it on changes to physics and server logic before merging.

Static checks you can run:
- `python3 scripts/stamp-headers.py --check`: every Java file's origin header (below).
- `scripts/mixincheck.py`: every mixin target exists in the jars we build against, after a dependency bump (its docstring says how).
- `python3 scripts/native-licenses.py --check`, after changing `natives/Cargo.lock`.

## Code conventions

- Every Java file starts with an origin and licence comment. After adding files, run `python3 scripts/stamp-headers.py`; for code ported from or modelled on IPSable, add the header line `LICENSING.md` describes, and a row in its table. Changes to upstream Immersive Portals files are marked `Immersive Aeronautics:` in the code.
- Mixins: `README.md` ("Mixin conventions"). A mixin on an optional mod goes in one of the compat configs, with string `targets`, `remap = false` and `require = 0`, and its package must be listed in `IPOptionalModMixinPlugin.MOD_BY_PACKAGE`, or players without that mod crash.
- Comments say what the code does and why, in terms of the game and the other mods involved. A comment citing `docs/...` points at the maintainer's private notes; a new comment should stand on its own.
- One change per pull request. Describe what players will notice; the maintainer writes the changelog entry.

## Commit messages

- A subject of at most 72 characters, no full stop, saying what changed in code or player terms.
- An optional short body: only what a reader of the diff needs that neither the diff nor the changelog says, such as a non-obvious reason or a trap for the next person.
- `Fixes #123` for an issue the change fixes.
- Signed off (`git commit -s`, below).

## Licence of contributions

This project's own code is under the PolyForm Shield License 1.0.0, and the code it took from Immersive Portals, Immersive Aeronautics, IPSable and Rapier stays under the Apache License 2.0 (`LICENSING.md`). For a contribution to ship with the rest, its licence has to allow that. By submitting a contribution (a pull request, a patch, or code posted in an issue) you agree that:

1. **You have the right to submit it.** You certify the [Developer Certificate of Origin 1.1](https://developercertificate.org/): you wrote it, or it is based on work whose licence lets you submit it under these terms, and you understand it and this agreement are public and kept. Sign off every commit to say so: `git commit -s` adds a `Signed-off-by: Your Name <email>` line. (Use an email you are happy to make public, such as GitHub's noreply address.)
2. **You license it to the maintainer.** You grant Alex Guha, the maintainer, a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable licence to use, reproduce, modify, prepare derivative works of, publicly display, sublicense and distribute your contribution and such derivative works, as part of this project, under the PolyForm Shield License 1.0.0 or under whatever licence the project uses at the time; and a patent licence on the same terms as section 3 of the Apache License 2.0.
3. **You keep your copyright.** This is a licence, not a transfer: you may still use your own contribution in any other way you like.
4. **Code you took from elsewhere keeps its licence.** If part of your contribution comes from another project, say so in the pull request, name the project and its licence, and keep its notices. Code under a licence that can't ship under these terms (GPL, for instance) can't go in.

If you can't agree to these terms, open an issue describing the change instead, and the maintainer may write it.
