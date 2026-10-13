#!/usr/bin/env bash
# Several mods on the compile classpath ship their classes / libraries as jar-in-jar
# (Sodium 0.8.x, Iris, Sable, Create, Create Aeronautics ...). Gradle only sees the outer
# wrapper jar, so extract the nested jars (recursively) into deps/ (gitignored), where
# build.gradle picks them up as compileOnly.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p deps
CACHE=~/.gradle/caches/modules-2/files-2.1/maven.modrinth

extract_nested() {  # $1 = jar to inspect
  local tmp; tmp=$(mktemp -d)
  if unzip -oq "$1" 'META-INF/jarjar/*.jar' -d "$tmp" 2>/dev/null; then
    for inner in "$tmp"/META-INF/jarjar/*.jar; do
      [ -f "$inner" ] || continue
      cp "$inner" "deps/$(basename "$inner")"
      echo "extracted $(basename "$inner")"
      extract_nested "$inner"
    done
  fi
  rm -rf "$tmp"
}

for outer in $(find "$CACHE/sodium" "$CACHE/iris" "$CACHE/sable" "$CACHE/create" "$CACHE/create-aeronautics" "$CACHE/physicsmod" "$CACHE/distanthorizons" "$CACHE/sable-ragdolls" -name '*.jar' 2>/dev/null); do
  extract_nested "$outer"
done

# Drop older duplicates of the same library (e.g. fabric-renderer-api bundled by both Sodium and Iris)
for prefix in $(ls deps | sed -E 's/-[0-9][^/]*\.jar$//' | sort | uniq -d); do
  for old in $(ls deps/"$prefix"-[0-9]*.jar | sort -V | head -n -1); do
    rm -f "$old"; echo "removed duplicate $(basename "$old")"
  done
done
