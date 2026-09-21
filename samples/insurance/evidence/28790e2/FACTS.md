# Verified insurance assembler demonstration

Technical handoff for an engineer who does not use HLASM. These are measured
facts for review, not a production or actuarial claim.

Source revision: `28790e2a3f343980f15d8bffd55e5811fbfa2ee5`.
PR: https://github.com/COG-GTM/cc370/pull/1
Session: https://app.devin.ai/sessions/8445e980914147f6a429a6b7d910d8a6
Validation date: 2026-09-20 UTC.

## Start with one returned calculation

The recorded run reads A001 from actual MVS `RESOUT`, not from generated
expectations. Policy `00000001` returns status `OKAY`:

| Value | Actual returned cents | Dollars |
|---|---:|---:|
| Interest | 3259 | 32.59 |
| Cash | 113259 | 1132.59 |
| Surrender charge | 3964 | 39.64 |
| Surrender quote | 109295 | 1092.95 |
| Death quote | 1000000 | 10000.00 |

The starting cash is $1,000.00, the premium is $100.00, the face amount is
$10,000.00, and the loan is zero. Actual days from 2024-01-01 to 2025-01-01
are 366. This synthetic contract uses a fixed 365-day divisor, a 325-basis-point
period-end annual rate and a 350-basis-point surrender fee:

```text
interest = half_up(100000 * 325 * 366 / 3650000) = 3259 cents
cash = 100000 + 3259 + 10000 = 113259 cents
charge = half_up(113259 * 350 / 10000) = 3964 cents
surrender = max(0, 113259 - 3964 - 0) = 109295 cents
death = max(0, max(1000000, 113259) - 0) = 1000000 cents
```

These are quotes; a death quote does not settle a claim. Rates are synthetic.
See `source/samples/insurance/docs/formulas.md`, rules BR-007 through BR-010,
and `runtime/ifox-iewl/anchors/{resout.bin,receipt.json,comparison.json}`.
All three executable paths return the same bytes for this example.

## What actually runs

```text
Ubuntu Linux
  Hercules 4.9.1 emulates a System/370
    TK5 Update 5 runs MVS 3.8J
      IFOX00 assembles; IEWL links; MVS executes S/370 load modules
```

xterm is the Linux terminal used to submit jobs and display captured results.
The insurance instructions execute inside MVS. Python moves records,
validates receipts and compares fields; it does not execute the application.

The application has seven assembler modules: INSBAT, INSSMOK, INSCALC,
INSVAL, INSPACK, INSDATE and INSRATE. It uses QSAM sequential files,
24-bit addressing, R13 save areas, CP037 text, packed-decimal cents and
big-endian binary dates/sequences. Policy, transaction and result records
are exactly 128, 40 and 96 bytes respectively.

