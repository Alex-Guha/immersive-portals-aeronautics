#!/usr/bin/env bash
# Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
# The fast static checks, each reported by name. Run after a build (./gradlew build, which also runs the unit tests and
# writes build/ipa/compile-classpath.txt), from anywhere in the checkout; CI runs it on every push.
#
#   scripts/check.sh [--only name,name] [--base <ref>]
#
#   headers          every Java file's origin header matches its origin (scripts/stamp-headers.py --check)
#   native-licences  the natives' licence list matches natives/Cargo.lock (scripts/native-licenses.py --check; needs
#                    cargo), only when Cargo.lock differs from the base (default: the merge base with main)
#   mixins           every class, method and field our mixins name exists in the jars we build against, and every
#                    mixin into an optional mod is skipped without it (scripts/mixincheck.py; known exceptions in
#                    scripts/mixincheck-expected.txt)
#   hooks            other mods' hooks into us, against our jar: the pinned releases of scripts/hooks-into-us.txt
#                    (downloaded and decompiled once into ${XDG_CACHE_HOME:-~/.cache}/ipa-check)
#   version          mod_version fits the branch: x.y.z on main with its changelog entry, x.y.z or
#                    x.y.z.N-<branch> on a branch, the tag's version on a v<x.y.z> tag
#   tests            the last build's unit test results exist and passed
#
# Exit status 1 if any check failed. JAVA_HOME selects the JDK (javap, java); Python 3 and git are needed.
set -uo pipefail
ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
cd "$ROOT"
ONLY=
BASE=
while [ $# -gt 0 ]; do
    case $1 in
        --only) ONLY=,$2,; shift 2 ;;
        --base) BASE=$2; shift 2 ;;
        *) echo "usage: scripts/check.sh [--only name,name] [--base <ref>]" >&2; exit 2 ;;
    esac
done
BIN=${JAVA_HOME:+$JAVA_HOME/bin/}
export JAVAP=${BIN}javap
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/ipa-check
VINEFLOWER_VERSION=1.11.2
VINEFLOWER_SHA256=e1e2415e7f78b34960402c4beddfc88e033d7842a23ecd132a8ec2eadd54f6bf
VERSION=$(sed -n 's/^mod_version=//p' gradle.properties)
JAR=build/libs/ip_aeronautics-$VERSION.jar
CLASSPATH_FILE=build/ipa/compile-classpath.txt

failed=()
OUT=$(mktemp)
trap 'rm -f "$OUT"' EXIT
run() { # name, command...: runs it unless --only leaves it out; PASS or FAIL with its output indented
    local name=$1; shift
    [ -z "$ONLY" ] || [[ $ONLY == *",$name,"* ]] || return 0
    if "$@" > "$OUT" 2>&1; then
        echo "PASS $name"
    else
        echo "FAIL $name"
        failed+=("$name")
    fi
    sed 's/^/    /' "$OUT"
}
need_build() {
    [ -f "$JAR" ] && [ -f "$CLASSPATH_FILE" ] || { echo "no $JAR or $CLASSPATH_FILE: build first (./gradlew build)"; return 1; }
}

check_headers() { # the counts on success; everything on a mismatch
    python3 scripts/stamp-headers.py --check > "$OUT.headers" 2>&1
    local status=$?
    if [ $status -eq 0 ]; then head -n 1 "$OUT.headers"; else cat "$OUT.headers"; fi
    rm -f "$OUT.headers"
    return $status
}

check_native_licences() {
    local base=${BASE:-$(git merge-base HEAD main 2>/dev/null || git merge-base HEAD origin/main 2>/dev/null || true)}
    if [ -n "$base" ] && git diff --quiet "$base" -- natives/Cargo.lock; then
        echo "natives/Cargo.lock unchanged since $(git rev-parse --short "$base"): not checked"
        return 0
    fi
    python3 scripts/native-licenses.py --check
}

# the mixin configs build.gradle registers; the Voxy one only when this jar has it (not with -PwithoutVoxy)
mixin_configs() {
    local cfg
    for cfg in $(sed -n 's/.*configs\.add("\([^"]*\)").*/\1/p' build.gradle); do
        python3 -c 'import sys, zipfile; sys.exit(sys.argv[2] not in zipfile.ZipFile(sys.argv[1]).namelist())' "$JAR" "$cfg" && echo "$cfg"
    done
}

check_mixins() {
    need_build || return 1
    python3 scripts/mixincheck.py --plugin src/main/java/qouteall/imm_ptl/core/compat/IPOptionalModMixinPlugin.java \
        --expected scripts/mixincheck-expected.txt "$ROOT" "$(cat "$CLASSPATH_FILE"):$ROOT/$JAR" $(mixin_configs)
}

fetch_vineflower() {
    local jar=$CACHE/vineflower-$VINEFLOWER_VERSION.jar
    if [ ! -f "$jar" ]; then
        mkdir -p "$CACHE"
        curl -fsSL -o "$jar.part" "https://repo1.maven.org/maven2/org/vineflower/vineflower/$VINEFLOWER_VERSION/vineflower-$VINEFLOWER_VERSION.jar" || return 1
        echo "$VINEFLOWER_SHA256  $jar.part" | sha256sum -c --quiet || { rm -f "$jar.part"; return 1; }
        mv "$jar.part" "$jar"
    fi
    echo "$jar"
}

