# A3 ACCEPTANCE — fresh Hercules/MVS observations vs Java

Everything in this directory was produced by an actual TK5 (MVS 3.8j) guest
run on Hercules on this VM, then compared against the Java implementation
built from source commit `f26505104a72a8dd6b021c26019d05f06a0d443c`
(JAR `sha256:7608405d5d9ede3dd0cace0653d85fd970dfc54b3a057bcfaf151eeb77def1ab`,
OpenJDK 21.0.12). It is distinct from `../fast/` (A1 generated goldens and
A2 archived observations). Nothing here is simulated or copied from the
archive; the guest job ids, dataset prefixes and step return codes are in the
receipts.

Legacy guest transport, build and validation used the unchanged tools in
`samples/insurance/tools/` (`tk5_demo.py`, `tk5_validate.py`, `compare.py`).
The only new tooling is on the Java side (`java/tools/targeted_a3.py`,
`java/tools/http_parity.sh`, `java/tools/parity_java.py`).

## 1. Full application corpus (`fresh-run/`)

`tools/tk5_demo.py` executed all three executable paths on a private local
DASD set with a fresh dataset prefix (`IBMUSER.INSJ4.*`; guest jobs
`JOB00009`–`JOB00166`):

| path | build | anchors, A, A-replay, B, B-replay | controls | recovery |
|---|---|---|---|---|
| `ifox-iewl` (guest IFOX00 → IEWL) | `INSBUILD` RC=0 | 5/5 PASS, 8,704 results | PASS | PASS |
| `as370-iewl` (host as370 → guest IEWL) | RC=0 | 5/5 PASS, 8,704 results | PASS | PASS |
| `as370-ld370` (host as370 → ld370 → IEBCOPY) | RC=0 | 5/5 PASS, 8,704 results | PASS | PASS |

Per path and stage: `receipt.json` (guest job id, RCs, input/output SHA-256,
counts) and `comparison.json` (legacy host validation against the goldens).
`current.json` is the chained master pointer, `controls.json` the RC=12 /
S806 / immutability / single-writer controls, `recovery.json` the
checkpoint → half-delivered B → restart sequence, `build.json` /
`guest.json` the toolchain and guest provenance (hashes only). `tk5_demo.log`
is the complete console log.

Read the recovery section carefully: the `partial` phase is *supposed* to
fail. The guest returned `RUN RC=0` for the half-delivered B input; the host
controller rejected it (`FAIL: 1 acceptance errors, 3039 differences`),
discarded the partial generation and re-ran B/B-replay from the unchanged
checkpoint (`restart_master_sha256 = 01dc633d…`). This proves controller
recovery only — not guest-side trailer enforcement, mid-job crash recovery,
power loss or native multi-writer semantics.

The fresh outputs are byte-identical to the archived A2 outputs
(`resout.bin`/`polout.bin` SHA-256 per stage equal to
`samples/insurance/evidence/28790e2/runtime/<path>/<stage>/`), which is why
the binaries are not duplicated here; `receipt.json` carries the hashes.

Java comparison (`reports/a3-<path>-<mode>.json`), each chaining the Java
implementation's own state through the five stages and comparing every
result record, every successor master record and the receipt hashes:

| mode | ifox-iewl | as370-iewl | as370-ld370 | typed / raw |
|---|---|---|---|---|
| `batch` | 5/5, 8,704 | 5/5, 8,704 | 5/5, 8,704 | n/a |
| `http-gen` (PostgreSQL, all `requests:raw`) | 5/5, 8,704 | 5/5, 8,704 | 5/5, 8,704 | 0 / 8,704 |
| `http-json` (PostgreSQL, hybrid) | 5/5, 8,704 | 5/5, 8,704 | 5/5, 8,704 | 7,424 / 1,280 |

The 1,280 raw fallbacks are the byte-nonrepresentable requests (512 malformed
packed, 256 negative zero, 256 F sign, 256 nonzero reserved/tail).
`reports/http_parity.log` is the driver log of the HTTP runs (A1, A2 and A3
in one invocation).

## 2. Targeted fresh guest cases (`targeted/`)

