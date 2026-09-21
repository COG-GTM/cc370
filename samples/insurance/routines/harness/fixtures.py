"""Build the versioned fixture bundle: raw case records, expectations, manifest.

    python3 fixtures.py build --version v1     # writes routines/fixtures/v1
    python3 fixtures.py check --version v1     # renders in memory, diffs, writes nothing

The manifest pins the inputs that the expectations depend on (frozen source,
macros, rate table, layouts, case list) so any drift is detected before a
guest observation is ever compared. load() re-verifies every declared hash,
the manifest schema, counts, identity/order and byte classes and refuses to
return fixtures that do not match the repository they came from.
"""

import argparse
import json
from pathlib import Path

from cases import Case, all_cases
from layout import (CAPTURE_LRECL, CASE, CASE_LRECL, FLAG_KEEP, FLAG_STATE, FLAG_TXN,
                    INSURANCE, LIBRARY, ROUTINES, WORK, WORKLEN, binary, decode_work,
                    number, sha256)
from model import expect

SCHEMA = "insurance-routines-v1"

STATE = tuple(f.name for f in WORK if f.group == "state")
TXN = tuple(f.name for f in WORK if f.group == "txn")
RESULT = tuple(f.name for f in WORK if f.group == "result")

# Logical I/O per routine. Every WORK byte falls into exactly one class:
#   output      logical result of the call; the Java business output
#   input       read by the routine and (unless also an output) left unchanged;
#               preserving it is business-visible (e.g. TREC bytes after INSCALC)
#   preserved   unrelated WORK storage the routine never touches; asserted as an
#               assembler preservation/ABI property, NOT a Java requirement
#   scratch     implementation scratch predicted by the model; asserted so the guest
#               evidence is complete, NOT a Java requirement
#   unasserted  implementation-defined arithmetic scratch; recorded, never compared
INPUTS = {
    "INSPACK": (), "INSDATE": ("WDATE",), "INSRATE": ("TDATE", "WAGE"),
    "INSVAL": STATE, "INSCALC": STATE + TXN,
}
OUTPUTS = {
    "INSPACK": (), "INSDATE": ("WVALID", "WYEAR", "WMD", "WORD"),
    "INSRATE": ("WRATE", "WFEE"), "INSVAL": ("WVALID",),
    "INSCALC": STATE + RESULT,
}
SCRATCH = {
    "INSPACK": (), "INSDATE": (), "INSRATE": (),
    "INSVAL": ("WDATE", "WYEAR", "WMD", "WORD"),
    "INSCALC": ("WDATE", "WYEAR", "WMD", "WORD", "WVALID", "WOLDORD", "WISSYR",
                "WISSMD", "WAGE", "WDAYS", "WRATE", "WFEE", "WBEFORE"),
}
UNASSERTED = {"INSCALC": ("WNUM", "WPROD")}
CLASSES = ("output", "input", "preserved", "scratch", "unasserted")
# R15 per routine: "result" when the caller contract reads it as the outcome,
# "fixed" when the routine always clears it (asserted as ABI, not a Java output).
R15 = {"INSPACK": "result", "INSDATE": "fixed", "INSRATE": "fixed",
       "INSVAL": "result", "INSCALC": "fixed"}


def field_class(routine: str, name: str) -> str:
    if name in UNASSERTED.get(routine, ()):
        return "unasserted"
    if name in SCRATCH.get(routine, ()):
        return "scratch"
    if name in OUTPUTS[routine]:
        return "output"
    if name in INPUTS[routine]:
        return "input"
    return "preserved"


def classes(routine: str) -> list[str]:
    table = [""] * WORKLEN
    for f in WORK:
        table[f.offset:f.end] = [field_class(routine, f.name)] * f.size
    return table


def logical_io(routine: str) -> dict:
    return {
        "inputs": list(INPUTS[routine]) + (["R1 -> seven packed bytes"] if routine == "INSPACK" else []),
        "outputs": list(OUTPUTS[routine]) + (["R15"] if R15[routine] == "result" else []),
        "r15": R15[routine],
        "preserved": [f.name for f in WORK if field_class(routine, f.name) == "preserved"],
        "scratch": list(SCRATCH.get(routine, ())),
        "unasserted": list(UNASSERTED.get(routine, ())),
    }


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