The official guest archive and emulator identity are in
`runtime/*-provenance/guest.json`. The guest distribution is
https://www.prince-webdesign.nl/tk5. Hercules is QPL 1.0
(https://sdl-hercules-390.github.io/html/herclic.html); IBM guest software
has separate terms.

## Three independently loaded executable paths

1. Seven real IFOX00 assemblies, two IEWL links, and INSSMOK.
2. Host as370 object decks transferred as binary data, linked by guest IEWL.
3. Host as370 plus host ld370; IEBCOPY imports its load modules into MVS.
   This path does not use IEWL.

Each path runs anchors, A, A replay, B and B replay. A replay and B consume
the actual validated stage-A master. B replay consumes the actual validated
stage-B master. Generated expectations are only comparison targets.

The corpus has 256 policies, 8,194 primary transactions, 500 replay requests
and ten anchors: 8,704 result records per executable path. All primary
validation stages compare every declared field and all remaining bytes.
Receipts bind actual inputs, outputs, build, guest and rate manifests.
The comparison report records every difference rather than stopping at one.
Cross-backend master and result files are byte-identical for all five stages.

All seven object decks match IFOX00 over columns 1–72 of 81 non-END cards.
Sequence columns and END translator metadata are excluded by the documented
convention. This is build evidence; the separate batch runs prove execution.
See `runtime/decks/deck-comparison.json` and `VERIFICATION.json`.

## Show the rejection, then the unchanged master

| Request or fault | Observed behavior |
|---|---|
| Same sequence and same request | `DUPL`; no second mutation |
| Same sequence with changed request | `CNFL`; rejected |
| Older sequence | `ORDR`; rejected |
| Invalid packed-decimal digits | `PACK`; rejected |
| Invalid calendar date | `DATE`; rejected |
| Duplicate, malformed or 513-record master | RUN RC 12; empty output |
| Missing program | S806; runner rejects |
| Competing writer | Lock prevents a second controller |
| Half of stage B delivered | Comparison rejects; no publication |

The five transaction rejections preserve the committed 128-byte master.
Invalid-master controls also re-export the input to prove it did not change.
The partial-B control intentionally reports one acceptance error and 3,039
differences. The published checkpoint stays unchanged, and a separate
controller reruns complete B and B replay successfully.

This tests persisted controller restart and incomplete input delivery.
It does not claim a forced emulator crash, power-loss recovery, or
multi-writer throughput. See `runtime/*-controls/controls.json`,
`runtime/*-recovery/recovery.json`, and the partial-run comparison reports.

## Evidence and corrections

The bundle retains console, printer, punch, JCL, binary cards, AWS tapes,
inputs, outputs, receipts, comparisons, source, host artifacts and hashes.
`SHA256SUMS` verifies the delivered files. `REDACTIONS.json` explicitly maps
private original hashes to delivered copies: only JOB-card passwords in
`submitted.ebcdic` are masked, preserving their byte lengths. Raw originals
remain on the originating private VM. No program, data, RC, spool text,
comparison result or generation metadata is rewritten.

Build manifests still contain original raw-evidence hashes. A redacted card
therefore matches the delivered manifest, not the private original card hash;
use `REDACTIONS.json` to identify that deliberate difference. Receipt and
provenance files themselves are unchanged.

Corrections made during integration were binary-tape mount/unit handling,
IFOX macro-concatenation block size, authenticated job submission, strict
completion checking, exhaustive differences and stage-generation selection.
An initial recording attempt used the wrong local demo password and was
rejected before any program executed. That failed submission is preserved in
`initial-rejected-submission`; the final recording uses a fresh evidence
directory and fresh jobs after consulting the bundled TK5 user manual.

The sample host checks passed 23 tests and deterministic regeneration of 21
frozen artifacts. Host tool warning policies were not weakened. This does
not claim a full compiler/toolchain regression run: the earlier full
`make tools -j4` attempt stopped on ar370's `-Werror=format-truncation`.
The three required tools built and were used successfully. GitHub reports
no checks on this PR despite the repository's build workflow; CI remains
unverified. The attached log records local checks.

## Reproduce

Read `source/samples/insurance/docs/integration.md`, install with the
versioned setup helper, export the exact guest macros, and start Hercules in
its loopback-only namespace. Supply the local guest credentials through the
environment; do not share authenticated card originals.

Run `tools/tk5_demo.py` with a new evidence directory and dataset prefix.
Do not run a second emulator against the same DASD. Start the guest again
after a VM restart; a filesystem snapshot does not retain a live emulator.
Use `--interactive` for the recorded chapter-by-chapter walkthrough.

The repository blueprint suggestion pins the tested source revision and runs
the versioned setup and macro-export helpers. It is pending user approval.
`BLUEPRINT-PLAN.md` records the proposal; it is not evidence that a new snapshot
has already been built. The recording is delivered separately to keep this
archive compact; `RECORDING.json` supplies URLs and SHA256 hashes for the full
12-minute-23-second capture and the accelerated 55-second annotated highlights.
The full capture joins the original recording segments without changing their
timing; the highlights are not a runtime-duration measurement.

All records and business rules are synthetic. No production data, insurer
parity, actuarial certification, production modernization or Java
implementation is claimed. The next review should inspect the raw receipts
and partial-run rejection before using these facts in presentation material.
