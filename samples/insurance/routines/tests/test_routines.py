"""Host tests for the routine harness scaffolding.

These prove the harness is internally consistent (layouts match the assembled
symbol tables, hand-worked numbers match the model, fixtures are deterministic,
the comparator fails on every deliberate corruption). They never stand in for
guest execution: runtime evidence comes only from run_guest.py / run_jobs.py.
"""

from datetime import date
from fractions import Fraction
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "harness"))

from cases import all_cases  # noqa: E402
from compare import compare, load  # noqa: E402
from contract import RULES, contract, coverage, validate_run  # noqa: E402
from controls import corruptions, coverage_controls, fixture_controls, synthesize  # noqa: E402
from fixtures import (CLASSES, INPUTS, OUTPUTS, R15, SCHEMA, SCRATCH, UNASSERTED,  # noqa: E402
                      case_record, classes, drift, expectations, field_class, render)
from layout import (CAPTURE, CAPTURE_LRECL, CASE, CASE_LRECL, INSURANCE,  # noqa: E402
                    LIBRARY, MAX_AMOUNT, ROUTINES, WORK, WORKLEN, binary, packed, sha256,
                    symbols, text)
from model import Work, expect, half_up, inscalc  # noqa: E402
from observe import decode  # noqa: E402

FIXTURES = ROUTINES / "fixtures" / "v1"
EVIDENCE = ROUTINES / "evidence"
INSCALC_SYM = INSURANCE / "build" / "INSCALC.sym"
INSDRV_SYM = ROUTINES / "build" / "INSDRV.sym"


class LayoutAgreesWithAssembledSymbols(unittest.TestCase):
    @unittest.skipUnless(INSCALC_SYM.exists(), "make -C samples/insurance core first")
    def test_work_fields_match_inscalc_dsect(self) -> None:
        table = symbols(INSCALC_SYM)
        for f in WORK:
            if f.name == "WPAD":  # unnamed doubleword alignment gap (DS 0D) in INSWORK
                self.assertEqual((table["WFEE"][0] + 3, table["WNUM"][0]), (f.offset, f.end))
                continue
            self.assertEqual(table[f.name][:2], (f.offset, f.size), f.name)
        self.assertEqual(table["WORKLEN"][0], WORKLEN)
        self.assertEqual([f.offset for f in WORK], [0] + [f.end for f in WORK[:-1]])

    @unittest.skipUnless(INSDRV_SYM.exists(), "assemble routines/driver/INSDRV.asm first")
    def test_capture_layout_matches_driver(self) -> None:
        table = symbols(INSDRV_SYM)
        self.assertEqual(table["CAPREC"][1], CAPTURE_LRECL)
        self.assertEqual(table["CASEREC"][1], CASE_LRECL)
        base = table["CAPREC"][0]
        names = {"id": "CAPID", "routine": "CAPRTN", "ordinal": "CAPORD", "flags": "CAPFLG",
                 "regs_before": "CAPRB", "regs_after": "CAPRA", "caller_save": "CAPSA",
                 "callee_save": "CAPCSA", "guard_work_low": "CAPGWL", "guard_work_high": "CAPGWH",
                 "arg_after": "CAPARG", "addr_work": "CAPAWK", "addr_return": "CAPARET",
                 "work_before": "CAPWB", "work_after": "CAPWA"}
        for key, symbol in names.items():
            self.assertEqual(table[symbol][0] - base, CAPTURE[key][0], key)
        cbase = table["CASEREC"][0]
        for key, symbol in {"id": "CSID", "routine": "CSRTN", "arg": "CSARG", "work": "CSWORK"}.items():
            self.assertEqual(table[symbol][0] - cbase, CASE[key][0], key)
        self.assertLessEqual(CASE["work"][0] + WORKLEN, CASE_LRECL)

    def test_byte_classes_cover_work_exactly(self) -> None:
        names = {f.name for f in WORK}
        for routine in LIBRARY:
            table = classes(routine)
            self.assertEqual(len(table), WORKLEN)
            self.assertTrue(set(table) <= set(CLASSES))
            for name in SCRATCH.get(routine, ()) + UNASSERTED.get(routine, ()) + INPUTS[routine] + OUTPUTS[routine]:
                self.assertIn(name, names)

    def test_logical_outputs_per_routine(self) -> None:
        # Direct outputs Java must reproduce, separated from unrelated WORK preservation.
        self.assertEqual(OUTPUTS["INSPACK"], ())
        self.assertEqual(R15["INSPACK"], "result")
        self.assertEqual(OUTPUTS["INSDATE"], ("WVALID", "WYEAR", "WMD", "WORD"))
        self.assertEqual(R15["INSDATE"], "fixed")  # SR 15,15 at DTEXIT
        self.assertEqual(OUTPUTS["INSRATE"], ("WRATE", "WFEE"))
        self.assertEqual(OUTPUTS["INSVAL"], ("WVALID",))
        self.assertEqual(R15["INSVAL"], "result")
        for name in ("OSTAT", "OINT", "ODEATH", "SCASH", "SSEQ", "SLAST"):
            self.assertEqual(field_class("INSCALC", name), "output", name)
        for name in ("TOP", "TAMT", "TDATE"):
            self.assertEqual(field_class("INSCALC", name), "input", name)
        for name in ("WBEFORE", "WDAYS", "WRATE", "WDATE"):
            self.assertEqual(field_class("INSCALC", name), "scratch", name)
        self.assertEqual(field_class("INSCALC", "WNUM"), "unasserted")
        for name in ("WDATE", "WYEAR", "WMD", "WORD"):
            self.assertEqual(field_class("INSVAL", name), "scratch", name)
        self.assertEqual(field_class("INSDATE", "SCASH"), "preserved")
        self.assertEqual(field_class("INSRATE", "OSTAT"), "preserved")
        self.assertEqual(field_class("INSPACK", "WVALID"), "preserved")


