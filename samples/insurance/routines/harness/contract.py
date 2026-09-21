"""Emit the machine-readable routine contract and the rule -> case coverage matrix.

    python3 contract.py --out ../contract [--observed DIR ...]

contract.json  entry points, R1 convention, input/output fields with offsets,
               widths and representations, R15 meaning, byte classes (stable /
               scratch / unasserted), register + save-chain assertions, and what
               is deliberately NOT asserted (undefined).
coverage.json  every rule with its cases and, when --observed comparison
               reports are supplied, whether the cases were measured on the
               real guest (PASS/FAIL) or remain unmeasured.

The contract describes the frozen caller interface as read from src/, copy/ and
INSENT/INSRET. It never asserts a condition code and never asserts registers
the linkage macros do not preserve (R15 is the return code; R1 is restored by
INSRET's LM 0,12 and is therefore asserted only as an ABI observation).
"""

import argparse
import json
from pathlib import Path

from cases import all_cases
from fixtures import SCRATCH, UNASSERTED
from layout import (CAPTURE, LIBRARY, MAX_AMOUNT, PROGRAMS, ROUTINES, WORK, WORKLEN,
                    sha256)

REPRESENTATION = {
    "text": "CP037 characters, fixed width, no NUL terminator",
    "binary": "big-endian two's-complement fullword",
    "packed": "signed packed decimal; digits 0-9, sign nibble C (+), D (-) or F (+ unsigned)",
    "hex": "raw bytes; asserted byte for byte",
}

