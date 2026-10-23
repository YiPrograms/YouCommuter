#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_dir=$(mktemp -d)
override_file="$task_dir/compose.test.yaml"
cat > "$override_file" <<'YAML'
networks:
  default:
    internal: true
services:
  fixture:
    build:
      context: .
      dockerfile: deploy/test-feed.Dockerfile
    healthcheck:
      test: ["CMD", "/fixture", "health"]
      interval: 1s
      timeout: 3s
      retries: 30
  collector:
    environment:
      YOUBIKE_BASE_URL: http://fixture:8082/
    depends_on:
      fixture:
        condition: service_healthy
YAML
export POSTGRES_PASSWORD=youcommuter_compose_test_only YOUCOMMUTER_HTTP_PORT=0
export FEED_POLL_INTERVAL=1m FEED_STALE_AFTER=5m SNAPSHOT_RETENTION_DAYS=90
compose=(docker compose --env-file /dev/null -p "youcommuter-smoke-$$" -f compose.yaml -f "$override_file")
cleanup(){
 "${compose[@]}" down --volumes --remove-orphans >/dev/null
 if [ -f "$task_dir/duplicate.log" ]; then unlink "$task_dir/duplicate.log"; fi
 unlink "$override_file"
 rmdir "$task_dir"
}
trap cleanup EXIT
sql(){ "${compose[@]}" exec -T postgres psql -U youcommuter -d youcommuter -At -v ON_ERROR_STOP=1 -c "$1"; }
wait_sql(){
 for attempt in {1..80}; do
  if [ "$(sql "$1")" = "$2" ]; then return; fi
  sleep 0.25
 done
 echo "Timed out waiting for fixture state: $1" >&2
 return 1
}
probe(){ "${compose[@]}" exec -T "$1" /healthcheck "http://127.0.0.1:$2/health/ready" >/dev/null; }
trigger(){ sql 'UPDATE feed_collection SET next_attempt_at=now()' >/dev/null; }
"${compose[@]}" up --build -d --wait postgres initdb backend fixture
"${compose[@]}" up -d collector
wait_sql 'SELECT count(*) FROM station_current' 2
probe collector 8081
[ "$(sql 'SELECT min(rent_electric) FROM station_current')" = 2 ]
[ "$(sql 'SELECT min(rent_regular) FROM station_current')" = 3 ]

# Restart and 304 preserve the accepted snapshot and validators.
checkpoint=$(sql 'SELECT etag FROM feed_collection')
observations=$(sql 'SELECT count(*) FROM station_snapshots')
checked=$(sql 'SELECT last_checked_at::text FROM feed_collection')
"${compose[@]}" restart collector
trigger
wait_sql "SELECT last_checked_at > '$checked'::timestamptz FROM feed_collection" t
[ "$(sql 'SELECT etag FROM feed_collection')" = "$checkpoint" ]
[ "$(sql 'SELECT count(*) FROM station_snapshots')" = "$observations" ]

# Frozen source data degrades coverage, while API/database readiness stays up.
"${compose[@]}" exec -T fixture /fixture stale
trigger
wait_sql "SELECT latest_feed_at < now()-interval '30 minutes' FROM provider_regions WHERE code='00'" t
if probe collector 8081; then echo 'Frozen feed reported healthy' >&2; exit 1; fi
probe backend 8080
"${compose[@]}" exec -T fixture /fixture fresh
trigger
wait_sql "SELECT latest_feed_at > now()-interval '5 minutes' FROM provider_regions WHERE code='00'" t
checkpoint=$(sql 'SELECT etag FROM feed_collection')
observations=$(sql 'SELECT count(*) FROM station_snapshots')

# Malformed bodies cannot commit their ETag or erase inventory.
"${compose[@]}" exec -T fixture /fixture invalid
trigger
wait_sql 'SELECT last_error_code FROM feed_collection' collection_failed
[ "$(sql 'SELECT etag FROM feed_collection')" = "$checkpoint" ]
[ "$(sql 'SELECT count(*) FROM station_snapshots')" = "$observations" ]

# A 429 delay survives a process restart and database restart.
"${compose[@]}" exec -T fixture /fixture limited
trigger
wait_sql 'SELECT last_error_code FROM feed_collection' rate_limited
[ "$(sql "SELECT next_attempt_at > now()+interval '100 seconds' FROM feed_collection")" = t ]
saved_schedule=$(sql 'SELECT next_attempt_at::text FROM feed_collection')
"${compose[@]}" restart collector
collector_id=$("${compose[@]}" ps -q collector)
restart_count=$(docker inspect --format '{{.RestartCount}}' "$collector_id")
"${compose[@]}" restart postgres
recovered=false
for attempt in {1..60}; do
 if [ "$(docker inspect --format '{{.RestartCount}}' "$collector_id")" -gt "$restart_count" ] &&
    "${compose[@]}" exec -T collector /healthcheck http://127.0.0.1:8081/health/live >/dev/null 2>&1 &&
    probe backend 8080 2>/dev/null; then recovered=true; break; fi
 sleep 0.5
done
[ "$recovered" = true ]
[ "$(sql 'SELECT next_attempt_at::text FROM feed_collection')" = "$saved_schedule" ]
if "${compose[@]}" run --rm --no-deps collector > "$task_dir/duplicate.log" 2>&1; then echo 'Duplicate collector acquired singleton lock' >&2; exit 1; fi
rg --quiet 'collector lock unavailable' "$task_dir/duplicate.log"
"${compose[@]}" stop -t 15 collector
[ "$(docker inspect --format '{{.State.ExitCode}}' "$collector_id")" = 0 ]
echo 'Compose nationwide ingestion, electric counts, atomic ETags, 304, stale data, malformed responses, 429, restart recovery and singleton lock passed.'