class HandWorkedNumbers(unittest.TestCase):
    def test_half_up_tie(self) -> None:
        self.assertEqual(half_up(5, 10), 1)
        self.assertEqual(half_up(4, 10), 0)
        self.assertEqual(half_up(15, 10), 2)

    def test_quotient_overflow_hand_calculation(self) -> None:
        # SCASH=MAXAMT, 19000101 -> 20250101 at 325 bps (period-end rate).
        days = (date(2025, 1, 1) - date(1900, 1, 1)).days
        self.assertEqual(days, 45656)
        interest = Fraction(MAX_AMOUNT * 325 * days, 3_650_000)
        self.assertEqual(half_up(MAX_AMOUNT * 325 * days, 3_650_000), 406_526_027_393)
        self.assertGreater(interest, MAX_AMOUNT)

    def test_case_hand_expectations_match_chained_model(self) -> None:
        # expected.json chains the model over physical order (keep-flag cases reuse WORK),
        # so hand-worked literals are checked against that chain, not an isolated call.
        expected = expectations(all_cases())
        hand_count = 0
        for exp in expected:
            for name, want in exp["hand_worked"].items():
                hand_count += 1
                if name == "R15":
                    self.assertEqual(exp["r15"], want, exp["id"])
                elif isinstance(want, str):
                    self.assertEqual(exp["decoded_after"][name], repr(want), f'{exp["id"]}.{name}')
                else:
                    self.assertEqual(int(exp["decoded_after"][name]), want, f'{exp["id"]}.{name}')
        self.assertGreater(hand_count, 100)

    def test_isolated_model_call_matches_expect(self) -> None:
        case = next(c for c in all_cases() if c.id == "CA069")
        outcome = expect(case.routine, case.work, case.arg)
        self.assertEqual(Work(outcome.work).get("OSTAT"), text("OVER"))

    def test_death_quote_does_not_close(self) -> None:
        work = Work(bytes(WORKLEN))
        values = {"SID": text("00000001"), "TID": text("00000001"),
                  "SISSUE": binary(20240101), "SDATE": binary(20240101),
                  "SFACE": packed(1_000_000), "SCASH": packed(100_000), "SLOAN": packed(0),
                  "TSEQ": binary(1), "TDATE": binary(20240101),
                  "TOP": text("D"), "TAMT": packed(0)}
        for name, raw in values.items():
            work.set(name, raw)
        out = Work(inscalc(work, b"").work)
        self.assertEqual(out.get("OSTAT"), text("OKAY"))
        self.assertEqual(out.num("ODEATH"), 1_000_000)
        self.assertEqual(out.num("SCASH"), 100_000)
        self.assertEqual(out.int("SSEQ"), 1)


