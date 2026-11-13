#!/usr/bin/env bash
# Cross-compiles the ipa_rapier native library (natives/, copied from Sable 2.0.6's sable_rapier crate) for the six
# platforms Sable supports and copies the results to src/main/resources/natives/ipa_rapier/, where the jar picks them
# up. Same toolchain as Sable (sable_rapier/build.gradle): cargo-zigbuild for Linux and macOS, cargo-xwin for Windows,
# both in Docker, Rust pinned by natives/rust-toolchain.toml. Each library built gets a line in SOURCE-HASH there with the
# source hash it was built from (scripts/natives-hash.sh); the Gradle build refuses a library whose line doesn't match.
#
#   scripts/build-natives.sh            build the images if missing, then all six targets
#   scripts/build-natives.sh linux-x86_64 windows-x86_64 ...   only these targets
#   scripts/build-natives.sh --images   rebuild both images first
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NATIVES=$ROOT/natives
OUT=$ROOT/src/main/resources/natives/ipa_rapier
RUST_VERSION=$(sed -n 's/^channel = "\(.*\)"/\1/p' "$NATIVES/rust-toolchain.toml")
ZIG_IMAGE=ipa-build-zigbuild
XWIN_IMAGE=ipa-build-xwin
ALL_TARGETS=(linux-x86_64 linux-aarch64 macos-x86_64 macos-aarch64 windows-x86_64 windows-aarch64)

rebuild_images=0
targets=()
for arg in "$@"; do
    case $arg in
        --images) rebuild_images=1 ;;
        *) targets+=("$arg") ;;
    esac
done
[ ${#targets[@]} -eq 0 ] && targets=("${ALL_TARGETS[@]}")

build_image() { # name dir
    if [ $rebuild_images = 1 ] || ! docker image inspect "$1" >/dev/null 2>&1; then
        docker build -t "$1" "$NATIVES/container/$2" --build-arg "RUST_VERSION=$RUST_VERSION"
    fi
}
# only the images the targets need: CI builds linux-x86_64 alone
[[ " ${targets[*]}" =~ " "(linux|macos)- ]] && build_image $ZIG_IMAGE zigbuild
[[ " ${targets[*]}" == *" windows-"* ]] && build_image $XWIN_IMAGE xwin

mkdir -p "$NATIVES/.cargo-cache/git" "$NATIVES/.cargo-cache/registry" "$OUT"
SOURCE_HASH=$("$ROOT/scripts/natives-hash.sh")
stamp() { # library file name: replace its line in SOURCE-HASH
    local file=$OUT/SOURCE-HASH
    { [ -f "$file" ] && grep -v "  $1\$" "$file" || true; echo "$SOURCE_HASH  $1"; } | LC_ALL=C sort -k2 > "$file.new"
    mv "$file.new" "$file"
}

run() { # image args...
    local image=$1; shift
    docker run --rm \
        -v "$NATIVES:/io" \
        -v "$NATIVES/.cargo-cache/git:/usr/local/cargo/git" \
        -v "$NATIVES/.cargo-cache/registry:/usr/local/cargo/registry" \
        -w /io/rapier "$image" "$@"
}

for t in "${targets[@]}"; do
    os=${t%-*}; arch=${t#*-}
    case $os in
        linux)   triple=$arch-unknown-linux-gnu; suffix=.2.17; file=libipa_rapier.so;    ext=so ;;
        macos)   triple=$arch-apple-darwin;      suffix=;      file=libipa_rapier.dylib; ext=dylib ;;
        windows) triple=$arch-pc-windows-msvc;   suffix=;      file=ipa_rapier.dll;      ext=dll ;;
        *) echo "unknown target $t" >&2; exit 1 ;;
    esac
    echo "== $t ($triple$suffix)"
    if [ "$os" = windows ]; then
        run $XWIN_IMAGE cargo xwin build --release --target "$triple"
    else
        run $ZIG_IMAGE cargo zigbuild --release --target "$triple$suffix"
    fi
    cp "$NATIVES/target/$triple/release/$file" "$OUT/ipa_rapier_${arch}_${os}.$ext"
    stamp "ipa_rapier_${arch}_${os}.$ext"
done

# The containers run as root; hand the build tree back to the current user.
IMAGE=$ZIG_IMAGE
docker image inspect $ZIG_IMAGE > /dev/null 2>&1 || IMAGE=$XWIN_IMAGE
docker run --rm -v "$NATIVES:/io" $IMAGE chown -R "$(id -u):$(id -g)" /io/target /io/.cargo-cache

ls -la "$OUT"
