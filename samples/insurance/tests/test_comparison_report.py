"""Exhaustive diagnostics must not weaken binary acceptance."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

from codec import packed, state  # noqa: E402
from compare import compare, differences, sha  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


class ComparisonReportTests(unittest.TestCase):
    def test_multiple_fields_records_and_independent_decode_errors(self) -> None:
        raw = state("00000001", 20240101, 1000, 100) + state("00000002", 20240101, 2000, 200)
        bad = bytearray(raw)
        for index in (0, 1):
            bad[index * 128 + 20:index * 128 + 27] = packed(9000)
            bad[index * 128 + 27:index * 128 + 34] = packed(8000)
        bad[34] = 0xFF
        report = differences(raw, bytes(bad), "state", {1: "CASE1", 2: "CASE2"})
        self.assertEqual([(r["record"], r["field"]) for r in report],
                         [(1, "face"), (1, "cash"), (1, "loan"), (2, "face"), (2, "cash")])
        self.assertEqual(report[0]["expected"]["value"], 1000)
        self.assertEqual(report[0]["observed"]["value"], 9000)
        self.assertEqual(report[0]["case_id"], "CASE1")
        self.assertIsNotNone(report[2]["observed"]["decode_error"])
        with self.assertRaises(ValueError) as caught:
            compare(raw, bytes(bad), "state")
        self.assertEqual(len(json.loads(str(caught.exception))), 5)

    def test_structural_diagnostics_retain_partial_and_absent_records(self) -> None:
        one = state("00000001", 20240101, 1000, 100)
        two = state("00000002", 20240101, 2000, 200)
        for observed, required in ((one, "missing_record"),
                                   (one + two + one, "extra_record"),
                                   ((one + two)[:-1], "truncated_record")):
            report = differences(one + two, observed, "state")
            self.assertIn("record_count", [r["type"] for r in report])
            self.assertIn(required, [r["type"] for r in report])
        self.assertEqual([r["record"] for r in differences(one + two, two + one, "state")
                          if r.get("field") == "id"], [1, 2])
        self.assertEqual(sum(r["type"] == "reordered_record"
                             for r in differences(one + two, two + one, "state")), 2)
        self.assertIn("duplicate_record",
                      [r["type"] for r in differences(one + two, one + two + one, "state")])

    def test_cli_aggregates_results_and_master_and_keeps_receipt_failure(self) -> None:
        golden = ROOT / "golden/v1"
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            master = bytearray((golden / "anchors.expected.pol.bin").read_bytes())
            result = bytearray((golden / "anchors.expected.res.bin").read_bytes())
            for index in (0, 1):
                master[index * 128 + 20:index * 128 + 27] = packed(1)
                master[index * 128 + 27:index * 128 + 34] = packed(2)
                result[index * 96 + 34:index * 96 + 41] = packed(3)
                result[index * 96 + 62:index * 96 + 69] = packed(4)
            (output / "pol").write_bytes(master)
            (output / "res").write_bytes(result)
            (output / "build").write_text("{}")
            (output / "guest").write_text("{}")
            receipt = {"schema": "insurance-run-v1", "job_id": "HOST-NEGATIVE-CONTROL",
                       "outcome": "completed", "timed_out": False, "abend": None,
                       "step_rc": {"RUN": 0},
                       "polin_sha256": sha((golden / "anchors.polin.bin").read_bytes()),
                       "txnin_sha256": sha((golden / "anchors.txns.bin").read_bytes()),
                       "polout_sha256": sha(master), "resout_sha256": sha(result),
                       "build_manifest_sha256": sha(b"{}"), "guest_manifest_sha256": sha(b"{}"),
                       "rates_sha256": sha((golden / "rates.json").read_bytes())}
            command = [sys.executable, str(ROOT / "tools/compare.py"), "--golden", str(golden),
                       "--stage", "anchors", "--polout", str(output / "pol"),
                       "--resout", str(output / "res"), "--receipt", str(output / "receipt"),
                       "--build-manifest", str(output / "build"),
                       "--guest-manifest", str(output / "guest"), "--report", str(output / "report")]
            for rc in (0, 12):
                receipt["step_rc"] = {"RUN": rc}
                (output / "receipt").write_text(json.dumps(receipt))
                process = subprocess.run(command, capture_output=True, text=True, check=False)
                self.assertNotEqual(process.returncode, 0)
                report = json.loads((output / "report").read_text())
                self.assertFalse(report["passed"])
                self.assertEqual(len(report["mismatches"]), 8)
                self.assertEqual({r["kind"] for r in report["mismatches"]}, {"state", "result"})
                self.assertEqual(len(report["errors"]), int(rc != 0))


if __name__ == "__main__":
    unittest.main()
