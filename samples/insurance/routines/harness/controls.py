"""Negative controls for the comparator, the fixture loader and the coverage validator.

    python3 controls.py --report ../evidence/controls.json [--evidence ../evidence]

comparator  a synthetic capture is built from the expectations (it is NOT guest
            output), then deliberately corrupted one aspect at a time; every
            corruption must be reported by compare.py under the expected class.
fixtures    a copy of fixtures/<version> (and, for the input-drift control, of
            samples/insurance) is mutated; fixtures.load() must refuse it.
coverage    a copy of one evidence directory is mutated (receipt, job steps,
            capture, comparison report, decoded observations); contract.coverage()
            must mark the run INVALID and no rule may be PASS.

These prove the harness, not the guest: a synthetic record or a mutated copy is
never evidence of routine behaviour.
"""

import argparse
import json
import shutil
import tempfile
from pathlib import Path

from compare import compare
from contract import coverage, validate_run
from fixtures import load
from layout import CAPTURE, CAPTURE_LRECL, GUARD, INSURANCE, ROUTINES, SENTINEL, binary

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
    ca, pk, dt, vl, rt = (next(e["ordinal"] for e in expected if e["id"] == cid) - 1
                          for cid in ("CA001", "PK008", "DT001", "VL001", "RT002"))
    cases = []

    def mutate(name: str, kind: str, index: int, field: str, delta: int, value: int) -> None:
        raw = bytearray(clean)
        offset = index * n + CAPTURE[field][0] + delta
        if raw[offset] == value:
            raise ValueError(f"control {name!r} would not change byte {offset}")
        raw[offset] = value
        cases.append((name, kind, bytes(raw)))

    mutate("known output: OSTAT last byte of CA001", "stable", ca, "work_after", 184 + 3, 0xC3)
    mutate("known output: OINT low digit of CA001", "stable", ca, "work_after", 230 + 6, 0x2C)
    mutate("known output: transaction byte of CA001", "stable", ca, "work_after", 128, 0xF9)
    mutate("known output: R15 of INSPACK reject PK008", "stable", pk, "regs_after", 63, 0x00)
    mutate("scratch: WAGE of CA001", "scratch", ca, "work_after", 296 + 3, 0x07)
    mutate("preserved: unrelated SREC byte after INSDATE DT001", "abi", dt, "work_after", 5, 0xFF)
    mutate("preserved: unrelated TREC byte after INSVAL VL001", "abi", vl, "work_after", 130, 0xFF)
    mutate("preserved: unrelated OREC byte after INSRATE RT002", "abi", rt, "work_after", 170, 0xFF)
    mutate("known output: WORD of INSDATE DT001", "stable", dt, "work_after", 276 + 3, 0x01)
    mutate("known output: WRATE of INSRATE RT002", "stable", rt, "work_after", 304 + 2, 0x0C)
    mutate("known output: SREC input byte after INSVAL VL001", "stable", vl, "work_after", 0, 0xC1)
    mutate("known output: TREC input byte after INSCALC CA001", "stable", ca, "work_after", 130, 0xFF)
    mutate("known output: R15 of INSVAL VL001", "stable", vl, "regs_after", 63, 0x08)
    mutate("abi: R15 of INSDATE DT001 (always 0)", "abi", dt, "regs_after", 63, 0x08)
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


def _edit_json(path: Path, change) -> None:
    data = json.loads(path.read_text())
    change(data)
    path.write_text(json.dumps(data, indent=1, sort_keys=True) + "\n")


