"""Decode a raw INSDRV capture into a Java-reusable observation bundle.

    python3 observe.py --capture DIR/capture.bin --out DIR/observed.json [--version v1]

Every record becomes one observation with three separately labelled parts:

  stable      business-visible work-area fields after the call (decoded + raw
              hex), R15 and, for INSPACK, the argument bytes. This is the part
              a pure Java implementation must reproduce.
  abi         assembler-only facts: register preservation, save-chain slots,
              guard bytes, relocated addresses. Java has no equivalent.
  unasserted  scratch bytes recorded for completeness and never compared.

Nothing here is an expectation: the file states what the guest did. The
independent expectation lives in fixtures/<version>/expected.json and the
verdict in comparison.json.
"""

import argparse
import json
from pathlib import Path

from fixtures import SCRATCH, UNASSERTED
from layout import (CAPTURE, CAPTURE_LRECL, GUARD, ROUTINES, WORK, decode_field,
                    number, sha256)


def part(raw: bytes, name: str) -> bytes:
    offset, size = CAPTURE[name]
    return raw[offset:offset + size]


def regs(raw: bytes) -> list[int]:
    return [int.from_bytes(raw[i:i + 4], "big") for i in range(0, 64, 4)]


def observation(raw: bytes, fixture: dict) -> dict:
    routine = part(raw, "routine").decode("cp037").strip()
    scratch, unasserted = set(SCRATCH.get(routine, ())), set(UNASSERTED.get(routine, ()))
    before, after = part(raw, "work_before"), part(raw, "work_after")
    stable, scratch_out, other = {}, {}, {}
    for f in WORK:
        got = after[f.offset:f.end]
        entry = {"decoded": decode_field(f, got), "raw": got.hex(),
                 "changed": got != before[f.offset:f.end]}
        (other if f.name in unasserted else scratch_out if f.name in scratch else stable)[f.name] = entry
    rb, ra = regs(part(raw, "regs_before")), regs(part(raw, "regs_after"))
    return {
        "id": part(raw, "id").decode("cp037").strip(), "routine": routine,
        "ordinal": number(part(raw, "ordinal")), "flags": part(raw, "flags")[0],
        "rule": fixture["rule"], "note": fixture["note"],
        "input": {"work_before": before.hex(), "arg": fixture["arg"]},
        "stable": {"r15": ra[15], "fields": stable,
                   "arg_after": part(raw, "arg_after").hex() if routine == "INSPACK" else None,
                   "work_after_sha256": sha256(after)},
        "abi": {
            "registers_preserved": all(rb[n] == ra[n] for n in range(0, 14)),
            "r14_low24_is_return_address": ra[14] & 0x00FFFFFF == number(part(raw, "addr_return")),
            "r1_target": "arg" if routine == "INSPACK" else "work",
            "r13_restored": rb[13] == ra[13], "r12_restored": rb[12] == ra[12],
            "guards_intact": all(part(raw, "guard_" + k) == v for k, v in GUARD.items()),
            "callee_save_back_pointer": part(raw, "callee_save")[4:8].hex(),
            "addresses": {k: part(raw, k).hex() for k in
                          ("addr_work", "addr_arg", "addr_save", "addr_return", "addr_base")},
            "note": "addresses are relocation-dependent and recorded, not required",
        },
        "unasserted": other,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--version", default="v1")
    args = parser.parse_args()
    expected = json.loads((ROUTINES / "fixtures" / args.version / "expected.json").read_text())
    by_id = {e["id"]: e for e in expected}
    raw = args.capture.read_bytes()
    records = [raw[i:i + CAPTURE_LRECL] for i in range(0, len(raw), CAPTURE_LRECL)]
    observed = []
    for rec in records:
        cid = part(rec, "id").decode("cp037").strip()
        observed.append(observation(rec, by_id.get(cid, {"rule": "?", "note": "?", "arg": ""})))
    bundle = {"schema": "insurance-routines-observed-v1", "fixture_version": args.version,
              "capture_sha256": sha256(raw), "records": len(records),
              "provenance": "raw INSDRV capture from real MVS 3.8j execution; decoded on the host only",
              "observations": observed}
    args.out.write_text(json.dumps(bundle, indent=1, sort_keys=True) + "\n")
    print(f"{args.out}: {len(records)} observations, capture {sha256(raw)[:16]}")


if __name__ == "__main__":
    main()
