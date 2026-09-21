# Java modernization of the synthetic insurance assembler sample

A Java 21 / Spring Boot re-implementation of the seven-module S/370 sample in
`samples/insurance` (INSBAT, INSCALC, INSVAL, INSPACK, INSDATE, INSRATE,
INSSMOK), built to reproduce the legacy **byte contract** exactly and to be
proven against the existing harness, goldens and archived MVS output rather
than against a shared calculation library.

This is a bounded synthetic demonstration. It makes no claim about any real
insurer's rules, data, z/OS, HLASM, production scale or power-loss behavior.

## Layout

| Module | Contents | Framework |
|---|---|---|
| `legacy-codec` | CP037, big-endian fullword, packed decimal (C/D/F signs, negative zero preserved), raw byte-backed 128/40/96-byte records (`PolicyRecord`, `TransactionRecord`, `ResultRecord`) | none |
| `contract-v001` | `ContractV001`: the V001 business contract — validation precedence, half-up interest, quote/commit operations, replay/order/conflict, exact PL7/PL3 width checks, 1900-2099 calendar, frozen rate table | none |
| `ledger-application` | `PolicyLedgerService`, `BatchRunner` (INSBAT semantics), `ExpectedManifest` (independent counts + input hashes), `Receipt`, `GenerationStore` + `FileGenerationStore` (pending/published/discarded generations), `BatchCli` | Jackson only |
| `ledger-app` | Single Spring Boot deployable: `JdbcGenerationStore` (PostgreSQL, Flyway, Spring Data JDBC, published-row INSERT/UPDATE/DELETE triggers), stateful raw + typed HTTP API, stateless `/v1/raw/evaluate`; `java -jar ledger-app.jar batch ...` dispatches to the CLI without starting the web container | Spring Boot 3.5 |
| `tools/parity_java.py` | Parity driver (`--mode batch\|http-gen\|http-json`): runs the JAR or the live HTTP service over anchors/A/A-replay/B/B-replay against an authority, validates receipts independently, reuses the unchanged `../tools/compare.py` for field-level diffs | Python 3 |
| `tools/http_parity.sh` | Launcher: private `postgres:15-alpine` container + fat JAR on a local port, then `http-gen` and `http-json` over A1 and all three A2 paths | bash, Docker |

Legacy source, macros, layouts, goldens, tests and `../tools/compare.py` are
unchanged; the Java tree is additive.

## Build and check

```sh
cd samples/insurance/java
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # any JDK 21
export MVNW_REPOURL=https://maven-central.storage-download.googleapis.com/maven2  # if repo.maven.apache.org is unreachable
./mvnw verify          # compile (-Werror), unit tests, spotless:check, checkstyle:check
./mvnw package         # -> ledger-app/target/ledger-app-0.1.0-SNAPSHOT.jar
```

## Batch adapter

```
java -jar ledger-app/target/ledger-app-0.1.0-SNAPSHOT.jar batch <command> ...
  bootstrap --store DIR --namespace NS --generation GEN --polin F --manifest F
  run       --store DIR --namespace NS --parent GEN --generation GEN
            --polin F --txnin F --manifest F --out DIR [--source-commit SHA]
  inbat     --polin F --txnin F --manifest F --out DIR
  verify    --store DIR
```

`run` pins the successor to a published parent whose bytes must equal the
supplied POLIN, applies every TXNIN record in physical order, validates the
outputs against the separately supplied expected manifest (counts and input
SHA-256s), publishes with an expected-parent compare-and-swap of the
namespace's current pointer, and writes `polout.bin`, `resout.bin` and
`receipt.json` to `--out`. Exit codes: 0 ok, 2 manifest/input rejection,
3 lifecycle/publication failure, 4 usage.

