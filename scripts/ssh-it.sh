#!/usr/bin/env bash
# Starts a throwaway sshd + SOCKS5 + HTTP proxies in Docker and runs the core:ssh integration tests.
# `--desktop`: runs the desktop app's ones instead (cargo, desktop/crates/nautile-net/tests/ssh_it.rs).
set -euo pipefail
cd "$(dirname "$0")/.."
NET=nautile-it
TMP=""
cleanup() {
  docker rm -f coq-sshd coq-socks coq-http >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  [ -n "$TMP" ] && rm -rf "$TMP"
  return 0
}
cleanup
TMP=$(mktemp -d)
trap cleanup EXIT
docker network create "$NET" >/dev/null

docker run -d --name coq-sshd --network "$NET" --network-alias sshd -p 127.0.0.1:22022:2222 \
  -p 127.0.0.1:60001-60005:60001-60005/udp \
  -e PASSWORD_ACCESS=true -e USER_NAME=nautile -e USER_PASSWORD=nautile \
  -e DOCKER_MODS=linuxserver/mods:openssh-server-ssh-tunnel \
  lscr.io/linuxserver/openssh-server:latest >/dev/null

docker run -d --name coq-socks --network "$NET" -p 127.0.0.1:21080:1080 \
  -e PROXY_USER=puser -e PROXY_PASSWORD=ppass serjs/go-socks5-proxy >/dev/null

printf 'Port 8888\nListen 0.0.0.0\nTimeout 600\nAllow 0.0.0.0/0\nMaxClients 20\n' > "$TMP/tinyproxy.conf"
docker run -d --name coq-http --network "$NET" -p 127.0.0.1:28888:8888 \
  -v "$TMP/tinyproxy.conf:/etc/tinyproxy/tinyproxy.conf:ro" vimagick/tinyproxy >/dev/null

echo "Waiting for sshd…"
for _ in $(seq 1 90); do
  docker logs coq-sshd 2>&1 | grep -q "done\." && break
  sleep 2
done
sleep 3
# Mosh server for the MoshChannel test (Alpine-based image).
docker exec coq-sshd sh -c 'apk add --no-cache mosh >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq mosh >/dev/null)' || true
export IT_PORT=22022 IT_INNER_PORT=2222 IT_INNER_HOST=sshd IT_SOCKS_PORT=21080 IT_HTTP_PORT=28888
if [ "${1:-}" = "--desktop" ]; then
  shift
  (cd desktop && cargo test -p nautile-net --test ssh_it "$@" -- --nocapture)
  exit
fi
./gradlew --console=plain :core:ssh:test -PsshIT --rerun-tasks "$@"
