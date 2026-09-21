# Per-routine runtime feedback harness

Direct guest-runtime observations for the five callable assembler library routines
(`INSPACK`, `INSDATE`, `INSRATE`, `INSVAL`, `INSCALC`) plus job-level observations
for the two executable programs (`INSBAT`, `INSSMOK`). Everything here is additive:
`src/`, `macros/`, `copy/`, `golden/v1`, `tests/`, `tools/` and the toolchain are
unchanged, and the frozen baseline (`28790e2`) remains the behavioural authority.

The demo application is synthetic. Expectations are hand-worked or source-derived and
labelled as such; nothing here is a real insurer's rule set.

## Layout

| Path | Purpose |
|------|---------|
| `driver/INSDRV.asm` | Guest caller driver. Reads FB512 case records, calls the real linked routine (`L 15,=V(name)` / `BALR 14,15`), captures registers, save areas, guards and WORK before/after into FB1536 records. Never interprets results. |
| `harness/layout.py` | WORK/case/capture field schema (offsets, widths, CP037 / binary / packed / raw), decoders, symbol-table reader. |
| `harness/model.py` | Independent expectation model (Python `int` + `datetime.date`). Not generated from assembler output. |
| `harness/cases.py` | 184 cases with hand-worked literals and rule IDs. |
| `harness/fixtures.py` | Builds `fixtures/v1/{cases.bin,expected.json,manifest.json}` (versioned, hashed). |
| `harness/compare.py` | Strict comparator: structure, harness, ABI, stable, scratch, unasserted classes; every mismatch preserved. |
| `harness/controls.py` | Negative comparator controls (deliberate corruption of synthetic captures). |
| `harness/run_guest.py` | Assembles/links the driver + routines on three paths and runs the load module under Hercules/TK5 MVS 3.8j, wrapping the frozen `tools/tk5.py` controller. |
| `harness/run_jobs.py` | Job-level `INSBAT`/`INSSMOK` cases, five-stage regression (8704 results per path) and frozen controls. |
| `harness/observe.py` | Raw capture -> `observed.json` (decoded stable fields, ABI observations, unasserted scratch). |
| `harness/contract.py` | Emits `contract/contract.json` and `contract/coverage.json`. |
| `harness/bundle.py` | Compact committed evidence (`evidence/`) + full raw archive with `RECEIPTS.json`. |
| `tests/test_routines.py` | Host tests: layout vs assembled symbols, hand numbers vs model, fixture determinism, comparator controls. Not runtime proof. |
| `fixtures/v1/` | Versioned fixtures and manifest. |
| `contract/` | Machine-readable contract and coverage. |
| `evidence/` | Observed results, receipts and provenance from the measured runs. |

## Entry points (what is and is not callable)

Callable library routines, all entered through `INSENT` (`STM 14,12,12(13)`, own 18F save
area chained at `4(SAVE)`/`8(caller)`) and left through `INSRET` (`L 13,4(13)`,
`L 14,12(13)`, `LM 0,12,20(13)`, `BR 14`):

| Routine | R1 | Reads | Writes | R15 |
|---------|----|-------|--------|-----|
| `INSPACK` | seven packed bytes (not WORK) | 7 bytes at R1 | nothing | 0 valid / 8 invalid nibble |
| `INSDATE` | WORK | `WDATE` | `WVALID`, `WYEAR`, `WMD`, `WORD` | always 0 (validity is `WVALID`) |
| `INSRATE` | WORK | `TDATE`, `WAGE` | `WRATE`, `WFEE` | always 0 |
| `INSVAL` | WORK | `SREC` | `WVALID`, `WDATE`, `WYEAR`, `WMD`, `WORD` | 0 valid / 8 invalid |
| `INSCALC` | WORK | `SREC`, `TREC` | `SREC` (on OKAY only), `OREC`, scratch | always 0 (outcome is `OSTAT`) |

Job-level programs (tested only through JCL, RC and datasets): `INSBAT` (`POLIN` FB128,
`TXNIN` FB40, `POLOUT` FB128, `RESOUT` FB96; RC 0 / 12 / 16) and `INSSMOK` (RC 0 / 12).

Not entry points: `INSENT`, `INSRET`, `INSWORK`, and every internal label.

Undefined and never asserted: the condition code at return, the high byte of R14 after
`BALR` (ILC/CC/mask), `WNUM`/`WPROD` arithmetic scratch after `INSCALC`.

Asserted ABI (separately from business values): R0-R13 restored to the seeded
sentinels, low 24 bits of R14 equal the return address, R1 pointed at the right target,
caller and callee save areas chained and unchanged, guard bytes around WORK, the
argument and the save area intact, no byte of WORK outside the declared writes changed.
Addresses are compared relative to the captured base, never as fixed values.