Legacy INSBAT semantics preserved: invalid master state -> RC 12 and no
output; genuine empty POLIN -> every transaction `NPOL`, zero POLOUT; genuine
empty TXNIN -> master copied unchanged, zero RESOUT. "Genuine" is decided by
the manifest, not by the delivered file, so aligned truncation (lost whole
records) and partial records are both rejected before anything is published.
The manifest (`insurance-expected-manifest-v1`: counts, POLIN/TXNIN
SHA-256, `rates_sha256` of the running rate table) is bound when the
generation is created, before any request is accepted.

```
java -jar ledger-app/target/ledger-app-0.1.0-SNAPSHOT.jar routines --cases F --out F
```

`routines` is the framework-free per-routine oracle used by
`tools/routine_parity.py`: it calls the Java equivalents of `INSPACK`,
`INSDATE`, `INSRATE`, `INSVAL` and `INSCALC` directly on the case bytes and
writes the business-observable outputs (validity, year/month-day/ordinal,
rate/fee, `SREC`/`OREC`) for comparison with the reviewed guest observations.

## HTTP API (stateful generations)

All paths are under `/v1/namespaces/{ns}`. Persistence is PostgreSQL
(`LEDGER_DB_URL`, `LEDGER_DB_USER`, `LEDGER_DB_PASSWORD`; Flyway migrates
`V1__generation_ledger.sql` on start-up). `LEDGER_SOURCE_COMMIT` is bound into
every receipt.

| Method / path | Purpose |
|---|---|
| `POST /import` | bootstrap a root generation from POLIN bytes pinned by an independent manifest (one root per namespace) |
| `POST /generations` | open a pending successor of a published parent; optional `polinBase64` must equal the parent's published bytes; returns a fence |
| `POST /generations/{gen}/requests:raw` | apply one 40-byte request (`recordHex`) under the fence; returns ordinal, echoed request bytes, 96-byte result bytes |
| `POST /generations/{gen}/requests` | typed JSON request; encoded to the exact 40 bytes before evaluation, response adds a typed `result` decoded from the result bytes |
| `POST /generations/{gen}/batch` | apply a whole TXNIN (base64) in physical order under the fence |
| `POST /generations/{gen}/publish` | validate against the pinned manifest, flip to PUBLISHED and CAS the namespace's current pointer; returns the receipt |
| `POST /generations/{gen}/discard` | fence-aware discard |
| `GET /generations/{gen}/{polout,resout,requests}` | published bytes only (404/409 for pending or discarded) |
| `GET /generations/{gen}/peek/...` | explicitly pending, unvalidated bytes |
| `GET /current`, `GET /generations/{gen}`, `GET /generations/{gen}/policies/{id}` | metadata and published policy reads |
| `POST /v1/raw/evaluate` | stateless evaluation of one master + one request (not used for parity) |

Status mapping: typed/envelope failures 400, unknown resources 404,
fence/lifecycle/CAS conflicts 409, manifest validation at publish 422; legacy
domain outcomes (`NPOL`, `ORDR`, `OVER`, ...) are HTTP 200 with the status in
the result bytes. Typed JSON is only accepted when it re-encodes to exactly the
submitted bytes; everything else (F signs, negative zero, malformed packed
fields, nonzero reserved/tail bytes, non-CP037 text, out-of-grammar dates) has
to go through `requests:raw`.

## Parity (FAST regression)

Authorities are kept distinct:

- **A1** generated goldens: `../golden/v1`
- **A2** archived observed MVS output: `../evidence/28790e2/runtime/<path>`
- **A3** fresh Hercules/MVS output: only if actually executed and recorded
  (`evidence/acceptance/`, see below)

```sh
cd samples/insurance/java
JAR=ledger-app/target/ledger-app-0.1.0-SNAPSHOT.jar
SHA=$(git rev-parse HEAD)
python3 tools/parity_java.py --authority a1 --jar $JAR --source-commit $SHA \
    --work /path/to/fresh/a1 --report /path/to/a1-report.json
for p in ifox-iewl as370-iewl as370-ld370; do
  python3 tools/parity_java.py --authority a2 --label $p \
      --authority-dir ../evidence/28790e2/runtime/$p --jar $JAR --source-commit $SHA \
      --work /path/to/fresh/a2-$p --report /path/to/a2-$p-report.json
done
```

