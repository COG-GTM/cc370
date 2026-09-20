"""Strict binary result/master acceptance gate for a captured guest run."""

import argparse
import hashlib
import json
from pathlib import Path

from codec import SCHEMAS, decode, records


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def compare(expected: bytes, observed: bytes, kind: str) -> None:
    wanted, actual = records(expected, kind), records(observed, kind)
    if len(wanted) != len(actual):
        raise ValueError(f"{kind} record count: expected {len(wanted)}, observed {len(actual)}"
                         " (missing, duplicate, or unexpected records)")
    for index, (left, right) in enumerate(zip(wanted, actual), 1):
        if left == right:
            continue
        for field in SCHEMAS[kind][1]:
            start, end = field.offset, field.offset + field.size
            if left[start:end] != right[start:end]:
                try:
                    values = (decode(left, kind)[field.name], decode(right, kind)[field.name])
                except ValueError as error:
                    values = (left[start:end].hex(), str(error))
                raise ValueError(
                    f"{kind} record {index}, id={left[:8].decode('cp037')!r}, "
                    f"field {field.name} bytes [{start}:{end}): "
                    f"expected {values[0]!r} [{left[start:end].hex()}], "
                    f"observed {values[1]!r} [{right[start:end].hex()}]"
                )
        raise ValueError(f"{kind} record {index}: undeclared byte mismatch")


def validate_receipt(receipt: dict[str, object], hashes: dict[str, str]) -> None:
    if receipt.get("schema") != "insurance-run-v1":
        raise ValueError("missing/unsupported run receipt schema")
    if not isinstance(receipt.get("job_id"), str) or not receipt["job_id"]:
        raise ValueError("missing job_id")
    if receipt.get("outcome") != "completed" or receipt.get("timed_out") is not False:
        raise ValueError("run did not complete without timeout")
    if "abend" not in receipt or receipt["abend"] is not None:
        raise ValueError("ABEND or missing ABEND evidence")
    rc = receipt.get("step_rc")
    if not isinstance(rc, dict) or "RUN" not in rc:
        raise ValueError("missing RUN step RC")
    if any(type(value) is not int or value != 0 for value in rc.values()):
        raise ValueError(f"nonzero/invalid step RC: {rc}")
    for name, value in hashes.items():
        if receipt.get(name) != value:
            raise ValueError(f"receipt hash mismatch: {name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--stage", required=True)
    parser.add_argument("--polout", type=Path, required=True)
    parser.add_argument("--resout", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--build-manifest", type=Path, required=True)
    parser.add_argument("--guest-manifest", type=Path, required=True,
                        help="guest OS/IFOX00/IEWL/macro revision and run provenance")
    args = parser.parse_args()
    try:
        inventory = (args.golden / "SHA256SUMS").read_text().splitlines()
        for line in inventory:
            digest, name = line.split("  ", 1)
            if sha((args.golden / name).read_bytes()) != digest:
                raise ValueError(f"modified golden artifact: {name}")
        coverage = json.loads((args.golden / "coverage.json").read_text())
        stage = next(s for s in coverage["stages"] if s["name"] == args.stage)
        observed_pol, observed_res = args.polout.read_bytes(), args.resout.read_bytes()
        hashes = {
            "polin_sha256": sha((args.golden / stage["input_master"]).read_bytes()),
            "txnin_sha256": sha((args.golden / stage["transactions"]).read_bytes()),
            "polout_sha256": sha(observed_pol), "resout_sha256": sha(observed_res),
            "build_manifest_sha256": sha(args.build_manifest.read_bytes()),
            "guest_manifest_sha256": sha(args.guest_manifest.read_bytes()),
            "rates_sha256": sha((args.golden / "rates.json").read_bytes()),
        }
        validate_receipt(json.loads(args.receipt.read_text()), hashes)
        compare((args.golden / stage["expected_results"]).read_bytes(), observed_res, "result")
        compare((args.golden / stage["expected_master"]).read_bytes(), observed_pol, "state")
    except (ValueError, OSError, StopIteration, KeyError, TypeError) as error:
        raise SystemExit(f"FAIL: {error}") from error
    print(f"PASS: {args.stage}; all result/state fields and receipt hashes match")


if __name__ == "__main__":
    main()
