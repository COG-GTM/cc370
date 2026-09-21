# A3 ACCEPTANCE — fresh Hercules/MVS observations vs Java

Everything in this directory was produced by an actual TK5 (MVS 3.8j) guest
run on Hercules on this VM (sections 1–2) or by the independently reviewed
per-routine guest harness (section 3), then compared against the Java
implementation. The Java comparison reports in `reports/` and `routines/`
record the source commit and JAR SHA-256 they were produced from (see
`source_commit` / `jar_sha256` / `java.build_identity` inside each report;
the guest observations themselves predate those commits and are bound by
their own receipts). It is distinct from `../fast/` (A1 generated goldens and
A2 archived observations). Nothing here is simulated or copied from the
archive; the guest job ids, dataset prefixes and step return codes are in the
receipts.

Legacy guest transport, build and validation used the unchanged tools in
`samples/insurance/tools/` (`tk5_demo.py`, `tk5_validate.py`, `compare.py`).
The only new tooling is on the Java side (`java/tools/targeted_a3.py`,
`java/tools/http_parity.sh`, `java/tools/parity_java.py`,
`java/tools/routine_parity.py`, and the negative-control pair
`java/tools/mutant_proxy.py` / `java/tools/negative_response_control.sh`).
Before any A2/A3 bytes are used as an authority, `parity_java.py` runs the
unchanged legacy receipt validator (`compare.validate_receipt`) with all seven
hashes recomputed independently — `polin_sha256`, `txnin_sha256`,
`polout_sha256`, `resout_sha256` from the files, `build_manifest_sha256` and
`guest_manifest_sha256` from the run's `<path>-provenance/{build,guest}.json`
manifests (archive layout, or explicit `--build-manifest`/`--guest-manifest`),
`rates_sha256` from the frozen `golden/v1/rates.json` (whose inventory in
`SHA256SUMS` is verified first) — and additionally checks
the observed `POLIN`/`TXNIN` against the pinned golden input bytes, not
against the same-directory receipt. It rejects missing, failed, timed-out,
nonzero-RC or ABEND receipts, a missing or wrong value for any of the seven
keys, missing manifests or files and hash/count mismatches.
`targeted_a3.py compare` applies the same rule to the targeted cases (positive
cases through the unchanged validator; the intentional RC=12 controls through
an explicit control-receipt validator that still fails closed on schema,
job identity, outcome, timeout, ABEND, wrong RC and provenance) and pins the
cases directory's `POLIN`/`TXNIN`. `tools/test_parity_authority.py` (33
tests) is the negative-control suite: one mutation per key/manifest/input,
each of which must be rejected.

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
The receipts are in the legacy `insurance-run-v1` shape, rederived by
`targeted_a3.py rederive` from the unchanged raw guest `result.json` of each
job (`derived_from` names it and pins the capture-time receipt's SHA-256) so
they carry the seven hashes the legacy validator checks; the build/guest
manifests they reference are `targeted/provenance/{build,guest}.json`.
Positive cases go through unchanged `compare.validate_receipt`; the four
RC=12 controls through the explicit fail-closed control validator
(`guest_receipt_validated` per case in the targeted reports).

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

## 3. Per-routine guest observations (`routines/`)

The five library routines (`INSPACK`, `INSDATE`, `INSRATE`, `INSVAL`,
`INSCALC`) were called directly on the guest by the separately owned routine
harness (routine-harness commit `5a71041743bc8086d47310380b417caf1e47ce08`,
evidence archive SHA-256
`3c881564cdc03812b9fb7affacb2634d7c5148d3b01aedaa2661a42f0ff553b2`, 184 cases,
three executable paths, identical `capture.bin` on all three:
`2121c27da9b626f46f1a049a4e7caa9480d50d8e70fbf10dfcf51345c6bfdbc9`). That
harness, its fixtures and its guest receipts are not part of this PR and were
not modified here.

`java/tools/routine_parity.py` accepts the archive only after it has
re-verified, independently of anything the archive says about itself:
the archive SHA-256; `RECEIPTS.json` (schema, three clean guest jobs per path —
allocate + assemble/link/import + run, every step `0000`, 184/184, zero
findings); `capture.bin` SHA-256 equal across the file, the receipt,
`observed.json` and the AWS tape payload; `cases-in.aws` payload SHA-256 equal
to the fixture manifest's `cases_sha256`; the fixture manifest's canonical
SHA-256 equal to the one recorded in `observed.json`; the frozen assembler
sources, macros and rate table in this checkout equal to the fixture-pinned
hashes; `observed.json` schema/count/order/valid flag with empty problem and
quarantine lists; and every observed input/output byte re-read from
`capture.bin` (including the `KEEP`/`STATE`/`TXN` chaining of `WORK` between
consecutive `INSCALC` cases). `expected.json` is never read. It then rebuilds
the 184 logical calls from the raw case bytes, runs
`java -jar ledger-app.jar routines` once, and compares only the business
observables:

| routine | cases | compared | not compared (assembler-only) |
|---|---|---|---|
| `INSPACK` | 20 | validity (guest `R15` ↔ Java `valid`); argument bytes unchanged | registers, save area |
| `INSDATE` | 26 | validity (`WVALID`); `WYEAR` whenever the guest wrote it (positive inputs, valid or not); `WMD`/`WORD` when assigned; `WDATE` unchanged | `R15` fixed 0 ABI, unwritten storage |
| `INSRATE` | 20 | `WRATE`/`WFEE` written (value) or left unwritten (below-table dates, no date validation); inputs unchanged | scratch |
| `INSVAL` | 34 | validity (`WVALID` and `R15`); the 128-byte policy unchanged | scratch |
| `INSCALC` | 84 | `SREC` and `OREC` byte-for-byte; `TREC` unchanged; status histogram OKAY 45 / OVER 6 / FUND 4 / ORDR 4 / CNFL 3 / FORM 3 / PACK 3 / STAT 3 / TYPE 3 / AMNT 2 / DATE 2 / DUPL 2 / NEGA 2 / NPOL 2 | arithmetic scratch, unrelated `WORK` bytes |

Result: **184/184 on each of `ifox-iewl`, `as370-iewl`, `as370-ld370`**
(`routines/routine-parity.json`, per-case `routines/routine-results-<path>.json`,
the reconstructed Java inputs and outputs in `routines/java-cases.json` /
`routines/java-outputs.json`, the fixture manifest as
`routines/routine-fixture-manifest-5a71041.json`). Java is scratch-free: it
neither models nor asserts the assembler's `WORK` area, registers, condition
codes or save areas; untouched unrelated `WORK` bytes are an assembler
preservation property, not a Java requirement.

Negative controls (`routines/negative-controls/`): six one-field mutations of
the Java output (`INSDATE` year +1, `INSRATE` writing a rate below the table,
`INSVAL` and `INSPACK` verdict flips, one `OREC` byte, one `SREC` byte) each
fail exactly their case with the guest and Java values printed; a one-byte
change to the archive is rejected before extraction (`tampered-archive.log`).

## 4. v3 acceptance items T-01 … T-16

Tier legend: **GUEST** = fresh guest output compared byte-for-byte (sections
1–3); **JAVA** = Java-only unit/Testcontainers/subprocess test (section 5).
Test names refer to `contract-v001` (`ContractV001Test`, `ContractBreadthTest`,
`DirectRoutineTest`), `ledger-application` (`ExpectedManifestAndBatchTest`,
`FileGenerationStoreTest`, `ProcessKillTest`, `RoutineCliTest`) and
`ledger-app` (`GenerationApiTest`, `JdbcGenerationStoreTest`, `JdbcTakeoverTest`,
`ServiceKillTest`, `HttpBoundaryTest`, `DbOutageTest`).

| item | status | evidence |
|---|---|---|
| T-01 interest tie + wrong-rounding control | closed | GUEST `interest-tie` (6.5 → 7, 32.5 → 33 ×2); JAVA `interestHalfUpTieRoundsAwayFromZero`; negative control `wrongRoundingIsDetectedOnTheTieOnly` (a half-even mutant of the same formula gives 6 on the tie and is caught; it agrees off the tie, so the tie is the discriminating case) |
| T-02 charge ties | closed | GUEST `charge-tie` (10.5 → 11, 3.5 → 4, exact 5.0/45.0/15.0); the 49 corpus ties are inside the 8,704-result A1/A2/A3 runs |
| T-03 ages 0..99 both sides of the anniversary | closed | JAVA `everyAgeOnBothSidesOfTheAnniversary` (issue 1925-06-15; for every age 0–99 the day before, the anniversary and the day after, with rate row and fee waiver checked per side); GUEST `age-rate-bands` (age 0/1 across a 2024-02-29 issue, age 9 fee / age 10 waived) — the full 0..99 sweep is Java-only, not guest-observed |
| T-04 rate-band boundaries | closed | GUEST `age-rate-bands` (every band edge 2019-12-31 … 2025-01-01, period-end semantics); GUEST routine `INSRATE` 20 direct cases incl. below-table dates; JAVA `lookupAgreesWithDirectOnEveryValidatedDate` |
| T-05 overflow precedence and reachability | closed | GUEST `malformed` (a: `TAMT` above MAXAMT with an unknown op → OVER before TYPE), `quotient-over` (b: 45,656-day quotient 406,526,027,393 → OVER; c: cash + interest → OVER), `cash-max-boundary` (d: exactly MAXAMT OKAY, +1 OVER), `funds-ops` (e: loan beyond cash → FUND first; f: negative cash → FUND); JAVA `reachableQuotientOverUnderFrozenRates`, `capitalizedCashOverIsDistinctFromQuotientOver`, `widthFaultFailsClosedInsteadOfLegacyStatus` (S0CB-equivalent, injected rate) |
| T-06 packed signs / malformed, all 13 digit positions | closed | JAVA `everyPackedDigitPositionIsDecodedAndValidated` (digit nibble A–F in each of the 13 positions, sign nibbles 0/A/B/E); GUEST `packed-signs`, `malformed`, routine `INSPACK` 20 cases; corpus 512 malformed + 256 F + 256 negative-zero requests |
| T-07 replay matrix at all 40 offsets | closed | JAVA `everyReplayOffsetHasItsFirstFailureOutcome` (offsets 0–7 → NPOL or the other policy's verdict, 8–11 → ORDR or fresh evaluation, 12–39 → CNFL; seq 0/−1/s−1 → ORDR; gap accepted; rejected request leaves SSEQ/SLAST); GUEST `sequence-matrix`, `packed-signs` (DUPL/CNFL) |
| T-08 comparator detects structural output mutations | closed | `tools/compare.py` unchanged; `parity_java.py` fails a stage on count mismatch, missing/extra/reordered records and receipt-hash mismatch; negative controls in section 2 and `../../README.md` § Parity (FAST regression) (one-byte authority mutation → hash rejection + field diff; wrong JAR/commit → receipt rejection) |
| T-09a genuine empty inputs | closed | GUEST `empty-txnin`, `empty-polin`; JAVA `batchEmptyTransactionsLeavesMasterUnchangedWithZeroResults`, `batchEmptyMasterYieldsNpolForEveryTransactionAndEmptyPolout`, `emptyTxninPublishesUnchangedMasterAndZeroResults` |
| T-09b truncated / short input (controller) | closed | JAVA `manifestRejectsAlignedTruncation`, `manifestRejectsPartialRecordAndUnexpectedlyEmptyDelivery`, `manifestRejectsWrongBytesOfRightLength`, `batchRejectsPartialMasterRecord`, `manifestPinnedAtCreationIsEnforcedAtPublishWith422AndDiscards`, `wrongTxninHashIsRejectedEvenWhenCountsMatch` — the manifest is bound at generation creation, before any request |
| T-10 timeout / errors / crash | closed (bounded, see D2′ and section 7) | JAVA (file store, real `Runtime.halt` of a child JVM) `ProcessKillTest`: halt before commit, after commit before the response, before publish, between the two publication renames, after publish; after each halt the store is reopened by a *new* process, the abandoned generation is **claimed** (`batch resume`), its durable prefix verified and the run continued at `lastOrdinal + 1` to the same bytes as the uninterrupted oracle (`killBeforeCommitLeavesNoTraceOfTheRequestAndResumeContinuesAtOrdinalThree`, `killAfterCommitLosesTheResponseAndResumeDoesNotReapplyTheCommittedRequest`, `killBeforePublishKeepsAllCommitsPendingAndResumeOnlyPublishes`, `resumeOfAnAbandonedGenerationCanBeKilledAndResumedAgain`); a corrupt journal fails the claim closed and leaves the generation abandoned, the explicit `discard-abandoned` + rerun is a separate path (`corruptJournalFailsTheClaimClosedAndLeavesTheGenerationAbandoned`, `abandonedGenerationCanBeExplicitlyDiscardedAndRerunFromTheParent`); the orphan between the two renames is never served. JAVA (PostgreSQL, the real Spring Boot service in a child JVM against a Testcontainers PostgreSQL, `ChildService` + `KillSwitch`) `ServiceKillTest`, 7 scenarios with retained runtime evidence `runtime/service-kill.*.json`: (1) halt inside the commit of request 3 → exit 137, DB holds exactly ordinals 1–2 and fence 1; a replacement service's `claim` while the dead writer's lease is still live → 409 `fenced` ("a live writer is never displaced") and the row is untouched; after expiry `claim` → 200 (`lastOrdinal` 2, fence 2, claims 1), the replacement continues at ordinal 3 and publishes — `RESOUT`/`POLOUT` equal the uninterrupted oracle; (2) halt after the commit of request 3 but before the response → DB holds ordinal 3; the claim reports `lastOrdinal` 3, `resumeIndex` skips the committed request (no `DUPL`/`ORDR` is appended), one request is applied on resume, output equals the oracle; (3) halt inside the publication transaction before the status flip → output rolled back, 4 entries pending, parent current; claim verifies the 4-entry prefix, applies nothing and publishes the verified prefix = oracle; (4) halt after publication before the receipt response → published, restart serves it, a claim of a `PUBLISHED` generation is refused; (5) `SIGSTOP` of the live writer, claim after its lease expires (fence 1 → 2), replacement commits ordinal 3, `SIGCONT` — the woken writer's `requests:raw`, `requests`, `batch`, `publish` and `discard` all → 409 `fenced` and the DB row is byte-identical before/after the wake (`frozenWriterWakesAfterTheTakeoverAndEveryMutationIsFenced`); (6) one result byte of committed entry 2 altered directly in the DB → `claim` 409 `checkpoint` "entry 2: stored result is not what the contract produces", the row stays `PENDING`/fence 1/claims 0, only the explicit `discard-abandoned` moves it to `DISCARDED` ("abandoned, discarded explicitly: corrupt durable prefix") and a fresh sibling from the parent equals the oracle; (7) the archived A2 stage `a` (4,096 TXNIN records, `ifox-iewl`, receipt/hash/pinned-input validated; the driver's seed-independent first request makes 4,097 committed entries in the uninterrupted run): halt after 2,049 durable entries → claim at fence 2, `resumed_from_ordinal` 2,050, 2,048 requests applied on resume, 4,097 final entries, final `POLOUT` `218da2fb…` / `RESOUT` `6215239a…` equal both the uninterrupted Java emulation and the archived MVS `polout.bin`/`resout.bin` (`equals_archived_mvs: true`). JAVA (PostgreSQL, real HTTP client through a TCP fault proxy, `HttpBoundaryTest`) client `HttpTimeoutException` with the request dropped before the service (no commit, retry → `OKAY`) and with the response dropped after the commit (commit present, retry → `DUPL`; with an intervening transaction → `ORDR`), gateway 503 unsent and 503 after the service committed, and an actual server 500 thrown inside the commit transaction (rolled back, retry → `OKAY`) and after it (kept, retry → `DUPL`); the DB is inspected directly after every fault, so a timeout is never read as proof of rollback — this is the *same-writer retry* path, distinct from the resume path above (a resume client reconciles the committed prefix and re-sends nothing). JAVA (PostgreSQL, real service in a child JVM against a PostgreSQL container owned by the test, `DbOutageTest`) **actual database outage mid-generation** — no kill switch, fault point or thrown exception: after a committed two-request prefix the container is `docker stop`ped (state `exited`, a direct JDBC connect is refused), the live service answers the next `requests:raw` with 500 (`FATAL: terminating connection due to administrator command`), the current read fails closed, the heartbeat logs its failure and the process stays up; after `docker start` the generation still holds exactly ordinals 1–2 (same entry SHA-256, no output, parent current), the same writer retries request 3 → `OKAY`, commits 4, renews and publishes = oracle. Also `docker pause` (request times out at the JDBC socket → 500, nothing committed on `unpause`); stop *during* publication (500, no receipt, `PENDING` with 4 entries, publish retry after restart = oracle); and a service restart *during* the outage that exits on `Connection … refused`, then after restore + lease expiry the restarted service **leaves the generation `PENDING` and claimable** (startup no longer discards), the old fence → 409, an explicit `claim` returns `lastOrdinal` 2 / fence + 1, `resumeIndex` = 2 and the resumed publication equals the oracle (`restartDuringTheOutageFailsClosedAndStartsAgainAfterRestore`). Retained evidence `runtime/db-outage.*.json`. In-process fault injection and fail-closed restart tests remain as unit-level coverage. |
| T-11 race / retry / fencing / CAS | closed (bounded, see D3 and section 7) | (a) fencing and takeover — `JdbcTakeoverTest` (34 tests, two-connection where a race is asserted, all against PostgreSQL time `clock_timestamp()`): `claimAfterLeaseExpiryVerifiesThePrefixAndResumesToTheUninterruptedOutput`, `resumeAfterAnUnacknowledgedCommitDoesNotAppendTheLostRequestAgain`, `claimWithNothingCommittedResumesFromTheSeed`, `liveWriterIsNeverDisplacedOrDiscarded`, `expiredWriterCannotRenewCommitPublishOrDiscardEvenBeforeAnyClaim` (an expired lease cannot be resurrected by its own renew), `repeatedClaimsChainForwardAndEveryEarlierWriterStaysRejected`, `concurrentClaimsHaveExactlyOneWinner` (row lock `FOR UPDATE`; the loser sees the live lease of the winner), `claimVersusCommitRaceAcrossLeaseExpiryLetsOnlyTheClaimThrough`, `claimVersusCommitRaceBeforeLeaseExpiryLetsOnlyTheCommitThrough`, `claimVersusRenewRaceAcrossLeaseExpiryDoesNotResurrectTheOldLease`, `claimFailsWhenThePinnedParentIsNoLongerCurrent`, `resumedPublicationKeepsExpectedParentCas`, `discardAbandonedIsExplicitRequiresAnExpiredLeaseAndKeepsTheEvidence`; fail-closed corruption/incompatibility controls `corruptResultBytesFailTheClaim`, `corruptSuccessorBytesFailTheClaim`, `corruptPolicyStateFailsTheClaim`, `ordinalGapFailsTheClaim`, `tamperedCheckpointFailsTheClaim`, `changedPinnedInputOrManifestFailsTheClaim`, `changedRateTableFailsTheClaim`, `changedContractImplementationIdentityFailsTheClaim`, `identityIsContentDerivedAndPinnedImmutably`, `entryStateAndCheckpointAreOneTransaction`; typed-metadata binding `flippedEntryTypedFlagBreaksTheChainAndFailsTheClaim`, `flippedTypedFlagWithAReforgedChainStillFailsOnTheCounters` (flags, chains and checkpoint re-forged consistently, request/result/successor bytes untouched — the persisted `typed_requests`/`raw_requests` still refuse the claim), `wrongTypedOrRawCounterFailsTheClaimWithIntactEntries`, `countersNotAccountingForEveryEntryFailTheClaim`, `claimReportsTheVerifiedTypedAndRawCounts`; mandatory V3 metadata `schemaRefusesMissingCheckpointMetadata` (V4 `NOT NULL`), `missingGenerationCheckpointFailsClosedEvenIfTheSchemaAllowedIt`, `missingContractIdentityFailsClosedEvenIfTheSchemaAllowedIt`, `missingEntryChainFailsClosedEvenIfTheSchemaAllowedIt` (constraint dropped in the test, null written, claim refused, row left `PENDING`/unclaimed — no downgrade to weaker verification); lost claim response `lostClaimResponseIsReconciledByTheSameReplacementWriterWithoutASecondTransition` (second `claim` by the same replacement writer while its own lease is live returns the identical `Claimed`; `claims` 1, fence, `writer_id`, `lease_expires_at` and the single `generation_claim` row unchanged; a third writer is still 409 while that lease is live; the reconciled writer continues to publication) and `reconciliationStillVerifiesThePrefixAndThePinnedManifest`; HTTP routes `claimRouteTakesOverAnExpiredWriterVerifiesThePrefixAndResumesAtTheNextOrdinal`, `discardAbandonedRouteIsExplicitAndRequiresAnExpiredLease`, `abandonedPendingGenerationIsLeftClaimableAtOpenAndNeverServedAsPublished`; file store `claimByTheHoldingProcessIsIdempotentAndRefusesUnknownOrPublishedGenerations`, `abandonedGenerationCanBeDiscardedInsteadOfClaimed`, `claimFailsClosedOnCorruptJournalChangedManifestOrChangedContract`, `claimFailsClosedOnTypedFlagOrCounterCorruption` (journal typed flag flipped, invalid flag byte, summary counters rewritten), `pendingResultsAreNotServedAsPublishedAndRestartLeavesThemClaimable`; HTTP `GenerationApiTest` same-holder `claim` → 200 with the same fence and `claims` 0, and a repeated `claim` after a takeover (lost response modelled by calling the route twice) → 200 with the identical body; plus the real-process scenarios (5)–(6) of T-10; (b) `twoHundredConcurrentRequestsCommitInAdmissionOrder` (200 concurrent accepted single raw requests, 32 threads; each ordinal equals the servlet-level admission ticket of `AdmissionSequencer`, each exactly once, echoed bytes and persisted `RESOUT` re-read after publish) and `mixedRejectedAndBatchCallsPreserveAdmissionOrderWithoutTicketOrdinalEquality` (60 concurrent calls mixing accepted single requests, 3-record batches and malformed 39-byte envelopes: committed ordinals follow ticket order, rejected envelopes consume a ticket but no ordinal, a batch consumes one ticket for three contiguous ordinals, ticket ≠ ordinal asserted) — the admission order is defined at the servlet filter within one JVM (D3), and `claim` takes an admission ticket too, so within the claiming JVM it is serialized with that generation's apply/batch calls; across JVMs the `FOR UPDATE` row lock and the fence decide (`concurrentClaimsHaveExactlyOneWinner`, `claimVersusCommitRace…`); (c) `siblingPublishRaceHasExactlyOneWinnerAndTheLoserIsDiscarded`, `publishCasFailsWhenCurrentMovedUnderTheLease`, `resumedPublicationKeepsExpectedParentCas`; (d) real kills in `ProcessKillTest` and `ServiceKillTest`, real client timeout / 503 / 500 at both commit boundaries in `HttpBoundaryTest` (before commit → `OKAY` on retry; committed-but-response-lost → `DUPL` while still latest; intervening transaction → `ORDR`) for the same-writer retry path, and the resume path that re-sends nothing (T-10 (2)); `Prefer: return=original` not implemented (optional, off, D5); (e) `applyAfterPublicationIsFencedAndLeavesThePublishedGenerationUntouched`, `directChildWriteBegunWhilePendingBlocksPublicationUntilItEnds`, `directChildWriteWaitsForThePublisherLockAndIsRejectedAfterTheFlip` (two connections), `generation_guard` trigger (V3): fence only increases, writer changes only with a new fence, ordinals contiguous and never rewound, identity columns immutable, `generation_claim` append-only |
| T-12 blind docs-only oracle | **optional — not authorized, not done** | separate approval per the v3 plan; nothing here depends on it |
| T-13 512 cap / order / NPOL | closed | GUEST `control-master-513`, `control-master-unordered`, `control-master-duplicate` (RC=12, no output) and `NPOL` cases; JAVA `bootstrapEnforcesTheLegacyMasterTableCapAndOrder`, `batchRejectsInvalidMasterWithRc12AndNoOutput` |
| T-14 `TYPE` after interest | closed | GUEST `malformed` precedence pair OVER<TYPE and TYPE cases; JAVA `typeAmntFundAfterInterest` |
| T-15 JSON ↔ raw ↔ bytes | closed | GUEST/HTTP: every typed response field compared to the independently decoded 96-byte record in `http-json` (7,424 typed + 86 targeted); `statelessEvaluateMatchesTheStatefulResultBytes`, `typedRequestReachesTheContractAndFieldsMatchResultBytes`; mismatch of a response against the authority is fatal in both HTTP modes; negative control `reports/negative-controls/` (`tools/negative_response_control.sh`): a reverse proxy alters only the 8th immediate apply response — one `OCHG` byte of `resultHex`, and for the typed mode a self-consistent `result.charge`/`result.recordHex` — while the request and the persisted `RESOUT` stay correct; both `http-json` and `http-gen` stop at record 7 of `anchors` with `immediate result differs from authority` (`charge` 0 → 100), `persisted_mismatches=0`, receipt valid (`summary.log`, `proxy-*.log`, `report-*.json`) |
| T-16 envelope vs domain boundary | closed | JAVA `envelopeFailuresAreHttp400AndConsumeNoOrdinal` (10^13, `99999-01-01`), `representableDomainFailuresAreHttp200WithLegacyStatus` (10^12 → OVER, 2100-01-01 → DATE, unknown policy → NPOL); GUEST `date-bounds` raw cases (999990101, negative, 0 → DATE) |

## 5. Deviations from the v3 plan (explicit)

Review findings F1–F11 have each been addressed as a *bounded* implementation
area with tests and evidence. After the user's decision "implement and prove
writer takeover and checkpoint resume", D1, D2 and D4 are **implemented** in
the form described below (kept here as D1′/D2′/D4′ so the earlier deviation
record stays legible); D3, D5 and D6 remain deviations.

- **D1′ — `claim`/takeover (implemented).** `POST
  /v1/namespaces/{ns}/generations/{gen}/claim` with the expected manifest (the
  same document the generation was created with). The store locks the
  generation row `FOR UPDATE`, requires `PENDING`, requires the lease to be
  expired against **database time** (`clock_timestamp()`, not the transaction
  start, not the caller's clock) — a live writer is never displaced (409
  `fenced`) —, verifies the pinned identity (the supplied manifest's SHA-256 equals the one
  pinned at creation — which pins the input hashes and counts —, seed/`POLIN`
  hash, parent still current, rate-table identity, and the
  content-derived implementation identity of `ContractBinding`: SHA-256 over
  the sorted class bytes of `insurance/contract/v001/**` and
  `insurance/legacy/codec/**` plus the canonical rate rows, computed from the
  running classpath, not a path or a caller-supplied label), then runs
  `PrefixVerifier` over the durable prefix (section T-11 (a)), and only then
  replaces `writer_id`, sets a strictly higher `fence` (drawn from the global
  `generation_fence_seq`, so consecutive fences of one generation need not be
  adjacent), a fresh lease and `claims + 1` and appends a `generation_claim` row — all in that one transaction. A
  successful claim returns `lastOrdinal`, the checkpoint, the new fence and the
  committed request count; the response carries the new lease. Repeated claims
  chain forward (fence 1 → 2 → 3, every earlier fence rejected); concurrent
  claims have exactly one winner. **Lost claim response (same writer).** A
  claim transaction may commit while its HTTP response is lost. If the *same*
  replacement writer asks again while its own lease is live, the store
  recognises it as the current owner, re-verifies manifest, identity and
  durable prefix, and returns the existing fence and prefix — no ownership
  transition, no fence/`claims` increment, no `generation_claim` row, no wait
  for a heartbeat. This is reconciliation of the caller's own committed claim,
  not a takeover: a *different* writer against a live lease is still 409, and
  ordinary takeover still requires expiry. Missing V3 metadata
  (`contract_identity`, `checkpoint`, entry `chain`) refuses the claim rather
  than downgrading the verification; V4 makes those columns `NOT NULL`. V4
  does not backfill or invent metadata: a database holding pre-V3 rows without
  it fails the migration (the service refuses to start), and a checkpoint
  written in the pre-typed-flag chain format fails the claim closed. No upgrade
  equivalence for an existing database across these formats has been
  demonstrated; the tests start from empty schemas. Old writers whose lease has expired can no
  longer renew, commit, publish or discard **even before any claim** (the
  predicates compare against `clock_timestamp()` inside the same statement), so
  an old process cannot resurrect its lease ahead of a takeover. The file
  store has the same lifecycle (`batch resume`, `batch discard-abandoned`),
  guarded by the OS-level store lock (a live writer holds it; a dead one does
  not) — it has no database clock, so its "expired" is "the lock is free and
  the journal says pending", documented in section 7.
- **D2′ — verified-prefix resume (implemented).** `PrefixVerifier.verify`
  takes the seed, every stored entry, `last_ordinal`, the stored policy state,
  the stored checkpoint and the `ContractBinding`, and fails closed
  (`CheckpointException`, HTTP 409 `checkpoint`) on any of: ordinal gap or
  duplicate, entry count ≠ `last_ordinal`, malformed request/result/successor
  length, a stored result that the pinned contract does **not** reproduce from
  the stored request and the replayed state, a stored successor that is not the
  contract's successor, replayed policy state ≠ persisted state, a per-entry
  typed flag outside {0,1}, typed/raw entry counts ≠ the persisted
  `typed_requests`/`raw_requests` (which must also sum to `last_ordinal`), a
  null chain or checkpoint, or a chain value ≠ the stored
  `generation_entry.chain` / `generation.checkpoint` (`c0 = sha256(seed)`,
  `ck = sha256(c(k−1) ‖ ordinal ‖ request ‖ result ‖ typed-flag ‖
  successor-flag ‖ successor)`; entry, state, ordinal, counters and checkpoint
  are written in one transaction). The resume client
  (`PolicyLedgerService.resumeIndex`, used by `batch resume`, `ServiceKillTest`
  and `DbOutageTest`) reads the committed request stream, requires it to be a
  byte-exact prefix of the pinned input, and continues at `lastOrdinal + 1`,
  so a request whose commit succeeded but whose response was lost is **not**
  re-sent (no `DUPL`/`ORDR` record is appended) and the recovered
  `RESOUT`/`POLOUT` are exactly the uninterrupted stream — proven against the
  batch oracle in every kill scenario and against the archived MVS stage `a`
  (4,097 entries, killed after 2,049, resumed at 2,050) in T-10 (7). A rejected claim leaves the row exactly as
  found (`PENDING`, old fence, `claims` unchanged); nothing is published and
  nothing is rerun from the parent under the name "resume" — that is the
  explicit `discard-abandoned` + new sibling path.
- **D3 — admission order is defined at the servlet filter, per JVM.**
  `AdmissionSequencer` assigns a per-namespace/generation ticket at filter
  entry for `requests`, `requests:raw`, `batch` and `claim` and admits tickets
  to the controller in order; the ticket is returned as `X-Admission-Sequence`.
  The bounded claim is **order preservation for accepted calls**: committed
  ordinals are assigned in ticket order. Ticket and ordinal are equal only for
  runs of accepted single-record envelopes with no rejected calls — a
  rejected envelope (400) consumes a ticket but creates no ordinal, and a
  `batch` call consumes one ticket while committing N contiguous ordinals
  (`mixedRejectedAndBatchCallsPreserveAdmissionOrderWithoutTicketOrdinalEquality`).
  This is *the service's admission order*, observed independently by the
  client from the header. It is **not** TCP/network arrival order, and it is
  not defined across instances; the plan's "server arrival order" is
  therefore met only in this bounded sense.
- **D4′ — partially resolved: abandoned pending generations are parked, not
  auto-discarded (implemented); publication-failure discard remains a
  deviation.** Startup/open no longer discards anything: an expired
  pending generation stays `PENDING` and claimable until an operator either
  claims it or calls `POST …/discard-abandoned` (which also requires the lease
  to be expired and records `abandoned, discarded explicitly: <reason>`;
  entries are kept as evidence). Resolved: takeover, checkpoint resume,
  abandoned-pending retention. **Not resolved (still a deviation from the
  plan's unpublishable-pending state):** a *failed publication* under a live
  lease (pinned-manifest mismatch 422, parent CAS lost 409) still moves the
  generation to `DISCARDED` rather than parking it. This is intentional for
  this round (the writer is alive and the outcome is final) and is left as a
  stated deviation, not claimed as resolved.
- **D5 — `Prefer: return=original`** is not implemented (optional, off).
- **D6 — T-12** is not done (optional, unapproved).

Consequential ambiguity left open on purpose (not decided silently): the
plan does not say whether a claim should *also* be allowed while the old
lease is live but its process is provably dead (e.g. a container that died
without expiring its lease). The implementation waits for expiry in every
case — it never displaces a lease that database time says is live — so the
worst case is one lease interval of unavailability for that generation, never
a double writer. If a faster operator-forced takeover is wanted it needs a
separate decision.

Maven suite: `./mvnw verify` runs the whole reactor. At the previously
reviewed head `8278f7a` the total was 110 = `legacy-codec` 7 + `contract-v001`
21 + `ledger-application` 33 + `ledger-app` 49; with the takeover/resume work
(`cf9aa9b`) it was 144 = 7 + 21 + 39 + 77; with the typed-metadata /
mandatory-metadata / claim-reconciliation review round it is **156 = 7 + 21 +
40 + 88** (the `ledger-app` figure alone is not the suite). The last run is in
section 8.

## 6. What is Java-only (not guest-observed)

Truncation (aligned and partial-record), expected-manifest rejection, fence
and sibling CAS publication races, retry before/after the commit boundary,
published-row INSERT/UPDATE/DELETE guards, fail-closed restart, process kills,
writer takeover, verified-prefix resume, checkpoint corruption controls,
admission ordering and the typed JSON envelope are exercised only by the Java
unit/Testcontainers/subprocess suites (`ledger-application`, `ledger-app`).
The legacy guest has no equivalent surface for them; they are service-layer
guarantees, not legacy parity. The full T-03 age sweep and T-06/T-07 breadth
are also Java-only beyond the guest cases listed above.

## 7. Limitations

- Fresh evidence is bounded to the corpus above (8,704 results × 3 paths plus
  86 targeted results); it is not a proof about an unseen estate, z/OS,
  HLASM, production rules or scale.
- The guest run is single-writer QSAM batch; no power-loss, fsync, or
  cut-power test was performed for either the guest or the Java file store.
- No TK5 credentials were used. `tools/tk5_job.py` adds a `USER=`/`PASSWORD=`
  card only when the optional `TK5_JOB_USER`/`TK5_JOB_PASSWORD` variables are
  set; they were not set for this run, the submitted decks carry no such
  card, and the guest's reader exit stamped its own default
  `USER=IBMUSER,PASSWORD=,` (empty) in the spool. Nothing in this directory
  or in the sanitized fresh-guest evidence archive contains a credential. Full
  JCL decks and spool listings are kept in that archive (attached to the PR
  conversation) rather than copied here to avoid duplicating the evidence in
  the repository.
- The per-routine comparison (section 3) is against the reviewed harness
  evidence at routine-harness commit `5a71041`; if that harness changes its
  fixtures or observed schema, `routine_parity.py` must be re-pinned and
  re-run — nothing is inferred from `expected.json`.
- The child JVMs of `ServiceKillTest`, `HttpBoundaryTest` and `DbOutageTest`
  (`RuntimeEvidence`) run the service from the **Maven test classpath**
  (`target/classes` of the reactor), not from the packaged JAR; the retained
  `runtime/*.json` bind those runs to the source commit (`source_commit`,
  `source_tree_dirty`) and the content-derived `contract_identity`, and are
  not evidence of packaged-JAR execution. The packaged JAR is what
  the batch/HTTP parity reports in `reports/` were produced with.
- The kill, HTTP-boundary and DB-outage tests fault one service process and
  one PostgreSQL container on one host. The outage is `docker stop` (fast
  shutdown) or `docker pause` (SIGSTOP) of the disposable Testcontainers
  database, shorter than the writer lease except in the restart scenario; it
  is not a power cut, a network partition, a replica failover or a disk
  failure. The same-writer continuation after restore is the live lease
  holder retrying; the replacement-writer continuation is the `claim` +
  verified-prefix resume of D1′/D2′. No power-loss/fsync claim is made for
  either store.
- Takeover waits for lease expiry as judged by PostgreSQL `clock_timestamp()`
  on the one database the service uses; there is no external failure
  detector, so a dead writer's generation is unavailable for up to one lease
  interval (default configured lease; 1–6 s in the tests). The file store has
  no database clock: its "abandoned" is "OS lock free + journal pending",
  which is correct on one host/filesystem with working `flock`, and is not
  claimed for network filesystems or across hosts. Its claim history
  (`claims.json`) and generation summary (`generation.json`) are separate
  writes: no atomic cross-file claim-history durability and no power-loss
  behaviour is claimed for the file store. PostgreSQL is the service
  acceptance target.
- The durable-prefix verification recomputes every stored result and
  successor with the running contract and replays the policy state; it
  detects any stored byte that the pinned rules would not have produced, and
  any chain/ordinal inconsistency. It cannot detect a request whose *input*
  bytes were replaced by other well-formed bytes before the crash and whose
  result/state/chain were all recomputed consistently by an attacker with
  database write access — the resume client's byte-exact comparison of the
  committed request stream against the pinned `TXNIN` closes that for the
  batch/resume paths; for a purely interactive HTTP generation there is no
  pinned input beyond the manifest hashes, so that residual case is bounded by
  the manifest's `txnin_sha256` check at publication.
- The implementation identity is the SHA-256 of the contract and codec class
  bytes plus the canonical rate rows as loaded by the running JVM. A different
  JDK's `javac` output for the same sources gives a different identity and
  therefore a refused claim (fail closed, explicit `discard-abandoned` + rerun
  is the operator path); this is intentional but means "same source" is not
  the criterion — "same bytes" is.
- Source-derived oracle agreement (`oracle_agrees`) is a cross-check, not
  independent business-intent validation; no production, estate or
  performance claim is made.

## 8. Reproduce

Last full verification (working directory `samples/insurance/java`, Java
21.0.12, offline Maven) at head `9e86b78f` (= implementation commit `194aedf7`
— typed-metadata binding, mandatory V3 metadata, same-writer claim
reconciliation — plus a test-only `RuntimeEvidence` provenance change and
README wording; no main-source change after `194aedf7`): `./mvnw -o -q
spotless:check`, `./mvnw -o -q checkstyle:check`, `./mvnw -o package` and
`./mvnw -o verify` (156 tests, 0 failures, 0 errors, 0 skipped: `legacy-codec`
7 + `contract-v001` 21 + `ledger-application` 40 + `ledger-app` 88) →
`ledger-app-0.1.0-SNAPSHOT.jar` SHA-256
`91978b63852f44440806d9e442b6f8a298480e5e12cdcf83be7af4fc5b5900e6` on this
machine (identical for `194aedf7` and `9e86b78f`, which differ only in test
source and documentation); an independent build of the same source has a
different JAR hash, which is expected. The parent's independent packaged-JAR
recovery check at `194aedf7` is bound to the parent's own JAR hash in the PR.

The `runtime/` evidence (`ServiceKillTest` 7, `HttpBoundaryTest` 3,
`DbOutageTest` 4) was re-produced at `9e86b78f` with
`./mvnw -o test -pl ledger-app -am -Dtest='ServiceKillTest,HttpBoundaryTest,DbOutageTest'
-Dsurefire.failIfNoSpecifiedTests=false -Dledger.evidence.dir=$PWD/evidence/acceptance/runtime`.
Every `runtime/*.json` now carries `execution` (Maven test-classpath child
JVM, not the packaged JAR), `source_commit` `9e86b78f…`, `source_tree_dirty:
false` (evidence directory excluded from the dirty check) and the
content-derived `contract_identity`
`contract:sha256:742cdcfa1e698308ccc111fbfc6325586c4d9beea1dc6a2530217e18cdf51dcc`;
the checkpoints inside differ from the `cf9aa9b0` files because the chain now
binds the per-entry typed flag. Receipts captured *inside* those files still
show `source_commit: unknown` / `build_identity: classes:…/target/classes`
because the child service is started without `LEDGER_SOURCE_COMMIT` and from
`target/classes` — that is the honest description of how they ran.

The parity reports were **not** regenerated for `194aedf7`/`9e86b78f`: the round changed
no contract, codec, batch or HTTP evaluation path (checkpoint metadata,
claim reconciliation and tests only), so every report in `reports/`,
`routines/`, `reports/negative-controls/` and `../fast/` remains the one
produced at head `cf9aa9b0` with JAR
`2a4615b29aaace17ec0cb756a82d920819de09c52396872961a8dcf01500e883`
(`source_commit` `cf9aa9b0`, `jar_sha256` `2a4615b2…` in each report) and is
labelled as such, not as new output of this head. At `cf9aa9b0` `./mvnw
verify` was 144 tests (7 + 21 + 39 + 77). Those reports cover: 7 batch stages × 5 + 7 `http-gen` + 7 `http-json`
full-corpus stages (8,704 results each), targeted 16/16 × 3 modes (86
results), routines 184/184 × 3 paths, response-only mutation detected in both
HTTP modes, 33 authority negative controls. The archived guest authorities
(A2 archive, A3 `run-01`, targeted authority, routine archive `3c881564…`)
were replayed unchanged — no new guest execution took place for either head.
`runtime/` is written by the Maven test run itself (`ServiceKillTest`,
`HttpBoundaryTest`, `DbOutageTest`: child JVMs on the Maven test classpath,
bound by source/build manifest), not by the packaged JAR.

Working directory `samples/insurance/java`, with the guest already reachable
via the pinned `tools/tk5_setup.sh` / `tk5_start.sh` (no credentials):

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

# Java vs the reviewed per-routine guest observations (archive pinned by SHA-256,
# fixture manifest from the routine-harness checkout at 5a71041, frozen baseline 28790e2)
python3 tools/routine_parity.py --archive <insurance-routines-evidence.tar.gz> \
    --archive-sha256 3c881564cdc03812b9fb7affacb2634d7c5148d3b01aedaa2661a42f0ff553b2 \
    --routine-manifest routines/routine-fixture-manifest-5a71041.json \
    --routine-commit 5a71041743bc8086d47310380b417caf1e47ce08 \
    --baseline <checkout-of-28790e2>/samples/insurance \
    --java "$JAVA_HOME/bin/java -jar $JAR" --out <reports>/routines

# response-only mutation must FAIL both HTTP modes (negative control)
JAVA=$JAVA_HOME/bin/java tools/negative_response_control.sh $JAR $SHA <work>/negative

# authority negative controls (receipt keys, manifests, rates, pinned inputs, RC12 controls)
python3 -m unittest tools/test_parity_authority.py

# F9/F10 + takeover/resume: real service-kill, HTTP-boundary and focused PostgreSQL
# takeover runs with retained runtime evidence (needs the reactor: -am)
./mvnw -o test -pl ledger-app -am -Dtest='ServiceKillTest,HttpBoundaryTest,JdbcTakeoverTest' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dledger.evidence.dir=$PWD/evidence/acceptance/runtime

# file-store takeover/resume lifecycle (batch CLI): kill, then claim + resume in a new process
java -jar $JAR batch resume --store <dir> --namespace <ns> --generation <gen> \
    --polin <polin.bin> --txnin <txnin.bin> --manifest <manifest.json> --out <out-dir>
java -jar $JAR batch discard-abandoned --store <dir> --namespace <ns> --generation <gen> --reason <text>

# PostgreSQL service: claim / explicit discard of an abandoned pending generation
curl -X POST http://127.0.0.1:8080/v1/namespaces/<ns>/generations/<gen>/claim \
    -H 'Content-Type: application/json' -d '{"manifestBase64":"<expected manifest, base64>"}'
curl -X POST http://127.0.0.1:8080/v1/namespaces/<ns>/generations/<gen>/discard-abandoned \
    -H 'Content-Type: application/json' -d '{"reason":"<text>"}'

# actual PostgreSQL outage mid-generation (docker stop / pause of the test-owned container)
./mvnw -o test -pl ledger-app -Dtest=DbOutageTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dledger.evidence.dir=$PWD/evidence/acceptance/runtime
```

## 9. Retained runtime evidence (`runtime/`)

One JSON per test method, written by the test itself only when
`-Dledger.evidence.dir` is set (schemas `insurance-java-service-kill-v2`,
`insurance-java-http-boundary-v1`, `insurance-java-db-outage-v1`), plus the
surefire summaries (`insurance.app.ServiceKillTest.txt` 7/7,
`insurance.app.HttpBoundaryTest.txt` 3/3, `insurance.app.DbOutageTest.txt`
4/4, `insurance.app.persistence.JdbcTakeoverTest.txt` 23/23). Each file records the Java runtime, the PostgreSQL image,
and per scenario: the fault spec, the child's exit code (137 for a halt) and
its last stderr line, the generation status / `last_ordinal` / entry count
read directly from PostgreSQL after the fault, the restart outcome, the retry
status, and for published generations the `RESOUT` SHA-256 against the batch
oracle. The `db-outage.*` files additionally record `db_before_outage` (status,
`last_ordinal`, entry count and entry SHA-256, output presence, current
pointer, lease expiry), the `outage` block (container state `exited`/paused,
the refused direct JDBC connect, the failed request's status and body, the
heartbeat failure), `restore` (container `running`, direct connect ok),
`db_after_restore`, then `retry` / `rerun` / `publication` with
`matches_oracle`. The `service-kill.*` v2 files record, per scenario, the DB
row read directly after the kill (`db_after_kill`: status, `last_ordinal`,
entry count, fence, `claims`, `writer_id`, lease expiry, checkpoint, current
pointer), the rejected claim while the lease was live where applicable
(`claim_while_lease_live`, with `db_untouched`), the `claim` response
(`lastOrdinal`, `fence`, `claims`, `checkpoint`, `committedRequestsSha256`),
`resumed_from_ordinal`, `applied_on_resume`, `db_after_resume`, and the final
`polout_sha256`/`resout_sha256` with `matches_oracle` (batch oracle) — for
the A2 corpus scenario also `equals_emulation` and `equals_archived_mvs`
against the receipt-validated archived stage `a` (`authority` block with all
seven hashes and the canonical rate identity). The frozen-writer file records
the old writer's `requests:raw`/`requests`/`batch`/`publish`/`discard`
responses after `SIGCONT` (`old_writer_after_wake`) and the row before/after.
The corrupt-prefix file records the direct DB mutation, the rejected claim
(`claim_rejected`), the unchanged row, the explicit discard and the rerun. Only `localhost:<ephemeral port>` appears as an address; no
credentials (Testcontainers credentials are not recorded). These are Java-only
controller evidence (section 6), not guest parity.
