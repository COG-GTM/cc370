"""Build the versioned fixture bundle: raw case records, expectations, manifest.

    python3 fixtures.py build --version v1     # writes routines/fixtures/v1
    python3 fixtures.py check --version v1     # regenerates and diffs

The manifest pins the inputs that the expectations depend on (frozen source,
macros, rate table, layouts, case list) so any drift is detected before a
guest observation is ever compared.
"""

import argparse
import json
from pathlib import Path

from cases import Case, all_cases
from layout import (CASE, CASE_LRECL, FLAG_KEEP, FLAG_STATE, FLAG_TXN, INSURANCE,
                    LIBRARY, ROUTINES, WORK, WORKLEN, binary, decode_work, sha256)
from model import expect

SCHEMA = "insurance-routines-v1"

# Byte classes per routine. Everything not listed is "stable": a business-visible
# value (or a byte the routine must leave unchanged) that Java must reproduce.
SCRATCH = {
    "INSPACK": (), "INSDATE": (), "INSRATE": (),
    "INSVAL": ("WDATE", "WYEAR", "WMD", "WORD"),
    "INSCALC": ("WDATE", "WYEAR", "WMD", "WORD", "WVALID", "WOLDORD", "WISSYR",
                "WISSMD", "WAGE", "WDAYS", "WRATE", "WFEE", "WBEFORE"),
}
UNASSERTED = {"INSCALC": ("WNUM", "WPROD")}


def classes(routine: str) -> list[str]:
    table = ["stable"] * WORKLEN
    for kind, names in (("scratch", SCRATCH.get(routine, ())),
                        ("unasserted", UNASSERTED.get(routine, ()))):
        for name in names:
            f = next(f for f in WORK if f.name == name)
            table[f.offset:f.end] = [kind] * f.size
    return table


def case_record(case: Case, ordinal: int) -> bytes:
    raw = bytearray(CASE_LRECL)
    for name, value in (("id", case.id.encode("cp037").ljust(8, b"\x40")),
                        ("routine", case.routine.encode("cp037").ljust(8, b"\x40")),
                        ("ordinal", binary(ordinal)), ("flags", bytes([case.flags])),
                        ("arg", case.arg), ("work", case.work)):
        offset, size = CASE[name]
        raw[offset:offset + size] = value
    return bytes(raw)


def expectations(cases: list[Case]) -> list[dict]:
    """Chain the model over the physical case order (keep-flags reuse WORK)."""
    previous = bytes(WORKLEN)
    out = []
    for ordinal, case in enumerate(cases, 1):
        if case.flags & FLAG_KEEP:
            before = bytearray(previous)
            if case.flags & FLAG_STATE:
                before[0:128] = case.work[0:128]
            if case.flags & FLAG_TXN:
                before[128:168] = case.work[128:168]
            before = bytes(before)
        else:
            before = case.work
        outcome = expect(case.routine, before, case.arg)
        previous = outcome.work
        decoded_before, decoded_after = decode_work(before), decode_work(outcome.work)
        out.append({
            "ordinal": ordinal, "id": case.id, "routine": case.routine, "rule": case.rule,
            "note": case.note, "flags": case.flags, "tags": list(case.tags),
            "hand_worked": case.hand, "model_notes": list(outcome.notes),
            "arg": case.arg.hex(), "r15": outcome.r15,
            "work_before": before.hex(), "work_after": outcome.work.hex(),
            "arg_after": outcome.arg.hex(),
            "changed_fields": sorted(n for n in decoded_after if decoded_after[n] != decoded_before[n]),
            "decoded_after": decoded_after,
        })
    return out


def tree_hash(paths: list[Path]) -> dict[str, str]:
    return {str(p.relative_to(INSURANCE)): sha256(p.read_bytes()) for p in sorted(paths)}


def build(version: str) -> Path:
    cases = all_cases()
    target = ROUTINES / "fixtures" / version
    target.mkdir(parents=True, exist_ok=True)
    raw = b"".join(case_record(case, i) for i, case in enumerate(cases, 1))
    expected = expectations(cases)
    (target / "cases.bin").write_bytes(raw)
    (target / "expected.json").write_text(json.dumps(expected, indent=1, sort_keys=True) + "\n")
    counts = {r: sum(1 for c in cases if c.routine == r) for r in LIBRARY}
    manifest = {
        "schema": SCHEMA, "version": version,
        "case_lrecl": CASE_LRECL, "capture_lrecl": 1536, "worklen": WORKLEN,
        "case_count": len(cases), "cases_per_routine": counts,
        "identity_order": [[c.id, c.routine] for c in cases],
        "cases_sha256": sha256(raw),
        "expected_sha256": sha256((target / "expected.json").read_bytes()),
        "byte_classes": {r: classes(r) for r in LIBRARY},
        "inputs": {
            **tree_hash(list((INSURANCE / "src").glob("*.asm"))),
            **tree_hash(list((INSURANCE / "copy").iterdir())),
            **tree_hash([INSURANCE / "golden/v1/rates.json"]),
            **tree_hash([ROUTINES / "harness" / n for n in
                         ("layout.py", "model.py", "cases.py", "fixtures.py")]),
            **tree_hash([ROUTINES / "driver/INSDRV.asm"]),
        },
        "expectation_provenance": {
            "arithmetic": "Python int / datetime.date (independent of assembler)",
            "rule_order": "frozen contract docs BR-002..BR-010 (source-derived, labelled)",
            "hand_worked": "literal values in cases.py, checked against the model by tests",
        },
    }
    (target / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True) + "\n")
    return target


def load(version: str) -> tuple[dict, list[dict], bytes]:
    target = ROUTINES / "fixtures" / version
    manifest = json.loads((target / "manifest.json").read_text())
    expected = json.loads((target / "expected.json").read_text())
    raw = (target / "cases.bin").read_bytes()
    if sha256(raw) != manifest["cases_sha256"]:
        raise ValueError("cases.bin does not match manifest")
    if sha256((target / "expected.json").read_bytes()) != manifest["expected_sha256"]:
        raise ValueError("expected.json does not match manifest")
    return manifest, expected, raw


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["build", "check"])
    parser.add_argument("--version", default="v1")
    args = parser.parse_args()
    target = ROUTINES / "fixtures" / args.version
    if args.command == "check":
        before = {p.name: p.read_bytes() for p in target.iterdir()}
        build(args.version)
        after = {p.name: p.read_bytes() for p in target.iterdir()}
        if before != after:
            raise SystemExit("fixtures drifted: regenerate and review the diff")
        print(f"fixtures {args.version} up to date: {len(all_cases())} cases")
    else:
        build(args.version)
        print(f"wrote {target}: {len(all_cases())} cases")


if __name__ == "__main__":
    main()
