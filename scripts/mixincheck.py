#!/usr/bin/env python3
"""Checks that every target class, method and field named by a set of mixins exists in the jars we build against.

    python3 scripts/mixincheck.py [options] <repo> <classpath> <config.json>...

<repo> is the root of this repository; <classpath> is a colon-separated list of jars and class folders (absolute paths),
which must include the named Minecraft jar and the jars of the mods the configs hook into: scripts/check.sh passes
build/ipa/compile-classpath.txt, which every build writes, plus our own jar. Each <config.json> is a mixin config under
src/main/resources (e.g. imm_ptl_sable_compat.mixins.json), or a path to one (another mod's, with --src). JAVAP selects
the javap to run (default: javap on the PATH).

Run it after bumping NeoForge or any mod we hook into (Sodium, Iris, Sable, Create, Create Aeronautics, Flywheel, ...):
a hook whose target was renamed or removed fails only at runtime otherwise; scripts/check.sh runs it on every build.
It prints how many member references it checked, then one line per problem (config, mixin, kind, detail). It reads the
mixin sources with regular expressions, so an unusual annotation layout can go unchecked.

Options:
  --src DIR          the Java sources of the mixins (default <repo>/src/main/java), for another mod's mixins
  --only PREFIX,...  check only targets in these packages (another mod's hooks into ours: qouteall.,ipaeronautics.)
  --plugin FILE      our IPOptionalModMixinPlugin.java: a target in one of its MOD_BY_PACKAGE packages with no class on
                     the classpath is a mod we don't compile against, counted as not checked rather than a problem; and
                     every mixin into a mod's class (a jar matching --mod-jars, or on no jar at all) must be skipped when
                     that mod is absent, by MOD_BY_PACKAGE or MOD_BY_MIXIN_PACKAGE: NOT COVERED otherwise
  --mod-jars REGEX   which classpath entries are optional mods (default: Modrinth and CurseForge maven, and deps/)
  --members FILE     also check these members, one "a.b.Class#member" per line (other lines are ignored): what
                     another mod reaches by reflection
  --expected FILE    problems that are known and accepted, one per line as printed, # for comments: they are counted,
                     not listed; a line there that no longer occurs is reported as stale (for the configs checked).
Exit status 1 on any problem not in --expected.
"""
import argparse
import functools
import json
import os
import re
import subprocess
import sys
import zipfile

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("repo")
parser.add_argument("classpath")
parser.add_argument("configs", nargs="*")
parser.add_argument("--src")
parser.add_argument("--only")
parser.add_argument("--plugin")
parser.add_argument("--mod-jars", default=r"/maven\.modrinth/|/curse\.maven/|/deps/")
parser.add_argument("--expected")
parser.add_argument("--members")
args = parser.parse_args()

ROOT = args.repo
CP = args.classpath
JAVAP = os.environ.get("JAVAP", "javap")
SRC = args.src or os.path.join(ROOT, "src/main/java")
ONLY = tuple(p for p in (args.only or "").split(",") if p)
MOD_JARS = re.compile(args.mod_jars)


def index_classpath(entries):
    """binary class name (a.b.C$D) -> the classpath entry holding it; the first one wins, as on a classpath"""
    index = {}
    for entry in entries:
        if os.path.isdir(entry):
            for folder, _dirs, files in os.walk(entry):
                for name in files:
                    if name.endswith(".class"):
                        rel = os.path.relpath(os.path.join(folder, name), entry)
                        index.setdefault(rel[:-6].replace(os.sep, "."), entry)
        elif entry.endswith(".jar") and os.path.exists(entry):
            with zipfile.ZipFile(entry) as jar:
                for name in jar.namelist():
                    if name.endswith(".class") and not name.startswith("META-INF/"):
                        index.setdefault(name[:-6].replace("/", "."), entry)
    return index


INDEX = index_classpath([e for e in CP.split(":") if e])
PACKAGES = {name.rsplit(".", 1)[0] for name in INDEX}


