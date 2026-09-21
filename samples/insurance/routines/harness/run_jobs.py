"""Job-level INSBAT/INSSMOK evidence for one executable path on the real guest.

    python3 run_jobs.py --backend ifox-iewl --evidence DIR --prefix INSJ.I1 \\
                        --maclib /abs/path/to/exact/SYS1.MACLIB/export

Phases (each is its own writer-locked Guest session, in order):
  build     load library for the path: IFOX00+IEWL (tools/tk5.py build),
            as370 decks + IEWL (tools/tk5.py host) or ld370 IEBCOPY import
            (tools/tk5_linker.py logic); INSSMOK runs inside the build job.
  cases     additive job-level cases with independent expectations
            (model.py): standalone INSSMOK RC, empty TXNIN, exactly 512
            masters accepted, no masters -> NPOL results, and the
            host-versus-guest boundary (guest RC0 on a half-delivered TXNIN,
            host comparator refuses to publish).
  stages    the frozen five-stage regression through tools/tk5_validate.run
            (anchors, a, a-replay, b, b-replay; 8704 results), each stage
            chaining its own observed validated master.
  controls  tools/tk5_controls.py (RC12 rejections, 513-policy cap, S806,
            immutable rejections, writer lock).

Nothing in tools/ is modified; this module only sequences those tools and
adds the cases above. Results land in DIR/jobs.json.
"""

import argparse
import json
import subprocess
import sys
import traceback
from pathlib import Path

from layout import INSURANCE, sha256, text
from model import Work, inscalc, insval

sys.path.insert(0, str(INSURANCE / "tools"))
from codec import decode, records, state, transaction  # noqa: E402
from tk5 import Guest, header, write_tape  # noqa: E402
from tk5_linker import unload_blocks  # noqa: E402
import tk5_validate  # noqa: E402

GOLDEN = INSURANCE / "golden" / "v1"
BACKENDS = ("ifox-iewl", "as370-iewl", "as370-ld370")


def insbat_model(policies: bytes, transactions: bytes) -> tuple[int, bytes, bytes]:
    """INSBAT as read from src/INSBAT.asm, with INSCALC from model.py."""
    table: list[bytes] = []
    previous = bytes(8)
    for record in records(policies, "state"):
        if len(table) >= 512 or record[:8] <= previous:
            return 12, b"", b""
        work = Work(bytes(460))
        work.set("SREC", record)
        if insval(work):
            return 12, b"", b""
        table.append(record)
        previous = record[:8]
    results = []
    for txn in records(transactions, "transaction"):
        index = next((i for i, rec in enumerate(table) if rec[:8] == txn[:8]), None)
        work = Work(bytes(460))
        work.set("SREC", table[index] if index is not None else bytes(128))
        work.set("TREC", txn)
        out = Work(inscalc(work, b"").work)
        if index is None:
            out.set("OSTAT", text("NPOL"))
        elif out.get("OSTAT") == text("OKAY"):
            table[index] = out.get("SREC")
        results.append(out.get("OREC"))
    return 0, b"".join(table), b"".join(results)


