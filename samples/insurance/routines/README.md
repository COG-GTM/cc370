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
| `harness/fixtures.py` | Builds `fixtures/v1/{cases.bin,expected.json,manifest.json}` (versioned, hashed); per-routine logical input/output map and WORK byte classes; `load()` validates schema, version, counts, identity/order, byte classes, logical I/O and every declared source/copy/rates/driver/harness hash before anything consumes the fixtures; `check` is non-mutating. |
| `harness/compare.py` | Strict comparator: structure, harness, ABI, stable, scratch, unasserted classes; every mismatch preserved. |
| `harness/controls.py` | Negative controls: comparator (corrupted synthetic captures), fixtures (mutated manifest/inputs/hashes must be rejected by `load()`), coverage (mutated receipts/comparisons/observations must never yield PASS). |
| `harness/run_guest.py` | Assembles/links the driver + routines on three paths and runs the load module under Hercules/TK5 MVS 3.8j, wrapping the frozen `tools/tk5.py` controller. |
| `harness/run_jobs.py` | Job-level `INSBAT`/`INSSMOK` cases, five-stage regression (8704 results per path) and frozen controls. |
| `harness/observe.py` | Raw capture -> `observed.json`; validates record count, identity, order, duplicates and unknown IDs against the fixtures first (an invalid capture yields no observations, only `problems` + `quarantined`), then splits each record into `business` / `abi` / `scratch` / `unasserted`. |
| `harness/contract.py` | Emits `contract/contract.json` and `contract/coverage.json`; `coverage()` fails closed: every consumed evidence folder must pass `validate_run()` (receipt schema/backend/jobs/RCs, capture hash and count, comparison and observation identity/order/counts, no global findings) or every rule is `FAIL`. |
| `harness/bundle.py` | Compact committed evidence (`evidence/`) + full raw archive with `RECEIPTS.json`. |
| `tests/test_routines.py` | Host tests (27): layout vs assembled symbols, hand numbers vs model, fixture determinism and `load()` rejection controls, non-mutating check, per-routine classification, comparator controls, observation rejection, fail-closed coverage against the committed evidence. Not runtime proof. |
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
| `INSDATE` | WORK | `WDATE` | `WVALID`, `WYEAR`, `WMD`, `WORD` | 0 (`SR 15,15` at `DTEXIT`; validity is `WVALID`) |
| `INSRATE` | WORK | `TDATE`, `WAGE` | `WRATE`, `WFEE` | 0 |
| `INSVAL` | WORK | `SREC` | `WVALID`; `WDATE`, `WYEAR`, `WMD`, `WORD` as INSDATE scratch | 0 valid / 8 invalid |
| `INSCALC` | WORK | `SREC`, `TREC` | `SREC` (on OKAY only), `OREC`; date/rate/days scratch | 0 (outcome is `OSTAT`) |

R15 is asserted for every routine: as the business result for `INSPACK`/`INSVAL`, and as
the fixed ABI value 0 for `INSDATE`/`INSRATE`/`INSCALC`.

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

