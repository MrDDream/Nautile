#!/usr/bin/env bash
# Starts a throwaway VNC server (Xvfb + x11vnc + xterm) in Docker and runs the core:vnc integration test.
# `--desktop`: runs the desktop app's one instead (desktop/crates/nautile-remote/tests/vnc_it.rs).
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=nautile-vnc
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
cleanup
trap cleanup EXIT
docker run -d --name "$NAME" -p 127.0.0.1:25900:5900 alpine:3.20 sh -c '
  apk add --no-cache xvfb x11vnc xterm font-dejavu >/dev/null &&
  (Xvfb :0 -screen 0 800x600x24 &) && sleep 2 &&
  (DISPLAY=:0 xterm -geometry 80x24+20+20 -bg white -fg black -e sh -c "echo Bonjour depuis VNC; exec sh" &) &&
  exec x11vnc -display :0 -passwd secret12 -forever -shared -rfbport 5900' >/dev/null
echo "Waiting for x11vnc…"
for _ in $(seq 1 60); do
  # Docker's port relay accepts before x11vnc listens: wait for x11vnc itself.
  docker logs "$NAME" 2>&1 | grep -q "PORT=5900" && break
  sleep 2
done
sleep 2
if [ "${1:-}" = "--desktop" ]; then
  shift
  (cd desktop && IT_VNC_PORT=25900 cargo test -p nautile-remote --test vnc_it "$@" -- --nocapture)
  exit
fi
IT_VNC_PORT=25900 ./gradlew --console=plain :core:vnc:test -PvncIT --rerun-tasks "$@"
# The app's remote desktop screen on that server (capture in app/build/check-vnc.png).
./gradlew --console=plain :app:testDebugUnitTest --tests '*ScreenshotTest.vncDesktop' -PnautileScreenshots=fr -PvncPort=25900 "$@"