class FixturesAreVersionedAndDeterministic(unittest.TestCase):
    def test_manifest_matches_regenerated_fixtures(self) -> None:
        manifest = json.loads((FIXTURES / "manifest.json").read_text())
        cases = all_cases()
        cases_bin = b"".join(case_record(c, i) for i, c in enumerate(cases, 1))
        self.assertEqual(manifest["schema"], SCHEMA)
        self.assertEqual(manifest["case_count"], len(cases))
        self.assertEqual(len(cases_bin), manifest["case_count"] * CASE_LRECL)
        self.assertEqual((FIXTURES / "cases.bin").read_bytes(), cases_bin)
        self.assertEqual(json.loads((FIXTURES / "expected.json").read_text()), expectations(cases))
        self.assertEqual(manifest["identity_order"], [[c.id, c.routine] for c in cases])
        for name in ("src/INSCALC.asm", "copy/INSWORK.copy", "routines/driver/INSDRV.asm",
                     "routines/harness/model.py", "routines/harness/fixtures.py",
                     "golden/v1/rates.json"):
            self.assertIn(name, manifest["inputs"])
        self.assertEqual(drift("v1"), [])

    def test_render_matches_tracked_files_without_writing(self) -> None:
        before = {p.name: p.read_bytes() for p in FIXTURES.iterdir()}
        mtimes = {p.name: p.stat().st_mtime_ns for p in FIXTURES.iterdir()}
        rendered = render("v1")
        self.assertEqual(set(rendered), set(before))
        self.assertEqual(rendered, before)
        self.assertEqual(mtimes, {p.name: p.stat().st_mtime_ns for p in FIXTURES.iterdir()})

    def test_load_verifies_every_declared_input_hash(self) -> None:
        manifest, expected, raw = load("v1")
        self.assertEqual(len(expected), manifest["case_count"])
        self.assertEqual(sha256(raw), manifest["cases_sha256"])
        for rel, digest in manifest["inputs"].items():
            self.assertEqual(sha256((INSURANCE / rel).read_bytes()), digest, rel)
        self.assertGreaterEqual(len(manifest["inputs"]), 7 + 3 + 1 + 4 + 1)

    def test_load_rejects_every_fixture_mutation(self) -> None:
        results = fixture_controls("v1")
        self.assertGreaterEqual(len(results), 25)
        for r in results:
            self.assertTrue(r["ok"], f'{r["control"]}: {r["error"]}')
        names = " ".join(r["control"] for r in results)
        for word in ("schema", "case_count", "identity_order", "byte class", "input hash",
                     "input removed", "undeclared input", "src/INSDATE.asm modified"):
            self.assertIn(word, names)

    def test_check_never_writes_when_fixtures_drift(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "v1"
            shutil.copytree(FIXTURES, root)
            (root / "manifest.json").write_text("{}")
            snapshot = {p.name: p.read_bytes() for p in root.iterdir()}
            self.assertEqual(drift("v1", root), ["manifest.json"])
            self.assertEqual(snapshot, {p.name: p.read_bytes() for p in root.iterdir()})
            with self.assertRaises(ValueError):
                load("v1", root)
            self.assertEqual(snapshot, {p.name: p.read_bytes() for p in root.iterdir()})

    def test_case_ids_unique_and_every_rule_described(self) -> None:
        ids = [c.id for c in all_cases()]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(set(c.rule for c in all_cases()), set(RULES))

    def test_expectations_never_come_from_the_guest(self) -> None:
        manifest = json.loads((FIXTURES / "manifest.json").read_text())
        provenance = json.dumps(manifest["expectation_provenance"]).lower()
        self.assertIn("independent of assembler", provenance)
        self.assertNotIn("capture", provenance)


class ComparatorControls(unittest.TestCase):
    def test_clean_synthetic_capture_passes(self) -> None:
        manifest, expected, _ = load("v1")
        self.assertTrue(compare(synthesize(expected), manifest, expected)["passed"])

    def test_every_corruption_is_detected_in_its_class(self) -> None:
        manifest, expected, _ = load("v1")
        clean = synthesize(expected)
        seen = set()
        for name, kind, raw in corruptions(clean, expected):
            outcome = compare(raw, manifest, expected)
            self.assertFalse(outcome["passed"], name)
            self.assertTrue(any(f["class"] == kind for f in outcome["findings"]), name)
            seen.add(kind)
        self.assertEqual(seen, {"structure", "harness", "abi", "stable", "scratch"})

    def test_finding_class_follows_field_class(self) -> None:
        manifest, expected, _ = load("v1")
        clean = synthesize(expected)
        by_name = {(name, kind) for name, kind, _ in corruptions(clean, expected)}
        self.assertIn(("preserved: unrelated SREC byte after INSDATE DT001", "abi"), by_name)
        self.assertIn(("known output: WORD of INSDATE DT001", "stable"), by_name)
        self.assertIn(("abi: R15 of INSDATE DT001 (always 0)", "abi"), by_name)
        self.assertIn(("known output: R15 of INSVAL VL001", "stable"), by_name)

    def test_all_mismatches_preserved(self) -> None:
        manifest, expected, _ = load("v1")
        clean = bytearray(synthesize(expected))
        for i in range(3):
            off = i * CAPTURE_LRECL + CAPTURE["work_after"][0]
            clean[off] ^= 0xFF
        outcome = compare(bytes(clean), manifest, expected)
        self.assertGreaterEqual(len({f["case"] for f in outcome["findings"]}), 3)
        for f in outcome["findings"]:
            for key in ("routine", "case", "field", "expected", "observed", "expected_raw", "observed_raw", "offset"):
                self.assertIn(key, f)


class ContractDocument(unittest.TestCase):
    def test_contract_lists_five_callables_and_two_programs(self) -> None:
        doc = contract()
        self.assertEqual([e["entry_point"] for e in doc["callable"]], list(LIBRARY))
        self.assertEqual([p["entry_point"] for p in doc["programs"]], ["INSBAT", "INSSMOK"])
        for e in doc["callable"]:
            self.assertEqual(e["linkage"]["condition_code"], "undefined at return; never asserted")
            self.assertEqual(sum(len(e["byte_classes"][c]) for c in CLASSES), len(WORK))
            self.assertIn("java", e)
        insdate = next(e for e in doc["callable"] if e["entry_point"] == "INSDATE")
        self.assertIn("R15 is always 0", insdate["java"]["outputs"])
        self.assertEqual(insdate["logical_io"]["outputs"], ["WVALID", "WYEAR", "WMD", "WORD"])
        self.assertIn("expected.json is the independent", doc["java_authority"])
        self.assertIn("does not close", RULES["CA-D"])
        self.assertIn("advances SSEQ/SDATE/SLAST", RULES["CA-D"])

    def test_coverage_without_observations_is_unmeasured(self) -> None:
        cov = coverage([])
        self.assertEqual(cov["cases_measured"], 0)
        self.assertTrue(all(r["status"] == "unmeasured" for r in cov["rules"].values()))


class ObservationDecoding(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest, self.expected, _ = load("v1")
        self.clean = synthesize(self.expected)

    def test_clean_synthetic_capture_decodes_with_scratch_kept_separately(self) -> None:
        bundle = decode(self.clean, self.manifest, self.expected, "v1")
        self.assertTrue(bundle["valid"], bundle["problems"])
        self.assertEqual(bundle["records"], len(self.expected))
        by_id = {o["id"]: o for o in bundle["observations"]}
        dt = by_id["DT002"]
        self.assertEqual(sorted(dt["business"]["outputs"]), ["WMD", "WORD", "WVALID", "WYEAR"])
        self.assertIsNone(dt["business"]["r15"])
        self.assertEqual(dt["abi"]["r15_fixed"], 0)
        ca = by_id["CA001"]
        self.assertIn("WBEFORE", ca["scratch"])
        self.assertNotIn("WBEFORE", ca["business"]["outputs"])
        self.assertIn("OSTAT", ca["business"]["outputs"])
        self.assertIn("WNUM", ca["unasserted"])
        self.assertEqual(by_id["PK001"]["business"]["outputs"], {})
        self.assertEqual(by_id["PK001"]["business"]["r15"], 0)

    def test_partial_unknown_duplicate_reordered_captures_are_rejected(self) -> None:
        n = CAPTURE_LRECL
        variants = {
            "partial record": self.clean[:-100],
            "missing record": self.clean[n:],
            "extra record": self.clean + self.clean[:n],
            "duplicate record": self.clean[:n] + self.clean[:n] + self.clean[2 * n:],
            "reordered records": self.clean[n:2 * n] + self.clean[:n] + self.clean[2 * n:],
            "empty capture": b"",
        }
        raw = bytearray(self.clean)
        raw[CAPTURE["id"][0] + 2] = 0xF9
        variants["unknown case id"] = bytes(raw)
        raw = bytearray(self.clean)
        raw[CAPTURE["routine"][0]] = 0xE7  # 'X': INSPACK -> XNSPACK
        variants["unknown routine"] = bytes(raw)
        for name, capture in variants.items():
            bundle = decode(capture, self.manifest, self.expected, "v1")
            self.assertFalse(bundle["valid"], name)
            self.assertTrue(bundle["problems"], name)
            self.assertEqual(bundle["observations"], [], name)


@unittest.skipUnless((EVIDENCE / "as370-iewl" / "run.json").exists(), "committed evidence missing")
class CoverageFailsClosed(unittest.TestCase):
    def test_committed_evidence_validates_and_covers(self) -> None:
        manifest, expected, _ = load("v1")
        for backend in ("ifox-iewl", "as370-iewl", "as370-ld370"):
            summary, problems = validate_run(EVIDENCE / backend, manifest, expected)
            self.assertEqual(problems, [], backend)
            self.assertEqual(summary["backend"], backend)
        cov = coverage([EVIDENCE / "as370-iewl"])
        self.assertTrue(cov["valid"])
        self.assertEqual(cov["cases_measured"], len(expected))
        self.assertTrue(all(r["status"] == "PASS" for r in cov["rules"].values()))

    def test_every_evidence_mutation_yields_no_pass(self) -> None:
        results = coverage_controls("v1", EVIDENCE)
        self.assertGreaterEqual(len(results), 35)
        for r in results:
            self.assertTrue(r["ok"], f'{r["control"]}: {r["error"]}')
        names = " ".join(r["control"] for r in results)
        for word in ("global '*' finding", "step RC", "capture_sha256", "reordered", "duplicated",
                     "unknown id", "passed false"):
            self.assertIn(word, names)

    def test_mixed_valid_and_invalid_evidence_fails_rules(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "as370-iewl"
            shutil.copytree(EVIDENCE / "as370-iewl", bad)
            shutil.copy2(EVIDENCE / "capture.bin", bad / "capture.bin")
            report = json.loads((bad / "comparison.json").read_text())
            report["findings"].append({"class": "structure", "routine": "*", "case": "*",
                                       "field": "extra record", "expected": 184, "observed": 185,
                                       "offset": None, "expected_raw": None, "observed_raw": None})
            (bad / "comparison.json").write_text(json.dumps(report))
            cov = coverage([EVIDENCE / "as370-iewl", bad])
            self.assertFalse(cov["valid"])
            statuses = {r["status"] for r in cov["rules"].values()}
            self.assertEqual(statuses, {"FAIL"})


if __name__ == "__main__":
    unittest.main()
