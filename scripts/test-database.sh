#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# This container is isolated from the developer's persistent Compose database.
container_id=$(docker run --rm -d \
  -e POSTGRES_USER=youcommuter -e POSTGRES_DB=youcommuter -e POSTGRES_PASSWORD=youcommuter_test_only \
  -p 127.0.0.1::5432 --tmpfs /var/lib/postgresql \
  postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677)
trap 'docker stop "$container_id" >/dev/null' EXIT

ready=false
for attempt in {1..60}; do
  # The image's temporary initialization server accepts Unix sockets only.
  # Wait for TCP so schema initialization cannot race its shutdown and final restart.
  if docker exec "$container_id" pg_isready -h 127.0.0.1 -U youcommuter -d youcommuter >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [ "$ready" != true ]; then
  echo "Disposable test database did not become ready" >&2
  exit 1
fi
address=$(docker port "$container_id" 5432/tcp)
YOUCOMMUTER_TEST_DATABASE_URL="postgres://youcommuter:youcommuter_test_only@${address}/youcommuter?sslmode=disable" \
  go test -race -count=1 ./backend/internal/postgres
