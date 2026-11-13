#!/usr/bin/env bash
# Desktop RDP client against a throwaway xrdp server (Ubuntu + XFCE) in Docker.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=nautile-rdp
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
cleanup
trap cleanup EXIT
docker run -d --name "$NAME" -p 127.0.0.1:23389:3389 --shm-size=1g scottyhardy/docker-remote-desktop:latest >/dev/null
echo "Waiting for xrdp…"
for _ in $(seq 1 90); do
  docker logs "$NAME" 2>&1 | grep -qi "listening" && break
  sleep 2
done
sleep 5
cd desktop
IT_RDP_PORT=23389 IT_RDP_USER=ubuntu cargo test -p nautile-remote --test rdp_it "$@" -- --nocapture
