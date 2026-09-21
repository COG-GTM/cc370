"""Emit the machine-readable routine contract and the rule -> case coverage matrix.

    python3 contract.py --out ../contract [--observed DIR ...]

contract.json  entry points, R1 convention, input/output fields with offsets,
               widths and representations, R15 meaning, byte classes (output /
               input / preserved / scratch / unasserted), the per-routine Java
               mapping, register + save-chain assertions, and what is
               deliberately NOT asserted (undefined).
coverage.json  every rule with its cases and, when --observed evidence
               directories are supplied, whether the cases were measured on the
               real guest (PASS/FAIL) or remain unmeasured. Evidence is
               validated before it counts (validate_run): the run receipt, its
               guest job steps, the capture file hash/count, the comparison
               report structure and the decoded observation identities must all
               agree with each other and with the current fixtures. Any defect
               marks every case of that run INVALID (never PASS) and the
               coverage document as not valid.

The contract describes the frozen caller interface as read from src/, copy/ and
INSENT/INSRET. It never asserts a condition code and never asserts registers
the linkage macros do not preserve (R15 is the return code; R1 is restored by
INSRET's LM 0,12 and is therefore asserted only as an ABI observation).
"""

import argparse
import json
from pathlib import Path

from cases import all_cases
from compare import SCHEMA as COMPARISON_SCHEMA
from compare import manifest_sha256
from fixtures import CLASSES, field_class, load, logical_io
from layout import (CAPTURE, CAPTURE_LRECL, LIBRARY, MAX_AMOUNT, PROGRAMS, ROUTINES, WORK,
                    WORKLEN, sha256)
from observe import SCHEMA as OBSERVED_SCHEMA

RUN_SCHEMA = "insurance-routines-run-v1"
BACKENDS = ("ifox-iewl", "as370-iewl", "as370-ld370")
REQUIRED_JOBS = {"ifox-iewl": ("allocate", "assemble", "run"),
                 "as370-iewl": ("allocate", "link", "run"),
                 "as370-ld370": ("allocate", "import", "run")}

JAVA = {
    "INSPACK": {"shape": "boolean isValidPacked(byte[7])",
                "inputs": "the seven bytes at R1 (never WORK)",
                "outputs": "R15: 0 valid / 8 invalid; argument bytes unchanged"},
    "INSDATE": {"shape": "Optional<CivilDate> parse(int yyyymmdd)",
                "inputs": "WDATE",
                "outputs": "WVALID (0 valid / 8 invalid), WYEAR, WMD, WORD (days since 19000101); "
                           "R15 is always 0 (SR 15,15 at DTEXIT) and carries no result"},
    "INSRATE": {"shape": "Rate rateFor(int yyyymmdd, int age)",
                "inputs": "TDATE, WAGE",
                "outputs": "WRATE (bps), WFEE (bps, 0 at age >= 10); R15 always 0"},
    "INSVAL": {"shape": "boolean isValidState(State)",
               "inputs": "SREC (128 bytes)",
               "outputs": "R15 0/8 and WVALID 0/8; SREC unchanged. WDATE/WYEAR/WMD/WORD are internal "
                          "INSDATE scratch, not part of the Java result"},
    "INSCALC": {"shape": "Outcome apply(State, Transaction)",
                "inputs": "SREC, TREC",
                "outputs": "OREC (status, age, rate, fee, cash, surrender, death, loan, interest, charge, "
                           "V001) and SREC (new state on OKAY, unchanged otherwise); TREC unchanged; "
                           "R15 always 0, outcome is OSTAT. WBEFORE and the W* scratch are assembler-only"},
}

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
    "CA-D": "D death quote: ODEATH = max(face, cash) - loan; zero amount required; like Q it posts interest and "
            "advances SSEQ/SDATE/SLAST but does not close or settle the policy (no cash/loan/face movement)",
    "CA-CHG": "surrender charge = half_up(cash * fee / 10000); surrender = cash - charge - loan",
    "CA-SURR": "surrender value when loan is present",
    "CA-RAW": "raw byte semantics: F-sign inputs echo as C-sign outputs; negative zero; transaction bytes preserved",
}