RULES = {
    # INSPACK
    "PK-VALID": "all seven digit nibbles 0-9 and a C/D/F sign nibble validate (R15=0)",
    "PK-SIGN": "sign nibble classes: C, D, F accepted (including negative zero D0); A, B, E and digit nibbles rejected",
    "PK-DIGIT": "any digit nibble A-F in any position rejects (R15=8); positions 0, 3, 6 and high/low nibble",
    # INSDATE
    "DT-RANGE": "years 1900..2099 valid; 1899 and 2100 invalid; zero and negative fullwords invalid",
    "DT-1900": "1900 is not a leap year (Gregorian century rule); 19000228 valid, 19000229 invalid, 19000301 ordinal 59",
    "DT-LEAP": "leap years 1904, 2000 (400-year rule), 2024; 2023 and 2100 non-leap",
    "DT-MONTH": "month 0 and 13 invalid",
    "DT-DAY": "day 0, 31 in a 30-day month, 32, and 30 in February invalid; 31 in a 31-day month valid",
    "DT-CONV": "WYEAR, WMD and WORD (days since 19000101) written for valid dates",
    "DT-EPOCH": "19000101 is ordinal 0",
    "DT-PREC": "invalid month and invalid day in one value: rejected once, WMD/WORD not written",
    "DT-SIGN": "negative or zero WDATE: WVALID=8 and WYEAR/WMD/WORD untouched",
    # INSRATE
    "RT-BAND1": "TDATE 19000101..19991231 -> rate 125 bps, fee 700 bps",
    "RT-BAND2": "TDATE 20000101..20191231 -> rate 175, fee 600",
    "RT-BAND3": "TDATE 20200101..20231231 -> rate 225, fee 500",
    "RT-BAND4": "TDATE 20240101..20241231 -> rate 300, fee 400",
    "RT-BAND5": "TDATE >= 20250101 -> rate 325, fee 350 (open-ended, e.g. 20991231 and beyond)",
    "RT-AGE": "WAGE >= 10 zeroes the fee; WAGE 9 keeps it; negative WAGE keeps it",
    "RT-DOMAIN": "direct domain is the binary fullword TDATE and WAGE only: INSRATE does not validate dates; "
                 "a raw invalid date such as 20241399 is banded by numeric comparison and TDATE < 19000101 "
                 "writes neither WRATE nor WFEE",
    # INSVAL
    "VL-OK": "well-formed first-state and continued-state masters validate (R15=0, WVALID=0)",
    "VL-ID": "SID must be eight CP037 digits F0-F9; blanks, letters, lower nibble > 9 and X'00' reject",
    "VL-PAD": "SPAD (7 reserved bytes) must be zero",
    "VL-TAIL": "STAIL (40 trailing bytes) must be zero",
    "VL-SEQ": "SSEQ must be >= 0 (negative fullword rejects); large positive accepted",
    "VL-PACK": "SFACE, SCASH and SLOAN must be valid packed decimal (sign and digit nibbles)",
    "VL-MAX": f"amounts 0..{MAX_AMOUNT} cents; MAXAMT accepted, MAXAMT+1 rejected",
    "VL-NEG": "negative face, cash or loan rejects (D sign with nonzero digits); negative zero is numerically zero",
    "VL-LOAN": "SLOAN may equal but not exceed SCASH",
    "VL-DATE": "SISSUE and SDATE must both be valid dates (INSDATE rules)",
    "VL-ORDER": "SDATE must not precede SISSUE",
    "VL-FIRST": "SSEQ=0 requires SLAST all zero and SDATE=SISSUE",
    "VL-HIST": "SSEQ>0 requires SLAST to carry SID, SSEQ and SDATE (historical checkpoint)",
    "VL-PREC": "when several validations fail, R15 is still 8 and no scratch other than WDATE/WYEAR/WMD/WORD/WVALID changes",
    # INSCALC
    "CA-STAT": "invalid state -> STAT, OCASH/OLOAN stay zero, SREC unchanged",
    "CA-NPOL": "TID != SID -> NPOL after echoing cash/loan",
    "CA-SEQ": "TSEQ <= 0 or TSEQ < SSEQ -> ORDR; TSEQ == SSEQ -> DUPL when TREC equals SLAST else CNFL; any TSEQ > SSEQ continues",
    "CA-DUPL": "replayed transaction (TSEQ == SSEQ, identical 40 bytes) is DUPL and does not change state",
    "CA-CNFL": "TSEQ == SSEQ with different transaction bytes is CNFL",
    "CA-ORDR": "TSEQ behind the state sequence (or non-positive) is ORDR; TDATE before SDATE is DATE",
    "CA-FORM": "TPAD/TTAIL must be zero (FORM)",
    "CA-PACK": "malformed TAMT packed digits/sign -> PACK",
    "CA-NEGA": "negative TAMT -> NEGA; negative zero D0 is accepted as zero",
    "CA-DATE": "invalid TDATE, or TDATE before SDATE -> DATE",
    "CA-TYPE": "operation not in P/W/L/R/Q/D -> TYPE",
    "CA-AGE": "policy age = TDATE year - SISSUE year, minus one before the anniversary month/day; fee zero from age 10",
    "CA-RATE": "period-end rate at each INSRATE boundary applied to the interest formula",
    "CA-TIE": "interest = half_up(cash * rate * days / 3650000); exact .5 ties round up (half-up on non-negative values)",
    "CA-PREC": "status precedence: STAT, NPOL, ORDR/DUPL/CNFL, FORM, PACK, NEGA, OVER(amount), DATE, then arithmetic; "
               "TYPE/AMNT/FUND/OVER after interest roll the state back",
    "CA-OVER": f"cash + interest > {MAX_AMOUNT} -> OVER with OINT zeroed and state rolled back; "
               "SCASH=MAXAMT 19000101->20250101 gives interest 406526027393 > MAXAMT",
    "CA-HIGH": "high values just below the cap commit",
    "CA-P": "P adds the premium to cash after interest",
    "CA-W": "W subtracts from cash; cash below zero or below the loan -> FUND",
    "CA-L": "L increases SLOAN; loan above cash -> FUND",
    "CA-R": "R decreases SLOAN; loan below zero -> FUND",
    "CA-Q": "Q requires zero amount (AMNT otherwise); posts interest and advances SSEQ/SDATE/SLAST",
    "CA-D": "D death quote: max(face, cash) - loan; zero amount required; does not close or settle the policy",
    "CA-CHG": "surrender charge = half_up(cash * fee / 10000); surrender = cash - charge - loan",
    "CA-SURR": "surrender value when loan is present",
    "CA-RAW": "raw byte semantics: F-sign inputs echo as C-sign outputs; negative zero; transaction bytes preserved",
}


