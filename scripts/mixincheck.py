#!/usr/bin/env python3
"""Checks that every target class, method and field named by our mixins exists in the jars we build against.

    python3 scripts/mixincheck.py <repo> <classpath> <config.json>...

<repo> is the root of this repository; <classpath> is a colon-separated list of jars (absolute paths), which must include
the named Minecraft jar (~/.gradle/caches/neoformruntime/intermediate_results/compiledWithNeoForge_*_output.jar after a
build) and the jars of the mods the configs hook into; each <config.json> is a mixin config under src/main/resources,
e.g. imm_ptl_sable_compat.mixins.json. JAVAP selects the javap to run (default: javap on the PATH).

Run it after bumping NeoForge or any mod we hook into (Sodium, Iris, Sable, Create, Create Aeronautics, Flywheel, ...):
a hook whose target was renamed or removed fails only at runtime otherwise. It prints how many member references it
checked, then one line per problem (config, mixin, kind, detail); nothing after the count means every target was found.
It reads the mixin sources with regular expressions, so an unusual annotation layout can go unchecked.
"""
import re, sys, os, json, subprocess, functools
ROOT = sys.argv[1]; CP = sys.argv[2]; CONFIGS = sys.argv[3:]
JAVAP = os.environ.get('JAVAP', 'javap')
SRC = os.path.join(ROOT, 'src/main/java')

@functools.lru_cache(maxsize=None)
def javap(cls):
    r = subprocess.run([JAVAP, '-p', '-cp', CP, cls], capture_output=True, text=True)
    if r.returncode != 0 or 'Error:' in r.stderr:
        return None
    return r.stdout

@functools.lru_cache(maxsize=None)
def canonical(cls):
    """Try a.b.C.D as a.b.C$D etc. for nested classes."""
    if javap(cls) is not None: return cls
    parts = cls.split('.')
    for i in range(len(parts) - 1, 0, -1):
        cand = '.'.join(parts[:i]) + '$' + '$'.join(parts[i:])
        if javap(cand) is not None: return cand
    return None

@functools.lru_cache(maxsize=None)
def members(cls):
    c = canonical(cls)
    if c is None: return None
    out = javap(c)
    names = set(['<init>', '<clinit>'])
    header = None
    for line in out.splitlines():
        line = line.strip()
        if re.match(r'(public |final |abstract |static )*(class|interface|enum|record)\s', line) and header is None:
            header = line; continue
        m = re.match(r'.*?\b([A-Za-z_$][\w$]*)\s*\(', line)
        if m: names.add(m.group(1))
        m = re.match(r'.*?\b([A-Za-z_$][\w$]*);$', line)
        if m: names.add(m.group(1))
    if header:
        body = header.split('{')[0]
        # strip generics (nested)
        while '<' in body:
            body2 = re.sub(r'<[^<>]*>', '', body)
            if body2 == body: break
            body = body2
        supers = []
        m = re.search(r'extends\s+([^{]+?)(?:\s+implements|$)', body)
        if m: supers += [x.strip().split('<')[0] for x in m.group(1).split(',')]
        m = re.search(r'implements\s+([^{]+)$', body)
        if m: supers += [x.strip().split('<')[0] for x in m.group(1).split(',')]
        for sup in supers:
            if sup and sup != 'java.lang.Object' and canonical(sup) != c:
                sm = members(sup)
                if sm: names |= sm
    return names

def resolve_class(simple, imports, pkg):
    if '.' in simple:
        head, rest = simple.split('.', 1)
        if head[:1].isupper():
            for imp in imports:
                if imp.endswith('.' + head): return imp + '.' + rest
            return pkg + '.' + simple
        return simple
    for imp in imports:
        if imp.endswith('.' + simple): return imp
    return pkg + '.' + simple

problems = []; checked = 0
for cfg in CONFIGS:
    data = json.load(open(os.path.join(ROOT, 'src/main/resources', cfg), encoding='utf-8-sig'))
    pkg = data['package']
    names = data.get('mixins', []) + data.get('client', []) + data.get('server', [])
    for name in names:
        path = os.path.join(SRC, (pkg + '.' + name).replace('.', '/') + '.java')
        if not os.path.exists(path):
            problems.append((cfg, name, 'SOURCE MISSING', path)); continue
        src = open(path).read()
        src = re.sub(r'//.*', '', src); src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
        imports = re.findall(r'^import\s+([\w.$]+);', src, flags=re.M)
        fpkg = re.search(r'^package\s+([\w.]+);', src, flags=re.M).group(1)
        m = re.search(r'@Mixin\s*\((.*?)\)\s*\n', src, flags=re.S)
        if not m: problems.append((cfg, name, 'NO @Mixin', '')); continue
        ann = m.group(1)
        targets = []
        for t in re.findall(r'targets\s*=\s*\{?([^})]*)\}?', ann):
            targets += [x.replace('/', '.') for x in re.findall(r'"([^"]+)"', t)]
        for t in re.findall(r'([\w.$]+)\.class', ann):
            targets.append(resolve_class(t, imports, fpkg))
        if not targets: problems.append((cfg, name, 'NO TARGETS', ann[:80])); continue
        tmembers = {}
        for t in targets:
            ms = members(t)
            if ms is None: problems.append((cfg, name, 'TARGET CLASS NOT FOUND', t))
            tmembers[t] = ms
        # injection method strings
        for mstr in re.findall(r'method\s*=\s*(\{[^}]*\}|"[^"]*")', src):
            for s in re.findall(r'"([^"]+)"', mstr):
                owner = None; mm = re.match(r'L([^;]+);(.*)', s)
                if mm: owner = mm.group(1).replace('/', '.'); s = mm.group(2)
                mname = s.split('(')[0].split('*')[0]
                if not mname or mname in ('<init>', '<clinit>'): continue
                owners = [owner] if owner else targets
                for o in owners:
                    ms = members(o)
                    if ms is None: continue
                    checked += 1
                    if mname not in ms: problems.append((cfg, name, 'METHOD NOT FOUND', o + '#' + mname))
        for tstr in re.findall(r'target\s*=\s*"([^"]+)"', src):
            mm = re.match(r'L([^;]+);([\w$<>]+)', tstr)
            if not mm: continue
            o = mm.group(1).replace('/', '.'); mname = mm.group(2)
            ms = members(o)
            if ms is None: problems.append((cfg, name, 'AT OWNER NOT FOUND', o)); continue
            checked += 1
            if mname not in ms: problems.append((cfg, name, 'AT TARGET NOT FOUND', o + '#' + mname))
print('checked %d member references' % checked)
for p in problems: print('  '.join(p))
