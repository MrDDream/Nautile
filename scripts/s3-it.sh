#!/usr/bin/env bash
# Starts a throwaway S3 server (RustFS, MinIO-compatible) in Docker and runs the S3 backup store test.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=nautile-s3
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
cleanup
trap cleanup EXIT
docker run -d --name "$NAME" -p 127.0.0.1:29000:9000 -e RUSTFS_ACCESS_KEY=nautile -e RUSTFS_SECRET_KEY=nautile-secret \
  rustfs/rustfs:latest >/dev/null
echo "Waiting for the S3 server…"
for _ in $(seq 1 60); do
  # Docker's port relay accepts at once: wait for an HTTP answer.
  curl -s -o /dev/null http://127.0.0.1:29000/ && break
  sleep 1
done
./gradlew --console=plain :app:testDebugUnitTest --tests '*S3StoreIT*' -Ps3Port=29000 "$@"