def fixture_controls(version: str) -> list[dict]:
    """Mutate a copy of the fixture bundle; fixtures.load() must raise for each mutation."""
    source = ROUTINES / "fixtures" / version

    def swap_order(m: dict) -> None:
        m["identity_order"][0], m["identity_order"][1] = m["identity_order"][1], m["identity_order"][0]

    def swap_classes(m: dict) -> None:
        table = m["byte_classes"]["INSDATE"]
        table[280:284] = ["preserved"] * 4  # WVALID is an INSDATE output

    def drop_input(m: dict) -> None:
        m["inputs"].pop("src/INSCALC.asm")

    def change_input_hash(m: dict) -> None:
        m["inputs"]["copy/INSWORK" if "copy/INSWORK" in m["inputs"] else sorted(m["inputs"])[0]] = "0" * 64

    def extra_input(m: dict) -> None:
        m["inputs"]["src/EXTRA.asm"] = "0" * 64

    def rewrite_expected(root: Path) -> None:
        exp = json.loads((root / "expected.json").read_text())
        exp[0]["r15"] = 4
        (root / "expected.json").write_text(json.dumps(exp, indent=1, sort_keys=True) + "\n")

    def rewrite_expected_order(root: Path) -> None:
        exp = json.loads((root / "expected.json").read_text())
        exp[0], exp[1] = exp[1], exp[0]
        (root / "expected.json").write_text(json.dumps(exp, indent=1, sort_keys=True) + "\n")

    def flip_case_byte(root: Path) -> None:
        raw = bytearray((root / "cases.bin").read_bytes())
        raw[100] ^= 0xFF
        (root / "cases.bin").write_bytes(bytes(raw))

    def truncate_cases(root: Path) -> None:
        raw = (root / "cases.bin").read_bytes()
        (root / "cases.bin").write_bytes(raw[:-512])

    manifest_mutations = [
        ("manifest schema changed", lambda m: m.update(schema="insurance-routines-v0")),
        ("manifest version changed", lambda m: m.update(version="v9")),
        ("manifest case_count changed", lambda m: m.update(case_count=m["case_count"] + 1)),
        ("manifest cases_per_routine changed", lambda m: m["cases_per_routine"].update(INSPACK=1)),
        ("manifest identity_order swapped", swap_order),
        ("manifest identity id changed", lambda m: m["identity_order"][0].__setitem__(0, "ZZ999")),
        ("manifest byte class changed (INSDATE WVALID -> preserved)", swap_classes),
        ("manifest logical_io changed", lambda m: m["logical_io"]["INSRATE"]["outputs"].append("WAGE")),
        ("manifest worklen changed", lambda m: m.update(worklen=m["worklen"] + 8)),
        ("manifest capture_lrecl changed", lambda m: m.update(capture_lrecl=1024)),
        ("manifest declared input hash changed", change_input_hash),
        ("manifest declared input removed", drop_input),
        ("manifest undeclared input added", extra_input),
        ("manifest cases_sha256 changed", lambda m: m.update(cases_sha256="0" * 64)),
        ("manifest expected_sha256 changed", lambda m: m.update(expected_sha256="0" * 64)),
        ("manifest missing key inputs", lambda m: m.pop("inputs")),
        ("manifest key wrong type", lambda m: m.update(identity_order="CA001")),
    ]
    file_mutations = [
        ("expected.json content changed (hash must catch it)", rewrite_expected),
        ("expected.json reordered", rewrite_expected_order),
        ("cases.bin byte flipped", flip_case_byte),
        ("cases.bin truncated by one record", truncate_cases),
        ("manifest.json missing", lambda root: (root / "manifest.json").unlink()),
        ("manifest.json not JSON", lambda root: (root / "manifest.json").write_text("{")),
    ]
    results = []
    with tempfile.TemporaryDirectory() as tmp:
        for name, change in manifest_mutations + file_mutations:
            root = Path(tmp) / "fx"
            shutil.rmtree(root, ignore_errors=True)
            shutil.copytree(source, root)
            if (name, change) in manifest_mutations:
                _edit_json(root / "manifest.json", change)
            else:
                change(root)
            results.append(_expect_load_failure("fixtures: " + name, version, root, INSURANCE))
        # Real input drift: a frozen source file changes under an otherwise intact manifest.
        tree = Path(tmp) / "insurance"
        shutil.copytree(INSURANCE, tree, ignore=shutil.ignore_patterns("evidence", "build", "__pycache__"))
        with (tree / "src" / "INSDATE.asm").open("a") as f:
            f.write("* drift\n")
        results.append(_expect_load_failure("fixtures: frozen src/INSDATE.asm modified on disk", version,
                                            tree / "routines" / "fixtures" / version, tree))
        (tree / "copy" / "INSNEW").write_text("new copybook\n")
        results.append(_expect_load_failure("fixtures: undeclared copy/ member added on disk", version,
                                            tree / "routines" / "fixtures" / version, tree))
    return results