fetch_mod() { # Modrinth version id, mixin config, mixin package folder -> a folder with the config (classes/) and the
              # decompiled mixins (src/), kept in the cache
    local dir=$CACHE/$1 vineflower meta url sha512
    if [ ! -d "$dir/src" ]; then
        vineflower=$(fetch_vineflower) || return 1
        rm -rf "$dir" && mkdir -p "$dir/classes"
        meta=$(curl -fsSL -A "mixin-hook-check" "https://api.modrinth.com/v2/version/$1") || return 1
        read -r url sha512 < <(python3 -c 'import json, sys; f = [f for f in json.load(sys.stdin)["files"] if f["primary"]] or [None]; print(f[0]["url"], f[0]["hashes"]["sha512"])' <<< "$meta")
        curl -fsSL -o "$dir/mod.jar" "$url" || return 1
        echo "$sha512  $dir/mod.jar" | sha512sum -c --quiet || { rm -rf "$dir"; return 1; }
        (cd "$dir/classes" && "${BIN}jar" xf ../mod.jar "$2" "$3") || { rm -rf "$dir"; return 1; }
        "${BIN}java" -jar "$vineflower" -log=WARN "$dir/classes/${3%%/*}" "$dir/src/${3%%/*}" > /dev/null || { rm -rf "$dir"; return 1; }
    fi
    echo "$dir"
}

check_hooks() {
    need_build || return 1
    local cp status=0 slug id version config folder dir
    cp="$(cat "$CLASSPATH_FILE"):$ROOT/$JAR"
    while read -r slug id version config folder; do
        dir=$(fetch_mod "$id" "$config" "$folder") || { echo "could not fetch $slug $version"; status=1; continue; }
        echo "$slug $version:"
        python3 scripts/mixincheck.py --only qouteall.,ipaeronautics. --src "$dir/src" "$ROOT" "$cp" "$dir/classes/$config" \
            | sed 's/^/  /'
        [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
    done < <(sed -n 's/^mod //p' scripts/hooks-into-us.txt)
    echo "members reached by reflection:"
    python3 scripts/mixincheck.py --members scripts/hooks-into-us.txt "$ROOT" "$cp" | sed 's/^/  /'
    [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
    return $status
}

check_version() {
    local ref=${GITHUB_REF:-} name kind
    if [ -n "${GITHUB_HEAD_REF:-}" ]; then
        kind=branch; name=$GITHUB_HEAD_REF                       # a pull request: its branch
    elif [[ $ref == refs/tags/* ]]; then
        kind=tag; name=${ref#refs/tags/}
    elif [[ $ref == refs/heads/* ]]; then
        kind=branch; name=${ref#refs/heads/}
    else
        kind=branch; name=$(git rev-parse --abbrev-ref HEAD)
    fi
    echo "mod_version=$VERSION on $kind $name"
    if [ $kind = tag ]; then
        [[ $name == v* ]] || { echo "not a version tag: nothing to check"; return 0; }
        [ "v$VERSION" = "$name" ] || { echo "the tag says ${name#v}"; return 1; }
    fi
    if [[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        if [ $kind = tag ]; then
            grep -qE "^## \[$VERSION\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$" changelog/*.md \
                || { echo "changelog/ has no [$VERSION] - <date> entry"; return 1; }
        elif [ "$name" = main ]; then
            grep -qE "^## \[($VERSION|Unreleased Changes)\]" changelog/*.md \
                || { echo "changelog/ has neither [$VERSION] nor [Unreleased Changes]"; return 1; }
        fi
        return 0
    fi
    if [[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+-(.+)$ ]]; then
        [ $kind = branch ] && [ "$name" != main ] || { echo "a test-jar version belongs on its branch, not on $kind $name"; return 1; }
        [ "${BASH_REMATCH[1]}" = "$name" ] || { echo "the version names branch ${BASH_REMATCH[1]}"; return 1; }
        return 0
    fi
    echo "not x.y.z or x.y.z.N-<branch>"
    return 1
}

check_tests() {
    local results=(build/test-results/test/TEST-*.xml)
    [ -f "${results[0]}" ] || { echo "no unit test results: build without -x test"; return 1; }
    python3 - "${results[@]}" <<'EOF'
import sys, xml.etree.ElementTree as ET
total = bad = 0
for path in sys.argv[1:]:
    suite = ET.parse(path).getroot()
    total += int(suite.get("tests", 0))
    for case in suite.iter("testcase"):
        if case.find("failure") is not None or case.find("error") is not None:
            bad += 1
            print("failed:", suite.get("name"), case.get("name"))
print(f"{total} tests, {bad} failed")
sys.exit(1 if bad else 0)
EOF
}

run headers check_headers
run native-licences check_native_licences
run mixins check_mixins
run hooks check_hooks
run version check_version
run tests check_tests

if [ ${#failed[@]} -gt 0 ]; then
    echo "failed: ${failed[*]}"
    exit 1
fi
echo "all checks passed"
