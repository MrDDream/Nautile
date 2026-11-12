#!/usr/bin/env bash
# Renders the real screens (Robolectric + Roborazzi, no device needed) into docs/assets/shots/
# as <lang>-<theme>-<screen>.png, framed as a phone, for the README and the website.
set -euo pipefail
cd "$(dirname "$0")/.."
for lang in fr en; do
  ./gradlew --no-daemon -q :app:testDebugUnitTest --tests '*ScreenshotTest*' -PnautileScreenshots="$lang" --rerun-tasks
done
ls docs/assets/shots