## Byte classes

`contract.json` classifies every WORK byte per routine:

* **stable** - business-visible; Java must reproduce (state, transaction, result fields,
  and bytes the routine must leave alone).
* **scratch** - written by the assembler and asserted against the model so the guest
  evidence is complete; *not* a Java requirement (`WDATE`, `WYEAR`, `WMD`, `WORD`,
  `WVALID`, `WOLDORD`, `WISSYR`, `WISSMD`, `WAGE`, `WDAYS`, `WRATE`, `WFEE`, `WBEFORE`).
* **unasserted** - implementation-defined arithmetic scratch, recorded only
  (`WNUM`, `WPROD`).

Negative zero is numerically zero but its raw bytes are significant: the comparator
compares decoded values *and* raw bytes, and `observed.json` carries both.

## Measured scope (this PR)

Routine level, real MVS 3.8j execution of the linked `INSDRV` load module, 184 cases,
0 findings on each path:

| Path | Assemble | Link | Run step | Result |
|------|----------|------|----------|--------|
| `ifox-iewl` | guest IFOX00 | guest IEWL | `DRV=0000` | 184/184 PASS |
| `as370-iewl` | host as370 | guest IEWL | `DRV=0000` | 184/184 PASS |
| `as370-ld370` | host as370 | host ld370 (+IEBCOPY import) | `DRV=0000` | 184/184 PASS |

IFOX00 vs as370 object decks (non-END cards, columns 1-72, matched macro library) are
identical for `INSPACK`, `INSDATE`, `INSRATE`, `INSVAL`, `INSCALC`, `INSDRV`
(`evidence/ifox-iewl/decks-deck-comparison.json`). Build parity is reported separately
from execution evidence and is not runtime proof.

The three raw captures hash identically (`evidence/RECEIPTS.json`,
`captures_identical_across_paths`): same object code, same region layout. Address
assertions are relocation-relative regardless.

Job level, each path independently chaining its own observed validated masters:
standalone `INSSMOK`, empty transactions, exactly 512 accepted masters, no masters
(all `NPOL`), known + unknown policy, 513th master RC12, misordered master RC12,
duplicate master RC12, invalid master RC12, valid-then-invalid master RC12, host
rejection of a half-delivered `TXNIN`, the five-stage regression (anchors 10, A 4097,
A-replay 244, B 4097, B-replay 256 = 8704 results) and the frozen transport controls -
all PASS on `ifox-iewl`, `as370-iewl` and `as370-ld370` (`evidence/jobs-*/jobs.json`).

Rule coverage: 59 rules, 184 cases, all measured on all three paths
(`contract/coverage.json`). Unmeasured paths are listed there explicitly:
`INSBAT`/`INSSMOK` internal control flow beyond RC and dataset bytes; `INSCALC` with
`TDATE` before 19000101 and a valid state (unreachable through `INSVAL`); `INSBAT`
RC16 OPEN failure (no DD-level fault injected).

Required frozen targets pass unchanged (`evidence/frozen-make.log`):
`make lint`, `make test` (23 tests), `make core`, `make batch MACLIBS=...`,
`make verify-golden` (21 artifacts).

## Notable hand-worked observations

* Quotient overflow with frozen rates: `SCASH=99999999999`, issue/state `19000101`,
  transaction `20250101` -> 45656 days at 325 bps -> interest 406526027393 > MAXAMT ->
  `OSTAT=OVER`, `OINT=0`, state rolled back (`CA069`).
* Exact half-cent ties round half-up (`CA-TIE`, five cases).
* Death quote `D` computes `max(face, cash) - loan` and leaves the state untouched
  (`CA-D`).
* `INSRATE`'s direct domain is the binary `TDATE` and `WAGE` only; it does not validate
  the date (`RT-DOMAIN`).
* `INSDATE` never returns a non-zero R15; validity is `WVALID` (`DT-*`).
* F-sign packed inputs echo as C-sign outputs; negative zero `D0` is accepted as zero
  (`CA-RAW`, `PK-SIGN`).

## Java handoff

Map each callable routine to a pure component on its **stable** fields only; the ABI
and scratch classes are assembler-specific and must not leak into Java.

| Routine | Pure Java shape | Inputs (stable) | Outputs (stable) | Reference cases |
|---------|-----------------|-----------------|------------------|-----------------|
| `INSPACK` | `boolean isValidPacked(byte[7])` | raw bytes | boolean (R15 0/8) | `PK-*` |
| `INSDATE` | `Optional<CivilDate> parse(int yyyymmdd)` | `WDATE` | valid flag; year, month-day, ordinal since 19000101 | `DT-*` |
| `INSRATE` | `Rate rateFor(int yyyymmdd, int age)` | `TDATE`, `WAGE` | rate bps, fee bps (fee 0 at age >= 10) | `RT-*` |
| `INSVAL` | `boolean isValidState(State)` | 128-byte state | boolean (R15 0/8) | `VL-*` |
| `INSCALC` | `Outcome apply(State, Transaction)` | `SREC`, `TREC` | `OREC` (status, age, rate, fee, cash, surrender, death, loan, interest, charge, `V001`), new `SREC` on OKAY, unchanged `SREC` otherwise | `CA-*` |