`java/tools/targeted_a3.py cases` generated 16 cases (`targeted/cases.json`,
with the intended semantics in each `description`); `capture` ran each one
as its own `INSRUN` job on the same guest, load library `IBMUSER.INSJ4.I`
(the fresh IFOX00 build), dataset prefix `IBMUSER.INSJ4.X.Cnn`, jobs
`JOB00167`–`JOB00214`. `targeted/authority/<case>/` holds the exact
`polin.bin`/`txnin.bin` submitted, the `polout.bin`/`resout.bin` read back,
and `receipt.json` (job id, RCs, hashes, observed statuses).
`targeted/authority/capture.json` is the summary, `capture.log` the console.

| case | tx | observed statuses (guest) | Java batch / http-gen / http-json |
|---|---|---|---|
| `interest-tie` — 73,000 @325 bps × 1 day = 6.5 → 7; 5 days 32.5 → 33; 14,600 × 25 days 32.5 → 33 | 3 | OKAY ×3 | PASS ×3 |
| `quotient-over` — cash 99,999,999,999 from 1900-01-01 to 2025-01-01 (45,656 days @325 bps) → interest 406,526,027,393 > MAXAMT; same cash, 1 day → OVER via cash+interest; cash = MAXAMT, 0 days → OKAY | 3 | OVER, OVER, OKAY | PASS |
| `cash-max-boundary` — 96,800,000,000 over 2024 (366 days) → interest 3,154,619,178, cash 99,954,619,178; premium 45,380,821 → cash exactly MAXAMT; premium 1 → OVER | 3 | OKAY, OKAY, OVER | PASS |
| `charge-tie` — 0-day premium fee ties: 10.5 → 11, 3.5 → 4 (half-even would give 10), plus 5.0/45.0/15.0 exact | 5 | OKAY ×5 | PASS |
| `date-bounds` — 1900-01-01, 2099-12-31 (age 199), 2100-01-01, 1900-02-29, 2000-02-29, 2025-04-31, 2025-13-01, 0, negative, 999990101, pre-SDATE | 13 | OKAY, OKAY, DATE, DATE, OKAY, OKAY, DATE ×6, OKAY | PASS |
| `age-rate-bands` — quotes on every rate-band edge (2019-12-31 … 2025-01-01), age 9 with fee / age 10 waived, 2024-02-29 issue → age 0 / 1 | 10 | OKAY ×10 | PASS |
| `packed-signs` — F sign, negative zero (SLAST keeps the D nibble), exact replay DUPL, C-sign zero on same seq CNFL, F-sign W, P −5, W −0 | 7 | OKAY, OKAY, DUPL, CNFL, OKAY, NEGA, OKAY | PASS |
| `malformed` — digit nibble A, sign nibbles 0/E, nonzero TPAD/TTAIL, op X / lowercase p, Q/D with amount, and precedence pairs (FORM<PACK, PACK<TYPE, NEGA<DATE, OVER<TYPE, DATE<TYPE, TYPE<FUND) | 15 | PACK, PACK, PACK, FORM, FORM, TYPE, TYPE, AMNT, AMNT, FORM, PACK, NEGA, OVER, DATE, TYPE | PASS |
| `sequence-matrix` — seq 0 / −1, replay, same-seq conflict (also over FORM), gap, lower seq, rejected seq then reused, NPOL with and without malformed bytes | 13 | ORDR, ORDR, OKAY, DUPL, CNFL, CNFL, ORDR, OKAY, ORDR, DATE, OKAY, NPOL, NPOL | PASS |
| `funds-ops` — W/L > cash, L = cash, R > loan, W leaving cash < loan, full R, D 0 does not close, Q after D, P MAXAMT then Q +1 day OVER, W back to 0 | 11 | FUND, FUND, OKAY, FUND, FUND, OKAY ×4, OVER, OKAY | PASS |
| `empty-txnin` — two policies, zero transactions | 0 | (unchanged master, empty RESOUT) | PASS |
| `empty-polin` — zero policies, three transactions | 3 | NPOL ×3, empty POLOUT | PASS |
| `control-master-loan-over-cash` | – | `RUN RC=12`, no output | Java rejects the master (PASS as control) |
| `control-master-unordered` | – | `RUN RC=12` | Java rejects (PASS as control) |
| `control-master-duplicate` | – | `RUN RC=12` | Java rejects (PASS as control) |
| `control-master-513` | – | `RUN RC=12` | Java rejects (PASS as control) |