The driver uses independent roots for `anchors` and the `a -> a-replay -> b
-> b-replay` chain, seeds each Java generation from the Java-produced,
validated predecessor, recomputes every receipt hash from the actual bytes,
compares POLOUT and RESOUT byte-for-byte including padding, tails, sign
nibbles and `SLAST`, reports each mismatch with record number, ID, field,
offset, raw bytes and decoded values, and stops at the first failing stage.

Stateful HTTP parity (mandatory; PostgreSQL-backed, not `/v1/raw/evaluate`):

```sh
cd samples/insurance/java
tools/http_parity.sh $JAR $SHA /path/to/fresh/work /path/to/reports [../evidence/28790e2/runtime]
# or, against an already running service:
python3 tools/parity_java.py --mode http-gen  --authority a1 --jar $JAR --source-commit $SHA \
    --base-url http://127.0.0.1:8080 --namespace-prefix gen-a1- --work W --report R
python3 tools/parity_java.py --mode http-json --authority a1 --jar $JAR --source-commit $SHA \
    --base-url http://127.0.0.1:8080 --namespace-prefix json-a1- --work W --report R
```

`http-gen` sends every request through `requests:raw`. `http-json` decides
independently (in Python, from the 40 bytes) whether a request is
byte-representable as typed JSON, sends those through `/requests` and the rest
through `requests:raw`, preserves physical order, checks the echoed request
bytes and ordinal on every response, decodes the returned 96-byte result and
compares each typed response field to it, then publishes, reads the published
POLOUT/RESOUT/requests back and compares them like the batch mode. Typed
coverage is reported separately (`typed_requests` / `raw_requests`).

Current results (`evidence/fast/`), A1 and all three A2 paths, 5/5 stages and
8,704 results each, successor masters identical:

| mode | typed | raw fallback |
|---|---|---|
| `batch` | n/a | n/a |
| `http-gen` | 0 | 8,704 |
| `http-json` | 7,424 | 1,280 (512 malformed packed, 256 negative zero, 256 F sign, 256 nonzero reserved/tail) |

Before A2/A3 bytes are used as an authority the driver runs the unchanged
legacy receipt validator (`../tools/compare.py`) with all seven receipt hashes
recomputed independently (`polin`/`txnin`/`polout`/`resout` from the files,
`build_manifest`/`guest_manifest` from the run's `provenance/` manifests,
`rates` from the frozen `data/rates`) and checks the observed inputs against
the pinned golden bytes; a missing, failed, timed-out, nonzero-RC or ABEND
receipt, a missing or wrong value for any key, a missing manifest or file, or
a hash/count mismatch rejects the stage before any comparison
(`tools/test_parity_authority.py`, 33 negative controls).

Negative controls: flipping one byte of an authority's `resout.bin` makes the
same driver stop at that stage with the first mismatching record, field,
offset, raw bytes and decoded values; changing the expected source commit or
JAR hash fails receipt validation before any comparison; a reverse proxy
(`tools/mutant_proxy.py`) that alters only one immediate HTTP response (its
`resultHex`, plus a self-consistent typed `charge`/`recordHex`) while the
request and the persisted `RESOUT` stay correct fails that stage in both
`http-gen` and `http-json` (`evidence/acceptance/reports/negative-controls/`).

## Parity (A3 ACCEPTANCE, fresh guest)

`evidence/acceptance/` records one fresh TK5/Hercules execution on this VM
(private DASD set, fresh dataset prefix, guest job ids and RCs in every
receipt) and the Java comparisons against it:

- the full corpus on all three executable paths (`tools/tk5_demo.py`,
  unchanged), 5/5 stages and 8,704 results per path, then Java `batch`,
  `http-gen` and `http-json` against each path: 9/9 runs PASS, same
  typed/raw split as the FAST table;
