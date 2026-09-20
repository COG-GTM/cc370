"""Prove persisted restart and discard/rerun using separate real-guest controllers."""

import argparse
import json
from pathlib import Path
import subprocess
import sys

from tk5 import ROOT
from tk5_validate import save, sha


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--load-prefix", required=True)
    parser.add_argument("--build-manifest", type=Path, required=True)
    parser.add_argument("--guest-manifest", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=Path("/home/ubuntu/mvs-demo"))
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=False)
    common = [
        sys.executable, str(ROOT / "tools/tk5_validate.py"), "--root", str(args.root),
        "--load-prefix", args.load_prefix, "--build-manifest", str(args.build_manifest),
        "--guest-manifest", str(args.guest_manifest),
    ]
    phases = [
        ("checkpoint", "a,a-replay", [], 0),
        ("partial", "b", ["--resume-from", str(args.evidence / "checkpoint/current.json"),
                           "--fault-stage", "b"], 1),
        ("restart", "b,b-replay", ["--resume-from", str(args.evidence / "checkpoint/current.json")], 0),
    ]
    results = []
    for index, (phase, stages, extra, expected_rc) in enumerate(phases, 1):
        command = [*common, "--evidence", str(args.evidence / phase),
                   "--prefix", f"{args.prefix}.R{index}", "--stages", stages, *extra]
        process = subprocess.run(command, check=False, text=True, capture_output=True)
        (args.evidence / (phase + ".log")).write_text(process.stdout + process.stderr)
        print(process.stdout + process.stderr, end="", flush=True)
        if process.returncode != expected_rc:
            raise ValueError(f"{phase}: expected controller RC={expected_rc}, got {process.returncode}")
        results.append({"phase": phase, "command": command, "controller_rc": process.returncode})
        if phase == "partial":
            before = json.loads((args.evidence / "checkpoint/current.json").read_text())
            after = json.loads((args.evidence / "partial/current.json").read_text())
            discarded = json.loads((args.evidence / "partial/b/discarded.json").read_text())
            if before != after or discarded["published"] is not False:
                raise ValueError("Partial run changed the published generation")
    final = json.loads((args.evidence / "restart/current.json").read_text())
    master = Path(final["master"]).read_bytes()
    if master != (ROOT / "golden/v1/b.expected.pol.bin").read_bytes():
        raise ValueError("Restart from persisted guest output differs from uninterrupted expectation")
    save(args.evidence / "recovery.json", {
        "phases": results, "partial_generation_discarded": True,
        "restart_master_sha256": sha(master), "passed": True,
        "fault": "Only half of stage B transactions delivered; strict receipt and byte checks rejected it",
    })
    print("PASS: persisted restart; partial generation rejected; rerun from unchanged checkpoint")


if __name__ == "__main__":
    main()
