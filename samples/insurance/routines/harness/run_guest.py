"""Assemble, link and execute the routine driver on the real TK5 guest.

    python3 run_guest.py --backend ifox-iewl  --evidence DIR --prefix INSR.I1 \\
                         --maclib /abs/path/to/exact/SYS1.MACLIB/export
    python3 run_guest.py --backend as370-iewl --evidence DIR --prefix INSR.H1 ...
    python3 run_guest.py --backend as370-ld370 --evidence DIR --prefix INSR.L1 ...

Backends differ only in how INSDRV and the five frozen routines reach
@prefix.LOAD; the executed load module is always the real linked production
code plus the INSDRV recorder. The existing tools/tk5.py Guest controller is
used unchanged (writer lock, quarantine, JES receipts, tape transport).

Evidence written under --evidence:
  host/            as370 driver deck, listing, symbols, ld370 module (+ hashes)
  <label>/         one directory per submitted job (JCL, console, spool, receipt)
  decks/           IFOX00 decks and cols 1-72 comparison (ifox-iewl only)
  capture.bin      raw FB1536 capture records exported from the guest
  comparison.json  strict comparator report against fixtures/<version>
  run.json         receipt: hashes, job ids, step RCs, scope statement
"""

import argparse
import json
import os
import subprocess
import sys
from itertools import zip_longest
from pathlib import Path

from compare import compare
from fixtures import load
from layout import CAPTURE_LRECL, CASE_LRECL, INSURANCE, LIBRARY, ROUTINES, sha256, symbols

sys.path.insert(0, str(INSURANCE / "tools"))
from deck_compare import normalized  # noqa: E402
from tk5 import Dataset, Guest, header, write_tape  # noqa: E402
from tk5_linker import unload_blocks  # noqa: E402

REPO = INSURANCE.parents[1]
DRIVER = ROUTINES / "driver" / "INSDRV.asm"
HOST = ROUTINES / "build"
BACKENDS = ("ifox-iewl", "as370-iewl", "as370-ld370")


def host_build(maclib: Path) -> dict:
    """as370 assembles INSDRV; ld370 links it with the frozen core decks."""
    HOST.mkdir(parents=True, exist_ok=True)
    core_manifest = INSURANCE / "build" / "core-manifest.json"
    if not core_manifest.exists():
        raise SystemExit("run `make -C samples/insurance core` first (frozen core decks)")
    private = INSURANCE / "build" / "hostbin" / "as370"
    if not private.exists():
        raise SystemExit("missing build/hostbin/as370 from `make core`")
    env = dict(os.environ, AS370_MACLIB="", ASMDATE="09/20/26", ASMTIME="00.00")
    cmd = [str(private), "-I", str(INSURANCE / "copy"), "-I", str(maclib),
           "-a=" + str(HOST / "INSDRV.lst"), "--sym=" + str(HOST / "INSDRV.sym"),
           "-o", str(HOST / "INSDRV.obj"), str(DRIVER)]
    print(" ".join(cmd), flush=True)
    subprocess.run(cmd, check=True, env=env)
    core = [INSURANCE / "build" / f"{name}.obj" for name in LIBRARY]
    cmd = [str(REPO / "ld370/ld370"), "--norent", "--noreus", "--entry", "INSDRV",
           "--name", "INSDRV", "-o", str(HOST / "INSDRV"), "-iebcopy",
           str(HOST / "INSDRV.obj"), *map(str, core)]
    print(" ".join(cmd), flush=True)
    subprocess.run(cmd, check=True, env=env)
    return {
        "scope": "host build only; not observed MVS execution",
        "driver_source": sha256(DRIVER.read_bytes()),
        "driver_deck": sha256((HOST / "INSDRV.obj").read_bytes()),
        "driver_ld370_iebcopy": sha256((HOST / "INSDRV.iebcopy").read_bytes()),
        "core_decks": {p.name: sha256(p.read_bytes()) for p in core},
        "core_manifest": json.loads(core_manifest.read_text()),
        "maclib": str(maclib),
    }


def source_members(prefix: str) -> list[Dataset]:
    members = []
    for folder, suffix in ((INSURANCE / "src", "SRC"), (INSURANCE / "copy", "MAC"),
                           (ROUTINES / "driver", "SRC")):
        for path in sorted(folder.iterdir()):
            if not path.is_file() or path.suffix not in (".asm", ".mac", ".copy"):
                continue
            if folder.name == "src" and path.stem not in LIBRARY:
                continue
            lines = path.read_text().splitlines()
            if any(len(line) > 80 for line in lines):
                raise ValueError(f"source exceeds 80 columns: {path}")
            raw = b"".join(line.ljust(80).encode("cp037") for line in lines)
            members.append(Dataset(f"{prefix}.{suffix}({path.stem})", 80, 3200, raw))
    return members