def tree_hash(paths: list[Path], root: Path = INSURANCE) -> dict[str, str]:
    return {str(p.relative_to(root)): sha256(p.read_bytes()) for p in sorted(paths)}


def current_inputs(insurance: Path = INSURANCE) -> dict[str, str]:
    """Hashes of every file the expectations depend on, keyed relative to samples/insurance."""
    routines = insurance / "routines"
    groups = (
        sorted((insurance / "src").glob("*.asm")),
        sorted(p for p in (insurance / "copy").iterdir() if p.is_file()),
        [insurance / "golden/v1/rates.json"],
        [routines / "harness" / n for n in ("layout.py", "model.py", "cases.py", "fixtures.py")],
        [routines / "driver/INSDRV.asm"],
    )
    return {k: v for group in groups for k, v in tree_hash(group, insurance).items()}


def render(version: str, insurance: Path = INSURANCE) -> dict[str, bytes]:
    """Fixture files as bytes, without touching the filesystem."""
    cases = all_cases()
    raw = b"".join(case_record(case, i) for i, case in enumerate(cases, 1))
    expected = (json.dumps(expectations(cases), indent=1, sort_keys=True) + "\n").encode()
    counts = {r: sum(1 for c in cases if c.routine == r) for r in LIBRARY}
    manifest = {
        "schema": SCHEMA, "version": version,
        "case_lrecl": CASE_LRECL, "capture_lrecl": CAPTURE_LRECL, "worklen": WORKLEN,
        "case_count": len(cases), "cases_per_routine": counts,
        "identity_order": [[c.id, c.routine] for c in cases],
        "cases_sha256": sha256(raw), "expected_sha256": sha256(expected),
        "byte_classes": {r: classes(r) for r in LIBRARY},
        "logical_io": {r: logical_io(r) for r in LIBRARY},
        "inputs": current_inputs(insurance),
        "expectation_provenance": {
            "arithmetic": "Python int / datetime.date (independent of assembler)",
            "rule_order": "frozen contract docs BR-002..BR-010 (source-derived, labelled)",
            "hand_worked": "literal values in cases.py, checked against the model by tests",
        },
    }
    return {"cases.bin": raw, "expected.json": expected,
            "manifest.json": (json.dumps(manifest, indent=1, sort_keys=True) + "\n").encode()}


def build(version: str) -> Path:
    target = ROUTINES / "fixtures" / version
    target.mkdir(parents=True, exist_ok=True)
    for name, data in render(version).items():
        (target / name).write_bytes(data)
    return target


def drift(version: str, target: Path | None = None) -> list[str]:
    """Names of tracked fixture files that differ from an in-memory render. Writes nothing."""
    target = target or ROUTINES / "fixtures" / version
    rendered = render(version)
    return [name for name, data in rendered.items()
            if not (target / name).exists() or (target / name).read_bytes() != data]


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError("fixture manifest invalid: " + message)


