# Proposed target architecture and migration plan (plan only)

No modernization is implemented in this branch, and none should be added to it.
This is the plan that the artifacts above are designed to make executable
later; the traceability identifiers exist so that a future implementation can
be checked against this sample rather than against someone's reading of it.

## What makes this domain hard to move

The business rules are not separable from the platform mechanics by reading.
The rate table is a `DC` in `INSRATE` with its effective dates as binary
fullwords; half-up rounding is an `AP` of half the divisor before a `DP`; the
operation set is an address-constant table resolved by the linker; and the
ordering, duplicate and rollback semantics live in register conventions and a
work-area pre-image. A reimplementation that reproduces the formulas but not
`DUPL`/`CNFL`/`ORDR`, the Mar-1 anniversary, or the fact that a quote advances
`SDATE`, will differ on real traffic while passing a naive test suite.

## Proposed target shape

A calculation core with no I/O, holding contract V001 as integer-cent
arithmetic with explicit half-up rounding and an externalized rate table; a
state store owning the policy record and the `SSEQ`/`SLAST` replay token as a
first-class idempotency key rather than a byte comparison; a batch driver that
preserves input order and generation-at-a-time publication; and codecs at the
edge for CP037, packed decimal and big-endian binary, so that no encoding
concern reaches the core. The 512-policy in-storage table is the one design
element that should not survive.

## Sequence

1. **Characterize.** Run the existing golden corpus on the guest and freeze the
   observed outputs as the behavioural baseline. Until then every expectation
   in `golden/v1` is generated, not observed.
2. **Extract the core.** Reimplement contract V001 against the frozen outputs,
   field by field with `tools/compare.py`, including every rejection status.
3. **Extract the edges.** Codecs and the state store, validated by round-trip
   against the same binary files.
4. **Shadow.** Run both implementations on the same input generation and
   compare raw bytes, not decoded values; a packed sign difference is a
   defect.
5. **Cut over one operation family at a time**, quotes first, since they are
   the ones whose hidden state effect is easiest to get wrong and cheapest to
   detect.

## Traceability identifiers

`BR-001`..`BR-014` (see `docs/formulas.md`) name the rules, the four-character
`OSTAT` values name the outcomes, `A001`..`A010` name the hand-worked anchors,
and the corpus case IDs in `golden/v1/cases.jsonl` name individual scenarios.
A future implementation should cite these identifiers in its tests so that a
reviewer can tell what was preserved deliberately and what was preserved by
accident.

## Explicitly out of scope here

No Java or other target-language implementation, no service, no data
migration, no production data, and no claim that this sample is equivalent to
any production system.
