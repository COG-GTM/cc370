#!/usr/bin/env python3
"""Per-routine parity: Java Contract V001 against observed guest calls of the five library
routines (INSPACK, INSDATE, INSRATE, INSVAL, INSCALC).

Authority is the reviewed routine-harness evidence archive, and only after it is bound:

  archive SHA-256 == the pinned value                      (--archive-sha256)
  RECEIPTS.json schema, per-path run receipt: every allocate/assemble/run job step 0000,
      comparison_passed, findings 0, 184 capture records
  capture.bin SHA-256 recomputed == receipt == observed.json == capture-out.aws payload
  cases input recovered from the shipped cases-in.aws tape; its SHA-256 == the fixture
      manifest cases_sha256 == observed.json cases_sha256
  fixture manifest canonical SHA-256 == observed.json fixture_manifest_sha256, and the
      manifest's pinned frozen-source hashes == the baseline checkout (--baseline)
  observed.json: schema insurance-routines-observed-v2, valid, no problems/quarantine,
      184 observations with contiguous ordinals matching the capture and the manifest order
  every observed input/output raw value re-derived from capture.bin bytes

Only then are the guest calls replayed through the Java oracle (``routines`` subcommand of
the single deployable) and compared on logical business outputs:

  INSPACK  R15 0/8 <-> Java packed validity; the 8-byte argument unchanged
  INSDATE  WVALID 0/8 <-> Java validity; WYEAR/WMD/WORD written iff Java writes them, equal
  INSRATE  WRATE/WFEE written iff Java writes them, equal (no date validation, below-table
           inputs leave the fields unchanged)
  INSVAL   WVALID and R15 0/8 <-> Java state validity; SREC unchanged
  INSCALC  SREC and OREC bytes after the call == Java master/result bytes; TREC unchanged

Registers, save areas, guards, scratch and unrelated WORK bytes are assembler-only evidence
(the harness's `abi`/`scratch`/`unasserted` classes) and are not compared here. Java has no
WORK area. expected.json (the harness's source-derived model) is never read.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

ARCHIVE_SHA256 = "3c881564cdc03812b9fb7affacb2634d7c5148d3b01aedaa2661a42f0ff553b2"
RECEIPTS_SCHEMA = "insurance-routines-receipts-v1"
OBSERVED_SCHEMA = "insurance-routines-observed-v2"
MANIFEST_SCHEMA = "insurance-routines-v1"
JAVA_SCHEMA = "insurance-java-routines-v1"
PATHS = ("ifox-iewl", "as370-iewl", "as370-ld370")
# ifox-iewl assembles on the guest; as370-iewl links host-assembled objects on the guest;
# as370-ld370 imports a host-linked load module. Each path carries its own build job.
BUILD_JOBS = ("assemble", "link", "import")
ROUTINES = ("INSPACK", "INSDATE", "INSRATE", "INSVAL", "INSCALC")
EXPECTED_PER_ROUTINE = {"INSPACK": 20, "INSDATE": 26, "INSRATE": 20, "INSVAL": 34, "INSCALC": 84}
CASE_LRECL, CAPTURE_LRECL, WORKLEN = 512, 1536, 460

# Offsets from the harness layout (routines/harness/layout.py at the reviewed commit),
# restated here so this driver has no import dependency on the harness code.
CASE = {"id": (0, 8), "routine": (8, 8), "ordinal": (16, 4), "flags": (20, 1),
        "arg": (24, 8), "work": (32, WORKLEN)}
CAPTURE = {"id": (0, 8), "routine": (8, 8), "ordinal": (16, 4), "flags": (20, 1),
           "regs_after": (88, 64), "arg_after": (328, 8),
           "work_before": (400, WORKLEN), "work_after": (860, WORKLEN)}
R15_IN_REGS = 15 * 4
# Case flags: KEEP carries the previous case's WORK-after into this case's WORK-before;
# STATE/TXN then overlay SREC/TREC from the shipped case record.
FLAG_KEEP, FLAG_STATE, FLAG_TXN = 0x80, 0x40, 0x20
FIELDS = {
    "WDATE": (264, 4), "WYEAR": (268, 4), "WMD": (272, 4), "WORD": (276, 4),
    "WVALID": (280, 4), "TDATE": (140, 4), "WAGE": (296, 4),
    "WRATE": (304, 3), "WFEE": (307, 3),
    "SREC": (0, 128), "TREC": (128, 40), "OREC": (168, 96),
}
FROZEN_SOURCE = ("src/INSPACK.asm", "src/INSDATE.asm", "src/INSRATE.asm", "src/INSVAL.asm",
                 "src/INSCALC.asm", "src/INSBAT.asm", "src/INSSMOK.asm",
                 "copy/INSENT.mac", "copy/INSRET.mac", "copy/INSWORK.copy",
                 "golden/v1/rates.json")


class Reject(Exception):
    pass


def need(cond: bool, what: str) -> None:
    if not cond:
        raise Reject(what)


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256(path.read_bytes())


def slice_(raw: bytes, span: tuple[int, int]) -> bytes:
    off, n = span
    return raw[off:off + n]


def fullword(raw: bytes) -> int:
    return int.from_bytes(raw, "big", signed=True)


def cp037(raw: bytes) -> str:
    return raw.decode("cp037").rstrip(" ")


def packed3(value: int) -> str:
    return f"{value:05d}c"


def aws_files(path: Path) -> list[bytes]:
    """Payloads of an AWS tape image, one bytes object per tape file."""
    data = path.read_bytes()
    files: list[list[bytes]] = [[]]
    i = 0
    while i + 6 <= len(data):
        cur, _prev, flags = struct.unpack("<HHH", data[i:i + 6])
        i += 6
        if flags & 0x4000:
            files.append([])
            continue
        files[-1].append(data[i:i + cur])
        i += cur
    return [b"".join(f) for f in files if f]


def canonical_manifest_sha256(manifest: dict) -> str:
    return sha256(json.dumps(manifest, sort_keys=True).encode())


# ----------------------------------------------------------------------------- binding


def bind_archive(archive: Path, expected: str, dest: Path) -> Path:
    actual = sha256_file(archive)
    need(actual == expected, f"archive sha256 {actual} != pinned {expected}")
    with tarfile.open(archive) as tar:
        for m in tar.getmembers():
            need(not (m.name.startswith("/") or ".." in Path(m.name).parts),
                 f"unsafe archive member {m.name}")
        tar.extractall(dest)
    entries = [p for p in dest.iterdir()]
    root = entries[0] if len(entries) == 1 and entries[0].is_dir() else dest
    need((root / "RECEIPTS.json").is_file(), "RECEIPTS.json missing")
    return root


def bind_receipts(root: Path, paths: tuple[str, ...]) -> dict:
    receipts = json.loads((root / "RECEIPTS.json").read_text())
    need(receipts.get("schema") == RECEIPTS_SCHEMA, "RECEIPTS.json schema")
    need(receipts.get("captures_identical_across_paths") is True,
         "captures differ across paths")
    runs = receipts.get("runs", {})
    for p in paths:
        need(p in runs, f"run receipt for {p} missing")
        r = runs[p]
        need(r.get("comparison_passed") is True, f"{p}: guest comparison did not pass")
        need(r.get("findings") == 0, f"{p}: findings != 0")
        need(r.get("capture_records") == 184, f"{p}: capture_records != 184")
        need(r.get("cases_passed") == 184, f"{p}: cases_passed != 184")
        jobs = r.get("jobs", {})
        need("allocate" in jobs and "run" in jobs, f"{p}: allocate/run job receipt missing")
        need(any(j in jobs for j in BUILD_JOBS), f"{p}: no build/transport job receipt")
        for job, receipt in jobs.items():
            need(receipt.get("passed") is True and receipt.get("errors") == [],
                 f"{p}: {job} job did not pass cleanly")
            steps = receipt.get("steps", [])
            need(steps and all(code == "0000" for _step, code in steps),
                 f"{p}: {job} job has a non-0000 step: {steps}")
        need(receipts["capture_sha256_by_path"].get(p) == r.get("capture_sha256"),
             f"{p}: receipt capture hash inconsistent")
    return receipts


def bind_manifest(manifest_path: Path, baseline: Path) -> tuple[dict, str, dict]:
    manifest = json.loads(manifest_path.read_text())
    need(manifest.get("schema") == MANIFEST_SCHEMA, "fixture manifest schema")
    need(manifest.get("case_count") == 184, "manifest case_count")
    need(manifest.get("case_lrecl") == CASE_LRECL and manifest.get("capture_lrecl") == CAPTURE_LRECL
         and manifest.get("worklen") == WORKLEN, "manifest record geometry")
    need(manifest.get("cases_per_routine") == EXPECTED_PER_ROUTINE, "manifest cases_per_routine")
    order = [tuple(x) for x in manifest["identity_order"]]
    need(len(order) == 184 and len(set(order)) == 184, "manifest identity_order")
    source_check = {}
    for rel in FROZEN_SOURCE:
        pinned = manifest["inputs"].get(rel)
        need(pinned is not None, f"manifest does not pin {rel}")
        local = baseline / rel
        need(local.is_file(), f"baseline file {rel} missing")
        actual = sha256_file(local)
        need(actual == pinned, f"{rel}: baseline {actual} != manifest {pinned}")
        source_check[rel] = pinned
    return manifest, canonical_manifest_sha256(manifest), source_check


def bind_path(root: Path, p: str, receipt: dict, manifest: dict, manifest_sha: str) -> dict:
    d = root / p
    capture_path, aws_out, aws_in, observed_path = (
        d / "capture.bin", d / "capture-out.aws", d / "cases-in.aws", d / "observed.json")
    for f in (capture_path, aws_out, aws_in, observed_path, d / "run.json"):
        need(f.is_file(), f"{p}: {f.name} missing")
    capture = capture_path.read_bytes()
    cap_sha = sha256(capture)
    need(cap_sha == receipt["capture_sha256"], f"{p}: capture.bin hash != receipt")
    tape_out = aws_files(aws_out)
    need(len(tape_out) == 1 and sha256(tape_out[0]) == cap_sha,
         f"{p}: capture-out.aws payload != capture.bin")
    need(len(capture) == 184 * CAPTURE_LRECL, f"{p}: capture.bin length {len(capture)}")
    tape_in = aws_files(aws_in)
    need(len(tape_in) == 1, f"{p}: cases-in.aws has {len(tape_in)} files")
    cases = tape_in[0]
    need(len(cases) == 184 * CASE_LRECL, f"{p}: shipped cases length {len(cases)}")
    cases_sha = sha256(cases)
    need(cases_sha == manifest["cases_sha256"], f"{p}: shipped cases hash != manifest")

    run = json.loads((d / "run.json").read_text())
    need(run.get("capture_sha256") == cap_sha and run.get("cases_sha256") == cases_sha,
         f"{p}: run.json hashes")
    need(run.get("jobs", {}).get("run", {}).get("steps") == [["DRV", "0000"]],
         f"{p}: run.json DRV step")

    observed = json.loads(observed_path.read_text())
    need(observed.get("schema") == OBSERVED_SCHEMA, f"{p}: observed schema")
    need(observed.get("valid") is True, f"{p}: observed not valid")
    need(observed.get("problems") == [] and observed.get("quarantined") == [],
         f"{p}: observed has problems/quarantine")
    need(observed.get("capture_sha256") == cap_sha and observed.get("cases_sha256") == cases_sha,
         f"{p}: observed hashes")
    need(observed.get("fixture_manifest_sha256") == manifest_sha,
         f"{p}: observed manifest hash {observed.get('fixture_manifest_sha256')} != {manifest_sha}")
    obs = observed["observations"]
    need(observed.get("records") == 184 and len(obs) == 184, f"{p}: observation count")

    order = [tuple(x) for x in manifest["identity_order"]]
    records = []
    counts = {r: 0 for r in ROUTINES}
    previous_after = None
    for i in range(184):
        case = cases[i * CASE_LRECL:(i + 1) * CASE_LRECL]
        cap = capture[i * CAPTURE_LRECL:(i + 1) * CAPTURE_LRECL]
        o = obs[i]
        cid, routine = cp037(slice_(case, CASE["id"])), cp037(slice_(case, CASE["routine"]))
        need((cid, routine) == order[i], f"{p}: case {i} identity {cid}/{routine} != manifest")
        need(cp037(slice_(cap, CAPTURE["id"])) == cid
             and cp037(slice_(cap, CAPTURE["routine"])) == routine, f"{p}: capture {i} identity")
        ordinal = fullword(slice_(case, CASE["ordinal"]))
        need(ordinal == i + 1 == fullword(slice_(cap, CAPTURE["ordinal"])),
             f"{p}: ordinal at {i}")
        need(o.get("id") == cid and o.get("routine") == routine and o.get("ordinal") == ordinal,
             f"{p}: observation {i} identity")
        need(routine in counts, f"{p}: unknown routine {routine}")
        counts[routine] += 1
        work_before = slice_(cap, CAPTURE["work_before"])
        work_after = slice_(cap, CAPTURE["work_after"])
        arg = slice_(case, CASE["arg"])
        arg_after = slice_(cap, CAPTURE["arg_after"])
        flags = case[CASE["flags"][0]]
        shipped = slice_(case, CASE["work"])
        if flags & FLAG_KEEP:
            need(previous_after is not None, f"{p}: {cid} KEEP without a previous case")
            expected = bytearray(previous_after)
            if flags & FLAG_STATE:
                expected[0:128] = shipped[0:128]
            if flags & FLAG_TXN:
                expected[128:168] = shipped[128:168]
            need(work_before == bytes(expected),
                 f"{p}: {cid} WORK before != previous WORK after with flag overlays")
        else:
            need(work_before == shipped, f"{p}: {cid} WORK before != shipped case")
        previous_after = work_after
        need(o["input"]["work_before"] == work_before.hex(), f"{p}: {cid} observed work_before")
        need(o["input"]["arg"] == arg.hex(), f"{p}: {cid} observed arg")
        r15 = fullword(slice_(cap, CAPTURE["regs_after"])[R15_IN_REGS:R15_IN_REGS + 4])
        biz = o["business"]
        if biz.get("r15") is not None:
            need(biz["r15"] == r15, f"{p}: {cid} observed r15 {biz['r15']} != capture {r15}")
        if biz.get("arg_after") is not None:
            need(biz["arg_after"] == arg_after.hex(), f"{p}: {cid} observed arg_after")
        for name, out in biz.get("outputs", {}).items():
            span = FIELDS.get(name) or _record_field(name)
            need(span is not None, f"{p}: {cid} unknown output field {name}")
            raw = slice_(work_after, span)
            need(out["raw"] == raw.hex(), f"{p}: {cid} observed {name} raw != capture")
            need(out["changed"] == (raw != slice_(work_before, span)),
                 f"{p}: {cid} observed {name} changed flag != capture")
        records.append({
            "ordinal": ordinal, "id": cid, "routine": routine, "arg": arg, "arg_after": arg_after,
            "work_before": work_before, "work_after": work_after, "r15": r15,
            "inputs_unchanged": biz.get("inputs_unchanged"),
            "outputs": biz.get("outputs", {}),
        })
    need(counts == EXPECTED_PER_ROUTINE, f"{p}: routine counts {counts}")
    return {"records": records, "capture_sha256": cap_sha, "cases_sha256": cases_sha,
            "observed_sha256": sha256_file(observed_path), "run_json_sha256": sha256_file(d / "run.json"),
            "manifest_sha256_at_run": run.get("fixture_manifest_sha256"),
            "manifest_sha256_at_observe": observed.get("fixture_manifest_sha256")}


_RECORD_FIELDS = {
    "SID": (0, 8), "SISSUE": (8, 4), "SDATE": (12, 4), "SSEQ": (16, 4), "SFACE": (20, 7),
    "SCASH": (27, 7), "SLOAN": (34, 7), "SPAD": (41, 7), "SLAST": (48, 40), "STAIL": (88, 40),
    "TID": (128, 8), "TSEQ": (136, 4), "TOP": (144, 1), "TPAD": (145, 3), "TAMT": (148, 7),
    "TTAIL": (155, 13),
    "OID": (168, 8), "OSEQ": (176, 4), "ODATE": (180, 4), "OSTAT": (184, 4), "OOP": (188, 1),
    "OPAD": (189, 3), "OAGE": (192, 4), "ORATE": (196, 3), "OFEE": (199, 3), "OCASH": (202, 7),
    "OSURR": (209, 7), "ODEATH": (216, 7), "OLOAN": (223, 7), "OINT": (230, 7), "OCHG": (237, 7),
    "OVERS": (244, 4), "OTAIL": (248, 16),
}


def _record_field(name: str) -> tuple[int, int] | None:
    return _RECORD_FIELDS.get(name)


# ----------------------------------------------------------------------------- Java oracle


def java_cases(records: list[dict]) -> dict:
    cases = []
    for r in records:
        wb = r["work_before"]
        c = {"id": r["id"], "routine": r["routine"]}
        if r["routine"] == "INSPACK":
            c["arg"] = r["arg"][:7].hex()
        elif r["routine"] == "INSDATE":
            c["wdate"] = fullword(slice_(wb, FIELDS["WDATE"]))
        elif r["routine"] == "INSRATE":
            c["tdate"] = fullword(slice_(wb, FIELDS["TDATE"]))
            c["wage"] = fullword(slice_(wb, FIELDS["WAGE"]))
        elif r["routine"] == "INSVAL":
            c["srec"] = slice_(wb, FIELDS["SREC"]).hex()
        else:
            c["srec"] = slice_(wb, FIELDS["SREC"]).hex()
            c["trec"] = slice_(wb, FIELDS["TREC"]).hex()
        cases.append(c)
    return {"cases": cases}


def run_java(java_cmd: list[str], cases: dict, workdir: Path) -> dict:
    cases_path, out_path = workdir / "java-cases.json", workdir / "java-outputs.json"
    cases_path.write_text(json.dumps(cases, indent=1) + "\n")
    proc = subprocess.run(java_cmd + ["routines", "--cases", str(cases_path), "--out", str(out_path)],
                          capture_output=True, text=True)
    need(proc.returncode == 0, f"java oracle rc={proc.returncode}: {proc.stderr.strip()}")
    report = json.loads(out_path.read_text())
    need(report.get("schema") == JAVA_SCHEMA, "java report schema")
    need(len(report["outputs"]) == len(cases["cases"]), "java output count")
    return report


# ----------------------------------------------------------------------------- comparison


def field_written(rec: dict, name: str) -> tuple[bool, str]:
    span = FIELDS[name]
    before, after = slice_(rec["work_before"], span), slice_(rec["work_after"], span)
    return after != before, after.hex()


def compare_written(rec: dict, name: str, java_value: str | None, diffs: list[str]) -> None:
    """A field Java writes must hold that value after the call; one Java leaves alone must be
    untouched (a coincidental equal write is indistinguishable from no write and accepted)."""
    changed, after = field_written(rec, name)
    if java_value is None:
        if changed:
            diffs.append(f"{name}: guest wrote {after}, Java writes nothing")
    elif after != java_value:
        diffs.append(f"{name}: guest {after} (changed={changed}), Java {java_value}")


def compare_one(rec: dict, out: dict) -> list[str]:
    diffs: list[str] = []
    routine = rec["routine"]
    if out.get("fault") is not None:
        return [f"Java fault: {out['fault']}"]
    if routine == "INSPACK":
        expect_r15 = 0 if out["valid"] else 8
        if rec["r15"] != expect_r15:
            diffs.append(f"R15: guest {rec['r15']}, Java validity implies {expect_r15}")
        if rec["arg_after"] != rec["arg"]:
            diffs.append("argument bytes changed by guest")
        if rec["work_after"] != rec["work_before"]:
            diffs.append("WORK changed by INSPACK")
    elif routine == "INSDATE":
        compare_written(rec, "WVALID", struct.pack(">i", 0 if out["valid"] else 8).hex(), diffs)
        for name, key in (("WYEAR", "year"), ("WMD", "monthDay"), ("WORD", "ordinal")):
            v = out.get(key)
            compare_written(rec, name, None if v is None else struct.pack(">i", v).hex(), diffs)
        if field_written(rec, "WDATE")[0]:
            diffs.append("WDATE input changed by guest")
    elif routine == "INSRATE":
        for name, key in (("WRATE", "rateBps"), ("WFEE", "feeBps")):
            v = out.get(key)
            compare_written(rec, name, None if v is None else packed3(v), diffs)
        for name in ("TDATE", "WAGE"):
            if field_written(rec, name)[0]:
                diffs.append(f"{name} input changed by guest")
    elif routine == "INSVAL":
        expect = 0 if out["valid"] else 8
        compare_written(rec, "WVALID", struct.pack(">i", expect).hex(), diffs)
        if rec["r15"] != expect:
            diffs.append(f"R15: guest {rec['r15']}, Java validity implies {expect}")
        if field_written(rec, "SREC")[0]:
            diffs.append("SREC changed by INSVAL")
    else:
        for name, key in (("SREC", "srec"), ("OREC", "orec")):
            after = slice_(rec["work_after"], FIELDS[name]).hex()
            if after != out[key]:
                diffs.append(f"{name}: guest {after} != Java {out[key]}")
        if field_written(rec, "TREC")[0]:
            diffs.append("TREC changed by guest")
        if rec["r15"] != 0:
            diffs.append(f"R15: guest {rec['r15']} (fixed 0 expected)")
    if rec["inputs_unchanged"] is not True:
        diffs.append("observed.json reports inputs changed")
    return diffs


def compare(records: list[dict], report: dict) -> tuple[list[dict], dict]:
    by_id = {o["id"]: o for o in report["outputs"]}
    results, per_routine = [], {r: {"cases": 0, "passed": 0} for r in ROUTINES}
    for rec in records:
        out = by_id.get(rec["id"])
        need(out is not None and out["routine"] == rec["routine"], f"java output for {rec['id']}")
        diffs = compare_one(rec, out)
        per_routine[rec["routine"]]["cases"] += 1
        per_routine[rec["routine"]]["passed"] += not diffs
        entry = {"ordinal": rec["ordinal"], "id": rec["id"], "routine": rec["routine"],
                 "passed": not diffs, "diffs": diffs}
        if rec["routine"] == "INSCALC":
            entry["ostat"] = cp037(slice_(rec["work_after"], _RECORD_FIELDS["OSTAT"]))
            entry["java_status"] = out.get("status")
        results.append(entry)
    return results, per_routine


# ----------------------------------------------------------------------------- main


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--archive", required=True, type=Path)
    ap.add_argument("--archive-sha256", default=ARCHIVE_SHA256)
    ap.add_argument("--routine-manifest", required=True, type=Path,
                    help="fixtures/v1/manifest.json at the reviewed routine-harness commit")
    ap.add_argument("--routine-commit", required=True, help="reviewed routine-harness commit (recorded)")
    ap.add_argument("--baseline", required=True, type=Path,
                    help="samples/insurance of the frozen baseline checkout (source hashes)")
    ap.add_argument("--java", required=True, help="Java oracle command, e.g. 'java -jar ledger-app.jar'")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--paths", nargs="+", default=list(PATHS), choices=list(PATHS))
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix="routine-evidence-", dir=str(args.out)))
    report = {"schema": "insurance-java-routine-parity-v1", "authority": "A3-routine (observed guest calls)",
              "archive_sha256": args.archive_sha256, "routine_commit": args.routine_commit,
              "passed": False, "paths": {}, "rejections": []}
    try:
        root = bind_archive(args.archive, args.archive_sha256, scratch)
        receipts = bind_receipts(root, tuple(args.paths))
        manifest, manifest_sha, sources = bind_manifest(args.routine_manifest, args.baseline)
        report["fixture_manifest_sha256"] = manifest_sha
        report["frozen_source_sha256"] = sources
        report["fixture_cases_sha256"] = manifest["cases_sha256"]
        java_cmd = args.java.split()
        all_pass = True
        for p in args.paths:
            bound = bind_path(root, p, receipts["runs"][p], manifest, manifest_sha)
            java = run_java(java_cmd, java_cases(bound["records"]), args.out)
            results, per_routine = compare(bound["records"], java)
            failures = [r for r in results if not r["passed"]]
            all_pass &= not failures
            (args.out / f"routine-results-{p}.json").write_text(json.dumps(results, indent=1) + "\n")
            report["paths"][p] = {
                "capture_sha256": bound["capture_sha256"], "cases_sha256": bound["cases_sha256"],
                "observed_sha256": bound["observed_sha256"], "run_json_sha256": bound["run_json_sha256"],
                "manifest_sha256_at_run": bound["manifest_sha256_at_run"],
                "manifest_sha256_at_observe": bound["manifest_sha256_at_observe"],
                "cases": len(results), "passed": len(results) - len(failures),
                "per_routine": per_routine,
                "inscalc_status_histogram": _histogram(r["ostat"] for r in results if r["routine"] == "INSCALC"),
                "failures": failures[:50],
            }
            report["java"] = {k: java[k] for k in ("build_identity", "runtime_identity", "rate_table_sha256")}
        report["passed"] = all_pass
    except Reject as e:
        report["rejections"].append(str(e))
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    (args.out / "routine-parity.json").write_text(json.dumps(report, indent=1) + "\n")
    _print_summary(report)
    return 0 if report["passed"] else 1


def _histogram(values) -> dict:
    h: dict[str, int] = {}
    for v in values:
        h[v] = h.get(v, 0) + 1
    return dict(sorted(h.items()))


def _print_summary(report: dict) -> None:
    if report["rejections"]:
        print("REJECTED:", *report["rejections"], sep="\n  ")
        return
    for p, info in report["paths"].items():
        print(f"{p}: {info['passed']}/{info['cases']} cases  "
              + "  ".join(f"{r}={v['passed']}/{v['cases']}" for r, v in info["per_routine"].items()))
        for f in info["failures"][:10]:
            print(f"  FAIL {f['id']} {f['routine']}: " + "; ".join(f["diffs"]))
    print("PASSED" if report["passed"] else "FAILED")


if __name__ == "__main__":
    sys.exit(main())
