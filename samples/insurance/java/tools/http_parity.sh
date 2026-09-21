#!/usr/bin/env bash
# Run the stateful HTTP FAST parity (http-gen and http-json) for every authority against a
# ledger-app started from the given JAR on a private PostgreSQL (Docker) database.
#
# Usage (working directory does not matter; paths below are explicit):
#   tools/http_parity.sh <jar> <source-commit> <work-dir> <report-dir> [a2-runtime-dir]
#
# Optional environment:
#   A3_RUNTIME=<dir>   fresh guest run laid out as <dir>/<path>/<stage>/{polin,txnin,polout,resout}.bin
#                      (tk5_demo.py evidence directory); adds a3-<path> runs for the three paths
#   A3_TARGETED=<cases-dir>:<authority-dir>
#                      targeted fresh-guest cases (targeted_a3.py cases / capture); adds a
#                      targeted comparison in both HTTP modes
#
# The database and server are private to this run and torn down at exit. Each authority gets its
# own namespace prefix because a namespace has exactly one root generation.
set -euo pipefail

JAR=$(readlink -f "$1"); SHA=$2; WORK=$3; REPORTS=$4
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
A2=${5:-$HERE/../../evidence/28790e2/runtime}
JAVA_BIN=${JAVA:-java}
PORT=${LEDGER_HTTP_PORT:-18080}
PG_NAME=insurance-parity-pg-$$

mkdir -p "$WORK" "$REPORTS"
docker run -d --rm --name "$PG_NAME" -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger \
    -e POSTGRES_PASSWORD=ledger -p 127.0.0.1::5432 postgres:15-alpine >/dev/null
PG_PORT=$(docker port "$PG_NAME" 5432/tcp | head -1 | sed 's/.*://')
cleanup() {
  [[ -n "${APP_PID:-}" ]] && kill "$APP_PID" 2>/dev/null && wait "$APP_PID" 2>/dev/null || true
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for _ in $(seq 1 60); do
  docker exec "$PG_NAME" pg_isready -U ledger -d ledger >/dev/null 2>&1 && break
  sleep 1
done

LEDGER_DB_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/ledger" LEDGER_DB_USER=ledger \
LEDGER_DB_PASSWORD=ledger LEDGER_SOURCE_COMMIT="$SHA" \
  "$JAVA_BIN" -jar "$JAR" --server.port="$PORT" >"$WORK/server.log" 2>&1 &
APP_PID=$!
for _ in $(seq 1 90); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/v1/namespaces/probe/current" 2>/dev/null \
     || [[ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/v1/namespaces/probe/current")" == "404" ]]; then
    break
  fi
  sleep 1
done

STATUS=0
run() { # mode authority label [authority-dir]
  local mode=$1 auth=$2 label=$3 dir=${4:-}
  local extra=()
  [[ -n "$dir" ]] && extra=(--authority-dir "$dir" --label "$label")
  python3 "$HERE/parity_java.py" --mode "$mode" --authority "$auth" "${extra[@]}" \
      --jar "$JAR" --source-commit "$SHA" --base-url "http://127.0.0.1:$PORT" \
      --namespace-prefix "${mode}-${label}-" --work "$WORK/${mode}-${label}" \
      --report "$REPORTS/${label}-${mode}.json" || STATUS=1
}
for mode in http-gen http-json; do
  run "$mode" a1 a1
  for p in ifox-iewl as370-iewl as370-ld370; do
    run "$mode" a2 "a2-$p" "$A2/$p"
    if [[ -n "${A3_RUNTIME:-}" ]]; then run "$mode" a3 "a3-$p" "$A3_RUNTIME/$p"; fi
  done
  if [[ -n "${A3_TARGETED:-}" ]]; then
    python3 "$HERE/targeted_a3.py" compare --mode "$mode" \
        --cases "${A3_TARGETED%%:*}" --authority "${A3_TARGETED##*:}" \
        --jar "$JAR" --source-commit "$SHA" --base-url "http://127.0.0.1:$PORT" \
        --namespace-prefix "${mode}-a3t-" --work "$WORK/${mode}-a3-targeted" \
        --report "$REPORTS/a3-targeted-${mode}.json" || STATUS=1
  fi
done
exit $STATUS
