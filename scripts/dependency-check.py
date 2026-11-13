#!/usr/bin/env python3
# Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
"""Finds newer releases of the mods we build against or that hook into us, and tries each one.

    python3 scripts/dependency-check.py find              list the newer NeoForge 1.21.1 releases on Modrinth
    python3 scripts/dependency-check.py run <report.md>   for each, build our jar against it and run
                                                          scripts/check.sh --only mixins,hooks; write the report

The pins are the Modrinth coordinates in build.gradle (maven.modrinth:<project>:<version id or number>) and the "mod"
lines of scripts/hooks-into-us.txt (mods that hook into us). run changes build.gradle and hooks-into-us.txt for each
try and puts them back after it, so the tree is as it was; it needs what a build needs (JDK 21, deps/ extracted).
Nothing is bumped for real: the report says which updates break a hook, and a maintainer bumps the pins.
.github/workflows/dependencies.yml runs it weekly and keeps one issue with the report open while there is anything new.
"""
import json
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUILD = ROOT / "build.gradle"
HOOKS = ROOT / "scripts" / "hooks-into-us.txt"
LOADER, GAME = "neoforge", "1.21.1"
NOTES = {
    "sable": "Our physics library is matched to exact Sable versions (IPSableBackendVersion): with a new Sable, ships "
             "fall back to stock physics until its Rust changes are merged into natives/.",
    "create": "Create bundles Flywheel and Ponder: their hooks are checked with it.",
}


def api(path, **query):
    url = "https://api.modrinth.com/v2/" + path
    if query:
        url += "?" + urllib.parse.urlencode({k: json.dumps(v) for k, v in query.items()})
    request = urllib.request.Request(url, headers={"User-Agent": "dependency-check-script"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def pins():
    """(project, pinned version id or number, where) of every pin"""
    found = []
    for project, version in re.findall(r'"maven\.modrinth:([\w.-]+):([^"]+)"', BUILD.read_text()):
        found.append((project, version, "build.gradle"))
    for line in HOOKS.read_text().splitlines():
        if line.startswith("mod "):
            _, project, version_id, *_ = line.split()
            found.append((project, version_id, "hooks-into-us.txt"))
    return found


def find():
    updates = []
    for project, pinned, where in pins():
        versions = api(f"project/{project}/version", loaders=[LOADER], game_versions=[GAME])
        if not versions:
            continue
        mine = next((v for v in versions if pinned in (v["id"], v["version_number"])), None)
        newest = versions[0]
        if mine and (newest["id"] == mine["id"] or newest["date_published"] <= mine["date_published"]):
            continue
        updates.append({"project": project, "where": where, "pinned": pinned,
                        "pinned_number": mine["version_number"] if mine else "?",
                        "id": newest["id"], "number": newest["version_number"], "type": newest["version_type"],
                        "date": newest["date_published"][:10]})
    return updates


def run(command, log):
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    log.append(f"$ {' '.join(command)}\n{(result.stdout + result.stderr)[-6000:]}")
    return result.returncode == 0


def apply(update):
    if update["where"] == "build.gradle":
        text = BUILD.read_text()
        BUILD.write_text(text.replace(f'maven.modrinth:{update["project"]}:{update["pinned"]}"',
                                      f'maven.modrinth:{update["project"]}:{update["id"]}"'))
    else:
        text = HOOKS.read_text()
        HOOKS.write_text(re.sub(rf"^mod {re.escape(update['project'])} \S+ \S+ ",
                                f"mod {update['project']} {update['id']} {update['number']} ", text, flags=re.M))


def try_update(update):
    """(build ok, checks ok, log) with this one update applied"""
    saved = {path: path.read_text() for path in (BUILD, HOOKS)}
    deps = ROOT / "deps"
    backup = ROOT / "build" / "deps-before-update"
    shutil.rmtree(backup, ignore_errors=True)
    if deps.exists():
        shutil.copytree(deps, backup)
    log = []
    try:
        apply(update)
        gradle = ["./gradlew", "--console=plain", "-q"]
        built = (run(gradle + ["downloadModJars"], log) and run(["scripts/extract-nested-deps.sh"], log)
                 and run(gradle + ["build", "-x", "test", "-PallowMissingNatives", "-PwithoutVoxy"], log))
        checked = built and run(["scripts/check.sh", "--only", "mixins,hooks"], log)
        return built, checked, log
    finally:
        for path, text in saved.items():
            path.write_text(text)
        if backup.exists():
            shutil.rmtree(deps, ignore_errors=True)
            backup.rename(deps)


def report(updates, results):
    lines = ["The weekly dependency check found newer NeoForge 1.21.1 releases on Modrinth of mods we build against or "
             "that hook into us. Our jar was built against each one alone and its hooks were checked "
             "(`scripts/check.sh --only mixins,hooks`). Nothing was changed in the repository.", "",
             "| Mod | Pinned | Newest | Published | Builds | Hooks hold |", "|---|---|---|---|---|---|"]
    for update, (built, checked, _log) in zip(updates, results):
        lines.append(f"| {update['project']} ({update['where']}) | {update['pinned_number']} | {update['number']} "
                     f"({update['type']}, `{update['id']}`) | {update['date']} | {'yes' if built else '**no**'} | "
                     f"{'yes' if checked else '**no**' if built else '-'} |")
    for update, (built, checked, log) in zip(updates, results):
        note = NOTES.get(update["project"])
        lines += ["", f"<details><summary>{update['project']} {update['number']}: "
                  f"{'fine' if checked else 'breaks the build' if not built else 'breaks a hook'}</summary>", ""]
        if note:
            lines += [note, ""]
        lines += ["```", "\n\n".join(log)[-20000:], "```", "</details>"]
    return "\n".join(lines) + "\n"


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "find":
        for update in find():
            print(f"{update['project']}: {update['pinned_number']} -> {update['number']} ({update['type']}, "
                  f"{update['date']}, {update['id']}) in {update['where']}")
    elif command == "run" and len(sys.argv) == 3:
        if (ROOT / "deps").is_symlink():
            sys.exit("deps/ is a link (a worktree shares it): run this in a clone of its own")
        updates = find()
        results = []
        for update in updates:
            print(f"== {update['project']} {update['number']}", flush=True)
            results.append(try_update(update))
            print("built" if results[-1][0] else "build failed", "/", "checks pass" if results[-1][1] else "checks fail")
        out = pathlib.Path(sys.argv[2])
        if updates:
            out.write_text(report(updates, results))
        elif out.exists():
            out.unlink()
        print(f"{len(updates)} update(s)")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