def import_ld370(guest: Guest, prefix: str) -> dict:
    guest.allocate(prefix)
    entries = ("INSSMOK", "INSBAT")
    artifacts = [(INSURANCE / "build" / (e + ".iebcopy")).read_bytes() for e in entries]
    tape = guest.evidence / "ld370.aws"
    write_tape(tape, [unload_blocks(raw) for raw in artifacts])
    jcl = header("INSLD")
    for index, raw in enumerate(artifacts, 1):
        blocksize = int.from_bytes(raw[14:16], "big")
        jcl += (f"//C{index:03d} EXEC PGM=IEBCOPY,REGION=2048K,COND=(0,NE)\n"
                "//SYSPRINT DD SYSOUT=*\n//SYSUT1 DD DSN=INS.UNLOAD,UNIT=480,\n"
                f"//         VOL=SER=INS001,LABEL=({index},NL),DISP=(OLD,KEEP),\n"
                f"//         DCB=(RECFM=VS,LRECL={blocksize - 4},BLKSIZE={blocksize})\n"
                f"//SYSUT2 DD DSN={prefix}.LOAD,DISP=OLD\n"
                "//SYSUT3 DD UNIT=SYSDA,SPACE=(TRK,(5,5))\n"
                "//SYSUT4 DD UNIT=SYSDA,SPACE=(TRK,(5,5))\n"
                "//SYSIN DD *\n COPY INDD=SYSUT1,OUTDD=SYSUT2\n/*\n")
    jcl += ("//SMOKE EXEC PGM=INSSMOK,REGION=512K,COND=(0,NE)\n"
            f"//STEPLIB DD DSN={prefix}.LOAD,DISP=SHR\n//SYSUDUMP DD SYSOUT=*\n//\n")
    return guest.job("import", jcl, ["C001", "C002", "SMOKE"], tape)


def build_phase(backend: str, evidence: Path, root: Path, load: str) -> tuple[dict, str]:
    with Guest(evidence / "build", root) as guest:
        if backend == "ifox-iewl":
            guest.build(load)
            label = "build"
        elif backend == "as370-iewl":
            guest.host(load)
            label = "link"
        else:
            import_ld370(guest, load)
            label = "import"
    return json.loads((evidence / "build" / label / "result.json").read_text()), label


