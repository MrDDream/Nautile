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
set -euo pipefail

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
export PATH="$CMAKE_BIN:$PATH"

for abi in "${ABIS[@]}"; do
  echo "=== $abi ==="
  cmake -S "$CPP" -B "$BUILD/$abi" -G Ninja \
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
  ls -la "$OUT/$abi"
done
