#!/usr/bin/env bash
# Negative control for the HTTP parity driver (T-15 / per-response comparison):
# runs ledger-app on a private PostgreSQL 15 container, puts tools/mutant_proxy.py in front of it
# so that ONLY the N-th immediate apply response is altered (request and persisted RESOUT stay
# correct), and drives parity_java.py in both HTTP modes through the proxy. Both runs must FAIL
# at the mutated record with zero persisted mismatches.
#
#   tools/negative_response_control.sh <jar> <source-commit> <out-dir> [nth=8]
set -uo pipefail
JAR=$(readlink -f "$1"); SHA=$2; OUT=$3; NTH=${4:-8}
JAVA_BIN=${JAVA:-java}
cd "$(dirname "$0")/.."
rm -rf "$OUT"; mkdir -p "$OUT"
PG=ledger-negctl-$$
docker run -d --rm --name "$PG" -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger \
  -e POSTGRES_PASSWORD=ledger -p 127.0.0.1::5432 postgres:15-alpine >/dev/null
PGP=$(docker port "$PG" 5432/tcp | head -1 | sed 's/.*://')
APP=""; PROXY=""
cleanup() { [ -n "$PROXY" ] && kill "$PROXY" 2>/dev/null; [ -n "$APP" ] && kill "$APP" 2>/dev/null
            docker rm -f "$PG" >/dev/null 2>&1; }
trap cleanup EXIT
for _ in $(seq 1 60); do docker exec "$PG" pg_isready -U ledger -d ledger >/dev/null 2>&1 && break; sleep 1; done
LEDGER_SOURCE_COMMIT=$SHA LEDGER_DB_URL="jdbc:postgresql://127.0.0.1:$PGP/ledger" \
LEDGER_DB_USER=ledger LEDGER_DB_PASSWORD=ledger \
  "$JAVA_BIN" -jar "$JAR" --server.port=18081 >"$OUT/server.log" 2>&1 &
APP=$!
for _ in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18081/v1/namespaces/probe/current)" = 404 ] && break
  sleep 1
done
STATUS=0
for mode in http-json http-gen; do
  python3 tools/mutant_proxy.py --listen 18082 --upstream http://127.0.0.1:18081 --nth "$NTH" \
    --log "$OUT/proxy-$mode.log" &
  PROXY=$!
  sleep 1
  python3 tools/parity_java.py --mode "$mode" --authority a1 --jar "$JAR" --source-commit "$SHA" \
    --base-url http://127.0.0.1:18082 --namespace-prefix "negctl-$mode-" \
    --work "$OUT/work-$mode" --report "$OUT/report-$mode.json" >"$OUT/driver-$mode.log" 2>&1
  echo "$mode driver exit=$?"
  kill "$PROXY"; wait "$PROXY" 2>/dev/null; PROXY=""
  python3 - "$OUT/report-$mode.json" "$mode" "$NTH" <<'EOF' || STATUS=1
import json, sys
r = json.load(open(sys.argv[1])); mode = sys.argv[2]; nth = int(sys.argv[3])
print(f"{mode}: passed={r['passed']} stopped_at={r.get('stopped_at')} stages_passed={r['stages_passed']}")
failed = [s for s in r["stages"] if not s["passed"]]
ok = bool(failed) and not r["passed"]
if failed:
    s = failed[0]
    print(f"  stage {s['stage']}: first_result_mismatch_record={s['first_result_mismatch_record']} "
          f"request_errors={len(s['request_errors'])} persisted_mismatches={len(s['mismatches'])} "
          f"receipt_errors={s['receipt_errors']} errors={s['errors']}")
    e = s["request_errors"][0]
    print(f"  record {e['record']} typed={e['typed']}")
    for line in e["errors"]:
        print("   ", line[:300])
    ok = ok and e["record"] == nth - 1 and not s["mismatches"] and not s["receipt_errors"]
print("  CONTROL", "DETECTED" if ok else "NOT DETECTED")
sys.exit(0 if ok else 1)
EOF
done
echo "NEGATIVE_CONTROL_STATUS=$STATUS"
exit $STATUS