def asm_jcl(prefix: str) -> tuple[str, list[str]]:
    jcl = header("INSRASM")
    jcl += ("//ASMPROC PROC MEMBER=\n"
            "//ASM      EXEC PGM=IFOX00,REGION=2048K,\n"
            "//             PARM='DECK,NOLOAD,LIST',COND=(0,NE)\n"
            f"//SYSLIB   DD DSN={prefix}.MAC,DISP=SHR,\n"
            "//             DCB=BLKSIZE=32720\n"
            "//         DD DSN=SYS1.MACLIB,DISP=SHR\n"
            f"//SYSIN    DD DSN={prefix}.SRC(&MEMBER),DISP=SHR\n"
            f"//SYSPUNCH DD DSN={prefix}.OBJ(&MEMBER),DISP=OLD\n"
            "//SYSGO    DD DUMMY\n//SYSPRINT DD SYSOUT=*\n"
            "//SYSUT1   DD UNIT=SYSDA,SPACE=(CYL,(1,1))\n"
            "//SYSUT2   DD UNIT=SYSDA,SPACE=(CYL,(1,1))\n"
            "//SYSUT3   DD UNIT=SYSDA,SPACE=(CYL,(1,1))\n"
            "//         PEND\n")
    steps = []
    for name in list(LIBRARY) + ["INSDRV"]:
        step = "A" + name[3:7]
        steps.append(step)
        jcl += f"//{step:<8} EXEC ASMPROC,MEMBER={name}\n"
    return jcl, steps


def link_jcl(prefix: str) -> str:
    return (f"//LDRV     EXEC PGM=IEWL,PARM='LIST,MAP,XREF',COND=(0,NE),\n"
            "//             REGION=2048K\n//SYSPRINT DD SYSOUT=*\n"
            "//SYSUT1   DD UNIT=SYSDA,SPACE=(CYL,(1,1))\n"
            f"//SYSLMOD  DD DSN={prefix}.LOAD,DISP=OLD\n"
            f"//OBJ      DD DSN={prefix}.OBJ,DISP=SHR\n//SYSLIN   DD *\n"
            " INCLUDE OBJ(INSDRV)\n"
            + "".join(f" INCLUDE OBJ({name})\n" for name in LIBRARY)
            + " ENTRY INSDRV\n NAME INSDRV(R)\n/*\n")


def run_jcl(prefix: str) -> str:
    return (header("INSRRUN")
            + "//DRV      EXEC PGM=INSDRV,REGION=1024K,TIME=(2,0)\n"
            f"//STEPLIB  DD DSN={prefix}.LOAD,DISP=SHR\n"
            f"//CASEIN   DD DSN={prefix}.CASES,DISP=OLD,\n"
            f"//             DCB=(RECFM=FB,LRECL={CASE_LRECL},BLKSIZE={CASE_LRECL * 10})\n"
            f"//CAPOUT   DD DSN={prefix}.CAPTURE,DISP=(NEW,CATLG,DELETE),\n"
            "//             UNIT=SYSDA,SPACE=(TRK,(60,20)),\n"
            f"//             DCB=(RECFM=FB,LRECL={CAPTURE_LRECL},BLKSIZE={CAPTURE_LRECL * 10})\n"
            "//SYSUDUMP DD SYSOUT=*\n//\n")