def batch_case(guest: Guest, name: str, load: str, prefix: str, policies: bytes,
               transactions: bytes, expect_rc: int) -> dict:
    rc, want_pol, want_res = insbat_model(policies, transactions)
    if rc != expect_rc:
        raise ValueError(f"{name}: model rc {rc} disagrees with the case design {expect_rc}")
    pol, res, job = guest.batch(name, load, prefix, policies, transactions, allow_failure=True)
    findings = []
    if job["steps"] != [["RUN", f"{expect_rc:04d}"]]:
        findings.append({"field": "step_rc", "expected": f"RUN={expect_rc:04d}", "observed": job["steps"]})
    if pol != want_pol:
        findings.append({"field": "POLOUT", "expected_sha256": sha256(want_pol), "observed_sha256": sha256(pol),
                         "expected_records": len(want_pol) // 128, "observed_records": len(pol) // 128})
    want_records, got_records = records(want_res, "result"), records(res, "result")
    if len(want_records) != len(got_records):
        findings.append({"field": "RESOUT.count", "expected": len(want_records), "observed": len(got_records)})
    for i, (want, got) in enumerate(zip(want_records, got_records)):
        if want != got:
            findings.append({"field": f"RESOUT[{i}]", "expected": want.hex(), "observed": got.hex(),
                             "expected_decoded": decode(want, "result"), "observed_decoded": decode(got, "result")})
    result = {"case": name, "job": job, "passed": not findings, "findings": findings,
              "polin_sha256": sha256(policies), "txnin_sha256": sha256(transactions),
              "polout_sha256": sha256(pol), "resout_sha256": sha256(res),
              "resout_records": len(got_records),
              "decoded_results": [decode(r, "result") for r in got_records][:16],
              "expectation_source": "routines/harness/model.py (independent) + src/INSBAT.asm control flow"}
    print(f'{name}: {"PASS" if result["passed"] else "FAIL"} {job["steps"]} '
          f'{len(pol) // 128} masters, {len(got_records)} results', flush=True)
    return result


def cases_phase(evidence: Path, root: Path, load: str, prefix: str,
                build_json: Path, guest_json: Path) -> list[dict]:
    anchors = (GOLDEN / "anchors.polin.bin").read_bytes()
    results = []
    with Guest(evidence / "cases", root) as guest:
        job = guest.job("smoke", header("INSSMOKE")
                        + "//SMOKE EXEC PGM=INSSMOK,REGION=512K,TIME=(1,0)\n"
                        f"//STEPLIB DD DSN={load}.LOAD,DISP=SHR\n//SYSUDUMP DD SYSOUT=*\n//\n",
                        ["SMOKE"], allow_failure=True)
        results.append({"case": "smoke_standalone", "job": job, "passed": job["steps"] == [["SMOKE", "0000"]],
                        "expectation_source": "src/INSSMOK.asm returns 0 when its hand assertions hold, 12 otherwise"})
        print("smoke_standalone:", job["steps"], flush=True)
        results.append(batch_case(guest, "empty_transactions", load, prefix + ".E1", anchors, b"", 0))
        cap = b"".join(state(f"{i:08d}", 20240101, 1_000_000, 100_000) for i in range(1, 513))
        results.append(batch_case(guest, "cap_512_accepted", load, prefix + ".E2", cap, b"", 0))
        orphan = (transaction("00000001", 1, 20250101, "P", 10_000)
                  + transaction("00000002", 1, 20250101, "Q", 0)
                  + transaction("00000003", 1, 20250230, "D", 0))
        results.append(batch_case(guest, "no_masters_npol", load, prefix + ".E3", b"", orphan, 0))
        one = anchors[:128]
        mixed = (transaction("00000001", 1, 20250101, "P", 10_000)
                 + transaction("00009999", 1, 20250101, "P", 10_000)
                 + transaction("00000001", 2, 20250101, "Q", 0))
        results.append(batch_case(guest, "known_and_unknown_policy", load, prefix + ".E4", one, mixed, 0))
        over = cap + state("00000513", 20240101, 1_000_000, 100_000)
        results.append(batch_case(guest, "cap_513_rejected_rc12", load, prefix + ".E5", over, mixed, 12))
        misordered = anchors[128:256] + anchors[:128]
        results.append(batch_case(guest, "misordered_masters_rc12", load, prefix + ".E6", misordered, mixed, 12))
        results.append(batch_case(guest, "duplicate_master_rc12", load, prefix + ".E7", one + one, mixed, 12))

        def bad_pad(policy_id: str) -> bytes:
            master = bytearray(state(policy_id, 20240101, 1_000_000, 100_000))
            master[41] = 0x01  # SPAD reserved byte must be zero (VL-PAD)
            return bytes(master)
        results.append(batch_case(guest, "invalid_master_rc12", load, prefix + ".E8",
                                  anchors[:128 * 3] + bad_pad("00000004"), mixed, 12))
        results.append(batch_case(guest, "valid_then_invalid_master_rc12", load, prefix + ".E9",
                                  one + bad_pad("00000002"), mixed, 12))
    fault = evidence / "fault"
    outcome = {"case": "host_rejects_half_delivered_txnin", "passed": False,
               "expectation_source": "tools/tk5_validate.py fault_stage: guest RC0 is not acceptance; "
                                     "host comparator must refuse to publish the generation"}
    try:
        with Guest(fault, root) as guest:
            tk5_validate.run(guest, load, prefix + ".F", build_json, guest_json, ("a",), None, "a")
    except RuntimeError as error:
        outcome["host_error"] = str(error)
        outcome["discarded"] = json.loads((fault / "a" / "discarded.json").read_text())
        outcome["receipt"] = json.loads((fault / "a" / "receipt.json").read_text())
        outcome["passed"] = (outcome["receipt"]["step_rc"] == {"RUN": 0}
                             and not (fault / "current.json").exists())
    print("host_rejects_half_delivered_txnin:", "PASS" if outcome["passed"] else "FAIL", flush=True)
    results.append(outcome)
    return results


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--backend", choices=BACKENDS, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--prefix", required=True, help="unique guest dataset prefix")
    parser.add_argument("--root", type=Path, default=Path.home() / "mvs-demo")
    parser.add_argument("--maclib", type=Path, required=True)
    parser.add_argument("--phases", default="build,cases,stages,controls")
    args = parser.parse_args()
    phases = args.phases.split(",")
    load = args.prefix
    args.evidence.mkdir(parents=True, exist_ok=True)
    report: dict = {"schema": "insurance-routines-jobs-v1", "backend": args.backend,
                    "prefix": args.prefix, "phases": {}, "measured_scope": []}
    try:
        if "build" in phases:
            outcome, label = build_phase(args.backend, args.evidence, args.root, load)
            report["phases"]["build"] = outcome
            subprocess.run([sys.executable, str(INSURANCE / "tools/tk5_provenance.py"),
                            "--output", str(args.evidence / "provenance"),
                            "--build-evidence", str(args.evidence / "build" / label),
                            "--backend", args.backend, "--load-prefix", load,
                            "--root", str(args.root), "--macro-export", str(args.maclib)], check=True)
            report["measured_scope"].append(f"{args.backend}: load library built and INSSMOK step RC "
                                            f"{dict(outcome['steps']).get('SMOKE')}")
        build_json = args.evidence / "provenance" / "build.json"
        guest_json = args.evidence / "provenance" / "guest.json"
        if "cases" in phases:
            report["phases"]["cases"] = cases_phase(args.evidence, args.root, load, args.prefix + ".C",
                                                    build_json, guest_json)
            report["measured_scope"].append("job-level cases: " + ", ".join(
                f'{c["case"]}={"PASS" if c["passed"] else "FAIL"}' for c in report["phases"]["cases"]))
        if "stages" in phases:
            with Guest(args.evidence / "stages", args.root) as guest:
                tk5_validate.run(guest, load, args.prefix + ".S", build_json, guest_json,
                                 ("anchors", "a", "a-replay", "b", "b-replay"))
            stages = {}
            for name in ("anchors", "a", "a-replay", "b", "b-replay"):
                folder = args.evidence / "stages" / name
                comparison = json.loads((folder / "comparison.json").read_text())
                stages[name] = {"passed": comparison["passed"],
                                "results": len((folder / "resout.bin").read_bytes()) // 96,
                                "masters": len((folder / "polout.bin").read_bytes()) // 128,
                                "receipt": json.loads((folder / "receipt.json").read_text())}
            report["phases"]["stages"] = stages
            total = sum(s["results"] for s in stages.values())
            report["measured_scope"].append(f"five-stage regression: {total} results, all stages "
                                            f'{"passed" if all(s["passed"] for s in stages.values()) else "FAILED"}')
        if "controls" in phases:
            proc = subprocess.run([sys.executable, str(INSURANCE / "tools/tk5_controls.py"),
                                   "--evidence", str(args.evidence / "controls"),
                                   "--prefix", args.prefix + ".N", "--load-prefix", load,
                                   "--root", str(args.root)], text=True, capture_output=True, check=False)
            (args.evidence / "controls.log").write_text(proc.stdout + proc.stderr)
            print(proc.stdout + proc.stderr, end="", flush=True)
            report["phases"]["controls"] = {"passed": proc.returncode == 0,
                                            "results": json.loads((args.evidence / "controls/controls.json").read_text())
                                            if (args.evidence / "controls/controls.json").exists() else None}
            report["measured_scope"].append(f'job controls: {"PASS" if proc.returncode == 0 else "FAIL"}')
    except Exception:  # keep partial evidence; the report says what did not run
        report["error"] = traceback.format_exc()
        print(report["error"], flush=True)
    passed = ("error" not in report
              and all(c["passed"] for c in report["phases"].get("cases", []))
              and all(s["passed"] for s in report["phases"].get("stages", {}).values())
              and report["phases"].get("controls", {"passed": True})["passed"])
    report["passed"] = passed
    (args.evidence / "jobs.json").write_text(json.dumps(report, indent=1, sort_keys=True) + "\n")
    for line in report["measured_scope"]:
        print("SCOPE", line)
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
