"""Run the frozen corpus on a real TK5 guest under one generation-writer lock."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from codec import decode, records
from tk5 import Guest, ROOT


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def save(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def run(guest: Guest, load: str, prefix: str, build: Path,
        manifest: Path, names: tuple[str, ...],
        resume: Path | None = None, fault_stage: str | None = None) -> None:
    golden = ROOT / "golden/v1"
    stages = json.loads((golden / "coverage.json").read_text())["stages"]
    current = None
    if resume:
        published = json.loads(resume.read_text())
        current = Path(published["master"])
        if sha(current.read_bytes()) != published["sha256"]:
            raise ValueError("Published master hash mismatch")
        comparison = json.loads((current.parent / "comparison.json").read_text())
        if comparison["passed"] is not True:
            raise ValueError("Cannot resume from an unvalidated generation")
        save(guest.evidence / "current.json", published)
    for index, name in enumerate(names, 1):
        stage = next(stage for stage in stages if stage["name"] == name)
        directory = guest.evidence / name
        directory.mkdir()
        input_path = golden / stage["input_master"] if name in ("anchors", "a") else current
        if input_path is None:
            raise ValueError(f"{name} requires a validated previous guest generation")
        policies, transactions = input_path.read_bytes(), (golden / stage["transactions"]).read_bytes()
        if name == fault_stage:
            transactions = transactions[:len(transactions) // 80 * 40]
        (directory / "polin.bin").write_bytes(policies)
        (directory / "txnin.bin").write_bytes(transactions)
        save(directory / "generation.json", {
            "status": "running", "input": str(input_path), "input_sha256": sha(policies),
            "dataset_prefix": f"{prefix}.G{index:02d}",
            "fault_injection": "partial transaction delivery" if name == fault_stage else None,
        })
        polout, resout, job = guest.batch(name, load, f"{prefix}.G{index:02d}",
                                         policies, transactions)
        (directory / "polout.bin").write_bytes(polout)
        (directory / "resout.bin").write_bytes(resout)
        receipt = {
            "schema": "insurance-run-v1", "job_id": job["job_id"],
            "outcome": "completed" if job["passed"] else "failed",
            "timed_out": not job["purged"], "abend": None,
            "step_rc": {step: int(code) for step, code in job["steps"]},
            "polin_sha256": sha(policies), "txnin_sha256": sha(transactions),
            "polout_sha256": sha(polout), "resout_sha256": sha(resout),
            "rates_sha256": sha((golden / "rates.json").read_bytes()),
            "build_manifest_sha256": sha(build.read_bytes()),
            "guest_manifest_sha256": sha(manifest.read_bytes()),
            "job_result_sha256": sha((guest.evidence / (name + "-run") / "result.json").read_bytes()),
            "input_generation": str(input_path),
        }
        save(directory / "receipt.json", receipt)
        command = [
            sys.executable, str(ROOT / "tools/compare.py"), "--stage", name,
            "--golden", str(golden), "--polout", str(directory / "polout.bin"),
            "--resout", str(directory / "resout.bin"),
            "--receipt", str(directory / "receipt.json"),
            "--build-manifest", str(build), "--guest-manifest", str(manifest),
            "--report", str(directory / "comparison.json"),
        ]
        result = subprocess.run(command, text=True, capture_output=True, check=False)
        (directory / "comparison.log").write_text(result.stdout + result.stderr)
        print(result.stdout + result.stderr, end="", flush=True)
        if result.returncode:
            save(directory / "discarded.json", {
                "reason": "receipt or byte comparison failed",
                "recovery": "rerun from the previous published master with complete inputs",
                "published": False,
            })
            raise RuntimeError(f"{name}: comparison failed; generation NOT published")
        save(directory / "generation.json", {
            "status": "validated", "input": str(input_path), "input_sha256": sha(policies),
            "dataset_prefix": f"{prefix}.G{index:02d}", "receipt": receipt,
        })
        if name in ("a", "b"):
            current = directory / "polout.bin"
            pending = guest.evidence / "current.pending"
            save(pending, {"generation": name, "master": str(current), "sha256": sha(polout)})
            os.replace(pending, guest.evidence / "current.json")
        if name == "anchors":
            actual = [decode(record, "result") for record in records(resout, "result")]
            save(directory / "decoded-results.json", actual)
            print("A001 ACTUAL MVS OUTPUT:", json.dumps(actual[0], sort_keys=True), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=Path("/home/ubuntu/mvs-demo"))
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--load-prefix", required=True)
    parser.add_argument("--build-manifest", type=Path, required=True)
    parser.add_argument("--guest-manifest", type=Path, required=True)
    parser.add_argument("--stages", default="anchors,a,a-replay,b,b-replay")
    parser.add_argument("--resume-from", type=Path,
                        help="Published current.json from an earlier completed process")
    parser.add_argument("--fault-stage", choices=("a", "b"),
                        help="Negative control: deliberately deliver only half the transactions")
    args = parser.parse_args()
    with Guest(args.evidence, args.root) as guest:
        run(guest, args.load_prefix, args.prefix, args.build_manifest, args.guest_manifest,
            tuple(args.stages.split(",")), args.resume_from, args.fault_stage)


if __name__ == "__main__":
    main()
