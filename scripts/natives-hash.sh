#!/usr/bin/env bash
# Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
# Prints the source hash of the ipa_rapier natives: a sha256 over the sha256sum lines of every tracked file of natives/
# (path and contents as in the working tree, in byte order of their paths). scripts/build-natives.sh stamps it in
# SOURCE-HASH for each library it builds; build.gradle (checkNativesSource) computes the same and refuses a library
# stamped with another; CI keys its natives cache on it.
#
#   scripts/natives-hash.sh
set -euo pipefail
cd "$(dirname "$0")/.."
git ls-files -z natives | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1