@functools.lru_cache(maxsize=None)
def canonical(cls):
    """a.b.C.D as found on the classpath: a.b.C.D, a.b.C$D, ...; java.* from the JDK; None if absent"""
    if cls in INDEX:
        return cls
    parts = cls.split(".")
    for i in range(len(parts) - 1, 0, -1):
        candidate = ".".join(parts[:i]) + "$" + "$".join(parts[i:])
        if candidate in INDEX:
            return candidate
    if cls.startswith(("java.", "javax.")):
        return cls
    return None


JAVAP_OUT = {}
HEADER = re.compile(r"^(?:[\w]+ )*(?:class|interface|enum|record|@interface) ([\w.$]+)")


def javap_all(classes):
    """runs javap once per batch of classes not seen yet, keeping each class's lines (None: javap found nothing)"""
    todo = sorted({c for c in classes if c and c not in JAVAP_OUT})
    for start in range(0, len(todo), 300):
        batch = todo[start:start + 300]
        result = subprocess.run([JAVAP, "-p", "-cp", CP] + batch, capture_output=True, text=True)
        current = None
        for line in result.stdout.splitlines():
            match = HEADER.match(line)
            if match:
                current = match.group(1)
                JAVAP_OUT[current] = [line]
            elif current:
                JAVAP_OUT[current].append(line)
        for cls in batch:
            JAVAP_OUT.setdefault(cls, None)


def supers_of(cls):
    lines = JAVAP_OUT.get(cls)
    if not lines:
        return []
    body = lines[0].split("{")[0]
    while "<" in body:
        stripped = re.sub(r"<[^<>]*>", "", body)
        if stripped == body:
            break
        body = stripped
    found = []
    match = re.search(r"extends\s+(.+?)(?:\s+implements|$)", body)
    if match:
        found += [x.strip() for x in match.group(1).split(",")]
    match = re.search(r"implements\s+(.+)$", body)
    if match:
        found += [x.strip() for x in match.group(1).split(",")]
    return [s for s in found if s and s != "java.lang.Object"]


def load_with_supers(classes):
    pending = {c for c in classes if c}
    while pending:
        javap_all(pending)
        pending = {s for c in pending for s in supers_of(c) if s not in JAVAP_OUT}


@functools.lru_cache(maxsize=None)
def members(cls):
    """names of the methods and fields of a class and its supertypes; None if the class is not on the classpath"""
    c = canonical(cls)
    if c is None:
        return None
    load_with_supers([c])
    lines = JAVAP_OUT.get(c)
    if lines is None:
        return None
    names = {"<init>", "<clinit>"}
    for line in lines[1:]:
        line = line.strip()
        match = re.match(r".*?\b([A-Za-z_$][\w$]*)\s*\(", line)
        if match:
            names.add(match.group(1))
        match = re.match(r".*?\b([A-Za-z_$][\w$]*);$", line)
        if match:
            names.add(match.group(1))
    for sup in supers_of(c):
        if sup != c:
            names |= members(sup) or set()
    return names


def resolve_class(simple, imports, wildcards, pkg):
    """the class a name in a mixin source means: imports, wildcard imports, its own package, java.lang"""
    head, _, rest = simple.partition(".")
    if not head[:1].isupper():
        return simple                                    # fully qualified
    candidates = [imp + ("." + rest if rest else "") for imp in imports if imp.endswith("." + head)]
    candidates += [w + "." + simple for w in wildcards] + [pkg + "." + simple, "java.lang." + simple]
    for candidate in candidates:
        if canonical(candidate):
            return candidate
    return candidates[0]


def plugin_maps(path):
    text = open(path, encoding="utf-8").read()

    def entries(name):
        block = re.search(name + r"\s*=\s*Map\.(?:ofEntries|of)\((.*?)\);", text, flags=re.S).group(1)
        return dict(re.findall(r'"([^"]+)"\s*,\s*"([^"]+)"', block))
    return entries("MOD_BY_PACKAGE"), entries("MOD_BY_MIXIN_PACKAGE")


