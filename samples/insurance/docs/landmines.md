# Landmine coverage: implemented, bounded, and deferred

| Area | Status | Evidence / boundary |
|----|----|----|
| Decimal precision and rounding | Implemented | Half-up to integer cents using assembler AP/DP and oracle Decimal; `test_decimal_against_independent_rational_math` re-derives interest and charge with `Fraction`, touching neither `Decimal` nor the oracle's rounder |
| Truncation and overflow | Implemented | `MAXAMT` checked on input, after interest, and after the operation; `OVER` cases in the corpus; `packed()` refuses to encode more than 13 digits |
| Signs | Implemented | `INSPACK` accepts `C`/`D`/`F` and rejects any other nibble; the comparator fails a sign difference even when the numeric value matches; corpus carries negative-zero and unsigned-`F` cases |
| Field overlays and encoding | Implemented | Shared DSECT overlays; `tools/check_layout.py` compares assembled offsets against the host codec on every build; reserved bytes must be binary zero, so CP037 blank padding is rejected |
| Hidden and reused state | Implemented, documented | Work-area results (`WORD`, `WRATE`, `WFEE`) are overwritten by each callee; quotes advance `SDATE`/`SSEQ`; `WBEFORE` rollback is asserted by the state-preservation tests |
| Order effects | Implemented | `test_actual_order_is_not_sorted` shows reversing two requests changes the committed master; BR-012 forbids sorting |
| External macro dependencies | Implemented | `make batch` fails without exact `--maclib` paths, assembles through a private as370 copy with `AS370_MACLIB=""`, and hashes every supplied macro member into the manifest |
| Restart and partial failure | Implemented at the oracle and record level | Replay stages return all-`DUPL`; a split mid-stream then resumed reproduces the uninterrupted master byte for byte. **The program has no mid-run checkpoint file**: an interrupted run is discarded and rerun from the input generation |
| Duplicate and conflict | Implemented | Main histories contain 244 `DUPL`, 244 `CNFL` and 756 `ORDR` results; separate replay stages add 500 `DUPL` results |
| Leap years, anniversaries, historical dates | Implemented | 1900 is correctly not a leap year (`DTMAX` special-cases it); Feb-29 issues anniversary on Mar 1; anchors A003/A004/A008/A009 |
| Atomic and idempotent state | Bounded | Per record: rejected transactions leave state byte-identical. Per run: a new generation is written only after transaction EOF, and promotion is the host controller's job under an exclusive lock |
| Concurrency | Explicitly not implemented | Single writer, single batch. No CICS, DB2, VSAM, ENQ or two-phase commit anywhere in this sample |
| IFOX00 parity | Deferred | Host as370 assembles at RC 0. Guest assembly and host/guest deck comparison (`tools/deck_compare.py`) need the Hercules runner |
| Execution evidence | Deferred | No golden file is an observed MVS result; `tools/compare.py` requires a receipt with job ID, RUN RC 0, no ABEND/timeout and matching hashes. Authentic spool capture remains the runner's responsibility |

## Known limits worth stating plainly

`INSBAT` holds the whole generation in storage (512 × 128 bytes) and searches it
linearly per transaction, which is fine at this size and would not be at
4.5 million policies. Historical duplicates are rejected rather than answered:
only the most recent accepted request is remembered, so an older replay returns
`ORDR` instead of the original result. Sequence gaps are allowed. `SSEQ` and
`SDATE` are per policy, so two policies in one file are independent. A death
quote is a quote, not settlement: nothing is closed and the policy stays active.

`INSVAL` checks checkpoint ID, sequence and effective date, but does not
recalculate the entire history or validate the rest of the saved request.
Accept persisted masters only from validated successful generations, with
controller-managed hashes. The formats contain no checksum, signature,
journal, crash recovery or protection against an operator replacing a master.

The host tests execute the oracle and test utilities, not S/370 instructions.
Host assembly/linkage and DSECT checks cannot establish the absence of
arithmetic, linkage, QSAM or macro-expansion defects on the guest.