def field_json(f) -> dict:
    return {"name": f.name, "offset": f.offset, "width": f.size, "kind": f.kind,
            "group": f.group, "representation": REPRESENTATION[f.kind]}


def entry(name: str, r1: str, reads: list[str], writes: list[str], r15: dict, notes: list[str]) -> dict:
    by_class = {c: [f.name for f in WORK if field_class(name, f.name) == c] for c in CLASSES}
    return {
        "entry_point": name, "kind": "library routine (direct BALR 14,15 call)",
        "linkage": {"entry": "INSENT: STM 14,12,12(13); LR 12,15; own 18F SAVE chained via 4(SAVE)/8(caller)",
                    "return": "INSRET: L 13,4(13); L 14,12(13); LM 0,12,20(13); BR 14",
                    "preserved": "R0-R12 restored, R13 restored, R14 restored (low 24 bits asserted; "
                                 "BALR stores ILC/CC/mask in the high byte and that byte is not asserted)",
                    "return_code": "R15", "condition_code": "undefined at return; never asserted"},
        "r1": r1, "reads": reads, "writes": writes, "r15": r15,
        "logical_io": logical_io(name),
        "java": JAVA[name],
        "byte_classes": by_class,
        "class_meaning": {
            "output": "logical result of the call; Java must reproduce (compared as class 'stable')",
            "input": "read by the routine and left unchanged; preserving it is business-visible "
                     "(compared as class 'stable')",
            "preserved": "unrelated WORK storage the routine never touches; asserted as an assembler "
                         "preservation/ABI property (compared as class 'abi'), NOT a Java requirement",
            "scratch": "written by the assembler implementation; asserted against the model so the "
                       "guest evidence is complete, NOT a Java requirement",
            "unasserted": "arithmetic scratch whose content is implementation-defined; recorded, not compared"},
        "storage_outside_work": "none permitted; guards around WORK, the argument and the save area are asserted",
        "notes": notes,
    }


