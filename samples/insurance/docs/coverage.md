# Golden V1 coverage and host acceptance

All fixtures are synthetic generated expectations, not captured guest results.
Seed: 37020260920. `coverage.json` is the machine-readable inventory.
`expectation-provenance.json` hashes codec, oracle, generator and hand-written
anchor inputs; `SHA256SUMS` hashes the other 20 frozen artifacts.
`make verify-golden` regenerates and compares all 21 artifacts, including the
checksum file itself. Generation refuses to overwrite a nonempty directory;
a changed contract must receive a new corpus version.

## Stage inventory

| Stage | Policies | Transactions | Input master | Expected master |
|----|----|----|----|----|
| anchors | 10 | 10 | anchors.polin.bin | anchors.expected.pol.bin |
| a | 256 | 4097 | polin.bin | a.expected.pol.bin |
| a-replay | 256 | 244 | a.expected.pol.bin | unchanged |
| b | 256 | 4097 | a.expected.pol.bin | b.expected.pol.bin |
| b-replay | 256 | 256 | b.expected.pol.bin | unchanged |

The primary corpus has 8,194 identified cases: 32 events for each policy,
split across two 16-event stages, plus one missing-policy request per stage.
Each stage interleaves policies in a seeded permutation, preserving each
policy's event order. Ten initial issue-date cohorts span 1900, the 2000 leap
year, rate-change dates, February 29, and the upper date bound.
Balances include zero, cent-size, maximum and seeded varied values; loans are
present on a subset. Rejections leave the current state in place, so later
events depend on prior success or failure.

`cases.jsonl` maps case ID, category and physical record index to raw transaction
bytes. `expected.jsonl` retains that identifier as `case_id` alongside every
decoded result field; its `id` field is the eight-digit policy ID.
`category` describes the attempted stimulus, not an assertion that it reaches
that check: earlier validation may take precedence.

## Primary outcomes (8,194 total)

| Status | Count | Status | Count |
|----|----|----|----|
| OKAY | 3413 | ORDR | 756 |
| DATE | 843 | FUND | 756 |
| PACK | 512 | OVER | 400 |
| AMNT | 256 | FORM | 256 |
| NEGA | 256 | TYPE | 256 |
| DUPL | 244 | CNFL | 244 |
| NPOL | 2 | | |

The 500 additional replay requests are all expected `DUPL`. Each has the exact
last accepted request from that policy's persisted stage output. The 10
hand-written anchors are checked independently from the seeded histories.

The 14 host tests cover anchors; sign/digit validation; complete byte layouts;
immutability on rejection/replay/conflict/order; invalid/truncated masters;
physical order; Fraction-versus-Decimal rounding; frozen fixtures and a split
restart at transaction 1,777; every result/master field; absent, extra,
duplicated, truncated and reordered output; packed sign differences; and
failed/malformed run receipts; and case-to-policy traceability. These are tests
of scaffolding and contracts.

## Reproduce the host checks

From `samples/insurance`:

```sh
make lint
make core
make batch MACLIBS="/absolute/mvs-smoke-v1/bundle/macros"
make test
make verify-golden
python3 -m pyflakes tools tests
../../file370/file370 --csects build/INSCALC.obj build/INSVAL.obj \
  build/INSBAT.obj build/INSSMOK build/INSBAT
../../file370/file370 build/INSSMOK.iebcopy build/INSBAT.iebcopy
git diff --check
```

The standard library suffices for normal sample targets. `pyflakes` is an
optional additional check available on this host; there is no configured
Python type checker. `make lint` performs fixed-column/label checks and Python
compilation. The assembler also validates operand/symbol usage and the build
compares DSECT offsets against codec layouts. None of those executes S/370.

The full repository command `make tools -j4` failed at the ar370
`-Werror=format-truncation` diagnostic on this host. No ar370 source or warning
policy was changed. The sample instead builds the existing individual
`as370/as370 ld370/ld370 file370/file370` targets successfully. No broad
toolchain test-suite success is asserted.

Expected negative checks:

- `python3 tools/build.py batch` exits 2 without explicit macro paths.
- Relative/nonexistent `--maclib` paths exit 2.
- The JCL renderer rejects missing `--syslib` for guest assembly and refuses
  input/output name collisions or existing output files.
- A zero-byte or truncated observed result file fails the comparator.

Remaining gates: guest allocations/transfers at LRECL 128/40/96; IFOX00
assembly/IEWL link; smoke and corpus execution; host-object execution; object
parity; actual partial-failure/restart tests; and a video of those executed
calculations. See `integration.md` for the exact runner boundary.
