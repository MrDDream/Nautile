#!/usr/bin/env bash
# Desktop backup places against throwaway S3 (RustFS) and WebDAV (Apache) servers in Docker.
set -euo pipefail
cd "$(dirname "$0")/.."
cleanup() { docker rm -f nautile-s3 nautile-dav >/dev/null 2>&1 || true; }
cleanup
trap cleanup EXIT
docker run -d --name nautile-s3 -p 127.0.0.1:29000:9000 -e RUSTFS_ACCESS_KEY=nautile -e RUSTFS_SECRET_KEY=nautile-secret \
  rustfs/rustfs:latest >/dev/null
docker run -d --name nautile-dav -p 127.0.0.1:28080:80 -e AUTH_TYPE=Basic -e USERNAME=nautile -e PASSWORD=nautile \
  bytemark/webdav >/dev/null
echo "Waiting for the servers…"
for _ in $(seq 1 60); do
  curl -s -o /dev/null http://127.0.0.1:29000/ && curl -s -o /dev/null http://127.0.0.1:28080/ && break
  sleep 1
done
sleep 2
cd desktop
S3_PORT=29000 WEBDAV_URL=http://127.0.0.1:28080/ cargo test -p nautile-net --test store_it "$@" -- --nocapture