def field_json(f) -> dict:
    return {"name": f.name, "offset": f.offset, "width": f.size, "kind": f.kind,
            "group": f.group, "representation": REPRESENTATION[f.kind]}


def entry(name: str, r1: str, reads: list[str], writes: list[str], r15: dict, notes: list[str]) -> dict:
    scratch, unasserted = SCRATCH.get(name, ()), UNASSERTED.get(name, ())
    return {
        "entry_point": name, "kind": "library routine (direct BALR 14,15 call)",
        "linkage": {"entry": "INSENT: STM 14,12,12(13); LR 12,15; own 18F SAVE chained via 4(SAVE)/8(caller)",
                    "return": "INSRET: L 13,4(13); L 14,12(13); LM 0,12,20(13); BR 14",
                    "preserved": "R0-R12 restored, R13 restored, R14 restored (low 24 bits asserted; "
                                 "BALR stores ILC/CC/mask in the high byte and that byte is not asserted)",
                    "return_code": "R15", "condition_code": "undefined at return; never asserted"},
        "r1": r1, "reads": reads, "writes": writes, "r15": r15,
        "byte_classes": {
            "stable": [f.name for f in WORK if f.name not in scratch and f.name not in unasserted],
            "scratch": list(scratch), "unasserted": list(unasserted)},
        "class_meaning": {
            "stable": "business-visible value or byte the routine must leave unchanged; Java must reproduce",
            "scratch": "written by the assembler implementation; asserted here against the model so the "
                       "guest evidence is complete, but NOT a Java requirement",
            "unasserted": "arithmetic scratch whose content is implementation-defined; recorded, not compared"},
        "storage_outside_work": "none permitted; guards around WORK, the argument and the save area are asserted",
        "notes": notes,
    }


def contract() -> dict:
    return {
        "schema": "insurance-routines-contract-v1",
        "worklen": WORKLEN,
        "capture_layout": {k: {"offset": o, "width": w} for k, (o, w) in CAPTURE.items()},
        "work_fields": [field_json(f) for f in WORK],
        "callable": [
            entry("INSPACK", "R1 -> seven packed bytes (not WORK)", ["7 bytes at R1"], [],
                  {"0": "valid packed decimal", "8": "invalid digit or sign nibble"},
                  ["pure predicate: WORK untouched, argument bytes untouched"]),
            entry("INSDATE", "R1 -> WORK", ["WDATE"], ["WVALID", "WYEAR", "WMD", "WORD"],
                  {"0": "always 0; validity is WVALID (0 valid / 8 invalid), not R15"},
                  ["WYEAR written for any positive WDATE; WMD/WORD only when the date is valid",
                   "ordinal WORD counts days since 19000101"]),
            entry("INSRATE", "R1 -> WORK", ["TDATE", "WAGE"], ["WRATE", "WFEE"],
                  {"0": "always 0"},
                  ["does not validate TDATE; see RT-DOMAIN", "WFEE zeroed when WAGE >= 10"]),
            entry("INSVAL", "R1 -> WORK", ["SREC (128 bytes)"], ["WVALID", "WDATE", "WYEAR", "WMD", "WORD"],
                  {"0": "state valid", "8": "state invalid"},
                  ["SREC never modified", "scratch WDATE/WYEAR/WMD/WORD come from the internal INSDATE calls"]),
            entry("INSCALC", "R1 -> WORK", ["SREC", "TREC"], ["SREC (on OKAY only)", "OREC", "scratch"],
                  {"0": "always 0; outcome is OSTAT (OKAY or a four-letter failure status)"},
                  ["TREC bytes are never modified", "WBEFORE holds the pre-call SREC; rollback restores it",
                   "OVERS is CP037 'V001'", "OTAIL/OPAD zero"]),
        ],
        "programs": [
            {"entry_point": "INSBAT", "kind": "job-level program (QSAM, PGM=INSBAT)",
             "ddnames": {"POLIN": "FB128 masters", "TXNIN": "FB40 transactions",
                         "POLOUT": "FB128 new generation", "RESOUT": "FB96 results"},
             "rc": {"0": "completed", "12": "invalid master, misordered master or more than 512 masters", "16": "OPEN failure"},
             "tested_by": "routines/harness/run_jobs.py (job-level only; internal labels are not entry points)"},
            {"entry_point": "INSSMOK", "kind": "job-level program (PGM=INSSMOK)",
             "rc": {"0": "all built-in hand assertions hold", "12": "an assertion failed"},
             "tested_by": "routines/harness/run_jobs.py smoke_standalone + the build job SMOKE step"},
        ],
        "not_entry_points": ["INSENT", "INSRET", "INSWORK (macros/copybook)", "internal labels inside any module"],
        "undefined_and_not_asserted": [
            "condition code at return", "R14 high byte after BALR (ILC/CC/program mask)",
            "WNUM/WPROD arithmetic scratch after INSCALC", "any register beyond R0-R15 general registers"],
        "rules": RULES,
        "library": list(LIBRARY), "programs_list": list(PROGRAMS),
    }


