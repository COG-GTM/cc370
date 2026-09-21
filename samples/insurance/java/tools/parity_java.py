#!/usr/bin/env python3
"""Java parity driver: run the Java batch adapter (and, later, the HTTP modes) over every corpus
stage against ONE authority and compare byte-for-byte with tools/compare.py's comparator.

Authorities (never mixed inside one run):
  a1  generated expectations   samples/insurance/golden/v1 (coverage.json, cases.jsonl, *.bin)
  a2  archived observed output  <evidence>/runtime/<path>/<stage>/{polin,txnin,polout,resout}.bin
  a3  fresh observed output     same layout as a2, produced by a fresh Hercules/MVS run

Each mode keeps its own state chain: stage N+1 is seeded from the Java generation published for
stage N (the adapter refuses to seed if that generation's bytes differ from the authority's input
master for stage N+1). The chain stops at the first failing stage. tools/compare.py is imported
unchanged; Java receipts are validated here, with every hash recomputed from the actual bytes.

Working directory: run from anywhere, all paths are explicit.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
SAMPLE = HERE.parent.parent  # samples/insurance
sys.path.insert(0, str(SAMPLE / "tools"))

from compare import differences  # noqa: E402  (unchanged legacy comparator)

CHAIN = ["a", "a-replay", "b", "b-replay"]
ROOTS = {"anchors": ["anchors"], "a": CHAIN}
RECEIPT_SCHEMA = "insurance-java-run-v1"
MANIFEST_SCHEMA = "insurance-expected-manifest-v1"
RECORD_SCHEMA = "POLIN/POLOUT FB128, TXNIN FB40, RESOUT FB96, CP037"


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def rate_table_sha(rates_json: Path) -> str:
    """Canonical form the Java receipt binds: 'effective,rate_bps,fee_bps\\n' per row."""
    rows = json.loads(rates_json.read_text())
    return sha("".join(f"{e},{r},{f}\n" for e, r, f in rows).encode("ascii"))


class Authority:
    def __init__(self, kind: str, root: Path, golden: Path):
        self.kind, self.root, self.golden = kind, root, golden
        self.coverage = json.loads((golden / "coverage.json").read_text())
        self.stages = {s["name"]: s for s in self.coverage["stages"]}
        cases = [json.loads(l) for l in (golden / "cases.jsonl").read_text().splitlines()]
        self.case_ids: dict[str, dict[int, str]] = {}
        for c in cases:
            self.case_ids.setdefault(c["stage"], {})[c["record"]] = c["id"]

    def stage_bytes(self, stage: str) -> dict[str, bytes]:
        s = self.stages[stage]
        if self.kind == "a1":
            return {
                "polin": (self.golden / s["input_master"]).read_bytes(),
                "txnin": (self.golden / s["transactions"]).read_bytes(),
                "polout": (self.golden / s["expected_master"]).read_bytes(),
                "resout": (self.golden / s["expected_results"]).read_bytes(),
            }
        d = self.root / stage
        return {k: (d / f"{k}.bin").read_bytes() for k in ("polin", "txnin", "polout", "resout")}

    def counts(self, stage: str) -> tuple[int, int]:
        s = self.stages[stage]
        return s["policies_count"], s["transactions_count"]


def validate_java_receipt(receipt: dict, expected: dict[str, object]) -> list[str]:
    errors = []
    if receipt.get("schema") != RECEIPT_SCHEMA:
        errors.append(f"receipt schema {receipt.get('schema')!r} != {RECEIPT_SCHEMA}")
    if receipt.get("record_schema") != RECORD_SCHEMA:
        errors.append("receipt record_schema mismatch")
    if receipt.get("outcome") != "completed" or receipt.get("return_code") != 0:
        errors.append(f"outcome/return_code: {receipt.get('outcome')}/{receipt.get('return_code')}")
    if receipt.get("publication_status") != "PUBLISHED":
        errors.append(f"publication_status {receipt.get('publication_status')}")
    for key, value in expected.items():
        if receipt.get(key) != value:
            errors.append(f"receipt {key}: {receipt.get(key)!r} != expected {value!r}")
    if receipt.get("results_count") != receipt.get("transactions_count"):
        errors.append("results_count != transactions_count")
    return errors


def java(jar: Path, *args: str) -> subprocess.CompletedProcess[str]:
    cmd = [os.environ.get("JAVA", "java"), "-jar", str(jar), "batch", *args]
    return subprocess.run(cmd, capture_output=True, text=True)


def run_stage(args, auth: Authority, jar_sha: str, rates_sha: str, ns: str, parent: str,
              stage: str, work: Path) -> dict:
    data = auth.stage_bytes(stage)
    policies, txns = auth.counts(stage)
    gen = f"{ns}-{stage}"
    stage_dir = work / "stages" / f"{ns}--{stage}"
    stage_dir.mkdir(parents=True, exist_ok=True)
    for k in ("polin", "txnin"):
        (stage_dir / f"{k}.bin").write_bytes(data[k])
    manifest = {
        "schema": MANIFEST_SCHEMA, "stage": stage,
        "policies_count": policies, "transactions_count": txns,
        "polin_sha256": sha(data["polin"]), "txnin_sha256": sha(data["txnin"]),
        "rates_sha256": rates_sha,
    }
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    (stage_dir / "manifest.json").write_bytes(manifest_bytes)
    out_dir = stage_dir / "out"
    started = time.time()
    proc = java(args.jar, "run", "--store", str(work / "store"), "--namespace", ns,
                "--parent", parent, "--generation", gen,
                "--polin", str(stage_dir / "polin.bin"), "--txnin", str(stage_dir / "txnin.bin"),
                "--manifest", str(stage_dir / "manifest.json"), "--out", str(out_dir),
                "--source-commit", args.source_commit)
    result = {
        "stage": stage, "namespace": ns, "generation": gen, "parent": parent,
        "authority_input_sha256": {"polin": manifest["polin_sha256"],
                                   "txnin": manifest["txnin_sha256"]},
        "authority_expected_sha256": {"polout": sha(data["polout"]), "resout": sha(data["resout"])},
        "expected_counts": {"policies": policies, "transactions": txns},
        "java_exit_code": proc.returncode, "seconds": round(time.time() - started, 3),
        "errors": [], "mismatches": [], "receipt_errors": [],
    }
    if proc.returncode != 0:
        result["errors"].append(f"java exit {proc.returncode}: {proc.stderr.strip()[:2000]}")
        result["passed"] = False
        return result
    polout = (out_dir / "polout.bin").read_bytes()
    resout = (out_dir / "resout.bin").read_bytes()
    receipt = json.loads((out_dir / "receipt.json").read_text())
    result["observed_sha256"] = {"polout": sha(polout), "resout": sha(resout)}
    result["receipt_errors"] = validate_java_receipt(receipt, {
        "mode": "batch", "stage": stage, "namespace": ns, "generation": gen,
        "parent_generation": parent, "source_commit": args.source_commit,
        "build_identity": f"jar:sha256:{jar_sha}", "rate_table_sha256": rates_sha,
        "expected_manifest_sha256": sha(manifest_bytes),
        "polin_sha256": sha(data["polin"]), "txnin_sha256": sha(data["txnin"]),
        "polout_sha256": sha(polout), "resout_sha256": sha(resout),
        "policies_count": policies, "transactions_count": txns, "results_count": txns,
        "typed_requests": 0, "raw_requests": txns,
    })
    result["mismatches"] = differences(data["resout"], resout, "result",
                                       auth.case_ids.get(stage, {}))
    result["mismatches"] += differences(data["polout"], polout, "state")
    result["receipt"] = receipt
    result["passed"] = not result["errors"] and not result["mismatches"] \
        and not result["receipt_errors"]
    return result


def bootstrap(args, auth: Authority, ns: str, stage: str, work: Path) -> dict:
    data = auth.stage_bytes(stage)
    policies, _ = auth.counts(stage)
    root_dir = work / "stages" / f"{ns}--root"
    root_dir.mkdir(parents=True, exist_ok=True)
    (root_dir / "polin.bin").write_bytes(data["polin"])
    manifest = {"schema": MANIFEST_SCHEMA, "stage": f"{stage}-root",
                "policies_count": policies, "transactions_count": 0,
                "polin_sha256": sha(data["polin"]), "txnin_sha256": sha(b""),
                "rates_sha256": None}
    (root_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    proc = java(args.jar, "bootstrap", "--store", str(work / "store"), "--namespace", ns,
                "--generation", f"{ns}-root", "--polin", str(root_dir / "polin.bin"),
                "--manifest", str(root_dir / "manifest.json"))
    return {"namespace": ns, "generation": f"{ns}-root", "java_exit_code": proc.returncode,
            "polin_sha256": manifest["polin_sha256"],
            "stderr": proc.stderr.strip()[:2000] if proc.returncode else ""}


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--authority", choices=["a1", "a2", "a3"], required=True)
    p.add_argument("--authority-dir", type=Path,
                   help="a2/a3: directory holding <stage>/{polin,txnin,polout,resout}.bin")
    p.add_argument("--golden", type=Path, default=SAMPLE / "golden" / "v1",
                   help="golden dir for coverage.json/cases.jsonl/rates.json (record ids, counts)")
    p.add_argument("--jar", type=Path, required=True)
    p.add_argument("--work", type=Path, required=True, help="fresh working directory")
    p.add_argument("--report", type=Path, required=True, help="JSON report path")
    p.add_argument("--source-commit", required=True)
    p.add_argument("--label", default="", help="free-text label (e.g. evidence path name)")
    args = p.parse_args()
    if args.authority != "a1" and not args.authority_dir:
        p.error("--authority-dir is required for a2/a3")
    auth = Authority(args.authority, args.authority_dir or args.golden, args.golden)
    args.work.mkdir(parents=True, exist_ok=True)
    jar_sha = sha(args.jar.read_bytes())
    rates_sha = rate_table_sha(args.golden / "rates.json")
    report = {
        "schema": "insurance-java-parity-v1", "mode": "batch",
        "authority": {"kind": args.authority, "label": args.label,
                      "dir": str(args.authority_dir or args.golden),
                      "golden_rates_json_sha256": sha((args.golden / "rates.json").read_bytes()),
                      "rate_table_canonical_sha256": rates_sha},
        "jar_sha256": jar_sha, "source_commit": args.source_commit,
        "java_version": subprocess.run([os.environ.get("JAVA", "java"), "-version"],
                                       capture_output=True, text=True).stderr.strip(),
        "roots": [], "stages": [], "stopped_at": None,
    }
    for ns, stages in ROOTS.items():
        boot = bootstrap(args, auth, ns, stages[0], args.work)
        report["roots"].append(boot)
        if boot["java_exit_code"] != 0:
            report["stopped_at"] = f"{ns}:bootstrap"
            break
        parent = boot["generation"]
        for stage in stages:
            r = run_stage(args, auth, jar_sha, rates_sha, ns, parent, stage, args.work)
            report["stages"].append(r)
            if not r["passed"]:
                report["stopped_at"] = f"{ns}:{stage}"
                break
            parent = r["generation"]
        if report["stopped_at"]:
            break
    expected = sum(len(v) for v in ROOTS.values())
    report["stages_passed"] = sum(1 for s in report["stages"] if s["passed"])
    report["stages_expected"] = expected
    report["results_compared"] = sum(s["expected_counts"]["transactions"]
                                     for s in report["stages"] if s["passed"])
    report["passed"] = report["stages_passed"] == expected and not report["stopped_at"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    status = "PASS" if report["passed"] else "FAIL"
    print(f"{status}: authority={args.authority} mode=batch stages "
          f"{report['stages_passed']}/{expected}, results compared {report['results_compared']}; "
          f"report {args.report}")
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
