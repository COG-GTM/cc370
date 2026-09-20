# Hercules / MVS 3.8j integration handoff

Nothing in this file has been run on a guest. Host `as370`/`ld370` assembly and
linkage pass, and `INSSMOK` links clean without any OS macro; everything below
is what the guest runner needs in order to produce the first execution
evidence.

## What the runner must supply

| Need | Detail |
|----|----|
| Macro libraries | `SYS1.MACLIB` and `SYS1.AMACLIB` from the running MVS 3.8j system, exported as host directories for `make batch MACLIBS="..."` and named in `SYSLIB` order for IFOX00 |
| Macros used | `DCB`, `OPEN`, `CLOSE`, `GET`, `PUT` (`INSBAT` only; the other six modules are macro-free) |
| Assembler | IFOX00, `PARM='OBJ,NODECK,TERM'`, `SYSPUNCH` to the object PDS |
| Linkage editor | IEWL, `PARM='XREF,LIST,LET,NCAL'`, `ENTRY INSBAT` / `ENTRY INSSMOK` |
| Runtime services | QSAM only. 24-bit, non-reentrant, unauthorized, no ENQ, no VSAM, no DB2, no CICS |

`jcl/build.jcl` and `jcl/run.jcl` are templates; render them rather than
editing them by hand, which is what enforces the 71-column limit and the
dataset-name rules:

```sh
python3 tools/render_jcl.py build --hlq IBMUSER.INSV1 --account ACCT \
  --syslib SYS1.MACLIB --syslib SYS1.AMACLIB --output build/guest-build.jcl
python3 tools/render_jcl.py run --hlq IBMUSER.INSV1 --account ACCT \
  --polin IBMUSER.INSV1.POLIN --txnin IBMUSER.INSV1.TXNA \
  --run IBMUSER.INSV1.RUNA --output build/guest-run-a.jcl
```

## Programs and DD names

| Program | DD | Direction | DSORG | RECFM | LRECL | BLKSIZE |
|----|----|----|----|----|----|----|
| `INSBAT` | `POLIN` | input | PS | FB | 128 | 1280 |
| `INSBAT` | `TXNIN` | input | PS | FB | 40 | 4000 |
| `INSBAT` | `POLOUT` | output | PS | FB | 128 | 1280 |
| `INSBAT` | `RESOUT` | output | PS | FB | 96 | 9600 |
| `INSBAT` | `STEPLIB`, `SYSUDUMP` | — | — | — | — | — |
| `INSSMOK` | none | — | — | — | — | — |

Datasets must be transferred **binary**: every file mixes CP037 text, packed
decimal and binary fullwords, so any text-mode transfer or codepage conversion
corrupts them. `xmit370` is the intended path. `POLOUT` and `RESOUT` must be
new datasets on each run; the input generation is never written in place, and
promoting the output to be the next run's input is the controller's step, under
whatever exclusive lock the site uses.

## Return codes

`INSBAT` returns 0 on a completed run, 8 if the master fails validation or
exceeds 512 policies, and 12 on an I/O failure. Any non-zero code invalidates
the whole output generation — per-record rejections are reported in `RESOUT`
with `OSTAT`, not in the step code. `INSSMOK` returns 0 if every hand-worked
assertion held and 12 on the first mismatch, with no message; it is a gate, not
a report.

## Feeding back results

Return the raw `POLOUT` and `RESOUT` bytes unconverted, plus a receipt:

```json
{"job": "JOB01234", "step": "RUN", "rc": 0, "abend": null, "timeout": false,
 "polin_sha256": "...", "txnin_sha256": "...",
 "polout_sha256": "...", "resout_sha256": "...",
 "rates_sha256": "...", "build_manifest_sha256": "...",
 "guest_manifest_sha256": "..."}
```

```sh
python3 tools/compare.py --golden golden/v1 --stage a \
  --polout run/POLOUT.bin --resout run/RESOUT.bin \
  --receipt run/receipt.json \
  --build-manifest build/manifest.json --guest-manifest run/guest.json
```

The comparator fails on a missing, extra, duplicated, truncated or reordered
record and on any byte of any declared field, reporting record number, policy,
field, offsets and both byte strings. A missing RC, an ABEND, a timeout or a
hash mismatch fails before any comparison runs, so a passing report cannot be
produced from a job that did not complete.

The guest manifest should carry the MVS level, the macro library volume and
member hashes, the IFOX00 and IEWL steps' return codes, and the object deck
hashes. With the decks in hand, `tools/deck_compare.py` compares host and guest
objects over columns 1-72 excluding the END card — that is build parity, and it
says nothing about runtime behaviour.

## Suggested bring-up order

1. `INSSMOK` assembled and linked on the guest, run standalone, expect RC 0.
   This exercises `INSCALC`, `INSVAL`, `INSPACK`, `INSDATE` and `INSRATE` with
   no I/O, so a failure here is arithmetic or linkage, not JCL.
2. `INSBAT` against `golden/v1/anchors.polin.bin` + `anchors.txns.bin`
   (10 policies, 10 transactions), compared with `--stage anchors`.
3. Stage `a` (256 policies, 4000 transactions), then the stage `a` replay file,
   which must return `DUPL` for every record and leave the master unchanged.
4. Stage `b`, then `b`'s replay.