def coverage(observed: list[Path]) -> dict:
    measured: dict[str, dict] = {}
    for folder in observed:
        report = json.loads((folder / "comparison.json").read_text())
        run = json.loads((folder / "run.json").read_text())
        failed = {f["case"] for f in report["findings"]}
        for cid in report["cases_passed"]:
            measured.setdefault(cid, {})[run["backend"]] = "PASS"
        for cid in failed:
            measured.setdefault(cid, {})[run["backend"]] = "FAIL"
    rules = {}
    for case in all_cases():
        rules.setdefault(case.rule, {"routine": case.routine, "description": RULES.get(case.rule, ""),
                                     "cases": []})
        rules[case.rule]["cases"].append({"id": case.id, "note": case.note, "tags": list(case.tags),
                                          "hand_worked": bool(case.hand),
                                          "measured": measured.get(case.id, {}) or "unmeasured"})
    missing = sorted(set(RULES) - set(rules))
    for rule in rules.values():
        states = {m for c in rule["cases"] for m in (c["measured"].values() if isinstance(c["measured"], dict) else [])}
        rule["status"] = ("unmeasured" if not states else "FAIL" if "FAIL" in states else "PASS")
    return {"schema": "insurance-routines-coverage-v1", "rules": rules,
            "rules_without_cases": missing,
            "unmeasured_paths": [
                "INSBAT/INSSMOK internal control flow beyond RC, POLOUT and RESOUT bytes (job-level only)",
                "INSCALC behaviour for TDATE below 19000101 with a valid state (unreachable: INSVAL requires SDATE >= 19000101 and ORDR precedes rating)",
                "OPEN failure RC16 in INSBAT (requires a DD-level fault the harness does not inject)",
            ],
            "observed_runs": [p.name for p in observed],
            "cases_total": sum(len(r["cases"]) for r in rules.values()),
            "cases_measured": sum(1 for r in rules.values() for c in r["cases"] if c["measured"] != "unmeasured")}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=ROUTINES / "contract")
    parser.add_argument("--observed", type=Path, nargs="*", default=[])
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    doc = contract()
    (args.out / "contract.json").write_text(json.dumps(doc, indent=1, sort_keys=True) + "\n")
    cov = coverage(args.observed)
    (args.out / "coverage.json").write_text(json.dumps(cov, indent=1, sort_keys=True) + "\n")
    print("contract", sha256((args.out / "contract.json").read_bytes())[:16],
          "coverage", cov["cases_measured"], "/", cov["cases_total"], "cases measured;",
          sum(1 for r in cov["rules"].values() if r["status"] == "PASS"), "rules PASS,",
          sum(1 for r in cov["rules"].values() if r["status"] == "FAIL"), "FAIL,",
          sum(1 for r in cov["rules"].values() if r["status"] == "unmeasured"), "unmeasured;",
          "rules without cases:", cov["rules_without_cases"])


if __name__ == "__main__":
    main()
