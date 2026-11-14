# Native libraries of core:rdp

`src/main/jniLibs/<abi>/*.so` are prebuilt by `scripts/build-freerdp.sh` (FreeRDP's Android superbuild,
run outside Gradle) and committed. This file records what they were built from and their SHA-256;
`scripts/check-native.sh` (run by CI) fails when a library changes without this file being updated.

## Sources

| Component | Version | Source | SHA-256 of the source |
|---|---|---|---|
| FreeRDP (`libfreerdp3`, `libfreerdp-client3`, `libwinpr3`, `libfreerdp-android`) | 3.33.1 | git `https://github.com/FreeRDP/FreeRDP.git`, tag `3.33.1`, commit `65bcca1913a28cbdd7eb913599f692bd2b707991` | (git commit) |
| OpenSSL (`libssl`, `libcrypto`) | 3.5.9 (LTS) | `https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz` | `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a` |
| cJSON (`libcjson`) | 1.7.19 | `https://github.com/DaveGamble/cJSON/archive/refs/tags/v1.7.19.tar.gz` | `7fa616e3046edfa7a28a32d5f9eacfd23f92900fe1f8ccd988c1662f30454562` |
| uriparser (`liburiparser`) | 1.0.3 | `https://github.com/uriparser/uriparser/releases/download/uriparser-1.0.3/uriparser-1.0.3.tar.xz` | `a327e438e238d6c9093f1845acc44dec20d4264639a3de1f3caf0927bd852e3b` |
| LLVM libc++ (`libc++_shared`) | NDK 29.0.14206865 | copied from the NDK sysroot | (Android SDK package `ndk;29.0.14206865`) |

The archives are downloaded and checked by the superbuild (`client/Android/cmake/External*.cmake`).

### Pins that differ from FreeRDP 3.33.1

FreeRDP 3.33.1's `cmake/DepVersions.cmake` pins OpenSSL 4.0.1 and uriparser 1.0.2, which have known
vulnerabilities (OpenSSL High-severity issues fixed in 4.0.3 / 3.5.9; uriparser CVE-2026-48841, fixed
in 1.0.3). The checkout is left untouched: `scripts/build-freerdp.sh` writes
`<build dir>/dep-overrides/DepVersions.cmake`, which includes the upstream file and then sets
`OPENSSL_VERSION`/`OPENSSL_HASH` and `URIPARSER_VERSION`/`URIPARSER_HASH` to the values in the table
above, and passes that directory as `CMAKE_MODULE_PATH` so the superbuild's `include(DepVersions)`
finds it first. The equivalent patch of the checkout would be:

```diff
-set(OPENSSL_VERSION "openssl-4.0.1")
-set(OPENSSL_HASH "SHA256=2db3f3a0d6ea4b59e1f094ace2c8cd536dffb87cdc39084c5afa1e6f7f37dd09")
+set(OPENSSL_VERSION "openssl-3.5.9")
+set(OPENSSL_HASH "SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a")
...
-set(URIPARSER_VERSION "1.0.2")
-set(URIPARSER_HASH "SHA256=7b2496f9622b4b201186a3d159d278605f1fb7262402a10cc1c0824889798b00")
+set(URIPARSER_VERSION "1.0.3")
+set(URIPARSER_HASH "SHA256=a327e438e238d6c9093f1845acc44dec20d4264639a3de1f3caf0927bd852e3b")
```

OpenSSL 3.5 is the LTS branch (supported until April 2030); FreeRDP 3.x supports OpenSSL 3.x and its
configure step reports `Using OpenSSL Version: 3.5.9`. The OpenSSL sum matches the `.sha256` files
published on GitHub and openssl.org.

## Recipe

1. Android SDK with `ndk;29.0.14206865` and `cmake;3.31.5` (it provides ninja); perl, make and pkg-config on the host.
2. `git clone --branch 3.33.1 https://github.com/FreeRDP/FreeRDP.git ~/android-tools/rdp-src/freerdp`
   and check that `git rev-parse HEAD` prints the commit above.