def deck_report(guest: Guest, prefix: str) -> list[dict]:
    names = list(LIBRARY) + ["INSDRV"]
    observed = guest.transfer("decks", [Dataset(f"{prefix}.OBJ({n})", 80, 3200) for n in names], "out")
    folder = guest.evidence / "decks"
    folder.mkdir(exist_ok=True)
    reports = []
    for name, raw in zip(names, observed):
        host = (HOST / "INSDRV.obj" if name == "INSDRV" else INSURANCE / "build" / f"{name}.obj").read_bytes()
        (folder / f"{name}.ifox.obj").write_bytes(raw)
        (folder / f"{name}.as370.obj").write_bytes(host)
        left, right = normalized(raw), normalized(host)
        differences = [{"card": i, "ifox": a.hex(), "as370": b.hex()}
                       for i, (a, b) in enumerate(zip_longest(left, right, fillvalue=b""), 1) if a != b]
        reports.append({"module": name, "identical": not differences,
                        "ifox_non_end_cards": len(left), "as370_non_end_cards": len(right),
                        "compared_columns": "1-72", "ifox_sha256": sha256(raw),
                        "as370_sha256": sha256(host), "differences": differences,
                        "scope": "object build comparison, separate from execution evidence"})
        print(name, "MATCH" if not differences else "DIFF", len(left), "non-END cards", flush=True)
    (folder / "deck-comparison.json").write_text(json.dumps(reports, indent=1) + "\n")
    return reports


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--backend", choices=BACKENDS, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--prefix", required=True, help="unique guest dataset prefix")
    parser.add_argument("--root", type=Path, default=Path.home() / "mvs-demo")
    parser.add_argument("--maclib", type=Path, required=True,
                        help="absolute path to the exact guest SYS1.MACLIB export")
    parser.add_argument("--version", default="v1")
    args = parser.parse_args()
    if not args.maclib.is_absolute() or not args.maclib.is_dir():
        parser.error("--maclib must be an existing absolute directory")
    manifest, expected, cases = load(args.version)
    host = host_build(args.maclib)
    (args.evidence).mkdir(parents=True, exist_ok=True)
    (args.evidence / "host").mkdir(exist_ok=True)
    for name in ("INSDRV.obj", "INSDRV.lst", "INSDRV.sym", "INSDRV.iebcopy"):
        (args.evidence / "host" / name).write_bytes((HOST / name).read_bytes())
    (args.evidence / "host" / "build.json").write_text(json.dumps(host, indent=1, sort_keys=True) + "\n")
    receipt: dict = {"schema": "insurance-routines-run-v1", "backend": args.backend,
                     "prefix": args.prefix, "fixture_version": args.version,
                     "fixture_manifest_sha256": sha256(json.dumps(manifest, sort_keys=True).encode()),
                     "cases_sha256": manifest["cases_sha256"], "jobs": {}}
    with Guest(args.evidence, args.root) as guest:
        guest.allocate(args.prefix)
        receipt["jobs"]["allocate"] = json.loads((guest.evidence / "allocate/result.json").read_text())
        if args.backend == "ifox-iewl":
            guest.transfer("sources", source_members(args.prefix), "in")
            jcl, steps = asm_jcl(args.prefix)
            receipt["jobs"]["assemble"] = guest.job("assemble", jcl + link_jcl(args.prefix) + "//\n",
                                                    steps + ["LDRV"])
            receipt["deck_comparison"] = deck_report(guest, args.prefix)
        elif args.backend == "as370-iewl":
            decks = [Dataset(f"{args.prefix}.OBJ(INSDRV)", 80, 3200, (HOST / "INSDRV.obj").read_bytes())]
            decks += [Dataset(f"{args.prefix}.OBJ({n})", 80, 3200,
                              (INSURANCE / "build" / f"{n}.obj").read_bytes()) for n in LIBRARY]
            guest.transfer("objects", decks, "in")
            receipt["jobs"]["link"] = guest.job("link", header("INSRLNK") + link_jcl(args.prefix) + "//\n",
                                                ["LDRV"])
        else:
            raw = (HOST / "INSDRV.iebcopy").read_bytes()
            tape = guest.evidence / "ld370.aws"
            write_tape(tape, [unload_blocks(raw)])
            blocksize = int.from_bytes(raw[14:16], "big")
            jcl = (header("INSRLD")
                   + "//C001 EXEC PGM=IEBCOPY,REGION=2048K\n//SYSPRINT DD SYSOUT=*\n"
                   "//SYSUT1 DD DSN=INS.UNLOAD,UNIT=480,\n"
                   "//         VOL=SER=INS001,LABEL=(1,NL),DISP=(OLD,KEEP),\n"
                   f"//         DCB=(RECFM=VS,LRECL={blocksize - 4},BLKSIZE={blocksize})\n"
                   f"//SYSUT2 DD DSN={args.prefix}.LOAD,DISP=OLD\n"
                   "//SYSUT3 DD UNIT=SYSDA,SPACE=(TRK,(5,5))\n"
                   "//SYSUT4 DD UNIT=SYSDA,SPACE=(TRK,(5,5))\n"
                   "//SYSIN DD *\n COPY INDD=SYSUT1,OUTDD=SYSUT2\n/*\n//\n")
            receipt["jobs"]["import"] = guest.job("import", jcl, ["C001"], tape)
        guest.transfer("cases-in", [Dataset(args.prefix + ".CASES", CASE_LRECL, CASE_LRECL * 10, cases)], "in")
        run = guest.job("run", run_jcl(args.prefix), ["DRV"], allow_failure=True)
        receipt["jobs"]["run"] = run
        captured = b""
        if run["passed"]:
            captured = guest.transfer("capture-out", [Dataset(args.prefix + ".CAPTURE",
                                                              CAPTURE_LRECL, CAPTURE_LRECL * 10)], "out")[0]
    (args.evidence / "capture.bin").write_bytes(captured)
    result = compare(captured, manifest, expected, symbols(HOST / "INSDRV.sym"))
    (args.evidence / "comparison.json").write_text(json.dumps(result, indent=1, sort_keys=True) + "\n")
    receipt.update({
        "capture_sha256": sha256(captured), "capture_records": len(captured) // CAPTURE_LRECL,
        "comparison_passed": result["passed"], "findings": len(result["findings"]),
        "cases_passed": len(result["cases_passed"]),
        "scope": ("observed MVS 3.8j execution of the linked INSDRV load module under Hercules; "
                  "direct-call evidence for INSPACK/INSDATE/INSRATE/INSVAL/INSCALC only"),
    })
    (args.evidence / "run.json").write_text(json.dumps(receipt, indent=1, sort_keys=True) + "\n")
    for f in result["findings"]:
        print(f'FAIL {f["class"]:9} {f["routine"]:8} {f["case"]:8} {f["field"]}: '
              f'expected {f["expected"]} observed {f["observed"]}')
    print(f'{args.backend}: run step {run["steps"]}, {receipt["capture_records"]}/'
          f'{len(expected)} records, {receipt["cases_passed"]} cases passed, '
          f'{receipt["findings"]} findings -> {"PASS" if result["passed"] else "FAIL"}', flush=True)
    raise SystemExit(0 if result["passed"] and run["passed"] else 1)


if __name__ == "__main__":
    main()
