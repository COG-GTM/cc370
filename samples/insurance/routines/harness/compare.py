"""Strict comparator for INSDRV capture records against the fixture expectations.

Every finding carries routine, case id, field / memory offset, expected and
observed decoded values and raw bytes, and a class:

  structure  record count, order, identity, malformed capture
  harness    driver-side preconditions (WORK before-image, R1 convention)
  abi        register preservation, save chain, guards, R15 for routines that
             always clear it, and unrelated WORK bytes the routine must leave
             alone (manifest byte class "preserved")
  stable     business-visible bytes Java must reproduce: manifest classes
             "output" and "input" (inputs must survive the call), R15 where
             the caller reads it as the result, INSPACK's argument bytes
  scratch    assembler-specific scratch the model predicts (not for Java)
  unasserted recorded only, never a failure (WNUM/WPROD remainder bytes)

All findings are kept; the comparison fails when any class other than
"unasserted" has findings. The report records the capture hash, the fixture
manifest hash and the hashes of the harness files that produced it, so a
re-derived report can never be mistaken for the one written at run time.
"""

import argparse
import json
from pathlib import Path

from fixtures import R15, load
from layout import (CAPTURE, CAPTURE_LRECL, GUARD, LIBRARY, ROUTINES, SENTINEL, WORK,
                    decode_field, number, sha256, symbols)

FAILING = ("structure", "harness", "abi", "stable", "scratch")
FINDING_CLASS = {"output": "stable", "input": "stable", "preserved": "abi",
                 "scratch": "scratch", "unasserted": "unasserted"}
SCHEMA = "insurance-routines-comparison-v2"


def manifest_sha256(manifest: dict) -> str:
    return sha256(json.dumps(manifest, sort_keys=True).encode())


def harness_identity() -> dict[str, str]:
    """Hashes of the host files whose behaviour a derived report depends on."""
    names = ("layout.py", "fixtures.py", "compare.py", "observe.py", "contract.py")
    return {n: sha256((ROUTINES / "harness" / n).read_bytes()) for n in names}


def field(raw: bytes, name: str) -> bytes:
    offset, size = CAPTURE[name]
    return raw[offset:offset + size]


def regs(raw: bytes) -> list[int]:
    return [int.from_bytes(raw[i:i + 4], "big") for i in range(0, 64, 4)]


class Report:
    def __init__(self) -> None:
        self.findings: list[dict] = []
        self.notes: list[dict] = []

    def add(self, kind: str, routine: str, case: str, where: str, expected, observed,
            expected_raw: bytes | None = None, observed_raw: bytes | None = None,
            offset: int | None = None) -> None:
        entry = {
            "class": kind, "routine": routine, "case": case, "field": where,
            "offset": offset, "expected": str(expected), "observed": str(observed),
            "expected_raw": expected_raw.hex() if expected_raw is not None else None,
            "observed_raw": observed_raw.hex() if observed_raw is not None else None,
        }
        (self.notes if kind == "unasserted" else self.findings).append(entry)


def compare_work(report: Report, exp: dict, classes: list[str], observed: bytes) -> None:
    expected = bytes.fromhex(exp["work_after"])
    for f in WORK:
        want, got = expected[f.offset:f.end], observed[f.offset:f.end]
        if want != got:
            report.add(FINDING_CLASS[classes[f.offset]], exp["routine"], exp["id"],
                       "work_after." + f.name, decode_field(f, want), decode_field(f, got),
                       want, got, f.offset)


