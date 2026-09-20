# Synthetic insurance batch integration sample

This is a deliberately bounded System/370 assembler application for studying
insurance calculations and mainframe storage mechanics together. Its rules,
records, and policyholders are invented. It is **not production insurer source,
data, actuarial advice, or a representation of production equivalence**.
No modernization implementation is included.

`cc370` is the appropriate home: the sample consumes its host assembler and
linker and targets MVS 3.8j. It is isolated from toolchain implementation and
from `mvs38src`'s historical IBM recovered-source corpus.

## Validation status

The host milestone assembles all seven modules and links `INSSMOK` and
`INSBAT` at RC 0 with the supplied TK5 Update 5 SYS1.MACLIB export.
`INSSMOK` and all five shared modules also build without system macros.
**Guest assembly, execution, restart runs, and host/IFOX00 deck comparisons are
separate acceptance gates. No golden file is an observed MVS result.**

## Build

From this directory, with Python 3.10+ and the repository C toolchain:

```sh
make core                 # system-macro-free core + guest smoke entry
make lint
make batch MACLIBS="/absolute/mvs-smoke-v1/bundle/macros"
```

The last command intentionally fails without explicit macro paths. The build
copies as370 to an isolated executable directory so its implicit sysroot macro
path cannot silently supply another revision. No warnings are suppressed:
assembler RC 4 is a failed build. `build/*-manifest.json` records source, tool,
and complete supplied macro-library hashes. Load modules are non-reentrant,
non-reusable, unauthorized, and use 24-bit addresses.

## Modules and linkage

```text
INSBAT   --QSAM--> POLIN, TXNIN, POLOUT, RESOUT
  |-- INSVAL -- INSPACK, INSDATE
  `-- INSCALC -- INSVAL -- INSPACK, INSDATE
        |-- INSPACK
        |-- INSDATE
        `-- INSRATE
INSSMOK -- INSCALC (hand-calculated executable assertions)
```

External calls use V constants, BALR 14,15, an R13 18-fullword save-area
chain, and an R1 work-area address. INSENT/INSRET are local application
macros, not replacements for OS macros. INSWORK is the shared DSECT.
INSPACK takes a pointer to seven packed bytes instead of the work area.
R15 is the return code; other registers are restored. Calculation results
are in the shared work area. Dispatch through an address table is intentional.

## Model

An immutable input generation contains at most 512 policies in increasing
eight-digit ID order. Transactions are processed in physical input order.
For each accepted transaction: accrue simple interest from the previous date
using the transaction date's rate; apply premium, withdrawal, loan, repayment,
cash/surrender quote or death-claim quote; write a result. A fresh output
master is written only after transaction EOF. Validation errors leave policy
state unchanged and have explicit per-record statuses. Infrastructure failures
and invalid masters invalidate the **entire new generation**.

The host integration controller must hold one exclusive lock across selection,
execution, validation, and generation publication. It must never overwrite the
input, publish on RC/ABEND/timeout failure, or promote partial output. JCL uses
exclusive input disposition; cataloging an output alone is not commit.
This is not a CICS, DB2, VSAM, concurrent, or in-place transaction system.

`SSEQ` is a policy's monotone high-water mark. An exact replay of its last
accepted 40-byte request gives `DUPL`; a changed request at that sequence gives
`CNFL`; lower sequences give `ORDR`. Gaps are permitted. Historical duplicates
are safely rejected but their old results are not cached. Quotes advance the
valuation date and sequence. A death quote is **not** settlement or closure.

## Documentation

- `docs/schemas.md` — record layouts with offsets, encodings, scales, statuses
- `docs/formulas.md` — contract V001, rule inventory BR-001..BR-014, worked anchors
- `docs/dependency-map.md` — modules, linkage, work-area conventions, dispatch
- `docs/landmines.md` — implemented, bounded and deferred coverage
- `docs/integration.md` — Hercules/MVS handoff: macros, DD names, JCL, receipts
- `docs/coverage.md` — deterministic corpus inventory and validation commands
- `docs/migration-plan.md` — proposed target architecture and sequence, plan only

## Tests and golden data

```sh
make test            # lint, py_compile, 13 contract tests
make verify-golden   # regenerate golden/v1 and compare every byte
```

`golden/v1` is a frozen V1 corpus from seed 37020260920: 256 policies, 8194
primary cases over 32 events per policy split across two stages, plus 500 replay
requests and ten hand-worked anchors, with `cases.jsonl`, `expected.jsonl`,
`coverage.json`, `SHA256SUMS`
and `expectation-provenance.json`. The expectations are computed by
`tools/oracle.py` (host Python, `Decimal` half-up) and are **generated
expectations, not captured MVS output**. `tools/{codec,oracle,corpus,compare,
deck_compare,check_layout,render_jcl}.py` are testing scaffolding and are
explicitly not the modernization deliverable.