- 16 targeted cases generated by `tools/targeted_a3.py`, each run as its own
  guest job: true interest ties (6.5 → 7, 32.5 → 33), the reachable quotient
  OVER (45,656 days on MAXAMT cash), cash exactly on MAXAMT, charge ties,
  date/age/rate-band edges, C/D/F signs and negative zero, malformed records
  and precedence pairs, the replay/conflict/order matrix, empty TXNIN, empty
  POLIN, and four RC=12 invalid-master controls; 86 results compared in each
  Java mode, 16/16 PASS.

The fresh full-corpus outputs are byte-identical to the archived A2
outputs. The recovery section of the fresh run contains an intentional
failure (half-delivered B: guest `RUN RC=0`, host rejects with 3,039
differences, restarts from the unchanged checkpoint) — controller recovery
evidence only.

## Status and limitations

- Done: codec, V001 core, batch adapter, file and PostgreSQL generation
  stores, receipts, stateful raw/typed HTTP, `batch`/`http-gen`/`http-json`
  FAST parity against A1 and A2, Java-only lifecycle tests (fence, CAS race,
  retry commit boundaries, truncation, immutability, fail-closed restart).
- Done (bounded): real child-JVM kills of the PostgreSQL-backed service
  (`ServiceKillTest`: inside the commit, after the commit before the
  response, inside publication before the flip, after publication) and real
  HTTP client timeout / 503 / server-500 at both commit boundaries through a
  TCP fault proxy (`HttpBoundaryTest`), each followed by direct DB inspection,
  restart and retry; and an actual PostgreSQL outage mid-generation
  (`DbOutageTest`: the test-owned container is `docker stop`ped / `pause`d
  after a committed prefix and during publication, the live service fails the
  request with 500 and no receipt, the DB is restored and the prefix, retry,
  heartbeat and publication are verified against the oracle; a service restart
  during the outage exits non-zero). Runtime evidence in
  `evidence/acceptance/runtime/`. Not covered: power loss, network partition,
  failover; no takeover/resume (D1/D2/D4 pending decision).
- Done: A3 fresh guest acceptance (full corpus on three paths, 16 targeted
  cases) with Java `batch`/`http-gen`/`http-json` parity against it
  (`evidence/acceptance/`).
- Done: per-routine logical parity (`INSPACK`, `INSDATE`, `INSRATE`, `INSVAL`,
  `INSCALC`; 184 cases × 3 guest paths) against the independently reviewed
  routine-harness observations via `java -jar ... routines` and
  `tools/routine_parity.py` (`evidence/acceptance/routines/`). Compared:
  business-observable outputs only; assembler ABI, registers, scratch and
  unrelated `WORK` bytes are out of scope for Java.
- Truncation, manifest rejection, fence/CAS races, retry commit boundaries,
  immutability guards and fail-closed restart are Java-only tests; the
  legacy guest has no equivalent surface.
- PostgreSQL immutability is enforced by row triggers and the fence; the
  in-process publish lock is per JVM, the durable guard is the expected-parent
  CAS in one transaction.
- Agreement with the source-derived oracle and goldens is implementation
  evidence, not independent business-intent validation.
- The file store does not claim power-loss durability (no fsync/cut-power
  tests); publication is two distinct renames, and restart fails closed on
  any inconsistency it can detect.
- The routine harness itself (fixtures, guest capture, observed-v2 derivation)
  is a separate workstream and is consumed here read-only, pinned by archive,
  fixture-manifest and capture hashes; it is not modified from this tree.
- Not implemented (explicit deviations from the plan, see
  `evidence/acceptance/README.md`): a claim/takeover endpoint, verified-prefix
  resume of a pending generation, an unpublishable-pending state (failed
  publication discards), and `Prefer: return=original`. Admission order in the
  HTTP adapter is defined at servlet-filter entry per namespace/generation
  within one JVM, not TCP arrival or cross-instance order; the claim is order
  preservation for accepted calls — a rejected envelope consumes a ticket but
  no ordinal and a `batch` call consumes one ticket for N contiguous
  ordinals, so the `X-Admission-Sequence` ticket is not in general equal to
  the committed ordinal.