Use `fixtures/v1/expected.json` (`work_before`, `arg`, `decoded_after`, `r15`) as the
input/expected set and `evidence/*/observed.json` (`stable`, `r15`) as the guest-observed
set. Both are byte-level and CP037/packed-aware; Java may decode them with its own codec.
`INSBAT` maps to a batch loop over `INSVAL` + `INSCALC` with the strictly-increasing
policy table, the 512 cap and the `NPOL` override for unknown IDs; check it against
`evidence/jobs-*/jobs.json` stage receipts and the frozen golden corpus, not against
assembler internals.

## Replay

Host-only (no guest):

```sh
make -C samples/insurance/routines test          # lint + host build of INSDRV + host tests (after make -C samples/insurance core)
make -C samples/insurance/routines fixtures      # regenerate fixtures/v1 (deterministic)
make -C samples/insurance/routines controls      # negative comparator controls
make -C samples/insurance/routines contract OBSERVED="<dir> ..."
```

Real guest (private TK5 at `~/mvs-demo/mvs-tk5`, one writer per DASD set, unique prefixes).
`TK5_JOB_USER` / `TK5_JOB_PASSWORD` must be in the environment: without them the
job card carries no `USER=`, the job runs as the RAKF default user `PROD`, and every
`DISP=(NEW,CATLG|KEEP|PASS)` allocation fails with `IEF197I SYSTEM ERROR DURING
ALLOCATION` (often preceded by `RAKF0005`/`RAKF000A ... DATASET`) before anything runs.
Never print, commit or copy the values into evidence:

```sh
cd samples/insurance/routines/harness
python3 run_guest.py --backend ifox-iewl   --evidence ~/mvs-demo/routines-evidence/ifox-iewl   --prefix INSR.I1 --maclib ~/mvs-demo/export/ascii
python3 run_guest.py --backend as370-iewl  --evidence ~/mvs-demo/routines-evidence/as370-iewl  --prefix INSR.H1 --maclib ~/mvs-demo/export/ascii
python3 run_guest.py --backend as370-ld370 --evidence ~/mvs-demo/routines-evidence/as370-ld370 --prefix INSR.L1 --maclib ~/mvs-demo/export/ascii
python3 observe.py --capture ~/mvs-demo/routines-evidence/ifox-iewl/capture.bin --out ~/mvs-demo/routines-evidence/ifox-iewl/observed.json
python3 run_jobs.py --backend ifox-iewl   --evidence ~/mvs-demo/routines-evidence/jobs-ifox-iewl   --prefix INSJ.I2 --maclib ~/mvs-demo/export/ascii
python3 run_jobs.py --backend as370-iewl  --evidence ~/mvs-demo/routines-evidence/jobs-as370-iewl  --prefix INSJ.H2 --maclib ~/mvs-demo/export/ascii
python3 run_jobs.py --backend as370-ld370 --evidence ~/mvs-demo/routines-evidence/jobs-as370-ld370 --prefix INSJ.L2 --maclib ~/mvs-demo/export/ascii
python3 contract.py --out ../contract --observed ~/mvs-demo/routines-evidence/{ifox-iewl,as370-iewl,as370-ld370}
python3 bundle.py --evidence ~/mvs-demo/routines-evidence --out ../evidence --archive ~/insurance-routines-evidence.tar.gz
```

`Guest.job()` creates JCL files exclusively; use a fresh evidence directory (or prefix)
for a rerun. Verify an archive offline with the per-file SHA-256s in `RECEIPTS.json`.

## Limitations

* Host tests, assembly, linking and deck parity are not runtime proof; only
  `run.json` / `jobs.json` receipts with guest job IDs and step RCs are.
* The comparator's negative controls corrupt *synthetic* captures; they prove the
  comparator, not any guest fault.
* `host_rejects_half_delivered_txnin` is a deliberate incomplete-input control: the
  guest runs to RC0 on the truncated input and the host controller rejects the run;
  the comparison difference count inside it is expected, not a production failure.
* The guest has no trailer integrity detection; empty vs truncated input is a host
  distinction only.
* Condition code, R14 high byte and `WNUM`/`WPROD` are undefined and never asserted.
* No emulator crash, power loss or customer production equivalence is claimed.
