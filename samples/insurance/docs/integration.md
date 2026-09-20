# Hercules / MVS 3.8j integration handoff

## Status and exact environment

Host `as370`/`ld370` now assemble all seven application modules and link both
`INSSMOK` and `INSBAT` at RC 0 using the supplied **747-member TK5 Update 5
SYS1.MACLIB export**. Its archive is
`mvs-smoke-v1.tar.gz`, SHA-256
`20bdd268f4eabc2af403ef1c64407809244e3ebc585b3572649e3e843304c703`.
All bundle checksums passed locally before use. No macro export or IBM source
is committed to this repository.

The [environment session](https://app.devin.ai/sessions/8445e980914147f6a429a6b7d910d8a6)
reports successful independent smoke jobs on TK5 Update 5 / Hyperion 4.9.1.
That is environment evidence; **this insurance application has not run on
MVS yet**, from either guest or host-built objects.

From the application directory, after extracting the supplied archive:

```sh
make batch MACLIBS="$HOME/mvs-insurance-deps/mvs-smoke-v1/bundle/macros"
```

The path is an example of this session's extraction location. Pass the actual
absolute `bundle/macros` path on the runner. `build/batch-manifest.json` records
every macro member, source and tool hash, plus host object/unload hashes.

## What the runner must supply

| Need | Detail |
|----|----|
| Macro libraries | Application `.MAC` first, then exact guest `SYS1.MACLIB`. No AMACLIB was needed for this TK5 build; never add a substitute implicitly |
| Macros used | `DCB`, `OPEN`, `CLOSE`, `GET`, `PUT` (`INSBAT` only); all modules use local application macros |
| Assembler | IFOX00, 2048K, `PARM='DECK,NOLOAD,LIST'`, `SYSPUNCH` to the FB80 object PDS; SYSGO is intentionally DUMMY because links consume SYSPUNCH |
| Linkage editor | IEWL, 2048K, `PARM='LIST,MAP,XREF'`, `ENTRY INSBAT` / `ENTRY INSSMOK`; no LET after unresolved references |
| Runtime services | QSAM only. 24-bit, non-reentrant, unauthorized, no ENQ, no VSAM, no DB2, no CICS |

`jcl/build.jcl`, `jcl/link.jcl` and `jcl/run.jcl` are templates; render them rather than
editing them by hand, which is what enforces the 71-column limit and the
dataset-name rules:

```sh
python3 tools/render_jcl.py build --hlq IBMUSER.INSV1 --account ACCT \
  --syslib SYS1.MACLIB --output build/guest-build.jcl
python3 tools/render_jcl.py link --hlq IBMUSER.INSHST --account ACCT \
  --output build/host-link.jcl
python3 tools/render_jcl.py run --hlq IBMUSER.INSV1 --account ACCT \
  --polin IBMUSER.INSV1.POLIN --txnin IBMUSER.INSV1.TXNA \
  --run IBMUSER.INSV1.RUNA --output build/guest-run-a.jcl
```

The renderer refuses to overwrite an existing file; use a fresh output name.
Before submission, allocate cataloged datasets on the guest:

| Dataset | Organization and format | Suggested starting allocation |
|----|----|----|
| `IBMUSER.INSV1.SRC` | PO, FB80, BLKSIZE 3200; seven source members named by basename | 10 tracks, 5 directory blocks |
| `IBMUSER.INSV1.MAC` | PO, FB80, BLKSIZE 3200; INSENT, INSRET, INSWORK | 5 tracks, 5 directory blocks |
| `IBMUSER.INSV1.OBJ` | PO, FB80, BLKSIZE 3200; IFOX00 object decks | 10 tracks, 5 directory blocks |
| `IBMUSER.INSV1.LOAD` | PO, U, BLKSIZE 32760 | 20 tracks, 5 directory blocks |
| `IBMUSER.INSHST.OBJ` | PO, FB80, BLKSIZE 3200; raw as370 object decks | 10 tracks, 5 directory blocks |
| `IBMUSER.INSHST.LOAD` | PO, U, BLKSIZE 32760; IEWL-linked host objects | 20 tracks, 5 directory blocks |

These allocations and application jobs remain unvalidated on the guest.
Source/macros must be padded to FB80 and encoded once as CP037; preserve
column 72 continuation flags. Objects are already binary and must not be
encoded again. The fixture files below require PS datasets at their own LRECL.

## Programs and DD names

| Program | DD | Direction | DSORG | RECFM | LRECL | BLKSIZE |
|----|----|----|----|----|----|----|
| `INSBAT` | `POLIN` | input | PS | FB | 128 | 1280 |
| `INSBAT` | `TXNIN` | input | PS | FB | 40 | 4000 |
| `INSBAT` | `POLOUT` | output | PS | FB | 128 | 1280 |
| `INSBAT` | `RESOUT` | output | PS | FB | 96 | 9600 |
| `INSBAT` | `STEPLIB`, `SYSUDUMP` | — | — | — | — | — |
| `INSSMOK` | none | — | — | — | — | — |

Application data must be transferred **binary**: every file mixes CP037 text, packed
decimal and binary fullwords, so any text-mode transfer or codepage conversion
corrupts them. `xmit370 create` packages text source PDS members; it must not be
used as a text converter for these mixed-format data files. `POLOUT` and `RESOUT` must be
new datasets on each run; the input generation is never written in place, and
promoting the output to be the next run's input is the controller's step, under
whatever exclusive lock the site uses.

## Return codes

`INSBAT` returns 0 on a completed run, 12 if the master fails validation or
exceeds 512 policies, and 16 when an OPEN-completion flag check fails.
Other I/O failures can ABEND; no SYNAD recovery routine is installed.
Any non-zero code or ABEND invalidates
the whole output generation — per-record rejections are reported in `RESOUT`
with `OSTAT`, not in the step code. `INSSMOK` returns 0 if every hand-worked
assertion held and 12 on the first mismatch, with no message; it is a gate, not
a report.

## Feeding back results

Return the raw `POLOUT` and `RESOUT` bytes unconverted, plus a receipt:

```json
{"schema": "insurance-run-v1", "job_id": "JOB01234",
 "outcome": "completed", "step_rc": {"RUN": 0},
 "abend": null, "timed_out": false,
 "polin_sha256": "...", "txnin_sha256": "...",
 "polout_sha256": "...", "resout_sha256": "...",
 "rates_sha256": "...", "build_manifest_sha256": "...",
 "guest_manifest_sha256": "..."}
```

```sh
python3 tools/compare.py --golden golden/v1 --stage a \
  --polout run/POLOUT.bin --resout run/RESOUT.bin \
  --receipt run/receipt.json \
  --build-manifest build/batch-manifest.json --guest-manifest run/guest.json
```

The comparator fails on a missing, extra, duplicated, truncated or reordered
record and on any byte of any declared field, reporting record number, policy,
field, offsets and both byte strings. A missing RC, an ABEND, a timeout or a
hash mismatch fails before comparison. The receipt is a runner attestation,
not an authenticity check: preserve the corresponding raw spool and derive
statuses from it, never from expected results.

The guest manifest should carry the MVS level, the macro library volume and
member hashes, the IFOX00 and IEWL steps' return codes, and the object deck
hashes. Include the deployed load library/module hashes or captured load
artifacts, execution path (`ifox-iewl`, `as370-iewl`, or `as370-ld370`),
actual submitted JCL, source commit, input dataset identifiers and transport
checks. With the decks in hand, `tools/deck_compare.py` compares host and guest
objects over columns 1-72 excluding the END card — that is build parity, and it
says nothing about runtime behaviour.

## Suggested bring-up order

1. `INSSMOK` assembled and linked on the guest, run standalone, expect RC 0.
   This exercises `INSCALC`, `INSVAL`, `INSPACK`, `INSDATE` and `INSRATE` with
   no application I/O. RC 12 isolates an assertion failure; loading/JCL
   errors must still be diagnosed from the spool.
2. `INSBAT` against `golden/v1/anchors.polin.bin` + `anchors.txns.bin`
   (10 policies, 10 transactions), compared with `--stage anchors`.
3. Stage `a` (256 policies, 4097 transactions), then the stage `a` replay file,
   which must return `DUPL` for every record and leave the master unchanged.
4. Stage `b` (4097 transactions), then `b`'s replay. Use the actual validated
   stage-a POLOUT as input, not the golden expected master as a replacement.
5. Repeat smoke and all five comparison stages using **host as370 objects
   linked by guest IEWL**, in the separate `IBMUSER.INSHST` libraries.
   Render `link` for the host object library, then `run` with
   `--hlq IBMUSER.INSHST` and new output prefixes. Compare raw outputs across
   both paths as well as against the goldens. Deck equality alone is insufficient.
6. Also execute host `ld370` load modules after binary IEBCOPY/RECV370 import
   into a third load library if the transport supports it. This independently
   validates the host linker; `as370 -> IEWL` execution does not.

## Adapting the supplied runner

The bundle's `run_job.py` accepts only a single FB80 binary stream per job.
**Do not pass the 128/40/96-byte fixtures to that interface unchanged**, even
when their total length happens to be divisible by 80. The integration child
must extend binary transport while preserving these layouts:

- A loader/unloader can wrap mixed-format records in FB80 transport cards,
  reassemble exact bytes into PS FB128/FB40, and unwrap FB96/FB128 output.
  Alternatively use another proven binary dataset path. Verify a round-trip
  of each actual LRECL before running the app; no line splitting or transcoding.
- Preserve raw punch captures. Remove only a positively identified JES
  transport separator from deck exports before `deck_compare.py`; never strip
  blank data records or silently truncate output to an expected count.
- Allocate/submit through the existing isolated guest on the environment
  child's machine. Do not install another image or mount its DASD elsewhere.
- Require all expected steps: build = ACALC, AVAL, APACK, ADATE, ARATE, ASMOK,
  ABAT, LSMOK, LBAT, SMOKE; host-object link = LSMOK, LBAT, SMOKE;
  batch = RUN, plus every added transport step. Check the PROC step naming
  against actual IEF142I output when adapting `--steps`.
- Convert the runner's `result.json` into the receipt schema above only after
  checking its `passed`, `purged`, `errors` and complete step list against
  the raw spool. Its smoke schema is not accepted directly by `compare.py`.

For each execution path, test failed-master RC 12, a rejected transaction with
unchanged state, duplicate/conflict/order records, successful generation
restart, and discard/re-run after a forced partial failure. Hold one controller
lock across input selection, submission, capture, comparison and publication;
the supplied per-job lock alone does not serialize a multi-job generation.
Record the final walkthrough only after real application execution succeeds.
