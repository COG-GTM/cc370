"""Decode a raw INSDRV capture into a Java-reusable observation bundle.

    python3 observe.py --capture DIR/capture.bin --out DIR/observed.json [--version v1]

Every record becomes one observation with separately labelled parts:

  business    what a pure Java implementation must reproduce: the routine's
              logical outputs (manifest class "output", decoded + raw hex),
              R15 where the caller reads it as the result, whether every
              input byte survived the call, INSPACK's argument bytes.
  abi         assembler-only facts: R15 for routines that always clear it,
              register preservation, save-chain slots, guard bytes, relocated
              addresses, and whether unrelated WORK storage ("preserved") was
              left alone. Java has no equivalent and must not emulate it.
  scratch     implementation scratch the routine wrote (manifest class
              "scratch"), decoded and kept so the guest evidence is complete;
              not a Java requirement.
  unasserted  arithmetic scratch recorded for completeness, never compared.

Nothing here is an expectation: the file states what the guest did. The
independent expectation lives in fixtures/<version>/expected.json and the
verdict in comparison.json. Records are validated before they are packaged:
a partial trailing record, an unknown routine or case id, a duplicate or
out-of-order identity, or a count that differs from the fixture set is
reported under "problems" and makes the bundle invalid (exit 1).
"""

import argparse
import json
from pathlib import Path

from compare import harness_identity, manifest_sha256
from fixtures import INPUTS, R15, field_class, load
from layout import (CAPTURE, CAPTURE_LRECL, GUARD, LIBRARY, WORK, decode_field, number,
                    sha256)

SCHEMA = "insurance-routines-observed-v2"


def part(raw: bytes, name: str) -> bytes:
    offset, size = CAPTURE[name]
    return raw[offset:offset + size]


def regs(raw: bytes) -> list[int]:
    return [int.from_bytes(raw[i:i + 4], "big") for i in range(0, 64, 4)]


def identity(raw: bytes) -> tuple[str, str, int]:
    return (part(raw, "id").decode("cp037").strip(),
            part(raw, "routine").decode("cp037").strip(), number(part(raw, "ordinal")))


def observation(raw: bytes, fixture: dict) -> dict:
    cid, routine, ordinal = identity(raw)
    before, after = part(raw, "work_before"), part(raw, "work_after")
    groups: dict[str, dict] = {"output": {}, "input": {}, "preserved": {}, "scratch": {},
                               "unasserted": {}}
    for f in WORK:
        got = after[f.offset:f.end]
        groups[field_class(routine, f.name)][f.name] = {
            "decoded": decode_field(f, got), "raw": got.hex(),
            "changed": got != before[f.offset:f.end]}
    rb, ra = regs(part(raw, "regs_before")), regs(part(raw, "regs_after"))
    inputs_changed = [n for n, e in groups["input"].items() if e["changed"]]
    preserved_changed = [n for n, e in groups["preserved"].items() if e["changed"]]
    r15_is_result = R15[routine] == "result"
    return {
        "id": cid, "routine": routine, "ordinal": ordinal, "flags": part(raw, "flags")[0],
        "rule": fixture["rule"], "note": fixture["note"],
        "input": {"work_before": before.hex(), "arg": fixture["arg"],
                  "fields": {f.name: {"decoded": decode_field(f, before[f.offset:f.end]),
                                      "raw": before[f.offset:f.end].hex()}
                             for f in WORK if f.name in INPUTS[routine]}},
        "business": {
            "r15": ra[15] if r15_is_result else None,
            "outputs": groups["output"],
            "inputs_unchanged": not inputs_changed, "inputs_changed": inputs_changed,
            "arg_after": part(raw, "arg_after").hex() if routine == "INSPACK" else None,
            "work_after_sha256": sha256(after),
        },
        "abi": {
            "r15_fixed": None if r15_is_result else ra[15],
            "registers_preserved": all(rb[n] == ra[n] for n in range(0, 14)),
            "r14_low24_is_return_address": ra[14] & 0x00FFFFFF == number(part(raw, "addr_return")),
            "r1_target": "arg" if routine == "INSPACK" else "work",
            "r13_restored": rb[13] == ra[13], "r12_restored": rb[12] == ra[12],
            "guards_intact": all(part(raw, "guard_" + k) == v for k, v in GUARD.items()),
            "unrelated_work_preserved": not preserved_changed,
            "unrelated_work_changed": preserved_changed,
            "callee_save_back_pointer": part(raw, "callee_save")[4:8].hex(),
            "addresses": {k: part(raw, k).hex() for k in
                          ("addr_work", "addr_arg", "addr_save", "addr_return", "addr_base")},
            "note": "addresses are relocation-dependent and recorded, not required",
        },
        "scratch": groups["scratch"],
        "scratch_changed": sorted(n for n, e in groups["scratch"].items() if e["changed"]),
        "unasserted": groups["unasserted"],
    }