def compare_record(report: Report, exp: dict, classes: list[str], raw: bytes,
                   driver: dict[str, tuple[int, int]]) -> None:
    routine, cid = exp["routine"], exp["id"]
    before, after = regs(field(raw, "regs_before")), regs(field(raw, "regs_after"))
    addr = {k: number(field(raw, k)) & 0xFFFFFF for k in
            ("addr_work", "addr_arg", "addr_save", "addr_return", "addr_base")}
    # Driver preconditions (relocation-safe: only relationships are asserted).
    if bytes.fromhex(exp["work_before"]) != field(raw, "work_before"):
        want, got = bytes.fromhex(exp["work_before"]), field(raw, "work_before")
        for f in WORK:
            if want[f.offset:f.end] != got[f.offset:f.end]:
                kind = "unasserted" if classes[f.offset] == "unasserted" else "harness"
                report.add(kind, routine, cid, "work_before." + f.name,
                           decode_field(f, want[f.offset:f.end]),
                           decode_field(f, got[f.offset:f.end]),
                           want[f.offset:f.end], got[f.offset:f.end], f.offset)
    r1_target = addr["addr_arg"] if routine == "INSPACK" else addr["addr_work"]
    if before[1] != r1_target:
        report.add("harness", routine, cid, "R1(before)", hex(r1_target), hex(before[1]))
    for n, value in SENTINEL.items():
        if before[n] != value:
            report.add("harness", routine, cid, f"R{n}(before)", hex(value), hex(before[n]))
    if before[13] != addr["addr_save"] or before[12] != addr["addr_base"]:
        report.add("harness", routine, cid, "R12/R13(before)",
                   (hex(addr["addr_base"]), hex(addr["addr_save"])),
                   (hex(before[12]), hex(before[13])))
    if "RETPT" in driver and "INSDRV" in driver:
        want = (addr["addr_base"] + driver["RETPT"][0]) & 0xFFFFFF
        if addr["addr_return"] != want:
            report.add("harness", routine, cid, "addr_return", hex(want), hex(addr["addr_return"]))
    # ABI: R0-R12 and R13 preserved; R14 returns to the instruction after BALR.
    for n in range(0, 14):
        if after[n] != before[n]:
            report.add("abi", routine, cid, f"R{n}(after)", hex(before[n]), hex(after[n]))
    if after[14] & 0xFFFFFF != addr["addr_return"]:
        report.add("abi", routine, cid, "R14(after).low24", hex(addr["addr_return"]),
                   hex(after[14] & 0xFFFFFF))
    r15_class = "stable" if R15[routine] == "result" else "abi"
    if after[15] != exp["r15"]:
        report.add(r15_class, routine, cid, "R15", exp["r15"], after[15])
    # Save chain: callee stored R14..R12 into the caller's area; callee area points back.
    caller = field(raw, "caller_save")
    if number(caller[12:16]) & 0xFFFFFF != addr["addr_return"]:
        report.add("abi", routine, cid, "caller_save[12] (R14)", hex(addr["addr_return"]),
                   hex(number(caller[12:16]) & 0xFFFFFF), None, caller[12:16], 12)
    stored = [int.from_bytes(caller[16 + 4 * i:20 + 4 * i], "big") for i in range(14)]
    for i, n in enumerate([15] + list(range(0, 13))):
        if stored[i] != before[n]:
            report.add("abi", routine, cid, f"caller_save[{16 + 4 * i}] (R{n})",
                       hex(before[n]), hex(stored[i]), offset=16 + 4 * i)
    forward = number(caller[8:12]) & 0xFFFFFF
    if forward == 0 or forward == addr["addr_save"]:
        report.add("abi", routine, cid, "caller_save[8] (forward)", "callee SAVE", hex(forward))
    callee = field(raw, "callee_save")
    if number(callee[4:8]) & 0xFFFFFF != addr["addr_save"]:
        report.add("abi", routine, cid, "callee_save[4] (back)", hex(addr["addr_save"]),
                   hex(number(callee[4:8]) & 0xFFFFFF), None, callee[4:8], 4)
    # Guards around WORK, the INSPACK argument and the save area.
    for name, pattern in GUARD.items():
        got = field(raw, "guard_" + name)
        if got != pattern:
            report.add("abi", routine, cid, "guard_" + name, pattern.hex(), got.hex(),
                       pattern, got)
    want_arg, got_arg = bytes.fromhex(exp["arg_after"]), field(raw, "arg_after")
    if want_arg != got_arg:
        report.add("stable", routine, cid, "arg_after", want_arg.hex(), got_arg.hex(),
                   want_arg, got_arg)
    compare_work(report, exp, classes, field(raw, "work_after"))