def _expect_load_failure(name: str, version: str, root: Path, insurance: Path) -> dict:
    try:
        load(version, root, insurance)
    except (ValueError, KeyError, TypeError) as error:
        return {"control": name, "expected_class": "fixtures", "ok": True, "error": str(error)[:160]}
    return {"control": name, "expected_class": "fixtures", "ok": False, "error": "load() accepted it"}


def coverage_controls(version: str, evidence: Path) -> list[dict]:
    """Mutate a copy of one evidence run; coverage must go INVALID with no PASS rule."""
    manifest, expected, _ = load(version)
    source = next((evidence / n for n in ("as370-iewl", "ifox-iewl", "as370-ld370")
                   if (evidence / n / "run.json").exists()), None)
    if source is None:
        return [{"control": "coverage: no evidence directory available", "expected_class": "coverage",
                 "ok": False, "error": f"nothing under {evidence}"}]
    capture_src = source / "capture.bin" if (source / "capture.bin").exists() else evidence / "capture.bin"
    n = len(expected)

    def run_edit(change):
        return lambda root: _edit_json(root / "run.json", change)

    def report_edit(change):
        return lambda root: _edit_json(root / "comparison.json", change)

    def observed_edit(change):
        return lambda root: _edit_json(root / "observed.json", change)

    def step_rc(r: dict) -> None:
        r["jobs"]["run"]["steps"][0][1] = "0008"

    def swap_obs(o: dict) -> None:
        o["observations"][0], o["observations"][1] = o["observations"][1], o["observations"][0]

    def global_finding(r: dict) -> None:
        r["findings"].append({"class": "structure", "routine": "*", "case": "*", "field": "capture length",
                              "expected": "multiple of 1536", "observed": "100", "offset": None,
                              "expected_raw": None, "observed_raw": None})

    def capture_flip(root: Path) -> None:
        raw = bytearray((root / "capture.bin").read_bytes())
        raw[5000] ^= 0xFF
        (root / "capture.bin").write_bytes(bytes(raw))

    def capture_extra(root: Path) -> None:
        raw = (root / "capture.bin").read_bytes()
        (root / "capture.bin").write_bytes(raw + raw[:CAPTURE_LRECL])

    mutations = [
        ("run.json missing", lambda root: (root / "run.json").unlink()),
        ("run.json schema changed", run_edit(lambda r: r.update(schema="other"))),
        ("run.json unknown backend", run_edit(lambda r: r.update(backend="host-only"))),
        ("run.json guest step RC 0008", run_edit(step_rc)),
        ("run.json run job missing", run_edit(lambda r: r["jobs"].pop("run"))),
        ("run.json run job not passed", run_edit(lambda r: r["jobs"]["run"].update(passed=False))),
        ("run.json run job has JES errors", run_edit(lambda r: r["jobs"]["run"].update(errors=["IEF450I"]))),
        ("run.json comparison_passed false", run_edit(lambda r: r.update(comparison_passed=False))),
        ("run.json findings nonzero", run_edit(lambda r: r.update(findings=1))),
        ("run.json capture_records short", run_edit(lambda r: r.update(capture_records=n - 1))),
        ("run.json capture_sha256 differs from capture.bin", run_edit(lambda r: r.update(capture_sha256="0" * 64))),
        ("run.json cases_sha256 differs from fixtures", run_edit(lambda r: r.update(cases_sha256="0" * 64))),
        ("run.json fixture_version differs", run_edit(lambda r: r.update(fixture_version="v0"))),
        ("capture.bin byte flipped (hash mismatch)", capture_flip),
        ("capture.bin extra record appended", capture_extra),
        ("capture.bin missing", lambda root: (root / "capture.bin").unlink()),
        ("comparison.json missing", lambda root: (root / "comparison.json").unlink()),
        ("comparison.json passed false with cases_passed intact", report_edit(lambda r: r.update(passed=False))),
        ("comparison.json global '*' finding with cases_passed intact", report_edit(global_finding)),
        ("comparison.json observed_records short", report_edit(lambda r: r.update(observed_records=n - 1))),
        ("comparison.json cases_passed missing one", report_edit(lambda r: r["cases_passed"].pop())),
        ("comparison.json cases_passed unknown id", report_edit(lambda r: r["cases_passed"].__setitem__(0, "ZZ999"))),
        ("comparison.json identity reordered", report_edit(lambda r: r["observed_identity"].reverse())),
        ("comparison.json capture hash differs", report_edit(lambda r: r.update(capture_sha256="0" * 64))),
        ("comparison.json derived from other manifest", report_edit(lambda r: r.update(fixture_manifest_sha256="0" * 64))),
        ("comparison.json schema v1 (old comparator)", report_edit(lambda r: r.update(schema="insurance-routines-comparison-v1"))),
        ("observed.json missing", lambda root: (root / "observed.json").unlink()),
        ("observed.json invalid flag", observed_edit(lambda o: o.update(valid=False))),
        ("observed.json problems recorded", observed_edit(lambda o: o.update(problems=["partial trailing record"]))),
        ("observed.json observation dropped", observed_edit(lambda o: o["observations"].pop())),
        ("observed.json observation duplicated", observed_edit(lambda o: o["observations"].append(o["observations"][0]))),
        ("observed.json observations reordered", observed_edit(swap_obs)),
        ("observed.json unknown id", observed_edit(lambda o: o["observations"][0].update(id="ZZ999"))),
        ("observed.json routine renamed", observed_edit(lambda o: o["observations"][0].update(routine="INSCALC"))),
        ("observed.json capture hash differs", observed_edit(lambda o: o.update(capture_sha256="0" * 64))),
        ("observed.json not JSON", lambda root: (root / "observed.json").write_text("[")),
    ]
    results = []
    with tempfile.TemporaryDirectory() as tmp:
        clean = Path(tmp) / "clean"
        shutil.copytree(source, clean, ignore=shutil.ignore_patterns("*.aws", "*.orig.json"))
        shutil.copy2(capture_src, clean / "capture.bin")
        _, problems = validate_run(clean, manifest, expected)
        results.append({"control": "coverage: untouched evidence copy validates (self-test)",
                        "expected_class": "coverage", "ok": not problems, "error": "; ".join(problems)[:200]})
        for name, change in mutations:
            root = Path(tmp) / "mut"
            shutil.rmtree(root, ignore_errors=True)
            shutil.copytree(clean, root)
            change(root)
            _, problems = validate_run(root, manifest, expected)
            cov = coverage([root], version)
            statuses = {r["status"] for r in cov["rules"].values()}
            ok = bool(problems) and not cov["valid"] and "PASS" not in statuses and cov["cases_measured"] == 0
            results.append({"control": "coverage: " + name, "expected_class": "coverage", "ok": ok,
                            "error": problems[0] if problems else "validate_run found nothing",
                            "rule_statuses": sorted(statuses)})
    return results


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", default="v1")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, default=ROUTINES / "evidence")
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
    results += fixture_controls(args.version)
    results += coverage_controls(args.version, args.evidence)
    report = {"schema": "insurance-routines-controls-v2",
              "note": "synthetic captures and mutated copies on the host; not guest observations",
              "all_ok": all(r["ok"] for r in results), "controls": results,
              "counts": {"comparator": sum(1 for r in results if r["expected_class"] not in ("fixtures", "coverage")),
                         "fixtures": sum(1 for r in results if r["expected_class"] == "fixtures"),
                         "coverage": sum(1 for r in results if r["expected_class"] == "coverage")}}
    args.report.write_text(json.dumps(report, indent=1, sort_keys=True) + "\n")
    for r in results:
        print(f'{"ok  " if r["ok"] else "BAD "} {r["control"]}'
              + (f' -> {r["expected_class"]} x{r["findings"]}' if r.get("findings") is not None else "")
              + (f' [{r["error"][:90]}]' if r.get("error") else ""))
    raise SystemExit(0 if report["all_ok"] else 1)


if __name__ == "__main__":
    main()