def decode(raw: bytes, manifest: dict, expected: list[dict], version: str) -> dict:
    """Validate the capture against the fixture identity set, then decode every whole record."""
    problems: list[str] = []
    whole = len(raw) - len(raw) % CAPTURE_LRECL
    if len(raw) % CAPTURE_LRECL:
        problems.append(f"partial trailing record: {len(raw) % CAPTURE_LRECL} bytes "
                        f"(capture is not a multiple of {CAPTURE_LRECL})")
    records = [raw[i:i + CAPTURE_LRECL] for i in range(0, whole, CAPTURE_LRECL)]
    if len(records) != len(expected):
        problems.append(f"record count {len(records)} != fixture case count {len(expected)}")
    by_id = {e["id"]: e for e in expected}
    seen: dict[str, int] = {}
    observed: list[dict] = []
    for index, rec in enumerate(records, 1):
        cid, routine, ordinal = identity(rec)
        if routine not in LIBRARY:
            problems.append(f"record {index}: unknown routine {routine!r} (id {cid!r})")
            continue
        if cid not in by_id:
            problems.append(f"record {index}: unknown case id {cid!r}")
            continue
        if cid in seen:
            problems.append(f"record {index}: duplicate case id {cid} (first at {seen[cid]})")
        seen.setdefault(cid, index)
        exp = by_id[cid]
        if (exp["routine"], exp["ordinal"]) != (routine, index) or ordinal != index:
            problems.append(f"record {index}: {cid}/{routine} ordinal {ordinal} out of order; "
                            f'fixture places {cid}/{exp["routine"]} at {exp["ordinal"]}')
        observed.append(observation(rec, exp))
    missing = [e["id"] for e in expected if e["id"] not in seen]
    if missing:
        problems.append(f"missing cases: {', '.join(missing[:10])}"
                        + (f" (+{len(missing) - 10})" if len(missing) > 10 else ""))
    return {
        "schema": SCHEMA, "fixture_version": version,
        "fixture_manifest_sha256": manifest_sha256(manifest), "cases_sha256": manifest["cases_sha256"],
        "capture_sha256": sha256(raw), "capture_bytes": len(raw), "records": len(records),
        "valid": not problems, "problems": problems,
        "harness_identity": harness_identity(),
        "provenance": ("raw INSDRV capture from real MVS 3.8j execution; decoded on the host by the "
                       "harness files hashed in harness_identity, which may postdate the run"),
        "class_meaning": {
            "business": "logical outputs, result R15 and input preservation; Java must reproduce",
            "abi": "assembler-only linkage and unrelated-storage preservation; not for Java",
            "scratch": "implementation scratch the routine wrote; recorded, not a Java requirement",
            "unasserted": "implementation-defined arithmetic scratch; recorded, never compared",
        },
        # An invalid capture yields no guest facts: decoded records are quarantined for
        # diagnosis only and "observations" stays empty so consumers cannot mistake them.
        "observations": observed if not problems else [],
        "quarantined": [] if not problems else observed,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--version", default="v1")
    args = parser.parse_args()
    manifest, expected, _ = load(args.version)
    bundle = decode(args.capture.read_bytes(), manifest, expected, args.version)
    args.out.write_text(json.dumps(bundle, indent=1, sort_keys=True) + "\n")
    for problem in bundle["problems"]:
        print("INVALID", problem)
    print(f'{args.out}: {bundle["records"]} observations, capture {bundle["capture_sha256"][:16]}, '
          f'{"valid" if bundle["valid"] else "INVALID"}')
    raise SystemExit(0 if bundle["valid"] else 1)


if __name__ == "__main__":
    main()
