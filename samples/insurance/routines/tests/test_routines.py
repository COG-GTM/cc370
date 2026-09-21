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
import sys
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "harness"))

from cases import all_cases  # noqa: E402
from compare import compare, load  # noqa: E402
from contract import RULES, contract, coverage  # noqa: E402
from controls import corruptions, synthesize  # noqa: E402
from fixtures import (SCHEMA, SCRATCH, UNASSERTED, case_record, classes,  # noqa: E402
                      expectations)
from layout import (CAPTURE, CAPTURE_LRECL, CASE, CASE_LRECL, INSURANCE,  # noqa: E402
                    LIBRARY, MAX_AMOUNT, ROUTINES, WORK, WORKLEN, binary, packed, symbols, text)
from model import Work, expect, half_up, inscalc  # noqa: E402

FIXTURES = ROUTINES / "fixtures" / "v1"
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
        for routine in LIBRARY:
            table = classes(routine)
            self.assertEqual(len(table), WORKLEN)
            for name in SCRATCH.get(routine, ()) + UNASSERTED.get(routine, ()):
                self.assertIn(name, {f.name for f in WORK})


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
                     "routines/harness/model.py"):
            self.assertIn(name, manifest["inputs"])

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
            self.assertEqual(len(e["byte_classes"]["stable"]) + len(e["byte_classes"]["scratch"])
                             + len(e["byte_classes"]["unasserted"]), len(WORK))

    def test_coverage_without_observations_is_unmeasured(self) -> None:
        cov = coverage([])
        self.assertEqual(cov["cases_measured"], 0)
        self.assertTrue(all(r["status"] == "unmeasured" for r in cov["rules"].values()))


if __name__ == "__main__":
    unittest.main()