def contract() -> dict:
    return {
        "schema": "insurance-routines-contract-v2",
        "worklen": WORKLEN,
        "java_authority": ("Java acceptance uses the guest observations (evidence/*/observed.json: input, "
                           "business.outputs, business.r15) and this contract. fixtures/<v>/expected.json is "
                           "the independent expectation the guest was compared against, not runtime truth; "
                           "where comparison.json passed the two agree byte for byte."),
        "capture_layout": {k: {"offset": o, "width": w} for k, (o, w) in CAPTURE.items()},
        "work_fields": [field_json(f) for f in WORK],
        "callable": [
            entry("INSPACK", "R1 -> seven packed bytes (not WORK)", ["7 bytes at R1"], [],
                  {"0": "valid packed decimal", "8": "invalid digit or sign nibble"},
                  ["pure predicate: WORK untouched, argument bytes untouched"]),
            entry("INSDATE", "R1 -> WORK", ["WDATE"], ["WVALID", "WYEAR", "WMD", "WORD"],
                  {"0": "always 0 (DTEXIT executes SR 15,15 before INSRET); validity is WVALID "
                        "(0 valid / 8 invalid), not R15"},
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


def _read_json(folder: Path, name: str, problems: list[str]):
    path = folder / name
    if not path.is_file():
        problems.append(f"{name}: missing")
        return None
    try:
        return json.loads(path.read_text())
    except ValueError as error:
        problems.append(f"{name}: not JSON ({error})")
        return None


def validate_run(folder: Path, manifest: dict, expected: list[dict]) -> tuple[dict, list[str]]:
    """Cross-check one evidence directory. Returns (summary, problems); any problem = INVALID."""
    problems: list[str] = []
    run = _read_json(folder, "run.json", problems)
    report = _read_json(folder, "comparison.json", problems)
    observed = _read_json(folder, "observed.json", problems)
    # Full run directories carry their own capture; the compact committed bundle keeps one
    # capture.bin beside the run folders (RECEIPTS.json proves all paths produced the same bytes).
    capture = folder / "capture.bin"
    if not capture.is_file():
        capture = folder.parent / "capture.bin"
    if not capture.is_file():
        problems.append("capture.bin: missing")
    summary: dict = {"folder": str(folder), "backend": None, "prefix": None, "jobs": {}}
    if problems:
        return summary, problems
    n = len(expected)
    identity = [[e["id"], e["routine"]] for e in expected]
    ids = {e["id"] for e in expected}
    current = manifest_sha256(manifest)
    raw = capture.read_bytes()

    def need(condition: bool, message: str) -> None:
        if not condition:
            problems.append(message)

    # run.json: the receipt written at run time (never regenerated).
    need(isinstance(run, dict) and run.get("schema") == RUN_SCHEMA, "run.json: schema")
    if not isinstance(run, dict):
        return summary, problems
    backend = run.get("backend")
    summary.update(backend=backend, prefix=run.get("prefix"),
                   fixture_manifest_at_run=run.get("fixture_manifest_sha256"))
    need(backend in BACKENDS, f"run.json: unknown backend {backend!r}")
    need(run.get("fixture_version") == manifest["version"], "run.json: fixture_version")
    need(run.get("cases_sha256") == manifest["cases_sha256"],
         "run.json: guest consumed different cases.bin than the current fixtures")
    jobs = run.get("jobs")
    need(isinstance(jobs, dict), "run.json: jobs")
    if isinstance(jobs, dict) and backend in REQUIRED_JOBS:
        for name in REQUIRED_JOBS[backend]:
            job = jobs.get(name)
            need(isinstance(job, dict), f"run.json: job {name} missing")
            if not isinstance(job, dict):
                continue
            steps = job.get("steps")
            need(isinstance(steps, list) and steps and all(
                isinstance(s, list) and len(s) == 2 and s[1] == "0000" for s in steps),
                f"run.json: job {name} steps not all RC 0000: {steps}")
            need(job.get("passed") is True and job.get("purged") is True and job.get("errors") == [],
                 f"run.json: job {name} did not pass cleanly")
            need(isinstance(job.get("job_id"), str) and job["job_id"].startswith("JOB"),
                 f"run.json: job {name} has no JES job id")
            summary["jobs"][name] = {"job_id": job.get("job_id"), "steps": steps}
    need(run.get("comparison_passed") is True, "run.json: comparison did not pass at run time")
    need(run.get("findings") == 0, "run.json: findings at run time")
    need(run.get("capture_records") == n, f'run.json: capture_records {run.get("capture_records")} != {n}')
    need(run.get("cases_passed") == n, "run.json: cases_passed != case count")
    # capture.bin: the raw guest output; must be exactly what the receipt hashed.
    need(sha256(raw) == run.get("capture_sha256"), "capture.bin: hash differs from run.json receipt")
    need(len(raw) == n * CAPTURE_LRECL, f"capture.bin: {len(raw)} bytes != {n} x {CAPTURE_LRECL}")
    # comparison.json: may be re-derived later, but only from this capture with the current fixtures.
    need(isinstance(report, dict) and report.get("schema") == COMPARISON_SCHEMA, "comparison.json: schema")
    if isinstance(report, dict):
        need(report.get("passed") is True, "comparison.json: not passed")
        need(report.get("findings") == [], "comparison.json: findings present")
        need(report.get("capture_sha256") == sha256(raw), "comparison.json: derived from a different capture")
        need(report.get("fixture_manifest_sha256") == current,
             "comparison.json: derived against a different fixture manifest; re-run compare.py")
        need(report.get("expected_records") == n and report.get("observed_records") == n,
             "comparison.json: record counts")
        need(report.get("observed_identity") == identity, "comparison.json: observed identity/order")
        passed = report.get("cases_passed")
        need(isinstance(passed, list) and len(passed) == n and set(passed) == ids,
             "comparison.json: cases_passed is not exactly the fixture case set")
        need(report.get("failed_cases") == [], "comparison.json: failed_cases")
        by_class = report.get("findings_by_class")
        need(isinstance(by_class, dict) and all(v == 0 for v in by_class.values()),
             "comparison.json: findings_by_class")
        summary["comparison_harness_identity"] = report.get("harness_identity")
    # observed.json: decoded facts; identities must be the fixture set in order.
    need(isinstance(observed, dict) and observed.get("schema") == OBSERVED_SCHEMA, "observed.json: schema")
    if isinstance(observed, dict):
        need(observed.get("valid") is True and observed.get("problems") == [], "observed.json: invalid bundle")
        need(observed.get("capture_sha256") == sha256(raw), "observed.json: derived from a different capture")
        need(observed.get("fixture_manifest_sha256") == current,
             "observed.json: derived against a different fixture manifest; re-run observe.py")
        need(observed.get("records") == n, "observed.json: record count")
        obs = observed.get("observations")
        need(isinstance(obs, list) and [[o.get("id"), o.get("routine")] for o in obs] == identity
             and [o.get("ordinal") for o in obs] == list(range(1, n + 1)),
             "observed.json: observation identity/order")
    summary["fixture_manifest_matches_run"] = run.get("fixture_manifest_sha256") == current
    return summary, problems


def coverage(observed: list[Path], version: str = "v1") -> dict:
    manifest, expected, _ = load(version)
    measured: dict[str, dict] = {}
    runs: dict[str, dict] = {}
    for folder in observed:
        summary, problems = validate_run(folder, manifest, expected)
        label = folder.name
        runs[label] = {**summary, "valid": not problems, "problems": problems}
        backend = summary.get("backend") or label
        state = "PASS" if not problems else "INVALID"
        for exp in expected:
            measured.setdefault(exp["id"], {})[backend] = state
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
        rule["status"] = ("unmeasured" if not states else "FAIL" if states - {"PASS"} else "PASS")
    return {"schema": "insurance-routines-coverage-v2", "rules": rules,
            "valid": all(r["valid"] for r in runs.values()),
            "runs": runs,
            "fixture_manifest_sha256": manifest_sha256(manifest),
            "evidence_policy": ("a case is PASS only when its run receipt, guest job steps, capture hash/count, "
                                "comparison report and decoded observations all validate; any defect marks "
                                "every case of that run INVALID and the rule FAIL"),
            "rules_without_cases": missing,
            "unmeasured_paths": [
                "INSBAT/INSSMOK internal control flow beyond RC, POLOUT and RESOUT bytes (job-level only)",
                "INSCALC behaviour for TDATE below 19000101 with a valid state (unreachable: INSVAL requires SDATE >= 19000101 and ORDR precedes rating)",
                "OPEN failure RC16 in INSBAT (requires a DD-level fault the harness does not inject)",
            ],
            "observed_runs": [p.name for p in observed],
            "cases_total": sum(len(r["cases"]) for r in rules.values()),
            "cases_measured": sum(1 for r in rules.values() for c in r["cases"]
                                  if isinstance(c["measured"], dict)
                                  and set(c["measured"].values()) == {"PASS"})}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=ROUTINES / "contract")
    parser.add_argument("--observed", type=Path, nargs="*", default=[])
    parser.add_argument("--version", default="v1")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    doc = contract()
    (args.out / "contract.json").write_text(json.dumps(doc, indent=1, sort_keys=True) + "\n")
    cov = coverage(args.observed, args.version)
    (args.out / "coverage.json").write_text(json.dumps(cov, indent=1, sort_keys=True) + "\n")
    for name, run in cov["runs"].items():
        for problem in run["problems"]:
            print(f"INVALID {name}: {problem}")
    print("contract", sha256((args.out / "contract.json").read_bytes())[:16],
          "coverage", cov["cases_measured"], "/", cov["cases_total"], "cases measured;",
          sum(1 for r in cov["rules"].values() if r["status"] == "PASS"), "rules PASS,",
          sum(1 for r in cov["rules"].values() if r["status"] == "FAIL"), "FAIL,",
          sum(1 for r in cov["rules"].values() if r["status"] == "unmeasured"), "unmeasured;",
          "rules without cases:", cov["rules_without_cases"],
          "; evidence", "valid" if cov["valid"] else "INVALID")
    raise SystemExit(0 if cov["valid"] else 1)


if __name__ == "__main__":
    main()
