#!/bin/bash
# Builds the WebM video decoder (libvpx, BSD licence) for the Mac: a static library for Apple-silicon Macs,
# packaged as macos/Vendor/Vpx.xcframework. Mac has no system VP8 decoder the app can use, so it decodes in software
# exactly as the iPhone app does.
# Source is cloned from Google's official repository at a pinned release tag into a scratch folder.
# Run once from anywhere:  bash macos/scripts/build_webm.sh
set -euo pipefail

VPX_TAG="v1.15.0"
MIN_MACOS="14.0"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WEBM_WORK:-$(mktemp -d /tmp/gdl-webm-mac.XXXXXX)}"
OUT="$HERE/Vendor"

echo "Working in $WORK"
git clone --depth 1 --branch "$VPX_TAG" https://chromium.googlesource.com/webm/libvpx "$WORK/libvpx"

SYSROOT="$(xcrun --sdk macosx --show-sdk-path)"
# libvpx's configure hard-codes its own minimum macOS version, so the compiler is called through a wrapper that drops
# those flags and passes the right -target and SDK instead.
wrapper() { # file compiler
  cat > "$1" <<EOF
#!/bin/bash
args=(); skip=0
for a in "\$@"; do
  if [ \$skip = 1 ]; then skip=0; continue; fi
  case "\$a" in
    -mmacosx-version-min=*|-fembed-bitcode) ;;
    -isysroot|-arch) skip=1 ;;
    *) args+=("\$a") ;;
  esac
done
exec $2 -target arm64-apple-macos$MIN_MACOS -isysroot $SYSROOT "\${args[@]}"
EOF
  chmod +x "$1"
}
wrapper "$WORK/cc" clang
wrapper "$WORK/cxx" clang++

# The newest macOS target this libvpx knows (darwin23 is macOS 14); the wrapper decides what the objects are really built for.
TARGET="arm64-darwin23-gcc"

mkdir -p "$WORK/build"
(
  cd "$WORK/build"
  CC="$WORK/cc" LD="$WORK/cc" AS="$WORK/cc" CXX="$WORK/cxx" \
  ../libvpx/configure --target="$TARGET" \
    --disable-examples --disable-tools --disable-docs --disable-unit-tests \
    --disable-vp8-encoder --disable-vp9-encoder --enable-vp8-decoder --enable-vp9-decoder \
    --disable-install-bins --disable-install-docs --enable-pic --disable-webm-io --disable-libyuv \
    --disable-runtime-cpu-detect
  make clean >/dev/null 2>&1 || true
  make -j"$(sysctl -n hw.ncpu)" libvpx.a CC="$WORK/cc" CXX="$WORK/cxx" AS="$WORK/cc" LD="$WORK/cc"
)

# Fail early if the objects were not built for the Mac.
obj="$(mktemp -d "$WORK/check.XXXXXX")"
(cd "$obj" && ar x "$WORK/build/libvpx.a" vp9_dx_iface.c.o)
platform="$(xcrun vtool -show-build "$obj/vp9_dx_iface.c.o" | awk '/platform/ {print $2}')"
echo "library platform: $platform"
[ "$platform" = "MACOS" ] || { echo "Wrong platform for the Mac build: $platform" >&2; exit 1; }

mkdir -p "$WORK/headers/vpx"
cp "$WORK"/libvpx/vpx/vpx_*.h "$WORK"/libvpx/vpx/vp8*.h "$WORK/headers/vpx/"
cp "$WORK"/build/vpx_config.h "$WORK/headers/vpx/" 2>/dev/null || true

mkdir -p "$OUT"
rm -rf "$OUT/Vpx.xcframework"
xcodebuild -create-xcframework -library "$WORK/build/libvpx.a" -headers "$WORK/headers" -output "$OUT/Vpx.xcframework"

echo "Done: $OUT/Vpx.xcframework"
echo "Scratch folder (safe to delete): $WORK"