def validate(manifest: dict, expected: list[dict], raw: bytes, expected_bytes: bytes,
             version: str, insurance: Path = INSURANCE) -> None:
    _require(isinstance(manifest, dict), "manifest is not an object")
    keys = {"schema": str, "version": str, "case_lrecl": int, "capture_lrecl": int, "worklen": int,
            "case_count": int, "cases_per_routine": dict, "identity_order": list,
            "cases_sha256": str, "expected_sha256": str, "byte_classes": dict, "logical_io": dict,
            "inputs": dict, "expectation_provenance": dict}
    for key, kind in keys.items():
        _require(key in manifest, f"missing key {key}")
        _require(type(manifest[key]) is kind, f"{key} must be {kind.__name__}")
    _require(manifest["schema"] == SCHEMA, f'schema {manifest["schema"]!r} != {SCHEMA!r}')
    _require(manifest["version"] == version, f'version {manifest["version"]!r} != {version!r}')
    _require(manifest["case_lrecl"] == CASE_LRECL, "case_lrecl")
    _require(manifest["capture_lrecl"] == CAPTURE_LRECL, "capture_lrecl")
    _require(manifest["worklen"] == WORKLEN, "worklen")
    n = manifest["case_count"]
    _require(n > 0, "case_count must be positive")
    _require(sha256(raw) == manifest["cases_sha256"], "cases.bin hash")
    _require(sha256(expected_bytes) == manifest["expected_sha256"], "expected.json hash")
    _require(len(raw) == n * CASE_LRECL, f"cases.bin length {len(raw)} != {n} x {CASE_LRECL}")
    _require(isinstance(expected, list) and len(expected) == n, "expected.json count")
    order = manifest["identity_order"]
    _require(len(order) == n, "identity_order count")
    _require(set(manifest["cases_per_routine"]) == set(LIBRARY), "cases_per_routine keys")
    _require(sum(manifest["cases_per_routine"].values()) == n, "cases_per_routine sum")
    ids = set()
    for i, (entry, exp) in enumerate(zip(order, expected), 1):
        _require(isinstance(entry, list) and len(entry) == 2, f"identity_order[{i}] shape")
        cid, routine = entry
        _require(routine in LIBRARY, f"{cid}: unknown routine {routine!r}")
        _require(cid not in ids, f"duplicate identity {cid}")
        ids.add(cid)
        _require((exp.get("id"), exp.get("routine"), exp.get("ordinal")) == (cid, routine, i),
                 f"expected.json[{i}] identity/order != {cid}/{routine}")
        rec = raw[(i - 1) * CASE_LRECL:i * CASE_LRECL]
        got = (rec[CASE["id"][0]:CASE["id"][0] + 8].decode("cp037").strip(),
               rec[CASE["routine"][0]:CASE["routine"][0] + 8].decode("cp037").strip(),
               number(rec[CASE["ordinal"][0]:CASE["ordinal"][0] + 4]))
        _require(got == (cid, routine, i), f"cases.bin[{i}] identity/order {got} != {(cid, routine, i)}")
    for routine in LIBRARY:
        _require(manifest["cases_per_routine"][routine] == sum(1 for _, r in order if r == routine),
                 f"cases_per_routine[{routine}]")
        table = manifest["byte_classes"].get(routine)
        _require(isinstance(table, list) and len(table) == WORKLEN, f"byte_classes[{routine}] length")
        _require(set(table) <= set(CLASSES), f"byte_classes[{routine}] values")
        _require(table == classes(routine), f"byte_classes[{routine}] differ from harness")
        _require(manifest["logical_io"].get(routine) == logical_io(routine),
                 f"logical_io[{routine}] differ from harness")
    _require(set(manifest["byte_classes"]) == set(LIBRARY), "byte_classes keys")
    declared, actual = manifest["inputs"], current_inputs(insurance)
    for path in sorted(set(declared) | set(actual)):
        if path not in actual:
            _require(False, f"declared input {path} is not present in the repository")
        if path not in declared:
            _require(False, f"input {path} exists but is not declared")
        _require(declared[path] == actual[path], f"declared input {path} changed since fixtures were built")


def load(version: str, root: Path | None = None,
         insurance: Path = INSURANCE) -> tuple[dict, list[dict], bytes]:
    """Read fixtures/<version> and verify the whole manifest against the repository."""
    target = root or ROUTINES / "fixtures" / version
    for name in ("manifest.json", "expected.json", "cases.bin"):
        if not (target / name).is_file():
            raise ValueError(f"fixture manifest invalid: missing {name}")
    manifest = json.loads((target / "manifest.json").read_text())
    expected_bytes = (target / "expected.json").read_bytes()
    expected = json.loads(expected_bytes)
    raw = (target / "cases.bin").read_bytes()
    validate(manifest, expected, raw, expected_bytes, version, insurance)
    return manifest, expected, raw


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["build", "check"])
    parser.add_argument("--version", default="v1")
    args = parser.parse_args()
    target = ROUTINES / "fixtures" / args.version
    if args.command == "check":
        changed = drift(args.version)
        if changed:
            raise SystemExit("fixtures drifted (nothing written): " + ", ".join(changed)
                             + "; run `fixtures.py build` and review the diff")
        load(args.version)
        print(f"fixtures {args.version} up to date and verified: {len(all_cases())} cases")
    else:
        build(args.version)
        print(f"wrote {target}: {len(all_cases())} cases")


if __name__ == "__main__":
    main()
