#!/usr/bin/env bash
# Checks the prebuilt core:rdp libraries against the SHA-256 recorded in core/rdp/NATIVE.md:
# every .so must be listed with its current sum, and every listed file must exist.
# Usage: scripts/check-native.sh   (from anywhere; exits 1 on any difference)
set -euo pipefail
cd "$(dirname "$0")/.."
MANIFEST=core/rdp/NATIVE.md
LIBS=core/rdp/src/main/jniLibs

expected=$(grep -E "^[0-9a-f]{64}  $LIBS/[^ ]+\.so$" "$MANIFEST" | LC_ALL=C sort -k2)
[ -n "$expected" ] || { echo "No library sums found in $MANIFEST" >&2; exit 1; }

status=0
# Listed files: present and unchanged.
if ! echo "$expected" | sha256sum --check --strict --quiet -; then
  status=1
fi
# Present files: all listed.
listed=$(echo "$expected" | awk '{print $2}')
while IFS= read -r so; do
  if ! grep -qxF "$so" <<<"$listed"; then
    echo "$so: not listed in $MANIFEST" >&2
    status=1
  fi
done < <(find "$LIBS" -name '*.so' | LC_ALL=C sort)

if [ $status -ne 0 ]; then
  echo "The native libraries differ from $MANIFEST: rebuild them with scripts/build-freerdp.sh and update the sums (and the sources table if they changed)." >&2
  exit 1
fi
echo "$(echo "$expected" | wc -l) native libraries match $MANIFEST."
