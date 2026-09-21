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
      Execute every case on the guest (one job per case, one dataset prefix per case). Stores
      <case>/polout.bin, <case>/resout.bin, the guest job receipt and SHA-256 of everything in
      DIR/authority/. Cases marked "control" are allowed to fail (their guest RC is the evidence).
  compare  --cases DIR --authority DIR --jar JAR --work DIR --report FILE --source-commit SHA
           [--mode batch|http-gen|http-json] [--base-url URL] [--namespace-prefix P]
      Run the Java implementation over each captured case in its own namespace and compare the
      published POLOUT/RESOUT with the guest bytes using tools/compare.py's comparator. The
      decoded guest statuses must also equal the case's intended status list, so a case that
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
from compare import differences  # noqa: E402
import parity_java as pj  # noqa: E402

MAX = 99_999_999_999


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


# ---------------------------------------------------------------------------- guest capture


def capture(args) -> None:
    from tk5 import Guest  # noqa: E402  (unchanged guest transport)
    index = json.loads((args.cases / "cases.json").read_text())
    authority = args.evidence / "authority"
    authority.mkdir(parents=True, exist_ok=False)
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
            (out / "polin.bin").write_bytes(polin)
            (out / "txnin.bin").write_bytes(txnin)
            (out / "polout.bin").write_bytes(polout)
            (out / "resout.bin").write_bytes(resout)
            statuses = [codec.decode(r, "result")["status"] for r in codec.records(resout, "result")] \
                if len(resout) % 96 == 0 else None
            receipt = {
                "schema": "insurance-a3-targeted-v1", "case": case["name"], "job_id": job["job_id"],
                "dataset_prefix": prefix, "step_rc": {s: int(c) for s, c in job["steps"]},
                "passed": job["passed"], "purged": job["purged"], "errors": job["errors"],
                "seconds": round(time.time() - started, 1),
                "polin_sha256": sha(polin), "txnin_sha256": sha(txnin),
                "polout_sha256": sha(polout), "resout_sha256": sha(resout),
                "job_result_sha256": sha((guest.evidence / (label + "-run") / "result.json").read_bytes()),
                "observed_statuses": statuses,
                "oracle_statuses": case["oracle_statuses"],
                "oracle_agrees": statuses == case["oracle_statuses"] if statuses is not None else None,
            }
            (out / "receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
            summary.append(receipt)
            print(f"{case['name']}: RUN RC={receipt['step_rc'].get('RUN')} statuses={statuses}",
                  flush=True)
    (authority / "capture.json").write_text(json.dumps({
        "schema": "insurance-a3-targeted-capture-v1", "load_prefix": args.load_prefix,
        "prefix": args.prefix, "cases": summary}, indent=2, sort_keys=True) + "\n")


# ---------------------------------------------------------------------------- java comparison


def compare(args) -> None:
    index = json.loads((args.cases / "cases.json").read_text())
    args.work.mkdir(parents=True, exist_ok=True)
    jar_sha = sha(args.jar.read_bytes())
    golden = SAMPLE / "golden" / "v1"
    rates_sha = pj.rate_table_sha(golden / "rates.json")
    runner = pj.BatchRunner(args) if args.mode == "batch" else pj.HttpRunner(args, args.mode)
    report = {"schema": "insurance-java-targeted-parity-v1", "mode": args.mode,
              "authority": {"kind": "a3-targeted", "dir": str(args.authority)},
              "jar_sha256": jar_sha, "source_commit": args.source_commit, "cases": []}
    for n, case in enumerate(index, 1):
        name = case["name"]
        d = args.authority / name
        receipt = json.loads((d / "receipt.json").read_text())
        data = {k: (d / f"{k}.bin").read_bytes() for k in ("polin", "txnin", "polout", "resout")}
        entry = {"case": name, "description": case["description"], "control": case["control"],
                 "guest_job_id": receipt["job_id"], "guest_step_rc": receipt["step_rc"],
                 "observed_statuses": receipt["observed_statuses"],
                 "oracle_statuses": case["oracle_statuses"], "errors": [], "mismatches": []}
        for k, v in data.items():
            if sha(v) != receipt[f"{k}_sha256"]:
                entry["errors"].append(f"authority {k}.bin hash differs from the guest receipt")
        ns = f"{args.namespace_prefix}t{n:02d}"
        gen = f"{ns}-run"
        policies, txns = len(data["polin"]) // 128, len(data["txnin"]) // 40
        _, root_manifest = pj.manifest_for(f"{name}-root", policies, 0, data["polin"], b"", None)
        boot_error = runner.bootstrap(ns, f"{ns}-root", data["polin"], root_manifest)
        if case["control"]:
            entry["guest_rejected"] = receipt["step_rc"].get("RUN") == 12 and not receipt["passed"]
            entry["java_rejected"] = boot_error is not None
            entry["java_error"] = boot_error
            entry["passed"] = entry["guest_rejected"] and entry["java_rejected"] and not entry["errors"]
            report["cases"].append(entry)
            print(f"{name}: guest RC={receipt['step_rc'].get('RUN')} java_rejected={boot_error is not None}"
                  f" -> {'PASS' if entry['passed'] else 'FAIL'}", flush=True)
            continue
        if boot_error:
            entry["errors"].append(f"bootstrap: {boot_error}")
            entry["passed"] = False
            report["cases"].append(entry)
            continue
        if receipt["step_rc"].get("RUN") != 0 or not receipt["passed"]:
            entry["errors"].append("guest run did not complete with RC=0; no authority for this case")
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
    j = sub.add_parser("compare")
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
    else:
        compare(args)


if __name__ == "__main__":
    main()