`contract.json` (`byte_classes`, `logical_io`) classifies every WORK byte **per routine**;
a field that is a logical output of one routine is scratch or preserved storage for
another (`WDATE`/`WYEAR`/`WMD`/`WORD` are INSDATE's outputs but INSVAL/INSCALC scratch):

* **output** - the routine's logical result; Java must reproduce it
  (`INSDATE`: `WVALID`, `WYEAR`, `WMD`, `WORD`; `INSRATE`: `WRATE`, `WFEE`;
  `INSVAL`: `WVALID`; `INSCALC`: `SREC` + `OREC`; `INSPACK`: none, R15 only).
* **input** - the routine's declared inputs, which it must leave unchanged unless they
  are also outputs (`INSDATE`: `WDATE`; `INSRATE`: `TDATE`, `WAGE`; `INSVAL`: `SREC`;
  `INSCALC`: `TREC`, and `SREC` which is also an output).
* **preserved** - unrelated WORK storage the routine must not touch. Asserted as
  assembler preservation/ABI evidence only; Java has no such bytes and must not emulate
  them.
* **scratch** - assembler-specific fields the routine writes on the way to its result;
  asserted against the model so the guest evidence is complete, *not* a Java requirement
  (`INSVAL`: `WDATE`, `WYEAR`, `WMD`, `WORD`; `INSCALC`: those plus `WOLDORD`, `WISSYR`,
  `WISSMD`, `WAGE`, `WDAYS`, `WRATE`, `WFEE`, `WBEFORE`).
* **unasserted** - implementation-defined arithmetic scratch, recorded only
  (`INSCALC`: `WNUM`, `WPROD`).

The comparator reports `output`/`input` mismatches and result-R15 mismatches as `stable`
findings, `preserved` mismatches and fixed-R15 mismatches as `abi`, and `scratch`
mismatches as `scratch`. `observed.json` keeps the same split per record: `business`
(outputs, result R15, input preservation), `abi` (registers, save chain, guards, unrelated
WORK), `scratch`, `unasserted`.

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
(`contract/coverage.json`, `valid: true`; each folder passed `validate_run`).
Unmeasured paths are listed there explicitly:
`INSBAT`/`INSSMOK` internal control flow beyond RC and dataset bytes; `INSCALC` with
`TDATE` before 19000101 and a valid state (unreachable through `INSVAL`); `INSBAT`
RC16 OPEN failure (no DD-level fault injected).

Required frozen targets pass unchanged (`evidence/frozen-make.log`):
`make lint`, `make test` (23 tests), `make core`, `make batch MACLIBS=...`,
`make verify-golden` (21 artifacts).

## Notable hand-worked observations

* Quotient overflow with frozen rates: `SCASH=99999999999`, issue/state `19000101`,
  transaction `20250101` -> 45656 days at 325 bps -> interest 406526027393 > MAXAMT ->
  `OSTAT=OVER`, `OINT=0`, state rolled back (`CA067`). `CA069` is the neighbouring
  one-day case: 1 day at 125 bps on MAXAMT (3424658 cents) already pushes cash over
  MAXAMT after the add.
* Exact half-cent ties round half-up (`CA-TIE`, five cases).
* Death quote `D` computes `ODEATH = max(face, cash) - loan` without closing or settling
  the policy (no payout: face and loan unchanged, cash moves only by the posted interest),
  but it is otherwise a normal accepted transaction: interest is posted to `SCASH`, and
  `SDATE`/`SSEQ`/`SLAST` advance to the quote transaction (`CA-D`).
* `INSRATE`'s direct domain is the binary `TDATE` and `WAGE` only; it does not validate
  the date (`RT-DOMAIN`).
* `INSDATE` returns R15=0 (`SR 15,15` at `DTEXIT`); validity is `WVALID` (`DT-*`).
* F-sign packed inputs echo as C-sign outputs; negative zero `D0` is accepted as zero
  (`CA-RAW`, `PK-SIGN`).

## Java handoff

Map each callable routine to a pure component on its **logical inputs and outputs** only
(`contract.json` -> `callable[].logical_io` and `java`); the `preserved`, `scratch`,
`unasserted` classes and every ABI assertion are assembler-specific and must not leak
into Java.

| Routine | Pure Java shape | Inputs | Outputs | Reference cases |
|---------|-----------------|--------|---------|-----------------|
| `INSPACK` | `boolean isValidPacked(byte[7])` | raw bytes | boolean (R15 0/8) | `PK-*` |
| `INSDATE` | `Optional<CivilDate> parse(int yyyymmdd)` | `WDATE` | `WVALID` (0/8), `WYEAR`, `WMD`, `WORD` (days since 19000101); R15 is always 0 and carries no result | `DT-*` |
| `INSRATE` | `Rate rateFor(int yyyymmdd, int age)` | `TDATE`, `WAGE` | `WRATE` bps, `WFEE` bps (fee 0 at age >= 10) | `RT-*` |
| `INSVAL` | `boolean isValidState(State)` | 128-byte `SREC` | boolean (R15 0/8, mirrored in `WVALID`); `SREC` unchanged | `VL-*` |
| `INSCALC` | `Outcome apply(State, Transaction)` | `SREC`, `TREC` | `OREC` (status, age, rate, fee, cash, surrender, death, loan, interest, charge, `V001`), new `SREC` on OKAY, unchanged `SREC` otherwise; `TREC` unchanged | `CA-*` |

**Authority.** The guest observations are the acceptance authority for Java:
`evidence/*/observed.json` carries, per case, the actual input bytes the routine was
called with (`input.work_before`, `input.arg`, `input.fields`) and the actual decoded
outputs it produced (`business.outputs`, `business.r15`, `business.arg_after`), all from
real MVS execution of the frozen routines. `fixtures/v1/expected.json` is the harness's
*independent* hand-worked/model expectation used to judge the guest; it agreed with the
guest on all 184 cases, but Java must be compared with the observed values, not with
`expected.json`. Both files are byte-level and CP037/packed-aware; Java may decode them
with its own codec. Java has no WORK area: do not reproduce `preserved` bytes, `scratch`
fields, save areas, registers or addresses.
`INSBAT` maps to a batch loop over `INSVAL` + `INSCALC` with the strictly-increasing
policy table, the 512 cap and the `NPOL` override for unknown IDs; check it against
`evidence/jobs-*/jobs.json` stage receipts and the frozen golden corpus, not against
assembler internals.

## Replay

Host-only (no guest):

```sh
make -C samples/insurance/routines test            # lint + host build of INSDRV + 27 host tests (after make -C samples/insurance core)
make -C samples/insurance/routines fixtures-check  # non-mutating: tracked fixtures vs in-memory render + all declared input hashes
make -C samples/insurance/routines fixtures        # regenerate fixtures/v1 (deterministic; only after an intentional case/model change)
make -C samples/insurance/routines controls        # comparator + fixture + coverage negative controls -> evidence/controls.json
make -C samples/insurance/routines contract        # contract + coverage from the committed evidence (fails closed)
make -C samples/insurance/routines derive          # re-decode/re-compare retained raw captures under $EVIDENCE with the current harness
```

`derive` never rewrites `capture.bin` or `run.json`: those are the guest receipt. When only
the comparator/decoder/docs change, the raw captures keep their original hashes and
`comparison.json`/`observed.json` are regenerated; `RECEIPTS.json -> derivation` and each
file's `harness_identity` record which harness revision produced them (and
`*.orig.json` keep the versions written at run time), so a regenerated artifact never
implies the guest ran the revised harness.

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
* The comparator's negative controls corrupt *synthetic* captures, and the fixture and
  coverage controls mutate *temporary copies*; they prove the harness rejects bad inputs,
  not any guest fault.
* The committed `comparison.json`/`observed.json` were re-derived from the retained
  captures by the harness revision in their `harness_identity`; the guest ran the driver
  and cases identified by `run.json` (`cases_sha256` unchanged; only manifest metadata and
  the host harness changed).
* `host_rejects_half_delivered_txnin` is a deliberate incomplete-input control: the
  guest runs to RC0 on the truncated input and the host controller rejects the run;
  the comparison difference count inside it is expected, not a production failure.
* The guest has no trailer integrity detection; empty vs truncated input is a host
  distinction only.
* Condition code, R14 high byte and `WNUM`/`WPROD` are undefined and never asserted.
* No emulator crash, power loss or customer production equivalence is claimed.
