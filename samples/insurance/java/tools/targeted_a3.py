#!/usr/bin/env python3
"""Targeted fresh-guest cases (A3) for the Java modernization.

The frozen 8,704-result corpus has no exact half-cent interest tie, no reachable interest
quotient overflow, accepted ages only in {0, 1, 10, 11} and no rate-band boundary. This tool
builds small, independent cases for those gaps, records what the REAL guest (INSBAT on TK5)
produces for each of them, and then compares the Java implementation byte-for-byte with that
observed output.

Subcommands (run from anywhere; every path is explicit):

  cases    --out DIR
      Write <case>/polin.bin and <case>/txnin.bin plus cases.json (description, expected status
      list derived from tools/oracle.py, which is source-derived and only used as a sanity check).
  capture  --cases DIR --evidence DIR --root MVSROOT --prefix HLQ --load-prefix HLQ
           --build-manifest FILE --guest-manifest FILE
      Execute every case on the guest (one job per case, one dataset prefix per case). Stores
      <case>/polout.bin, <case>/resout.bin and a guest receipt in the UNCHANGED legacy
      insurance-run-v1 shape (derived from the raw tk5_job result.json exactly as
      tools/tk5_validate.py derives it) that binds the pinned case inputs, the observed outputs,
      the build/guest manifests of the load library actually used and the frozen rate table, in
      DIR/authority/; the manifests are copied to DIR/provenance/. Cases marked "control" are
      allowed to fail (their guest RC=12 is the evidence).
  rederive --cases DIR --authority DIR --guest DIR --build-manifest FILE --guest-manifest FILE
      Rebuild every <case>/receipt.json of an existing capture from the retained raw guest
      evidence (DIR/<NN>-<case>-run/result.json, whose SHA-256 the capture-time receipt already
      pinned as job_result_sha256) without running the guest again. Fails closed if the raw
      result.json does not match the pinned hash or the manifests do not name the load library.
  compare  --cases DIR --authority DIR --jar JAR --work DIR --report FILE --source-commit SHA
           [--mode batch|http-gen|http-json] [--base-url URL] [--namespace-prefix P]
           [--build-manifest FILE --guest-manifest FILE]
      Before any Java code runs, every case's authority is validated: golden inventory intact;
      cases-dir POLIN/TXNIN equal cases.json's pinned hashes and the authority's polin/txnin bytes;
      the receipt passes compare.validate_receipt (unchanged legacy validator) with all seven
      hashes recomputed here (pinned inputs, observed outputs, build/guest manifest bytes, golden
      rates.json), or - for RC=12 controls only - validate_control_receipt, which keeps every
      fail-closed rule (schema, timeout, ABEND, provenance, hashes) and additionally requires the
      guest to have REJECTED the job (outcome failed, RUN RC=12 and nothing else). Then the Java
      implementation runs each case in its own namespace and the published POLOUT/RESOUT are
      compared with the guest bytes using tools/compare.py's comparator. The statuses decoded
      here from the observed RESOUT must equal the case's intended status list, so a case that
      silently stopped exercising its intended path is reported as failed.

Guest credentials come only from TK5_JOB_USER / TK5_JOB_PASSWORD in the environment (tk5_job.py).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
SAMPLE = HERE.parent.parent
sys.path.insert(0, str(SAMPLE / "tools"))
sys.path.insert(0, str(HERE))

import codec  # noqa: E402
import oracle  # noqa: E402
from compare import differences, validate_receipt  # noqa: E402
import parity_java as pj  # noqa: E402
from parity_java import AuthorityError  # noqa: E402

MAX = 99_999_999_999
TARGETED_RECEIPT = "insurance-a3-targeted-v2"
CONTROL_RC = 12


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def st(policy: str, issue: int, cash: int, face: int = 1_000_000, loan: int = 0) -> bytes:
    return codec.state(policy, issue, face, cash, loan)


def tx(policy: str, seq: int, date: int, op: str, amount: int) -> bytes:
    return codec.transaction(policy, seq, date, op, amount)


def raw_tx(policy: str, seq: int, date: int, op: bytes, pad: bytes, amount: bytes,
           tail: bytes) -> bytes:
    rec = codec.text(policy) + codec.binary(seq) + codec.binary(date) + op + pad + amount + tail
    assert len(rec) == 40, len(rec)
    return rec


def amt(value: int, sign: str = "") -> bytes:
    return codec.packed(value, 7, sign)


# ---------------------------------------------------------------------------- case definitions


def build_cases() -> list[dict]:
    cases: list[dict] = []

    def add(name: str, description: str, policies: list[bytes], txns: list[bytes],
            control: str | None = None) -> None:
        cases.append({"name": name, "description": description,
                      "polin": b"".join(policies), "txnin": b"".join(txns),
                      "control": control})

    # T-01 true half-cent interest ties (half-up must give 7 / 33 / 33; half-even would give 6 / 32 / 32)
    add("interest-tie",
        "cash 73000 @325bps: 1 day = 6.5 -> 7; 5 days = 32.5 -> 33; cash 14600, 25 days = 32.5 -> 33",
        [st("00000001", 20250101, 73_000), st("00000002", 20250101, 73_000),
         st("00000003", 20250101, 14_600)],
        [tx("00000001", 1, 20250102, "Q", 0), tx("00000002", 1, 20250106, "Q", 0),
         tx("00000003", 1, 20250126, "Q", 0)])

    # T-05(b) reachable interest quotient overflow with frozen rates (no injected rate)
    add("quotient-over",
        "cash 99,999,999,999 from 1900-01-01 to 2025-01-01 (45,656 days @325bps): interest "
        "406,526,027,393 > MAXAMT -> OVER; sibling policy proves a 1-day interval on the same cash is OVER "
        "via cash+interest; third policy shows cash exactly MAXAMT with a 0-day interval is OKAY",
        [st("00000001", 19000101, MAX), st("00000002", 20250101, MAX), st("00000003", 20250101, MAX)],
        [tx("00000001", 1, 20250101, "Q", 0), tx("00000002", 1, 20250102, "Q", 0),
         tx("00000003", 1, 20250101, "Q", 0)])

    # cash + premium boundary at exactly MAXAMT, then one cent more
    add("cash-max-boundary",
        "cash 96,800,000,000 over 2024 (366 days @325bps) -> interest 3,154,619,178, cash "
        "99,954,619,178 OKAY; premium 45,380,821 (0 days) -> cash exactly MAXAMT OKAY; premium 1 -> OVER",
        [st("00000001", 20240101, 96_800_000_000)],
        [tx("00000001", 1, 20250101, "Q", 0), tx("00000001", 2, 20250101, "P", 45_380_821),
         tx("00000001", 3, 20250101, "P", 1)])

    # charge ties: cash*fee/10000 ends in exactly .5
    add("charge-tie",
        "0-day premium into cash 0 @350bps: cash 300 -> charge 10.5 -> 11 (half-even 10); cash 100 -> "
        "3.5 -> 4; @400bps (2024) cash 125 -> 5.0 exact; cash 1125 -> 45.0; cash 375 -> 15.0",
        [st("00000001", 20250101, 0), st("00000002", 20250101, 0), st("00000003", 20240101, 0)],
        [tx("00000001", 1, 20250101, "P", 300), tx("00000002", 1, 20250101, "P", 100),
         tx("00000003", 1, 20240101, "P", 125), tx("00000003", 2, 20240101, "P", 1000),
         tx("00000003", 3, 20240101, "W", 750)])

    # calendar boundaries
    add("date-bounds",
        "1900-01-01 lower bound OKAY; 2099-12-31 upper bound OKAY (age 199); 2100-01-01 DATE; "
        "1900-02-29 DATE (not leap); 2000-02-29 OKAY; 2025-04-31 DATE; 2025-13-01 DATE; 0 DATE; "
        "negative DATE; 999990101 DATE; date before SDATE DATE",
        [st("00000001", 19000101, 10_000), st("00000002", 19000101, 10_000),
         st("00000003", 20000101, 10_000), st("00000004", 20250101, 10_000)],
        [tx("00000001", 1, 19000101, "Q", 0), tx("00000001", 2, 20991231, "Q", 0),
         tx("00000001", 3, 21000101, "Q", 0),
         tx("00000002", 1, 19000229, "Q", 0), tx("00000002", 2, 19000301, "Q", 0),
         tx("00000003", 1, 20000229, "Q", 0),
         tx("00000004", 1, 20250431, "Q", 0), tx("00000004", 2, 20251301, "Q", 0),
         tx("00000004", 3, 0, "Q", 0), tx("00000004", 4, -20250101, "Q", 0),
         tx("00000004", 5, 999990101, "Q", 0), tx("00000004", 6, 20241231, "Q", 0),
         tx("00000004", 7, 20250101, "Q", 0)])

    # leap-day anniversary and age/fee waiver boundary and rate bands
    add("age-rate-bands",
        "issue 2015-06-01: quotes on every rate-band edge (2019-12-31 175/600, 2020-01-01 225/500, "
        "2023-12-31 225/500, 2024-01-01 300/400, 2024-12-31 300/400, 2025-01-01 325/350), age 9 with fee "
        "on 2025-05-31, age 10 fee waived on 2025-06-01; issue 2024-02-29: 2025-02-28 age 0, 2025-03-01 age 1",
        [st("00000001", 20150601, 5_000_000), st("00000002", 20240229, 5_000_000)],
        [tx("00000001", 1, 20191231, "Q", 0), tx("00000001", 2, 20200101, "Q", 0),
         tx("00000001", 3, 20231231, "Q", 0), tx("00000001", 4, 20240101, "Q", 0),
         tx("00000001", 5, 20241231, "Q", 0), tx("00000001", 6, 20250101, "Q", 0),
         tx("00000001", 7, 20250531, "Q", 0), tx("00000001", 8, 20250601, "Q", 0),
         tx("00000002", 1, 20250228, "Q", 0), tx("00000002", 2, 20250301, "Q", 0)])

    # packed signs: F accepted as positive, negative zero accepted as zero with the D nibble kept in SLAST
    p = "00000001"
    negzero = raw_tx(p, 2, 20250101, codec.text("P"), bytes(3), amt(0, "D"), bytes(13))
    add("packed-signs",
        "P 12345 with F sign OKAY; P negative-zero (0000000D) OKAY as 0 and SLAST keeps the D nibble; "
        "exact replay of the negative-zero record DUPL; same seq with C-sign zero CNFL; W 1 with F sign "
        "OKAY; P -5 NEGA; W -0 (negative zero, seq 4) OKAY",
        [st(p, 20250101, 100_000)],
        [raw_tx(p, 1, 20250101, codec.text("P"), bytes(3), amt(12_345, "F"), bytes(13)),
         negzero, negzero,
         raw_tx(p, 2, 20250101, codec.text("P"), bytes(3), amt(0, "C"), bytes(13)),
         raw_tx(p, 3, 20250101, codec.text("W"), bytes(3), amt(1, "F"), bytes(13)),
         tx(p, 4, 20250101, "P", -5),
         raw_tx(p, 4, 20250101, codec.text("W"), bytes(3), amt(0, "D"), bytes(13))])

    # malformed records and first-failure precedence
    add("malformed",
        "PACK digit nibble A; PACK sign nibble 0; PACK sign E; FORM nonzero TPAD; FORM nonzero TTAIL; "
        "TYPE op X; TYPE lowercase p; AMNT Q 1; AMNT D 1; FORM before PACK; PACK before TYPE; NEGA before "
        "DATE; OVER (amount > MAXAMT) before TYPE; DATE before TYPE; TYPE before FUND-size amount",
        [st(p, 20250101, 100_000)],
        [raw_tx(p, 1, 20250101, codec.text("P"), bytes(3), bytes.fromhex("000000000a012c"), bytes(13)),
         raw_tx(p, 1, 20250101, codec.text("P"), bytes(3), bytes.fromhex("00000000001230"), bytes(13)),
         raw_tx(p, 1, 20250101, codec.text("P"), bytes(3), bytes.fromhex("0000000000123e"), bytes(13)),
         raw_tx(p, 1, 20250101, codec.text("P"), b"\0\0\1", amt(100), bytes(13)),
         raw_tx(p, 1, 20250101, codec.text("P"), bytes(3), amt(100), bytes(12) + b"\x40"),
         tx(p, 1, 20250101, "X", 100), tx(p, 1, 20250101, "p", 100),
         tx(p, 1, 20250101, "Q", 1), tx(p, 1, 20250101, "D", 1),
         raw_tx(p, 1, 20250101, codec.text("P"), b"\xff\0\0", bytes.fromhex("000000000a012c"), bytes(13)),
         raw_tx(p, 1, 20250101, codec.text("X"), bytes(3), bytes.fromhex("000000000a012c"), bytes(13)),
         tx(p, 1, 21000101, "P", -1),
         tx(p, 1, 20250101, "X", MAX + 1),
         tx(p, 1, 21000101, "X", 1),
         tx(p, 1, 20250101, "X", 99_999_999_999)])

    # sequence matrix
    q = "00000002"
    okay1 = tx(p, 1, 20250101, "P", 100)
    add("sequence-matrix",
        "seq 0 ORDR; seq -1 ORDR; seq 1 OKAY; exact replay DUPL; same seq other amount CNFL; same seq with "
        "FORM defect CNFL (CNFL precedes FORM); seq 0 with bad packed ORDR; gap to seq 5 OKAY; seq 3 ORDR; "
        "seq 7 DATE-rejected then seq 7 OKAY (rejection did not advance SSEQ); unknown policy NPOL; "
        "unknown policy with malformed bytes NPOL; second policy untouched",
        [st(p, 20250101, 100_000), st(q, 20250101, 100_000)],
        [tx(p, 0, 20250101, "P", 100), tx(p, -1, 20250101, "P", 100), okay1, okay1,
         tx(p, 1, 20250101, "P", 101),
         raw_tx(p, 1, 20250101, codec.text("P"), b"\0\0\1", amt(100), bytes(13)),
         raw_tx(p, 0, 20250101, codec.text("P"), bytes(3), bytes.fromhex("000000000a012c"), bytes(13)),
         tx(p, 5, 20250101, "P", 100), tx(p, 3, 20250101, "P", 100),
         tx(p, 7, 21000101, "P", 100), tx(p, 7, 20250101, "P", 100),
         tx("00000009", 1, 20250101, "P", 100),
         raw_tx("00000009", 1, 20250101, codec.text("X"), b"\1\1\1", bytes(7), bytes(13))])

    # funds and operations
    add("funds-ops",
        "W > cash FUND; L > cash FUND; L = cash OKAY; R > loan FUND; W leaving cash < loan FUND; R full "
        "OKAY; D 0 OKAY (does not close); Q after D OKAY; P MAXAMT into cash 0 (0 days) OKAY; then Q one "
        "day later OVER (cash+interest); W MAXAMT back to 0 OKAY",
        [st(p, 20250101, 100_000), st(q, 20250101, 0, face=0)],
        [tx(p, 1, 20250101, "W", 100_001), tx(p, 2, 20250101, "L", 100_001),
         tx(p, 3, 20250101, "L", 100_000), tx(p, 4, 20250101, "R", 100_001),
         tx(p, 5, 20250101, "W", 1), tx(p, 6, 20250101, "R", 100_000),
         tx(p, 7, 20250101, "D", 0), tx(p, 8, 20250101, "Q", 0),
         tx(q, 1, 20250101, "P", MAX), tx(q, 2, 20250102, "Q", 0),
         tx(q, 3, 20250101, "W", MAX)])

    # empty inputs (genuine, with matching manifests)
    add("empty-txnin", "two policies, zero transactions: unchanged master, zero results",
        [st(p, 20250101, 100_000), st(q, 20250101, 5)], [])
    add("empty-polin", "no policies, three transactions: NPOL each, empty POLOUT",
        [], [tx(p, 1, 20250101, "P", 100), tx(q, 1, 20250101, "Q", 0), okay1])

    # controls: invalid master input must not run (guest RC=12, Java bootstrap refusal)
    add("control-master-loan-over-cash", "master with loan > cash: INSVAL rejects at load, RC=12",
        [st(p, 20250101, 100, loan=101)], [okay1], control="RC=12")
    add("control-master-unordered", "master IDs out of order: RC=12",
        [st(q, 20250101, 100), st(p, 20250101, 100)], [okay1], control="RC=12")
    add("control-master-duplicate", "duplicate master IDs: RC=12",
        [st(p, 20250101, 100), st(p, 20250101, 100)], [okay1], control="RC=12")
    add("control-master-513", "513 policies: RC=12",
        [st(f"{i:08d}", 20250101, 100) for i in range(1, 514)], [okay1], control="RC=12")
    return cases


def oracle_statuses(case: dict) -> list[str] | None:
    try:
        _, resout = oracle.batch(case["polin"], case["txnin"])
    except ValueError:
        return None
    return [codec.decode(r, "result")["status"] for r in codec.records(resout, "result")]


def write_cases(out: Path) -> None:
    out.mkdir(parents=True, exist_ok=False)
    index = []
    for case in build_cases():
        d = out / case["name"]
        d.mkdir()
        (d / "polin.bin").write_bytes(case["polin"])
        (d / "txnin.bin").write_bytes(case["txnin"])
        index.append({
            "name": case["name"], "description": case["description"], "control": case["control"],
            "policies_count": len(case["polin"]) // 128,
            "transactions_count": len(case["txnin"]) // 40,
            "polin_sha256": sha(case["polin"]), "txnin_sha256": sha(case["txnin"]),
            "oracle_statuses": oracle_statuses(case),
        })
    (out / "cases.json").write_text(json.dumps(index, indent=2) + "\n")
    print(f"{len(index)} cases written to {out}")


# ---------------------------------------------------------------------------- authority binding


def load_provenance(build: Path, guest: Path, golden: Path, load_prefix: str) -> dict[str, str]:
    """Hash the build/guest manifests of the load library the capture used, and the rate table.

    The build manifest must name the captured load library (load_prefix), so a manifest of a
    different build cannot be substituted. Absent or unreadable files fail closed.
    """
    for label, f in (("build manifest", build), ("guest manifest", guest)):
        if not f.is_file():
            raise AuthorityError(f"targeted: missing {label} {f} (--build-manifest/--guest-manifest "
                                 f"or <authority>/../provenance/)")
    build_bytes, guest_bytes = build.read_bytes(), guest.read_bytes()
    try:
        build_doc, guest_doc = json.loads(build_bytes), json.loads(guest_bytes)
    except ValueError as e:
        raise AuthorityError(f"targeted: unreadable provenance manifest: {e}")
    if not isinstance(build_doc, dict) or build_doc.get("load_prefix") != load_prefix:
        got = build_doc.get("load_prefix") if isinstance(build_doc, dict) else None
        raise AuthorityError(f"targeted: build manifest {build} is for load library {got!r}, "
                             f"the capture used {load_prefix!r}")
    if not isinstance(guest_doc, dict) or not guest_doc:
        raise AuthorityError(f"targeted: guest manifest {guest} is not an object")
    return {
        "load_prefix": load_prefix, "backend": build_doc.get("backend"),
        "build_manifest": str(build), "build_manifest_sha256": sha(build_bytes),
        "guest_manifest": str(guest), "guest_manifest_sha256": sha(guest_bytes),
        "rates_json": str(golden / "rates.json"),
        "rates_sha256": sha((golden / "rates.json").read_bytes()),
    }


def default_manifests(args) -> tuple[Path, Path]:
    d = args.authority.parent / "provenance"
    return (args.build_manifest or d / "build.json", args.guest_manifest or d / "guest.json")


def decoded_statuses(resout: bytes) -> list[str] | None:
    if len(resout) % 96:
        return None
    return [codec.decode(r, "result")["status"] for r in codec.records(resout, "result")]


def build_receipt(case: dict, job: dict, job_result: bytes, data: dict[str, bytes],
                  prov: dict[str, str], dataset_prefix: str, seconds: float) -> dict:
    """Guest receipt in the legacy insurance-run-v1 shape (derivation of tools/tk5_validate.py:
    outcome from passed, timed_out from purged, abend None) plus the targeted-case fields."""
    statuses = decoded_statuses(data["resout"])
    return {
        "schema": "insurance-run-v1", "targeted": TARGETED_RECEIPT,
        "case": case["name"], "control": bool(case["control"]), "job_id": job["job_id"],
        "outcome": "completed" if job["passed"] else "failed",
        "timed_out": not job["purged"], "abend": None,
        "step_rc": {s: int(c) for s, c in job["steps"]},
        "passed": job["passed"], "purged": job["purged"], "errors": job["errors"],
        "dataset_prefix": dataset_prefix, "load_prefix": prov["load_prefix"],
        "seconds": round(seconds, 1),
        "polin_sha256": sha(data["polin"]), "txnin_sha256": sha(data["txnin"]),
        "polout_sha256": sha(data["polout"]), "resout_sha256": sha(data["resout"]),
        "build_manifest_sha256": prov["build_manifest_sha256"],
        "guest_manifest_sha256": prov["guest_manifest_sha256"],
        "rates_sha256": prov["rates_sha256"],
        "job_result_sha256": sha(job_result),
        "observed_statuses": statuses,
        "oracle_statuses": case["oracle_statuses"],
        "oracle_agrees": statuses == case["oracle_statuses"] if statuses is not None else None,
    }


def validate_control_receipt(receipt: dict, hashes: dict[str, str]) -> None:
    """Receipt of a case the guest must have REJECTED (host validation RC=12).

    Same fail-closed rules as compare.validate_receipt (schema, job id, no timeout, no ABEND,
    every supplied hash) except the outcome: the run must have failed with RUN RC=12 exactly and
    no other step; a control the guest accepted is not evidence of rejection.
    """
    if receipt.get("schema") != "insurance-run-v1":
        raise ValueError("missing/unsupported run receipt schema")
    if not isinstance(receipt.get("job_id"), str) or not receipt["job_id"]:
        raise ValueError("missing job_id")
    if receipt.get("timed_out") is not False:
        raise ValueError("control run timed out or lacks timeout evidence")
    if "abend" not in receipt or receipt["abend"] is not None:
        raise ValueError("ABEND or missing ABEND evidence")
    if receipt.get("outcome") != "failed" or receipt.get("passed") is not False:
        raise ValueError("control run was not rejected by the guest")
    rc = receipt.get("step_rc")
    if not isinstance(rc, dict) or set(rc) != {"RUN"} or type(rc["RUN"]) is not int \
            or rc["RUN"] != CONTROL_RC:
        raise ValueError(f"control RUN RC is not exactly {CONTROL_RC}: {rc}")
    for name, value in hashes.items():
        if receipt.get(name) != value:
            raise ValueError(f"receipt hash mismatch: {name}")


def validate_case_authority(case: dict, authority: Path, cases: Path,
                            prov: dict[str, str]) -> tuple[dict, dict[str, bytes]]:
    """Refuse a captured case unless its bytes and receipt bind to pinned inputs and provenance."""
    name = case["name"]
    pinned = {}
    for k in ("polin", "txnin"):
        f = cases / name / f"{k}.bin"
        if not f.is_file():
            raise AuthorityError(f"{name}: missing pinned case input {f}")
        pinned[k] = f.read_bytes()
        if sha(pinned[k]) != case[f"{k}_sha256"]:
            raise AuthorityError(f"{name}: cases-dir {k}.bin differs from cases.json")
    d = authority / name
    data = {}
    for k in ("polin", "txnin", "polout", "resout"):
        f = d / f"{k}.bin"
        if not f.is_file():
            raise AuthorityError(f"{name}: missing {f}")
        data[k] = f.read_bytes()
    for k in ("polin", "txnin"):
        if data[k] != pinned[k]:
            raise AuthorityError(f"{name}: authority {k}.bin differs from the pinned case input")
    receipt_path = d / "receipt.json"
    if not receipt_path.is_file():
        raise AuthorityError(f"{name}: missing guest receipt {receipt_path}")
    try:
        receipt = json.loads(receipt_path.read_text())
    except ValueError as e:
        raise AuthorityError(f"{name}: unreadable guest receipt: {e}") from None
    if not isinstance(receipt, dict):
        raise AuthorityError(f"{name}: guest receipt is not an object")
    if receipt.get("case") != name or receipt.get("control") is not bool(case["control"]):
        raise AuthorityError(f"{name}: guest receipt is for another case/kind")
    hashes = {
        "polin_sha256": sha(pinned["polin"]), "txnin_sha256": sha(pinned["txnin"]),
        "polout_sha256": sha(data["polout"]), "resout_sha256": sha(data["resout"]),
        "build_manifest_sha256": prov["build_manifest_sha256"],
        "guest_manifest_sha256": prov["guest_manifest_sha256"],
        "rates_sha256": prov["rates_sha256"],
    }
    assert tuple(hashes) == pj.GUEST_RECEIPT_HASH_KEYS
    missing = [k for k in pj.GUEST_RECEIPT_HASH_KEYS if k not in receipt]
    if missing:
        raise AuthorityError(f"{name}: guest receipt lacks {missing}")
    try:
        if case["control"]:
            validate_control_receipt(receipt, hashes)
        else:
            validate_receipt(receipt, hashes)  # unchanged legacy validator
    except ValueError as e:
        raise AuthorityError(f"{name}: guest receipt rejected: {e}")
    if receipt.get("observed_statuses") != decoded_statuses(data["resout"]):
        raise AuthorityError(f"{name}: receipt observed_statuses differ from the observed RESOUT")
    return receipt, data


# ---------------------------------------------------------------------------- guest capture


def capture(args) -> None:
    from tk5 import Guest  # noqa: E402  (unchanged guest transport)
    index = json.loads((args.cases / "cases.json").read_text())
    golden = SAMPLE / "golden" / "v1"
    pj.verify_golden_inventory(golden)
    prov = load_provenance(args.build_manifest, args.guest_manifest, golden, args.load_prefix)
    authority = args.evidence / "authority"
    authority.mkdir(parents=True, exist_ok=False)
    provenance = args.evidence / "provenance"
    provenance.mkdir()
    (provenance / "build.json").write_bytes(args.build_manifest.read_bytes())
    (provenance / "guest.json").write_bytes(args.guest_manifest.read_bytes())
    summary = []
    with Guest(args.evidence / "guest", args.root) as guest:
        for n, case in enumerate(index, 1):
            d = args.cases / case["name"]
            polin, txnin = (d / "polin.bin").read_bytes(), (d / "txnin.bin").read_bytes()
            if sha(polin) != case["polin_sha256"] or sha(txnin) != case["txnin_sha256"]:
                raise ValueError(f"{case['name']}: case bytes differ from cases.json")
            prefix = f"{args.prefix}.C{n:02d}"
            label = f"{n:02d}-{case['name']}"
            started = time.time()
            polout, resout, job = guest.batch(label, args.load_prefix, prefix, polin, txnin,
                                              allow_failure=bool(case["control"]))
            out = authority / case["name"]
            out.mkdir()
            data = {"polin": polin, "txnin": txnin, "polout": polout, "resout": resout}
            for k, v in data.items():
                (out / f"{k}.bin").write_bytes(v)
            job_result = (guest.evidence / (label + "-run") / "result.json").read_bytes()
            receipt = build_receipt(case, job, job_result, data, prov, prefix,
                                    time.time() - started)
            (out / "receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
            summary.append(receipt)
            print(f"{case['name']}: RUN RC={receipt['step_rc'].get('RUN')} "
                  f"statuses={receipt['observed_statuses']}", flush=True)
    (authority / "capture.json").write_text(json.dumps({
        "schema": "insurance-a3-targeted-capture-v2", "load_prefix": args.load_prefix,
        "prefix": args.prefix, "provenance": prov, "cases": summary},
        indent=2, sort_keys=True) + "\n")


def rederive(args) -> None:
    """Rebuild receipts from retained raw guest evidence; no guest job is executed."""
    index = json.loads((args.cases / "cases.json").read_text())
    golden = SAMPLE / "golden" / "v1"
    pj.verify_golden_inventory(golden)
    capture_path = args.authority / "capture.json"
    summary_doc = json.loads(capture_path.read_text())
    prov = load_provenance(args.build_manifest, args.guest_manifest, golden,
                           summary_doc["load_prefix"])
    provenance = args.authority.parent / "provenance"
    provenance.mkdir(exist_ok=True)
    (provenance / "build.json").write_bytes(args.build_manifest.read_bytes())
    (provenance / "guest.json").write_bytes(args.guest_manifest.read_bytes())
    summary = []
    for n, case in enumerate(index, 1):
        name = case["name"]
        d = args.authority / name
        old = json.loads((d / "receipt.json").read_text())
        data = {k: (d / f"{k}.bin").read_bytes() for k in ("polin", "txnin", "polout", "resout")}
        for k in ("polin", "txnin"):
            if sha(data[k]) != case[f"{k}_sha256"] or \
                    data[k] != (args.cases / name / f"{k}.bin").read_bytes():
                raise AuthorityError(f"{name}: authority {k}.bin is not the pinned case input")
        result_path = args.guest / f"{n:02d}-{name}-run" / "result.json"
        if not result_path.is_file():
            raise AuthorityError(f"{name}: missing raw guest evidence {result_path}")
        job_result = result_path.read_bytes()
        if sha(job_result) != old.get("job_result_sha256"):
            raise AuthorityError(f"{name}: raw guest result.json differs from the capture-time "
                                 f"receipt's job_result_sha256")
        job = json.loads(job_result)
        if job["job_id"] != old["job_id"] or {s: int(c) for s, c in job["steps"]} != old["step_rc"]:
            raise AuthorityError(f"{name}: raw guest evidence disagrees with the capture-time receipt")
        receipt = build_receipt(case, job, job_result, data, prov, old["dataset_prefix"],
                                old["seconds"])
        receipt["derived_from"] = {"capture_receipt_sha256": sha((d / "receipt.json").read_bytes()),
                                   "guest_result_json": str(result_path.relative_to(args.guest))}
        (d / "receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        summary.append(receipt)
        print(f"{name}: receipt rederived from {result_path.name} (RUN RC="
              f"{receipt['step_rc'].get('RUN')})", flush=True)
    summary_doc.update({"schema": "insurance-a3-targeted-capture-v2", "provenance": prov,
                        "cases": summary, "rederived": True})
    capture_path.write_text(json.dumps(summary_doc, indent=2, sort_keys=True) + "\n")


# ---------------------------------------------------------------------------- java comparison


def compare(args) -> None:
    index = json.loads((args.cases / "cases.json").read_text())
    args.work.mkdir(parents=True, exist_ok=True)
    jar_sha = sha(args.jar.read_bytes())
    golden = SAMPLE / "golden" / "v1"
    try:
        pj.verify_golden_inventory(golden)
        capture_doc = json.loads((args.authority / "capture.json").read_text())
        build, guest = default_manifests(args)
        prov = load_provenance(build, guest, golden, capture_doc["load_prefix"])
    except (AuthorityError, OSError, KeyError, ValueError) as e:
        raise SystemExit(f"FAIL: targeted authority rejected before any Java code ran: {e}")
    rates_sha = pj.rate_table_sha(golden / "rates.json")
    runner = pj.BatchRunner(args) if args.mode == "batch" else pj.HttpRunner(args, args.mode)
    report = {"schema": "insurance-java-targeted-parity-v2", "mode": args.mode,
              "authority": {"kind": "a3-targeted", "dir": str(args.authority),
                            "cases_dir": str(args.cases), "provenance": prov},
              "jar_sha256": jar_sha, "source_commit": args.source_commit, "cases": []}
    for n, case in enumerate(index, 1):
        name = case["name"]
        entry = {"case": name, "description": case["description"], "control": case["control"],
                 "oracle_statuses": case["oracle_statuses"], "errors": [], "mismatches": []}
        try:
            receipt, data = validate_case_authority(case, args.authority, args.cases, prov)
        except AuthorityError as e:
            entry["errors"].append(f"authority rejected: {e}")
            entry["passed"] = False
            report["cases"].append(entry)
            print(f"{name}: AUTHORITY REJECTED ({e}) -> FAIL", flush=True)
            continue
        entry.update({"guest_job_id": receipt["job_id"], "guest_step_rc": receipt["step_rc"],
                      "guest_receipt_validated": "control" if case["control"] else "legacy",
                      "observed_statuses": receipt["observed_statuses"]})
        ns = f"{args.namespace_prefix}t{n:02d}"
        gen = f"{ns}-run"
        policies, txns = len(data["polin"]) // 128, len(data["txnin"]) // 40
        _, root_manifest = pj.manifest_for(f"{name}-root", policies, 0, data["polin"], b"",
                                           rates_sha)
        boot_error = runner.bootstrap(ns, f"{ns}-root", data["polin"], root_manifest)
        if case["control"]:
            entry["guest_rejected"] = True  # validate_control_receipt required RUN RC=12
            entry["java_rejected"] = boot_error is not None
            entry["java_error"] = boot_error
            entry["passed"] = entry["java_rejected"] and not entry["errors"]
            report["cases"].append(entry)
            print(f"{name}: guest RC={receipt['step_rc'].get('RUN')} java_rejected={boot_error is not None}"
                  f" -> {'PASS' if entry['passed'] else 'FAIL'}", flush=True)
            continue
        if boot_error:
            entry["errors"].append(f"bootstrap: {boot_error}")
            entry["passed"] = False
            report["cases"].append(entry)
            continue
        if receipt["observed_statuses"] != case["oracle_statuses"]:
            entry["errors"].append("guest statuses differ from the source-derived oracle's expectation "
                                   "(case may not exercise the intended path; inspect)")
        manifest, manifest_bytes = pj.manifest_for(name, policies, txns, data["polin"], data["txnin"],
                                                   rates_sha)
        out = runner.run(ns, f"{ns}-root", gen, data, manifest_bytes)
        entry["errors"] += out.errors
        entry["request_errors"] = out.request_errors
        entry["typed_requests"], entry["raw_requests"] = out.typed, out.raw
        if out.receipt is not None:
            entry["observed_sha256"] = {"polout": sha(out.polout), "resout": sha(out.resout)}
            if out.requests is not None and out.requests != data["txnin"]:
                entry["errors"].append("published request bytes differ from TXNIN")
            entry["receipt_errors"] = pj.validate_java_receipt(out.receipt, {
                "mode": runner.mode, "stage": name, "namespace": ns, "generation": gen,
                "parent_generation": f"{ns}-root", "source_commit": args.source_commit,
                "build_identity": f"jar:sha256:{jar_sha}", "rate_table_sha256": rates_sha,
                "expected_manifest_sha256": sha(manifest_bytes),
                "polin_sha256": sha(data["polin"]), "txnin_sha256": sha(data["txnin"]),
                "polout_sha256": sha(out.polout), "resout_sha256": sha(out.resout),
                "policies_count": policies, "transactions_count": txns, "results_count": txns,
                "typed_requests": out.typed, "raw_requests": out.raw,
            })
            ids = {i + 1: f"{name}#{i + 1}" for i in range(txns)}
            entry["mismatches"] = differences(data["resout"], out.resout, "result", ids)
            entry["mismatches"] += differences(data["polout"], out.polout, "state")
            entry["java_receipt"] = out.receipt
        else:
            entry["receipt_errors"] = ["no Java receipt"]
        entry["passed"] = not entry["errors"] and not entry["mismatches"] \
            and not entry["receipt_errors"] and not entry["request_errors"]
        report["cases"].append(entry)
        print(f"{name}: {txns} results, statuses={receipt['observed_statuses']} -> "
              f"{'PASS' if entry['passed'] else 'FAIL'}", flush=True)
    report["cases_passed"] = sum(1 for c in report["cases"] if c["passed"])
    report["cases_total"] = len(report["cases"])
    report["results_compared"] = sum(len(c["observed_statuses"] or []) for c in report["cases"]
                                     if c["passed"] and not c["control"])
    report["passed"] = report["cases_passed"] == report["cases_total"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(f"{'PASS' if report['passed'] else 'FAIL'}: mode={args.mode} cases "
          f"{report['cases_passed']}/{report['cases_total']}, results compared "
          f"{report['results_compared']}; report {args.report}")
    if not report["passed"]:
        raise SystemExit(1)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    c = sub.add_parser("cases")
    c.add_argument("--out", type=Path, required=True)
    g = sub.add_parser("capture")
    g.add_argument("--cases", type=Path, required=True)
    g.add_argument("--evidence", type=Path, required=True)
    g.add_argument("--root", type=Path, default=Path.home() / "mvs-demo")
    g.add_argument("--prefix", required=True, help="dataset HLQ for this capture (fresh)")
    g.add_argument("--load-prefix", required=True, help="HLQ of the guest-built LOAD library")
    g.add_argument("--build-manifest", type=Path, required=True,
                   help="build.json of the guest build that produced --load-prefix")
    g.add_argument("--guest-manifest", type=Path, required=True, help="guest.json of that run")
    r = sub.add_parser("rederive")
    r.add_argument("--cases", type=Path, required=True)
    r.add_argument("--authority", type=Path, required=True)
    r.add_argument("--guest", type=Path, required=True,
                   help="retained raw guest evidence directory of the capture (<NN>-<case>-run/)")
    r.add_argument("--build-manifest", type=Path, required=True)
    r.add_argument("--guest-manifest", type=Path, required=True)
    j = sub.add_parser("compare")
    j.add_argument("--build-manifest", type=Path,
                   help="default: <authority>/../provenance/build.json")
    j.add_argument("--guest-manifest", type=Path,
                   help="default: <authority>/../provenance/guest.json")
    j.add_argument("--cases", type=Path, required=True)
    j.add_argument("--authority", type=Path, required=True)
    j.add_argument("--mode", choices=pj.MODES, default="batch")
    j.add_argument("--jar", type=Path, required=True)
    j.add_argument("--base-url", default="http://127.0.0.1:8080")
    j.add_argument("--namespace-prefix", default="")
    j.add_argument("--work", type=Path, required=True)
    j.add_argument("--report", type=Path, required=True)
    j.add_argument("--source-commit", required=True)
    args = p.parse_args()
    if args.command == "cases":
        write_cases(args.out)
    elif args.command == "capture":
        capture(args)
    elif args.command == "rederive":
        rederive(args)
    else:
        compare(args)


if __name__ == "__main__":
    main()
