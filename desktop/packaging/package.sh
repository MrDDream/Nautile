#!/usr/bin/env bash
# Builds the desktop packages from desktop/target/release/nautile(.exe) into desktop/dist:
#   Linux:   nautile_<version>-1_amd64.deb, nautile-<version>-1.x86_64.rpm, nautile-<version>-linux-x86_64.tar.gz
#   Windows: Nautile-<version>-setup.exe, Nautile-<version>-portable.zip
#   macOS:   Nautile-<version>-macos-<arch>.dmg (not notarised yet)
# Needs cargo-deb and cargo-generate-rpm (Linux), makensis (Windows).
# Every package carries LICENSE and packaging/THIRD-PARTY-LICENSES.txt (scripts/gen-licenses.py desktop).
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml | head -1)
rm -rf dist && mkdir -p dist
case "$(uname -s)" in
  Linux)
    cargo deb -p nautile-app --no-build --no-strip -o dist/
    cargo generate-rpm -p crates/nautile-app -o dist/
    # A pre-release is written 0.5.0~rc1 (sorted before 0.5.0); GitHub would change the ~ of the file name, and
    # the signed sums would no longer name the files published: renamed 0.5.0.rc1 here, as the app looks for.
    for f in dist/*~*; do
      [ -e "$f" ] && mv "$f" "${f//\~/.}"
    done
    mkdir -p "dist/nautile-$VERSION"
    cp target/release/nautile packaging/nautile.desktop packaging/nautile.png ../LICENSE packaging/THIRD-PARTY-LICENSES.txt "dist/nautile-$VERSION/"
    tar -C dist -czf "dist/nautile-$VERSION-linux-x86_64.tar.gz" "nautile-$VERSION"
    rm -rf "dist/nautile-$VERSION"
    ;;
  MINGW*|MSYS*|CYGWIN*)
    EXE="$(cygpath -w "$PWD/target/release/nautile.exe")"
    MAKENSIS=$(command -v makensis || echo "/c/Program Files (x86)/NSIS/makensis.exe")
    (cd packaging/windows && "$MAKENSIS" -V2 "-DVERSION=$VERSION" "-DEXE=$EXE" "-DOUT=..\\..\\dist\\Nautile-$VERSION-setup.exe" nautile.nsi)
    mkdir -p "dist/Nautile-$VERSION-portable"
    cp target/release/nautile.exe packaging/portable.txt ../LICENSE packaging/THIRD-PARTY-LICENSES.txt "dist/Nautile-$VERSION-portable/"
    (cd dist && 7z a -bd -tzip "Nautile-$VERSION-portable.zip" "Nautile-$VERSION-portable" >/dev/null)
    rm -rf "dist/Nautile-$VERSION-portable"
    ;;
  Darwin)
    ARCH=$(uname -m)
    APP=dist/Nautile.app
    mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
    cp target/release/nautile "$APP/Contents/MacOS/nautile"
    cp ../LICENSE packaging/THIRD-PARTY-LICENSES.txt "$APP/Contents/Resources/"
    ICONSET=$(mktemp -d)/nautile.iconset
    mkdir -p "$ICONSET"
    for s in 16 32 128 256 512; do
      sips -z $s $s packaging/nautile-512.png --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
      d=$((s * 2)); [ $d -le 512 ] && sips -z $d $d packaging/nautile-512.png --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
    done
    iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/nautile.icns"
    sed "s/@VERSION@/$VERSION/g" packaging/macos/Info.plist > "$APP/Contents/Info.plist"
    hdiutil create -volname Nautile -srcfolder "$APP" -ov -format UDZO "dist/Nautile-$VERSION-macos-$ARCH.dmg" >/dev/null
    rm -rf "$APP"
    ;;
esac
ls -la dist