def compare(captured: bytes, manifest: dict, expected: list[dict],
            driver: dict[str, tuple[int, int]] | None = None) -> dict:
    report = Report()
    driver = driver or {}
    if len(captured) % CAPTURE_LRECL:
        report.add("structure", "*", "*", "capture length", f"multiple of {CAPTURE_LRECL}",
                   len(captured))
    records = [captured[i:i + CAPTURE_LRECL] for i in range(0, len(captured) - len(captured)
                                                            % CAPTURE_LRECL, CAPTURE_LRECL)]
    if len(records) != len(expected):
        report.add("structure", "*", "*", "record count", len(expected), len(records))
    observed_ids = []
    for index, raw in enumerate(records, 1):
        cid = field(raw, "id").decode("cp037").strip()
        routine = field(raw, "routine").decode("cp037").strip()
        ordinal = number(field(raw, "ordinal"))
        observed_ids.append(cid)
        if routine not in LIBRARY:
            report.add("structure", routine, cid, "routine", "one of " + ",".join(LIBRARY), routine)
            continue
        if ordinal != index:
            report.add("structure", routine, cid, "ordinal", index, ordinal)
        if index > len(expected):
            report.add("structure", routine, cid, "extra record", "absent", f"position {index}")
            continue
        exp = expected[index - 1]
        if (cid, routine) != (exp["id"], exp["routine"]):
            report.add("structure", routine, cid, "identity/order",
                       f'{exp["id"]}/{exp["routine"]}', f"{cid}/{routine}")
            continue
        if field(raw, "flags")[0] != exp["flags"]:
            report.add("structure", routine, cid, "flags", exp["flags"], field(raw, "flags")[0])
        compare_record(report, exp, manifest["byte_classes"][routine], raw, driver)
    for exp in expected[len(records):]:
        report.add("structure", exp["routine"], exp["id"], "missing record", "present", "absent")
    duplicates = sorted({c for c in observed_ids if observed_ids.count(c) > 1})
    for cid in duplicates:
        report.add("structure", "*", cid, "duplicate identity", 1, observed_ids.count(cid))
    by_class = {k: sum(1 for f in report.findings if f["class"] == k) for k in FAILING}
    failed_cases = sorted({f["case"] for f in report.findings})
    return {
        "schema": SCHEMA,
        "passed": not report.findings,
        "capture_sha256": sha256(captured), "capture_bytes": len(captured),
        "fixture_version": manifest["version"], "fixture_manifest_sha256": manifest_sha256(manifest),
        "cases_sha256": manifest["cases_sha256"], "harness_identity": harness_identity(),
        "expected_records": len(expected), "observed_records": len(records),
        "observed_identity": [[field(r, "id").decode("cp037").strip(),
                               field(r, "routine").decode("cp037").strip()] for r in records],
        "findings_by_class": by_class, "failed_cases": failed_cases,
        "cases_passed": [e["id"] for e in expected if e["id"] not in failed_cases
                         and e["id"] in observed_ids],
        "findings": report.findings, "unasserted_changes": report.notes,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--version", default="v1")
    parser.add_argument("--driver-symbols", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    manifest, expected, _ = load(args.version)
    driver = symbols(args.driver_symbols) if args.driver_symbols else {}
    result = compare(args.capture.read_bytes(), manifest, expected, driver)
    args.report.write_text(json.dumps(result, indent=1, sort_keys=True) + "\n")
    for finding in result["findings"]:
        print(f'FAIL {finding["class"]:9} {finding["routine"]:8} {finding["case"]:8} '
              f'{finding["field"]}: expected {finding["expected"]} observed {finding["observed"]}')
    print(f'{"PASS" if result["passed"] else "FAIL"}: {result["observed_records"]}/'
          f'{result["expected_records"]} records, {len(result["cases_passed"])} cases passed, '
          f'{len(result["findings"])} findings, {len(result["unasserted_changes"])} unasserted')
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
