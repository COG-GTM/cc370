"""Strict binary result/master acceptance gate for a captured guest run."""

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path

from codec import SCHEMAS, Field, number, unpacked


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def field_value(raw: bytes, field: Field) -> dict[str, object]:
    try:
        if len(raw) != field.size:
            raise ValueError(f"truncated field: expected {field.size} bytes, got {len(raw)}")
        value: str | int
        if field.kind == "packed":
            value = unpacked(raw)
        elif field.kind == "binary":
            value = number(raw)
        elif field.kind == "text":
            value = raw.decode("cp037")
        else:
            value = raw.hex()
        return {"value": value, "bytes": raw.hex(), "decode_error": None}
    except ValueError as error:
        return {"value": None, "bytes": raw.hex(), "decode_error": str(error)}


def differences(expected: bytes, observed: bytes, kind: str,
                case_ids: dict[int, str] | None = None) -> list[dict[str, object]]:
    size, fields = SCHEMAS[kind]
    wanted = [expected[i:i + size] for i in range(0, len(expected), size)]
    actual = [observed[i:i + size] for i in range(0, len(observed), size)]
    positions: dict[bytes, list[int]] = defaultdict(list)
    for index, record in enumerate(wanted, 1):
        positions[record].append(index)
    observed_counts = Counter(actual)
    result: list[dict[str, object]] = []
    if len(expected) != len(observed):
        result.append({"kind": kind, "type": "record_count", "lrecl": size,
                       "expected_bytes": len(expected), "observed_bytes": len(observed),
                       "expected_full_records": len(expected) // size,
                       "observed_full_records": len(observed) // size})
    for side, raw in (("expected", expected), ("observed", observed)):
        if len(raw) % size:
            result.append({"kind": kind, "type": "truncated_record", "side": side,
                           "record": len(raw) // size + 1,
                           "bytes": raw[len(raw) // size * size:].hex()})
    for index in range(max(len(wanted), len(actual))):
        left = wanted[index] if index < len(wanted) else b""
        right = actual[index] if index < len(actual) else b""
        context = {"kind": kind, "record": index + 1,
                   "id": left[:8].decode("cp037") if left else None,
                   "observed_id": right[:8].decode("cp037") if right else None,
                   "case_id": (case_ids or {}).get(index + 1)}
        if right != left and right in positions:
            result.append({**context, "type": "duplicate_record"
                           if observed_counts[right] > len(positions[right])
                           else "reordered_record",
                           "expected_positions": positions[right]})
        if not left or not right:
            result.append({**context, "type": "missing_record" if left else "extra_record",
                           "expected_bytes": left.hex(), "observed_bytes": right.hex()})
            continue
        covered: set[int] = set()
        for field in fields:
            start, end = field.offset, field.offset + field.size
            covered.update(range(start, end))
            if left[start:end] != right[start:end]:
                result.append({**context, "type": "field", "field": field.name,
                               "offset": start, "size": field.size,
                               "expected": field_value(left[start:end], field),
                               "observed": field_value(right[start:end], field)})
        for offset in range(min(len(left), len(right))):
            if offset not in covered and left[offset] != right[offset]:
                result.append({**context, "type": "undeclared_byte", "offset": offset,
                               "expected": left[offset], "observed": right[offset]})
    return result


def compare(expected: bytes, observed: bytes, kind: str) -> None:
    mismatches = differences(expected, observed, kind)
    if mismatches:
        raise ValueError(json.dumps(mismatches, indent=2))


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
    parser.add_argument("--report", type=Path,
                        help="JSON report; defaults beside the receipt")
    args = parser.parse_args()
    report_path = args.report or args.receipt.with_suffix(".comparison.json")
    errors: list[str] = []
    mismatches: list[dict[str, object]] = []
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
        try:
            validate_receipt(json.loads(args.receipt.read_text()), hashes)
        except ValueError as error:
            errors.append(str(error))
        cases = [json.loads(line) for line in (args.golden / "cases.jsonl").read_text().splitlines()]
        case_ids = {case["record"]: case["id"] for case in cases if case["stage"] == args.stage}
        mismatches.extend(differences(
            (args.golden / stage["expected_results"]).read_bytes(), observed_res, "result",
            case_ids))
        mismatches.extend(differences(
            (args.golden / stage["expected_master"]).read_bytes(), observed_pol, "state"))
    except (ValueError, OSError, StopIteration, KeyError, TypeError) as error:
        errors.append(str(error))
    report = {"schema": "insurance-comparison-v1", "stage": args.stage,
              "passed": not errors and not mismatches, "errors": errors,
              "mismatches": mismatches}
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    if not report["passed"]:
        raise SystemExit(f"FAIL: {len(errors)} acceptance errors, "
                         f"{len(mismatches)} differences; {report_path}")
    print(f"PASS: {args.stage}; all result/state fields and receipt hashes match")


if __name__ == "__main__":
    main()
