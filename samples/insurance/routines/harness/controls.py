"""Comparator negative controls.

A synthetic capture is built from the expectations (it is NOT guest output),
then deliberately corrupted one aspect at a time. Every corruption must be
reported by compare.py under the expected class. These prove the comparator,
not the guest: a synthetic record is never evidence of routine behaviour.
"""

import argparse
import json
from pathlib import Path

from compare import compare
from fixtures import load
from layout import CAPTURE, CAPTURE_LRECL, GUARD, SENTINEL, binary

BASE = 0x020000  # arbitrary relocation for the synthetic driver
RETPT_OFFSET = 0x16C


def synthesize(expected: list[dict], base: int = BASE) -> bytes:
    out = bytearray()
    for exp in expected:
        rec = bytearray(CAPTURE_LRECL)

        def put(name: str, value: bytes) -> None:
            offset, size = CAPTURE[name]
            rec[offset:offset + size] = value.ljust(size, b"\0")[:size]

        work, arg, save = base + 0x1000, base + 0x1400, base + 0x1800
        routine_addr = base + 0x4000
        before = [0] * 16
        for n, v in SENTINEL.items():
            before[n] = v
        before[1] = arg if exp["routine"] == "INSPACK" else work
        before[11] = base + 0x1000 - 0x100
        before[12], before[13], before[15] = base, save, routine_addr
        ret = 0x80000000 | (base + RETPT_OFFSET)
        after = list(before)
        after[14], after[15] = ret, exp["r15"]
        put("id", exp["id"].encode("cp037").ljust(8, b"\x40"))
        put("routine", exp["routine"].encode("cp037").ljust(8, b"\x40"))
        put("ordinal", binary(exp["ordinal"]))
        put("flags", bytes([exp["flags"]]))
        put("regs_before", b"".join(v.to_bytes(4, "big") for v in before))
        put("regs_after", b"".join(v.to_bytes(4, "big") for v in after))
        caller = bytearray(72)
        caller[4:8] = (0x00ABCDE0).to_bytes(4, "big")
        caller[8:12] = (routine_addr + 0x200).to_bytes(4, "big")
        caller[12:16] = ret.to_bytes(4, "big")
        for i, n in enumerate([15] + list(range(0, 13))):
            caller[16 + 4 * i:20 + 4 * i] = before[n].to_bytes(4, "big")
        put("caller_save", bytes(caller))
        callee = bytearray(72)
        callee[4:8] = save.to_bytes(4, "big")
        put("callee_save", bytes(callee))
        for name, pattern in GUARD.items():
            put("guard_" + name, pattern)
        put("arg_after", bytes.fromhex(exp["arg_after"]))
        put("addr_work", binary(work))
        put("addr_arg", binary(arg))
        put("addr_save", binary(save))
        put("addr_return", binary(base + RETPT_OFFSET))
        put("addr_base", binary(base))
        put("work_before", bytes.fromhex(exp["work_before"]))
        put("work_after", bytes.fromhex(exp["work_after"]))
        out += rec
    return bytes(out)


def corruptions(clean: bytes, expected: list[dict]) -> list[tuple[str, str, bytes]]:
    """(name, class that must report it, corrupted capture)."""
    n = CAPTURE_LRECL
    ca = next(e["ordinal"] for e in expected if e["id"] == "CA001") - 1
    pk = next(e["ordinal"] for e in expected if e["id"] == "PK008") - 1
    cases = []

    def mutate(name: str, kind: str, index: int, field: str, delta: int, value: int) -> None:
        raw = bytearray(clean)
        offset = index * n + CAPTURE[field][0] + delta
        raw[offset] = value
        cases.append((name, kind, bytes(raw)))

    mutate("known output: OSTAT last byte of CA001", "stable", ca, "work_after", 184 + 3, 0xC3)
    mutate("known output: OINT low digit of CA001", "stable", ca, "work_after", 230 + 6, 0x2C)
    mutate("known output: transaction byte of CA001", "stable", ca, "work_after", 128, 0xF9)
    mutate("known output: R15 of INSPACK reject PK008", "stable", pk, "regs_after", 63, 0x00)
    mutate("scratch: WAGE of CA001", "scratch", ca, "work_after", 296 + 3, 0x07)
    mutate("guard: low WORK guard byte", "abi", ca, "guard_work_low", 0, 0x00)
    mutate("guard: high save guard byte", "abi", ca, "guard_save_high", 7, 0x00)
    mutate("register: R7 not preserved", "abi", ca, "regs_after", 7 * 4 + 3, 0x00)
    mutate("register: R13 not restored", "abi", ca, "regs_after", 13 * 4 + 2, 0x11)
    mutate("register: R14 low byte", "abi", ca, "regs_after", 14 * 4 + 3, 0x00)
    mutate("save chain: callee back pointer", "abi", ca, "callee_save", 5, 0x00)
    mutate("save chain: stored R2 in caller save", "abi", ca, "caller_save", 16 + 4 * 3 + 3, 0x00)
    mutate("identity: case id byte", "structure", ca, "id", 4, 0xF9)
    mutate("identity: routine name", "structure", ca, "routine", 3, 0xD7)
    mutate("identity: ordinal", "structure", ca, "ordinal", 3, 0xFF)
    mutate("identity: flags", "structure", ca, "flags", 0, 0x80)
    mutate("harness: WORK before-image", "harness", ca, "work_before", 27, 0xFF)
    mutate("harness: R1 sentinel", "harness", ca, "regs_before", 1 * 4 + 3, 0x01)
    raw = bytearray(clean)
    del raw[ca * n:(ca + 1) * n]
    cases.append(("missing record (CA001 removed)", "structure", bytes(raw)))
    cases.append(("extra record (PK008 appended)", "structure",
                  clean + clean[pk * n:(pk + 1) * n]))
    cases.append(("duplicate/reordered (CA001 swapped with next)", "structure",
                  clean[:ca * n] + clean[(ca + 1) * n:(ca + 2) * n]
                  + clean[ca * n:(ca + 1) * n] + clean[(ca + 2) * n:]))
    cases.append(("truncated capture (last 100 bytes cut)", "structure", clean[:-100]))
    cases.append(("empty capture", "structure", b""))
    return cases


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="v1")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    manifest, expected, _ = load(args.version)
    clean = synthesize(expected)
    baseline = compare(clean, manifest, expected)
    results = [{"control": "synthetic clean capture (comparator self-test)",
                "expected_class": None, "passed": baseline["passed"],
                "ok": baseline["passed"]}]
    for name, kind, raw in corruptions(clean, expected):
        outcome = compare(raw, manifest, expected)
        hit = [f for f in outcome["findings"] if f["class"] == kind]
        results.append({"control": name, "expected_class": kind, "passed": outcome["passed"],
                        "findings": len(outcome["findings"]), "ok": bool(hit) and not outcome["passed"],
                        "first_finding": hit[0] if hit else None})
    report = {"schema": "insurance-routines-controls-v1",
              "note": "synthetic captures corrupted on the host; not guest observations",
              "all_ok": all(r["ok"] for r in results), "controls": results}
    args.report.write_text(json.dumps(report, indent=1, sort_keys=True) + "\n")
    for r in results:
        print(f'{"ok  " if r["ok"] else "BAD "} {r["control"]}'
              + (f' -> {r["expected_class"]} x{r["findings"]}' if r["expected_class"] else ""))
    raise SystemExit(0 if report["all_ok"] else 1)


if __name__ == "__main__":
    main()
