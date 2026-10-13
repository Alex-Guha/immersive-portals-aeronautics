"""Stamp an origin and licence header on every Java source file (Apache-2.0 section 4b notices; docs/licensing.md).

Classification (recorded here so it can be re-run after adding files):
  A  file exists at upstream tag v6.0.7 and differs from it   -> modified upstream file; the header also names
     Immersive Aeronautics 1.1.4 when the fork changed it too (reference/fork-vs-upstream.diff)
  B  file not in upstream, but its class name exists in reference/decompiled (the fork) -> reconstructed
  C  neither                                                    -> new in this project
Our code and our changes are PolyForm Shield 1.0.0; code taken from the Apache-2.0 works stays Apache-2.0.
Files that already start with a header mentioning "Apache License" or "PolyForm" (code copied from Sable) are
left alone. Code that follows or ports IPSable gets one more header line by hand, naming the IPSable class(es) and
"by r2smith141 (Apache License 2.0)" (see docs/provenance.md).
Run from the repository root: python3 scripts/stamp-headers.py
  --check   stamp nothing; report files whose header does not match their origin, and list the files that credit
            IPSable. Exit code 1 on a mismatch.
"""
import glob, os, subprocess, sys

HEADERS = {
    'A': """/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
""",
    'AF': """/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
""",
    'B': """/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
""",
    'C': """/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
""",
}

def header_kind(src):
    head = src[:900]
    if 'reconstructed and modified file' in head:
        return 'B'
    if 'Aeronautics - modified file' in head:
        return 'AF' if 'with changes from\n * Immersive Aeronautics 1.1.4' in head else 'A'
    if 'Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0)' in head:
        return 'C'
    if 'Copied from Sable' in head:
        return 'sable'
    return None


def check(up, mod, fork_mod, fork_names):
    bad, ipsable, counts = [], [], {}
    for path in sorted(glob.glob('src/main/java/**/*.java', recursive=True)):
        src = open(path, encoding='utf-8').read()
        kind = header_kind(src)
        if path in up:
            expected = ('AF' if path in fork_mod else 'A') if path in mod else None
        elif os.path.basename(path) in fork_names:
            expected = 'B'
        else:
            expected = ('C', 'sable')
        ok = kind in expected if isinstance(expected, tuple) else kind == expected
        if not ok:
            bad.append(f'{path}: header {kind}, expected {expected}')
        counts[kind or 'unmodified upstream'] = counts.get(kind or 'unmodified upstream', 0) + 1
        if 'r2smith141' in src[:900]:
            ipsable.append(path)
    print(counts)
    print(f'{len(ipsable)} file(s) credit IPSable:')
    for path in ipsable:
        print('  ' + path)
    for line in bad:
        print('MISMATCH ' + line)
    return 1 if bad else 0


def main():
    up = set(subprocess.run(['git', 'ls-tree', '-r', '--name-only', 'v6.0.7', 'src/main/java'],
                            capture_output=True, text=True).stdout.split())
    mod = set(subprocess.run(['git', 'diff', '--name-only', 'v6.0.7', '--', 'src/main/java'],
                             capture_output=True, text=True).stdout.split())
    fork_names = {os.path.basename(p) for p in glob.glob('reference/decompiled/**/*.java', recursive=True)}
    # upstream files the fork changed: "diff -ru decompiled-upstream/<path> decompiled/<path>" lines
    fork_mod = {'src/main/java/' + line.split()[-1][len('decompiled/'):]
                for line in open('reference/fork-vs-upstream.diff', encoding='utf-8')
                if line.startswith('diff ') and line.rstrip().endswith('.java')}
    if '--check' in sys.argv[1:]:
        sys.exit(check(up, mod, fork_mod, fork_names))
    counts = {'A': 0, 'AF': 0, 'B': 0, 'C': 0, 'skipped': 0}
    for path in sorted(glob.glob('src/main/java/**/*.java', recursive=True)):
        src = open(path, encoding='utf-8').read()
        if 'Apache License' in src[:600] or 'PolyForm' in src[:600]:
            counts['skipped'] += 1
            continue
        if path in up:
            if path not in mod:
                counts['skipped'] += 1
                continue  # unmodified upstream file: nothing to declare
            kind = 'AF' if path in fork_mod else 'A'
        elif os.path.basename(path) in fork_names:
            kind = 'B'
        else:
            kind = 'C'
        open(path, 'w', encoding='utf-8').write(HEADERS[kind] + src)
        counts[kind] += 1
    print(counts)

if __name__ == '__main__':
    main()