MOD_BY_PACKAGE, MOD_BY_MIXIN_PACKAGE = plugin_maps(args.plugin) if args.plugin else ({}, {})


def absent_mod(cls):
    """the MOD_BY_PACKAGE package of a class whose mod has no class on the classpath at all, else None"""
    for prefix in MOD_BY_PACKAGE:
        if cls.startswith(prefix) and not any((p + ".").startswith(prefix) for p in PACKAGES):
            return prefix
    return None


def wanted(cls):
    return not ONLY or cls.startswith(ONLY)


def parse(cfg_name, pkg, name, src):
    """(targets, [(owner or None, member)] of injectors, accessors and invokers, [(owner, member)] of @At targets)"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//.*", "", src)
    imports = re.findall(r"^import\s+([\w.$]+);", src, flags=re.M)
    wildcards = re.findall(r"^import\s+([\w.$]+)\.\*;", src, flags=re.M)
    fpkg = re.search(r"^package\s+([\w.]+);", src, flags=re.M).group(1)
    match = re.search(r"@Mixin\s*\((.*?)\)\s*\n", src, flags=re.S)
    if not match:
        problems.append((cfg_name, name, "NO @Mixin", ""))
        return None
    ann = match.group(1)
    targets = []
    for t in re.findall(r"targets\s*=\s*\{?([^})]*)\}?", ann):
        targets += [x.replace("/", ".") for x in re.findall(r'"([^"]+)"', t)]
    for t in re.findall(r"([\w.$]+)\.class", ann):
        targets.append(resolve_class(t, imports, wildcards, fpkg))
    if not targets:
        problems.append((cfg_name, name, "NO TARGETS", ann[:80]))
        return None
    methods = []
    for mstr in re.findall(r'method\s*=\s*(\{[^}]*\}|"[^"]*")', src):
        for s in re.findall(r'"([^"]+)"', mstr):
            owner = None
            mm = re.match(r"L([^;]+);(.*)", s)
            if mm:
                owner = mm.group(1).replace("/", ".")
                s = mm.group(2)
            mname = s.split("(")[0].split("*")[0]
            if mname and mname not in ("<init>", "<clinit>"):
                methods.append((owner, mname))
    ats = []
    for tstr in re.findall(r'target\s*=\s*"([^"]+)"', src):
        mm = re.match(r"L([^;]+);([\w$<>]+)", tstr)
        if mm:
            ats.append((mm.group(1).replace("/", "."), mm.group(2)))
    # @Accessor("field") and @Invoker("method"), or the name taken from the method's (getX/isX/setX, callX/invokeX)
    for kind, given, method in re.findall(
            r'@(Accessor|Invoker)\s*(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?[^;{(]*?\b(\w+)\s*\(', src):
        if not given:
            prefix = re.match(r"(get|is|set)(?=[A-Z])" if kind == "Accessor" else r"(call|invoke)(?=[A-Z])", method)
            given = method[prefix.end()].lower() + method[prefix.end() + 1:] if prefix else method
        methods.append((None, given))
    return targets, methods, ats


problems = []
unchecked = {}          # package of a mod not on the classpath -> mixins into it
checked = 0
mixin_targets = []      # (config, mixin, mixin's full class name, target) for the coverage check
parsed = []
for cfg in args.configs:
    cfg_path = cfg if os.path.sep in cfg else os.path.join(ROOT, "src/main/resources", cfg)
    cfg_name = os.path.basename(cfg)
    data = json.load(open(cfg_path, encoding="utf-8-sig"))
    pkg = data["package"]
    for name in data.get("mixins", []) + data.get("client", []) + data.get("server", []):
        path = os.path.join(SRC, (pkg + "." + name).replace(".", "/") + ".java")
        if not os.path.exists(path):
            problems.append((cfg_name, name, "SOURCE MISSING", (pkg + "." + name)))
            continue
        result = parse(cfg_name, pkg, name, open(path, encoding="utf-8").read())
        if result:
            mixin_targets += [(cfg_name, name, pkg + "." + name, t) for t in result[0]]
            parsed.append((cfg_name, name) + result)

# every class named anywhere, loaded in a few javap runs rather than one per mixin
load_with_supers(canonical(c) for _cfg, _name, targets, methods, ats in parsed
                 for c in targets + [o for o, _m in methods if o] + [o for o, _m in ats])

for cfg_name, name, targets, methods, ats in parsed:
    def target_missing(cls, kind):
        prefix = absent_mod(cls)
        if prefix:
            unchecked.setdefault(prefix, set()).add(name)
        else:
            problems.append((cfg_name, name, kind, cls))

    targets = [t for t in targets if wanted(t)]
    for t in targets:
        if members(t) is None:
            target_missing(t, "TARGET CLASS NOT FOUND")
    for owner, mname in methods:
        for o in ([owner] if owner and wanted(owner) else [] if owner else targets):
            ms = members(o)
            if ms is None:
                continue
            checked += 1
            if mname not in ms:
                problems.append((cfg_name, name, "METHOD NOT FOUND", o + "#" + mname))
    for o, mname in ats:
        if not wanted(o):
            continue
        ms = members(o)
        if ms is None:
            target_missing(o, "AT OWNER NOT FOUND")
            continue
        checked += 1
        if mname not in ms:
            problems.append((cfg_name, name, "AT TARGET NOT FOUND", o + "#" + mname))

if args.members:
    wanted_members = re.findall(r"^\s*([\w.$]+)#([\w$<>]+)\s*$", open(args.members, encoding="utf-8").read(), flags=re.M)
    load_with_supers(canonical(c) for c, _m in wanted_members)
    for cls, member in wanted_members:
        ms = members(cls)
        checked += 1
        if ms is None or member not in ms:
            problems.append((os.path.basename(args.members), "reflection", "MEMBER NOT FOUND", cls + "#" + member))

if args.plugin:
    # a mixin into an optional mod's class must be skipped without that mod: MOD_BY_PACKAGE (by target) or
    # MOD_BY_MIXIN_PACKAGE (by our mixin's package); a class on no jar at all counts as a mod's
    for cfg_name, name, mixin, target in mixin_targets:
        if target.startswith(("qouteall.", "ipaeronautics.", "java.", "javax.")):
            continue
        cls = canonical(target)
        where = INDEX.get(cls) if cls else None
        if where is not None and not MOD_JARS.search(where):
            continue
        if any(target.startswith(p) for p in MOD_BY_PACKAGE) or any(mixin.startswith(p) for p in MOD_BY_MIXIN_PACKAGE):
            continue
        problems.append((cfg_name, name, "NOT COVERED", target + " (add its package to IPOptionalModMixinPlugin.MOD_BY_PACKAGE)"))

print("checked %d member references" % checked)
for prefix, mixins in sorted(unchecked.items()):
    print("not checked, %s is not on the classpath: %d mixin(s)" % (prefix.rstrip("."), len(mixins)))

lines = sorted({"  ".join(p) for p in problems})
expected = set()
if args.expected:
    expected = {l.strip() for l in open(args.expected, encoding="utf-8") if l.strip() and not l.lstrip().startswith("#")}
configs = {os.path.basename(c) for c in args.configs}
known = [l for l in lines if l in expected]
if known:
    print("%d known problem(s), accepted in %s" % (len(known), os.path.basename(args.expected)))
for line in lines:
    if line not in expected:
        print(line)
for line in sorted(expected - set(lines)):
    if line.split("  ")[0] in configs:
        print("stale in %s, no longer a problem: %s" % (os.path.basename(args.expected), line))
sys.exit(1 if any(l not in expected for l in lines) else 0)
