#!/usr/bin/env bash
# Builds FreeRDP (and OpenSSL, cJSON, uriparser) for Android and copies the shared libraries into
# core/rdp/src/main/jniLibs/<abi>/. Runs the upstream superbuild in
# FreeRDP/client/Android/Studio/freeRDPCore/src/main/cpp outside Gradle, so the app build only
# packages prebuilt .so files.
#
# Usage: scripts/build-freerdp.sh [abi...]   (default: arm64-v8a armeabi-v7a x86_64)
# Env:   FREERDP_SRC   FreeRDP checkout (tag 3.33.1)          default ~/android-tools/rdp-src/freerdp
#        ANDROID_SDK   SDK with ndk;29.0.14206865 and cmake;3.31.5  default ~/android-tools/sdk
#        API           minimum Android API level               default 26 (app minSdk)
# Needs perl, make and pkg-config on the host.
#
# Dependency pins: FreeRDP 3.33.1's cmake/DepVersions.cmake pins OpenSSL 4.0.1 and uriparser 1.0.2,
# both with known vulnerabilities. Instead of editing the checkout, the script puts an override
# DepVersions.cmake first on CMAKE_MODULE_PATH: it includes the upstream file, then replaces the
# OpenSSL and uriparser pins below (cJSON and the others stay as upstream). The superbuild checks
# each archive against these SHA-256 before extracting it. Every run is a clean build (build dir,
# the superbuild's shared install dir and extracted sources are removed) so no header or library
# of a previous version can leak in.
set -euo pipefail

FREERDP_COMMIT=65bcca1913a28cbdd7eb913599f692bd2b707991   # tag 3.33.1
OPENSSL_VERSION=3.5.9   # LTS
OPENSSL_SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
URIPARSER_VERSION=1.0.3
URIPARSER_SHA256=a327e438e238d6c9093f1845acc44dec20d4264639a3de1f3caf0927bd852e3b

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FREERDP_SRC="${FREERDP_SRC:-$HOME/android-tools/rdp-src/freerdp}"
ANDROID_SDK="${ANDROID_SDK:-$HOME/android-tools/sdk}"
NDK="${NDK:-$ANDROID_SDK/ndk/29.0.14206865}"
CMAKE_BIN="${CMAKE_BIN:-$ANDROID_SDK/cmake/3.31.5/bin}"
API="${API:-26}"
ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a armeabi-v7a x86_64)

CPP="$FREERDP_SRC/client/Android/Studio/freeRDPCore/src/main/cpp"
DEPS="$FREERDP_SRC/client/Android/Studio/freeRDPCore/src/main/jniLibs"
OUT="$ROOT/core/rdp/src/main/jniLibs"
BUILD="${BUILD_DIR:-$HOME/android-tools/rdp-build}"

[ -f "$CPP/CMakeLists.txt" ] || { echo "FreeRDP introuvable : $CPP" >&2; exit 1; }
[ -d "$NDK" ] || { echo "NDK introuvable : $NDK" >&2; exit 1; }
command -v pkg-config >/dev/null || { echo "pkg-config manquant" >&2; exit 1; }
head=$(git -C "$FREERDP_SRC" rev-parse HEAD)
[ "$head" = "$FREERDP_COMMIT" ] || { echo "FreeRDP : commit $head, attendu $FREERDP_COMMIT (tag 3.33.1)" >&2; exit 1; }
export PATH="$CMAKE_BIN:$PATH"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"

OVERRIDE="$BUILD/dep-overrides"
mkdir -p "$OVERRIDE"
cat >"$OVERRIDE/DepVersions.cmake" <<CMAKE
include("\${FREERDP_SOURCE_DIR}/cmake/DepVersions.cmake")
set(OPENSSL_VERSION "openssl-$OPENSSL_VERSION")
set(OPENSSL_HASH "SHA256=$OPENSSL_SHA256")
set(URIPARSER_VERSION "$URIPARSER_VERSION")
set(URIPARSER_HASH "SHA256=$URIPARSER_SHA256")
CMAKE

for abi in "${ABIS[@]}"; do
  echo "=== $abi ==="
  rm -rf "${BUILD:?}/$abi" "${DEPS:?}/$abi" "${CPP:?}/external"
  cmake -S "$CPP" -B "$BUILD/$abi" -G Ninja \
    -DCMAKE_MODULE_PATH="$OVERRIDE" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DWITH_OPENSSL=ON -DWITH_CJSON=ON \
    -DWITH_MEDIACODEC=ON \
    -DWITH_FFMPEG=OFF -DWITH_OPENH264=OFF -DWITH_OPUS=OFF \
    -DWITH_PNG=OFF -DWITH_WEBP=OFF -DWITH_JPEG=OFF
  cmake --build "$BUILD/$abi" -j"$(nproc)"

  rm -rf "${OUT:?}/$abi" && mkdir -p "$OUT/$abi"
  cp "$BUILD/$abi/libfreerdp-android.so" "$OUT/$abi/"
  # Runtime dependencies installed by the superbuild (versioned symlinks are not packaged by AGP).
  # The OpenSSL legacy provider is not needed: FreeRDP is built with its internal RC4/MD4/MD5.
  find "$DEPS/$abi" -maxdepth 1 -name 'lib*.so' -type f -exec cp {} "$OUT/$abi/" \;
  find "$DEPS/$abi" -maxdepth 1 -name 'lib*.so' -type l | while read -r l; do cp -L "$l" "$OUT/$abi/"; done
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;; armeabi-v7a) triple=arm-linux-androideabi ;;
    x86_64) triple=x86_64-linux-android ;; x86) triple=i686-linux-android ;; riscv64) triple=riscv64-linux-android ;;
  esac
  cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$triple/libc++_shared.so" "$OUT/$abi/"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$OUT/$abi/"*.so

  grep -qa "OpenSSL $OPENSSL_VERSION " "$OUT/$abi/libcrypto.so" \
    || { echo "$abi : libcrypto.so n'est pas OpenSSL $OPENSSL_VERSION" >&2; exit 1; }
  # 16 KB pages only exist on 64-bit Android; armeabi-v7a keeps the NDK's 4 KB default
  # (its libc++_shared.so from the NDK is 4 KB-aligned too).
  case "$abi" in
    arm64-v8a|x86_64|riscv64)
      for so in "$OUT/$abi/"*.so; do
        if "$READELF" -lW "$so" | awk '$1 == "LOAD" && $NF != "0x4000" { bad = 1 } END { exit !bad }'; then
          echo "$so : segments LOAD non alignés sur 16 Ko" >&2; exit 1
        fi
      done ;;
  esac
  ls -la "$OUT/$abi"
done