3. `scripts/build-freerdp.sh` (ABIs `arm64-v8a armeabi-v7a x86_64`, API 26, Release, OpenSSL and cJSON on,
   MediaCodec on, FFmpeg/OpenH264/Opus/PNG/WebP/JPEG off; the libraries are stripped with `llvm-strip --strip-unneeded`).
   Each run is a clean build. The script fails if `libcrypto.so` is not the pinned OpenSSL version or if a
   64-bit library has a `LOAD` segment not aligned on 16 KB (`0x4000`); armeabi-v7a stays 4 KB-aligned
   (16 KB pages only exist on 64-bit Android, and the NDK's own 32-bit `libc++_shared.so` is 4 KB-aligned).
4. Update the table below: `sha256sum core/rdp/src/main/jniLibs/*/*.so | LC_ALL=C sort -k2` from the repository root.

The build is not bit-for-bit reproducible yet (paths and timestamps can end up in the binaries): a rebuild
gives other sums, which is why the sums of the committed files are recorded here rather than recomputed in CI.

## SHA-256 of the committed libraries

```
c4c2fe5cbcb1fba0003a31fc7ab29a9bb12df6cc187ec45a806462540e83d93b  core/rdp/src/main/jniLibs/arm64-v8a/libc++_shared.so
c0d41c0668f115d831d5da26efed29625ddc6febbb345af5855a91d9a2278d26  core/rdp/src/main/jniLibs/arm64-v8a/libcjson.so
82251da1c550a2287a5c48f16616b7ee78f52cefe882e1595b6b77ccc9fcf04f  core/rdp/src/main/jniLibs/arm64-v8a/libcrypto.so
8d4a8616b381967d9dce7214de2018cd706143d652530fa1cdd4e89f42416f76  core/rdp/src/main/jniLibs/arm64-v8a/libfreerdp-android.so
356d287a360bb873b84083b8fa5e36458cc763b33575bda9b49fab6752b66925  core/rdp/src/main/jniLibs/arm64-v8a/libfreerdp-client3.so
ea991be34cc123c3f376b0d397ab3b1cf4ba53c364de7538e2520e0339979c67  core/rdp/src/main/jniLibs/arm64-v8a/libfreerdp3.so
54cb8bbe60ed5f7d6aafda3e6219f3c225bd14c81d1b655749b30ffe92f64f73  core/rdp/src/main/jniLibs/arm64-v8a/libssl.so
188a257ab1bdc252af920d07d57497682e8bb237375404f5a891ea2cced28d0f  core/rdp/src/main/jniLibs/arm64-v8a/liburiparser.so
8afb6c5c990e625c6bb3220658c57a84e72fe9bef70a2c58386b8b0f78058e8d  core/rdp/src/main/jniLibs/arm64-v8a/libwinpr3.so
af383654daf4cf0829615460419a180f84edd9d8bf51aa0f81ed0db811bf8491  core/rdp/src/main/jniLibs/armeabi-v7a/libc++_shared.so
b99e3a9aeeb0757694dd83c91703802d7f55513cf6fde38cffe8d3a88c0653c8  core/rdp/src/main/jniLibs/armeabi-v7a/libcjson.so
1f4755ec9226e85583c483cba8ce0a25995c83b72d82a73ace043656cb32e37f  core/rdp/src/main/jniLibs/armeabi-v7a/libcrypto.so
20d89c05221704073858148ee8b21ac9b10da72929282631a89499371280164b  core/rdp/src/main/jniLibs/armeabi-v7a/libfreerdp-android.so
7cfcdb1c8e83a00ee88f84d69d27e4d814e21f7e7aceb79e9100bc0e77cbec3c  core/rdp/src/main/jniLibs/armeabi-v7a/libfreerdp-client3.so
f20c7d8433af2502825e8661a46c41d66bec6a42d6e256f03a378b8af73c8897  core/rdp/src/main/jniLibs/armeabi-v7a/libfreerdp3.so
f7c52c7eaca31ddf6911137877244c4c059f73f3ba3df29c0295760b55fecc89  core/rdp/src/main/jniLibs/armeabi-v7a/libssl.so
6c1fe2bf095123e0d9c8862b06603747f38c46020eee3b2745729c63c6597492  core/rdp/src/main/jniLibs/armeabi-v7a/liburiparser.so
427278aab4f1404bbc7c5d26584b0a456155b70abfc2bc00774a0d5758d0a108  core/rdp/src/main/jniLibs/armeabi-v7a/libwinpr3.so
683a1c7cd2f6829fd40bf805739166c5fce35235bf60d2c4e4a95e9f65a342ef  core/rdp/src/main/jniLibs/x86_64/libc++_shared.so
b62e2ce5bba1b6581b2a73e2afae206018e77bdc67bce71d4e72c83ff9edb9e2  core/rdp/src/main/jniLibs/x86_64/libcjson.so
6c2bf3af17be50c30fd1956c928a2da95bce2d326a48773d8bbdd2834c721faa  core/rdp/src/main/jniLibs/x86_64/libcrypto.so
5cda94b89f2d299334adfb817d41cea9b11fe00e199322b9fdd9564d7127cb4a  core/rdp/src/main/jniLibs/x86_64/libfreerdp-android.so
90d9538dce12df9dbd4f42d1662175469b4c6a354cef1ef618cc03a1d1071121  core/rdp/src/main/jniLibs/x86_64/libfreerdp-client3.so
490b5ab2d030d2407be2c16f464a8314b3b17fc8575846705388136d40721b43  core/rdp/src/main/jniLibs/x86_64/libfreerdp3.so
565510b3d816c0f42e4dab352650f8fd1d8a4de3d5004f22031b9f8a7ccccf44  core/rdp/src/main/jniLibs/x86_64/libssl.so
c01d21ac73df4ea77b0f800a5f5cfbf8c6b06d121af507bd8bfa75ab4e223e6d  core/rdp/src/main/jniLibs/x86_64/liburiparser.so
780d8b0960af860864a2463dcf17e246a29f8fef69d542d3a2209951fa2c2e4a  core/rdp/src/main/jniLibs/x86_64/libwinpr3.so
```