86 result records and every successor master compared byte-for-byte in each
of the three Java modes (`reports/a3-targeted-{batch,http-gen,http-json}.json`,
`16/16` cases each). In `http-json` the per-case typed/raw split is recorded
(`date-bounds` 9/4, `packed-signs` 2/5, `malformed` 8/7, `sequence-matrix`
10/3, all others fully typed); every typed response field was checked
against the independently decoded 96-byte record.

`capture.json` also records the source-derived oracle's statuses
(`oracle_statuses`, `oracle_agrees`) for each case. Agreement there is a
cross-check of the Python oracle against the guest, **not** independent
business-intent validation; the pass/fail authority for Java is the guest
output.

Negative control (not committed, reproducible): flipping the low byte of
`OINT` in `interest-tie/resout.bin` (7 → 67 cents) makes
`targeted_a3.py compare` fail that case with
`authority resout.bin hash differs from the guest receipt` and report the
first mismatch at record 1, field `interest`, offset 62, expected
`0000000000067c` (67) vs observed `0000000000007c` (7); `15/16`.

## 3. What is Java-only (not guest-observed)

Truncation (aligned and partial-record), expected-manifest rejection, fence
and sibling CAS publication races, retry before/after the commit boundary,
published-row INSERT/UPDATE/DELETE guards, fail-closed restart and the typed
JSON envelope are exercised only by the Java unit/Testcontainers suites
(`ledger-application`, `ledger-app`). The legacy guest has no equivalent
surface for them; they are service-layer guarantees, not legacy parity.

## 4. Limitations

- Fresh evidence is bounded to the corpus above (8,704 results × 3 paths plus
  86 targeted results); it is not a proof about an unseen estate, z/OS,
  HLASM, production rules or scale.
- The guest run is single-writer QSAM batch; no power-loss, fsync, or
  cut-power test was performed for either the guest or the Java file store.
- Guest credentials were supplied through environment variables only and do
  not appear in this directory; the JCL and spool files, which would echo
  them, were deliberately not copied.
- Per-routine assembler integration acceptance is a separate workstream and
  remains pending until reviewed fixtures and fresh observations are
  supplied.

## 5. Reproduce

Working directory `samples/insurance/java`, with the guest already reachable
via the pinned `tools/tk5.py` setup (`TK5_*` environment for credentials):

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export MVNW_REPOURL=https://maven-central.storage-download.googleapis.com/maven2
./mvnw -q package
JAR=ledger-app/target/ledger-app-0.1.0-SNAPSHOT.jar; SHA=$(git rev-parse HEAD)

# fresh full-application run (legacy tools, unchanged)
python3 ../tools/tk5_demo.py --root <mvs-root> --prefix IBMUSER.<fresh> --evidence <run-dir>

# targeted fresh cases
python3 tools/targeted_a3.py cases   --out <cases-dir>
python3 tools/targeted_a3.py capture --cases <cases-dir> --evidence <authority-dir> \
    --root <mvs-root> --prefix IBMUSER.<fresh>.X --load-prefix IBMUSER.<fresh>.I

# Java vs fresh guest, batch
for p in ifox-iewl as370-iewl as370-ld370; do
  python3 tools/parity_java.py --mode batch --authority a3 --authority-dir <run-dir>/$p \
      --jar $JAR --source-commit $SHA --work <work>/$p --report <reports>/a3-$p-batch.json
done
python3 tools/targeted_a3.py compare --mode batch --cases <cases-dir> --authority <authority-dir> \
    --jar $JAR --source-commit $SHA --work <work>/targeted --report <reports>/a3-targeted-batch.json

# Java vs A1 + A2 + fresh guest, stateful HTTP (PostgreSQL in Docker), both modes
A3_RUNTIME=<run-dir> A3_TARGETED=<cases-dir>:<authority-dir> \
    tools/http_parity.sh $JAR $SHA <work>/http <reports>
```
